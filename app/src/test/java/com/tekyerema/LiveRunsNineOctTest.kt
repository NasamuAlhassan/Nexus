package com.tekyerema

import com.tekyerema.language.EntityTags
import com.tekyerema.language.HttpSpeech
import com.tekyerema.language.TtsDiskCache
import com.tekyerema.language.TwiText
import com.tekyerema.money.UssdSpeech
import com.tekyerema.money.ussd.Boundary
import com.tekyerema.money.ussd.MenuModel
import com.tekyerema.money.ussd.TxContext
import com.tekyerema.money.ussd.TxIntent
import com.tekyerema.voice.FallbackTts
import com.tekyerema.voice.SpeechLint
import com.tekyerema.voice.SpokenText
import com.tekyerema.voice.TtsProvider
import com.tekyerema.voice.Verbalize
import com.tekyerema.voice.understand.TwiSpeechText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner's Twi balance check on the A20e, 9 October 2026 ("mewɔ sika sɛn"): the walk was right, but the PIN line went
 * through the translator and the lab (16 s — it played after the PIN was entered and the balance read), and the balance
 * itself was read in English by the phone's voice.
 */
class LiveRunsNineOctTest {
    private val pin = MenuModel.read("Fee is GHS 0.00. Enter MM PIN\nCancel", hasInput = true, masked = false)
    private val balance = TxContext(TxIntent.CHECK_BALANCE, "mtn", confirmedUpFront = true)

    @Test fun `a Twi balance check hands over with the fixed Twi PIN line`() {
        assertEquals(MenuModel.Kind.PIN, pin.kind)
        val v = Boundary.decide(balance, pin, twi = true) as Boundary.Verdict.HandOff
        assertEquals(Boundary.TWI_BALANCE_PIN, v.line)
        // English conversations are unchanged
        val en = (Boundary.decide(balance, pin, twi = false) as Boundary.Verdict.HandOff).line
        assertTrue(en, en.startsWith("Enter your PIN now, and I'll read your Mobile Money balance"))
    }

    @Test fun `the Twi PIN line reaches the lab voice unchanged and its recorded clip is in the app`() {
        // the turn's path: brief → SpeechLint → Verbalize → RoutingTts (TwiSpeechText) → the lab voice's key
        val spoken = Verbalize.apply(SpeechLint.apply(SpeechLint.firstSentence(Boundary.TWI_BALANCE_PIN)))
        assertEquals(Boundary.TWI_BALANCE_PIN, spoken)
        assertEquals(spoken, TwiSpeechText.prepare(spoken))
        val keys = HttpSpeech.parts(TwiText.canonical(SpokenText.plain(TwiText.canonical(EntityTags.display(spoken)))).trim(), 250)
            .map { "twi|$it" }
        assertEquals(1, keys.size)
        val dir = listOf(java.io.File("src/main/assets/voice/twi_lab"), java.io.File("app/src/main/assets/voice/twi_lab"))
            .first { it.isDirectory }
        assertTrue("no lab clip for the line — run campaign/twi_lab_clips.py", java.io.File(dir, TtsDiskCache.name(keys[0]) + ".wav").isFile)
    }

    @Test fun `the balance figure is found in the network's answer, the available one first`() {
        assertEquals("115.50", UssdSpeech.momoBalance("Current Balance: GHS 120.00. Available Balance: GHS 115.50. "))
        assertEquals("1234.00", UssdSpeech.momoBalance("Your MoMo balance is GHS 1,234.00"))
        assertEquals("0.00", UssdSpeech.momoBalance("Current Balance GHS 0.00 Available Balance GHS 0.00"))
        assertEquals("12.50", UssdSpeech.momoBalance("Balance: GHS12.50"))
        assertNull(UssdSpeech.momoBalance("Wrong PIN. You have 2 attempts left."))
        assertNull(UssdSpeech.momoBalance("Balance enquiry failed. Try again in 5 minutes."))
    }

    @Test fun `the balance is one Twi sentence with the amount in Twi words`() {
        assertEquals("MTN se: wo Momo mu sika a aka yɛ sidi dumienu ne pesewa aduonum.",
            UssdSpeech.twiBalance("Current Balance: GHS 12.50 Available Balance: GHS 12.50", "MTN"))
        assertEquals("MTN se: wo Momo mu sika a aka yɛ sidi sero.", UssdSpeech.twiBalance("Available Balance: GHS 0.00", "MTN"))
        val big = UssdSpeech.twiBalance("Available Balance: GHS 1,234.00", "MTN")!!
        assertFalse(big, big.any { it.isDigit() })
        // no balance in it: read as the network wrote it
        assertNull(UssdSpeech.twiBalance("Wrong PIN. You have 2 attempts left.", "MTN"))
    }

    @Test fun `heard nothing in a Twi conversation - the recorded Twi line, never the translator`() {
        assertEquals(com.tekyerema.voice.Closers.TWI_NOT_HEARD, com.tekyerema.voice.Closers.noInput(1, twi = true))
        assertNull(com.tekyerema.voice.Closers.noInput(2, twi = true))
        assertEquals("I didn't hear anything. Tap and try again.", com.tekyerema.voice.Closers.noInput(1))
        // said directly (no Verbalize): the lab voice's key is the line itself, and its clip ships in the app
        val key = "twi|" + TwiText.canonical(com.tekyerema.voice.Closers.TWI_NOT_HEARD)
        val dir = listOf(java.io.File("src/main/assets/voice/twi_lab"), java.io.File("app/src/main/assets/voice/twi_lab"))
            .first { it.isDirectory }
        assertTrue(java.io.File(dir, TtsDiskCache.name(key) + ".wav").isFile)
    }

    @Test fun `the log names the voice that actually spoke`() = runBlocking {
        class Voice(override val name: String, val works: Boolean) : TtsProvider {
            override suspend fun speak(text: String, language: String) = works
            override fun stop() {}
            override fun shutdown() {}
        }
        val chain = FallbackTts(listOf(Voice("http:hcilab_tts", true), Voice("system", true)))
        assertTrue(chain.speak("Yoo.", "twi"))
        assertEquals("http:hcilab_tts", chain.lastVoice)
        val failing = FallbackTts(listOf(Voice("http:hcilab_tts", false), Voice("system", true)))
        assertTrue(failing.speak("Yoo.", "twi"))
        assertEquals("system", failing.lastVoice)
        val silent = FallbackTts(listOf(Voice("http:hcilab_tts", false)))
        assertFalse(silent.speak("Yoo.", "twi"))
        assertNull(silent.lastVoice)
    }
}
