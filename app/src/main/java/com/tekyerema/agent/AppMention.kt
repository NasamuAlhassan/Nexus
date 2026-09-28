package com.tekyerema.agent

/**
 * Finds the app a compound request is ABOUT — "search chrome for jollof rice", "open WhatsApp and message Kofi",
 * "turn on dark mode in settings", "play highlife on YouTube" — so the router can launch it locally as step one
 * instead of spending a cloud round-trip (or two: seen on-device, the model tapped the Chrome icon, the check
 * missed the cold start, and it then called open_app anyway).
 *
 * Returns candidate PHRASES only, most explicit pattern first. Whether a phrase is really an installed app is
 * decided by the caller with a STRICT resolver (no fuzzy matching), so "turn on do not disturb" or "make the
 * text on my phone bigger" yield phrases that simply resolve to nothing. Pure, English-only, unit-tested.
 */
object AppMention {
    private const val NAME = "([a-z0-9][\\w'.&+-]*(?:\\s+[a-z0-9][\\w'.&+-]*){0,2}?)"
    private val PATTERNS = listOf(
        // "open whatsapp and …", "launch the youtube app then …"
        Regex("^(?:please\\s+)?(?:open|launch|start|go\\s+to)\\s+(?:the\\s+|my\\s+)?$NAME(?:\\s+app)?\\s*(?:,|\\band\\b|\\bthen\\b)"),
        // "search chrome for …", "look up youtube for …"
        Regex("\\b(?:search|look\\s+up|check)\\s+(?:the\\s+|my\\s+)?$NAME(?:\\s+app)?\\s+for\\b"),
        // "… in settings", "… on youtube", "… using chrome" — at the end, or before a comma
        Regex("\\b(?:in|on|using|with|via)\\s+(?:the\\s+|my\\s+)?$NAME(?:\\s+app)?\\s*(?:,|$)"),
        // "in settings, turn on …" / "on whatsapp send …" — leading
        Regex("^(?:in|on|using)\\s+(?:the\\s+|my\\s+)?$NAME(?:\\s+app)?\\s*,")
    )

    fun candidates(goal: String): List<String> {
        val g = goal.trim().lowercase().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
        return PATTERNS.mapNotNull { it.find(g)?.groupValues?.get(1)?.trim() }
            .filter { it.isNotEmpty() && it !in NOT_APPS }
            .distinct()
    }

    /** Phrases the patterns can catch that are never an app ("on my phone", "in the morning"). */
    private val NOT_APPS = setOf("my phone", "phone screen", "the phone", "the screen", "my screen", "screen", "it", "this",
        "that", "here", "there", "the morning", "morning", "the evening", "evening", "the night", "night", "loudspeaker", "speaker")
}
