package com.tekyerema

import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.FinalActionGuard
import com.tekyerema.agent.MessageFidelity
import com.tekyerema.tools.OpenSettingTool
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner's round on the A20e, 14 October 2026 00:28–00:33: the Telegram message, and "turn off the data and turn on the Wi-Fi". */
class PhoneRoundFourteenOctTest {

    // ------------------------------------------------------------------ the message (D-027)
    private val goal = "can you tell Richmond on telegram that I'm there I have issues with the file right in fixing it"

    @Test fun dictated_words_are_tidied_into_sentences_never_into_other_words() {
        assertEquals("I'm there. I have issues with the file right in fixing it.",
            MessageFidelity.tidy("I'm there I have issues with the file right in fixing it"))
        assertEquals("I'm on my way.", MessageFidelity.tidy("um I'm on my way"))
        assertEquals("Okay I will call you later.", MessageFidelity.tidy("okay i will call you later"))   // a leading "okay" before a non-word stays
        assertEquals("I think I'm late.", MessageFidelity.tidy("I think I'm late"))                        // "think" joins clauses: no break
        assertEquals("Meeting moved to 3. I'm sorry.", MessageFidelity.tidy("meeting moved to 3 I'm sorry"))
        assertEquals("Already punctuated, I'm fine!", MessageFidelity.tidy("Already punctuated, I'm fine!"))
    }

    @Test fun a_polished_message_is_kept_a_different_one_is_not() {
        val d = MessageFidelity.dictated(goal)!!
        assertEquals("I'm there I have issues with the file right in fixing it", d.raw)
        assertTrue(MessageFidelity.faithful("I'm there. I have some issues with the file, but I'm fixing it.", d))
        assertTrue(MessageFidelity.faithful("I'm there. I'm having an issue with the file, I'm fixing it now.", d))
        assertFalse(MessageFidelity.faithful("I'm at the office. The file is broken, call me back.", d))
        assertFalse(MessageFidelity.faithful("Richmond", d))
    }

    @Test fun the_planners_typed_message_goes_out_tidied_and_a_search_box_is_left_alone() {
        val asHeard = AgentDecision.CallTool("type", JSONObject().put("element_id", 17).put("submit", true)
            .put("text", "I'm there I have issues with the file right in fixing it"), "planner")
        val sent = FinalActionGuard.apply(asHeard, goal, emptyList()) as AgentDecision.CallTool
        assertEquals("I'm there. I have issues with the file right in fixing it.", sent.args.getString("text"))
        val polished = AgentDecision.CallTool("type", JSONObject().put("element_id", 17).put("submit", true)
            .put("text", "I'm there. I have some issues with the file, but I'm fixing it."), "planner")
        assertEquals("I'm there. I have some issues with the file, but I'm fixing it.",
            (FinalActionGuard.apply(polished, goal, emptyList()) as AgentDecision.CallTool).args.getString("text"))
        val search = AgentDecision.CallTool("type", JSONObject().put("element_id", 6).put("text", "Richmond"), "planner")
        assertTrue(FinalActionGuard.apply(search, goal, emptyList()) === search)
    }

    // ------------------------------------------------------------------ the switches on One UI's panels
    private fun tv(id: Int, label: String?) = UiElement(id, "TextView", label, Box(0, id * 80, 900, id * 80 + 70))
    private fun sw(id: Int, on: Boolean) = UiElement(id, "Switch", null, Box(900, id * 80, 1000, id * 80 + 70), clickable = true,
        checkable = true, checked = on)

    /** The A20e's Internet panel as uiautomator read it (14 Oct 2026): unlabelled switches after sibling labels. */
    private val internetPanel = UiSnapshot("com.android.settings", null, listOf(
        tv(0, "Internet Connectivity"), tv(1, "Wi-Fi"), sw(2, on = false), tv(3, "STUDENT"), tv(4, "Connected"),
        tv(5, "PRINCE's HP"), tv(6, "eduroam"), tv(7, "Mobile data"), tv(8, "MTN"), sw(9, on = true),
        tv(10, "See more"), tv(11, "Done")), 0L)
    private val wifiPanel = UiSnapshot("com.android.settings", null, listOf(
        tv(0, "Wi-Fi"), tv(1, "Wi-Fi"), sw(2, on = false), tv(3, "STUDENT"), tv(4, "See more"), tv(5, "Done")), 0L)

    @Test fun the_switch_beside_its_label_is_found_on_the_a20e_panels() {
        assertEquals(9, OpenSettingTool.findToggle(internetPanel, "mobile_data")!!.id)
        assertEquals(2, OpenSettingTool.findToggle(internetPanel, "wifi")!!.id)
        assertEquals(2, OpenSettingTool.findToggle(wifiPanel, "wifi")!!.id)
        assertNull(OpenSettingTool.findToggle(wifiPanel, "mobile_data"))
        assertNull("never the first checkable thing", OpenSettingTool.findToggle(wifiPanel, "bluetooth"))
    }

    @Test fun android_15s_internet_dialog_and_its_non_breaking_hyphen() {
        // The Pixel emulator's System UI "Internet" dialog (14 Oct 2026): the switches carry descriptions, "Wi‑Fi" with U+2011.
        fun swd(id: Int, desc: String, on: Boolean) = UiElement(id, "Switch", desc, Box(900, id * 80, 1000, id * 80 + 70),
            clickable = true, checkable = true, checked = on)
        val dialog = UiSnapshot("com.android.systemui", null, listOf(tv(0, "Internet"), tv(1, "Wi-Fi is off"), tv(2, "T-Mobile"),
            tv(3, "Connected / 3G"), swd(4, "Mobile data", true), tv(5, "Wi‑Fi"), swd(6, "Wi‑Fi", false), tv(7, "Done")), 0L)
        assertEquals(4, OpenSettingTool.findToggle(dialog, "mobile_data")!!.id)
        assertEquals(6, OpenSettingTool.findToggle(dialog, "wifi")!!.id)
        val emuWifiPanel = UiSnapshot("com.android.settings", null, listOf(tv(0, "Wi‑Fi"), tv(1, "Wi‑Fi"), sw(2, on = false),
            tv(3, "Settings"), tv(4, "Done")), 0L)
        assertEquals(2, OpenSettingTool.findToggle(emuWifiPanel, "wifi")!!.id)
    }

    @Test fun the_state_is_read_from_the_targets_own_switch_and_a_confirm_dialog_is_answered_as_asked() {
        val after = internetPanel.copy(elements = internetPanel.elements.map { if (it.id == 9) it.copy(checked = false) else it })
        val toggle = OpenSettingTool.findToggle(internetPanel, "mobile_data")!!
        assertEquals("mobile data is now off — not Wi-Fi's switch, the first unlabelled one", false, OpenSettingTool.stateOf(after, toggle, "mobile_data"))
        val dialog = UiSnapshot("com.android.settings", null, listOf(tv(0, "Turn off mobile data?"),
            UiElement(1, "Button", "Cancel", Box(0, 400, 400, 470), clickable = true),
            UiElement(2, "Button", "Turn off", Box(500, 400, 900, 470), clickable = true)), 0L)
        assertEquals(2, OpenSettingTool.confirmButton(dialog, want = false)!!.id)
        assertNull(OpenSettingTool.confirmButton(dialog, want = true))
    }
}
