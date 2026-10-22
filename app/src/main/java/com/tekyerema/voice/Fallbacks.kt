package com.tekyerema.voice

import android.util.Log
import kotlinx.coroutines.async

/**
 * Provider-agnostic voice plumbing: composites that try providers in order. [SpeechProviders] builds them from
 * config (B-18), so a second vendor per role — the UG HCI Lab ASR/TTS API, then the on-device engine — is a
 * config change, not a code change.
 */

/**
 * The English voice Settings › Voice names (owner, 18 October 2026: Ghanaian as the fourth accent beside British,
 * Nigerian and American). Automatic or Ghanaian: our own on-device voice, the phone's engine behind it so a failed
 * model is never silence. British, Nigerian or American: the phone's engine alone — before this, a bundled own voice
 * spoke first whatever accent was picked.
 */
class AccentTts(
    private val own: TtsProvider,
    private val system: TtsProvider,
    private val ownFirst: () -> Boolean = { TtsAccent.ownVoiceFirst() }
) : TtsProvider {
    private val both = FallbackTts(listOf(own, system))
    override val name = "accent(${own.name}|${system.name})"
    override suspend fun speak(text: String, language: String): Boolean =
        if (ownFirst()) both.speak(text, language) else system.speak(text, language)
    override fun stop() { own.stop(); system.stop() }
    override fun shutdown() { own.shutdown(); system.shutdown() }
}

/** A voice that fetches its audio before it plays it — so two can be asked at once and only the first to answer heard. */
interface PrefetchTts : TtsProvider {
    /** The clips for [text] (cached or fetched), never played; null when this voice can't say it now. */
    suspend fun fetch(text: String, language: String): List<ByteArray>?
    suspend fun play(clips: List<ByteArray>): Boolean
}

/**
 * Try TTS providers in order; the first that speaks wins. Degrades to the system engine (never silent).
 *
 * When the first two are network voices ([PrefetchTts]) the first gets [hedgeMs] (`providers.speech.ttsHedgeMs`, 6 s) to
 * fetch its audio; still fetching, the second is asked beside it and the first audio back is played. A line the first
 * voice already holds (a recorded lab clip, the cache) plays at once. On the A20e on 16 Oct 2026 the lab's voice timed out
 * at 25 s before Khaya spoke a line it fetched in ~4 s.
 */
class FallbackTts(private val chain: List<TtsProvider>, private val hedgeMs: () -> Long = { configuredHedgeMs() }) : TtsProvider, VoiceAvailability {
    override val name = "fallback(" + chain.joinToString(",") { it.name } + ")"
    /** The first reason any voice in the chain gives for being unusable (the network voice's: key, quota, link). */
    override fun whyUnavailable(): String? = chain.firstNotNullOfOrNull { (it as? VoiceAvailability)?.whyUnavailable() }
    /** The voice that said the last line, null when none did — for the log: "spoke Twi via fallback(lab, system)" named
     *  both, and read like the lab voice had failed when it had spoken (A20e, 9 October 2026). */
    @Volatile var lastVoice: String? = null
        private set
    override suspend fun speak(text: String, language: String): Boolean {
        val a = chain.getOrNull(0) as? PrefetchTts
        val b = chain.getOrNull(1) as? PrefetchTts
        val hedge = hedgeMs()
        val rest = if (a != null && b != null && hedge > 0) {
            raced(a, b, text, language, hedge)?.let { (who, clips) -> if (who.play(clips)) { lastVoice = who.name; return true } }
            chain.drop(2)
        } else chain
        for (p in rest) if (p.speak(text, language)) { lastVoice = p.name; return true }
        lastVoice = null
        return false
    }

    /** The first voice's audio, or the second's once [hedge] ms pass without it — whichever is back first. Null: neither. */
    private suspend fun raced(a: PrefetchTts, b: PrefetchTts, text: String, language: String, hedge: Long): Pair<PrefetchTts, List<ByteArray>>? =
        kotlinx.coroutines.coroutineScope {
            val first = async { fetchOrNull(a, text, language) }
            kotlinx.coroutines.withTimeoutOrNull(hedge) { first.join() }
            if (first.isCompleted) return@coroutineScope first.await()?.let { a to it } ?: fetchOrNull(b, text, language)?.let { b to it }
            Log.i("Tekyerema", "[TTS] ${a.name} still fetching after ${hedge}ms — asking ${b.name} too")
            val pending = mutableListOf(first to a, async { fetchOrNull(b, text, language) } to b)
            while (pending.isNotEmpty()) {
                val (entry, clips) = kotlinx.coroutines.selects.select<Pair<Pair<kotlinx.coroutines.Deferred<List<ByteArray>?>, PrefetchTts>, List<ByteArray>?>> {
                    pending.forEach { e -> e.first.onAwait { e to it } }
                }
                pending.remove(entry)
                if (clips != null) { pending.forEach { it.first.cancel() }; return@coroutineScope entry.second to clips }
            }
            null
        }

    private suspend fun fetchOrNull(p: PrefetchTts, text: String, language: String): List<ByteArray>? =
        try { p.fetch(text, language) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { Log.w("Tekyerema", "[TTS] ${p.name} failed → next: ${e.message}"); null }

    override fun stop() { chain.forEach { it.stop() } }
    override fun shutdown() { chain.forEach { it.shutdown() } }

    companion object {
        fun configuredHedgeMs(): Long =
            try { com.tekyerema.core.config.AppConfig.long("providers.speech.ttsHedgeMs", 6_000L) } catch (_: Throwable) { 6_000L }
    }
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

/**
 * A language's network voices with the phone's own voice for the SAME language (offline Twi, owner 20 Oct 2026: "the fallback
 * should be in the same language"). Offline: the phone's voice speaks at once (no waiting on dead links). Online: the network
 * chain first; if it cannot say the line, the phone's voice says it — before any other-language fallback in [network].
 */
class OfflineFirstTts(
    private val network: TtsProvider,
    private val local: TtsProvider,
    private val isOnline: () -> Boolean,
    /** The network chain without its last-resort voice (the system engine), so the phone's own voice comes before it. */
    private val networkOnly: TtsProvider = network
) : TtsProvider, VoiceAvailability {
    override val name = "offline-first(${local.name} | ${network.name})"
    @Volatile var lastVoice: String? = null
        private set
    override fun whyUnavailable(): String? = null
    /** Bumped by [stop]: a line cut short while a voice played it is over — never said again by the next voice (audit, 22 Oct 2026). */
    private val stops = java.util.concurrent.atomic.AtomicInteger()
    override suspend fun speak(text: String, language: String): Boolean {
        val gen = stops.get()
        if (!isOnline()) {
            if (local.speak(text, language)) { lastVoice = local.name; return true }
            if (stops.get() != gen) return true
            return network.speak(text, language).also { if (it) lastVoice = network.name }
        }
        if (networkOnly.speak(text, language)) { lastVoice = (networkOnly as? FallbackTts)?.lastVoice ?: networkOnly.name; return true }
        // A stopped clip comes back false (MediaAudioSink): a volume key at the PIN, hush, a new request. The phone's Twi voice used to
        // read the whole line again from the start — stopped counts as said, as SherpaTts reports it (audit, 22 Oct 2026).
        if (stops.get() != gen) return true
        if (local.speak(text, language)) { lastVoice = local.name; return true }
        if (stops.get() != gen) return true
        if (networkOnly !== network && network.speak(text, language)) { lastVoice = network.name; return true }
        return false
    }
    override fun stop() { stops.incrementAndGet(); network.stop(); local.stop(); if (networkOnly !== network) networkOnly.stop() }
    override fun shutdown() { network.shutdown(); local.shutdown(); if (networkOnly !== network) networkOnly.shutdown() }
}
