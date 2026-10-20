package com.tekyerema.voice

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The phone's features against the Kaggle export's parity kit (build/twi_offline/hub/asr/parity: clipN.wav and the
 * clipN.input_features.npy the ONNX model was checked with). Skipped when the kit is not on this machine.
 */
class W2vBertParityKitTest {
    private val kit = listOf(File("build/twi_offline/hub/asr/parity"), File("../build/twi_offline/hub/asr/parity")).firstOrNull { it.isDirectory }

    private fun wav(f: File): FloatArray {
        val b = f.readBytes()
        var p = 12
        while (p + 8 <= b.size) {
            val id = String(b, p, 4); val len = ByteBuffer.wrap(b, p + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") {
                val s = ByteBuffer.wrap(b, p + 8, len).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                return FloatArray(s.remaining()) { s.get(it) / 32768f }
            }
            p += 8 + len + (len and 1)
        }
        error("no data chunk")
    }

    private fun npy(f: File): Pair<IntArray, FloatArray> {
        val b = f.readBytes()
        val hlen = ByteBuffer.wrap(b, 8, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        val header = String(b, 10, hlen)
        val shape = Regex("\\(([^)]*)\\)").find(header)!!.groupValues[1].split(',').mapNotNull { it.trim().toIntOrNull() }.toIntArray()
        val fb = ByteBuffer.wrap(b, 10 + hlen, b.size - 10 - hlen).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return shape to FloatArray(fb.remaining()) { fb.get(it) }
    }

    @Test fun featuresMatchTheExportKit() {
        assumeTrue(kit != null)
        for (i in 0..2) {
            val (shape, want) = npy(File(kit, "clip$i.input_features.npy"))
            val got = W2vBertFeatures.compute(wav(File(kit, "clip$i.wav")))
            val rows = shape[shape.size - 2]
            assertTrue("clip$i rows ${got.size} vs $rows", got.size == rows)
            var worst = 0f
            for (r in 0 until rows) for (c in 0 until 160) worst = maxOf(worst, kotlin.math.abs(got[r][c] - want[r * 160 + c]))
            println("clip$i: $rows rows, largest difference $worst")
            assertTrue("clip$i largest difference $worst", worst < 5e-3f)
        }
    }
}
