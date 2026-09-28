package com.tekyerema.settings

import android.content.Context
import com.tekyerema.BuildConfig

/** Simple, synchronous settings backed by SharedPreferences. The API key stays on device. */
class Settings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Only what the user typed in the app (may be blank). */
    val storedApiKey: String get() = prefs.getString(KEY_API, "").orEmpty()

    /** Effective key: a user-entered key overrides the build-time default. */
    var geminiApiKey: String
        get() = storedApiKey.ifBlank { BuildConfig.DEFAULT_GEMINI_KEY }
        set(v) { prefs.edit().putString(KEY_API, v.trim()).apply() }

    var model: String
        get() = prefs.getString(KEY_MODEL, null) ?: com.tekyerema.core.config.AppConfig.string("models.gemini.model", DEFAULT_MODEL)
        set(v) { prefs.edit().putString(KEY_MODEL, v.trim()).apply() }

    val hasKey: Boolean get() = geminiApiKey.isNotBlank()

    // --- Groq / OpenAI-compatible provider (default agent brain when a key is present) ---
    val storedGroqKey: String get() = prefs.getString(KEY_GROQ, "").orEmpty()

    var groqApiKey: String
        get() = storedGroqKey.ifBlank { BuildConfig.DEFAULT_GROQ_KEY }
        set(v) { prefs.edit().putString(KEY_GROQ, v.trim()).apply() }

    /** All configured Groq keys, in priority order, for rate-limit (429) rotation. The primary key
     *  (possibly user-overridden in prefs) first, then the extra build-time keys. Distinct + non-blank. */
    val groqApiKeys: List<String>
        get() = listOf(groqApiKey, BuildConfig.DEFAULT_GROQ_KEY_2, BuildConfig.DEFAULT_GROQ_KEY_3, BuildConfig.DEFAULT_GROQ_KEY_4)
            .map { it.trim() }.filter { it.isNotBlank() }.distinct()

    var groqModel: String
        get() = prefs.getString(KEY_GROQ_MODEL, null)
            ?: BuildConfig.DEFAULT_GROQ_MODEL.ifBlank { com.tekyerema.core.config.AppConfig.string("models.planner.model", DEFAULT_GROQ_MODEL) }
        set(v) { prefs.edit().putString(KEY_GROQ_MODEL, v.trim()).apply() }

    /** Base URL for the OpenAI-compatible brain slot. Defaults to Groq's cloud endpoint, but
     *  local.properties' GROQ_BASE_URL can repoint this at any OpenAI-compatible server —
     *  including a local Ollama instance (e.g. http://<lan-ip>:11434/v1) — with no code change. */
    var groqBaseUrl: String
        get() = prefs.getString(KEY_GROQ_URL, null)?.ifBlank { null } ?: BuildConfig.DEFAULT_GROQ_BASE_URL.ifBlank { com.tekyerema.core.config.AppConfig.string("providers.groq.baseUrl", DEFAULT_GROQ_URL) }
        set(v) = put { putString(KEY_GROQ_URL, v.trim()) }

    /** Optional `reasoning_effort` sent to the OpenAI-compatible endpoint (e.g. "none"/"low"/
     *  "medium"/"high"). Blank (the default) omits the field entirely, preserving Groq's own
     *  default reasoning behavior for gpt-oss models — set via local.properties' GROQ_REASONING_EFFORT
     *  for endpoints/models where it helps (e.g. a local "thinking" model that's otherwise too slow). */
    val groqReasoningEffort: String
        get() = prefs.getString(KEY_REASONING_OVERRIDE, null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.DEFAULT_GROQ_REASONING_EFFORT

    /** DEBUG A/B only (RunGoalReceiver `--es reasoning low|medium|high|default`): overrides the build default. */
    var reasoningEffortOverride: String?
        get() = prefs.getString(KEY_REASONING_OVERRIDE, null)
        set(v) { prefs.edit().putString(KEY_REASONING_OVERRIDE, v?.trim()?.ifBlank { null }).apply() }

    val hasGroqKey: Boolean get() = groqApiKey.isNotBlank()

    /** When true, the RoutingBrain sends EVERY request to the cloud brain (the relay = Claude) instead of
     *  letting the on-device LocalBrain handle simple commands first. Auto-enabled by `set_brain <url>`. */
    var brainCloudFirst: Boolean
        get() = prefs.getBoolean(KEY_CLOUD_FIRST, false)
        set(v) = put { putBoolean(KEY_CLOUD_FIRST, v) }

    /** True if any cloud brain is configured. */
    val hasAnyBrain: Boolean get() = hasGroqKey || geminiApiKey.isNotBlank()

    // --- Phase 2 voice ---
    /** Base URL of the online voice endpoint (ASR + LID + normalization). Shares the origin of the
     *  OpenAI-compatible brain slot: the same proxy exposes /v1/voice next to /v1/chat/completions.
     *  On-device ASR is infeasible on the 2.8 GB test phone, so this is the online (Path B) provider. */
    val voiceBaseUrl: String get() = groqBaseUrl

    // --- Phase 8 hybrid ASR: online Ghanaian-English endpoint (PLACEHOLDER, configurable) ---
    /** OpenAI-compatible `/audio/transcriptions` origin that hosts the strong online Gh-EN model
     *  (whisper-large-v3-turbo-tekyerema-eng, 0.894 on GOLD). BLANK by default → online ASR is
     *  DISABLED and the router runs fully offline (on-device). Fill via local.properties'
     *  ASR_ONLINE_URL once the model is hosted (HF Inference Endpoint / self-host / Groq-compatible). */
    var asrOnlineBaseUrl: String
        get() = prefs.getString(KEY_ASR_URL, null)?.ifBlank { null } ?: BuildConfig.DEFAULT_ASR_ONLINE_URL
        set(v) = put { putString(KEY_ASR_URL, v.trim()) }

    /** API key for the online ASR endpoint. Falls back to the Groq key if a dedicated one isn't set. */
    var asrOnlineKey: String
        get() = (prefs.getString(KEY_ASR_KEY, null)?.ifBlank { null }
            ?: BuildConfig.DEFAULT_ASR_ONLINE_KEY.ifBlank { groqApiKey })
        set(v) = put { putString(KEY_ASR_KEY, v.trim()) }

    /** Model id served at [asrOnlineBaseUrl] (default = our Ghanaian-English tune). */
    var asrOnlineModel: String
        get() = prefs.getString(KEY_ASR_MODEL, null)?.ifBlank { null }
            ?: BuildConfig.DEFAULT_ASR_ONLINE_MODEL.ifBlank { DEFAULT_ASR_ONLINE_MODEL }
        set(v) = put { putString(KEY_ASR_MODEL, v.trim()) }

    /** True only when a real online-ASR endpoint is configured. When false the hybrid router never
     *  attempts the network — offline device control keeps working with zero online dependency. */
    val hasOnlineAsr: Boolean get() = asrOnlineBaseUrl.isNotBlank() && asrOnlineKey.isNotBlank()

    // --- Khaya (Ghana NLP) — the online path for NATURAL Twi speech (ASR + TTS), purpose-built for Twi.
    //     One key powers both. BLANK by default → Twi stays fully on-device (tiny model). Set KHAYA_API_KEY
    //     in local.properties (gitignored). When set + online, natural Twi transcribes far better and the
    //     response is spoken in a real Twi voice; offline still falls back to the on-device tiny model. ---
    var khayaApiKey: String
        get() = prefs.getString(KEY_KHAYA, null)?.ifBlank { null } ?: BuildConfig.DEFAULT_KHAYA_KEY
        set(v) = put { putString(KEY_KHAYA, v.trim()) }
    /** Ghana NLP Khaya API origin (ASR: /asr/v1/transcribe, TTS: /tts/v1/synthesize). */
    val khayaBaseUrl: String get() = BuildConfig.DEFAULT_KHAYA_URL.ifBlank { DEFAULT_KHAYA_URL }
    val hasKhaya: Boolean get() = khayaApiKey.isNotBlank()

    // --- Abena AI (Mobobi) — PRIMARY online voice provider (ASR + TTS, both languages). Ships
    //     Ghanaian-accented ENGLISH voices (akua_eng/kwabena_eng) plus Twi (abena_twi_high/lite). Khaya
    //     drops to fallback. Set ABENA_API_KEY in local.properties (gitignored). ---
    var abenaApiKey: String
        get() = prefs.getString(KEY_ABENA, null)?.ifBlank { null } ?: BuildConfig.DEFAULT_ABENA_KEY
        set(v) = put { putString(KEY_ABENA, v.trim()) }
    val abenaBaseUrl: String get() = BuildConfig.DEFAULT_ABENA_URL.ifBlank { DEFAULT_ABENA_URL }
    val hasAbena: Boolean get() = abenaApiKey.isNotBlank()

    // ---- Wake word (on-device: trained "Hey Tɛkyerɛma" openWakeWord heads; no account/key) ----
    /** Owner toggle for hands-free wake-word activation. ON by default now that a TRAINED model is wired
     *  (WakeScorer); the service still needs mic permission and the bundled assets/wakeword models (checked at
     *  arm time). Tap-to-talk always works regardless. Toggle at runtime with `--es set_wake 1|0`. */
    var wakeWordEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAKE, true)
        set(v) = put { putBoolean(KEY_WAKE, v) }
    val wakeWordActive: Boolean get() = wakeWordEnabled

    /** Ghanaian-English voice: akua_eng (female, default) or kwabena_eng (male). */
    var abenaEnglishVoice: String
        get() = prefs.getString(KEY_ABENA_EN_VOICE, null)?.ifBlank { null } ?: "akua_eng"
        set(v) = put { putString(KEY_ABENA_EN_VOICE, v.trim()) }
    /** Last-seen Abena ASR credit balance (from responses); -1 = unknown. §2.2/§2.3 budget guard. */
    var abenaCredits: Int
        get() = prefs.getInt(KEY_ABENA_CREDITS, -1)
        set(v) = put { putInt(KEY_ABENA_CREDITS, v) }
    /** Stop spending Abena ASR credits on non-live paths once the balance drops below this. */
    val abenaCreditReserve: Int get() = 10

    /** Session language context (§11). Persisted so the user need not re-pick each utterance;
     *  confidence-aware LID may flip it per-utterance but low-signal input falls back to this. */
    var sessionLanguage: String
        get() = prefs.getString(KEY_LANG, DEFAULT_LANG) ?: DEFAULT_LANG
        set(v) { prefs.edit().putString(KEY_LANG, v.trim().ifBlank { DEFAULT_LANG }).apply() }

    // --- User-facing preferences (the only things the Settings screens expose) ---

    /** Bumped on every write so Compose screens can re-read synchronously without a listener. Process-wide (shared by
     *  every Settings instance): a preference changed by voice (H-08) must reach the voice engine's own instance. */
    private val _changes get() = sharedChanges
    val changes: kotlinx.coroutines.flow.StateFlow<Long> get() = sharedChanges
    private fun put(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply(); _changes.value = _changes.value + 1
    }

    /** Language behaviour chosen explicitly by the user (§11 — never rely on auto-LID alone):
     *  [LANG_MODE_ENGLISH] / [LANG_MODE_TWI] force that language; [LANG_MODE_BOTH] lets LID follow
     *  natural code-switching with [sessionLanguage] as the low-signal fallback. */
    var languageMode: String
        get() = prefs.getString(KEY_LANG_MODE, LANG_MODE_BOTH) ?: LANG_MODE_BOTH
        set(v) = put { putString(KEY_LANG_MODE, v) }

    /** Language to force for ASR/TTS, or null to follow the speaker (auto LID). */
    val forcedLanguage: String? get() = when (languageMode) { LANG_MODE_ENGLISH -> "gh_en"; LANG_MODE_TWI -> "twi"; else -> null }

    /** Read every reply aloud (off = rely on TalkBack / on-screen text only). */
    var speakAloud: Boolean
        get() = prefs.getBoolean(KEY_SPEAK, true)
        set(v) = put { putBoolean(KEY_SPEAK, v) }

    /** TTS rate: 0 = slow, 1 = normal, 2 = fast. */
    var speechRate: Int
        get() = prefs.getInt(KEY_RATE, 1)
        set(v) = put { putInt(KEY_RATE, v.coerceIn(0, 2)) }

    /** B-13: how much Tɛkyerɛma says — "brief" | "normal" | "detailed" (default from config ux.verbosity). */
    var verbosity: String
        get() = prefs.getString("verbosity", null) ?: com.tekyerema.core.config.AppConfig.string("ux.verbosity", "normal")
        set(v) = put { putString("verbosity", v.trim().lowercase()) }

    /** B-13: voice pitch 0 = lower, 1 = normal, 2 = higher. */
    var speechPitch: Int
        get() = prefs.getInt("speech_pitch", 1)
        set(v) = put { putInt("speech_pitch", v.coerceIn(0, 2)) }

    /** K-11: "beginner" (patient listening, fuller explanations, hints) | "expert" (brief, quick) | "custom". */
    var profile: String
        get() = prefs.getString("profile", null) ?: "custom"
        set(v) {
            val p = v.trim().lowercase()
            put {
                putString("profile", p)
                when (p) {
                    "beginner" -> { putString("verbosity", "detailed"); putString("vad_strategy", "patient") }
                    "expert" -> { putString("verbosity", "brief"); putString("vad_strategy", "quick") }
                }
            }
        }

    /** J-18: allow the Gemini free tier as a backup brain. Google may use free-tier requests to improve its products,
     *  so a user can switch it off; Groq (no training on API data) then carries every request alone. */
    var allowGeminiBackup: Boolean
        get() = prefs.getBoolean("allow_gemini_backup", com.tekyerema.core.config.AppConfig.bool("privacy.allowGeminiBackup", true))
        set(v) = put { putBoolean("allow_gemini_backup", v) }

    /** L-04: the person called and texted (with your location) when you say "emergency". Set by voice or in Settings. */
    var emergencyContactName: String
        get() = prefs.getString("emergency_contact_name", "").orEmpty()
        set(v) = put { putString("emergency_contact_name", v.trim()) }
    var emergencyContactNumber: String
        get() = prefs.getString("emergency_contact_number", "").orEmpty()
        set(v) = put { putString("emergency_contact_number", v.filter { it.isDigit() || it == '+' }) }

    /** B-03: how long Tɛkyerɛma waits through pauses — "quick" | "normal" | "patient" (default from config). */
    var vadStrategy: String
        get() = prefs.getString("vad_strategy", null) ?: com.tekyerema.core.config.AppConfig.string("speech.vad.strategy", "normal")
        set(v) = put { putString("vad_strategy", v.trim().lowercase()) }

    var haptics: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(v) = put { putBoolean(KEY_HAPTICS, v) }

    var sounds: Boolean
        get() = prefs.getBoolean(KEY_SOUNDS, true)
        set(v) = put { putBoolean(KEY_SOUNDS, v) }

    var reduceMotion: Boolean
        get() = prefs.getBoolean(KEY_REDUCE_MOTION, false)
        set(v) = put { putBoolean(KEY_REDUCE_MOTION, v) }

    /** The floating "Talk to Tɛkyerɛma" trigger shown over other apps while screen control is on. */
    var floatingTrigger: Boolean
        get() = prefs.getBoolean(KEY_FLOATING, true)
        set(v) = put { putBoolean(KEY_FLOATING, v) }

    /** First launch shows one permissions page, then the product. */
    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDED, false)
        set(v) = put { putBoolean(KEY_ONBOARDED, v) }

    /** Q-07: the user agreed to keep their tasks (screens + actions) on this phone, encrypted, to improve Tɛkyerɛma.
     *  Off unless they switch it on; switching it off deletes what was kept. */
    var trajectoryConsent: Boolean
        get() = prefs.getBoolean("trajectory_consent", false)
        set(v) = put { putBoolean("trajectory_consent", v) }

    /** Q-08: keep a private log of what was asked and how it ended (encrypted, on this phone). Off by default. */
    var fieldLog: Boolean
        get() = prefs.getBoolean("field_log", false)
        set(v) = put { putBoolean("field_log", v) }

    companion object {
        const val PREFS_NAME = "tekyerema"
        private val sharedChanges = kotlinx.coroutines.flow.MutableStateFlow(0L)
        /** A preference was written outside this class (J-13 undo restoring voice settings). */
        fun notifyChanged() { sharedChanges.value = sharedChanges.value + 1 }
        const val LANG_MODE_ENGLISH = "english"
        const val LANG_MODE_TWI = "twi"
        const val LANG_MODE_BOTH = "both"
        private const val KEY_LANG_MODE = "language_mode"
        private const val KEY_SPEAK = "speak_aloud"
        private const val KEY_RATE = "speech_rate"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_SOUNDS = "sounds"
        private const val KEY_REDUCE_MOTION = "reduce_motion"
        private const val KEY_FLOATING = "floating_trigger"
        private const val KEY_ONBOARDED = "onboarding_done"
        private const val KEY_API = "gemini_api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_GROQ = "groq_api_key"
        private const val KEY_GROQ_MODEL = "groq_model"
        private const val KEY_GROQ_URL = "groq_base_url"
        private const val KEY_CLOUD_FIRST = "brain_cloud_first"
        private const val KEY_REASONING_OVERRIDE = "debug_reasoning_effort"
        private const val KEY_LANG = "session_language"
        const val DEFAULT_MODEL = "gemini-3.6-flash"
        const val DEFAULT_GROQ_MODEL = "openai/gpt-oss-120b"
        const val DEFAULT_GROQ_URL = "https://api.groq.com/openai/v1"
        const val DEFAULT_LANG = "gh_en"
        private const val KEY_ASR_URL = "asr_online_url"
        private const val KEY_ASR_KEY = "asr_online_key"
        private const val KEY_ASR_MODEL = "asr_online_model"
        const val DEFAULT_ASR_ONLINE_MODEL = "whisper-large-v3-turbo-tekyerema-eng"
        private const val KEY_KHAYA = "khaya_api_key"
        const val DEFAULT_KHAYA_URL = "https://translation-api.ghananlp.org"
        private const val KEY_ABENA = "abena_api_key"
        private const val KEY_ABENA_EN_VOICE = "abena_english_voice"
        private const val KEY_ABENA_CREDITS = "abena_credits_remaining"
        const val DEFAULT_ABENA_URL = "https://abena.mobobi.com/playground/api/v1"
        private const val KEY_WAKE = "wake_word_enabled"
    }
}
