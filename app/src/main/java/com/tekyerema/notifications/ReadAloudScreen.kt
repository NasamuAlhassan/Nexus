package com.tekyerema.notifications

/**
 * Private reading on the SCREEN (owner's order, 2 Nov 2026). [PrivateReading] keeps message words off the loudspeaker; this
 * keeps them off the screen. With Settings › Privacy › "Hide messages on screen" on, an answer that is a message being read
 * aloud shows who and when only, never the words — "Reading 3 messages aloud" over "Ama · WhatsApp · 10 minutes ago" — on the
 * answer card and on the panel over other apps. Someone beside a blind user (a trotro, a queue) sees the screen even when the
 * words go to earphones. The voice still reads them in full; TalkBack reads the cover, so nothing is said twice.
 *
 * How the screen knows: every message the phone reads out passes through [MessageReading.item] (the catch-up, "read my
 * messages", the inbox, "continue", "read them"), and the cloud's notification list through [note]. Each is remembered for
 * [FRESH_MS] as its words' first few tokens; an answer that contains them is a reading and gets the cover. Matching on the words
 * themselves, not on a flag with its own lifetime, means the cover lasts exactly as long as the words would be on screen — and
 * an answer without them (the who-only line of PrivateReading, "You have 3 new messages: from Ama…") is shown as usual.
 * Pure Kotlin → unit-tested (ReadAloudScreenTest).
 */
object ReadAloudScreen {
    const val FRESH_MS = 15 * 60_000L
    private const val MAX_SEEN = 40
    /** A message's identity on screen: its first words, enough to be unmistakable, few enough to survive a cut at 300 chars. */
    private const val KEY_TOKENS = 6
    /** Fewer words than this and the words alone could be anyone's ("Ok", "Call me") — the sender's name must be there too. */
    private const val SHORT = 3

    data class Line(val who: String?, val where: String?, val whenSpoken: String?) {
        /** "Ama · WhatsApp · 10 minutes ago" */
        val shown: String get() = listOfNotNull(who?.trim()?.takeIf { it.isNotEmpty() } ?: "Someone",
            where?.trim()?.takeIf { it.isNotEmpty() }, whenSpoken?.trim()?.takeIf { it.isNotEmpty() }).joinToString(" · ")
        /** "From Ama, on WhatsApp, 10 minutes ago." for TalkBack. */
        val said: String get() = StringBuilder("From ").append(who?.trim()?.takeIf { it.isNotEmpty() } ?: "someone").apply {
            where?.trim()?.takeIf { it.isNotEmpty() }?.let { append(", on ").append(it) }
            whenSpoken?.trim()?.takeIf { it.isNotEmpty() }?.let { append(", ").append(it) }
        }.append('.').toString()
    }

    data class Cover(val lines: List<Line>) {
        val title: String get() = if (lines.size == 1) "Reading a message aloud" else "Reading ${lines.size} messages aloud"
        /** After the voice has finished. */
        val doneTitle: String get() = if (lines.size == 1) "A message, read aloud" else "${lines.size} messages, read aloud"
        /** The whole cover as one plain text (the panel over other apps, history, anything that shows one string). */
        val text: String get() = title + "\n" + lines.joinToString("\n") { it.shown }
        val description: String get() = "$title. " + lines.joinToString(" ") { it.said } + " The words are hidden on screen."
    }

    private class Seen(val line: Line, val keys: List<List<String>>, val whoTokens: List<String>, val short: Boolean, val at: Long)

    private val seen = ArrayDeque<Seen>()

    /** A message the phone is about to read out: [texts] = its words as written, as the voice says them, with a balance
     *  hidden — every form the screen may show. */
    fun note(who: String?, where: String?, whenSpoken: String?, texts: List<String>, now: Long = System.currentTimeMillis()) {
        val keys = texts.map { tokens(it) }.filter { it.isNotEmpty() }.map { it.take(KEY_TOKENS) }.distinct()
        if (keys.isEmpty()) return
        val short = keys.all { it.size < SHORT }
        val line = Line(who, where, whenSpoken)
        synchronized(seen) {
            seen.removeAll { it.keys == keys && it.line.who == who }
            seen.addLast(Seen(line, keys, tokens(who.orEmpty()), short, now))
            while (seen.size > MAX_SEEN) seen.removeFirst()
        }
    }

    /** The cover for [shown] (an answer about to be drawn), or null when it quotes no message read in the last [FRESH_MS]. */
    fun coverFor(shown: String, now: Long = System.currentTimeMillis()): Cover? {
        if (shown.isBlank()) return null
        val t = tokens(shown)
        if (t.isEmpty()) return null
        val hits = synchronized(seen) {
            seen.removeAll { now - it.at >= FRESH_MS }
            seen.mapNotNull { s ->
                val at = s.keys.map { indexOf(t, it) }.filter { it >= 0 }.minOrNull() ?: return@mapNotNull null
                if (s.short && (s.whoTokens.isEmpty() || indexOf(t, s.whoTokens) < 0)) return@mapNotNull null
                at to s.line
            }
        }
        if (hits.isEmpty()) return null
        return Cover(hits.sortedBy { it.first }.map { it.second }.distinct())
    }

    fun clear() = synchronized(seen) { seen.clear() }

    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")
    internal fun tokens(s: String): List<String> = s.lowercase().split(SPLIT).filter { it.isNotEmpty() }

    private fun indexOf(hay: List<String>, needle: List<String>): Int {
        if (needle.isEmpty() || needle.size > hay.size) return -1
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
