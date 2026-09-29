package com.tekyerema.tools

import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot

/**
 * Honest results (2026-09-29) — what the chat on screen shows after Send, so a send's result QUOTES its evidence instead of
 * claiming "sent". Why: blind users over-trust an assistant that says it succeeded (they rated one 3.76/5 while it was
 * wrong 22% of the time), and shipping phone agents claim sends they never checked (docs/agent/research/
 * assistive_tech_blind.md, shipping_phone_agents.md). For WhatsApp and most chat apps the chat itself is the only witness.
 *
 *  - a NEW bubble holding the words (more of them than before Send) → [Seen.Shown], with a readable "Sent" / "Delivered" /
 *    "Read" / "Pending" / "Not sent" status when the app exposes one (WhatsApp's tick icons carry those descriptions,
 *    Samsung and Google Messages print them under the bubble) — which statuses the A20e's apps expose is not verified,
 *    so a missing status is never read as a failure;
 *  - the words still in the message box → [Seen.StillInBox]: it did not go;
 *  - neither → [Seen.NotSeen]: we can't tell, and say so.
 * A long message is often cut short in its bubble ("… Read more"): a visible start of 12+ characters counts. Emoji are
 * compared as they are, and again without them (some apps drop them from the accessibility text). Pure → SendEvidenceTest.
 */
object SendEvidence {
    enum class Status { SENDING, SENT, DELIVERED, READ, FAILED }

    sealed class Seen {
        /** A new bubble with the words is in the chat: [quote] is what it shows, [status] the app's own word, if any. */
        data class Shown(val quote: String, val status: Status?, val truncated: Boolean) : Seen()
        /** The words are still sitting in the message box — not sent. */
        object StillInBox : Seen() { override fun toString() = "StillInBox" }
        /** No new bubble with the words, and the box doesn't hold them either — can't tell from here. */
        object NotSeen : Seen() { override fun toString() = "NotSeen" }
    }

    /** What the screen [after] Send says about [text], compared with the screen [before] it (null = not known). */
    fun check(before: UiSnapshot?, after: UiSnapshot?, text: String): Seen {
        val want = norm(text)
        if (want.isEmpty() || after == null) return Seen.NotSeen
        if (after.elements.any { it.editable && !it.password && inBox(it, want) }) return Seen.StillInBox
        val hits = bubbles(after, text)
        val prior = if (before == null) 0 else bubbles(before, text).size
        if (hits.size <= prior) return Seen.NotSeen
        val newest = hits.maxByOrNull { it.first.bounds.bottom }!!
        return Seen.Shown(newest.second.quote, statusFor(after, newest.first, want), newest.second.truncated)
    }

    /** How many message bubbles on [screen] hold [text] (the box itself never counts). */
    fun count(screen: UiSnapshot?, text: String): Int = if (screen == null) 0 else bubbles(screen, text).size

    /** Shown and not marked as failed: the words left the box and are in the chat. */
    fun sentOnScreen(s: Seen): Boolean = s is Seen.Shown && s.status != Status.FAILED

    /**
     * The spoken result: "WhatsApp shows your message to Prince — 'I'm on my way' — as delivered." / "I pressed Send, but
     * I couldn't see the message in the chat, so I can't confirm it went. Check before sending again." Never "sent" without
     * the screen saying so. Plain, short, no emoji.
     */
    fun line(s: Seen, app: String?, who: String?): String {
        val to = who?.trim()?.takeIf { it.isNotEmpty() }?.let { " to $it" } ?: ""
        val appName = app?.trim()?.takeIf { it.isNotEmpty() }
        val shows = appName?.let { "$it shows" } ?: "The chat shows"
        return when (s) {
            is Seen.Shown -> {
                val q = spokenQuote(s.quote, s.truncated)
                val msg = if (q.isEmpty()) "your message$to" else "your message$to — '$q' —"
                when (s.status) {
                    Status.SENT -> "$shows $msg as sent."
                    Status.DELIVERED -> "$shows $msg as delivered."
                    Status.READ -> "$shows $msg as read."
                    Status.SENDING -> "$shows $msg in the chat, still sending."
                    Status.FAILED -> "$shows $msg as not sent. Check ${appName ?: "the chat"} before sending it again."
                    null -> "$shows $msg in the chat."
                }
            }
            Seen.StillInBox -> "I pressed Send, but your message$to is still in the box, so it hasn't gone. Check before sending again."
            Seen.NotSeen -> "I pressed Send, but I couldn't see the message in the chat, so I can't confirm it went. Check before sending again."
        }
    }

    /** A text the radio accepted (SmsManager RESULT_OK on every part) — the carrier's word, not the screen's. */
    fun radioLine(who: String, text: String): String {
        val q = spokenQuote(text, false)
        return if (q.isEmpty()) "The phone network accepted your text to $who." else "The phone network accepted your text to $who — '$q'."
    }

    /** reply_to_notification: what the app did with its notification after the direct reply. */
    enum class ReplySeen { SHOWN_IN_NOTIFICATION, NOTIFICATION_UPDATED, NOTIFICATION_CLEARED, NOTHING }

    fun replyVerdict(stillThere: Boolean, postedLater: Boolean, showsText: Boolean): ReplySeen = when {
        !stillThere -> ReplySeen.NOTIFICATION_CLEARED
        showsText -> ReplySeen.SHOWN_IN_NOTIFICATION
        postedLater -> ReplySeen.NOTIFICATION_UPDATED
        else -> ReplySeen.NOTHING
    }

    /** Only the app's own reaction is evidence; a cleared notification is reported as just that, never as "sent". */
    fun replyLine(r: ReplySeen, app: String?, who: String?, text: String): String {
        val a = app?.trim()?.takeIf { it.isNotEmpty() }
        val to = who?.trim()?.takeIf { it.isNotEmpty() }?.let { " to $it" } ?: ""
        val q = spokenQuote(text, false)
        val qEnd = if (q.isEmpty()) "" else " — '$q'"
        val qMid = if (q.isEmpty()) "" else " — '$q' —"
        return when (r) {
            ReplySeen.SHOWN_IN_NOTIFICATION -> "${a?.let { "$it's" } ?: "The app's"} notification now shows your reply$to$qEnd."
            ReplySeen.NOTIFICATION_UPDATED -> "${a ?: "The app"} updated its notification after your reply$to$qEnd."
            ReplySeen.NOTIFICATION_CLEARED -> "${a ?: "The app"} took your reply$to$qMid and cleared its notification."
            ReplySeen.NOTHING -> "I handed your reply$to to ${a ?: "the app"}, but it didn't show that it went, so I can't confirm it. " +
                "Check before sending again."
        }
    }

    // ---------------------------------------------------------------- matching

    private class Hit(val quote: String, val truncated: Boolean)

    private val ZERO_WIDTH = Regex("[\\u200B-\\u200D\\u2060\\uFE0E\\uFE0F]")
    private val SPACES = Regex("\\s+")

    /** Case- and space-insensitive, curly quotes straightened, zero-width joiners and emoji variation marks dropped. */
    internal fun norm(s: String?): String = s.orEmpty().replace(ZERO_WIDTH, "")
        .replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
        .replace(SPACES, " ").trim().lowercase()

    /** [s] without emoji and other pictographs — for an app whose accessibility text leaves them out, and for speech. */
    internal fun plain(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val type = Character.getType(cp)
            val pictograph = type == Character.OTHER_SYMBOL.toInt() || type == Character.SURROGATE.toInt() ||
                cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF
            if (!pictograph) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString().replace(SPACES, " ").trim()
    }

    /** A time stamp, a status word or "edited" riding at the end of a bubble's text ("On my way, 10:42 AM, Delivered"). */
    private val TAIL = Regex("(?:[\\s,·•|]+(?:\\d{1,2}:\\d{2}(?:\\s?[ap]\\.?\\s?m\\.?)?|sent|delivered|read|seen|pending|sending(?:…|\\.\\.\\.)?|" +
        "edited|failed|not sent|not delivered))+$", RegexOption.IGNORE_CASE)
    private val MORE = Regex("(?:\\s*(?:…|\\.\\.\\.)\\s*(?:read more|more)?|\\s+read more)$", RegexOption.IGNORE_CASE)

    private fun bubbles(screen: UiSnapshot, text: String): List<Pair<UiElement, Hit>> {
        val want = norm(text)
        val wantPlain = plain(want)
        return screen.elements.mapNotNull { e ->
            if (e.editable || e.label.isNullOrBlank()) return@mapNotNull null
            matchBubble(e.label, text, want, wantPlain)?.let { e to it }
        }
    }

    private fun matchBubble(label: String, text: String, want: String, wantPlain: String): Hit? {
        val l = norm(label)
        val core = l.replace(TAIL, "").trim()
        fun same(a: String) = a == want || (wantPlain.length >= 2 && plain(a) == wantPlain)
        // The whole message (with or without a time / status tail).
        if (same(core) || same(l)) return Hit(text.trim(), false)
        // A longer label that carries the message (a row's description: "Ama, On my way, 10:42") — long messages only.
        if (want.length >= 6 && (l.contains(want) || (wantPlain.length >= 6 && plain(l).contains(wantPlain)))) return Hit(text.trim(), false)
        // A bubble cut short: its visible start ("I'm on my way to the mar… Read more") begins the message.
        if (MORE.containsMatchIn(l)) {
            val start = l.replace(MORE, "").trim()
            if (start.length >= 12 && (want.startsWith(start) || wantPlain.startsWith(plain(start))))
                return Hit(label.trim().replace(MORE, "").trim(), true)
        }
        return null
    }

    private fun inBox(e: UiElement, want: String): Boolean {
        val l = norm(e.label)
        if (l.isEmpty() || (e.hint != null && l == norm(e.hint))) return false
        val wantPlain = plain(want)
        return l == want || (want.length >= 6 && l.contains(want)) || (wantPlain.length >= 2 && plain(l) == wantPlain)
    }

    // ---------------------------------------------------------------- status

    private val STATUS_WORDS: List<Pair<Regex, Status>> = listOf(
        Regex("^(?:message )?(?:not sent|not delivered|failed(?: to send)?|send failed|couldn'?t send)\\b.*$") to Status.FAILED,
        Regex("^tap to (?:retry|try again)$") to Status.FAILED,
        Regex("^(?:sending(?:…|\\.\\.\\.)?|pending|waiting(?: to send)?)$") to Status.SENDING,
        Regex("^(?:read|seen|opened)$") to Status.READ,
        Regex("^delivered$") to Status.DELIVERED,
        Regex("^sent$") to Status.SENT
    )

    internal fun statusOf(word: String?): Status? {
        val w = norm(word).trimEnd('.', '!')
        if (w.isEmpty() || w.length > 40) return null
        return STATUS_WORDS.firstOrNull { it.first.matches(w) }?.second
    }

    /** The status of [bubble]: a word after the message in its own text ("…, Delivered"), else the nearest status label in
     *  or just under it (WhatsApp's tick icon, Messages' "Delivered" line). */
    private fun statusFor(screen: UiSnapshot, bubble: UiElement, want: String): Status? {
        val l = norm(bubble.label)
        val at = l.indexOf(want)
        if (at >= 0) {
            l.substring(at + want.length).split(Regex("[,·•|\\n]")).map { it.trim() }.filter { it.isNotEmpty() }
                .reversed().firstNotNullOfOrNull { statusOf(it) }?.let { return it }
        }
        val b = bubble.bounds
        val reach = b.bottom + maxOf(b.height, 60)
        return screen.elements.asSequence()
            .filter { it !== bubble && !it.editable && it.bounds.centerY in b.top..reach }
            .mapNotNull { e -> statusOf(e.label)?.let { e to it } }
            .minByOrNull { (e, _) -> kotlin.math.abs(e.bounds.centerY - b.bottom) }?.second
    }

    // ---------------------------------------------------------------- speech

    private const val QUOTE_MAX = 48

    /** What the user hears of the message: no emoji (never spoken), at most [QUOTE_MAX] characters, cut at a word. */
    internal fun spokenQuote(text: String, truncated: Boolean): String {
        val t = plain(text.replace(ZERO_WIDTH, "").replace(SPACES, " ").trim()).trim('\'', '"', ' ')
        if (t.isEmpty()) return ""
        if (t.length <= QUOTE_MAX) return if (truncated) "$t…" else t
        val cut = t.take(QUOTE_MAX)
        val at = cut.lastIndexOf(' ').takeIf { it > QUOTE_MAX / 2 } ?: cut.length
        return cut.substring(0, at).trimEnd(',', ';', ':', ' ') + "…"
    }
}
