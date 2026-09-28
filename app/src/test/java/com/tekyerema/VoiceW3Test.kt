package com.tekyerema

import com.tekyerema.agent.FastOutcome
import com.tekyerema.core.config.AppConfig
import com.tekyerema.voice.Confirmation
import com.tekyerema.voice.SpeechChunks
import com.tekyerema.voice.Transcript
import com.tekyerema.voice.TurnDetector
import com.tekyerema.voice.VadStrategy
import com.tekyerema.voice.Verbalize
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** W3 voice robustness: B-02, B-03, B-06, B-09, B-17, B-20, K-03. */
class VoiceW3Test {
    @After fun reset() = AppConfig.loadFrom(emptyList())

    // ---- B-20 / B-17 ----
    @Test fun money_codes_times_and_links_are_spoken_naturally() {
        assertEquals("You have five cedis fifty pesewas.", Verbalize.apply("You have GH₵ 5.50."))
        assertEquals("ten pesewas", Verbalize.apply("GHS 0.10"))
        assertEquals("one cedi", Verbalize.apply("1 cedi"))
        assertEquals("one thousand five hundred cedis", Verbalize.apply("1,500 cedis"))
        assertEquals("Dial star one seven zero hash", Verbalize.apply("Dial *170#"))
        assertEquals("It's seven oh five", Verbalize.apply("It's 07:05"))
        assertEquals("at fourteen thirty", Verbalize.apply("at 14:30"))
        assertEquals("fifty percent", Verbalize.apply("50%"))
        assertEquals("two point five", Verbalize.apply("2.5"))
        assertEquals("See a link for details", Verbalize.apply("See https://example.com/x?y=1 for details"))
        assertEquals("Done", Verbalize.apply("**Done** ✅"))
        assertEquals("twenty eight September two thousand twenty six", Verbalize.apply("28/09/2026"))
        assertEquals("Open Momo", Verbalize.apply("Open MoMo"))                        // lexicon
        assertEquals("Call zero five five two zero nine three six nine zero", Verbalize.apply("Call 0552093690"))
        AppConfig.loadFrom(listOf("c" to """{"speech":{"tts":{"lexicon":{"Kwame":"Kwah-meh"}}}}"""))
        assertEquals("Call Kwah-meh", Verbalize.apply("Call Kwame"))
    }

    // ---- B-06 ----
    @Test fun long_replies_are_chunked_at_sentences() {
        assertEquals(listOf("Short."), SpeechChunks.split("Short."))
        val long = "First sentence here is fine. " + "Second one is a little longer than the first. ".repeat(4) + "End."
        val chunks = SpeechChunks.split(long, maxChars = 80)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 80 })
        assertEquals(long.replace(Regex("\\s+"), " ").trim(), chunks.joinToString(" "))
        val oneHuge = "word ".repeat(60).trim()
        assertTrue(SpeechChunks.split(oneHuge, 80).all { it.length <= 80 })
    }

    // ---- B-02 / B-09 ----
    @Test fun unfinished_requests_and_hold_requests_are_recognised() {
        assertTrue(TurnDetector.looksUnfinished("Call my"))
        assertTrue(TurnDetector.looksUnfinished("send a message to"))
        assertTrue(TurnDetector.looksUnfinished("read my messages and,"))
        for (done in listOf("call her", "turn the torch on", "read my message", "who is that", "open WhatsApp", "call"))
            assertFalse(done, TurnDetector.looksUnfinished(done))
        assertTrue(TurnDetector.isHold("Wait."))
        assertTrue(TurnDetector.isHold("hold on please"))
        assertTrue(TurnDetector.isHold("Twɛn kakra"))
        assertFalse(TurnDetector.isHold("wait for the bus"))
        val joined = TurnDetector.join(Transcript("call my", "call my", "gh_en"), Transcript("mother", "mother", "gh_en"))
        assertEquals("call my mother", joined.canonical)
    }

    @Test fun wait_keeps_a_confirmation_pending_but_no_wait_is_still_no() {
        assertEquals(Confirmation.Answer.HOLD, Confirmation.interpret("wait"))
        assertEquals(Confirmation.Answer.HOLD, Confirmation.interpret("hold on"))
        assertEquals(Confirmation.Answer.HOLD, Confirmation.interpret("give me a moment"))
        assertEquals(Confirmation.Answer.NO, Confirmation.interpret("no wait"))
        assertEquals(Confirmation.Answer.UNCLEAR, Confirmation.interpret("yes wait"))
        assertEquals(Confirmation.Answer.YES, Confirmation.interpret("yes"))
        assertFalse(Confirmation.looksLikeRevision("wait"))
    }

    // ---- B-03 ----
    @Test fun vad_strategies_have_sane_defaults_and_config_overrides() {
        assertTrue(VadStrategy.PATIENT.hangoverMs > VadStrategy.NORMAL.hangoverMs)
        assertTrue(VadStrategy.CONTINUE.noSpeechMs < VadStrategy.NORMAL.noSpeechMs)
        assertEquals("normal", VadStrategy.default().name)
        AppConfig.loadFrom(listOf("c" to """{"speech":{"vad":{"strategy":"patient","strategies":{"patient":{"hangoverMs":1500,"noSpeechMs":99999}}}}}"""))
        val p = VadStrategy.default()
        assertEquals("patient", p.name); assertEquals(1500, p.hangoverMs); assertEquals(30_000, p.noSpeechMs)   // clamped
    }

    // ---- K-03 ----
    @Test fun fast_path_failure_lines_are_not_ok() {
        assertTrue(FastOutcome("VOLUME_UP", "Volume up.").ok)
        assertFalse(FastOutcome("FLASHLIGHT", "Sorry, I couldn't switch the flashlight on.").ok)
        assertFalse(FastOutcome("FLASHLIGHT", "This phone doesn't have a flashlight I can reach.").ok)
        assertFalse(FastOutcome("BATTERY", "I couldn't read the battery level right now.").ok)
    }
}
