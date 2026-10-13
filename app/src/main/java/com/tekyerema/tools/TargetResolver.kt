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
                MatchOutcome.None -> ResolveOutcome.NotFound("no element labelled '${target.label}' on the current screen" +
                    nearHint(Grounding.nearLabels(snap.elements, target.label)))
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
     *
     * G-21: it stops at the END of the list (the screen stopped moving) instead of scrolling a fixed 7 times with a
     * settle wait each — at the bottom of One UI Settings that was ~10 s of dead scrolls — and then looks back UP, since
     * an earlier step may have left the list scrolled past the item. A miss then means "not in this list", which the
     * reply says, so the planner stops scrolling it.
     */
    suspend fun resolve(target: Target, ctrl: AccessibilityController, snapshot: UiSnapshot?, allowScroll: Boolean): Pair<ResolveOutcome, UiSnapshot?> {
        var snap = snapshot
        val first = resolveStatic(target, snap)
        if (first !is ResolveOutcome.NotFound || target !is Target.ByLabel || !allowScroll) return first to snap
        val plan = ScrollSearch(MAX_SEARCH_SCROLLS)
        val near = LinkedHashSet<String>()   // the nearest labels seen anywhere in the list, for an honest "not here" reply
        while (true) {
            snap = ctrl.buildSnapshot()
            when (val r = resolveStatic(target, snap)) {
                is ResolveOutcome.Found, is ResolveOutcome.OnlyWeak -> return r to snap
                else -> {}
            }
            snap?.let { near += Grounding.nearLabels(it.elements, target.label) }
            val scrollable = snap?.elements?.firstOrNull { it.scrollable }?.id
                ?: return ResolveOutcome.NotFound("couldn't find '${target.label}' and nothing is scrollable") to snap
            val direction = plan.next(snap?.signature()) ?: break
            if (!ctrl.scrollElement(scrollable, direction)) { plan.blocked(); continue }
            ctrl.waitForSettle(1500)
        }
        return ResolveOutcome.NotFound("couldn't find an item labelled '${target.label}' anywhere in this list (scrolled to its end" +
            (if (plan.reversed) " and back up" else "") + ") — it isn't here; try the app's own search or another screen" +
            nearHint(near.take(3))) to snap
    }

    /** " (the nearest labels were 'About emulated device', 'System')" — or nothing. */
    private fun nearHint(labels: List<String>): String =
        if (labels.isEmpty()) "" else " (the nearest labels ${if (labels.size == 1) "was" else "were"} ${labels.joinToString { "'$it'" }})"

    private const val MAX_SEARCH_SCROLLS = 12

    /**
     * Which way to scroll next while searching a list, or null when it's done: down until the list stops moving (or the
     * scroll action is refused), then up until it stops again, [max] scrolls in all. Pure, so it is unit-tested.
     */
    internal class ScrollSearch(private val max: Int) {
        var reversed = false; private set
        private var dir = "down"
        private var lastSig: String? = null
        private var used = 0
        private var done = false

        /** [sig] is the screen now; null = unknown (never counts as "stopped moving"). */
        fun next(sig: String?): String? {
            if (done || used >= max) return null
            if (sig != null && sig == lastSig && !turn()) return null
            lastSig = sig
            used++
            return dir
        }

        /** The scroll action itself was refused (already at that end). */
        fun blocked() { used--; lastSig = null; if (!turn()) done = true }

        private fun turn(): Boolean {
            if (reversed) { done = true; return false }
            reversed = true; dir = "up"; return true
        }
    }

    /**
     * G-08 — find the SAME element in a fresh snapshot after a delay (a confirmation wait). Element ids are
     * indices into one snapshot, so after a reflow (new message, keyboard, scroll) "element 7" can be a different
     * node. Match by identity instead: resource id, label, role and editability, then nearest bounds. Ambiguous
     * or far-moved matches are refused rather than guessed.
     */
    fun reResolve(previous: UiElement, fresh: UiSnapshot): ResolveOutcome {
        val same = fresh.elements.filter { e ->
            e.source == previous.source && e.editable == previous.editable && e.role == previous.role &&
                (if (previous.viewId != null && e.viewId != null) e.viewId == previous.viewId else true) &&
                (if (!previous.label.isNullOrBlank()) e.label == previous.label else e.bounds == previous.bounds)
        }
        if (same.isEmpty()) return ResolveOutcome.NotFound("the item you confirmed is no longer on the screen")
        fun dist(e: UiElement) = Math.hypot((e.bounds.centerX - previous.bounds.centerX).toDouble(), (e.bounds.centerY - previous.bounds.centerY).toDouble())
        val ranked = same.sortedBy(::dist)
        val best = ranked.first()
        // Several identical items (e.g. a "Reply" per message): only accept one that stayed (almost) in place.
        if (ranked.size > 1 && dist(best) > REFLOW_TOLERANCE_PX)
            return ResolveOutcome.NotFound("several items look like the one you confirmed and it moved — re-observe")
        return ResolveOutcome.Found(ResolvedTarget(best, best.bounds.centerX, best.bounds.centerY,
            if (best.source == ElementSource.VISION) "vision" else "identity"))
    }

    private const val REFLOW_TOLERANCE_PX = 48.0

    fun describe(t: Target): String = when (t) {
        is Target.ById -> "element ${t.id}"
        is Target.ByLabel -> "'${t.label}'"
        is Target.ByPoint -> "(${t.x},${t.y})"
    }
}
