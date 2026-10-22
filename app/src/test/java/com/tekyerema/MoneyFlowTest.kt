package com.tekyerema

import com.tekyerema.agent.AgentDecision
import com.tekyerema.core.config.AppConfig
import com.tekyerema.money.MoneyFlow
import com.tekyerema.money.MoneyPlan
import com.tekyerema.money.MoneyStatusTool
import com.tekyerema.money.ussd.UssdNavigatorTool
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Finance audit (2026-10-01): the on-phone plan for a spoken financial request. Every request below is from the decline-only
 * sweep on the A20e that night (audit/finance-2026-10-01/sweep1.out) — what each one did THEN is in the comment.
 */
class MoneyFlowTest {
    // These pin the walk itself; the reference question the owner asked for (14 Oct 2026) is pinned in DemoStepsTest.
    @Before fun setUp() = AppConfig.loadFrom(listOf("money.json" to """{"money":{"flow":{"askReference":false}}}"""))
    @After fun tearDown() = AppConfig.loadFrom(emptyList())

    private fun call(goal: String, history: List<String> = emptyList()) = MoneyFlow.decide(goal, history) as AgentDecision.CallTool
    private fun asked(q: String, a: String) = "ask_user(\"$q\") → the user answered: \"$a\""
    private fun question(d: AgentDecision?): String {
        d as AgentDecision.CallTool
        assertEquals("ask_user", d.name)
        return d.args.getString("question")
    }

    // ---------------------------------------------------------------- send

    @Test fun every_wording_of_a_send_to_a_contact_takes_the_same_route() {
        // Then: mobile_money with the name as a string (contact never looked up); or the cloud planner, which opened the
        // Contacts app, asked for "a reference", asked for the phone number, or said "the main AI is busy".
        for (goal in listOf("send fifty cedis to Prince Melanin", "I want to send Prince Melanin fifty", "please transfer 50 to Prince Melanin",
                "pay Prince Melanin 50 cedis", "momo 50 cedis to Prince Melanin")) {
            val d = call(goal)
            assertEquals(goal, UssdNavigatorTool.NAME, d.name)
            assertEquals(goal, "Prince Melanin", d.args.getString(UssdNavigatorTool.ARG_CONTACT))
            assertEquals(goal, "50", d.args.getString("amount"))
            assertFalse(goal, d.args.has("number"))
            assertEquals(MoneyFlow.R_SEND, d.rationale)
        }
        val big = call("send Kofi 5 thousand cedis")          // was: 5 cedis to a person called "Kofi thousand"
        assertEquals("5000", big.args.getString("amount"))
        assertEquals("Kofi", big.args.getString(UssdNavigatorTool.ARG_CONTACT))
        val lunch = call("send Prince Melanin 5 cedis for lunch")
        assertEquals("lunch", lunch.args.getString("reference"))
        assertEquals("5", lunch.args.getString("amount"))
    }

    @Test fun a_send_to_a_number_needs_no_currency_word_and_no_reference_question() {
        // Then: "send 50 to 0552093690" → the cloud planner asked "What reference would you like to use for the transfer?".
        for (goal in listOf("send 50 to 0552093690", "send one hundred and twenty cedis to 0552093690", "send 50 cedis to +233 55 209 3690")) {
            val d = call(goal)
            assertEquals(goal, UssdNavigatorTool.NAME, d.name)
            assertEquals(goal, "0552093690", d.args.getString("number"))
            assertFalse(goal, d.args.has("reference"))
        }
        assertEquals("120", call("send one hundred and twenty cedis to 0552093690").args.getString("amount"))
        // Then: read back as 0.5 cedis.
        assertEquals("50.5", call("send 50 cedis 50 pesewas to 0552093690").args.getString("amount"))
    }

    @Test fun a_missing_amount_or_person_is_asked_for_once_and_never_guessed() {
        val goal = "send money to Prince Melanin"
        val q1 = question(MoneyFlow.decide(goal, emptyList()))
        assertEquals("How much do you want to send to Prince Melanin?", q1)
        val d = call(goal, listOf(asked(q1, "fifty cedis")))
        assertEquals("50", d.args.getString("amount"))
        assertEquals("Prince Melanin", d.args.getString(UssdNavigatorTool.ARG_CONTACT))
        // An answer that isn't an amount: one more try, worded differently; then it stops — nothing is sent.
        val q2 = question(MoneyFlow.decide(goal, listOf(asked(q1, "I don't know"))))
        assertEquals("How much, in cedis? For example, say “twenty cedis”.", q2)
        assertEquals(MoneyFlow.GAVE_UP, (MoneyFlow.decide(goal, listOf(asked(q1, "erm"), asked(q2, "erm"))) as AgentDecision.Done).summary)

        val who = question(MoneyFlow.decide("send 50 cedis", emptyList()))
        assertEquals("Who should I send the 50 cedis to? Say a name from your contacts, or the phone number.", who)
        assertEquals("0552093690", call("send 50 cedis", listOf(asked(who, "0 5 5, 2 0 9, 3 6 9 0"))).args.getString("number"))
        assertEquals("Prince Melanin", call("send 50 cedis", listOf(asked(who, "Prince Melanin"))).args.getString(UssdNavigatorTool.ARG_CONTACT))
    }

    @Test fun zero_is_asked_about_and_a_short_number_is_said_back() {
        // Then: "Send 0 cedis to 0 5 5, 2 0 9, 3 6 9 0, an MTN number, with Mobile Money?" was read back for a yes.
        assertEquals("I heard zero cedis. How much do you want to send to the number ending 6 9 0?",
            question(MoneyFlow.decide("send 0 cedis to 0552093690", emptyList())))
        val bad = MoneyFlow.decide("send 50 cedis to 055209369", emptyList()) as AgentDecision.Done
        assertTrue(bad.summary, bad.summary.startsWith("I heard the number 0 5 5, 2 0 9, 3 6 9 — that's 9 digits, and a Ghana mobile number has 10."))
    }

    @Test fun once_the_walk_has_run_nothing_more_is_proposed_from_here() {
        val ran = "ussd_navigate({\"amount\":\"50\",\"number\":\"0552093690\"}) → success [native]; verified=true (the network's screens were read and matched); MTN shows 50 cedis to Zelia Awini — if that's right, enter your PIN; if not, press Cancel."
        assertNull(MoneyFlow.decide("send 50 cedis to 0552093690", listOf(ran)))
        // A correction at the read-back that the engine could not apply is the planner's, not re-proposed unchanged.
        val note = "You proposed: \"Send 50 cedis…\". The user did NOT approve it — they said: \"no, that's wrong\". Treat that as a correction…"
        assertNull(MoneyFlow.decide("send 50 cedis to 0552093690", listOf(note)))
    }

    // ---------------------------------------------------------------- airtime

    @Test fun airtime_for_someone_else_keeps_the_someone_else() {
        // Then: "send 5 cedis airtime to Prince Melanin" and "buy 5 cedis credit for 0552093690" were both read back as
        // "Buy 5 cedis of airtime with Mobile Money?" — the other person silently dropped.
        val other = call("send 5 cedis airtime to Prince Melanin")
        assertEquals(UssdNavigatorTool.SEND_AIRTIME, other.args.getString(UssdNavigatorTool.ARG_INTENT))
        assertEquals("Prince Melanin", other.args.getString(UssdNavigatorTool.ARG_CONTACT))
        assertEquals("5", other.args.getString("amount"))
        val number = call("buy 5 cedis credit for 0552093690")
        assertEquals(UssdNavigatorTool.SEND_AIRTIME, number.args.getString(UssdNavigatorTool.ARG_INTENT))
        assertEquals("0552093690", number.args.getString("number"))
        val self = call("buy me ten cedis airtime")
        assertEquals(UssdNavigatorTool.BUY_AIRTIME, self.args.getString(UssdNavigatorTool.ARG_INTENT))
        assertEquals("10", self.args.getString("amount"))
        assertFalse(self.args.has("number") || self.args.has(UssdNavigatorTool.ARG_CONTACT))
        assertEquals("How much airtime do you want to buy?", question(MoneyFlow.decide("buy airtime", emptyList())))
    }

    @Test fun the_older_airtime_route_is_one_switch_away() {
        AppConfig.loadFrom(listOf("config/t.json" to "{\"money\":{\"flow\":{\"airtime\":false}}}"))
        assertNull(MoneyFlow.decide("buy me ten cedis airtime", emptyList()))
        AppConfig.loadFrom(listOf("config/t.json" to "{\"money\":{\"navigator\":{\"enabled\":false}}}"))
        assertNull("with the navigator off, the older rules decide", MoneyFlow.decide("send 50 cedis to 0552093690", emptyList()))
    }

    // ---------------------------------------------------------------- balance

    @Test fun check_my_balance_asks_which_balance() {
        // Then: escalated to the cloud planner ("The main AI is busy…").
        val q = question(MoneyFlow.decide("check my balance", emptyList()))
        assertEquals(MoneyFlow.Q_BALANCE, q)
        val momo = call("check my balance", listOf(asked(q, "mobile money")))
        assertEquals(UssdNavigatorTool.NAME, momo.name)
        assertEquals(UssdNavigatorTool.CHECK_BALANCE, momo.args.getString(UssdNavigatorTool.ARG_INTENT))
        val air = call("check my balance", listOf(asked(q, "airtime")))
        assertEquals("run_ussd", air.name)
        assertEquals("*124#", air.args.getString("code"))
        val said = "run_ussd({\"code\":\"*124#\"}) → success [native]; verified=true (the network answered); Your airtime balance is 6 pesewas."
        assertEquals("Your airtime balance is 6 pesewas.", (MoneyFlow.decide("check my balance", listOf(asked(q, "airtime"), said)) as AgentDecision.Done).summary)
        assertEquals(UssdNavigatorTool.CHECK_BALANCE, call("how much money do I have").args.getString(UssdNavigatorTool.ARG_INTENT))
        assertNull("the airtime balance keeps its own on-phone rule", MoneyFlow.decide("what is my airtime balance", emptyList()))
    }

    // ---------------------------------------------------------------- status / history

    @Test fun did_it_go_through_is_answered_from_the_phones_own_records() {
        // Then: "did that payment go through" → the cloud planner asked "Which payment are you referring to?".
        for ((goal, mode) in listOf("did that payment go through" to MoneyStatusTool.STATUS, "what was my last transaction" to MoneyStatusTool.LAST,
                "how much have I sent today" to MoneyStatusTool.SPENT, "did I receive any money today" to MoneyStatusTool.RECEIVED,
                "read my last momo message" to MoneyStatusTool.MESSAGE, "has anyone sent me money" to MoneyStatusTool.RECEIVED)) {
            val d = call(goal)
            assertEquals(goal, MoneyStatusTool.NAME, d.name)
            assertEquals(goal, mode, d.args.getString(MoneyStatusTool.ARG_MODE))
        }
        val ok = "money_status({\"mode\":\"status\"}) → success [native]; verified=true (ok); Yes — MTN confirmed 5 cedis sent to Kofi Mensah, 3 minutes ago."
        assertEquals("Yes — MTN confirmed 5 cedis sent to Kofi Mensah, 3 minutes ago.",
            (MoneyFlow.decide("did that payment go through", listOf(ok)) as AgentDecision.Done).summary)
        val failed = "money_status({\"mode\":\"received\"}) → fail [native]; verified=false (no); I need permission to read your text messages for that."
        assertTrue(MoneyFlow.decide("did I receive any money today", listOf(failed)) is AgentDecision.CannotDo)
    }

    // ---------------------------------------------------------------- what stays with the planner

    @Test fun requests_that_are_not_a_transaction_are_left_alone() {
        for (goal in listOf("send a message to Kofi saying I sent 50 cedis", "open WhatsApp", "remind me to pay my light bill tomorrow",
                "what are the momo charges for sending 100 cedis", "send 20 cedis to Ama and then call her", "set my momo limit to 200 cedis",
                "read my messages", "did I receive a message from Kofi"))
            assertNull(goal, MoneyFlow.decide(goal, emptyList()))
    }

    @Test fun a_job_with_no_checked_route_is_said_plainly_not_guessed_at() {
        // Then: "pay my light bill" → "Which phone number should I send the payment to? none?"; "withdraw 100 cedis" → a
        // question about a bank account nobody mentioned.
        // Owner, 22 Oct 2026: bills and Allow Cash Out are on the network's own menus — the brain walks there (menu_goal), never
        // a refusal that tells a blind user to dial a code. Only a withdrawal (an agent starts it) is explained.
        assertNull(MoneyFlow.notYet("pay my light bill"))
        assertNull(MoneyFlow.notYet("I want to pay my DSTV"))
        assertNull(MoneyFlow.notYet("approve the cash out"))
        assertNull(MoneyFlow.notYet("allow cash out for me"))
        assertEquals(MoneyFlow.CASH_OUT_LINE, (MoneyFlow.decide("withdraw 100 cedis", emptyList()) as AgentDecision.Done).summary)
        assertTrue(!MoneyFlow.CASH_OUT_LINE.contains("star 1 7 0") && !MoneyFlow.CASH_OUT_LINE.contains("dial"))
        assertNull("only as the first word on it — never in the middle of a task", MoneyFlow.decide("pay my light bill", listOf("open_app({}) → success [intent]; verified=true (ok); Opened ECG")))
        assertNull(MoneyFlow.decide("text Ama that I will pay the light bill", emptyList()))
    }

    @Test fun it_sits_behind_the_older_money_plans() {
        // "I sent money to the wrong number" is still the after-care guide's (it runs first in MoneyPlan).
        assertTrue(MoneyPlan.decide("I sent money to the wrong number", emptyList()) is AgentDecision.Done)
        val d = MoneyPlan.decide("send 50 cedis to 0552093690", emptyList()) as AgentDecision.CallTool
        assertEquals(UssdNavigatorTool.NAME, d.name)
    }
}
