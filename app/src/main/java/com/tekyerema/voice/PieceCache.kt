package com.tekyerema.voice

import android.content.Context
import com.tekyerema.core.config.AppConfig
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Audio the phone's own voice already made, kept so a piece said again plays at once (28 Oct 2026, A20e: one warm Twi piece took
 * ~11 s to synthesise). The app's own lines repeat ("Cancelled. Nothing was sent.", the money and confirmation lines); a message the
 * user reads usually does not.
 *
 * Privacy: a piece goes to disk only the SECOND time it is said (one-off content such as a read message stays in memory only), never
 * during a secure step (the PIN), in the app's private files, capped at `speech.tts.onDevice.cacheMb` (40) with the oldest dropped.
 * The key covers the voice, its settings and the speed, so a changed voice never plays an old recording.
 */
object PieceCache {
    private const val DIR = "tts_cache"
    private val seen = object : LinkedHashMap<String, Int>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 2000
    }
    private val memory = object : LinkedHashMap<String, Pair<Int, ShortArray>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Int, ShortArray>>?) = size > 48
    }

    fun key(s: OnDeviceVoice.Spec, speed: Float, words: String): String {
        val raw = "${s.label}|${s.model}|${s.lengthScale}|${s.noiseScale}|${s.noiseScaleW}|$speed|$words"
        return MessageDigest.getInstance("SHA-1").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun enabled() = AppConfig.bool("speech.tts.onDevice.cache", true)

    /** (sample rate, 16-bit samples) for [words] in this voice, or null. */
    @Synchronized
    fun get(ctx: Context, s: OnDeviceVoice.Spec, speed: Float, words: String): Pair<Int, ShortArray>? {
        if (!enabled()) return null
        val k = key(s, speed, words)
        memory[k]?.let { return it }
        val f = File(File(ctx.filesDir, DIR), "$k.pcm")
        if (!f.exists()) return null
        return try {
            DataInputStream(f.inputStream().buffered()).use { inp ->
                val hz = inp.readInt()
                val n = inp.readInt()
                val pcm = ShortArray(n) { inp.readShort() }
                f.setLastModified(System.currentTimeMillis())
                (hz to pcm).also { memory[k] = it }
            }
        } catch (_: Throwable) { f.delete(); null }
    }

    @Synchronized
    fun put(ctx: Context, s: OnDeviceVoice.Spec, speed: Float, words: String, hz: Int, parts: List<ShortArray>) {
        if (!enabled() || parts.isEmpty()) return
        val k = key(s, speed, words)
        val pcm = ShortArray(parts.sumOf { it.size }).also { out -> var o = 0; for (p in parts) { p.copyInto(out, o); o += p.size } }
        memory[k] = hz to pcm
        val times = (seen[k] ?: 0) + 1
        seen[k] = times
        if (times < 2 || secure()) return
        try {
            val dir = File(ctx.filesDir, DIR).apply { mkdirs() }
            DataOutputStream(File(dir, "$k.pcm").outputStream().buffered()).use { out ->
                out.writeInt(hz); out.writeInt(pcm.size); for (v in pcm) out.writeShort(v.toInt())
            }
            prune(dir, AppConfig.long("speech.tts.onDevice.cacheMb", 40L) * 1024 * 1024)
        } catch (_: Throwable) {}
    }

    /** An app line made ahead of time (SherpaTts.prerender): kept on disk at once — it is the app's own words, never a message. */
    @Synchronized
    fun putFixed(ctx: Context, s: OnDeviceVoice.Spec, speed: Float, words: String, hz: Int, pcm: ShortArray) {
        if (!enabled() || pcm.isEmpty() || secure()) return
        val k = key(s, speed, words)
        seen[k] = 2
        put(ctx, s, speed, words, hz, listOf(pcm))
    }

    /** Everything the cache holds, gone (memory and disk). */
    @Synchronized
    fun clear(ctx: Context) {
        memory.clear(); seen.clear()
        try { File(ctx.filesDir, DIR).deleteRecursively() } catch (_: Throwable) {}
    }

    private fun secure(): Boolean = try { SecureStep.active() } catch (_: Throwable) { false }

    private fun prune(dir: File, maxBytes: Long) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) { if (total <= maxBytes) break; total -= f.length(); f.delete() }
    }
}
