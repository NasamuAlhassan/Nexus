package com.tekyerema.language

import android.content.Context
import android.util.Log
import com.tekyerema.core.config.AppConfig
import com.tekyerema.voice.AsrProvider
import com.tekyerema.voice.FallbackAsr
import com.tekyerema.voice.Lang
import com.tekyerema.voice.SpeechProviders
import com.tekyerema.voice.TtsProvider
import org.json.JSONObject

/**
 * B-18/X-05 — the language layer's assembly point. Reads `providers.speech.clients` (assets/config/language.json),
 * resolves each client's `${…}` placeholders from local.properties, and builds the ASR / TTS / MT registries that
 * [SpeechProviders] routes over by language (`providers.speech.asr.online.<lang>`, `tts.<role>`, `mt.<direction>`).
 *
 * Degrades by construction: a client that is disabled, missing a key, or not https is simply not in the registry,
 * so routing falls through to the next id and finally to the on-device engines. With the shipped config every
 * client is disabled — the app behaves exactly as it did before this layer existed.
 */
object LanguageLayer {
    private const val TAG = "Tekyerema"

    class Registries(
        val asr: Map<String, AsrProvider>,
        val tts: Map<String, TtsProvider>,
        val mt: Map<String, TranslationProvider>,
        /** id → why it was not built (disabled, missing key…). Never contains a secret. */
        val skipped: Map<String, String>
    ) {
        fun summary(): String =
            "asr=${asr.keys} tts=${tts.keys} mt=${mt.keys}" + if (skipped.isEmpty()) "" else " skipped=" +
                skipped.entries.joinToString(",") { "${it.key}(${it.value})" }

        companion object { val EMPTY = Registries(emptyMap(), emptyMap(), emptyMap(), emptyMap()) }
    }

    /**
     * Endpoints and keys, from local.properties via BuildConfig: every `SPEECH_*` entry, plus the older
     * `ASR_ONLINE_URL/KEY/MODEL` slots so the OpenAI-compatible preset can reuse them. Never logged.
     */
    fun secrets(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        try {
            val o = JSONObject(com.tekyerema.BuildConfig.SPEECH_SECRETS_JSON.ifBlank { "{}" })
            for (k in o.keys()) o.optString(k).takeIf { it.isNotBlank() }?.let { out[k] = it }
        } catch (t: Throwable) { Log.w(TAG, "[LANG] SPEECH_* secrets unreadable: ${t.javaClass.simpleName}") }
        com.tekyerema.BuildConfig.DEFAULT_ASR_ONLINE_URL.takeIf { it.isNotBlank() }?.let { out.putIfAbsent("ASR_ONLINE_URL", it.trimEnd('/')) }
        com.tekyerema.BuildConfig.DEFAULT_ASR_ONLINE_KEY.takeIf { it.isNotBlank() }?.let { out.putIfAbsent("ASR_ONLINE_KEY", it) }
        com.tekyerema.BuildConfig.DEFAULT_ASR_ONLINE_MODEL.takeIf { it.isNotBlank() }?.let { out.putIfAbsent("ASR_ONLINE_MODEL", it) }
        return out
    }

    /**
     * Pure builder (unit-tested): one provider per usable client entry. [sink] is only called for TTS clients, so
     * a test (or a device with no TTS client) never creates a MediaPlayer.
     */
    fun build(
        clients: JSONObject?,
        secrets: Map<String, String>,
        transport: SpeechTransport,
        sink: () -> AudioSink,
        health: (String) -> ProviderHealth = { ProviderHealthRegistry.of(it) },
        ledger: QuotaLedger = QuotaLedger()
    ): Registries {
        clients ?: return Registries.EMPTY
        val asr = LinkedHashMap<String, AsrProvider>()
        val tts = LinkedHashMap<String, TtsProvider>()
        val mt = LinkedHashMap<String, TranslationProvider>()
        val skipped = LinkedHashMap<String, String>()
        for (rawId in clients.keys()) {
            if (rawId.startsWith("_")) continue
            val id = rawId.trim().lowercase()
            val o = clients.optJSONObject(rawId) ?: continue
            val spec = HttpSpeechSpec.parse(id, o)
            if (spec == null) { skipped[id] = "unknown kind '${o.optString("kind")}'"; continue }
            val unusable = HttpSpeech.unusableReason(spec, secrets)
            if (unusable != null) { skipped[id] = unusable; continue }
            if (!spec.limits.isEmpty) Log.i(TAG, "[LANG] $id quota: ${ledger.usage(id, spec.limits)}")
            // Backstop a little past the HTTP call timeout; the transport's own timeouts normally fire first.
            val guard = ProviderGuard(health(spec.quotaGroup), spec.connectMs + spec.callMs + 1_000L)
            val s = { secrets }
            when (spec.kind) {
                HttpSpeechSpec.Kind.ASR -> asr[id] = HttpAsrProvider(spec, s, transport, guard, ledger)
                HttpSpeechSpec.Kind.TTS -> tts[id] = HttpTtsProvider(spec, s, transport, guard, sink(), ledger)
                HttpSpeechSpec.Kind.MT -> mt[id] = HttpTranslationProvider(spec, s, transport, guard)
            }
        }
        return Registries(asr, tts, mt, skipped)
    }

    /** Quota counts survive a restart (the HCI Lab plan's day and week are small). Only counts — no content. */
    private class PrefsStore(app: Context) : QuotaLedger.Store {
        private val p = app.getSharedPreferences("tekyerema_speech_quota", Context.MODE_PRIVATE)
        override fun get(key: String) = p.getLong(key, 0L)
        override fun put(key: String, value: Long) { p.edit().putLong(key, value).apply() }
    }

    /** The app's registries from the loaded config and local.properties. */
    fun build(app: Context): Registries {
        val r = try {
            build(AppConfig.obj("providers.speech.clients"), secrets(), OkHttpSpeechTransport, { MediaAudioSink(app) },
                ledger = QuotaLedger(PrefsStore(app)))
        } catch (t: Throwable) {
            Log.w(TAG, "[LANG] language layer failed to build — on-device only: ${t.javaClass.simpleName}: ${t.message}", t); Registries.EMPTY
        }
        Log.i(TAG, "[LANG] speech clients: ${r.summary()}")
        return r
    }

    private fun usable(p: Any): Boolean = (p as? HealthAware)?.available() ?: true

    /**
     * The online recogniser for [lang] (null = auto), or null to stay on-device. Null when [cloudAllowed] is false —
     * the caller folds in "offline" AND the Secure Input boundary (audio never leaves the phone at a PIN screen) —
     * and skips providers that are resting after quota/auth/network failures, so a dead API costs no time.
     */
    fun onlineAsr(lang: String?, registry: Map<String, AsrProvider>, cloudAllowed: Boolean): AsrProvider? {
        if (!cloudAllowed || registry.isEmpty()) return null
        val chain = SpeechProviders.resolve(SpeechProviders.onlineAsrIds(lang), registry) {
            Log.w(TAG, "[LANG] asr.online.${lang ?: "auto"}: no client '$it' (disabled or missing keys) — skipped")
        }.filter(::usable)
        return when (chain.size) { 0 -> null; 1 -> chain[0]; else -> FallbackAsr(chain) }
    }

    /** Config key for a translation direction: `providers.speech.mt.enToTwi` / `twiToEn`. */
    fun mtIds(from: String, to: String): List<String> =
        AppConfig.stringList("providers.speech.mt.${direction(from, to)}", emptyList())

    private fun direction(from: String, to: String) =
        (if (from == Lang.TWI) "twi" else "en") + "To" + (if (to == Lang.TWI) "Twi" else "En")

    /** The configured translator for a direction (resting providers skipped at call time), or null. */
    fun translator(from: String, to: String, registry: Map<String, TranslationProvider>, isOnline: () -> Boolean): TranslationProvider? {
        val chain = SpeechProviders.resolve(mtIds(from, to), registry) {
            Log.w(TAG, "[LANG] mt.${direction(from, to)}: no client '$it' — skipped")
        }
        if (chain.isEmpty()) return null
        val inner = if (chain.size == 1) chain[0] else FallbackTranslator(chain)
        return object : TranslationProvider {
            override val name = inner.name
            override suspend fun translate(text: String, from: String, to: String): String? =
                if (!isOnline()) null else inner.translate(text, from, to)
        }
    }
}
