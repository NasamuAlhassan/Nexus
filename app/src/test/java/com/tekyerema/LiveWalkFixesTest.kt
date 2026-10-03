package com.tekyerema

import com.tekyerema.accessibility.KeyPattern
import com.tekyerema.agent.AgentDecision
import com.tekyerema.core.config.AppConfig
import com.tekyerema.money.BankUssd
import com.tekyerema.money.TelcoGuide
import com.tekyerema.money.TelcoSession
import com.tekyerema.money.TelcoUssd
import com.tekyerema.money.TelcoUssdTool
import com.tekyerema.money.ussd.SemanticMatch
import com.tekyerema.money.ussd.TxIntent
import com.tekyerema.voice.PromptSurface
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The owner's first live walk of the navigator (A20e, 2 October 2026, "Send 10 pesewas to Prince"): it chose Transfer
 * Money, then stopped at MTN's second menu — the REAL screen below — said "I'm not sure which option means send money
 * here", read all seven options, and could be neither interrupted nor answered by typing. A test sentence that arrived
 * at that moment was typed into the menu as it was ("Incorrect choice, try again").
 */
class LiveWalkFixesTest {
    @Before fun setUp() { AppConfig.loadFrom(emptyList()); TelcoGuide.resetForTest() }
    @After fun tearDown() { AppConfig.loadFrom(emptyList()); TelcoGuide.resetForTest() }

    /** MTN's menu after "Transfer Money", as the phone read it. */
    private val REAL = "More offers await on the new MoMo App 1) MoMo User 2) Non MoMo User 3) Send with Care 4) Favorite " +
        "5) Other Networks 6) Bank Account 7) # for next"
    private val rows = TelcoUssd.rows(REAL)

    // ---------------------------------------------------------------- the menu choice

    @Test fun the_recipients_network_decides_between_momo_user_and_other_networks() {
        assertEquals(7, rows.size)
        val same = SemanticMatch.best(TxIntent.SEND_MONEY, rows, crossNetwork = false)!!
        assertEquals("1", same.option)
        assertTrue(same.toString(), same.confidence >= SemanticMatch.actBar() && same.margin >= SemanticMatch.marginBar())
        val other = SemanticMatch.best(TxIntent.SEND_MONEY, rows, crossNetwork = true)!!
        assertEquals("5", other.option)
        assertTrue(other.toString(), other.confidence >= SemanticMatch.actBar() && other.margin >= SemanticMatch.marginBar())
        // Network not known: both are real candidates, so the navigator asks rather than guesses.
        val unknown = SemanticMatch.best(TxIntent.SEND_MONEY, rows)!!
        assertTrue(unknown.toString(), unknown.margin < SemanticMatch.marginBar())
    }

    @Test fun non_momo_user_is_never_chosen_for_the_user() {
        for (cross in listOf(false, true, null))
            assertNotEquals("2", SemanticMatch.best(TxIntent.SEND_MONEY, rows, crossNetwork = cross)?.option)
        assertNull(SemanticMatch.best(TxIntent.SEND_MONEY, listOf("1" to "Non MoMo User", "2" to "Bank Account")))
        // "Non MTN" is a wording of "another network", not a negation of the intent.
        assertEquals("2", SemanticMatch.best(TxIntent.SEND_MONEY, listOf("1" to "MTN User", "2" to "Non MTN"), crossNetwork = true)!!.option)
    }

    @Test fun a_row_that_only_sounds_close_does_not_turn_a_clear_menu_into_a_question() {
        // A fake embedder that finds "Send with Care" very close to "send money" (0.74 after mapping).
        val embed: (String) -> FloatArray = { s -> if (s.contains("send")) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f) }
        val v = SemanticMatch.best(TxIntent.SEND_MONEY, rows, embed, crossNetwork = false)!!
        assertEquals("1", v.option)
        assertTrue(v.toString(), v.margin >= SemanticMatch.marginBar())
    }

    // ---------------------------------------------------------------- the guided fallback

    private val flow = TelcoUssd.Flow(TelcoUssd.NAV, "*170#", emptyList(), "send money", "MTN",
        number = "0552093690", amount = "0.1", reference = "Payment", name = "Prince")
    private fun view(screen: String) = TelcoSession.View(flow, screen, BankUssd.ScreenKind.MENU, 1L, 1L, userDrove = true)
    private val handOver = "ussd_navigate({\"amount\":\"0.1\",\"number\":\"0552093690\"}) → success [native]; verified=true (the network's screens " +
        "were read and matched); I'm not sure which option means send money here. Which number should I enter?"
    private fun answered(q: String, a: String) = "ask_user(\"$q\") → the user answered: \"$a\""
    private fun decide(history: List<String>, screen: String = REAL) =
        TelcoGuide.decide("send 10 pesewas to Prince", history, view(screen), "mtn")
    private fun question(d: AgentDecision?): String { d as AgentDecision.CallTool; assertEquals("ask_user", d.name); return d.args.getString("question") }
    private fun reply(d: AgentDecision?): org.json.JSONObject { d as AgentDecision.CallTool; assertEquals(TelcoUssdTool.NAME, d.name); return d.args }

    @Test fun a_menu_is_read_as_its_options_without_the_advert_above_them() {
        val q = question(decide(listOf(handOver)))
        assertTrue(q, q.startsWith("MTN says: “1 MoMo User; 2 Non MoMo User; 3 Send with Care; 4 Favorite; 5 Other Networks; 6 Bank Account; " +
            "7 # for next”. What should I enter?"))
        assertFalse(q.contains("More offers await"))
        // A refusal is kept — the user needs to hear that the last entry was not accepted.
        assertTrue(TelcoGuide.heard("Incorrect choice, try again. $REAL").startsWith("Incorrect choice, try again."))
        assertEquals("Enter Amount (GHC 0.07 - 4.99)", TelcoGuide.heard("Enter Amount (GHC 0.07 - 4.99)"))
    }

    @Test fun a_sentence_is_never_typed_into_a_menu() {
        val q = question(decide(listOf(handOver)))
        val again = question(decide(listOf(handOver, answered(q, "send fifty cedis to Prince Melanin"))))
        assertTrue(again, again.startsWith(TelcoGuide.NOT_AN_OPTION))
        assertTrue(question(decide(listOf(handOver, answered(q, "karaoke")))).startsWith(TelcoGuide.NOT_AN_OPTION))
        assertTrue("two rows end with these words", question(decide(listOf(handOver, answered(q, "user")))).startsWith(TelcoGuide.NOT_AN_OPTION))
    }

    @Test fun an_option_can_be_said_as_its_number_its_place_or_its_own_words() {
        val q = question(decide(listOf(handOver)))
        for ((said, option) in listOf("1" to "1", "one" to "1", "the first one" to "1", "option 5" to "5", "MoMo user" to "1",
                "other networks" to "5", "the MoMo user one" to "1", "bank account" to "6")) {
            val args = reply(decide(listOf(handOver, answered(q, said))))
            assertEquals(said, option, args.getString(TelcoUssdTool.ARG_TEXT))
            assertFalse(said, args.optBoolean(TelcoUssdTool.ARG_AUTO))
        }
        assertEquals("1", TelcoGuide.rowNamed(rows, "MoMo User"))
        assertNull(TelcoGuide.rowNamed(rows, "the green one"))
    }

    @Test fun what_was_confirmed_up_front_is_entered_without_asking_again() {
        fun step(screen: String) = "telco_ussd({\"_action\":\"reply\",\"_text\":\"1\"}) → success [native]; verified=true (the network's next screen was read); MTN: $screen"
        for ((screen, value) in listOf("Enter mobile number" to "0552093690", "Confirm mobile number" to "0552093690",
                "Enter Amount" to "0.1", "Enter Reference" to "Payment")) {
            val args = reply(decide(listOf(handOver, step(screen)), screen))
            assertEquals(screen, value, args.getString(TelcoUssdTool.ARG_TEXT))
            assertTrue("approved in the one read-back: entered like an auto step", args.optBoolean(TelcoUssdTool.ARG_AUTO))
            assertNull("no second read-back for it", TelcoUssdTool.argTier(args))
        }
        // …but a value the network just refused is never typed again.
        val refused = "Wrong amount value. Enter Amount again"
        assertTrue(question(decide(listOf(handOver, step(refused)), refused)).startsWith("The network didn't accept that."))
    }

    // ---------------------------------------------------------------- answering at once

    @Test fun the_second_volume_press_may_come_later_while_tekyerema_is_busy() {
        var busy = false
        val keys = KeyPattern(keyCode = { android.view.KeyEvent.KEYCODE_VOLUME_DOWN }, windowMs = { if (busy) 700L else 450L })
        assertFalse(keys.onKeyUp(android.view.KeyEvent.KEYCODE_VOLUME_DOWN, 1_000))
        assertFalse("600 ms apart while idle: two volume presses, not the gesture", keys.onKeyUp(android.view.KeyEvent.KEYCODE_VOLUME_DOWN, 1_600))
        busy = true
        assertTrue("600 ms apart while it is talking: the gesture", keys.onKeyUp(android.view.KeyEvent.KEYCODE_VOLUME_DOWN, 2_200))
    }

    @Test fun a_question_and_a_confirmation_can_be_typed_but_never_the_secure_step() {
        assertTrue(PromptSurface.canType(PromptSurface.Kind.QUESTION))
        assertTrue(PromptSurface.canType(PromptSurface.Kind.CONFIRM))
        assertFalse(PromptSurface.canType(PromptSurface.Kind.SECURE))
    }
}
