package com.tekyerema.teach

import com.tekyerema.policy.TargetSemantics

/**
 * Teach mode — steps in plain words (the read-back before saving, "what does X do", the hand-over goal), and which steps
 * the policy would ask about. Pure → unit-tested.
 */
object StepWords {
    private const val LABEL_MAX = 40

    /** `com.bank:id/dataTile` → "data tile". */
    fun idWords(viewId: String?): String = viewId.orEmpty().substringAfter(":id/")
        .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replace(Regex("[^A-Za-z0-9]+"), " ").trim().lowercase()

    private fun short(s: String) = s.trim().replace(Regex("\\s+"), " ").let { if (it.length > LABEL_MAX) it.take(LABEL_MAX).trimEnd() + "…" else it }

    /** The name of the box a TYPE step goes into. */
    fun boxName(s: TeachStep): String =
        (s.hint ?: s.label)?.takeIf { it.isNotBlank() }?.let(::short) ?: idWords(s.viewId).takeIf { it.isNotBlank() } ?: "text"

    /** The element a CLICK / LONG_CLICK step names: its label, else a words-form of its resource id, else null. */
    fun targetName(s: TeachStep): String? =
        s.label?.takeIf { it.isNotBlank() }?.let(::short) ?: idWords(s.viewId).takeIf { it.isNotBlank() }

    fun appWords(s: TeachStep): String = s.appName?.takeIf { it.isNotBlank() } ?: s.pkg.substringAfterLast('.')

    /** One step: "open WhatsApp", "tap Chats", "type into the Search box", "scroll down". */
    fun describe(s: TeachStep): String = when (s.kind) {
        StepKind.OPEN_APP -> "open ${appWords(s)}"
        StepKind.CLICK -> targetName(s)?.let { "tap $it" } ?: "tap a button I couldn't name"
        StepKind.LONG_CLICK -> targetName(s)?.let { "press and hold $it" } ?: "press and hold something I couldn't name"
        StepKind.TYPE -> (if (s.text != null) "type “${short(s.text)}” into the ${boxName(s)} box" else "type into the ${boxName(s)} box") +
            if (s.submit) " and search" else ""
        StepKind.SCROLL -> "scroll ${s.direction ?: "down"}"
        StepKind.BACK -> "go back"
        StepKind.HOME -> "go to the home screen"
    }

    /** "first open MyMTN, then tap Data, then tap Balance." — at most [max] steps are named, the rest counted. */
    fun spoken(steps: List<TeachStep>, max: Int = 8): String {
        if (steps.isEmpty()) return ""
        val shown = if (steps.size > max) steps.take(max - 1) else steps
        val parts = shown.mapIndexed { i, s -> (if (i == 0) "first " else "then ") + describe(s) }.toMutableList()
        if (steps.size > shown.size) parts.add("and ${steps.size - shown.size} more step${if (steps.size - shown.size == 1) "" else "s"}")
        return parts.joinToString(", ") + "."
    }

    /** The remaining steps as one plain sentence, for a hand-over goal ("open MyMTN, tap Data and tap Balance"). */
    fun asGoal(steps: List<TeachStep>): String {
        val d = steps.filter { !(it.kind == StepKind.SCROLL && it.optional) }.map(::describe)
        return when (d.size) { 0 -> ""; 1 -> d[0]; else -> d.dropLast(1).joinToString(", ") + " and " + d.last() }
    }

    /**
     * Would the policy ask about this step? Judged from the recorded label alone (the same words the engine's
     * [TargetSemantics.classify] reads for a tap on that caption), so the read-back can warn "this step asks you each time".
     * Replay does not rely on this flag — it re-checks the real element on the real screen before every step.
     */
    fun isSensitive(s: TeachStep): Boolean = when (s.kind) {
        StepKind.CLICK, StepKind.LONG_CLICK ->
            s.label?.let { TargetSemantics.classify("tap", null, it, false, null).let { c -> c.tier != null || c.block != null } } == true
        StepKind.TYPE -> TargetSemantics.classify("type", null, s.hint ?: s.label, s.submit, null).let { c -> c.tier != null || c.block != null }
        else -> false
    }
}
