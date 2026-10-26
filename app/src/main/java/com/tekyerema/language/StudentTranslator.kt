package com.tekyerema.language

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.channels.FileChannel

/**
 * Offline English -> Twi translation on the phone: the distilled student translator (models session export v2).
 * Two ONNX graphs run by ONNX Runtime 1.17.1: an encoder and a decoder with no key/value cache (the whole prefix goes in
 * on every step and only the last position's logits come back). The embedding lookup is NOT in the graphs: row i of
 * `table_q8.bin` is int8[1024] times the float32 at `scales_f32.bin`[i] (the sqrt(1024) scale is already in the rows).
 *
 * Folder [dir] holds encoder.onnx, decoder.onnx, table_q8.bin, scales_f32.bin; [tokenizer] is the BPE port.
 * Greedy decoding: prefix [2, 5 (twi_Latn)], argmax each step, stop at 2 or [maxNew] tokens.
 * Output keeps the [P1]/[A1]/[N1] tags only when the model did: callers must check and fall back ([tagsKept]).
 */
class StudentTranslator(dir: File, private val tokenizer: StudentTokenizer, threads: Int, prepacking: Boolean) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val encoder: OrtSession
    private val decoder: OrtSession
    private val table: java.nio.MappedByteBuffer
    private val tableChannel: FileChannel
    private val scales: FloatArray

    init {
        val t0 = SystemClock.uptimeMillis()
        fun opts() = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            // The same lean set-up as the Twi hearing model: no CPU arena, no memory pattern, so the peak stays near the files' size.
            setCPUArenaAllocator(false)
            setMemoryPatternOptimization(false)
            if (!prepacking) addConfigEntry("session.disable_prepacking", "1")
        }
        encoder = env.createSession(File(dir, "encoder.onnx").absolutePath, opts())
        decoder = env.createSession(File(dir, "decoder.onnx").absolutePath, opts())
        tableChannel = RandomAccessFile(File(dir, "table_q8.bin"), "r").channel
        table = tableChannel.map(FileChannel.MapMode.READ_ONLY, 0, tableChannel.size())
        val sb = ByteBuffer.wrap(File(dir, "scales_f32.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        scales = FloatArray(sb.remaining() / 4) { sb.getFloat() }
        Log.i("Tekyerema", "[MT] student ready (${threads} threads, prepacking=$prepacking, ${scales.size} rows) in ${SystemClock.uptimeMillis() - t0} ms")
    }

    /** Rows of the embedding table for [ids], as one [1, n, 1024] float buffer. */
    private fun embed(ids: List<Int>): FloatBuffer {
        val out = FloatBuffer.allocate(ids.size * DIM)
        val row = ByteArray(DIM)
        for (id in ids) {
            val at = if (id in scales.indices) id else StudentTokenizer.UNK
            table.position(at * DIM)
            table.get(row)
            val s = scales[at]
            for (k in 0 until DIM) out.put(row[k] * s)
        }
        out.rewind()
        return out
    }

    /** The Twi for [english], or null when it can't (empty input, or the decoder ran off). */
    fun translate(english: String): String? {
        val enc = tokenizer.encode(english).map { it.toInt() }
        if (enc.size <= 2) return null
        val l = enc.size.toLong()
        val maxNew = maxOf(64, minOf(160, 3 * enc.size + 16))
        OnnxTensor.createTensor(env, embed(enc), longArrayOf(1, l, DIM.toLong())).use { embeds ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(enc.size) { 1L }), longArrayOf(1, l)).use { mask ->
                val hidden = encoder.run(mapOf("inputs_embeds" to embeds, "attention_mask" to mask))
                hidden.use { h ->
                    val hs = h[0] as OnnxTensor
                    val prefix = arrayListOf(StudentTokenizer.EOS, StudentTokenizer.TWI)
                    while (prefix.size - 2 < maxNew) {
                        val next = nextToken(prefix, hs, mask)
                        if (next == StudentTokenizer.EOS) break
                        prefix.add(next)
                    }
                    val text = tokenizer.decode(prefix.drop(2))
                    return text.ifBlank { null }
                }
            }
        }
    }

    private fun nextToken(prefix: List<Int>, hidden: OnnxTensor, mask: OnnxTensor): Int {
        OnnxTensor.createTensor(env, embed(prefix), longArrayOf(1, prefix.size.toLong(), DIM.toLong())).use { dec ->
            decoder.run(mapOf("decoder_inputs_embeds" to dec, "encoder_hidden_states" to hidden, "encoder_attention_mask" to mask)).use { out ->
                val logits = (out[0] as OnnxTensor).floatBuffer
                var best = 0
                var bv = Float.NEGATIVE_INFINITY
                for (i in 0 until logits.remaining()) { val v = logits.get(i); if (v > bv) { bv = v; best = i } }
                return best
            }
        }
    }

    override fun close() { runCatching { encoder.close() }; runCatching { decoder.close() }; runCatching { tableChannel.close() } }

    companion object {
        const val DIM = 1024

        /** True when every [P1]/[A1]/[N1]/[Q1] tag in [source] appears, once each, in [translated]. */
        fun tagsKept(source: String, translated: String): Boolean {
            val tag = Regex("\\[[PANQ]\\d+]")
            return tag.findAll(source).map { it.value }.sorted().toList() == tag.findAll(translated).map { it.value }.sorted().toList()
        }
    }
}
