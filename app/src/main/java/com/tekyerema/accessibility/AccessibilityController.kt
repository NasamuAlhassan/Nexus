package com.tekyerema.accessibility

import android.graphics.Bitmap

/**
 * The narrow contract the agent uses to perceive and act through the AccessibilityService.
 * Implemented by [TekyeremaAccessibilityService]; null when the service is not enabled.
 *
 * Every method here is a MECHANISM the primitives compose — none is a task. Node-based methods
 * take a per-snapshot element id; gesture methods take screen coordinates. Primitives try the
 * node action first and fall back to a gesture (plan §7.2).
 */
interface AccessibilityController {
    val ready: Boolean

    /** Tree-only snapshot. Vision is layered on top by [Perception], not here. */
    suspend fun buildSnapshot(): UiSnapshot

    /** On-demand screenshot (API 30+, config-gated). Null when unavailable — secure window,
     *  older API, rate limit, or the platform refused. Never called continuously. */
    suspend fun captureScreenshot(): Bitmap?

    // ---- node actions (level 1) ----
    suspend fun clickElement(id: Int): Boolean
    suspend fun longClickElement(id: Int): Boolean
    /** @param submit also fire the field's IME action (Enter/Search/Send) via ACTION_IME_ENTER. */
    suspend fun setText(id: Int, text: String, submit: Boolean = false): Boolean
    suspend fun scrollElement(id: Int, direction: String): Boolean
    suspend fun focusElement(id: Int): Boolean
    /** Set a range control (SeekBar/Slider) to [value]: ACTION_SET_PROGRESS, else stepped scroll. */
    suspend fun setRangeValue(id: Int, value: Float): Boolean
    /** Bounds of a node from the current snapshot, if still valid. */
    fun boundsOf(id: Int): Box?
    /** Why the last captureScreenshot() returned null, if it did (e.g. "secure window (FLAG_SECURE)"). */
    fun lastScreenshotFailure(): String?

    // ---- gestures (levels 2–3) ----
    suspend fun tap(x: Int, y: Int, durationMs: Long = 60L): Boolean
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300L): Boolean
    /** Continuous touch following [points] (≥2), split across chained strokes. */
    suspend fun drag(points: List<Pair<Int, Int>>, durationMs: Long = 800L): Boolean
    /** Two-finger pinch centred at (cx,cy): fingers move between [startGap] and [endGap] px apart
     *  simultaneously (endGap>startGap = zoom in). Two concurrent strokes in one gesture. */
    suspend fun pinch(cx: Int, cy: Int, startGap: Int, endGap: Int, durationMs: Long = 400L): Boolean

    // ---- system ----
    /** "back" | "home" | "recents" */
    fun globalAction(action: String): Boolean
    /** "up" | "down" | "mute" — AudioManager, the generic hardware-key equivalent. */
    fun adjustVolume(direction: String): Boolean
    /** The package of the ACTIVITY in front (event-tracked, cheap) — "is Chrome open?". */
    fun foregroundPackage(): String?
    /** The package of the window that has focus right now — which can be the notification shade / Quick Settings
     *  (systemui) OVER an activity. What a UiSnapshot reflects, so it's what the stale-screen check compares.
     *  May do a binder round-trip: call off the main thread. */
    fun activeWindowPackage(): String? = foregroundPackage()
    /** The last real app that owned the screen BEFORE Tɛkyerɛma's own UI came to front — so a handoff can
     *  return to the app the user was actually looking at (e.g. Messages) instead of slamming HOME. Null if
     *  we've only ever seen our own UI or the launcher. */
    fun priorForegroundPackage(): String?
    fun screenSize(): Pair<Int, Int>

    /** Suspends until the UI stops changing (or timeout). */
    suspend fun waitForSettle(timeoutMs: Long = 2500L)
}
