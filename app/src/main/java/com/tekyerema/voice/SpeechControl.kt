package com.tekyerema.voice

/**
 * Speech controls on the last thing Nexus said (MASTER_STATUS #39, 2 Nov 2026): "again", "slower", "spell it". Older
 * and blind users ask for these constantly; a line that went by too fast is otherwise lost. VoiceController handles them
 * before the fast path, whole utterances only, and never while a read-back waits for yes/no (the confirmation loop has its
 * own "say that again", which repeats the read-back itself).
 *
 *  - [Kind.AGAIN]: "again", "one more time" — the words "say that again" / "repeat" already go to [RepeatIntent];
 *  - [Kind.SLOWER]: "slower", "say that slower", "again, slowly" — the voice goes one step slower (kept until "normal
 *    speed", the same setting as "speak slower") and the last line is said again;
 *  - [Kind.SPELL]: "spell it", "spell that name", "spell the number" — the last name or number group in the last line,
 *    letter by letter or digit by digit ([spellLast]); a phone number keeps the 3-3-4 grouping Verbalize reads it in.
 * Pure, unit-tested (SpeechControlTest in ScreenSummaryTest's file).
 */
object SpeechControl {
    enum class Kind { AGAIN, SLOWER, SPELL }

    private val AGAIN = Regex("^(?:(?:say it |say that )?again|one more time|once more)(?: please)?$")
    private val SLOWER = Regex("^(?:(?:say (?:it|that)|repeat (?:it|that)|read (?:it|that)|again),? )?(?:a (?:bit|little) )?(?:more )?slow(?:er|ly)(?: please)?$|" +
        "^(?:slower|more slowly)(?:,? please)?$")
    private val SPELL = Regex("^(?:(?:please|can you|could you) )?(?:spell|how do you spell) (?:it|that|this|the name|that name|his name|her name|" +
        "their name|the number|that number|the reference|that reference)(?: out)?(?: for me)?(?: please)?$")

    fun classify(text: String): Kind? {
        val g = text.lowercase().replace('’', '\'').replace(Regex("\\s+"), " ").trim().trimEnd('.', '!', '?', ',').trim()
        return when {
            AGAIN.matches(g) -> Kind.AGAIN
            SLOWER.matches(g) -> Kind.SLOWER
            SPELL.matches(g) -> Kind.SPELL
            else -> null
        }
    }

    private val DIGITS = Regex("\\+?\\d(?:[ -]?\\d){2,}")
    private val CAP_RUN = Regex("\\b\\p{Lu}[\\p{L}'-]*(?:\\s+\\p{Lu}[\\p{L}'-]*){0,2}")
    private val ONES = arrayOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
    /** Capitalised only because they start a sentence (or are us): never "the name". */
    private val STARTERS = setOf("i", "i'm", "i'll", "i've", "i'd", "you", "you're", "your", "you've", "the", "a", "an", "it", "it's", "this",
        "that", "there", "here", "send", "sending", "sent", "calling", "call", "from", "to", "please", "okay", "ok", "done", "now", "yes",
        "no", "careful", "which", "who", "what", "where", "when", "how", "let", "sorry", "your", "nexus", "secure", "say", "shall", "should",
        "do", "did", "can", "could", "would", "will", "is", "are", "was", "new", "one", "two", "and", "but", "so", "if", "then", "today")

    /** The last name or number group in [line], spelled; null when there is none. */
    fun spellLast(line: String?): String? {
        if (line.isNullOrBlank()) return null
        DIGITS.findAll(line).lastOrNull()?.let { return spellNumber(it.value) }
        val names = CAP_RUN.findAll(line).mapNotNull { m ->
            m.value.trim().split(Regex("\\s+")).dropWhile { it.lowercase() in STARTERS }.joinToString(" ").takeIf { it.length >= 2 }
        }.toList()
        val name = names.lastOrNull() ?: return null
        return "$name: " + name.split(Regex("\\s+")).joinToString(". ") { w ->
            w.filter { it.isLetter() }.map { it.uppercaseChar() }.joinToString(", ")
        } + "."
    }

    private fun spellNumber(raw: String): String {
        val d = raw.filter { it.isDigit() }
        val said = { s: String -> s.map { ONES[it - '0'] }.joinToString(" ") }
        // A Ghana phone number: 0 + 9 digits → 3-3-4 (as Verbalize reads it); anything else in threes.
        val groups = if (d.length == 10 && d.startsWith("0")) listOf(d.take(3), d.substring(3, 6), d.substring(6))
            else d.chunked(3)
        val lead = if (raw.startsWith("+")) "plus " else ""
        return (lead + groups.joinToString(", ") { said(it) }).replaceFirstChar { it.uppercaseChar() } + "."
    }
}
