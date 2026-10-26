package com.tekyerema

import com.tekyerema.voice.OnDeviceVoice
import com.tekyerema.voice.SpokenText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's own on-device voice (voice/SherpaTts.kt), the parts that need no phone: reading `voice.json`, this voice's
 * own pronunciations, and cutting a reply into pieces that start speaking quickly.
 */
class OnDeviceVoiceTest {
    private val json = """{"type": "vits-piper", "label": "en_GH-anchorf1", "model": "en_GH-anchorf1-medium.onnx",
        "tokens": "tokens.txt", "dataDir": "espeak-ng-data", "threads": 2, "lengthScale": 1.05,
        "say": {"Tekyerema": "tetchy rehma", "MTN": "em tee en", "mobile money": "mobile munny", "money": "munny"}}"""

    @Test fun `a voice json is read with its files and its own pronunciations`() {
        val s = OnDeviceVoice.parse(json)
        assertNotNull(s)
        assertEquals("en_GH-anchorf1", s!!.label)
        assertEquals("en_GH-anchorf1-medium.onnx", s.model)
        assertEquals("espeak-ng-data", s.dataDir)
        assertEquals(2, s.threads)
        assertEquals(1.05f, s.lengthScale, 0.001f)
        assertEquals("em tee en", s.say["mtn"])          // keys are matched in any case
    }

    @Test fun `an unknown model type or a missing model file is not a voice`() {
        assertNull(OnDeviceVoice.parse("""{"type": "kokoro", "model": "m.onnx"}"""))
        assertNull(OnDeviceVoice.parse("""{"type": "vits-piper", "tokens": "tokens.txt"}"""))
        assertNull(OnDeviceVoice.parse("not json"))
    }

    @Test fun `our own words are respelled for this voice - whole words, any case, longest first`() {
        val say = OnDeviceVoice.parse(json)!!.say
        assertEquals("Welcome to tetchy rehma.", OnDeviceVoice.say("Welcome to Tekyerema.", say))
        assertEquals("em tee en said: enter your PIN", OnDeviceVoice.say("MTN said: enter your PIN", say))
        assertEquals("Your mobile munny balance", OnDeviceVoice.say("Your Mobile Money balance", say))   // not "mobile munny" twice over
        assertEquals("the munny is sent", OnDeviceVoice.say("the money is sent", say))
        // inside another word nothing changes
        assertEquals("moneyed MTNs", OnDeviceVoice.say("moneyed MTNs", say))
        assertEquals("no change", OnDeviceVoice.say("no change", emptyMap()))
    }

    @Test fun `a long reply opens with a short piece so it starts speaking sooner`() {
        val text = "Send ten pesewas to Prince Melanin, on zero five five two zero nine three six nine zero? Say yes to send it, or no to stop."
        val pieces = OnDeviceVoice.plan(text, maxChars = 140, firstChars = 60)
        assertTrue(pieces.size >= 2)
        assertEquals("Send ten pesewas to Prince Melanin,", pieces[0].text)
        assertEquals(text, pieces.mapNotNull { it.text }.joinToString(" "))      // nothing lost, nothing reordered
    }

    @Test fun `a short reply is one piece`() {
        assertEquals(listOf(OnDeviceVoice.Piece("It is ten past four.")), OnDeviceVoice.plan("It is ten past four."))
        // no early clause break: the sentence is not cut in an odd place
        val one = "Your airtime balance is six pesewas and you also have about eleven gigabytes"
        assertEquals(listOf(one), OnDeviceVoice.opening(one, 60))
    }

    @Test fun `a reading's pause marks become silences between the pieces`() {
        val text = "From Ama, on WhatsApp." + SpokenText.PAUSE + "I am on my way." + SpokenText.BREAK + "From Kofi." + SpokenText.PAUSE + "Call me."
        val pieces = OnDeviceVoice.plan(text, pauseMs = 450, breakMs = 900)
        assertEquals(listOf("From Ama, on WhatsApp.", null, "I am on my way.", null, "From Kofi.", null, "Call me."), pieces.map { it.text })
        assertEquals(listOf(450L, 900L, 450L), pieces.filter { it.text == null }.map { it.silenceMs })
    }

    @Test fun `model output becomes 16-bit audio without wrapping round at the limits`() {
        val pcm = OnDeviceVoice.pcm16(floatArrayOf(0f, 0.5f, -0.5f, 1f, -1f, 1.7f, -3f))
        assertEquals(0, pcm[0].toInt())
        assertEquals(16383, pcm[1].toInt())
        assertEquals(-16383, pcm[2].toInt())
        assertEquals(32767, pcm[3].toInt())
        assertEquals(-32767, pcm[4].toInt())
        assertEquals(32767, pcm[5].toInt())          // louder than full scale is clipped, not wrapped to a negative click
        assertEquals(-32767, pcm[6].toInt())
    }

    @Test fun `a reply is read clause by clause with a breath after each, short pieces joined to their neighbour`() {
        val p = OnDeviceVoice.breathe("You have a message from My wife. It says: please buy bread on your way home, and call me. Okay.")
        val spoken = p.mapNotNull { it.text }
        assertEquals(listOf("You have a message from My wife.", "It says: please buy bread on your way home,", "and call me. Okay."), spoken)
        // a breath (silence) sits between the spoken pieces, longer after a full stop than after a comma
        assertEquals(listOf(550L, 280L), p.filter { it.text == null }.map { it.silenceMs })
        // the owner can scale every breath at run time (speech.tts.onDevice.breathScale)
        assertEquals(listOf(275L, 140L), OnDeviceVoice.breathe("You have a message from My wife. It says: please buy bread on your way home, and call me. Okay.", scale = 0.5)
            .filter { it.text == null }.map { it.silenceMs })
    }

    @Test fun `a bare short line is never cut up`() {
        assertEquals(listOf("Cancelled. Nothing was sent."), OnDeviceVoice.breathe("Cancelled. Nothing was sent.").mapNotNull { it.text })
        assertEquals(listOf("Say your answer."), OnDeviceVoice.breathe("Say your answer.").mapNotNull { it.text })
    }

    @Test fun `plan with breaths keeps the old way when breaths are off`() {
        val t = "You have three new messages. The first is from Kofi Mensah, the second is from Ama."
        assertTrue(OnDeviceVoice.plan(t, breaths = false).count { it.text != null } <= 2)   // the opening clause, then the rest
        assertTrue(OnDeviceVoice.plan(t, breaths = true).count { it.text != null } >= 3)
    }

    @Test fun `voice json carries this voice's own speed`() {
        assertEquals(0.7f, OnDeviceVoice.parse("""{"type":"vits-piper","model":"m.onnx","speed":0.7}""")!!.speed, 0.0001f)
        assertEquals(1f, OnDeviceVoice.parse("""{"type":"vits-piper","model":"m.onnx"}""")!!.speed, 0.0001f)
    }

    @Test fun `a long text keeps its breath between chunks as well as inside them`() {
        val text = "Dr. Mensah, speaking in Accra on Tuesday morning, urged the public to wear masks in crowded places; " +
            "however, many people ignored the call and went on as before, which worried the ministry."
        val p = OnDeviceVoice.plan(text, maxChars = 100, firstChars = 60, breaths = true)
        for (i in 0 until p.lastIndex) assertTrue("two spoken pieces ran together at $i: $p", p[i].text == null || p[i + 1].text == null)
    }
}
