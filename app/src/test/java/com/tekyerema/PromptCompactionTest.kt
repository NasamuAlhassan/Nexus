package com.tekyerema

import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.PerceptionRoute
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.BrainContext
import com.tekyerema.agent.BrainPrompts
import com.tekyerema.agent.LocalBrain
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-02 / N-03 — the per-step prompt under Groq's free tier (8k tokens a MINUTE per key): older steps compacted
 * (observation masking, arXiv 2508.21433) while the user's own words are never cut, what is fixed for the task placed
 * first for Groq's exact-prefix cache, and the screen listing naming what a control does only once.
 */
class PromptCompactionTest {

    private fun el(id: Int, label: String?, clickable: Boolean = true, editable: Boolean = false, scrollable: Boolean = false,
                   checkable: Boolean = false, checked: Boolean = false, role: String = "LinearLayout") =
        UiElement(id, role, label, Box(0, id * 100, 100, id * 100 + 90), clickable = clickable,
            editable = editable, scrollable = scrollable, checkable = checkable, checked = checked)

    private val recovery = " — re-observe the new screen and try a DIFFERENT approach; do not repeat the same call."

    @Test fun older_steps_are_compacted_the_newest_kept_whole_and_the_users_words_never_cut() {
        val h = ArrayList<String>()
        h += "ask_user(\"Which app?\") → the user answered: \"YouTube Music\""
        for (i in 1..10) h += "scroll({\"direction\":\"down\"}) → success [node]; verified=false (expected an element containing " +
            "'About phone'; none found on com.android.settings (screen changed=true) (after 3 re-observations)); Scrolled down.$recovery"
        h += "tap({\"label\":\"About phone\"}) → success [node]; verified=true (found 'Software information'); Tapped 'About phone'."
        val out = BrainPrompts.historyForPrompt(h, full = 2, window = 6)
        assertEquals("(earlier: ask_user: ok, scroll: unverified ×5)", out.first())
        assertTrue("the user's answer survives the window", out.any { it.contains("the user answered: \"YouTube Music\"") })
        assertEquals(h.last(), out.last())
        assertEquals("the newest two are verbatim", h[h.size - 2], out[out.size - 2])
        val older = out[out.size - 3]
        assertFalse("recovery advice is cut from older steps", older.contains("DIFFERENT approach"))
        assertFalse(older.contains("re-observation"))
        assertTrue(older.length <= 171)
        val chars = out.sumOf { it.length }
        assertTrue("compacted record ($chars chars) is smaller than the raw last-6 (${h.takeLast(6).sumOf { it.length }})",
            chars < h.takeLast(6).sumOf { it.length })
    }

    @Test fun a_read_keeps_more_of_what_it_read_when_compacted() {
        val msg = "Kofi: " + "the meeting moved to Friday at the usual place, bring the documents. ".repeat(6)
        val line = "read_sms({\"from\":\"Kofi\"}) → success [provider]; verified=true (observation); $msg"
        assertTrue(BrainPrompts.compact(line).length in 300..361)
        assertTrue(BrainPrompts.compact("tap({\"element_id\":3}) → success [node]; verified=true (x); " + "y".repeat(400)).length <= 171)
        assertEquals("scroll: partial", BrainPrompts.stepToken("scroll({}) → success [node]; verified=partial (x; but it moved on)"))
        assertEquals("tap: refused", BrainPrompts.stepToken("tap({}) → refused (stale); Screen changed"))
        assertNull(BrainPrompts.stepToken("You are going in CIRCLES — stop repeating it."))
    }

    @Test fun the_prompt_puts_what_is_fixed_for_the_task_first_and_the_screen_last() {
        val ctx = BrainContext("open Settings and tell me which Android version this phone has",
            JSONObject().put("app", "com.android.settings"),
            listOf("open_app({\"app\":\"Settings\"}) → success [intent]; verified=true (ok); Opened Settings"),
            JSONArray(), JSONArray(), conversation = listOf("User: " + "a".repeat(300), "Tɛkyerɛma: ok", "User: hi"),
            progress = "app=settings")
        val t = BrainPrompts.userText(ctx)
        val goal = t.indexOf("USER GOAL"); val acts = t.indexOf("ACTIONS SO FAR"); val prog = t.indexOf("PROGRESS SO FAR")
        val scr = t.indexOf("CURRENT SCREEN")
        assertTrue(t.indexOf("CONVERSATION SO FAR") in 0 until goal)
        assertTrue(goal < acts && acts < prog && prog < scr)
        assertFalse("an older conversation turn is cut", t.contains("a".repeat(200)))
        assertEquals(listOf("a", "b"), BrainPrompts.conversationForPrompt(listOf("a", "b")))
    }

    @Test fun system_prompt_teaches_claims_for_this_step_and_partial() {
        assertTrue(BrainPrompts.SYSTEM.contains("verified=partial"))
        assertTrue(BrainPrompts.SYSTEM.contains("not the end goal"))
    }

    @Test fun the_listing_names_what_a_control_does_once() {
        val s = UiSnapshot("com.whatsapp", "Chats", listOf(
            el(0, "Search", role = "ImageButton"),
            el(1, "Message", editable = true, clickable = false),
            el(2, "Chats", clickable = false, scrollable = true),
            el(3, "Wi-Fi", checkable = true, checked = true)), 0L, PerceptionRoute("tree", "tree exposed 4 actionable elements"))
        val j = s.toCompactJson()
        val arr = j.getJSONArray("elements")
        for (i in 0 until arr.length()) {
            val flags = arr.getJSONObject(i).optJSONArray("flags")?.toString().orEmpty()
            for (f in listOf("clickable", "editable", "scrollable", "long_pressable", "adjustable")) assertFalse("$i $flags", flags.contains(f))
        }
        assertEquals("tap", arr.getJSONObject(0).getJSONArray("actions").getString(0))
        assertEquals("type", arr.getJSONObject(1).getJSONArray("actions").getString(0))
        assertEquals("scroll", arr.getJSONObject(2).getJSONArray("actions").getString(0))
        assertTrue(arr.getJSONObject(3).getJSONArray("flags").toString().contains("checked"))
        assertFalse("routine tree reason dropped", j.getJSONObject("perception").has("reason"))
        assertEquals("tree", j.getJSONObject("perception").getString("source"))
        val failedVision = s.copy(perception = PerceptionRoute("tree", "vision requested by the model; vision fallback failed: rate limited"))
        assertTrue(failedVision.toCompactJson().getJSONObject("perception").getString("reason").contains("rate limited"))

        // The on-device screen summary still finds the controls from `actions`.
        val brain = LocalBrain()
        fun decide() = runBlocking { brain.decide(BrainContext("what's on my screen", j.put("appName", "WhatsApp"), emptyList(), JSONArray(), JSONArray())) }
        val first = decide()
        val d = if (first is AgentDecision.CallTool) decide() else first
        val text = (d as AgentDecision.Done).summary
        assertTrue(text, text.contains("You can use:") && text.contains("Search") && text.contains("Message"))
    }
}
