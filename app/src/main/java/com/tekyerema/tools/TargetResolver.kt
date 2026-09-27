package com.tekyerema.tools

import com.tekyerema.accessibility.AccessibilityController
import com.tekyerema.accessibility.ElementSource
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import org.json.JSONObject

/** How the model addressed an on-screen target. Exactly one form per call. */
sealed class Target {
    data class ById(val id: Int) : Target()
    data class ByLabel(val label: String) : Target()
    data class ByPoint(val x: Int, val y: Int) : Target()
}

/** A target resolved against a CURRENT snapshot. [element] is null only for a bare vision point. */
data class ResolvedTarget(
    val element: UiElement?,
    val x: Int,
    val y: Int,
    val how: String            // "id" | "label" | "vision"
) {
    val label: String? get() = element?.label
    val nodeId: Int? get() = element?.takeIf { it.source == ElementSource.TREE }?.id
}

sealed class ResolveOutcome {
    data class Found(val target: ResolvedTarget) : ResolveOutcome()
    data class OnlyWeak(val weakLabels: List<String>) : ResolveOutcome()
    data class NotFound(val detail: String) : ResolveOutcome()
    data class Invalid(val detail: String) : ResolveOutcome()
}

/**
 * The ONE target-resolution mechanism shared by every element-taking primitive (plan §10 step 1).
 *
 *  - element_id: resolved against the current snapshot only. Ids never survive an observation.
 *  - label:      Grounding.bestMatch — strong/weak/none; a weak match is never silently chosen.
 *  - x,y:        accepted only when a `source:"vision"` element in the current snapshot contains
 *                the point. Coordinates the model invents are rejected.
 *
 * The executor, not the model, owns final resolution.
 */
object TargetResolver {
    fun parse(args: JSONObject): Target? {
        args.intOrNull("element_id")?.let { return Target.ById(it) }
        args.str("label")?.trim()?.takeIf { it.isNotEmpty() }?.let { return Target.ByLabel(it) }
        val x = args.intOrNull("x"); val y = args.intOrNull("y")
        if (x != null && y != null) return Target.ByPoint(x, y)
        return null
    }

    /** Pure, side-effect-free resolution used by the policy gate before anything executes. */
    fun resolveStatic(target: Target, snapshot: UiSnapshot?): ResolveOutcome {
        val snap = snapshot ?: return ResolveOutcome.Invalid("no current observation to resolve against")
        return when (target) {
            is Target.ById -> {
                val e = snap.element(target.id)
                    ?: return ResolveOutcome.Invalid("element_id ${target.id} is not in the current screen listing — re-observe and use a current id")
                ResolveOutcome.Found(ResolvedTarget(e, e.bounds.centerX, e.bounds.centerY, if (e.source == ElementSource.VISION) "vision" else "id"))
            }
            is Target.ByLabel -> when (val m = Grounding.bestMatch(snap.elements, target.label)) {
                is MatchOutcome.Found -> ResolveOutcome.Found(
                    ResolvedTarget(m.element, m.element.bounds.centerX, m.element.bounds.centerY,
                        if (m.element.source == ElementSource.VISION) "vision" else "label")
                )
                is MatchOutcome.OnlyWeak -> ResolveOutcome.OnlyWeak(m.weakLabels)
                MatchOutcome.None -> ResolveOutcome.NotFound("no element labelled '${target.label}' on the current screen")
            }
            is Target.ByPoint -> {
                val hit = snap.elements.firstOrNull { it.source == ElementSource.VISION && it.bounds.contains(target.x, target.y) }
                    ?: return ResolveOutcome.Invalid(
                        "coordinates (${target.x},${target.y}) do not fall inside any vision element of the current screen — " +
                            "use an element id, a label, or the bounds of a listed vision element"
                    )
                ResolveOutcome.Found(ResolvedTarget(hit, target.x, target.y, "vision"))
            }
        }
    }

    /**
     * Executor-time resolution. For labels not visible in the current snapshot, scrolls the first
     * scrollable region to reveal more (bounded), re-observing after each scroll — the retained
     * Phase 1 scroll-to-reveal behaviour, now shared by every label-targeting primitive.
     */
    suspend fun resolve(target: Target, ctrl: AccessibilityController, snapshot: UiSnapshot?, allowScroll: Boolean): Pair<ResolveOutcome, UiSnapshot?> {
        var snap = snapshot
        val first = resolveStatic(target, snap)
        if (first !is ResolveOutcome.NotFound || target !is Target.ByLabel || !allowScroll) return first to snap
        repeat(7) {
            snap = ctrl.buildSnapshot()
            when (val r = resolveStatic(target, snap)) {
                is ResolveOutcome.Found, is ResolveOutcome.OnlyWeak -> return r to snap
                else -> {}
            }
            val scrollable = snap?.elements?.firstOrNull { it.scrollable }?.id
                ?: return ResolveOutcome.NotFound("couldn't find '${target.label}' and nothing is scrollable") to snap
            ctrl.scrollElement(scrollable, "down")
            ctrl.waitForSettle(1500)
        }
        return ResolveOutcome.NotFound("couldn't find an item labelled '${target.label}' after scrolling") to snap
    }

    fun describe(t: Target): String = when (t) {
        is Target.ById -> "element ${t.id}"
        is Target.ByLabel -> "'${t.label}'"
        is Target.ByPoint -> "(${t.x},${t.y})"
    }
}
