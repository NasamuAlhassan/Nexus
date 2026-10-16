package com.tekyerema.voice

import android.util.Log
import kotlinx.coroutines.async

/**
 * Provider-agnostic voice plumbing: composites that try providers in order. [SpeechProviders] builds them from
 * config (B-18), so a second vendor per role — the UG HCI Lab ASR/TTS API, then the on-device engine — is a
 * config change, not a code change.
 */

/** Try TTS providers in order; the first that speaks wins. Degrades to the system engine (never silent). */
class FallbackTts(private val chain: List<TtsProvider>) : TtsProvider, VoiceAvailability {
    override val name = "fallback(" + chain.joinToString(",") { it.name } + ")"
    /** The first reason any voice in the chain gives for being unusable (the network voice's: key, quota, link). */
    override fun whyUnavailable(): String? = chain.firstNotNullOfOrNull { (it as? VoiceAvailability)?.whyUnavailable() }
    /** The voice that said the last line, null when none did — for the log: "spoke Twi via fallback(lab, system)" named
     *  both, and read like the lab voice had failed when it had spoken (A20e, 9 October 2026). */
    @Volatile var lastVoice: String? = null
        private set
    override suspend fun speak(text: String, language: String): Boolean {
        for (p in chain) if (p.speak(text, language)) { lastVoice = p.name; return true }
        lastVoice = null
        return false
    }
    override fun stop() { chain.forEach { it.stop() } }
    override fun shutdown() { chain.forEach { it.shutdown() } }
}

/**
 * Try ASR providers in order; the first that returns a transcript wins.
 *
 * With [hedgeMs] > 0 (`providers.speech.asr.hedgeMs`, 7 s) the first provider gets a head start: still busy after it, the
 * second is asked beside it and whichever answers first is used, the other cancelled. The UG HCI Lab answers in 4–6 s;
 * on 16 Oct 2026 it timed out at 15 s on every turn before Khaya answered in 1.5–2.7 s — the A20e waited ~18 s per reply.
 * The lab is still asked first and still wins whenever it is itself.
 */
class FallbackAsr(private val chain: List<AsrProvider>, private val hedgeMs: () -> Long = { configuredHedgeMs() }) : AsrProvider {
    override val name = "fallback(" + chain.joinToString(",") { it.name } + ")"
    override suspend fun transcribe(wav: ByteArray, sessionLanguage: String, forceLanguage: String?): Transcript {
        val hedge = hedgeMs()
        val rest = if (chain.size >= 2 && hedge > 0) {
            raced(wav, sessionLanguage, forceLanguage, hedge)?.let { return it }
            chain.drop(2)
        } else chain
        for (p in rest) attempt(p, wav, sessionLanguage, forceLanguage)?.let { return it }
        throw AsrException("no ASR provider answered")
    }

    /** The first provider, and the second beside it once [hedge] ms pass without an answer. Null when both failed. */
    private suspend fun raced(wav: ByteArray, s: String, f: String?, hedge: Long): Transcript? = kotlinx.coroutines.coroutineScope {
        val first = async { attempt(chain[0], wav, s, f) }
        kotlinx.coroutines.withTimeoutOrNull(hedge) { first.join() }
        if (first.isCompleted) return@coroutineScope first.await() ?: attempt(chain[1], wav, s, f)
        Log.i("Tekyerema", "[ASR] ${chain[0].name} still busy after ${hedge}ms — asking ${chain[1].name} too")
        val pending = mutableListOf(first, async { attempt(chain[1], wav, s, f) })
        while (pending.isNotEmpty()) {
            val (done, t) = kotlinx.coroutines.selects.select<Pair<kotlinx.coroutines.Deferred<Transcript?>, Transcript?>> {
                pending.forEach { d -> d.onAwait { d to it } }
            }
            pending.remove(done)
            if (t != null) { pending.forEach { it.cancel() }; return@coroutineScope t }
        }
        null
    }

    private suspend fun attempt(p: AsrProvider, wav: ByteArray, s: String, f: String?): Transcript? =
        try { p.transcribe(wav, s, f) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }   // user stop must propagate, never fall through
        // Catch ANY provider failure — including a raw network IOException/SocketTimeout — so a timeout on
        // the primary provider still falls through to the next tier instead of silently killing the turn.
        catch (e: Exception) { Log.w("Tekyerema", "[ASR] ${p.name} failed → next: ${e.message}"); null }

    companion object {
        fun configuredHedgeMs(): Long =
            try { com.tekyerema.core.config.AppConfig.long("providers.speech.asr.hedgeMs", 7_000L) } catch (_: Throwable) { 7_000L }
    }
}

/**
 * B-18 — which speech providers fill each role, from config `providers.speech.*` instead of code:
 *
 *   "tts": { "english": ["system"], "twi": ["system"], "ghEnglish": [] }   tried in order; the system engine is
 *                                                                          always the last resort (never silent)
 *   "asr": { "online": { "twi": [], "gh_en": [], "auto": [] } }            online ids per language; empty ⇒ on-device
 *
 * Known ids: "system" (the phone's TTS engine) plus every enabled client in `providers.speech.clients`
 * (assets/config/language.json) — the language layer ([com.tekyerema.language.LanguageLayer]) builds those from config
 * and local.properties, e.g. the UG HCI Lab ASR/TTS/MT API. An id with no client is skipped and logged, never guessed.
 * Translation directions route the same way: `providers.speech.mt.enToTwi` / `twiToEn`.
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
