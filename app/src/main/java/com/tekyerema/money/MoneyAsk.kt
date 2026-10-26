package com.tekyerema.money

import java.math.BigDecimal

/**
 * What a spoken ENGLISH financial request asks for — decided on the phone, no model (finance audit 2026-10-01).
 *
 * Why: the on-phone rules only understood "send <amount> <currency> to <someone>". The A20e sweep that night showed the
 * rest going to the cloud planner (which drove the Contacts app, or asked for a reference nobody needs) or losing a slot:
 * "I want to send Prince Melanin fifty", "please transfer 50 to …", "pay Prince Melanin 10 cedis", "send 50 cedis 50
 * pesewas" (became 0.5), "one hundred and twenty" (the "and" made it a compound request), "send 5 cedis airtime to Prince
 * Melanin" (airtime for SELF was proposed), "check my balance" (no route at all).
 *
 * Conservative by design: this only NAMES the request and its slots. It never guesses a number, never reads an amount out
 * of a phone number (29 Sep: "Send money to 0553238" became 553238 cedis), and returns null — the planner's, or the older
 * rules' — for Twi, messages and media, bills and merchants, cash-out, loans, "send it back", questions ABOUT sending,
 * limit commands, dialled codes and anything with a second command joined on. Every result is still read back and
 * confirmed by the tool that runs it. Pure → MoneyAskTest.
 */
object MoneyAsk {
    enum class Kind { SEND, AIRTIME, BALANCE_MOMO, BALANCE_AIRTIME, BALANCE_UNCLEAR, STATUS, LAST, SPENT }

    data class Ask(
        val kind: Kind,
        /** Plain cedi figure: "50", "0.5", "50.5", "120". Null = not said. "0" is returned as "0" (the caller refuses it). */
        val amount: String? = null,
        /** A valid Ghana mobile number in 0-form (10 digits from 0; "+233…"/"233…" folded). */
        val number: String? = null,
        /** Digits heard that look like a phone number (7+ digits) but are not a valid 10-digit one — the caller reads them back. */
        val badNumber: String? = null,
        /** A person named instead of a number ("Prince Melanin", "Richmond's Dad", "my mother"). */
        val name: String? = null,
        val reference: String? = null,
        /** AIRTIME only: true = for the user's own line. */
        val forSelf: Boolean = true,
        /** A phone number the recogniser destroyed ("zeroffvtziro ne tsi sir"): neither a name nor a number — the caller asks
         *  for the number again (end-to-end pass, 6 October 2026). The words as heard. */
        val lostNumber: String? = null
    )

    /** The request as a financial ask, or null when it is not one (or is one this layer must leave to the planner). */
    fun parse(text: String): Ask? {
        if (com.tekyerema.agent.MessageToSomeone.matches(text)) return null   // "tell Kofi to send me 50 cedis" is a message, not a send by the user
        // Repaired here for EVERY money request, typed ones too — on purpose: "typed" text is often a keyboard's own
        // dictation, and in a request that is already about money "10 pesos" can only be pesewas. (A typed request
        // that is not about money never reaches a repair: VoiceController.runTurn repairs speech only.)
        val tidied = tidy(heard(text))
        // "send 10 pesewas to Prince, actually make it 20" — a correction inside the sentence: the head is the request, the
        // tail replaces its amount (unit inherited) or its person/number. Before this the tail was read as part of the
        // name ("I couldn't find 'Prince Melanin, actually make 20'" — QA N12, A20e, 3 October 2026).
        SELF_FIX.find(tidied)?.let { m ->
            val head = m.groupValues[1].trim().trimEnd(','); val tail = m.groupValues[2].trim().trimEnd('.', '!')
            if (head.isNotEmpty() && tail.isNotEmpty()) parse(head)?.let { base ->
                val unitless = UNITLESS.matches(tail)
                val tailAmount = if (unitless && PESEWA_WORD.containsMatchIn(head)) amount("$tail pesewas") else amount(tail)
                if (tailAmount != null) return base.copy(amount = tailAmount)
                val digits = tail.filter { it.isDigit() }
                if (digits.length >= 9 && tail.all { it.isDigit() || it == ' ' || it == '+' || it == '-' })
                    return base.copy(number = foldNumber(digits), badNumber = if (foldNumber(digits) == null) digits else null, name = null)
                if (tail.split(' ').size <= 3 && tail.all { it.isLetter() || it == ' ' || it == '\'' || it == '.' })
                    return base.copy(name = tail, number = null, badNumber = null, forSelf = false)
            }
        }
        // A figure that reads two ways ("1,5": 1.5 or 15? "2.500": 2.5 or 2,500?) or is finer than a pesewa ("0.005"): the
        // request is kept, its amount is NOT taken — the flow asks "How much?". It used to send the whole request to the
        // cloud planner, which picked a reading (safety audit 2026-10-05).
        UNCLEAR_FIGURE.findAll(tidied).filter { m -> m.value.count { it.isDigit() } < 9 }.toList().takeIf { it.isNotEmpty() }?.let { hits ->
            var rest = tidied
            for (m in hits.reversed()) rest = rest.removeRange(m.range)
            val base = parse(rest.replace(SPACES, " ").trim()) ?: return null
            return if (base.kind == Kind.SEND || base.kind == Kind.AIRTIME) base.copy(amount = null) else null
        }
        // "… to a telephone number", "… to another number", "… for someone": somebody else, not yet said who. The words are
        // taken out (the recogniser also turns "telephone number" into "telegram number", which read as a message), and
        // the caller asks for the number — the request is never quietly turned into one for the user's own phone.
        val vague = SOME_NUMBER.containsMatchIn(tidied)
        val clean = if (vague) SOME_NUMBER.replace(tidied, " ").replace(SPACES, " ").trim() else tidied
        if (clean.isEmpty()) return null
        val g = clean.lowercase()
        if (looksTwi(g) || CODE.containsMatchIn(g) || FOLLOW_ON.containsMatchIn(g)) return null
        history(g)?.let { return Ask(it) }
        if (balanceShaped(g)) return Ask(balanceOf(g))
        if (LIMIT.containsMatchIn(g)) return null
        // "send Kofi 5 cedis for credit" gives a reason; "send 5 cedis credit to Kofi" buys airtime.
        if (AIRTIME_WORD.containsMatchIn(g) && !FOR_AIRTIME.containsMatchIn(g))
            return (airtime(clean) ?: harvest(clean, airtime = true))
                ?.let { if (vague && it.number == null && it.badNumber == null) it.copy(name = null, forSelf = false) else it }
        return (send(clean) ?: harvest(clean, airtime = false))
            ?.let { if (vague && it.number == null && it.badNumber == null) it.copy(name = null) else it }
    }

    /**
     * The request said in pieces, or twice — the way people talk: "I want you to buy it for a telephone number and the number
     * is 050 879-7323, I want you to buy 2 cedis airtime" (the owner's answer, A20e, 14 Oct 2026). The strict reading gave up
     * on the extra words, and the cloud planner then spent two minutes on web searches and codes typed into the dialer. Here
     * the slots are collected from the whole sentence (R-A10's slot filling): exactly ONE phone number; the amount (the same
     * figure said twice is one; two different figures leave it unsaid, so the flow asks "How much?"); the kind from its words —
     * and only when nothing else is asked for (another action, a bundle, a bill, a question, a refund, a scratch card).
     */
    private fun harvest(clean: String, airtime: Boolean): Ask? {
        val s0 = VIA.replace(clean, " ")
        val g = s0.lowercase()
        if (QUESTION_HEAD.containsMatchIn(g) || ABOUT.containsMatchIn(g) || PRICE.containsMatchIn(g) || BILL.containsMatchIn(g) ||
            CARD.containsMatchIn(g) || BACK.containsMatchIn(g) || DATA_WORD.containsMatchIn(g) || AIR_MEDIA.containsMatchIn(g) ||
            OTHER_ACTION.containsMatchIn(g)) return null
        var t = toks(s0)
        if (t.none { it.low in (if (airtime) AIRTIME_VERBS else SEND_VERBS) }) return null
        if (!airtime && !MONEY_CUE.containsMatchIn(g)) return null
        val numbers = ArrayList<Phone>()
        while (numbers.size < 4) {
            val p = findPhone(t) ?: break
            numbers += p
            t = t.subList(0, p.from) + PHONE_TOK + t.subList(p.to, t.size)
        }
        val valid = numbers.mapNotNull { it.number }.distinct()
        if (valid.size != 1 || numbers.any { it.number == null }) return null
        val figures = ArrayList<String>()
        var rest = t
        while (figures.size < 4) {
            val a = firstAmount(rest) ?: break
            figures += a.value ?: return null          // "two fifty": 2.50 or 250 — the strict reading's question, not a guess
            rest = cut(rest, a)
        }
        val amount = figures.takeIf { f -> f.map { it.toBigDecimal().stripTrailingZeros() }.distinct().size == 1 }?.first()
        return Ask(if (airtime) Kind.AIRTIME else Kind.SEND, amount = amount, number = valid.single(), forSelf = false)
    }

    /**
     * What the recogniser makes of Ghanaian money words: "10 pesos" is ten PESEWAS (heard three times in one live session,
     * 2 October 2026 — and read back as 10 cedis, a hundred times too much). Only after a figure, and never in a request
     * about exchanging or converting currencies.
     */
    fun heard(text: String, aboutMoney: Boolean = false): String = com.tekyerema.voice.GhanaWords.fix(text, aboutMoney)

    private val SOME_NUMBER = Regex("(?i)\\b(?:to|for)\\s+(?:(?:a|an|another|some|one|the|a different|someone(?:'s)?|somebody(?:'s)?)\\s+)(?:(?:other|different|new|telephone|phone|mobile|telegram|momo|mtn|telecel|else)\\s+)*(?:number|line|person)\\b|\\b(?:to|for)\\s+(?:someone|somebody)(?:\\s+else)?\\b(?!')")

    /** An answer to "How much?": "fifty", "50 cedis", "2 cedis 50", "fifty pesewas", "one hundred and twenty" → plain cedis; null when no amount. */
    fun amount(text: String): String? {
        // The ANSWER to "How much?" is heard by the same recognisers: "50 pesos" / "50 ps" answered here used to be
        // taken as fifty CEDIS — the repair was only applied to whole requests (found 2026-10-02).
        var t = toks(tidy(heard(text, aboutMoney = true)))
        val phone = findPhone(t)
        if (phone != null) t = t.subList(0, phone.from) + PHONE_TOK + t.subList(phone.to, t.size)
        return firstAmount(t)?.value
    }

    /** An answer to "Who to?": a number → Ask(kind=SEND, number=… or badNumber=…); otherwise a cleaned name → Ask(kind=SEND, name=…); null when empty. */
    fun recipient(text: String): Ask? {
        val s = tidy(text)
        if (s.isEmpty()) return null
        findPhone(toks(s))?.let { return Ask(Kind.SEND, number = it.number, badNumber = it.bad) }
        val t = dropTails(toks(RECIPIENT_LEAD.replaceFirst(s, "")))
        if (t.isEmpty() || t.size > 5) return null
        val name = joinRaw(t).takeIf { n -> n.any { it.isLetter() } } ?: return null
        if (name.lowercase() in PRONOUNS) return null
        return Ask(Kind.SEND, name = name)
    }

    /** An answer to "Mobile Money or airtime?": BALANCE_MOMO, BALANCE_AIRTIME, or null. */
    fun balanceKind(text: String): Kind? {
        val g = tidy(text).lowercase()
        val momo = PICK_MOMO.containsMatchIn(g)
        val air = PICK_AIRTIME.containsMatchIn(g)
        return when {
            momo && !air -> Kind.BALANCE_MOMO
            air && !momo -> Kind.BALANCE_AIRTIME
            else -> null
        }
    }

    // ------------------------------------------------------------------ send money

    private fun send(clean: String): Ask? {
        val g = clean.lowercase()
        if (QUESTION_HEAD.containsMatchIn(g) || ABOUT.containsMatchIn(g) || MEDIA.containsMatchIn(g) ||
            BACK.containsMatchIn(g) || CASH_OUT.containsMatchIn(g)) return null
        // "… with mobile money", "… from my wallet": money evidence, and not part of anyone's name.
        var moneyWord = false
        val s = VIA.replace(clean) { moneyWord = true; " " }.replace(SPACES, " ").trim()
        val all = toks(s)
        val v = verbIndex(all, SEND_VERBS) ?: return null
        val verb = all[v].low
        var rest = FOR_PRONOUN.replace(dropTails(all.subList(v + 1, all.size)).joinToString(" ") { it.raw }, " ")

        // The reference comes off FIRST, so its words and digits ("for order 5") are never the recipient or the amount.
        var reference: String? = null
        val asRef = AS_REF.find(rest)
        val refWord = if (asRef == null) REF_WORD.find(rest) else null
        val forRef = if (asRef == null && refWord == null) FOR.find(rest) else null
        when {
            asRef != null -> { reference = asRef.groupValues[1]; rest = rest.removeRange(asRef.range) }
            refWord != null -> { reference = refWord.groupValues[1]; rest = rest.substring(0, refWord.range.first) }
            forRef != null -> {
                val tail = forRef.groupValues[1]
                val before = rest.substring(0, forRef.range.first)
                val toIn = TO_IN_REF.find(tail)
                when {
                    findPhone(toks(tail))?.from == 0 -> { rest = "$before to $tail" }   // "for 055…" is the recipient
                    toIn != null -> {                                                   // "for lunch to Kofi"
                        reference = tail.substring(0, toIn.range.first)
                        rest = before + " to " + tail.substring(toIn.range.last + 1)
                    }
                    else -> { reference = tail; rest = before }
                }
            }
        }
        var ref: String? = reference?.trim(*TRIM)?.takeIf { it.isNotEmpty() }

        val restLow = rest.lowercase()
        // A MoMo service ("my yello save") is a menu row, not a person to look up: the planner walks the menu toward it.
        if (DATA_WORD.containsMatchIn(restLow) || AIRTIME_WORD.containsMatchIn(restLow) || BILL.containsMatchIn(restLow) ||
            MoneyServices.NAME.containsMatchIn(restLow)) return null

        var t = toks(rest)
        val phone = findPhone(t)
        if (phone != null) t = t.subList(0, phone.from) + PHONE_TOK + t.subList(phone.to, t.size)
        val toAt = t.indexOfFirst { it.low == "to" }
        var head: List<Tok> = if (toAt >= 0) t.subList(0, toAt) else t
        var toSeg: List<Tok> = if (toAt >= 0) t.subList(toAt + 1, t.size) else emptyList()
        // "… to Kofi to buy food": the second "to" gives a purpose, not a person.
        val to2 = toSeg.indexOfFirst { it.low == "to" }
        if (to2 >= 0) {
            if (ref == null) ref = joinRaw(toSeg.subList(to2 + 1, toSeg.size)).takeIf { it.isNotEmpty() }
            toSeg = toSeg.subList(0, to2)
        }

        var amt = firstAmount(head)
        var afterAmt: List<Tok> = emptyList()
        if (amt != null) {
            afterAmt = leftover(head.subList(amt.to, head.size))
            head = cut(head, amt)
        } else {
            amt = firstAmount(toSeg)
            if (amt != null) toSeg = cut(toSeg, amt)
        }
        if ((head + toSeg).any { it.low in MONEY_WORDS || isCurrency(it.low) || isPesewa(it.low) }) moneyWord = true

        val headName = leftover(head)
        val toName = leftover(toSeg)
        if (headName.isNotEmpty() && toName.isNotEmpty()) return null            // two candidates for the person: not ours
        val nameToks = if (toName.isNotEmpty()) toName else headName
        if (nameToks.size > 5 || nameToks.any { it.low == "and" || it.low == "or" }) return null
        var name: String? = joinRaw(nameToks).takeIf { n -> n.any { it.isLetter() } }
        if (name != null && name.lowercase() in PRONOUNS) name = null             // "send him 50 cedis": who is asked for
        if (phone != null) name = null                                            // a number said out loud wins
        // A number the recogniser destroyed is no one's name: asked for again, never looked up as a contact.
        val lost = name?.takeIf { com.tekyerema.voice.understand.SpokenNumbers.looksLikeLostDigits(it) }
        if (lost != null) name = null

        val hasAmt = amt != null
        val hasWho = phone != null || name != null
        val evidence = amt?.currency == true || moneyWord || when (verb) {
            "transfer", "momo" -> hasAmt || hasWho
            "pay" -> hasAmt && afterAmt.isEmpty()
            else -> hasAmt && hasWho && afterAmt.isEmpty()                         // "send 50 to Kofi", "send Kofi fifty"
        }
        if (!evidence) return null
        if (ref != null && BILL.containsMatchIn(ref.lowercase()) && !hasWho) return null   // "pay 50 cedis for my light bill"
        if (POLITE_Q.containsMatchIn(g) && !hasAmt && !hasWho) return null                 // "can you send money?"
        return Ask(Kind.SEND, amt?.value, phone?.number, phone?.bad, name, ref, lostNumber = lost)
    }

    // ------------------------------------------------------------------ airtime

    /** "Buy airtime to call my wife": what the airtime is FOR, not who it is for, so it is this phone's (audit, 22 Oct 2026: "call
     *  my wife" became the contact to send airtime to). Only that clause is cut: the verb and up to eight words after it, never
     *  "number" or an amount, ending where an amount ("five", "a hundred", "5", "GH₵5"), "for", a comma, a question mark or the end
     *  comes — the recogniser writes no commas, so "… my wife five cedis" ends at "five" and "…, for Kofi" keeps its person (the
     *  models session's three reviews, 23 Oct 2026). Never after send / give / transfer: "airtime to phone number 024…" keeps its
     *  number. Lazy: the number words come from the same tables the amounts are read with, defined further down. */
    private val PURPOSE_CLAUSE by lazy {
        val num = (UNITS.keys + TEENS.keys + TENS.keys + listOf("hundred", "thousand", "half", "cedi", "cedis", "ghana", "pesewa", "pesewas"))
            .joinToString("|")
        Regex("(?i)\\s+(?:in order\\s+|so as\\s+)?to\\s+(?:call|ring|phone|talk|chat|text|browse)\\b" +
            "(?:\\s+(?!(?:for|number|a\\s+(?:hundred|thousand)|an?\\s+half|$num)\\b)[\\p{L}'’.\\-]+){0,8}?" +
            // A currency shape only ("GHS5", "GH₵ 5", "₵5", "¢5") — never a bare "gh": "call Mr Ghartey" is a name (review 4 N-A).
            "(?=\\s+(?:for\\b|an?\\s+(?:hundred|thousand|half)\\b|(?:$num)\\b|\\d|gh[sc₵¢]?\\s?\\d|gh[sc₵¢]\\b|[₵¢])|\\s*[,?]|\\s*$)")
    }
    private val GIVES_AIR = Regex("(?i)\\b(?:send|give|transfer|gift|share)\\b")

    private fun airtime(clean: String): Ask? {
        val said = VIA.replace(clean, " ")
        val s0 = if (GIVES_AIR.containsMatchIn(said)) said else PURPOSE_CLAUSE.replace(said, "")
        // The guards read what was SAID: a question after the clause ("…, how much does it cost") is still a question.
        val g = said.lowercase()
        if (DATA_WORD.containsMatchIn(g) || QUESTION_HEAD.containsMatchIn(g) || ABOUT.containsMatchIn(g) || PRICE.containsMatchIn(g) ||
            BILL.containsMatchIn(g) || CARD.containsMatchIn(g) || BACK.containsMatchIn(g) || AIR_MEDIA.containsMatchIn(g)) return null
        // "for me", "my phone", "this number": the user's own line — said or not, that is the default.
        val all = toks(SELF.replace(s0, " ").replace(SPACES, " ").trim())
        // "10 pesewas airtime to a telephone number" — said with no verb at all (live A20e 2026-10-02): an amount and the
        // word airtime are the request. Without a verb AND without an amount it is not one ("my airtime finished").
        val v = verbIndex(all, AIRTIME_VERBS)
        var rest: List<Tok> = if (v == null) all else all.subList(v + 1, all.size)
        if (v != null && all[v].low == "top") {
            if (rest.firstOrNull()?.low != "up") return null
            rest = rest.drop(1)
        }
        rest = dropTails(rest)
        val phone = findPhone(rest)
        val withPhone: List<Tok> =
            if (phone != null) rest.subList(0, phone.from) + PHONE_TOK + rest.subList(phone.to, rest.size) else rest
        var t = withPhone.filterIndexed { i, k ->
            !(k.low in AIRTIME_NOUNS || (k.low == "call" && withPhone.getOrNull(i + 1)?.low == "credit") ||
                (k.low == "top" && withPhone.getOrNull(i + 1)?.low == "up") || (k.low == "up" && withPhone.getOrNull(i - 1)?.low == "top"))
        }
        val amt = firstAmount(t)
        if (v == null && amt == null) return null
        if (amt != null) t = cut(t, amt)
        val other = t.any { it.low in OTHER_PRON }
        val left = t.filter {
            it.low != PHONE_LOW && it.low.isNotEmpty() && it.low !in FILL_AIR && it.low !in OTHER_PRON &&
                it.low !in MONEY_WORDS && it.low !in NETWORK_WORDS && !isCurrency(it.low) && !isPesewa(it.low)
        }
        if (left.size > 4 || left.any { it.low == "and" || it.low == "or" }) return null
        val heardName = if (phone != null) null else joinRaw(left).takeIf { n -> n.any { it.isLetter() } }
        val lost = heardName?.takeIf { com.tekyerema.voice.understand.SpokenNumbers.looksLikeLostDigits(it) }
        val name = if (lost != null) null else heardName
        val forSelf = phone == null && name == null && lost == null && !other
        return Ask(Kind.AIRTIME, amt?.value, phone?.number, phone?.bad, name, null, forSelf, lostNumber = lost)
    }

    // ------------------------------------------------------------------ balances, status, history

    /** Every word is one a balance question is made of ("set white balance", "bank balance", "battery left" are not). */
    private fun balanceShaped(g: String): Boolean {
        val w = g.replace(NOT_WORD, " ").trim().split(SPACES).filter { it.isNotEmpty() }
        if (w.isEmpty() || w.size > 12 || w.any { it !in BAL_VOCAB }) return false
        val s = w.joinToString(" ")
        return "balance" in w || (s.contains("how much") && BAL_TAIL.containsMatchIn(s))
    }

    private fun balanceOf(g: String): Kind = when {
        BAL_MOMO.containsMatchIn(g) -> Kind.BALANCE_MOMO
        BAL_AIRTIME.containsMatchIn(g) -> Kind.BALANCE_AIRTIME
        BAL_MONEY.containsMatchIn(g) -> Kind.BALANCE_MOMO
        BAL_LINE.containsMatchIn(g) -> Kind.BALANCE_AIRTIME
        else -> Kind.BALANCE_UNCLEAR
    }

    private fun history(g: String): Kind? {
        val q = LEAD_Q.replaceFirst(g.replace(PUNCT, " ").replace(SPACES, " ").trim(), "")
        return when {
            SPENT.any { it.containsMatchIn(q) } -> Kind.SPENT
            STATUS.any { it.containsMatchIn(q) } -> Kind.STATUS
            LAST.any { it.containsMatchIn(q) } -> Kind.LAST
            else -> null
        }
    }

    // ------------------------------------------------------------------ words

    private data class Tok(val raw: String, val low: String)

    private const val PHONE_LOW = "<phone>"
    private val PHONE_TOK = Tok("", PHONE_LOW)
    private val TRIM = charArrayOf(' ', ',', '.', ';', ':', '!', '?', '"', '\'', '(', ')')

    /** "10p" / "50ps" — the everyday shorthand for pesewas ("momo 10p prince melanin" asked how much; QA N02, 3 October 2026).
     *  Never "10pm" / "10 p.m.". */
    private val PESEWA_SHORT = Regex("(?i)\\b(\\d+)\\s?ps?\\b(?!\\.?\\s*m\\b)")

    private fun tidy(text: String): String =
        WAKE.replaceFirst(text.replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
            .replace(SPACES, " ").trim(), "").trim().trimEnd('.', '!', '?', ',', ';', ' ')
            .replace(PESEWA_SHORT, "$1 pesewas")

    private fun looksTwi(g: String): Boolean = g.any { it == 'ɛ' || it == 'ɔ' } || TWI_WORD.containsMatchIn(g)

    private fun toks(s: String): List<Tok> = s.split(' ').filter { it.isNotBlank() }
        .map { Tok(it, it.lowercase().trim(',', ';', ':', '.', '!', '?', '"', '(', ')')) }

    private fun joinRaw(t: List<Tok>): String = t.joinToString(" ") { it.raw }.trim(*TRIM)

    /** The request's verb: the first word after the polite lead-in ("I want to", "please", "can you"), or null. */
    private fun verbIndex(t: List<Tok>, verbs: Set<String>): Int? {
        for (i in t.indices) {
            if (i > 8) return null
            val w = t[i].low
            if (w in verbs) return i
            if (w !in LEAD) return null
        }
        return null
    }

    private fun dropTails(t: List<Tok>): List<Tok> {
        var r = t
        while (r.isNotEmpty()) {
            val last = r.last().low
            val prev = r.getOrNull(r.size - 2)?.low
            r = when {
                (prev == "thank" && last == "you") || (prev == "right" && last == "now") -> r.dropLast(2)
                last in TAILS || last.isEmpty() -> r.dropLast(1)
                else -> return r
            }
        }
        return r
    }

    /** What is left of a segment once the number, the amount, money words and filler are gone: the person's name. */
    private fun leftover(t: List<Tok>): List<Tok> = t.filter {
        it.low != PHONE_LOW && it.low.isNotEmpty() && it.low !in FILL && it.low !in MONEY_WORDS && !isCurrency(it.low) && !isPesewa(it.low) &&
            // "send 5 cedis to Kofi on MTN": the network is how, not who (robustness audit 2026-10-05 — was "Kofi MTN")
            it.low !in NETWORK_WORDS
    }

    private fun cut(t: List<Tok>, a: Amt): List<Tok> = t.subList(0, a.from) + t.subList(a.to, t.size)

    // ------------------------------------------------------------------ phone numbers

    /** [number] valid 0-form, or [bad] = the digits heard; tokens [from, to). */
    private data class Phone(val number: String?, val bad: String?, val from: Int, val to: Int)

    /** A token made only of digits ("055", "+233", "055-209-3690"), as its digits; else null ("0.5", "1,250", "fifty"). */
    private fun digitsOf(low: String): String? {
        val t = low.removePrefix("+")
        if (t.isEmpty() || t.startsWith("-") || t.endsWith("-")) return null
        val d = t.replace("-", "")
        return if (d.isNotEmpty() && d.all { it in '0'..'9' }) d else null
    }

    /**
     * The phone number said in [t], digits in one token or in groups ("055 209 3690", "0 5 5 2 …", "+233 55 209 3690").
     * A run starts at a 0-led or +233/233-led group and takes groups until it is whole — so the amount beside it ("…
     * 0552093690 50 cedis") is never swallowed, and the number is never read as an amount.
     */
    private fun findPhone(t: List<Tok>): Phone? {
        var i = 0
        while (i < t.size) {
            val low = t[i].low
            val d0 = digitsOf(low)
            if (d0 != null) {
                val plus = low.startsWith("+")
                val zeroLed = !plus && d0.startsWith("0")
                if (zeroLed || plus || d0.startsWith("233")) {
                    val target = if (zeroLed) 10 else 12
                    val sb = StringBuilder(d0)
                    var j = i + 1
                    while (sb.length < target && j < t.size) {
                        if (t[j].low.startsWith("+")) break
                        val d = digitsOf(t[j].low) ?: break
                        // "… 055209369 50 cedis": the figure before a currency word is the amount, not more of the number
                        if (j + 1 < t.size && (isCurrency(t[j + 1].low) || isPesewa(t[j + 1].low))) break
                        sb.append(d); j++
                    }
                    val all = sb.toString()
                    val number = when {
                        zeroLed -> all.takeIf { it.length == 10 }
                        all.length == 12 && all.startsWith("233") -> "0" + all.substring(3)
                        all.length == 13 && all.startsWith("2330") -> all.substring(3)
                        else -> null
                    }
                    if (number != null) return Phone(number, null, i, j)
                    if ((zeroLed && all.length >= 3) || all.length >= 7) return Phone(null, all, i, j)
                    // "233 cedis", a lone "0": a figure, not a number
                } else if (d0.length >= 7) return Phone(null, d0, i, i + 1)
            }
            i++
        }
        return null
    }

    // ------------------------------------------------------------------ amounts

    /** [value] plain cedis, null when two figures sit side by side and can't be told apart ("two fifty"); tokens [from, to). */
    private data class Amt(val value: String?, val from: Int, val to: Int, val currency: Boolean)

    private data class Num(val value: BigDecimal, val next: Int, val prefixed: Boolean)

    private val HUNDRED = BigDecimal(100)
    private val TEN = BigDecimal.TEN

    private fun isCurrency(low: String): Boolean = low in CURRENCY
    private fun isPesewa(low: String): Boolean = low in PESEWA

    private fun plain(v: BigDecimal): String = if (v.signum() == 0) "0" else v.stripTrailingZeros().toPlainString()

    /** The first amount in [t] (never the phone number: the caller has already replaced it). */
    private fun firstAmount(t: List<Tok>): Amt? {
        for (i in t.indices) {
            val a = amountAt(t, i) ?: continue
            // Two figures side by side ("two fifty") could be 2.50 or 250 — the caller asks rather than guesses.
            val second = if (a.to < t.size) numberAt(t, a.to) else null
            return if (second != null) Amt(null, a.from, second.next, a.currency) else a
        }
        return null
    }

    /** "50", "50 cedis", "GHS 50", "fifty pesewas", "50 cedis (and) 50 pesewas", "2 cedis 50" starting at [i]. */
    private fun amountAt(t: List<Tok>, i: Int): Amt? {
        val n = numberAt(t, i) ?: return null
        var k = n.next
        if (k + 1 < t.size && t[k].low == "ghana" && isCurrency(t[k + 1].low)) k++
        // The worst-harm error: "10 pesewas" typed as 10 cedis is a hundred times too much.
        if (!n.prefixed && k < t.size && isPesewa(t[k].low)) return Amt(plain(n.value.movePointLeft(2)), i, k + 1, true)
        var end = n.next
        var currency = n.prefixed
        if (k < t.size && isCurrency(t[k].low)) { currency = true; end = k + 1 }
        var value = n.value
        if (currency && n.value.scale() <= 0) {
            var m = end
            val and = m < t.size && t[m].low == "and"
            if (and) m++
            val sub = numberAt(t, m)
            if (sub != null && !sub.prefixed && sub.value.scale() <= 0 && sub.value.signum() > 0 && sub.value < HUNDRED) {
                val pesewas = sub.next < t.size && isPesewa(t[sub.next].low)
                val bare = !and && sub.value >= TEN &&
                    !(sub.next < t.size && (isCurrency(t[sub.next].low) || t[sub.next].low == "ghana"))
                if (pesewas) { value = n.value + sub.value.movePointLeft(2); end = sub.next + 1 }
                else if (bare) { value = n.value + sub.value.movePointLeft(2); end = sub.next }      // "2 cedis 50"
            }
        }
        return Amt(plain(value), i, end, currency)
    }

    /** A figure ("50", "2.50", "1,250", "GHS50", "GHS 50") or number words at [i]. Never 7+ digits or a 0-led run. */
    private fun numberAt(t: List<Tok>, i: Int): Num? {
        if (i < 0 || i >= t.size) return null
        val low = t[i].low
        if (low in CUR_PREFIX && i + 1 < t.size) figure(t[i + 1].low)?.let { return Num(it.first, i + 2, true) }
        figure(low)?.let {
            // "5 thousand cedis", "2 hundred": a figure with a scale word is one number — it used to be read as 5 cedis
            // to a person called "… thousand" (code review, 2026-10-02).
            val scale = when (t.getOrNull(i + 1)?.low) { "hundred" -> 100; "thousand" -> 1000; else -> null }
            return if (scale != null && it.first.scale() <= 0) Num(it.first.multiply(BigDecimal(scale)), i + 2, it.second)
            else Num(it.first, i + 1, it.second)
        }
        return wordNumber(t, i)?.let { Num(BigDecimal(it.first), it.second, false) }
    }

    /** (value, had a currency prefix) for one token, or null. */
    private fun figure(low: String): Pair<BigDecimal, Boolean>? {
        val m = FIGURE.matchEntire(low) ?: return null
        val whole = m.groupValues[2].replace(",", "")
        if (whole.length >= 7 || (whole.length >= 2 && whole.startsWith("0"))) return null
        return BigDecimal(whole + m.groupValues[3]) to m.groupValues[1].isNotEmpty()
    }

    // ---- number words, to 9,999 and a little beyond ("one hundred and twenty", "a hundred", "twenty-five")
    private const val L_NONE = 0
    private const val L_UNIT = 1
    private const val L_TEEN = 2
    private const val L_TENS = 3
    private const val L_HUNDRED = 4
    private const val L_THOUSAND = 5
    private const val L_AND = 6
    private const val L_A = 7

    private data class NS(val total: Int = 0, val cur: Int = 0, val last: Int = L_NONE, val any: Boolean = false)

    private val UNITS = mapOf("zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9)
    private val TEENS = mapOf("ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15,
        "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19)
    private val TENS = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fourty" to 40, "fifty" to 50, "sixty" to 60,
        "seventy" to 70, "eighty" to 80, "ninety" to 90)

    private fun isNumWord(w: String?): Boolean = w != null && (w in UNITS || w in TEENS || w in TENS)

    /** The state after [w], or null when [w] can't continue the number ([next] = the word after it). */
    private fun take(s: NS, w: String, next: String?): NS? {
        val afterScale = s.last == L_NONE || s.last == L_HUNDRED || s.last == L_THOUSAND || s.last == L_AND
        UNITS[w]?.let { v ->
            return when {
                v == 0 -> if (s.last == L_NONE) s.copy(last = L_UNIT, any = true) else null
                afterScale || s.last == L_TENS -> s.copy(cur = s.cur + v, last = L_UNIT, any = true)
                else -> null
            }
        }
        TEENS[w]?.let { v -> return if (afterScale) s.copy(cur = s.cur + v, last = L_TEEN, any = true) else null }
        TENS[w]?.let { v -> return if (afterScale) s.copy(cur = s.cur + v, last = L_TENS, any = true) else null }
        return when (w) {
            "hundred" -> if ((s.last == L_UNIT || s.last == L_TEEN || s.last == L_A) && s.cur in 1..99)
                s.copy(cur = s.cur * 100, last = L_HUNDRED) else null
            "thousand" -> if (s.last != L_NONE && s.last != L_AND && s.last != L_THOUSAND && s.cur > 0 && s.total == 0)
                s.copy(total = s.cur * 1000, cur = 0, last = L_THOUSAND) else null
            // "and" only inside a number: after a scale word, before a number word — never the "and" of a second command.
            "and" -> if ((s.last == L_HUNDRED || s.last == L_THOUSAND) && isNumWord(next)) s.copy(last = L_AND) else null
            "a", "an" -> if (s.last == L_NONE && (next == "hundred" || next == "thousand")) s.copy(cur = 1, last = L_A, any = true) else null
            else -> null
        }
    }

    /** (value, index after the last word of the number) for the number words starting at [from], or null. */
    private fun wordNumber(t: List<Tok>, from: Int): Pair<Int, Int>? {
        var s = NS()
        var good: Pair<Int, Int>? = null
        var i = from
        while (i < t.size) {
            val parts = t[i].low.split('-').filter { it.isNotEmpty() }
            if (parts.isEmpty()) break
            val nextTok = t.getOrNull(i + 1)?.low?.split('-')?.firstOrNull { it.isNotEmpty() }
            var cur: NS? = s
            for ((k, p) in parts.withIndex()) {
                val c = cur ?: break
                cur = take(c, p, parts.getOrNull(k + 1) ?: nextTok)
            }
            val ns = cur ?: break
            s = ns
            i++
            if (s.any && s.last != L_AND) good = (s.total + s.cur) to i
        }
        return good
    }

    // ------------------------------------------------------------------ vocabulary

    private val SPACES = Regex("\\s+")
    /** Another kind of action in the same sentence — never collected into a money request (harvest). */
    private val OTHER_ACTION = Regex("\\b(?:call|ring|text|message|whatsapp|tell|inform|notify|remind|open|play|read|show|search|" +
        "set|turn|switch|navigate|alarm|timer|check|balance|limit|withdraw|cash ?out|loan|borrow|save|savings)\\b")
    /** A send of MONEY says so: a money word or a currency next to the figure. */
    private val MONEY_CUE = Regex("\\b(?:money|momo|mobile money|cash|cedis?|pesewas?|ghs|ghc)\\b|[₵¢]")
    private val NOT_WORD = Regex("[^a-z' ]")
    private val PUNCT = Regex("[?.!,]")
    private val WAKE = Regex("^(?:(?:hey|hi|ok|okay)[ ,]+)?t[ɛeƐE]kyer[ɛeƐE]ma[ ,:.!]*", RegexOption.IGNORE_CASE)
    private val TWI_WORD = Regex("\\b(?:fa|ma|sika)\\b")
    private val CODE = Regex("[*#]\\s?\\d|\\bstar\\s+\\d|\\bhash\\b")
    /** A second command joined on — the planner's. "one hundred and twenty" has no verb after its "and". */
    private val FOLLOW_ON = Regex("(?:\\band then\\b|\\bafter that\\b|\\bthen\\b|\\band\\b|;)\\s*(?:also\\s+|please\\s+|kindly\\s+)?" +
        "(?:call|text|tell|open|send|check|buy|message|whatsapp|read|play|set|turn|remind|go|ask|dial|ring|search|show|take|start|" +
        "launch|pay|transfer|let|inform|notify|close|switch)\\b")
    private val LIMIT = Regex("\\blimits?\\b")
    private val QUESTION_HEAD = Regex("^(?:how|what|whats|why|when|where|which|who|is|are|does|do|did|should|am|has|have|was)\\b")
    private val ABOUT = Regex("\\bcheapest\\b|\\bcheaper\\b|\\bcompare\\b|\\bhow much does\\b|\\bcost (?:of|to)\\b|\\bhow to\\b|" +
        "\\bhow do i\\b|\\bhow can i\\b|\\bfees? for\\b|\\bcharges? for\\b")
    private val POLITE_Q = Regex("^(?:please\\s+)?(?:can|could|will|would|may)\\s+(?:you|i|we)\\b")
    private val MEDIA = Regex("\\b(?:messages?|texts?|sms|whatsapp|telegram|e-?mails?|mail|photos?|pictures?|pics?|images?|screenshots?|" +
        "files?|documents?|links?|location|voice note|voice message|contact card|videos?|audio|recording|calls?|songs?|music)\\b")
    private val AIR_MEDIA = Regex("\\b(?:messages?|sms|whatsapp|telegram|e-?mails?|photos?|pictures?|screenshots?|files?|links?)\\b")
    /** "Send it back", "return the money": the shape of the commonest scam — never proposed from here. */
    private val BACK = Regex("\\bback\\b|\\breturn\\b|\\brefund\\b|\\brevers(?:e|al|ed)\\b")
    private val CASH_OUT = Regex("\\bcash\\s?-?out\\b|\\bwithdraw(?:al)?\\b|\\bloans?\\b|\\bborrow\\b")
    private val BILL = Regex("\\b(?:bills?|ecg|light|electricity|prepaid|postpaid|water|dstv|gotv|startimes|subscription|merchant|till|" +
        "shop|store|momopay|momo pay|bank|account|ticket|fare|rent|fees?|tuition|insurance|invoice|vendor)\\b")
    private val DATA_WORD = Regex("\\b(?:data|bundles?|internet|mashup|gigs?|gb|mb|megabytes?|gigabytes?|midnight)\\b|\\d\\s?(?:gb|mb)\\b")
    private val AIRTIME_WORD = Regex("\\b(?:airtime|credit|units?|top[- ]?up|recharge)\\b")
    private val FOR_AIRTIME = Regex("\\bfor\\s+(?:his\\s+|her\\s+|their\\s+|my\\s+|some\\s+|call\\s+)?(?:airtime|credit|units|top[- ]?up)\\b")
    private val PRICE = Regex("\\bprices?\\b|\\bcosts?\\b|\\bcheaper\\b|\\bcheapest\\b|\\bcompare\\b|\\brates?\\b")
    private val CARD = Regex("\\bcards?\\b|\\bscratch\\b|\\bvouchers?\\b")
    private val VIA = Regex("\\b(?:via|on|with|by|through|using|from)\\s+(?:my\\s+)?(?:mtn\\s+)?(?:momo|mobile\\s+money)(?:\\s+(?:wallet|account))?\\b|" +
        "\\bfrom\\s+my\\s+(?:wallet|account)\\b", RegexOption.IGNORE_CASE)
    private val SELF = Regex("\\b(?:(?:for|to|on|onto|into)\\s+)?(?:me|myself|(?:my|this)\\s+(?:own\\s+)?(?:phone|number|line|sim|mobile))\\b|" +
        // "top up my airtime", "recharge my own credit": the user's airtime (robustness audit 2026-10-05 — "my" was a name)
        "\\bmy\\s+(?:own\\s+)?(?=(?:airtime|credit|credits|units?)\\b)", RegexOption.IGNORE_CASE)
    /** A network named in an airtime request says which network, never who it is for ("buy MTN airtime of 5 cedis"). */
    private val NETWORK_WORDS = setOf("mtn", "mtn's", "telecel", "vodafone", "airteltigo", "airtel", "tigo", "glo")
    /** "for me" / "for him" at the end of a send is not a reference ("for her birthday" is). */
    private val FOR_PRONOUN = Regex("\\bfor\\s+(?:me|him|her|them|us|myself)\\b(?=\\s*\$|\\s*,|\\s+to\\b)", RegexOption.IGNORE_CASE)
    private val AS_REF = Regex("\\b(?:use|with|put|make)\\s+(.+?)\\s+as\\s+(?:the\\s+|my\\s+)?(?:reference|ref|narration|remark)\\b",
        RegexOption.IGNORE_CASE)
    private val REF_WORD = Regex("(?:\\bwith\\s+)?(?:\\b(?:the|a)\\s+)?\\b(?:reference|ref|narration|remark)\\b\\s*:?\\s*(?:(?:is|of|as|should be)\\s+)?(.+)\$",
        RegexOption.IGNORE_CASE)
    private val FOR = Regex("\\bfor\\s+(.+)\$", RegexOption.IGNORE_CASE)
    private val TO_IN_REF = Regex("\\s+to\\s+", RegexOption.IGNORE_CASE)
    private val RECIPIENT_LEAD = Regex("^(?:(?:send|give|pay|transfer)\\s+(?:it|the money|them|that)\\s+)?(?:to\\s+)?" +
        "(?:(?:it's|its|it is|that's|that is)\\s+)?", RegexOption.IGNORE_CASE)
    private val FIGURE = Regex("^(gh[sc₵¢]|₵|¢)?(\\d{1,3}(?:,\\d{3})+|\\d+)(\\.\\d{1,2})?\$")

    private val SEND_VERBS = setOf("send", "transfer", "momo", "pay", "give")
    private val AIRTIME_VERBS = setOf("buy", "get", "purchase", "send", "transfer", "give", "load", "recharge", "top", "topup", "top-up")
    private val LEAD = setOf("i", "i'd", "i'll", "want", "wanna", "to", "would", "like", "need", "please", "kindly", "can", "could",
        "will", "you", "help", "me", "let", "let's", "lets", "us", "just", "quickly", "now", "hey", "hi", "ok", "okay", "wish",
        "go", "ahead", "abeg", "pls", "we", "may")
    private val TAILS = setOf("please", "now", "today", "quickly", "asap", "immediately", "thanks", "pls", "abeg", "ok", "okay")
    private val CURRENCY = setOf("cedi", "cedis", "ghs", "ghc", "gh₵", "gh¢", "₵", "¢")
    private val CUR_PREFIX = setOf("ghs", "ghc", "gh₵", "gh¢", "₵", "¢")
    /** Digits said as a Ghana number, folded to the 0-form (10 digits), or null when they are not one. */
    private fun foldNumber(digits: String): String? = when {
        digits.length == 10 && digits.startsWith("0") -> digits
        digits.length == 12 && digits.startsWith("233") -> "0" + digits.substring(3)
        digits.length == 13 && digits.startsWith("2330") -> digits.substring(3)
        else -> null
    }

    /** The head of a request, then a correction: ", actually make it 20" / "no wait, 2 cedis" / "I mean Kofi". */
    private val SELF_FIX = Regex("(?i)^(.+?)[,;]?\\s+(?:actually|no wait|wait no|no,|i mean|i meant|sorry|rather|make that)[,\\s]+" +
        "(?:(?:make|change) (?:it|that|the amount)(?: to)?\\s+|it'?s\\s+|it should be\\s+|let'?s say\\s+|say\\s+|to\\s+)?(.+)$")
    private val UNITLESS = Regex("^\\d+(?:\\.\\d+)?$")
    private val UNCLEAR_FIGURE = Regex("(?i)(?<![\\d.,])(?:(?:gh[sc₵¢]|₵|¢)\\s?)?(?:\\d+,\\d{1,2}|\\d+\\.\\d{3,}|\\d+(?:\\.\\d+){2,})(?![\\d.,]*\\d)")
    private val PESEWA_WORD = Regex("(?i)\\bpesewas?\\b")
    private val PESEWA = setOf("pesewa", "pesewas", "pese")
    private val MONEY_WORDS = setOf("money", "cash", "momo", "funds")
    private val FILL = setOf("some", "of", "worth", "amount", "sum", "about", "exactly", "only", "just", "the", "a", "an", "number",
        "phone", "mobile", "it", "that", "this", "please", "on", "with", "by", "ghana")
    private val FILL_AIR = setOf("to", "for", "on", "of", "worth", "some", "with", "the", "a", "an", "number", "phone", "mobile",
        "line", "that", "this", "it", "in", "into", "onto", "please", "ghana")
    private val AIRTIME_NOUNS = setOf("airtime", "credit", "credits", "unit", "units", "top-up", "topup", "recharge")
    private val PRONOUNS = setOf("him", "her", "them", "me", "us", "you", "myself", "someone", "somebody")
    private val OTHER_PRON = setOf("him", "her", "them", "someone", "somebody")

    private val BAL_VOCAB = setOf("please", "can", "could", "you", "kindly", "i", "i'd", "want", "to", "would", "like", "need", "let",
        "me", "help", "check", "tell", "what", "what's", "whats", "is", "are", "read", "show", "give", "say", "know", "see", "get",
        "hear", "find", "out", "my", "the", "current", "available", "remaining", "balance", "on", "in", "of", "for", "how", "much",
        "left", "do", "have", "got", "now", "there", "a", "quick", "again", "just", "momo", "mobile", "money", "mtn", "wallet",
        "account", "cash", "airtime", "credit", "call", "units", "phone", "sim", "line")
    private val BAL_TAIL = Regex("\\bdo i have\\b|\\bhave i got\\b|\\bi have\\b|\\bleft\\b|\\bremaining\\b|\\bis (?:in|on|there)\\b")
    private val BAL_MOMO = Regex("\\bmomo\\b|\\bmobile money\\b|\\bwallet\\b")
    private val BAL_AIRTIME = Regex("\\bairtime\\b|\\bcredit\\b|\\bunits\\b")
    private val BAL_MONEY = Regex("\\bmoney\\b|\\bcash\\b|\\baccount\\b")
    private val BAL_LINE = Regex("\\bphone\\b|\\bsim\\b|\\bline\\b")
    private val PICK_MOMO = Regex("\\bmomo\\b|\\bmobile money\\b|\\bwallet\\b|\\bmoney\\b|\\bcash\\b|\\bfirst\\b")
    private val PICK_AIRTIME = Regex("\\bairtime\\b|\\bcredit\\b|\\bunits?\\b|\\bsecond\\b|\\blast\\b")

    private val LEAD_Q = Regex("^(?:(?:please|kindly|can you|could you|tell me|check|do you know|i want to know|let me know)\\s+)+")
    /** "my last money transfer", "the momo payment", "that transaction": a payment named with its usual words. The
     *  "last … money transfer" form went to the cloud planner, which opened the myMTN app and tapped round it for 100
     *  seconds (A20e, 2 October 2026). */
    private const val TX = "(?:(?:last|latest|recent|most recent|previous) )?(?:(?:money|momo|mobile money|cash) )?" +
        "(?:money|payment|transfer|transaction|momo|mobile money|send|cash)"
    private const val Q_TAIL = "(?: to [a-z' ]{1,30})?(?: yet| now| already)?\$"
    private val STATUS = listOf(
        Regex("^did (?:it|that|this|(?:the|my|that|this) $TX)(?: to [a-z' ]{1,30}?)? " +
            "(?:go through|go|work|succeed|send|get sent|complete|finish|arrive|reach (?:him|her|them))$Q_TAIL"),
        Regex("^did (?:he|she|they|[a-z']+(?: [a-z']+)?) (?:get|receive) (?:the|my|that|his|her|their) " +
            "(?:money|payment|transfer|cash|momo)(?: yet| now| already)?\$"),
        Regex("^(?:was|is) (?:it|that|(?:the|my|that) $TX) (?:successful|sent|done|completed|complete|through)$Q_TAIL"),
        Regex("^has (?:it|that|(?:the|my|that) $TX) " +
            "(?:been sent|gone through|gone|been received|arrived|reached (?:him|her|them)|been successful|been completed)$Q_TAIL"),
        Regex("^(?:if|whether) (?:it|that|(?:the|my|that) $TX) (?:went through|has gone through|was successful|went|worked|was sent|has been sent)$Q_TAIL"),
        Regex("^(?:what(?:'s| is) )?(?:the |my )?(?:payment|transfer|transaction|momo) status\$")
    )
    private val LAST = listOf(
        Regex("\\b(?:my|the) (?:last|latest|most recent|previous) (?:(?:momo|mobile money|money|cash) )?(?:transaction|payment|transfer|momo|send)\\b"),
        Regex("^who did i (?:last|just) (?:send|pay|transfer)\\b|^who did i (?:send|pay|transfer)\\b.*\\b(?:last|just now|recently)\\b"),
        Regex("^who (?:was|is) the last (?:person|one) i (?:sent|paid)\\b")
    )
    private val SPENT = listOf(
        Regex("^how much (?:money |cash )?(?:have i|did i|i have|i've) (?:sent|send|spent|spend|transferred|transfer|paid|pay)(?: out)?" +
            "(?: (?:on|with|via|by|through) (?:momo|mobile money))?(?: so far today| today| so far| this (?:day|morning|afternoon|evening))?" +
            "(?: (?:on|with|via|by|through) (?:momo|mobile money))?\$"),
        Regex("^(?:what(?:'s| is) )?(?:my )?total (?:sent|spent|spending)(?: today| so far)?\$")
    )
}
