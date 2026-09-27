package com.tekyerema.voice

/**
 * §A.1 — Twi (and English) confirmation lines built as TEMPLATES with variable slots, NOT by machine
 * translation. A blind Twi speaker must be asked, in Twi, whether to place a call / send money / send a
 * message — but the SLOTS that matter for safety are never translated:
 *   - a contact name passes through VERBATIM,
 *   - a phone number / amount is read DIGIT BY DIGIT (same discipline as the English path),
 * so MT can never mangle the very value the user is verifying. No network, no MT model, no dependency on a
 * Twi voice being available (the words are Twi even if the voice is not).
 *
 * The fixed Twi frames below are marked for native-speaker verification by the owner; the digit/name
 * integrity is what the unit tests lock down.
 */
object ConfirmTemplates {

    /** What is being confirmed. Slots are optional; only the ones a template uses are read. */
    data class Spec(
        val kind: Kind,
        val name: String? = null,     // contact / recipient — passed through VERBATIM, never translated
        val number: String? = null,   // phone number — read digit by digit
        val amount: String? = null,   // cedi amount — read digit by digit
        val message: String? = null   // message body — passed through VERBATIM
    ) {
        enum class Kind { CALL, SEND_MONEY, BUY_AIRTIME, SEND_MESSAGE, GENERIC }
    }

    // digit → Twi numeral (1–9 from the project's TWI numeral table; 0 kept as "zero", widely understood).
    // [owner: verify Twi digit words + frames with a native speaker before shipping.]
    private val TWI_DIGIT = mapOf(
        '0' to "zero", '1' to "baako", '2' to "mmienu", '3' to "mmiɛnsa", '4' to "anan",
        '5' to "anum", '6' to "nsia", '7' to "nson", '8' to "nwɔtwe", '9' to "nkron"
    )

    /** Read a phone number / amount digit-by-digit in Twi numerals ("024" → "zero mmienu anan"). */
    fun twiDigits(s: String): String =
        s.filter { it.isDigit() || it == '+' }
            .map { if (it == '+') "plus" else TWI_DIGIT[it] ?: it.toString() }
            .joinToString(" ")

    /** Read digit-by-digit in English ("024" → "0 2 4"); mirrors the English confirmation discipline. */
    fun enDigits(s: String): String =
        s.filter { it.isDigit() || it == '+' }
            .map { if (it == '+') "plus" else it.toString() }
            .joinToString(" ")

    private const val TWI_YESNO = " Ka aane anaa daabi."   // "Say yes or no." [owner: verify]

    /** The Twi confirmation question for [spec], slots filled verbatim / digit-by-digit. */
    fun twi(spec: Spec): String {
        val body = when (spec.kind) {
            Spec.Kind.CALL ->
                if (!spec.name.isNullOrBlank()) "Wopɛ sɛ mefrɛ ${spec.name} wɔ ${twiDigits(spec.number.orEmpty())}?"
                else "Wopɛ sɛ mefrɛ ${twiDigits(spec.number.orEmpty())}?"
            // §P2.1 — the app OPENS Mobile Money; the user finishes the amount + PIN. Say that, don't imply
            // the app itself pays. [owner: verify Twi]
            Spec.Kind.SEND_MONEY ->
                "Menbue Mobile Money sɛ wode cedi ${twiDigits(spec.amount.orEmpty())} bɛkɔma " +
                    (spec.name?.takeIf { it.isNotBlank() } ?: twiDigits(spec.number.orEmpty())) +
                    "? Wo ankasa na wobɛhyɛ PIN."
            Spec.Kind.BUY_AIRTIME -> "Menbue Mobile Money sɛ wobɛtɔ airtime cedi ${twiDigits(spec.amount.orEmpty())}? Wo ankasa na wobɛhyɛ PIN."
            Spec.Kind.SEND_MESSAGE -> "Wopɛ sɛ mesoma nkra kɔ ${spec.name ?: "no"} hɔ?"
            Spec.Kind.GENERIC -> "Wopɛ sɛ meyɛ eyi?"
        }
        return body + TWI_YESNO
    }
}
