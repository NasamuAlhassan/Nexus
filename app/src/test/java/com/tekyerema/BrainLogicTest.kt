package com.tekyerema

import com.tekyerema.agent.AgentBrain
import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.BrainContext
import com.tekyerema.agent.LocalBrain
import com.tekyerema.agent.OfflineCapability
import com.tekyerema.agent.RoutingBrain
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Directive §49 brain tests: on-device reasoning, tool selection, offline capability boundary, hybrid routing. */
class BrainLogicTest {

    private fun ctx(goal: String, screen: JSONObject = JSONObject(), history: List<String> = emptyList()) =
        BrainContext(goal, screen, history, JSONArray(), JSONArray())

    private fun decide(brain: AgentBrain, goal: String, screen: JSONObject = JSONObject()) =
        runBlocking { brain.decide(ctx(goal, screen)) }

    private fun screenWith(vararg labels: String): JSONObject {
        val arr = JSONArray()
        labels.forEachIndexed { i, l -> arr.put(JSONObject().put("id", i).put("role", "Button").put("label", l)) }
        return JSONObject().put("app", "Settings").put("elements", arr)
    }

    private fun tool(d: AgentDecision): AgentDecision.CallTool {
        assertTrue("expected CallTool, got $d", d is AgentDecision.CallTool); return d as AgentDecision.CallTool
    }

    // ---- LocalBrain: natural language → structured tool call (fast path) ----
    @Test fun local_launches_app() {
        val t = tool(decide(LocalBrain(), "open WhatsApp"))
        assertEquals("open_app", t.name); assertEquals("WhatsApp", t.args.getString("app"))
    }

    @Test fun local_open_app_still_works_for_multiword_names() {
        // Guard against over-eager compound detection (§1.9): genuine multi-word app names still open.
        assertEquals("open_app", tool(decide(LocalBrain(), "open Play Store")).name)
        assertEquals("Google Play Music", tool(decide(LocalBrain(), "open Google Play Music")).args.getString("app"))
    }

    @Test fun local_defers_compound_open_requests() {
        // §1.9 — the fast path does ONE action; a compound must go to the planner, not mis-open one app
        // (the audit's "open whatsapp and send prince a message" → opened Messages).
        assertTrue(decide(LocalBrain(), "open whatsapp and send prince a message") is AgentDecision.CannotDo)
        // even without a conjunction, an embedded messaging verb marks a second instruction
        assertTrue(decide(LocalBrain(), "open whatsapp send prince a message") is AgentDecision.CannotDo)
    }

    @Test fun local_screen_read_is_honest_when_service_off() {
        // §1.19 — "no perception" (accessibility service off) must not be reported as an empty screen.
        val off = JSONObject().put("app", "unknown").put("perception", JSONObject().put("source", "none"))
        val b = LocalBrain()
        val d = runBlocking {
            b.decide(ctx("what's on my screen", off))        // step 1 → read_state
            b.decide(ctx("what's on my screen", off))        // step 2 → summarize
        }
        assertTrue(d is AgentDecision.Done)
        assertTrue((d as AgentDecision.Done).summary.contains("accessibility service is off"))
    }

    @Test fun local_screen_read_is_honest_on_protected_screen() {
        // §1.20 — a FLAG_SECURE screen blocks reading; say so instead of implying it's empty.
        val secure = JSONObject().put("app", "com.bank").put("secureWindow", true)
            .put("perception", JSONObject().put("source", "tree"))
        val b = LocalBrain()
        val d = runBlocking {
            b.decide(ctx("what's on my screen", secure))
            b.decide(ctx("what's on my screen", secure))
        }
        assertTrue((d as AgentDecision.Done).summary.contains("protected"))
    }

    @Test fun local_verb_need_not_be_sentence_initial() {
        // §6.2 — "Bagyo frɛ 0552093690" (a Twi vocative before the verb) must still resolve to a call.
        val c = tool(decide(LocalBrain(), "Bagyo frɛ 0552093690"))
        assertEquals("make_call", c.name); assertEquals("0552093690", c.args.getString("number"))
    }

    @Test fun local_reads_notifications_and_speaks_the_result() {
        // §6.1/§6.6 — "Kenkan me nkra" (read my messages) → read_notifications, NOT a launcher screen-read.
        val b = LocalBrain()
        val d1 = tool(decide(b, "Kenkan me nkra"))
        assertEquals("read_notifications", d1.name)
        // step 2 speaks the tool's actual result lifted from history (not a canned line)
        val d2 = runBlocking { b.decide(ctx("Kenkan me nkra",
            history = listOf("read_notifications({}) → success [native]; verified=true (read 2); You have 2: from Ama; from Kofi"))) }
        assertTrue(d2 is AgentDecision.Done)
        assertEquals("You have 2: from Ama; from Kofi", (d2 as AgentDecision.Done).summary)
    }

    @Test fun local_find_contact_uses_search_accelerator() {
        // §6.1 — "find prince in my contacts" → search_contacts with the bare name.
        val c = tool(decide(LocalBrain(), "find prince in my contacts"))
        assertEquals("search_contacts", c.name); assertEquals("prince", c.args.getString("query"))
    }

    @Test fun local_twi_timer_is_language_aware() {
        // §6.7 — Twi numerals + a Twi minute unit must produce a timer (was hardcoded Gh-EN → silently failed).
        val t = tool(decide(LocalBrain(), "hyɛ bere anum simma"))   // "set a timer, five minutes"
        assertEquals("set_timer", t.name); assertEquals(300, t.args.getInt("seconds"))
    }

    @Test fun local_play_music_and_settings_toggle() {
        // §A.2 — generic local music
        assertEquals("play_music", tool(decide(LocalBrain(), "play my music")).name)
        // §A.2 — settings toggles open the setting screen (Android 11 can't flip directly)
        val w = tool(decide(LocalBrain(), "turn on wifi")); assertEquals("open_setting", w.name); assertEquals("wifi", w.args.getString("target"))
        val d = tool(decide(LocalBrain(), "turn off mobile data")); assertEquals("mobile_data", d.args.getString("target"))
        // E-10: the direction travels with the call; with none, the screen is only opened.
        assertEquals("on", w.args.getString("state"))
        assertEquals("off", d.args.getString("state"))
        assertEquals("off", tool(decide(LocalBrain(), "turn bluetooth off")).args.getString("state"))
        assertEquals("off", tool(decide(LocalBrain(), "disable location")).args.getString("state"))
    }

    @Test fun open_setting_matches_its_own_switch_only() {
        val t = com.tekyerema.tools.OpenSettingTool
        assertTrue(t.labelPattern("wifi")!!.containsMatchIn("Wi-Fi"))
        assertTrue(t.labelPattern("wifi")!!.containsMatchIn("Use Wi-Fi"))
        assertFalse(t.labelPattern("wifi")!!.containsMatchIn("Switch"))          // the old "wi" keyword hit this
        assertFalse(t.labelPattern("wifi")!!.containsMatchIn("Window animation"))
        assertTrue(t.labelPattern("bluetooth")!!.containsMatchIn("Bluetooth"))
        assertNull(t.labelPattern("sound"))                                        // no switch to flip for a screen
    }

    @Test fun local_mobile_money_send_and_airtime() {
        // §A.2 — send money: amount + recipient, opens the MoMo menu (user does PIN)
        val s = tool(decide(LocalBrain(), "send 50 cedis to Ama"))
        assertEquals("mobile_money", s.name)
        assertEquals("send_money", s.args.getString("_kind"))
        assertEquals("50", s.args.getString("_amount"))
        assertEquals("Ama", s.args.getString("_recipient"))
        // buy airtime with an amount
        val a = tool(decide(LocalBrain(), "buy airtime of 5 cedis"))
        assertEquals("mobile_money", a.name); assertEquals("buy_airtime", a.args.getString("_kind")); assertEquals("5", a.args.getString("_amount"))
    }

    /** A judge on the A20e, 2026-09-29: "Send money to 0553238" was read back as "Send 553238 cedis", and "Send money
     *  to 055" as "Send 55 cedis" — the amount was the first digits of the sentence, i.e. the phone number. */
    @Test fun the_amount_never_comes_out_of_the_phone_number() {
        for (g in listOf("Send money to 0553238.", "Send money to 055.", "Send money to 0244123456", "send cash to 05533238011")) {
            val d = decide(LocalBrain(), g)
            if (d is AgentDecision.CallTool) {
                val amount = d.args.optString("amount").ifEmpty { d.args.optString("_amount") }
                assertTrue("$g → $d", amount.isEmpty() || amount.length < 3)
                assertFalse("$g → $d", d.name == "momo_send" || d.name == "mobile_money")
            }
        }
        val ok = tool(decide(LocalBrain(), "send 5 cedis to 0244123456"))
        assertEquals("momo_send", ok.name); assertEquals("5", ok.args.getString("amount")); assertEquals("0244123456", ok.args.getString("number"))
        val after = tool(decide(LocalBrain(), "send money to 0244123456 5 cedis"))
        assertEquals("5", after.args.getString("amount")); assertEquals("0244123456", after.args.getString("number"))
        val cash = tool(decide(LocalBrain(), "send cash of 10 cedis to 0244123456"))
        assertEquals("momo_send", cash.name); assertEquals("10", cash.args.getString("amount"))
        assertEquals("0244123456", tool(decide(LocalBrain(), "send 5 cedis to +233 24 412 3456")).args.getString("number"))
        // A misheard number (7 or 11 digits) is said back and asked for again — never read back for approval.
        for (g in listOf("send 5 cedis to 0553238", "send 5 cedis to 05533238011")) {
            val d = decide(LocalBrain(), g)
            assertTrue("$g → $d", d is AgentDecision.Done && d.summary.contains("Ghana mobile number has 10"))
        }
        // The owner's live send: "use 'Prince' as the reference" went out as "Payment".
        val ref = tool(decide(LocalBrain(), "Send 0.1 cedis to 0552093690 use 'Prince' as the reference"))
        assertEquals("momo_send", ref.name); assertEquals("0.1", ref.args.getString("amount"))
        assertEquals("0552093690", ref.args.getString("number")); assertEquals("prince", ref.args.getString("reference").lowercase())
        assertEquals("school fees", tool(decide(LocalBrain(), "send 5 cedis to 0244123456 reference school fees")).args.getString("reference").lowercase())
        assertEquals("rent", tool(decide(LocalBrain(), "send 5 cedis to 0244123456 with reference: rent")).args.getString("reference").lowercase())
        // After momo_send stops at the PIN, the task is over — nothing plans past it (a live send, 2026-09-29).
        val h = listOf("momo_send({number=0552093690}) → success [native]; verified=false (no expectation given); " +
            "I've entered everything. The Mobile Money PIN screen is up — enter your PIN to send 0.1 cedis to 0552093690. I never handle the PIN.")
        assertTrue(com.tekyerema.agent.RoutingBrain.pinHandoff(h)!!.contains("enter your PIN"))
        assertNull(com.tekyerema.agent.RoutingBrain.pinHandoff(listOf("momo_send({}) → fail [none]; verified=false (x); The menu didn't respond")))
        assertNull(com.tekyerema.agent.RoutingBrain.pinHandoff(listOf("open_app({app=WhatsApp}) → success [intent]; verified=true (x); Opened")))
        assertTrue(com.tekyerema.policy.TargetSemantics.credentialField.containsMatchIn("Total Amount is GHS 0.10. Enter MM # for next"))
        val M = com.tekyerema.tools.MoMoSendTool
        assertNull(M.numberProblem("0244123456")); assertNull(M.numberProblem("233244123456"))
        assertTrue(M.numberProblem("0553238")!!.contains("7 digits"))
        assertTrue(M.numberProblem("05533238011")!!.contains("11 digits"))
        assertTrue(M.numberProblem(null)!!.contains("don't have"))
    }

    @Test fun mobile_money_helper_parses_and_maps() {
        val MM = com.tekyerema.tools.GhanaMobileMoney
        assertEquals("50", MM.parseAmount("send 50 cedis to Ama"))
        assertEquals("2.50", MM.parseAmount("buy 2.50 airtime"))
        assertEquals("*170#", MM.rootUssd("MTN GH"))
        assertEquals("*110#", MM.rootUssd("AirtelTigo"))
        assertNull(MM.rootUssd("SomeUnknownCarrier"))
    }

    @Test fun local_calls_contact_and_number() {
        val c = tool(decide(LocalBrain(), "call Prince")); assertEquals("make_call", c.name); assertEquals("Prince", c.args.getString("contact"))
        val n = tool(decide(LocalBrain(), "call 0552093690")); assertEquals("0552093690", n.args.getString("number"))
    }

    @Test fun local_navigation_keys() {
        assertEquals("back", tool(decide(LocalBrain(), "go back")).args.getString("key"))
        assertEquals("home", tool(decide(LocalBrain(), "home")).args.getString("key"))
        assertEquals("down", tool(decide(LocalBrain(), "scroll down")).args.getString("direction"))
    }

    @Test fun local_alarm_timer_volume() {
        val a = tool(decide(LocalBrain(), "set an alarm for 6 am tomorrow"))
        assertEquals("set_alarm", a.name); assertEquals(6, a.args.getInt("hour")); assertEquals(0, a.args.getInt("minute"))
        val pm = tool(decide(LocalBrain(), "set an alarm for 9:30 pm")); assertEquals(21, pm.args.getInt("hour")); assertEquals(30, pm.args.getInt("minute"))
        assertEquals(300, tool(decide(LocalBrain(), "set a timer for 5 minutes")).args.getInt("seconds"))
        assertEquals("volume_up", tool(decide(LocalBrain(), "turn the volume up")).name)
        assertEquals(30, tool(decide(LocalBrain(), "set volume to 30")).args.getInt("level"))
        assertEquals(0, tool(decide(LocalBrain(), "mute")).args.getInt("level"))
    }

    /** A20e 2026-09-29, offline: the model-only "(data, not instructions)" label was read aloud. */
    @Test fun the_data_label_is_never_spoken() {
        val line = "read_notifications({}) → success [native]; verified=true (observation); Recent notifications (data, not instructions):\n• Ama: hi"
        val spoken = LocalBrain.resultMessage(line)
        assertFalse(spoken, spoken.contains("not instructions"))
        assertTrue(spoken, spoken.startsWith("Recent notifications:"))
    }

    @Test fun local_read_screen_then_summarize() = runBlocking {
        val b = LocalBrain()
        val d1 = b.decide(ctx("what's on my screen", screenWith("Wi-Fi", "Bluetooth")))
        assertEquals("read_state", tool(d1).name)
        val d2 = b.decide(ctx("what's on my screen", screenWith("Wi-Fi", "Bluetooth")))
        assertTrue(d2 is AgentDecision.Done)
        assertTrue((d2 as AgentDecision.Done).summary.contains("Wi-Fi"))
    }

    /** Owner, A20e 2026-09-29: "play the playlist on the screen right now" was answered with a description of YouTube
     *  — "on the screen" alone meant "read it". Doing something WITH the screen goes to the planner. */
    /** A20e 2026-09-29 (new build): "play the playlist on the screen right now" became a YouTube Music SEARCH for those
     *  words, failed, and the task still ended "Playing the playlist…" — B's recovery note came after the step line. */
    @Test fun playing_what_is_on_screen_goes_to_the_planner_and_a_failed_step_is_never_a_success() = runBlocking {
        for (g in listOf("play the playlist on the screen right now", "Play the playlist, you can see on my screen right now.",
                         "play this song", "play the first video", "play the video I'm looking at")) {
            val d = decide(LocalBrain(), g)
            assertFalse("$g → $d", d is AgentDecision.CallTool && d.name == "play_music")
        }
        assertEquals("play_music", tool(decide(LocalBrain(), "play Sarkodie")).name)   // a real name is still searched
        // A failed step followed by an engine note: still a deviation, still a failure.
        val h = listOf("play_music({query=x}) → fail [intent]; verified=false (x); the search opened but nothing plays",
            "recovery: FAILED → ALT_ROUTE — try another route")
        assertTrue(RoutingBrain.deviated(h))
        assertTrue(LocalBrain.lastStep(h).startsWith("play_music("))
        val b = LocalBrain()
        b.decide(ctx("play Sarkodie"))
        val second = b.decide(BrainContext("play Sarkodie", JSONObject(), h, JSONArray(), JSONArray()))
        assertFalse("a failed play is never reported as playing: $second", second is AgentDecision.Done)
    }

    @Test fun an_action_on_something_on_screen_is_not_a_screen_reading() = runBlocking {
        for (g in listOf("Play the playlist on the screen right now.", "Play the playlist, you can see on my screen right now.",
                         "can you play the video on my screen", "tap the first song on the screen",
                         "what's on my screen? play it", "open the link on my screen")) {
            assertFalse(g, LocalBrain.isScreenRead(g.lowercase()))
            val d = decide(LocalBrain(), g)
            assertFalse("$g → $d", d is AgentDecision.CallTool && d.name == "read_state")
        }
        for (g in listOf("what's on my screen", "what is on the screen?", "read the screen", "read my screen please",
                         "describe the screen", "what am I looking at", "what do you see", "which app am I in",
                         "tell me what is on my screen"))
            assertTrue(g, LocalBrain.isScreenRead(g.lowercase()))
    }

    @Test fun local_read_screen_speaks_app_name_not_package() = runBlocking {
        val home = screenWith("At a glance").put("app", "com.google.android.apps.nexuslauncher").put("appName", "the home screen")
        val b = LocalBrain()
        b.decide(ctx("what's on my screen", home))
        val s = (b.decide(ctx("what's on my screen", home)) as AgentDecision.Done).summary
        assertTrue(s, s.startsWith("You're on the home screen."))
        assertFalse(s, s.contains("nexuslauncher"))
        // No human name known → say nothing about where, rather than read out a package id.
        val raw = screenWith("OK").put("app", "com.example.thing")
        val b2 = LocalBrain(); b2.decide(ctx("what's on my screen", raw))
        val s2 = (b2.decide(ctx("what's on my screen", raw)) as AgentDecision.Done).summary
        assertFalse(s2, s2.contains("com.example"))
    }

    @Test fun local_failed_single_action_is_cannot_do_not_done() = runBlocking {
        val b = LocalBrain()
        assertEquals("open_app", tool(b.decide(ctx("open the Zorblax app"))).name)
        val d = b.decide(ctx("open the Zorblax app", history = listOf(
            "open_app({\"app\":\"Zorblax app\"}) → fail [none]; verified=false (no outcome); I couldn't find an app called Zorblax on this phone.")))
        assertTrue("expected CannotDo, got $d", d is AgentDecision.CannotDo)
        d as AgentDecision.CannotDo
        assertEquals(LocalBrain.ACTION_FAILED, d.reasonClass)   // not DEFER/NETWORK → RoutingBrain won't escalate
        assertEquals("I couldn't find an app called Zorblax on this phone.", d.detail)
    }

    @Test fun local_online_only_is_network_gated() {
        val d = decide(LocalBrain(), "search the web for the weather in Accra")
        assertTrue(d is AgentDecision.CannotDo)
        assertEquals(LocalBrain.NETWORK, (d as AgentDecision.CannotDo).reasonClass)
    }

    @Test fun local_defers_unknown_and_multistep() {
        assertEquals(LocalBrain.DEFER, (decide(LocalBrain(), "reconcile my quarterly ledgers") as AgentDecision.CannotDo).reasonClass)
        assertEquals(LocalBrain.DEFER, (decide(LocalBrain(), "open WhatsApp and then message Prince") as AgentDecision.CannotDo).reasonClass)
    }

    @Test fun local_twi_basic_verbs() {
        assertEquals("open_app", tool(decide(LocalBrain(), "bue WhatsApp")).name)
        assertEquals("make_call", tool(decide(LocalBrain(), "frɛ Prince")).name)
    }

    @Test fun local_tap_requires_label_on_screen() {
        assertEquals("tap", tool(decide(LocalBrain(), "tap Settings", screenWith("Settings", "Search"))).name)
        // label absent → defer rather than tap a nonexistent element
        val d = decide(LocalBrain(), "tap Nonexistent", screenWith("Settings"))
        assertEquals(LocalBrain.DEFER, (d as AgentDecision.CannotDo).reasonClass)
    }

    // ---- OfflineCapability boundary ----
    @Test fun capability_boundary() {
        assertTrue(OfflineCapability.needsInternet("search the internet for MTN share price"))
        assertTrue(OfflineCapability.needsInternet("what is the weather in Kumasi"))
        assertTrue(!OfflineCapability.needsInternet("what's on my screen"))
        assertTrue(!OfflineCapability.needsInternet("open WhatsApp"))
        assertTrue(!OfflineCapability.needsInternet("call Prince"))
    }

    // ---- RoutingBrain: hybrid local/cloud ----
    private class FakeBrain(private val d: AgentDecision) : AgentBrain {
        var calls = 0
        override suspend fun decide(ctx: BrainContext): AgentDecision { calls++; return d }
    }

    /** Returns each scripted decision in turn (last one repeats). */
    private class ScriptBrain(vararg ds: AgentDecision) : AgentBrain {
        private val q = ds.toList(); var calls = 0
        override suspend fun decide(ctx: BrainContext): AgentDecision = q[minOf(calls++, q.size - 1)]
    }

    @Test fun fallback_retries_once_on_a_network_blip_then_succeeds() = runBlocking {
        val dns = AgentDecision.Fail("LLM request failed: Unable to resolve host \"api.groq.com\": No address associated with hostname")
        val groq = ScriptBrain(dns, AgentDecision.Done("done"))
        val gemini = ScriptBrain(AgentDecision.Fail("Gemini request failed: Unable to resolve host"))
        val d = com.tekyerema.agent.FallbackBrain(listOf(groq, gemini), networkRetryDelayMs = 1).decide(ctx("search chrome for waakye"))
        assertEquals(AgentDecision.Done("done"), d)
        assertEquals(2, groq.calls)
    }

    @Test fun fallback_network_down_is_an_honest_cannot_do_and_quota_is_not_retried() = runBlocking {
        val dns = AgentDecision.Fail("LLM request failed: Unable to resolve host \"api.groq.com\"")
        val down = com.tekyerema.agent.FallbackBrain(listOf(ScriptBrain(dns)), networkRetryDelayMs = 1).decide(ctx("x"))
        assertTrue(down is AgentDecision.CannotDo)
        assertEquals(com.tekyerema.agent.FallbackBrain.NETWORK_LOST, (down as AgentDecision.CannotDo).reasonClass)
        assertTrue(down.detail.contains("internet"))
        val quota = ScriptBrain(AgentDecision.Fail("HTTP 429 rate limit on all keys"))
        val d = com.tekyerema.agent.FallbackBrain(listOf(quota), networkRetryDelayMs = 1).decide(ctx("x"))
        assertTrue(d is AgentDecision.Fail)
        assertEquals(1, quota.calls)   // not a network error → no retry
    }

    /** A20e on a stalled Wi-Fi: every provider timed out, the retry timed out again — 2 min 40 s of "still working". */
    @Test fun fallback_does_not_retry_a_stalled_connection() = runBlocking {
        val timeout = AgentDecision.Fail("LLM request failed: timeout")
        val slow = object : AgentBrain {
            var calls = 0
            override suspend fun decide(ctx: BrainContext): AgentDecision { calls++; kotlinx.coroutines.delay(60); return timeout }
        }
        val d = com.tekyerema.agent.FallbackBrain(listOf(slow), networkRetryDelayMs = 1, retryWithinMs = 20).decide(ctx("x"))
        assertEquals(1, slow.calls)
        assertEquals(com.tekyerema.agent.FallbackBrain.NETWORK_LOST, (d as AgentDecision.CannotDo).reasonClass)
        assertTrue(d.detail.contains("internet"))
    }

    @Test fun app_mention_finds_the_app_a_compound_goal_is_about() {
        fun c(g: String) = com.tekyerema.agent.AppMention.candidates(g)
        assertEquals("chrome", c("search chrome for jollof rice").first())
        assertEquals("whatsapp", c("open WhatsApp and send Kofi a message").first())
        assertEquals("settings", c("turn on zorblax mode in settings").first())
        assertEquals("youtube", c("play highlife music on YouTube").first())
        assertTrue(c("make the text on my phone bigger").none { it == "my phone" })
        assertTrue(c("set an alarm for 6 in the morning").isEmpty())
    }

    @Test fun router_opens_the_named_app_locally_then_hands_to_cloud() = runBlocking {
        val cloud = FakeBrain(AgentDecision.Done("cloud handled"))
        val apps = mapOf("chrome" to ("Chrome" to "com.android.chrome"))
        val appFor: (String) -> Pair<String, String>? = { g -> com.tekyerema.agent.AppMention.candidates(g).firstNotNullOfOrNull { apps[it] } }
        val r = RoutingBrain(LocalBrain(), cloud, appForGoal = appFor) { true }
        val home = JSONObject().put("app", "com.google.android.apps.nexuslauncher").put("elements", JSONArray())
        val d1 = r.decide(ctx("search chrome for waakye", home))
        assertEquals("open_app", tool(d1).name)
        assertEquals("Chrome", tool(d1).args.getString("app"))
        assertEquals(0, cloud.calls)
        // step 2 (history now has the open_app) → the cloud operator takes over, sticky
        val d2 = r.decide(ctx("search chrome for waakye", JSONObject().put("app", "com.android.chrome"),
            history = listOf("open_app({\"app\":\"Chrome\"}) → success [intent]; verified=true (foreground is com.android.chrome); Opened Chrome")))
        assertTrue(d2 is AgentDecision.Done); assertEquals(1, cloud.calls)
        // already in the app → no redundant local launch
        val r2 = RoutingBrain(LocalBrain(), FakeBrain(AgentDecision.Done("x")), appForGoal = appFor) { true }
        assertTrue(r2.decide(ctx("search chrome for waakye", JSONObject().put("app", "com.android.chrome"))) is AgentDecision.Done)
    }

    @Test fun router_uses_local_fast_path_without_cloud() {
        val cloud = FakeBrain(AgentDecision.Fail("should not be called"))
        val r = RoutingBrain(LocalBrain(), cloud) { true }
        assertEquals("open_app", tool(decide(r, "open Settings")).name)
        assertEquals(0, cloud.calls)   // local handled it; cloud never touched
    }

    @Test fun router_escalates_to_cloud_when_online_and_sticky() = runBlocking {
        val cloud = FakeBrain(AgentDecision.Done("cloud handled"))
        val r = RoutingBrain(LocalBrain(), cloud) { true }
        val d1 = r.decide(ctx("reconcile my quarterly ledgers"))   // local defers → cloud
        assertTrue(d1 is AgentDecision.Done)
        val d2 = r.decide(ctx("open WhatsApp"))                     // sticky: stays on cloud
        assertTrue(d2 is AgentDecision.Done)
        assertEquals(2, cloud.calls)
    }

    @Test fun router_offline_refuses_network_intent() {
        val cloud = FakeBrain(AgentDecision.Done("nope"))
        val r = RoutingBrain(LocalBrain(), cloud) { false }         // offline
        val d = decide(r, "search the web for the news")
        assertTrue(d is AgentDecision.CannotDo)
        assertEquals("network_unavailable", (d as AgentDecision.CannotDo).reasonClass)
    }

    @Test fun router_offline_defer_is_honest_cannotdo_not_false_success() {
        // §1.2: an offline request the operator can't ground is a FAILURE, not a completion. It must be
        // CannotDo (→ status CANNOT_DO), never Done (which would be spoken as success for work not done).
        // The detail still nudges the user by naming what IS possible offline (§1.3, from real intents).
        val r = RoutingBrain(LocalBrain(), cloud = null) { false }
        val d = decide(r, "reconcile my quarterly ledgers")
        assertTrue("offline-unknown must not read as success", d is AgentDecision.CannotDo)
        d as AgentDecision.CannotDo
        assertEquals("offline_unknown_command", d.reasonClass)
        assertTrue("nudge should list a real offline capability", d.detail.contains("open apps"))
    }

    @Test fun malformed_tool_shaped_text_is_flagged() {
        assertTrue(AgentDecision.fromText("tap(label=\"Send\")") is AgentDecision.Malformed)
        assertTrue(AgentDecision.fromText("The screen shows the home launcher.") is AgentDecision.Done)
    }
}
