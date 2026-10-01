package com.tekyerema.agent

/**
 * What the user HEARS when the planner refuses. The planner's `cannot_do` detail is written for the log — "User asked
 * for their mobile money PIN, which is sensitive personal security information." was read aloud word for word (A20e
 * audit 2026-10-01, SEC01/SEC02). A line about "the user", or with no detail, is replaced by a sentence said TO them,
 * chosen from the reason class and what the request was about; a line already addressed to them is left alone.
 * Pure → unit-tested.
 */
object CannotDoSpeech {
    private val THIRD_PERSON = Regex("(?i)^(?:the )?user\\b|\\bthe user\\b|\\buser (?:asked|asks|wants|wanted|requested|is asking|said)\\b|" +
        "^request(?:ed)? (?:to|for)\\b|^no detail given$|^cannot_do$")
    private val CREDENTIAL = Regex("(?i)\\b(?:pin|pass ?word|pass ?code|otp|one[- ]time|security code|cvv|credential)\\b")

    fun forUser(reasonClass: String, detail: String, goal: String = ""): String {
        val d = detail.trim()
        if (d.isNotEmpty() && !THIRD_PERSON.containsMatchIn(d)) return d
        val aboutCredential = CREDENTIAL.containsMatchIn(d) || CREDENTIAL.containsMatchIn(goal)
        return when {
            aboutCredential -> "I never handle PINs or passwords, and I don't keep them. You enter those yourself."
            reasonClass == "authentication_required" -> "That needs you to sign in yourself first."
            reasonClass == "permission_missing" -> "I don't have the permission for that yet."
            reasonClass == "no_accessible_ui" || reasonClass == "app_blocks_automation" -> "That app doesn't let me do this."
            reasonClass == "device_restriction" -> "This phone doesn't allow that."
            reasonClass == "resource_unavailable" -> "I couldn't find what that needs on this phone."
            reasonClass == "repeated_failure" -> "I tried a few ways and it didn't work, so I've stopped."
            reasonClass == "network_unavailable" -> "The internet isn't working right now."
            reasonClass == "unsafe_or_uncertain" -> "I can't do that safely, so I've left it."
            else -> "I can't do that."
        }
    }
}
