package com.tekyerema.tools

import com.tekyerema.accessibility.UiElement

sealed class MatchOutcome {
    data class Found(val element: UiElement) : MatchOutcome()
    data class OnlyWeak(val weakLabels: List<String>) : MatchOutcome()
    object None : MatchOutcome()
}

/**
 * Label grounding, shared by every label-targeting primitive. Retained unchanged in logic from
 * Phase 1's TapTextTool (proven across rounds 6–8 to eliminate false-positive taps):
 *
 * Ranks candidates instead of taking the first substring hit — a query "Message" must not
 * match an unrelated label like "Play voice message", and a name like "Kwame" must not match
 * an unrelated message body that merely mentions someone named Kwame in passing.
 *
 * Preference order (each pass tried over clickable elements first, then all elements):
 *   1. exact match (case-insensitive)
 *   2. label starts-with query
 *   3. STRONG word-boundary/contains match — the query is a substantial fraction of a short
 *      label (a real UI label/name), not a few words buried in a paragraph. Shortest wins.
 *
 * If only WEAK matches exist (the query occurs incidentally inside much longer text), this does
 * NOT silently pick the closest one: it reports [MatchOutcome.OnlyWeak] so the caller can give
 * the model real signal (scroll more / search / refine). Generic: driven by label length/ratio,
 * not any app or word.
 */
object Grounding {
    private const val MAX_INCIDENTAL_LABEL_LENGTH = 60
    private const val MIN_MATCH_RATIO = 0.25

    fun bestMatch(elements: List<UiElement>, query: String): MatchOutcome {
        val q = query.trim()
        if (q.isEmpty()) return MatchOutcome.None

        fun isStrong(label: String): Boolean =
            label.length <= MAX_INCIDENTAL_LABEL_LENGTH && q.length.toDouble() / label.length >= MIN_MATCH_RATIO

        fun strongPass(pool: List<UiElement>): UiElement? {
            pool.firstOrNull { it.label?.equals(q, true) == true }?.let { return it }
            // Prefix is only a strong signal on a SHORT label. Phase 1 left this pass unguarded; a
            // long message that merely begins with the query ("Kwame is in the group…") is not a
            // "Kwame" item and must fall through to the weak-match refusal like any other paragraph.
            pool.firstOrNull { e -> e.label?.let { it.startsWith(q, true) && isStrong(it) } == true }?.let { return it }
            val wordBoundary = Regex("\\b${Regex.escape(q)}\\b", RegexOption.IGNORE_CASE)
            pool.filter { e -> e.label?.let { wordBoundary.containsMatchIn(it) && isStrong(it) } == true }
                .minByOrNull { it.label!!.length }?.let { return it }
            pool.filter { e -> e.label?.let { it.contains(q, true) && isStrong(it) } == true }
                .minByOrNull { it.label!!.length }?.let { return it }
            return null
        }

        val clickable = elements.filter { it.clickable }
        (strongPass(clickable) ?: strongPass(elements))?.let { return MatchOutcome.Found(it) }

        val weak = elements.mapNotNull { it.label }
            .filter { it.contains(q, true) }
            .distinct()
        return if (weak.isNotEmpty()) MatchOutcome.OnlyWeak(weak.take(2)) else MatchOutcome.None
    }
}
