package com.tekyerema.mcp

import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.policy.TargetSemantics
import org.json.JSONObject

/**
 * describe_screen for a paired computer (pure, unit-tested). The blindness boundaries are enforced HERE, on the phone,
 * never left to the host (research note §1.4 item 5):
 *  - while the user enters a PIN, password or one-time code (Secure Input Mode), nothing is read at all;
 *  - a credential or robot-check screen, an app on the user's deny list (J-11), or a window that asks not to be
 *    captured (FLAG_SECURE, e.g. a banking app) is withheld, with the reason;
 *  - otherwise the same sanitised listing the planner sees ([UiSnapshot.toCompactJson]: hidden-channel characters
 *    stripped, one-time codes hidden, text nobody can see on screen withheld) — from the accessibility tree only, never
 *    a screenshot.
 */
object ScreenView {
    const val SECURE_STEP = "A PIN, password or code is being entered on the phone, so the screen isn't shown."
    const val PRIVATE_SCREEN = "The screen is a PIN, password, code or robot-check screen, so it isn't shown."
    const val DENIED_APP = "The app on screen is on the user's deny list, so it isn't shown."
    const val NO_CAPTURE = "The app on screen asks not to be captured (a secure window), so it isn't shown."
    const val UNAVAILABLE = "The screen can't be read right now (Nexus's accessibility service isn't connected)."

    fun report(snap: UiSnapshot?, secureStep: Boolean, denyApps: Set<String>): ScreenReport {
        val why = when {
            secureStep -> SECURE_STEP
            snap == null || (snap.appPackage == null && snap.elements.isEmpty()) -> UNAVAILABLE
            snap.appPackage != null && snap.appPackage in denyApps -> DENIED_APP
            snap.secureWindow -> NO_CAPTURE
            TargetSemantics.isCredentialScreen(snap) || TargetSemantics.isHumanCheckScreen(snap) -> PRIVATE_SCREEN
            else -> null
        }
        if (why != null || snap == null) return ScreenReport(why ?: UNAVAILABLE, JSONObject(), blinded = why ?: UNAVAILABLE)
        val json = snap.toCompactJson()
        val arr = json.optJSONArray("elements")
        val lines = ArrayList<String>()
        lines += "App: ${json.optString("appName").ifBlank { json.optString("app") }}" +
            (json.optString("screen").takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")
        if (arr != null) for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val label = e.optString("label").takeIf { it.isNotBlank() }?.let { "“$it”" } ?: "(no label)"
            val acts = e.optJSONArray("actions")?.let { a -> (0 until a.length()).joinToString("/") { a.optString(it) } }.orEmpty()
            lines += "- ${e.optString("role")} $label" + (if (acts.isNotEmpty()) " [$acts]" else "")
        }
        json.optString("note").takeIf { it.isNotBlank() }?.let { lines += it }
        return ScreenReport(lines.joinToString("\n"), json)
    }
}
