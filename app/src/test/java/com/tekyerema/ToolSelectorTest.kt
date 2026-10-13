package com.tekyerema

import com.tekyerema.agent.ToolSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-05 token diet: the tool block a planner step carries is the core primitives plus the groups the request is ABOUT —
 * "open settings and go to about phone" no longer loads alarms, music and the clipboard with it (29 schemas, ~5k prompt
 * tokens a step on the Pixel emulator, 13 October 2026).
 */
class ToolSelectorTest {
    private val all = ToolSelector.DEFAULT_CORE + listOf("make_call", "reply_to_notification", "read_notifications", "search_contacts",
        "find_contact", "search_messages", "send_sms", "call_control", "call_log", "read_sms", "notification_action", "open_intent",
        "create_contact", "share_text", "share_screenshot", "mobile_money", "momo_send", "run_ussd", "set_alarm", "cancel_alarm",
        "set_timer", "set_volume", "volume_up", "volume_down", "open_setting", "play_music", "show_alarms", "calendar", "location",
        "clipboard", "drag", "pinch", "set_range", "scroll_to_find", "wait_for_element", "node_action", "read_all")

    private fun pick(goal: String) = ToolSelector.select(goal, emptySet(), emptySet(), all)

    @Test fun `a settings request carries the core block and the settings tools only`() {
        val t = pick("open settings and go to about phone and tell me the android version")
        assertTrue(t.containsAll(ToolSelector.DEFAULT_CORE))
        assertTrue(t.contains("open_setting"))
        assertFalse(t.contains("set_alarm")); assertFalse(t.contains("play_music")); assertFalse(t.contains("clipboard"))
        assertFalse(t.contains("send_sms"))
        assertTrue("sent ${t.size} tools: $t", t.size <= ToolSelector.DEFAULT_CORE.size + 2)
    }

    @Test fun `each split group loads for its own words`() {
        assertTrue(pick("set an alarm for 6 am").contains("set_alarm"))
        assertFalse(pick("set an alarm for 6 am").contains("play_music"))
        val vol = pick("turn the volume up")
        assertTrue(vol.contains("volume_up") && vol.contains("set_volume"))
        assertFalse(vol.contains("open_setting"))
        assertTrue(pick("play some highlife music").contains("play_music"))
        assertTrue(pick("what meetings do I have tomorrow").contains("calendar"))
        assertTrue(pick("copy that number").contains("clipboard"))
        assertTrue(pick("save this number as a new contact").contains("create_contact"))
    }

    @Test fun `earlier turns count only when the request leans on them`() {
        val turns = listOf("send 50 cedis to 0552093690 with mobile money", "MTN is asking for your PIN — enter it yourself to finish.")
        // A self-contained new request after a money walk: no comms or money block from the old turns (37 → 19 tools, 13 Oct 2026).
        val fresh = ToolSelector.select(ToolSelector.contextFor("open settings and go to about phone and tell me the android version", turns), emptySet(), emptySet(), all)
        assertFalse(fresh.contains("mobile_money")); assertFalse(fresh.contains("make_call"))
        assertTrue(fresh.contains("open_setting"))
        // A follow-up that leans on the conversation keeps it: "do the same for Ama" / "send it again" / a four-word ask.
        assertTrue(ToolSelector.select(ToolSelector.contextFor("do the same for Ama", turns), emptySet(), emptySet(), all).contains("mobile_money"))
        assertTrue(ToolSelector.select(ToolSelector.contextFor("try that again", turns), emptySet(), emptySet(), all).contains("mobile_money"))
        assertTrue(ToolSelector.select(ToolSelector.contextFor("and to Kofi", turns), emptySet(), emptySet(), all).contains("mobile_money"))
        assertEquals("the goal alone, when it stands alone", "turn on bluetooth please now",
            ToolSelector.contextFor("turn on bluetooth please now", turns))
    }

    @Test fun `the core block comes first and in a fixed order, then the groups`() {
        val t = pick("send 10 cedis to Kofi with mobile money")
        assertEquals(ToolSelector.DEFAULT_CORE, t.take(ToolSelector.DEFAULT_CORE.size))
        assertTrue(t.contains("mobile_money"))
        // used and discovered tools come last, in arrival order
        val withUsed = ToolSelector.select("open settings", setOf("open_app", "set_timer"), setOf("calendar"), all)
        assertEquals(listOf("calendar", "set_timer"), withUsed.takeLast(2))
    }
}
