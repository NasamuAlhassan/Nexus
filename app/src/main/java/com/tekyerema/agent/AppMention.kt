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
    private const val LEADIN = "(?:(?:please|kindly|can you|could you|would you|will you|i want to|i'd like to|i would like to|help me)\\s+)*"
    private const val OPEN = "(?:open(?:ing)?|launch(?:ing)?|start(?:ing)?|go\\s+to)\\s+(?!hours?\\b|times?\\b)(?:up\\s+)?(?:the\\s+|my\\s+)?"
    private val PATTERNS = listOf(
        // "open whatsapp and …", "can you please open the youtube app then …"
        Regex("^$LEADIN$OPEN$NAME(?:\\s+app)?\\s*(?:,|\\band\\b|\\bthen\\b)"),
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
            .filter { it.isNotEmpty() && it !in NOT_APPS && !isTheDevice(g, it) }
            .distinct()
    }

    /** "phone", "device", "tablet" — also "this phone", "your device": the thing in the user's hand unless the words say the
     *  APP — "how much storage is left on my phone" opened the Phone app and the task was marked done (multi suite, 13 Oct 2026). */
    private val DEVICE_WORDS = setOf("phone", "device", "tablet", "handset")
    private val DETERMINERS = Regex("^(?:this|that|my|the|your|our|his|her|their)\\s+")
    private fun isTheDevice(g: String, candidate: String): Boolean {
        val head = candidate.replace(DETERMINERS, "")
        return head in DEVICE_WORDS && !namesTheApp(g, head)
    }
    private fun namesTheApp(g: String, word: String): Boolean =
        Regex("\\b(?:open(?:ing)?|launch(?:ing)?|start(?:ing)?|go\\s+to|switch\\s+to|use|using)\\s+(?:up\\s+)?(?:the\\s+|my\\s+)?${Regex.escape(word)}(?:\\s+app)?\\b").containsMatchIn(g) ||
            Regex("\\b${Regex.escape(word)}\\s+app\\b").containsMatchIn(g)

    private val SINGLE_OPEN = Regex("^$LEADIN$OPEN$NAME(?:\\s+app)?(?:\\s+(?:please|for me))?$")

    /** The app a whole goal asks to open ("opening the calculator." → "calculator"), or null. */
    fun openTarget(goal: String): String? {
        val g = goal.trim().lowercase().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
        return SINGLE_OPEN.find(g)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() && it !in NOT_APPS }
    }

    /** The app a goal is about, whether it only opens it or opens it and does more. */
    fun named(goal: String): String? = candidates(goal).firstOrNull() ?: openTarget(goal)

    /** Phrases the patterns can catch that are never an app ("on my phone", "in the morning"). */
    private val NOT_APPS = setOf("my phone", "phone screen", "the phone", "the screen", "my screen", "screen", "it", "this",
        "that", "here", "there", "the morning", "morning", "the evening", "evening", "the night", "night", "loudspeaker", "speaker")
}
