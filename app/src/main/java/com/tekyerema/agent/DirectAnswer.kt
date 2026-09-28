package com.tekyerema.agent

import com.tekyerema.core.config.AppConfig

/**
 * I-01 — the direct answer path (T1). A general-knowledge question ("what is the capital of Ghana", "how many
 * cedis are in a pesewa", "explain inflation") needs no screen: it used to step off our UI (BACK/HOME), observe the
 * phone and upload the screen before answering. Now such a question goes to the brain with NO screen observation
 * and only a few tools (read the screen, search the web, give up) in case it turns out to need them.
 * Anything that mentions the phone, the user's own things or "this" stays on the normal loop. Pure → unit-tested.
 */
object DirectAnswer {
    private val QUESTION = Regex("^(?:please\\s+)?(?:who|what|what's|whats|when|where|why|how|which|whose|is|are|was|were|can|could|" +
        "does|do|did|define|explain|tell me (?:about|a joke|a story|something)|what is the meaning of)\\b", RegexOption.IGNORE_CASE)

    private val DEFAULT_PHONE_WORDS = listOf(
        "my", "me", "mine", "i", "phone", "screen", "app", "apps", "message", "messages", "notification", "notifications", "call",
        "called", "text", "texts", "sms", "battery", "wifi", "wi-fi", "bluetooth", "setting", "settings", "alarm", "timer",
        "contact", "contacts", "this", "that", "here", "open", "whatsapp", "momo", "balance", "volume", "brightness", "camera",
        "photo", "calendar", "storage", "location", "flashlight", "torch", "airtime", "data", "internet", "online", "playing"
    )

    fun isKnowledgeQuestion(goal: String): Boolean {
        val g = goal.trim().lowercase()
        if (g.isEmpty() || !QUESTION.containsMatchIn(g)) return false
        // "tell me a joke": the opener's "me" is the listener, not the user's own things.
        val body = g.replaceFirst(Regex("^(?:please\\s+)?tell me\\s+"), "")
        val words = body.split(Regex("[^\\p{L}\\p{N}'-]+")).filter { it.isNotBlank() }.toSet()
        val phone = AppConfig.stringList("answers.direct.phoneWords", DEFAULT_PHONE_WORDS)
        return phone.none { it in words } && words.size <= 25
    }
}
