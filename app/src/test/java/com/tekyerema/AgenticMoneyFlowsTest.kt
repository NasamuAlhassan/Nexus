package com.tekyerema

import com.tekyerema.android.ContactResolver
import com.tekyerema.core.config.AppConfig
import com.tekyerema.money.TelcoGuide
import com.tekyerema.money.TelcoUssd
import com.tekyerema.money.TelcoUssdTool
import com.tekyerema.money.ussd.MenuModel
import com.tekyerema.money.ussd.SemanticMatch
import com.tekyerema.money.ussd.SlotKind
import com.tekyerema.money.ussd.TxIntent
import com.tekyerema.money.ussd.UssdNavigatorTool
import com.tekyerema.operator.OperatorPolicy
import com.tekyerema.policy.PolicyConfig
import com.tekyerema.policy.RiskTier
import com.tekyerema.tools.ToolResult
import com.tekyerema.tools.defaultTools
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The final check of the agentic behaviour (owner, 2 October 2026): a simple instruction is understood whole and the
 * menus are walked by intent; Tɛkyerɛma interrupts only for what is genuinely missing, reads a transaction back ONCE, and
 * keeps the money and PIN checkpoints. One flow per section: Mobile Money, airtime, data, balance, mashup.
 */
class AgenticMoneyFlowsTest {
    @Before fun setUp() { AppConfig.loadFrom(emptyList()); OperatorPolicy.resetForTest(); TelcoGuide.resetForTest() }
    @After fun tearDown() { AppConfig.loadFrom(emptyList()); OperatorPolicy.resetForTest(); TelcoGuide.resetForTest() }

    /** MTN's real *170# main menu (A20e, 30 September 2026) — rows as the phone reads them, "&" with no spaces. */
    private val mtnMain = listOf("1" to "Transfer Money", "2" to "MoMoPay&Pay Bill", "3" to "Airtime&Bundles",
        "4" to "Allow Cash Out", "5" to "Financial")
    private fun sure(v: SemanticMatch.Verdict?) = v != null && v.confidence >= SemanticMatch.actBar() && v.margin >= SemanticMatch.marginBar()
    private fun tool(name: String) = defaultTools().first { it.name == name }

    // ---------------------------------------------------------------- airtime

    @Test fun airtime_finds_its_way_through_the_real_main_menu_for_self_and_for_someone_else() {
        // "Airtime&Bundles" used to score as a weak keyword hit (0.6 < the 0.75 needed to act) — and the walk asked.
        for (intent in listOf(TxIntent.BUY_AIRTIME, TxIntent.SEND_AIRTIME)) {
            val v = SemanticMatch.best(intent, mtnMain)
            assertEquals(intent.name, "3", v!!.option)
            assertTrue(intent.name + " " + v, sure(v))
        }
        // INVENTED rows (no airtime walk has been captured live): only the self / others rule is checked.
        val who = listOf("1" to "Self", "2" to "Others")
        assertEquals("1", SemanticMatch.best(TxIntent.BUY_AIRTIME, who)!!.option)
        assertTrue(sure(SemanticMatch.best(TxIntent.BUY_AIRTIME, who)))
        assertEquals("2", SemanticMatch.best(TxIntent.SEND_AIRTIME, who)!!.option)
        assertTrue(sure(SemanticMatch.best(TxIntent.SEND_AIRTIME, who)))
    }

    // ---------------------------------------------------------------- menus that need a page turn or a value

    @Test fun mtns_hash_for_next_is_a_page_turn_not_an_option() {
        val real = "More offers await on the new MoMo App 1) MoMo User 2) Non MoMo User 3) Send with Care 4) Favorite " +
            "5) Other Networks 6) Bank Account 7) # for next"
        assertEquals("#", TelcoUssd.moreOption(real))
        assertEquals("99", TelcoUssd.moreOption("Welcome to the Bundle Portal. Please select an option 1. Proceed to buy bundle (No 4G bonus) 99. More"))
        assertNull(TelcoUssd.moreOption("1) Transfer Money 2) MoMoPay&Pay Bill"))
        assertNull("the row that says it is never chosen as an answer",
            SemanticMatch.best(TxIntent.CHECK_BALANCE, listOf("7" to "# for next")))
    }

    @Test fun a_prompt_for_a_value_is_not_a_menu_because_it_states_a_range() {
        val m = MenuModel.read("Enter amount (1 - 5000)", hasInput = true, masked = false)
        assertEquals(MenuModel.Kind.INPUT, m.kind)
        assertEquals(SlotKind.AMOUNT, m.slot)
        assertEquals(MenuModel.Kind.MENU, MenuModel.read("1) MoMo User 2) Non MoMo User", true, false).kind)
        assertEquals("one real option is still a menu", MenuModel.Kind.MENU, MenuModel.read("Select 1. Proceed to buy bundle", true, false).kind)
    }

    // ---------------------------------------------------------------- balance: no "shall I?"

    @Test fun a_balance_check_is_not_confirmed_and_everything_else_still_is() {
        val ussd = tool("run_ussd")
        assertTrue(PolicyConfig.readOnlyCheck(ussd, JSONObject().put("code", "*124#")))
        assertFalse(PolicyConfig.readOnlyCheck(ussd, JSONObject().put("code", "*170#")))
        assertFalse(PolicyConfig.readOnlyCheck(ussd, JSONObject().put("code", "*124*1#")))
        assertEquals("the coded tier is untouched for every other layer", RiskTier.TIER2, ussd.riskTier)
        assertEquals(RiskTier.TIER3, PolicyConfig.argTier(ussd, JSONObject().put("code", "*170#")))

        val nav = tool(UssdNavigatorTool.NAME)
        assertEquals(RiskTier.TIER3, nav.riskTier)
        assertTrue(PolicyConfig.readOnlyCheck(nav, JSONObject().put(UssdNavigatorTool.ARG_INTENT, UssdNavigatorTool.CHECK_BALANCE)))
        assertFalse("a send is always read back", PolicyConfig.readOnlyCheck(nav, JSONObject().put("number", "0552093690").put("amount", "5")))
        assertFalse(PolicyConfig.readOnlyCheck(nav, JSONObject().put(UssdNavigatorTool.ARG_INTENT, UssdNavigatorTool.BUY_AIRTIME).put("amount", "5")))

        // Opening the data-BALANCE menu is not confirmed; opening a menu that sells something is.
        assertNull(TelcoUssdTool.argTier(JSONObject().put(TelcoUssdTool.ARG_ACTION, TelcoUssdTool.OPEN).put(TelcoUssdTool.ARG_FLOW, "data_balance")))
        assertEquals(RiskTier.TIER3, TelcoUssdTool.argTier(JSONObject().put(TelcoUssdTool.ARG_ACTION, TelcoUssdTool.OPEN).put(TelcoUssdTool.ARG_FLOW, "data")))
        assertEquals(RiskTier.TIER3, TelcoUssdTool.argTier(JSONObject().put(TelcoUssdTool.ARG_ACTION, TelcoUssdTool.OPEN).put(TelcoUssdTool.ARG_FLOW, "mashup")))
    }

    // ---------------------------------------------------------------- data and mashup: one read-back with everything in it

    private fun open(goal: String) = (TelcoUssd.parse(goal, "mtn") as TelcoUssd.Request.Open).flow

    @Test fun a_bundle_request_carries_its_amount_and_who_it_is_for() {
        val mash = open("send 10 pesewas mashup to Prince")
        assertEquals("mashup", mash.key)
        assertEquals("0.10", mash.amount)
        assertEquals("the person used to be ignored and the bundle bought for the user's own phone", "Prince", mash.name)
        assertEquals("Prince Melanin", open("buy data for Prince Melanin").name)
        assertEquals("my mother", open("buy a mashup bundle for my mother").name)
        assertEquals("0552093690", open("buy 5 cedis mashup for 0552093690").number)
        assertNull(open("buy 5 cedis mashup for 0552093690").name)
        // Not people: the user themselves, a purpose, a period.
        for (self in listOf("buy data for me", "buy a data bundle for my phone", "I want to buy data", "buy data for browsing",
                "buy a data bundle for the week", "buy data for 3 days", "I want to buy mashup"))
            assertNull(self, open(self).name)
        // Only a figure said as MONEY is an amount: one gigabyte is not one cedi.
        assertNull(open("buy 1 gig of data").amount)
        assertEquals("10", open("buy a 10 cedi data bundle").amount)
        assertEquals("0.1", open("Send ₵0.1 mashup to 0552093690").amount)
        assertEquals("an MTN Mashup bundle", mash.what)
    }

    private val several: (String, List<ContactResolver.Contact>) -> ToolResult =
        { said, m -> ToolResult(false, "More than one contact matches $said: ${m.size}", needsDisambiguation = true) }

    @Test fun the_person_is_looked_up_before_the_read_back_and_never_replaced_by_the_user() {
        val args = JSONObject().put(TelcoUssdTool.ARG_ACTION, TelcoUssdTool.OPEN).put(TelcoUssdTool.ARG_FLOW, "mashup")
            .put(TelcoUssdTool.ARG_CODE, "*567#").put(TelcoUssdTool.ARG_WHAT, "an MTN Mashup bundle").put(TelcoUssdTool.ARG_NET, "MTN")
            .put(TelcoUssdTool.ARG_AMOUNT, "0.10").put(TelcoUssdTool.ARG_CONTACT, "prince melanin")
        assertNull(TelcoUssdTool.beneficiary(args, { listOf(ContactResolver.Contact("Prince Melanin", "+233552093690")) }, several))
        assertEquals("0552093690", args.getString(TelcoUssdTool.ARG_NUMBER))
        assertEquals("Buy an MTN Mashup bundle for 0.10 cedis, for Prince Melanin, on 0 5 5, 2 0 9, 3 6 9 0, an MTN number?\n" +
            "I'll go through MTN's menu and stop for you to confirm the payment and enter your PIN.", TelcoUssdTool.confirmationPrompt(args))

        fun named(who: String) = JSONObject().put(TelcoUssdTool.ARG_ACTION, TelcoUssdTool.OPEN).put(TelcoUssdTool.ARG_CONTACT, who)
        assertTrue(TelcoUssdTool.beneficiary(named("Kofi"), { emptyList() }, several)!!.message.startsWith("I couldn't find “Kofi” in your contacts, so nothing was bought."))
        assertTrue(TelcoUssdTool.beneficiary(named("Prince"),
            { listOf(ContactResolver.Contact("Prince Mensah", "0240000001"), ContactResolver.Contact("Prince Melanin", "0552093690")) }, several)!!.needsDisambiguation)
        assertTrue(TelcoUssdTool.beneficiary(named("her"), { error("never looked up") }, several)!!.message.startsWith("I don't know who “her” is"))
        assertNull("nobody named: nothing to resolve", TelcoUssdTool.beneficiary(JSONObject().put(TelcoUssdTool.ARG_ACTION, TelcoUssdTool.OPEN), { error("never") }, several))
    }

    @Test fun a_plain_open_keeps_its_guided_wording_and_a_purchase_says_what_is_being_bought() {
        val plain = JSONObject().put(TelcoUssdTool.ARG_ACTION, TelcoUssdTool.OPEN).put(TelcoUssdTool.ARG_FLOW, "data")
            .put(TelcoUssdTool.ARG_CODE, "*138#").put(TelcoUssdTool.ARG_WHAT, "a data bundle").put(TelcoUssdTool.ARG_NET, "MTN")
        assertTrue(TelcoUssdTool.confirmationPrompt(plain).startsWith("Open MTN's menu for a data bundle, star 1 3 8 hash?"))
        plain.put(TelcoUssdTool.ARG_AMOUNT, "10")
        assertEquals("Buy a data bundle for 10 cedis?\nI'll go through MTN's menu and stop for you to confirm the payment and enter your PIN.",
            TelcoUssdTool.confirmationPrompt(plain))
    }

    @Test fun the_one_offer_with_the_price_that_was_said_is_chosen() {
        // INVENTED offer rows in three plausible price shapes (no priced list has been captured live).
        val offers = listOf("1" to "MashUp GHC 1", "2" to "MashUp GHC 5", "3" to "MashUp GHC 10", "0" to "Back")
        assertEquals("2", TelcoGuide.priceRow(offers, "5")!!.first)
        assertEquals("3", TelcoGuide.priceRow(offers, "10")!!.first)
        assertNull("no such price", TelcoGuide.priceRow(offers, "7"))
        assertEquals("1", TelcoGuide.priceRow(listOf("1" to "500MB @ 5.00 cedis", "2" to "5GB for GHS 50"), "5")!!.first)
        assertNull("a size is not a price", TelcoGuide.priceRow(listOf("1" to "10GB monthly", "2" to "10 days bundle"), "10"))
        assertNull("two offers at that price: the user chooses", TelcoGuide.priceRow(listOf("1" to "Voice GHS 5", "2" to "Data GHS 5"), "5"))
    }
}
