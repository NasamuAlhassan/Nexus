package com.tekyerema.ime

/**
 * G-05 — the Nexus keyboard's key model. Pure Kotlin (no Android types), so every layout, label and spoken name is
 * unit-tested; the view only draws what this file describes.
 *
 * Layout decisions:
 *  - Plain QWERTY (English only since 31 Oct 2026; the Twi mode and its language key are gone). ɛ and ɔ stay a
 *    long-press on E and O, because Ghanaian English text names Twi people and places.
 *  - Numbers/symbols put the cedi sign ₵ where other keyboards put $ (the local currency goes first).
 *  - Number, phone and PIN fields get a big 4×4 pad: fewer, larger targets for amounts and phone numbers.
 */
enum class KeyKind { CHAR, SHIFT, BACKSPACE, SPACE, ENTER, SYMBOLS, MORE_SYMBOLS, LETTERS, SPACER }

enum class Page { LETTERS, SYMBOLS, MORE_SYMBOLS, PAD }

/** Which pad a number-like field gets (see [FieldPolicy]). */
enum class PadKind { NUMBER, PHONE, PIN, DATETIME }

enum class ShiftState { OFF, ONCE, LOCKED }

/**
 * One key. [text] is what a CHAR key types; [label] what is drawn; [spoken] what TalkBack reads (its content
 * description); [variants] what a long-press offers; [width] in key units (a letter key is 1).
 */
data class Key(
    val kind: KeyKind,
    val text: String = "",
    val label: String = text,
    val spoken: String = KeyNames.spoken(text),
    val variants: List<String> = emptyList(),
    val width: Float = 1f
)

data class KeyboardLayout(val page: Page, val rows: List<List<Key>>) {
    /** Same number of rows, keys, kinds and widths ⇒ the view can relabel in place instead of rebuilding, which
     *  keeps TalkBack's focus on the key the user is touching (shift toggles must not throw it away). */
    fun shape(): List<List<Pair<KeyKind, Float>>> = rows.map { r -> r.map { it.kind to it.width } }
}

/** The enter key for the current field — see [EnterKeys]. */
data class EnterKey(val label: String, val spoken: String, val behaviour: EnterBehaviour, val actionId: Int = 0)

enum class EnterBehaviour { EDITOR_ACTION, NEW_LINE, KEY_EVENT }

object KeyboardLayouts {
    private val ROW1 = "qwertyuiop"
    private val ROW2 = "asdfghjkl"
    private val ROW3 = "zxcvbnm"

    /** Long-press offers, lower-case; [shifted] upper-cases them. Accents are offered for names and French. */
    private val VARIANTS: Map<String, List<String>> = mapOf(
        "q" to listOf("1"),
        "w" to listOf("2"),
        "e" to listOf("ɛ", "é", "è", "ê", "ë", "3"),
        "r" to listOf("4"),
        "t" to listOf("5"),
        "y" to listOf("6"),
        "o" to listOf("ɔ", "ó", "ò", "ô", "ö", "9"),
        "p" to listOf("0"),
        "n" to listOf("ŋ", "ñ"),
        "a" to listOf("á", "à", "â", "ä", "ã"),
        "i" to listOf("í", "ì", "î", "ï", "8"),
        "u" to listOf("ú", "ù", "û", "ü", "7"),
        "c" to listOf("ç"),
        "." to listOf(",", "?", "!", "'", "\"", ":", ";", "-"),
        "," to listOf(";", ":", "'", "\""),
        "₵" to listOf("GH₵", "GH¢", "¢", "$", "€", "£"),
        "-" to listOf("–", "—", "_"),
        "/" to listOf("\\"),
        "(" to listOf("[", "{", "<"),
        ")" to listOf("]", "}", ">"),
        "?" to listOf("¿"),
        "!" to listOf("¡"),
        "\"" to listOf("“", "”"),
        "'" to listOf("‘", "’"),
        "<" to listOf(">", "≤", "≥")
    )

    fun variantsOf(text: String, shifted: Boolean): List<String> {
        val v = VARIANTS[text.lowercase()] ?: return emptyList()
        return if (shifted) v.map { upper(it) } else v
    }

    /** Upper-case for a key: ɛ→Ɛ, ɔ→Ɔ, ŋ→Ŋ come from Unicode's own case pairs; multi-letter labels stay as they are. */
    fun upper(s: String): String = if (s.length == 1 && s[0].isLetter()) s.uppercase() else s

    fun letters(shift: ShiftState, enter: EnterKey): KeyboardLayout {
        val shifted = shift != ShiftState.OFF
        fun letter(c: Char): Key {
            val base = c.toString()
            val t = if (shifted) upper(base) else base
            return Key(KeyKind.CHAR, t, variants = variantsOf(base, shifted))
        }
        val shiftKey = Key(KeyKind.SHIFT, label = if (shift == ShiftState.LOCKED) "⇪" else "⇧", spoken = KeyNames.shift(shift), width = 1.5f)
        return KeyboardLayout(Page.LETTERS, listOf(
            ROW1.map(::letter),
            listOf(spacer(0.5f)) + ROW2.map(::letter) + spacer(0.5f),
            listOf(shiftKey) + ROW3.map(::letter) + backspace(),
            bottomRow(Key(KeyKind.SYMBOLS, label = "?123", spoken = "Numbers and symbols", width = 1.5f), enter)
        ))
    }

    fun symbols(enter: EnterKey): KeyboardLayout = KeyboardLayout(Page.SYMBOLS, listOf(
        "1234567890".map { char(it.toString()) },
        listOf("@", "#", "₵", "_", "&", "-", "+", "(", ")", "/").map(::char),
        listOf(Key(KeyKind.MORE_SYMBOLS, label = "=\\<", spoken = "More symbols", width = 1.5f)) +
            listOf("*", "\"", "'", ":", ";", "!", "?").map(::char) + backspace(),
        bottomRow(Key(KeyKind.LETTERS, label = "ABC", spoken = "Letters", width = 1.5f), enter)
    ))

    fun moreSymbols(enter: EnterKey): KeyboardLayout = KeyboardLayout(Page.MORE_SYMBOLS, listOf(
        listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆").map(::char),
        listOf("£", "¢", "$", "€", "¥", "^", "°", "=", "{", "}").map(::char),
        listOf(Key(KeyKind.SYMBOLS, label = "?123", spoken = "Numbers and symbols", width = 1.5f)) +
            listOf("\\", "%", "©", "®", "[", "]", "<").map(::char) + backspace(),
        bottomRow(Key(KeyKind.LETTERS, label = "ABC", spoken = "Letters", width = 1.5f), enter)
    ))

    /** A 4×4 pad. The fourth column and the corners around 0 depend on the field; a PIN pad has digits only. */
    fun pad(kind: PadKind, enter: EnterKey): KeyboardLayout {
        val (x1, x2, x3, x4) = when (kind) {
            PadKind.PHONE -> listOf(char("+"), space(1f), char("*"), char("#"))
            PadKind.NUMBER -> listOf(char("-"), char(","), Key(KeyKind.LETTERS, label = "ABC", spoken = "Letters"), char("."))
            PadKind.PIN -> listOf(spacer(1f), spacer(1f), spacer(1f), spacer(1f))
            PadKind.DATETIME -> listOf(char("/"), char(":"), char("-"), space(1f))
        }
        return KeyboardLayout(Page.PAD, listOf(
            listOf(char("1"), char("2"), char("3"), x1),
            listOf(char("4"), char("5"), char("6"), x2),
            listOf(char("7"), char("8"), char("9"), backspace(1f)),
            listOf(x3, char("0"), x4, enterKey(enter, 1f))
        ))
    }

    /** ?123 / ABC, comma, space, full stop, enter — ten units wide like the letter rows. A long-press on space switches
     *  to another keyboard (the job the old language key also did). */
    private fun bottomRow(pageKey: Key, enter: EnterKey): List<Key> = listOf(
        pageKey,
        char(","),
        space(5f),
        char("."),
        enterKey(enter, 1.5f)
    )

    private fun char(t: String) = Key(KeyKind.CHAR, t, variants = variantsOf(t, false))
    private fun space(w: Float) = Key(KeyKind.SPACE, " ", label = "space", spoken = "Space", width = w)
    private fun spacer(w: Float) = Key(KeyKind.SPACER, label = "", spoken = "", width = w)
    private fun backspace(w: Float = 1.5f) = Key(KeyKind.BACKSPACE, label = "⌫", spoken = "Delete", width = w)
    private fun enterKey(e: EnterKey, w: Float) = Key(KeyKind.ENTER, label = e.label, spoken = e.spoken, width = w)
}

/**
 * What TalkBack says for each key. The Akan letters get their name after the character ("ɛ, open e") because a
 * phone's English voice often reads ɛ/ɔ as nothing or as "epsilon"; the name makes the key unambiguous either way.
 * Symbols that engines skip or misread (₵, ¢, brackets) are spelled out.
 */
object KeyNames {
    private val NAMED = mapOf(
        "ɛ" to "ɛ, open e", "Ɛ" to "Ɛ, capital open E",
        "ɔ" to "ɔ, open o", "Ɔ" to "Ɔ, capital open O",
        "ŋ" to "ŋ, eng", "Ŋ" to "Ŋ, capital eng",
        "₵" to "cedi sign", "GH₵" to "Ghana cedi sign", "GH¢" to "Ghana cedi, cent style", "¢" to "cent sign",
        "$" to "dollar", "€" to "euro", "£" to "pound", "¥" to "yen",
        "." to "full stop", "," to "comma", "?" to "question mark", "!" to "exclamation mark",
        "'" to "apostrophe", "\"" to "quote", ":" to "colon", ";" to "semicolon", "-" to "dash", "_" to "underscore",
        "@" to "at sign", "#" to "hash", "&" to "and sign", "+" to "plus", "*" to "star", "/" to "slash",
        "\\" to "backslash", "(" to "open bracket", ")" to "close bracket", "[" to "open square bracket",
        "]" to "close square bracket", "{" to "open curly bracket", "}" to "close curly bracket", "<" to "less than",
        ">" to "greater than", "=" to "equals", "%" to "percent", "~" to "tilde", "`" to "backtick", "|" to "vertical bar",
        "•" to "bullet", "√" to "square root", "π" to "pi", "÷" to "divided by", "×" to "times", "¶" to "paragraph",
        "∆" to "delta", "^" to "caret", "°" to "degrees", "©" to "copyright", "®" to "registered", "–" to "en dash",
        "—" to "em dash", "“" to "open quote", "”" to "close quote", "‘" to "open single quote",
        "’" to "close single quote", "¿" to "inverted question mark", "¡" to "inverted exclamation mark",
        "≤" to "less than or equal", "≥" to "greater than or equal", "ç" to "c cedilla", "ñ" to "n tilde"
    )
    private val ACCENTS = mapOf('́' to "acute", '̀' to "grave", '̂' to "circumflex", '̈' to "diaeresis", '̃' to "tilde")

    fun spoken(text: String): String {
        if (text.isEmpty()) return ""
        NAMED[text]?.let { return it }
        if (text.length == 1) {
            val c = text[0]
            // Accented vowels: "é" → "e acute" (decomposed, so every vowel is covered by five marks).
            val d = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
            if (d.length == 2 && d[1] in ACCENTS) {
                val base = d[0]
                return (if (base.isUpperCase()) "capital ${base.lowercaseChar()}" else "$base") + " " + ACCENTS.getValue(d[1])
            }
            if (c.isLetter() && c.isUpperCase()) return "capital ${c.lowercaseChar()}"
        }
        return text
    }

    fun shift(state: ShiftState) = when (state) {
        ShiftState.OFF -> "Shift"
        ShiftState.ONCE -> "Shift, on"
        ShiftState.LOCKED -> "Shift, caps lock on"
    }
}
