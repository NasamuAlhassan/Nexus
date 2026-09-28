package com.tekyerema.policy

import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot

/**
 * Classifies the RESOLVED TARGET of a primitive — not the primitive's name (plan §13.2a).
 *
 * Whatever primitive the model uses (tap, long_press, swipe, drag, type+submit…), once the target
 * resolves to an element or a requested label, its semantics decide the risk. This closes the
 * class of bug where an enumerated list of "risky tool names" misses a new way to reach the same
 * control (the real round-6 set_text bypass). Keyword classes are generic UI vocabulary — no
 * app, package or task names.
 */
object TargetSemantics {

    /** Controls whose activation leaves the device, spends, destroys, or is hard to undo. */
    val consequential = Regex(
        "\\b(send|pay|transfer|delete|remove|confirm|call|buy|purchase|checkout|submit|post|share|forward|" +
            "archive|block|report|unsubscribe|sign out|log out|logout|reset|format|erase|uninstall|accept|agree|allow)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Fields whose IME action can BE a send (message compose boxes and the like). */
    val communicationField = Regex("\\b(message|reply|chat|comment|caption|post|tweet|status)\\b", RegexOption.IGNORE_CASE)

    /** Credential inputs the operator must never type into, whatever the model says. */
    val credentialField = Regex(
        "\\b(pin|otp|password|passcode|passphrase|cvv|verification code|one[- ]time|security code)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Fields where a submit is a harmless query, not a send — the only case where type+submit is waved
     *  through without a confirmation (§1.14). Everything else that submits is treated as a possible send. */
    val queryField = Regex(
        "\\b(search|find|query|filter|url|address|web|browser|www|go to|look ?up|zoom)\\b",
        RegexOption.IGNORE_CASE
    )

    /** A label that is essentially a phone number — tapping it in a dialer/recents can place a call (§1.16). */
    private val phoneNumberLabel = Regex("^[+(]?[0-9][0-9\\s().\\-]{5,}$")
    private val singleDigit = Regex("[0-9]")

    data class Classification(
        /** A tier to escalate to (only ever raises the tool's static tier), or null for no change. */
        val tier: RiskTier?,
        /** Non-null = hard block, with the reason. */
        val block: String?,
        val reason: String
    ) {
        companion object { val NONE = Classification(null, null, "no risky target semantics") }
    }

    /**
     * True when the CURRENT SCREEN is a credential-entry surface (MoMo PIN, password, OTP, passcode) —
     * the trigger for Secure Input Mode, where Tɛkyerɛma must go blind and NEVER hand the screen to the
     * LLM / OCR / logs. Reuses the same [credentialField] vocabulary as the never-type guard, keyed on
     * structure only (no app names). Conservative-but-specific: a FLAG_SECURE window, an actual password
     * field, or an editable field on a screen whose visible text names a credential.
     */
    fun isCredentialScreen(screen: UiSnapshot): Boolean {
        if (screen.secureWindow) return true                       // FLAG_SECURE (system PIN/lock/keyguard-like)
        if (screen.elements.any { it.password }) return true       // a masked password/PIN field
        val hasInput = screen.elements.any { it.editable }
        if (!hasInput) return false
        val texts = screen.elements.mapNotNull { it.label } + listOfNotNull(screen.screenTitle)
        return texts.any { credentialField.containsMatchIn(it) }   // e.g. "Enter MoMo PIN", "OTP", "password"
    }

    /** A search / address box, recognised by its resource id or widget class — NOT its label: once text is in it,
     *  the label IS that text (Chrome's omnibox showed "kakum national park" and a new search was gated as a
     *  possible SEND). A box that ever receives a message has a comms id (compose, message, reply…), not these. */
    fun isQueryBox(e: UiElement?): Boolean {
        if (e == null || !e.editable) return false
        val id = (e.viewId ?: "").substringAfter(":id/").lowercase()
        return Regex("search|url|omnibox|query|location_bar").containsMatchIn(id) ||
            Regex("(?i)SearchView|SearchAutoComplete").containsMatchIn(e.role) ||
            // a web page's search input has no resource id; its placeholder says what it is ("Search", "Search Google…")
            (e.hint != null && queryField.containsMatchIn(e.hint) && !communicationField.containsMatchIn(e.hint))
    }

    fun classify(
        primitive: String,
        target: UiElement?,
        requestedLabel: String?,
        submit: Boolean,
        screen: UiSnapshot? = null
    ): Classification {
        val label = target?.label ?: requestedLabel
        val lower = label?.lowercase()

        if (primitive == "type") {
            if (target?.password == true) return Classification(null, "credential field: typing into a password field is never permitted", "isPassword")
            if (lower != null && credentialField.containsMatchIn(lower))
                return Classification(null, "credential field ('$label'): PINs, OTPs and passwords are never entered by the operator", "credential label")
            // §1.14 — a submit fires the field's IME action, which in a compose/chat box SENDS. The field
            // label is often blank or an icon, so we can't rely on it saying "message". Default: any submit
            // needs confirmation; only wave it through when the field is CLEARLY a query/search box.
            if (submit) {
                val benign = (lower != null && queryField.containsMatchIn(lower)) || isQueryBox(target)
                if (!benign) return Classification(
                    RiskTier.TIER2, null,
                    "type+submit fires the field's action (can send) on '${label ?: "an unlabelled field"}'"
                )
            }
            return Classification.NONE
        }

        // ---- tap/long_press/drag/swipe on a resolved element ----
        val secure = screen?.secureWindow == true
        val digitKeys = screen?.elements?.count { it.clickable && it.label?.trim()?.matches(singleDigit) == true } ?: 0
        val onNumericKeypad = digitKeys >= 8
        val targetIsDigit = lower?.trim()?.matches(singleDigit) == true

        // §1.17 — a numeric keypad on a FLAG_SECURE screen is a PIN/passcode/lock pad: never tap it.
        if (onNumericKeypad && targetIsDigit && secure)
            return Classification(null, "PIN/passcode pad: entering a device or app passcode is never permitted", "pin pad on secure screen")

        // §1.16 — tapping a digit key on a (non-secure) numeric keypad, or a phone-number row, can place a
        // call outright on many dialers; require confirmation.
        if (onNumericKeypad && targetIsDigit)
            return Classification(RiskTier.TIER2, null, "dialer keypad key ('$label') — a tap here can dial")
        if (primitive == "tap" && lower != null && phoneNumberLabel.matches(label!!.trim()))
            return Classification(RiskTier.TIER2, null, "target '$label' is a phone number — tapping it can place a call")

        // §1.15 — an unlabelled icon tapped WHILE a message is being composed is very likely the Send
        // button. Scope it to a compose context so ordinary icon taps elsewhere don't all demand a yes.
        // A focused SEARCH/URL box is not a message composer (see [isQueryBox]). Seen on-device: tapping a
        // Settings-search result asked "Tap element 5?".
        val composing = screen?.elements?.any { f ->
            f.editable && (f.focused || communicationField.containsMatchIn(f.label ?: "")) && !isQueryBox(f)
        } == true
        if (primitive == "tap" && label.isNullOrBlank() && target?.clickable == true && composing)
            return Classification(RiskTier.TIER2, null, "an unlabelled control tapped while composing a message may send it")

        if (lower != null && consequential.containsMatchIn(lower) && looksLikeControlCaption(label!!)) {
            return Classification(RiskTier.TIER2, null, "target '$label' is a consequential control")
        }
        return Classification.NONE
    }

    /**
     * A consequential keyword should only escalate a real control CAPTION — a button like "Send", "Pay now",
     * "Delete", "Confirm & Pay" — NOT a long text blob that merely CONTAINS the word. This is the fix for a
     * demo-breaking false positive: a conversation-list row like "Prince Melanin Can you send me 10 pesewas to
     * buy water?? 16:19" is TAPPED to OPEN and READ it, not a Send button — but it contains "send" AND "buy",
     * so it was wrongly gated with "Send this message?" and reading became impossible. Structure-only heuristic
     * (no app names): a button caption is short — at most 4 words AND 30 characters. Longer labels are
     * sentences / message previews / notifications, i.e. navigation, not a consequential action. Genuine
     * money moves go through the dedicated TIER3 tools (momo_send / mobile_money), not a generic labelled tap,
     * so this narrowing doesn't weaken the real send-money boundary.
     */
    private fun looksLikeControlCaption(label: String): Boolean {
        val t = label.trim()
        if (t.isEmpty() || t.length > 30) return false
        return t.split(Regex("\\s+")).size <= 4
    }
}
