package com.tekyerema.agent

import com.tekyerema.accessibility.ElementSource
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.core.config.AppConfig
import com.tekyerema.verifier.Expectation

/**
 * G-21 / C-21 — how far one executed step moved the task, beyond a bare verified/unverified. Pure, so the loop's
 * bookkeeping is unit-tested off-device.
 *
 * Why (A20e, 2026-09-28, "open Settings and tell me which Android version this phone has"): One UI keeps "About phone"
 * at the very bottom of Settings and the version one level deeper (About phone › Software information). The model
 * scrolled toward "About phone" with `expects element_present: About phone`; each scroll changed the screen and
 * revealed rows it had never seen, but the claim wasn't met yet, so it counted as a FAILURE — two of those switched
 * on vision and "high" reasoning, and a third asked the user "keep trying or stop?" in the middle of a task that
 * was going fine. Mobile-agent work draws the same line: MobileUse's trajectory reflector fires on repeated actions,
 * repeated screens and accumulated errors — not on a step that moved forward (arXiv 2507.16853).
 *
 * So a step that ran, didn't meet its claim, but moved the SAME app on to content this task hasn't seen yet is
 * [Verdict.PARTIAL]: exploratory progress. It is NEVER verified — completion gating still requires the last
 * state-changing action to verify — it only stops the loop from treating exploration as being stuck.
 */
object Progress {
    enum class Verdict {
        /** The claim was met (or the tool confirmed its own outcome). */
        VERIFIED,
        /** Not met yet, but the step moved the same app on to content new to this task. Not success, not failure. */
        PARTIAL,
        /** Ran, but the screen is exactly as it was. */
        NO_CHANGE,
        /** Ran, changed something, but neither met the claim nor revealed anything new. */
        UNVERIFIED,
        /** The tool itself failed. */
        FAILED,
        /** A read (screen, messages, web): it changes nothing on the phone, so it says nothing about being stuck. */
        OBSERVED
    }

    /** Navigation primitives: they move around inside an app. A consequential tap (send/call/pay) is excluded by the
     *  engine through [classify]'s `confirmed`/`consequential` inputs, never by this list. */
    val NAV_TOOLS: Set<String>
        get() = AppConfig.stringList("executor.progress.tools", listOf("tap", "scroll", "scroll_to_find", "swipe", "key_event")).toSet()

    /** How many never-seen labels make a screen "new content" — 2, so a ticking clock or a changed badge isn't. */
    val minNovel: Int get() = AppConfig.int("executor.progress.minNovel", 2).coerceAtLeast(1)

    fun classify(
        tool: String, mutating: Boolean, success: Boolean, verified: Boolean,
        /** the user approved this step (send/call/pay…) — its outcome must verify, never "partly" */
        confirmed: Boolean, consequential: Boolean,
        /** the model gave no concrete claim — it earns no benefit of the doubt */
        vacuous: Boolean,
        sameApp: Boolean, changed: Boolean, novel: Int,
        navTools: Set<String> = NAV_TOOLS, minNew: Int = minNovel
    ): Verdict = when {
        !success -> Verdict.FAILED
        !mutating -> Verdict.OBSERVED
        verified -> Verdict.VERIFIED
        !changed -> Verdict.NO_CHANGE
        tool in navTools && !confirmed && !consequential && !vacuous && sameApp && novel >= minNew -> Verdict.PARTIAL
        else -> Verdict.UNVERIFIED
    }

    /**
     * C-21 / G-12: the consecutive-failure streak that raises reasoning effort, switches to vision and — at
     * `executor.recovery.maxConsecutiveFailures` — asks the user. A verified step clears it; real stuckness (a failed
     * tool, a dead action, an unmet claim with nothing new on screen) adds to it; exploration and reads HOLD it. A read
     * used to reset it, so "fail, read, fail, read…" could never escalate however long it went on.
     */
    fun nextFailStreak(streak: Int, v: Verdict): Int = when (v) {
        Verdict.VERIFIED -> 0
        Verdict.PARTIAL, Verdict.OBSERVED -> streak
        Verdict.NO_CHANGE, Verdict.UNVERIFIED, Verdict.FAILED -> streak + 1
    }

    /**
     * Completion gating (plan §11): may a "done" that followed state-changing steps be reported as verified? Only if the
     * LAST state-changing step verified and, over three or more assessed steps, at least half did. PARTIAL steps are
     * navigation on the way (scrolling to "About phone", tapping into its sub-menu) — left out of the ratio, never
     * counted as verified, and a PARTIAL last step leaves [lastVerified] false, so the gate still holds.
     */
    fun substantiveOk(lastVerified: Boolean?, mutating: Int, verified: Int, partial: Int): Boolean {
        val assessed = mutating - partial
        val ratio = if (assessed <= 0) 1.0 else verified.toDouble() / assessed
        return lastVerified == true && (assessed < 3 || ratio >= 0.5)
    }

    /** The label a claim is looking for (element_present / new_element), or null. */
    fun soughtLabel(e: Expectation?): String? = when (e) {
        is Expectation.ElementPresent -> e.label
        is Expectation.NewElement -> e.label
        else -> null
    }

    /**
     * Goal-directed scroll: after a `scroll` whose claim names an element that still isn't there, keep scrolling the
     * same way while the list moves and the app stays the same (bounded by `executor.scroll.autoRepeat`). One
     * planning step instead of one per screenful — the model's own claim says what it is scrolling toward.
     */
    fun keepScrolling(tool: String, success: Boolean, verified: Boolean, sought: String?, sameApp: Boolean, moved: Boolean,
                      repeats: Int, maxRepeats: Int = AppConfig.int("executor.scroll.autoRepeat", 4).coerceIn(0, 8)): Boolean =
        tool == "scroll" && success && !verified && sought != null && sameApp && moved && repeats < maxRepeats

    /**
     * An app that just came to the front often draws its list a moment later (seen on the A20e: Settings showed 4
     * actionable elements right after it opened, so the model spent a whole step on read_state). True when the step
     * before switched apps and the new screen is still that sparse — the engine waits for it to settle and looks again.
     */
    fun stillLoading(appSwitched: Boolean, actionable: Int, maxSparse: Int = AppConfig.int("executor.launch.sparseActionable", 5)): Boolean =
        appSwitched && actionable <= maxSparse

    /** Normalized labels of a snapshot's tree elements (vision OCR is too noisy to count as "new content"). */
    internal fun labelsOf(snap: UiSnapshot?): List<String> =
        snap?.elements.orEmpty().asSequence().filter { it.source == ElementSource.TREE }
            .mapNotNull { it.label?.lowercase()?.replace(Regex("\\s+"), " ")?.trim()?.take(80)?.takeIf { l -> l.length >= 2 } }
            .toList()
}

/**
 * The task's memory of every screen it has seen, per app, so a step can tell whether it revealed anything new
 * ([Progress.Verdict.PARTIAL]). Novelty — not "the screen changed" — is the signal: a list scrolled back up, a Back to
 * a menu already visited, or a flip to another app all change the screen without moving the task forward.
 */
class Exploration {
    private val seen = HashSet<String>()

    /** Remember everything on [snap]. */
    fun note(snap: UiSnapshot?) {
        val pkg = snap?.appPackage ?: return
        Progress.labelsOf(snap).forEach { seen.add("$pkg|$it") }
    }

    /** How many labels on [snap] this task has never seen in the same app. Call BEFORE [note]-ing it. */
    fun novelty(snap: UiSnapshot?): Int {
        val pkg = snap?.appPackage ?: return 0
        return Progress.labelsOf(snap).distinct().count { "$pkg|$it" !in seen }
    }
}

/**
 * Goal-level futility: the model keeps expecting the SAME element and it never appears (seen: "turn on zorblax mode"
 * looked for 'Zorblax mode' 10+ times until the time budget ran out). A plain miss weighs [MISS]; a miss that still
 * revealed new content ([Progress.Verdict.PARTIAL] — scrolling down a long list toward it) weighs half, so walking a
 * long Settings list isn't read as "it doesn't exist" while it is still turning up rows. A label that DOES appear —
 * even a step later than claimed — is cleared: it exists.
 */
class Futility(
    private val nudgeAt: Int = AppConfig.int("executor.futility.nudge", 3).coerceAtLeast(2),
    private val stopAt: Int = AppConfig.int("executor.futility.stop", 5).coerceAtLeast(3)
) {
    enum class Signal { NONE, NUDGE, STOP }

    private val weight = HashMap<String, Int>()
    private val nudged = HashSet<String>()

    /** [label] was claimed and didn't appear. */
    fun miss(label: String, partial: Boolean): Signal {
        val key = norm(label)
        if (key.isEmpty()) return Signal.NONE
        val w = (weight[key] ?: 0) + if (partial) HALF else MISS
        weight[key] = w
        return when {
            w >= stopAt * MISS -> Signal.STOP
            w >= nudgeAt * MISS && nudged.add(key) -> Signal.NUDGE
            else -> Signal.NONE
        }
    }

    /** Anything on [snap] that was being looked for has been found — forget its misses. Only an item of its OWN
     *  counts: not text the model typed into a field, and not a page echoing it back ("No results for 'Zorblax
     *  mode'"), or a search for something that doesn't exist would clear its own futility count. */
    fun observed(snap: UiSnapshot?) {
        if (weight.isEmpty() || snap == null) return
        val labels = snap.elements.asSequence().filter { it.source == ElementSource.TREE && !it.editable }
            .mapNotNull { it.label?.let(::norm) }.toList()
        weight.keys.filter { k -> labels.any { l -> ownItem(l, k) } }.forEach { weight.remove(it); nudged.remove(it) }
    }

    /** [label] IS the item [key] names: equal, starts with it ("Android version 11"), or is mostly it. */
    internal fun ownItem(label: String, key: String): Boolean =
        label == key || label.startsWith("$key ") || label.startsWith("$key,") ||
            (label.contains(key) && key.length * 2 >= label.length)

    private fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()

    private companion object { const val MISS = 2; const val HALF = 1 }
}
