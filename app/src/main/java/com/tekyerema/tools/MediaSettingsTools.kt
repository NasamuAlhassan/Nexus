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
    override val description = "Open a system setting screen (wifi, mobile data, bluetooth, airplane, location, sound, display). " +
        "Pass state=on or state=off to have its switch set to that position; without state the screen is only opened."
    override val scope = CapabilityScope.DEVICE_CONTROL
    override val riskTier = RiskTier.TIER1
    override val isAccelerator = true
    override val params = listOf(
        ToolParam("target", "string", "Which setting: wifi | mobile_data | bluetooth | airplane | location | nfc | volume | sound | " +
            "display | battery | accessibility | apps | storage | date | language | security | dnd | settings."),
        ToolParam("state", "string", "The position to leave the switch in. Omit to only open the screen.", required = false,
            enumValues = listOf("on", "off"))
    )

    private fun norm(target: String) = target.trim().lowercase().replace(" ", "_")

    /** Map a target to (intent action, spoken label). Panel actions land on a togglable surface where they exist.
     *  E-11: `os.settings.map` in config can add targets ({"target": {"action": "...", "label": "..."}}). */
    private fun actionFor(target: String): Pair<String, String>? {
        com.tekyerema.core.config.AppConfig.obj("os.settings.map.${norm(target)}")?.let { o ->
            val a = o.optString("action"); if (a.isNotBlank()) return a to o.optString("label", target)
        }
        return when (norm(target)) {
            "wifi", "wi-fi", "wi_fi" -> Settings.Panel.ACTION_WIFI to "Wi-Fi"
            "mobile_data", "data", "internet", "cellular" -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY to "mobile data"
            "airplane", "flight", "aeroplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS to "airplane mode"
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS to "Bluetooth"
            "location", "gps" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS to "location"
            "nfc" -> Settings.Panel.ACTION_NFC to "NFC"                                   // E-10 panel
            "volume", "volume_panel" -> Settings.Panel.ACTION_VOLUME to "volume"          // E-10 panel
            "sound", "silent", "ringtone" -> Settings.ACTION_SOUND_SETTINGS to "sound"
            "display", "brightness", "screen" -> Settings.ACTION_DISPLAY_SETTINGS to "display"
            "battery", "battery_saver", "power_saving" -> Settings.ACTION_BATTERY_SAVER_SETTINGS to "battery saver"
            "accessibility", "talkback" -> Settings.ACTION_ACCESSIBILITY_SETTINGS to "accessibility"
            "apps", "applications" -> Settings.ACTION_APPLICATION_SETTINGS to "apps"
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS to "storage"
            "date", "time", "date_time" -> Settings.ACTION_DATE_SETTINGS to "date and time"
            "language", "languages" -> Settings.ACTION_LOCALE_SETTINGS to "language"
            "security", "lock", "screen_lock" -> Settings.ACTION_SECURITY_SETTINGS to "security"
            "dnd", "do_not_disturb" -> Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS to "Do Not Disturb"
            "settings" -> Settings.ACTION_SETTINGS to "Settings"
            else -> null
        }
    }

    /** Whole-word label patterns for each target's switch ("wi" alone matched "Switch", "Window"…). */
    internal fun labelPattern(target: String): Regex? = when (norm(target)) {
        "wifi", "wi-fi", "wi_fi" -> Regex("(?i)\\b(wi-?fi|wlan)\\b")
        "mobile_data", "data", "internet", "cellular" -> Regex("(?i)\\b(mobile data|data|cellular data)\\b")
        "airplane", "flight", "aeroplane" -> Regex("(?i)\\b(airplane|aeroplane|flight) mode\\b|\\bairplane\\b")
        "bluetooth" -> Regex("(?i)\\bbluetooth\\b")
        "location", "gps" -> Regex("(?i)\\b(location|use location|gps)\\b")
        else -> null
    }

    /** E-10: the switch whose label names the target — never "the first checkable thing on the screen". */
    internal fun findToggle(snap: UiSnapshot, target: String): com.tekyerema.accessibility.UiElement? {
        val re = labelPattern(target) ?: return null
        return snap.elements.firstOrNull { it.checkable && it.label?.let(re::containsMatchIn) == true }
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val target = args.str("target")?.trim().orEmpty()
        val want: Boolean? = when (args.str("state")?.trim()?.lowercase()) { "on" -> true; "off" -> false; else -> null }
        val (action, label) = actionFor(target)
            ?: return ToolResult(false, "I don't know which setting '$target' is.", recoverable = true)
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(ctx.appContext.packageManager) == null)
            return ToolResult(false, "This phone doesn't expose a $label setting screen I can open.", recoverable = true)
        try { ctx.appContext.startActivity(intent) } catch (t: Throwable) {
            return ToolResult(false, "Couldn't open $label settings: ${t.message}", recoverable = true)
        }
        // §P2.2 — the API forbids an app from flipping these directly, but our ACCESSIBILITY service can find and
        // tap the switch like a person would. It only taps when a direction was asked for AND the switch isn't
        // already there, then VERIFIES the checked state — reporting honestly if it didn't change.
        val ctrl = ctx.controller
            ?: return ToolResult(true, "Opened $label settings — screen control is off, so please use the switch there.",
                data = JSONObject().put("confirmed", false), channel = "intent")
        ctrl.waitForSettle(1500)
        val before = ctrl.buildSnapshot()
        val toggle = findToggle(before, target)
        if (want == null) {
            val now = toggle?.checked?.let { if (it) " It's on." else " It's off." } ?: ""
            return ToolResult(true, "Opened $label settings.$now", data = JSONObject().put("confirmed", true), channel = "intent")
        }
        if (toggle == null)
            return ToolResult(true, "Opened $label settings — I couldn't find its switch, so please set it there.",
                data = JSONObject().put("confirmed", false), channel = "intent", recoverable = true)
        val word = if (want) "on" else "off"
        if (toggle.checked == want)
            return ToolResult(true, "$label is already $word.", data = JSONObject().put("confirmed", true), channel = "a11y")
        val tapped = ctrl.clickElement(toggle.id)
        ctrl.waitForSettle(900)
        val after = ctrl.buildSnapshot()
        val nowChecked = after.elements.firstOrNull { it.checkable && it.label == toggle.label }?.checked
            ?: findToggle(after, target)?.checked
        return if (tapped && nowChecked == want)
            ToolResult(true, "Turned $label $word.", data = JSONObject().put("confirmed", true), channel = "a11y")
        else
            ToolResult(true, "Opened $label settings — I tried to turn it $word but couldn't confirm it, so please check it.",
                data = JSONObject().put("confirmed", false), channel = "a11y", recoverable = true)
    }

    override suspend fun verify(
        args: JSONObject, ctx: ToolContext, before: UiSnapshot?, after: UiSnapshot?, result: ToolResult
    ): VerificationResult = VerificationResult(result.success && result.data?.optBoolean("confirmed", false) == true, result.message)
}
