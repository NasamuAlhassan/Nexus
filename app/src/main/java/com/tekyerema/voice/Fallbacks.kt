package com.tekyerema.voice

import android.util.Log

/**
 * Provider-agnostic voice plumbing: composites that try providers in order. [SpeechProviders] builds them from
 * config (B-18), so a second vendor per role — the UG HCI Lab ASR/TTS API, then the on-device engine — is a
 * config change, not a code change.
 */

/** Try TTS providers in order; the first that speaks wins. Degrades to the system engine (never silent). */
class FallbackTts(private val chain: List<TtsProvider>) : TtsProvider {
    override val name = "fallback(" + chain.joinToString(",") { it.name } + ")"
    override suspend fun speak(text: String, language: String): Boolean {
        for (p in chain) if (p.speak(text, language)) return true
        return false
    }
    override fun stop() { chain.forEach { it.stop() } }
    override fun shutdown() { chain.forEach { it.shutdown() } }
}

/** Try ASR providers in order; the first that returns a transcript wins. */
class FallbackAsr(private val chain: List<AsrProvider>) : AsrProvider {
    override val name = "fallback(" + chain.joinToString(",") { it.name } + ")"
    override suspend fun transcribe(wav: ByteArray, sessionLanguage: String, forceLanguage: String?): Transcript {
        var last: Exception? = null
        for (p in chain) {
            try { return p.transcribe(wav, sessionLanguage, forceLanguage) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }   // user stop must propagate, never fall through
            // Catch ANY provider failure — including a raw network IOException/SocketTimeout — so a timeout on
            // the primary provider still falls through to the next tier instead of silently killing the turn.
            catch (e: Exception) { Log.w("Tekyerema", "[ASR] ${p.name} failed → next: ${e.message}"); last = e }
        }
        throw AsrException(last?.message ?: "no ASR provider available")
    }
}

/**
 * B-18 — which speech providers fill each role, from config `providers.speech.*` instead of code:
 *
 *   "tts": { "english": ["system"], "twi": ["system"], "ghEnglish": [] }   tried in order; the system engine is
 *                                                                          always the last resort (never silent)
 *   "asr": { "online": { "twi": [], "gh_en": [], "auto": [] } }            online ids per language; empty ⇒ on-device
 *
 * Known ids today: "system" (the phone's TTS engine). The UG HCI Lab ASR/TTS clients register their ids in the
 * registries VoiceEngine passes in; an id with no client is skipped and logged, never guessed.
 */
object SpeechProviders {
    /** Pure: the configured ids that have a registered provider, in order (unknown ids dropped). */
    fun <T> resolve(ids: List<String>, registry: Map<String, T>, unknown: (String) -> Unit = {}): List<T> =
        ids.map { it.trim().lowercase() }.distinct().mapNotNull { id -> registry[id] ?: run { unknown(id); null } }

    fun ttsIds(role: String, default: List<String>): List<String> =
        com.tekyerema.core.config.AppConfig.stringList("providers.speech.tts.$role", default)

    fun onlineAsrIds(lang: String?): List<String> =
        com.tekyerema.core.config.AppConfig.stringList("providers.speech.asr.online.${lang ?: "auto"}", emptyList())

    /** A TTS role from config, ending in [fallback] so the chain can never be silent. */
    fun tts(role: String, registry: Map<String, TtsProvider>, fallback: TtsProvider, default: List<String> = listOf("system")): TtsProvider {
        val chain = resolve(ttsIds(role, default), registry) { Log.w("Tekyerema", "[VOICE] tts.$role: no provider '$it' — skipped") }
        val full = if (fallback in chain) chain else chain + fallback
        return if (full.size == 1) full[0] else FallbackTts(full)
    }

    /** The online ASR for a language from config, or null (⇒ on-device). */
    fun onlineAsr(lang: String?, registry: Map<String, AsrProvider>): AsrProvider? {
        val chain = resolve(onlineAsrIds(lang), registry) { Log.w("Tekyerema", "[VOICE] asr.online.$lang: no provider '$it' — skipped") }
        return when (chain.size) { 0 -> null; 1 -> chain[0]; else -> FallbackAsr(chain) }
    }
}
