package com.tekyerema.tools

import android.content.Intent
import android.provider.MediaStore
import android.provider.Settings
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.RiskTier
import com.tekyerema.verifier.VerificationResult
import org.json.JSONObject

/**
 * §A.2 play_music — launch the phone's music playback. No-arg = "play my music" (generic play intent the
 * default media app answers); with a `query` = `ACTION_MEDIA_PLAY_FROM_SEARCH` so the player searches +
 * plays it. Single intent, reversible → TIER1 accelerator. Honest: we fire the intent; if no app handles
 * it we say so (we can't verify audio actually started from outside the player).
 */
object PlayMusicTool : Tool {
    override val name = "play_music"
    override val description = "Play music on the phone. Optional 'query' names a song/artist; no query plays the user's music."
    override val scope = CapabilityScope.DEVICE_CONTROL
    override val riskTier = RiskTier.TIER1
    override val isAccelerator = true
    override val params = listOf(ToolParam("query", "string", "Optional song/artist/playlist to play.", required = false))

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val query = args.str("query")?.trim().orEmpty()
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (query.isNotEmpty()) {
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                putExtra(android.app.SearchManager.QUERY, query)
                putExtra(MediaStore.EXTRA_MEDIA_TITLE, query)
            }
        }
        if (intent.resolveActivity(ctx.appContext.packageManager) == null)
            return ToolResult(false, "No music app on this phone can handle a play request.", recoverable = true)
        return try {
            ctx.appContext.startActivity(intent)
            ToolResult(true, if (query.isEmpty()) "Asked your music app to play." else "Asked your music app to play \"$query\".", channel = "intent")
        } catch (t: Throwable) {
            ToolResult(false, "Couldn't start music: ${t.message}", recoverable = true)
        }
    }
}

/**
 * §A.2 open_setting — Android 11 does NOT let an ordinary app flip Wi-Fi / mobile data / Bluetooth /
 * airplane mode directly (system permission), so we do the honest thing: OPEN the relevant settings screen
 * (a Settings Panel where the platform offers one) and tell the user to toggle it there. We never claim to
 * have flipped a switch we cannot flip.
 */
object OpenSettingTool : Tool {
    override val name = "open_setting"
    override val description = "Open a system setting screen so the user can toggle it (wifi, mobile data, bluetooth, airplane, location, sound, display)."
    override val scope = CapabilityScope.DEVICE_CONTROL
    override val riskTier = RiskTier.TIER1
    override val isAccelerator = true
    override val params = listOf(ToolParam("target", "string", "Which setting: wifi | mobile_data | bluetooth | airplane | location | sound | display | settings."))

    /** Map a target to (intent action, spoken label). Panel actions land on a togglable surface where they exist. */
    private fun actionFor(target: String): Pair<String, String>? = when (target.lowercase().replace(" ", "_")) {
        "wifi", "wi-fi", "wi_fi" -> Settings.Panel.ACTION_WIFI to "Wi-Fi"
        "mobile_data", "data", "internet", "cellular" -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY to "mobile data"
        "airplane", "flight", "aeroplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS to "airplane mode"
        "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS to "Bluetooth"
        "location", "gps" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS to "location"
        "sound", "volume", "silent" -> Settings.ACTION_SOUND_SETTINGS to "sound"
        "display", "brightness", "screen" -> Settings.ACTION_DISPLAY_SETTINGS to "display"
        "settings" -> Settings.ACTION_SETTINGS to "Settings"
        else -> null
    }

    /** Keyword a toggle's label should contain for each target. */
    private fun keyword(target: String): String = when (target.lowercase().replace(" ", "_")) {
        "wifi", "wi-fi", "wi_fi" -> "wi"
        "mobile_data", "data", "internet", "cellular" -> "data"
        "airplane", "flight", "aeroplane" -> "airplane"
        "bluetooth" -> "bluetooth"
        "location", "gps" -> "location"
        "display", "brightness", "screen" -> "brightness"
        else -> target.lowercase()
    }

    /** Find the switch to flip: a checkable element whose label matches the target, else the first checkable. */
    private fun findToggle(snap: UiSnapshot, target: String): com.tekyerema.accessibility.UiElement? {
        val kw = keyword(target)
        return snap.elements.firstOrNull { it.checkable && (it.label?.lowercase()?.contains(kw) == true) }
            ?: snap.elements.firstOrNull { it.checkable && it.clickable }
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val target = args.str("target")?.trim().orEmpty()
        val (action, label) = actionFor(target)
            ?: return ToolResult(false, "I don't know which setting '$target' is.", recoverable = true)
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(ctx.appContext.packageManager) == null)
            return ToolResult(false, "This phone doesn't expose a $label setting screen I can open.", recoverable = true)
        try { ctx.appContext.startActivity(intent) } catch (t: Throwable) {
            return ToolResult(false, "Couldn't open $label settings: ${t.message}", recoverable = true)
        }
        // §P2.2 — the API forbids an app from flipping these directly, but our ACCESSIBILITY service can find
        // and tap the switch like a person would. Open the panel, locate the toggle, tap it, and VERIFY the
        // checked state actually changed — reporting honestly if it didn't (never claim a flip we can't see).
        val ctrl = ctx.controller
            ?: return ToolResult(true, "Opened $label settings — turn it on or off there (screen control is off, so I can't do it for you).", channel = "intent")
        ctrl.waitForSettle(1500)
        val before = ctrl.buildSnapshot()
        val toggle = findToggle(before, target)
            ?: return ToolResult(true, "Opened $label settings — I couldn't find the switch to flip, so please toggle it there.", channel = "intent", recoverable = true)
        val wasChecked = toggle.checked
        val tapped = ctrl.clickElement(toggle.id)
        ctrl.waitForSettle(900)
        val after = ctrl.buildSnapshot()
        val nowChecked = after.elements.firstOrNull { it.checkable && it.label == toggle.label }?.checked
            ?: findToggle(after, target)?.checked
        return if (tapped && nowChecked != null && nowChecked != wasChecked)
            ToolResult(true, "Turned $label ${if (nowChecked) "on" else "off"}.", channel = "a11y")
        else
            ToolResult(true, "Opened $label settings — I tapped the switch but couldn't confirm it changed, so please check it.", channel = "a11y", recoverable = true)
    }

    override suspend fun verify(
        args: JSONObject, ctx: ToolContext, before: UiSnapshot?, after: UiSnapshot?, result: ToolResult
    ): VerificationResult = VerificationResult(result.success, "opened a settings screen; the toggle is the user's to flip")
}
