package com.tekyerema.operator

/**
 * WS-C — what each AI provider does with what Tɛkyerɛma sends it, in one or two plain sentences (shown under each provider
 * in Settings › Control and spoken for "where does my data go?"). Wording follows docs/agent/research/
 * agenticness_interop_safety_sovereignty.md §3.2, which cites the providers' own terms:
 *  - Groq, "Your Data in GroqCloud" (console.groq.com/docs/your-data) [S65]: no retention of inference requests by default;
 *    kept up to 30 days only to investigate abuse or troubleshoot; Zero Data Retention can be switched on.
 *  - Gemini API Additional Terms (ai.google.dev/gemini-api/terms) [S66]: on the unpaid tier, content is used to provide,
 *    improve and develop Google products and human reviewers may read it; the paid tier's prompts aren't used that way.
 * Re-check the providers' pages before quoting them in a submission — terms change.
 */
object Disclosure {
    data class Statement(val provider: String, val name: String, val text: String, val source: String)

    val GROQ = Statement(
        "groq", "Groq",
        "Groq doesn't keep your requests by default. It may keep them for up to 30 days, only to look into abuse or " +
            "fix problems.",
        "console.groq.com/docs/your-data"
    )

    val GEMINI = Statement(
        "gemini", "Google Gemini",
        "On the free tier, Google may use what you send to improve its products, and people at Google may read it. " +
            "With a paid key it isn't used that way. Switch Gemini off if you don't want that.",
        "ai.google.dev/gemini-api/terms"
    )

    fun of(provider: String): Statement? = when (provider.lowercase()) {
        "groq" -> GROQ
        "gemini" -> GEMINI
        else -> null
    }

    fun name(provider: String): String = of(provider)?.name ?: provider

    /** "Where does my data go?" (pure). */
    fun spoken(cloudOn: Boolean, providers: List<String>): String {
        if (!cloudOn) return "The cloud is off, so nothing you say leaves your phone. I'm using only what's on the phone."
        if (providers.isEmpty()) return "No online AI is switched on, so nothing you say leaves your phone."
        val order = providers.joinToString(", then ") { name(it) }
        return "I use $order. " + providers.mapNotNull { of(it)?.text }.joinToString(" ") +
            " You can change this in Settings, Control."
    }
}
