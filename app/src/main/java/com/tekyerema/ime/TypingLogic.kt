package com.tekyerema.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * G-05 — the keyboard's typing rules, pure and unit-tested: shift/caps, the word at the cursor, the "3"→ɛ / "c"→ɔ
 * texting habit, and what the enter key does. The service only feeds these what the editor reports.
 */

/**
 * Shift: tap = next letter capital; a quick second tap or a long-press = caps lock; any tap from caps lock = off.
 * A TalkBack user cannot double-tap shift quickly (each activation is itself a double-tap), so long-press — TalkBack's
 * double-tap-and-hold — is the caps-lock route that works for everyone.
 */
class ShiftMachine(private val doubleTapMs: Long = 400) {
    var state: ShiftState = ShiftState.OFF
        private set
    private var lastTapAt = Long.MIN_VALUE / 2

    fun tap(nowMs: Long): ShiftState {
        state = when (state) {
            ShiftState.OFF -> ShiftState.ONCE
            ShiftState.ONCE -> if (nowMs - lastTapAt <= doubleTapMs) ShiftState.LOCKED else ShiftState.OFF
            ShiftState.LOCKED -> ShiftState.OFF
        }
        lastTapAt = nowMs
        return state
    }

    fun lock(): ShiftState { state = ShiftState.LOCKED; return state }

    /** A letter was typed: a one-shot shift is used up; caps lock stays. */
    fun afterCharacter() { if (state == ShiftState.ONCE) state = ShiftState.OFF }

    /** The editor says the next letter starts a sentence (or not). Caps lock is the user's choice and is kept. */
    fun autoCapitalize(capital: Boolean) { if (state != ShiftState.LOCKED) state = if (capital) ShiftState.ONCE else ShiftState.OFF }

    fun reset() { state = ShiftState.OFF; lastTapAt = Long.MIN_VALUE / 2 }
}

/** The word being typed: the run of letters, digits and inner apostrophes that ends at the cursor. */
object WordAtCursor {
    data class Word(val text: String, val before: Char?)

    fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '’'

    fun of(textBeforeCursor: CharSequence?): Word {
        val s = textBeforeCursor ?: return Word("", null)
        var i = s.length
        while (i > 0 && isWordChar(s[i - 1])) i--
        // A leading apostrophe is a quote mark, not part of the word.
        while (i < s.length && (s[i] == '\'' || s[i] == '’')) i++
        return Word(s.substring(i), if (i > 0) s[i - 1] else null)
    }

    /** How much "delete word" (backspace long-press) removes: the spaces before the cursor, then the word — or the run
     *  of punctuation — before them. Always at least one character. */
    fun deleteWordLength(textBeforeCursor: CharSequence?): Int {
        val s = textBeforeCursor ?: return 1
        var i = s.length
        while (i > 0 && s[i - 1].isWhitespace()) i--
        if (i > 0 && isWordChar(s[i - 1])) { while (i > 0 && isWordChar(s[i - 1])) i-- }
        else while (i > 0 && !s[i - 1].isWhitespace() && !isWordChar(s[i - 1])) i--
        return (s.length - i).coerceAtLeast(1)
    }
}

/**
 * The texting habit: without an Akan keyboard people type "3" for ɛ and "c" for ɔ ("mey3", "wc"). In Twi mode, when
 * the user finishes a word, those stand-ins become the real letters — only where that cannot damage numbers or
 * English words:
 *  - "3" converts only as a single digit touching a letter ("y3", "3na"), never inside a number ("233", "30"), never in
 *    a number-with-unit or ordinal ("3pm", "3rd", "3GB"), never alone (a lone 3 is a number).
 *  - "c" converts where English cannot use it: not before a vowel or h/l/r/y ("cedi", "check", "class"), not after a
 *    vowel ("back", "music"), not in ALL-CAPS acronyms ("ECG", "PC"), not in a few known words ("etc", "disc"). A word
 *    that already shows Twi (a "3", ɛ or ɔ) converts every c ("cy3" → "ɔyɛ").
 * The ")" for ɔ habit is deliberately NOT converted: ")" is real punctuation far too often.
 */
object TextingHabit {
    private val UNIT_OR_ORDINAL = Regex("^\\d+(st|nd|rd|th|am|pm|g|gb|mb|kb|kg|km|cm|mm|ml|d|k|x|s|hrs?|mins?)$", RegexOption.IGNORE_CASE)
    private val KEEP = setOf("mp3", "etc", "disc", "zinc", "sync", "arc", "abc")
    private const val VOWELS = "aeiou"

    fun convert(word: String): String {
        if (word.isEmpty() || word.lowercase() in KEEP || UNIT_OR_ORDINAL.matches(word)) return word
        if (!word.any { it.isLetter() }) return word
        val letters = word.filter { it.isLetter() }
        val allCaps = letters.length >= 2 && letters.all { it.isUpperCase() }
        val sb = StringBuilder(word.length)
        for (i in word.indices) {
            val c = word[i]
            if (c == '3' && isLoneDigit(word, i) && touchesLetter(word, i)) sb.append(if (allCaps) 'Ɛ' else 'ɛ') else sb.append(c)
        }
        val step1 = sb.toString()
        val twiEvidence = step1.any { it == 'ɛ' || it == 'Ɛ' || it == 'ɔ' || it == 'Ɔ' }
        if (allCaps && !twiEvidence) return step1
        val out = StringBuilder(step1.length)
        for (i in step1.indices) {
            val c = step1[i]
            if ((c == 'c' || c == 'C') && (twiEvidence || cIsOpenO(step1, i))) out.append(if (c == 'C') 'Ɔ' else 'ɔ') else out.append(c)
        }
        return out.toString()
    }

    private fun isLoneDigit(w: String, i: Int) =
        (i == 0 || !w[i - 1].isDigit()) && (i == w.length - 1 || !w[i + 1].isDigit())

    private fun touchesLetter(w: String, i: Int) =
        (i > 0 && (w[i - 1].isLetter() || w[i - 1] == '3')) || (i < w.length - 1 && (w[i + 1].isLetter() || w[i + 1] == '3'))

    private fun cIsOpenO(w: String, i: Int): Boolean {
        val prev = if (i > 0) w[i - 1].lowercaseChar() else null
        val next = if (i < w.length - 1) w[i + 1].lowercaseChar() else null
        if (next != null && next in "aeiouhlry'’") return false
        if (prev != null && prev in VOWELS) return false
        return true
    }
}

/**
 * When a word is finished (space, enter or sentence punctuation) the habit conversion runs once; a backspace straight
 * after it puts the original back — the same "undo the autocorrect" gesture Gboard uses, so nothing is ever forced —
 * and the restored word is not converted again when the user finishes it the second time.
 */
class HabitSession {
    data class Replacement(val deleteBefore: Int, val insert: String)

    private var last: Triple<String, String, String>? = null   // original, converted, separator
    private var suppressed: String? = null

    /** The user finishes [word] with [separator]; [before] is the character in front of the word. Returns the edit
     *  to make INSTEAD of just typing the separator, or null to type it as usual. */
    fun onWordEnd(word: String, before: Char?, separator: String): Replacement? {
        val skip = word.isEmpty() || word == suppressed || (before != null && (before in ".@/:_-#" || before.isDigit()))
        suppressed = null
        if (skip) { last = null; return null }
        val converted = TextingHabit.convert(word)
        if (converted == word) { last = null; return null }
        last = Triple(word, converted, separator)
        return Replacement(word.length, converted + separator)
    }

    /** Backspace. If the text still ends with the conversion just made, undo it (and the separator) in one step. */
    fun onBackspace(textBeforeCursor: CharSequence?): Replacement? {
        val (original, converted, separator) = last ?: return null
        last = null
        val tail = converted + separator
        if (textBeforeCursor == null || !textBeforeCursor.endsWith(tail)) return null
        suppressed = original
        return Replacement(tail.length, original)
    }

    /** Any other key or a cursor move: the undo window closes. */
    fun onOtherInput() { last = null }

    fun reset() { last = null; suppressed = null }
}

/** What the enter key shows, says and does for the current field (EditorInfo.imeOptions / inputType). */
object EnterKeys {
    fun of(imeOptions: Int, inputType: Int): EnterKey {
        val multiLine = (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
            (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        val noAction = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (noAction || action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED) {
            return if (multiLine) EnterKey("⏎", "New line", EnterBehaviour.NEW_LINE) else EnterKey("⏎", "Enter", EnterBehaviour.KEY_EVENT)
        }
        val (label, spoken) = when (action) {
            EditorInfo.IME_ACTION_GO -> "Go" to "Go"
            EditorInfo.IME_ACTION_SEARCH -> "Search" to "Search"
            EditorInfo.IME_ACTION_SEND -> "Send" to "Send"
            EditorInfo.IME_ACTION_NEXT -> "Next" to "Next field"
            EditorInfo.IME_ACTION_DONE -> "Done" to "Done"
            EditorInfo.IME_ACTION_PREVIOUS -> "Prev" to "Previous field"
            else -> "⏎" to "Enter"
        }
        return EnterKey(label, spoken, EnterBehaviour.EDITOR_ACTION, action)
    }
}
