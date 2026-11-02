package com.tekyerema.money

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.tekyerema.agent.NumberSpeech
import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.RiskTier
import com.tekyerema.tools.Tool
import com.tekyerema.tools.ToolContext
import com.tekyerema.tools.ToolResult
import com.tekyerema.tools.str
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * The guided bank menu's hands ([BankGuide] decides every call; no model does — the tool is [hiddenFromModel], so it is
 * in no planner schema and no find_tool result). Two actions, in arguments only the guide sets:
 *  - `open`: dial a VERIFIED bank code from [BankUssd] (never a code from the request) and read the first screen. TIER3 —
 *    the user hears "Open Stanbic Bank's USSD menu, star 7 1 5 hash? It's guided…" and says yes first.
 *  - `reply`: type exactly what the user just said into the open bank dialog and press its Send, then read the next
 *    screen. Only inside a bank menu the user opened and confirmed ([BankSession.active]); never into a PIN or payment
 *    confirmation screen (those end the task and are the user's — [BankGuide.end]). A menu choice runs at once (the user
 *    just said it; waiting would time the session out); an amount or account number (3+ digits) is read back for a yes
 *    first (TIER2, [argTier]) — the recogniser mishears digits, and the bank's menu can't be taken back.
 * A PIN is never typed, read or logged: the entry box's text never reaches a snapshot (TelephonyWindows), and a masked or
 * PIN-worded screen is handed over.
 */
object BankUssdTool : Tool {
    const val NAME = "bank_ussd"
    const val ARG_ACTION = "_action"
    const val ARG_BANK = "_bank"
    const val ARG_TEXT = "_text"
    const val ARG_SCREEN = "_screen"
    const val OPEN = "open"
    const val REPLY = "reply"
    private const val TAG = "Tekyerema"

    override val name = NAME
    override val description = "Guided bank USSD menu (used by the on-phone bank guide only)."
    override val scope = CapabilityScope.COMMS_SEND
    /** Raised per call by [argTier]: opening is TIER3, typing a long number TIER2, a menu choice stays here. */
    override val riskTier = RiskTier.TIER1
    override val isAccelerator = true
    override val params = emptyList<com.tekyerema.tools.ToolParam>()
    /** Only BankGuide calls it: no model is ever offered a bank menu (and the planner's schema doesn't grow). */
    override val hiddenFromModel = true

    /** The tool itself read the bank's NEXT screen (the dialog changed after Send) — that is the outcome, so a step that
     *  reached it is verified; without this the engine's no-progress guard asked "should I keep trying?" mid-menu. */
    override val verifiesOutcome = true

    override suspend fun verify(args: JSONObject, ctx: ToolContext, before: com.tekyerema.accessibility.UiSnapshot?,
                                after: com.tekyerema.accessibility.UiSnapshot?, result: ToolResult): com.tekyerema.verifier.VerificationResult =
        com.tekyerema.verifier.VerificationResult(result.success && result.data?.has("kind") == true,
            if (result.success) "the bank's next screen was read" else "the bank's menu didn't answer")

    /** PolicyConfig.argTier: the tier this call's arguments imply (only ever raises). Pure. */
    fun argTier(args: JSONObject): RiskTier? {
        if (args.optString(ARG_ACTION) != REPLY) return RiskTier.TIER3
        val digits = args.optString(ARG_TEXT).count { it.isDigit() }
        return if (digits >= 3) RiskTier.TIER2 else null
    }

    override suspend fun preflight(args: JSONObject, ctx: ToolContext): ToolResult? {
        return when (args.str(ARG_ACTION)) {
            OPEN -> if (BankUssd.byKey(args.str(ARG_BANK)) == null)
                ToolResult(false, "I only open bank codes I've checked. ${BankUssd.supportedLine()}", recoverable = false) else null
            REPLY -> if (!BankSession.active()) ToolResult(false, "The bank's menu isn't open any more. Nothing was sent.", recoverable = false)
                else if (args.str(ARG_TEXT).isNullOrBlank()) ToolResult(false, "There's nothing to enter.", recoverable = false) else null
            else -> ToolResult(false, "This is only for the guided bank menu.", recoverable = false)
        }
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val ctrl = ctx.controller ?: return ToolResult(false, "The accessibility service isn't running, so I can't read the bank's menu.", recoverable = false)
        return when (args.str(ARG_ACTION)) {
            OPEN -> open(args, ctx, ctrl)
            REPLY -> reply(args, ctx, ctrl)
            else -> ToolResult(false, "This is only for the guided bank menu.", recoverable = false)
        }
    }

    private suspend fun open(args: JSONObject, ctx: ToolContext, ctrl: com.tekyerema.accessibility.AccessibilityController): ToolResult {
        val bank = BankUssd.byKey(args.str(ARG_BANK)) ?: return ToolResult(false, "I only open bank codes I've checked.", recoverable = false)
        if (ctx.appContext.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
            return ToolResult(false, "Nexus isn't allowed to make calls, so I can't open ${bank.name}'s menu. Allow Phone for Nexus in Settings.", recoverable = false)
        BankSession.end()
        try {
            ctx.appContext.startActivity(com.tekyerema.phone.SimChoice.attach(ctx.appContext, Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(bank.code))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
        } catch (t: Throwable) {
            return ToolResult(false, "I couldn't open ${bank.name}'s menu.", recoverable = false)
        }
        Log.i(TAG, "[BANK] opened ${bank.code} (${bank.key}) — guided")
        val first = UssdDialog.awaitNext(ctrl, "", 25_000L)
            ?: return ToolResult(false, "${bank.name}'s menu didn't open in time. Nothing was sent.", recoverable = false)
        return screenResult(bank, first, opened = true, speakBalance = speakBalance(ctx))
    }

    private suspend fun reply(args: JSONObject, ctx: ToolContext, ctrl: com.tekyerema.accessibility.AccessibilityController): ToolResult {
        val session = BankSession.current() ?: return ToolResult(false, "The bank's menu isn't open any more. Nothing was sent.", recoverable = false)
        val value = args.str(ARG_TEXT)?.trim().orEmpty()
        val now = UssdDialog.awaitCurrent(ctrl)
            ?: return ToolResult(false, "${session.bank.name}'s menu has closed — it may have timed out. Nothing was sent.", recoverable = false)
        // Never type into a PIN or a payment confirmation (the guide ends there; this is the second lock).
        val kind = BankUssd.kind(now.text, now.hasInput, now.masked)
        if (kind != BankUssd.ScreenKind.MENU) {
            BankSession.screen(now.text, kind, speakBalance(ctx))
            if (kind != BankUssd.ScreenKind.FINAL) handOver()
            return ToolResult(true, "${session.bank.name}'s screen needs you now.", data = JSONObject().put("kind", kind.name.lowercase()), channel = "native")
        }
        Log.i(TAG, "[BANK] reply (${value.length} chars)")   // never the words: they can be an account number
        if (!UssdDialog.answer(ctrl, now, value))
            return ToolResult(false, "I couldn't type into ${session.bank.name}'s menu. Nothing was sent.", recoverable = false)
        delay(400)
        val next = UssdDialog.awaitNext(ctrl, now.text, 18_000L)
            ?: return ToolResult(false, "${session.bank.name}'s menu didn't answer in time, so it may have closed. Nothing is sent without your PIN.",
                recoverable = false)
        return screenResult(session.bank, next, opened = false, speakBalance = speakBalance(ctx))
    }

    /** A balance on a bank screen is read aloud only with earphones / Bluetooth in, or when the user switched balances on. */
    private fun speakBalance(ctx: ToolContext): Boolean = try {
        com.tekyerema.tools.MoneyPrivacy.privateAudio(ctx.appContext) || com.tekyerema.settings.Settings(ctx.appContext).alertMoneyBalance
    } catch (_: Throwable) { false }

    private fun screenResult(bank: BankUssd.Bank, s: UssdDialog.Screen, opened: Boolean, speakBalance: Boolean): ToolResult {
        val kind = BankUssd.kind(s.text, s.hasInput, s.masked)
        if (opened) BankSession.opened(bank, s.text, kind, speakBalance) else BankSession.screen(s.text, kind, speakBalance)
        Log.i(TAG, "[BANK] screen: ${kind.name.lowercase()} ${com.tekyerema.core.Privacy.content(s.text.take(120).replace("\n", " | "))}")
        if (kind != BankUssd.ScreenKind.MENU && kind != BankUssd.ScreenKind.FINAL) handOver()
        val data = JSONObject().put("kind", kind.name.lowercase())
        // The planner-facing words are short; the whole screen is in BankSession for the guide (history lines are cut).
        return ToolResult(true, "${bank.name}: ${BankUssd.spokenScreen(s.text, speakBalance = false).take(120)}", data = data, channel = "native")
    }

    /** A payment confirmation or a PIN: no self-opened microphone while the user finishes it, no helper at the PIN. */
    private fun handOver() {
        com.tekyerema.voice.SecureStep.begin()
        com.tekyerema.agent.helper.HelperGate.atPin()
    }

    override fun confirmationPrompt(args: JSONObject): String {
        val bank = BankUssd.byKey(args.optString(ARG_BANK))
        val name = bank?.name ?: "the bank"
        if (args.optString(ARG_ACTION) != REPLY)
            return "Open $name's USSD menu" + (bank?.let { ", ${BankUssd.spokenCode(it.code)}" } ?: "") + "? ${BankUssd.GUIDED}"
        val text = args.optString(ARG_TEXT)
        val screen = args.optString(ARG_SCREEN).takeIf { it.isNotBlank() }
        return (screen?.let { "$name's screen says “$it”. " } ?: "") + "Enter ${spokenEntry(text, screen)} and send it?"
    }

    /** An amount as a figure ("200"); anything else with its digits apart, grouped ("0 1 2, 3 4 5, 6 7 8 9"). Pure. */
    fun spokenEntry(text: String, screen: String?): String {
        val digits = text.filter { it.isDigit() }
        if (digits.isEmpty()) return "“$text”"
        if (screen != null && Regex("(?i)\\bamount\\b|\\bcedis?\\b|\\bghs\\b|₵").containsMatchIn(screen)) return text
        return NumberSpeech.spoken(digits)
    }
}
