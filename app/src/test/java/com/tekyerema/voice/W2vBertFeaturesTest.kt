package com.tekyerema.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The phone's w2v-BERT features equal transformers' SeamlessM4TFeatureExtractor (fixture: test resources w2vbert/). */
class W2vBertFeaturesTest {
    private fun res(name: String) = javaClass.classLoader!!.getResourceAsStream("w2vbert/$name")!!.readBytes()

    private fun samples(): FloatArray {
        val b = res("clip.wav")
        val data = ByteBuffer.wrap(b, 44, b.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(data.remaining()) { data.get(it) / 32768f }
    }

    @Test fun matchesTheTransformersFeatureExtractor() {
        val expected = ByteBuffer.wrap(res("features.f32")).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val got = W2vBertFeatures.compute(samples())
        assertEquals(99, got.size)
        assertEquals(160, got[0].size)
        var worst = 0f
        for (r in got.indices) for (c in 0 until 160) worst = maxOf(worst, kotlin.math.abs(got[r][c] - expected.get(r * 160 + c)))
        assertTrue("largest difference $worst", worst < 2e-3f)
    }

    @Test fun tooShortIsEmpty() {
        assertEquals(0, W2vBertFeatures.compute(FloatArray(300)).size)
    }
}
