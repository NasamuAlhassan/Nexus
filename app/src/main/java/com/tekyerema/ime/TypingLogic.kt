package com.tekyerema.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * G-05 — the keyboard's typing rules, pure and unit-tested: shift/caps, the word at the cursor and what the enter
 * key does. The service only feeds these what the editor reports.
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
