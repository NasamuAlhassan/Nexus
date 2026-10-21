package com.tekyerema

import com.tekyerema.language.SpaceAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SpaceAudioTest {
    private fun wav(samples: ShortArray, rate: Int = 16000): ByteArray {
        val b = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + samples.size * 2).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(samples.size * 2)
        samples.forEach { b.putShort(it) }
        return b.array()
    }

    @Test fun muLawMatchesG711() {
        assertEquals(0xFF.toByte(), SpaceAudio.muLaw(0))
        assertEquals(0x80.toByte(), SpaceAudio.muLaw(32767))
        assertEquals(0x00.toByte(), SpaceAudio.muLaw((-32768).toShort()))
    }

    @Test fun silenceIsCutAndTheWordsKept() {
        // 1 s silence, 0.5 s tone, 1 s silence at 16 kHz → about 0.5 s + 2 × 0.25 s kept, one byte a sample
        val s = ShortArray(40000) { i -> if (i in 16000 until 24000) (8000 * Math.sin(i / 5.0)).toInt().toShort() else 0 }
        val out = SpaceAudio.compact(wav(s))
        assertEquals("RIFF", String(out, 0, 4))
        assertEquals(7, ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).getShort(20).toInt())   // μ-law
        val data = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).getInt(40)
        assertTrue("kept $data samples", data in 15000..17000)
        assertTrue(out.size < wav(s).size / 4)
    }

    @Test fun notPcmIsSentAsIs() {
        val junk = "not a wav at all, just bytes".toByteArray()
        assertTrue(SpaceAudio.compact(junk) === junk)
    }
}
