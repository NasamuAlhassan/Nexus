package com.tekyerema.core

/**
 * Names and numbers never go through machine translation (docs/agent/KEYBOARD_LANGUAGES.md §5, owner 2 Nov 2026). The live Space probe
 * turned "cedis" into cedars / shekels / chiefs and "what time is it" into "…for dinner?"; a phone number or a person's name
 * changed by translation would send a message or money to the wrong place.
 *
 * [protect] swaps every digit run (amounts, phone numbers, times, dates) and every known contact name for a slot `X1`, `X2`, …;
 * [restore] puts the originals back after translation, and returns null when any slot did not come back exactly once — the caller
 * then asks the user ("Who should get it?" / "What number?") or falls back to English, instead of guessing.
 *
 * Why `X1`: the voice session's live probe through NLLB-600M v3 (2 Nov, campaign/space_brain/probe_slots.py): `⟦N1⟧` lost its brackets
 * 0/8, `#1` split 6/8, `[1]`/`__1__` survived but left 3/8 sentences untranslated; `X1` survived 8/8 (Twi, Ewe) and 6/6 (Kusaal) with the
 * sentence really translated. Ewe once glued a suffix ("X1e"): restore tolerates up to 3 glued letters and drops them (safer for
 * names), and never reads X1 inside X10. Pure Kotlin, unit-tested (TranslateGuardTest).
 */
object TranslateGuard {
    data class Guarded(val text: String, val slots: Map<String, String>)

    /** Digit runs with their separators: 0244123456, 024 412 3456, 50, 1,500, 12.50, 10:30, 2/11/2026. */
    private val NUMBER = Regex("\\d[\\d ,.:/-]*\\d|\\d")
    /** A slot as it comes back from translation: X or x, its number (never a prefix of a longer one), up to 3 glued letters. */
    private val SLOT_BACK = Regex("(?<![\\p{L}\\p{N}])[Xx](\\d+)(?!\\d)(\\p{L}{0,3})(?![\\p{L}])")

    private fun token(n: Int) = "X$n"

    fun protect(text: String, names: Collection<String> = emptyList()): Guarded {
        val slots = LinkedHashMap<String, String>()
        var n = 0
        // Numbers FIRST: the slots themselves carry digits (X1), so a number pass after the names would eat the names' slots.
        var out = NUMBER.replace(text) { m ->
            val t = token(++n)
            slots[t] = m.value
            t
        }
        // Longest names first, so "Ama Serwaa" is one slot and not "Ama" + "Serwaa".
        for (name in names.map { it.trim() }.filter { it.length >= 2 }.distinct().sortedByDescending { it.length }) {
            val re = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(name) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            if (!re.containsMatchIn(out)) continue
            val t = token(++n)
            out = re.replace(out) { m -> slots[t] = m.value; t }
        }
        return Guarded(out, slots)
    }

    /** The translation with the originals put back, or null when a slot was lost, doubled, or one appeared that was never sent. */
    fun restore(translated: String, guarded: Guarded): String? {
        val seen = HashMap<String, Int>()
        for (m in SLOT_BACK.findAll(translated)) {
            val t = token(m.groupValues[1].toInt())
            if (t !in guarded.slots) return null          // a slot we never sent: the model invented or renumbered one
            seen[t] = (seen[t] ?: 0) + 1
        }
        if (guarded.slots.keys.any { seen[it] != 1 }) return null
        return SLOT_BACK.replace(translated) { m -> guarded.slots.getValue(token(m.groupValues[1].toInt())) }
    }
}
