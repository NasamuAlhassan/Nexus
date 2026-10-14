package com.tekyerema.money.ussd

import com.tekyerema.money.TelcoGuide
import com.tekyerema.money.TelcoUssd
import java.math.BigDecimal

/**
 * The small brain for the networks' menus (owner, 2 October 2026, after the USSD sweep: "the brain, the flow, is not
 * smart. It keeps getting stuck, which means offline itself it won't work… give it a small brain to understand the menus
 * and choose the best one"). One pure decision per live screen, on the phone, online or offline:
 *
 *  - what the screen IS — a loan offer, an offer to pay another way, "how do you want to pay", a product with its "Buy",
 *    a list of prices, a list of minute/data mixes, Self / Others, rows that only work in an app;
 *  - from what the user asked (their own words, the amount, how they pay, who it's for, more calls or more data), the row
 *    to press — or the ONE plain question only the user can answer, with their answers mapped to rows in advance.
 *
 * Money rules it never bends: a loan is never accepted, paying from airtime (no PIN) is never chosen unless the user said
 * so, a "Buy" is pressed by itself only at the price the user already approved, and a row that "is only available on the
 * MyMTN app" is never chosen (picking it ends the USSD session — seen live, 2 October 2026). Pure → unit-tested on the
 * real screens of the sweep (audit/ussd-2026-10-02).
 */
object MenuBrain {
    enum class Prefer { MINUTES, DATA, CHEAPEST, BIGGEST }

    data class Goal(
        /** The request's own distinctive words ("midnight", "kokrokoo") — see [goalWords]. */
        val words: Set<String> = emptySet(),
        /** A cedi figure the user said (the price, or the amount for an "Enter amount" row). */
        val amount: String? = null,
        /** "momo" | "airtime" | null — null is paid with Mobile Money (the user's PIN stays the last step). */
        val pay: String? = null,
        /** Whose phone: true = someone else's, false = this one, null = not known. */
        val forOthers: Boolean? = null,
        val prefer: Prefer? = null,
        /** The read-back the user approved named [amount] as the price — a "Buy" at exactly that price is pressed. */
        val priceConfirmed: Boolean = false,
        val net: String = "The network",
        /** A tier is inferred from an amount that is none of the prices only until the user has steered the menu
         *  themselves (a "0, go back" must not be undone by the same guess — live A20e, 30 September 2026). */
        val inferTier: Boolean = true
    )

    sealed class Move {
        data class Press(val option: String, val row: String, val why: String) : Move()
        /** A question for the user. [options] maps an answer ("yes", "no", "minutes", "data") to the row it presses; an
         *  empty option means "close the menu". A [Kind.PRICE] question is answered with an amount instead. */
        data class Ask(val question: String, val kind: Kind, val options: Map<String, String> = emptyMap()) : Move() {
            /** A loan, a switch of wallet, a purchase: asked before any planned step could press something else. */
            val urgent: Boolean get() = kind == Kind.LOAN || kind == Kind.SWITCH_PAY || kind == Kind.CONFIRM
        }
    }

    enum class Kind { LOAN, SWITCH_PAY, CONFIRM, PREFERENCE, PRICE }

    /** The move for [screen], or null when the brain has nothing sure to add (the caller's plan, then a read-out). */
    fun decide(goal: Goal, screen: String): Move? {
        val rows = TelcoUssd.rows(screen)
        if (rows.isEmpty()) return null
        val flat = screen.replace(Regex("\\s+"), " ").trim()
        loan(goal, flat, rows)?.let { return it }
        switchPay(goal, flat, rows)?.let { return it }
        payMode(goal, flat, rows)?.let { return it }
        buyButton(goal, flat, rows)?.let { return it }
        notice(goal, flat, rows)?.let { return it }
        val usable = rows.filter { !isNav(it.second) && !appOnly(it.second) }
        if (usable.isEmpty()) return null
        goalWord(goal, usable)?.let { return it }
        whose(goal, usable)?.let { return it }
        goal.amount?.let { a ->
            TelcoGuide.priceRow(usable, a)?.let { (n, w) -> return Move.Press(n, w, "the price you said") }
            TelcoGuide.amountRow(usable, a)?.let { (n, w) -> return Move.Press(n, w, "enter your amount") }
            if (goal.inferTier) TelcoGuide.tierRow(usable, a)?.let { (n, w) -> return Move.Press(n, w, "the price tier your amount is in") }
        }
        mixes(goal, flat, usable)?.let { return it }
        prices(goal, usable)?.let { return it }
        return null
    }

    // ------------------------------------------------------------------ screens that are about money, not products

    private val LOAN = Regex("(?i)\\badvance\\b|\\bborrow|\\bloan\\b|xtra ?time|qwik ?loan|\\bpay (?:it )?back\\b|\\brepay")
    private val ACCEPT_ROW = Regex("(?i)^(?:accept|yes|ok|okay|borrow|get it|take it|continue|proceed)\\b")
    private val CANCEL_ROW = Regex("(?i)^(?:cancel|no|exit|decline|reject|back|stop)\\b")
    private val SHORT = Regex("(?i)\\binsufficient\\b|\\bnot enough\\b|\\blow balance\\b|don'?t have enough")
    private val MOMO_ROW = Regex("(?i)^(?:pay (?:with |via |by )?)?(?:mobile money|momo|mm)\\b")
    private val AIRTIME_ROW = Regex("(?i)^(?:pay (?:with |via |by |from )?)?(?:airtime|main balance|credit)\\b")

    private fun cancelOption(rows: List<Pair<String, String>>): String =
        rows.firstOrNull { CANCEL_ROW.containsMatchIn(it.second.trim()) }?.first ?: ""

    /** "Uh oh. Looks like you don't have enough balance… You'll be offered an advance… 1. Accept 2. Cancel" (A20e, 2
     *  October 2026 — read out as "1 Accept; 2 Cancel", the word "advance" lost). A loan is the user's own decision. */
    private fun loan(goal: Goal, flat: String, rows: List<Pair<String, String>>): Move? {
        if (!LOAN.containsMatchIn(flat)) return null
        val accept = rows.firstOrNull { ACCEPT_ROW.containsMatchIn(it.second.trim()) } ?: return null
        return Move.Ask("${goal.net} says there isn't enough balance for this, and offers you an advance — a loan you pay back " +
            "from your next top-up. Do you want the advance? Say yes to take it, or no to stop.", Kind.LOAN,
            mapOf("yes" to accept.first, "no" to cancelOption(rows)))
    }

    /**
     * "Your Momo balance is insufficient to buy this bundle press 1 to pay with Airtime. 1. Airtime 0. Cancel" and its
     * mirror (both seen live, 2 October 2026). Paying from airtime happens at once with no PIN, so it is asked, plainly;
     * moving to Mobile Money keeps the PIN as the last step, so it is taken when the user hadn't insisted on airtime.
     */
    private fun switchPay(goal: Goal, flat: String, rows: List<Pair<String, String>>): Move? {
        if (!SHORT.containsMatchIn(flat)) return null
        val airtime = rows.firstOrNull { AIRTIME_ROW.containsMatchIn(it.second.trim()) }
        val momo = rows.firstOrNull { MOMO_ROW.containsMatchIn(it.second.trim()) }
        if ((airtime == null) == (momo == null)) return null       // neither, or both (that is the payment-mode menu)
        val no = cancelOption(rows)
        if (momo != null) {
            if (goal.pay != "airtime") return Move.Press(momo.first, momo.second, "your airtime isn't enough — Mobile Money, with your PIN")
            return Move.Ask("Your airtime isn't enough for this. ${goal.net} offers to take it from Mobile Money instead — you'd " +
                "enter your PIN. Pay with Mobile Money?", Kind.SWITCH_PAY, mapOf("yes" to momo.first, "no" to no))
        }
        return Move.Ask("Your Mobile Money balance isn't enough for this. ${goal.net} offers to take it from your airtime " +
            "instead — that is paid at once, with no PIN. Pay from your airtime?", Kind.SWITCH_PAY, mapOf("yes" to airtime!!.first, "no" to no))
    }

    /** "Choose payment mode 1. Airtime 2. Mobile Money": the way the user said, else Mobile Money. */
    private fun payMode(goal: Goal, flat: String, rows: List<Pair<String, String>>): Move? {
        if (SHORT.containsMatchIn(flat)) return null
        val row = TelcoGuide.payRow(rows, if (goal.pay == "airtime") "airtime" else "momo") ?: return null
        return Move.Press(row.first, row.second, if (goal.pay == "airtime") "pay from airtime, as you said" else "pay with Mobile Money")
    }

    private val BUY_ROW = Regex("(?i)^(?:buy|confirm|proceed|subscribe|activate|purchase|continue|yes|accept)\\b")
    private val REPLY_CONFIRM = Regex("(?i)\\b(?:reply|press|enter|dial|send)\\s+(\\d)\\s+to\\s+(?:confirm|accept|proceed|continue|buy)")
    private val REPLY_CANCEL = Regex("(?i)\\b(\\d)\\s+to\\s+(?:cancel|start again|go back|reject|decline|exit)")

    /**
     * A product with its own "Buy" ("Y'ello! The GHC 3 Data Bundle will give you 406.89 MB. This bundle does not expire.
     * 1. Buy" — 2 October 2026, asked as "What should I enter?"). Pressed at the price the user approved; otherwise one yes.
     */
    private fun buyButton(goal: Goal, flat: String, rows: List<Pair<String, String>>): Move? {
        val actions = rows.filter { !isNav(it.second) && !CANCEL_ROW.containsMatchIn(it.second.trim()) }
        val buy = actions.singleOrNull()?.takeIf { BUY_ROW.containsMatchIn(it.second.trim()) } ?: return null
        val price = MenuModel.amountOf(flat)?.toBigDecimalOrNull() ?: return null      // a product shows its price
        val asked = goal.amount?.replace(",", "")?.toBigDecimalOrNull()
        if (goal.priceConfirmed && asked != null && asked.compareTo(price) == 0)
            return Move.Press(buy.first, buy.second, "the price you approved")
        return Move.Ask("${goal.net} offers: ${describe(flat)}. Buy it? Say yes or no.", Kind.CONFIRM,
            mapOf("yes" to buy.first, "no" to cancelOption(rows)))
    }

    /** A bare "go on" row — the word alone ("1. Confirm", "1. Proceed"), not "Proceed to buy bundle (No 4G bonus)". */
    private val GO_ON_ROW = Regex("(?i)^(?:confirm|proceed|continue|ok|okay|yes|accept|agree|i agree|go on|next)\\W*$")
    /** A head that is only a prompt to pick a row ("Welcome to MTN Pulse. Please select an option"), not a notice. */
    private val PROMPT_HEAD = Regex("(?i)^(?:welcome[^.]*\\.?\\s*)?(?:please\\s+)?(?:select|choose|pick|enter)\\b")

    /**
     * A NOTICE the network wants acknowledged, with one bare "Confirm" / "Proceed" and a way back — no price, so it is not
     * a product's "Buy": "Y'ello, this bundle can only be used between the hours of 12am and 5am. 1. Confirm 0. Back"
     * and "Airtime purchased for other networks cannot be reversed 1. Proceed 2. Cancel" (both A20e, 3 October 2026 —
     * each was asked as "MTN says: 1 Confirm; 0 Back. What should I enter?", the words of the notice never spoken).
     * The user hears what the network says and answers yes or no; yes presses the one row, no closes the menu.
     */
    private fun notice(goal: Goal, flat: String, rows: List<Pair<String, String>>): Move? {
        val actions = rows.filter { !isNav(it.second) && !CANCEL_ROW.containsMatchIn(it.second.trim()) }
        val go = actions.singleOrNull()?.takeIf { GO_ON_ROW.matches(it.second.trim()) } ?: return null
        val said = describe(flat)
        if (said == "this" || said.length < 12 || PROMPT_HEAD.containsMatchIn(said)) return null   // nothing to tell
        return Move.Ask("${goal.net} says: $said. Shall I go on? Say yes or no.", Kind.CONFIRM,
            mapOf("yes" to go.first, "no" to cancelOption(rows)))
    }

    /** The option that confirms a "Reply 1 to confirm or 0 to start again" screen (a payment confirmation), and the one
     *  that backs out of it ("" = none shown: close the menu). Null when the screen offers no confirm key. */
    fun confirmKeys(screen: String): Pair<String, String>? {
        val flat = screen.replace(Regex("\\s+"), " ")
        val rows = TelcoUssd.rows(screen)
        val yes = REPLY_CONFIRM.find(flat)?.groupValues?.get(1)
            ?: rows.firstOrNull { BUY_ROW.containsMatchIn(it.second.trim()) }?.first ?: return null
        val no = REPLY_CANCEL.find(flat)?.groupValues?.get(1) ?: cancelOption(rows)
        return yes to no
    }

    /** The words above the rows, for the ear: "The GHC 3 Data Bundle will give you 406.89 megabytes. This bundle does not expire". */
    internal fun describe(flat: String): String {
        val head = flat.split(Regex("(?<=\\s|^)\\d{1,2}\\s*[.):-]\\s"), 2)[0]
        return units(head.replace(Regex("(?i)^\\s*(?:y'?ello[!,.]?|dear (?:valued )?customer[,.]?)\\s*"), "").trim().trimEnd('.', ' ', ','))
            .replace(Regex("(?i)\\bGH[SC₵¢]?\\s*(\\d+(?:\\.\\d+)?)"), "$1 cedis").ifBlank { "this" }
    }

    // ------------------------------------------------------------------ what the user asked for

    /** Words that say nothing about WHICH row ("buy", "bundle", "data", the network…): left out of [goalWords]. */
    private val GENERIC = setOf("buy", "purchase", "get", "want", "need", "please", "kindly", "me", "my", "mine", "i", "a", "an", "the",
        "for", "to", "of", "on", "in", "with", "using", "from", "by", "via", "and", "or", "some", "any", "this", "that", "phone", "number",
        "line", "sim", "self", "myself", "own", "bundle", "bundles", "data", "internet", "package", "packages", "offer", "offers", "plan",
        "mtn", "telecel", "vodafone", "airteltigo", "at", "network", "menu", "code", "dial", "open", "run", "ussd", "star", "hash",
        "cedi", "cedis", "pesewa", "pesewas", "ghs", "ghc", "momo", "mobile", "money", "wallet", "airtime", "credit", "pay", "paying",
        "more", "most", "less", "minutes", "mins", "calls", "call", "talk", "voice", "biggest", "cheapest", "largest", "smallest",
        "one", "option", "can", "you", "could", "would", "like", "help", "do", "it", "is", "be", "just", "now", "today", "go", "mashup",
        "mash", "up", "others", "other", "someone", "another", "send", "gift", "give", "share", "transfer")

    /** The request's distinctive words: "Buy an MTN midnight bundle" → {midnight}; "kokrokoo" → {kokrokoo}. */
    fun goalWords(goal: String): Set<String> =
        goal.lowercase().replace('’', '\'').replace(Regex("[^\\p{L}' ]"), " ").split(Regex("\\s+"))
            .map { it.trim('\'') }.filter { it.length >= 3 && it !in GENERIC }.toSet()

    // In Ghana "airtime" and "credit" are what you call with: on a bundle they mean the minutes side of a mix.
    private const val MIN_WORDS = "calls?|calling|minutes?|mins|talk(?: ?time)?|talking|voice|airtime|credit"
    private const val DATA_WORDS = "data|internet|megabytes?|mb|gigs?|gb|browsing|browse|net|whatsapp"
    private val PREFER_MINUTES = Regex("(?i)\\b(?:more|most|mostly|mainly|lots? of|plenty of|extra|bigger|enough) (?:$MIN_WORDS)\\b|" +
        "\\bfor (?:calls|calling|talking)\\b|\\b(?:calls|minutes?|airtime) (?:more|rather)\\b|\\bcalls?\\b(?! me)")
    private val PREFER_DATA = Regex("(?i)\\b(?:more|most|mostly|mainly|lots? of|plenty of|extra|bigger|enough) (?:$DATA_WORDS)\\b|" +
        "\\b(?:data|internet) (?:more|rather)\\b|\\bfor (?:browsing|internet)\\b")
    private val PREFER_CHEAP = Regex("(?i)\\bcheapest\\b|\\bsmallest\\b|\\bleast expensive\\b")
    private val PREFER_BIG = Regex("(?i)\\bbiggest\\b|\\blargest\\b")
    /** "more minutes than data", "airtime more than data", "data rather than calls", "calls, not data": X wins over Y. */
    private val COMPARE = Regex("(?i)\\b(?:(?:more|most|mostly|mainly|plenty of|lots? of|extra|bigger|much)\\s+)?($MIN_WORDS|$DATA_WORDS)\\b\\s*" +
        "(?:bundles?\\s+)?(?:more than|rather than|over|instead of|not|than|before)\\s+(?:more\\s+|the\\s+|just\\s+|any\\s+)?($MIN_WORDS|$DATA_WORDS)\\b")
    private val IS_MIN = Regex("(?i)^(?:$MIN_WORDS)$")
    private val IS_DATA = Regex("(?i)^(?:$DATA_WORDS)$")

    /**
     * "…with more minutes" → MINUTES; "more data" → DATA; "the cheapest" → CHEAPEST. Null when not said. A comparison is
     * read as one ("the person needs more minutes than data" → MINUTES — both words occur, and the old reading took that
     * for "not said" and asked again at the mixes; owner, A20e, 13 October 2026). Two sides without a comparison → null.
     */
    fun preferOf(text: String): Prefer? {
        COMPARE.find(text)?.let { m ->
            val x = m.groupValues[1]; val y = m.groupValues[2]
            if (IS_MIN.matches(x) && IS_DATA.matches(y)) return Prefer.MINUTES
            if (IS_DATA.matches(x) && IS_MIN.matches(y)) return Prefer.DATA
        }
        val data = PREFER_DATA.containsMatchIn(text)
        val minutes = PREFER_MINUTES.containsMatchIn(text)
        return when {
            data && !minutes -> Prefer.DATA
            minutes && !data -> Prefer.MINUTES
            PREFER_CHEAP.containsMatchIn(text) -> Prefer.CHEAPEST
            PREFER_BIG.containsMatchIn(text) -> Prefer.BIGGEST
            else -> null
        }
    }

    private fun stem(w: String) = w.lowercase().removeSuffix("s")

    /** "1. Buy Data Bundle 2. Midnight Bundles 3. Kokrokoo Bundle" + "midnight" → 2: the ONE row carrying the user's word. */
    private fun goalWord(goal: Goal, rows: List<Pair<String, String>>): Move? {
        if (goal.words.isEmpty()) return null
        val want = goal.words.map(::stem).toSet()
        val hits = rows.map { r -> r to r.second.lowercase().split(Regex("[^\\p{L}]+")).map(::stem).toSet().count { it in want } }
        val best = hits.maxOf { it.second }
        if (best == 0) return null
        val top = hits.filter { it.second == best }.singleOrNull() ?: return null
        return Move.Press(top.first.first, top.first.second, "the row that says what you asked for")
    }

    private val SELF = Regex("(?i)\\bself\\b|\\bmyself\\b|\\bmy (?:number|line|phone)\\b|\\bown number\\b")
    private val OTHERS = Regex("(?i)\\bothers?\\b|\\banother\\b|\\bsomeone\\b|\\bgift\\b|\\bfriends?\\b")

    /** "1. MashUp for Self 2. MashUp for Others": the user's own phone unless they named someone. */
    private fun whose(goal: Goal, rows: List<Pair<String, String>>): Move? {
        val self = rows.filter { SELF.containsMatchIn(it.second) }
        val others = rows.filter { OTHERS.containsMatchIn(it.second) && !SELF.containsMatchIn(it.second) }
        if (self.size != 1 || others.size != 1) return null
        return if (goal.forOthers == true) Move.Press(others[0].first, others[0].second, "it's for someone else")
            else Move.Press(self[0].first, self[0].second, "it's for this phone")
    }

    // ------------------------------------------------------------------ packages: minutes and data

    /** One offer row in numbers: megabytes, minutes, price (cedis). */
    internal data class Mix(val option: String, val row: String, val mb: Double?, val mins: Double?, val price: BigDecimal?)

    private val MB = Regex("(?i)(\\d+(?:\\.\\d+)?)\\s*(MB|GB)\\b")
    private val MINS = Regex("(?i)(\\d+(?:\\.\\d+)?)\\s*(?:mins?|minutes?)\\b")
    private val PRICE = Regex("(?i)(?:GH[SC₵¢]?|₵|¢)\\s*(\\d+(?:\\.\\d+)?)|(\\d+(?:\\.\\d+)?)\\s*(?:cedis?|GHS|GHC)\\b")

    internal fun mixOf(option: String, row: String): Mix {
        val mb = MB.findAll(row).sumOf { m -> m.groupValues[1].toDouble() * (if (m.groupValues[2].equals("GB", true)) 1024.0 else 1.0) }
            .takeIf { MB.containsMatchIn(row) }
        val mins = MINS.find(row)?.groupValues?.get(1)?.toDoubleOrNull()
        val price = PRICE.find(row)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.toBigDecimalOrNull()
        return Mix(option, row, mb, mins, price)
    }

    /**
     * MTN's Mashup mixes for one price ("1. 15.27MB and 15.64Mins(available on mymtn app only) 2. 25.45MB and 11.17Mins …
     * 5. 50.89MB Only"): the most minutes, the most data, the cheapest — whichever the user asked for; when they didn't say,
     * ONE question ("more calls or more data?") instead of five rows of decimals read aloud.
     */
    private fun mixes(goal: Goal, flat: String, rows: List<Pair<String, String>>): Move? {
        val mixes = rows.map { mixOf(it.first, it.second) }.filter { it.mb != null || it.mins != null }
        if (mixes.size < 2) return null
        fun pick(m: Mix, why: String) = Move.Press(m.option, m.row, why)
        val byMins = mixes.filter { it.mins != null }.maxWithOrNull(compareBy<Mix>({ it.mins }, { it.mb ?: 0.0 }))
        val byData = mixes.maxWithOrNull(compareBy<Mix>({ it.mb ?: 0.0 }, { it.mins ?: 0.0 }))
        when (goal.prefer) {
            Prefer.MINUTES -> return (byMins ?: return null).let { pick(it, "the most call minutes") }
            Prefer.DATA, Prefer.BIGGEST -> return pick(byData!!, "the most data")
            Prefer.CHEAPEST -> mixes.filter { it.price != null }.minByOrNull { it.price!! }?.let { return pick(it, "the cheapest") }
            null -> {}
        }
        // Only a real choice between calls and data is asked; a plain list of data sizes is a price list (below).
        if (byMins == null || byData == null || byMins.option == byData.option) return null
        val same = mixes.mapNotNull { it.price }.distinct().singleOrNull()
        val forWhat = same?.let { "For ${AmountRange.cedis(it)}, " } ?: ""
        val dataNote = byData.mins?.let { ", with ${fig(it)} minutes" } ?: " and no call minutes"
        return Move.Ask("${forWhat}${goal.net} has ${mixes.size} mixes. The most calls is ${fig(byMins.mins!!)} minutes with " +
            "${fig(byMins.mb ?: 0.0)} megabytes; the most data is ${fig(byData.mb ?: 0.0)} megabytes$dataNote. " +
            "Do you want more calls or more data?", Kind.PREFERENCE,
            mapOf("minutes" to byMins.option, "data" to byData.option))
    }

    private fun fig(v: Double): String = if (v >= 10) Math.round(v).toString() else String.format(java.util.Locale.US, "%.1f", v).removeSuffix(".0")

    /** A list of price tiers ("MashUp GHC 1 / 5 / 10 / 30") and no amount said: "Which one — 1, 5, 10 or 30 cedis?" */
    private fun prices(goal: Goal, rows: List<Pair<String, String>>): Move? {
        if (goal.amount != null) return null
        val tiers = rows.map { it to TIER.find(it.second.trim())?.groupValues?.get(1)?.toBigDecimalOrNull() }
        if (tiers.size < 2 || tiers.any { it.second == null }) return null
        val said = tiers.map { it.second!! }.joinToString(", ") { it.stripTrailingZeros().toPlainString() }
            .let { s -> s.substringBeforeLast(", ").let { if (it == s) s else "$it or ${s.substringAfterLast(", ")}" } }
        return Move.Ask("${goal.net} has these at $said cedis. Which one do you want?", Kind.PRICE)
    }

    private val TIER = Regex("(?i)\\bGH[SC₵¢]?\\s*(\\d+(?:\\.\\d+)?)\\s*$")

    // ------------------------------------------------------------------ rows never chosen

    private val NAV = Regex("(?i)^(?:back|more|next|previous|exit|quit|main menu|home|cancel)\\W*$|^#")
    private fun isNav(row: String) = NAV.matches(row.trim())

    /** A row MTN only sells in its app: "(available on mymtn app only)". Choosing it ends the USSD session with an SMS link. */
    private val APP_ONLY = Regex("(?i)\\bapp only\\b|\\bon (?:the )?my ?mtn app\\b|\\bonly (?:on|in|via) (?:the )?(?:my ?mtn )?app\\b|\\bavailable on (?:the )?\\S+ app\\b")
    fun appOnly(row: String): Boolean = APP_ONLY.containsMatchIn(row)

    // ------------------------------------------------------------------ the user's answer to a brain question

    sealed class Answer {
        data class Press(val option: String) : Answer()
        /** "No" / "cancel": close the network's menu. */
        object Close : Answer()
        /** The answer taught the walk something (a price, a preference) — pressed now, and remembered for later screens. */
        data class Learned(val option: String, val amount: String? = null, val prefer: Prefer? = null) : Answer()
        object Unclear : Answer()
    }

    private val SAYS_MINUTES = Regex("(?i)\\b(?:calls?|calling|minutes?|mins|talk(?:time| time)?|voice)\\b")
    private val SAYS_DATA = Regex("(?i)\\b(?:data|internet|megabytes?|mb|browsing|browse|gigs?|bundle)\\b")

    /** What [answer] means for the question [ask] on [screen]. A digit that is one of the menu's options is that option. */
    fun resolve(ask: Move.Ask, answer: String, screen: String): Answer {
        val rows = TelcoUssd.rows(screen)
        val a = answer.trim()
        // "The second one", "option 2": a position, whatever the question was about.
        ordinalRow(rows, a)?.let { return Answer.Press(it) }
        when (ask.kind) {
            Kind.LOAN, Kind.SWITCH_PAY, Kind.CONFIRM -> {
                // "No" to a loan, a switch of wallet or a purchase closes the network's menu outright — surer than its own
                // "0. Cancel", which may open another screen.
                return when (com.tekyerema.voice.Confirmation.interpret(a)) {
                    com.tekyerema.voice.Confirmation.Answer.YES -> ask.options["yes"]?.takeIf { it.isNotEmpty() }?.let { Answer.Press(it) } ?: Answer.Unclear
                    com.tekyerema.voice.Confirmation.Answer.NO -> Answer.Close
                    else -> if (com.tekyerema.money.BankUssd.isCancel(a)) Answer.Close else Answer.Unclear
                }
            }
            Kind.PREFERENCE -> {
                val m = SAYS_MINUTES.containsMatchIn(a); val d = SAYS_DATA.containsMatchIn(a)
                if (m && !d) ask.options["minutes"]?.let { return Answer.Learned(it, prefer = Prefer.MINUTES) }
                if (d && !m) ask.options["data"]?.let { return Answer.Learned(it, prefer = Prefer.DATA) }
            }
            Kind.PRICE -> {
                // "5", "five", "one cedi", "5 cedis", "I told you 5 cedis, 5" — on a price list a figure is a PRICE, not an
                // option number. The figure next to a money word wins; the same figure said twice is that figure; two
                // DIFFERENT figures are never glued into one (the digits of "5 … 5" had become 55, and the tier rule then
                // chose the 30-cedi bundle — A20e, 13 October 2026). A figure above every tier is no tier either: ask again.
                val amount = amountSaid(a)
                if (amount != null) {
                    (TelcoGuide.priceRow(rows, amount) ?: TelcoGuide.tierRow(rows, amount, aboveToo = false))
                        ?.let { return Answer.Learned(it.first, amount = amount) }
                    return Answer.Unclear
                }
            }
        }
        if (com.tekyerema.money.BankUssd.isCancel(a)) return Answer.Close
        val digits = com.tekyerema.money.BankUssd.entry(a)?.takeIf { d -> d.all { it.isDigit() } }
        if (digits != null && rows.any { it.first == digits }) return Answer.Press(digits)
        TelcoGuide.rowNamed(rows, a)?.let { return Answer.Press(it) }
        return Answer.Unclear
    }

    private val MONEY_NEAR = Regex("(?i)(?:gh[sc₵¢]?|₵|¢)\\s*(\\d+(?:\\.\\d+)?)|(\\d+(?:\\.\\d+)?)\\s*(?:cedis?|ghs|ghc|₵|¢|pesewas?)\\b")
    private val FIGURE = Regex("\\d+(?:\\.\\d+)?")
    /** "the cheap one", "the second one": that "one" is a pronoun, not a figure — dropped before number words become figures. */
    private val PRONOUN_ONE = Regex("(?i)\\b(the|that|this|which|cheap(?:est)?|dear(?:est)?|expensive|small(?:est)?|big(?:gest)?|first|second|third|fourth|fifth|last|other|same|middle|top|bottom|next|previous)\\s+one\\b")
    private val ALTERNATIVES = Regex("(\\d+(?:\\.\\d+)?)(?:\\s*(?:cedis?|ghs|ghc|pesewas?))?\\s+or\\s+(?:maybe\\s+)?(\\d+(?:\\.\\d+)?)")

    /**
     * The amount an answer names: the figure next to a money word ("5 cedis", "GHC 10", "50 pesewas" → 0.50); else the one
     * figure in it, said once or repeated ("5", "5 5"); null when figures disagree ("5 or 10") — never their digits glued.
     */
    internal fun amountSaid(answer: String): String? {
        // Number WORDS ("one cedi", "five", "twenty five cedis") become figures first; an answer that already has figures
        // is left as heard, so "5 5" stays two figures of the same price and is never joined into 55.
        val heard = if (answer.any { it.isDigit() }) answer
            else try { com.tekyerema.voice.Normalizer.normalize(PRONOUN_ONE.replace(answer, "$1"), com.tekyerema.voice.Lang.GH_EN) } catch (_: Throwable) { answer }
        val s = heard.lowercase().replace(",", "")
        // "5 or 10 cedis" is a choice not yet made, even though one figure sits beside the money word.
        ALTERNATIVES.find(s)?.let { m -> if (m.groupValues[1] != m.groupValues[2]) return null }
        val near = MONEY_NEAR.findAll(s).mapNotNull { m ->
            (m.groupValues[1].ifEmpty { m.groupValues[2] }).toBigDecimalOrNull()
                ?.let { v -> if (Regex("(?i)pesewas?").containsMatchIn(m.value)) v.movePointLeft(2) else v }
        }.toList()
        val figures = FIGURE.findAll(s).mapNotNull { it.value.toBigDecimalOrNull() }.toList()
        val v = when {
            near.isNotEmpty() && near.map { it.stripTrailingZeros() }.distinct().size == 1 -> near[0]
            near.isEmpty() && figures.isNotEmpty() && figures.map { it.stripTrailingZeros() }.distinct().size == 1 -> figures[0]
            else -> return null
        }
        return v.toPlainString()
    }

    private val ORDINAL = Regex("(?i)\\b(?:the\\s+)?(first|second|third|fourth|fifth|sixth|last|1st|2nd|3rd|4th|5th)(?:\\s+(?:one|option|row|choice))?\\b|" +
        "\\b(?:option|number|choice)\\s+(\\d{1,2})\\b")

    /** "The second one" / "option 2" → that row's number (positions skip Back / More). Null when no position is named. */
    internal fun ordinalRow(rows: List<Pair<String, String>>, answer: String): String? {
        val m = ORDINAL.find(answer) ?: return null
        m.groupValues[2].takeIf { it.isNotEmpty() }?.let { n -> return rows.firstOrNull { it.first == n }?.first }
        val usable = rows.filter { !isNav(it.second) }
        val idx = when (m.groupValues[1].lowercase()) {
            "first", "1st" -> 0; "second", "2nd" -> 1; "third", "3rd" -> 2; "fourth", "4th" -> 3; "fifth", "5th" -> 4; "sixth" -> 5
            "last" -> usable.lastIndex
            else -> return null
        }
        return usable.getOrNull(idx)?.first
    }

    private fun units(s: String): String = s
        .replace(Regex("(?i)(\\d)\\s*MB\\b"), "$1 megabytes").replace(Regex("(?i)(\\d)\\s*GB\\b"), "$1 gigabytes")
        .replace(Regex("(?i)(\\d)\\s*Mins?\\b"), "$1 minutes")
}
