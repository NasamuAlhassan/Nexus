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
    /** Set the text of the box that has INPUT focus — the one the keyboard types into — even when the app hides it behind
     *  drawn cells (a PIN row). False when nothing has input focus or it takes no text. */
    suspend fun setFocusedText(text: String, maxExisting: Int = Int.MAX_VALUE, refuse: Regex? = null): Boolean = false
    /** Cheap: does the screen in front show any of [words] (case-insensitive, in text or description)? True when it can't tell. */
    fun screenMentions(words: List<String>): Boolean = true
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
    /** How many windows the service can see right now (-1 = not a live service). A bound service that sees none reads every
     *  screen as empty ("active root null; windows= → none", emulator 13 October 2026) — the ping shows it. */
    fun windowCount(): Int = -1
    /**
     * Close the notification shade / Quick Settings COMPLETELY. One Back collapses only one level on some shells:
     * Samsung One UI (A20e, API 30) went from Quick Settings to the notification list and stayed there, so every
     * "set brightness" / "turn on do not disturb" left the user inside the shade (audit 2026-10-01: S10, S11, S25, S26).
     * Back is pressed only while System UI holds the focused window — never once the app underneath is in front, where
     * it would navigate back inside that app. True when no shade is in front any more.
     */
    suspend fun closeShade(): Boolean {
        repeat(3) {
            if (activeWindowPackage() != SYSTEM_UI) return true
            if (!globalAction("back")) return false
            kotlinx.coroutines.delay(300)
            waitForSettle(700)
        }
        return activeWindowPackage() != SYSTEM_UI
    }

    /** The last real app that owned the screen BEFORE Tɛkyerɛma's own UI came to front — so a handoff can
     *  return to the app the user was actually looking at (e.g. Messages) instead of slamming HOME. Null if
     *  we've only ever seen our own UI or the launcher. */
    fun priorForegroundPackage(): String?
    fun screenSize(): Pair<Int, Int>

    /** J-08: the package of ANOTHER app's overlay window (drawn over apps) covering screen point (x,y), or null.
     *  A coordinate gesture there would land on that overlay instead of the app the agent observed. */
    fun foreignOverlayAt(x: Int, y: Int): String? = null

    /** K-09: screen magnification — "in" | "out" | "reset". Returns the scale now in effect, or null if unavailable. */
    fun magnify(action: String): Float? = null

    /** G-01 / G-02 / G-03: a semantic node action on element [id] — "select" | "expand" | "collapse" | "dismiss" |
     *  "copy" | "paste" | "cut" | "show_on_screen" | "page_up" | "page_down" | "page_left" | "page_right" |
     *  "context_click" | "custom:<label>" (an action the app itself defines, e.g. "Archive", "Mark as read"). */
    suspend fun nodeAction(id: Int, action: String): Boolean = false

    /** Suspends until the UI stops changing (or timeout). */
    suspend fun waitForSettle(timeoutMs: Long = 2500L)

    /** Uptime (ms) of the last accessibility event — the mark an action's settle waits to see passed. */
    fun lastEventUptime(): Long = 0L

    /**
     * G-10: event-driven settle after an action — wait for the screen to REACT (an event after [sinceUptime]), then for
     * it to go quiet. Returns when nothing reacted within [reactMs] (a no-op, or a change the events don't report),
     * so a dead tap costs [reactMs], not a fixed sleep. Default: the plain quiet wait.
     */
    suspend fun waitForChange(sinceUptime: Long, reactMs: Long, timeoutMs: Long) = waitForSettle(timeoutMs)

    companion object {
        /** The package that owns the notification shade, Quick Settings and the other system panels. */
        const val SYSTEM_UI = "com.android.systemui"
    }
}
