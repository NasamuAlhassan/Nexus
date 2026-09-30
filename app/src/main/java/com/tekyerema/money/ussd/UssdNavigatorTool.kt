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
import org.json.JSONObject

/**
 * The intent-driven USSD walk (owner's navigator brief, 2026-09-30): ONE up-front TIER3 read-back (recipient +
 * amount, with the money-safety warnings), then SILENT navigation by meaning — each live menu matched to the intent
 * (SemanticMatch: exact → keywords → the on-device embedding model; the small cloud model only when the phone is
 * unsure; the USER when nobody is) — slots answered from what was already confirmed, ONE consolidated line at the
 * network's own confirmation with its SHOWN NAME, and the PIN forever the user's (SecureStep). On any doubt the walk
 * hands over guided (TelcoSession seeds the guide from the CURRENT screen) — SAFE STOP beats a wrong press.
 *
 * `money.navigator.enabled=false` restores the fixed momo_send driver unchanged.
 */
object UssdNavigatorTool : Tool {
    const val NAME = "ussd_navigate"
    private const val TAG = "Tekyerema"

    override val name = NAME
    override val description =
        "Send money via the network's USSD menu: navigates silently by meaning, reads the network's confirmation (registered name) and stops at the PIN. Needs number + amount."
    override val scope = CapabilityScope.COMMS_SEND
    override val riskTier = RiskTier.TIER3
    override val isAccelerator = true
    /** The on-phone brain routes sends here deterministically; the cloud planner keeps momo_send — no schema growth,
     *  and no model ever drives the navigator directly (§19: the LLM is never the execution authority). */
    override val hiddenFromModel = true
    override val params = listOf(
        com.tekyerema.tools.ToolParam("number", "string", "Recipient phone number (digits)."),
        com.tekyerema.tools.ToolParam("amount", "string", "Cedi amount."),
        com.tekyerema.tools.ToolParam("reference", "string", "Payment reference.", required = false)
    )

    /** The same money preflights as the fixed driver: limits first, a malformed number asked again, the SIM's
     *  network detected (never assumed), first-time-number and on-call warnings for the read-back. */
    override suspend fun preflight(args: JSONObject, ctx: ToolContext): ToolResult? {
        com.tekyerema.money.MoneyLimits.preflight(args.str("amount"))?.let { (line, final) -> return ToolResult(false, line, recoverable = !final) }
        MoMoSendTool.numberProblem(args.str("number"))?.let { return ToolResult(false, it, recoverable = true) }
        args.put("number", com.tekyerema.agent.NumberSpeech.normalize(args.str("number")))
        com.tekyerema.money.MoneyLimits.parseAmount(args.str("amount"))?.let { args.put("amount", it.toPlainString()) }
            ?: return ToolResult(false, "I couldn't make out the amount \"${args.str("amount")}\" in cedis — ask the user to say it again.", recoverable = true)
        val sim = Provider.current(ctx.appContext)
        if (sim.provider == null)
            return ToolResult(false, "I couldn't tell which network this SIM is on, so I won't guess a Mobile Money code.", recoverable = true)
        args.put(MoMoSendTool.ARG_SIM, sim.provider)
        if (com.tekyerema.money.KnownRecipients.isKnown(args.str("number"))) args.remove(MoMoSendTool.ARG_NEW_RECIPIENT)
        else args.put(MoMoSendTool.ARG_NEW_RECIPIENT, true)
        if (com.tekyerema.tools.MoneyPrivacy.onCall(ctx.appContext)) args.put(MoMoSendTool.ARG_ON_CALL, true)
        return null
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        if (ctx.appContext.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
            return ToolResult(false, "CALL_PHONE permission isn't granted, so I can't open Mobile Money.")
        val ctrl = ctx.controller ?: return ToolResult(false, "The accessibility service isn't running, so I can't read the menu.")
        val root = Provider.momoRoot(ctx.appContext)
            ?: return ToolResult(false, "I couldn't tell which network this SIM is on, so I won't guess a code.", recoverable = true)
        val sim = Provider.current(ctx.appContext)
        val tx = TxContext(
            intent = TxIntent.SEND_MONEY,
            provider = sim.provider,
            recipient = args.str("number")?.filter { it.isDigit() },
            amount = args.str("amount"),
            reference = args.str("reference")?.trim()?.ifBlank { null },
            confirmedUpFront = true
        )
        try {
            ctx.appContext.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(root))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            return ToolResult(false, "Couldn't open Mobile Money: ${t.message}", recoverable = true)
        }
        Log.i(TAG, "[NAV] opened $root (${sim.provider}${if (sim.dualSim) ", dual-SIM: default voice slot ${sim.slot}" else ""}) intent=${tx.intent}")
        com.tekyerema.money.MoMoRetry.clear()
        val first = UssdDialog.awaitNext(ctrl, "", 25_000L)
            ?: return timedOut(args, "The menu didn't open in time. Nothing was sent.")
        val embedder: ((String) -> FloatArray)? =
            com.tekyerema.agent.IntentMatcher.get(ctx.appContext)?.let { m -> { s: String -> m.embed(s) } }
        val online = OnlineFallback(try { com.tekyerema.settings.Settings(ctx.appContext).groqApiKeys } catch (_: Throwable) { emptyList() })
        val nav = Navigator(ctrl, tx, embedder, online)
        return when (val out = nav.run(first)) {
            is Navigator.Outcome.Boundary -> {
                // The walk reached the authorization: today's total, the number as known, the receipt window.
                com.tekyerema.money.MoneyLimits.parseAmount(tx.amount)?.let { com.tekyerema.money.MoneyLimits.recordSend(it) }
                tx.recipient?.let { com.tekyerema.money.KnownRecipients.add(it) }
                com.tekyerema.money.MoMoAfterCare.handedOff(sim.provider, com.tekyerema.money.MoneyLimits.parseAmount(tx.amount))
                ToolResult(true, out.line, data = JSONObject().put("boundary", true), channel = "native")
            }
            is Navigator.Outcome.Final -> ToolResult(true, out.line, data = JSONObject().put("final", true), channel = "native")
            is Navigator.Outcome.NeedUser -> {
                // Guided from the CURRENT screen: the guide asks, the user chooses, the same dialog machinery types.
                val now = UssdDialog.awaitCurrent(ctrl, 2_000L)
                val screenText = now?.text ?: ""
                val kind = if (now != null) BankUssd.kind(now.text, now.hasInput, now.masked) else BankUssd.ScreenKind.FINAL
                TelcoSession.opened(TelcoUssd.Flow("nav", root, emptyList(), tx.intent.spoken, sim.provider ?: "the network"),
                    screenText, kind)
                TelcoSession.screen(screenText, kind, auto = false, more = false, user = true)
                ToolResult(true, out.ask, data = JSONObject().put("ask", true), channel = "native")
            }
            is Navigator.Outcome.Failed -> ToolResult(false, out.line, recoverable = false)
        }
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

    override fun confirmationPrompt(args: JSONObject): String {
        val amt = args.str("amount")?.let { "$it cedis" } ?: "money"
        val raw = args.str("number")?.takeIf { n -> n.any { it.isDigit() } }
            ?: return "Send $amt with Mobile Money? I'll go through the menu and stop at the PIN for you to enter."
        val (to, netWarn) = com.tekyerema.agent.NumberSpeech.moneyReadBack(raw, args.str(MoMoSendTool.ARG_SIM))
        val warn = listOfNotNull(netWarn, MoMoSendTool.firstTimeWarning(args),
            com.tekyerema.tools.MoneyPrivacy.callWarning(args.optBoolean(MoMoSendTool.ARG_ON_CALL)))
            .joinToString(" ").ifEmpty { null }
        return "Send $amt to $to, with Mobile Money?\n" + (warn?.let { "$it " } ?: "") +
            "I'll go through the menu quietly and stop at the PIN for you to enter."
    }
}
