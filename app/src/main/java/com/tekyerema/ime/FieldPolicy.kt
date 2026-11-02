package com.tekyerema.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * G-05 — what the keyboard may do in the field it is typing into. A keyboard sees everything typed, so the safe
 * choices are decided here, once per field, and unit-tested:
 *
 *  - SENSITIVE (password, visible password, web password, number password / PIN, a field whose hint says PIN,
 *    password, passcode, OTP, CVV — a MoMo USSD PIN box is often a plain text or number field — or the agent's
 *    Secure Input step being active): no suggestions, no learning, no voice typing. The keys
 *    still type and are still spoken to TalkBack: since Android 8 apps are told to give accessibility complete
 *    information and leave password echo to the screen reader (Settings.Secure.ACCESSIBILITY_SPEAK_PASSWORD, deprecated).
 *  - IME_FLAG_NO_PERSONALIZED_LEARNING (incognito tabs, some messengers): suggestions may show, nothing is learned.
 *  - Email, URL, and fields that ask for no suggestions: no suggestions, no learning, no "3"/"c" conversion, no
 *    auto-capitals (a "3" in an address is a digit).
 *  - Number / phone / date fields get the big pad (a PIN gets digits only).
 */
data class FieldPolicy(
    val sensitive: Boolean,
    val suggestions: Boolean,
    val learning: Boolean,
    val autoCapitalize: Boolean,
    val voice: Boolean,
    val startPage: Page,
    val pad: PadKind?
) {
    companion object {
        private val SENSITIVE_HINT = Regex(
            "\\b(pin|pins|password|passcode|passwd|pass code|otp|one[- ]time|cvv|cvc|secret code|verification code|security code)\\b",
            RegexOption.IGNORE_CASE)

        fun isPasswordType(inputType: Int): Boolean {
            val cls = inputType and InputType.TYPE_MASK_CLASS
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            return when (cls) {
                InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
                else -> false
            }
        }

        fun hintLooksSensitive(hint: CharSequence?): Boolean = hint != null && SENSITIVE_HINT.containsMatchIn(hint)

        /**
         * @param hint the field's hint and/or label (EditorInfo.hintText, EditorInfo.label) — joined by the caller.
         * @param secureStep true while the agent's Secure Input step is running (Privacy.secureStep).
         */
        fun of(inputType: Int, imeOptions: Int, hint: CharSequence?, secureStep: Boolean): FieldPolicy {
            val cls = inputType and InputType.TYPE_MASK_CLASS
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            val sensitive = isPasswordType(inputType) || hintLooksSensitive(hint) || secureStep
            val noLearning = (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
            val isText = cls == InputType.TYPE_CLASS_TEXT
            val addressLike = isText && variation in setOf(
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_URI, InputType.TYPE_TEXT_VARIATION_FILTER)
            val noSuggestFlag = isText && (inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
            val pad = when (cls) {
                InputType.TYPE_CLASS_PHONE -> if (sensitive) PadKind.PIN else PadKind.PHONE
                InputType.TYPE_CLASS_NUMBER -> if (sensitive) PadKind.PIN else PadKind.NUMBER
                InputType.TYPE_CLASS_DATETIME -> PadKind.DATETIME
                else -> null
            }
            val prose = isText && !sensitive && !addressLike
            return FieldPolicy(
                sensitive = sensitive,
                suggestions = prose && !noSuggestFlag,
                learning = prose && !noSuggestFlag && !noLearning,
                autoCapitalize = prose,
                voice = isText && !sensitive,
                startPage = if (pad != null) Page.PAD else Page.LETTERS,
                pad = pad
            )
        }
    }
}
