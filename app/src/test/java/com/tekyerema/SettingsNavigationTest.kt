package com.tekyerema

import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.BrainContext
import com.tekyerema.agent.LocalBrain
import com.tekyerema.agent.RoutingBrain
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-04 (Pixel emulator, 12 October 2026): "open settings and go to the battery page" opened Settings and then tried to open
 * an APP called "battery page"; the router ended the task as "not installed". A page inside the app that was just opened is
 * a tap on its label; a page that isn't one tap away hands the task to the planner from inside the app; and a planner that
 * hunts icons on the home screen while the goal names an installed app gets that app opened by name.
 */
class SettingsNavigationTest {
    private fun ctx(goal: String, screen: JSONObject = JSONObject(), history: List<String> = emptyList()) =
        BrainContext(goal, screen, history, JSONArray(), JSONArray())

    private fun screenOf(app: String, vararg labels: String): JSONObject {
        val els = JSONArray()
        labels.forEachIndexed { i, l -> els.put(JSONObject().put("id", i).put("label", l).put("clickable", true)) }
        return JSONObject().put("app", app).put("elements", els)
    }

    private val HOME = "com.google.android.apps.nexuslauncher"
    private val SETTINGS = "com.android.settings"

    @Test fun `a page named after open-app is a label to tap, not another app`() {
        assertEquals("battery", LocalBrain.inAppNavTarget("go to the battery page"))
        assertEquals("Software information", LocalBrain.inAppNavTarget("go to the Software information page"))
        assertEquals("about phone", LocalBrain.inAppNavTarget("open about phone"))
        assertEquals("Wi-Fi", LocalBrain.inAppNavTarget("take me to the Wi-Fi settings"))
        assertEquals("Display", LocalBrain.inAppNavTarget("show me the Display screen"))
        // really another app: a bare known app, or a one-word name after an open verb
        assertNull(LocalBrain.inAppNavTarget("open Chrome"))
        assertNull(LocalBrain.inAppNavTarget("open Telegram"))
        assertNull(LocalBrain.inAppNavTarget("open Jiji"))
        // nothing named, or a second instruction
        assertNull(LocalBrain.inAppNavTarget("open it"))
        assertNull(LocalBrain.inAppNavTarget("send a message"))
    }

    @Test fun `open settings and go to the battery page - open, tap the page, done`() = runBlocking {
        val goal = "open Settings and go to the battery page"
        val b = LocalBrain()
        val first = b.decide(ctx(goal, screenOf(HOME, "Phone", "Messages", "Chrome")))
        assertTrue(first.toString(), first is AgentDecision.CallTool && first.name == "open_app")
        assertEquals("Settings", (first as AgentDecision.CallTool).args.getString("app"))

        val h1 = listOf("""open_app({"app":"Settings"}) → success verified=true; Settings is open.""")
        val second = b.decide(ctx(goal, screenOf(SETTINGS, "Network & internet", "Battery", "Display"), h1))
        assertTrue(second.toString(), second is AgentDecision.CallTool && second.name == "tap")
        assertEquals("battery", (second as AgentDecision.CallTool).args.getString("label"))

        val h2 = h1 + """tap({"label":"battery"}) → success verified=true; Tapped 'Battery' in com.android.settings."""
        val third = b.decide(ctx(goal, screenOf(SETTINGS, "Battery Saver", "Battery percentage", "Battery usage"), h2))
        assertTrue(third.toString(), third is AgentDecision.Done)
        assertEquals("Settings is open. Battery is open.", (third as AgentDecision.Done).summary)
    }

    @Test fun `an app that reopens on the wanted page is already there - no tap on its own title`() = runBlocking {
        val goal = "open Settings and go to the battery page"
        val b = LocalBrain()
        b.decide(ctx(goal, screenOf(HOME)))
        val h1 = listOf("""open_app({"app":"Settings"}) → success verified=true; Settings is open.""")
        val onBattery = screenOf(SETTINGS, "Battery", "Battery Saver", "Battery percentage").put("screen", "Battery")
        val d = b.decide(ctx(goal, onBattery, h1))
        assertTrue(d.toString(), d is AgentDecision.Done)
        assertEquals("Settings is open. Battery is open.", (d as AgentDecision.Done).summary)
    }

    @Test fun `a page that is not one tap away hands the task to the planner from inside the app`() = runBlocking {
        val goal = "open Settings and go to the Software information page"
        val b = LocalBrain()
        b.decide(ctx(goal, screenOf(HOME)))
        val h1 = listOf("""open_app({"app":"Settings"}) → success verified=true; Settings is open.""")
        val second = b.decide(ctx(goal, screenOf(SETTINGS, "About phone"), h1)) as AgentDecision.CallTool
        assertEquals("tap", second.name)
        assertEquals("Software information", second.args.getString("label"))
        // the tap tool scrolled and found no such label
        val h2 = h1 + """tap({"label":"Software information"}) → fail; verified=false; No element matching 'Software information' was found, even after scrolling."""
        val third = b.decide(ctx(goal, screenOf(SETTINGS, "About phone"), h2))
        assertTrue(third.toString(), third is AgentDecision.CannotDo && (third as AgentDecision.CannotDo).reasonClass == LocalBrain.DEFER)
        // and the router does NOT call that a missing app: an earlier step of this task succeeded
        assertNull(RoutingBrain.appNotInstalled(h1 + """open_app({"app":"Software information page"}) → fail [none]: I couldn't find an app called Software information page on this phone. It isn't installed, or the name was misheard."""))
        // a first-step miss is still the honest "not installed" line
        assertTrue(RoutingBrain.appNotInstalled(listOf("""open_app({"app":"Zorblax"}) → fail [none]: I couldn't find an app called Zorblax on this phone. It isn't installed, or the name was misheard."""))!!
            .startsWith("I couldn't find an app called Zorblax"))
    }

    @Test fun `a planner gesture on the home screen becomes open_app when the goal names an installed app`() = runBlocking {
        val cloud = ScriptedBrain({ tool("long_press", JSONObject().put("element_id", 4), "element_present: Apps list") })
        val router = RoutingBrain(LocalBrain(), cloud, appForGoal = { g -> if (g.contains("settings", true)) "Settings" to SETTINGS else null },
            linkCheck = null, answers = null, localModel = null) { true }
        // "tell me the Android version" is nothing the rules do, so the whole request is the planner's; the history is not
        // empty, so the router's own "open the named app first" step is past — the cloud answers with a home-screen gesture
        val history = listOf("""read_screen({}) → success verified=true; You're on the home screen.""")
        val d = router.decide(ctx("Open Settings and tell me the Android version", screenOf(HOME, "Phone", "Chrome"), history))
        assertTrue(d.toString(), d is AgentDecision.CallTool && d.name == "open_app")
        assertEquals("Settings", (d as AgentDecision.CallTool).args.getString("app"))
        assertEquals("foreground_is: $SETTINGS", d.expects)
        // not when the user asked for the gesture itself, and not when the goal names no app
        val swipe = ScriptedBrain({ tool("swipe", JSONObject().put("direction", "left")) })
        val r2 = RoutingBrain(LocalBrain(), swipe, appForGoal = { "Settings" to SETTINGS }, linkCheck = null, answers = null, localModel = null) { true }
        val d2 = r2.decide(ctx("swipe left on the home screen and tell me the android version", screenOf(HOME), history))
        assertTrue(d2.toString(), d2 is AgentDecision.CallTool && d2.name == "swipe")
    }
}
