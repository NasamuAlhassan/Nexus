package com.tekyerema

import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.RetrievalNeed
import com.tekyerema.agent.RoutingBrain
import com.tekyerema.agent.journal.EffectKeys
import com.tekyerema.core.config.AppConfig
import com.tekyerema.money.BankGuide
import com.tekyerema.money.BankSession
import com.tekyerema.money.BankUssd
import com.tekyerema.money.BankUssdTool
import com.tekyerema.money.GuidedSend
import com.tekyerema.money.KnownRecipients
import com.tekyerema.money.LimitCommand
import com.tekyerema.money.MoMoAfterCare
import com.tekyerema.money.MoMoRetry
import com.tekyerema.money.MoneyCommands
import com.tekyerema.money.MoneyLimits
import com.tekyerema.money.MoneyPlan
import com.tekyerema.operator.Capability
import com.tekyerema.operator.MemoryOperatorStore
import com.tekyerema.operator.OperatorPolicy
import com.tekyerema.policy.PolicyConfig
import com.tekyerema.policy.PolicyEngine
import com.tekyerema.policy.PolicyOutcome
import com.tekyerema.policy.RiskTier
import com.tekyerema.tools.MoMoSendTool
import com.tekyerema.tools.MobileMoneyTool
import com.tekyerema.tools.RunUssdTool
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/**
 * Money safety round 2 (2026-09-29, money/): spending limits with a cooling-off, the first-time-recipient warning, "try
 * again" after a *170# timeout, after-care (receipt, wrong-number guide) and the guided bank USSD menu — the pure parts.
 */
class MoneySafetyRound2Test {
    private val t0 = 1_790_000_000_000L   // a fixed instant (UTC day boundaries: zoneOffset 0 below)
    private val min = 60_000L
    private lateinit var store: MemoryOperatorStore

    @Before fun setUp() {
        AppConfig.loadFrom(emptyList())
        store = MemoryOperatorStore()
        OperatorPolicy.resetForTest(store)
        OperatorPolicy.zoneOffset = { 0 }
        MoMoRetry.clear(); MoMoAfterCare.resetForTest(); GuidedSend.resetForTest(); BankSession.end()
    }

    @After fun tearDown() {
        OperatorPolicy.resetForTest()
        MoMoRetry.clear(); MoMoAfterCare.resetForTest(); GuidedSend.resetForTest(); BankSession.end()
        AppConfig.loadFrom(emptyList())
    }

    private fun bd(s: String) = BigDecimal(s)

    // ---------------------------------------------------------------- 1. spending limits

    @Test fun no_limit_until_the_user_sets_one() {
        assertNull(MoneyLimits.limit(MoneyLimits.Kind.PER_SEND, t0))
        assertNull(MoneyLimits.check(bd("100000"), t0))
        assertFalse(MoneyLimits.any(t0))
        assertTrue(MoneyLimits.statusLine(t0).startsWith("You haven't set a Mobile Money limit."))
    }

    @Test fun a_first_or_lower_limit_is_immediate_and_a_send_over_it_is_refused_with_the_reason() {
        val c = MoneyLimits.set(MoneyLimits.Kind.PER_SEND, bd("200"), t0)
        assertTrue(c is MoneyLimits.Change.Now)
        assertEquals("Okay — your Mobile Money limit is now 200 cedis per send.", MoneyLimits.changeLine(c, t0))
        assertNull(MoneyLimits.check(bd("200"), t0))
        val refusal = MoneyLimits.check(bd("250"), t0)!!
        assertEquals("That's more than your limit of 200 cedis per send. You can change the limit in Settings or by saying " +
            "“set my MoMo limit”.", refusal)
        assertTrue(MoneyLimits.isRefusal(refusal))
        // Lower again: immediate.
        assertTrue(MoneyLimits.set(MoneyLimits.Kind.PER_SEND, bd("100"), t0 + min) is MoneyLimits.Change.Now)
        assertNotNull(MoneyLimits.check(bd("150"), t0 + min))
    }

    @Test fun raising_or_removing_waits_out_the_cooling_off() {
        MoneyLimits.set(MoneyLimits.Kind.PER_SEND, bd("200"), t0)
        val up = MoneyLimits.set(MoneyLimits.Kind.PER_SEND, bd("500"), t0)
        assertTrue(up is MoneyLimits.Change.Later)
        assertEquals("Okay — your new limit starts in 30 minutes. Until then, it stays at 200 cedis per send.", MoneyLimits.changeLine(up, t0))
        // A caller can't have the user send 400 right after lifting it.
        assertNotNull(MoneyLimits.check(bd("400"), t0 + 29 * min))
        assertEquals(0, bd("200").compareTo(MoneyLimits.limit(MoneyLimits.Kind.PER_SEND, t0 + 29 * min)))
        assertNull(MoneyLimits.check(bd("400"), t0 + 30 * min))
        assertEquals(0, bd("500").compareTo(MoneyLimits.limit(MoneyLimits.Kind.PER_SEND, t0 + 30 * min)))
        // Removing waits too; lowering in the meantime cancels the wait.
        val off = MoneyLimits.set(MoneyLimits.Kind.PER_SEND, null, t0 + 31 * min)
        assertTrue(off is MoneyLimits.Change.Later)
        assertTrue(MoneyLimits.changeLine(off, t0 + 31 * min).startsWith("Okay — the limit comes off in 30 minutes."))
        assertTrue(MoneyLimits.set(MoneyLimits.Kind.PER_SEND, bd("300"), t0 + 40 * min) is MoneyLimits.Change.Now)
        assertNull(MoneyLimits.pendingChange(MoneyLimits.Kind.PER_SEND, t0 + 40 * min))
        assertEquals(0, bd("300").compareTo(MoneyLimits.limit(MoneyLimits.Kind.PER_SEND, t0 + 90 * min)))
        // The same value again: nothing to do.
        assertEquals("Your Mobile Money limit is already 300 cedis per send.",
            MoneyLimits.changeLine(MoneyLimits.set(MoneyLimits.Kind.PER_SEND, bd("300.00"), t0 + 91 * min), t0 + 91 * min))
    }

    @Test fun the_cooling_off_is_config_driven() {
        AppConfig.loadFrom(listOf("config/p.json" to """{"money":{"limits":{"coolingOffMs":600000}}}"""))
        MoneyLimits.set(MoneyLimits.Kind.DAILY, bd("100"), t0)
        val up = MoneyLimits.set(MoneyLimits.Kind.DAILY, bd("1000"), t0) as MoneyLimits.Change.Later
        assertEquals(t0 + 10 * min, up.at)
    }

    @Test fun the_daily_limit_counts_sends_that_reached_the_pin_per_local_day() {
        MoneyLimits.set(MoneyLimits.Kind.DAILY, bd("500"), t0)
        MoneyLimits.recordSend(bd("450"), t0)
        assertNull(MoneyLimits.check(bd("50"), t0))
        val r = MoneyLimits.check(bd("100"), t0)!!
        assertEquals("That's over your daily limit of 500 cedis: you've sent 450 cedis today. You can change the limit in " +
            "Settings or by saying “set my MoMo limit”.", r)
        assertTrue(MoneyLimits.isRefusal(r))
        // Tomorrow (local day) starts from nothing.
        val tomorrow = (Math.floorDiv(t0, 86_400_000L) + 1) * 86_400_000L + min
        assertNull(MoneyLimits.check(bd("100"), tomorrow))
        assertEquals(0, BigDecimal.ZERO.compareTo(MoneyLimits.spentToday(tomorrow)))
    }

    @Test fun refusal_lines_fit_the_history_line_even_with_big_amounts() {
        val per = MoneyLimits.perSendLine(bd("99999.99"))
        val day = MoneyLimits.dailyLine(bd("99999.99"), bd("99999.98"))
        assertTrue(per.length <= 160); assertTrue(day, day.length <= 160)
    }

    @Test fun limits_pending_changes_and_spending_survive_a_restart() {
        MoneyLimits.set(MoneyLimits.Kind.PER_SEND, bd("200"), t0)
        MoneyLimits.set(MoneyLimits.Kind.DAILY, bd("500"), t0)
        MoneyLimits.set(MoneyLimits.Kind.PER_SEND, bd("800"), t0)   // waiting
        MoneyLimits.recordSend(bd("120.50"), t0)
        MoneyLimits.resetForTest(store)                               // a restart: load from the store
        assertEquals(0, bd("200").compareTo(MoneyLimits.limit(MoneyLimits.Kind.PER_SEND, t0 + min)))
        assertEquals(t0 + 30 * min, MoneyLimits.pendingChange(MoneyLimits.Kind.PER_SEND, t0 + min)!!.at)
        assertEquals(0, bd("120.50").compareTo(MoneyLimits.spentToday(t0 + min)))
        assertEquals(0, bd("800").compareTo(MoneyLimits.limit(MoneyLimits.Kind.PER_SEND, t0 + 31 * min)))
    }

    @Test fun every_limit_change_is_worded_for_the_activity_log() {
        assertEquals("set the Mobile Money limit to 200 cedis per send", OperatorPolicy.describeChange(MoneyLimits.K_PER_SEND, "200"))
        assertEquals("removed the daily Mobile Money limit", OperatorPolicy.describeChange(MoneyLimits.K_DAILY, null))
        val pending = """{"PER_SEND":{"v":"500","at":1},"DAILY":{"v":null,"at":2}}"""
        val words = OperatorPolicy.describeChange(MoneyLimits.K_PENDING, pending)!!
        assertTrue(words, words.contains("asked to raise the Mobile Money limit to 500 cedis per send"))
        assertTrue(words, words.contains("asked to remove the Mobile Money limit a day"))
        assertNull(OperatorPolicy.describeChange(MoneyLimits.K_SPENT, "{}"))       // a counter, not a choice
        assertNull(OperatorPolicy.describeChange(KnownRecipients.K_KNOWN, "x"))
    }

    @Test fun the_status_line_says_both_limits_what_was_sent_and_what_is_waiting() {
        MoneyLimits.set(MoneyLimits.Kind.PER_SEND, bd("200"), t0)
        MoneyLimits.set(MoneyLimits.Kind.DAILY, bd("500"), t0)
        MoneyLimits.recordSend(bd("50"), t0)
        MoneyLimits.set(MoneyLimits.Kind.PER_SEND, bd("300"), t0)
        val s = MoneyLimits.statusLine(t0 + 18 * min)
        assertEquals("Your Mobile Money limit is 200 cedis per send, and 500 cedis a day. You've sent 50 cedis today. " +
            "A new limit of 300 cedis per send starts in 12 minutes.", s)
    }

    @Test fun limit_commands_by_voice() {
        fun c(s: String) = MoneyCommands.classify(s)
        assertEquals(LimitCommand.Set(MoneyLimits.Kind.PER_SEND, bd("200")), c("Set my MoMo limit to 200 cedis."))
        assertEquals(LimitCommand.Set(MoneyLimits.Kind.DAILY, bd("500")), c("set my daily MoMo limit to 500"))
        assertEquals(LimitCommand.Set(MoneyLimits.Kind.DAILY, bd("100")), c("limit my mobile money to 100 cedis a day"))
        assertEquals(LimitCommand.Set(MoneyLimits.Kind.PER_SEND, bd("1000")), c("change my momo limit to GHS 1,000"))
        assertEquals(LimitCommand.Set(MoneyLimits.Kind.PER_SEND, bd("0.50")), c("set my MoMo limit to 50 pesewas"))
        assertEquals(LimitCommand.SetNoAmount, c("set my momo limit"))
        assertEquals(LimitCommand.Query, c("What is my MoMo limit?"))
        assertEquals(LimitCommand.Query, c("what's my daily mobile money limit"))
        assertEquals(LimitCommand.Query, c("how much can I send with momo"))
        assertEquals(LimitCommand.Remove(null), c("remove my MoMo limit"))
        assertEquals(LimitCommand.Remove(MoneyLimits.Kind.DAILY), c("remove my daily momo limit"))
        assertEquals(LimitCommand.Remove(null), c("I don't want a momo limit"))
        // Not money, or not a limit command.
        for (s in listOf("set my data limit to 5", "what is my limit", "set a timer for 5 minutes", "send 5 cedis to 0553238011",
                "send a message to Ama saying the limit is 5 cedis", "what's the time"))
            assertNull(s, c(s))
    }

    @Test fun limit_commands_apply_and_speak_on_the_fast_path() {
        val set = MoneyCommands.handle("set my MoMo limit to 200 cedis", t0)!!
        assertEquals("Okay — your Mobile Money limit is now 200 cedis per send.", set.spoken)
        assertTrue(set.systemVoice && set.ok)
        assertEquals("Okay — your new limit starts in 30 minutes. Until then, it stays at 200 cedis per send.",
            MoneyCommands.handle("set my momo limit to 500", t0)!!.spoken)
        val remove = MoneyCommands.handle("remove my momo limit", t0)!!.spoken
        assertEquals("Okay — the limit comes off in 30 minutes. Until then, it stays at 200 cedis per send.", remove)
        assertTrue(MoneyCommands.handle("what is my momo limit", t0)!!.spoken.startsWith("Your Mobile Money limit is 200 cedis per send"))
        assertNull(MoneyCommands.handle("open whatsapp", t0))
        assertEquals("You don't have a Mobile Money limit set.", MoneyLimits.removeAllLine(listOf(
            MoneyLimits.Change.Same(MoneyLimits.Kind.PER_SEND, null), MoneyLimits.Change.Same(MoneyLimits.Kind.DAILY, null))))
    }

    @Test fun a_limit_refusal_ends_the_task_but_a_misheard_number_still_goes_back_to_the_planner() {
        val refused = "momo_send({\"number\":\"0553238011\",\"amount\":\"500\"}) → fail [preflight]; verified=false (not attempted); " +
            MoneyLimits.perSendLine(bd("200"))
        val end = RoutingBrain.moneyEnd(listOf(refused))!!
        assertEquals(AgentStatus.CANNOT_DO, end.first)
        assertEquals(MoneyLimits.perSendLine(bd("200")), end.second)
        val guided = "mobile_money({\"_kind\":\"send_money\",\"_amount\":\"900\"}) → fail [preflight]; verified=false (not attempted); " +
            MoneyLimits.dailyLine(bd("500"), bd("0"))
        assertEquals(AgentStatus.CANNOT_DO, RoutingBrain.moneyEnd(listOf(guided))!!.first)
        assertNull(RoutingBrain.moneyEnd(listOf("momo_send({number=0553238}) → fail [preflight]; verified=false (not attempted); I heard the number …")))
        // With a limit set, an amount that isn't one clear figure is asked for again (not an end, not read back).
        MoneyLimits.set(MoneyLimits.Kind.PER_SEND, bd("200"), t0)
        val (unclear, final) = MoneyLimits.preflight("five or six", t0)!!
        assertFalse(final)
        assertNull(RoutingBrain.moneyEnd(listOf("mobile_money({}) → fail [preflight]; verified=false (not attempted); $unclear")))
        assertEquals(MoneyLimits.perSendLine(bd("200")) to true, MoneyLimits.preflight("GHS 250", t0))
        assertNull(MoneyLimits.preflight("150 cedis", t0))
        assertEquals(0, bd("0.50").compareTo(MoneyLimits.parseAmount("50 pesewas")))
        assertNull(MoneyLimits.parseAmount("5 or 10"))
    }

    // ---------------------------------------------------------------- 2. first-time recipient

    @Test fun paid_numbers_are_kept_as_salted_hashes_only() {
        val h1 = KnownRecipients.hash("salt-a", "0553238011")!!
        assertEquals(64, h1.length)
        assertEquals(h1, KnownRecipients.hash("salt-a", "+233 55 323 8011"))   // the same number written the other way
        assertFalse(h1 == KnownRecipients.hash("salt-b", "0553238011"))       // another phone's salt → another code
        assertNull(KnownRecipients.hash("salt-a", "no digits"))
        assertFalse(KnownRecipients.isKnown("0553238011"))
        KnownRecipients.add("0553238011")
        assertTrue(KnownRecipients.isKnown("233553238011"))
        assertFalse(KnownRecipients.isKnown("0244123456"))
        // Nothing in the store is the number itself.
        assertTrue(store.values.values.none { it.contains("553238011") })
        // Survives a restart, bounded, and "delete everything" forgets it.
        KnownRecipients.resetForTest(store)
        assertTrue(KnownRecipients.isKnown("0553238011"))
        for (i in 0 until KnownRecipients.MAX + 5) KnownRecipients.add("02441%05d".format(i))
        assertEquals(KnownRecipients.MAX, KnownRecipients.count())
        assertFalse("the oldest was dropped", KnownRecipients.isKnown("0553238011"))
        KnownRecipients.clear()
        assertEquals(0, KnownRecipients.count())
        assertFalse(KnownRecipients.isKnown("0244100010"))
    }

    @Test fun a_first_time_number_is_said_in_the_read_back_with_the_other_warnings() {
        val a = JSONObject().put("number", "0553238011").put("amount", "5").put(MoMoSendTool.ARG_SIM, "mtn")
        assertNull(MoMoSendTool.networkWarning(a))
        a.put(MoMoSendTool.ARG_NEW_RECIPIENT, true)
        assertEquals(KnownRecipients.FIRST_TIME_LINE, MoMoSendTool.networkWarning(a))
        val p = MoMoSendTool.confirmationPrompt(a)
        assertTrue(p, p.contains("an MTN number, with Mobile Money?\nYou haven't sent money to this number before. I'll go through"))
        a.put(MoMoSendTool.ARG_ON_CALL, true)
        assertTrue(MoMoSendTool.networkWarning(a)!!.startsWith("You haven't sent money to this number before. You're on a phone call"))
        // The guided send says it too; airtime says nothing of the kind.
        val g = JSONObject().put("_kind", "send_money").put("_amount", "5").put("_number", "0553238011").put(MoMoSendTool.ARG_NEW_RECIPIENT, true)
        assertEquals(KnownRecipients.FIRST_TIME_LINE, MobileMoneyTool.warning(g))
        assertTrue(MobileMoneyTool.confirmationPrompt(g).contains("\nYou haven't sent money to this number before."))
        assertNull(MobileMoneyTool.warning(JSONObject().put("_kind", "buy_airtime").put("_amount", "5")))
    }

    @Test fun a_guided_send_at_its_pin_counts_toward_the_limit_and_the_paid_numbers() {
        val args = JSONObject().put("_kind", "send_money").put("_amount", "20").put("_number", "0553238011").put(GuidedSend.ARG_SIM, "mtn")
        GuidedSend.approved("send 20 cedis to 0553238011", "mobile_money", args, t0)
        assertFalse("another task's PIN doesn't count", GuidedSend.atPin("something else", "momo_pin", t0 + min))
        assertFalse("not a Mobile Money PIN", GuidedSend.atPin("send 20 cedis to 0553238011", "password", t0 + min))
        assertTrue(GuidedSend.atPin("send 20 cedis to 0553238011", "momo_pin", t0 + min))
        assertFalse("once", GuidedSend.atPin("send 20 cedis to 0553238011", "momo_pin", t0 + min))
        assertEquals(0, bd("20").compareTo(MoneyLimits.spentToday(t0 + min)))
        assertTrue(KnownRecipients.isKnown("0553238011"))
        GuidedSend.approved("buy airtime", "mobile_money", JSONObject().put("_kind", "buy_airtime").put("_amount", "5"), t0)
        assertFalse(GuidedSend.atPin("buy airtime", "momo_pin", t0 + min))
    }

    // ---------------------------------------------------------------- 3. try again after a timeout

    @Test fun try_again_reproposes_the_same_send_once_through_the_full_read_back() {
        assertNull(MoMoRetry.rewrite("try again", t0))                      // nothing timed out
        MoMoRetry.remember("0553238011", "0.1", "school fees", t0)
        for (s in listOf("Try again", "try it again.", "please try again", "retry", "ok, try again"))
            assertTrue(s, MoMoRetry.isTryAgain(s))
        assertFalse(MoMoRetry.isTryAgain("try again later to call Ama"))
        val goal = MoMoRetry.rewrite("try again", t0 + 4 * min)!!
        assertEquals("try again: send 0.1 cedis to 0553238011 with Mobile Money, reference school fees", goal)
        assertNull("used once", MoMoRetry.rewrite("try again", t0 + 4 * min))
        val d = MoMoRetry.decide(goal, emptyList()) as AgentDecision.CallTool
        assertEquals("momo_send", d.name)
        assertEquals("0553238011", d.args.getString("number")); assertEquals("0.1", d.args.getString("amount"))
        assertEquals("school fees", d.args.getString("reference"))
        // The retry goal names the number, so the read-back never calls it "a number you didn't say".
        assertTrue(com.tekyerema.policy.ActionSemantics.namedInGoal("0553238011", goal) { null })
        // A preflight refusal ends it; the user changing the send ends it plainly (a retry repeats a send exactly).
        val pre = MoMoRetry.decide(goal, listOf("momo_send({}) → fail [preflight]; verified=false (not attempted); I couldn't tell which network this SIM is on."))
        assertTrue(pre is AgentDecision.CannotDo)
        assertEquals(MoMoRetry.CHANGED, (MoMoRetry.decide(goal, listOf("You proposed: \"Send…\". The user did NOT approve it — they said: \"make it 5\".")) as AgentDecision.Done).summary)
        assertNull("not a retry goal", MoMoRetry.decide("send 5 cedis to 0244123456", emptyList()))
    }

    @Test fun try_again_expires_and_the_timeout_lines_offer_it_and_end_the_task() {
        MoMoRetry.remember("0553238011", "5", null, t0)
        assertNull(MoMoRetry.rewrite("try again", t0 + MoMoRetry.WINDOW_MS + 1))
        for (line in listOf(MoMoRetry.MENU_TIMEOUT, MoMoRetry.CONFIRM_TIMEOUT)) {
            assertTrue(line.length <= 160)
            val end = RoutingBrain.moneyEnd(listOf("momo_send({\"number\":\"0553238011\"}) → fail [native]; verified=false (x); $line"))!!
            assertEquals(AgentStatus.CANNOT_DO, end.first)
            assertTrue(end.second, end.second.endsWith("Say “try again” to start again."))
        }
    }

    // ---------------------------------------------------------------- 4. after-care

    @Test fun mtn_send_messages_are_parsed() {
        fun p(s: String) = MoMoAfterCare.parseSent(s)
        assertEquals(MoMoAfterCare.Sent("5.00", "Kofi Mensah"), p("Payment made for GHS 5.00 to KOFI MENSAH. Current Balance: GHS 120.00. Reference: lunch."))
        assertEquals(MoMoAfterCare.Sent("5.00", "Kofi Mensah"), p("You have sent GHS 5.00 to KOFI MENSAH 0244123456. Fee: GHS 0.00"))
        assertEquals(MoMoAfterCare.Sent("0.10", "Zelia Awini"), p("Your transfer of GHS 0.10 to AMA MENSAH was successful."))
        assertEquals(MoMoAfterCare.Sent("1,250.00", null), p("You've sent GH₵1,250.00 to 0244123456. Transaction ID: 1234"))
        assertNull(p("Payment received for GHS 50.00 from KOFI MENSAH"))
        assertNull(p("Your balance is GHS 20.00"))
    }

    @Test fun a_receipt_is_read_only_just_after_a_hand_off_and_once() {
        val sms = "Payment made for GHS 5.00 to KOFI MENSAH. Current Balance: GHS 120.00."
        assertNull("no hand-off", MoMoAfterCare.receiptFor(sms, false, t0))
        MoMoAfterCare.handedOff("mtn", bd("5"), t0)
        assertNull("not a send message", MoMoAfterCare.receiptFor("Your balance is GHS 20.00", false, t0 + min))
        assertEquals("MTN confirms 5 cedis sent to Kofi Mensah.", MoMoAfterCare.receiptFor(sms, false, t0 + min))
        assertNull("once", MoMoAfterCare.receiptFor(sms, false, t0 + min))
        MoMoAfterCare.handedOff("mtn", bd("5"), t0)
        assertNull("too late", MoMoAfterCare.receiptFor(sms, false, t0 + 6 * min))
        MoMoAfterCare.handedOff("mtn", bd("5"), t0)
        assertEquals("MTN confirms 5 cedis sent to Kofi Mensah. Your balance is 120 cedis.", MoMoAfterCare.receiptFor(sms, true, t0 + min))
        // MTN's figure isn't what was handed off: said, with where to call.
        val odd = MoMoAfterCare.receiptLine(MoMoAfterCare.Sent("50.00", "Kofi Mensah"), "mtn", bd("5"), null)
        assertEquals("MTN confirms 50 cedis sent to Kofi Mensah. That isn't the 5 cedis you asked for — call MTN on 100 if it's wrong.", odd)
    }

    @Test fun the_wrong_number_guide_and_its_offer_to_call_100() {
        for (s in listOf("I sent money to the wrong number", "I sent it to the wrong person!", "i mistakenly sent 50 cedis to a wrong number",
                "how do I reverse a momo transfer"))
            assertTrue(s, MoMoAfterCare.isWrongTransfer(s))
        for (s in listOf("send money to Ama", "I called the wrong number", "what's the wrong answer"))
            assertFalse(s, MoMoAfterCare.isWrongTransfer(s))
        val d = MoMoAfterCare.decide("I sent money to the wrong number", emptyList(), t0) as AgentDecision.Done
        assertEquals("Call MTN on 100 straight away and ask them to reverse it — the sooner, the better. Only use 100: don't call a " +
            "number someone gives you, and don't send money to anyone who calls about it. As a guide, the Mobile Money rules give you " +
            "30 days to complain, and MTN should acknowledge a complaint within 3 working days. Should I call MTN on 100?", d.summary)
        // "yes" → a new "call 100" request (confirmed like any call); used once; "no" is answered on the phone.
        assertNull(MoMoAfterCare.rewrite("call my mum", t0 + min))
        assertEquals("call 100", MoMoAfterCare.rewrite("Yes please", t0 + min))
        assertNull(MoMoAfterCare.rewrite("yes", t0 + min))
        MoMoAfterCare.decide("I sent it to the wrong person", emptyList(), t0)
        assertEquals("Okay. If you need it, say “call 100”.", MoMoAfterCare.declineLine("no thanks", t0 + min))
        assertNull(MoMoAfterCare.declineLine("no", t0 + min))
        MoMoAfterCare.decide("I sent it to the wrong person", emptyList(), t0)
        assertNull("the offer lapses", MoMoAfterCare.rewrite("yes", t0 + 3 * min))
        assertNull("only as the first step", MoMoAfterCare.decide("I sent money to the wrong number", listOf("x(y) → success"), t0))
        // The call itself goes through the normal confirmation (TIER2 make_call), never dialled by itself.
        val call100 = JSONObject().put("number", "100")
        assertEquals(PolicyOutcome.CONFIRM, PolicyEngine().evaluate(com.tekyerema.tools.MakeCallTool, call100).outcome)
        assertEquals("Call 1 0 0?", com.tekyerema.tools.MakeCallTool.confirmationPrompt(call100))
    }

    // ---------------------------------------------------------------- 5. guided bank USSD

    @Test fun only_verified_bank_codes_and_the_shipped_table_matches_the_code() {
        val keys = BankUssd.banks().associate { it.key to it.code }
        assertEquals(mapOf("stanbic" to "*715#", "zenith" to "*966#", "uba" to "*822#", "republic" to "*414#",
            "absa" to "*859*99#", "ghanapay" to "*707#"), keys)
        loadAppConfig()
        assertEquals(keys, BankUssd.banks().associate { it.key to it.code })
        assertTrue("GCB" in BankUssd.unverified())
        assertTrue(BankUssd.banks().none { it.name.contains("GCB") || it.name.contains("Ecobank") })
        assertEquals("star 8 5 9 star 9 9 hash", BankUssd.spokenCode("*859*99#"))
    }

    @Test fun bank_requests_are_understood() {
        fun p(s: String) = BankUssd.parse(s)
        assertEquals("stanbic", (p("Send money from my Stanbic account") as BankUssd.Request.Open).bank.key)
        assertEquals("uba", (p("open U.B.A. USSD") as BankUssd.Request.Open).bank.key)
        assertEquals("zenith", (p("dial Zenith bank's code") as BankUssd.Request.Open).bank.key)
        assertEquals("absa", (p("transfer money with absa") as BankUssd.Request.Open).bank.key)
        assertEquals("stanbic", (p("send money from my Stanbic account to my MoMo wallet") as BankUssd.Request.Open).bank.key)
        assertEquals(BankUssd.Request.Which, p("open my bank's USSD"))
        assertEquals(BankUssd.Request.Which, p("send money from my bank account"))
        assertEquals(BankUssd.Request.Unsupported("GCB"), p("send money from my GCB account"))
        assertEquals(BankUssd.Request.Unsupported("Xyz"), p("send money from my xyz bank account"))
        assertEquals(BankUssd.Request.Supported, p("which banks can you open?"))
        // Not ours: the bank's own app, Mobile Money's menu, money to a bank from MoMo, anything else.
        for (s in listOf("open Stanbic", "open the momo ussd", "send money to my Stanbic account", "send 5 cedis to 0244123456",
                "check my airtime balance with ussd", "open whatsapp"))
            assertNull(s, p(s))
        assertTrue(BankUssd.unsupportedLine("GCB").endsWith("Your bank's own app, with your fingerprint, is the safer way to send money."))
    }

    @Test fun what_the_user_says_becomes_what_is_typed() {
        assertEquals("2", BankUssd.entry("option 2"))
        assertEquals("1", BankUssd.entry("the first one"))
        assertEquals("1", BankUssd.entry("one"))
        assertEquals("0123456789", BankUssd.entry("0 1 2 3 4 5 6 7 8 9"))
        assertEquals("200", BankUssd.entry("200 cedis"))
        assertEquals("5.50", BankUssd.entry("5.50"))
        assertEquals("school fees", BankUssd.entry("school fees"))
        assertNull(BankUssd.entry("   "))
        assertTrue(BankUssd.isCancel("cancel")); assertTrue(BankUssd.isRepeat("say that again"))
    }

    @Test fun bank_screens_are_menus_or_handed_over() {
        assertEquals(BankUssd.ScreenKind.MENU, BankUssd.kind("1. Transfer\n2. Balance", hasInput = true, masked = false))
        assertEquals(BankUssd.ScreenKind.MENU, BankUssd.kind("Enter amount", hasInput = true, masked = false))
        assertEquals(BankUssd.ScreenKind.MONEY, BankUssd.kind("Transfer GHS 200 to KOFI MENSAH. Fee GHS 1.00\n1. Confirm\n2. Cancel", true, false))
        assertEquals(BankUssd.ScreenKind.PIN, BankUssd.kind("Enter your PIN", true, false))
        assertEquals(BankUssd.ScreenKind.PIN, BankUssd.kind("Please continue", true, masked = true))
        assertEquals(BankUssd.ScreenKind.FINAL, BankUssd.kind("Request received. You will get an SMS.", false, false))
        // A menu that only lists "Change PIN" isn't asking for it — but Secure Input won't let us act on it, so it's handed over.
        assertEquals(BankUssd.ScreenKind.GUARDED, BankUssd.kind("1. Transfer\n2. Balance\n4. Change PIN", true, false))
        assertEquals(BankUssd.ScreenKind.GUARDED, BankUssd.kind("Enter branch code", true, false))
        assertEquals(BankUssd.ScreenKind.PIN, BankUssd.kind("Enter your security code", true, false))
        val guarded = BankGuide.handOffLine(session("1. Transfer\n4. Change PIN", BankUssd.ScreenKind.GUARDED))
        assertTrue(guarded, guarded.endsWith("It mentions a PIN or a code, so I'll leave this screen to you — choose on it yourself, or " +
            "press Cancel. I never see or type a PIN."))
        // A balance is not read out on the loudspeaker (the research's "do not"); the rest of the screen is.
        val bal = "Your balance is GHS 1,234.00.\n1. Main menu"
        assertEquals("Your balance is (not read on the loudspeaker).; 1. Main menu", BankUssd.spokenScreen(bal, speakBalance = false))
        assertEquals("Your balance is GHS 1,234.00.; 1. Main menu", BankUssd.spokenScreen(bal, speakBalance = true))
        assertEquals("Transfer GHS 200 to KOFI; 1. Confirm", BankUssd.spokenScreen("Transfer GHS 200 to KOFI\n1. Confirm", speakBalance = false))
        // The engine agrees: such a screen is a credential screen, so a guide that went on would be stopped anyway.
        val snap = com.tekyerema.accessibility.UiSnapshot("com.android.phone", null, listOf(
            com.tekyerema.accessibility.UiElement(1, "android.widget.TextView", "1. Transfer\n4. Change PIN", com.tekyerema.accessibility.Box(0, 0, 10, 10)),
            com.tekyerema.accessibility.UiElement(2, "android.widget.EditText", null, com.tekyerema.accessibility.Box(0, 20, 10, 30), editable = true)), 1L)
        assertTrue(com.tekyerema.policy.TargetSemantics.isCredentialScreen(snap))
    }

    @Test fun opening_is_confirmed_a_menu_choice_is_not_and_a_long_number_is_read_back() {
        val open = JSONObject().put(BankUssdTool.ARG_ACTION, BankUssdTool.OPEN).put(BankUssdTool.ARG_BANK, "stanbic")
        val choice = JSONObject().put(BankUssdTool.ARG_ACTION, BankUssdTool.REPLY).put(BankUssdTool.ARG_BANK, "stanbic").put(BankUssdTool.ARG_TEXT, "1")
        val amount = JSONObject(choice.toString()).put(BankUssdTool.ARG_TEXT, "200").put(BankUssdTool.ARG_SCREEN, "Enter amount")
        val account = JSONObject(choice.toString()).put(BankUssdTool.ARG_TEXT, "0123456789").put(BankUssdTool.ARG_SCREEN, "Enter account number")
        val pe = PolicyEngine()
        assertEquals(PolicyOutcome.CONFIRM, pe.evaluate(BankUssdTool, open).outcome)
        assertEquals(RiskTier.TIER3, PolicyConfig.argTier(BankUssdTool, open))
        assertEquals(PolicyOutcome.ALLOW, pe.evaluate(BankUssdTool, choice).outcome)
        assertEquals(PolicyOutcome.CONFIRM, pe.evaluate(BankUssdTool, amount).outcome)
        assertEquals("Open Stanbic Bank's USSD menu, star 7 1 5 hash? It's guided: I read each screen, you choose, and you confirm " +
            "the payment and enter your PIN yourself.", BankUssdTool.confirmationPrompt(open))
        assertEquals("Stanbic Bank's screen says “Enter amount”. Enter 200 and send it?", BankUssdTool.confirmationPrompt(amount))
        assertEquals("Stanbic Bank's screen says “Enter account number”. Enter 0 1 2, 3 4 5, 6 7 8 9 and send it?",
            BankUssdTool.confirmationPrompt(account))
        // "Never touch my money" covers the bank menu; so does Guide only.
        OperatorPolicy.revoke(Capability.MONEY)
        assertEquals(PolicyOutcome.BLOCK, pe.evaluate(BankUssdTool, choice).outcome)
        OperatorPolicy.restore(Capability.MONEY)
        OperatorPolicy.executionMode = com.tekyerema.operator.ExecutionMode.GUIDE_ONLY
        assertEquals(PolicyOutcome.BLOCK, pe.evaluate(BankUssdTool, open).outcome)
        OperatorPolicy.executionMode = com.tekyerema.operator.ExecutionMode.STANDARD
        // A bank code dialled any other way is a money code (TIER3), and a bank step is not an effect-ledger entry.
        assertEquals(RiskTier.TIER3, PolicyConfig.argTier(RunUssdTool, JSONObject().put("code", "*715#")))
        assertEquals(RiskTier.TIER3, PolicyConfig.argTier(RunUssdTool, JSONObject().put("code", "*859*99#")))
        assertNull(EffectKeys.describe("bank_ussd", choice, null, null, false, null))
        // "open Stanbic Bank USSD" is not a website to research.
        assertNull(RetrievalNeed.locate("open Stanbic Bank USSD"))
    }

    private fun session(screen: String, kind: BankUssd.ScreenKind) =
        BankSession.View(BankUssd.byKey("stanbic")!!, screen, kind, t0, t0)

    @Test fun the_bank_guide_reads_each_screen_types_what_the_user_says_and_hands_over_at_the_payment() {
        val goal = "send money from my Stanbic account"
        val open = BankGuide.decide(goal, emptyList(), null) as AgentDecision.CallTool
        assertEquals(BankUssdTool.NAME, open.name); assertEquals(BankUssdTool.OPEN, open.args.getString(BankUssdTool.ARG_ACTION))
        val menu = session("1. Transfer\n2. Balance", BankUssd.ScreenKind.MENU)
        val opened = "bank_ussd({\"_action\":\"open\",\"_bank\":\"stanbic\"}) → success [native]; verified=true (x); Stanbic Bank: 1. Transfer; 2. Balance"
        assertNull("a menu goes on", BankGuide.end(listOf(opened), menu))
        val ask = BankGuide.decide(goal, listOf(opened), menu) as AgentDecision.CallTool
        assertEquals("ask_user", ask.name)
        assertEquals("Stanbic Bank says: “1. Transfer; 2. Balance”. What should I enter? You can say cancel to stop.", ask.args.getString("question"))
        val answered = "ask_user(\"${ask.args.getString("question")}\") → the user answered: \"option 1\""
        val reply = BankGuide.decide(goal, listOf(opened, answered), menu) as AgentDecision.CallTool
        assertEquals(BankUssdTool.REPLY, reply.args.getString(BankUssdTool.ARG_ACTION))
        assertEquals("1", reply.args.getString(BankUssdTool.ARG_TEXT))
        // "say that again" asks again (once), "cancel" stops, a refused question stops.
        val again = BankGuide.decide(goal, listOf(opened, "ask_user(\"x\") → the user answered: \"say that again\""), menu) as AgentDecision.CallTool
        assertTrue(again.args.getString("question").startsWith("Again — Stanbic Bank says:"))
        assertEquals(BankGuide.STOPPED, (BankGuide.decide(goal, listOf(opened, "ask_user(\"x\") → the user answered: \"no\""), menu) as AgentDecision.Done).summary)
        assertTrue(BankGuide.decide(goal, listOf(opened, "ask_user(\"x\") → not asked: already asked"), menu) is AgentDecision.CannotDo)
        // The payment confirmation ends the task: read out, handed over, never submitted.
        val money = session("Transfer GHS 200 to KOFI MENSAH. Fee GHS 1.00\n1. Confirm\n2. Cancel", BankUssd.ScreenKind.MONEY)
        val replied = "bank_ussd({\"_action\":\"reply\",\"_text\":\"200\"}) → success [native]; verified=true (x); Stanbic Bank: Transfer GHS 200"
        val end = BankGuide.end(listOf(opened, answered, replied), money)!!
        assertEquals(AgentStatus.COMPLETED, end.first)
        assertEquals("Stanbic Bank's screen says: “Transfer GHS 200 to KOFI MENSAH. Fee GHS 1.00; 1. Confirm; 2. Cancel”. If that's " +
            "right, confirm it on the screen and enter your PIN yourself; if not, press Cancel. I've stopped here — I never confirm a " +
            "payment or type a PIN.", end.second)
        val failed = BankGuide.end(listOf("bank_ussd({}) → fail [native]; verified=false (x); Stanbic Bank's menu didn't open in time. Nothing was sent."), null)!!
        assertEquals(AgentStatus.CANNOT_DO, failed.first)
        assertEquals("Stanbic Bank's menu didn't open in time. Nothing was sent.", failed.second)
    }

    @Test fun no_bank_named_asks_which_and_an_unsupported_bank_says_the_safer_way() {
        val goal = "open my bank's USSD"
        val ask = BankGuide.decide(goal, emptyList(), null) as AgentDecision.CallTool
        assertEquals("Which bank? I can open Stanbic Bank, Zenith Bank, UBA, Republic Bank, Absa or GhanaPay.", ask.args.getString("question"))
        val q = "ask_user(\"${ask.args.getString("question")}\") → the user answered: "
        assertEquals("zenith", (BankGuide.decide(goal, listOf(q + "\"Zenith\""), null) as AgentDecision.CallTool).args.getString(BankUssdTool.ARG_BANK))
        assertTrue((BankGuide.decide(goal, listOf(q + "\"Ecobank\""), null) as AgentDecision.Done).summary.startsWith("I can't open Ecobank's menu"))
        assertTrue((BankGuide.decide(goal, listOf(q + "\"my uncle\""), null) as AgentDecision.Done).summary.startsWith(BankGuide.NOT_UNDERSTOOD))
        assertTrue((BankGuide.decide("send money from my Fidelity account", emptyList(), null) as AgentDecision.Done).summary
            .startsWith("I can't open Fidelity's menu: I only open bank codes I've checked, for Stanbic Bank, Zenith Bank, UBA, Republic Bank, Absa and GhanaPay."))
    }

    @Test fun the_bank_tool_is_never_offered_to_a_model() {
        val reg = com.tekyerema.tools.ToolRegistry(com.tekyerema.tools.defaultTools()) { true }
        val names = (0 until reg.openAiTools().length()).map { reg.openAiTools().getJSONObject(it).getJSONObject("function").getString("name") }
        assertFalse(BankUssdTool.NAME in names)
        assertTrue("momo_send" in names)
        assertTrue(com.tekyerema.agent.ToolSelector.search("bank ussd menu transfer", com.tekyerema.tools.defaultTools()).none { it.name == BankUssdTool.NAME })
        assertEquals("the on-phone guide still reaches it", BankUssdTool, reg.get(BankUssdTool.NAME))
    }

    @Test fun the_money_plan_runs_before_any_brain_and_leaves_other_requests_alone() {
        assertTrue(MoneyPlan.decide("send money from my Stanbic account", emptyList()) is AgentDecision.CallTool)
        assertTrue(MoneyPlan.decide("I sent money to the wrong number", emptyList()) is AgentDecision.Done)
        assertNull(MoneyPlan.decide("open whatsapp", emptyList()))
        assertNull(MoneyPlan.decide("send 5 cedis to 0553238011", emptyList()))
        assertNull(MoneyPlan.decide("try again", emptyList()))
        assertNull(MoneyPlan.rewrite("try again"))
    }
}
