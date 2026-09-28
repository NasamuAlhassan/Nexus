package com.tekyerema.policy

import com.tekyerema.accessibility.UiSnapshot

/**
 * J-11 / Secure Input — which screens the agent may read and act on, decided in one place (pure, unit-tested).
 *
 *  - A credential (PIN / password / one-time code) or robot-check screen is never read: not the tree, not OCR.
 *  - An app on the user's deny list is never shown to the model and never operated. "Shown" includes the tools that
 *    RETURN SCREEN TEXT as their result ([SCREEN_READERS]), not only the grounded primitives that act on an element:
 *    read_state and read_all used to hand a denied app's whole screen to the cloud model through the step history,
 *    because the gate only looked at `tool.grounded`.
 *  - A guide-only app is read and explained, never acted on.
 */
object ScreenPrivacy {
    /** Tools whose result carries what is on the screen. */
    val SCREEN_READERS = setOf("read_state", "read_screen", "read_all", "scroll_to_find", "wait_for_element")

    const val DENIED = "this app is on the user's deny list"
    const val GUIDE_ONLY = "this app is guide-only — tell the user what to tap instead of tapping it"

    fun isPrivate(s: UiSnapshot): Boolean = TargetSemantics.isCredentialScreen(s) || TargetSemantics.isHumanCheckScreen(s)

    fun isDenied(pkg: String?, deny: Set<String> = PolicyConfig.denyApps()): Boolean = pkg != null && pkg in deny

    /** Why [tool] may not run while [fgPkg] is in front, or null when it may. */
    fun appRule(tool: String, grounded: Boolean, mutating: Boolean, fgPkg: String?, deny: Set<String>, guideOnly: Set<String>): String? = when {
        fgPkg == null -> null
        fgPkg in deny && (grounded || tool in SCREEN_READERS) -> DENIED
        fgPkg in guideOnly && grounded && mutating -> GUIDE_ONLY
        else -> null
    }

    /** A denied app's screen as the model may see it: which app is in front, and nothing on it (toasts included). */
    fun blank(s: UiSnapshot): UiSnapshot = s.copy(elements = emptyList(), screenTitle = null, announcements = emptyList())
}
