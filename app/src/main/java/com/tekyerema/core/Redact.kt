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

    private val DEFAULT_DENY_PACKAGES = setOf(
        "com.google.android.apps.authenticator2",
        "com.azure.authenticator",
        "com.authy.authy",
        "com.lastpass.authenticator",
        "org.fedorahosted.freeotp"
    )

    /** True when a notification from [packageName] must not be captured at all. */
    fun deniedPackage(packageName: String?): Boolean {
        if (packageName == null) return false
        val extra = AppConfig.stringList("privacy.notifications.denyPackages", emptyList())
        return packageName in DEFAULT_DENY_PACKAGES || packageName in extra
    }

    fun looksSecret(text: String?): Boolean =
        text != null && SECRET_CONTEXT.containsMatchIn(text) && CODE.containsMatchIn(text)

    /** [text] with every code hidden when it reads as a secret-carrying message; unchanged otherwise. */
    fun secrets(text: String?): String? {
        if (text == null || !looksSecret(text)) return text
        return CODE.replace(text, HIDDEN)
    }
}
