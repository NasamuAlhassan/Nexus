package com.tekyerema.settings

import android.content.Context
import com.tekyerema.BuildConfig

/** Simple, synchronous settings backed by SharedPreferences. The API key stays on device. */
class Settings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** J-15 (safety audit): an API key the user types in is kept ENCRYPTED (SecureStore — a Keystore AES-GCM key that
     *  never leaves the phone), not as plain text in the preferences file. An old plain value is re-saved encrypted the
     *  first time it's read. If the Keystore can't be used the key is kept as before, so the app keeps working. */
    private fun putSecret(key: String, value: String) {
        val v = value.trim()
        val enc = if (v.isEmpty()) "" else com.tekyerema.core.SecureStore.encrypt(v.toByteArray(Charsets.UTF_8))
            ?.let { ENC + android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
        val before = prefs.getString(key, "").orEmpty()
        prefs.edit().putString(key, enc ?: v).apply()
        // WS-C: the Control page shows which keys are yours (masked) — redraw only when the key itself changed (a plain
        // value re-saved on read, with no Keystore to encrypt it, must not bump the counter on every read).
        val unchanged = if (before.startsWith(ENC)) isSame(before, v) else before == v
        if (!unchanged) _changes.value = _changes.value + 1
    }

    /** Does the stored (encrypted) value [stored] hold [plain]? */
    private fun isSame(stored: String, plain: String): Boolean = try {
        com.tekyerema.core.SecureStore.decrypt(android.util.Base64.decode(stored.removePrefix(ENC), android.util.Base64.NO_WRAP))
            ?.toString(Charsets.UTF_8) == plain
    } catch (_: Throwable) { false }

    private fun getSecret(key: String): String {
        val raw = prefs.getString(key, "").orEmpty()
        if (raw.isEmpty()) return ""
        if (!raw.startsWith(ENC)) { putSecret(key, raw); return raw }
        return try {
            com.tekyerema.core.SecureStore.decrypt(android.util.Base64.decode(raw.removePrefix(ENC), android.util.Base64.NO_WRAP))
                ?.toString(Charsets.UTF_8).orEmpty()
        } catch (_: Throwable) { "" }
    }

    /** Only what the user typed in the app (may be blank). */
    val storedApiKey: String get() = getSecret(KEY_API)

    /** Effective key: a user-entered key overrides the build-time default. */
    var geminiApiKey: String
        get() = storedApiKey.ifBlank { BuildConfig.DEFAULT_GEMINI_KEY }
        set(v) = putSecret(KEY_API, v)

    var model: String
        get() = prefs.getString(KEY_MODEL, null) ?: com.tekyerema.core.config.AppConfig.string("models.gemini.model", DEFAULT_MODEL)
        set(v) { prefs.edit().putString(KEY_MODEL, v.trim()).apply() }

    val hasKey: Boolean get() = geminiApiKey.isNotBlank()

    // --- Groq / OpenAI-compatible provider (default agent brain when a key is present) ---
    val storedGroqKey: String get() = getSecret(KEY_GROQ)

    /** WS-C: the user pointed Tɛkyerɛma at their OWN OpenAI-compatible server in Settings › Control (not the debug
     *  relay, which keeps the old behaviour). Then only the user's own key is ever sent there — never a built-in one. */
    val usesOwnServer: Boolean
        get() = prefs.getBoolean(KEY_OWN_SERVER, false) && !prefs.getString(KEY_GROQ_URL, null).isNullOrBlank()

    var groqApiKey: String
        get() = if (usesOwnServer) storedGroqKey else storedGroqKey.ifBlank { BuildConfig.DEFAULT_GROQ_KEY }
        set(v) = putSecret(KEY_GROQ, v)

    /** All configured Groq keys, in priority order, for rate-limit (429) rotation. The primary key
     *  (possibly user-overridden in prefs) first, then the extra build-time keys. Distinct + non-blank.
     *  WS-C: with the user's own server, only the user's own key. */
    val groqApiKeys: List<String>
        get() = (if (usesOwnServer) listOf(storedGroqKey)
            else listOf(groqApiKey, BuildConfig.DEFAULT_GROQ_KEY_2, BuildConfig.DEFAULT_GROQ_KEY_3, BuildConfig.DEFAULT_GROQ_KEY_4,
                BuildConfig.DEFAULT_GROQ_KEY_5, BuildConfig.DEFAULT_GROQ_KEY_6))
            .map { it.trim() }.filter { it.isNotBlank() }.distinct()

    /** P1-15: keys for the extra planner rungs (`PLANNER_KEY_<ID>` in local.properties → debug builds only), id → key. */
    val plannerKeys: Map<String, String>
        get() = com.tekyerema.agent.PlannerProvider.parseKeys(BuildConfig.DEFAULT_PLANNER_KEYS)

    /** WS-C: a key built into this copy of the app exists for [provider] ("groq" | "gemini") — shown as "Built-in key". */
    fun hasBuiltInKey(provider: String): Boolean = when (provider) {
        "groq" -> BuildConfig.DEFAULT_GROQ_KEY.isNotBlank()
        "gemini" -> BuildConfig.DEFAULT_GEMINI_KEY.isNotBlank()
        else -> false
    }

    /** WS-C: the key the user typed for [provider], or "" (decrypted — show only masked, never log). */
    fun userKey(provider: String): String = when (provider) {
        "groq" -> storedGroqKey
        "gemini" -> storedApiKey
        else -> ""
    }

    /** WS-C: save (encrypted) or, with "", remove the user's own key for [provider]. */
    fun setUserKey(provider: String, key: String) {
        when (provider) {
            "groq" -> putSecret(KEY_GROQ, key)
            "gemini" -> putSecret(KEY_API, key)
        }
    }

    /** WS-C: use the user's own OpenAI-compatible server (and optionally model) for the planner slot; null = back to Groq. */
    fun setOwnServer(url: String?, model: String?) {
        if (url.isNullOrBlank()) put { remove(KEY_GROQ_URL); remove(KEY_OWN_SERVER); remove(KEY_GROQ_MODEL) }
        else put {
            putString(KEY_GROQ_URL, url.trim()); putBoolean(KEY_OWN_SERVER, true)
            if (!model.isNullOrBlank()) putString(KEY_GROQ_MODEL, model.trim())
        }
    }

    var groqModel: String
        get() = prefs.getString(KEY_GROQ_MODEL, null)
            ?: BuildConfig.DEFAULT_GROQ_MODEL.ifBlank { com.tekyerema.core.config.AppConfig.string("models.planner.model", DEFAULT_GROQ_MODEL) }
        set(v) = put { putString(KEY_GROQ_MODEL, v.trim()) }   // WS-C: chosen in Settings › Control, so the page redraws

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

    /** API key for the online ASR endpoint. Falls back to the Groq key if a dedicated one isn't set.
     *  WS-C: kept encrypted like the other keys (an old plain value is re-saved encrypted on first read). */
    var asrOnlineKey: String
        get() = (getSecret(KEY_ASR_KEY).ifBlank { null }
            ?: BuildConfig.DEFAULT_ASR_ONLINE_KEY.ifBlank { groqApiKey })
        set(v) = putSecret(KEY_ASR_KEY, v)

    /** Model id served at [asrOnlineBaseUrl] (default = our Ghanaian-English tune). */
    var asrOnlineModel: String
        get() = prefs.getString(KEY_ASR_MODEL, null)?.ifBlank { null }
            ?: BuildConfig.DEFAULT_ASR_ONLINE_MODEL.ifBlank { DEFAULT_ASR_ONLINE_MODEL }
        set(v) = put { putString(KEY_ASR_MODEL, v.trim()) }

    /** True only when a real online-ASR endpoint is configured. When false the hybrid router never
     *  attempts the network — offline device control keeps working with zero online dependency. */
    val hasOnlineAsr: Boolean get() = asrOnlineBaseUrl.isNotBlank() && asrOnlineKey.isNotBlank()

    // The Khaya (Ghana NLP) and Abena (Mobobi) speech keys were removed 2026-09-28 with their clients (owner-
    // approved): the UG HCI Lab ASR/TTS API is the required provider and plugs in through SpeechProviders (B-18).

    // ---- Wake word (on-device: trained "Hey Tɛkyerɛma" openWakeWord heads; no account/key) ----
    /** Owner toggle for hands-free wake-word activation. ON by default now that a TRAINED model is wired
     *  (WakeScorer); the service still needs mic permission and the bundled assets/wakeword models (checked at
     *  arm time). Tap-to-talk always works regardless. Toggle at runtime with `--es set_wake 1|0`. */
    var wakeWordEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAKE, true)
        set(v) = put { putBoolean(KEY_WAKE, v) }
    val wakeWordActive: Boolean get() = wakeWordEnabled

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
        // Nexus is English only (owner, 31 Oct 2026): a Twi or auto mode stored by an older build is not followed.
        get() = if (com.tekyerema.core.config.AppConfig.bool("speech.language.englishOnly", false)) LANG_MODE_ENGLISH
            else prefs.getString(KEY_LANG_MODE, LANG_MODE_BOTH) ?: LANG_MODE_BOTH
        set(v) = put { putString(KEY_LANG_MODE, v) }

    /** Language to force for ASR/TTS, or null to follow the speaker (auto LID). */
    val forcedLanguage: String? get() = when (languageMode) { LANG_MODE_ENGLISH -> "gh_en"; LANG_MODE_TWI -> "twi"; LANG_MODE_KUS -> "kus"; else -> null }

    /** Read every reply aloud (off = rely on TalkBack / on-screen text only). */
    var speakAloud: Boolean
        get() = prefs.getBoolean(KEY_SPEAK, true)
        set(v) = put { putBoolean(KEY_SPEAK, v) }

    /** TTS rate: 0 = slow, 1 = normal, 2 = fast. */
    var speechRate: Int
        get() = prefs.getInt(KEY_RATE, 1)
        set(v) = put { putInt(KEY_RATE, v.coerceIn(0, 2)) }

    /** The English voice's accent (voice/TtsAccent): "auto" | "GH" | "GB" | "NG" | "US". */
    var ttsAccent: String
        get() = prefs.getString(KEY_TTS_ACCENT, com.tekyerema.voice.TtsAccent.AUTO) ?: com.tekyerema.voice.TtsAccent.AUTO
        set(v) = put { putString(KEY_TTS_ACCENT, v.takeIf { it in com.tekyerema.voice.TtsAccent.CHOICES } ?: com.tekyerema.voice.TtsAccent.AUTO) }

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
    /** WS-C: every Gemini call site checks this, so it also says no while the user has the cloud off, today's budget is
     *  spent, or Gemini isn't in their provider list (Settings › Control) — the stored J-18 choice itself is unchanged. */
    var allowGeminiBackup: Boolean
        get() = prefs.getBoolean("allow_gemini_backup", com.tekyerema.core.config.AppConfig.bool("privacy.allowGeminiBackup", true)) &&
            com.tekyerema.operator.OperatorPolicy.providerAllowed(com.tekyerema.agent.ModelRoles.GEMINI)
        set(v) = put { putBoolean("allow_gemini_backup", v) }

    /** L-04: the person called and texted (with your location) when you say "emergency". Set by voice or in Settings. */
    var emergencyContactName: String
        get() = prefs.getString("emergency_contact_name", "").orEmpty()
        set(v) = put { putString("emergency_contact_name", v.trim()) }
    var emergencyContactNumber: String
        get() = prefs.getString("emergency_contact_number", "").orEmpty()
        set(v) = put { putString("emergency_contact_number", v.filter { it.isDigit() || it == '+' }) }

    /** The danger code word (safety/DangerAlert): the phrase that, once heard, starts the emergency alert after a short cancel window.
     *  Off until the user chooses a phrase and turns it on. The cancel PIN is kept like the other secrets. */
    var dangerEnabled: Boolean
        get() = prefs.getBoolean("danger_enabled", false) && dangerCodeWord.isNotBlank()
        set(v) = put { putBoolean("danger_enabled", v) }
    /** True once the user has heard what the code word does and said "I agree to the danger code word". Deleting the danger data clears it. */
    var dangerConsented: Boolean
        get() = prefs.getBoolean("danger_consented", false)
        set(v) = put { putBoolean("danger_consented", v) }
    var dangerCodeWord: String
        get() = getSecret("danger_code_word")
        set(v) = putSecret("danger_code_word", v.trim().lowercase())
    var dangerCancelPin: String
        get() = getSecret("danger_cancel_pin")
        set(v) = putSecret("danger_cancel_pin", v.filter { it.isDigit() })
    /** People texted the user's location when the alert fires: "name|number" lines, at most [MAX_TRUSTED]. The emergency contact is always one. */
    var trustedContacts: List<Pair<String, String>>
        get() = prefs.getString("trusted_contacts", "").orEmpty().lines().mapNotNull { l ->
            val i = l.indexOf('|'); if (i <= 0) null else l.substring(0, i) to l.substring(i + 1)
        }.filter { it.second.isNotBlank() }
        set(v) = put { putString("trusted_contacts", v.take(MAX_TRUSTED).joinToString("\n") { (n, num) -> "${n.replace('|', ' ')}|${num.filter { it.isDigit() || it == '+' }}" }) }

    /** Private reading (2026-09-29, notifications/PrivateReading): on the loudspeaker, say who messages are from and how
     *  many — not what they say — until the user says "read them". Headphones read them in full. Off by default. */
    var privateReading: Boolean
        get() = prefs.getBoolean("private_reading", false)
        set(v) = put { putBoolean("private_reading", v) }

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

    /** Settings › Privacy (2 Nov 2026): cover the screen for the WHOLE money flow, from the read-back until the network's
     *  answer has left the screen (phone/PinCurtain, money/MoneyMoment). Off by default: low-vision users read the amount and
     *  the name on screen as their own check of the read-back; the PIN step is covered either way (`pin.curtain`). */
    var moneyCurtain: Boolean
        get() = prefs.getBoolean(KEY_MONEY_CURTAIN, com.tekyerema.core.config.AppConfig.bool("privacy.moneyCurtain", false))
        set(v) = put { putBoolean(KEY_MONEY_CURTAIN, v) }

    var reduceMotion: Boolean
        get() = prefs.getBoolean(KEY_REDUCE_MOTION, false)
        set(v) = put { putBoolean(KEY_REDUCE_MOTION, v) }

    /** Light, dark, or follow the phone ("system", the default). */
    var appearance: String
        get() = prefs.getString(KEY_APPEARANCE, APPEARANCE_SYSTEM) ?: APPEARANCE_SYSTEM
        set(v) = put { putString(KEY_APPEARANCE, v) }

    /** The floating "Talk to Tɛkyerɛma" trigger shown over other apps while screen control is on. */
    var floatingTrigger: Boolean
        get() = prefs.getBoolean(KEY_FLOATING, true)
        set(v) = put { putBoolean(KEY_FLOATING, v) }

    /** First launch shows one permissions page, then the product. */
    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDED, false)
        set(v) = put { putBoolean(KEY_ONBOARDED, v) }

    /** First-run voice welcome (voice/Welcome.kt): set as it starts, so it plays once; Settings › Replay the welcome runs it again. */
    var welcomeDone: Boolean
        get() = prefs.getBoolean("welcome_done", false)
        set(v) = put { putBoolean("welcome_done", v) }

    /** What the user asked to be called in the welcome ("Good morning, Ama"); blank = no name. */
    var preferredName: String
        get() = prefs.getString("preferred_name", "").orEmpty()
        set(v) = put { putString("preferred_name", v.trim().take(40)) }

    /** Q-07: the user agreed to keep their tasks (screens + actions) on this phone, encrypted, to improve Tɛkyerɛma.
     *  Off unless they switch it on; switching it off deletes what was kept. */
    var trajectoryConsent: Boolean
        get() = prefs.getBoolean("trajectory_consent", false)
        set(v) = put { putBoolean("trajectory_consent", v) }

    /** Q-08: keep a private log of what was asked and how it ended (encrypted, on this phone). Off by default. */
    var fieldLog: Boolean
        get() = prefs.getBoolean("field_log", false)
        set(v) = put { putBoolean("field_log", v) }

    // ---- Phone care alerts (phonecare/PhoneAlerts). A20e 2026-09-29: nothing told the user that money came in, who
    // called or that the battery was going (docs/agent/research/openclaw.md §5.2). Each alert is OFF until the user
    // switches it on (Settings › Phone care, or by voice). "Private alerts" starts ON — the stricter side: on the
    // loudspeaker only "You have a money alert. Say 'read it'…" (assistive_tech_blind.md §8).
    var alertMoney: Boolean
        get() = prefs.getBoolean("alert_money", false)
        set(v) = put { putBoolean("alert_money", v) }
    /** Money alerts also say the balance. Off: a balance is never spoken unasked. */
    var alertMoneyBalance: Boolean
        get() = prefs.getBoolean("alert_money_balance", false)
        set(v) = put { putBoolean("alert_money_balance", v) }
    var alertMissedCalls: Boolean
        get() = prefs.getBoolean("alert_missed_calls", false)
        set(v) = put { putBoolean("alert_missed_calls", v) }
    var alertBattery: Boolean
        get() = prefs.getBoolean("alert_battery", false)
        set(v) = put { putBoolean("alert_battery", v) }
    var alertsPrivate: Boolean
        get() = prefs.getBoolean("alerts_private", true)
        set(v) = put { putBoolean("alerts_private", v) }

    companion object {
        const val MAX_TRUSTED = 3
        const val PREFS_NAME = "tekyerema"
        private val sharedChanges = kotlinx.coroutines.flow.MutableStateFlow(0L)
        /** A preference was written outside this class (J-13 undo restoring voice settings). */
        fun notifyChanged() { sharedChanges.value = sharedChanges.value + 1 }
        const val LANG_MODE_ENGLISH = "english"
        const val LANG_MODE_TWI = "twi"
        /** Kusaal only (19 October 2026): heard, translated and voiced by our language Space; there is no auto-detection
         *  of Kusaal, so it is always this explicit choice. */
        const val LANG_MODE_KUS = "kus"
        const val LANG_MODE_BOTH = "both"
        private const val KEY_LANG_MODE = "language_mode"
        private const val KEY_SPEAK = "speak_aloud"
        private const val KEY_RATE = "speech_rate"
        private const val KEY_MONEY_CURTAIN = "money_curtain"
        private const val KEY_TTS_ACCENT = "tts_accent"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_SOUNDS = "sounds"
        private const val KEY_REDUCE_MOTION = "reduce_motion"
        private const val KEY_APPEARANCE = "appearance"
        const val APPEARANCE_SYSTEM = "system"
        const val APPEARANCE_LIGHT = "light"
        const val APPEARANCE_DARK = "dark"
        private const val KEY_FLOATING = "floating_trigger"
        private const val KEY_ONBOARDED = "onboarding_done"
        private const val ENC = "enc1:"
        private const val KEY_API = "gemini_api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_GROQ = "groq_api_key"
        private const val KEY_GROQ_MODEL = "groq_model"
        private const val KEY_GROQ_URL = "groq_base_url"
        private const val KEY_OWN_SERVER = "groq_base_url_user_set"
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
        private const val KEY_WAKE = "wake_word_enabled"
    }
}
