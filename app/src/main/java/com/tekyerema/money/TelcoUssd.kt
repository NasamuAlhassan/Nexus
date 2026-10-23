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
                    val number: String? = null, val amount: String? = null,
                    /** A walk handed over by the navigator ("nav"): the reference to enter when the network asks, and the
                     *  person's name for the outcome ledger. Both were part of the one read-back the user approved. */
                    val reference: String? = null, val name: String? = null,
                    /** How the user said they'd pay ("with mobile money" → "momo", "from my airtime" → "airtime"); null
                     *  = not said, so the network's "Choose payment mode" is the user's to answer. */
                    val pay: String? = null,
                    /** A navigator hand-over: what the walk is FOR (a TxIntent name) and the SIM's network key — so the
                     *  menus after the user's one answer are still chosen by meaning, not read out one by one. */
                    val intent: String? = null, val sim: String? = null,
                    /** The request's distinctive words ("midnight") and what the user prefers (more calls / more data) —
                     *  what the menu brain (money/ussd/MenuBrain) chooses rows by. */
                    val words: Set<String> = emptySet(), val prefer: String? = null) {
        /** The navigator's hand-over: number, amount and reference were CONFIRMED up front, so they are entered when
         *  the network asks for them without a second question each. */
        val confirmed: Boolean get() = key == NAV
    }

    const val NAV = "nav"
    /** A network menu opened by its own code ("Dial *138#"): no researched plan, the brain and the user choose. */
    const val MENU = "menu"
    private val MENU_CODE = Regex("\\*\\d[\\d*]*#")

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

    /** Every network-menu code this table has for any network (the only ones a model may dial unasked). */
    fun checkedCodes(): Set<String> = nets().flatMap { listOfNotNull(it.data, it.mashup, it.offers) }.toSet()
    private val PAY_MOMO = Regex("\\b(?:with|using|from|by|via|through)\\s+(?:my\\s+)?(?:momo|mobile money|wallet)\\b")
    private val PAY_AIRTIME = Regex("\\b(?:with|using|from|by)\\s+(?:my\\s+)?(?:airtime|credit)\\b")
    private val MONEY_WORD = Regex("\\b(?:cedis?|pesewas?|ghs|ghc|gh)\\b|₵|¢")
    /** The person a bundle is for: the words after the LAST "for" (or, for a request that gives — send / gift / share —
     *  the last "to"), when they are not the user themselves and not what the bundle is for. */
    private const val WHO = "(?!me\\b|myself\\b|my (?:phone|number|line|self|sim)\\b|self\\b|this (?:phone|number)\\b|" +
        "another\\b|someone\\b|somebody\\b|a different\\b|the\\b|\\d)([\\p{L}][\\p{L}' .-]{1,40})$"
    private val PERSON_FOR = Regex("(?i)^.*\\bfor\\s+$WHO")
    private val PERSON_TO = Regex("(?i)^.*\\bto\\s+$WHO")
    private val GIVES = Regex("\\b(?:send|give|gift|share|transfer)\\b")
    private val NOT_A_PERSON = Regex("(?i)\\b(?:data|bundles?|mash ?up|internet|airtime|credit|gig|gb|mb|cedis?|pesewas?|days?|weeks?|" +
        "months?|hours?|night|midnight|today|tomorrow|now|use|browse|browsing|work|school|study|surf|download|chat|play|watch|" +
        "stream|streaming|social|media|facebook|instagram|whatsapp|youtube|tiktok|video|videos|music|games?|gaming|calls?|free|" +
        "cheap|all|everything|buy|get|want|need)\\b")

    // The auto-steps' row words, from the research (ghana_ussd_flows.md §2.4/§2.6). A row that doesn't match on the
    // live screen is never pressed — the guide asks instead, so menu drift degrades to guided, never to a wrong key.
    private val BUY = Regex("(?i)buy|proceed|purchase|bundle")
    private val DATA_ROW = Regex("(?i)data bundle|buy data|internet")
    private val SELF = Regex("(?i)\\bself\\b|myself|my ?(?:number|self)|own number")
    private val OTHER = Regex("(?i)other(?:s)?\\b|another|different number|someone")
    private val BALANCE = Regex("(?i)check.*balance|bundle balance|data balance|\\bbalance\\b")
    private val NAMED_BUNDLES = setOf("midnight", "kokrokoo", "night", "weekend", "social", "video", "youtube", "whatsapp")

    /** The words of a question about bundles rather than a request for one (matched on the normalised, lower-case goal;
     *  "how much data do I have left" is a balance and stays ours). */
    private val ASKS_ABOUT = Regex("\\b(?:compare|comparison|versus|vs|cheaper|cheapest|prices?|costs?|difference|" +
        "more expensive)\\b|\\bwhich (?:is|one|network|bundle|offer)\\b|\\bhow much (?:is|are|does|do)\\b(?! i )")
    private val BUY_LEAD = Regex("^ (?:please |kindly |can you |could you |i want to |i'd like to |i would like to |help me )*" +
        "(?:buy|purchase|get me|get) ")

    /** What [goal] asks of the network's own menu on the [network] SIM ("mtn" | "telecel" | "airteltigo"), or null. */
    fun parse(goal: String, network: String?): Request? {
        val g = " " + goal.lowercase().replace('’', '\'').replace(Regex("[^\\p{L}\\p{N}' ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        if (g.isBlank() || network == null) return null
        val net = nets().firstOrNull { it.key == network } ?: return null
        // A question ABOUT bundles — a comparison, prices, which is cheaper — is research, never a walk into the
        // network's purchase menu (A20e audit 2026-10-01, R04: "compare MTN and Telecel data bundle prices and tell me
        // which is cheaper" was asked "Open MTN's menu for a data bundle?"). An outright "buy the cheapest…" still buys.
        if (ASKS_ABOUT.containsMatchIn(g) && !BUY_LEAD.containsMatchIn(g)) return null
        val otherSaid = Regex("\\bfor (?:another|an ?other|someone|somebody|a different)\\b| for [0-9]| to [0-9]").containsMatchIn(g)
        // The request's own slots: a 10-digit Ghana number (folded from +233/233 forms) and a cedi amount.
        val numberSpan = Regex("(?:\\+?233|0)\\s?\\d[\\d\\s-]{7,}").find(goal)?.value
        val number = numberSpan?.filter { it.isDigit() }
            ?.let { if (it.startsWith("233") && it.length == 12) "0" + it.drop(3) else it }?.takeIf { it.length == 10 && it.startsWith("0") }
        // The recipient number is stripped FIRST so it isn't read as the amount — AS IT WAS SAID ("055-2093 690", with the
        // recogniser's dashes and spaces), not as its folded digits, which never occur in the text (A20e, 13 October 2026:
        // "do 5 cedis mashup send it to 055-2093 690" kept the number's four figures, lost its 5 cedis, and the price was
        // asked again at the offers). Then the remaining text goes to MoneyLimits.parseAmount, which converts pesewas →
        // cedis ("10 pesewas" → 0.1, NOT 10 — live A20e 2026-09-30). Only a figure said AS MONEY is an amount: "buy 1 gig of
        // data" is one gigabyte, not one cedi.
        val amount = if (!MONEY_WORD.containsMatchIn(goal.lowercase())) null
            else MoneyLimits.parseAmount(if (number != null && numberSpan != null) goal.replace(numberSpan, " ") else goal)?.toPlainString()
        // "… for Prince Melanin", "… to my mother": a person named instead of a number. It used to be ignored — and the
        // bundle was then bought for the user's own phone. The tool looks the name up before anything is read back.
        // (How it is paid for — "… with mobile money" — is not part of who it is for.)
        val clean = goal.trim().trimEnd('.', '!', '?')
            .replace(Regex("(?i)\\s*\\b(?:with|using|from|by|via|through)\\s+(?:my\\s+)?(?:momo|mobile money|wallet|airtime|credit)\\b"), "").trim()
        val person = if (number != null) null
            else (PERSON_FOR.find(clean) ?: (if (GIVES.containsMatchIn(g)) PERSON_TO.find(clean) else null))?.groupValues?.get(1)?.trim()
                ?.replace(Regex("(?i)\\s+(?:please|now|today)$"), "")
                ?.takeIf { it.length >= 2 && it.split(Regex("\\s+")).size <= 4 && !NOT_A_PERSON.containsMatchIn(it) }
        val other = otherSaid || person != null
        // Paid with Mobile Money unless the user said airtime (owner, 2 October 2026: "for all of these purchases, pay using
        // MoMo rather than airtime"): the PIN stays the last step, and the read-back says so.
        val pay = when {
            PAY_AIRTIME.containsMatchIn(g) -> "airtime"
            else -> "momo"
        }
        val words = com.tekyerema.money.ussd.MenuBrain.goalWords(goal)
        val prefer = com.tekyerema.money.ussd.MenuBrain.preferOf(goal)?.name
        fun open(key: String, code: String?, auto: List<Regex>, what: String, missing: String, buys: Boolean = true): Request =
            code?.let { Request.Open(Flow(key, it, auto, what, net.name, number = number, amount = amount, name = person,
                pay = if (buys) pay else null, words = words, prefer = prefer)) } ?: Request.Unsupported(
                "I don't have a checked code for $missing on ${net.name}, and I never guess codes. " +
                    // Only a network the brain's walk has an official root for can keep "I'll find it" — Telecel and AT have none,
                    // so asking again gave this same line, or "I won't guess one" (audit, 22 Oct 2026).
                    (if (com.tekyerema.money.ussd.UssdNavigatorTool.goalRoots(net.key).isNotEmpty())
                        "Ask me again in your own words and I'll find it on ${net.name}'s main menu." else "Nothing was dialled."))
        // "Dial *138#", "open star 5 6 7 hash and buy a midnight bundle": a network menu the user named by its code is walked
        // with them — the brain chooses by their words, and asks where they said nothing (2 October 2026: "Dial star 138
        // hash" placed a phone call to 138, and "run the USSD code *138#" failed as a one-shot request).
        MENU_CODE.find(goal)?.value?.let { code ->
            val menus = checkedCodes() + com.tekyerema.money.ussd.Provider.momoRoots(network)
            if (code in menus) return open(MENU, code, emptyList(), "its menu at ${spokenCode(code)}", "that menu",
                buys = words.isNotEmpty() || amount != null)
        }
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
                open("mashup", net.mashup, listOf(BUY, if (other) OTHER else SELF), "${if (net.name.first().uppercaseChar() in "AEFHILMNORSX" && net.name.length <= 4) "an" else "a"} ${net.name} Mashup bundle", "a combo-bundle menu")
            // "buy data", "buy a data bundle", "I need internet bundles" (airtime/credit stays with Mobile Money).
            Regex("\\b(?:buy|purchase|get|need|want)\\b.{0,24}\\b(?:data|internet|bundles?)\\b|\\bdata bundle\\b").containsMatchIn(g) &&
                !Regex("\\bairtime\\b|\\bcredit\\b|\\btop.?up\\b").containsMatchIn(g) ->
                // MTN *138# (HIGH): buy → Buy Data Bundles → self/other → the bundles (the user chooses) → pay → PIN stop.
                // Telecel *700# / AT *111#: the tree isn't published — open and guide every step.
                // A named kind of bundle ("midnight", "Kokrokoo") is its own row on the bundle menu: the brain presses THAT
                // row, never the plain "Buy Data Bundle" (2 October 2026: "buy an MTN midnight bundle" walked the plain data
                // menu), so the plan stops after its first step.
                if (words.any { it in NAMED_BUNDLES })
                    open("data", net.data, if (net.key == "mtn") listOf(BUY) else emptyList(),
                        "a ${words.first { it in NAMED_BUNDLES }.let { w -> if (w == "kokrokoo") "Kokrokoo" else w }} bundle", "its data menu")
                else open("data", net.data, if (net.key == "mtn") listOf(BUY, DATA_ROW, if (other) OTHER else SELF) else emptyList(),
                    "a data bundle", "its data menu")
            else -> null
        }
    }

    /**
     * The menu's rows as (option number, its words). The A20e's USSD dialog hands the WHOLE menu as one line
     * ("…select an option 1. Proceed to buy bundle (No 4G bonus) 99. More", seen live 2026-09-30), so rows are found
     * anywhere in the text, each ending where the next "n." begins — never only at line starts.
     */
    fun rows(screen: String): List<Pair<String, String>> {
        val text = " " + screen.replace('\n', ' ')
        val spaced = ROW.findAll(text).map { it.groupValues[1] to it.groupValues[2].trim() }.toList()
        if (spaced.size >= 2) return spaced
        return compactRows(text).takeIf { it.size >= 2 } ?: spaced
    }

    /**
     * MTN's Mashup mixes are written with NO space after the option number — "Select your preferred option 1.2.54MB and
     * 1.12Mins 2.3.05MB and 0.9Mins 3.3.56MB and 0.67Mins 4.5.09MB Only" (seen live, 2 October 2026) — so the ordinary
     * row pattern found no options and the screen was read out as one unintelligible run. An option here is "n."
     * directly followed by a decimal figure, and the numbers must count 1, 2, 3 … or it is not a list.
     */
    private fun compactRows(text: String): List<Pair<String, String>> {
        val marks = COMPACT.findAll(text).toList()
        if (marks.size < 2 || marks.withIndex().any { (i, m) -> m.groupValues[1].toIntOrNull() != i + 1 }) return emptyList()
        return marks.mapIndexed { i, m ->
            val end = if (i + 1 < marks.size) marks[i + 1].range.first else text.length
            m.groupValues[1] to text.substring(m.range.last + 1, end).trim()
        }
    }

    private val COMPACT = Regex("(?<![\\d.])(\\d{1,2})\\.(?=\\d+\\.\\d)")

    /** The next auto-step's row on [screen] as its option number, or null (then the guide asks). "2. Buy Data Bundles" → "2". */
    fun autoAnswer(screen: String, step: Regex): String? =
        rows(screen).firstOrNull { (_, words) -> step.containsMatchIn(words) }?.first

    /** The "More"/"Next" row's number on [screen], or null — pressed while a step's row isn't on this page yet. */
    fun moreOption(screen: String): String? =
        rows(screen).firstOrNull { (_, words) -> Regex("(?i)^(?:more|next)\\b").containsMatchIn(words) }?.first
            // MTN's menus page with the hash key and say so on the screen ("… 6) Bank Account 7) # for next", seen live
            // 2 October 2026; the PIN screen of 29 September ends "# for next" too).
            ?: if (HASH_NEXT.containsMatchIn(screen)) "#" else null

    private val HASH_NEXT = Regex("(?i)#\\s*(?:for\\s+)?(?:next|more)\\b")

    /** A row: a number, a separator, then its words up to the next row (or the end). "(No 4G bonus)" must not start one,
     *  so the number can't follow a letter, '(' or a digit directly — it follows a space/start; "4G" never matches ([.):-] needed). */
    private val ROW = Regex("(?<=[\\s])(\\d{1,3})\\s*[.):-]\\s+(.+?)(?=\\s+\\d{1,3}\\s*[.):-]\\s+|$)")

    /** "*138#" spoken: "star 1 3 8 hash" (BankUssd's reader — never a number). */
    fun spokenCode(code: String): String = BankUssd.spokenCode(code)

    private val SPOKEN_CODE = Regex("(?i)(?:\\*|\\bstar\\b|\\basterisk\\b)\\s*((?:(?:\\d+|zero|oh|one|two|three|four|five|six|seven|eight|nine|\\*|star|asterisk)[\\s,.-]*)+?)\\s*(?:#|\\bhash(?:\\s*tag)?\\b|\\bpound\\b)")
    private val DIGIT_WORD = mapOf("zero" to "0", "oh" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4",
        "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "star" to "*", "asterisk" to "*")

    /**
     * A code said the way people say it — "Dial star 138 hash", "star one three eight hash", "*138 hash" — written as the
     * code ("Dial *138#"), or null when [text] says none (or already writes it). "Dial star 138 hash" was taken for a phone
     * number and a real call to 138 was placed (A20e, 2 October 2026). Pure.
     */
    fun withSpokenCodes(text: String): String? {
        var changed = false
        val out = SPOKEN_CODE.replace(text) { m ->
            val body = m.groupValues[1].split(Regex("[\\s,.-]+")).filter { it.isNotBlank() }
                .joinToString("") { t -> DIGIT_WORD[t.lowercase()] ?: if (t.all(Char::isDigit) || t == "*") t else "" }
            if (body.none(Char::isDigit) || body.startsWith("*")) return@replace m.value
            val code = "*$body#"
            if (code != m.value) changed = true
            code
        }
        return if (changed) out else null
    }
}
