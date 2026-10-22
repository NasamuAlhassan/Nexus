package com.tekyerema.money

import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.LocalBrain
import com.tekyerema.agent.NumberSpeech
import com.tekyerema.core.config.AppConfig
import com.tekyerema.money.ussd.UssdNavigatorTool
import org.json.JSONObject

/**
 * The on-phone plan for a spoken financial request (finance audit, 2026-10-01) — decided before any brain
 * ([MoneyPlan.decide]), online or offline, with no model in it.
 *
 * Why: the phone sweep that night showed that only ONE wording of a send ("send 50 cedis to <ten digits>") took the
 * careful route. "Send 50 cedis to Prince Melanin" was read back without looking the contact up and then left to the
 * cloud planner to walk *170#; "I want to send Prince Melanin fifty", "transfer 50 to …", "pay … 10 cedis" went to the
 * cloud planner, which opened the Contacts app and tapped around, asked for a "reference", or — with the day's quota
 * spent — answered "The main AI is busy"; "send 5 cedis airtime to Prince Melanin" was read back as airtime for the
 * user's own phone; "did that payment go through?" got "Which payment are you referring to?".
 *
 * What it does — one transaction, its slots, and the next step:
 *  - SEND: amount and recipient are collected (asked for once each when missing — never guessed, never defaulted), a
 *    named person is resolved against the contacts by the tool's preflight (several matches → the engine's "which
 *    one?"), and the walk is the navigator's: one read-back, a quiet walk, the network's own confirmation, the user's PIN.
 *  - AIRTIME: for this phone, or for a named person / number — the same walk with the airtime intent.
 *  - BALANCE: "check my balance" asks which one (Mobile Money or airtime) instead of guessing.
 *  - STATUS / HISTORY: answered from the ledger and the network's own messages ([MoneyStatusTool]).
 * Everything that moves money still passes the engine's TIER3 read-back; this only decides WHAT is proposed.
 * Pure: (goal, history) → decision; unit-tested.
 */
object MoneyFlow {
    private const val NAV = UssdNavigatorTool.NAME
    const val R_SEND = "local:send money (money flow)"
    const val R_AIRTIME = "local:buy airtime (money flow)"
    const val R_BALANCE = "local:mobile money balance (money flow)"
    const val R_AIRTIME_BALANCE = "local:airtime balance (money flow)"
    const val R_STATUS = "local:money status (money flow)"
    const val R_ASK = "local:money flow needs one detail"

    const val Q_BALANCE = "Your Mobile Money balance, or your airtime balance?"
    const val GAVE_UP = "I didn't get that, so I've stopped. Nothing was sent. Say the whole request again when you're ready."

    fun enabled(): Boolean = AppConfig.bool("money.flow.enabled", true) && AppConfig.bool("money.navigator.enabled", true)

    /** What a request outside [MoneyAsk]'s kinds asks of the phone's own money records, or null. */
    internal fun recordsMode(goal: String): String? {
        val g = goal.lowercase().replace('’', '\'').replace(Regex("[^\\p{L}\\p{N}' ]"), " ").replace(Regex("\\s+"), " ").trim()
        return when {
            RECEIVED.containsMatchIn(g) -> MoneyStatusTool.RECEIVED
            MESSAGE.containsMatchIn(g) -> MoneyStatusTool.MESSAGE
            else -> null
        }
    }

    private val RECEIVED = Regex("\\b(?:did|have) i (?:receive|received|get|got)\\b.{0,20}\\b(?:money|payment|payments|momo|mobile money|cash)\\b|" +
        "\\bhas (?:anyone|anybody|someone|somebody) (?:sent|paid) me\\b|\\bdid (?:anyone|anybody|someone|somebody) (?:send|pay) me\\b|" +
        "\\b(?:has|did) (?:any |the |my )?(?:money|payment) (?:come|came|arrive|arrived)\\b")
    private val MESSAGE = Regex("\\b(?:read|check|open|what(?:'s| is| was| does| did)?)\\b.{0,30}\\b(?:last|latest|recent|newest)\\b.{0,12}" +
        "\\b(?:momo|mobile money)\\b.{0,12}\\b(?:message|messages|text|sms|alert)\\b|" +
        "\\bwhat (?:did|does) (?:momo|mobile money|mtn momo) (?:say|said|send|sent)\\b")

    /**
     * Two Mobile Money jobs Tɛkyerɛma has no checked route for yet — said plainly, with where they live in the network's
     * own menu, instead of a model's guess (sweep 2026-10-01: "pay my light bill" → "Which phone number should I send the
     * payment to? none?"; "withdraw 100 cedis" → "…from your mobile money wallet to a bank account?"). The menu rows named
     * are the ones on the real *170# screen captured on the A20e ("2) MoMoPay&Pay Bill", "4) Allow Cash Out").
     */
    internal fun notYet(goal: String): String? {
        val g = goal.lowercase().replace('’', '\'')
        if (NOT_A_REQUEST.containsMatchIn(g)) return null
        // Owner, 22 Oct 2026: "a blind person to dial *170#? who does that?" — a bill, a school fee, Allow Cash Out are on the
        // network's own menus, so the brain walks there (mobile_money menu_goal → the navigator); only a withdrawal, which an agent
        // starts, is explained — and never with "dial it yourself".
        return when {
            ALLOW_CASH_OUT.containsMatchIn(g) -> null
            CASH_OUT.containsMatchIn(g) -> CASH_OUT_LINE
            else -> null
        }
    }

    const val CASH_OUT_LINE = "A cash-out is started by a Mobile Money agent. When the agent starts it, I'll read the network's prompt " +
        "to you and you approve it with your PIN. If the agent asks you to allow it first, say “allow cash out” and I'll do it on the menu."
    const val BILL_LINE = "I can't pay bills for you yet, so nothing was paid. On MTN it's under MoMoPay and Pay Bill in the star 1 7 0 hash menu."

    private val ALLOW_CASH_OUT = Regex("\\b(?:allow|approve|enable|authori[sz]e)\\b.{0,15}\\b(?:cash ?out|cashout|withdrawal)\\b")
    private val CASH_OUT = Regex("\\b(?:withdraw|withdrawal|cash ?out|cashout)\\b")
    private val BILL = Regex("\\bpay\\b.{0,30}\\b(?:bills?|ecg|light|electricity|water|dstv|gotv|startimes|tv subscription|school fees)\\b|" +
        "\\b(?:buy|top ?up|recharge)\\b.{0,20}\\b(?:prepaid|electricity|ecg|light)\\b")
    /** A second command joined on, or a message to someone — the planner's. */
    private val NOT_A_REQUEST = Regex("\\b(?:and then|then|after that)\\b|\\b(?:message|text|sms|whatsapp|tell|remind|call)\\b")

    /**
     * What a CLOUD planner proposed, before it is acted on. Two rules, both from one live run (A20e, 2 October 2026 03:23):
     *  - a Mobile Money call (`mobile_money`, `momo_send`) with its details becomes the navigator's walk. `mobile_money`
     *    only opened *170# and left the model to walk it — it typed the phone number into MTN's MAIN menu;
     *  - a dialled code must be one the user said or one from the checked tables. The model then asked to dial
     *    "*124*0.10*0508797323#", a code it had made up.
     * Everything else is returned untouched.
     */
    fun fromPlanner(d: AgentDecision, goal: String, history: List<String> = emptyList()): AgentDecision {
        if (d !is AgentDecision.CallTool) return d
        val a = d.args
        fun s(key: String) = a.optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
        if (d.name == "run_ussd") {
            val code = s("code")?.replace(Regex("\\s"), "") ?: return d
            return if (codeAllowed(code, goal)) d else AgentDecision.CannotDo("money", MADE_UP_CODE)
        }
        // A code TYPED into the dialer is a dialled code: the planner typed "*516*0508797323*2#" and "*170*0508797323*2#"
        // (A20e, 14 Oct 2026 00:13) — a money shortcut it composed, with the user's PIN as its next step.
        if (d.name == "type") {
            val text = s("text")?.replace(Regex("\\s"), "") ?: return d
            if (TYPED_CODE.matches(text) && !codeAllowed(text, goal)) return AgentDecision.CannotDo("money", MADE_UP_CODE)
        }
        if (!enabled()) return d
        val nav = JSONObject()
        when (d.name) {
            "momo_send" -> {
                val number = s("number")?.takeIf { n -> n.count { it.isDigit() } >= 7 }
                val name = s("contact") ?: s("number")?.takeIf { n -> n.count { it.isDigit() } < 7 }
                when {
                    number != null -> nav.put("number", number)
                    name != null -> nav.put(UssdNavigatorTool.ARG_CONTACT, name)
                    else -> return d
                }
                nav.put("amount", s("amount") ?: return d)
                s("reference")?.let { nav.put("reference", it) }
            }
            "mobile_money" -> {
                val kind = s("_kind")
                // Anything else on the network's own menus: the navigator walks toward the user's words from an official root
                // (UssdNavigatorTool.goalRoots checks it), reads the network's confirmation back and stops at the PIN.
                if (kind == com.tekyerema.tools.MobileMoneyTool.KIND_GOAL) {
                    nav.put(UssdNavigatorTool.ARG_INTENT, UssdNavigatorTool.MENU_GOAL).put(UssdNavigatorTool.ARG_GOAL, s("_goal") ?: goal)
                    s("_root")?.let { nav.put(UssdNavigatorTool.ARG_ROOT, it) }
                    s("_amount")?.let { nav.put("amount", it) }
                    s("_number")?.let { nav.put("number", it) } ?: s("_recipient")?.let { nav.put(UssdNavigatorTool.ARG_CONTACT, it) }
                    return AgentDecision.CallTool(NAV, nav, "the planner's menu goal — walked on the phone, by the navigator")
                }
                if (kind == com.tekyerema.tools.MobileMoneyTool.KIND_BALANCE) nav.put(UssdNavigatorTool.ARG_INTENT, UssdNavigatorTool.CHECK_BALANCE)
                else {
                    if (kind != "buy_airtime" && kind != "send_money") return d
                    nav.put("amount", s("_amount") ?: return d)
                    val who = s("_recipient")
                    val number = s("_number") ?: who?.takeIf { w -> w.count { it.isDigit() } >= 7 }
                    val name = who?.takeIf { w -> w.count { it.isDigit() } < 7 }
                    if (number != null) nav.put("number", number) else if (name != null) nav.put(UssdNavigatorTool.ARG_CONTACT, name)
                    if (kind == "buy_airtime") nav.put(UssdNavigatorTool.ARG_INTENT,
                        if (number != null || name != null) UssdNavigatorTool.SEND_AIRTIME else UssdNavigatorTool.BUY_AIRTIME)
                    else if (number == null && name == null) return d
                }
            }
            else -> return d
        }
        // A number — or an AMOUNT — the user never said in this request or its answers (the model took it from an earlier
        // turn): the read-back says so. The amount check came from the A20e, 5 October 2026: "soma pesee kɔma prinsmalane"
        // was read back as "Send 1 cedi …" — the 1 cedi was from the request before.
        val said = goal + " " + answers(history).joinToString(" ") { it.second }
        val digits = nav.optString("number").filter { it.isDigit() }
        if (digits.length >= 7 && !said.filter { it.isDigit() }.contains(digits.takeLast(9))) nav.put(UssdNavigatorTool.ARG_UNSAID, true)
        nav.optString("amount").takeIf { it.isNotBlank() }?.let { amt ->
            if (!amountSaid(amt, said)) nav.put(UssdNavigatorTool.ARG_UNSAID_AMOUNT, true)
        }
        return AgentDecision.CallTool(NAV, nav, "the planner's Mobile Money request — walked on the phone, by the navigator")
    }

    /** [amount] (cedis) is in [said] — as a figure or number words, in cedis or as pesewas, in English or Twi. Pure. */
    internal fun amountSaid(amount: String, said: String): Boolean {
        val a = amount.replace(",", "").trim().toBigDecimalOrNull() ?: return false
        val figures = (com.tekyerema.voice.Normalizer.normalize(said, com.tekyerema.voice.Lang.GH_EN) + " " +
            com.tekyerema.voice.Normalizer.normalize(said, com.tekyerema.voice.Lang.TWI))
            .replace(Regex("(\\d),(\\d{3})\\b"), "$1$2")   // "1,250" is one thousand two hundred and fifty
            .let { Regex("\\d+(?:[.,]\\d+)?").findAll(it) }.mapNotNull { it.value.replace(",", ".").toBigDecimalOrNull() }.toList()
        return figures.any { f -> f.compareTo(a) == 0 || f.compareTo(a.movePointRight(2)) == 0 }
    }

    const val MADE_UP_CODE = "I don't have a checked code for that, and I never dial a code I made up. Nothing was dialled."

    /** A code may be dialled when the user said it ("dial star 1 3 5 hash", "*135#") or it is in the checked tables: the
     *  balance code, the networks' data / offers menus, the Mobile Money and bank roots. */
    private val TYPED_CODE = Regex("^\\*\\d{2,4}(?:\\*[\\d.]+)*#?$")

    internal fun codeAllowed(code: String, goal: String): Boolean {
        val said = goal.lowercase().replace(Regex("\\bstar\\b"), "*").replace(Regex("\\b(?:hash|pound)\\b"), "#")
            .replace(Regex("[\\s,]"), "")
        if (said.contains(code)) return true
        if (code in com.tekyerema.policy.PolicyConfig.freeReadOnlyCodes()) return true
        if (code in TelcoUssd.checkedCodes()) return true
        val roots = com.tekyerema.tools.GhanaMobileMoney.moneyPrefixes() + BankUssd.moneyPrefixes()
        return roots.any { code == "$it#" }
    }

    fun decide(goal: String, history: List<String>): AgentDecision? {
        if (!enabled()) return null
        // A correction the engine could not apply itself is the planner's (RoutingBrain.pendingRevision) — never re-proposed here.
        if (com.tekyerema.agent.RoutingBrain.pendingRevision(history)) return null
        recordsMode(goal)?.let { return records(it, history) }
        // "I want you to buy" — the recogniser stopped before the rest (A20e, 14 Oct 2026 00:11). One question, here, and the
        // answer is a money request in its own right (MoneyPlan.fromAnswer) — the planner used to take it from here and
        // spent two minutes on web searches and codes typed into the dialer.
        if (!walked(history) && BARE_BUY.matches(goal.lowercase().replace('’', '\'').trim())) return question(history, WHAT_PREFIX, WHAT_WORDINGS)
        val ask = try { MoneyAsk.parse(goal) } catch (_: Throwable) { null }
            ?: return if (history.isEmpty()) notYet(goal)?.let { AgentDecision.Done(it) } else null
        return when (ask.kind) {
            MoneyAsk.Kind.SEND -> send(ask, history, goal)
            MoneyAsk.Kind.AIRTIME -> airtime(ask, history)
            MoneyAsk.Kind.BALANCE_UNCLEAR -> balance(history)
            MoneyAsk.Kind.BALANCE_MOMO -> if (walked(history)) null else momoBalance()
            MoneyAsk.Kind.BALANCE_AIRTIME -> null                     // LocalBrain's own rule (it knows the Twi wordings too)
            MoneyAsk.Kind.STATUS -> records(MoneyStatusTool.STATUS, history)
            MoneyAsk.Kind.LAST -> records(MoneyStatusTool.LAST, history)
            MoneyAsk.Kind.SPENT -> records(MoneyStatusTool.SPENT, history)
        }
    }

    // ------------------------------------------------------------------ send
    /**
     * Demo step 3 (owner, 14 Oct 2026): "send money to him" right after a message asked for money — the amount, the number IN
     * THE CHAT and the name come from that message ([MoneyRequest]); the read-back then reads the number in full and says it
     * came from the message. A "him" with no such message is the person just talked about (References).
     */
    private fun fromRequest(first: MoneyAsk.Ask, goal: String): Pair<MoneyAsk.Ask, Boolean> {
        if (first.number != null) return first to false
        if (first.name != null && !com.tekyerema.android.ContactResolver.isPronoun(first.name)) {
            // "send money to Kofi" right after Kofi Demo's message asked for money is that request too (rehearsal, 15 Oct 2026:
            // it asked "How much … to Kofi?" instead). The read-back names Kofi Demo and reads the message's number in full,
            // so another Kofi is one "no" away. A different full name ("Kofi Sample") is not this request.
            val r = MoneyRequest.fresh()?.takeIf { sameAsSender(first.name, it) } ?: return first to false
            return first.copy(number = r.number, name = r.name, amount = first.amount ?: r.amount, forSelf = false) to (r.number != null)
        }
        MoneyRequest.fresh()?.let { r ->
            return first.copy(number = r.number, name = r.name, amount = first.amount ?: r.amount, forSelf = false) to (r.number != null)
        }
        val person = if (PRONOUN_TO.containsMatchIn(goal)) com.tekyerema.agent.References.recentPerson() else null
        return (if (person != null) first.copy(name = person, forSelf = false) else first) to false
    }
    private val PRONOUN_TO = Regex("(?i)\\b(?:to|for)\\s+(?:him|her|them)\\b|\\bsend\\s+(?:him|her|them)\\b")
    /** [said] is the request's person: their name (or the sender's) holds it as whole words — "Kofi" in "Kofi Demo" — or a
     *  one-word name a letter off a word of it ("Kofe": the Twi path doesn't correct names to the contacts first —
     *  rehearsal twi 09). Four letters or more for the near-miss; the read-back names the person either way. */
    internal fun sameAsSender(said: String, r: MoneyRequest.Req): Boolean {
        val s = said.trim()
        if (s.length < 2) return false
        val words = Regex("(?i)(?<![\\p{L}])" + Regex.escape(s) + "(?![\\p{L}])")
        if (words.containsMatchIn(r.name) || words.containsMatchIn(r.sender)) return true
        if (s.contains(' ') || s.length < 4) return false
        return (r.name.split(' ') + r.sender.split(' ')).any { w -> w.length >= 4 && oneLetterOff(s.lowercase(), w.lowercase()) }
    }
    private fun oneLetterOff(a: String, b: String): Boolean {
        if (a == b) return true
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0; var j = 0; var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when { a.length > b.length -> i++; a.length < b.length -> j++; else -> { i++; j++ } }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }

    private fun send(first: MoneyAsk.Ask, history: List<String>, goal: String = ""): AgentDecision? {
        if (walked(history)) return null                               // the walk's own end (MoneyPlan.end) takes it from here
        val (filled, fromMessage) = fromRequest(first, goal)
        val a = withAnswers(filled, history)
        a.badNumber?.let { return AgentDecision.Done(badNumberLine(it)) }
        val to = a.name ?: a.number?.let { "the number ending ${it.takeLast(3).toList().joinToString(" ")}" }
        val amount = a.amount
        if (amount == null || amount.toBigDecimalOrNull()?.let { it.signum() <= 0 } != false) {
            val zero = amount != null
            return question(history, AMOUNT_PREFIX,
                listOf((if (zero) "I heard zero cedis. " else "") + "$AMOUNT_PREFIX do you want to send" + (to?.let { " to $it" } ?: "") + "?",
                    "$AMOUNT_PREFIX, in cedis? For example, say “twenty cedis”."))
        }
        if (a.number == null && a.name == null && a.lostNumber != null) return lostNumber(history)
        if (a.number == null && a.name == null)
            return question(history, WHO_PREFIX, listOf("$WHO_PREFIX the ${cedis(amount)} to? Say a name from your contacts, or the phone number.",
                "$WHO_PREFIX it to? Say the name as it is saved, or the ten digits of the number."))
        // The reference is ALWAYS asked by voice, before the read-back (owner, 14 Oct 2026: "always asks for the reference by
        // voice") — so it is read back with the money and typed by the walk without a second question.
        if (a.reference == null && AppConfig.bool("money.flow.askReference", true)) return question(history, REF_PREFIX, REF_WORDINGS)
        val args = JSONObject().put("amount", amount)
        if (a.number != null) args.put("number", a.number) else args.put(UssdNavigatorTool.ARG_CONTACT, a.name)
        if (a.number != null && a.name != null && !com.tekyerema.android.ContactResolver.isPronoun(a.name)) args.put(UssdNavigatorTool.ARG_CONTACT, a.name)
        // The number came from the message, not from the user: read in full, with a "from the message" warning.
        if (fromMessage && a.number == filled.number) args.put(UssdNavigatorTool.ARG_FROM_MESSAGE, true)
        a.reference?.let { args.put("reference", it) }
        return AgentDecision.CallTool(NAV, args, R_SEND)
    }

    // ------------------------------------------------------------------ airtime
    private fun airtime(first: MoneyAsk.Ask, history: List<String>): AgentDecision? {
        if (!AppConfig.bool("money.flow.airtime", true)) return null   // the older route: mobile_money opens the menu
        if (walked(history)) return null
        val a = withAnswers(first, history)
        a.badNumber?.let { return AgentDecision.Done(badNumberLine(it)) }
        val amount = a.amount
        if (amount == null || amount.toBigDecimalOrNull()?.let { it.signum() <= 0 } != false)
            return question(history, AMOUNT_PREFIX, listOf("$AMOUNT_PREFIX airtime do you want to buy" +
                (a.name?.let { " for $it" } ?: "") + "?", "$AMOUNT_PREFIX airtime, in cedis? For example, say “five cedis”."))
        if (a.number == null && a.name == null && a.lostNumber != null) return lostNumber(history)
        val other = !a.forSelf || a.number != null || a.name != null
        if (other && a.number == null && a.name == null)
            return question(history, WHO_PREFIX, listOf("$WHO_PREFIX the airtime to? Say a name from your contacts, or the phone number.",
                "$WHO_PREFIX it to? Say the name as it is saved, or the ten digits of the number."))
        val args = JSONObject().put("amount", amount)
            .put(UssdNavigatorTool.ARG_INTENT, if (other) UssdNavigatorTool.SEND_AIRTIME else UssdNavigatorTool.BUY_AIRTIME)
        if (other) { if (a.number != null) args.put("number", a.number) else args.put(UssdNavigatorTool.ARG_CONTACT, a.name) }
        return AgentDecision.CallTool(NAV, args, R_AIRTIME)
    }

    /** The number was destroyed by the recogniser: asked for again — never looked up, never guessed. The answer is read
     *  as a phone number (voice/understand/AnswerContext: "oh five five…", "double", "+233"). */
    private fun lostNumber(history: List<String>): AgentDecision = question(history, NUMBER_PREFIX,
        listOf("$NUMBER_PREFIX. Please say the phone number again.", "$NUMBER_PREFIX. Please say the ten digits of the number, one at a time."))

    // ------------------------------------------------------------------ balance
    private fun momoBalance() = AgentDecision.CallTool(NAV,
        JSONObject().put(UssdNavigatorTool.ARG_INTENT, UssdNavigatorTool.CHECK_BALANCE), R_BALANCE)

    private fun balance(history: List<String>): AgentDecision? {
        if (walked(history)) return null
        val last = LocalBrain.lastStep(history)
        if (last.startsWith("run_ussd(")) return spoken(last, "I couldn't get your airtime balance.")
        val answer = answers(history).lastOrNull { it.first == Q_BALANCE }?.second
            ?: return if (asked(history, Q_BALANCE)) AgentDecision.Done(GAVE_UP)
            // (No separate options list: the question names both, and with one it was said twice — "…or your airtime
            // balance? Mobile Money, or airtime?", phone re-check 2 Oct.)
            else AgentDecision.CallTool("ask_user", JSONObject().put("question", Q_BALANCE), R_ASK)
        return when (MoneyAsk.balanceKind(answer)) {
            MoneyAsk.Kind.BALANCE_MOMO -> momoBalance()
            MoneyAsk.Kind.BALANCE_AIRTIME -> AgentDecision.CallTool("run_ussd",
                JSONObject().put("code", AppConfig.string("connectors.telco.airtimeBalanceCode", "*124#")), R_AIRTIME_BALANCE)
            else -> AgentDecision.Done("I didn't catch which one. Say “check my Mobile Money balance” or “check my airtime balance”.")
        }
    }

    // ------------------------------------------------------------------ status / history
    private fun records(mode: String, history: List<String>): AgentDecision {
        val last = LocalBrain.lastStep(history)
        if (last.startsWith("${MoneyStatusTool.NAME}(")) return spoken(last, "I couldn't read your Mobile Money records just now.")
        return AgentDecision.CallTool(MoneyStatusTool.NAME, JSONObject().put(MoneyStatusTool.ARG_MODE, mode), R_STATUS)
    }

    /** A read's own result is the answer; its failure is said as a failure, never as a completion. */
    private fun spoken(last: String, fallback: String): AgentDecision {
        val msg = MoneyPlan.spokenPart(LocalBrain.resultMessage(last))
        return if (last.contains("→ success")) AgentDecision.Done(msg.ifBlank { fallback })
        else AgentDecision.CannotDo("money", msg.ifBlank { fallback })
    }

    // ------------------------------------------------------------------ slots from the user's answers
    private const val AMOUNT_PREFIX = "How much"
    private const val WHO_PREFIX = "Who should I send"
    const val REF_PREFIX = "What should the reference say"
    private val REF_WORDINGS = listOf("$REF_PREFIX? For example, say “transport” or “school fees”.",
        "$REF_PREFIX, in a word or two? Or say “no reference”.")
    /** The answer to [REF_PREFIX]: "no reference" / "none" / "skip" → "Payment" (MTN wants something there). */
    internal fun referenceOf(answer: String): String? {
        val t = answer.trim().trim('.', '!', '"', '“', '”').trim()
        if (t.isEmpty()) return null
        if (Regex("(?i)^(?:no(?:ne| reference)?|nothing|skip(?: it)?|leave it(?: empty| blank)?|no need|any(?:thing)?)$").matches(t)) return "Payment"
        // USSD takes the phone's basic alphabet: the Twi letters ɛ / ɔ (a Twi recogniser's "transpɔt") can't be typed into
        // MTN's menu — and "transpɔt" was read back as the reference (A20e, 17 Oct 2026). Plain letters instead.
        return t.removePrefix("the reference is ").removePrefix("reference ").removePrefix("it's ").removePrefix("it is ").trim()
            .replace('ɛ', 'e').replace('Ɛ', 'E').replace('ɔ', 'o').replace('Ɔ', 'O').take(30)
    }
    /** "I want you to buy" and nothing more: what, how much, for whom — asked once, answered as a whole request. */
    const val WHAT_PREFIX = "What should I buy"
    private val WHAT_WORDINGS = listOf(
        "$WHAT_PREFIX — airtime, data, or a bundle? Say the amount, and the number if it's for someone else.",
        "$WHAT_PREFIX? For example, say “2 cedis of airtime for 0 2 4…”, or “a 3-cedi data bundle”.")
    private val BARE_BUY = Regex("^(?:please |kindly |so |okay )*(?:i want (?:you )?to |i'd like (?:you )?to |i would like (?:you )?to |can you |could you |help me )?" +
        "(?:buy|purchase|top ?up|recharge)(?: it| some| something| for me| me| one)?\\s*[.!?]*$")

    /** The (question, answer) of one history line, or null when it isn't an answered question. */
    internal fun answerOf(line: String): Pair<String, String>? = ASKED.find(line)?.let { m -> m.groupValues[1] to m.groupValues[2] }
    /** The number was said but the recogniser destroyed it: asked for again, on its own (end-to-end pass, 6 October 2026). */
    private const val NUMBER_PREFIX = "I couldn't catch the phone number"
    private val ASKED = Regex("^ask_user\\(\"(.*?)\"\\) → the user answered: \"(.*?)\"(?: \\(= .*\\))?$")

    /** (question, answer) for every question of this task that was answered, oldest first. */
    internal fun answers(history: List<String>): List<Pair<String, String>> =
        history.mapNotNull { ASKED.find(it)?.let { m -> m.groupValues[1] to m.groupValues[2] } }

    private fun asked(history: List<String>, question: String): Boolean = history.any { it.startsWith("ask_user(\"$question\")") }

    /** The request with what the user answered since — a later answer replaces an earlier one. */
    internal fun withAnswers(first: MoneyAsk.Ask, history: List<String>): MoneyAsk.Ask {
        var a = first
        for ((q, ans) in answers(history)) when {
            q.startsWith(REF_PREFIX) -> referenceOf(ans)?.let { a = a.copy(reference = it) }
            q.contains(AMOUNT_PREFIX) -> MoneyAsk.amount(ans)?.let { a = a.copy(amount = it) }
            q.startsWith(WHO_PREFIX) || q.startsWith(NUMBER_PREFIX) -> MoneyAsk.recipient(ans)?.let { r ->
                val lost = r.name?.takeIf { com.tekyerema.voice.understand.SpokenNumbers.looksLikeLostDigits(it) }
                a = a.copy(number = r.number, badNumber = r.badNumber, name = if (lost != null) null else r.name,
                    lostNumber = lost, forSelf = false) }
        }
        return a
    }

    /** The next wording of a question not asked yet (the engine never asks the same words twice); after the last, stop. */
    private fun question(history: List<String>, prefix: String, wordings: List<String>): AgentDecision {
        val next = wordings.firstOrNull { !asked(history, it) } ?: return AgentDecision.Done(GAVE_UP)
        // Two tries per detail: a third unanswered question helps nobody.
        if (history.count { it.startsWith("ask_user(\"") && it.contains(prefix) } >= 2) return AgentDecision.Done(GAVE_UP)
        return AgentDecision.CallTool("ask_user", JSONObject().put("question", next), R_ASK)
    }

    /** A walk (or the older tools) already ran in this task: nothing more is proposed from here. */
    internal fun walked(history: List<String>): Boolean =
        history.any { it.startsWith("$NAV(") || it.startsWith("momo_send(") || it.startsWith("mobile_money(") }

    private fun cedis(amount: String): String = com.tekyerema.tools.GhanaMobileMoney.cedisPhrase(amount)

    /** A number heard with the wrong count of digits is said back and asked for again — never walked with. */
    internal fun badNumberLine(digits: String): String {
        val net = NumberSpeech.networkKey(digits.take(3) + "0000000")?.let { NumberSpeech.networkName(it) }
        return "I heard the number ${NumberSpeech.spoken(digits)} — that's ${digits.length} digits, and a Ghana mobile number has 10. " +
            (net?.let { "It starts like ${NumberSpeech.networkPhrase(it)}. " } ?: "") + "Please say the whole request again with the full number."
    }
}
