package com.tekyerema.voice

import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.tekyerema.agent.AgentEngine
import com.tekyerema.agent.AgentStatus
import com.tekyerema.history.HistoryStore
import com.tekyerema.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * One voice session for the whole process, mirroring [AgentEngine]: the Home screen and the
 * arrive-on-top [com.tekyerema.ui.VoiceActivity] both observe the same microphone, state machine
 * and TTS, so triggering Tɛkyerɛma from anywhere never races a second recorder.
 */
object VoiceEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settings: Settings
    private lateinit var systemTts: AndroidTts
    private lateinit var controller: VoiceController
    private var initialized = false
    /** voice/io: which voice layer is live (`speech.voiceLayer.profile`), for diagnostics. */
    @Volatile var voiceLayer: String = com.tekyerema.voice.io.VoiceLayer.BUILTIN_EN
        private set
    /** Kept for QA auditioning of the "thinking" filler clips over ADB (`--es play_fillers twi|en`). */
    @Volatile private var fillerPlayer: FillerPlayer? = null
    @Volatile private var sherpa: SherpaAsr? = null
    /** The app's own Ghanaian-English voice ([SherpaTts]) when this build carries one; null ⇒ the phone's engine only. */
    @Volatile private var ownVoice: SherpaTts? = null
    /** What says an English line outside a voice turn: the own voice first, then the phone's engine (never silent). */
    private lateinit var englishVoice: TtsProvider
    /** The voice the turns reply with (RoutingTts: English, or Twi with its fall-backs), for lines said outside a turn. */
    @Volatile private var replyVoice: TtsProvider? = null
    /** B-19: reload contact names / app labels in the background (no-op while fresh). */
    @Volatile private var vocabRefresh: () -> Unit = {}

    /** DEBUG QA: play every filler clip of a language in order so the owner can vet the audio. */
    fun auditionFillers(language: String) { fillerPlayer?.audition(language) }

    /** B-18: online ASR clients by config id, built by the language layer from `providers.speech.clients` + local.properties.
     *  Empty with the shipped config (every client disabled) ⇒ on-device only, exactly as before. */
    @Volatile private var asrRegistry: Map<String, AsrProvider> = emptyMap()

    /** The configured online ASR for [lang] while online, else null (⇒ on-device). Never at a PIN/secure screen: the
     *  Secure Input boundary keeps that audio on the phone. Providers resting after a quota/network failure are skipped. */
    fun onlineAsrFor(lang: String?): AsrProvider? = com.tekyerema.language.LanguageLayer.onlineAsr(lang, asrRegistry,
        cloudAllowed = com.tekyerema.core.NetworkMonitor.isOnline() && AgentEngine.state.value.status != AgentStatus.SECURE_INPUT)

    // ===== G-05 keyboard dictation (voice/Dictation.kt): the IME shares this process, so the mic key reuses the
    // SAME recogniser stack as a voice turn — no second model copy. Set only when the captured stack is built
    // (profiles ghana / gh_mixed); null under builtin_en, where Dictation honestly reports unavailable.
    @Volatile internal var dictationAsr: AsrProvider? = null
        private set
    @Volatile internal var dictationMic: Recorder? = null
        private set

    private val ENGLISH_COMMAND = Regex("(?i)^(?:send|buy|call|open|read|check|did|turn|what'?s|what|how|set|play|find|show|tell|go|text|message|remind|stop|who)\\b")

    /** Twi → English for the cloud planner (`providers.speech.mt.twiToEn`), set when the voice stack is built. */
    @Volatile private var twiToEnglish: com.tekyerema.language.TranslationProvider? = null
    /** The language the conversation is in right now (LanguageSession), set when the voice stack is built. */
    @Volatile private var conversationLanguage: () -> String = { Lang.GH_EN }

    /**
     * The request in English for the cloud planner when the user is speaking Twi, or null (English, no translator, offline,
     * a failure — then the planner gets the Twi with a hint). The owner's Twi test of 2 October 2026 went to the planner
     * untranslated — "somatensidisma ziroffvtziro…" came back as "who should I send the message to?" — since the Khaya
     * translator was removed on 27 September and nothing took its place.
     */
    suspend fun goalInEnglish(goal: String): String? {
        if (conversationLanguage() != Lang.TWI && !com.tekyerema.language.TwiText.hasAkanLetters(goal)) return null
        // A Twi request the phone already understood arrives as its English command (language/TwiCommands): not Twi.
        // Its quoted message words may be Twi on purpose ("…saying “Meba anɔpa”") — they are sent as said, never translated.
        val unquoted = goal.replace(Regex("“[^”]*”"), "")
        if (!com.tekyerema.language.TwiText.hasAkanLetters(unquoted) && ENGLISH_COMMAND.containsMatchIn(unquoted)) return null
        // Names, amounts and numbers go to the translator — and on to the planner — as TAGS ([P1], [A1], [N1]); the real
        // values come back in RoutingBrain (language/EntityTags; owner, 2 October 2026). A translation that loses or
        // doubles a tag isn't used: the planner then gets the tagged Twi and the facts only (also with no translator).
        val tagged = com.tekyerema.language.EntityTags.protect(Normalizer.forTranslation(goal), ::contactName)
        val mt = twiToEnglish
        // Bounded: a cold translator (the ZeroGPU Space asleep, then Khaya) cost 40 s of silence before the planner even
        // started (QA TR03 / TR04, A20e, 3 October 2026). Past `providers.speech.mt.goalWaitMs` the planner gets the Twi.
        val waitMs = com.tekyerema.core.config.AppConfig.long("providers.speech.mt.goalWaitMs", 8_000L)
        val t0 = System.currentTimeMillis()
        val out = if (mt == null) null
            else try { kotlinx.coroutines.withTimeoutOrNull(waitMs) { mt.translate(tagged.text, Lang.TWI, Lang.GH_EN) }
                ?.trim()?.takeIf { it.isNotBlank() && !it.equals(tagged.text.trim(), true) } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { null }
        if (out == null && mt != null && System.currentTimeMillis() - t0 >= waitMs)
            android.util.Log.i("Tekyerema", "[MT] the request's translation took longer than $waitMs ms — the planner gets the Twi")
        val english = out?.takeIf { tagged.isEmpty || com.tekyerema.language.EntityTags.intact(tagged.text, it, tagged.entities) }
        if (out != null && english == null) android.util.Log.w("Tekyerema", "[MT] the translation lost a name/amount tag — not used")
        com.tekyerema.language.TwiTagSession.set(com.tekyerema.language.TwiTagSession.Entry(goal, tagged.text, english, tagged.entities))
        return english
    }

    /** English → Twi with names as tags and amounts / numbers kept in English (ReplyLocalizer), set with the voice stack. */
    @Volatile private var englishToTwi: (suspend (String) -> String?)? = null

    /** The user is speaking Twi right now. */
    fun speaksTwi(): Boolean = conversationLanguage() == Lang.TWI

    @Volatile private var warmedAt = 0L
    /** The raw English→Twi translator (no tags), for the warm-up only. */
    @Volatile private var englishToTwiMt: com.tekyerema.language.TranslationProvider? = null
    private val warmCount = java.util.concurrent.atomic.AtomicInteger()

    /** Wake the translator (the owner's ZeroGPU Space sleeps when idle: its first call after a pause timed out at 32 s —
     *  QA, 3 October 2026) with one short line each way, in the background — when Twi mode starts or a Twi turn begins.
     *  Each warm-up line is new ("… 2", "… 3"): the fixed line was answered from the translator's own cache in 8 ms and
     *  never reached the Space, which then timed out on the real request (A20e, 4 October 2026). */
    fun warmTranslator() {
        val mt = twiToEnglish ?: return
        val now = System.currentTimeMillis()
        if (now - warmedAt < 10 * 60_000L) return
        warmedAt = now
        val n = warmCount.incrementAndGet()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val t0 = System.currentTimeMillis()
            val ok = try { mt.translate("Mepa wo kyɛw $n", Lang.TWI, Lang.GH_EN) != null } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Throwable) { false }
            android.util.Log.i("Tekyerema", "[MT] translator warm-up ${if (ok) "ok" else "failed"} in ${System.currentTimeMillis() - t0}ms")
            // …and the OTHER direction: since 3 October the Space answers English→Twi with a second model (v2) that loads
            // on its first use (~10 s) — unwarmed, the first Twi reply would miss the reply bound and be spoken in English.
            val t1 = System.currentTimeMillis()
            val ok2 = try { englishToTwiMt?.translate("Please wait a moment $n.", Lang.GH_EN, Lang.TWI) != null }
                catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Throwable) { false }
            android.util.Log.i("Tekyerema", "[MT] English→Twi warm-up ${if (ok2) "ok" else "failed"} in ${System.currentTimeMillis() - t1}ms")
        }
    }

    /**
     * The Twi line for an English [text] when the conversation is in Twi — a confirmation the pop-up shows and the voice
     * reads (owner, 2 October 2026: "even when switched to Twi, the confirmation pop-ups remain in English"). Amounts and
     * numbers come back marked for the English voice (EntityTags). Null: not Twi, no translator, or the translation
     * couldn't be trusted — then the English stands.
     */
    /** How long a question / confirmation waits for its Twi wording (the voice waits the same). */
    fun promptWaitMs(): Long = com.tekyerema.core.config.AppConfig.long("providers.speech.mt.promptWaitMs", 8_000L)

    suspend fun twiLine(text: String): String? =
        if (!speaksTwi()) null else try { englishToTwi?.invoke(text) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { null }

    /** Twi words that are never a contact's name, however a contact is saved ("Ma", "Sika"…). */
    private val NOT_A_NAME = setOf("ma", "me", "wo", "no", "na", "ne", "sɛ", "kɔ", "frɛ", "soma", "sika", "aka", "sɛn", "bɛn",
        "tɔ", "bue", "mu", "yɛ", "de", "fa", "nkra", "data", "airtime", "kredit", "cedi", "cedis", "message", "momo", "mepɛ")

    /** The saved contact a word or two-word phrase names (from the recogniser's contact vocabulary), or null. */
    private fun contactName(said: String): String? {
        val low = said.lowercase()
        if (low.length < 3 || low in NOT_A_NAME || low.split(" ").any { it in NOT_A_NAME }) return null
        val compact = PhoneticKey.compact(said)
        return SpeechVocabulary.current().entries.firstOrNull { it.kind == EntityCorrector.Kind.CONTACT && it.compact == compact }?.text
    }

    /** The keyboard's own dictation can produce text in this language right now: the on-device models are
     *  present, or an online recogniser for the language is configured and reachable. */
    fun dictationReady(twi: Boolean): Boolean = initialized && dictationAsr != null &&
        (sherpa?.ready == true || onlineAsrFor(if (twi) Lang.TWI else null) != null)

    /** Live microphone level 0..1 — drives the living voice visual while listening (either mic). */
    val level: StateFlow<Float> get() = _level
    private val _level = kotlinx.coroutines.flow.MutableStateFlow(0f)
    val state: StateFlow<VoiceUiState> get() = controller.state
    val isRunning: Boolean get() = controller.isRunning
    /** C-12: a task is working with the mic free — "Hey Tɛkyerɛma" then interjects (pause, status, a correction). */
    val canInterject: Boolean get() = initialized && controller.canInterject
    /** The task waits on the user and nothing is listening for the reply — a wake or a tap should answer it. */
    val canRespond: Boolean get() = initialized && controller.canRespond
    /** Only our own reply is in the way — opening Tɛkyerɛma stops it and listens (C-12). */
    val canPreempt: Boolean get() = initialized && controller.canPreempt

    fun init(app: Application) {
        if (initialized) return
        initialized = true
        settings = Settings(app)
        // P3-02 (13 October 2026): this initialiser took 16.6 s of a 24-s Application.onCreate on the emulator (the start-up
        // ANR that leaves the accessibility service "crashed"); each stage is timed so the heavy ones can be moved off here.
        val startT0 = android.os.SystemClock.uptimeMillis()
        var startLast = startT0
        val startMarks = StringBuilder()
        fun stage(name: String) { val now = android.os.SystemClock.uptimeMillis(); startMarks.append(name).append('=').append(now - startLast).append(' '); startLast = now }
        systemTts = AndroidTts(app); stage("systemTts")
        val isOnline = { com.tekyerema.core.NetworkMonitor.isOnline() }
        // The system voice is wrapped so Twi letters (ɛ/ɔ) are read as plain vowels instead of being spelled out;
        // English text passes through unchanged.
        val systemVoice = com.tekyerema.language.PlainLatinVoice(systemTts)
        // The app's own Ghanaian-English voice, on the phone (owner, 2026-10-02: the Nigerian system voice mangles our
        // words). Present only when the build bundles one (assets/models/ondevice/eng/tts/voice.json); it speaks first
        // and the phone's engine stays behind it, so a missing or failed model is never silence.
        val own = SherpaTts(app).takeIf { it.present }?.let { v -> ownVoice = v; v.warm(); com.tekyerema.language.PlainLatinVoice(v) }
        englishVoice = if (own != null) FallbackTts(listOf(own, systemVoice)) else systemVoice
        stage("ownVoice")
        android.util.Log.i("Tekyerema", "[STARTUP] on-device voice: ${ownVoice?.name ?: "none in this build"}")
        val levels = ArrayList<StateFlow<Float>>()
        val mics = ArrayList<MicCapture>()   // warmed (neural VAD loaded) on a background thread after start-up — P3-02
        // B-19: contact names + app labels for post-ASR name correction, loaded off the voice path (READ_CONTACTS only
        // if already granted). Refreshed again whenever a listen starts (cheap no-op inside its TTL). Both profiles.
        scope.launch(Dispatchers.IO) { try { SpeechVocabulary.refresh(app) } catch (_: Throwable) {} }
        vocabRefresh = { scope.launch(Dispatchers.IO) { try { SpeechVocabulary.refresh(app) } catch (_: Throwable) {} } }

        // ===== voice/io — THE voice-layer switch (the only reader of speech.voiceLayer.profile) =====
        // builtin_en (testing default): Android's SpeechRecognizer + the system TTS, English. SherpaAsr and the HCI Lab
        // language layer are not built. ghana: MicCapture + HybridAsr + RoutingTts, exactly as before the boundary.
        val plan = com.tekyerema.voice.io.VoiceLayer.plan(
            com.tekyerema.core.config.AppConfig.string(com.tekyerema.voice.io.VoiceLayer.CONFIG_KEY, com.tekyerema.voice.io.VoiceLayer.BUILTIN_EN))
        voiceLayer = plan.profile
        // Filled by the ghana factory below (captured-audio stack); stay as-is under builtin_en.
        var hybrid: AsrProvider = LocalAsrProvider()          // never used for listening under builtin_en (no audio to give it)
        var tts: TtsProvider = englishVoice
        var sessionLang: () -> String = { Lang.GH_EN }
        var onLang: (String) -> Unit = {}
        var forced: () -> String? = { plan.pinnedLanguage }
        val built = com.tekyerema.voice.io.VoiceLayer.build(plan, object : com.tekyerema.voice.io.VoiceLayer.Factory<SherpaAsr, com.tekyerema.language.LanguageLayer.Registries> {
            override fun onDeviceAsr() = SherpaAsr(app).also { sherpa = it; it.hotwords = { SpeechVocabulary.current().entries.map { e -> e.text } }; stage("sherpaAsr") }
            override fun languageLayer() = com.tekyerema.language.LanguageLayer.build(app).also { stage("languageLayer") }
            override fun systemInput(): com.tekyerema.voice.io.VoiceInput {
                val cfg = { com.tekyerema.voice.io.SystemSpeechConfig.fromConfig() }
                val main = android.os.Handler(android.os.Looper.getMainLooper())
                val focus = AudioFocus(app)
                return com.tekyerema.voice.io.SystemSpeechInput(
                    engine = com.tekyerema.voice.io.AndroidRecognizerEngine(app) { cfg().component },
                    config = cfg, isOnline = isOnline, main = Dispatchers.Main.immediate,
                    postToMain = { r -> if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) r() else main.post { r() } },
                    micHandOver = { WakeWordService.handOver() }, micReclaim = { WakeWordService.reclaim() },
                    acquireFocus = { focus.acquire(AudioFocus.Kind.LISTEN) },
                    releaseFocus = { t -> focus.release(t as? android.media.AudioFocusRequest) }
                ).also { s ->
                    levels.add(s.level)
                    // Startup facts, no content: which recognizer, and does it list en-GH? If it says no, use the fallback.
                    val c = cfg()
                    com.tekyerema.voice.io.AndroidRecognizerEngine.probe(app, c.languageTag, c.fallbackTag) { langs ->
                        if (c.languageTag.lowercase() !in langs && c.fallbackTag.lowercase() in langs) s.useFallbackTag = true
                    }
                }
            }
            override fun capturedInput(onDevice: SherpaAsr, layer: com.tekyerema.language.LanguageLayer.Registries): com.tekyerema.voice.io.VoiceInput {
                // §P0 — build the mics with app context so the Silero VAD model (assets/vad/silero_vad.onnx) loads.
                val mic = MicCapture(appContext = app, defaultStrategy = { VadStrategy.named(settings.vadStrategy) })   // B-03
                // Yes/no answers: a shorter silence window than a full request.
                val answerMic = MicCapture(appContext = app, noSpeechTimeoutMs = 6000)
                levels.add(mic.level); levels.add(answerMic.level); mics += mic; mics += answerMic
                // B-18: which voice fills each role comes from config (providers.speech.tts.*), always ending in the phone's
                // own engine so the assistant is never silent. The language layer builds the network clients (UG HCI Lab or
                // any REST API) from config + local.properties; with the shipped config there are none, so everything is the
                // system engine and a Twi reply is spoken from its text (no MT).
                asrRegistry = layer.asr
                val ttsRegistry = layer.tts + ("system" to systemVoice) + (own?.let { mapOf("ghvoice" to it) } ?: emptyMap())
                val englishIds = if (own != null) listOf("ghvoice", "system") else listOf("system")
                val enTw = com.tekyerema.language.LanguageLayer.translator(Lang.GH_EN, Lang.TWI, layer.mt, isOnline)
                val localizer = com.tekyerema.language.ReplyLocalizer(enTw)
                englishToTwiMt = enTw
                // …and the way in: a Twi request reaches the cloud planner in English too (with its Twi), as it did with Khaya.
                twiToEnglish = com.tekyerema.language.LanguageLayer.translator(Lang.TWI, Lang.GH_EN, layer.mt, isOnline)
                // A question or a confirmation must be understood: in Twi it waits longer for its Twi wording than a passing
                // reply does (one language per setting — owner, 5 October 2026; the A20e then read a confirmation in English
                // after 3.5 s of translator). `providers.speech.mt.promptWaitMs`.
                englishToTwi = { localizer.toTwi(it, promptWaitMs()) }
                tts = RoutingTts(
                    english = SpeechProviders.tts("english", ttsRegistry, systemVoice, englishIds),
                    twi = SpeechProviders.tts("twi", ttsRegistry, systemVoice),
                    enToTw = { localizer.toTwi(it) },  // null (speak as-is) unless an English→Twi translator is configured
                    ghEnglish = SpeechProviders.resolve(SpeechProviders.ttsIds("ghEnglish", emptyList()), ttsRegistry)
                        .takeIf { it.isNotEmpty() }?.let { if (it.size == 1) it[0] else FallbackTts(it) },
                    isOnline = isOnline
                )
                // B-05: reply in the language the user last spoke; the persisted habitual language only moves after a streak.
                val language = com.tekyerema.language.LanguageSession(
                    loadHabitual = { settings.sessionLanguage }, saveHabitual = { settings.sessionLanguage = it })
                sessionLang = { language.current() }
                conversationLanguage = { language.current() }
                onLang = { language.onTurn(it) }
                forced = { settings.forcedLanguage }
                // Preload the tiny on-device model off the critical path so the first voice request isn't slow (§20).
                // Offline only (2026-09-30): the offline English model is now 660 MB (fine-tuned Parakeet) — online, the phone's
                // recogniser does English, so it isn't held in RAM (~600 MB free on the A20e); it loads when the network goes.
                // …and the offline meaning matcher (agent/IntentMatcher, 23 MB), so the first offline request doesn't wait for it.
                if (!isOnline()) scope.launch(Dispatchers.Default) {
                    try { onDevice.warm(settings.sessionLanguage) } catch (_: Throwable) {}
                    com.tekyerema.agent.IntentMatcher.get(app)
                }
                com.tekyerema.core.NetworkMonitor.onWentOffline = {
                    scope.launch(Dispatchers.Default) {
                        try { onDevice.warm(settings.sessionLanguage) } catch (_: Throwable) {}
                        com.tekyerema.agent.IntentMatcher.get(app)
                    }
                }
                // B-19 speculative recognition: start the on-device decode during the end-of-turn silence (MicCapture.onPause).
                // Only when this turn would be recognised ON the phone anyway — never a second call to a quota'd online API
                // (a forced-Twi turn with the HCI Lab online skips it). `speech.asr.speculative` = false turns it off.
                mic.onPause = { wav ->
                    if (com.tekyerema.core.config.AppConfig.bool("speech.asr.speculative", true) && onDevice.ready &&
                        onlineAsrFor(settings.forcedLanguage) == null) {
                        val gen = onDevice.specGeneration          // taken now, on the mic thread: a drop from here on voids it
                        scope.launch(Dispatchers.Default) { try { onDevice.speculate(wav, settings.forcedLanguage, gen) } catch (_: Throwable) {} }
                    }
                }
                mic.onSpeculationStale = { onDevice.dropSpeculation() }
                // Not on the yes/no mic: a confirmation answer is short (little to gain) and "yes… no" must never reach the
                // confirmation as "yes".
                answerMic.onPause = null
                // Phase 8 HYBRID ASR (§12/§13): on-device whisper-TINY fine-tunes via sherpa-onnx are the OFFLINE path; an
                // online provider per language comes from config (providers.speech.asr.online.<lang>) — the UG HCI Lab ASR
                // client is built into asrRegistry by the language layer — and is used only while connected, degrading to
                // on-device on any failure. LanguagePipeline (LID/normalize/entities) runs locally on the raw text.
                hybrid = HybridAsr(
                    onDevice = onDevice,
                    onlineFor = { lang -> onlineAsrFor(lang) },
                    forceLanguage = { settings.forcedLanguage },
                    sessionLanguage = { language.current() }
                )
                // G-05: the keyboard's dictation (voice/Dictation.kt) — the same hybrid recogniser, through its own
                // dedicated mic (a dictation-length no-speech window; never shared with a turn's mics — Dictation
                // refuses while a turn runs). Its level joins the living visual like every other microphone.
                dictationAsr = hybrid
                dictationMic = MicCapture(appContext = app, noSpeechTimeoutMs = 8000).also { levels.add(it.level); mics += it }
                stage("capturedInput")
                return com.tekyerema.voice.io.CapturedAudioInput(mic, answerMic, hybrid, sessionLang, forced)
            }
            // gh_mixed (owner, 2026-09-29): English listens → the phone's recognizer; Twi listens → the captured path (UG HCI
            // Lab ASR, on-device Twi Whisper as fallback). The language is the user's setting, else the session language —
            // both set by capturedInput above. Replies already split the same way (RoutingTts: english=system, twi=hcilab).
            override fun mixedInput(english: com.tekyerema.voice.io.VoiceInput, twi: com.tekyerema.voice.io.VoiceInput) =
                com.tekyerema.voice.io.LanguageRoutedInput(english, twi, language = { forced() ?: sessionLang() }, isOnline = isOnline)
        })
        stage("layer+inputs")
        // Startup diagnostics: which ASR/TTS stack is wired, verifiable live.
        android.util.Log.i("Tekyerema", "[STARTUP] voice layer=${plan.profile} input=${built.input.id} | " +
            "on-device ASR=${built.onDevice?.let { "${it.name} ready=${it.ready}" } ?: "not built"} | " +
            "language layer: ${built.layer?.summary() ?: "not built"} | " +
            "cloud brain keys: groq=${settings.groqApiKeys.size} gemini=${if (settings.geminiApiKey.isNotBlank()) 1 else 0}")
        val cues = AndroidVoiceCues(app, soundsOn = { settings.sounds }, hapticsOn = { settings.haptics })
        val duplex = DuplexAudio(app)   // B-07
        // LOCAL FAST PATH: deterministic device ops (time/battery/volume/open-app/back…) before the LLM. Kept here so the
        // first-run welcome changes the speaking speed through the same H-08 handler.
        val fastRouter = com.tekyerema.agent.LocalFastRouter(app) { AgentEngine.currentController() }; stage("fastRouter")
        replyVoice = tts
        controller = VoiceController(
            asr = hybrid,
            tts = tts,
            sessionLanguage = sessionLang,
            onLanguageResolved = onLang,
            scope = scope,
            hasMicPermission = {
                ContextCompat.checkSelfPermission(app, android.Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            },
            cues = cues,
            forceLanguage = forced,
            speakAloud = { settings.speakAloud },
            input = built.input,
            // A visible "Tɛkyerɛma is asking…" card over any app while a follow-up question waits for an answer.
            showAsking = { q -> com.tekyerema.ui.OverlayConfirm.showQuestion(app, q) },
            hideAsking = { com.tekyerema.ui.OverlayConfirm.hide(app) },
            // "Thinking" fillers while the brain works — the reply voice (own TTS instance), or the progress tone in Twi.
            fillers = FillerPlayer(app, scope, cue = { cues.progress() }, voice = {
                (when (settings.speechRate) { 0 -> 0.8f; 2 -> 1.25f; else -> 1.0f }) to
                    (when (settings.speechPitch) { 0 -> 0.85f; 2 -> 1.15f; else -> 1.0f })
            }).also { fillerPlayer = it },
            fastRouter = fastRouter,
            // Fast-path ACTION confirmations speak on an OFFLINE voice so they're instant on any network: the own voice
            // when the build has one (one voice for every English line), else the system engine.
            systemVoice = if (own != null) englishVoice else systemTts,
            // B-07 barge-in: an echo-cancelled mic that ignores the first 700 ms of each reply and needs ~200 ms of
            // sustained speech, so our own voice doesn't interrupt itself. Flag + config switchable.
            bargeMic = MicCapture(appContext = app, toneGuardMs = 700, echoCancelled = true, onsetMs = 200).also { mics += it },
            // …and the reply itself on the voice-communication path while that mic listens, so the echo canceller has
            // our voice as its reference (DuplexAudio). Only when barge-in is on (below).
            duplexBegin = { duplex.begin() },
            duplexEnd = { duplex.end() },
            verbosity = { settings.verbosity },   // B-13
            // C-10: long tasks keep working (time on the user never counts); check-ins + notification cover the wait.
            agentTimeoutMs = com.tekyerema.core.config.AppConfig.long("agent.limits.maxTaskMs", 600_000L),
            healthNotice = { if (com.tekyerema.core.config.FeatureFlags.isEnabled("N-16", true)) com.tekyerema.core.Health.newNotice(app) else null },
            bargeInEnabled = {
                com.tekyerema.core.config.FeatureFlags.isEnabled("B-07") &&
                    com.tekyerema.core.config.AppConfig.bool("speech.bargeIn.enabled", true)
            },
            // First-run voice welcome (voice/Welcome.kt): answers go into Settings; the speed through H-08. A Twi voice
            // exists only on the "ghana" voice layer — the welcome says so honestly when Twi is chosen without one.
            welcome = SettingsWelcomeHost(settings, twiVoice = plan.pinnedLanguage == null) { v ->
                fastRouter.applyPreference(com.tekyerema.agent.FastIntent.Preference("rate", v)).spoken
            },
            // "You ask this every Friday — shall I remind you?" (agent/habits): read/info requests only, reminders only speak.
            habits = com.tekyerema.agent.habits.Habits(app)
        )
        // The living visual follows whichever microphone is listening (our two MicCaptures, or the phone's recognizer).
        stage("controller")
        if (levels.isNotEmpty()) scope.launch { combine(levels.toList()) { a -> a.maxOrNull() ?: 0f }.collect { _level.value = it } }
        HistoryStore.init(app); stage("history")
        // The reply panel follows the task: whenever it waits on the user (a confirmation, a question, the secure step)
        // a panel over any app shows what it's waiting for, with buttons that fit (PromptSurface).
        scope.launch(Dispatchers.Main) {
            AgentEngine.state.map { PromptSurface.of(it.status, PromptSurface.confirmationText(it.pendingConfirmation, it.pendingConfirmationTwi), PromptSurface.confirmationText(it.pendingQuestion, it.pendingQuestionTwi), it.confirmationSeq, twi = speaksTwi()) }
                .distinctUntilChanged()
                .collect { p -> if (p == null) com.tekyerema.ui.OverlayConfirm.hidePrompt(app) else com.tekyerema.ui.OverlayConfirm.showPrompt(app, p) }
        }
        // N-16: why the process last ended, current issues — two binder calls into ActivityManager, off the start-up path (P3-02).
        scope.launch(Dispatchers.IO) { try { com.tekyerema.core.Health.logStartup(app) } catch (_: Throwable) {} }
        applyVoiceSettings(); stage("settings")
        android.util.Log.i("Tekyerema", "[STARTUP] VoiceEngine.init ${android.os.SystemClock.uptimeMillis() - startT0} ms: $startMarks")
        // The neural VAD of every microphone loads here, off the start-up path, so the first listen still finds it ready.
        scope.launch(Dispatchers.Default) {
            kotlinx.coroutines.delay(1_500)
            val t = android.os.SystemClock.uptimeMillis()
            mics.forEach { m -> try { m.warm() } catch (_: Throwable) {} }
            android.util.Log.i("Tekyerema", "[STARTUP] VAD warm for ${mics.size} microphones in ${android.os.SystemClock.uptimeMillis() - t} ms")
        }
        scope.launch { settings.changes.collect { applyVoiceSettings() } }
        // History: one row per finished task, written on the transition into a terminal status.
        scope.launch {
            var wasActive = false
            AgentEngine.state.collect { st ->
                // SECURE_INPUT counts as active: a task paused at a PIN is still running (it used to lose its history row).
                val active = st.status == AgentStatus.THINKING || st.status == AgentStatus.ACTING ||
                    st.status == AgentStatus.WAITING_CONFIRMATION || st.status == AgentStatus.SECURE_INPUT ||
                    st.status == AgentStatus.ASKING || st.status == AgentStatus.PAUSED
                val terminal = st.status == AgentStatus.COMPLETED || st.status == AgentStatus.COMPLETED_UNVERIFIED ||
                    st.status == AgentStatus.CANNOT_DO || st.status == AgentStatus.FAILED || st.status == AgentStatus.STOPPED
                if (wasActive && terminal) st.goal?.let {
                    // §P1 — record the REAL outcome, not a boolean, so History states what happened honestly.
                    val outcome = when (st.status) {
                        AgentStatus.COMPLETED -> com.tekyerema.history.Outcome.COMPLETED
                        AgentStatus.COMPLETED_UNVERIFIED -> com.tekyerema.history.Outcome.UNVERIFIED
                        // WS-A: a task paused for the cloud (network / quota) ends its run CANNOT_DO but is kept, resumable.
                        AgentStatus.CANNOT_DO -> if (AgentEngine.isPausedResult(st.lastResult)) com.tekyerema.history.Outcome.PAUSED
                            else com.tekyerema.history.Outcome.CANNOT
                        AgentStatus.STOPPED -> com.tekyerema.history.Outcome.STOPPED
                        else -> com.tekyerema.history.Outcome.FAILED
                    }
                    HistoryStore.record(it, outcome)
                    // K-04: the outcome stays in the shade briefly (auto-clears) for a task finished while away.
                    st.log.lastOrNull { e -> e.kind == com.tekyerema.agent.LogEntry.Kind.RESULT }?.text?.let { r ->
                        VoiceSessionService.postResult(app, if (st.status == AgentStatus.COMPLETED_UNVERIFIED) "$r (not confirmed)" else r)
                    }
                }
                // §C.1 — pin the process with a foreground service WHILE a task is active, release it when the
                // session ends, so the mic/agent don't die if the screen changes mid-task.
                if (active) {
                    VoiceSessionService.start(app)
                    VoiceSessionService.update(app, notificationLine(st))   // K-04: live status
                } else if (terminal) VoiceSessionService.stop(app)
                wasActive = active
            }
        }
    }

    /** K-04: one plain line for the ongoing notification — never tool names or element ids. */
    private fun notificationLine(st: com.tekyerema.agent.AgentUiState): String = when (st.status) {
        AgentStatus.WAITING_CONFIRMATION -> "Waiting for your OK: " + (st.pendingConfirmation ?: "confirm the next step").take(120)
        AgentStatus.SECURE_INPUT -> "Secure step — finish it on the phone yourself, then say \"done\"."
        AgentStatus.ASKING -> "Waiting for your answer: " + (st.pendingQuestion ?: "").take(120)
        AgentStatus.PAUSED -> "Paused — say \"continue\" when you're ready."
        else -> "Working on your request."   // no step numbers — TalkBack reads this line too (owner, 2026-09-28)
    }

    /** N-08: under memory pressure, drop the on-device ASR models when no voice turn is using them (they reload on
     *  the next request, from the files dir). Called from TekyeremaApp.onTrimMemory. */
    fun onTrimMemory(level: Int) {
        if (!initialized) return
        val pressing = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW &&
            level != android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        if (pressing && !controller.isRunning) sherpa?.let { s -> scope.launch { try { s.releaseAll() } catch (_: Throwable) {} } }
        if (pressing && !controller.isRunning) ownVoice?.let { v -> scope.launch(Dispatchers.Default) { try { v.release() } catch (_: Throwable) {} } }
    }

    private fun applyVoiceSettings() {
        TtsAccent.value = settings.ttsAccent   // the English voice re-picks itself on the next line it speaks
        systemTts.setRate(when (settings.speechRate) { 0 -> 0.8f; 2 -> 1.25f; else -> 1.0f })
        ReadingPace.rate = when (settings.speechRate) { 0 -> 0.8f; 2 -> 1.25f; else -> 1.0f }   // readings sized in seconds
        ownVoice?.rate = ReadingPace.rate
        systemTts.setPitch(when (settings.speechPitch) { 0 -> 0.85f; 2 -> 1.15f; else -> 1.0f })   // B-13
        Welcome.userName = settings.preferredName   // spoken greetings use the name from the welcome
    }

    /**
     * First-run voice welcome (voice/Welcome.kt). Home calls it the first time it opens after the permissions page;
     * Settings › Replay the welcome calls it with [replay]. Marked done the moment it starts, so it never repeats.
     * Waits (not marked done) while replies aren't read aloud or the mic is off — a later Home opening tries again.
     */
    fun startWelcome(replay: Boolean = false): Boolean {
        if (!initialized || (!replay && settings.welcomeDone)) return false
        if (!com.tekyerema.core.config.FeatureFlags.isEnabled("K-10", true) ||
            !com.tekyerema.core.config.AppConfig.bool("onboarding.welcome.enabled", true)) return false
        return controller.startWelcome().also { if (it) settings.welcomeDone = true }
    }

    /** K-10: speak a line outside a voice turn (onboarding steps) — only when replies are read aloud and no screen
     *  reader is already reading the page (TalkBack users hear the screen itself). */
    fun announce(text: String) {
        if (!initialized || !settings.speakAloud || text.isBlank()) return
        scope.launch {
            val ok = try { englishVoice.speak(Verbalize.apply(text), Lang.GH_EN) } catch (_: Throwable) { false }
            android.util.Log.i("Tekyerema", "[ANNOUNCE] spoke=$ok (${text.length} chars)")   // no words in the log
        }
    }

    /**
     * The network's answer after the PIN (a balance), in the conversation's language: [twi] when the conversation is in
     * Twi and the answer could be put into one Twi sentence, else [english] through the reply voice (which decides what a
     * Twi conversation hears for an English line). [announce] always used the English voice, so a Twi user heard the
     * balance in English (A20e, 9 October 2026). Whatever is still being said is stopped first: once the network has
     * answered, the PIN line is stale (that day the Twi PIN line played AFTER the balance).
     */
    fun announceAnswer(english: String, twi: String?, urgent: Boolean = false) {
        if (!initialized || !settings.speakAloud || english.isBlank()) return
        hush()
        scope.launch {
            // "speak Twi" counts before any Twi has been said — in the demo the call comes first (rehearsal, 15 Oct 2026).
            val lang = settings.forcedLanguage ?: conversationLanguage()
            // Twi only while the Twi voice is known to work: a lab that is down spends its retries and then has the English
            // voice read Twi words — the English line, at once, instead. An [urgent] line (a ringing phone) can't wait for a
            // new lab line either (~10–15 s even when it is up): English unless `phone.announceCallerTwiLive` says otherwise.
            val inTwi = lang == Lang.TWI && twi != null && TwiVoiceStatus.shared.state == TwiVoiceStatus.State.OK &&
                (!urgent || com.tekyerema.core.config.AppConfig.bool("phone.announceCallerTwiLive", false))
            val text = if (inTwi) twi!! else english
            val ok = try { (replyVoice ?: englishVoice).speak(Verbalize.apply(text), if (inTwi) Lang.TWI else Lang.GH_EN) } catch (_: Throwable) { false }
            android.util.Log.i("Tekyerema", "[ANNOUNCE] spoke=$ok lang=$lang twiVoice=$inTwi (${text.length} chars)")   // no words in the log
        }
    }

    /** Tap on the living visual: start listening, or cancel whatever is in flight. */
    fun toggle() { vocabRefresh(); controller.toggle() }
    /** Talk: the reply to a waiting prompt, an interjection while a task runs (stop / pause / continue / status / a
     *  correction, C-12), or a new turn — cutting our own reply short if it is still being read (see NewRequest). */
    fun start() { vocabRefresh(); controller.start() }
    /** The circle tapped while Tɛkyerɛma talks: stop talking and listen. While a task works it stays the kill switch. */
    fun interrupt() = controller.interrupt()
    /** Stop whatever is being said right now — and nothing else (the PIN pad: the user is entering their PIN, in silence). */
    fun hush() {
        if (!initialized) return
        try { controller.hush() } catch (_: Throwable) {}
        try { englishVoice.stop() } catch (_: Throwable) {}
    }
    /** Reopen the microphone for the prompt the task is waiting on (the panel's Answer button) — cutting the prompt's
     *  own reading short when it is still being read. */
    fun respond() { if (initialized) controller.respond() }
    /** A read-back or a question is being read aloud right now. */
    val readingPrompt: Boolean get() = initialized && controller.readingPrompt
    /** The prompt panel's Type box opened: stop reading, close the answer microphone, keep the prompt waiting. */
    fun quietForTyping() { if (initialized) controller.quietForTyping() }
    /** Listen once more after a reply so the conversation can continue; silence just rests (no nag). */
    fun followUp() = controller.followUp()
    /** Volume-up twice (the physical "say that again"): the last reply, spoken again. */
    fun repeatLast() = controller.repeatLast()
    /** Any of our voices mid-sentence, turns and mini-turns alike — the panel never closes itself while true. */
    val isSpeaking: Boolean get() = NowSpeaking.active
    fun cancel() = controller.cancel()

    /** Typed command → the same agent entry point as an ASR transcript (see VoiceController.submitText). */
    fun submitText(text: String): Boolean = controller.submitText(text)

    /** DEBUG: [raw] as a recogniser's transcript (the full speech-robustness layer), not as typed text. */
    fun submitHeard(raw: String): Boolean = initialized && controller.submitHeard(raw)
    /** Typed command with what happened to it (a notice for the screen when it went to a running task, etc.). */
    fun submitTyped(text: String): VoiceController.TypedResult = controller.submitTyped(text)
    fun beginTyping() = controller.beginTyping()
    fun endTyping() = controller.endTyping()
}
