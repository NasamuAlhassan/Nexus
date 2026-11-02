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

    /** A Twi or Kusaal conversation (owner, 19 Oct 2026): the machine translation is not strong enough for a long frame, so the
     *  line is kept to who and the words — no app, no time ("10 minutes ago"), no "in the last 12 hours" — and only those
     *  few words go through it. English keeps the full line (the English voice says times and names well). */
    fun plain(): Boolean = try {
        com.tekyerema.voice.VoiceEngine.speaksTwi() || com.tekyerema.voice.VoiceEngine.speaksKusaal()
    } catch (_: Throwable) { false }

    /** "From Ama, on WhatsApp, 10 minutes ago." ⏸ "Are you coming?" */
    fun item(m: Msg): String {
        val head = StringBuilder("From ").append(m.who?.trim()?.takeIf { it.isNotEmpty() } ?: "someone")
        if (!plain()) {
            m.where?.trim()?.takeIf { it.isNotEmpty() }?.let { head.append(", on ").append(it) }
            m.whenSpoken?.trim()?.takeIf { it.isNotEmpty() }?.let { head.append(", ").append(it) }
        }
        head.append('.')
        // A likely scam is said once, after who and before the words, never changing them (money/fraud/ScamWarning).
        val warning = com.tekyerema.money.fraud.ScamWarning.before(m.who, m.text)?.let { it + SpokenText.PAUSE } ?: ""
        val said = SpokenText.forEar(m.text)
        // The screen side of private reading (ReadAloudScreen): remember these words so an answer showing them gets the cover.
        try { ReadAloudScreen.note(m.who, if (plain()) null else m.where, if (plain()) null else m.whenSpoken,
            listOf(m.text, said, PrivateReading.maskBalances(m.text))) } catch (_: Throwable) {}
        return head.toString() + SpokenText.PAUSE + warning + said
    }

    data class Page(val text: String, val rest: List<Msg>)

    /** [intro] ("You have 5 new messages."), up to [per] messages, and — when more are left — how to hear them. */
    fun page(intro: String?, msgs: List<Msg>, per: Int = PER_TURN): Page {
        val shown = msgs.take(per)
        val rest = msgs.drop(per)
        val parts = ArrayList<String>()
        if (!plain()) intro?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
        shown.forEach { parts += item(it) }
        if (rest.isNotEmpty()) parts += more(rest.size)
        return Page(parts.joinToString(SpokenText.BREAK.toString()), rest)
    }

    /** Ends like the screen reader's parts, so the reply re-opens the microphone for "continue" (ReadingPace). */
    /** The tail shrinks once the user knows the ropes (voice/Coaching) — but "say continue" stays: it's what reopens the mic. */
    fun more(n: Int): String = com.tekyerema.voice.Coaching.tail("tail_more",
        if (n == 1) "There's one more — say continue." else "There are $n more — say continue.",
        if (n == 1) "One more — say continue." else "$n more — say continue.")
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
