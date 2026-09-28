package com.tekyerema

import android.provider.CallLog
import com.tekyerema.tools.CallLogFormat
import com.tekyerema.tools.SmsFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

/** Pure helpers behind call_log / read_sms: what the user hears, and which texts are picked. Deterministic (UTC, fixed now). */
class PhoneToolsTest {
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")
    private val now = Instant.parse("2026-09-28T10:00:00Z").toEpochMilli()
    private val sec = 1_000L
    private val min = 60 * sec
    private val hour = 60 * min
    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()
    private fun ago(dateMs: Long) = CallLogFormat.ago(dateMs, now, utc)

    // ---- CallLogFormat.ago ----

    @Test fun agoJustNowUnderOneMinute() {
        assertEquals("just now", ago(now))
        assertEquals("just now", ago(now - 59 * sec))
        assertEquals("just now", ago(now + 5 * min))   // clock skew: a future stamp is never "in 5 minutes"
    }

    @Test fun agoMinutes() {
        assertEquals("1 minute ago", ago(now - min))
        assertEquals("10 minutes ago", ago(now - 10 * min))
        assertEquals("59 minutes ago", ago(now - 59 * min - 59 * sec))
    }

    @Test fun agoHours() {
        assertEquals("1 hour ago", ago(now - hour))
        assertEquals("2 hours ago", ago(now - 2 * hour - 30 * min))
        assertEquals("23 hours ago", ago(now - 23 * hour - 59 * min))
    }

    @Test fun agoYesterdayIsTheCalendarDayBefore() {
        assertEquals("yesterday", ago(now - 24 * hour))                   // 27th 10:00
        assertEquals("yesterday", ago(at("2026-09-27T00:00:01Z")))         // ~34 h ago, still the 27th
    }

    @Test fun agoDaysPastYesterday() {
        assertEquals("2 days ago", ago(at("2026-09-26T23:59:00Z")))       // only ~34 h ago, but two calendar days back
        assertEquals("5 days ago", ago(now - 5 * 24 * hour))
    }

    // ---- CallLogFormat.line / spokenNumber ----

    @Test fun missedCallWithName() {
        assertEquals("Missed call from Ama, 10 minutes ago",
            CallLogFormat.line("Ama", "0241234567", CallLog.Calls.MISSED_TYPE, now - 10 * min, now, utc))
    }

    @Test fun outgoingNumberOnlyIsGroupedForSpeech() {
        assertEquals("Call to 024 123 4567, yesterday",
            CallLogFormat.line(null, "0241234567", CallLog.Calls.OUTGOING_TYPE, now - 26 * hour, now, utc))
        assertEquals("Call from 024 123 4567, 3 hours ago",   // a blank cached name falls back to the number
            CallLogFormat.line("  ", "024 123 4567", CallLog.Calls.INCOMING_TYPE, now - 3 * hour, now, utc))
    }

    @Test fun spokenNumberShapes() {
        assertEquals("+233 241 234 567", CallLogFormat.spokenNumber("+233241234567"))
        assertEquals("100", CallLogFormat.spokenNumber("100"))
        assertEquals("MobileMoney", CallLogFormat.spokenNumber("MobileMoney"))
        assertEquals("a private number", CallLogFormat.spokenNumber("-1"))
        assertEquals("a private number", CallLogFormat.spokenNumber(""))
    }

    @Test fun contactMatchingByNameDigitsOrKnownNumber() {
        assertTrue(CallLogFormat.matchesContact("Ama Serwaa", "0241234567", "ama"))
        assertTrue(CallLogFormat.matchesContact(null, "+233241234567", "4567"))
        assertTrue(CallLogFormat.matchesContact(null, "+233241234567", "Ama", listOf("024 123 4567")))
        assertFalse(CallLogFormat.matchesContact(null, "0201112222", "Ama", listOf("024 123 4567")))
    }

    // ---- SmsFilter.select ----

    private val momo1 = SmsFilter.Sms("MobileMoney", "Payment received for GHS 50.00 from KOFI MENSAH", now - min)
    private val ama = SmsFilter.Sms("+233241234567", "Are you coming to church?", now - hour)
    private val mtn = SmsFilter.Sms("MTN", "Your data bundle expires today", now - 2 * hour)
    private val kojo = SmsFilter.Sms("0201112222", "Call me when you can", now - 3 * hour)
    private val momo2 = SmsFilter.Sms("MobileMoney", "Cash Out of GHS 20.00 completed", now - 24 * hour)
    private val inbox = listOf(momo1, ama, mtn, kojo, momo2)            // newest first, as the provider returns it
    private val contacts = mapOf("+233241234567" to "Ama Serwaa", "0201112222" to "Kojo")
    private val nameOf: (String) -> String? = { contacts[it] }

    @Test fun selectBySenderAddress() {
        assertEquals(listOf(momo1, momo2), SmsFilter.select(inbox, "mobilemoney", null, 10, nameOf))
        assertEquals(listOf(momo1, momo2), SmsFilter.select(inbox, "Mobile Money", null, 10, nameOf))   // ASR spacing
        assertEquals(listOf(mtn), SmsFilter.select(inbox, "mtn", null, 10, nameOf))
    }

    @Test fun selectByContactNameViaLookup() {
        assertEquals(listOf(ama), SmsFilter.select(inbox, "ama", null, 10, nameOf))
        assertEquals(emptyList<SmsFilter.Sms>(), SmsFilter.select(inbox, "ama", null, 10) { null })   // no contacts → no name match
    }

    @Test fun selectBySenderDigits() {
        assertEquals(listOf(kojo), SmsFilter.select(inbox, "2222", null, 10, nameOf))
        assertEquals(listOf(ama), SmsFilter.select(inbox, "0241234567", null, 10, nameOf))   // local form of +233…
    }

    @Test fun selectByContainsCaseInsensitive() {
        assertEquals(listOf(momo1, momo2), SmsFilter.select(inbox, null, "ghs", 10, nameOf))
        assertEquals(listOf(momo2), SmsFilter.select(inbox, "MobileMoney", "cash out", 10, nameOf))
    }

    @Test fun limitIsRespected() {
        assertEquals(listOf(momo1, ama), SmsFilter.select(inbox, null, null, 2, nameOf))
        assertEquals(listOf(momo1), SmsFilter.select(inbox, "MobileMoney", null, 1, nameOf))
        assertTrue(SmsFilter.select(inbox, null, null, 0, nameOf).isEmpty())
    }

    @Test fun mostRecentFirstIsPreserved() {
        assertEquals(inbox, SmsFilter.select(inbox, null, null, 10, nameOf))
        assertEquals(inbox, SmsFilter.select(inbox.reversed(), null, null, 10, nameOf))
    }

    // ---- SmsFilter.line ----

    @Test fun secretMessageIsNamedNotRead() {
        val otp = SmsFilter.Sms("Google", "G-[code hidden] is your Google verification code.", now - 2 * min, secret = true)
        assertEquals("From Google, 2 minutes ago: a one-time code message (code hidden)", SmsFilter.line("Google", otp, now, utc))
    }

    @Test fun longBodyIsFlattenedAndCapped() {
        val long = SmsFilter.Sms("MTN", "Line one\nline two " + "x".repeat(400), now - hour)
        val line = SmsFilter.line("MTN", long, now, utc)
        assertTrue(line.startsWith("From MTN, 1 hour ago: Line one line two x"))
        assertTrue(line.endsWith("…"))
        assertEquals(SmsFilter.MAX_BODY + 1, line.substringAfter(": ").length)
    }
}
