package com.tekyerema.notifications

import com.tekyerema.voice.SpokenText

/**
 * Messages read the way a person reads them to you (owner, A20e 2026-09-30: "it's supposed to read one at a time… taking
 * pauses so that the user can actually follow"): newest first, one at a time — who, where and when, a breath
 * ([SpokenText.PAUSE]), the words as they'd be said ([SpokenText.forEar]), then a longer pause ([SpokenText.BREAK]) before
 * the next. [PER_TURN] per turn, the way voice assistants read lists (short, then offer more); the rest wait for
 * "continue" in [MessageCursor]. Pure except the cursor's state.
 */
object MessageReading {
    const val PER_TURN = 3

    data class Msg(val who: String?, val where: String?, val whenSpoken: String?, val text: String)

    /** "From Ama, on WhatsApp, 10 minutes ago." ⏸ "Are you coming?" */
    fun item(m: Msg): String {
        val head = StringBuilder("From ").append(m.who?.trim()?.takeIf { it.isNotEmpty() } ?: "someone")
        m.where?.trim()?.takeIf { it.isNotEmpty() }?.let { head.append(", on ").append(it) }
        m.whenSpoken?.trim()?.takeIf { it.isNotEmpty() }?.let { head.append(", ").append(it) }
        head.append('.')
        return head.toString() + SpokenText.PAUSE + SpokenText.forEar(m.text)
    }

    data class Page(val text: String, val rest: List<Msg>)

    /** [intro] ("You have 5 new messages."), up to [per] messages, and — when more are left — how to hear them. */
    fun page(intro: String?, msgs: List<Msg>, per: Int = PER_TURN): Page {
        val shown = msgs.take(per)
        val rest = msgs.drop(per)
        val parts = ArrayList<String>()
        intro?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
        shown.forEach { parts += item(it) }
        if (rest.isNotEmpty()) parts += more(rest.size)
        return Page(parts.joinToString(SpokenText.BREAK.toString()), rest)
    }

    /** Ends like the screen reader's parts, so the reply re-opens the microphone for "continue" (ReadingPace). */
    fun more(n: Int): String = if (n == 1) "There's one more — say continue." else "There are $n more — say continue."
}

/** The messages still to read after a [MessageReading.page] — "continue" / "next" / "more" reads the next page. */
object MessageCursor {
    private const val FRESH_MS = 5 * 60_000L
    @Volatile private var rest: List<MessageReading.Msg> = emptyList()
    @Volatile private var at = 0L

    val pending: Boolean get() = rest.isNotEmpty() && System.currentTimeMillis() - at < FRESH_MS

    fun set(more: List<MessageReading.Msg>, now: Long = System.currentTimeMillis()) { rest = more; at = now }

    /** The next page, or null when nothing is left (or it went stale). */
    @Synchronized
    fun next(now: Long = System.currentTimeMillis()): String? {
        if (rest.isEmpty() || now - at >= FRESH_MS) { clear(); return null }
        val p = MessageReading.page(null, rest)
        rest = p.rest; at = now
        return p.text
    }

    fun clear() { rest = emptyList(); at = 0L }
}
