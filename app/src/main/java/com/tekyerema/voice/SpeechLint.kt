package com.tekyerema.voice

import com.tekyerema.core.config.AppConfig

/**
 * K-16 — a spoken reply is heard by someone who cannot see the screen. Before TTS, rewrite visual-only wording the
 * model sometimes produces: "as you can see", "click", "above/below" pointers, "the green button" as the only cue.
 * Rules are per-locale data (`ux.languageLint.rules`: [[regex, replacement], …]) on top of built-in English rules.
 * Past tense for unverified actions is handled separately (the COMPLETED_UNVERIFIED tail). Pure → unit-tested.
 */
object SpeechLint {
    private val DEFAULT_RULES: List<Pair<Regex, String>> = listOf(
        Regex("(?i)\\bas you can see,?\\s*") to "",
        Regex("(?i)\\byou can see (?:that )?") to "",
        Regex("(?i)\\bclicked\\b") to "tapped",
        Regex("(?i)\\bclicking\\b") to "tapping",
        Regex("(?i)\\bclicks\\b") to "taps",
        Regex("(?i)\\bclick\\b") to "tap",
        Regex("(?i)\\b(?:shown|displayed) (?:above|below|here)\\b") to "on the screen",
        Regex("(?i)\\b(?:the )?(?:button|link|icon) (?:above|below|on the (?:left|right))\\b") to "that button",
        Regex("(?i)\\bhighlighted in (?:red|green|blue|yellow)\\b") to "marked",
        Regex("(?i)\\blook at\\b") to "check"
    )

    fun apply(text: String): String {
        var t = text
        val extra = (AppConfig.node("ux.languageLint.rules") as? org.json.JSONArray)?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                a.optJSONArray(i)?.let { r -> runCatching { Regex(r.optString(0), RegexOption.IGNORE_CASE) to r.optString(1) }.getOrNull() }
            }
        }.orEmpty()
        for ((re, rep) in DEFAULT_RULES + extra) t = re.replace(t, rep)
        // Re-capitalise a sentence start the rewrite may have exposed ("As you can see, it's on." → "It's on.").
        t = t.replace(Regex("\\s{2,}"), " ").trim()
        return if (t.isNotEmpty() && text.firstOrNull()?.isUpperCase() == true) t.replaceFirstChar { it.uppercase() } else t
    }

    /** B-13 "brief": the first sentence only (a question is kept whole — it needs an answer). */
    fun firstSentence(text: String): String {
        val t = text.trim()
        if (t.endsWith("?")) return t
        val m = Regex("^(.+?[.!])(\\s|$)").find(t) ?: return t
        return m.groupValues[1]
    }
}
