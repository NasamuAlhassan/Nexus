package com.tekyerema.phonecare

import android.content.Context
import com.tekyerema.agent.FastIntent
import com.tekyerema.agent.FastOutcome
import com.tekyerema.core.config.FeatureFlags

/**
 * Phone care by voice, on the Local Fast Path (no model): "check my phone", "tell me when I receive money", "stop
 * telling me about missed calls", "warn me when my battery is low", "read it" / "read my alerts".
 *
 * Every pattern is a WHOLE utterance, so "check my phone and call Ama" still reaches the agent. Checked before the older
 * fast-path rules on purpose: "tell me about missed calls" is a switch here, not the call history; "stop telling me about
 * money" is not a person called Money (A-12's watch rule); "warn me when my battery is low" is not the battery level.
 * A bare "read it" is claimed ONLY in the few minutes after a private alert said "say 'read it'" — at any other time it
 * keeps meaning the screen. Pure → unit-tested.
 */
object PhoneCareIntents {
    const val MONEY = PhoneAlerts.MONEY
    const val MISSED_CALL = PhoneAlerts.MISSED_CALL
    const val BATTERY = PhoneAlerts.BATTERY
    const val BALANCE = "balance"
    const val PRIVATE = "private"

    private const val LEAD = "^(?:(?:please|okay|ok|hey|so|and|tekyerema|tɛkyerɛma|can you|could you|would you|will you)[, ]+)*"
    private const val TAIL = "(?: (?:please|for me|now|again))*$"

    private val CHECK = Regex(LEAD + "(?:" +
        "check (?:my|the|this) phone(?: for (?:me|problems|any problems))?|" +
        "(?:run|do|give me) (?:a |an )?(?:quick |full )?(?:phone |system |self[- ]?)?(?:check|check-?up|test|diagnosis|diagnostic)|" +
        "(?:phone|self|system)[- ]?(?:check|test)|check-?up|" +
        "check (?:yourself|your setup|your settings|the setup|everything)|" +
        "check (?:if|whether|that) everything(?:'s| is) (?:working|ok|okay|fine|alright|all right)|" +
        "is everything (?:working|ok|okay|fine|alright|all right)(?: with (?:you|my phone|the phone|this phone))?|" +
        "is my phone (?:ok|okay|fine|alright|all right|set up (?:right|properly|correctly))|" +
        "why (?:aren't|are not|isn't|is not|don't|do not|won't) you (?:working|responding|answering|doing anything)(?: properly| right| well)?|" +
        "why (?:are you|is tekyerema|is tɛkyerɛma) (?:not working|so slow|slow|not responding)|" +
        "what(?:'s| is) wrong with (?:you|my phone|the phone|this phone|tekyerema|tɛkyerɛma)|" +
        "is (?:something|anything) wrong(?: with (?:you|my phone|the phone|this phone))?|" +
        "diagnose (?:my phone|the phone|yourself|the problem|what's wrong)" +
        ")$TAIL")

    /** Only after a private alert: "read it", "yes read it", "tell me", "go on". */
    private val READ_IT = Regex("^(?:(?:ok|okay|yes|yeah|please)[, ]+)*(?:read it(?: out)?(?: to me)?|read that|tell me|go on|say it|what is it|what's the alert)(?: please)?$")
    /** Any time: the alerts by name. "read my alerts out loud" READS them — it never switches privacy off. */
    private val READ_ALERTS = Regex(LEAD + "(?:(?:read|tell me|say|repeat) (?:me )?(?:my |the |that |those |these )?(?:last |latest |new |waiting )?(?:money |missed call |battery )?alerts?(?: out loud| aloud)?|" +
        "what (?:is|was|are|were) (?:my |the |that )?(?:last |latest |new )?alerts?|(?:do i have|have i got|are there) (?:any )?(?:new )?alerts|any (?:new )?alerts)$TAIL")

    private const val TELL = "(?:tell me|let me know|alert me|notify me|warn me|inform me)"
    private const val STOP_TELL = "(?:stop|don't|do not|never) (?:telling|tell|alerting|alert|notifying|notify|warning|warn|informing|inform|letting)(?: me)?(?: know)?"
    private const val MONEY_WORD = "(?:money|momo|mobile money|payments?|cash|cedis)"

    private val SWITCHES: List<Triple<Regex, String, Boolean>> = listOf(
        // ---- money
        Triple(Regex(LEAD + "$TELL (?:when|whenever|if|each time|every time) (?:(?:i|we) (?:receive|get)(?: any| some)? (?:money|momo|mobile money|a payment|payments|cash)|" +
            "(?:someone|anyone|somebody|people) (?:sends?|pays?) me(?: money| momo)?|(?:money|momo|mobile money|a payment|cash) (?:comes in|arrives|is received|is sent to me|lands))$TAIL"), MONEY, true),
        Triple(Regex(LEAD + "(?:(?:turn|switch) on (?:the )?|enable (?:the )?|start (?:the )?)$MONEY_WORD alerts?$TAIL|^$MONEY_WORD alerts? on$"), MONEY, true),
        Triple(Regex(LEAD + "$STOP_TELL (?:about|of|when i (?:receive|get)) $MONEY_WORD(?: alerts?)?(?: any more| anymore)?$TAIL"), MONEY, false),
        Triple(Regex(LEAD + "(?:(?:turn|switch) off (?:the )?|disable (?:the )?|stop (?:the )?|no more )$MONEY_WORD alerts?$TAIL"), MONEY, false),
        // ---- missed calls
        // Not "tell me about MY / ANY missed calls": those ask to hear the calls (the call history), as before.
        Triple(Regex(LEAD + "$TELL (?:about|of) missed calls$TAIL"), MISSED_CALL, true),
        Triple(Regex(LEAD + "$TELL (?:when|whenever|if) (?:i miss (?:a call|someone's call|a call from someone)|i have a missed call|(?:someone|anyone) calls and i miss it)$TAIL"), MISSED_CALL, true),
        Triple(Regex(LEAD + "(?:(?:turn|switch) on (?:the )?|enable (?:the )?)missed call alerts?$TAIL|^missed call alerts? on$"), MISSED_CALL, true),
        Triple(Regex(LEAD + "$STOP_TELL (?:about|of) (?:my )?missed calls(?: any more| anymore)?$TAIL"), MISSED_CALL, false),
        Triple(Regex(LEAD + "(?:(?:turn|switch) off (?:the )?|disable (?:the )?|stop (?:the )?|no more )missed call alerts?$TAIL"), MISSED_CALL, false),
        // ---- battery
        Triple(Regex(LEAD + "$TELL (?:when|whenever|if|before) (?:my |the )?(?:battery|phone|charge) (?:is|gets|goes|runs|becomes) (?:low|down|flat|finishing|weak)$TAIL"), BATTERY, true),
        Triple(Regex(LEAD + "(?:(?:turn|switch) on (?:the )?|enable (?:the )?)(?:low )?battery (?:alerts?|warnings?)$TAIL"), BATTERY, true),
        Triple(Regex(LEAD + "$STOP_TELL (?:about|of) (?:my |the )?(?:low )?battery(?: alerts?| warnings?)?(?: any more| anymore)?$TAIL"), BATTERY, false),
        Triple(Regex(LEAD + "(?:(?:turn|switch) off (?:the )?|disable (?:the )?|stop (?:the )?|no more )(?:low )?battery (?:alerts?|warnings?)$TAIL"), BATTERY, false),
        // ---- the balance inside money alerts. Only explicit words LOOSEN privacy: "tell me my balance (too)" is a Mobile
        // Money question for the agent, never a standing switch; saying less ("don't tell me my balance") may be loose.
        Triple(Regex(LEAD + "(?:include|add|put|say|read|tell me) (?:my |the )?balance (?:in|with) (?:my |the )?money alerts$TAIL|" +
            LEAD + "include (?:my |the )?balance(?: in (?:my |the )?(?:money )?alerts)?$TAIL"), BALANCE, true),
        Triple(Regex(LEAD + "(?:don't|do not|stop|never) (?:tell|telling|say|saying|read|reading) (?:me )?(?:my |the )?balance(?: in (?:my |the )?(?:money )?alerts| aloud| out loud)?$TAIL"), BALANCE, false),
        // ---- loudspeaker privacy: switching it OFF needs the words "private alerts".
        Triple(Regex(LEAD + "(?:keep|make) (?:my )?alerts private$TAIL|" + LEAD + "(?:turn|switch) on private alerts$TAIL|^private alerts on$"), PRIVATE, true),
        Triple(Regex(LEAD + "(?:(?:turn|switch) off |stop (?:the )?|disable )private alerts$TAIL|^private alerts off$"), PRIVATE, false)
    )

    /**
     * @param g the classifier's normalised text (lower case, single spaces, no trailing punctuation).
     * @param readItPending a private alert was just announced ([PhoneAlerts.readItPending]).
     */
    fun classify(g: String, readItPending: Boolean = false): FastIntent? {
        if (g.isEmpty() || g.length > 120) return null
        if (CHECK.matches(g)) return FastIntent.CheckPhone
        if (readItPending && READ_IT.matches(g)) return FastIntent.ReadAlert
        if (READ_ALERTS.matches(g)) return FastIntent.ReadAlert
        for ((re, kind, on) in SWITCHES) if (re.matches(g)) return FastIntent.AlertSwitch(kind, on)
        return null
    }

    // ---------------------------------------------------------------- execution (Android)

    /** Run a phone-care fast intent; null for any other intent (or a switched-off feature → the agent, as before). */
    suspend fun execute(ctx: Context, intent: FastIntent): FastOutcome? {
        val app = ctx.applicationContext
        return when (intent) {
            // ok = true: finding problems is the check working ("I can't use the microphone…" must not play the error cue).
            FastIntent.CheckPhone -> if (!FeatureFlags.isEnabled(PhoneAlerts.FEATURE_CHECK, true)) null
                else FastOutcome(intent.name, PhoneCheck.run(app), ok = true)
            // The balance only when the user wants it in alerts NOW (it may have been switched off since the alert came).
            FastIntent.ReadAlert -> FastOutcome(intent.name,
                PhoneAlerts.readWaiting(withBalance = com.tekyerema.settings.Settings(app).alertMoneyBalance), ok = true)
            is FastIntent.AlertSwitch -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                FastOutcome(intent.name, switch(app, intent.kind, intent.on), systemVoice = true)
            }
            else -> null
        }
    }

    /** Apply a switch said aloud and say what changed — plus why it can't work yet, when it can't. */
    fun switch(app: Context, kind: String, on: Boolean): String {
        val s = com.tekyerema.settings.Settings(app)
        val feature = when (kind) { MONEY -> PhoneAlerts.FEATURE_MONEY; MISSED_CALL -> PhoneAlerts.FEATURE_MISSED_CALL; BATTERY -> PhoneAlerts.FEATURE_BATTERY; else -> null }
        if (on && feature != null && !FeatureFlags.isEnabled(feature, true)) return "Sorry, that alert is switched off on this phone."
        when (kind) {
            MONEY -> s.alertMoney = on
            MISSED_CALL -> s.alertMissedCalls = on
            BATTERY -> s.alertBattery = on
            BALANCE -> s.alertMoneyBalance = on
            PRIVATE -> s.alertsPrivate = on
        }
        val needsNotifications = on && kind in setOf(MONEY, MISSED_CALL)
        return switchLine(kind, on,
            moneyOn = s.alertMoney,
            notificationAccess = !needsNotifications || try { com.tekyerema.core.config.SystemChecks.notificationAccess(app) } catch (_: Throwable) { true },
            messageAlertsOn = com.tekyerema.operator.OperatorPolicy.messageAlertsEnabled)
    }

    /** The spoken confirmation for a switch, with at most one reason it can't work yet. Pure. */
    fun switchLine(kind: String, on: Boolean, moneyOn: Boolean = true, notificationAccess: Boolean = true, messageAlertsOn: Boolean = true): String {
        val base = when (kind) {
            MONEY -> if (on) "Okay. I'll tell you when you receive Mobile Money, but not your balance." else "Okay. I'll stop telling you about money."
            MISSED_CALL -> if (on) "Okay. I'll tell you when you miss a call from someone in your contacts." else "Okay. I'll stop telling you about missed calls."
            BATTERY -> if (on) "Okay. I'll warn you when your battery gets low." else "Okay. I'll stop warning you about the battery."
            BALANCE -> if (on) "Okay. Money alerts will include your balance." else "Okay. I won't say your balance in money alerts."
            PRIVATE -> if (on) "Okay. On the loudspeaker I'll only say that you have an alert. Say “read it” to hear it."
                else "Okay. I'll read alerts out straight away, even on the loudspeaker."
            else -> "Okay."
        }
        val caveat = when {
            !on -> null
            kind == BALANCE && !moneyOn -> "Money alerts are off. Say “tell me when I receive money” to switch them on."
            kind in setOf(MONEY, MISSED_CALL) && !notificationAccess ->
                "But I can't see your notifications yet. To fix it: in Tɛkyerɛma, open Settings, Permissions, Read messages."
            kind in setOf(MONEY, MISSED_CALL) && !messageAlertsOn ->
                "But message alerts are switched off in Settings, Control, so I'll stay quiet until you switch them on."
            else -> null
        }
        return if (caveat == null) base else "$base $caveat"
    }
}
