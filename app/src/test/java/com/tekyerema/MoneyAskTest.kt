package com.tekyerema

import com.tekyerema.money.MoneyAsk
import com.tekyerema.money.MoneyAsk.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Finance audit 2026-10-01 — a spoken English money request understood on the phone: the A20e sweep's phrasings, slot by slot. */
class MoneyAskTest {
    private fun send(amount: String? = null, number: String? = null, bad: String? = null, name: String? = null, ref: String? = null) =
        MoneyAsk.Ask(Kind.SEND, amount, number, bad, name, ref)

    private fun air(amount: String? = null, number: String? = null, name: String? = null, self: Boolean = true) =
        MoneyAsk.Ask(Kind.AIRTIME, amount, number, null, name, null, self)

    private fun none(vararg requests: String) { for (r in requests) assertNull(r, MoneyAsk.parse(r)) }

    // ---- send money: the sweep's phrasings (N01–N16)

    @Test fun the_sweeps_send_phrasings_are_understood() {
        assertEquals(send("50", name = "Prince Melanin"), MoneyAsk.parse("send fifty cedis to Prince Melanin"))
        assertEquals(send("50", name = "Prince Melanin"), MoneyAsk.parse("I want to send Prince Melanin fifty"))
        assertEquals(send("50", name = "Prince Melanin"), MoneyAsk.parse("please transfer 50 to Prince Melanin"))
        assertEquals(send("50", number = "0552093690"), MoneyAsk.parse("send 50 to 0552093690"))
        assertEquals(send("20", name = "Prince Melanin"), MoneyAsk.parse("momo 20 cedis to Prince Melanin"))
        assertEquals(send("5", name = "Prince Melanin", ref = "lunch"), MoneyAsk.parse("send Prince Melanin 5 cedis for lunch"))
        assertEquals(send("10", name = "Prince Melanin"), MoneyAsk.parse("pay Prince Melanin 10 cedis"))
        assertEquals(send("50", name = "Prince"), MoneyAsk.parse("send 50 cedis to Prince"))
        assertEquals(send("2500", name = "Prince Melanin"), MoneyAsk.parse("send 2500 cedis to Prince Melanin"))
        assertEquals(send("50", name = "Richmond's Dad"), MoneyAsk.parse("send 50 cedis to Richmond's Dad"))
        assertEquals(send("50", name = "Richmond's Dad"), MoneyAsk.parse("send 50 cedis to Richmond’s Dad."))
    }

    @Test fun a_missing_slot_is_left_empty_for_the_caller_to_ask() {
        assertEquals("no amount", send(name = "Prince Melanin"), MoneyAsk.parse("send money to Prince Melanin"))
        assertEquals("no recipient", send("50"), MoneyAsk.parse("send 50 cedis"))
        assertEquals("a pronoun names nobody", send("50"), MoneyAsk.parse("send him 50 cedis"))
        assertEquals(send(name = "Kofi"), MoneyAsk.parse("transfer to Kofi"))
    }

    @Test fun amounts_in_words_pesewas_and_zero() {
        assertEquals("the 'and' is part of the number", send("120", number = "0552093690"),
            MoneyAsk.parse("send one hundred and twenty cedis to 0552093690"))
        assertEquals("50 cedis 50 pesewas is 50.50, not 0.50", send("50.5", number = "0552093690"),
            MoneyAsk.parse("send 50 cedis 50 pesewas to 0552093690"))
        assertEquals("zero is passed on for the caller to refuse", send("0", number = "0552093690"),
            MoneyAsk.parse("send 0 cedis to 0552093690"))
        assertEquals(send("0.1", number = "0552093690"), MoneyAsk.parse("send 10 pesewas to 0552093690"))
        assertEquals(send("50", name = "Kofi"), MoneyAsk.parse("give Kofi 50 cedis"))
    }

    @Test fun a_phone_number_is_never_the_amount() {
        // 29 Sep, live: "Send money to 0553238" was read back as 553238 cedis.
        assertEquals(send(bad = "0553238"), MoneyAsk.parse("Send money to 0553238"))
        assertEquals(send("50", bad = "055209369"), MoneyAsk.parse("send 50 cedis to 055209369"))
        assertEquals(send("50", number = "0552093690"), MoneyAsk.parse("send 50 cedis to 055 209 3690"))
        assertEquals(send("50", number = "0552093690"), MoneyAsk.parse("send 50 cedis to +233 55 209 3690"))
        assertEquals(send("5", number = "0552093690"), MoneyAsk.parse("send 5 cedis to 0 5 5 2 0 9 3 6 9 0"))
        assertEquals("the number said out loud wins over the name", send("50", number = "0552093690"),
            MoneyAsk.parse("transfer 50 cedis to Kofi on 0552093690"))
    }

    @Test fun references_politeness_and_tails() {
        assertEquals(send("20", number = "0244123456", ref = "school fees"),
            MoneyAsk.parse("I'd like to send 20 cedis to 0244123456 for school fees"))
        assertEquals(send("5", name = "Kofi", ref = "Prince"), MoneyAsk.parse("send 5 cedis to Kofi, use Prince as the reference"))
        assertEquals(send("50", name = "Kofi", ref = "buy food"), MoneyAsk.parse("send 50 cedis to Kofi to buy food"))
        assertEquals("a reason is not a purchase", send("5", name = "Kofi", ref = "credit"), MoneyAsk.parse("send Kofi 5 cedis for credit"))
        assertEquals(send("50", name = "Kofi"), MoneyAsk.parse("send 50 cedis to Kofi with mobile money"))
        assertEquals(send("50", name = "Prince Melanin"), MoneyAsk.parse("send 50 cedis to Prince Melanin please"))
        assertEquals(send("50", name = "Kofi"), MoneyAsk.parse("can you send 50 cedis to Kofi"))
        assertEquals(send("50", name = "Prince"), MoneyAsk.parse("Tɛkyerɛma, send 50 cedis to Prince"))
    }

    // ---- not a send: the planner's, the safety layer's, or the older rules'

    @Test fun messages_bills_scam_shapes_questions_and_compounds_are_left_alone() {
        none("send a message to Kofi saying I sent 50 cedis", "send Kofi a photo", "send my location to Ama",
            "pay my light bill", "pay DSTV 100 cedis", "pay 20 cedis to the merchant", "send 50 cedis to my bank account",
            "pay 500 cedis school fees",
            "send it back to 0552093690", "return the money to 0552093690",
            "cash out 50 cedis", "withdraw 100 cedis",
            "how do I send money", "how much does it cost to send 50 cedis", "can you send money",
            "what is the cheapest way to send 50 cedis to Kofi",
            "set my momo limit to 200 cedis",
            "dial *170# and send 5 cedis to Kofi",
            "send 20 cedis to Ama and then call her", "send 50 cedis to Kofi and tell him", "send 50 cedis to Kofi and Ama",
            "I sent money to the wrong number", "did the message go through")
    }

    @Test fun without_money_evidence_it_is_not_a_send() {
        none("send 50", "give me 5 minutes", "transfer my contacts to the new phone", "send him the file")
    }

    @Test fun twi_and_data_keep_their_old_routes() {
        none("fa 5 cedis ma Kofi", "tɔ airtime 5 cedis", "buy 1 gig of data", "buy a 10 cedi data bundle",
            "send 5 cedis data to Kofi", "buy data for Prince Melanin")
    }

    // ---- airtime

    @Test fun airtime_keeps_the_person_it_is_for() {
        assertEquals(air("5", number = "0552093690", self = false), MoneyAsk.parse("buy 5 cedis credit for 0552093690"))
        assertEquals(air("5", name = "Prince Melanin", self = false), MoneyAsk.parse("send 5 cedis airtime to Prince Melanin"))
        assertEquals(air(name = "my mother", self = false), MoneyAsk.parse("buy airtime for my mother"))
        assertEquals("someone else, not yet named", air(self = false), MoneyAsk.parse("buy airtime for him"))
    }

    @Test fun airtime_for_the_users_own_line() {
        assertEquals(air("10"), MoneyAsk.parse("buy me ten cedis airtime"))
        assertEquals(air("2"), MoneyAsk.parse("top up my phone with 2 cedis"))
        assertEquals(air(), MoneyAsk.parse("buy airtime"))
        assertEquals(air("10"), MoneyAsk.parse("buy 10 cedis airtime for me"))
        assertEquals(air("5"), MoneyAsk.parse("recharge 5 cedis"))
        assertEquals(air("2"), MoneyAsk.parse("get me 2 cedis credit"))
        assertEquals(air("5"), MoneyAsk.parse("buy call credit of 5 cedis"))
        none("what's the price of airtime", "buy 5 cedis airtime and then call Kofi")
    }

    // ---- balances

    @Test fun balance_questions() {
        for (q in listOf("check my balance", "what's my balance", "how much do I have left", "my balance"))
            assertEquals(q, MoneyAsk.Ask(Kind.BALANCE_UNCLEAR), MoneyAsk.parse(q))
        for (q in listOf("how much money do I have", "how much is in my wallet", "how much is in my account", "check my mobile money balance"))
            assertEquals(q, MoneyAsk.Ask(Kind.BALANCE_MOMO), MoneyAsk.parse(q))
        for (q in listOf("what is my airtime balance", "how much credit do I have"))
            assertEquals(q, MoneyAsk.Ask(Kind.BALANCE_AIRTIME), MoneyAsk.parse(q))
        none("check my bank balance", "check my data balance", "how much battery do I have left", "set white balance")
    }

    @Test fun the_answer_to_mobile_money_or_airtime() {
        assertEquals(Kind.BALANCE_MOMO, MoneyAsk.balanceKind("the mobile money one"))
        assertEquals(Kind.BALANCE_MOMO, MoneyAsk.balanceKind("momo"))
        assertEquals(Kind.BALANCE_MOMO, MoneyAsk.balanceKind("the first one"))
        assertEquals(Kind.BALANCE_AIRTIME, MoneyAsk.balanceKind("airtime"))
        assertEquals(Kind.BALANCE_AIRTIME, MoneyAsk.balanceKind("my credit"))
        assertEquals(Kind.BALANCE_AIRTIME, MoneyAsk.balanceKind("the second one"))
        assertNull(MoneyAsk.balanceKind("both"))
    }

    // ---- status, last, spent

    @Test fun did_it_go_through_and_what_was_my_last_transaction() {
        for (q in listOf("did it go through", "did the money go", "did the payment go through", "did the transfer go through?",
                "was it successful", "did he get the money", "did she get the money", "has it been sent", "did my transfer work"))
            assertEquals(q, MoneyAsk.Ask(Kind.STATUS), MoneyAsk.parse(q))
        for (q in listOf("what was my last transaction", "who did I last send money to", "my last momo", "check my last payment"))
            assertEquals(q, MoneyAsk.Ask(Kind.LAST), MoneyAsk.parse(q))
        for (q in listOf("how much have I sent today", "how much have I spent today", "how much have I spent today on momo"))
            assertEquals(q, MoneyAsk.Ask(Kind.SPENT), MoneyAsk.parse(q))
    }

    // ---- answers to the caller's questions

    @Test fun an_answer_to_how_much() {
        assertEquals("50", MoneyAsk.amount("fifty"))
        assertEquals("50", MoneyAsk.amount("50 cedis"))
        assertEquals("50", MoneyAsk.amount("make it fifty"))
        assertEquals("2.5", MoneyAsk.amount("2 cedis 50"))
        assertEquals("2.5", MoneyAsk.amount("2.50"))
        assertEquals("0.5", MoneyAsk.amount("fifty pesewas"))
        assertEquals("0.1", MoneyAsk.amount("10 pesewas"))
        assertEquals("50.2", MoneyAsk.amount("50 cedis and 20 pesewas"))
        assertEquals("120", MoneyAsk.amount("one hundred and twenty"))
        assertEquals("1200", MoneyAsk.amount("one thousand two hundred"))
        assertEquals("2500", MoneyAsk.amount("two thousand five hundred"))
        assertEquals("100", MoneyAsk.amount("a hundred cedis"))
        assertEquals("25", MoneyAsk.amount("twenty-five cedis"))
        assertEquals("1250", MoneyAsk.amount("1,250"))
        assertEquals("20", MoneyAsk.amount("GHS 20"))
        assertNull("no figure", MoneyAsk.amount("nothing"))
        assertNull("2.50 or 250? ask again", MoneyAsk.amount("two fifty"))
        assertNull("a phone number is not an amount", MoneyAsk.amount("0552093690"))
    }

    @Test fun an_answer_to_who_to() {
        assertEquals(send(number = "0552093690"), MoneyAsk.recipient("0 5 5, 2 0 9, 3 6 9 0"))
        assertEquals(send(number = "0552093690"), MoneyAsk.recipient("+233 55 209 3690"))
        assertEquals(send(bad = "055209369"), MoneyAsk.recipient("055209369"))
        assertEquals(send(name = "Prince Melanin"), MoneyAsk.recipient("Prince Melanin please"))
        assertEquals(send(name = "Richmond's Dad"), MoneyAsk.recipient("to Richmond’s Dad"))
        assertEquals(send(name = "Kofi Mensah"), MoneyAsk.recipient("it's Kofi Mensah"))
        assertNull(MoneyAsk.recipient(""))
        assertNull("a pronoun names nobody", MoneyAsk.recipient("him"))
    }
}
