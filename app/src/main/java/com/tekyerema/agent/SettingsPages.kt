package com.tekyerema.agent

/**
 * N-09 (3) — the Settings pages Android opens DIRECTLY (13 October 2026). "Open settings and go to the battery page"
 * was two actions — launch Settings, then find and tap "Battery" (a scroll down a long list on most phones) — each
 * with its own wait and verification: 20 s of the agent's time on the emulator, more on the A20e. Android has had a
 * deep link for these pages since its first versions, so the local plan goes straight there: one intent, one check
 * that the page's title is in front, no model in the loop. Pure; the intents live in OpenSettingTool.
 */
object SettingsPages {
    /**
     * [target] is OpenSettingTool's name for the page; [title] a fragment the opened page's title must contain — one that
     * survives the phones' wordings ("About phone", "About device", "About emulated device" all contain "About";
     * "Sound & vibration" contains "Sound"); [spoken] how the page is named back to the user.
     */
    data class Page(val target: String, val title: String, val spoken: String)

    private val PAGES: List<Pair<Regex, Page>> = listOf(
        Regex("battery(?: (?:usage|use|health|life|info|information|status))?") to Page("battery_usage", "Battery", "Battery"),
        Regex("(?:battery saver|power sav(?:ing|er)(?: mode)?)") to Page("battery", "Battery Saver", "Battery Saver"),
        Regex("(?:about(?: (?:the |this |my )?(?:phone|device|emulated device))?|(?:phone|device) (?:info|information|status))") to
            Page("about_phone", "About", "About phone"),
        Regex("(?:do not disturb|don'?t disturb|dnd)(?: mode)?") to Page("dnd_page", "Do Not Disturb", "Do Not Disturb"),
        Regex("(?:display|screen|brightness)") to Page("display", "Display", "Display"),
        Regex("(?:sounds?|sounds? and vibration|volume)") to Page("sound", "Sound", "Sound"),
        Regex("storage") to Page("storage", "Storage", "Storage"),
        Regex("(?:apps|applications|all apps|app list)") to Page("apps", "Apps", "Apps"),
        Regex("accessibility") to Page("accessibility", "Accessibility", "Accessibility"),
        Regex("(?:security|security and privacy|screen lock)") to Page("security", "Security", "Security"),
        Regex("privacy") to Page("privacy", "Privacy", "Privacy"),
        Regex("(?:languages?|languages? and input|language and keyboard)") to Page("language", "Language", "Languages"),
        Regex("(?:date|time|date and time|time and date)") to Page("date", "Date", "Date and time"),
        Regex("bluetooth") to Page("bluetooth", "Bluetooth", "Bluetooth"),
        Regex("notifications?") to Page("notifications", "Notification", "Notifications")
    )

    /** The page a navigation label ("battery", "the About phone page", "Do Not Disturb") names, or null when it is not one Android opens directly. */
    fun find(label: String): Page? {
        var l = label.trim().lowercase().replace("&", " and ").replace(Regex("\\s+"), " ")
        l = l.replace(Regex("^(?:the |my |its )+"), "")
        l = l.replace(Regex(" (?:settings?|page|screen|section|menu|tab|options?)$"), "").replace(Regex("^(?:the |my )+"), "").trim()
        if (l.isEmpty()) return null
        return PAGES.firstOrNull { (re, _) -> re.matches(l) }?.second
    }
}
