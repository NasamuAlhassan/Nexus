package com.tekyerema.money

import com.tekyerema.core.config.AppConfig

/**
 * Guided bank transfers over USSD (money safety round 2, 2026-09-29; secure_payment_auth.md recommendation 12: "prefer the
 * bank app's own biometric; else verified USSD codes with the same PIN boundary").
 *
 * Only VERIFIED codes are ever dialled — `connectors.banks.ussd` (the research verified Stanbic *715#, Zenith *966#,
 * UBA *822#, Republic *414#, Absa Spark *859*99#, GhanaPay *707#; GCB, Ecobank, Fidelity, CalBank, Access, ADB and AT
 * Money are unverified and NOT included — a bank is added only once its code is checked). Nothing here automates a bank's
 * menu: Tɛkyerɛma opens the code, reads each screen aloud, types only what the user says, and stops — handing over — at a
 * payment confirmation or a PIN, which the user completes themselves (BankGuide, BankUssdTool). Pure → unit-tested.
 */
object BankUssd {
    data class Bank(val key: String, val name: String, val code: String, val aliases: List<String>)

    /** What a request about a bank's USSD asks for. */
    sealed class Request {
        data class Open(val bank: Bank) : Request()
        /** A bank transfer / "my bank's USSD" with no bank named. */
        object Which : Request()
        /** A bank Tɛkyerɛma has no verified code for. */
        data class Unsupported(val name: String) : Request()
        /** "Which banks can you open?" */
        object Supported : Request()
    }

    private val DEFAULTS = listOf(
        Bank("stanbic", "Stanbic Bank", "*715#", listOf("stanbic", "stanbic bank", "stan bic")),
        Bank("zenith", "Zenith Bank", "*966#", listOf("zenith", "zenith bank")),
        Bank("uba", "UBA", "*822#", listOf("uba", "u b a", "united bank for africa")),
        Bank("republic", "Republic Bank", "*414#", listOf("republic", "republic bank")),
        Bank("absa", "Absa", "*859*99#", listOf("absa", "absa bank", "absa spark", "absar")),
        Bank("ghanapay", "GhanaPay", "*707#", listOf("ghanapay", "ghana pay"))
    )

    /** Banks people name whose USSD code is NOT verified (said as unsupported, never dialled). */
    private val DEFAULT_UNVERIFIED = listOf("GCB", "GCB Bank", "Ecobank", "Fidelity", "Fidelity Bank", "CalBank", "Cal Bank",
        "Access Bank", "ADB", "Agricultural Development Bank", "Standard Chartered", "Societe Generale", "Prudential Bank",
        "First Atlantic", "FBN", "CBG", "Consolidated Bank", "OmniBSIC", "NIB", "GTBank", "Guaranty Trust", "Bank of Africa",
        "First National Bank", "ARB Apex", "AT Money")

    /** The verified table: `connectors.banks.ussd` when present (a code must start with * and end with #), else the defaults. */
    fun banks(): List<Bank> {
        val o = AppConfig.obj("connectors.banks.ussd") ?: return DEFAULTS
        val out = ArrayList<Bank>()
        for (k in o.keys()) {
            if (k.startsWith("_")) continue
            val b = o.optJSONObject(k) ?: continue
            val code = b.optString("code").trim()
            if (!USSD_CODE.matches(code)) continue
            val aliases = b.optJSONArray("aliases")?.let { a -> (0 until a.length()).map { a.optString(it).lowercase().trim() } }
                .orEmpty().filter { it.isNotEmpty() }
            out.add(Bank(k, b.optString("name").ifBlank { k }, code, aliases.ifEmpty { listOf(k.lowercase()) }))
        }
        return out.ifEmpty { DEFAULTS }
    }

    fun unverified(): List<String> = AppConfig.stringList("connectors.banks.unverified", DEFAULT_UNVERIFIED)

    private val USSD_CODE = Regex("^\\*[0-9*]+#$")

    fun byKey(key: String?): Bank? = key?.let { k -> banks().firstOrNull { it.key == k } }

    /** Every verified bank code without its '#' ("*715") — a USSD code starting with one is TIER3 whatever dials it. */
    fun moneyPrefixes(): List<String> = banks().map { it.code.trimEnd('#') }

    // ---------------------------------------------------------------- understanding the request (pure)
    fun norm(t: String): String = t.lowercase().replace('’', '\'').replace(Regex("(?<=\\b[a-z])\\.(?=[a-z]\\b)"), " ")
        .replace(Regex("[^\\p{L}\\p{N}'* #]"), " ").replace(Regex("\\s+"), " ").trim()

    /** A bank the words name: (verified bank, null) or (null, unverified name); null when none. */
    fun findBank(text: String): Pair<Bank?, String?>? {
        val g = " ${norm(text)} "
        banks().firstOrNull { b -> b.aliases.any { a -> g.contains(" $a ") || g.contains(" $a's ") } }?.let { return it to null }
        unverified().sortedByDescending { it.length }.firstOrNull { u -> g.contains(" ${u.lowercase()} ") || g.contains(" ${u.lowercase()}'s ") }
            ?.let { return null to it }
        return null
    }

    private val SUPPORTED_Q = Regex("\\b(?:which|what) banks?\\b.*\\b(?:open|support|use|work with|know|ussd|can you)\\b")
    /** A request to send from / open a bank: money-from-bank, a bank transfer, or a bank's USSD / menu / code. */
    private val BANK_MONEY = Regex("\\b(?:send|transfer|move|pay|withdraw)\\b.*\\b(?:from|with|using|through|via|on)\\b.*\\b(?:bank|account)\\b|" +
        "\\bbank (?:transfer|to bank)\\b|\\btransfer (?:money )?(?:from|with) (?:my )?bank\\b")
    private val BANK_MENU = Regex("\\b(?:ussd|short ?code|star code|bank menu|bank's menu|bank code|bank's code)\\b")
    private val NAMED_MENU = Regex("\\b(?:ussd|short ?code|menu|code|dial)\\b")
    private val FROM_NAMED = Regex("\\b(?:send|transfer|move|pay)\\b.*\\b(?:from|with|using|through|via)\\b")
    /** "from my Xyz bank account" / "from my Xyz account": a bank name we don't know at all. */
    private val FROM_UNKNOWN = Regex("\\bfrom (?:my |the )?([a-z][a-z ]{1,24}?) (?:bank )?account\\b")

    /**
     * What [goal] asks about a bank's USSD, or null when it isn't about that. "Open Stanbic" alone is NOT ours (the bank's
     * own app wins); it needs a USSD / menu / code word, or a send "from/with" the bank.
     */
    fun parse(goal: String): Request? {
        val g = norm(goal)
        if (g.isEmpty()) return null
        if (SUPPORTED_Q.containsMatchIn(g)) return Request.Supported
        val found = findBank(g)
        // Mobile Money is not a bank: "send money from my MoMo to my Stanbic account" stays with Mobile Money — unless the
        // money comes FROM the bank ("from my Stanbic account to my MoMo wallet").
        if (MOMO.containsMatchIn(g)) {
            val from = g.substringAfter(" from ", "").take(30)
            if (found == null || from.isEmpty() || MOMO.containsMatchIn(from.take(20)) || findBank(from) == null) return null
        }
        if (found != null) {
            val cue = BANK_MONEY.containsMatchIn(g) || BANK_MENU.containsMatchIn(g) || NAMED_MENU.containsMatchIn(g) || FROM_NAMED.containsMatchIn(g)
            if (!cue) return null
            found.first?.let { return Request.Open(it) }
            return found.second?.let { Request.Unsupported(it) }
        }
        // No bank named: the words must say "bank" and ask to send from it or open its menu.
        if (!Regex("\\bbank").containsMatchIn(g) || !(BANK_MONEY.containsMatchIn(g) || BANK_MENU.containsMatchIn(g))) return null
        FROM_UNKNOWN.find(g)?.groupValues?.get(1)?.trim()?.takeIf { it !in GENERIC }?.let { name ->
            return Request.Unsupported(name.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } })
        }
        return Request.Which
    }

    private val MOMO = Regex("\\b(?:momo|mo mo|mobile money|mtn|telecel|airteltigo|vodafone|wallet)\\b")

    private val GENERIC = setOf("bank", "my bank", "the bank", "savings", "current", "own", "other", "my", "the", "mobile money", "momo")

    // ---------------------------------------------------------------- spoken (pure)
    private fun names(): List<String> = banks().map { it.name }

    private fun joinOr(items: List<String>, word: String): String = when (items.size) {
        0 -> ""; 1 -> items[0]; else -> items.dropLast(1).joinToString(", ") + " $word " + items.last()
    }

    const val GUIDED = "It's guided: I read each screen, you choose, and you confirm the payment and enter your PIN yourself."

    fun supportedLine(): String = "I can open the USSD menu for ${joinOr(names(), "and")}. $GUIDED"

    fun whichQuestion(): String = "Which bank? I can open ${joinOr(names(), "or")}."

    fun unsupportedLine(name: String): String =
        "I can't open $name's menu: I only open bank codes I've checked, for ${joinOr(names(), "and")}. " +
            "Your bank's own app, with your fingerprint, is the safer way to send money."

    /** "*715#" → "star 7 1 5 hash"; "*859*99#" → "star 8 5 9 star 9 9 hash" — never read as a number. */
    fun spokenCode(code: String): String = code.map { c ->
        when (c) { '*' -> "star"; '#' -> "hash"; else -> c.toString() }
    }.joinToString(" ")

    // ---------------------------------------------------------------- a menu screen
    enum class ScreenKind {
        /** A menu or an entry prompt: the user says what to enter. */
        MENU,
        /** A payment confirmation (a figure + total / fee / confirm…): read out and handed over — never submitted by us. */
        MONEY,
        /** A PIN / password / code prompt: handed over. */
        PIN,
        /** A menu that merely MENTIONS a PIN or a code ("4. Change PIN"): the Secure Input boundary won't let Tɛkyerɛma act
         *  on such a screen (TargetSemantics.isCredentialScreen), so it is read out and handed over too. */
        GUARDED,
        /** A last message with nothing to enter: the session ended. */
        FINAL
    }

    /** Anything that may be a secret: a PIN, a password, a one-time or security CODE, a token. Wider than a PIN on purpose —
     *  a screen that turns out to be harmless ("Enter branch code") is only handed over early; a secret typed from a spoken
     *  answer would be heard, typed and logged. */
    private val PIN_WORDS = Regex("(?i)\\bpin\\b|\\bpasscode\\b|\\bpassword\\b|\\bsecret\\b|\\botp\\b|\\bone[- ]time\\b|" +
        "\\benter mm\\b|\\bmm pin\\b|\\btoken\\b|\\b(?:security|verification|authori[sz]ation|access|confirmation) code\\b")
    /** A bare "code" anywhere keeps Tɛkyerɛma's hands off the screen too (it may be a secret), without calling it a PIN. */
    private val ANY_CODE = Regex("(?i)\\bcode\\b")

    /** A numbered menu row ("4. Change PIN", "2) Balance"): an option, not what the screen asks for. */
    private val MENU_LINE = Regex("^\\s*\\d{1,2}\\s*[.):-]")

    /**
     * MTN's PIN prompt is cut off where the USSD page ends, and the cut moves with the length of what comes before it:
     * "… Total Amount is GHS 0.10. Enter MM # for next" (29 September), and with the reference "transport" only
     * "… Total Amount is GHS 0.10. Enter # for next" (A20e, 2 October 2026) — no "MM", no "PIN", so the screen was read
     * as a confirmation and the user was told to "confirm it there". A payment summary whose last words before the page
     * mark are a bare "Enter" is the same PIN prompt. Pure.
     */
    internal fun cutOffPinAsk(text: String): Boolean =
        com.tekyerema.policy.TargetSemantics.isMoneyConfirmationText(text) &&
            CUT_OFF_ASK.containsMatchIn(text.replace(Regex("\\s+"), " ").trim())
    private val CUT_OFF_ASK = Regex("(?i)[.,;:]\\s*enter(?:\\s+(?:your|mm|momo|mobile money))*\\s*(?:\\d{1,2}\\s*[.):-]\\s*)?#\\s*(?:for\\s+)?next\\W*$")

    /** What a bank screen is. The PROMPT (its lines that aren't numbered options) decides whether it asks for a secret;
     *  a secret word anywhere else still keeps Tɛkyerɛma's hands off it, the same as the engine's credential check. */
    fun kind(text: String, hasInput: Boolean, masked: Boolean): ScreenKind {
        val prompt = text.lines().filterNot { MENU_LINE.containsMatchIn(it) }.joinToString("\n")
        return when {
            masked || PIN_WORDS.containsMatchIn(prompt) -> ScreenKind.PIN
            hasInput && cutOffPinAsk(text) -> ScreenKind.PIN
            com.tekyerema.policy.TargetSemantics.isMoneyConfirmationText(text) -> ScreenKind.MONEY
            com.tekyerema.policy.TargetSemantics.credentialField.containsMatchIn(text) || PIN_WORDS.containsMatchIn(text) ||
                ANY_CODE.containsMatchIn(text) -> ScreenKind.GUARDED
            hasInput -> ScreenKind.MENU
            else -> ScreenKind.FINAL
        }
    }

    /**
     * A screen's words as one spoken line: menu rows joined with pauses, not too long. A BALANCE figure is only read when
     * [speakBalance] (earphones or Bluetooth connected, or the user switched balances on) — the research's "do not speak
     * balances on the loudspeaker by default" (secure_payment_auth.md, "Do not"); the rest of the screen is still read.
     */
    fun spokenScreen(text: String, speakBalance: Boolean = true): String {
        val t = if (speakBalance) text else text.split(Regex("(?<=[\\n|])|(?<=\\.\\s)")).joinToString("") { part ->
            if (BALANCE_WORD.containsMatchIn(part)) FIGURE.replace(part, HIDDEN) else part
        }
        return t.replace(Regex("\\s*[|\\n]\\s*"), "; ").replace(Regex("\\s+"), " ").trim().trim(';', ' ')
            .let { if (it.length > 400) it.take(400).trimEnd() + "…" else it }
    }

    const val HIDDEN = "(not read on the loudspeaker)"
    private val BALANCE_WORD = Regex("(?i)\\bbal(?:ance)?\\b|\\bavailable\\b|\\bavail\\b")
    private val FIGURE = Regex("(?i)(?:GH\\s?₵|GH\\s?¢|GHS|GHC|₵|¢)\\s*\\d[\\d,]*(?:\\.\\d+)?|\\d[\\d,]*(?:\\.\\d+)?\\s*(?:GHS|GHC|cedis?)\\b")

    private val ORDINALS = mapOf("first" to "1", "second" to "2", "third" to "3", "fourth" to "4", "fifth" to "5", "sixth" to "6",
        "seventh" to "7", "eighth" to "8", "ninth" to "9", "tenth" to "10")
    /** Number words a recogniser leaves as words ("to"/"for" only when the whole answer is number words). */
    private val WORDS = mapOf("zero" to "0", "oh" to "0", "one" to "1", "two" to "2", "to" to "2", "too" to "2", "three" to "3",
        "four" to "4", "for" to "4", "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10")
    private val FILLER = setOf("option", "number", "enter", "type", "choose", "select", "press", "pick", "put", "it", "it's", "its",
        "is", "the", "please", "okay", "ok", "choice", "item", "i", "want", "say", "cedi", "cedis", "ghs")

    /**
     * What to type for the user's answer: the digits they said ("option 2" → "2", "0 1 2 3 4" → "01234", "200 cedis" →
     * "200", "5.50" → "5.50"), an ordinal or number words ("the second one" → "2", "option one" → "1"), else their words
     * (a reference or a name, at most 40 characters). Null when there is nothing to type. Pure.
     */
    fun entry(answer: String): String? {
        val a = answer.lowercase().replace('’', '\'').trim().trimEnd('.', '!', '?', ',').trim()
        if (a.isEmpty()) return null
        if (a.any { it.isDigit() }) {
            val sb = StringBuilder()
            for ((i, c) in a.withIndex()) {
                if (c.isDigit()) sb.append(c)
                else if (c == '.' && i > 0 && a[i - 1].isDigit() && i + 1 < a.length && a[i + 1].isDigit()) sb.append(c)
            }
            return sb.toString().ifEmpty { null }
        }
        val words = a.replace(Regex("[^\\p{L}' ]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
        words.firstNotNullOfOrNull { ORDINALS[it] }?.let { return it }
        val content = words.filter { it !in FILLER }
        if (content.isNotEmpty() && content.all { it in WORDS }) return content.joinToString("") { WORDS.getValue(it) }
        return a.take(40)
    }

    private val CANCEL = Regex("^(?:no|cancel|stop|exit|quit|end|close|end it|close it|leave it|forget it|never mind|nevermind|go back|back)(?: please)?$")
    private val REPEAT = Regex("^(?:repeat(?: it| that)?|say (?:it|that) again|again|what|pardon|sorry|come again|read (?:it|that) again|" +
        "i didn't (?:hear|get) (?:it|that))(?: please)?$")

    fun isCancel(answer: String): Boolean = CANCEL.matches(answer.lowercase().trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " "))
    fun isRepeat(answer: String): Boolean = REPEAT.matches(answer.lowercase().trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " "))
}
