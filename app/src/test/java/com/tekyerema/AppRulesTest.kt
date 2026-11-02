package com.tekyerema

import com.tekyerema.android.AppResolver.AppInfo
import com.tekyerema.core.Redact
import com.tekyerema.core.config.AppConfig
import com.tekyerema.operator.AppRule
import com.tekyerema.operator.AppRuleEntry
import com.tekyerema.operator.AppRuleVoice
import com.tekyerema.operator.AppRules
import com.tekyerema.operator.Capability
import com.tekyerema.operator.ExecutionMode
import com.tekyerema.operator.MemoryOperatorStore
import com.tekyerema.operator.OpCommand
import com.tekyerema.operator.OperatorCommands
import com.tekyerema.operator.OperatorPolicy
import com.tekyerema.policy.PolicyConfig
import com.tekyerema.policy.ScreenPrivacy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * D8 — user-editable per-app rules: persisted in OperatorPolicy, unioned with the app's config lists (a user can only make
 * things stricter), set by voice on the fast path (a loosening needs a spoken yes), and never on Tɛkyerɛma itself or the
 * emergency call path.
 */
class AppRulesTest {
    private val apps = listOf(
        AppInfo("WhatsApp", "com.whatsapp"), AppInfo("Chrome", "com.android.chrome"), AppInfo("Jiji", "com.olx.ssa.gh"),
        AppInfo("MoMo", "com.mtn.momo"), AppInfo("GCB Bank", "com.gcb.mobile"), AppInfo("Ecobank", "com.ecobank.xpress"),
        AppInfo("Phone", "com.samsung.android.dialer"), AppInfo("Tɛkyerɛma", "com.tekyerema"), AppInfo("LinkedIn", "com.linkedin.android"),
        AppInfo("Settings", "com.android.settings")
    )
    private var clock = 1_000_000L

    @Before fun setUp() {
        AppConfig.loadFrom(emptyList()); OperatorPolicy.resetForTest(MemoryOperatorStore())
        AppRuleVoice.clearPending(); AppRules.phoneExtra = emptySet()
    }
    @After fun tearDown() { AppRuleVoice.clearPending(); OperatorPolicy.resetForTest(); AppConfig.loadFrom(emptyList()) }

    private fun c(s: String) = OperatorCommands.classify(s)
    private fun say(s: String): String? {
        clock += 1_000
        AppRuleVoice.answer(s, clock)?.let { return it }
        val cmd = c(s) ?: return null
        return AppRuleVoice.decide(cmd, apps, clock)
    }

    // ---------------------------------------------------------------- persistence and the union with config

    @Test fun rules_survive_a_restart_and_garbage_or_protected_entries_are_dropped() {
        val store = MemoryOperatorStore()
        OperatorPolicy.resetForTest(store)
        assertTrue(OperatorPolicy.setAppRule("com.whatsapp", "WhatsApp", AppRule.NEVER))
        assertTrue(OperatorPolicy.setAppRule("com.android.chrome", "Chrome", AppRule.GUIDE))
        OperatorPolicy.resetForTest(store)   // a restart: load from the same store
        assertEquals(AppRule.NEVER, OperatorPolicy.appRule("com.whatsapp"))
        assertEquals(AppRule.GUIDE, OperatorPolicy.appRule("com.android.chrome"))
        assertEquals("WhatsApp", OperatorPolicy.appRules.getValue("com.whatsapp").label)

        store.put(OperatorPolicy.K_APP_RULES, "{not json")
        OperatorPolicy.resetForTest(store)
        assertTrue(OperatorPolicy.appRules.isEmpty())   // unreadable → no user rule (config still applies)
        store.put(OperatorPolicy.K_APP_RULES, AppRules.encode(listOf(AppRuleEntry("com.tekyerema", "Me", AppRule.NEVER),
            AppRuleEntry("com.samsung.android.dialer", "Phone", AppRule.NEVER), AppRuleEntry("com.whatsapp", "WhatsApp", AppRule.GUIDE))))
        OperatorPolicy.resetForTest(store)
        assertEquals(setOf("com.whatsapp"), OperatorPolicy.appRules.keys)

        OperatorPolicy.resetForTest()   // the reset path clears them
        assertTrue(OperatorPolicy.appRules.isEmpty())
    }

    @Test fun no_rule_on_tekyerema_itself_or_the_emergency_call_path() {
        assertFalse(OperatorPolicy.setAppRule("com.tekyerema", "Tɛkyerɛma", AppRule.NEVER))
        for (p in listOf("com.samsung.android.dialer", "com.google.android.dialer", "com.android.phone", "com.samsung.android.incallui"))
            assertFalse(p, OperatorPolicy.setAppRule(p, "Phone", AppRule.GUIDE))
        AppRules.phoneExtra = setOf("com.acme.dialer")   // this phone's default dialer, learned at run time
        assertFalse(OperatorPolicy.setAppRule("com.acme.dialer", "Acme Phone", AppRule.NEVER))
        assertTrue(OperatorPolicy.appRules.isEmpty())
        assertTrue(say("never touch the phone app")!!.contains("call for help in an emergency"))
        assertTrue(say("stay out of Tɛkyerɛma")!!.startsWith("I can't make a rule for Nexus"))
        assertTrue(OperatorPolicy.appRules.isEmpty())
    }

    @Test fun config_and_user_rules_are_a_union_and_config_cant_be_lifted() {
        AppConfig.loadFrom(listOf("policy.json" to """{"policy":{"denyApps":["com.gcb.mobile"],"guideOnlyApps":["com.olx.ssa.gh"]}}"""))
        OperatorPolicy.setAppRule("com.whatsapp", "WhatsApp", AppRule.NEVER)
        OperatorPolicy.setAppRule("com.android.chrome", "Chrome", AppRule.GUIDE)
        assertEquals(setOf("com.gcb.mobile", "com.whatsapp"), PolicyConfig.denyApps())
        assertEquals(setOf("com.olx.ssa.gh", "com.android.chrome"), PolicyConfig.guideOnlyApps())
        assertEquals(setOf("com.gcb.mobile"), PolicyConfig.configDenyApps())
        // The loop's gate reads the union.
        assertEquals(ScreenPrivacy.DENIED, ScreenPrivacy.appRule("read_state", false, false, "com.whatsapp",
            PolicyConfig.denyApps(), PolicyConfig.guideOnlyApps()))
        // A user can't lift a config rule, by voice or otherwise.
        assertTrue(say("you can use GCB Bank again")!!.contains("app's own settings"))
        assertTrue(say("you can use Jiji again")!!.contains("app's own settings"))
        assertTrue(PolicyConfig.denyApps().contains("com.gcb.mobile"))
        // …but can make a config guide-only app stricter.
        assertTrue(say("never touch Jiji")!!.startsWith("Okay. I'll stay out of Jiji"))
        assertTrue("com.olx.ssa.gh" in PolicyConfig.denyApps())
    }

    @Test fun every_change_reaches_the_activity_log_in_plain_words() {
        val never = AppRules.encode(listOf(AppRuleEntry("com.whatsapp", "WhatsApp", AppRule.NEVER)))
        val both = AppRules.encode(listOf(AppRuleEntry("com.whatsapp", "WhatsApp", AppRule.NEVER),
            AppRuleEntry("com.android.chrome", "Chrome", AppRule.GUIDE)))
        val k = OperatorPolicy.K_APP_RULES
        assertEquals("put WhatsApp off limits (never touch)", OperatorPolicy.describeChange(k, never, null))
        assertEquals("set Chrome to guide only", OperatorPolicy.describeChange(k, both, never))
        assertEquals("allowed WhatsApp again", OperatorPolicy.describeChange(k,
            AppRules.encode(listOf(AppRuleEntry("com.android.chrome", "Chrome", AppRule.GUIDE))), both))
        assertEquals("allowed WhatsApp again", OperatorPolicy.describeChange(k, null, never))
        assertEquals("changed the app rules", OperatorPolicy.describeChange(k, "1"))
        // The data export carries the choice (package and rule).
        OperatorPolicy.setAppRule("com.whatsapp", "WhatsApp", AppRule.NEVER)
        assertTrue(OperatorPolicy.toJson().toString().contains("\"rule\":\"NEVER\""))
    }

    // ---------------------------------------------------------------- voice: what counts as an app-rule command

    @Test fun the_spoken_forms() {
        for (s in listOf("Never touch WhatsApp", "don't open my bank app", "Stay out of MoMo.", "keep away from Jiji",
            "don’t go into WhatsApp", "WhatsApp is off limits", "make MoMo off-limits", "you can't touch WhatsApp", "stop opening WhatsApp"))
            assertEquals(s, AppRule.NEVER, (c(s) as? OpCommand.AppRuleSet)?.rule)
        assertEquals(OpCommand.AppRuleSet("my bank app", AppRule.NEVER), c("don't open my bank app"))
        for (s in listOf("Only guide me in Chrome", "just tell me what to do in Jiji", "in Chrome just guide me", "make Chrome guide only",
            "guide only in Chrome", "don't tap anything in Chrome"))
            assertEquals(s, AppRule.GUIDE, (c(s) as? OpCommand.AppRuleSet)?.rule)
        for (s in listOf("You can use WhatsApp again", "you can open WhatsApp again", "remove the rule on WhatsApp",
            "WhatsApp is not off limits anymore", "stop guiding me in Chrome"))
            assertEquals(s, OpCommand.AppRuleSet(if (s.contains("Chrome")) "chrome" else "whatsapp", null), c(s))
        for (s in listOf("Which apps are off limits?", "what are my app rules", "which apps can't you touch", "which apps do you stay out of"))
            assertEquals(s, OpCommand.AppRulesList, c(s))
    }

    @Test fun commands_that_were_already_something_else_stay_that() {
        assertEquals(OpCommand.Mode(ExecutionMode.GUIDE_ONLY), c("don't touch my phone"))
        assertEquals(OpCommand.Mode(ExecutionMode.GUIDE_ONLY), c("just guide me"))
        assertEquals(OpCommand.Revoke(Capability.MONEY), c("never touch my money"))
        assertEquals(OpCommand.Cloud(false), c("don't use the internet"))
        assertEquals(OpCommand.Restore(Capability.MESSAGES), c("you can send messages again"))
        assertEquals(OpCommand.Cloud(true), c("you can use the cloud again"))
        // Not a rule: a pronoun, the phone itself, or a request that goes on after the app.
        for (s in listOf("don't open it", "never touch my phone", "stay out of this", "don't open WhatsApp and call Ama",
            "don't open WhatsApp open Telegram instead", "never touch anything", "don't open WhatsApp use SMS",
            // Everyday requests that only look alike.
            "allow notifications", "unblock Kofi", "you can use the camera now", "just explain it in Twi",
            "just tell me what to do with the rice", "just guide me through it"))
            assertNull(s, c(s))
        // A looser form naming no app or rule is left to the rest of the pipeline (null), not answered "can't find".
        assertNull(AppRuleVoice.decide(c("just tell me what to do in the morning")!!, apps, clock))
        assertNull(AppRuleVoice.decide(c("you can use the torch again")!!, apps, clock))
    }

    // ---------------------------------------------------------------- voice: deciding

    @Test fun a_new_rule_applies_at_once_and_says_so_plainly() {
        val line = say("never touch WhatsApp")!!
        assertEquals(AppRule.NEVER, OperatorPolicy.appRule("com.whatsapp"))
        assertTrue(line, line.startsWith("Okay. I'll stay out of WhatsApp") && line.contains("you can use WhatsApp again"))
        assertTrue(say("only guide me in Chrome")!!.startsWith("Okay. In Chrome I'll only guide you"))
        assertEquals(AppRule.GUIDE, OperatorPolicy.appRule("com.android.chrome"))
        // Guide → Never is stricter: no question.
        assertTrue(say("stay out of Chrome")!!.startsWith("Okay. I'll stay out of Chrome"))
        assertEquals(AppRule.NEVER, OperatorPolicy.appRule("com.android.chrome"))
        assertEquals("I'm already staying out of WhatsApp.", say("never touch WhatsApp"))
        // Plain words: no emoji, no counts or step numbers.
        for (l in listOf(line, AppRules.listLine(OperatorPolicy.appRules.values, emptyList(), emptyList())))
            assertFalse(l, Regex("\\d|[\\x{1F300}-\\x{1FAFF}]").containsMatchIn(l))
    }

    @Test fun names_resolve_tolerantly_ambiguity_asks_and_a_missing_app_is_said() {
        val which = say("don't open my bank app")!!
        assertTrue(which, which.startsWith("A few apps match bank: Ecobank and GCB Bank") && which.contains("“never touch Ecobank”"))
        assertTrue(OperatorPolicy.appRules.isEmpty())   // never a coin-flip
        assertEquals("I can't find an app called Twitter on this phone.", say("never touch Twitter")?.replace("twitter", "Twitter"))
        // A NEW rule doesn't use the fuzzy rescue: "that link" isn't LinkedIn.
        assertNull(c("don't open that link")?.let { AppRuleVoice.decide(it, apps, clock) }?.takeIf { it.contains("LinkedIn") })
        assertTrue(OperatorPolicy.appRules.isEmpty())
        // An alias ("whats app", "jiji ghana") and a spoken-apart name still resolve.
        assertTrue(say("never touch whats app")!!.contains("WhatsApp"))
        assertEquals(AppRule.NEVER, OperatorPolicy.appRule("com.whatsapp"))
    }

    @Test fun lifting_a_rule_needs_a_spoken_yes() {
        say("never touch WhatsApp")
        val ask = say("you can use WhatsApp again")!!
        assertTrue(ask, ask.endsWith("yes or no?") && ask.contains("never to touch WhatsApp"))
        assertEquals(AppRule.NEVER, OperatorPolicy.appRule("com.whatsapp"))   // nothing loosened yet
        assertEquals(AppRules.keptLine("WhatsApp", AppRule.NEVER), say("no"))
        assertEquals(AppRule.NEVER, OperatorPolicy.appRule("com.whatsapp"))

        say("you can use WhatsApp again")
        assertEquals(AppRules.clearedLine("WhatsApp"), say("yes"))
        assertNull(OperatorPolicy.appRule("com.whatsapp"))
        assertEquals(AppRules.noRuleLine("WhatsApp"), say("you can use WhatsApp again"))
    }

    @Test fun never_to_guide_is_a_loosening_too_and_other_words_let_the_question_lapse() {
        say("never touch Chrome")
        val ask = say("only guide me in Chrome")!!
        assertTrue(ask, ask.contains("Should I read it and guide you there"))
        assertEquals(AppRule.NEVER, OperatorPolicy.appRule("com.android.chrome"))
        // Anything else: nothing loosened, the words are handled as usual (null here = not an answer).
        assertNull(AppRuleVoice.answer("what's the time", clock + 1))
        assertNull(AppRuleVoice.answer("yes", clock + 2))   // the question is gone
        assertEquals(AppRule.NEVER, OperatorPolicy.appRule("com.android.chrome"))
        // A late yes (after the minute) opens nothing.
        say("only guide me in Chrome")
        assertNull(AppRuleVoice.answer("yes", clock + OperatorCommands.CONFIRM_WINDOW_MS + 1))
        assertEquals(AppRule.NEVER, OperatorPolicy.appRule("com.android.chrome"))
        say("only guide me in Chrome")
        assertTrue(say("yes")!!.startsWith("Okay. In Chrome I'll only guide you"))
        assertEquals(AppRule.GUIDE, OperatorPolicy.appRule("com.android.chrome"))
    }

    @Test fun the_list_names_the_users_rules_and_the_apps_own() {
        assertTrue(say("which apps are off limits")!!.startsWith("There are no app rules"))
        say("never touch WhatsApp"); say("stay out of MoMo"); say("only guide me in Chrome")
        val l = say("which apps are off limits")!!
        assertTrue(l, l.contains("I stay out of MoMo and WhatsApp.") && l.contains("In Chrome I only guide you."))
        AppConfig.loadFrom(listOf("policy.json" to """{"policy":{"denyApps":["com.gcb.mobile"]}}"""))
        assertTrue(say("what are my app rules")!!.contains("The app's own settings keep me out of GCB Bank."))
    }

    // ---------------------------------------------------------------- the gates other layers call

    @Test fun never_stops_a_launch_guide_does_not_and_emergencies_are_exempt() {
        OperatorPolicy.setAppRule("com.whatsapp", "WhatsApp", AppRule.NEVER)
        OperatorPolicy.setAppRule("com.android.chrome", "Chrome", AppRule.GUIDE)
        assertEquals(AppRules.neverOpenLine("WhatsApp"), AppRules.openRefusal("com.whatsapp", "WhatsApp"))
        assertNull(AppRules.openRefusal("com.android.chrome", "Chrome"))   // opened, to be guided there
        assertNull(AppRules.openRefusal("com.whatsapp", "WhatsApp", emergency = true))
        // App-driving tools: both rules stop a press; the emergency help text ignores the user's rules only.
        assertNotNull(com.tekyerema.tools.driveRefusal("com.whatsapp"))
        assertNotNull(com.tekyerema.tools.driveRefusal("com.android.chrome"))
        assertNull(com.tekyerema.tools.driveRefusal("com.whatsapp", emergency = true))
        AppConfig.loadFrom(listOf("policy.json" to """{"policy":{"denyApps":["com.whatsapp"]}}"""))
        assertNotNull(com.tekyerema.tools.driveRefusal("com.whatsapp", emergency = true))   // config still holds
        assertNotNull(AppRules.openRefusal("com.whatsapp", "WhatsApp", emergency = true))
    }

    @Test fun a_never_apps_home_screen_icon_is_not_the_way_in_either() {
        OperatorPolicy.setAppRule("com.whatsapp", "WhatsApp", AppRule.NEVER)
        val g = com.tekyerema.agent.LauncherGuard
        val r = g.check("tap", onHome = true, labelMissing = false, goal = "message Ama on WhatsApp", named = "WhatsApp",
            openAppTried = true, notInstalled = null, targetLabel = "WhatsApp")!!
        assertTrue(r, r.contains("never to touch WhatsApp") && r.contains(AppRules.neverOpenLine("WhatsApp")))
        assertNull(g.check("tap", onHome = true, labelMissing = false, goal = "open Chrome", named = "Chrome",
            openAppTried = true, notInstalled = null, targetLabel = "Chrome"))
        // Not on the home screen: the guard's other rules only (inside a never app the loop's gate already blocks).
        assertNull(g.check("tap", onHome = false, labelMissing = false, goal = "x", named = null,
            openAppTried = false, notInstalled = null, targetLabel = "WhatsApp"))
    }

    @Test fun a_never_app_is_not_read_its_notifications_included() {
        assertFalse(Redact.deniedPackage("com.whatsapp"))
        OperatorPolicy.setAppRule("com.whatsapp", "WhatsApp", AppRule.NEVER)
        assertTrue(Redact.deniedPackage("com.whatsapp"))
        assertTrue(ScreenPrivacy.isDenied("com.whatsapp"))
        OperatorPolicy.setAppRule("com.whatsapp", "WhatsApp", AppRule.GUIDE)
        assertFalse(Redact.deniedPackage("com.whatsapp"))   // guide only: read and explained
        assertFalse(ScreenPrivacy.isDenied("com.whatsapp"))
    }

    @Test fun the_control_row_summary() {
        assertEquals("None", AppRules.summary(0, 0))
        assertEquals("1 app", AppRules.summary(1, 0))
        assertEquals("3 apps", AppRules.summary(2, 1))
    }
}
