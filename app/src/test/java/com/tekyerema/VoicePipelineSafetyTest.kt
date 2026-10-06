package com.tekyerema

import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.FinalActionGuard
import com.tekyerema.agent.TaskFacts
import com.tekyerema.android.ContactMatcher
import com.tekyerema.android.ContactResolver
import com.tekyerema.language.TwiCommands
import com.tekyerema.money.MoneyAsk
import com.tekyerema.money.MoneyFlow
import com.tekyerema.voice.Confirmation
import com.tekyerema.voice.EntityCorrector
import com.tekyerema.voice.Lang
import com.tekyerema.voice.LanguagePipeline
import com.tekyerema.voice.SpeechVocabulary
import com.tekyerema.voice.io.Heard
import com.tekyerema.voice.io.InputCaps
import com.tekyerema.voice.io.LanguageRoutedInput
import com.tekyerema.voice.io.ListenEvents
import com.tekyerema.voice.io.ListenPurpose
import com.tekyerema.voice.io.ListenSpec
import com.tekyerema.voice.io.VoiceInput
import com.tekyerema.voice.understand.AnswerContext
import com.tekyerema.voice.understand.SpokenNumbers
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The end-to-end pass of 6 October 2026: never invent a contact, never reuse a demo contact, never invent or reuse an
 * amount, never treat a destroyed transcript as truth, never let the planner override the phone's own checks — and the
 * exact failures of the owner's Twi session on the A20e (5 October, 23:27–23:34) as regressions.
 */
class VoicePipelineSafetyTest {
    /** The phone in the session: six Princes, a Kwame, two Kwame-ish names, Richmond's Dad. */
    private val phoneNames = listOf("Prince Melanin", "Prince Tesano AG youth Sec", "Prince Ice P", "Prinz", "Osei Kwame", "Kwesi Dain",
        "Qweku Marvin", "Richmond's Dad", "Efua Mensah", "Kwame Mensah")
    private val phone = phoneNames.mapIndexed { i, n -> ContactResolver.Contact(n, "02400003%02d".format(i)) }
    private val vocab = EntityCorrector.Vocab.of(phoneNames, emptyList())
    private lateinit var saved: () -> EntityCorrector.Vocab

    @Before fun setUp() { saved = LanguagePipeline.vocabulary; LanguagePipeline.vocabulary = { vocab }; com.tekyerema.voice.HeardAs.clear() }
    @After fun tearDown() { LanguagePipeline.vocabulary = saved; com.tekyerema.voice.HeardAs.clear() }

    private fun call(name: String, vararg kv: Pair<String, String>) = AgentDecision.CallTool(name, JSONObject().apply { kv.forEach { put(it.first, it.second) } }, "planner")
    private fun asked(q: String, a: String) = "ask_user(\"$q\") → the user answered: \"$a\""

    // ================================================================== 1. no hard-coded, demo or remembered contact

    @Test fun `send money to Kwame never resolves to Prince`() {
        val onlyPrinces = phone.filter { it.name.startsWith("Prince") || it.name == "Prinz" }
        assertEquals(ContactMatcher.Confidence.NONE, ContactMatcher.confidence(ContactMatcher.rank("Kwame", onlyPrinces)))
        assertTrue(ContactMatcher.pick("Kwame", ContactMatcher.rank("Kwame", onlyPrinces)).isEmpty())
    }

    @Test fun `the planner cannot put in a recipient this request never named`() {
        // a planner copying an example name, or the person of an earlier request
        for ((goal, proposed) in listOf(
            "call Ama" to call("make_call", "contact" to "Prince"),
            "send money to Kwame" to call("ussd_navigate", "contact" to "Prince Melanin", "amount" to "5"),
            "text my brother saying hi" to call("send_sms", "contact" to "Kofi", "message" to "hi"),
            "send money" to call("ussd_navigate", "number" to "0552093690", "amount" to "1"))) {
            val d = FinalActionGuard.apply(proposed, goal, emptyList())
            assertTrue("$goal → ${proposed.name}", d is AgentDecision.CallTool && d.name == "ask_user")
        }
    }

    @Test fun `a failed contact search never falls back to a known contact`() {
        val history = listOf("search_contacts({\"query\":\"prinsmalane\"}) → fail [native]; verified=false; No contact in the phone's address book matches 'prinsmalane'.")
        // the planner then proposes the demo person — not grounded: asked
        val d = FinalActionGuard.apply(call("ussd_navigate", "contact" to "Prince Alhassan", "amount" to "1"), "soma pesee kɔma Kwesi", history)
        assertEquals("ask_user", (d as AgentDecision.CallTool).name)
    }

    @Test fun `a contact found by a look-up in this task is grounded, and so is one the user said`() {
        val found = listOf(asked("Who should I send it to?", "check a contact list no message prince melanin"),
            "search_contacts({\"query\":\"Prince Melanin\"}) → success [native]; verified=true; Matches for 'Prince Melanin': Prince Melanin — +233552093690")
        val facts = TaskFacts("soma pesee kɔma prinsmalane", found)
        assertTrue(facts.personGrounded("Prince Melanin"))
        assertTrue(facts.numberGrounded("233552093690"))
        // said by sound (the lab's spelling) counts too — the resolver then names the contact in full in the read-back
        assertTrue(TaskFacts("soma pesee kɔma prinsmalane", emptyList()).personGrounded("Prince Melanin"))
        assertTrue(TaskFacts("send 5 cedis to Kwame", emptyList()).personGrounded("Osei Kwame"))
        assertFalse(TaskFacts("send 5 cedis to Kwame", emptyList()).personGrounded("Prince Melanin"))
    }

    @Test fun `the debug scenarios carry no contact of their own`() {
        val src = java.io.File("src/main/java/com/tekyerema/agent/TestBrain.kt").readText()
        for (n in listOf("\"Prince", "\"Kwame", "\"Kofi", "\"Ama", "Melanin", "0552093690")) assertFalse("TestBrain has $n", src.contains(n))
        val prompt = com.tekyerema.agent.BrainPrompts.SYSTEM
        for (n in listOf("Kofi", "Kwame", "Prince", "Melanin", "Richmond", "Abena", "0552093690")) assertFalse("planner prompt has $n", prompt.contains(n))
    }

    // ================================================================== 2. amounts: stated in THIS task, or asked

    @Test fun `a stale amount from an earlier request is never used`() {
        // T5 on the A20e: "soma pesee kɔma prinsmalane", then the planner proposed 1 cedi — said in the request before
        val history = listOf(asked("What phone number and how much money should I send?", "check a contact list no message prince melanin"),
            "search_contacts({\"query\":\"Prince Melanin\"}) → success [native]; verified=true; Matches for 'Prince Melanin': Prince Melanin — +233552093690")
        val d = FinalActionGuard.apply(call("ussd_navigate", "number" to "233552093690", "amount" to "1"), "soma pesee kɔma prinsmalane", history)
        d as AgentDecision.CallTool
        assertEquals("ask_user", d.name)
        assertTrue(d.args.getString("question").startsWith("How much"))
        // …once the user says it, it stands
        val answered = history + asked("How much do you want to send?", "1 cedis")
        assertEquals("ussd_navigate", (FinalActionGuard.apply(call("ussd_navigate", "number" to "233552093690", "amount" to "1"),
            "soma pesee kɔma prinsmalane", answered) as AgentDecision.CallTool).name)
    }

    @Test fun `the planner cannot read an amount the phone left unresolved`() {
        for ((goal, planner) in listOf("send 1,5 cedis to Kwame" to "1.5", "send 1,5 cedis to Kwame" to "15",
                "send two fifty to Kwame" to "2.5", "send two fifty to Kwame" to "250", "send 2.500 cedis to Kwame" to "2500")) {
            val d = FinalActionGuard.apply(call("ussd_navigate", "contact" to "Kwame", "amount" to planner), goal, emptyList())
            assertEquals("$goal → $planner", "ask_user", (d as AgentDecision.CallTool).name)
        }
        // what the phone itself read is the user's
        for ((goal, amt) in listOf("send 10 pesos to Kwame" to "0.1", "send half a cedi to Kwame" to "0.5", "send GH 20 to Kwame" to "20",
                "send 100 and fifty cedis to Kwame" to "150", "send 5 cedis 50 to Kwame" to "5.5", "send 2.5 cedis to Kwame" to "2.5",
                "send 5 cedis to Kwame and 10 cedis to Ama" to "10"))
            assertTrue("$goal → $amt", TaskFacts(goal, emptyList()).amountStated(amt))
        // a guard that kept asking stops honestly instead of guessing
        val twice = List(2) { asked("How much do you want to send to Kwame?", "erm") }
        assertTrue(FinalActionGuard.apply(call("ussd_navigate", "contact" to "Kwame", "amount" to "9"), "send money to Kwame", twice) is AgentDecision.CannotDo)
    }

    @Test fun `a figure the planner types into a USSD menu must be the user's`() {
        val ussd = "com.android.phone"
        fun typed(text: String, goal: String) =
            FinalActionGuard.apply(call("type", "element_id" to "3", "text" to text), goal, emptyList(), "", ussd)
        assertEquals("type", (typed("1", "send 5 cedis to Kwame") as AgentDecision.CallTool).name)            // a menu choice
        assertEquals("type", (typed("5", "send 5 cedis to Kwame") as AgentDecision.CallTool).name)
        assertEquals("type", (typed("0.5", "send half a cedi to Kwame") as AgentDecision.CallTool).name)    // the stated amount
        assertEquals("ask_user", (typed("50", "send 5 cedis to Kwame") as AgentDecision.CallTool).name)     // not what was said
        assertEquals("ask_user", (typed("0244123456", "send 5 cedis to Kwame") as AgentDecision.CallTool).name)
        // outside a phone window, typing is the planner's ordinary work
        assertEquals("type", (FinalActionGuard.apply(call("type", "text" to "50"), "send 5 cedis", emptyList(), "", "com.whatsapp")
            as AgentDecision.CallTool).name)
    }

    // ================================================================== 3. phone numbers the recogniser destroyed

    @Test fun `a destroyed phone number is asked for again, never looked up`() {
        assertTrue(SpokenNumbers.looksLikeLostDigits("zeroffvtziro ne tsi sir"))
        assertFalse(SpokenNumbers.looksLikeLostDigits("Prince Melanin"))
        assertFalse(SpokenNumbers.looksLikeLostDigits("Kwame"))
        val a = MoneyAsk.parse("send 10 pesewas to zeroffvtziro ne tsi sir")!!
        assertNull(a.name); assertNull(a.number); assertEquals("zeroffvtziro ne tsi sir", a.lostNumber)
        // the flow asks for the number on its own…
        val q = MoneyFlow.decide("send 10 pesewas to zeroffvtziro ne tsi sir", emptyList()) as AgentDecision.CallTool
        assertEquals("ask_user", q.name)
        val question = q.args.getString("question")
        assertTrue(question, question.startsWith("I couldn't catch the phone number"))
        assertEquals(AnswerContext.Expect.PHONE, AnswerContext.expect(question))
        // …the answer is read as digits ("oh five five…"), and the send goes to that number
        val answer = AnswerContext.read("oh five five two oh nine three six nine oh", question)
        val next = MoneyFlow.decide("send 10 pesewas to zeroffvtziro ne tsi sir", listOf(asked(question, answer))) as AgentDecision.CallTool
        assertEquals("ussd_navigate", next.name)
        assertEquals("0552093690", next.args.getString("number"))
        assertEquals("0.1", next.args.getString("amount"))
        // a second answer that is still destroyed is asked again, not used
        val again = MoneyFlow.decide("send 10 pesewas to zeroffvtziro ne tsi sir", listOf(asked(question, "ziroffvt tsi"))) as AgentDecision.CallTool
        assertEquals("ask_user", again.name)
    }

    @Test fun `phone number answers in English or Twi digits`() {
        val q = "I couldn't catch the phone number. Please say the phone number again."
        for (a in listOf("0552093690", "oh five five two oh nine three six nine oh", "zero double five two zero nine three six nine zero",
                "+233 55 209 3690", "055 209 3690", "zero anum anum, mmienu zero nkron, mmiɛnsa nsia nkron zero"))
            assertEquals(a, "0552093690", AnswerContext.read(a, q))
        // missing digits are never invented
        assertEquals("055 209", AnswerContext.read("055 209", q))
    }

    // ================================================================== 4. the A20e session, request by request

    private val isContact: (String) -> Boolean = { SpeechVocabulary.isContactPhrase(it, vocab) }

    @Test fun `T3 soma sika cedi kɔma prince is a send on the phone, the amount asked`() {
        val g = TwiCommands.gloss("soma sika cedi kɔma prince", isContact)
        assertEquals("send money to Prince", g)
        val q = MoneyFlow.decide(g!!, emptyList()) as AgentDecision.CallTool
        assertEquals("How much do you want to send to Prince?", q.args.getString("question"))
    }

    @Test fun `T5 soma pesee kɔma prinsmalane is a send to a sound-alike contact`() {
        val g = TwiCommands.gloss("soma pesee kɔma prinsmalane", isContact)
        assertEquals("send money to Prinsmalane", g)
        val ranked = ContactMatcher.rank("prinsmalane", phone)
        assertEquals(ContactMatcher.Confidence.WEAK, ContactMatcher.confidence(ranked))       // read back in full, never acted on silently
        assertEquals(listOf("Prince Melanin"), ContactMatcher.pick("prinsmalane", ranked).map { it.name })
    }

    @Test fun `T2 tempɛsos kɔma a destroyed number asks for the number`() {
        val g = TwiCommands.gloss("tempɛsos kɔma zeroffvtziro ne tsi sir", isContact)
        assertEquals("send 10 pesewas to zeroffvtziro ne tsi sir", g)
        assertEquals("zeroffvtziro ne tsi sir", MoneyAsk.parse(g!!)!!.lostNumber)
        assertEquals("10 pesewas", SpokenNumbers.unglueAmounts("tempɛsos"))
        assertEquals("5 cedis to Kofi", SpokenNumbers.unglueAmounts("fivecedis to Kofi"))
        assertEquals("send 10 pesewas", SpokenNumbers.unglueAmounts("send 10pesewas"))
    }

    @Test fun `T4 frɛ prince maai asks which Prince`() {
        assertEquals("call Prince", TwiCommands.gloss("frɛ prince maai", isContact))
        assertEquals(ContactMatcher.Confidence.CHOOSE, ContactMatcher.confidence(ContactMatcher.rank("Prince", phone)))
        assertEquals(ContactMatcher.Confidence.CHOOSE, ContactMatcher.confidence(ContactMatcher.rank("Prince maai", phone)))
    }

    @Test fun `T1 katweɛrichmond wɔ telegram sɛ ɔmfra me is a message on the phone, no translator`() {
        assertEquals("send a message to Richmond on Telegram saying “Ɔmfra me”",
            TwiCommands.gloss("katweɛrichmond wɔ telegram sɛ ɔmfra me", isContact))
        assertEquals("send a message to Kwame saying “Meba”", TwiCommands.gloss("ka kyerɛ Kwame sɛ meba", isContact))
    }

    @Test fun `a message by Telegram is not turned into a text to a contact named "X on Telegram"`() {
        assertNull(com.tekyerema.agent.LocalBrain.parseSms("send a message to Richmond on Telegram saying “Ɔmfra me”"))
        assertNotNull(com.tekyerema.agent.LocalBrain.parseSms("send a message to Richmond saying hi"))
        assertEquals(true, com.tekyerema.agent.LocalBrain.parseSms("send a message to Richmond on WhatsApp saying hi")?.whatsApp)
    }

    @Test fun `closed choice answers - ordinals and the lab's spellings`() {
        val which = "Which Prince: Prince Melanin, Prince Tesano AG youth Sec, or Prince Ice P? Or someone else?"
        assertEquals("Prince Melanin", AnswerContext.read("the first one", which))
        assertEquals("Prince Melanin", AnswerContext.read("that first one", which))
        assertEquals("Prince Tesano AG youth Sec", AnswerContext.read("the second one", which))
        assertEquals("Prince Melanin", AnswerContext.read("Prince Melanin", which))
        assertEquals("Prince Melanin", AnswerContext.read("press malam", which))
        assertEquals("Richmond Apenensu", AnswerContext.read("rekyemɔndapanu nso",
            "Which chat should I send the message to? Richmond, or Richmond Apenensu?"))
    }

    // ================================================================== 5. Twi confirmations are heard in Twi

    @Test fun `a Twi yes or no is heard by the Twi recogniser, digits by the phone's`() = kotlinx.coroutines.runBlocking {
        val heard = mutableListOf<String>()
        class Rec(override val id: String) : VoiceInput {
            override val caps = InputCaps(false, false, false, emptySet(), secureListen = false, patientEndpointing = false)
            override suspend fun listen(spec: ListenSpec, events: ListenEvents): Heard { heard += "$id/${spec.language}"; return Heard.NoSpeech }
        }
        val routed = LanguageRoutedInput(english = Rec("phone"), twi = Rec("hcilab"), language = { Lang.TWI }, isOnline = { true })
        routed.listen(ListenSpec(ListenPurpose.YES_NO), object : ListenEvents {})
        routed.listen(ListenSpec(ListenPurpose.ANSWER, digits = true), object : ListenEvents {})
        routed.listen(ListenSpec(ListenPurpose.ANSWER), object : ListenEvents {})
        assertEquals(listOf("hcilab/${Lang.TWI}", "phone/${Lang.GH_EN}", "hcilab/${Lang.TWI}"), heard)
    }

    @Test fun `Twi yes and no, as the lab writes them - unclear is asked again`() {
        for (y in listOf("aane", "aan", "yiw", "yoo", "kɔ so")) assertEquals(y, Confirmation.Answer.YES, Confirmation.interpret(y))
        for (n in listOf("daabi", "dabi", "fida", "gyae", "gyai", "mma")) assertEquals(n, Confirmation.Answer.NO, Confirmation.interpret(n))
        for (u in listOf("so", "ɛte sɛn", "")) assertEquals("'$u'", Confirmation.Answer.UNCLEAR, Confirmation.interpret(u))
    }

    // ================================================================== 6. read-backs: what will actually happen

    @Test fun `a contact's number is read back by its last four digits, a said number in full`() {
        val byName = com.tekyerema.money.ussd.UssdNavigatorTool.confirmationPrompt(JSONObject()
            .put("amount", "10").put("contact", "Prince Melanin").put("number", "0552093690"))
        assertTrue(byName, byName.startsWith("Send 10 cedis to Prince Melanin, ending 3 6 9 0, an MTN number, with Mobile Money?"))
        val bySaid = com.tekyerema.money.ussd.UssdNavigatorTool.confirmationPrompt(JSONObject().put("amount", "10").put("number", "0552093690"))
        assertTrue(bySaid, bySaid.contains("0 5 5, 2 0 9, 3 6 9 0"))
    }

    @Test fun `a disclosure has its own Twi frame, so a Twi read-back needs no translator`() {
        assertEquals("Mete sɛ “press malam”, enti mɛfa no sɛ Prince Melanin.",
            com.tekyerema.voice.HeardAs.twi("I heard 'press malam' — taking it as Prince Melanin."))
        assertEquals("Mete sɛ “10 pesos”, enti mɛfa no sɛ 10 pesewas.",
            com.tekyerema.voice.HeardAs.twi("I heard '10 pesos' — taking it as 10 pesewas."))
        assertNull(com.tekyerema.voice.HeardAs.twi("You didn't name Richmond Apenensu — it's the chat that is open on the screen."))
        // …and the Twi voice gets the amount in Twi words
        assertEquals("Mete sɛ “du pesos”, enti mɛfa no sɛ pesewa du.",   // the lab voice skips digits: the heard figure in Twi too
            com.tekyerema.voice.understand.TwiSpeechText.prepare("Mete sɛ “10 pesos”, enti mɛfa no sɛ 10 pesewas."))
    }

    @Test fun `a person filled in from an earlier turn is said in the read-back`() {
        com.tekyerema.voice.HeardAs.noteReference("him", "Osei Kwame")
        assertEquals("Taking 'him' as Osei Kwame, from before.",
            com.tekyerema.voice.HeardAs.line("call Osei Kwame", "make_call", JSONObject().put("contact", "Osei Kwame")))
    }

    // ================================================================== 7. networks and message bodies

    @Test fun `a contact named like a network keeps its name, and MTN in a message stays as said`() {
        LanguagePipeline.vocabulary = { EntityCorrector.Vocab.of(listOf("MTM Ventures"), emptyList()) }
        assertEquals("send 5 cedis to MTM Ventures", LanguagePipeline.process("send 5 cedis to MTM Ventures", Lang.GH_EN, Lang.GH_EN).canonical)
        // with a contact called "MTM …" on the phone, "MTM" is that contact's word, never rewritten
        assertEquals("buy MTM airtime", LanguagePipeline.process("buy MTM airtime", Lang.GH_EN, Lang.GH_EN).canonical)
        LanguagePipeline.vocabulary = { vocab }
        assertEquals("buy MTN airtime", LanguagePipeline.process("buy MTM airtime", Lang.GH_EN, Lang.GH_EN).canonical)
        assertEquals("tell Kwame the MTM office sells 5 pesos credit",
            LanguagePipeline.process("tell Kwame the MTM office sells 5 pesos credit", Lang.GH_EN, Lang.GH_EN).canonical)
    }

    @Test fun `the Twi reply translator keeps you and I've as words`() {
        val terms = com.tekyerema.language.ReplyLocalizer.protectedTerms(
            "Send this message to Richmond Apenensu?\n“Please call me”\nYou didn't name Richmond Apenensu.")
        assertFalse(terms.toString(), terms.contains("You"))
        assertFalse(com.tekyerema.language.ReplyLocalizer.protectedTerms("Okay, I've left it.").contains("I've"))
    }
}
