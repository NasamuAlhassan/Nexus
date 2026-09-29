package com.tekyerema.agent

import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.agent.journal.EffectKeys
import com.tekyerema.agent.journal.EffectKind
import com.tekyerema.tools.ComposerSend
import com.tekyerema.tools.SendEvidence
import com.tekyerema.tools.ToolResult
import org.json.JSONObject

/**
 * Honest results (2026-09-29): the final spoken reply of a task that sent a message says what the SCREEN showed after the
 * send, not what the planner believes. The planner's Done summary paraphrases ("I've sent it to Prince") — and on a chat
 * whose bubble never appeared that paraphrase was a false "sent". So the engine keeps each send's evidence line
 * ([Tracker.note], after the step's verification) and, at Done, the evidence replaces the planner's sentences about sending
 * ([Tracker.summary] / [finalSummary]); anything else the summary says is kept.
 *
 * Where the evidence comes from: `send_sms` (SMS composer, WhatsApp, the radio) and `reply_to_notification` put their own
 * line in `data.evidence`; a GUI send — a tap on the chat's Send control, or type + submit into a chat — is checked here
 * against the screens before and after ([SendEvidence.check]). Pure → SendEvidenceTest / HonestResultsEngineTest.
 */
object SendReport {
    /** One send in this task: who, what, where, the spoken line, whether the screen showed it, and the bubble count before. */
    data class Sent(val who: String?, val text: String, val app: String?, val line: String, val shown: Boolean, val preCount: Int)

    /** The sends of one task (the engine's RunCtx holds one). Not thread-shared: the loop is sequential. */
    class Tracker {
        private val sends = ArrayList<Sent>()
        val all: List<Sent> get() = sends.toList()

        /** After an executed + verified step: remember its send evidence, if it was a send. */
        fun note(tool: String, args: JSONObject, result: ToolResult, spec: EffectKeys.Spec?, target: UiElement?,
                 before: UiSnapshot, after: UiSnapshot?) {
            val s = try { sent(tool, args, result, spec, target, before, after) } catch (_: Throwable) { null } ?: return
            sends.removeAll { it.who.equals(s.who, ignoreCase = true) && it.text == s.text }
            sends.add(s)
            while (sends.size > 3) sends.removeAt(0)
        }

        /** The Done [summary] with the sends' evidence in place of the planner's words about sending. [screen] = the screen
         *  the Done was decided on: a send that couldn't be confirmed is looked for again there (it may have shown up since). */
        fun summary(summary: String, screen: UiSnapshot?): String =
            if (sends.isEmpty()) summary else finalSummary(sends.map { refresh(it, screen).line }, summary)
    }

    /** Tools whose own result carries the send's evidence line (`data.evidence`). */
    private val SELF_REPORTING = setOf("send_sms", "reply_to_notification")

    /** The evidence of one executed step, or null when it wasn't a message send. */
    fun sent(tool: String, args: JSONObject, result: ToolResult, spec: EffectKeys.Spec?, target: UiElement?,
             before: UiSnapshot, after: UiSnapshot?): Sent? {
        if (tool in SELF_REPORTING) {
            val d = result.data ?: return null
            val line = d.optString("evidence").takeIf { it.isNotBlank() } ?: return null
            val who = listOf("contact", "sender", "number").firstNotNullOfOrNull { k -> args.optString(k).trim().takeIf { it.isNotEmpty() && it != "null" } }
            return Sent(who, args.optString("message"), d.optString("app").ifBlank { null }, line,
                d.optBoolean("shown", d.optBoolean("sent", false)), d.optInt("preCount", -1))
        }
        // A GUI send: only a tap on the chat's own Send control, or type + submit into a chat that HAS a Send control —
        // a "Reply" button or a search box is not a message leaving the phone.
        if (!result.success || spec == null || spec.kind != EffectKind.CHAT_MESSAGE || after == null) return null
        if (spec.payload.startsWith("screen:") || spec.payload.isBlank()) return null   // a photo / a file: nothing to quote
        val isSend = when (tool) {
            "tap", "node_action" -> target != null && ComposerSend.isSendControl(target)
            "type" -> ComposerSend.sendButton(before, null) != null || ComposerSend.sendButton(after, null) != null
            else -> false
        }
        if (!isSend) return null
        val seen = SendEvidence.check(before, after, spec.payload)
        // type + submit is reported only on POSITIVE evidence: Enter may not send at all (WhatsApp adds a new line), and a
        // box that keeps its words after Enter is a search box far more often than an unsent message.
        if (tool == "type" && seen !is SendEvidence.Seen.Shown) return null
        val app = before.appName ?: after.appName
        return Sent(spec.label, spec.payload, app, SendEvidence.line(seen, app, spec.label), SendEvidence.sentOnScreen(seen),
            SendEvidence.count(before, spec.payload))
    }

    /** A send that couldn't be confirmed, looked for again on [screen]: the same chat now showing a NEW bubble with the words
     *  (more than before Send) and an empty box upgrades its line. Anything less leaves the honest "couldn't confirm". */
    fun refresh(s: Sent, screen: UiSnapshot?): Sent {
        if (s.shown || s.preCount < 0 || screen == null) return s
        val title = EffectKeys.chatTitle(screen)
        if (s.who != null && (title == null || !sameChat(title, s.who))) return s
        val seen = SendEvidence.check(null, screen, s.text)
        if (seen !is SendEvidence.Seen.Shown || SendEvidence.count(screen, s.text) <= s.preCount || !SendEvidence.sentOnScreen(seen)) return s
        return s.copy(line = SendEvidence.line(seen, s.app ?: screen.appName, s.who), shown = true)
    }

    private fun sameChat(title: String, who: String): Boolean {
        val t = title.lowercase().trim(); val w = who.lowercase().trim()
        return t == w || t.contains(w) || w.contains(t)
    }

    /** What the planner says about sending — the sentences (or "…and …" clauses) the evidence replaces. */
    private val SEND_TALK = Regex("(?i)\\b(sent|send|sending|messag(?:e|ed|es|ing)|text(?:ed|ing)?|sms|repl(?:y|ied|ies)|whatsapp(?:ed)?|" +
        "deliver(?:ed|y)?|chat)\\b")
    private val SENTENCE = Regex("(?<=[.!])\\s+")
    private val CLAUSE = Regex("(?i)\\s*(?:,\\s*and|\\band|\\bthen|\\balso|,)\\s+(?=(?:I\\s+)?(?:set|opened|turned|called|played|saved|added|" +
        "created|changed|read|found|searched|installed|took|started|stopped|closed|checked|booked|paid|put|made|went)\\b)")
    private val STARTS_WITH_I = Regex("(?i)^(?:i|i've|i have|i'll)\\b")

    /**
     * The evidence [lines] first, then whatever else [summary] says that isn't about the send ("…and I set an alarm for 6"
     * stays as "I also set an alarm for 6."). A question asserts nothing and stays as it is; a summary that already carries
     * every line is kept.
     */
    fun finalSummary(lines: List<String>, summary: String): String {
        val ev = lines.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (ev.isEmpty()) return summary
        val s = summary.trim()
        if (s.endsWith("?")) return summary
        if (ev.all { s.contains(it) }) return summary
        val kept = ArrayList<String>()
        for (sentence in s.split(SENTENCE).map { it.trim() }.filter { it.isNotEmpty() }) {
            if (!SEND_TALK.containsMatchIn(sentence)) { kept.add(sentence); continue }
            // A mixed sentence: keep the clauses that aren't about the send ("…and set an alarm for 6.").
            val parts = sentence.split(CLAUSE).map { it.trim().trimEnd('.', '!') }.filter { it.isNotEmpty() }
            for (p in parts.drop(1)) if (!SEND_TALK.containsMatchIn(p)) {
                kept.add((if (STARTS_WITH_I.containsMatchIn(p)) p.replaceFirstChar { it.uppercaseChar() } else "I also $p") + ".")
            }
        }
        return (ev + kept.filter { k -> ev.none { it.contains(k) } }).joinToString(" ")
    }

    /**
     * The honesty tail the voice layer adds to an unverified completion ("… — but I could not confirm it."), unless the
     * words already say so — an evidence line like "…so I can't confirm it went" must not be followed by a second doubt.
     */
    fun unverified(text: String): String =
        if (ALREADY_DOUBTS.containsMatchIn(text)) text else "$text — but I could not confirm it."
    private val ALREADY_DOUBTS = Regex("(?i)\\b(?:can'?t|cannot|couldn'?t|could not) confirm|hasn'?t gone|\\bas not sent\\b")
}
