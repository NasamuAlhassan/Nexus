package com.tekyerema.money

import com.tekyerema.agent.FastOutcome
import java.math.BigDecimal

/** A spending-limit command said or typed by the user (no model involved). */
sealed class LimitCommand {
    data class Set(val kind: MoneyLimits.Kind, val amount: BigDecimal) : LimitCommand()
    /** "Set my MoMo limit" with no amount. */
    object SetNoAmount : LimitCommand()
    /** [kind] null = both limits. */
    data class Remove(val kind: MoneyLimits.Kind?) : LimitCommand()
    object Query : LimitCommand()
}

/**
 * The spending limits by voice, on the local fast path right after the operator's own controls (they are the user's
 * controls too, so they work in every mode and offline): "set my MoMo limit to 200 cedis", "set my daily MoMo limit to
 * 500", "what is my MoMo limit", "remove my MoMo limit". Raising or removing waits out the cooling-off ([MoneyLimits]).
 * Also the "no" to "Should I call MTN on 100?" ([MoMoAfterCare]). Classification is pure → unit-tested.
 */
object MoneyCommands {
    private const val MOMO = "(?:momo|mo mo|mobile money|money|spending|sending|send|sends|transfer|transfers|payment|payments)"
    private val MOMO_WORD = Regex("\\b$MOMO\\b")
    private val CURRENCY = Regex("\\b(?:cedis?|ghs|ghc|pesewas?)\\b|₵|¢")
    private val LIMIT = Regex("\\blimits?\\b|\\bcap\\b")
    private val DAILY = Regex("\\bdaily\\b|\\bper day\\b|\\ba day\\b|\\beach day\\b|\\bevery day\\b|\\bday's\\b")
    private val PER_SEND = Regex("\\bper[- ]?(?:send|transfer|transaction)\\b|\\bsingle[- ]send\\b|\\beach (?:send|transfer)\\b|\\bone send\\b|\\bat once\\b")
    private val SET_VERB = Regex("^(?:please |can you |could you |kindly )*(?:set|change|make|put|update|lower|raise|reduce|increase|move|limit|cap)\\b")
    private val REMOVE = Regex("^(?:please |can you |could you |kindly )*(?:remove|delete|clear|cancel|lift|drop|turn off|switch off|take off|take away|" +
        "get rid of)\\b|^(?:i )?(?:don't|do not) want (?:a |any |my )?|^no more\\b")
    private val QUERY = Regex("^(?:what(?:'s| is| are)|tell me|say|do i have|have i set|is there)\\b")
    private val HOW_MUCH = Regex("^how much (?:can|may) i (?:send|spend|transfer)(?: (?:with|on|by|using) (?:momo|mobile money))?(?: today| now| per send| at once| in a day)?$")
    /** An amount after "to / at / of": "200", "GHS 200", "200 cedis", "50 pesewas". */
    private val AMOUNT = Regex("\\b(?:to|at|of)\\s+(?:gh₵|ghs|ghc|gh¢|₵|¢)?\\s*(\\d+(?:\\.\\d{1,2})?)\\s*(cedis?|ghs|ghc|pesewas?)?")

    fun normalize(raw: String): String =
        raw.trim().lowercase().replace('’', '\'').replace(Regex("(?<=\\d),(?=\\d{3}\\b)"), "")
            .replace(Regex("[,;:!?\"“”]"), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.').trim()

    fun classify(raw: String): LimitCommand? {
        val g = normalize(raw)
        if (g.isEmpty()) return null
        if (HOW_MUCH.matches(g)) return LimitCommand.Query
        if (!LIMIT.containsMatchIn(g)) return null
        // "my data limit", "call limit", "screen time limit": not money.
        if (!MOMO_WORD.containsMatchIn(g) && !CURRENCY.containsMatchIn(g)) return null
        val kind = when {
            DAILY.containsMatchIn(g) -> MoneyLimits.Kind.DAILY
            PER_SEND.containsMatchIn(g) -> MoneyLimits.Kind.PER_SEND
            else -> null
        }
        if (REMOVE.containsMatchIn(g)) return LimitCommand.Remove(kind)
        if (QUERY.containsMatchIn(g)) return LimitCommand.Query
        if (!SET_VERB.containsMatchIn(g) && !Regex("^(?:my |the )?(?:daily )?$MOMO? ?limits? (?:is|should be|to)\\b").containsMatchIn(g)) return null
        val m = AMOUNT.find(g) ?: return LimitCommand.SetNoAmount
        var amount = m.groupValues[1].toBigDecimalOrNull() ?: return LimitCommand.SetNoAmount
        if (m.groupValues[2].startsWith("pesewa")) amount = amount.movePointLeft(2)
        return LimitCommand.Set(kind ?: MoneyLimits.Kind.PER_SEND, amount)
    }

    /** Apply [cmd] and say what happened (pure but for [MoneyLimits]' state). */
    fun apply(cmd: LimitCommand, now: Long = System.currentTimeMillis()): String = when (cmd) {
        is LimitCommand.Set -> MoneyLimits.changeLine(MoneyLimits.set(cmd.kind, cmd.amount, now), now)
        LimitCommand.SetNoAmount -> "Say the amount too — for example, “set my MoMo limit to 200 cedis”, or “set my daily MoMo limit to 500”."
        is LimitCommand.Remove -> if (cmd.kind != null) MoneyLimits.changeLine(MoneyLimits.set(cmd.kind, null, now), now)
            else MoneyLimits.removeAllLine(MoneyLimits.Kind.entries.map { MoneyLimits.set(it, null, now) }, now)
        LimitCommand.Query -> MoneyLimits.statusLine(now)
    }

    /** The fast path's hook (LocalFastRouter.tryHandle): a limit command or a "no" to the call offer; null = not ours. */
    fun handle(raw: String, now: Long = System.currentTimeMillis()): FastOutcome? {
        MoMoAfterCare.declineLine(raw, now)?.let { return FastOutcome("MONEY_CALL_DECLINED", it, systemVoice = true) }
        val cmd = classify(raw) ?: return null
        val name = "MONEY_LIMIT_" + (cmd::class.simpleName ?: "COMMAND").uppercase()
        return FastOutcome(name, apply(cmd, now), systemVoice = true, ok = cmd != LimitCommand.SetNoAmount)
    }
}
