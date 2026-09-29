package com.tekyerema

import com.tekyerema.agent.References
import com.tekyerema.agent.RetrievalNeed
import com.tekyerema.agent.research.Candidate
import com.tekyerema.agent.research.Candidates
import com.tekyerema.agent.research.ChoiceStore
import com.tekyerema.agent.research.Decide
import com.tekyerema.agent.research.OptionAnswer
import com.tekyerema.agent.research.QuestionBudget
import com.tekyerema.agent.research.SafeUrl
import com.tekyerema.agent.research.SpokenOptions
import com.tekyerema.agent.research.TargetResearch
import com.tekyerema.agent.search.Source
import com.tekyerema.core.SecureStore
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Workstream E — research before acting and the ambiguity policy (research brief §B): the LOCATE class, deterministic
 * candidates from search sources (the UG Sakai example), risk-scaled thresholds, spoken options and their answers, the
 * question budget, https-only opening, and the encrypted, expiring, forgettable memory of the user's picks.
 */
class ResearchAmbiguityTest {
    @After fun reset() { SecureStore.keyProvider = null; References.clear() }

    /** The sources a search for "University of Ghana Sakai login" returned (research brief [B-web1]). */
    private val sakaiSources = listOf(
        Source("UG Sakai : Gateway : Welcome", "https://sakai.ug.edu.gh/"),
        Source("Login Required - UG Sakai", "https://sakai.ug.edu.gh/portal/xlogin"),
        Source("I can't login Sakai. What should I do? - UGCS", "https://ugcs.ug.edu.gh/i-cant-login-sakai-what-should-i-do"),
        Source("University of Ghana Sakai Portal Login, Registration, E-Learning, Admission", "https://yen.com.gh/183566-university-ghana-sakai-portal-login-registration-e-learning-admission.html"),
        Source("Sakai Login - UG Sakai Portal Guide", "https://mabumbe.com/sakai-login/")
    )

    // ------------------------------------------------------------------ LOCATE
    @Test fun locate_finds_an_external_destination_and_nothing_else() {
        assertEquals("university of ghana sakai", RetrievalNeed.locate("Open University of Ghana Sakai"))
        assertEquals("ug student portal", RetrievalNeed.locate("please open the UG student portal."))
        assertEquals("ecg website", RetrievalNeed.locate("go to the ECG website"))
        assertEquals("gra.gov.gh", RetrievalNeed.locate("visit gra.gov.gh"))
        assertNull("an app", RetrievalNeed.locate("open whatsapp"))
        assertNull("an app, even with an organisation word", RetrievalNeed.locate("open my bank app"))
        assertNull("a phone setting", RetrievalNeed.locate("open wifi settings"))
        assertNull("directions, not a website", RetrievalNeed.locate("take me to the hospital"))
        assertNull("near me is a maps search", RetrievalNeed.locate("open a hospital near me"))
        assertNull("a question", RetrievalNeed.locate("what is the capital of Ghana"))
        assertEquals("questions still classify as before", RetrievalNeed.Need.NONE, RetrievalNeed.classify("open University of Ghana Sakai"))
        assertTrue(RetrievalNeed.hint(RetrievalNeed.Need.LOCATE)!!.contains("open_url"))
    }

    // ------------------------------------------------------------------ candidates
    @Test fun sakai_the_official_domain_wins_and_guide_sites_are_dropped() {
        val c = Candidates.from(sakaiSources, "university of ghana sakai")
        val hosts = c.map { it.host }
        assertEquals("sakai.ug.edu.gh", hosts.first())
        assertFalse("guide sites never become candidates", hosts.any { it.contains("yen.com.gh") || it.contains("mabumbe") })
        assertEquals("one candidate per host", hosts.distinct(), hosts)
        assertEquals("the portal's own root, not its deep login path", "https://sakai.ug.edu.gh/", c.first().url)
        assertTrue(c.first().institutional)
        val help = c.first { it.host == "ugcs.ug.edu.gh" }
        assertTrue("a help article on the same domain scores well below", c.first().score - help.score >= 0.3)
        assertEquals("UG Sakai", Candidates.label(c.first(), "university of ghana sakai"))
        // The T1 (open) decision: one clear official site → act.
        val d = Decide.decide(c, Decide.Tier.OPEN)
        assertEquals("sakai.ug.edu.gh", (d as Decide.Outcome.Act).pick.host)
    }

    @Test fun gemini_redirect_sources_use_the_domain_in_the_title_and_http_is_upgraded() {
        val c = Candidates.from(listOf(
            Source("sakai.ug.edu.gh", "https://vertexaisearch.cloud.google.com/grounding-api-redirect/AbC"),
            Source("UG Sakai", "http://sakai.ug.edu.gh/portal")), "ug sakai")
        assertEquals(1, c.size)
        assertTrue(c.single().url.startsWith("https://sakai.ug.edu.gh"))
        assertTrue(Candidates.from(listOf(Source("no domain here", "https://vertexaisearch.cloud.google.com/x")), "ug sakai").isEmpty())
    }

    @Test fun a_site_the_user_named_is_not_dropped_as_a_guide() {
        val c = Candidates.from(listOf(Source("MyJoyOnline - Ghana News", "https://www.myjoyonline.com/")), "myjoyonline website")
        assertEquals("myjoyonline.com", c.single().host)
    }

    @Test fun several_portals_of_one_university_are_asked_about() {
        val c = Candidates.from(listOf(
            Source("UG Sakai : Gateway : Welcome", "https://sakai.ug.edu.gh/"),
            Source("MIS Web", "https://ienabler.ug.edu.gh/"),
            Source("Academic Affairs - University of Ghana", "https://www.ug.edu.gh/aad")), "ug portal")
        val d = Decide.decide(c, Decide.Tier.OPEN) as Decide.Outcome.Ask
        assertEquals(3, d.options.size)
        assertFalse(d.narrowing)
    }

    // ------------------------------------------------------------------ thresholds
    private fun cand(host: String, score: Double) = Candidate(host, "https://$host/", host, score, false)

    @Test fun thresholds_scale_with_what_a_wrong_pick_costs() {
        val close = listOf(cand("a.gh", 0.70), cand("b.gh", 0.55))
        // read-only: top ≥ 0.62 and gap ≥ 0.10 → act
        assertTrue(Decide.decide(close, Decide.Tier.READ_ONLY) is Decide.Outcome.Act)
        // opening a site: top ≥ 0.75 and gap ≥ 0.15 → 0.70 isn't enough → ask
        assertTrue(Decide.decide(close, Decide.Tier.OPEN) is Decide.Outcome.Ask)
        assertTrue(Decide.decide(listOf(cand("a.gh", 0.80), cand("b.gh", 0.70)), Decide.Tier.OPEN) is Decide.Outcome.Ask)   // gap 0.10
        assertTrue(Decide.decide(listOf(cand("a.gh", 0.80), cand("b.gh", 0.60)), Decide.Tier.OPEN) is Decide.Outcome.Act)
        // send / call / pay: never on a guess, even a perfect one
        assertTrue(Decide.decide(listOf(cand("a.gh", 1.0)), Decide.Tier.CONSEQUENTIAL) is Decide.Outcome.Ask)
        // nothing above the floor
        assertEquals(Decide.Outcome.NoneFound, Decide.decide(listOf(cand("a.gh", 0.2)), Decide.Tier.OPEN))
        // more than three fit: ask about the top three and offer "something else"
        val many = (1..5).map { cand("s$it.gh", 0.6) }
        val ask = Decide.decide(many, Decide.Tier.OPEN) as Decide.Outcome.Ask
        assertEquals(3, ask.options.size); assertTrue(ask.narrowing)
    }

    // ------------------------------------------------------------------ spoken options + answers
    @Test fun options_are_read_as_which_one_a_b_or_c_without_counts() {
        assertEquals("Which one: UG Sakai, MIS Web, or Academic Affairs?", SpokenOptions.question(listOf("UG Sakai", "MIS Web", "Academic Affairs")))
        assertEquals("Which one: A, or B? Or something else?", SpokenOptions.question(listOf("A", "B"), narrowing = true))
        assertEquals("Do you mean UG Sakai?", SpokenOptions.question(listOf("UG Sakai")))
        assertEquals("Which Ama do you mean? Ama Mensah, or Ama Boateng?",
            SpokenOptions.withQuestion("Which Ama do you mean?", SpokenOptions.split("Ama Mensah, Ama Boateng")))
        assertEquals("How much should I send?", SpokenOptions.withQuestion("How much should I send?", emptyList()))
        assertEquals("at most three read out", 3,
            SpokenOptions.split("Ama Mensah Boateng Snr, Kofi Annan Junior Jnr, Yaw Mensah of Tema, Efua Sutherland Grey Jnr").size)
        assertEquals("four only when each is tiny", 4, SpokenOptions.split("MTN, Telecel, AirtelTigo, Glo").size)
        assertFalse(SpokenOptions.question(listOf("A", "B", "C")).contains(Regex("\\d")))
    }

    @Test fun an_answer_picks_by_ordinal_name_none_or_stop() {
        val o = listOf("UG Sakai", "MIS Web", "Academic Affairs")
        fun p(a: String) = OptionAnswer.parse(a, o)
        assertEquals(OptionAnswer.Pick.Chosen(1), p("the second one"))
        assertEquals(OptionAnswer.Pick.Chosen(0), p("The first."))
        assertEquals(OptionAnswer.Pick.Chosen(2), p("the last one"))
        assertEquals(OptionAnswer.Pick.Chosen(1), p("number two"))
        assertEquals(OptionAnswer.Pick.Chosen(0), p("Sakai"))
        assertEquals("a name wins over the word 'one'", OptionAnswer.Pick.Chosen(1), p("the MIS one"))
        assertEquals(OptionAnswer.Pick.Chosen(2), p("academic affairs please"))
        assertEquals(OptionAnswer.Pick.None, p("none of them"))
        assertEquals(OptionAnswer.Pick.None, p("something else"))
        assertEquals(OptionAnswer.Pick.Stop, p("stop"))
        assertEquals(OptionAnswer.Pick.Stop, p("never mind"))
        assertEquals(OptionAnswer.Pick.Unclear, p("hmm"))
        assertEquals("a tie is unclear", OptionAnswer.Pick.Unclear, OptionAnswer.parse("ama", listOf("Ama Mensah", "Ama Boateng")))
        // One option offered ("Do you mean UG Sakai?"): yes / no.
        assertEquals(OptionAnswer.Pick.Chosen(0), OptionAnswer.parse("yes please", listOf("UG Sakai")))
        assertEquals(OptionAnswer.Pick.None, OptionAnswer.parse("no", listOf("UG Sakai")))
    }

    @Test fun the_question_budget_never_repeats_and_caps_choice_questions() {
        val b = QuestionBudget(maxChoices = 2)
        assertNull(b.refusal("Which Ama?", isChoice = true)); b.note("Which Ama?", true)
        assertNotNull("never the same question twice", b.refusal("which ama", isChoice = true))
        assertNull(b.refusal("How much?", isChoice = false)); b.note("How much?", false)
        assertNull(b.refusal("Which network?", isChoice = true)); b.note("Which network?", true)
        assertNotNull("a third 'which one' question is refused", b.refusal("Which account?", isChoice = true))
        assertNull("information only the user has isn't capped", b.refusal("What reference?", isChoice = false))
    }

    // ------------------------------------------------------------------ opening safely
    @Test fun only_plain_https_addresses_are_opened() {
        assertEquals("https://sakai.ug.edu.gh/", SafeUrl.httpsOrNull("https://sakai.ug.edu.gh"))
        assertEquals("https://sakai.ug.edu.gh/portal/xlogin?x=1", SafeUrl.httpsOrNull(" https://SAKAI.ug.edu.gh/portal/xlogin?x=1 "))
        for (bad in listOf("http://sakai.ug.edu.gh", "intent://scan/#Intent;scheme=zxing;end", "file:///sdcard/x", "javascript:alert(1)",
                "content://contacts/people", "data:text/html,hi", "https://user@evil.example.com/", "https://sakai.ug.edu.gh:8443/",
                "https://127.0.0.1/", "https://localhost/", "https://exa mple.com/", "", null))
            assertNull("$bad must be refused", SafeUrl.httpsOrNull(bad))
        assertEquals("sakai dot u g dot edu dot g h", SafeUrl.spokenHost("sakai.ug.edu.gh"))
    }

    // ------------------------------------------------------------------ remembered choices
    private fun key(): javax.crypto.SecretKey = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun choices_are_encrypted_expire_and_can_be_forgotten() {
        val k = key()
        SecureStore.keyProvider = { k }
        var now = 1_000_000L
        val f = java.io.File.createTempFile("choices", ".enc").apply { delete(); deleteOnExit() }
        val s = ChoiceStore(f, clock = { now }, ttlMs = { 10_000L })
        s.remember("University of Ghana Sakai", "url", "https://sakai.ug.edu.gh/", "UG Sakai", "research")
        assertTrue(f.exists())
        assertFalse("nothing in the clear", String(f.readBytes(), Charsets.ISO_8859_1).contains("sakai"))
        // Another process (a fresh store on the same file) recalls it, whatever filler words are said.
        val again = ChoiceStore(f, clock = { now }, ttlMs = { 10_000L })
        assertEquals("https://sakai.ug.edu.gh/", again.recall("the university of ghana sakai website")?.value)
        // Unused past the TTL → gone.
        now += 20_000L
        assertNull(again.recall("university of ghana sakai"))
        // Forgotten by name, the recent one, or all.
        again.remember("ug sakai", "url", "https://sakai.ug.edu.gh/", "UG Sakai", "asked")
        again.remember("mis web", "url", "https://ienabler.ug.edu.gh/", "MIS Web", "asked")
        assertTrue(again.forget("Sakai"))
        assertNull(again.recall("ug sakai"))
        assertEquals("MIS Web", again.forgetRecent())
        again.remember("gra", "url", "https://gra.gov.gh/", "GRA", "research")
        again.clear()
        assertEquals(0, again.size())
        assertFalse("an empty store leaves no file", f.exists())
    }

    @Test fun forgetting_a_choice_by_voice_is_a_fast_path_command() {
        fun c(s: String) = com.tekyerema.agent.FastIntentClassifier.classify(s)
        assertEquals(com.tekyerema.agent.FastIntent.Forget("choice"), c("forget that choice"))
        assertEquals(com.tekyerema.agent.FastIntent.Forget("choice"), c("Forget that site."))
        assertEquals(com.tekyerema.agent.FastIntent.Forget("choices"), c("forget my choices"))
        assertEquals("forget <name> still reaches the person/site path", com.tekyerema.agent.FastIntent.Forget("person", "Sakai"), c("forget Sakai"))
        assertEquals(com.tekyerema.agent.FastIntent.Forget("last"), c("forget that"))
    }

    @Test fun without_a_key_choices_stay_in_memory_only() {
        SecureStore.keyProvider = { null }
        val f = java.io.File.createTempFile("choices", ".enc").apply { delete(); deleteOnExit() }
        val s = ChoiceStore(f)
        s.remember("ug sakai", "url", "https://sakai.ug.edu.gh/", "UG Sakai", "asked")
        assertFalse(f.exists())
        assertEquals("UG Sakai", s.recall("ug sakai")?.label)
    }

    // ------------------------------------------------------------------ the whole pre-step
    private class Harness(
        val sources: List<Source>? = null,
        val answer: String? = null,
        val localApps: Set<String> = emptySet(),
        val online: Boolean = true
    ) {
        val store = ChoiceStore(null)
        val searches = mutableListOf<String>()
        val asked = mutableListOf<String>()
        fun deps() = TargetResearch.Deps(store, online,
            search = { q -> searches += q; sources },
            isLocalApp = { it in localApps },
            handlerFor = { "com.android.chrome" },
            ask = { q, _ -> asked += q; answer })
    }

    @Test fun one_clear_official_site_is_opened_with_a_verify_claim_and_remembered_once_it_opened() = runTest {
        val h = Harness(sakaiSources)
        val plan = TargetResearch.plan("open University of Ghana Sakai", h.deps())!!
        assertEquals(listOf("university of ghana sakai official website login"), h.searches)
        assertTrue("no question for one clear official site", h.asked.isEmpty())
        val open = plan.open!!
        assertEquals("open_intent", open.name)
        assertEquals("open_url", open.args.getString("template"))
        assertEquals("https://sakai.ug.edu.gh/", open.args.getString("url"))
        assertEquals("foreground_is: com.android.chrome", open.expects)
        assertEquals(TargetResearch.TAG, open.rationale)
        assertNull("nothing remembered before it opened", h.store.recall("university of ghana sakai"))
        assertEquals("Opening UG Sakai at sakai dot u g dot edu dot g h.", plan.after(success = true, verified = true))
        assertEquals("https://sakai.ug.edu.gh/", h.store.recall("university of ghana sakai")?.value)
        // Next time: straight from memory, no search, no question.
        val h2 = Harness(sakaiSources)
        h2.store.remember("university of ghana sakai", "url", "https://sakai.ug.edu.gh/", "UG Sakai", "research")
        val again = TargetResearch.plan("open university of ghana sakai", h2.deps())!!
        assertTrue(h2.searches.isEmpty())
        assertEquals("Opening UG Sakai, like last time.", again.after(success = true, verified = true))
    }

    @Test fun a_failed_open_hands_back_to_the_planner_and_is_not_remembered() = runTest {
        val h = Harness(sakaiSources)
        val plan = TargetResearch.plan("open University of Ghana Sakai", h.deps())!!
        assertNull(plan.after(success = false, verified = false))
        assertNull(h.store.recall("university of ghana sakai"))
    }

    private val ugPortals = listOf(
        Source("UG Sakai : Gateway : Welcome", "https://sakai.ug.edu.gh/"),
        Source("MIS Web", "https://ienabler.ug.edu.gh/"),
        Source("Academic Affairs - University of Ghana", "https://www.ug.edu.gh/aad"))

    @Test fun close_candidates_are_asked_about_once_and_the_pick_is_opened() = runTest {
        val h = Harness(ugPortals, answer = "the second one")
        val plan = TargetResearch.plan("open the UG portal", h.deps())!!
        assertEquals(listOf("Which one: UG Sakai, MIS Web, or Academic Affairs?"), h.asked)
        assertEquals("https://ienabler.ug.edu.gh/", plan.open!!.args.getString("url"))
        assertEquals("ordinals resolve at T0 later too", listOf("UG Sakai", "MIS Web", "Academic Affairs"), References.lastOptions)
        assertEquals("Opening MIS Web.", plan.after(true, true))
        assertEquals("asked", h.store.recall("ug portal")?.source)
    }

    @Test fun stop_none_unclear_and_silence_are_each_handled() = runTest {
        val stop = TargetResearch.plan("open the UG portal", Harness(ugPortals, answer = "stop").deps())!!
        assertTrue(stop.end!!.stopped); assertNull(stop.open)
        val none = TargetResearch.plan("open the UG portal", Harness(ugPortals, answer = "none of those").deps())!!
        assertNull(none.open); assertTrue(none.note!!.contains("none"))
        val unclear = TargetResearch.plan("open the UG portal", Harness(ugPortals, answer = "the green one").deps())!!
        assertNull(unclear.open); assertTrue(unclear.note!!.contains("ienabler.ug.edu.gh"))
        val silent = TargetResearch.plan("open the UG portal", Harness(ugPortals, answer = null).deps())!!
        assertFalse(silent.end!!.stopped)
    }

    @Test fun an_app_on_the_phone_offline_or_no_search_leaves_the_normal_route() = runTest {
        val app = Harness(sakaiSources, localApps = setOf("university of ghana sakai"))
        assertNull(TargetResearch.plan("open University of Ghana Sakai", app.deps()))
        assertTrue(app.searches.isEmpty())
        assertNull(TargetResearch.plan("open University of Ghana Sakai", Harness(sakaiSources, online = false).deps()))
        assertNull(TargetResearch.plan("open University of Ghana Sakai", Harness(sources = null).deps()))
        assertNull("not a LOCATE request", TargetResearch.plan("call Ama", Harness(sakaiSources).deps()))
        val nothing = TargetResearch.plan("open the GRA portal", Harness(emptyList()).deps())!!
        assertTrue(nothing.note!!.contains("no official site"))
    }

    @Test fun not_that_one_forgets_the_pick_and_asks_instead_of_guessing_again() = runTest {
        val h = Harness(sakaiSources, answer = "the UGCS one")
        TargetResearch.plan("open University of Ghana Sakai", h.deps())!!.after(true, true)
        val fix = TargetResearch.plan("no, not that one", h.deps())!!
        assertNull("the wrong pick is forgotten", h.store.recall("university of ghana sakai")?.takeIf { it.value == "https://sakai.ug.edu.gh/" })
        assertEquals("asked this time, the wrong site left out", 1, h.asked.size)
        assertFalse(h.asked.single().contains("UG Sakai,"))
        assertTrue(fix.open!!.args.getString("url").contains("ugcs.ug.edu.gh"))
    }

    @Test fun unattended_runs_never_ask_and_never_guess() = runTest {
        val h = Harness(ugPortals)
        val d = TargetResearch.Deps(h.store, true, search = { ugPortals }, isLocalApp = { false }, handlerFor = { null }, ask = null)
        val plan = TargetResearch.plan("open the UG portal", d)!!
        assertNull(plan.open)
        assertTrue(plan.note!!.contains("don't open any"))
    }
}
