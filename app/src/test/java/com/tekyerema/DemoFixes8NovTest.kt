package com.tekyerema

import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.Invariants
import com.tekyerema.agent.MessageFidelity
import com.tekyerema.agent.Rephrase
import com.tekyerema.agent.ReviseTurn
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The faults seen live on the A20e during the SAO Fund demo recording (8 Nov 2026), each with the line from the log, and the
 * behaviour wanted instead.
 */
class DemoFixes8NovTest {
    @After fun reset() = Rephrase.clear()

    private fun el(id: Int, label: String, role: String = "ViewGroup", structure: String? = null, actions: List<String> = emptyList()) =
        JSONObject().put("id", id).put("role", role).put("label", label).apply {
            structure?.let { put("structure", it) }
            if (actions.isNotEmpty()) put("actions", JSONArray(actions))
        }
    private fun screen(app: String, vararg els: JSONObject) = JSONObject().put("app", app).put("elements", JSONArray().apply { els.forEach { put(it) } })
    private fun tap(id: Int) = AgentDecision.CallTool("tap", JSONObject().put("element_id", id), null)
    private fun question(d: AgentDecision) = ((d as? AgentDecision.CallTool)?.takeIf { it.name == "ask_user" })?.args?.optString("question")

    // ---- 1. "I found “Go back”. Is that who you mean?" / "I found “Send, Button”…" after the message had been typed ----

    @Test fun `the app's own controls are never asked about as people`() {
        val goal = "Tell Richmond on Telegram that I'll be there at half past ten, and text Prince to please start without me"
        val history = listOf("open_app({\"app\":\"Telegram\"}) → success", "type({\"element_id\":12,\"text\":\"I'll be there at half past ten\"}) → success")
        val s = screen("org.telegram.messenger",
            el(1, "Go back", role = "android.widget.ImageButton"),
            el(2, "Send, Button", role = "android.widget.ImageView"),
            el(3, "Profile", role = "android.widget.TextView"),
            el(4, "Search Chats", role = "android.widget.EditText", actions = listOf("tap", "type")),
            el(5, "Chats", role = "android.widget.TextView", structure = "tab 1 of 3"))
        for (id in 1..5) assertNull("element $id must not be taken for a person", question(Invariants.apply(tap(id), goal, history, s)))
    }

    @Test fun `the words the agent itself typed are not a person`() {
        val goal = "Text Prince to please start without me"
        val history = listOf("type({\"element_id\":9,\"text\":\"Please start without me\"}) → success")
        val s = screen("com.samsung.android.messaging", el(1, "Please start without me", role = "android.widget.TextView"))
        assertNull(question(Invariants.apply(tap(1), goal, history, s)))
    }

    @Test fun `a chat row for someone the user did not name is still asked about`() {
        val goal = "Send a WhatsApp message to Prince saying hello"
        val s = screen("com.whatsapp", el(1, "Chats", role = "android.widget.TextView"), el(2, "Ama Owusu", structure = "row 3"), el(3, "Kofi Mensah, Hi!, 10:42"))
        assertEquals("I found “Ama Owusu”. Is that who you mean?", question(Invariants.apply(tap(2), goal, emptyList(), s)))
        assertEquals("I found “Kofi Mensah”. Is that who you mean?", question(Invariants.apply(tap(3), goal, emptyList(), s)))
    }

    // ---- 2. send_sms message ", and then remind me about the meeting at twenty-five past ten." ----

    @Test fun `a trailing instruction is never the dictated message`() {
        assertNull(MessageFidelity.dictated("Text Prince a one-line summary of that, and then remind me about the meeting at twenty-five past ten."))
        assertEquals("I'll be there at six", MessageFidelity.dictated("Text Prince that I'll be there at six, and then set an alarm for seven")!!.raw)
        assertEquals("please start without me", MessageFidelity.dictated("Text Prince: please start without me.")!!.raw)
        assertEquals("Richmond is asking when we are meeting", MessageFidelity.dictated("Text Prince: Richmond is asking when we are meeting.")!!.raw)
        assertEquals("I'm on my way", MessageFidelity.dictated("tell Kofi that I'm on my way")!!.raw)
    }

    @Test fun `two dictated messages go to their own recipients`() {
        val goal = "Tell Richmond on Telegram that I'll be there at half past ten, and text Prince to please start without me."
        assertEquals("please start without me", MessageFidelity.dictatedFor(goal, "Prince")!!.raw)
        assertEquals("I'll be there at half past ten", MessageFidelity.dictatedFor(goal, "Richmond Apenensu")!!.raw)
        assertNull(MessageFidelity.dictatedFor(goal, null))
        assertEquals("Please start without me.", MessageFidelity.enforced("send_sms", "I'll be there at half past ten.", goal, "Prince"))
        assertEquals("I'm on my way", MessageFidelity.dictatedFor("text Prince saying I'm on my way", "Prince")!!.raw)
    }

    // ---- 3. "Last time I did that, and it wasn't what you wanted" on a restated request with new words ----

    @Test fun `a restated request with its own new words is not a redo`() {
        val first = "Text Prince a one-line summary of that, and then remind me about the meeting at twenty-five past ten."
        Rephrase.observe(first, listOf("search_contacts({\"query\":\"Prince\"}) → success", "set_alarm({\"hour\":10,\"minute\":25}) → success"), null, now = 1_000L)
        val second = "No, that's not a summary. Text Prince: Richmond is asking when we are meeting."
        assertNull(Rephrase.noteFor(second, now = 20_000L))
        assertFalse(Rephrase.isRedo(second, now = 20_000L))
        assertFalse(Rephrase.repeats(second, "send_sms", JSONObject().put("contact", "Prince").put("message", "Richmond is asking when we are meeting.")))
    }

    @Test fun `saying the same thing again without new words is still a redo`() {
        Rephrase.observe("Call Richmond", listOf("make_call({\"contact\":\"Richmond's Dad\"}) → success"), null, now = 1_000L)
        assertTrue(Rephrase.isRedo("No, I said call Richmond", now = 20_000L))
    }

    // ---- 4. "Sorry, make it six." at the read-back of "I'll be there at five." re-planned the whole send ----

    @Test fun `one number in the pending message is changed in place`() {
        val pending = JSONObject().put("contact", "Prince").put("message", "I'll be there at five.")
        val r = ReviseTurn.patch("send_sms", pending, "Sorry, make it six.")!!
        assertEquals("I'll be there at six.", r.args.getString("message"))
        assertEquals("Prince", r.args.getString("contact"))
        assertEquals("the message", r.what)
        assertEquals("I'll be there at 7:30.", ReviseTurn.patch("send_sms", pending, "make it 7:30")!!.args.getString("message"))
        assertEquals("I'll be there at six.", ReviseTurn.patch("send_sms", pending, "no, six, not five")!!.args.getString("message"))
        assertEquals("I'll be there at six.", ReviseTurn.patch("send_sms", pending, "change five to six")!!.args.getString("message"))
    }

    @Test fun `an ambiguous number change goes to the planner`() {
        val two = JSONObject().put("contact", "Prince").put("message", "Two of us at five.")
        assertNull(ReviseTurn.patch("send_sms", two, "make it six"))
        val none = JSONObject().put("contact", "Prince").put("message", "See you soon.")
        assertNull(ReviseTurn.patch("send_sms", none, "make it six"))
    }

    // ---- 5. "Remember that the meeting with Richmond is…" opened the Calendar; "Richmond is only on Telegram" was never kept ----

    @Test fun `facts said in the user's own words are kept as notes`() {
        val n = com.tekyerema.memory.MemoryParse.classify("Remember that the meeting with Richmond is in N Block, room 3, at half past ten.")
        assertEquals("the meeting with Richmond is in N Block, room 3, at half past ten", (n as com.tekyerema.memory.MemCmd.Note).text)
        val kept = com.tekyerema.memory.MemoryParse.classify("No calendar. Just keep this in mind: my meeting with Richmond is in N Block, room 3, at half past ten.")
        assertTrue(kept.toString(), kept is com.tekyerema.memory.MemCmd.SetFact || kept is com.tekyerema.memory.MemCmd.Note)
        assertEquals("Richmond is only on Telegram", (com.tekyerema.memory.MemoryParse.classify("Richmond is only on Telegram.") as com.tekyerema.memory.MemCmd.Note).text)
        assertEquals("the gate code is on the fridge", (com.tekyerema.memory.MemoryParse.classify("Don't forget that the gate code is on the fridge") as com.tekyerema.memory.MemCmd.Note).text)
        // still the old paths
        assertTrue(com.tekyerema.memory.MemoryParse.classify("Remember that my lecture hall is N Block, room 3") is com.tekyerema.memory.MemCmd.SetFact)
        // a reminder is not a note
        assertNull(com.tekyerema.memory.MemoryParse.classify("Remember to call Ama at six"))
        assertEquals("the user said: Richmond is only on Telegram",
            com.tekyerema.memory.PlannerMemory.factSentence(com.tekyerema.memory.MemoryCore.noteKey("Richmond is only on Telegram"), "Richmond is only on Telegram"))
    }

    // ---- 6. the finale ended at the read-out; the text and the reminder were never attempted ----

    @Test fun `a reply after the first part of a multi-part request is refused with what is left`() {
        val goal = "Read me the last message Richmond sent on Telegram, then text Prince a one-line summary of it, and remind me about the meeting at twenty-five past ten."
        val note = com.tekyerema.agent.LegCheck.missing(goal, listOf("open_app({\"app\":\"Telegram\"}) → success"))!!
        assertTrue(note, note.contains("text Prince a one-line summary of it") && note.contains("remind me about the meeting"))
        assertNull(com.tekyerema.agent.LegCheck.missing(goal, listOf("open_app({\"app\":\"Telegram\"}) → success",
            "send_sms({\"contact\":\"Prince\",\"message\":\"Richmond asks when you are meeting\"}) → success", "set_alarm({\"hour\":10,\"minute\":25}) → success")))
        assertNull(com.tekyerema.agent.LegCheck.missing("What time is it?", emptyList()))
        assertNull(com.tekyerema.agent.LegCheck.missing("Read my last message and tell me who sent it", emptyList()))
    }

    // ---- 7. the user's own bubble was read as Richmond's ----

    @Test fun `chat bubbles are marked You or the other side`() {
        val box = com.tekyerema.accessibility.UiElement(1, "android.widget.EditText", "Message", com.tekyerema.accessibility.Box(0, 1400, 600, 1480), editable = true)
        val mine = com.tekyerema.accessibility.UiElement(2, "android.view.ViewGroup", "I'm in the lift, two minutes.\nSent at 08:33, Not seen", com.tekyerema.accessibility.Box(300, 900, 700, 980))
        val theirs = com.tekyerema.accessibility.UiElement(3, "android.view.ViewGroup", "Heyy… when are we meeting", com.tekyerema.accessibility.Box(20, 700, 480, 780))
        val header = com.tekyerema.accessibility.UiElement(4, "android.widget.TextView", "Richmond Apenensu", com.tekyerema.accessibility.Box(0, 0, 720, 100))
        val s = com.tekyerema.accessibility.UiSnapshot("org.telegram.messenger", "Richmond Apenensu", listOf(header, theirs, mine, box), 0L)
        assertTrue(com.tekyerema.accessibility.ChatBubbles.isChatScreen(s))
        assertEquals("You: I'm in the lift, two minutes.", com.tekyerema.accessibility.ChatBubbles.promptLabel(mine, s, "Richmond Apenensu"))
        assertEquals("Richmond Apenensu: Heyy… when are we meeting", com.tekyerema.accessibility.ChatBubbles.promptLabel(theirs, s, "Richmond Apenensu"))
        assertNull(com.tekyerema.accessibility.ChatBubbles.promptLabel(box, s, "Richmond Apenensu"))
        assertNull(com.tekyerema.accessibility.ChatBubbles.promptLabel(header, s, "Richmond Apenensu"))
        val json = s.toCompactJson().getJSONArray("elements")
        val labels = (0 until json.length()).map { json.getJSONObject(it).optString("label") }
        assertTrue(labels.toString(), labels.any { it.startsWith("You: I'm in the lift") } && labels.any { it.startsWith("Richmond Apenensu: Heyy") })
    }

    @Test fun `a run-together Telegram node is split into marked bubbles, newest last`() {
        val label = "Heyy... when are we meeting Received at 07:43; I'll be there at half past ten Sent at 08:06, Not seen; I'm in the lift, two minutes. Sent at 08:33, Not seen"
        val node = com.tekyerema.accessibility.UiElement(2, "android.view.ViewGroup", label, com.tekyerema.accessibility.Box(0, 300, 720, 1300))
        val box = com.tekyerema.accessibility.UiElement(1, "android.widget.EditText", "Message", com.tekyerema.accessibility.Box(0, 1400, 600, 1480), editable = true)
        val s = com.tekyerema.accessibility.UiSnapshot("org.telegram.messenger", "Richmond Apenensu", listOf(node, box), 0L)
        assertEquals("Richmond Apenensu: Heyy... when are we meeting; You: I'll be there at half past ten; You: I'm in the lift, two minutes.",
            com.tekyerema.accessibility.ChatBubbles.promptLabel(node, s, "Richmond Apenensu"))
        assertNull(com.tekyerema.agent.MoneyGround.unseenDetail("Richmond's last message was “Heyy… when are we meeting”.", label, emptyList(), "read it"))
    }

    @Test fun `a chat list with a search box is not a chat screen and You is never a person`() {
        val search = com.tekyerema.accessibility.UiElement(1, "android.widget.EditText", "Search Chats", com.tekyerema.accessibility.Box(0, 100, 720, 180), editable = true)
        val row = com.tekyerema.accessibility.UiElement(2, "android.view.ViewGroup", "Richmond Apenensu, Heyy... when are we meeting, 07:43", com.tekyerema.accessibility.Box(0, 300, 720, 420))
        val list = com.tekyerema.accessibility.UiSnapshot("org.telegram.messenger", "Telegram", listOf(search, row), 0L)
        assertFalse(com.tekyerema.accessibility.ChatBubbles.isChatScreen(list))
        assertNull(com.tekyerema.accessibility.ChatBubbles.promptLabel(row, list, null))
        // the person guard: "You: …" / "You" (the planner's own bubble prefix) is never asked about as a person
        val goal = "Read me the last message Richmond sent on Telegram, then text Prince a one-line summary of it"
        val s = screen("org.telegram.messenger", el(5, "You: I'm in the lift, two minutes.", role = "android.view.ViewGroup", structure = "row 4"), el(6, "You"))
        assertNull(question(Invariants.apply(tap(5), goal, emptyList(), s)))
        assertNull(question(Invariants.apply(tap(6), goal, emptyList(), s)))
        assertFalse(Invariants.looksLikeName("You"))
        assertFalse(Invariants.looksLikeName("Them"))
    }

    // ---- 8. "I couldn't see the result on the screen, so I won't guess." after a real send ----

    @Test fun `a bubble with the app's sent marker confirms the send`() {
        val sent = com.tekyerema.accessibility.UiElement(2, "android.view.ViewGroup", "I'll be there at six.\nSent at 08:19, Not seen", com.tekyerema.accessibility.Box(300, 900, 700, 980))
        val box = com.tekyerema.accessibility.UiElement(1, "android.widget.EditText", "Message", com.tekyerema.accessibility.Box(0, 1400, 600, 1480), editable = true)
        val s = com.tekyerema.accessibility.UiSnapshot("org.telegram.messenger", "Prince", listOf(sent, box), 0L)
        assertTrue(com.tekyerema.agent.SendTruth.onScreen(s, "I'll be there at six."))
        assertFalse(com.tekyerema.agent.SendTruth.onScreen(s, "I'll be there at five."))
    }

    // ---- 9. cancel_alarm: the switch without the time in its own label ----

    @Test fun `the switch in the alarm's row is found by geometry`() {
        val row = com.tekyerema.accessibility.UiElement(5, "android.widget.TextView", "10:25, Sun, 8 Nov", com.tekyerema.accessibility.Box(48, 900, 560, 1000))
        val sw = com.tekyerema.accessibility.UiElement(6, "android.widget.Switch", "", com.tekyerema.accessibility.Box(586, 880, 720, 1020), checkable = true, checked = true)
        val other = com.tekyerema.accessibility.UiElement(7, "android.widget.Switch", "", com.tekyerema.accessibility.Box(586, 600, 720, 760), checkable = true, checked = true)
        val s = com.tekyerema.accessibility.UiSnapshot("com.sec.android.app.clockpackage", null, listOf(row, sw, other), 0L)
        assertEquals(listOf(6), com.tekyerema.tools.CancelAlarmTool.switchesByRow(s, 10, 25).map { it.id })
    }
}
