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

    @Test fun `the core block comes first and in a fixed order, then the groups`() {
        val t = pick("send 10 cedis to Kofi with mobile money")
        assertEquals(ToolSelector.DEFAULT_CORE, t.take(ToolSelector.DEFAULT_CORE.size))
        assertTrue(t.contains("mobile_money"))
        // used and discovered tools come last, in arrival order
        val withUsed = ToolSelector.select("open settings", setOf("open_app", "set_timer"), setOf("calendar"), all)
        assertEquals(listOf("calendar", "set_timer"), withUsed.takeLast(2))
    }
}
