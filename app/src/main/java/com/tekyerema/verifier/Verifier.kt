package com.tekyerema.verifier

import com.tekyerema.accessibility.AccessibilityController
import com.tekyerema.accessibility.UiSnapshot
import kotlinx.coroutines.delay

/** Reusable verification predicates. Each tool composes these in its own verify(). */
object Verify {
    fun foregroundPackageContains(after: UiSnapshot?, token: String): Boolean =
        after?.appPackage?.contains(token, ignoreCase = true) == true

    /**
     * Polls the live foreground package for a short window before giving up — a launched
     * app/screen can take a moment to become foreground, so a single instant check right after
     * dispatch is a race condition. Shared by any tool that opens/switches to something
     * identifiable by package (open_app, open_settings, future app-launch tools), so the fix
     * lives in one place instead of being re-implemented per tool.
     */
    suspend fun pollForegroundContains(
        controller: AccessibilityController?,
        token: String,
        attempts: Int = 6,
        intervalMs: Long = 400L
    ): Pair<Boolean, String?> {
        var fg = controller?.foregroundPackage()
        repeat(attempts) {
            if (fg?.contains(token, ignoreCase = true) == true) return true to fg
            delay(intervalMs)
            fg = controller?.foregroundPackage()
        }
        return (fg?.contains(token, ignoreCase = true) == true) to fg
    }

    /**
     * Did an app launch take effect? True as soon as [pkg] is in front — OR once a DIFFERENT screen than [prevFg] has
     * held the foreground for [stablePolls] consecutive polls: launching resumes the app's TASK, whose top screen may
     * belong to a helper package (Settings' search page is com.google.android.settings.intelligence; a sign-in step is
     * GMS). Seen on-device: "open settings" landed on the search page and the package-equality check called it failed.
     * A launch that did nothing leaves [prevFg] in front and still fails.
     */
    suspend fun pollLaunched(
        controller: AccessibilityController?, pkg: String, prevFg: String?,
        attempts: Int = 24, intervalMs: Long = 250L, stablePolls: Int = 3
    ): Pair<Boolean, String?> {
        var fg: String? = null
        var sameOther = 0
        repeat(attempts) {
            fg = controller?.foregroundPackage()
            val f = fg
            if (f != null && f.contains(pkg, ignoreCase = true)) return true to f
            // Only with a KNOWN prior screen can "something else came to front" be evidence of our launch.
            if (f != null && prevFg != null && f != prevFg) { if (++sameOther >= stablePolls) return true to f } else sameOther = 0
            delay(intervalMs)
        }
        return false to fg
    }

    fun screenChanged(before: UiSnapshot?, after: UiSnapshot?): Boolean {
        if (before == null || after == null) return false
        return before.signature() != after.signature()
    }

    fun textPresent(after: UiSnapshot?, text: String): Boolean {
        if (after == null || text.isBlank()) return false
        return after.elements.any { it.label?.contains(text, ignoreCase = true) == true }
    }

    /**
     * Coarse, cheap check for whether the screen an action is about to act on is still
     * "meaningfully the same" screen the model reasoned about when it chose that action.
     *
     * Why this exists: AgentEngine takes a fresh `before` snapshot immediately before every
     * planning call, and the model's decision is based on exactly that snapshot. Normally the
     * live screen at execution time still matches it — but the LLM call itself takes seconds,
     * during which something outside the agent's control (a lock screen, an incoming call UI, a
     * system dialog, unexpected focus) could change what's actually in front. If that happens,
     * a coordinate/label-based tap could silently act on the wrong screen. This is a STATE-class
     * risk, not a GROUNDING one — the element search itself is fine, it's just searching the
     * wrong haystack.
     *
     * Deliberately NOT "package changed = reject": legitimate cross-app steps (open_app, then
     * act in the new app) are a *different* planning step with its own fresh `before` snapshot
     * that already reflects the new app — so by the time that step's own tool executes, expected
     * and live package already agree. This check only ever fires within a single step, comparing
     * what that one step's decision was based on against what's live microseconds before it acts.
     *
     * Deliberately coarse (foreground package only, not a full content diff): a full signature
     * comparison would false-positive on ordinary dynamic content (a clock ticking, a badge
     * count changing, a list scrolling on its own) inside the *same* screen — exactly the
     * "excessively fragile" failure mode this is meant to avoid. Package identity catches "this
     * is a different app/screen entirely," which is the actual risk (a tap landing in the wrong
     * app), without being sensitive to ordinary in-screen change.
     */
    fun screenStillCurrent(observed: UiSnapshot?, livePackage: String?): Boolean {
        val expectedPkg = observed?.appPackage ?: return true // nothing meaningful to compare
        if (livePackage == null) return true // can't tell — don't block on missing data
        return expectedPkg == livePackage
    }
}
