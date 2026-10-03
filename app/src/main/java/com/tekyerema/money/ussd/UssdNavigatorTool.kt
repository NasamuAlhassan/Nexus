package com.tekyerema.money.ussd

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.tekyerema.money.BankUssd
import com.tekyerema.money.TelcoSession
import com.tekyerema.money.TelcoUssd
import com.tekyerema.money.UssdDialog
import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.RiskTier
import com.tekyerema.tools.GhanaMobileMoney
import com.tekyerema.tools.MoMoSendTool
import com.tekyerema.tools.Tool
import com.tekyerema.tools.ToolContext
import com.tekyerema.tools.ToolResult
import com.tekyerema.tools.str
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The intent-driven USSD walk (owner's navigator brief, 2026-09-30): ONE up-front TIER3 read-back (recipient +
 * amount, with the money-safety warnings), then SILENT navigation by meaning — each live menu matched to the intent
 * (SemanticMatch: exact → keywords → the on-device embedding model; the small cloud model only when the phone is
 * unsure; the USER when nobody is) — slots answered from what was already confirmed, ONE consolidated line at the
 * network's own confirmation with its SHOWN NAME, and the PIN forever the user's (SecureStep). On any doubt the walk
 * hands over guided (TelcoSession seeds the guide from the CURRENT screen) — SAFE STOP beats a wrong press.
 *
 * Finance audit (2026-10-01): a send may name a CONTACT instead of a number — it is resolved here, before the read-back
 * (one match → its number, read back with the name; several → the engine's "which one?"; none → said so), so "send 50
 * cedis to Kofi" takes the same careful route as a typed number. Airtime (for this phone or for someone else) uses the
 * same walk with its own intent. Every message a preflight returns is said to the USER as it is (no model reads it).
 *
 * `money.navigator.enabled=false` restores the fixed momo_send driver unchanged.
 */
object UssdNavigatorTool : Tool {
    const val NAME = "ussd_navigate"
    private const val TAG = "Tekyerema"

    override val name = NAME
    override val description =
        "Send money via the network's USSD menu, or check the Mobile Money balance: navigates silently by meaning, reads the network's confirmation (registered name) and stops at the PIN. A send needs number + amount."
    override val scope = CapabilityScope.COMMS_SEND
    /** One read-back for anything that moves money. A balance check is the one call that is not confirmed — it stops at
     *  the user's PIN and changes nothing (PolicyConfig.readOnlyCheck); the coded tier stays TIER3 for every other layer. */
    override val riskTier = RiskTier.TIER3
    override val isAccelerator = true
    /** The on-phone brain routes sends here deterministically; the cloud planner keeps momo_send — no schema growth,
     *  and no model ever drives the navigator directly (§19: the LLM is never the execution authority). */
    override val hiddenFromModel = true

    /** What the walk is for: a send (the default, number + amount), airtime, or a balance check (no slots at all). */
    const val ARG_INTENT = "intent"
    const val SEND_MONEY = "send_money"
    const val CHECK_BALANCE = "check_balance"
    const val BUY_AIRTIME = "buy_airtime"
    const val SEND_AIRTIME = "send_airtime"
    /** A person named instead of a number — resolved against the contacts in [preflight] (the same key the engine's
     *  "which one?" writes its pick to). */
    const val ARG_CONTACT = "contact"
    /** Set by [preflight]: the earlier hand-off this send would repeat, as the warning to read back (money/TxLedger). */
    internal const val ARG_REPEAT = "_repeat"
    /** Set when a model supplied a number the user's request never said (money/MoneyFlow.fromPlanner). */
    const val ARG_UNSAID = "_unsaid"
    private const val UNSAID_LINE = "You didn't say this number — I chose it myself, so check it."
    const val BALANCE_PROMPT = "Check your Mobile Money balance? I'll go through the menu; you'll enter your PIN."

    // number + amount are checked in [preflight] for a send (a missing one is asked for again there); a balance check
    // carries neither — so they are not "required" at the argument check.
    override val params = listOf(
        com.tekyerema.tools.ToolParam("number", "string", "Recipient phone number (digits) — needed for a send.", required = false),
        com.tekyerema.tools.ToolParam("amount", "string", "Cedi amount — needed for a send.", required = false),
        com.tekyerema.tools.ToolParam("reference", "string", "Payment reference.", required = false),
        com.tekyerema.tools.ToolParam(ARG_CONTACT, "string", "Recipient by contact name (instead of number).", required = false),
        com.tekyerema.tools.ToolParam(ARG_INTENT, "string", "send_money (default) | check_balance | buy_airtime | send_airtime", required = false,
            enumValues = listOf(SEND_MONEY, CHECK_BALANCE, BUY_AIRTIME, SEND_AIRTIME))
    )

    /** A balance check: the menu is walked to the PIN, nothing is sent — no limits, no recipient, no receipt window. */
    internal fun isBalance(args: JSONObject): Boolean = args.str(ARG_INTENT) == CHECK_BALANCE

    internal fun intentOf(args: JSONObject): TxIntent = when (args.str(ARG_INTENT)) {
        CHECK_BALANCE -> TxIntent.CHECK_BALANCE
        BUY_AIRTIME -> TxIntent.BUY_AIRTIME
        SEND_AIRTIME -> TxIntent.SEND_AIRTIME
        else -> TxIntent.SEND_MONEY
    }

    /** What is wrong with the amount, said to the user, or null. Zero is never read back as a send (sweep 2026-10-01:
     *  "send 0 cedis to …" was). */
    internal fun amountProblem(raw: String?): String? {
        val v = com.tekyerema.money.MoneyLimits.parseAmount(raw)
            ?: return if (raw.isNullOrBlank()) "I didn't get the amount. Say the request again with the amount in cedis."
                else "I couldn't make out the amount “$raw” in cedis. Say the request again with the amount."
        return if (v.signum() <= 0) "I heard zero cedis, so there's nothing to send. Say the request again with the amount." else null
    }

    /** The contact's name and number for a walk that needs a recipient, or the failure to return. A number already in the
     *  arguments wins (the user dictated it, or picked one of several contacts). Internal: unit-tested with a resolver. */
    internal fun recipient(args: JSONObject, resolve: (String) -> List<com.tekyerema.android.ContactResolver.Contact>,
                           several: (String, List<com.tekyerema.android.ContactResolver.Contact>) -> ToolResult): ToolResult? {
        val contact = args.str(ARG_CONTACT)?.trim()?.takeIf { it.isNotEmpty() }
        if (args.str("number").isNullOrBlank()) {
            contact ?: return ToolResult(false, "I didn't get who it's for. Say the request again with a name from your contacts, or the phone number.",
                recoverable = false)
            if (com.tekyerema.android.ContactResolver.isPronoun(contact))
                return ToolResult(false, "I don't know who “$contact” is — no one was named. Say the request again with the name, or the phone number.",
                    recoverable = false)
            val matches = resolve(contact)
            when {
                matches.isEmpty() -> return ToolResult(false, "I couldn't find “$contact” in your contacts, so nothing was sent. " +
                    "Say it again with the name as it's saved, or with the phone number.", recoverable = false)
                matches.size > 1 -> return several(com.tekyerema.tools.ContactChoice.narrow(contact).first, matches)
            }
            args.put("number", matches[0].number).put(ARG_CONTACT, matches[0].name.trim())
        }
        val n = com.tekyerema.agent.NumberSpeech.normalize(args.str("number"))
        if (!(n.length == 10 && n.startsWith("0"))) {
            val who = args.str(ARG_CONTACT)?.let { "$it's saved number" } ?: "The number"
            return ToolResult(false, "$who, ${com.tekyerema.agent.NumberSpeech.spoken(n).ifBlank { "which I couldn't read" }}, isn't a ten-digit " +
                "Ghana mobile number, so I can't use it for Mobile Money. Nothing was sent.", recoverable = false)
        }
        args.put("number", n)
        return null
    }

    /** The same money preflights as the fixed driver: limits first, a malformed number said back, the SIM's
     *  network detected (never assumed), first-time-number, repeat and on-call warnings for the read-back. */
    override suspend fun preflight(args: JSONObject, ctx: ToolContext): ToolResult? {
        val intent = intentOf(args)
        val sim = Provider.current(ctx.appContext)
        if (sim.provider == null)
            return ToolResult(false, "I couldn't tell which network this SIM is on, so I won't guess a Mobile Money code.", recoverable = false)
        if (intent == TxIntent.CHECK_BALANCE) return null
        amountProblem(args.str("amount"))?.let { return ToolResult(false, it, recoverable = false) }
        val amount = com.tekyerema.money.MoneyLimits.parseAmount(args.str("amount"))!!
        args.put("amount", amount.toPlainString())
        if (intent == TxIntent.SEND_MONEY)
            com.tekyerema.money.MoneyLimits.check(amount)?.let { return ToolResult(false, it, recoverable = false) }
        args.put(MoMoSendTool.ARG_SIM, sim.provider)
        if (com.tekyerema.tools.MoneyPrivacy.onCall(ctx.appContext)) args.put(MoMoSendTool.ARG_ON_CALL, true) else args.remove(MoMoSendTool.ARG_ON_CALL)
        if (intent == TxIntent.BUY_AIRTIME) return null
        recipient(args, { com.tekyerema.tools.ContactChoice.resolve(ctx.appContext, it) },
            { said, matches -> com.tekyerema.tools.ContactChoice.result(ctx.appContext, said, matches) })?.let { return it }
        args.remove(MoMoSendTool.ARG_NEW_RECIPIENT); args.remove(ARG_REPEAT)
        if (intent == TxIntent.SEND_MONEY) {
            if (!com.tekyerema.money.KnownRecipients.isKnown(args.str("number"))) args.put(MoMoSendTool.ARG_NEW_RECIPIENT, true)
            com.tekyerema.money.TxLedger.repeatOf(args.str("number"), amount)?.let { args.put(ARG_REPEAT, com.tekyerema.money.TxLedger.repeatWarning(it)) }
        }
        return null
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        if (ctx.appContext.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
            return ToolResult(false, "Tɛkyerɛma isn't allowed to make calls, so I can't open Mobile Money. Allow Phone for Tɛkyerɛma in Settings.")
        val ctrl = ctx.controller ?: return ToolResult(false, "The accessibility service isn't running, so I can't read the menu.")
        val root = Provider.momoRoot(ctx.appContext)
            ?: return ToolResult(false, "I couldn't tell which network this SIM is on, so I won't guess a code.", recoverable = true)
        val sim = Provider.current(ctx.appContext)
        val intent = intentOf(args)
        val balance = intent == TxIntent.CHECK_BALANCE
        val contact = args.str(ARG_CONTACT)?.trim()?.takeIf { it.isNotEmpty() }
        val tx = if (balance) TxContext(intent = intent, provider = sim.provider, confirmedUpFront = true)
        else TxContext(
            intent = intent,
            provider = sim.provider,
            recipient = if (intent == TxIntent.BUY_AIRTIME) null else args.str("number")?.filter { it.isDigit() },
            recipientName = if (intent == TxIntent.BUY_AIRTIME) null else contact,
            amount = args.str("amount"),
            reference = args.str("reference")?.trim()?.ifBlank { null },
            confirmedUpFront = true
        )
        Log.i(TAG, "[NAV] opened $root (${sim.provider}${if (sim.dualSim) ", dual-SIM: default voice slot ${sim.slot}" else ""}) intent=${tx.intent}")
        if (!balance) com.tekyerema.money.MoMoRetry.clear()   // a balance check doesn't cancel a send waiting to be retried
        // A dialog left open is closed first, and a "Connection problem or invalid MMI code" (nothing ran) is redialled once.
        val first = try { UssdDialog.dial(ctx.appContext, ctrl, root) } catch (t: Throwable) {
            return ToolResult(false, "I couldn't open Mobile Money. ${nothing(intent)}", recoverable = true)
        } ?: return if (intent == TxIntent.SEND_MONEY)
                timedOut(args, "${com.tekyerema.agent.NumberSpeech.networkName(sim.provider ?: "the network")}'s menu didn't answer — " +
                    "a network problem on their side. Nothing was sent. Say try again in a moment.")
                else ToolResult(false, "The Mobile Money menu didn't answer — a network problem on their side. ${nothing(intent)}", recoverable = false)
        val embedder: ((String) -> FloatArray)? =
            com.tekyerema.agent.IntentMatcher.get(ctx.appContext)?.let { m -> { s: String -> m.embed(s) } }
        val online = OnlineFallback(try { com.tekyerema.settings.Settings(ctx.appContext).groqApiKeys } catch (_: Throwable) { emptyList() })
        val pinTip = com.tekyerema.tools.MoneyPrivacy.pinTip(com.tekyerema.tools.MoneyPrivacy.screenReaderOn(ctx.appContext),
            com.tekyerema.tools.MoneyPrivacy.privateAudio(ctx.appContext))
        val nav = Navigator(ctrl, tx, embedder, online, pinTip,
            paidBefore = intent == TxIntent.SEND_MONEY && com.tekyerema.money.KnownRecipients.isKnown(tx.recipient))
        return when (val out = nav.run(first)) {
            is Navigator.Outcome.Boundary -> {
                // The walk reached the authorization: the ledger entry (the outcome is the network's to tell), today's
                // total for a send, the follow-up look. (A balance check sends nothing: none of that.)
                if (!balance) com.tekyerema.money.MoneyStatus.handedOff(ctx.appContext,
                    if (intent == TxIntent.SEND_MONEY) com.tekyerema.money.TxLedger.What.SEND else com.tekyerema.money.TxLedger.What.AIRTIME,
                    com.tekyerema.money.MoneyLimits.parseAmount(tx.amount), tx.recipient, contact ?: out.registeredName, sim.provider,
                    registered = out.registeredName)
                // A balance is only an answer once it is HEARD: the network shows it on the screen after the PIN, where
                // a blind user cannot read it (2 October 2026: the walk reached the PIN, the owner entered it, and
                // nothing was said). The PIN prompt itself is never kept — see [readAfterPin].
                else readAfterPin(ctrl, sim.provider)
                ToolResult(true, out.line, data = JSONObject().put("boundary", true), channel = "native")
            }
            is Navigator.Outcome.Final -> ToolResult(true, out.line, data = JSONObject().put("final", true), channel = "native")
            is Navigator.Outcome.NeedUser -> {
                // Guided from the CURRENT screen: the guide asks, the user chooses, the same dialog machinery types.
                val now = UssdDialog.awaitCurrent(ctrl, 2_000L)
                val screenText = now?.text ?: ""
                val kind = if (now != null) BankUssd.kind(now.text, now.hasInput, now.masked) else BankUssd.ScreenKind.FINAL
                // What the user confirmed rides along: after their one answer the guide enters the number, the amount and
                // the reference itself instead of asking for each again (first live walk, 2 October 2026).
                TelcoSession.opened(TelcoUssd.Flow(TelcoUssd.NAV, root, emptyList(), tx.intent.spoken,
                    com.tekyerema.agent.NumberSpeech.networkName(sim.provider ?: "the network"),
                    number = tx.recipient, amount = tx.amount,
                    reference = if (intent == TxIntent.SEND_MONEY) tx.reference ?: "Payment" else null, name = contact,
                    intent = intent.name, sim = sim.provider),
                    screenText, kind)
                TelcoSession.screen(screenText, kind, auto = false, more = false, user = true)
                // The brain's question (a payment confirmation, a loan, a switch of wallet) is answered against this screen.
                out.pending?.takeIf { now != null }?.let { TelcoSession.ask(it) }
                ToolResult(true, out.ask, data = JSONObject().put("ask", true), channel = "native")
            }
            is Navigator.Outcome.Failed -> ToolResult(false, out.line, recoverable = false)
        }
    }

    private val after = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    @Volatile private var reader: kotlinx.coroutines.Job? = null

    /**
     * After the PIN hand-off of a balance check: wait for the network's ANSWER — the first phone dialog with no entry
     * box — and say it. While the dialog still has an entry box (the PIN prompt, or a "wrong PIN, try again") nothing
     * about it is kept, logged or spoken; what is typed into it never reaches a snapshot at all (TelephonyWindows). Gives
     * up when the dialog has been gone for a while (cancelled) or after the secure step's own limit.
     */
    private fun readAfterPin(ctrl: com.tekyerema.accessibility.AccessibilityController, network: String?) {
        reader?.cancel()
        reader = after.launch {
            val deadline = android.os.SystemClock.uptimeMillis() + com.tekyerema.voice.SecureStep.MAX_MS
            var gone = 0
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                kotlinx.coroutines.delay(900)
                val s = try { UssdDialog.read(ctrl) } catch (_: Throwable) { null }
                if (s == null) { if (++gone > 25) return@launch; continue }      // no dialog for ~22 s: closed or cancelled
                gone = 0
                if (s.hasInput || s.masked) continue                              // still asking for something: not ours to read
                val who = network?.let { com.tekyerema.agent.NumberSpeech.networkName(it) } ?: "The network"
                Log.i(TAG, "[NAV] the network's answer after the PIN is read out (${s.text.length} chars)")
                com.tekyerema.voice.VoiceEngine.announce(com.tekyerema.money.UssdSpeech.forEar(s.text, intro = "$who says"))
                // The answer has been taken; close the dialog so the user isn't left to find its OK button.
                try { s.cancelId?.let { ctrl.clickElement(it) } } catch (_: Throwable) {}
                return@launch
            }
        }
    }

    private fun nothing(intent: TxIntent): String = when (intent) {
        TxIntent.SEND_MONEY -> "Nothing was sent."
        TxIntent.CHECK_BALANCE -> "Nothing was changed."
        else -> "Nothing was bought."
    }

    private fun timedOut(args: JSONObject, line: String): ToolResult {
        com.tekyerema.money.MoMoRetry.remember(args.str("number").orEmpty(), args.str("amount").orEmpty(), args.str("reference"))
        return ToolResult(false, line, recoverable = false)
    }

    override val verifiesOutcome = true
    override suspend fun verify(args: JSONObject, ctx: ToolContext, before: com.tekyerema.accessibility.UiSnapshot?,
                                after: com.tekyerema.accessibility.UiSnapshot?, result: ToolResult): com.tekyerema.verifier.VerificationResult =
        com.tekyerema.verifier.VerificationResult(result.success,
            if (result.success) "the network's screens were read and matched" else "the walk stopped honestly")

    /** "Prince Melanin, on 0 5 5, 2 0 9, 3 6 9 0, an MTN number" / the number alone — and the "check it" warning. */
    private fun whoWords(args: JSONObject): Pair<String, String?>? {
        val raw = args.str("number")?.takeIf { n -> n.any { it.isDigit() } }
        val name = args.str(ARG_CONTACT)?.trim()?.takeIf { it.isNotEmpty() }
        if (raw == null) return name?.let { it to null }
        val (to, netWarn) = com.tekyerema.agent.NumberSpeech.moneyReadBack(raw, args.str(MoMoSendTool.ARG_SIM))
        return (name?.let { "$it, on $to" } ?: to) to netWarn
    }

    /**
     * The read-back's slots for the Twi template (voice/ConfirmTemplates) — a send or airtime with NO warning attached.
     * In a Twi conversation the A20e showed this read-back in English (3 October 2026): the translator won't take a line
     * full of digits. Any warning (another network, a number you didn't say, a repeat, a first-time number, a call in
     * progress, a reference) → null, and the full English sentence is read instead of a Twi one that would drop it.
     */
    fun twiSpec(args: JSONObject): com.tekyerema.voice.ConfirmTemplates.Spec? {
        val intent = intentOf(args)
        val amount = args.str("amount")
        if (intent == TxIntent.BUY_AIRTIME)
            return com.tekyerema.voice.ConfirmTemplates.Spec(com.tekyerema.voice.ConfirmTemplates.Spec.Kind.BUY_AIRTIME, amount = amount)
        if (intent != TxIntent.SEND_MONEY) return null
        val number = args.str("number")?.takeIf { n -> n.any { it.isDigit() } } ?: return null
        if (com.tekyerema.agent.NumberSpeech.moneyReadBack(number, args.str(MoMoSendTool.ARG_SIM)).second != null) return null
        if (args.optBoolean(ARG_UNSAID) || args.str(ARG_REPEAT) != null || MoMoSendTool.firstTimeWarning(args) != null ||
            args.optBoolean(MoMoSendTool.ARG_ON_CALL) || !args.str("reference").isNullOrBlank()) return null
        return com.tekyerema.voice.ConfirmTemplates.Spec(com.tekyerema.voice.ConfirmTemplates.Spec.Kind.SEND_MONEY,
            name = args.str(ARG_CONTACT)?.trim()?.takeIf { it.isNotEmpty() }, number = number, amount = amount)
    }

    override fun confirmationPrompt(args: JSONObject): String {
        val intent = intentOf(args)
        if (intent == TxIntent.CHECK_BALANCE) return BALANCE_PROMPT
        val amt = args.str("amount")?.let { GhanaMobileMoney.cedisPhrase(it) } ?: "money"
        val who = whoWords(args)
        val call = com.tekyerema.tools.MoneyPrivacy.callWarning(args.optBoolean(MoMoSendTool.ARG_ON_CALL))
        val tail = "I'll go through the menu quietly and stop at the PIN for you to enter."
        if (intent != TxIntent.SEND_MONEY) {
            val whom = if (intent == TxIntent.BUY_AIRTIME) "this phone" else who?.first ?: "another number"
            val warn = listOfNotNull(UNSAID_LINE.takeIf { args.optBoolean(ARG_UNSAID) }, who?.second.takeIf { intent == TxIntent.SEND_AIRTIME }, call)
                .joinToString(" ").ifEmpty { null }
            return "Buy $amt of airtime for $whom, from your Mobile Money wallet?\n" + (warn?.let { "$it " } ?: "") + tail
        }
        // The reference goes out with the money, so it is read back with it (A20e audit 2026-10-01, F09).
        val ref = GhanaMobileMoney.referencePhrase(args.str("reference"))
        who ?: return "Send $amt with Mobile Money$ref? I'll go through the menu and stop at the PIN for you to enter."
        val warn = listOfNotNull(UNSAID_LINE.takeIf { args.optBoolean(ARG_UNSAID) }, who.second, args.str(ARG_REPEAT),
            MoMoSendTool.firstTimeWarning(args), call).joinToString(" ").ifEmpty { null }
        return "Send $amt to ${who.first}, with Mobile Money$ref?\n" + (warn?.let { "$it " } ?: "") + tail
    }
}
