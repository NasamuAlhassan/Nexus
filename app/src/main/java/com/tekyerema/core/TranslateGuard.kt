package com.tekyerema.core

/**
 * Names and numbers never go through machine translation (docs/agent/KEYBOARD_LANGUAGES.md §5, owner 2 Nov 2026). The live Space probe
 * turned "cedis" into cedars / shekels / chiefs and "what time is it" into "…for dinner?"; a phone number or a person's name
 * changed by translation would send a message or money to the wrong place.
 *
 * [protect] swaps every digit run (amounts, phone numbers, times, dates) and every known contact name in the text for a placeholder;
 * [restore] puts the originals back after translation, and returns null when any placeholder did not survive — the caller then
 * asks the user ("Who should get it?" / "What number?") instead of guessing. Pure Kotlin, unit-tested (TranslateGuardTest).
 * The placeholder shape is configurable ([style]) because which tokens the translation model copies unchanged is measured on the
 * live Space, not assumed.
 */
object TranslateGuard {
    data class Guarded(val text: String, val slots: Map<String, String>)

    /** The token for slot [n] of [kind] ("N" number, "P" person). Default ⟦N1⟧; the keyboard may switch styles after a live test. */
    @Volatile var style: (kind: Char, n: Int) -> String = { kind, n -> "⟦$kind$n⟧" }

    /** Digit runs with their separators: 0244123456, 024 412 3456, 50, 1,500, 12.50, 10:30, 2/11/2026. */
    private val NUMBER = Regex("\\d[\\d ,.:/-]*\\d|\\d")

    fun protect(text: String, names: Collection<String> = emptyList()): Guarded {
        val slots = LinkedHashMap<String, String>()
        // Numbers FIRST: the tokens carry digits (⟦P1⟧), so a number pass after the names would eat the names' own tokens.
        var n = 0
        var out = NUMBER.replace(text) { m ->
            val token = style('N', ++n)
            slots[token] = m.value
            token
        }
        var p = 0
        // Longest names first, so "Ama Serwaa" is one slot and not "Ama" + "Serwaa".
        for (name in names.map { it.trim() }.filter { it.length >= 2 }.distinct().sortedByDescending { it.length }) {
            val re = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(name) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            if (!re.containsMatchIn(out)) continue
            val token = style('P', ++p)
            out = re.replace(out) { m -> slots[token] = m.value; token }
        }
        return Guarded(out, slots)
    }

    /** The translation with the originals put back, or null when a placeholder was lost, doubled or changed by the translation. */
    fun restore(translated: String, guarded: Guarded): String? {
        var out = translated
        for ((token, original) in guarded.slots) {
            val count = out.split(token).size - 1
            if (count != 1) return null
            out = out.replace(token, original)
        }
        return out
    }
}
