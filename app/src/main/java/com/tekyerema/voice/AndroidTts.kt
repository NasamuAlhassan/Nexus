package com.tekyerema.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Native TTS via Android's system engine (Google TTS is present on the test device). Provides a
 * real Ghanaian-English-*adjacent* neutral English voice (§22 documents that no truly Ghanaian
 * English voice exists — this is the honest fallback, swappable behind [TtsProvider]).
 *
 * Twi (§20-21): the system engine has no Twi voice, and the permissive `nano-twi` (MIT) is not yet
 * packaged on-device; `mms-tts-aka` is CC-BY-NC and must NOT ship. So [speak] for Twi reports
 * `false` (spoke=false) rather than pretend an English voice is Twi — the controller surfaces that
 * honestly instead of mis-speaking.
 */
class AndroidTts(context: Context) : TtsProvider {
    override val name = "android-system-tts (English; Twi=gap)"

    companion object {
        /**
         * Ghana's words spelled so that THIS voice (Google's Nigerian English, en-ng-x-tfn — the engine cannot be given
         * sounds, only letters) says them the way they are said here. Chosen BY EAR by the owner on the A20e, 2 October
         * 2026, from numbered options played in the voice itself, after measuring his own recordings and radio clips:
         *  - cedis is short, "SI-dis" (which is why recognisers write "cities") — "sidis" was among the ones he passed;
         *  - pesewas is two syllables: "the pesewas is pronounced like peswes" — of five spellings of that, "pesswess";
         *    every three-syllable form was rejected ("pessaywahs" was the old one);
         *  - the app's name is three beats, "tekyerrma" in his words — of six spellings of that, "Tetcherrma" (the old
         *    "techeremah" came out with a hard k and four syllables).
         * The singular forms follow the plurals and were not played to him.
         */
        private val RESPELL: List<Pair<Regex, String>> = listOf(
            // 13 October 2026, the owner on the installed build: "not 5-city but cedis… not cities" — "sidis" came out as
            // "cities" (a short i and a t-like d). "seedy"/"seedies" is the long "SEE-dee" every English voice already
            // knows how to say; `speech.tts.respell` {"cedis": "…"} overrides it by ear without a rebuild.
            Regex("(?i)\\bcedis\\b") to "seedies",
            Regex("(?i)\\bcedi\\b") to "seedy",
            Regex("(?i)\\bpesewas\\b") to "pesswess",
            Regex("(?i)\\bpesewa\\b") to "pessweh",
            Regex("(?i)\\btɛkyerɛma\\b|\\btekyerema\\b") to "Tetcherrma"
        )
        /** A line with figures in it — a number said digit by digit, an amount — before Verbalize turns them into words. */
        private val FIGURES = Regex("(?i)\\d\\s\\d\\s\\d|\\d{4,}|\\b\\d+(?:\\.\\d+)?\\s*(?:cedis?|pesewas?|ghs|ghc)\\b|(?:GH₵|GHS|GHC|₵)\\s?\\d")
    }

    private val ready = CompletableDeferred<Boolean>()
    private var loggedEngine = false
    /**
     * Judge feedback (2026-09-30): the default Samsung engine has ONE English voice and mangles our words. When
     * Google's engine is installed (it is, on the A20e) it is preferred (`speech.tts.engine`, "auto") — it carries
     * many more voices, and [bestVoice] then picks the closest English to ours: en-GH, else en-NG (the nearest West
     * African English Google ships), else what the phone had. "system" or a package name overrides.
     */
    private val preferred: String? = run {
        val want = try { com.tekyerema.core.config.AppConfig.string("speech.tts.engine", "auto") } catch (_: Throwable) { "auto" }
        when {
            want == "system" -> null
            want != "auto" && want.isNotBlank() -> want
            else -> "com.google.android.tts".takeIf {
                try { context.packageManager.getPackageInfo(it, 0); true } catch (_: Throwable) { false }
            }
        }
    }
    private val engine = if (preferred != null)
        TextToSpeech(context.applicationContext, { status -> ready.complete(status == TextToSpeech.SUCCESS) }, preferred)
    else TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }
    private fun logEngineOnce() {
        if (loggedEngine) return; loggedEngine = true
        try { Log.i("Tekyerema", "[TTS] system engine=${engine.defaultEngine} preferred=${preferred ?: "(default)"} voice=${engine.voice?.name}") } catch (_: Throwable) {}
    }

    /** The closest installed English voice to Ghana: en-GH, else en-NG, else en-GB — never a network-only voice.
     *  Cached once and re-applied on every speak (setLanguage resets the engine's voice). */
    private var voicePicked = false
    private var pickedFor: String? = null
    private var pickedVoice: android.speech.tts.Voice? = null
    private fun applyBestVoice() {
        if (!voicePicked || pickedFor != TtsAccent.value) {
            voicePicked = true
            pickedFor = TtsAccent.value
            pickedVoice = try {
                // Offline-capable English voices only; a voice whose pack isn't downloaded yet is still a candidate —
                // Google fetches the pack on first use (the A20e ships en-NG local voices undownloaded).
                val voices = engine.voices?.filter {
                    it.locale?.language.equals("en", true) && !it.isNetworkConnectionRequired
                }.orEmpty()
                val installed = voices.filterNot { it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                // Ghana first if a phone ever ships it; then the accent chosen in Settings › Voice, British by default —
                // the Nigerian voice is offered, not imposed (owner, 13 October 2026).
                TtsAccent.order().firstNotNullOfOrNull { c ->
                    fun best(pool: List<android.speech.tts.Voice>) =
                        pool.filter { (it.locale?.country ?: "").equals(c, true) }.maxByOrNull { it.quality }
                    best(installed) ?: best(voices)
                }?.also {
                    Log.i("Tekyerema", "[TTS] voice picked: ${it.name} (${it.locale}, q=${it.quality}" +
                        if (it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) ", downloading)" else ")")
                }
            } catch (_: Throwable) { null }
        }
        pickedVoice?.let { try { engine.voice = it } catch (_: Throwable) {} }
    }

    /**
     * Words the system voices mangle, respelled the way they are SAID in Ghana (judge feedback 2026-09-30: "cedis",
     * numbers, the app's own name). Speech only — the screen keeps real spellings; the lab's Ghanaian voices never
     * pass through here. `speech.tts.respell` (config) adds or overrides pairs.
     */
    private fun respell(text: String): String {
        var t = text
        for ((re, word) in RESPELL) t = re.replace(t, word)
        val extra = try { com.tekyerema.core.config.AppConfig.obj("speech.tts.respell") } catch (_: Throwable) { null }
        if (extra != null) for (k in extra.keys()) {
            val v = extra.optString(k)
            // "\\b", not "\b": in a Kotlin string "\b" is a BACKSPACE character, so no pair from the config ever matched
            // (found 2026-10-02 while fixing how "MTN" is said).
            if (v.isNotBlank()) t = Regex("(?i)\\b" + Regex.escape(k) + "\\b").replace(t, Regex.escapeReplacement(v))
        }
        return t
    }

    /**
     * DEBUG (§B.4): enumerate the device's TTS voices and give a DEFINITIVE yes/no on whether any
     * Ghanaian / African-accented English voice exists — so it's known before the demo, not a surprise.
     */
    suspend fun logVoices() {
        if (!ready.await()) { Log.w("Tekyerema", "[TTS-VOICES] engine init failed"); return }
        val voices = try { engine.voices?.toList() ?: emptyList() } catch (t: Throwable) { emptyList() }
        Log.i("Tekyerema", "[TTS-VOICES] engine=${engine.defaultEngine} total=${voices.size}")
        val english = voices.filter { it.locale?.language.equals("en", true) }
        english.forEach { v ->
            Log.i("Tekyerema", "[TTS-VOICES] en voice: ${v.name} locale=${v.locale} quality=${v.quality} network=${v.isNetworkConnectionRequired}")
        }
        val african = english.filter { v ->
            (v.locale?.country ?: "").uppercase() in setOf("GH", "NG", "KE", "ZA", "TZ", "UG")
        }
        val ghana = english.filter { (it.locale?.country ?: "").equals("GH", true) }
        Log.i("Tekyerema", "[TTS-VOICES] === VERDICT: Ghanaian(en-GH) voice=${ghana.isNotEmpty()}; " +
            "African-accented English voice=${african.isNotEmpty()} (${african.map { it.locale }.distinct()}); " +
            "en-GH language available=${runCatching { engine.isLanguageAvailable(Locale("en","GH")) >= TextToSpeech.LANG_AVAILABLE }.getOrDefault(false)}")
    }

    private fun supports(language: String): Boolean {
        if (language == Lang.TWI) return false        // no Twi voice; do not fake it
        val locale = Locale("en", "GH")               // prefer en-GH; fall back to en
        val r = try { engine.isLanguageAvailable(locale) } catch (t: Throwable) { TextToSpeech.LANG_NOT_SUPPORTED }
        val use = if (r >= TextToSpeech.LANG_AVAILABLE) locale else Locale.ENGLISH
        return try { engine.setLanguage(use) >= TextToSpeech.LANG_AVAILABLE } catch (t: Throwable) { false }
    }

    override suspend fun speak(text: String, language: String): Boolean {
        if (text.isBlank()) return false
        NowSpeaking.begin()
        try { return speakInner(text, language) } finally { NowSpeaking.end() }
    }

    private suspend fun speakInner(text: String, language: String): Boolean {
        if (!ready.await()) { Log.w("Tekyerema", "[TTS] engine init failed"); return false }
        logEngineOnce()
        if (!supports(language)) {
            Log.i("Tekyerema", "[TTS] no voice for '$language'; not speaking (honest gap)")
            return false
        }
        applyBestVoice()   // setLanguage (in supports) resets the voice — put the picked one back
        // Every line is verbalised HERE, at the engine's door: the confirmation question went to the engine as written
        // ("an MTN number" → "a mountain number", digits run together) because its path skipped Verbalize (owner, A20e,
        // 13 October 2026). Verbalize is idempotent, so lines already verbalised by their callers are unchanged.
        @Suppress("NAME_SHADOWING") val text = respell(Verbalize.apply(text))
        // B-15/K-07: our stream (accessibility when TalkBack drives the phone) + may-duck focus for the duration.
        try { engine.setAudioAttributes(focus.attributes(AudioFocus.Kind.SPEAK)) } catch (_: Throwable) {}
        // Live tuning knobs (owner, 2026-09-30 "let's manage the current one"): rate and pitch from config, so the
        // voice's feel is adjusted by set_config on the phone — no rebuild. Clamped so a typo can't make it absurd.
        // A line that carries a phone number or an amount is said a touch slower (`speech.tts.figuresRate`, 0.9): listeners
        // check digits best at a measured pace with the groups apart (R-A12 — Baumann 2001; nine-digit recall 55 → 69 % with
        // pauses). The user's own slow/normal/fast choice still scales everything.
        val figures = FIGURES.containsMatchIn(text)
        try {
            val base = com.tekyerema.core.config.AppConfig.double("speech.tts.rate", 1.0)
            val factor = if (figures) com.tekyerema.core.config.AppConfig.double("speech.tts.figuresRate", 0.9) else 1.0
            engine.setSpeechRate((base * factor).toFloat().coerceIn(0.5f, 1.5f))
        } catch (_: Throwable) {}
        try { engine.setPitch(com.tekyerema.core.config.AppConfig.double("speech.tts.pitch", 1.0).toFloat().coerceIn(0.7f, 1.3f)) } catch (_: Throwable) {}
        // Our OWN focus request, released only by us: a newer line used to acquire focus, then this line's finally
        // released it, and the newer line played with the music at full volume.
        val focusToken = focus.acquire(AudioFocus.Kind.SPEAK)
        // Natural reading (owner, A20e 2026-09-30): the reading's pause marks (SpokenText) become real silences between its
        // pieces — a breath after "From Ama, on WhatsApp", a longer one between messages — instead of one run-on line.
        // playSilentUtterance works on every engine; SSML breaks may be read aloud by some.
        val maxChars = com.tekyerema.core.config.AppConfig.int("speech.tts.chunkChars", 180)
        val pieces = ArrayList<Pair<String?, Long>>()             // text, or null + a silence in ms
        for (seg in SpokenText.segments(text, com.tekyerema.core.config.AppConfig.long("speech.tts.pauseMs", 450L),
                com.tekyerema.core.config.AppConfig.long("speech.tts.breakMs", 900L))) {
            SpeechChunks.split(seg.text, maxChars).forEach { pieces += it to 0L }
            if (seg.pauseAfterMs > 0) pieces += null to seg.pauseAfterMs
        }
        if (pieces.isEmpty()) return false
        pieces.filter { it.first == null }.takeIf { it.isNotEmpty() }?.let { s ->
            Log.i("Tekyerema", "[TTS] paced reading: ${pieces.size - s.size} spoken parts, pauses ${s.map { it.second }} ms")
        }
        val chunks = pieces
        val t0 = android.os.SystemClock.uptimeMillis()
        try {
            return suspendCancellableCoroutine { cont ->
                // B-06: queue every chunk now (the engine pipelines synthesis) and finish on the LAST one's onDone.
                val ids = chunks.indices.map { "tk-" + System.nanoTime() + "-" + it }
                val call = Utterances.Call(ids, onFirstAudio = { lastFirstAudioMs = android.os.SystemClock.uptimeMillis() - t0 }) { ok ->   // Q-01
                    if (cont.isActive) cont.resume(ok)
                }
                // QUEUE_FLUSH below drops whatever this engine was still saying. End those callers now — they used to stay
                // suspended forever once their listener was replaced (a progress line talked over by a confirmation never
                // returned, and its later cancellation stopped whatever was speaking by then).
                utterances.begin(call)
                engine.setOnUtteranceProgressListener(utterances.listener)
                // At night the voice comes down to ~65% (QuietHours) — softer, not different.
                val vol = QuietHours.factor()
                val params = if (vol < 1f) android.os.Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, vol) } else null
                for ((i, c) in chunks.withIndex()) {
                    val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    val rc = c.first?.let { engine.speak(it, mode, params, ids[i]) } ?: engine.playSilentUtterance(c.second, mode, ids[i])
                    if (rc != TextToSpeech.SUCCESS) { utterances.finish(call, false); break }
                }
                cont.invokeOnCancellation { if (utterances.isCurrent(call)) engine.stop(); utterances.finish(call, false) }
            }
        } finally {
            focus.release(focusToken)
        }
    }

    private val utterances = Utterances()

    /**
     * The same voice, rate and respelling as [speak], written to [out] as a WAV by the engine's own synthesizeToFile — for
     * evidence, and to dub a screen recording that had no audio track (the demo of 8 Nov 2026 was captured over ADB, video
     * only). One utterance, so the paced pauses between pieces are not reproduced. Debug hook `tts_render`.
     */
    suspend fun renderWav(text: String, language: String, out: java.io.File): Boolean {
        if (text.isBlank() || !ready.await() || !supports(language)) return false
        applyBestVoice()
        @Suppress("NAME_SHADOWING") val text = respell(Verbalize.apply(text))
        try {
            val base = com.tekyerema.core.config.AppConfig.double("speech.tts.rate", 1.0)
            val factor = if (FIGURES.containsMatchIn(text)) com.tekyerema.core.config.AppConfig.double("speech.tts.figuresRate", 0.9) else 1.0
            engine.setSpeechRate((base * factor).toFloat().coerceIn(0.5f, 1.5f))
            engine.setPitch(com.tekyerema.core.config.AppConfig.double("speech.tts.pitch", 1.0).toFloat().coerceIn(0.7f, 1.3f))
        } catch (_: Throwable) {}
        val id = "render-" + System.nanoTime()
        return suspendCancellableCoroutine { cont ->
            val call = Utterances.Call(listOf(id)) { ok -> if (cont.isActive) cont.resume(ok) }
            utterances.begin(call)
            engine.setOnUtteranceProgressListener(utterances.listener)
            val rc = try { engine.synthesizeToFile(text, android.os.Bundle(), out, id) } catch (_: Throwable) { TextToSpeech.ERROR }
            if (rc != TextToSpeech.SUCCESS) utterances.finish(call, false)
            cont.invokeOnCancellation { utterances.finish(call, false) }
        }
    }

    // A call rings → the reply stops — except the line that ANNOUNCES that call (phone/IncomingCall, demo step 1), which is
    // spoken while it rings: the ringtone's focus stopped it after half a second on the emulator (14 Oct 2026).
    private val focus = AudioFocus(context, onLost = { if (!com.tekyerema.phone.IncomingCall.isRinging) stop() })

    /** Q-01: time from speak() to the first audible chunk of the last utterance (ms), or -1. */
    @Volatile var lastFirstAudioMs: Long = -1; private set

    /** 1.0 = the engine's normal pace; the Voice settings page offers slow/normal/fast. */
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The user's rate — applied once the engine is up. Before it is, the engine refuses the call (the setting was lost at
     *  start-up) and on a slow start the call sat on the main thread while the engine bound (P3-02, 13 October 2026). */
    fun setRate(rate: Float) {
        val r = rate.coerceIn(0.5f, 2.0f)
        settingsScope.launch { if (ready.await()) try { engine.setSpeechRate(r) } catch (_: Throwable) {} }
    }

    /** B-13: 1.0 = the engine's normal pitch — applied the same way, once the engine is up. */
    fun setPitch(pitch: Float) {
        val p = pitch.coerceIn(0.5f, 2.0f)
        settingsScope.launch { if (ready.await()) try { engine.setPitch(p) } catch (_: Throwable) {} }
    }

    override fun stop() { try { engine.stop() } catch (_: Throwable) {} }
    override fun shutdown() { try { engine.stop(); engine.shutdown() } catch (_: Throwable) {} }
}

/**
 * Who is waiting on which utterance of ONE TextToSpeech instance. A single listener serves every caller: the engine
 * keeps only one listener, and each speak() used to install its own, so the caller it replaced was never told its
 * line was flushed and stayed suspended. Pure (no Android) → unit-tested.
 */
internal class Utterances {
    class Call(val ids: List<String>, val onFirstAudio: () -> Unit = {}, val onEnd: (Boolean) -> Unit)

    private val byId = java.util.concurrent.ConcurrentHashMap<String, Call>()
    @Volatile private var current: Call? = null

    /** [call] is about to QUEUE_FLUSH: every earlier caller's line is being dropped — end them (not spoken fully). */
    fun begin(call: Call) {
        byId.values.toSet().forEach { if (it !== call) finish(it, false) }
        call.ids.forEach { byId[it] = call }
        current = call
    }

    fun isCurrent(call: Call) = current === call

    /** Ends [call] once (later callbacks for its ids find nothing). */
    fun finish(call: Call, ok: Boolean) {
        var mine = false
        call.ids.forEach { if (byId.remove(it, call)) mine = true }
        if (current === call) current = null
        if (mine) call.onEnd(ok)
    }

    private fun owner(id: String?): Call? = id?.let { byId[it] }   // ConcurrentHashMap rejects a null key

    val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) { owner(utteranceId)?.let { c -> if (c.ids.first() == utteranceId) c.onFirstAudio() } }
        override fun onDone(utteranceId: String?) { owner(utteranceId)?.let { c -> if (c.ids.last() == utteranceId) finish(c, true) } }
        // Stopped, or flushed by a newer line: only the caller that owns this id ends.
        override fun onStop(utteranceId: String?, interrupted: Boolean) { owner(utteranceId)?.let { finish(it, false) } }
        @Deprecated("deprecated in API 21")
        override fun onError(utteranceId: String?) { owner(utteranceId)?.let { finish(it, false) } }
        override fun onError(utteranceId: String?, errorCode: Int) { owner(utteranceId)?.let { finish(it, false) } }
    }
}
