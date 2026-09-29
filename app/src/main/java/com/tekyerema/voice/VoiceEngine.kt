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
        systemTts = AndroidTts(app)
        val isOnline = { com.tekyerema.core.NetworkMonitor.isOnline() }
        // The system voice is wrapped so Twi letters (ɛ/ɔ) are read as plain vowels instead of being spelled out;
        // English text passes through unchanged.
        val systemVoice = com.tekyerema.language.PlainLatinVoice(systemTts)
        val levels = ArrayList<StateFlow<Float>>()
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
        var tts: TtsProvider = systemVoice
        var sessionLang: () -> String = { Lang.GH_EN }
        var onLang: (String) -> Unit = {}
        var forced: () -> String? = { plan.pinnedLanguage }
        val built = com.tekyerema.voice.io.VoiceLayer.build(plan, object : com.tekyerema.voice.io.VoiceLayer.Factory<SherpaAsr, com.tekyerema.language.LanguageLayer.Registries> {
            override fun onDeviceAsr() = SherpaAsr(app).also { sherpa = it }
            override fun languageLayer() = com.tekyerema.language.LanguageLayer.build(app)
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
                levels.add(mic.level); levels.add(answerMic.level)
                // B-18: which voice fills each role comes from config (providers.speech.tts.*), always ending in the phone's
                // own engine so the assistant is never silent. The language layer builds the network clients (UG HCI Lab or
                // any REST API) from config + local.properties; with the shipped config there are none, so everything is the
                // system engine and a Twi reply is spoken from its text (no MT).
                asrRegistry = layer.asr
                val ttsRegistry = layer.tts + ("system" to systemVoice)
                val localizer = com.tekyerema.language.ReplyLocalizer(
                    com.tekyerema.language.LanguageLayer.translator(Lang.GH_EN, Lang.TWI, layer.mt, isOnline))
                tts = RoutingTts(
                    english = SpeechProviders.tts("english", ttsRegistry, systemVoice),
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
                onLang = { language.onTurn(it) }
                forced = { settings.forcedLanguage }
                // Preload the tiny on-device model off the critical path so the first voice request isn't slow (§20).
                scope.launch(Dispatchers.Default) { try { onDevice.warm(settings.sessionLanguage) } catch (_: Throwable) {} }
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
                return com.tekyerema.voice.io.CapturedAudioInput(mic, answerMic, hybrid, sessionLang, forced)
            }
            // gh_mixed (owner, 2026-09-29): English listens → the phone's recognizer; Twi listens → the captured path (UG HCI
            // Lab ASR, on-device Twi Whisper as fallback). The language is the user's setting, else the session language —
            // both set by capturedInput above. Replies already split the same way (RoutingTts: english=system, twi=hcilab).
            override fun mixedInput(english: com.tekyerema.voice.io.VoiceInput, twi: com.tekyerema.voice.io.VoiceInput) =
                com.tekyerema.voice.io.LanguageRoutedInput(english, twi) { forced() ?: sessionLang() }
        })
        // Startup diagnostics: which ASR/TTS stack is wired, verifiable live.
        android.util.Log.i("Tekyerema", "[STARTUP] voice layer=${plan.profile} input=${built.input.id} | " +
            "on-device ASR=${built.onDevice?.let { "${it.name} ready=${it.ready}" } ?: "not built"} | " +
            "language layer: ${built.layer?.summary() ?: "not built"} | " +
            "cloud brain keys: groq=${settings.groqApiKeys.size} gemini=${if (settings.geminiApiKey.isNotBlank()) 1 else 0}")
        val cues = AndroidVoiceCues(app, soundsOn = { settings.sounds }, hapticsOn = { settings.haptics })
        val duplex = DuplexAudio(app)   // B-07
        // LOCAL FAST PATH: deterministic device ops (time/battery/volume/open-app/back…) before the LLM. Kept here so the
        // first-run welcome changes the speaking speed through the same H-08 handler.
        val fastRouter = com.tekyerema.agent.LocalFastRouter(app) { AgentEngine.currentController() }
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
            // Fast-path ACTION confirmations speak on the offline system engine so they're instant on any network.
            systemVoice = systemTts,
            // B-07 barge-in: an echo-cancelled mic that ignores the first 700 ms of each reply and needs ~200 ms of
            // sustained speech, so our own voice doesn't interrupt itself. Flag + config switchable.
            bargeMic = MicCapture(appContext = app, toneGuardMs = 700, echoCancelled = true, onsetMs = 200),
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
        if (levels.isNotEmpty()) scope.launch { combine(levels.toList()) { a -> a.maxOrNull() ?: 0f }.collect { _level.value = it } }
        HistoryStore.init(app)
        // The reply panel follows the task: whenever it waits on the user (a confirmation, a question, the secure step)
        // a panel over any app shows what it's waiting for, with buttons that fit (PromptSurface).
        scope.launch(Dispatchers.Main) {
            AgentEngine.state.map { PromptSurface.of(it.status, it.pendingConfirmation, it.pendingQuestion, it.confirmationSeq) }
                .distinctUntilChanged()
                .collect { p -> if (p == null) com.tekyerema.ui.OverlayConfirm.hidePrompt(app) else com.tekyerema.ui.OverlayConfirm.showPrompt(app, p) }
        }
        com.tekyerema.core.Health.logStartup(app)   // N-16: why the process last ended, current issues
        applyVoiceSettings()
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
    }

    private fun applyVoiceSettings() {
        systemTts.setRate(when (settings.speechRate) { 0 -> 0.8f; 2 -> 1.25f; else -> 1.0f })
        ReadingPace.rate = when (settings.speechRate) { 0 -> 0.8f; 2 -> 1.25f; else -> 1.0f }   // readings sized in seconds
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
            val ok = try { systemTts.speak(Verbalize.apply(text), Lang.GH_EN) } catch (_: Throwable) { false }
            android.util.Log.i("Tekyerema", "[ANNOUNCE] spoke=$ok (${text.length} chars)")   // no words in the log
        }
    }

    /** Tap on the living visual: start listening, or cancel whatever is in flight. */
    fun toggle() { vocabRefresh(); controller.toggle() }
    /** Talk: the reply to a waiting prompt, an interjection while a task runs (stop / pause / continue / status / a
     *  correction, C-12), or a new turn — cutting our own reply short if it is still being read (see NewRequest). */
    fun start() { vocabRefresh(); controller.start() }
    /** The circle tapped while Tɛkyerɛma talks: stop talking and listen. While a task works it stays the kill switch. */
    fun interrupt() = controller.interrupt()
    /** Reopen the microphone for the prompt the task is waiting on (the panel's Answer button). */
    fun respond() { if (initialized) controller.respond() }
    /** Listen once more after a reply so the conversation can continue; silence just rests (no nag). */
    fun followUp() = controller.followUp()
    fun cancel() = controller.cancel()

    /** Typed command → the same agent entry point as an ASR transcript (see VoiceController.submitText). */
    fun submitText(text: String): Boolean = controller.submitText(text)
    /** Typed command with what happened to it (a notice for the screen when it went to a running task, etc.). */
    fun submitTyped(text: String): VoiceController.TypedResult = controller.submitTyped(text)
    fun beginTyping() = controller.beginTyping()
    fun endTyping() = controller.endTyping()
}
