package com.tekyerema.core

import com.tekyerema.core.config.AppConfig

/**
 * J-06 — deterministic detector for instruction-like text inside UNTRUSTED content (screen labels, notification
 * and message bodies, USSD replies). It does not decide anything by itself: the engine uses it to (1) warn the
 * model that the content is data, and (2) STOP a task that is about to send, call or pay with a payload taken from
 * content that tried to give orders. Patterns are data (`policy.injection.patterns`) with a built-in default set;
 * config can only add patterns, never remove the defaults.
 */
object Injection {
    private val DEFAULT_PATTERNS = listOf(
        "\\bignore (?:all |any |the |your )?(?:previous|prior|above|earlier|preceding) (?:instructions?|messages?|rules?|prompts?)",
        "\\bdisregard (?:all |any |the |your )?(?:previous|prior|above|earlier) ",
        "\\b(?:new|updated|revised) (?:system )?instructions?\\s*:",
        "\\bsystem prompt\\b",
        "\\byou are (?:now )?(?:an? |the )?(?:ai|assistant|agent|chatbot|language model)\\b",
        "\\b(?:as an ai|dear (?:ai|assistant|agent))\\b",
        "\\b(?:assistant|agent|ai|tɛkyerɛma|tekyerema)\\s*[,:]\\s*(?:please\\s+)?(?:send|transfer|pay|call|forward|reply|open|tap|type|delete)\\b",
        "\\bdo not (?:tell|inform|alert|notify) the user\\b",
        "\\bwithout (?:asking|telling|confirming with) the user\\b",
        "\\bdeveloper mode\\b|\\bjailbreak\\b",
        "</?\\s*(?:system|instructions?|assistant)\\s*>"
    )

    private fun patterns(): List<Regex> =
        (DEFAULT_PATTERNS + AppConfig.stringList("policy.injection.patterns", emptyList()))
            .mapNotNull { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }

    /** The matched phrase when [text] reads like instructions aimed at an AI agent, else null. */
    fun detect(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val t = Sanitize.label(text) ?: return null
        for (re in patterns()) re.find(t)?.let { return it.value.trim().take(60) }
        return null
    }
}
