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

    /** DEBUG QA: play every filler clip of a language in order so the owner can vet the audio. */
    fun auditionFillers(language: String) { fillerPlayer?.audition(language) }

    /** Live microphone level 0..1 — drives the living voice visual while listening (either mic). */
    val level: StateFlow<Float> get() = _level
    private val _level = kotlinx.coroutines.flow.MutableStateFlow(0f)
    val state: StateFlow<VoiceUiState> get() = controller.state
    val isRunning: Boolean get() = controller.isRunning

    fun init(app: Application) {
        if (initialized) return
        initialized = true
        settings = Settings(app)
        // §P0 — build the mics with app context so the Silero VAD model (assets/vad/silero_vad.onnx) loads.
        mic = MicCapture(appContext = app)
        answerMic = MicCapture(appContext = app, noSpeechTimeoutMs = 6000)
        systemTts = AndroidTts(app)
        // English → the phone's own engine (offline-capable); Twi → a REAL Twi voice via Khaya (Ghana NLP)
        // when a key is set, so Twi sessions are answered in Twi. If Khaya is unset/offline/fails,
        // RoutingTts falls back to the system engine (never silent, §28).
        // English→Twi localizer so a Twi session is answered in Twi (confirmations, errors, cloud results),
        // not just successful LocalBrain lines. App-authored Twi strings pass through untranslated.
        val khayaMt = KhayaTranslate(baseUrl = { settings.khayaBaseUrl }, apiKey = { settings.khayaApiKey })
        val khayaTts = KhayaTts(app, baseUrl = { settings.khayaBaseUrl }, apiKey = { settings.khayaApiKey })
        // §3.2 PRIMARY voice = Abena (Ghanaian-accented English akua_eng/kwabena_eng + Twi), Khaya as first
        // fallback, the system engine as the last resort. One instance handles both languages + caches fixed
        // strings to disk (§2.1). Reused for Twi and English.
        val abenaTts = AbenaTts(app, settings)
        val voiceChain = FallbackTts(listOf(abenaTts, khayaTts))   // Abena (primary) → Khaya (fallback) → (system)
        val tts = RoutingTts(
            english = systemTts,                                    // final fallback (neutral, offline-capable)
            twi = voiceChain,                                       // Twi: Abena → Khaya → (system)
            // §4.4 don't attempt MT offline; translate English→Twi so the Twi voice speaks Twi words.
            enToTw = { text -> if (settings.hasKhaya && com.tekyerema.core.NetworkMonitor.isOnline()) khayaMt.toTwi(text) else null },
            // English: Abena (Ghanaian-accented) → Khaya → system. Both give a Ghanaian accent.
            ghEnglish = voiceChain,
            isOnline = { (settings.hasAbena || settings.hasKhaya) && com.tekyerema.core.NetworkMonitor.isOnline() }
        )
        val sherpa = SherpaAsr(app)
        // Preload the tiny on-device model off the critical path so the first voice request isn't slow (§20).
        scope.launch(Dispatchers.Default) { try { sherpa.warm(settings.sessionLanguage) } catch (_: Throwable) {} }
        // §4.1 Abena's first call to a voice takes 10–15 s (model load), ~1 s after. Pre-warm the two demo
        // voices off the critical path and CACHE the greeting, so the demo doesn't open with silence and the
        // warm-up is spent only once. Cache-first + budget-gated, so it won't drain quota on every launch.
        if (settings.hasAbena) scope.launch(Dispatchers.Default) {
            try { abenaTts.speak("Akwaaba.", Lang.TWI); abenaTts.speak("Welcome.", Lang.GH_EN) } catch (_: Throwable) {}
        }
        // Startup diagnostics (§B.2/B.3): print exactly which ASR/TTS stack is wired so it's verifiable live.
        android.util.Log.i("Tekyerema", "[STARTUP] on-device ASR=${sherpa.name} ready=${sherpa.ready} | " +
            "ASR: Twi=${if (settings.hasKhaya) "Khaya" else "OFF"}→Abena, Gh-EN=${if (settings.hasOnlineAsr) settings.asrOnlineModel else "?"}→Abena (Khaya=Twi-only) | " +
            "TTS: ${if (settings.hasAbena) "Abena(${settings.abenaEnglishVoice}+abena_twi_high)" else ""}→${if (settings.hasKhaya) "Khaya" else ""}→system | " +
            "cloud brain keys: groq=${settings.groqApiKeys.size} gemini=${if (settings.geminiApiKey.isNotBlank()) 1 else 0}")
        controller = VoiceController(
            // Phase 8 HYBRID ASR (§12/§13): on-device whisper-TINY fine-tunes (Twi = tiny-twi; Gh-EN = our
            // tekyerema-ghen-tiny) via sherpa-onnx are the OFFLINE path — Twi always stays on-device. For
            // Gh-EN, when a real online endpoint is configured (Settings.asrOnlineBaseUrl, PLACEHOLDER until
            // whisper-large-v3-turbo-tekyerema-eng is hosted) AND we're connected, the strong online model
            // is used, degrading to on-device on any failure. LanguagePipeline (LID/normalize/entities) runs
            // locally on the raw text for every path. Offline/no-endpoint ⇒ the network is never touched.
            asr = HybridAsr(
                onDevice = sherpa,
                // Language-aware online routing, network folded in (offline ⇒ null ⇒ on-device, no hang):
                //  Twi   → Khaya (natural-Twi ASR) when a key is set;
                //  Gh-EN → the strong whisper endpoint when configured.
                onlineFor = { lang ->
                    if (!com.tekyerema.core.NetworkMonitor.isOnline()) null
                    else {
                        // KHAYA is the primary language layer where it can be (Twi ASR + all TTS); ABENA is the
                        // fallback. Khaya's ASR is Twi-ONLY, so English speech-to-text stays on Groq Whisper
                        // (fast) → Abena. Twi ASR → Khaya → Abena. AUTO keeps Abena first (only it transcribes
                        // mixed Twi+English). English audio must never be sent to the Twi-only Khaya ASR.
                        val abena = if (settings.hasAbena) AbenaAsr(app, settings) else null
                        val khaya = if (settings.hasKhaya) KhayaAsr(baseUrl = { settings.khayaBaseUrl }, apiKey = { settings.khayaApiKey }) else null
                        val groq = if (settings.hasOnlineAsr) GroqWhisperAsr(baseUrl = { settings.asrOnlineBaseUrl }, apiKey = { settings.asrOnlineKey }, model = settings.asrOnlineModel) else null
                        val effective = lang ?: if (settings.sessionLanguage == Lang.TWI) Lang.TWI else null
                        val chain = when (effective) {
                            Lang.TWI -> listOfNotNull(khaya, abena)          // Twi: Khaya (primary) → Abena
                            Lang.GH_EN -> listOfNotNull(groq, abena)         // Gh-English: Groq (fast; Khaya can't do En) → Abena
                            else -> if (abena != null) listOfNotNull(abena, khaya, groq) else emptyList()
                        }
                        when { chain.isEmpty() -> null; chain.size == 1 -> chain[0]; else -> FallbackAsr(chain) }
                    }
                },
                forceLanguage = { settings.forcedLanguage },
                sessionLanguage = { settings.sessionLanguage }
            ),
            tts = tts,
            sessionLanguage = { settings.sessionLanguage },
            onLanguageResolved = { settings.sessionLanguage = it },
            scope = scope,
            hasMicPermission = {
                ContextCompat.checkSelfPermission(app, android.Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            },
            mic = mic,
            cues = AndroidVoiceCues(app, soundsOn = { settings.sounds }, hapticsOn = { settings.haptics }),
            forceLanguage = { settings.forcedLanguage },
            speakAloud = { settings.speakAloud },
            answerMic = answerMic,
            // A visible "Tɛkyerɛma is asking…" card over any app while a follow-up question waits for an answer.
            showAsking = { q -> com.tekyerema.ui.OverlayConfirm.showQuestion(app, q) },
            hideAsking = { com.tekyerema.ui.OverlayConfirm.hide(app) },
            // Instant offline "thinking" fillers (bundled Abena-voiced clips) played while the brain works.
            fillers = FillerPlayer(app).also { fillerPlayer = it },
            // LOCAL FAST PATH: deterministic device ops (time/battery/volume/open-app/back…) before the LLM.
            fastRouter = com.tekyerema.agent.LocalFastRouter(app) { AgentEngine.currentController() },
            // Fast-path ACTION confirmations speak on the offline system engine so they're instant on any network.
            systemVoice = systemTts
        )
        scope.launch { combine(mic.level, answerMic.level) { a, b -> maxOf(a, b) }.collect { _level.value = it } }
        HistoryStore.init(app)
        applyVoiceSettings()
        scope.launch { settings.changes.collect { applyVoiceSettings() } }
        // History: one row per finished task, written on the transition into a terminal status.
        scope.launch {
            var wasActive = false
            AgentEngine.state.collect { st ->
                val active = st.status == AgentStatus.THINKING || st.status == AgentStatus.ACTING ||
                    st.status == AgentStatus.WAITING_CONFIRMATION
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
                }
                // §C.1 — pin the process with a foreground service WHILE a task is active, release it when the
                // session ends, so the mic/agent don't die if the screen changes mid-task.
                if (active) VoiceSessionService.start(app) else if (terminal) VoiceSessionService.stop(app)
                wasActive = active
            }
        }
    }

    private fun applyVoiceSettings() {
        systemTts.setRate(when (settings.speechRate) { 0 -> 0.8f; 2 -> 1.25f; else -> 1.0f })
    }

    /** Tap on the living visual: start listening, or cancel whatever is in flight. */
    fun toggle() = controller.toggle()
    fun start() = controller.start()
    /** Listen once more after a reply so the conversation can continue; silence just rests (no nag). */
    fun followUp() = controller.followUp()
    fun cancel() = controller.cancel()

    /** Typed command → the same agent entry point as an ASR transcript (see VoiceController.submitText). */
    fun submitText(text: String): Boolean = controller.submitText(text)
    fun beginTyping() = controller.beginTyping()
    fun endTyping() = controller.endTyping()
}
