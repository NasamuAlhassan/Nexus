package com.tekyerema.voice

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Provider-agnostic voice plumbing: an on-disk TTS cache and the fallback composites that try providers in
 * order. Extracted from the old AbenaVoice.kt when the Abena/Khaya integrations were removed (2026-09-27),
 * so the seams survive for the next provider (e.g. the UG HCI Lab ASR/TTS API).
 */

/** Persistent on-disk cache of synthesized WAVs, keyed by (text, voice, speed). Fixed strings are
 *  synthesized ONCE and reused forever, so confirmations/errors/prompts never re-hit a network TTS. */
object TtsCache {
    private fun dir(ctx: Context) = File(ctx.filesDir, "tts_cache").apply { mkdirs() }
    private fun key(text: String, voice: String, speed: Double): String {
        val md = MessageDigest.getInstance("SHA-256").digest("$voice|$speed|$text".toByteArray(Charsets.UTF_8))
        return md.joinToString("") { "%02x".format(it) }
    }
    fun get(ctx: Context, text: String, voice: String, speed: Double): ByteArray? {
        val f = File(dir(ctx), key(text, voice, speed) + ".wav")
        return if (f.exists() && f.length() > 44) f.readBytes() else null
    }
    fun put(ctx: Context, text: String, voice: String, speed: Double, wav: ByteArray) {
        try { File(dir(ctx), key(text, voice, speed) + ".wav").writeBytes(wav) } catch (_: Throwable) {}
    }
}

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
