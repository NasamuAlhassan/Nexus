package com.tekyerema.voice.io

import com.tekyerema.core.config.AppConfig
import com.tekyerema.voice.EntityCorrector
import com.tekyerema.voice.Lang
import com.tekyerema.voice.LanguagePipeline
import com.tekyerema.voice.PhoneticKey
import com.tekyerema.voice.SpeechVocabulary
import com.tekyerema.voice.Transcript
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

// ───────────────────────────── the recognizer seam (Android lives in AndroidRecognizer.kt) ─────────────────────────────

/** One recognition request, platform-free. Mapped to RecognizerIntent extras by [AndroidRecognizerEngine]. */
data class RecognitionRequest(
    val languageTag: String,
    val preferOffline: Boolean,
    val maxResults: Int,
    val partials: Boolean,
    /** SPEECH_INPUT_* extras (ms) — only when `silenceExtras` is on; the field evidence says they are ignored. */
    val silenceMs: Triple<Long, Long, Long>? = null
)

/** A thin mirror of RecognitionListener for ONE session. */
interface RecognizerCallbacks {
    fun onReady()
    fun onBeginningOfSpeech()
    fun onRms(db: Float)
    fun onEndOfSpeech()
    fun onPartial(best: String?)
    fun onResults(hypotheses: List<String>, confidences: FloatArray?)
    fun onError(code: Int)
}

/** One SpeechRecognizer instance. Main thread only. */
interface RecognizerHandle {
    fun start(request: RecognitionRequest, callbacks: RecognizerCallbacks)
    fun cancel()
    fun destroy()
}

interface RecognizerEngine {
    /** Backend label for the transcript ("android-speech/<service package>"). */
    val name: String
    /** SpeechRecognizer.isRecognitionAvailable. */
    fun available(): Boolean
    /** createSpeechRecognizer (default service, or the configured component); null when the platform refuses. */
    fun create(): RecognizerHandle?
}

/** SpeechRecognizer error codes (the platform constants; 1–9 exist on API 30, 10–14 are API 31+). */
object RecognizerErrors {
    const val NETWORK_TIMEOUT = 1
    const val NETWORK = 2
    const val AUDIO = 3
    const val SERVER = 4
    const val CLIENT = 5
    const val SPEECH_TIMEOUT = 6
    const val NO_MATCH = 7
    const val RECOGNIZER_BUSY = 8
    const val INSUFFICIENT_PERMISSIONS = 9
    const val TOO_MANY_REQUESTS = 10
    const val SERVER_DISCONNECTED = 11
    const val LANGUAGE_NOT_SUPPORTED = 12
    const val LANGUAGE_UNAVAILABLE = 13
    /** Ours: createSpeechRecognizer gave nothing. */
    const val CREATE_FAILED = -1
}

/** `speech.voiceLayer.systemSpeech` (assets/config/speech.json), with the brief's defaults. */
data class SystemSpeechConfig(
    val languageTag: String = "en-GH",
    val fallbackTag: String = "en-US",
    /** "whenOffline" (default) | "always" | "never". A preference only: it may have no effect (javadoc). */
    val preferOffline: String = "whenOffline",
    val maxResults: Int = 3,
    val yesNoMaxResults: Int = 5,
    val partials: Boolean = true,
    /** "" = the system default recognizer; else a flattened ComponentName (only if the device test says so). */
    val component: String = "",
    /** "beforeStart" = our tone, then the recognizer starts; "none" = the recognizer's own tone only. */
    val ownCue: String = "beforeStart",
    val cueGuardMs: Long = 250,
    val retryOnBusyOrClient: Int = 1,
    val silenceExtras: Boolean = false,
    val completeSilenceMs: Long = 2_000,
    val possiblyCompleteSilenceMs: Long = 1_500,
    val minimumLengthMs: Long = 0,
    /** A new SpeechRecognizer per listen: the only sure way a stopped session's late callbacks can't reach the next
     *  one (field reports: ERROR_CLIENT / leaked results on quick restarts; destroy + recreate fixed it). */
    val freshPerListen: Boolean = true,
    /** How many more listens a patient purpose gets when the recognizer's own endpointer times out on silence. */
    val patientRelistens: Int = 1,
    /** n-best re-rank toward a contact/app name (the API-30 stand-in for EXTRA_BIASING_STRINGS). */
    val nbestRerank: Boolean = true
) {
    val playsOwnCue: Boolean get() = ownCue.equals("none", ignoreCase = true)

    companion object {
        fun fromConfig(): SystemSpeechConfig {
            val o = AppConfig.obj("speech.voiceLayer.systemSpeech") ?: return SystemSpeechConfig()
            val d = SystemSpeechConfig()
            fun s(k: String, def: String) = if (o.has(k) && !o.isNull(k)) o.optString(k, def) else def
            return SystemSpeechConfig(
                languageTag = s("languageTag", d.languageTag).ifBlank { d.languageTag },
                fallbackTag = s("fallbackTag", d.fallbackTag),
                preferOffline = s("preferOffline", d.preferOffline),
                maxResults = o.optInt("maxResults", d.maxResults).coerceIn(1, 10),
                yesNoMaxResults = o.optInt("yesNoMaxResults", d.yesNoMaxResults).coerceIn(1, 10),
                partials = o.optBoolean("partials", d.partials),
                component = s("component", d.component),
                ownCue = s("ownCue", d.ownCue),
                cueGuardMs = o.optLong("cueGuardMs", d.cueGuardMs).coerceIn(0, 1_000),
                retryOnBusyOrClient = o.optInt("retryOnBusyOrClient", d.retryOnBusyOrClient).coerceIn(0, 3),
                silenceExtras = o.optBoolean("silenceExtras", d.silenceExtras),
                completeSilenceMs = o.optLong("completeSilenceMs", d.completeSilenceMs),
                possiblyCompleteSilenceMs = o.optLong("possiblyCompleteSilenceMs", d.possiblyCompleteSilenceMs),
                minimumLengthMs = o.optLong("minimumLengthMs", d.minimumLengthMs),
                freshPerListen = o.optBoolean("freshPerListen", d.freshPerListen),
                patientRelistens = o.optInt("patientRelistens", d.patientRelistens).coerceIn(0, 3),
                nbestRerank = o.optBoolean("nbestRerank", d.nbestRerank)
            )
        }
    }
}

// ───────────────────────────── SystemSpeechInput ─────────────────────────────

/**
 * Profile `builtin_en` — Android's built-in SpeechRecognizer behind [VoiceInput] (§A.4 of the brief):
 *  1. every recognizer call on the main thread ([main]); a listen is a cancellable suspension whose cancellation
 *     cancels the recognizer;
 *  2. a session guard: callbacks after a session's first terminal event, or from an older session, are dropped;
 *  3. an explicit microphone hand-over from the wake word before the recognizer starts, re-arm after it ends;
 *  4. our listening tone BEFORE startListening (we never see the audio, so the MicCapture tone-guard trick is out);
 *  5. extras per purpose (free form, en-GH → en-US, partials for the UI only, n-best 3 / 5 for yes/no, PREFER_OFFLINE
 *     only when offline — it is a preference, never a guarantee);
 *  6. the best hypothesis goes through [LanguagePipeline.process] (normalization + contact/app name correction);
 *  7. a cheap n-best re-rank toward a name the phone knows;
 *  9. levels for the visual; 10. transient-exclusive audio focus while listening (music pauses);
 *  11. a sensitive spec — or ANY listen while the secure step runs — returns NOT_ALLOWED_HERE without opening the
 *      recognizer: the audio could leave the phone.
 * Error codes map to [Heard] per [mapError]; CLIENT/BUSY retry once on a fresh recognizer, a network error retries once
 * with PREFER_OFFLINE — but only while the user hasn't spoken yet (a retry after speech would silently drop their words).
 * Nothing the user said reaches logcat unless content logging is allowed (release builds: never).
 */
class SystemSpeechInput(
    private val engine: RecognizerEngine,
    private val config: () -> SystemSpeechConfig = { SystemSpeechConfig.fromConfig() },
    private val isOnline: () -> Boolean = { true },
    /** The main thread (Dispatchers.Main.immediate in the app; the test dispatcher in tests). */
    private val main: CoroutineContext,
    /** Run on the main thread from any thread (cancel() only). */
    private val postToMain: (() -> Unit) -> Unit = { it() },
    /** WakeWordService.handOver / reclaim: the wake word's AudioRecord is closed before the recognizer opens its own. */
    private val micHandOver: suspend () -> Unit = {},
    private val micReclaim: () -> Unit = {},
    /** Transient-exclusive audio focus while listening (a token, released after). */
    private val acquireFocus: () -> Any? = { null },
    private val releaseFocus: (Any?) -> Unit = {},
    /** Privacy.secureStep — the secure step is running; the recognizer is then never started. */
    private val secureStep: () -> Boolean = { com.tekyerema.core.Privacy.secureStep },
    private val logContent: () -> Boolean = { com.tekyerema.core.Privacy.logContent() },
    private val vocabulary: () -> EntityCorrector.Vocab = { SpeechVocabulary.current() },
    private val process: (raw: String, backend: String) -> Transcript = { raw, backend ->
        LanguagePipeline.process(raw, Lang.GH_EN, Lang.GH_EN, backend = backend)
    },
    private val log: (String) -> Unit = { try { android.util.Log.i("Tekyerema", it) } catch (_: Throwable) {} }
) : VoiceInput {
    override val id = "system-speech"
    override val caps: InputCaps get() = InputCaps(
        mayLeavePhone = true, partials = config().partials, transcribesAudio = false, languages = setOf(Lang.GH_EN),
        secureListen = false, patientEndpointing = false, playsOwnCue = config().playsOwnCue
    )

    private val _level = MutableStateFlow(0f)
    /** Input level 0..1 while the recognizer listens (onRmsChanged), for the living visual. */
    val level: StateFlow<Float> get() = _level

    /** Set when the primary language tag was refused (LANGUAGE_* error, or the startup probe) — then the fallback. */
    @Volatile var useFallbackTag = false

    private var handle: RecognizerHandle? = null
    /** The callbacks of the session in flight; anything else is stale and dropped. Main thread. */
    private var current: RecognizerCallbacks? = null
    private var currentCont: CancellableContinuation<Outcome>? = null
    private var sessions = 0L

    internal sealed class Outcome {
        class Results(val hypotheses: List<String>, val confidences: FloatArray?, val speechBegan: Boolean) : Outcome()
        class Error(val code: Int, val speechBegan: Boolean) : Outcome()
        object Cancelled : Outcome()
    }

    override suspend fun listen(spec: ListenSpec, events: ListenEvents): Heard {
        // §A.5 / J-15: the phone's recognizer may stream audio to a server (PREFER_OFFLINE is only a hint on API 30), so
        // it is never started at the secure step — whatever the caller asked for.
        if (spec.sensitive || spec.purpose == ListenPurpose.SECURE_DONE || secureStep()) {
            log("[VOICE] system speech: not started — secure step (audio may leave the phone)")
            return Heard.Unavailable(Heard.Cause.NOT_ALLOWED_HERE, "the phone's recognizer may send audio off the phone")
        }
        val cfg = config()
        if (!engine.available()) return Heard.Unavailable(Heard.Cause.NO_RECOGNIZER, "no speech recognition service on this phone")
        var focus: Any? = null
        try {
            micHandOver()                 // inside the try: a cancel while waiting for the wake mic still reclaims it
            focus = acquireFocus()
            var clientRetries = cfg.retryOnBusyOrClient
            var offlineRetries = 1
            var audioRetries = 1
            var languageRetries = 1
            var relistens = if (spec.patient) cfg.patientRelistens else 0
            var preferOffline = when (cfg.preferOffline.lowercase()) {
                "always" -> true; "never" -> false; else -> !isOnline()
            }
            var cue = true
            while (true) {
                val request = RecognitionRequest(
                    languageTag = tag(cfg), preferOffline = preferOffline,
                    maxResults = if (spec.purpose == ListenPurpose.YES_NO) cfg.yesNoMaxResults else maxOf(cfg.maxResults, spec.maxAlternatives),
                    partials = cfg.partials && !spec.sensitive,
                    silenceMs = if (cfg.silenceExtras) Triple(cfg.completeSilenceMs, cfg.possiblyCompleteSilenceMs, cfg.minimumLengthMs) else null
                )
                val out = session(request, events, cfg, playCue = cue)
                cue = false
                when (out) {
                    is Outcome.Cancelled -> return Heard.Cancelled
                    is Outcome.Results -> return finish(out, spec, cfg)
                    is Outcome.Error -> {
                        val c = out.code
                        // Retries only while the user hasn't spoken: after speech a retry would drop their words unheard.
                        if (!out.speechBegan) when {
                            (c == RecognizerErrors.CLIENT || c == RecognizerErrors.RECOGNIZER_BUSY) && clientRetries > 0 -> {
                                clientRetries--; withContext(main) { drop() }; delay(RETRY_GAP_MS); continue
                            }
                            c in NETWORK_ERRORS && !preferOffline && offlineRetries > 0 -> {
                                offlineRetries--; preferOffline = true; continue
                            }
                            c == RecognizerErrors.AUDIO && audioRetries > 0 -> {
                                // The wake mic is already handed over (once per listen — reclaim pairs with it); another
                                // capture may still be letting go: give it a moment.
                                audioRetries--; delay(AUDIO_RETRY_MS); continue
                            }
                            c in LANGUAGE_ERRORS && !useFallbackTag && cfg.fallbackTag.isNotBlank() && languageRetries > 0 -> {
                                languageRetries--; useFallbackTag = true
                                log("[VOICE] system speech: ${cfg.languageTag} refused — using ${cfg.fallbackTag} from now on")
                                continue
                            }
                            (c == RecognizerErrors.SPEECH_TIMEOUT || c == RecognizerErrors.NO_MATCH) && relistens > 0 -> {
                                relistens--; cue = true; continue   // patient: listen once more (the endpointer can't wait longer)
                            }
                        }
                        log("[VOICE] system speech: error $c (speech=${out.speechBegan})")
                        return mapError(c, out.speechBegan)
                    }
                }
            }
        } finally {
            _level.value = 0f
            releaseFocus(focus)
            micReclaim()
        }
    }

    private fun tag(cfg: SystemSpeechConfig) = if (useFallbackTag && cfg.fallbackTag.isNotBlank()) cfg.fallbackTag else cfg.languageTag

    /** One recognizer session on the main thread: start → the first terminal callback (or cancellation). */
    private suspend fun session(request: RecognitionRequest, events: ListenEvents, cfg: SystemSpeechConfig, playCue: Boolean): Outcome =
        withContext(main) {
            // Our tone first, then the recognizer (it can't be kept out of the recognizer's audio any other way).
            if (playCue && !cfg.playsOwnCue) { events.onReady(); delay(cfg.cueGuardMs) }
            val h = handle ?: engine.create()?.also { handle = it }
                ?: return@withContext Outcome.Error(RecognizerErrors.CREATE_FAILED, false)
            val id = ++sessions
            var began = false
            var ended = false
            var ready = false
            val out = try {
                suspendCancellableCoroutine<Outcome> { cont ->
                    val cb = object : RecognizerCallbacks {
                        private fun live() = current === this && cont.isActive
                        private fun end(o: Outcome) { current = null; currentCont = null; cont.resume(o) }
                        override fun onReady() {
                            if (!live() || ready) return          // onReadyForSpeech has been seen to fire twice
                            ready = true
                            if (cfg.playsOwnCue && playCue) events.onReady()
                        }
                        override fun onBeginningOfSpeech() { if (live() && !began) { began = true; events.onSpeechStart() } }
                        override fun onRms(db: Float) { if (live()) levelOf(db).let { _level.value = it; events.onLevel(it) } }
                        override fun onEndOfSpeech() { if (live() && !ended) { ended = true; _level.value = 0f; events.onCaptured() } }
                        override fun onPartial(best: String?) {
                            if (live() && request.partials && !best.isNullOrBlank()) events.onPartial(best)   // UI only; never logged
                        }
                        override fun onResults(hypotheses: List<String>, confidences: FloatArray?) {
                            if (live()) end(Outcome.Results(hypotheses, confidences, began))
                        }
                        override fun onError(code: Int) { if (live()) end(Outcome.Error(code, began)) }
                    }
                    current = cb
                    currentCont = cont
                    try { h.start(request, cb) } catch (t: Throwable) {
                        log("[VOICE] system speech: start failed (${t.javaClass.simpleName})")
                        if (current === cb && cont.isActive) { current = null; currentCont = null; cont.resume(Outcome.Error(RecognizerErrors.CLIENT, false)) }
                    }
                }
            } catch (c: CancellationException) {
                // The turn was cancelled (tap, stop, a prompt taking the mic): stop the recognizer and drop what it hears.
                current = null; currentCont = null
                try { h.cancel() } catch (_: Throwable) {}
                if (cfg.freshPerListen) drop()
                throw c
            }
            if (id == sessions && cfg.freshPerListen) drop()
            out
        }

    /** cancel() + destroy() the recognizer; the next session creates a fresh one. Main thread. */
    private fun drop() {
        val h = handle ?: return
        handle = null
        try { h.cancel() } catch (_: Throwable) {}
        try { h.destroy() } catch (_: Throwable) {}
    }

    private fun finish(out: Outcome.Results, spec: ListenSpec, cfg: SystemSpeechConfig): Heard {
        val hyps = out.hypotheses.map { it.trim() }.filter { it.isNotEmpty() }
        if (hyps.isEmpty()) return if (out.speechBegan) Heard.TooShort else Heard.NoSpeech
        val all = hyps.mapIndexed { i, h -> Hypothesis(h, out.confidences?.getOrNull(i)?.takeIf { it >= 0f }) }
        val pick = if (cfg.nbestRerank && spec.purpose in NAME_PURPOSES) NBest.pick(all, vocabulary()) else 0
        val backend = "android-speech/${engine.name}"
        val t = process(hyps[pick], backend)
        // raw stays exactly as heard (the top hypothesis); only the canonical goal may come from an alternative.
        val tr = if (pick == 0) t else t.copy(raw = hyps[0], reason = t.reason + "+nbest")
        if (logContent()) log("[VOICE] system speech: '${tr.raw}' → '${tr.canonical}' n=${hyps.size} conf=${all[0].confidence} pick=$pick")
        else log("[VOICE] system speech: len=${tr.canonical.length} n=${hyps.size} conf=${all[0].confidence} pick=$pick")
        return Heard.Speech(tr, all)
    }

    override fun cancel() {
        postToMain {
            val cont = currentCont ?: return@postToMain
            current = null; currentCont = null
            try { handle?.cancel() } catch (_: Throwable) {}
            if (cont.isActive) cont.resume(Outcome.Cancelled)
        }
    }

    override fun release() { postToMain { current = null; currentCont = null; drop() } }

    companion object {
        private const val RETRY_GAP_MS = 150L
        private const val AUDIO_RETRY_MS = 300L
        private val NETWORK_ERRORS = setOf(RecognizerErrors.NETWORK_TIMEOUT, RecognizerErrors.NETWORK, RecognizerErrors.SERVER,
            RecognizerErrors.SERVER_DISCONNECTED)
        private val LANGUAGE_ERRORS = setOf(RecognizerErrors.LANGUAGE_NOT_SUPPORTED, RecognizerErrors.LANGUAGE_UNAVAILABLE)
        /** Purposes whose words can name a contact or an app. */
        private val NAME_PURPOSES = setOf(ListenPurpose.REQUEST, ListenPurpose.FOLLOW_UP, ListenPurpose.CONTINUATION,
            ListenPurpose.PATIENT, ListenPurpose.ANSWER)

        /** onRmsChanged dB (about -2 in silence to 10 when speaking) → 0..1 for the visual. */
        fun levelOf(db: Float): Float = ((db + 2f) / 12f).coerceIn(0f, 1f)

        /** A SpeechRecognizer error code (after any retries) → what the controller hears (§A.2 table). */
        fun mapError(code: Int, speechBegan: Boolean): Heard = when (code) {
            RecognizerErrors.NETWORK_TIMEOUT, RecognizerErrors.NETWORK, RecognizerErrors.SERVER_DISCONNECTED ->
                Heard.Unavailable(Heard.Cause.OFFLINE, "the phone's speech service couldn't be reached (error $code)")
            RecognizerErrors.AUDIO -> Heard.Unavailable(Heard.Cause.AUDIO, "the microphone isn't free (error $code)")
            RecognizerErrors.SERVER -> Heard.Unavailable(Heard.Cause.OTHER, "the speech service returned an error (error $code)")
            RecognizerErrors.CLIENT -> Heard.Unavailable(Heard.Cause.OTHER, "the speech service stopped (error $code)")
            RecognizerErrors.SPEECH_TIMEOUT -> Heard.NoSpeech
            RecognizerErrors.NO_MATCH -> if (speechBegan) Heard.TooShort else Heard.NoSpeech
            RecognizerErrors.RECOGNIZER_BUSY, RecognizerErrors.TOO_MANY_REQUESTS ->
                Heard.Unavailable(Heard.Cause.BUSY, "the speech service is busy (error $code)")
            RecognizerErrors.INSUFFICIENT_PERMISSIONS -> Heard.Unavailable(Heard.Cause.PERMISSION, "microphone permission (error $code)")
            RecognizerErrors.LANGUAGE_NOT_SUPPORTED, RecognizerErrors.LANGUAGE_UNAVAILABLE ->
                Heard.Unavailable(Heard.Cause.LANGUAGE, "the speech service doesn't have this language (error $code)")
            RecognizerErrors.CREATE_FAILED -> Heard.Unavailable(Heard.Cause.NO_RECOGNIZER, "the speech service couldn't be started")
            else -> Heard.Unavailable(Heard.Cause.OTHER, "speech error $code")
        }
    }
}

/**
 * §A.4.7 — the API-30 stand-in for EXTRA_BIASING_STRINGS: when the top hypothesis names nothing the phone knows but an
 * alternative (≤ 3rd) with the same first word does name a contact or app — and its confidence isn't under half the
 * top's (unknown = no objection) — that alternative becomes the canonical goal. Pure → unit-tested.
 */
object NBest {
    fun pick(hyps: List<Hypothesis>, vocab: EntityCorrector.Vocab, maxRank: Int = 3): Int {
        if (hyps.size < 2 || vocab.isEmpty) return 0
        if (namesKnown(hyps[0].text, vocab)) return 0
        val verb = firstWord(hyps[0].text)
        val top = hyps[0].confidence
        for (k in 1 until minOf(hyps.size, maxRank + 1)) {
            val h = hyps[k]
            if (firstWord(h.text) != verb) continue
            val c = h.confidence
            if (top != null && c != null && c < 0.5f * top) continue
            if (namesKnown(h.text, vocab)) return k
        }
        return 0
    }

    private fun firstWord(s: String) = s.trim().lowercase().substringBefore(' ')

    /** A word (or two neighbouring words) of [text] is exactly a contact or app name on this phone. */
    internal fun namesKnown(text: String, vocab: EntityCorrector.Vocab): Boolean {
        val words = text.split(Regex("\\s+")).map { PhoneticKey.compact(it) }.filter { it.isNotEmpty() }
        for (i in words.indices) {
            if (i > 0 && words[i].length >= 3 && words[i] in vocab.exactCompacts) return true   // not the verb itself
            if (i > 0 && i + 1 < words.size && (words[i] + words[i + 1]) in vocab.exactCompacts) return true
        }
        return false
    }
}
