package com.tekyerema

import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.agent.SendReport
import com.tekyerema.agent.journal.EffectKeys
import com.tekyerema.agent.journal.EffectKind
import com.tekyerema.tools.SendEvidence
import com.tekyerema.tools.SendEvidence.Seen
import com.tekyerema.tools.SendEvidence.Status
import com.tekyerema.tools.ToolResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Honest results (2026-09-29): a send's result quotes what the chat shows — the new bubble and its status — or says it
 * couldn't see it. Never "sent" without evidence.
 */
class SendEvidenceTest {
    private var id = 0
    private fun text(label: String?, top: Int, left: Int = 300, right: Int = 1000, height: Int = 80) =
        UiElement(id++, "android.widget.TextView", label, Box(left, top, right, top + height))
    private fun icon(desc: String, top: Int) = UiElement(id++, "android.widget.ImageView", desc, Box(940, top, 980, top + 30))
    private fun box(label: String?) = UiElement(id++, "android.widget.EditText", label ?: "Message", Box(100, 2100, 700, 2200),
        editable = true, clickable = true, hint = "Message")
    private fun send() = UiElement(id++, "android.widget.ImageButton", "Send", Box(900, 2100, 1080, 2200), clickable = true)
    private fun chat(composer: String?, vararg bubbles: UiElement, title: String = "Prince") = UiSnapshot("com.whatsapp", title,
        listOf(text(title, 60, 120, 600)) + bubbles.toList() + listOf(box(composer), send()), 1L, appName = "WhatsApp")

    // ---------------------------------------------------------------- what the screen shows

    @Test fun a_new_bubble_with_the_words_is_shown() {
        val before = chat("I'm on my way")
        val after = chat(null, text("I'm on my way", 1800))
        val seen = SendEvidence.check(before, after, "I'm on my way")
        assertEquals(Seen.Shown("I'm on my way", null, false), seen)
        assertTrue(SendEvidence.sentOnScreen(seen))
        assertEquals("WhatsApp shows your message to Prince — 'I'm on my way' — in the chat.", SendEvidence.line(seen, "WhatsApp", "Prince"))
    }

    @Test fun the_words_still_in_the_box_are_not_sent() {
        val before = chat("I'm on my way")
        val stuck = chat("I'm on my way")
        assertEquals(Seen.StillInBox, SendEvidence.check(before, stuck, "I'm on my way"))
        assertFalse(SendEvidence.sentOnScreen(Seen.StillInBox))
        assertEquals("I pressed Send, but your message to Prince is still in the box, so it hasn't gone. Check before sending again.",
            SendEvidence.line(Seen.StillInBox, "WhatsApp", "Prince"))
    }

    @Test fun no_bubble_and_an_empty_box_cannot_be_confirmed() {
        val seen = SendEvidence.check(chat("see you"), chat(null), "see you")
        assertEquals(Seen.NotSeen, seen)
        assertEquals("I pressed Send, but I couldn't see the message in the chat, so I can't confirm it went. Check before sending again.",
            SendEvidence.line(seen, "WhatsApp", "Prince"))
    }

    @Test fun an_identical_older_message_is_not_evidence_of_this_one() {
        val old = text("ok", 900)
        val before = chat("ok", old)
        assertEquals("the same words were already there — nothing new", Seen.NotSeen, SendEvidence.check(before, chat(null, text("ok", 900)), "ok"))
        assertTrue(SendEvidence.check(before, chat(null, text("ok", 900), text("ok", 1800)), "ok") is Seen.Shown)
        // Short words never match inside a longer label ("ok" in "Book a ride").
        assertEquals(Seen.NotSeen, SendEvidence.check(chat("ok"), chat(null, text("Book a ride", 1800)), "ok"))
    }

    @Test fun a_long_message_cut_short_in_its_bubble_still_counts() {
        val msg = "I'm on my way to the market, I will be there in about twenty minutes, wait for me"
        val after = chat(null, text("I'm on my way to the market, I will be there… Read more", 1700, height = 300))
        val seen = SendEvidence.check(chat(msg), after, msg)
        assertTrue(seen is Seen.Shown && seen.truncated)
        val line = SendEvidence.line(seen, "WhatsApp", "Ama")
        assertTrue(line, line.startsWith("WhatsApp shows your message to Ama — 'I'm on my way to the market, I will be there…'"))
        // A start too short to tell is not enough.
        assertEquals(Seen.NotSeen, SendEvidence.check(chat(msg), chat(null, text("I'm on… Read more", 1700)), msg))
    }

    @Test fun emoji_compare_as_written_and_without_them_and_are_never_spoken() {
        val msg = "On my way 🚗"
        assertTrue(SendEvidence.check(chat(msg), chat(null, text("On my way 🚗", 1800)), msg) is Seen.Shown)
        // An app whose accessibility text leaves the emoji out.
        val dropped = SendEvidence.check(chat(msg), chat(null, text("On my way", 1800)), msg)
        assertTrue(dropped is Seen.Shown)
        assertEquals("WhatsApp shows your message to Ama — 'On my way' — in the chat.", SendEvidence.line(dropped, "WhatsApp", "Ama"))
        // A variation selector (❤️ = ❤ + U+FE0F) doesn't break the match.
        assertTrue(SendEvidence.check(chat("I love it ❤️"), chat(null, text("I love it ❤", 1800)), "I love it ❤️") is Seen.Shown)
        // An emoji-only message is shown without a quote.
        val thumbs = SendEvidence.check(chat("👍"), chat(null, text("👍", 1800)), "👍")
        assertTrue(thumbs is Seen.Shown)
        assertEquals("WhatsApp shows your message to Ama in the chat.", SendEvidence.line(thumbs, "WhatsApp", "Ama"))
    }

    @Test fun a_readable_status_is_said_and_a_missing_one_is_not_a_failure() {
        val msg = "see you at 6"
        fun after(vararg extra: UiElement) = chat(null, text(msg, 1800), *extra)
        assertEquals(Status.DELIVERED, (SendEvidence.check(chat(msg), after(icon("Delivered", 1850)), msg) as Seen.Shown).status)
        assertEquals(Status.READ, (SendEvidence.check(chat(msg), after(icon("Read", 1850)), msg) as Seen.Shown).status)
        assertEquals(Status.SENDING, (SendEvidence.check(chat(msg), after(icon("Pending", 1850)), msg) as Seen.Shown).status)
        // Samsung / Google Messages: the status is a line under the bubble.
        assertEquals(Status.SENT, (SendEvidence.check(chat(msg), after(text("Sent", 1890, 800, 1000, 40)), msg) as Seen.Shown).status)
        // The status inside the bubble's own description.
        val inline = SendEvidence.check(chat(msg), chat(null, text("see you at 6, 10:42 AM, Delivered", 1800)), msg)
        assertEquals(Status.DELIVERED, (inline as Seen.Shown).status)
        // A message whose words contain a status word doesn't mark itself.
        assertNull((SendEvidence.check(chat("yes, sent"), chat(null, text("yes, sent", 1800)), "yes, sent") as Seen.Shown).status)

        val failed = SendEvidence.check(chat(msg), after(text("Not sent. Tap to try again", 1890, 600, 1000, 40)), msg)
        assertFalse("a bubble marked not sent is not a send", SendEvidence.sentOnScreen(failed))
        assertEquals("Messages shows your message to Kofi — 'see you at 6' — as not sent. Check Messages before sending it again.",
            SendEvidence.line(failed, "Messages", "Kofi"))
        val delivered = SendEvidence.check(chat(msg), after(icon("Delivered", 1850)), msg)
        assertEquals("WhatsApp shows your message to Prince — 'see you at 6' — as delivered.", SendEvidence.line(delivered, "WhatsApp", "Prince"))
        val pending = SendEvidence.check(chat(msg), after(icon("Pending", 1850)), msg)
        assertEquals("WhatsApp shows your message to Prince — 'see you at 6' — in the chat, still sending.", SendEvidence.line(pending, "WhatsApp", "Prince"))
    }

    @Test fun long_quotes_are_cut_at_a_word_and_lines_stay_short() {
        val msg = "Please remember to bring the documents for the land registration and the receipts from last week"
        val line = SendEvidence.line(SendEvidence.check(chat(msg), chat(null, text(msg, 1700, height = 200)), msg), "WhatsApp", "Kofi Mensah")
        assertTrue(line, line.contains("'Please remember to bring the documents for the…'"))
        assertTrue("fits the planner's history line (160)", line.length <= 160)
    }

    @Test fun the_radio_and_a_direct_reply_say_whose_word_it_is() {
        assertEquals("The phone network accepted your text to Ama — 'I'm late'.", SendEvidence.radioLine("Ama", "I'm late"))
        fun reply(r: SendEvidence.ReplySeen) = SendEvidence.replyLine(r, "WhatsApp", "Ama", "ok")
        assertEquals("WhatsApp's notification now shows your reply to Ama — 'ok'.",
            reply(SendEvidence.replyVerdict(stillThere = true, postedLater = true, showsText = true)))
        assertEquals("WhatsApp updated its notification after your reply to Ama — 'ok'.",
            reply(SendEvidence.replyVerdict(stillThere = true, postedLater = true, showsText = false)))
        assertEquals("WhatsApp took your reply to Ama — 'ok' — and cleared its notification.",
            reply(SendEvidence.replyVerdict(stillThere = false, postedLater = false, showsText = false)))
        val nothing = reply(SendEvidence.replyVerdict(stillThere = true, postedLater = false, showsText = false))
        assertEquals("I handed your reply to Ama to WhatsApp, but it didn't show that it went, so I can't confirm it. Check before sending again.", nothing)
        for (l in listOf(nothing, reply(SendEvidence.ReplySeen.NOTIFICATION_CLEARED))) assertFalse(l, Regex("(?i)\\bsent\\b").containsMatchIn(l))
    }

    // ---------------------------------------------------------------- the final reply (SendReport)

    @Test fun the_evidence_replaces_the_planners_words_about_sending_and_keeps_the_rest() {
        val ev = listOf("WhatsApp shows your message to Ama — 'hi' — in the chat.")
        assertEquals(ev[0], SendReport.finalSummary(ev, "I've sent your message to Ama."))
        assertEquals(ev[0] + " I also set an alarm for 6.", SendReport.finalSummary(ev, "I sent Ama hi and set an alarm for 6."))
        assertEquals(ev[0] + " The alarm is set for 6.", SendReport.finalSummary(ev, "Message sent. The alarm is set for 6."))
        assertEquals("a question asserts nothing", "Send it to Ama or Kofi?", SendReport.finalSummary(ev, "Send it to Ama or Kofi?"))
        assertEquals("already quoted", "Done. " + ev[0], SendReport.finalSummary(ev, "Done. " + ev[0]))
        assertEquals("no send, no change", "Opened Settings.", SendReport.finalSummary(emptyList(), "Opened Settings."))
        val two = listOf("WhatsApp shows your message to Ama in the chat.", "WhatsApp shows your message to Kofi in the chat.")
        assertEquals(two.joinToString(" "), SendReport.finalSummary(two, "I messaged both of them."))
    }

    @Test fun an_unconfirmed_send_is_not_doubted_twice() {
        val cant = SendEvidence.line(Seen.NotSeen, "WhatsApp", "Ama")
        assertEquals(cant, SendReport.unverified(cant))
        assertEquals("I changed the setting — but I could not confirm it.", SendReport.unverified("I changed the setting"))
        val stuck = SendEvidence.line(Seen.StillInBox, "WhatsApp", "Ama")
        assertEquals(stuck, SendReport.unverified(stuck))
    }

    @Test fun a_gui_send_is_only_a_tap_on_the_chats_own_send_control() {
        val before = chat("see you at 6", title = "Ama")
        val after = chat(null, text("see you at 6", 1800), title = "Ama")
        val spec = EffectKeys.Spec(EffectKind.CHAT_MESSAGE, "ama", "Ama", "see you at 6")
        val ok = ToolResult(true, "Tapped.")
        val sendBtn = before.elements.first { it.label == "Send" }
        val s = SendReport.sent("tap", JSONObject(), ok, spec, sendBtn, before, after)!!
        assertEquals("WhatsApp shows your message to Ama — 'see you at 6' — in the chat.", s.line)
        assertTrue(s.shown)
        // A "Reply" button, a failed tap, a photo (no words) or a type into a screen with no Send control: not a send.
        val reply = UiElement(99, "android.widget.Button", "Reply", Box(0, 0, 10, 10), clickable = true)
        assertNull(SendReport.sent("tap", JSONObject(), ok, spec, reply, before, after))
        assertNull(SendReport.sent("tap", JSONObject(), ToolResult(false, "no"), spec, sendBtn, before, after))
        assertNull(SendReport.sent("tap", JSONObject(), ok, spec.copy(payload = "screen:abc"), sendBtn, before, after))
        val search = UiSnapshot("com.google.android.youtube", "YouTube", listOf(box("gospel")), 1L, appName = "YouTube")
        assertNull(SendReport.sent("type", JSONObject(), ok, spec, null, search, search))
        // type + submit into a chat: reported only when the bubble shows (a box that keeps its words is usually a search box).
        assertEquals(s.line, SendReport.sent("type", JSONObject(), ok, spec, null, chat(null, title = "Ama"), after)!!.line)
        assertNull(SendReport.sent("type", JSONObject(), ok, spec, null, chat(null, title = "Ama"), chat("see you at 6", title = "Ama")))
        // send_sms speaks for itself (data.evidence); without it there is nothing to report.
        val sms = ToolResult(true, "x", data = JSONObject().put("evidence", "The phone network accepted your text to Ama.").put("sent", true))
        assertEquals("The phone network accepted your text to Ama.", SendReport.sent("send_sms", JSONObject().put("contact", "Ama"), sms, null, null, before, after)!!.line)
        assertNull(SendReport.sent("send_sms", JSONObject(), ToolResult(true, "Sent to Ama."), null, null, before, after))
    }

    @Test fun a_send_that_could_not_be_confirmed_is_looked_for_again_on_the_done_screen() {
        val t = SendReport.Tracker()
        val data = JSONObject().put("evidence", SendEvidence.line(Seen.NotSeen, "WhatsApp", "Ama")).put("app", "WhatsApp")
            .put("sent", false).put("shown", false).put("preCount", 0)
        t.note("send_sms", JSONObject().put("contact", "Ama").put("message", "see you at 6").put("via", "whatsapp"),
            ToolResult(false, "x", data = data), null, null, chat(null, title = "Ama"), null)
        // Still nothing on the screen the task ended on: the honest line stands.
        assertEquals(SendEvidence.line(Seen.NotSeen, "WhatsApp", "Ama"), t.summary("I've sent it.", chat(null, title = "Ama")))
        // Another chat showing the same words proves nothing about Ama's.
        assertEquals(SendEvidence.line(Seen.NotSeen, "WhatsApp", "Ama"), t.summary("I've sent it.", chat(null, text("see you at 6", 1800), title = "Kofi")))
        // Ama's chat now shows it: the line says so.
        assertEquals("WhatsApp shows your message to Ama — 'see you at 6' — in the chat.",
            t.summary("I've sent it.", chat(null, text("see you at 6", 1800), title = "Ama")))
    }
}
