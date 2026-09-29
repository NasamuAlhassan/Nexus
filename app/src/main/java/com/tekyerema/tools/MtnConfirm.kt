package com.tekyerema.tools

import com.tekyerema.agent.NumberSpeech
import java.math.BigDecimal

/**
 * MTN's own confirmation screen — the last *170# screen, where MTN asks for the PIN — read and checked BEFORE the user
 * types the PIN. Pure, unit-tested.
 *
 * Why: in the owner's live send (A20e, 2026-09-29) that screen read "Transfer to AMA MENSAH for GHS 0.1 with Reference:
 * Payment. Fee is GHS 0.00, Tax amount is GHS 0.00. Total Amount is GHS 0.10. Enter MM # for next | Cancel", and the
 * recipient's REGISTERED NAME was never spoken. Hearing the name is the best protection a blind user has against a
 * misheard number (15–25% of spoken Ghanaian-English digits are misrecognised; research: african_voice_and_protocols.md),
 * and blind users over-trust an assistant (assistive_tech_blind.md) — so MTN's words are read out, facts first:
 * amount, name, fee, total, then the one instruction.
 *
 * Fail closed: when the amount on the screen is not the amount the user confirmed, when it can't be found at all, when a
 * number on the screen is not the confirmed number, or when the screen isn't the PIN prompt, the driver presses Cancel
 * (always safe) and says what MTN showed — nothing is sent. A missing NAME still hands off, but says so.
 */
object MtnConfirm {

    /** What the confirmation screen says. Every field is null when the screen doesn't show it. */
    data class Screen(
        /** The recipient's registered name as MTN shows it ("AMA MENSAH"). */
        val name: String?,
        val amount: BigDecimal?,
        val fee: BigDecimal?,
        val tax: BigDecimal?,
        val total: BigDecimal?,
        /** A phone number shown for the recipient, folded to 0… (+233 / 233 → 0). */
        val number: String?,
        val reference: String?
    )

    /** What the driver does at the screen: hand the PIN to the user (with the line to say), or press Cancel (and why). */
    sealed class Verdict {
        abstract val screen: Screen
        data class HandOff(val spoken: String, override val screen: Screen) : Verdict()
        data class Cancel(val why: String, override val screen: Screen) : Verdict()
    }

    // Currency as MTN and people write it. "GH₵", "GH¢", "GHS", "GHC", a bare "₵"/"¢", or "cedi(s)" after the figure.
    private const val CUR = "(?:GH\\s?₵|GH\\s?¢|GHS|GHC|₵|¢)"
    private const val FIG = "(\\d[\\d,]*(?:\\.\\d+)?)"
    private val MONEY = Regex("(?i)$CUR\\s*$FIG|$FIG\\s*(?:$CUR|cedis?\\b)")
    /** Where a clause starts, for labelling a figure: , ; | newline, or a full stop that isn't a decimal point. */
    private val CLAUSE_END = Regex("[,;|\\n]|\\.(?!\\d)")
    private val L_BALANCE = Regex("(?i)\\b(?:balance|bal)\\b")
    private val L_TAX = Regex("(?i)\\b(?:tax|taxes|levy|e-?levy|vat)\\b")
    private val L_FEE = Regex("(?i)\\b(?:fee|fees|charge|charges|commission)\\b")
    private val L_TOTAL = Regex("(?i)\\btotal\\b")

    /** "to <recipient>" up to the next clause word, currency, bracket or clause end. */
    private val RECIPIENT = Regex(
        "(?i)\\bto\\s+(.+?)(?=\\s+(?:for|with|of|amount|reference|ref|fee|on)\\b|\\s*[.,;:|\\n(\\[]|\\s+$CUR|\\s*$)")
    private val LABELLED_NAME = Regex("(?i)\\b(?:recipient|receiver|beneficiary|name|payee)\\s*(?:name)?\\s*[:\\-]\\s*([^.,;|\\n(]+)")
    private val LABELLED_NUMBER = Regex("(?i)\\b(?:number|no\\.?|msisdn|phone|mobile|wallet)\\s*[:\\-]?\\s*(\\+?\\d[\\d ]{8,15}\\d)")
    private val PHONE = Regex("\\+?\\d[\\d ]{8,15}\\d")
    /** A number in brackets right after the recipient: "to AMA MENSAH (0553238011)". */
    private val BRACKETED = Regex("^\\s*[(\\[]\\s*(\\+?\\d[\\d ]{8,15}\\d)\\s*[)\\]]")
    private val REFERENCE = Regex("(?i)\\b(?:reference|ref)\\s*[:\\-]?\\s*([^.\\n|]+)")
    /** Words that follow "to" on a USSD screen but are not a person ("Reply 1 to confirm", "to cancel"). */
    private val NOT_A_NAME = Regex("(?i)^(?:confirm|cancel|continue|proceed|next|accept|reject|go back|return|enter|reply|" +
        "the|your|my|mobile money|momo|wallet|account|main menu|menu|pay|send|transfer)\\b")

    fun parse(text: String): Screen {
        val t = text.replace(' ', ' ')
        val (name, recipientNumber) = recipient(t)
        var amount: BigDecimal? = null
        var fee: BigDecimal? = null
        var tax: BigDecimal? = null
        var total: BigDecimal? = null
        for (m in MONEY.findAll(t)) {
            val v = (m.groups[1] ?: m.groups[2])?.value?.replace(",", "")?.toBigDecimalOrNull() ?: continue
            // The label is in the clause before the figure; the recipient's own name never labels it ("FEE MENSAH").
            val start = CLAUSE_END.findAll(t.substring(0, m.range.first)).lastOrNull()?.range?.last?.plus(1) ?: 0
            var ctx = t.substring(start, m.range.first)
            if (name != null) ctx = ctx.replace(name, " ", ignoreCase = true)
            when {
                L_BALANCE.containsMatchIn(ctx) -> {}
                L_TAX.containsMatchIn(ctx) -> if (tax == null) tax = v
                L_FEE.containsMatchIn(ctx) -> if (fee == null) fee = v
                L_TOTAL.containsMatchIn(ctx) -> if (total == null) total = v
                else -> if (amount == null) amount = v
            }
        }
        val number = recipientNumber
            ?: LABELLED_NUMBER.find(t)?.groupValues?.get(1)?.let { phoneOrNull(it) }
        val reference = REFERENCE.find(t)?.groupValues?.get(1)?.trim()?.trimEnd('.', ' ')?.takeIf { it.isNotBlank() }
        return Screen(name, amount, fee, tax, total, number, reference)
    }

    /** (registered name, number) from "to AMA MENSAH", "to 233553238011 AMA MENSAH", "to AMA MENSAH (0553238011)",
     *  "Recipient: AMA MENSAH". */
    private fun recipient(t: String): Pair<String?, String?> {
        for (m in RECIPIENT.findAll(t)) {
            val seg = m.groupValues[1].trim()
            if (seg.isEmpty() || NOT_A_NAME.containsMatchIn(seg)) continue
            val number = (PHONE.find(seg)?.value ?: BRACKETED.find(t.substring(m.range.last + 1))?.groupValues?.get(1))
                ?.let { phoneOrNull(it) }
            val name = cleanName(seg)
            if (name != null || number != null) return name to number
        }
        return LABELLED_NAME.find(t)?.groupValues?.get(1)?.let { cleanName(it) } to null
    }

    private fun cleanName(s: String): String? {
        val n = s.replace(Regex("[+\\d()\\[\\]]"), " ").replace(Regex("\\s+"), " ").trim(' ', '-', ':', '.', ',')
        if (n.count { it.isLetter() } < 2 || n.length > 60 || NOT_A_NAME.matches(n)) return null
        return n.split(' ').take(6).joinToString(" ")
    }

    private fun phoneOrNull(s: String): String? =
        NumberSpeech.normalize(s).takeIf { it.length == 10 && it.startsWith("0") }

    // ---------------------------------------------------------------- the decision

    /**
     * Hand off or cancel. [requestedAmount] / [requestedNumber] are what the user confirmed; [pinScreen] is whether the
     * driver recognised this as the PIN prompt (credential wording or a masked field) — anything else is not handed off.
     */
    fun verdict(text: String, requestedAmount: String, requestedNumber: String, pinScreen: Boolean): Verdict {
        val s = parse(text)
        val shown = quote(text)
        val want = requestedAmount.replace(",", "").trim().toBigDecimalOrNull()
        val to = s.name?.let { " to ${speakName(it)}" }.orEmpty()
        if (!pinScreen) return Verdict.Cancel("MTN showed “$shown”, which isn't the PIN screen I expected", s)
        if (s.amount == null || want == null)
            return Verdict.Cancel("I couldn't find the amount on MTN's screen — it said “$shown”", s)
        if (s.amount.compareTo(want) != 0)
            return Verdict.Cancel("MTN's screen says it would send ${cedis(s.amount)}$to, but you asked for ${cedis(want)}", s)
        val wantNumber = NumberSpeech.normalize(requestedNumber)
        if (s.number != null && wantNumber.isNotEmpty() && s.number.takeLast(9) != wantNumber.takeLast(9))
            return Verdict.Cancel("MTN's screen shows the number ${NumberSpeech.spoken(s.number)}, not ${NumberSpeech.spoken(wantNumber)}", s)
        val costs = buildList {
            when {
                s.fee == null -> {}
                s.fee.signum() == 0 -> add("no fee")
                else -> add("fee ${cedis(s.fee)}")
            }
            if (s.tax != null && s.tax.signum() != 0) add("tax ${cedis(s.tax)}")
            if (s.total != null) add("total ${cedis(s.total)}")
        }.joinToString(", ").let { if (it.isEmpty()) "" else ", $it" }
        // One sentence on purpose: "brief" speech keeps only the first sentence, and the instruction must survive it.
        val line = if (s.name != null)
            "MTN says this sends ${cedis(s.amount)}$to$costs — if that's right, enter your PIN; if not, press Cancel."
        else
            "MTN says this sends ${cedis(s.amount)}$costs, but it didn't show a name, so check the number: " +
                "${NumberSpeech.spoken(wantNumber)} — if it's right, enter your PIN; if not, press Cancel."
        return Verdict.HandOff(line, s)
    }

    /** The failure line after a Cancel: why, what happened to the screen, and that nothing moved. */
    fun cancelledLine(why: String, pressed: Boolean): String =
        if (pressed) "$why, so I pressed Cancel. Nothing was sent."
        else "$why. Don't enter your PIN — press Cancel on that screen. Nothing is sent without your PIN."

    /** "0.10 cedis", "5 cedis", "1 cedi" — the spoken line is verbalised later ("ten pesewas", "five cedis"). */
    fun cedis(v: BigDecimal): String {
        val whole = v.stripTrailingZeros().scale() <= 0
        val s = if (whole) v.setScale(0, java.math.RoundingMode.UNNECESSARY).toPlainString() else v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
        return if (s == "1") "1 cedi" else "$s cedis"
    }

    /** A name as a voice should say it: MTN writes it in capitals, which some voices spell out letter by letter. */
    fun speakName(n: String): String =
        if (n.any { it.isLowerCase() }) n
        else n.lowercase().split(' ').joinToString(" ") { w -> w.split('-').joinToString("-") { it.replaceFirstChar(Char::uppercaseChar) } }

    /** MTN's words as they can be spoken: one line, no menu separators, not too long. */
    private fun quote(text: String): String =
        text.replace(Regex("\\s*[|\\n]\\s*"), " ").replace(Regex("\\s+"), " ").trim().let { if (it.length > 140) it.take(140).trimEnd() + "…" else it }

    /** Structured facts for later layers (ToolResult.data). */
    fun data(s: Screen): org.json.JSONObject = org.json.JSONObject().apply {
        s.name?.let { put("registeredName", it) }
        s.amount?.let { put("amount", it.toPlainString()) }
        s.fee?.let { put("fee", it.toPlainString()) }
        s.tax?.let { put("tax", it.toPlainString()) }
        s.total?.let { put("total", it.toPlainString()) }
        s.number?.let { put("shownNumber", it) }
        s.reference?.let { put("reference", it) }
    }
}
