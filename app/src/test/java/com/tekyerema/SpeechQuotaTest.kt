package com.tekyerema

import com.tekyerema.language.HttpSpeech
import com.tekyerema.language.QuotaLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The speech-gateway quotas counted on the phone, so a spent quota never costs a call; and the request-size splitting. */
class SpeechQuotaTest {
    @Test fun the_ledger_stops_before_the_gateway_would_refuse() {
        var now = 1_790_000_000_000L
        val led = QuotaLedger(clock = { now })
        val l = QuotaLedger.Limits(perMinute = 5, perDay = 10, perWeek = 50, audioSecPerWeek = 1800)
        repeat(5) { assertNull(led.refusal("a", l, QuotaLedger.Units(audioSec = 3.0))); led.record("a", l, QuotaLedger.Units(audioSec = 3.0)) }
        assertTrue("5 a minute", led.refusal("a", l, QuotaLedger.Units())!!.contains("minute"))
        now += 61_000L
        repeat(5) { assertNull(led.refusal("a", l, QuotaLedger.Units())); led.record("a", l, QuotaLedger.Units()) }
        now += 61_000L
        assertTrue("10 a day", led.refusal("a", l, QuotaLedger.Units())!!.contains("today"))
        now += 24 * 3_600_000L
        assertNull("a new day", led.refusal("a", l, QuotaLedger.Units()))
        assertTrue("30 minutes of audio a week", led.refusal("a", l, QuotaLedger.Units(audioSec = 1_790.0))!!.contains("minutes"))
        led.exhaust("a", l, "day")
        assertTrue("the gateway said the day is spent", led.refusal("a", l, QuotaLedger.Units())!!.contains("today"))
    }

    @Test fun text_is_split_at_sentence_ends_within_the_limit() {
        val sentence = "How are you? " + "I am well. ".repeat(40)
        val parts = HttpSpeech.parts(sentence, 250)
        assertTrue(parts.all { it.length <= 250 })
        assertEquals(sentence, parts.joinToString(" ") + " ")
    }
}
