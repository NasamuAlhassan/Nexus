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
        return when {
            CASH_OUT.containsMatchIn(g) -> CASH_OUT_LINE
            BILL.containsMatchIn(g) -> BILL_LINE
            else -> null
        }
    }

    const val CASH_OUT_LINE = "I can't do a cash-out for you yet. It's done with a Mobile Money agent: the agent starts it, and you " +
        "approve it on your phone with your PIN. If the agent asks you to allow it first, that's Allow Cash Out in the star 1 7 0 hash menu on MTN."
    const val BILL_LINE = "I can't pay bills for you yet, so nothing was paid. On MTN it's under MoMoPay and Pay Bill in the star 1 7 0 hash menu."

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
    fun fromPlanner(d: AgentDecision, goal: String): AgentDecision {
        if (d !is AgentDecision.CallTool) return d
        val a = d.args
        fun s(key: String) = a.optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
        if (d.name == "run_ussd") {
            val code = s("code")?.replace(Regex("\\s"), "") ?: return d
            return if (codeAllowed(code, goal)) d else AgentDecision.CannotDo("money", MADE_UP_CODE)
        }
        if (!enabled()) return d
        val nav = JSONObject()
        when (d.name) {
            "momo_send" -> {
                nav.put("number", s("number") ?: return d).put("amount", s("amount") ?: return d)
                s("reference")?.let { nav.put("reference", it) }
            }
            "mobile_money" -> {
                val kind = s("_kind")
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
        // A number the user never said (the model took one from an earlier turn): the read-back says so.
        val digits = nav.optString("number").filter { it.isDigit() }
        if (digits.length >= 7 && !goal.filter { it.isDigit() }.contains(digits.takeLast(9))) nav.put(UssdNavigatorTool.ARG_UNSAID, true)
        return AgentDecision.CallTool(NAV, nav, "the planner's Mobile Money request — walked on the phone, by the navigator")
    }

    const val MADE_UP_CODE = "I don't have a checked code for that, and I never dial a code I made up. Nothing was dialled."

    /** A code may be dialled when the user said it ("dial star 1 3 5 hash", "*135#") or it is in the checked tables: the
     *  balance code, the networks' data / offers menus, the Mobile Money and bank roots. */
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
        val ask = try { MoneyAsk.parse(goal) } catch (_: Throwable) { null }
            ?: return if (history.isEmpty()) notYet(goal)?.let { AgentDecision.Done(it) } else null
        return when (ask.kind) {
            MoneyAsk.Kind.SEND -> send(ask, history)
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
    private fun send(first: MoneyAsk.Ask, history: List<String>): AgentDecision? {
        if (walked(history)) return null                               // the walk's own end (MoneyPlan.end) takes it from here
        val a = withAnswers(first, history)
        a.badNumber?.let { return AgentDecision.Done(badNumberLine(it)) }
        val to = a.name ?: a.number?.let { "the number ending ${it.takeLast(3).toList().joinToString(" ")}" }
        val amount = a.amount
        if (amount == null || amount.toBigDecimalOrNull()?.let { it.signum() <= 0 } != false) {
            val zero = amount != null
            return question(history, AMOUNT_PREFIX,
                listOf((if (zero) "I heard zero cedis. " else "") + "$AMOUNT_PREFIX do you want to send" + (to?.let { " to $it" } ?: "") + "?",
                    "$AMOUNT_PREFIX, in cedis? For example, say “twenty cedis”."))
        }
        if (a.number == null && a.name == null)
            return question(history, WHO_PREFIX, listOf("$WHO_PREFIX the ${cedis(amount)} to? Say a name from your contacts, or the phone number.",
                "$WHO_PREFIX it to? Say the name as it is saved, or the ten digits of the number."))
        val args = JSONObject().put("amount", amount)
        if (a.number != null) args.put("number", a.number) else args.put(UssdNavigatorTool.ARG_CONTACT, a.name)
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
        val other = !a.forSelf || a.number != null || a.name != null
        if (other && a.number == null && a.name == null)
            return question(history, WHO_PREFIX, listOf("$WHO_PREFIX the airtime to? Say a name from your contacts, or the phone number.",
                "$WHO_PREFIX it to? Say the name as it is saved, or the ten digits of the number."))
        val args = JSONObject().put("amount", amount)
            .put(UssdNavigatorTool.ARG_INTENT, if (other) UssdNavigatorTool.SEND_AIRTIME else UssdNavigatorTool.BUY_AIRTIME)
        if (other) { if (a.number != null) args.put("number", a.number) else args.put(UssdNavigatorTool.ARG_CONTACT, a.name) }
        return AgentDecision.CallTool(NAV, args, R_AIRTIME)
    }

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
    private val ASKED = Regex("^ask_user\\(\"(.*?)\"\\) → the user answered: \"(.*?)\"(?: \\(= .*\\))?$")

    /** (question, answer) for every question of this task that was answered, oldest first. */
    internal fun answers(history: List<String>): List<Pair<String, String>> =
        history.mapNotNull { ASKED.find(it)?.let { m -> m.groupValues[1] to m.groupValues[2] } }

    private fun asked(history: List<String>, question: String): Boolean = history.any { it.startsWith("ask_user(\"$question\")") }

    /** The request with what the user answered since — a later answer replaces an earlier one. */
    internal fun withAnswers(first: MoneyAsk.Ask, history: List<String>): MoneyAsk.Ask {
        var a = first
        for ((q, ans) in answers(history)) when {
            q.contains(AMOUNT_PREFIX) -> MoneyAsk.amount(ans)?.let { a = a.copy(amount = it) }
            q.startsWith(WHO_PREFIX) -> MoneyAsk.recipient(ans)?.let { r ->
                a = a.copy(number = r.number, badNumber = r.badNumber, name = r.name, forSelf = false) }
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
    private fun walked(history: List<String>): Boolean =
        history.any { it.startsWith("$NAV(") || it.startsWith("momo_send(") || it.startsWith("mobile_money(") }

    private fun cedis(amount: String): String = com.tekyerema.tools.GhanaMobileMoney.cedisPhrase(amount)

    /** A number heard with the wrong count of digits is said back and asked for again — never walked with. */
    internal fun badNumberLine(digits: String): String {
        val net = NumberSpeech.networkKey(digits.take(3) + "0000000")?.let { NumberSpeech.networkName(it) }
        return "I heard the number ${NumberSpeech.spoken(digits)} — that's ${digits.length} digits, and a Ghana mobile number has 10. " +
            (net?.let { "It starts like ${NumberSpeech.networkPhrase(it)}. " } ?: "") + "Please say the whole request again with the full number."
    }
}
