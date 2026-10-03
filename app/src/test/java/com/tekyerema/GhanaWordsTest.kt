package com.tekyerema

import com.tekyerema.money.MoneyAsk
import com.tekyerema.voice.GhanaWords
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The repairs in voice/GhanaWords.kt, on the transcripts they were written from: our offline recogniser's output on
 * the owner's own recordings (build/asr_bench, 2 October 2026) and the phone recogniser's output in that night's live
 * runs. Each "left alone" case is a sentence that must NOT be touched.
 */
class GhanaWordsTest {
    private fun same(s: String) = assertEquals(s, GhanaWords.fix(s))

    // ---- what our own offline model wrote for the owner's clips (reference in the comment)

    @Test fun `cedis heard as cities, CDs and ceds - after a figure, in a money request`() {
        assertEquals("Send two cedis to my wife.", GhanaWords.fix("Send two cities to my wife."))          // K07
        assertEquals("Buy 20 cedis airtime for 0244123456.", GhanaWords.fix("Buy 20 cities airtime for 0244123456."))   // E17
        assertEquals("Do you want me to send 5 cedis to Kwame?", GhanaWords.fix("Do you want me to send 5 cities to Kwame?"))   // S06
        assertEquals("That cost 12 cedis.", GhanaWords.fix("That cost 12 cities."))                        // C12
        assertEquals("send 5 cedis to Ama", GhanaWords.fix("send 5 ceds to Ama"))
    }

    @Test fun `an amount on its own is an amount - the answer to How much`() {
        assertEquals("20 cedis", GhanaWords.fix("20 cities"))                    // C05
        assertEquals("5 cedis", GhanaWords.fix("5 ceds"))                        // C03
        assertEquals("2 cedis.", GhanaWords.fix("2 cds."))                       // C02
        assertEquals("50 pesewas.", GhanaWords.fix("50 ps."))                    // C08
        assertEquals("10 cedis 50 pesewas.", GhanaWords.fix("10 cities 50 ps."))   // C09
        assertEquals("2 cedis 50 pesewas.", GhanaWords.fix("2 cds 50 pesos."))     // C10
    }

    @Test fun `MoMo heard as Mumu, and Buy heard as By`() {
        assertEquals("Go to MoMo and send 5 cedis to Ama.", GhanaWords.fix("Go to Mumu and send 5 cities to Ama."))   // K21
        assertEquals("Buy 5 cedis of airtime.", GhanaWords.fix("By 5 cities of airtime."))                           // K09
        assertEquals("Buy airtime for my mother", GhanaWords.fix("by airtime for my mother"))
    }

    @Test fun `WhatsApp heard as was up, what's up and wasa - after an app word`() {
        assertEquals("Open WhatsApp.", GhanaWords.fix("Open was up."))            // K14
        assertEquals("Open WhatsApp.", GhanaWords.fix("Hoping what's up."))       // E07
        assertEquals("Close WhatsApp.", GhanaWords.fix("Close wasa."))            // E09
        assertEquals("message Ama on WhatsApp that I am coming", GhanaWords.fix("message Ama on what's up that I am coming"))
    }

    // ---- what the phone's recogniser wrote, live on the A20e

    @Test fun `pesewas heard as pesos - after a figure`() {
        assertEquals("send 10 pesewas to Kofi", GhanaWords.fix("send 10 pesos to Kofi"))
        assertEquals("ten pesewas of airtime", GhanaWords.fix("ten pesos of airtime"))
    }

    @Test fun `a Telecel number heard as a telegram number - in a money request`() {
        assertEquals("10 pesewas airtime to a Telecel number", GhanaWords.fix("10 pesewas airtime to a telegram number"))
        assertEquals("buy data for a Telecel line", GhanaWords.fix("buy data for a tele cell line"))
    }

    @Test fun `the answer to How much is repaired too - fifty pesos is never fifty cedis`() {
        assertEquals("0.5", MoneyAsk.amount("50 pesos"))
        assertEquals("0.5", MoneyAsk.amount("50 ps."))
        assertEquals("0.1", MoneyAsk.amount("ten pesos"))
        assertEquals("20", MoneyAsk.amount("20 cities"))
        assertEquals("10.5", MoneyAsk.amount("10 cities 50 ps."))
        assertEquals("2", MoneyAsk.amount("2 cds."))
        // untouched answers still read as before
        assertEquals("50", MoneyAsk.amount("fifty"))
        assertEquals("0.5", MoneyAsk.amount("fifty pesewas"))
    }

    // ---- left alone

    @Test fun `ordinary sentences keep their words`() {
        same("I visited 3 cities last year")
        same("how many cities are in Ghana")
        same("send pictures of 3 cities to Ama")
        same("convert 10 pesos to cedis")
        same("send it to Kofi on Telegram")
        same("call this telephone number 0244123456")
        same("tell me what's up")
        same("stand by me")
        same("send 10 cedis to Kofi")
        same("what is the weather in 2 cities")
    }

    // The independent review of 2 October 2026: every one of these was rewritten by the first version of the rules.
    @Test fun `a repair never rewrites the words of a message or another app's name`() {
        same("Send Ama a message that I visited 2 cities")          // the text of an SMS
        same("send Kofi a message: don't be a mumu")
        same("clear telegram data")                                 // the chat app's storage, not a network
        same("send my telegram number to Ama")
        same("what is 100 pesos in cedis")                          // a currency question
        same("go to Wasa Akropong")                                 // a town
        same("the price went up by 2 cities")                       // "by" here is not "buy"
    }

    @Test fun `where the caller knows it is about money, the amount needs no money word of its own`() {
        assertEquals("no, 20 cedis", GhanaWords.fix("no, 20 cities", aboutMoney = true))
        assertEquals("no, 20 cities", GhanaWords.fix("no, 20 cities"))
        // a correction at a payment's confirmation: "make it 20 pesos" is twenty pesewas, not left for a model to guess
        val money = org.json.JSONObject().put("number", "0244123456").put("amount", "5")
        val r = com.tekyerema.agent.ReviseTurn.patch("ussd_navigate", money, "make it 20 pesos")
        assertEquals("0.2", r!!.args.getString("amount"))
        assertEquals("20", com.tekyerema.agent.ReviseTurn.patch("ussd_navigate", money, "no, 20 cities")!!.args.getString("amount"))
        // …and a correction to a text message keeps its words
        val sms = org.json.JSONObject().put("contact", "Ama").put("message", "hello")
        assertEquals("I visited 2 cities",
            com.tekyerema.agent.ReviseTurn.patch("send_sms", sms, "say I visited 2 cities")!!.args.getString("message"))
    }

    @Test fun `who is paid may come between the verb and the figure`() {
        assertEquals("Send Ama 20 cedis", GhanaWords.fix("Send Ama 20 cities"))
        assertEquals("send my wife 20 cedis", GhanaWords.fix("send my wife 20 cities"))
        assertEquals("Send her 2 cedis", GhanaWords.fix("Send her 2 cities"))
        assertEquals("Buy airtime of 1 cedis", GhanaWords.fix("Buy airtime of 1 cd"))
        assertEquals("Buy me 8 cedis credit", GhanaWords.fix("Buy me 8 cds credit"))
        assertEquals("top up 5 cedis", GhanaWords.fix("top up 5 cds"))
        // A decision, not an accident: a phone cannot send compact discs, and "2 cds" after a giving verb has always
        // been read as cedis (voice/LangPipeline CURRENCY_MISHEAR). The read-back says "2 cedis" before anything moves.
        assertEquals("Send Kofi 2 cedis", GhanaWords.fix("Send Kofi 2 CDs"))
    }

    // The second review round: the first fix for the cases above let these through.
    @Test fun `words that only look like paying are left alone`() {
        same("what are the top 5 cities in Ghana")
        same("give me 3 cities to visit")
        same("send me 5 cities in the north")
        same("show me mobile data for 3 cities")
        same("By 2030, cities will grow")
    }

    @Test fun `a correction that is not an amount does not become one`() {
        val money = org.json.JSONObject().put("number", "0244123456").put("amount", "5").put("reference", "rent")
        // before the fix these set the amount to 2
        val a = com.tekyerema.agent.ReviseTurn.patch("ussd_navigate", money, "the reference is 2 cds")
        assertEquals("5", (a?.args ?: money).getString("amount"))
        val b = com.tekyerema.agent.ReviseTurn.patch("ussd_navigate", money, "no, send it to Kofi in 2 cities")
        assertEquals("5", (b?.args ?: money).getString("amount"))
        // an amount on its own still changes it
        assertEquals("20", com.tekyerema.agent.ReviseTurn.patch("ussd_navigate", money, "make it 20 cities instead")!!.args.getString("amount"))
    }

    @Test fun `a figure is never cut in half`() {
        // "of 30 cities" must not become "of 30 cedis" by matching from the second digit
        same("send photos of 30 cities to Ama")
        assertEquals("pay 1,050 cedis", GhanaWords.fix("pay 1,050 cities"))
    }
}
