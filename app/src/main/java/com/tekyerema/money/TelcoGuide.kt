package com.tekyerema.money

import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.LocalBrain
import org.json.JSONObject

/**
 * The guided NETWORK menu — data bundles, Mashup, offers, data balance — decided on the phone every step, before any
 * brain (MoneyPlan.decide), online or offline. The telco twin of [BankGuide], plus the hybrid the USSD research
 * recommends (ghana_ussd_flows.md, Recommendation): the researched steps are AUTO-taken, but only when a menu row's
 * own words on the LIVE screen match ("1. Buy Data Bundles" is pressed because the screen says so, never because the
 * research said "1") — any screen that doesn't match is read aloud and the user chooses. A payment confirmation or a
 * PIN ends the task with the user (Secure Input; nothing is paid without the user's own PIN).
 */
object TelcoGuide {
    const val RATIONALE = "local:guided telco USSD"
    const val STOPPED = "Okay — I've stopped. Press Cancel on the network's screen to close it; nothing is paid without your PIN."
    private const val AGAIN = "Again — "
    private const val REASON = "telco"

    /** The step for a network-menu request, or null when [goal] isn't one. */
    fun decide(goal: String, history: List<String>, session: TelcoSession.View? = TelcoSession.current(),
               network: String? = simNetwork()): AgentDecision? {
        if (history.isEmpty()) {
            return when (val req = TelcoUssd.parse(goal, network) ?: return null) {
                is TelcoUssd.Request.Unsupported -> AgentDecision.Done(req.line)
                is TelcoUssd.Request.Open -> open(req.flow)
            }
        }
        // Mid-task: only OUR task (a telco step, the navigator's hand-back, or our ask in history) is continued here.
        val ours = history.any { it.startsWith("${TelcoUssdTool.NAME}(") || it.startsWith("${com.tekyerema.money.ussd.UssdNavigatorTool.NAME}(") }
        if (!ours && TelcoUssd.parse(goal, network) == null) return null
        val last = LocalBrain.lastStep(history)
        return when {
            last.startsWith("ask_user(") && last.contains("→ not asked") -> AgentDecision.CannotDo(REASON, STOPPED)
            last.startsWith("ask_user(") -> {
                val answer = answerOf(last) ?: return AgentDecision.Done(STOPPED)
                menuAnswer(answer, last, session)
            }
            last.startsWith("${TelcoUssdTool.NAME}(") -> afterStep(last, session)
            // The navigator handed over mid-walk: its own question when it asked one (a payment confirmation: "shall I
            // confirm it?"), else the CURRENT screen, chosen by the brain where it can and asked where it can't.
            last.startsWith("${com.tekyerema.money.ussd.UssdNavigatorTool.NAME}(") ->
                if (last.contains("→ success") && session != null) {
                    session.pending?.let { ask(it.question) }
                        ?: if (session.kind == BankUssd.ScreenKind.MENU) next(session, first = true) else null
                } else null
            else -> if (ours) AgentDecision.Done(STOPPED) else AgentDecision.Done("Okay — I didn't open it.")
        }
    }

    private fun open(flow: TelcoUssd.Flow): AgentDecision {
        noteOpen(flow)
        val args = JSONObject()
            .put(TelcoUssdTool.ARG_ACTION, TelcoUssdTool.OPEN)
            .put(TelcoUssdTool.ARG_FLOW, flow.key).put(TelcoUssdTool.ARG_CODE, flow.code)
            .put(TelcoUssdTool.ARG_WHAT, flow.what).put(TelcoUssdTool.ARG_NET, flow.netName)
        // What the request itself said — read back ONCE, here, and then entered without a second question each.
        flow.amount?.let { args.put(TelcoUssdTool.ARG_AMOUNT, it) }
        flow.number?.let { args.put(TelcoUssdTool.ARG_NUMBER, it) }
        flow.name?.let { args.put(TelcoUssdTool.ARG_CONTACT, it) }
        flow.pay?.let { args.put(TelcoUssdTool.ARG_PAY, it) }
        return AgentDecision.CallTool(TelcoUssdTool.NAME, args, RATIONALE)
    }

    /** [TelcoUssdTool.open]'s args back as the flow (the tool never dials a code the catalog didn't give). */
    internal fun flowOf(args: JSONObject): TelcoUssd.Flow? {
        val code = args.optString(TelcoUssdTool.ARG_CODE).takeIf { Regex("^\\*[0-9*]+#$").matches(it) } ?: return null
        val session = TelcoSession.current()
        // The flow of the open being confirmed NOW comes first: the session may still hold an EARLIER walk on the same code
        // for ten minutes — "buy an MTN midnight bundle" re-used the previous request's "3 cedis" and pressed "GHC 3"
        // (A20e, 2 October 2026).
        val flow = pendingOpen?.takeIf { it.code == code } ?: session?.flow?.takeIf { it.code == code } ?: return null
        // A named person the tool's preflight resolved (or the user picked from "which one?"): their number rides along.
        val number = args.optString(TelcoUssdTool.ARG_NUMBER).filter { it.isDigit() }.takeIf { it.length == 10 }
        return if (number != null && number != flow.number)
            flow.copy(number = number, name = args.optString(TelcoUssdTool.ARG_CONTACT).takeIf { it.isNotBlank() } ?: flow.name)
        else flow
    }

    /** The flow whose open is being confirmed (set at [open]'s decision, read by the tool when it runs). */
    @Volatile private var pendingOpen: TelcoUssd.Flow? = null

    private fun menuAnswer(answer: String, last: String, s: TelcoSession.View?): AgentDecision {
        val session = s ?: return AgentDecision.CannotDo(REASON, "The network's menu has closed. Nothing is paid without your PIN.")
        // The brain's own question on this screen ("more calls or more data?", "pay from your airtime?", "buy it?"): the
        // answer is mapped to the row it means, and a price or preference is remembered for the screens after.
        session.pending?.let { q ->
            if (BankUssd.isRepeat(answer)) return askBrain(q, again = true)
            return when (val r = com.tekyerema.money.ussd.MenuBrain.resolve(q, answer, session.screen)) {
                is com.tekyerema.money.ussd.MenuBrain.Answer.Press -> reply(session, r.option, auto = false, more = false, row = null)
                is com.tekyerema.money.ussd.MenuBrain.Answer.Learned -> {
                    TelcoSession.learn(r.amount, r.prefer)
                    reply(session, r.option, auto = false, more = false, row = null)
                }
                com.tekyerema.money.ussd.MenuBrain.Answer.Close -> close()
                com.tekyerema.money.ussd.MenuBrain.Answer.Unclear ->
                    if (questionOf(last)?.startsWith(AGAIN) == true) close() else askBrain(q, again = true)
            }
        }
        if (BankUssd.isCancel(answer)) return close()
        if (BankUssd.isRepeat(answer)) {
            if (questionOf(last)?.startsWith(AGAIN) == true) return AgentDecision.CannotDo(REASON, STOPPED)
            return ask(AGAIN + question(session, first = false))
        }
        // A reference is typed as the user said it — the entry cleaner below keeps only the digits of an answer that has
        // any ("school fees 2" → "2").
        if (TelcoUssd.rows(session.screen).isEmpty() &&
            com.tekyerema.money.ussd.MenuModel.slotOf(session.screen) == com.tekyerema.money.ussd.SlotKind.REFERENCE)
            return reply(session, referenceOf(answer) ?: return askReference(session), auto = false, more = false, row = null)
        val entry = BankUssd.entry(answer) ?: return ask(AGAIN + question(session, first = false))
        // A MENU wants one of its options. Digits are typed as said; words must name a row ("MoMo user" → 1); anything
        // else is asked again and never typed (2 October 2026: a whole sentence went into MTN's menu — "Incorrect choice").
        val rows = TelcoUssd.rows(session.screen)
        if (rows.isNotEmpty() && !entry.all { it.isDigit() || it == '#' || it == '*' }) {
            // "The one with more minutes", "the 5 cedis one": the brain's own reading of a preference or a price.
            com.tekyerema.money.ussd.MenuBrain.preferOf(answer)?.let { p ->
                val goal = goalOf(session.flow.copy(prefer = p.name))
                (com.tekyerema.money.ussd.MenuBrain.decide(goal, session.screen) as? com.tekyerema.money.ussd.MenuBrain.Move.Press)?.let { m ->
                    TelcoSession.learn(prefer = p)
                    return reply(session, m.option, auto = false, more = false, row = null)
                }
            }
            val option = rowNamed(rows, answer)
                ?: com.tekyerema.money.MoneyLimits.parseAmount(answer)?.toPlainString()?.let { a -> (priceRow(rows, a) ?: tierRow(rows, a))?.first }
                ?: return ask(NOT_AN_OPTION + question(session, first = false))
            return reply(session, option, auto = false, more = false, row = null)
        }
        return reply(session, entry, auto = false, more = false, row = null)
    }

    /** The user said no / cancel: the menu is closed BY Tɛkyerɛma (TelcoUssdTool's close), not left for them to find. */
    private fun close(): AgentDecision = AgentDecision.CallTool(TelcoUssdTool.NAME,
        JSONObject().put(TelcoUssdTool.ARG_ACTION, TelcoUssdTool.CLOSE), RATIONALE)

    private fun askBrain(q: com.tekyerema.money.ussd.MenuBrain.Move.Ask, again: Boolean = false): AgentDecision {
        TelcoSession.ask(q)
        return ask((if (again) AGAIN else "") + q.question)
    }

    /** What the menu brain chooses by, from the walk's own flow (the request's slots, as read back and approved). */
    internal fun goalOf(flow: TelcoUssd.Flow): com.tekyerema.money.ussd.MenuBrain.Goal = com.tekyerema.money.ussd.MenuBrain.Goal(
        words = flow.words,
        amount = flow.amount,
        pay = flow.pay,
        forOthers = if (flow.number != null || flow.name != null) true else if (flow.key == TelcoUssd.MENU) null else false,
        prefer = flow.prefer?.let { p -> try { com.tekyerema.money.ussd.MenuBrain.Prefer.valueOf(p) } catch (_: Throwable) { null } },
        // The open's read-back said this amount ("Buy a data bundle for 3 cedis?") — or the user chose it on a price list.
        priceConfirmed = flow.amount != null && !flow.confirmed,
        net = flow.netName)

    const val NOT_AN_OPTION = "That isn't one of the options. "

    /** The row the navigator's own matcher is SURE about for this hand-over's intent, or null (then the user is asked). */
    internal fun navChoice(s: TelcoSession.View): Pair<String, String>? {
        val intent = try { com.tekyerema.money.ussd.TxIntent.valueOf(s.flow.intent ?: return null) } catch (_: Throwable) { return null }
        val rows = TelcoUssd.rows(s.screen)
        if (rows.size < 2) return null
        val to = com.tekyerema.agent.NumberSpeech.networkKey(s.flow.number)
        val cross = if (to == null || s.flow.sim == null) null else to != s.flow.sim.lowercase()
        val v = com.tekyerema.money.ussd.SemanticMatch.best(intent, rows, null, cross) ?: return null
        return if (v.confidence >= com.tekyerema.money.ussd.SemanticMatch.actBar() && v.margin >= com.tekyerema.money.ussd.SemanticMatch.marginBar())
            v.option to v.row else null
    }

    /** The "Enter amount" row of a menu, when [amount] is inside the range that row (or the screen) states. */
    internal fun amountRow(rows: List<Pair<String, String>>, amount: String): Pair<String, String>? =
        rows.filter { Regex("(?i)^enter (?:an? |your |the )?amount").containsMatchIn(it.second.trim()) }.singleOrNull()
            ?.takeIf { com.tekyerema.money.ussd.AmountRange.of(it.second) != null && inRange(amount, it.second) }

    private val TIER_PRICE = Regex("(?i)\\bGH[SC₵¢]?\\s*(\\d+(?:\\.\\d+)?)\\s*$")

    /**
     * MTN's Mashup offers are price tiers ("MashUp GHC 1", "GHC 5", "GHC 10", "GHC 30"), and the tier opens onto
     * "Enter amount (0.07-4.99)" — so an amount that is none of the tier prices belongs to the highest tier at or below
     * it, or to the first tier when it is smaller than all of them. Seen once live (10 pesewas → "MashUp GHC 1" →
     * "Enter amount GHC (0.07-4.99)"); the next screen's own range is still checked before anything is typed, and a
     * wrong tier costs one "0. Back". Null unless EVERY option (but Back / More) is such a tier.
     */
    internal fun tierRow(rows: List<Pair<String, String>>, amount: String): Pair<String, String>? {
        val v = amount.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return null
        val options = rows.filter { !Regex("(?i)^(?:back|more|next|exit|main menu)\\b").containsMatchIn(it.second.trim()) }
        val tiers = options.mapNotNull { r -> TIER_PRICE.find(r.second.trim())?.groupValues?.get(1)?.toBigDecimalOrNull()?.let { r to it } }
        if (tiers.size < 2 || tiers.size != options.size) return null
        return (tiers.filter { it.second <= v }.maxByOrNull { it.second } ?: tiers.minByOrNull { it.second })?.first
    }

    /** The row for the way the user said they'd pay, on a screen that offers both Airtime and Mobile Money. */
    internal fun payRow(rows: List<Pair<String, String>>, pay: String): Pair<String, String>? {
        val momo = rows.filter { Regex("(?i)^(?:mobile money|momo)\\b").containsMatchIn(it.second.trim()) }
        val airtime = rows.filter { Regex("(?i)^airtime\\b").containsMatchIn(it.second.trim()) }
        if (momo.size != 1 || airtime.size != 1) return null
        return if (pay == "momo") momo[0] else if (pay == "airtime") airtime[0] else null
    }

    /** The single row that carries [amount] as a cedi price ("GHS 10", "GHC10.00", "10 cedis", "@ 10"), or null when
     *  none or several do. A bare "10" (10 GB, 10 days) is never a price. */
    internal fun priceRow(rows: List<Pair<String, String>>, amount: String): Pair<String, String>? {
        val v = amount.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return null
        val whole = v.stripTrailingZeros().let { if (it.scale() <= 0) it.toPlainString() else null }
        val exact = v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()                 // "10.00", "0.50"
        val figures = listOfNotNull(whole?.let { Regex.escape(it) + "(?:\\.0{1,2})?" }, Regex.escape(exact),
            Regex.escape(v.stripTrailingZeros().toPlainString())).distinct().joinToString("|")
        val price = Regex("(?i)(?:gh[sc]?|₵|¢|@)\\s*(?:$figures)(?![\\d.])|(?<![\\d.])(?:$figures)\\s*(?:gh[sc]\\b|cedis?\\b|₵|¢)")
        return rows.filter { price.containsMatchIn(it.second) }.singleOrNull()
    }

    /** The option whose own words the user said ("MoMo user", "other networks"), or null when none or several fit. */
    internal fun rowNamed(rows: List<Pair<String, String>>, answer: String): String? {
        fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
        val a = norm(answer).removePrefix("the ").removeSuffix(" one").removeSuffix(" option").trim()
        if (a.length < 3) return null
        rows.filter { norm(it.second) == a }.singleOrNull()?.let { return it.first }
        return rows.filter { norm(it.second).let { w -> w.startsWith("$a ") || w.endsWith(" $a") || a.endsWith(" $w") || a.startsWith("$w ") } }
            .singleOrNull()?.first
    }

    private fun afterStep(last: String, s: TelcoSession.View?): AgentDecision {
        if (!last.contains("→ success"))
            return AgentDecision.CannotDo(REASON, stepMessage(last).ifBlank { "The network's menu didn't work just now. Nothing was bought." })
        val session = s ?: return AgentDecision.Done(STOPPED)
        if (session.kind != BankUssd.ScreenKind.MENU) return AgentDecision.Done(handOffLine(session))
        return next(session, first = !last.contains("\"${TelcoUssdTool.ARG_ACTION}\":\"${TelcoUssdTool.REPLY}\""))
    }

    /**
     * The step for the CURRENT menu screen. In order (owner, 2 October 2026 — "give it a small brain… and choose the best
     * one"): the menu brain's sure presses and its money questions (a loan, a switch of wallet, a "Buy" — before any plan
     * could press something else); the researched plan; the entry screens the request already answers; the navigator's
     * meaning match; the brain's own questions ("more calls or more data?", "which price?"); and only then the menu read out.
     */
    private fun next(session: TelcoSession.View, first: Boolean): AgentDecision {
        val rejected = RE_REJECT.containsMatchIn(session.screen)
        val brain = if (rejected) null
            else com.tekyerema.money.ussd.MenuBrain.decide(goalOf(session.flow).copy(inferTier = !session.userDrove), session.screen)
        when {
            // A brain press comes from the request itself (its words, price, payer, whose phone): once it has chosen, the
            // researched plan has nothing left to add — a later plan step must not press a row the brain passed over.
            brain is com.tekyerema.money.ussd.MenuBrain.Move.Press ->
                return reply(session, brain.option, auto = true, more = false, row = brain.row,
                    stepIdx = if (session.flow.auto.isEmpty()) -1 else session.flow.auto.size - 1)
            brain is com.tekyerema.money.ussd.MenuBrain.Move.Ask && brain.urgent -> return askBrain(brain)
        }
        // The hybrid: while researched steps remain, press the one whose row the LIVE screen shows. Steps are
        // SKIPPABLE — the live menu may fold a level the research expected (A20e 2026-09-30: *567# went straight to
        // "MashUp for Self/Others", and the missing middle step sent the walk paging into a dead end) — so the FIRST
        // remaining step with a matching row wins. A long menu is paged with "More" a bounded few times. And once the
        // USER has answered a screen themselves, the autopilot stays off: every screen after is read and asked.
        if (!session.userDrove) {
            for (i in session.stepDone until session.flow.auto.size) {
                TelcoUssd.autoAnswer(session.screen, session.flow.auto[i])?.let { n ->
                    val row = TelcoUssd.rows(session.screen).firstOrNull { it.first == n }?.second
                    return reply(session, n, auto = true, more = false, row = row, stepIdx = i)
                }
            }
            if (session.stepDone < session.flow.auto.size && session.morePressed < 3)
                TelcoUssd.moreOption(session.screen)?.let { n ->
                    return reply(session, n, auto = true, more = true, row = "More")
                }
        }
        // An ENTRY screen whose answer the user's own request already carried ("Send ₵0.1 mashup to 055…"): enter it
        // instead of asking them to dictate it again — the number still gets its one TIER2 read-back ("Enter 0 5 5, …
        // and send it?"), so nothing is typed unheard (live A20e 2026-09-30: the ask came and the recogniser then
        // failed on the digits twice; the walk stalled on information the user had already said).
        if (TelcoUssd.rows(session.screen).isEmpty()) {
            // A "wrong value / enter again" re-prompt is NEVER auto-filled: the value we hold is exactly what the
            // network just rejected, and re-typing it is the blind-retry money safety forbids (live A20e 2026-09-30:
            // "10" was outside MTN's 0.07–4.99 range and got re-entered until the session timed out). Ask the user.
            if (RE_REJECT.containsMatchIn(session.screen))
                return ask("The network didn't accept that. It says: “${BankUssd.spokenScreen(session.screen)}”. What should I enter?")
            val slot = com.tekyerema.money.ussd.MenuModel.slotOf(session.screen)
            val value = when (slot) {
                com.tekyerema.money.ussd.SlotKind.PHONE_NUMBER -> session.flow.number
                com.tekyerema.money.ussd.SlotKind.CONFIRM_NUMBER -> session.lastNumber ?: session.flow.number
                // Only fill an amount the screen's OWN stated range accepts — otherwise read the range and ask.
                com.tekyerema.money.ussd.SlotKind.AMOUNT -> session.flow.amount?.takeIf { inRange(it, session.screen) }
                com.tekyerema.money.ussd.SlotKind.REFERENCE -> session.flow.reference
                else -> null
            }
            // These values were read back and approved ONCE already (the navigator's read-back, or the open question
            // of this walk, which now says the amount and who it is for) — entered like an auto step, not asked again.
            if (value != null) return reply(session, value, auto = true, more = false, row = null)
            // The network wants a reference and the user gave none: ASKED, never invented (owner, 5 October 2026).
            if (slot == com.tekyerema.money.ussd.SlotKind.REFERENCE) return askReference(session)
            // An amount we hold but that's out of range: say so rather than let the network reject it.
            if (slot == com.tekyerema.money.ussd.SlotKind.AMOUNT && session.flow.amount != null)
                return ask("Your amount, ${session.flow.amount} cedis, is outside what this offer allows. " +
                    "It says: “${BankUssd.spokenScreen(session.screen)}”. What amount should I enter?")
        }
        // A walk the navigator handed over: after the user's one answer the NEXT menus are chosen by meaning again
        // (the same matcher, the same bars) — it used to read every later menu aloud and ask (2 October 2026: after
        // "1" for Airtime it asked about "Self / Others / Welcome Pack / Other Networks" too). Never on a screen that
        // says the last entry was refused.
        if (session.flow.confirmed && !rejected)
            navChoice(session)?.let { (n, words) -> return reply(session, n, auto = true, more = false, row = words) }
        // (A price said, the "Enter amount" row, the price tier, how to pay — the brain's presses above, read off the LIVE
        // rows, from the owner's Mashup run of 2 October 2026 where each of these had been asked aloud.)
        // The network asking to CONFIRM a number the user dictated mid-walk: the same one back — one YES, never
        // ten digits said twice (MTN's Enter → Confirm double-entry).
        session.lastNumber?.let { n ->
            if (RE_CONFIRM_NUMBER.containsMatchIn(session.screen)) return reply(session, n, auto = true, more = false, row = null)
        }
        // The brain's own question — one plain choice instead of a list of rows read out.
        if (brain is com.tekyerema.money.ussd.MenuBrain.Move.Ask) return askBrain(brain)
        // First ask of this walk: no reply and no ask has happened yet (like the bank's first-question check).
        return ask(question(session, first = first))
    }

    private fun reply(session: TelcoSession.View, value: String, auto: Boolean, more: Boolean, row: String?,
                      stepIdx: Int = -1): AgentDecision {
        val args = JSONObject().put(TelcoUssdTool.ARG_ACTION, TelcoUssdTool.REPLY).put(TelcoUssdTool.ARG_TEXT, value)
        if (auto) args.put(TelcoUssdTool.ARG_AUTO, true)
        if (more) args.put(TelcoUssdTool.ARG_MORE, true)
        if (stepIdx >= 0) args.put(TelcoUssdTool.ARG_STEP, stepIdx)
        row?.let { args.put(TelcoUssdTool.ARG_ROW, it.take(60)) }
        if (!auto) args.put(TelcoUssdTool.ARG_SCREEN, BankUssd.spokenScreen(session.screen).take(160))
        return AgentDecision.CallTool(TelcoUssdTool.NAME, args, RATIONALE)
    }

    private fun ask(q: String, twi: String? = null): AgentDecision =
        AgentDecision.CallTool("ask_user", JSONObject().put("question", q).apply { twi?.let { put("question_twi", it) } }, RATIONALE)

    /** "MTN asks for a reference for this payment. What should it say?" — with its own Twi, so a Twi conversation hears and
     *  sees only Twi (no translation). [owner: verify the Twi] */
    private fun askReference(s: TelcoSession.View): AgentDecision =
        ask("${s.flow.netName} asks for a reference for this payment. What should it say?",
            twi = "${s.flow.netName} pɛ reference ma sika yi. Dɛn na menkyerɛw?")

    private val NO_REFERENCE = Regex("^(?:none|no reference|nothing|skip(?: it)?|anything|any|whatever|leave it blank|blank|" +
        "hwee|biribiara|biribi biara|ɛnhia|enhia)$")

    /** The user's reference as said, tidied: no closing punctuation, one space between words, at most 30 characters (a
     *  network's reference box is short). "None" / "skip" / "hwee" → MTN's usual "Payment". Null for an empty answer. Pure. */
    internal fun referenceOf(answer: String): String? {
        val a = answer.trim().trimEnd('.', '!', '?', ',').replace(Regex("\\s+"), " ").trim('"', '“', '”', '\'', '‘', '’', ' ')
        if (a.isEmpty()) return null
        if (NO_REFERENCE.matches(a.lowercase())) return "Payment"
        return a.take(30).trim()
    }

    /** "MTN says: “1 Buy Data; 2 …”. What should I enter?" — the screen first, one question. */
    fun question(s: TelcoSession.View, first: Boolean): String =
        "${s.flow.netName} says: “${heard(s.screen)}”. What should I enter?" +
            if (first) com.tekyerema.voice.Coaching.tail("tail_telco_cancel", " You can say cancel to stop.") else ""

    /** A menu is read as its OPTIONS: the line the network puts above them ("More offers await on the new MoMo App")
     *  is an advert the listener has to sit through before the first choice (first live walk, 2 October 2026). It is
     *  kept when it says the last entry was refused, and for any screen that is not a menu. */
    internal fun heard(screen: String): String {
        val rows = TelcoUssd.rows(screen)
        if (rows.size < 2 || RE_REJECT.containsMatchIn(screen)) return BankUssd.spokenScreen(screen)
        return rows.joinToString("; ") { (n, w) ->
            val words = units(w.trim().trimEnd('.', ';', ','))
            // "1 2.54MB" is heard as "twelve point five four": an option that starts with a figure is announced as one.
            if (words.firstOrNull()?.isDigit() == true) "option $n, $words" else "$n $words"
        }
    }

    private fun units(s: String): String = s
        .replace(Regex("(?i)(\\d)\\s*MB\\b"), "$1 megabytes").replace(Regex("(?i)(\\d)\\s*GB\\b"), "$1 gigabytes")
        .replace(Regex("(?i)(\\d)\\s*Mins?\\b"), "$1 minutes")

    /** The last line at a payment confirmation, a PIN prompt or a final message — read out, then the user takes over. */
    fun handOffLine(s: TelcoSession.View): String {
        // A FINAL screen (a balance, a receipt) is the ANSWER: paced, in words, one item per breath (UssdSpeech).
        if (s.kind == BankUssd.ScreenKind.FINAL) return UssdSpeech.forEar(s.screen, intro = "${s.flow.netName} says")
        val said = "${s.flow.netName}'s screen says: “${BankUssd.spokenScreen(s.screen)}”."
        return when (s.kind) {
            BankUssd.ScreenKind.MONEY -> "$said If that's right, confirm it on the screen and enter your PIN yourself; if not, press Cancel. " +
                "I've stopped here — I never confirm a payment or type a PIN."
            // After the user's own "yes, confirm it" (the navigator's payment confirmation) the details were just heard:
            // the PIN prompt is one short line, not the screen read again.
            BankUssd.ScreenKind.PIN -> (if (s.flow.confirmed) "${s.flow.netName} is asking for your PIN — enter it yourself to finish, or press Cancel. I never see or type it."
                else "$said It's asking for your PIN — enter it yourself if everything is right, or press Cancel. I never see or type it.") +
                PinPad.handOffTip()
            BankUssd.ScreenKind.GUARDED -> "$said It mentions a PIN or a code, so I'll leave this screen to you — choose on it yourself, or press Cancel."
            else -> said
        }
    }

    /**
     * RoutingBrain.moneyEnd for a telco step: a failed step, or a screen that isn't a menu (payment confirmation, PIN,
     * last message), ENDS the task at once — before the screen is observed again and before any recovery step runs in a
     * network's menu. Null = the guide goes on.
     */
    fun end(history: List<String>, session: TelcoSession.View? = TelcoSession.current()): Pair<AgentStatus, String>? {
        val last = history.lastOrNull { STEP_LINE.containsMatchIn(it) } ?: return null
        if (!last.startsWith("${TelcoUssdTool.NAME}(")) return null
        // The user's "no": the menu was closed by Tɛkyerɛma, and that is the end of it.
        if (last.contains("\"${TelcoUssdTool.ARG_ACTION}\":\"${TelcoUssdTool.CLOSE}\""))
            return AgentStatus.COMPLETED to stepMessage(last).ifBlank { "Okay — I've closed the menu. Nothing was bought." }
        if (last.contains("[preflight]")) return AgentStatus.CANNOT_DO to stepMessage(last).ifBlank { "I couldn't open the network's menu." }
        if (!last.contains("→ success")) return AgentStatus.CANNOT_DO to stepMessage(last).ifBlank { "The network's menu didn't work just now. Nothing was bought." }
        val s = session ?: return AgentStatus.CANNOT_DO to "The network's menu has closed. Nothing is paid without your PIN."
        if (s.kind == BankUssd.ScreenKind.MENU) return null
        return AgentStatus.COMPLETED to handOffLine(s)
    }

    /** The SIM's network key ("mtn" | "telecel" | "airteltigo"), via the same table Mobile Money uses. */
    private fun simNetwork(): String? = try {
        val ctx = com.tekyerema.agent.AgentEngine.appContext() ?: return null
        val tm = ctx.getSystemService(android.content.Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        com.tekyerema.tools.GhanaMobileMoney.network(tm?.simOperatorName?.ifBlank { tm.simOperator } ?: tm?.simOperator)
    } catch (_: Throwable) { null }

    /** The walk is waiting on the user's spoken yes/no to a payment confirmation's confirm key (not a PIN screen). */
    fun confirmationAsked(): Boolean =
        TelcoSession.current()?.pending?.kind == com.tekyerema.money.ussd.MenuBrain.Kind.CONFIRM

    /** [open] decisions remember their flow for the tool (the args carry only strings). */
    fun noteOpen(flow: TelcoUssd.Flow) { pendingOpen = flow }

    fun resetForTest() { pendingOpen = null; TelcoSession.end() }

    /** A screen asking the number be entered AGAIN ("Confirm Phone Number", "Re-enter number"). */
    private val RE_CONFIRM_NUMBER = Regex("(?i)\\b(?:confirm|re-?enter|repeat)\\b.{0,20}\\b(?:phone|number|msisdn)\\b")
    /** The network rejected the last entry and wants it again ("wrong amount value. Enter Amount again", "invalid"). */
    private val RE_REJECT = com.tekyerema.money.ussd.AmountRange.REFUSED
    /** True when [amount] is within any range the [screen] states, or the screen states none. */
    internal fun inRange(amount: String, screen: String): Boolean = com.tekyerema.money.ussd.AmountRange.allows(amount, screen)

    private val STEP_LINE = Regex("^[a-z_]+\\(")
    private val RECOVERY_HINT = Regex(" — (?:this route|re-observe|only the user|the '[^']*' accelerator)")
    private fun stepMessage(last: String): String = RECOVERY_HINT.split(LocalBrain.resultMessage(last), 2)[0].trim()
    private fun answerOf(line: String): String? =
        Regex("the user answered: \"(.*)\"(?: \\(= .*\\))?\\s*$").find(line)?.groupValues?.get(1)?.trim()?.ifEmpty { null }
    private fun questionOf(line: String): String? = Regex("^ask_user\\(\"(.*?)\"\\) →").find(line)?.groupValues?.get(1)
}
