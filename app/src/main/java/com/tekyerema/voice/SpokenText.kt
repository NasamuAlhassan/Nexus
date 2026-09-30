package com.tekyerema.voice

/**
 * Reading for the ear, not the eye (owner, A20e 2026-09-30: messages were read "straight through… not like a human being";
 * it should "read one at a time, take care of punctuation… taking pauses so the user can actually follow").
 *
 * Two parts, both pure:
 *  - **Pauses.** A reading marks where a listener needs a breath: [PAUSE] after "From Ama, on WhatsApp, at 9:40" before the
 *    words, and [BREAK] between one message and the next. The system voice turns them into real silences
 *    (AndroidTts → `playSilentUtterance`), which every engine honours — unlike SSML, which the Samsung engine on the A20e
 *    may read aloud. SSML's own scale is the model: a comma-length pause inside an item, a paragraph break between items
 *    (Amazon Alexa SSML reference: `weak`/`medium` = a comma, `x-strong` = a paragraph). Voices that can't pause get
 *    [plain] text (a space — every item already ends in punctuation). On screen the markers are line breaks.
 *  - **[forEar]**: a message as a person would read it out — it ends with punctuation (so the voice drops its pitch and
 *    stops), line breaks become sentence breaks, links are "a link", emoji aren't spelled out, "!!!" is one "!", a
 *    SHOUTED word isn't spelled letter by letter, and a few unambiguous text-speak words are said in full.
 */
object SpokenText {
    // Invisible, never whitespace. Not U+2028/U+2029: Android's regex (ICU) counts those as \s, so every "\\s+" tidy-up on
    // the way to the voice flattened them to spaces and no pause was ever played (A20e 2026-09-30; JVM tests passed).
    /** A short pause (about a comma and a breath): between "From Ama, on WhatsApp" and what Ama wrote. */
    const val PAUSE = '⁣'
    /** A long pause (a paragraph): between one message and the next. */
    const val BREAK = '⁤'

    data class Segment(val text: String, val pauseAfterMs: Long)

    /** [text] cut at its markers, each piece with the silence that follows it (none after the last). */
    fun segments(text: String, pauseMs: Long = 450, breakMs: Long = 900): List<Segment> {
        val out = ArrayList<Segment>()
        val cur = StringBuilder()
        var pending = 0L
        fun flush() {
            val t = cur.toString().trim()
            if (t.isNotEmpty()) out += Segment(t, 0)
            cur.clear()
        }
        for (ch in text) {
            if (ch == PAUSE || ch == BREAK) {
                flush()
                val ms = if (ch == BREAK) breakMs else pauseMs
                if (out.isNotEmpty()) out[out.lastIndex] = out.last().copy(pauseAfterMs = maxOf(out.last().pauseAfterMs, ms))
            } else cur.append(ch)
        }
        flush()
        if (out.isNotEmpty()) out[out.lastIndex] = out.last().copy(pauseAfterMs = 0)
        return out
    }

    /** For a voice that can't pause: markers become spaces (every marked piece already ends in punctuation). */
    fun plain(text: String): String = text.replace(PAUSE, ' ').replace(BREAK, ' ').replace(Regex(" {2,}"), " ").trim()

    fun hasMarks(text: String): Boolean = text.indexOf(PAUSE) >= 0 || text.indexOf(BREAK) >= 0

    // ---------------------------------------------------------------- a message, for the ear

    private val URL = Regex("(?i)\\b(?:https?://|www\\.)\\S+")
    // Emoji and their glue: pictographs, dingbats, flags, skin tones, variation selectors, zero-width joiners, keycaps.
    private val EMOJI = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{1F1E6}-\\x{1F1FF}\\x{FE0F}\\x{FE0E}\\x{200D}\\x{20E3}\\x{E0020}-\\x{E007F}]")
    private val NEWLINES = Regex("\\s*[\\r\\n]+\\s*")
    private val SHOUT = Regex("\\b[A-Z]{5,}\\b")
    private val REPEAT_BANG = Regex("[!?]*!+[!?]*")          // "!!!", "!?!" → "!"
    private val REPEAT_ASK = Regex("\\?{2,}")
    private val DOTS = Regex("\\.{4,}")
    /** Text-speak that is never anything else in a message; said in full so the voice doesn't spell it. */
    private val SHORTHAND = listOf(
        "pls" to "please", "plz" to "please", "pliz" to "please", "thx" to "thanks", "thnx" to "thanks", "tnx" to "thanks",
        "msg" to "message", "tmrw" to "tomorrow", "tmr" to "tomorrow", "2moro" to "tomorrow", "bcos" to "because",
        "bcoz" to "because", "bcz" to "because", "abt" to "about", "gd" to "good", "mrng" to "morning", "nyt" to "night",
        "u" to "you", "ur" to "your", "wat" to "what", "wht" to "what", "b4" to "before",
        "asap" to "as soon as possible", "btw" to "by the way", "idk" to "I don't know", "lol" to "ha ha"
    ).map { (k, v) -> Regex("(?i)(?<![\\p{L}\\p{N}'])$k(?![\\p{L}\\p{N}'])") to v }

    /**
     * A message body as it should be heard. Never changes numbers, names or amounts (NumberSpeech and the money read-backs
     * own those); only how the words are paced and what isn't a word.
     */
    fun forEar(message: String): String {
        var t = message.trim().trim('"', '“', '”', '\'')
        if (t.isEmpty()) return t
        val hadEmoji = EMOJI.containsMatchIn(t)
        t = URL.replace(t, "a link")
        t = EMOJI.replace(t, " ")
        t = t.replace("&", " and ")
        // A line break ends a thought: a sentence break unless the line already ended one.
        t = t.split(NEWLINES).map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString(" ") { l -> if (l.last() in ".!?…:;,") l else "$l." }
        // SHOUTING isn't spelled letter by letter: a mostly-capitals message is said as a sentence; elsewhere a long capital
        // word (5+ letters) is a word, while MTN, SMS, GHS stay acronyms.
        val letters = t.filter { it.isLetter() }
        t = if (letters.length >= 8 && letters.count { it.isUpperCase() } >= letters.length * 0.7)
            t.lowercase().replaceFirstChar { it.uppercaseChar() }
        else SHOUT.replace(t) { it.value.lowercase() }
        for ((re, full) in SHORTHAND) t = re.replace(t, full)
        t = REPEAT_ASK.replace(t, "?")
        t = REPEAT_BANG.replace(t, "!")
        t = DOTS.replace(t, "…")
        t = t.replace(Regex("\\s+"), " ").replace(Regex(" ([.,!?;:…])"), "$1").trim().trimStart('.', ',', ';', ':', ' ')
        if (t.none { it.isLetterOrDigit() }) return if (hadEmoji) "An emoji." else "(no words)."
        t = t.replaceFirstChar { it.uppercaseChar() }
        return if (t.last() in ".!?…") t else "$t."
    }
}
