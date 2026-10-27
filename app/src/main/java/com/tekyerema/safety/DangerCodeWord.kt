package com.tekyerema.safety

/**
 * The danger code word: a phrase the user chooses, which — heard — starts the emergency alert (DangerAlert). Pure → DangerCodeWordTest.
 *
 * Two rules keep it from going off by accident. The phrase must be distinctive (at least two words, at least eight letters, and not
 * one of the everyday cries for help the app already answers). And the match is on whole words in order, forgiving one slip of the
 * speech recogniser in a long word ("kente" heard as "kenty"), never a part of a longer word.
 */
object DangerCodeWord {
    const val MIN_WORDS = 2
    const val MIN_LETTERS = 8

    /** Everyday phrases that must never be the code word: they are said in jokes, films and ordinary talk. */
    private val COMMON = setOf("help me", "help help", "call police", "call the police", "i need help", "emergency", "call for help",
        "hey tekyerema", "hello there", "good morning", "thank you", "open the door", "what time")

    fun words(text: String): List<String> =
        text.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(' ').filter { it.isNotEmpty() }

    /** Null when [phrase] is acceptable as a code word, else a spoken reason. */
    fun problem(phrase: String): String? {
        val w = words(phrase)
        return when {
            w.size < MIN_WORDS -> "Choose a phrase of at least two words, so it is not said by accident."
            w.sumOf { it.length } < MIN_LETTERS -> "That phrase is too short. Choose a longer one."
            w.joinToString(" ") in COMMON -> "That is too common a phrase. Choose something only you would say."
            else -> null
        }
    }

    /** True when [heard] contains the whole [codeWord] in order. A blank code word never matches. */
    fun matches(heard: String, codeWord: String): Boolean {
        val code = words(codeWord)
        if (code.size < MIN_WORDS) return false
        val h = words(heard)
        if (h.size < code.size) return false
        for (start in 0..h.size - code.size) {
            if (code.indices.all { sameWord(h[start + it], code[it]) }) return true
        }
        return false
    }

    /** Equal, or one letter apart when the word is long enough (5+) for a slip to be a slip and not another word. */
    internal fun sameWord(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length < 5 || b.length < 5 || Math.abs(a.length - b.length) > 1) return false
        return editDistanceAtMostOne(a, b)
    }

    private fun editDistanceAtMostOne(a: String, b: String): Boolean {
        var i = 0; var j = 0; var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                else -> { i++; j++ }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }

    private val CANCEL_WORDS = listOf("cancel", "false alarm", "i am safe", "i'm safe", "im safe", "stop the alert", "stop alert", "never mind", "its okay", "it's okay")

    /** During the cancel window: the user says cancel (or a stand-down phrase), or speaks the cancel PIN. */
    fun isCancel(heard: String, pin: String): Boolean {
        val h = heard.lowercase()
        if (CANCEL_WORDS.any { h.contains(it) }) return true
        val digits = heard.filter { it.isDigit() }
        return pin.length >= 4 && digits.contains(pin)
    }
}
