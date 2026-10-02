package com.tekyerema.core

import com.tekyerema.core.config.AppConfig

/**
 * J-10 — one-time codes and PINs must never reach the model, the logs or a spoken summary. A message is treated as
 * carrying a secret when it talks about a code/PIN/OTP/verification AND contains a 4-8 digit run; every such run is
 * replaced with "[code hidden]". Other numbers in ordinary messages (amounts, phone numbers, transaction IDs of 9+
 * digits) are left alone. Apps whose every notification is a secret (authenticators) are dropped entirely.
 */
object Redact {
    const val HIDDEN = "[code hidden]"

    private val SECRET_CONTEXT = Regex(
        "(?i)\\b(otp|one[- ]?time|verification|verify|security code|auth(?:entication)? code|login code|sign[- ]?in code|" +
            "passcode|pass code|pin|password|confirmation code|your code|code is|code:)\\b"
    )
    // A 4-8 digit run (or "123-456") that is not part of a longer number or a decimal amount ("1234.56") — a
    // sentence-ending full stop after the code is fine.
    private val CODE = Regex("(?<!\\d)(?<!\\d[.,])\\d{4,8}(?!\\d)(?![.,]\\d)|(?<!\\d)\\d{3}[- ]\\d{3}(?!\\d)")

    // Finance audit (2026-10-01) — two REAL code messages on the test phone that the rule above let through:
    //  - MoMoApp: "This confidential code gives access to your MoMo account:<44 base64 characters>== Do not share it with
    //    anyone." — no digit run at all, so nothing was hidden and the message could be read aloud or sent to the model;
    //  - MTN ID: "do not share your code with any MTN employee or anyone. Your code is : <9+ digits>" — longer than 8.
    private val ACCESS_CONTEXT = Regex("(?i)\\b(?:confidential|secret|access|activation|login)\\s+code\\b|\\bcode gives access\\b|" +
        "\\bdo not share (?:it|this|your code|the code|this code)\\b|\\bnever share (?:it|this|your code|the code|this code)\\b")
    /** An opaque token: 20+ letters/digits/+// with at least one digit and one letter (a key, not a word or a number). */
    private val TOKEN = Regex("(?<![A-Za-z0-9+/=_-])(?=[A-Za-z0-9+/_-]*\\d)(?=[A-Za-z0-9+/_-]*[A-Za-z])[A-Za-z0-9+/_-]{20,}={0,2}")
    /** "code is : 123456789" / "Code:1234" — the digits right after the word, up to 12 of them, on the next line too. */
    private val LABELLED = Regex("((?i:\\bcode(?:\\s+is)?)\\s*[:=-]?\\s*)(\\d{4,20}(?!\\d)|(?=[A-Z0-9]*\\d)(?=[A-Z0-9]*[A-Z])[A-Z0-9]{5,20}\\b)")

    private val DEFAULT_DENY_PACKAGES = setOf(
        "com.google.android.apps.authenticator2",
        "com.azure.authenticator",
        "com.authy.authy",
        "com.lastpass.authenticator",
        "org.fedorahosted.freeotp"
    )

    /** True when a notification from [packageName] must not be captured at all. J-11: an app on the deny list — the
     *  app's `policy.denyApps` or one the user said "never touch" (D8) — is never shown to the model, so its
     *  notifications are not captured either. */
    fun deniedPackage(packageName: String?): Boolean {
        if (packageName == null) return false
        if (packageName in DEFAULT_DENY_PACKAGES) return true
        return packageName in AppConfig.stringList("privacy.notifications.denyPackages", emptyList()) ||
            packageName in com.tekyerema.policy.PolicyConfig.denyApps()
    }

    fun looksSecret(text: String?): Boolean {
        if (text == null) return false
        if (SECRET_CONTEXT.containsMatchIn(text) && (CODE.containsMatchIn(text) || LABELLED.containsMatchIn(text))) return true
        return ACCESS_CONTEXT.containsMatchIn(text) && (TOKEN.containsMatchIn(text) || CODE.containsMatchIn(text) || LABELLED.containsMatchIn(text))
    }

    /** [text] with every code hidden when it reads as a secret-carrying message; unchanged otherwise. */
    fun secrets(text: String?): String? {
        if (text == null || !looksSecret(text)) return text
        var t = LABELLED.replace(text) { m -> m.groupValues[1] + HIDDEN }
        t = CODE.replace(t, HIDDEN)
        if (ACCESS_CONTEXT.containsMatchIn(text)) t = TOKEN.replace(t, HIDDEN)
        return t
    }
}
