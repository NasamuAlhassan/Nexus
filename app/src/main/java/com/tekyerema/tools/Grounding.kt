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

        // A partial match must not ADD an action the model didn't name: "Library" is not "Remove from library" (YT Music's
        // song menu, A20e 2026-09-29 — the policy caught it). Such a label stays a weak match, never silently chosen.
        val qLower = q.lowercase()
        fun addsAction(label: String): Boolean {
            val l = label.lowercase()
            return (com.tekyerema.policy.TargetSemantics.consequential.containsMatchIn(l) &&
                !com.tekyerema.policy.TargetSemantics.consequential.containsMatchIn(qLower)) ||
                (com.tekyerema.policy.TargetSemantics.isConsent(l) && !com.tekyerema.policy.TargetSemantics.isConsent(qLower))
        }
        fun isStrong(label: String): Boolean =
            label.length <= MAX_INCIDENTAL_LABEL_LENGTH && q.length.toDouble() / label.length >= MIN_MATCH_RATIO && !addsAction(label)

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
        // G-07: the visible label didn't match — try the element's other attributes before giving up.
        attributeMatch(elements, q)?.let { return MatchOutcome.Found(it) }
        // The thing in the user's hand goes by many names — "About phone" (Samsung, Pixel), "About emulated device" (the
        // emulator, 13 October 2026), "About tablet", "About device" — so a multi-word query with a device word in it is
        // tried once more with that word made canonical on both sides. Never for the bare word: "Phone" is the Phone app.
        deviceWordPass(elements, q)?.let { return MatchOutcome.Found(it) }

        val weak = elements.mapNotNull { it.label }
            .filter { it.contains(q, true) }
            .distinct()
        return if (weak.isNotEmpty()) MatchOutcome.OnlyWeak(weak.take(2)) else MatchOutcome.None
    }

    private val DEVICE_WORDS = Regex("(?i)\\b(?:emulated\\s+device|device|phone|tablet|handset|mobile)\\b")
    private fun canonDevice(s: String) = DEVICE_WORDS.replace(s.lowercase(), "device").replace(Regex("\\s+"), " ").trim()

    /** "About phone" ↔ "About emulated device" / "About tablet" / "About device": equal or a strong prefix once the device
     *  word is canonical on both sides, on a short label, clickable first. Null for a one-word query. */
    private fun deviceWordPass(elements: List<UiElement>, q: String): UiElement? {
        if (!DEVICE_WORDS.containsMatchIn(q) || q.trim().split(Regex("\\s+")).size < 2) return null
        val qc = canonDevice(q)
        fun ok(e: UiElement): Boolean {
            val l = e.label ?: return false
            if (l.length > MAX_INCIDENTAL_LABEL_LENGTH || !DEVICE_WORDS.containsMatchIn(l)) return false
            val lc = canonDevice(l)
            return lc == qc || (lc.startsWith(qc) && qc.length.toDouble() / lc.length >= MIN_MATCH_RATIO)
        }
        return elements.filter { it.clickable }.firstOrNull(::ok) ?: elements.firstOrNull(::ok)
    }

    /**
     * Labels that share a real word with [query] (four letters or more, not a filler word) — the hint when nothing matched,
     * so the planner is told "no 'About phone' here; the nearest labels are 'About emulated device'" instead of guessing
     * (A20e/emulator, 13 October 2026: it tapped 'System' instead). Shortest first, at most [max].
     */
    fun nearLabels(elements: List<UiElement>, query: String, max: Int = 3): List<String> {
        val words = query.lowercase().split(WORD_BREAK).filter { it.length >= 4 && it !in FILLER }.toSet()
        if (words.isEmpty()) return emptyList()
        return elements.mapNotNull { it.label?.trim() }
            .filter { it.length <= MAX_INCIDENTAL_LABEL_LENGTH && it.lowercase().split(WORD_BREAK).any { w -> w in words } }
            .distinct().sortedBy { it.length }.take(max)
    }
    private val WORD_BREAK = Regex("[^\\p{L}\\p{N}]+")
    private val FILLER = setOf("the", "this", "that", "with", "from", "your", "into", "onto", "button", "item", "option", "menu", "page", "screen")

    /**
     * G-07 weighted multi-attribute locator — the fallback cascade after the label passes: resource id (an icon
     * button with no text is often "send_button" / "btn_search"), an editable's placeholder hint ("Message", "Search"
     * while it holds text), then a role word ("the text box" when exactly one exists). Each attribute has a weight
     * (`executor.locators.weights`); the best score at or above `executor.locators.minScore` wins, and a tie between
     * different elements is refused rather than guessed.
     */
    internal fun attributeMatch(elements: List<UiElement>, query: String): UiElement? {
        val q = norm(query)
        if (q.isEmpty()) return null
        val w = weights()
        // A role word ("the text box", "the switch") names the element when exactly one of that kind is on screen.
        val rolePred = ROLE_WORDS.entries.firstOrNull { (re, _) -> re.matches(query.trim().lowercase()) }?.value
        val roleOnly = rolePred?.let { p -> elements.filter(p).singleOrNull() }
        fun score(e: UiElement): Double {
            var s = 0.0
            e.viewId?.substringAfter(":id/")?.let { id ->
                val entry = norm(id)
                val tokens = id.split('_', '-', '.').flatMap { splitCamel(it) }.map { norm(it) }.filter { it.isNotEmpty() }
                s = maxOf(s, when {
                    entry == q -> w.getValue("viewId")
                    q in tokens || tokens.joinToString("") == q -> w.getValue("viewId") * 0.8
                    else -> 0.0
                })
            }
            e.hint?.let { if (norm(it) == q) s = maxOf(s, w.getValue("hint")) }
            if (roleOnly != null && e === roleOnly) s = maxOf(s, w.getValue("role"))
            if (s > 0 && (e.clickable || e.editable)) s += 0.01   // prefer something actionable on a tie
            return s
        }
        val ranked = elements.map { it to score(it) }.filter { it.second >= minScore() }.sortedByDescending { it.second }
        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)
        if (second != null && second.second == best.second && second.first.bounds != best.first.bounds) return null
        return best.first
    }

    private val ROLE_WORDS: Map<Regex, (UiElement) -> Boolean> = mapOf(
        Regex("(?:the )?(?:text ?box|text field|input(?: field)?|message (?:box|field)|search (?:box|field|bar))") to { e -> e.editable },
        Regex("(?:the )?(?:switch|toggle)") to { e -> e.checkable && (e.role.contains("Switch") || e.role.contains("Toggle")) },
        Regex("(?:the )?(?:slider|seek ?bar)") to { e -> e.range != null }
    )

    private val DEFAULT_WEIGHTS = mapOf("viewId" to 0.75, "hint" to 0.7, "role" to 0.5)

    private fun weights(): Map<String, Double> {
        val cfg = com.tekyerema.core.config.AppConfig.obj("executor.locators.weights") ?: return DEFAULT_WEIGHTS
        return DEFAULT_WEIGHTS.mapValues { (k, v) -> cfg.optDouble(k, v) }
    }

    private fun minScore(): Double = com.tekyerema.core.config.AppConfig.double("executor.locators.minScore", 0.5)

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun splitCamel(s: String): List<String> = s.split(Regex("(?<=[a-z])(?=[A-Z])"))
}
