package com.tekyerema

import com.tekyerema.voice.Lang
import com.tekyerema.voice.OnDeviceVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner, 18 October 2026: a Kusaal voice on the phone too — the approved Space voice converted for sherpa-onnx. */
class OnDeviceKusaalVoiceTest {
    private val kus = """{"type": "vits-mms", "label": "kus-v3", "language": "kus", "model": "kus.onnx", "tokens": "tokens.txt",
        "threads": 2, "lengthScale": 1.22, "noiseScale": 0.42, "noiseScaleW": 0.6}"""
    private val piper = """{"type": "vits-piper", "label": "en_GH-f5r0", "model": "en_GH-f5r0-medium.onnx", "tokens": "tokens.txt",
        "dataDir": "espeak-ng-data"}"""

    @Test fun anMmsVoiceIsReadWithItsLanguageAndNoEspeak() {
        val s = OnDeviceVoice.parse(kus)!!
        assertEquals(OnDeviceVoice.VITS_MMS, s.type)
        assertEquals("kus", s.language)
        assertNull(s.dataDir)
        assertEquals(1.22f, s.lengthScale, 1e-6f)
    }

    @Test fun anMmsVoiceWithoutALanguageIsRefused() {
        assertNull(OnDeviceVoice.parse(kus.replace("\"language\": \"kus\", ", "")))
    }

    @Test fun eachVoiceSpeaksOnlyItsOwnLanguage() {
        val k = OnDeviceVoice.parse(kus)!!
        val e = OnDeviceVoice.parse(piper)!!
        assertTrue(OnDeviceVoice.speaks(k, "kus"))
        assertFalse(OnDeviceVoice.speaks(k, Lang.GH_EN))
        assertFalse(OnDeviceVoice.speaks(k, Lang.TWI))
        assertTrue(OnDeviceVoice.speaks(e, Lang.GH_EN))
        assertFalse(OnDeviceVoice.speaks(e, Lang.TWI))
    }

    @Test fun kusaalLettersReachTheModelInLowerCase() {
        val k = OnDeviceVoice.parse(kus)!!
        assertEquals("ɛ ɔ ʋ bɛ", OnDeviceVoice.forModel(k, "Ɛ Ɔ Ʋ BƐ"))
        assertEquals("Hello Accra", OnDeviceVoice.forModel(OnDeviceVoice.parse(piper)!!, "Hello Accra"))
    }
}
