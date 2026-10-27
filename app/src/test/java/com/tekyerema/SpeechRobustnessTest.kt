package com.tekyerema

import com.tekyerema.android.ContactMatcher
import com.tekyerema.android.ContactResolver
import com.tekyerema.money.MoneyAsk
import com.tekyerema.voice.EntityCorrector
import com.tekyerema.voice.HeardAs
import com.tekyerema.voice.Lang
import com.tekyerema.voice.LanguagePipeline
import com.tekyerema.voice.Normalizer
import com.tekyerema.voice.Verbalize
import com.tekyerema.voice.understand.AnswerContext
import com.tekyerema.voice.understand.FinanceVocab
import com.tekyerema.voice.understand.TelcoTerms
import com.tekyerema.voice.understand.TwiSpeechText
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The robustness layer around the HCI Lab recognisers and voice (5 October 2026): what an English or lab recogniser
 * writes for a Ghanaian money request → what the app acts on → what the voice says. Realistic commands only — Ghanaian
 * names, cedis/pesewas/GHS, MTN, phone numbers, MoMo, airtime, data.
 */
class SpeechRobustnessTest {
    private val names = listOf("Kwame Mensah", "Osei Kwame", "Abena Owusu", "Akosua Boateng", "Kofi Annan", "Prince Alhassan",
        "Princess Adjei", "Richmond Apenensu", "Yaw Asante", "Ama Serwaa", "Nana Yaa", "Efua Mensah", "Kwabena Darko",
        "Abuakwa Printing Press", "Mum", "Adwoa Pokuaa")
    private val contacts = names.mapIndexed { i, n -> ContactResolver.Contact(n, "05540000%02d".format(i)) }
    private lateinit var saved: () -> EntityCorrector.Vocab

    @Before fun setUp() {
        saved = LanguagePipeline.vocabulary
        LanguagePipeline.vocabulary = { EntityCorrector.Vocab.of(names, listOf("WhatsApp", "Telegram")) }
        HeardAs.clear()
    }

    @After fun tearDown() { LanguagePipeline.vocabulary = saved; HeardAs.clear() }

    private fun heard(raw: String) = LanguagePipeline.process(raw, Lang.GH_EN, Lang.GH_EN).canonical
    private fun ask(raw: String) = MoneyAsk.parse(heard(raw))
    private fun en(s: String) = Normalizer.normalize(s, Lang.GH_EN)

    // ------------------------------------------------------------------ numbers as Ghanaians say them

    @Test fun `spoken numbers become figures`() {
        assertEquals("send 10 cedis to Kwame", en("send ten cedis to Kwame"))
        assertEquals("send 2.5 cedis", en("send two point five cedis"))
        assertEquals("send 1.50 cedis", en("send one point five zero cedis"))
        assertEquals("send 50 pesewas to Yaw", en("send half a cedi to Yaw"))
        assertEquals("send 1 cedi to Kofi", en("send a cedi to Kofi"))
        assertEquals("send 120 cedis", en("send one hundred and twenty cedis"))
        assertEquals("send 3500 cedis", en("send three thousand five hundred cedis"))
        assertEquals("send 150 cedis to Mum", en("send 100 and fifty cedis to Mum"))
        assertEquals("send 1050 cedis", en("send 1000 and fifty cedis"))
    }

    @Test fun `phone digits said the Ghanaian way`() {
        assertEquals("0552093690", en("oh five five two oh nine three six nine oh").filter { it.isDigit() })
        assertEquals("0552093690", en("zero double five two zero nine three six nine zero").filter { it.isDigit() })
        assertEquals("0244000123", en("zero two double four triple zero one two three").filter { it.isDigit() })
    }

    @Test fun `number words that are not numbers stay words`() {
        assertEquals("book a double room", en("book a double room"))
        assertEquals("triple check it", en("triple check it"))
        assertEquals("Oh, 5 cedis? No, 10", en("Oh, five cedis? No, ten"))
        assertEquals("100 and 50 people came", en("100 and fifty people came"))
    }

    // ------------------------------------------------------------------ networks, currencies, money words

    @Test fun `network names as recognisers write them`() {
        assertEquals("buy MTN airtime", TelcoTerms.repair("buy em ti en airtime"))
        assertEquals("buy MTN data bundle", TelcoTerms.repair("buy MTM data bundle"))
        assertEquals("check my MTN balance", TelcoTerms.repair("check my M10 balance"))
        assertEquals("airtime for MTN number", TelcoTerms.repair("airtime for empty n number"))
        assertEquals("buy MTN airtime", TelcoTerms.repair("buy m.t.n airtime"))
        assertEquals("buy AirtelTigo airtime", TelcoTerms.repair("buy airtel tigo airtime"))
        assertEquals("send GHS 20 to Akosua", TelcoTerms.repair("send GH 20 to Akosua"))
    }

    @Test fun `ordinary words are never made into networks`() {
        assertEquals("I met the men at the market", TelcoTerms.repair("I met the men at the market"))
        assertEquals("send money to the men", TelcoTerms.repair("send money to the men"))
        assertEquals("I am at home", TelcoTerms.repair("I am at home"))
    }

    @Test fun `money words repaired only in a money request and never in a message`() {
        assertEquals("check my MoMo balance", FinanceVocab.repair("check my mumu balance").text)
        assertEquals("check my Mobile Money balance", FinanceVocab.repair("check my mobile many balance").text)
        assertEquals("transfer 30 cedis to Adwoa with reference rent", FinanceVocab.repair("transfer 30 cedis to Adwoa with reverence rent").text)
        assertEquals("message Abena saying the mumu is ready", FinanceVocab.repair("message Abena saying the mumu is ready").text)
        assertEquals("my mama is at home", FinanceVocab.repair("my mama is at home").text)
        assertEquals("send 5 cedis to Mumu", FinanceVocab.repair("send 5 cedis to Mumu", protect = setOf("mumu")).text)
    }

    // ------------------------------------------------------------------ whole requests, recogniser → slots

    private data class Want(val amount: String?, val name: String? = null, val number: String? = null, val kind: MoneyAsk.Kind = MoneyAsk.Kind.SEND)

    @Test fun `realistic money requests reach the right amount and recipient`() {
        val cases = mapOf(
            "send ten cedis to Kwame Mensah" to Want("10", "Kwame Mensah"),
            "send 10 pesos to Kwame" to Want("0.1", "Kwame"),
            "send five cedis fifty pesewas to Abena on MoMo" to Want("5.5", "Abena"),
            "send GH 20 to Akosua" to Want("20", "Akosua"),
            "send two point five cedis to Yaw Asante" to Want("2.5", "Yaw Asante"),
            "send half a cedi to Nana Yaa" to Want("0.5", "Nana Yaa"),
            "send 5 cities to Ama Serwaa" to Want("5", "Ama Serwaa"),
            "send fifty Ghana cedis to Richmond Apenensu" to Want("50", "Richmond Apenensu"),
            "send GH₵ 2.50 to Abena Owusu" to Want("2.5", "Abena Owusu"),
            "pay 10 GHC to Ama" to Want("10", "Ama"),
            "send five see dis to Kofi" to Want("5", "Kofi"),
            "send 100 and fifty cedis to Mum" to Want("150", "Mum"),
            "send 5 cedis to Kofi on MTN" to Want("5", "Kofi"),
            "send twenty five cedis to oh five five two oh nine three six nine oh" to Want("25", number = "0552093690"),
            "send 5 cedis to zero double five two zero nine three six nine zero" to Want("5", number = "0552093690"),
            "send 1 cedi to +233 55 209 3690" to Want("1", number = "0552093690"),
            "buy em ti en airtime of 5 cedis" to Want("5", kind = MoneyAsk.Kind.AIRTIME),
            "top up my airtime with 3 cedis" to Want("3", kind = MoneyAsk.Kind.AIRTIME),
            "recharge 50 pesewas airtime" to Want("0.5", kind = MoneyAsk.Kind.AIRTIME),
            "buy airtel tigo airtime 5 cedis for 0271234567" to Want("5", number = "0271234567", kind = MoneyAsk.Kind.AIRTIME),
            "buy 2 cedis airtime for empty n number 0244123456" to Want("2", number = "0244123456", kind = MoneyAsk.Kind.AIRTIME),
        )
        for ((raw, want) in cases) {
            val a = ask(raw) ?: throw AssertionError("not understood: $raw → ${heard(raw)}")
            assertEquals(raw, want.kind, a.kind)
            assertEquals(raw, want.amount, a.amount)
            assertEquals(raw, want.name, a.name)
            assertEquals(raw, want.number, a.number)
        }
        // airtime for the user's own line stays the user's own line
        assertEquals(true, ask("top up my airtime with 3 cedis")!!.forSelf)
        assertEquals(true, ask("buy MTN airtime of 5 cedis")!!.forSelf)
        assertEquals(true, ask("recharge my own credit with 2 cedis")!!.forSelf)
    }

    @Test fun `balance and reference words reach the request`() {
        assertEquals(MoneyAsk.Kind.BALANCE_MOMO, ask("check my mumu balance")!!.kind)
        assertEquals(MoneyAsk.Kind.BALANCE_MOMO, ask("check my mobile many balance")!!.kind)
        assertEquals("rent", ask("transfer 30 cedis to Adwoa Pokuaa with reverence rent")!!.reference)
        assertEquals("school fees", ask("send 2 cedis to 055 209 3690 reference school fees")!!.reference)
    }

    @Test fun `an amount said two ways is never guessed`() {
        // "two fifty" — 2.50 or 250? Left for the flow to ask; never turned into either.
        assertEquals("two fifty", AnswerContext.read("two fifty", "How much should I send?"))
        // …and in a request: the person is kept, the amount is not decided (the flow asks "How much?")
        val a = ask("send two fifty to Kwame")
        assertNull(a!!.amount); assertEquals("Kwame", a.name)
    }

    @Test fun `a message keeps its words`() {
        assertEquals("tell Kwame I will send the money tomorrow", heard("tell Kwame I will send the money tomorrow"))
        assertEquals("message Abena saying the MTN office is closed", heard("message Abena saying the MTN office is closed"))
        // a name split by the recogniser is still joined ("Kofi Anan" → Kofi Annan)
        assertEquals("call Kofi Annan", heard("call Kofi Anan"))
        assertEquals("call Prince", heard("call prints"))
    }

    // ------------------------------------------------------------------ safety audit (5 October 2026)

    @Test fun `a message is passed on in the user's own words`() {
        val asSaid = listOf(
            "message Abena saying em ti en office is closed",
            "send Kofi a message saying em ti en is down",
            "tell Kofi that I don't have a cedi",
            "tell Kofi I don't have a cedi",
            "text Ama saying meet me at half a cedi junction",
            "message Abena saying the mumu line is down",
            "text Ama saying I visited 5 cities",
            "message Abena saying call coffee now",
            "text Ama: I'll pay 2 point 5 cedis",
            "let Kofi know that I sent 10 pesos")
        for (raw in asSaid) {
            assertEquals(raw, raw, heard(raw))
            assertEquals(raw, raw, MoneyAsk.heard(heard(raw)))          // the recogniser's money repair (GhanaWords) too
        }
        // the recipient's number said aloud is still a number
        assertEquals("text 0 5 5 2 0 9 3 6 9 0 saying hi", heard("text oh five five two oh nine three six nine oh saying hi"))
        // and a request that only mentions telling is not a message: "tell me my mumu balance" is a balance question
        assertEquals("tell me my MoMo balance", MoneyAsk.heard(heard("tell me my mumu balance")))
    }

    @Test fun `unrelated speech and contact names are not repaired`() {
        assertEquals("play MTV on my data", heard("play MTV on my data"))
        assertEquals("buy an airline ticket", heard("buy an airline ticket"))
        assertEquals("send the report to Kwame", heard("send the report to Kwame"))
        LanguagePipeline.vocabulary = { EntityCorrector.Vocab.of(listOf("Mumu Boateng"), emptyList()) }
        assertEquals("send 5 cedis to Mumu Boateng", heard("send 5 cedis to Mumu Boateng"))
    }

    @Test fun `an amount that reads two ways is asked, never taken`() {
        for (raw in listOf("send two fifty to Kwame", "send five fifty to Kwame", "send 2 50 to Kwame", "send one fifty cedis to Kwame",
                "send 1 50 cedis to Kwame", "send 10 50 cedis to Kwame", "send fifty fifty to Kwame", "send 20 cedis 5 to Kwame",
                "send 5 pesewas 50 to Kwame", "send 5 hundred cedis to Kwame", "send 2 thousand cedis to Kwame",
                "send 1,5 cedis to Kwame", "send 2.500 cedis to Kwame", "send 0.005 cedis to Kwame", "send 1.5.0 cedis to Kwame")) {
            val a = ask(raw) ?: throw AssertionError("dropped: $raw")
            assertEquals(raw, MoneyAsk.Kind.SEND, a.kind)
            assertNull(raw, a.amount)
            assertEquals(raw, "Kwame", a.name)
        }
        // the same as an answer to "How much?": nothing taken, the flow asks again
        for (ans in listOf("two fifty", "2 50", "1,5", "2.500", "0.005", "1.5.0", "20 cedis 5"))
            assertNull(ans, MoneyAsk.amount(AnswerContext.read(ans, "How much should I send?")))
        assertEquals("500", MoneyAsk.amount(AnswerContext.read("5 hundred", "How much should I send?")))   // one reading only
        // what IS clear stays clear
        mapOf("send 5 cedis 50 to Kwame" to "5.5", "send one cedi fifty to Kwame" to "1.5", "send point five cedis to Kwame" to "0.5",
            "send 2,500 cedis to Kwame" to "2500", "send fifteen hundred cedis to Kwame" to "1500", "send 5 cedis 5 pesewas to Kwame" to "5.05",
            "send a hundred cedis to Kwame" to "100", "send GHS5 to Kwame" to "5").forEach { (raw, amt) -> assertEquals(raw, amt, ask(raw)!!.amount) }
    }

    @Test fun `every inferred interpretation is said once before the action`() {
        // an option chosen by its sound
        val who = "Who did you mean: Kwame Mensah, Osei Kwame or Efua Mensah?"
        AnswerContext.noteInference("afua", AnswerContext.read("afua", who), who)
        assertEquals("I heard 'afua' — taking it as Efua Mensah.",
            HeardAs.line(null, "ussd_navigate", JSONObject().put("contact", "Efua Mensah")))
        // …not an ordinal, which is the user's own choice
        HeardAs.clear()
        AnswerContext.noteInference("the first one", AnswerContext.read("the first one", who), who)
        assertNull(HeardAs.line(null, "ussd_navigate", JSONObject().put("contact", "Kwame Mensah")))
        // an amount answer whose unit was repaired
        val much = "How much should I send?"
        AnswerContext.noteInference("10 pesos", AnswerContext.read("10 pesos", much), much)
        assertEquals("I heard '10 pesos' — taking it as 10 pesewas.", HeardAs.moneyLine("ussd_navigate", JSONObject().put("amount", "0.1")))
        // a guess that stands on a corrected name says the word actually heard, once
        HeardAs.note("call Kwame", listOf(EntityCorrector.Fix("Kwami", "Kwame", EntityCorrector.Kind.CONTACT)))
        HeardAs.noteGuess("Kwame", "Osei Kwame")
        assertEquals("I heard 'Kwami' — taking it as Osei Kwame.", HeardAs.line("call Kwame", "make_call", JSONObject().put("contact", "Kwame")))
        assertNull(HeardAs.line("call Kwame", "make_call", JSONObject().put("contact", "Kwame")))
    }

    @Test fun `contact matching keeps its four answers and refuses lookalikes`() {
        val phone = listOf("Kwamena Boateng", "Efua Mensah", "Kwame Mensah", "Richmond's Dad").mapIndexed { i, n -> ContactResolver.Contact(n, "02000000%02d".format(i)) }
        // NONE: a name only found INSIDE another word, or a possessive
        assertEquals(ContactMatcher.Confidence.NONE, ContactMatcher.confidence(ContactMatcher.rank("ama", phone)))
        assertEquals(ContactMatcher.Confidence.NONE, ContactMatcher.confidence(ContactMatcher.rank("Richmond", phone)))
        // a three-letter start of a LATER word is not a match ("men" is not Mensah)
        assertEquals(ContactMatcher.Confidence.NONE, ContactMatcher.confidence(ContactMatcher.rank("men", phone)))
        // SURE: the name as said, glued or not
        assertEquals(1.0, ContactMatcher.score("Kwamemensah", "Kwame Mensah"), 0.0)
        assertEquals(ContactMatcher.Confidence.SURE, ContactMatcher.confidence(ContactMatcher.rank("Kwame Mensah", phone)))
        // CHOOSE: two people fit
        assertEquals(ContactMatcher.Confidence.CHOOSE, ContactMatcher.confidence(ContactMatcher.rank("Mensah", phone)))
        // CHOOSE too when a fragment fits two people ("Kwamen": Kwamena, and Kwame Mensah with a consonant dropped)
        assertEquals(ContactMatcher.Confidence.CHOOSE, ContactMatcher.confidence(ContactMatcher.rank("Kwamen", phone)))
        // WEAK: one, loosely ("Efu" is the start of Efua — read back in full, said as a guess)
        assertEquals(ContactMatcher.Confidence.WEAK, ContactMatcher.confidence(ContactMatcher.rank("Efu", phone)))
    }

    @Test fun `the voices get money, numbers and codes they can say`() {
        assertEquals("Balance in cedis:", Verbalize.apply("Balance in GHS:"))
        assertEquals("Send to zero two four, four one two, three four five six?", Verbalize.apply("Send to 0244-123-456?"))
        assertEquals("Call plus two three three, five five, two zero nine, three six nine zero?", Verbalize.apply("Call +233 55 209 3690?"))
        assertEquals("Fee: thirty eight pesewas", Verbalize.apply("Fee: GH₵ 0.38"))
        assertEquals("Dial star one seven zero hash", Verbalize.apply("Dial *170#"))
        assertEquals("Send ten pesewas to Osei Kwame, on zero five four, two nine zero, six six six six, an M T N number, with Mobile Money?",
            Verbalize.apply("Send 0.1 cedis to Osei Kwame, on 0 5 4, 2 9 0, 6 6 6 6, an MTN number, with Mobile Money?"))
        // Twi: the same, whether the line comes straight or through the English verbaliser first
        for ((line, twi) in mapOf(
            "Frɛ +233 55 209 3690?" to "Frɛ sero anum anum, mmienu sero nkron, mmiɛnsa nsia nkron sero?",
            "Frɛ *170#" to "Frɛ star baako nson sero hash",
            "Wo balance yɛ GHS 1,000,000." to "Wo balance yɛ sidi ɔpepem.",
            "data 2.5GB" to "data gigabyte mmienu pɔint anum",
            "Fee yɛ GH₵ 0.38." to "Fee yɛ pesewa aduasa nwɔtwe.")) {
            assertEquals(line, twi, TwiSpeechText.prepare(line))
            assertEquals(line, twi, TwiSpeechText.prepare(Verbalize.apply(line)).replace("M T N", "MTN"))
        }
    }

    // ------------------------------------------------------------------ the owner's Twi session on the A20e (5 October, 23:27–23:34)

    @Test fun `a closed choice heard the lab recogniser's way is matched by sound and said back`() {
        val which = "Which Prince: Prince Melanin, Prince Tesano AG youth Sec, or Prince Ice P? Or someone else?"
        assertEquals("Prince Melanin", AnswerContext.read("press malam", which))
        val chat = "Which chat should I send the message to? Richmond, or Richmond Apenensu?"
        assertEquals(AnswerContext.Expect.CHOICE, AnswerContext.expect(chat))
        assertEquals(listOf("Richmond", "Richmond Apenensu"), AnswerContext.options(chat))
        assertEquals("Richmond Apenensu", AnswerContext.read("rekyemɔndapanu nso", chat))
        // a guess like that is disclosed before the action
        AnswerContext.noteInference("press malam", "Prince Melanin", which)
        assertEquals("I heard 'press malam' — taking it as Prince Melanin.",
            HeardAs.line(null, "ussd_navigate", JSONObject().put("contact", "Prince Melanin")))
        // nothing close enough: the answer is passed on as said (and asked again), never guessed
        assertEquals("kofi", AnswerContext.read("kofi", which))
    }

    @Test fun `call X for me in Twi calls X`() {
        assertEquals("call Prince", com.tekyerema.language.TwiCommands.gloss("frɛ prince maai"))
        assertEquals("call Prince", com.tekyerema.language.TwiCommands.gloss("frɛ prince ma me"))
    }

    @Test fun `a first name with words glued on, or a whole name glued into one, is asked about`() {
        val phone = listOf("Prince Melanin", "Prince Tesano AG youth Sec", "Prince Ice P", "Kwame Mensah")
            .mapIndexed { i, n -> ContactResolver.Contact(n, "02400001%02d".format(i)) }
        assertEquals(ContactMatcher.Confidence.CHOOSE, ContactMatcher.confidence(ContactMatcher.rank("Prince maai", phone)))
        val glued = ContactMatcher.rank("prinsmalane", phone)
        assertEquals(ContactMatcher.Confidence.WEAK, ContactMatcher.confidence(glued))
        assertEquals(listOf("Prince Melanin"), ContactMatcher.pick("prinsmalane", glued).map { it.name })
    }

    @Test fun `an amount the planner took from an earlier request is said to be unsaid`() {
        assertEquals(false, com.tekyerema.money.MoneyFlow.amountSaid("1", "soma pesee kɔma prinsmalane check a contact list no message prince melanin"))
        assertEquals(true, com.tekyerema.money.MoneyFlow.amountSaid("0.1", "send 10 pesos to Kwame"))
        assertEquals(true, com.tekyerema.money.MoneyFlow.amountSaid("1", "soma sika kɔma prince 1 cedis"))
        assertEquals(true, com.tekyerema.money.MoneyFlow.amountSaid("1250", "send 1,250 cedis to Kwame"))
        val d = com.tekyerema.money.MoneyFlow.fromPlanner(
            com.tekyerema.agent.AgentDecision.CallTool("momo_send", JSONObject().put("number", "233552093690").put("amount", "1"), "planner"),
            "soma pesee kɔma prinsmalane",
            listOf("ask_user(\"What phone number and how much money should I send?\") → the user answered: \"check a contact list no message prince melanin\""))
        val args = (d as com.tekyerema.agent.AgentDecision.CallTool).args
        assertEquals(true, args.optBoolean(com.tekyerema.money.ussd.UssdNavigatorTool.ARG_UNSAID_AMOUNT))
        assertEquals(true, com.tekyerema.money.ussd.UssdNavigatorTool.confirmationPrompt(args)
            .contains("You didn't say this amount in this request — it's from earlier, so check it."))
    }

    @Test fun `a question with the word cedi in it is still put into Twi`() {
        assertEquals(false, com.tekyerema.language.ReplyLocalizer.hasQuantity("How much cedi should I send to Prince?"))
        assertEquals(true, com.tekyerema.language.ReplyLocalizer.hasQuantity("Send five cedis to Prince?"))
    }

    // ------------------------------------------------------------------ contacts: sure, choose, weak, none

    private fun rank(h: String) = ContactMatcher.rank(h, contacts)
    private fun picks(h: String) = ContactMatcher.pick(h, rank(h)).map { it.name }

    @Test fun `contact names matched by sound without renaming anyone`() {
        assertEquals(ContactMatcher.Confidence.SURE, ContactMatcher.confidence(rank("prince")))
        assertEquals(listOf("Prince Alhassan"), picks("prince"))                     // not Princess
        assertEquals(listOf("Kwame Mensah"), picks("kwami"))
        assertEquals(listOf("Kofi Annan"), picks("coffee"))
        assertEquals(listOf("Efua Mensah"), picks("afua"))                            // Akan initial vowel
        assertEquals(listOf("Mum"), picks("mom"))
    }

    @Test fun `several people fit - the user is asked`() {
        assertEquals(ContactMatcher.Confidence.CHOOSE, ContactMatcher.confidence(rank("press")))
        assertEquals(listOf("Abuakwa Printing Press", "Prince Alhassan", "Princess Adjei"), picks("press"))
        assertEquals(ContactMatcher.Confidence.CHOOSE, ContactMatcher.confidence(rank("mensah")))
    }

    @Test fun `found on the A20e - a changed consonant or a possessive is someone else`() {
        val phone = listOf("Osei Kwame", "Kwesi Dain", "Qweku Marvin", "Richmond's Dad", "Prince Melanin")
            .mapIndexed { i, n -> ContactResolver.Contact(n, "02400000%02d".format(i)) }
        val kwame = ContactMatcher.rank("Kwame", phone)
        assertEquals(listOf("Osei Kwame"), ContactMatcher.pick("Kwame", kwame).map { it.name })      // not Kwesi, not Qweku
        assertEquals(ContactMatcher.Confidence.NONE, ContactMatcher.confidence(ContactMatcher.rank("Richmond", phone)))
        assertEquals(listOf("Richmond's Dad"), ContactMatcher.pick("Richmond's Dad", ContactMatcher.rank("Richmond's Dad", phone)).map { it.name })
        assertEquals(listOf("Richmond's Dad"), ContactMatcher.pick("Richmonds dad", ContactMatcher.rank("Richmonds dad", phone)).map { it.name })
        // the press/Prince rule still holds: one consonant dropped
        assertEquals(listOf("Prince Melanin"), ContactMatcher.pick("press", ContactMatcher.rank("press", phone)).map { it.name })
        // and the name corrector leaves "Richmond" alone when the only Richmond on the phone is "Richmond's Dad"
        LanguagePipeline.vocabulary = { EntityCorrector.Vocab.of(phone.map { it.name }, emptyList()) }
        assertEquals("tell Richmond I will call you", heard("tell Richmond I will call you"))
    }

    @Test fun `a loose match is weak and a stranger is nobody`() {
        assertEquals(ContactMatcher.Confidence.WEAK, ContactMatcher.confidence(rank("rich man")))
        assertEquals(ContactMatcher.Confidence.NONE, ContactMatcher.confidence(rank("jaw")))
    }

    @Test fun `a loose contact match is said in the read-back`() {
        HeardAs.noteGuess("rich man", "Richmond Apenensu")
        assertEquals("I heard 'rich man' — taking it as Richmond Apenensu.",
            HeardAs.line("call rich man", "make_call", JSONObject().put("contact", "Richmond Apenensu")))
        assertNull(HeardAs.line("call rich man", "make_call", JSONObject().put("contact", "Richmond Apenensu")))   // once
    }

    // ------------------------------------------------------------------ answers read for their question

    @Test fun `answers are read as what the question asked for`() {
        val much = "How much should I send?"
        assertEquals("10 cedis", AnswerContext.read("ten cedis", much))
        assertEquals("10 pesewas", AnswerContext.read("10 pesos", much))
        assertEquals("GHS 20", AnswerContext.read("GH 20", much))
        assertEquals("50 pesewas", AnswerContext.read("half a cedi", much))
        val number = "What number should I send it to?"
        assertEquals("0552093690", AnswerContext.read("oh five five two oh nine three six nine oh", number))
        assertEquals("0552093690", AnswerContext.read("zero double five two zero nine three six nine zero", number))
        assertEquals("0552093690", AnswerContext.read("+233 55 209 3690", number))
        assertEquals("0552093690", AnswerContext.read("552093690", number))
        assertEquals("0244123456", AnswerContext.read("it is 024 412 3456", number))
        val who = "Who did you mean: Kwame Mensah, Osei Kwame or Efua Mensah?"
        assertEquals("Kwame Mensah", AnswerContext.read("the first one", who))
        assertEquals("Efua Mensah", AnswerContext.read("the last one", who))
        assertEquals("Osei Kwame", AnswerContext.read("Osei", who))
        assertEquals("Efua Mensah", AnswerContext.read("afua", who))
        assertEquals("mensah", AnswerContext.read("mensah", who))                  // two fit: asked again, not guessed
        assertEquals("school fees", AnswerContext.read("school fees", "What is the reference?"))
    }

    // ------------------------------------------------------------------ what the voices are given

    @Test fun `heard money words are said back once`() {
        HeardAs.noteWords("send 10 pesos to Kwame", "send 10 pesewas to Kwame")
        assertNull(HeardAs.moneyLine("ussd_navigate", JSONObject().put("amount", "10")))
        assertEquals("I heard '10 pesos' — taking it as 10 pesewas.", HeardAs.moneyLine("ussd_navigate", JSONObject().put("amount", "0.1")))
        assertNull(HeardAs.moneyLine("ussd_navigate", JSONObject().put("amount", "0.1")))
    }

    @Test fun `the English voice gets amounts, networks and numbers it can say`() {
        assertEquals("Send ten cedis fifty pesewas to Kwame on M T N?", Verbalize.apply("Send GH₵ 10.50 to Kwame on MTN?"))
        assertEquals("Send five cedis to zero five five, two zero nine, three six nine zero?", Verbalize.apply("Send 5 cedis to 055 209 3690?"))
        assertEquals("Send to plus two three three, five five, two zero nine, three six nine zero?", Verbalize.apply("Send to +233552093690?"))
        assertEquals("Prince Alhassan ending three two three", Verbalize.apply("Prince Alhassan ending 323"))
        assertEquals("Buy two gigabytes M T N data for five cedis?", Verbalize.apply("Buy 2GB MTN data for GH₵ 5?"))
        assertEquals("Your balance is one thousand two hundred fifty cedis seventy five pesewas.", Verbalize.apply("Your balance is GHS 1,250.75."))
    }

    @Test fun `the Twi voice gets every figure as Twi words`() {
        assertEquals("Mede sidi du ne pesewa aduonum rekɔma Kwame. Wopene?", TwiSpeechText.prepare("Mede GH₵ 10.50 rekɔma Kwame. Wopene?"))
        assertEquals("Mede sidi anum rekɔma sero anum anum, mmienu sero nkron, mmiɛnsa nsia nkron sero.",
            TwiSpeechText.prepare("Mede sidi 5 rekɔma 0552093690."))
        assertEquals("Me nsa aka pesewa aduonum.", TwiSpeechText.prepare("Me nsa aka 50 pesewas."))
        assertEquals("Wo balance yɛ sidi apem ne ahaanu ne aduonum ne pesewa aduɔson anum.", TwiSpeechText.prepare("Wo balance yɛ GHS 1,250.75."))
        assertEquals("Prince Alhassan ending mmiɛnsa mmienu mmiɛnsa", TwiSpeechText.prepare("Prince Alhassan ending 323"))
    }

    @Test fun `a Twi line already verbalised in English still reaches the Twi voice in Twi`() {
        // VoiceController runs every line through Verbalize before the voice: the English number words go back to Twi
        assertEquals("Mede sidi du ne pesewa aduonum rekɔma Kwame. Wopene?",
            TwiSpeechText.prepare(Verbalize.apply("Mede GH₵ 10.50 rekɔma Kwame. Wopene?")))
        assertEquals("Mede sidi anum rekɔma sero anum anum, mmienu sero nkron, mmiɛnsa nsia nkron sero.",
            TwiSpeechText.prepare(Verbalize.apply("Mede sidi 5 rekɔma 0552093690.")))
    }

    @Test fun `a Twi line with no figure is left exactly as recorded`() {
        for (l in listOf("Yoo, mɛyɛ o.", "Wo ankasa na wobɛhyɛ PIN.", "M T N se: airtime sidi baako ma wo fɔn yi."))
            assertEquals(l, TwiSpeechText.prepare(l))
    }
}
