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
    private lateinit var mic: MicCapture
    /** Yes/no answers: a shorter silence window than a full request. */
    private lateinit var answerMic: MicCapture
    private var initialized = false
    /** Kept for QA auditioning of the "thinking" filler clips over ADB (`--es play_fillers twi|en`). */
    @Volatile private var fillerPlayer: FillerPlayer? = null
    @Volatile private var sherpa: SherpaAsr? = null

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

    fun init(app: Application) {
        if (initialized) return
        initialized = true
        settings = Settings(app)
        // §P0 — build the mics with app context so the Silero VAD model (assets/vad/silero_vad.onnx) loads.
        mic = MicCapture(appContext = app, defaultStrategy = { VadStrategy.named(settings.vadStrategy) })   // B-03
        answerMic = MicCapture(appContext = app, noSpeechTimeoutMs = 6000)
        systemTts = AndroidTts(app)
        // B-18: which voice fills each role comes from config (providers.speech.tts.*), always ending in the phone's
        // own engine so the assistant is never silent. The language layer builds the network clients (UG HCI Lab or
        // any REST API) from config + local.properties; with the shipped config there are none, so everything is the
        // system engine and a Twi reply is spoken from its text (no MT). The system voice is wrapped so Twi letters
        // (ɛ/ɔ) are read as plain vowels instead of being spelled out; English text passes through unchanged.
        val layer = com.tekyerema.language.LanguageLayer.build(app)
        asrRegistry = layer.asr
        val systemVoice = com.tekyerema.language.PlainLatinVoice(systemTts)
        val ttsRegistry = layer.tts + ("system" to systemVoice)
        val isOnline = { com.tekyerema.core.NetworkMonitor.isOnline() }
        val localizer = com.tekyerema.language.ReplyLocalizer(
            com.tekyerema.language.LanguageLayer.translator(Lang.GH_EN, Lang.TWI, layer.mt, isOnline))
        val tts = RoutingTts(
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
        val sherpa = SherpaAsr(app).also { this.sherpa = it }
        // Preload the tiny on-device model off the critical path so the first voice request isn't slow (§20).
        scope.launch(Dispatchers.Default) { try { sherpa.warm(settings.sessionLanguage) } catch (_: Throwable) {} }
        // Startup diagnostics: which ASR/TTS stack is wired, verifiable live.
        android.util.Log.i("Tekyerema", "[STARTUP] on-device ASR=${sherpa.name} ready=${sherpa.ready} | " +
            "language layer: ${layer.summary()} | " +
            "cloud brain keys: groq=${settings.groqApiKeys.size} gemini=${if (settings.geminiApiKey.isNotBlank()) 1 else 0}")
        val cues = AndroidVoiceCues(app, soundsOn = { settings.sounds }, hapticsOn = { settings.haptics })
        controller = VoiceController(
            // Phase 8 HYBRID ASR (§12/§13): on-device whisper-TINY fine-tunes (Twi = tiny-twi; Gh-EN = our
            // tekyerema-ghen-tiny) via sherpa-onnx are the OFFLINE path — Twi always stays on-device. For
            // Gh-EN, when a real online endpoint is configured (Settings.asrOnlineBaseUrl, PLACEHOLDER until
            // whisper-large-v3-turbo-tekyerema-eng is hosted) AND we're connected, the strong online model
            // is used, degrading to on-device on any failure. LanguagePipeline (LID/normalize/entities) runs
            // locally on the raw text for every path. Offline/no-endpoint ⇒ the network is never touched.
            asr = HybridAsr(
                onDevice = sherpa,
                // Language-aware online routing, network folded in (offline ⇒ null ⇒ on-device, no hang). B-18: the
                // provider per language comes from config (providers.speech.asr.online.<lang>); the UG HCI Lab ASR
                // client is built into asrRegistry by the language layer. None is enabled in the shipped config, so
                // English and Twi both transcribe ON-DEVICE via sherpa.
                onlineFor = { lang -> onlineAsrFor(lang) },
                forceLanguage = { settings.forcedLanguage },
                sessionLanguage = { language.current() }
            ),
            tts = tts,
            sessionLanguage = { language.current() },
            onLanguageResolved = { language.onTurn(it) },
            scope = scope,
            hasMicPermission = {
                ContextCompat.checkSelfPermission(app, android.Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            },
            mic = mic,
            cues = cues,
            forceLanguage = { settings.forcedLanguage },
            speakAloud = { settings.speakAloud },
            answerMic = answerMic,
            // A visible "Tɛkyerɛma is asking…" card over any app while a follow-up question waits for an answer.
            showAsking = { q -> com.tekyerema.ui.OverlayConfirm.showQuestion(app, q) },
            hideAsking = { com.tekyerema.ui.OverlayConfirm.hide(app) },
            // "Thinking" fillers while the brain works — the reply voice (own TTS instance), or the progress tone in Twi.
            fillers = FillerPlayer(app, scope, cue = { cues.progress() }, voice = {
                (when (settings.speechRate) { 0 -> 0.8f; 2 -> 1.25f; else -> 1.0f }) to
                    (when (settings.speechPitch) { 0 -> 0.85f; 2 -> 1.15f; else -> 1.0f })
            }).also { fillerPlayer = it },
            // LOCAL FAST PATH: deterministic device ops (time/battery/volume/open-app/back…) before the LLM.
            fastRouter = com.tekyerema.agent.LocalFastRouter(app) { AgentEngine.currentController() },
            // Fast-path ACTION confirmations speak on the offline system engine so they're instant on any network.
            systemVoice = systemTts,
            // B-07 barge-in: an echo-cancelled mic that ignores the first 700 ms of each reply and needs ~200 ms of
            // sustained speech, so our own voice doesn't interrupt itself. Flag + config switchable.
            bargeMic = MicCapture(appContext = app, toneGuardMs = 700, echoCancelled = true, onsetMs = 200),
            verbosity = { settings.verbosity },   // B-13
            // C-10: long tasks keep working (time on the user never counts); check-ins + notification cover the wait.
            agentTimeoutMs = com.tekyerema.core.config.AppConfig.long("agent.limits.maxTaskMs", 600_000L),
            healthNotice = { if (com.tekyerema.core.config.FeatureFlags.isEnabled("N-16", true)) com.tekyerema.core.Health.newNotice(app) else null },
            bargeInEnabled = {
                com.tekyerema.core.config.FeatureFlags.isEnabled("B-07") &&
                    com.tekyerema.core.config.AppConfig.bool("speech.bargeIn.enabled", true)
            }
        )
        scope.launch { combine(mic.level, answerMic.level) { a, b -> maxOf(a, b) }.collect { _level.value = it } }
        HistoryStore.init(app)
        // The reply panel follows the task: whenever it waits on the user (a confirmation, a question, the secure step)
        // a panel over any app shows what it's waiting for, with buttons that fit (PromptSurface).
        scope.launch(Dispatchers.Main) {
            AgentEngine.state.map { PromptSurface.of(it.status, it.pendingConfirmation, it.pendingQuestion) }
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
                        AgentStatus.CANNOT_DO -> com.tekyerema.history.Outcome.CANNOT
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
        systemTts.setPitch(when (settings.speechPitch) { 0 -> 0.85f; 2 -> 1.15f; else -> 1.0f })   // B-13
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
    fun toggle() = controller.toggle()
    /** C-12: starting a turn while a task runs is an interjection (stop / pause / continue / status / a correction). */
    fun start() = when {
        controller.canRespond -> controller.respond()   // the task is waiting on the user: this is the reply
        AgentEngine.isBusy -> controller.interject()
        else -> controller.start()
    }
    /** Reopen the microphone for the prompt the task is waiting on (the panel's Answer button). */
    fun respond() { if (initialized) controller.respond() }
    /** Listen once more after a reply so the conversation can continue; silence just rests (no nag). */
    fun followUp() = controller.followUp()
    fun cancel() = controller.cancel()

    /** Typed command → the same agent entry point as an ASR transcript (see VoiceController.submitText). */
    fun submitText(text: String): Boolean = controller.submitText(text)
    fun beginTyping() = controller.beginTyping()
    fun endTyping() = controller.endTyping()
}
