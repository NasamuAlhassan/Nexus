package com.tekyerema

import com.tekyerema.core.config.AppConfig
import com.tekyerema.phonecare.AlertGate
import com.tekyerema.phonecare.AlertInbox
import com.tekyerema.phonecare.AlertPrivacy
import com.tekyerema.phonecare.BatteryAlerts
import com.tekyerema.phonecare.MissedCalls
import com.tekyerema.phonecare.MoneyAlerts
import com.tekyerema.phonecare.MoneyAlerts.Event
import com.tekyerema.phonecare.PhoneAlerts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phone care event alerts (phonecare/): the Mobile Money parser (incl. the "sent by mistake" fraud), missed calls from a
 * contact, low battery once per level, and the once / rate-limit / loudspeaker-privacy rules. All pure.
 */
class PhoneAlertsTest {
    @After fun reset() { AppConfig.loadFrom(emptyList()); PhoneAlerts.inbox.clear() }

    // ---------------------------------------------------------------- money received

    private val mtn = "Payment received for GHS 50.00 from KOFI MENSAH 0244123456 Current Balance: GHS 123.45 . " +
        "Available Balance: GHS 123.45. Reference: rent. Transaction ID: 12345678901. TRANSACTION FEE: 0.00"

    @Test fun mtn_payment_received_is_parsed_from_fields_only() {
        val e = MoneyAlerts.classify(mtn, trusted = true)
        assertEquals(Event.Received("50.00", "Kofi Mensah", "123.45"), e)
        e as Event.Received
        assertEquals("You've received 50 cedis from Kofi Mensah.", MoneyAlerts.receivedLine(e, withBalance = false))
        assertEquals("You've received 50 cedis from Kofi Mensah. Your balance is 123 cedis 45 pesewas.", MoneyAlerts.receivedLine(e, withBalance = true))
        // Never the transaction id, the reference or the payer's number.
        val line = MoneyAlerts.receivedLine(e, withBalance = true)
        for (bad in listOf("12345678901", "0244123456", "rent", "Reference")) assertFalse(bad, line.contains(bad))
    }

    @Test fun the_common_credit_phrasings_parse() {
        fun rx(t: String) = MoneyAlerts.classify(t, trusted = true) as Event.Received
        assertEquals(Event.Received("50.00", "Kofi Mensah", "200.00"),
            rx("You have received GHS 50.00 from KOFI MENSAH (0244123456). Your new balance is GHS 200.00."))
        assertEquals(Event.Received("100.00", "Agent Kwame Stores", "300.00"),
            rx("Cash In received for GHS 100.00 from AGENT KWAME STORES. Current Balance GHS 300.00"))
        assertEquals("Ama Serwaa", rx("You have received GHS20.00 from 233244123456 - AMA SERWAA. Ref: school fees").from)
        assertEquals(Event.Received("20.00", "Kofi Mensah", "45.50"),
            rx("You have received GHS 20.00 from 0201234567 KOFI MENSAH. Your Telecel Cash balance is GHS 45.50"))
        // Telecel / Vodafone Cash put the date and time after the payer: the name still ends there.
        assertEquals(Event.Received("20.00", "Kofi Mensah", "45.50"), rx("You have received GHS 20.00 from KOFI MENSAH 0201234567 " +
            "on 2024-01-15 at 10:30:45. Your Telecel Cash balance is GHS 45.50"))
        assertEquals("Kofi", rx("You've received GHS 20.00 from Kofi.").from)
        assertEquals(Event.Received("20.00", "Ama Serwaa", null), rx("You received GH₵20.00 from Ama Serwaa"))
        assertEquals(Event.Received("75.00", "Yaw", null), rx("GHS 75.00 has been credited to your wallet from YAW."))
        assertEquals(Event.Received("1,250.00", "Esi Owusu", null), rx("Your wallet has been credited with GH₵ 1,250.00 from Esi Owusu."))
        assertEquals("You've received 1250 cedis from Esi Owusu.", MoneyAlerts.receivedLine(rx("Your wallet has been credited with GH₵ 1,250.00 from Esi Owusu."), true))
        // A number only: the amount, and no name.
        val e = rx("You have received GHS 5.00 from 0244123456.")
        assertNull(e.from)
        assertEquals("You've received 5 cedis.", MoneyAlerts.receivedLine(e, false))
    }

    @Test fun debits_codes_and_chatter_are_not_money_received() {
        assertNull(MoneyAlerts.classify("Payment made for GHS 20.00 to KOFI MENSAH. Current Balance: GHS 100.00", trusted = true))
        assertNull(MoneyAlerts.classify("Your MoMo verification code is [code hidden]. Do not share it.", trusted = true))
        assertNull(MoneyAlerts.classify("Sorry, I sent that text by mistake", trusted = false))
        assertNull(MoneyAlerts.classify("", trusted = true))
        assertNull(MoneyAlerts.classify(null, trusted = true))
        // An outgoing payment's receipt, even from the MoMo app itself, is not money in.
        assertNull(MoneyAlerts.classify("Your payment of GHS 20.00 has been received by KFC.", trusted = true))
        assertNull(MoneyAlerts.classify("GHS 20.00 received.", trusted = true))
        // Airtime and bundles are not cash.
        assertNull(MoneyAlerts.classify("You have received GHS 5.00 airtime from MTN.", trusted = true))
        assertNull(MoneyAlerts.classify("You have received GHS 10 data bundle", trusted = true))
    }

    @Test fun a_credit_from_an_ordinary_number_is_the_fake_credit_trick() {
        val fake = "You have received GHS 500.00 from KOFI MENSAH. Current Balance: GHS 612.00"
        assertEquals(Event.UntrustedCredit, MoneyAlerts.classify(fake, trusted = false, fromNumber = true))
        assertEquals("A text from an ordinary number says you received money. Check your balance before you believe it.", MoneyAlerts.untrustedLine())
        // A bank or a remittance service (named, not a number) is simply not ours to announce — no false fraud alarm.
        assertNull(MoneyAlerts.classify("Your account has been credited with GHS 500.00 from ZEEPAY.", trusted = false, fromNumber = false))
        assertTrue(MoneyAlerts.looksLikeNumber("0244123456"))
        assertTrue(MoneyAlerts.looksLikeNumber("+233 24 412 3456"))
        assertFalse(MoneyAlerts.looksLikeNumber("MobileMoney"))
        assertFalse(MoneyAlerts.looksLikeNumber("GCB Bank"))
        assertFalse(MoneyAlerts.looksLikeNumber(null))
    }

    @Test fun the_real_sms_is_matched_to_its_notification() {
        val sms = "Payment received for GHS 50.00 from KOFI MENSAH 0244123456 Current Balance: GHS 123.45"
        assertTrue(MoneyAlerts.sameMessage(sms, "Payment received for GHS 50.00 from KOFI MENSAH 0244123456 Current…"))
        assertFalse(MoneyAlerts.sameMessage(sms, "Payment received for GHS 60.00 from AMA"))
        assertFalse("too little to go on", MoneyAlerts.sameMessage(sms, "Payment"))
        assertFalse(MoneyAlerts.sameMessage(null, sms))
    }

    @Test fun sent_by_mistake_becomes_a_warning_never_a_relay() {
        for (t in listOf(
            "Hello, I mistakenly sent GHS 200 to your MoMo number. Please send it back to 0244123456",
            "Please I sent money to your number by mistake, kindly send it back",
            "You have received GHS 300.00 from KOFI MENSAH. I transferred it in error, please refund the money",
            "Please do a reversal of the GHS 300 I sent you"
        )) assertEquals(t, Event.MistakeClaim, MoneyAlerts.classify(t, trusted = false))
        assertEquals("A message claims money was sent to you by mistake. Don't send anything back before checking your balance.",
            MoneyAlerts.mistakeLine())
        // Mobile Money's own reversal notice and its fraud advice are not the fraud script.
        assertNull(MoneyAlerts.classify("Your reversal request for transaction 12345678901 of GHS 50.00 has been processed.", trusted = true))
        assertNull(MoneyAlerts.classify("Beware of fraudsters who claim they sent money to you by mistake. Never send money back.", trusted = true))
        assertNull(MoneyAlerts.classify("The GHS 50.00 sent in error has been reversed.", trusted = true))
        // Friends talking about money, with no claim that money came to the user: no loudspeaker warning.
        assertNull(MoneyAlerts.classify("Please return my money", trusted = false))
        assertNull(MoneyAlerts.classify("pay my money back when you can", trusted = false))
    }

    @Test fun names_are_cleaned_and_never_a_sentence() {
        assertEquals("Kofi Mensah", MoneyAlerts.cleanName("KOFI MENSAH 0244123456"))
        assertEquals("Ama Serwaa", MoneyAlerts.cleanName("233244123456 - AMA SERWAA"))
        assertEquals("Kwame O'Neil", MoneyAlerts.cleanName("Kwame O'Neil"))
        assertNull(MoneyAlerts.cleanName("0244123456"))
        assertNull(MoneyAlerts.cleanName("please call this number now and send me the code you got"))
        assertNull(MoneyAlerts.cleanName("[code hidden]"))
        assertNull(MoneyAlerts.cleanName("KOFI 12"))
    }

    @Test fun cedis_are_said_the_way_people_say_them() {
        assertEquals("50 cedis", MoneyAlerts.spokenCedis("50.00"))
        assertEquals("1 cedi", MoneyAlerts.spokenCedis("1.00"))
        assertEquals("12 cedis 50 pesewas", MoneyAlerts.spokenCedis("12.50"))
        assertEquals("12 cedis 50 pesewas", MoneyAlerts.spokenCedis("12.5"))
        assertEquals("12 cedis 5 pesewas", MoneyAlerts.spokenCedis("12.05"))
        assertEquals("50 pesewas", MoneyAlerts.spokenCedis("0.50"))
        assertEquals("1250 cedis", MoneyAlerts.spokenCedis("1,250.00"))
        assertEquals("100 cedis", MoneyAlerts.spokenCedis("100"))
    }

    @Test fun trusted_senders_and_apps() {
        assertTrue(MoneyAlerts.isTrustedSender("MobileMoney"))
        assertTrue(MoneyAlerts.isTrustedSender("Mobile Money"))
        assertTrue(MoneyAlerts.isTrustedSender("MOBILE-MONEY"))
        assertFalse(MoneyAlerts.isTrustedSender("0244123456"))
        assertFalse(MoneyAlerts.isTrustedSender("Kofi"))
        assertFalse(MoneyAlerts.isTrustedSender(null))
        assertTrue(MoneyAlerts.isMoneyApp("com.mtn.momo.app"))
        assertFalse(MoneyAlerts.isMoneyApp("com.whatsapp"))
    }

    @Test fun shipped_config_and_extra_patterns_are_read() {
        val dir = listOf(java.io.File("src/main/assets/config"), java.io.File("app/src/main/assets/config")).first { it.isDirectory }
        AppConfig.loadFrom(listOf("config/alerts.json" to java.io.File(dir, "alerts.json").readText()))
        assertTrue("MobileMoney" in MoneyAlerts.senders())
        assertEquals(listOf(15, 5), BatteryAlerts.thresholds())
        assertTrue("com.samsung.android.dialer" in MissedCalls.packages())
        // A new operator's phrasing is added in config, not code.
        assertNull(MoneyAlerts.classify("Momo credit alert: 75.00 cedis in from ABENA", trusted = true))
        AppConfig.loadFrom(listOf("config/alerts.json" to
            """{"alerts":{"money":{"extraPatterns":["credit alert: (\\d+(?:\\.\\d{1,2})?) cedis in from (\\w+)", "(unbalanced"]}}}"""))
        assertEquals(Event.Received("75.00", "Abena", null), MoneyAlerts.classify("Momo credit alert: 75.00 cedis in from ABENA", trusted = true))
    }

    // ---------------------------------------------------------------- missed calls

    @Test fun dialer_missed_call_notifications_parse_both_ways_round() {
        assertEquals(MissedCalls.Missed(listOf("Kofi Mensah"), 1), MissedCalls.parse("Missed call", "Kofi Mensah"))
        assertEquals(MissedCalls.Missed(listOf("Kofi Mensah"), 1), MissedCalls.parse("Kofi Mensah", "Missed call"))
        assertEquals(MissedCalls.Missed(listOf("Ama Mensah"), 2), MissedCalls.parse("2 missed calls", "Ama Mensah"))
        assertEquals(MissedCalls.Missed(listOf("Ama"), 1), MissedCalls.parse("Missed call from Ama", null))
        assertEquals(MissedCalls.Missed(listOf("Kofi Mensah", "Ama"), 2), MissedCalls.parse("2 missed calls", "Kofi Mensah, Ama"))
        // A time after the name is not part of it (else "your mother" is never found).
        assertEquals(MissedCalls.Missed(listOf("Kofi"), 1), MissedCalls.parse("Missed call from Kofi at 10:32", null))
        assertEquals(MissedCalls.Missed(listOf("Ama Mensah"), 1), MissedCalls.parse("Missed call", "Ama Mensah · 10:32 AM"))
        assertNull(MissedCalls.parse("Ama", "Are you coming?"))
        assertNull(MissedCalls.parse(null, null))
    }

    @Test fun only_saved_contacts_are_announced() {
        for (n in listOf("Kofi", "Ama Mensah", "Mama Akos")) assertTrue(n, MissedCalls.isSavedName(n))
        for (n in listOf("0244123456", "+233 24 412 3456", "Unknown", "Private number", "Spam", "Suspected spam", "Suspected spam caller",
            "Spam risk", "Scam likely", "", null, "[code hidden]"))
            assertFalse(n.toString(), MissedCalls.isSavedName(n))
    }

    @Test fun a_taught_relation_is_said_instead_of_the_name() {
        val rel = mapOf("mother" to "Ama Mensah", "brother" to "Kofi")
        assertEquals("mother", MissedCalls.relationFor("ama  mensah", rel))
        assertEquals("Missed call from your mother.", MissedCalls.line(listOf("Ama Mensah"), rel, several = false))
        assertEquals("Missed call from Esi.", MissedCalls.line(listOf("Esi"), rel, several = false))
        assertEquals("Missed calls from your mother and your brother.", MissedCalls.line(listOf("Ama Mensah", "Kofi"), rel, several = true))
        // No counts spoken, ever.
        assertEquals("Missed calls from Esi.", MissedCalls.line(listOf("Esi"), rel, several = true))
    }

    // ---------------------------------------------------------------- low battery

    @Test fun low_battery_is_said_once_per_level_per_charge() {
        var set = emptySet<Int>()
        fun at(level: Int, charging: Boolean = false): Int? { val s = BatteryAlerts.step(level, charging, set); set = s.announced; return s.announce }
        assertNull(at(40)); assertNull(at(16))
        assertEquals(15, at(15))
        assertNull("once", at(15)); assertNull(at(14)); assertNull("a wobble up is not a new warning", at(16)); assertNull(at(15))
        assertEquals(5, at(5))
        assertNull(at(4)); assertNull(at(3))
        // Charge: each level is re-armed as the battery climbs past it.
        assertNull(at(6, charging = true)); assertEquals(setOf(15), set)
        assertNull(at(30, charging = true)); assertEquals(emptySet<Int>(), set)
        assertEquals(15, at(15))
        // Unplugged at 14 after charging from 10: 15 already said this discharge, 5 re-armed.
        set = setOf(15, 5)
        assertNull(at(14, charging = true)); assertEquals(setOf(15), set)
        assertNull(at(14)); assertEquals(5, at(5))
    }

    @Test fun starting_below_both_levels_says_the_urgent_one_only() {
        val s = BatteryAlerts.step(4, false, emptySet())
        assertEquals(5, s.announce)
        assertEquals(setOf(15, 5), s.announced)
        assertNull(BatteryAlerts.step(-1, false, emptySet()).announce)
        // Charged while switched off (no charging seen): the level alone re-arms.
        assertEquals(emptySet<Int>(), BatteryAlerts.step(80, false, setOf(15, 5)).announced)
    }

    @Test fun battery_lines() {
        assertEquals("Your battery is at 15 percent. Plug in your charger soon.", BatteryAlerts.line(15, 15, 5))
        assertEquals("Your battery is at 5 percent. Plug in your charger now, or the phone will switch off.", BatteryAlerts.line(5, 5, 5))
        assertEquals(setOf(5, 15), BatteryAlerts.decode(BatteryAlerts.encode(setOf(15, 5))))
        assertEquals(emptySet<Int>(), BatteryAlerts.decode("junk,"))
    }

    // ---------------------------------------------------------------- once, rate limit, privacy, "read it"

    @Test fun each_alert_once_and_not_too_often() {
        val g = AlertGate(maxSeen = 3)
        assertTrue(g.firstTime("a")); assertFalse(g.firstTime("a"))
        g.firstTime("b"); g.firstTime("c"); g.firstTime("d")
        assertTrue("the oldest is forgotten when the list is full", g.firstTime("a"))
        val restored = AlertGate().apply { restore(listOf("x", "y")) }
        assertFalse("kept across a restart", restored.firstTime("x"))

        val r = AlertGate()
        assertTrue(r.mayAnnounce("money", 0L, 30_000L, 3))
        assertFalse("same type too soon", r.mayAnnounce("money", 10_000L, 30_000L, 3))
        assertTrue("another type", r.mayAnnounce("battery", 10_000L, 30_000L, 3))
        assertTrue(r.mayAnnounce("money", 40_000L, 30_000L, 3))
        assertFalse("hourly cap", r.mayAnnounce("missed_call", 50_000L, 30_000L, 3))
        assertTrue("an hour later", r.mayAnnounce("missed_call", 3_700_000L, 30_000L, 3))
    }

    @Test fun on_the_loudspeaker_a_private_alert_only_says_there_is_one() {
        assertTrue(AlertPrivacy.hideOnSpeaker(canBePrivate = true, privateSetting = true, headsetConnected = false))
        assertFalse("earphones in: read it", AlertPrivacy.hideOnSpeaker(true, true, headsetConnected = true))
        assertFalse("the user switched private alerts off", AlertPrivacy.hideOnSpeaker(true, false, false))
        assertFalse("warnings and the battery carry nothing private", AlertPrivacy.hideOnSpeaker(false, true, false))
        assertEquals("You have a money alert. Say “read it” to hear it.", AlertPrivacy.privateLine(PhoneAlerts.MONEY))
        assertEquals("You have a missed call alert. Say “read it” to hear it.", AlertPrivacy.privateLine(PhoneAlerts.MISSED_CALL))
    }

    @Test fun the_inbox_keeps_waiting_alerts_for_read_it() {
        val box = AlertInbox(max = 2)
        box.put(AlertInbox.Item("money", "You've received 50 cedis from Kofi Mensah.", at = 1_000L, announcedPrivately = true, expiresAt = 61_000L))
        assertTrue(box.readItPending(now = 60_000L, windowMs = 180_000L))
        assertFalse("too long ago for a bare 'read it'", box.readItPending(now = 500_000L, windowMs = 180_000L))
        box.put(AlertInbox.Item("battery", "b", 2_000L, false, expiresAt = 62_000L))
        box.put(AlertInbox.Item("missed_call", "c", 3_000L, false, expiresAt = 63_000L))
        assertEquals("bounded", 2, box.size())
        assertEquals(listOf("b", "c"), box.take(now = 10_000L).map { it.line })
        assertEquals("read once, then gone", 0, box.size())
        box.put(AlertInbox.Item("money", "old", 0L, false, expiresAt = 60_000L))
        assertEquals("expired", emptyList<AlertInbox.Item>(), box.take(now = 10_000_000L))
    }

    @Test fun read_it_says_the_balance_only_if_wanted_now() {
        val now = System.currentTimeMillis()
        val credit = Event.Received("50.00", "Kofi Mensah", "123.45")
        assertEquals("There's no alert waiting.", PhoneAlerts.readWaiting(withBalance = false))
        PhoneAlerts.inbox.put(AlertInbox.Item("money", MoneyAlerts.receivedLine(credit, true), now, true, now + 60_000L, money = credit))
        // Switched off since the alert came: the balance is not said, although the stored line had it.
        assertEquals("You've received 50 cedis from Kofi Mensah.", PhoneAlerts.readWaiting(withBalance = false))
        assertEquals("There's no alert waiting.", PhoneAlerts.readWaiting(withBalance = true))
        PhoneAlerts.inbox.put(AlertInbox.Item("money", "x", now, true, now + 60_000L, money = credit))
        assertEquals("You've received 50 cedis from Kofi Mensah. Your balance is 123 cedis 45 pesewas.", PhoneAlerts.readWaiting(withBalance = true))
    }

    @Test fun an_alert_held_in_quiet_hours_lasts_until_the_morning() {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val window = com.tekyerema.agent.triggers.QuietHours.parse("22:00-06:00")
        val at23 = 23 * 3_600_000L                         // 1970-01-01 23:00 UTC
        assertEquals("seven hours to 06:00, then the usual hour", at23 + 7 * 3_600_000L + 3_600_000L, AlertInbox.expiry(at23, 3_600_000L, window, utc))
        val at12 = 12 * 3_600_000L
        assertEquals(at12 + 3_600_000L, AlertInbox.expiry(at12, 3_600_000L, window, utc))
        assertEquals(at23 + 3_600_000L, AlertInbox.expiry(at23, 3_600_000L, null, utc))
    }

    @Test fun the_same_event_under_two_notifications_is_said_once() {
        val g = AlertGate()
        assertFalse(g.repeatWithin("money|50.00|kofi mensah", 0L, 600_000L))
        assertTrue("the SMS after the MoMo app's push", g.repeatWithin("money|50.00|kofi mensah", 5_000L, 600_000L))
        assertFalse("a different payment", g.repeatWithin("money|20.00|kofi mensah", 6_000L, 600_000L))
        assertFalse("much later it is a new payment", g.repeatWithin("money|50.00|kofi mensah", 700_000L, 600_000L))
    }
}
