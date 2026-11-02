package com.tekyerema.teach

import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.tools.Grounding
import com.tekyerema.tools.MatchOutcome

/**
 * Teach mode — does the screen in front still match a recorded step, and which element is it? Pure → unit-tested.
 *
 * Order: the same app; then the resource id (the most durable selector) — with the label agreeing when the id is shared
 * by several rows; then the label through [Grounding.bestMatch] (the same grounding every tap uses, so a label that only
 * appears inside a long paragraph is never taken). A label that changes by nature (it holds digits: a balance, a time)
 * is matched by its id alone. Nothing is ever matched by position.
 */
object StepMatcher {
    sealed class Match {
        data class Found(val element: UiElement, val how: String) : Match()
        /** The wrong app / screen is in front. */
        data class WrongApp(val expected: String, val actual: String?) : Match()
        /** The element isn't on this screen (yet). */
        data class Missing(val what: String) : Match()
    }

    private val HAS_DIGIT = Regex("\\d")

    fun find(step: TeachStep, snap: UiSnapshot): Match {
        if (snap.appPackage != step.pkg) return Match.WrongApp(step.pkg, snap.appPackage)
        return when (step.kind) {
            StepKind.TYPE -> findField(step, snap)
            StepKind.CLICK, StepKind.LONG_CLICK -> findControl(step, snap)
            StepKind.SCROLL -> snap.elements.firstOrNull { it.scrollable }?.let { Match.Found(it, "scrollable") } ?: Match.Missing("something to scroll")
            StepKind.OPEN_APP -> Match.Missing("app")
            StepKind.BACK, StepKind.HOME -> Match.Missing("a key")   // keys have no element; ReplayEngine handles them before matching
        }
    }

    private fun findControl(step: TeachStep, snap: UiSnapshot): Match {
        val label = step.label?.takeIf { it.isNotBlank() }
        val what = label ?: StepWords.idWords(step.viewId).ifBlank { "that button" }
        val byId = step.viewId?.takeIf { it.isNotBlank() }?.let { id -> snap.elements.filter { it.viewId == id && it.enabled } }.orEmpty()
        if (byId.size == 1) {
            val e = byId[0]
            // One element with that id: take it, unless both sides have a steady name and the names disagree (a different thing).
            if (label == null || e.label.isNullOrBlank() || HAS_DIGIT.containsMatchIn(label) || sameName(label, e.label)) return Match.Found(e, "id")
        } else if (byId.size > 1 && label != null) {
            byId.firstOrNull { sameName(label, it.label) }?.let { return Match.Found(it, "id+label") }
        }
        if (label != null) {
            when (val m = Grounding.bestMatch(snap.elements.filter { it.enabled }, label)) {
                is MatchOutcome.Found -> return Match.Found(m.element, "label")
                else -> {}
            }
        }
        return Match.Missing(what)
    }

    private fun findField(step: TeachStep, snap: UiSnapshot): Match {
        val fields = snap.elements.filter { it.editable && !it.password && it.enabled }
        val id = step.viewId?.takeIf { it.isNotBlank() }
        if (id != null) {
            val m = fields.filter { it.viewId == id }
            if (m.isNotEmpty()) return pick(m, "id")
        }
        val hint = (step.hint ?: step.label)?.takeIf { it.isNotBlank() }
        if (hint != null) {
            val m = fields.filter { sameName(hint, it.hint) || sameName(hint, it.label) }
            if (m.isNotEmpty()) return pick(m, "hint")
        }
        return Match.Missing("the ${StepWords.boxName(step)} box")
    }

    /** Several boxes fit: the one with the cursor, else the first. */
    private fun pick(m: List<UiElement>, how: String): Match = Match.Found(m.firstOrNull { it.focused } ?: m.first(), how)

    private fun sameName(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        val x = a.trim().lowercase(); val y = b.trim().lowercase()
        return x == y || x.contains(y) && y.length >= 3 || y.contains(x) && x.length >= 3
    }
}
