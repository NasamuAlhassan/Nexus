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
        val number: String? = null,   // phone number — read digit by digit, in 3-3-4 groups
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

    /** A phone number in Twi numerals, in the groups people check it in (3-3-4, a pause between groups):
     *  "0553238011" → "zero anum anum, mmiɛnsa mmienu mmiɛnsa, nwɔtwe zero baako baako" (money safety, 2026-09-29). */
    fun twiNumber(s: String): String {
        val groups = com.tekyerema.agent.NumberSpeech.groups(s)
        if (groups.isEmpty()) return twiDigits(s)
        val plus = s.trim().startsWith("+") && !com.tekyerema.agent.NumberSpeech.isGhanaMobileShape(s)
        return (if (plus) "plus " else "") + groups.joinToString(", ") { twiDigits(it) }
    }

    /** Read digit-by-digit in English ("024" → "0 2 4"); mirrors the English confirmation discipline. */
    fun enDigits(s: String): String =
        s.filter { it.isDigit() || it == '+' }
            .map { if (it == '+') "plus" else it.toString() }
            .joinToString(" ")

    /**
     * The amount IN ENGLISH, marked for the English voice — "five Ghana cedis", "ten pesewas" (owner, 2 October 2026: "I
     * do not want the amount spoken in Twi… all numbers and amounts should be kept and pronounced in normal English";
     * option B: the English voice says them inside the Twi line). Null when no amount was said.
     */
    private fun cedisWords(amount: String?): String? {
        val v = amount?.replace(",", "")?.trim()?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return null
        return if (v < java.math.BigDecimal.ONE) {
            val p = v.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toPlainString()
            "$p ${if (p == "1") "pesewa" else "pesewas"}"
        } else {
            val c = v.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()
            "$c Ghana ${if (c == "1") "cedi" else "cedis"}"
        }
    }

    /** A phone number in English, digit by digit in its 3-3-4 groups. */
    private fun numberWords(n: String?): String? = n?.takeIf { it.any(Char::isDigit) }?.let { com.tekyerema.agent.NumberSpeech.spoken(it) }

    /** The person in English: "Prince Alhassan, 0 5 5, 2 0 9, 3 6 9 0" — name and number when both are known. */
    private fun whoWords(name: String?, number: String?): String? =
        listOfNotNull(name?.trim()?.takeIf { it.isNotEmpty() }, numberWords(number)).joinToString(", ").ifEmpty { null }

    /** Marked for the English voice ([com.tekyerema.language.EntityTags.segments]). */
    private fun en(s: String) = com.tekyerema.language.EntityTags.EN_OPEN + s + com.tekyerema.language.EntityTags.EN_CLOSE

    private const val TWI_YESNO = " Ka aane anaa daabi."   // "Say yes or no." [owner: verify]

    /**
     * The Twi confirmation question for [spec]. Since 3 October 2026 the Twi words are FIXED frames and everything that
     * changes — the amount, the name, the number — is ONE English stretch for the English voice ("Menbue Mobile Money sɛ
     * wode | 5 Ghana cedis to Prince Alhassan, 0 5 5, 2 0 9, 3 6 9 0? | Wo ankasa na wobɛhyɛ PIN. Ka aane anaa daabi."). The
     * frames are recorded once in the HCI Lab's voice and ship in the app (voice/LabClips), so a confirmation starts at once
     * instead of after the lab's ~14 s, and costs no daily quota. Money safety is unchanged: the amount, name and number
     * are all read back. [owner: verify the Twi]
     */
    fun twi(spec: Spec): String {
        val who = whoWords(spec.name, spec.number)
        val amount = cedisWords(spec.amount)
        val body = when (spec.kind) {
            Spec.Kind.CALL -> "Wopɛ sɛ mefrɛ " + en("${who ?: "this number"}?")
            // §P2.1 — the app OPENS Mobile Money; the user finishes the amount + PIN. Say that, don't imply the app pays.
            // No amount said: "sika" (money), and the user picks the amount in the menu.
            Spec.Kind.SEND_MONEY ->
                (if (amount != null) "Menbue Mobile Money sɛ wode " + en("$amount to ${who ?: "them"}?")
                 else "Menbue Mobile Money sɛ wode sika " + en("to ${who ?: "them"}?")) + " Wo ankasa na wobɛhyɛ PIN."
            Spec.Kind.BUY_AIRTIME ->
                if (amount != null) "Menbue Mobile Money sɛ wobɛtɔ airtime " + en("$amount?") + " Wo ankasa na wobɛhyɛ PIN."
                else "Menbue Mobile Money sɛ wobɛtɔ airtime? Wo ankasa na wobɛhyɛ PIN."
            // K-05: the words themselves are read back too (verbatim), not only the recipient. Words dictated in Twi are
            // read by the Twi voice; English words by the English voice.
            Spec.Kind.SEND_MESSAGE -> {
                val head = spec.name?.trim()?.takeIf { it.isNotEmpty() }?.let { "Wopɛ sɛ mesoma nkra kɔma " + en("$it?") }
                    ?: "Wopɛ sɛ mesoma nkra no?"
                val words = spec.message?.trim()?.takeIf { it.isNotEmpty() }?.let { m ->
                    " Nkra no se: " + if (com.tekyerema.language.ReplyLocalizer.englishForVoice(m)) en("“$m”.") else "“$m”."
                } ?: ""
                head + words
            }
            Spec.Kind.GENERIC -> "Wopɛ sɛ meyɛ eyi?"
        }
        return body + TWI_YESNO
    }
}
