package com.tekyerema.money.ussd

import com.tekyerema.money.BankUssd
import com.tekyerema.money.TelcoUssd

/**
 * What a live USSD screen IS — before anything decides what to press (navigator brief §5/§14). Built on the pieces
 * already proven on the phone: TelcoUssd.rows (the A20e hands whole menus as ONE line) and BankUssd.kind (PIN /
 * payment-confirmation / guarded classification, the Secure Input boundary's own detector). Pure → unit-tested.
 */
object MenuModel {
    enum class Kind { MENU, INPUT, CONFIRM, PIN, FINAL, ERROR }

    data class Screen(
        val text: String,
        val kind: Kind,
        val rows: List<Pair<String, String>>,   // option number → its words (empty for INPUT/FINAL)
        val slot: SlotKind?,                     // what an INPUT screen asks for
        /** The recipient's name as the NETWORK shows it ("Name: Prince Nasamu", MTN's "Transfer to KOFI MENSAH") — read
         *  at the one consolidated confirmation; null when the network shows none (then it is never invented). */
        val shownName: String?,
        /** A cedi figure the screen shows (the confirmation's amount) — checked against the user's, never trusted alone. */
        val shownAmount: String?
    )

    fun read(text: String, hasInput: Boolean, masked: Boolean): Screen {
        val t = text.trim()
        val bank = BankUssd.kind(t, hasInput, masked)
        val rows = TelcoUssd.rows(t)
        val kind = when {
            bank == BankUssd.ScreenKind.PIN -> Kind.PIN
            bank == BankUssd.ScreenKind.MONEY -> Kind.CONFIRM
            ERROR.containsMatchIn(t) && rows.isEmpty() -> Kind.ERROR
            hasInput && rows.isEmpty() -> Kind.INPUT
            // "Enter amount (1 - 5000)": the range reads like one numbered row, but the screen is asking for a value.
            hasInput && rows.size == 1 && ENTRY.containsMatchIn(t) -> Kind.INPUT
            rows.isNotEmpty() -> Kind.MENU
            else -> Kind.FINAL
        }
        return Screen(t, kind, rows, if (kind == Kind.INPUT) slotOf(t) else null, nameOf(t), amountOf(t))
    }

    /** What an entry screen wants, from its own words. CONFIRM_NUMBER before PHONE_NUMBER: "Confirm Phone Number". */
    internal fun slotOf(prompt: String): SlotKind = when {
        RE_CONFIRM_NUM.containsMatchIn(prompt) -> SlotKind.CONFIRM_NUMBER
        RE_NUMBER.containsMatchIn(prompt) -> SlotKind.PHONE_NUMBER
        RE_AMOUNT.containsMatchIn(prompt) -> SlotKind.AMOUNT
        RE_REFERENCE.containsMatchIn(prompt) -> SlotKind.REFERENCE
        RE_PRODUCT.containsMatchIn(prompt) -> SlotKind.PRODUCT
        else -> SlotKind.OTHER
    }

    /** The network's shown recipient name, or null — NEVER guessed (brief §12). Handles MTN's confirmation shapes
     *  ("Transfer GHS 0.10 to AMA MENSAH", "Name: Prince Nasamu", "Recipient: …"). */
    internal fun nameOf(text: String): String? {
        for (re in NAME_SHAPES) re.find(text)?.let { m ->
            val name = m.groupValues[1].trim().trimEnd('.', ',').trim()
            // A name is words, not digits or a menu row; 2..40 chars keeps codes and paragraphs out.
            if (name.length in 2..40 && name.any { it.isLetter() } && !name.any { it.isDigit() }) return name
        }
        return null
    }

    /** The first cedi figure on the screen ("GHS 50.00", "GHC 1", "50 cedis"), digits only, or null. */
    internal fun amountOf(text: String): String? =
        RE_MONEY.find(text)?.groupValues?.firstOrNull { it.isNotBlank() && it[0].isDigit() }

    private val ENTRY = Regex("(?i)^\\s*(?:please\\s+)?(?:enter|input|type|re-?enter|confirm|provide)\\b")
    private val RE_CONFIRM_NUM = Regex("(?i)\\b(?:confirm|re-?enter|repeat)\\b.{0,20}\\b(?:phone|number|msisdn)\\b")
    private val RE_NUMBER = Regex("(?i)\\b(?:phone|mobile|recipient|beneficiary)?\\s*number\\b|\\bmsisdn\\b|\\benter (?:the )?number\\b")
    private val RE_AMOUNT = Regex("(?i)\\bamount\\b|\\bhow much\\b|\\bGH[S₵C]?\\b.{0,12}enter|enter.{0,12}\\bGH[S₵C]?\\b")
    private val RE_REFERENCE = Regex("(?i)\\breference\\b|\\bnarration\\b|\\bpurpose\\b|\\breason\\b")
    private val RE_PRODUCT = Regex("(?i)\\bbundle\\b|\\bpackage\\b|\\boffer\\b|\\bplan\\b")
    private val ERROR = Regex("(?i)\\binvalid\\b|\\bfailed\\b|\\berror\\b|\\bnot allowed\\b|try again later|could not be processed")
    private val NAME_SHAPES = listOf(
        Regex("(?i)\\bname\\s*:\\s*([A-Za-z][A-Za-z .'-]{1,39})"),
        Regex("(?i)\\brecipient\\s*:?\\s*([A-Za-z][A-Za-z .'-]{1,39})"),
        Regex("\\bto\\s+([A-Z][A-Z '-]{1,39})"),                                     // MTN's ALL-CAPS registered name (case-sensitive; '.' ends it)
        Regex("(?i)\\btransfer to\\s+([A-Za-z][A-Za-z .'-]{1,39})")
    )
    private val RE_MONEY = Regex("(?i)(?:GH[S₵C¢]|₵|¢)\\s*(\\d+(?:\\.\\d{1,2})?)|(\\d+(?:\\.\\d{1,2})?)\\s*(?:GHS|GHC|cedis?)\\b")
}
