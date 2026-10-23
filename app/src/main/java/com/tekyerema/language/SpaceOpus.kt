package com.tekyerema.language

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.nio.ByteOrder

/**
 * The words for our language Space as Ogg/Opus — about a fifth of the μ-law bytes (SpaceAudio), heard as well or better.
 * Measured against the live Space on 16 of the owner's Twi money commands (app session, 23 Oct 2026): μ-law 19.4 KB, 2.8 %
 * character errors, upload 4.1 s (median); Opus 24 kbps 3.8 KB, 2.3 %, 1.7 s; Opus 16 kbps 2.6 KB but 4.7 % ("cedi" heard
 * "fidi"), so 24 kbps. The Space reads it with the same soundfile call it reads WAV with. The phone's own encoder and Ogg
 * writer (Android 10+); null on an older phone or any failure, and the caller sends μ-law as before.
 */
object SpaceOpus {
    private const val TAG = "Tekyerema"
    /** Opus takes these input rates only. */
    private val RATES = setOf(8_000, 12_000, 16_000, 24_000, 48_000)

    fun encode(wav: ByteArray, bitrate: Int = 24_000, padMs: Int = 250, budgetMs: Long = 2_000L): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val (rate, s) = SpaceAudio.parse(wav) ?: return null
        if (s.isEmpty() || rate !in RATES) return null
        val (from, to) = SpaceAudio.speechSpan(s, rate, padMs)
        // The same level as the μ-law path (~70 % of full scale, at most 8×): a quiet command reaches the model at a normal level.
        var peak = 1
        for (i in from until to) peak = maxOf(peak, kotlin.math.abs(s[i].toInt()))
        val gain = minOf(8.0, 0.7 * 32767 / peak)
        val t0 = SystemClock.elapsedRealtime()
        val out = File.createTempFile("asr", ".ogg")
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, rate, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).also {
                it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); it.start()
            }
            val mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG).also { muxer = it }
            val info = MediaCodec.BufferInfo()
            var track = -1
            var pos = from
            var inputDone = false
            while (true) {
                if (SystemClock.elapsedRealtime() - t0 > budgetMs) { Log.w(TAG, "[ASR] opus: over ${budgetMs} ms — μ-law instead"); return null }
                if (!inputDone) {
                    val ix = codec.dequeueInputBuffer(2_000)
                    if (ix >= 0) {
                        val buf = codec.getInputBuffer(ix)!!.apply { clear(); order(ByteOrder.nativeOrder()) }
                        val n = minOf(buf.remaining() / 2, to - pos)
                        val ptsUs = (pos - from) * 1_000_000L / rate
                        if (n <= 0) { codec.queueInputBuffer(ix, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else {
                            for (i in pos until pos + n) buf.putShort((s[i] * gain).toInt().coerceIn(-32768, 32767).toShort())
                            codec.queueInputBuffer(ix, 0, n * 2, ptsUs, 0)
                            pos += n
                        }
                    }
                }
                // Every packet that is ready, not one per pass: a 20-ms Opus frame per packet is ~200 packets for 4 s of speech, and
                // one per 10-ms wait took over 2 s on the emulator (23 Oct 2026). Wait only when nothing is ready.
                var done = false
                var wait = if (inputDone) 10_000L else 0L   // still feeding: never sit waiting for output
                while (true) {
                    val ox = codec.dequeueOutputBuffer(info, wait)
                    if (ox == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    wait = 0L
                    if (ox == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = mux.addTrack(codec.outputFormat); mux.start(); continue }
                    if (ox < 0) continue
                    val buf = codec.getOutputBuffer(ox)!!
                    // The codec's own header buffers are already in its output format (the muxer writes OpusHead / OpusTags).
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        mux.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(ox, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { done = true; break }
                }
                if (done) break
            }
            mux.stop(); mux.release(); muxer = null
            val bytes = out.readBytes()
            Log.i(TAG, "[ASR] opus: ${(to - from) * 1000L / rate} ms of speech → ${bytes.size / 1024} KB in ${SystemClock.elapsedRealtime() - t0} ms")
            return bytes.takeIf { it.size > 64 }
        } catch (t: Throwable) {
            Log.w(TAG, "[ASR] opus encode failed (${t.javaClass.simpleName}) — μ-law instead")
            return null
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { muxer?.release() } catch (_: Throwable) {}
            out.delete()
        }
    }
}
