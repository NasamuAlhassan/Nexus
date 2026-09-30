package com.tekyerema.money

import com.tekyerema.core.config.AppConfig

/**
 * The networks' own menus for DATA and bundle buying, guided (owner 2026-09-30; research
 * docs/agent/research/ghana_ussd_flows.md): "buy a data bundle", "buy a mashup bundle", "what are my offers",
 * "check my data balance". The hybrid the research recommends — the researched branch map is the PLAN, but no key is
 * ever pressed blind: an auto-step only presses an option whose OWN words on the live screen match ("Buy Data
 * Bundles"), a screen that doesn't match is read aloud for the user to choose, and a payment confirmation or a PIN
 * always ends the walk with the user (BankUssd's screen kinds; the Secure Input boundary). Sending money is NOT here —
 * momo_send (MTN, live-verified) and the guided mobile_money menu keep that. Pure → unit-tested.
 *
 * Codes per network (config `connectors.telco.<net>`, defaults from the research, confidence noted there):
 * MTN data *138#, Mashup *567#, Just4U *141#; Telecel data *700#; AT bundles *111#. A code the research marked
 * UNKNOWN is simply absent — the request is answered honestly instead of dialling a guess.
 */
object TelcoUssd {
    /** One guided walk: the code to dial, the auto-steps (each must MATCH a menu row on the live screen), what it's for. */
    data class Flow(val key: String, val code: String, val auto: List<Regex>, val what: String, val netName: String,
                    /** Slots the USER'S OWN REQUEST carried ("Send ₵0.1 mashup to 055…") — entered when the network
                     *  asks, instead of asking the user to dictate what they already said (live A20e 2026-09-30:
                     *  the ask came AND the recogniser then failed on the digits, twice). */
                    val number: String? = null, val amount: String? = null)

    sealed class Request {
        data class Open(val flow: Flow) : Request()
        /** The network has no checked code for this — said honestly, nothing dialled. */
        data class Unsupported(val line: String) : Request()
    }

    private data class Net(val key: String, val name: String, val data: String?, val mashup: String?, val offers: String?)

    private val DEFAULTS = listOf(
        Net("mtn", "MTN", data = "*138#", mashup = "*567#", offers = "*141#"),
        Net("telecel", "Telecel", data = "*700#", mashup = null, offers = null),
        Net("airteltigo", "AT", data = "*111#", mashup = null, offers = null)
    )

    private fun nets(): List<Net> {
        val o = AppConfig.obj("connectors.telco") ?: return DEFAULTS
        val out = ArrayList<Net>()
        for (k in o.keys()) {
            val n = o.optJSONObject(k) ?: continue
            fun code(name: String) = n.optString(name).trim().takeIf { CODE.matches(it) }
            out.add(Net(k, n.optString("name").ifBlank { k }, code("data"), code("mashup"), code("offers")))
        }
        return out.ifEmpty { DEFAULTS }
    }

    private val CODE = Regex("^\\*[0-9*]+#$")

    // The auto-steps' row words, from the research (ghana_ussd_flows.md §2.4/§2.6). A row that doesn't match on the
    // live screen is never pressed — the guide asks instead, so menu drift degrades to guided, never to a wrong key.
    private val BUY = Regex("(?i)buy|proceed|purchase|bundle")
    private val DATA_ROW = Regex("(?i)data bundle|buy data|internet")
    private val SELF = Regex("(?i)\\bself\\b|myself|my ?(?:number|self)|own number")
    private val OTHER = Regex("(?i)other(?:s)?\\b|another|different number|someone")
    private val BALANCE = Regex("(?i)check.*balance|bundle balance|data balance|\\bbalance\\b")

    /** What [goal] asks of the network's own menu on the [network] SIM ("mtn" | "telecel" | "airteltigo"), or null. */
    fun parse(goal: String, network: String?): Request? {
        val g = " " + goal.lowercase().replace('’', '\'').replace(Regex("[^\\p{L}\\p{N}' ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        if (g.isBlank() || network == null) return null
        val net = nets().firstOrNull { it.key == network } ?: return null
        val other = Regex("\\bfor (?:another|an ?other|someone|somebody|a different)\\b| for [0-9]| to [0-9]").containsMatchIn(g)
        // The request's own slots: a 10-digit Ghana number (folded from +233/233 forms) and a cedi amount.
        val number = Regex("(?:\\+?233|0)\\s?\\d[\\d\\s-]{7,}").find(goal)?.value?.filter { it.isDigit() }
            ?.let { if (it.startsWith("233") && it.length == 12) "0" + it.drop(3) else it }?.takeIf { it.length == 10 && it.startsWith("0") }
        // The recipient number is stripped FIRST so it isn't read as the amount; then the whole remaining text goes to
        // MoneyLimits.parseAmount, which converts pesewas → cedis ("10 pesewas" → 0.1, NOT 10 — live A20e 2026-09-30).
        val amount = MoneyLimits.parseAmount(goal.replace(number ?: "\u0000", " "))?.toPlainString()
        fun open(key: String, code: String?, auto: List<Regex>, what: String, missing: String): Request =
            code?.let { Request.Open(Flow(key, it, auto, what, net.name, number = number, amount = amount)) } ?: Request.Unsupported(
                "I don't have a checked code for $missing on ${net.name}, and I never guess codes. " +
                    "You can dial it yourself and ask me to read the menu.")
        return when {
            // "what are my offers", "any offers for me", "just4u" — the personalised-offers menu (MTN *141#, HIGH).
            Regex("\\b(?:my |any )?offers?\\b|just ?4 ?u|just for you\\b").containsMatchIn(g) && !g.contains(" send ") ->
                open("offers", net.offers, emptyList(), "your ${net.name} offers", "its offers menu")
            // "check my data balance", "how much data do I have left" — MTN: *138# → buy → (More…) → Check Bundle Balance.
            Regex("\\bdata balance\\b|how much data\\b|\\bbundle balance\\b|data (?:do i have|left|remaining)").containsMatchIn(g) ->
                open("data_balance", net.data, listOf(BUY, BALANCE), "your ${net.name} data balance", "its data menu")
            // "buy a mashup/pulse bundle" — MTN's no-expiry voice+data combos. The LIVE *567# menu (A20e 2026-09-30)
            // goes buy → "MashUp for Self / Others" directly; steps are skippable anyway, but the plan matches reality.
            Regex("\\bmash ?up\\b|\\bpulse\\b|\\bfuse\\b").containsMatchIn(g) ->
                open("mashup", net.mashup, listOf(BUY, if (other) OTHER else SELF), "a ${net.name} Mashup bundle", "a combo-bundle menu")
            // "buy data", "buy a data bundle", "I need internet bundles" (airtime/credit stays with Mobile Money).
            Regex("\\b(?:buy|purchase|get|need|want)\\b.{0,24}\\b(?:data|internet|bundles?)\\b|\\bdata bundle\\b").containsMatchIn(g) &&
                !Regex("\\bairtime\\b|\\bcredit\\b|\\btop.?up\\b").containsMatchIn(g) ->
                // MTN *138# (HIGH): buy → Buy Data Bundles → self/other → the bundles (the user chooses) → pay → PIN stop.
                // Telecel *700# / AT *111#: the tree isn't published — open and guide every step.
                open("data", net.data, if (net.key == "mtn") listOf(BUY, DATA_ROW, if (other) OTHER else SELF) else emptyList(),
                    "a data bundle", "its data menu")
            else -> null
        }
    }

    /**
     * The menu's rows as (option number, its words). The A20e's USSD dialog hands the WHOLE menu as one line
     * ("…select an option 1. Proceed to buy bundle (No 4G bonus) 99. More", seen live 2026-09-30), so rows are found
     * anywhere in the text, each ending where the next "n." begins — never only at line starts.
     */
    fun rows(screen: String): List<Pair<String, String>> =
        ROW.findAll(" " + screen.replace('\n', ' ')).map { it.groupValues[1] to it.groupValues[2].trim() }.toList()

    /** The next auto-step's row on [screen] as its option number, or null (then the guide asks). "2. Buy Data Bundles" → "2". */
    fun autoAnswer(screen: String, step: Regex): String? =
        rows(screen).firstOrNull { (_, words) -> step.containsMatchIn(words) }?.first

    /** The "More"/"Next" row's number on [screen], or null — pressed while a step's row isn't on this page yet. */
    fun moreOption(screen: String): String? =
        rows(screen).firstOrNull { (_, words) -> Regex("(?i)^(?:more|next)\\b").containsMatchIn(words) }?.first

    /** A row: a number, a separator, then its words up to the next row (or the end). "(No 4G bonus)" must not start one,
     *  so the number can't follow a letter, '(' or a digit directly — it follows a space/start; "4G" never matches ([.):-] needed). */
    private val ROW = Regex("(?<=[\\s])(\\d{1,3})\\s*[.):-]\\s+(.+?)(?=\\s+\\d{1,3}\\s*[.):-]\\s+|$)")

    /** "*138#" spoken: "star 1 3 8 hash" (BankUssd's reader — never a number). */
    fun spokenCode(code: String): String = BankUssd.spokenCode(code)
}
