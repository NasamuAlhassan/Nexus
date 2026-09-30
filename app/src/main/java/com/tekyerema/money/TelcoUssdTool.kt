package com.tekyerema.money

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.RiskTier
import com.tekyerema.tools.Tool
import com.tekyerema.tools.ToolContext
import com.tekyerema.tools.ToolResult
import com.tekyerema.tools.str
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * The guided network menu the user is walking through ([TelcoUssdTool] writes it; [TelcoGuide] reads it) — the telco
 * twin of [BankSession]. In memory only.
 */
object TelcoSession {
    data class View(val flow: TelcoUssd.Flow, val screen: String, val kind: BankUssd.ScreenKind, val openedAt: Long, val at: Long,
                    /** How many auto-steps have been taken (index into flow.auto; a lookahead match jumps it forward). */
                    val stepDone: Int = 0,
                    /** "More" presses spent looking for the CURRENT step's row (bounded — a loop must end as guided). */
                    val morePressed: Int = 0,
                    /** The USER answered a screen by voice: from then on the walk stays guided — the autopilot never
                     *  takes the wheel back (live A20e 2026-09-30: it resumed a stale step and looped into More). */
                    val userDrove: Boolean = false,
                    /** The number the user just dictated (7+ digits) — offered back with one yes when the network asks
                     *  to CONFIRM it, instead of making them say all ten digits twice (live A20e 2026-09-30). */
                    val lastNumber: String? = null)

    const val MAX_MS = 10 * 60_000L
    @Volatile private var view: View? = null

    fun opened(flow: TelcoUssd.Flow, screen: String, kind: BankUssd.ScreenKind, now: Long = System.currentTimeMillis()) {
        view = View(flow, screen, kind, now, now)
    }

    fun screen(screen: String, kind: BankUssd.ScreenKind, auto: Boolean, more: Boolean, stepIdx: Int = -1,
               user: Boolean = false, typed: String? = null, now: Long = System.currentTimeMillis()) {
        view = view?.let {
            it.copy(screen = screen, kind = kind, at = now,
                // A matched step (possibly a LATER one than expected — menus drift) moves the walk past itself.
                stepDone = if (auto && !more && stepIdx >= 0) maxOf(it.stepDone, stepIdx + 1) else it.stepDone,
                morePressed = if (more) it.morePressed + 1 else 0,
                userDrove = it.userDrove || user,
                lastNumber = if (user && typed != null && typed.count(Char::isDigit) >= 7) typed.filter(Char::isDigit)
                    else it.lastNumber)
        }
    }

    fun end() { view = null }
    fun current(now: Long = System.currentTimeMillis()): View? = view?.takeIf { now - it.openedAt in 0..MAX_MS }
    fun active(now: Long = System.currentTimeMillis()): Boolean = current(now) != null
}

/**
 * The guided network menu's hands ([TelcoGuide] decides every call; no model does — [hiddenFromModel]). The telco twin
 * of [BankUssdTool], sharing [UssdDialog] (which only ever reads the PHONE app's own window) and [BankUssd]'s screen
 * kinds: a masked or PIN screen and a payment confirmation are handed to the user, never typed into. Two actions:
 *  - `open`: dial a code from [TelcoUssd]'s checked table (never one from the request) and read the first screen. TIER3 —
 *    "Open MTN's data bundles, star 1 3 8 hash? It's guided…" is confirmed first.
 *  - `reply`: press ONE option. `_auto` replies carry the row's own on-screen words in `_row` — the row was matched by
 *    [TelcoGuide] against the live screen (the researched step is a plan, not a truth). A user's answer is typed as
 *    said, an amount or number read back first ([argTier], like the bank).
 */
object TelcoUssdTool : Tool {
    const val NAME = "telco_ussd"
    const val ARG_ACTION = "_action"
    const val ARG_FLOW = "_flow"
    const val ARG_CODE = "_code"
    const val ARG_WHAT = "_what"
    const val ARG_NET = "_net"
    const val ARG_TEXT = "_text"
    const val ARG_AUTO = "_auto"
    const val ARG_MORE = "_more"
    const val ARG_ROW = "_row"
    const val ARG_STEP = "_step"
    const val ARG_SCREEN = "_screen"
    const val OPEN = "open"
    const val REPLY = "reply"
    private const val TAG = "Tekyerema"

    override val name = NAME
    override val description = "Guided network USSD menu — data bundles, offers (used by the on-phone telco guide only)."
    override val scope = CapabilityScope.COMMS_SEND
    override val riskTier = RiskTier.TIER1
    override val isAccelerator = true
    override val params = emptyList<com.tekyerema.tools.ToolParam>()
    override val hiddenFromModel = true
    override val verifiesOutcome = true

    override suspend fun verify(args: JSONObject, ctx: ToolContext, before: com.tekyerema.accessibility.UiSnapshot?,
                                after: com.tekyerema.accessibility.UiSnapshot?, result: ToolResult): com.tekyerema.verifier.VerificationResult =
        com.tekyerema.verifier.VerificationResult(result.success && result.data?.has("kind") == true,
            if (result.success) "the network's next screen was read" else "the network's menu didn't answer")

    /** Opening is TIER3 (one clear yes); an auto menu press or a short answer is TIER1; 3+ typed digits (an amount,
     *  a number) are TIER2 — read back first, like the bank's. Only ever raises. */
    fun argTier(args: JSONObject): RiskTier? {
        if (args.optString(ARG_ACTION) != REPLY) return RiskTier.TIER3
        if (args.optBoolean(ARG_AUTO)) return null
        return if (args.optString(ARG_TEXT).count { it.isDigit() } >= 3) RiskTier.TIER2 else null
    }

    override suspend fun preflight(args: JSONObject, ctx: ToolContext): ToolResult? = when (args.str(ARG_ACTION)) {
        OPEN -> if (args.str(ARG_CODE).isNullOrBlank()) ToolResult(false, "No checked menu code to open.", recoverable = false) else null
        REPLY -> if (!TelcoSession.active()) ToolResult(false, "The network's menu isn't open any more. Nothing was bought.", recoverable = false)
            else if (args.str(ARG_TEXT).isNullOrBlank()) ToolResult(false, "There's nothing to enter.", recoverable = false) else null
        else -> ToolResult(false, "This is only for the guided network menu.", recoverable = false)
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val ctrl = ctx.controller ?: return ToolResult(false, "The accessibility service isn't running, so I can't read the network's menu.", recoverable = false)
        return when (args.str(ARG_ACTION)) {
            OPEN -> open(args, ctx, ctrl)
            REPLY -> reply(args, ctrl)
            else -> ToolResult(false, "This is only for the guided network menu.", recoverable = false)
        }
    }

    private suspend fun open(args: JSONObject, ctx: ToolContext, ctrl: com.tekyerema.accessibility.AccessibilityController): ToolResult {
        val flow = TelcoGuide.flowOf(args) ?: return ToolResult(false, "I don't have a checked code for that.", recoverable = false)
        if (ctx.appContext.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
            return ToolResult(false, "Tɛkyerɛma isn't allowed to make calls, so I can't open ${flow.netName}'s menu. Allow Phone for Tɛkyerɛma in Settings.", recoverable = false)
        TelcoSession.end()
        try {
            ctx.appContext.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(flow.code))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            return ToolResult(false, "I couldn't open ${flow.netName}'s menu.", recoverable = false)
        }
        Log.i(TAG, "[TELCO] opened ${flow.code} (${flow.netName} ${flow.key}) — guided, ${flow.auto.size} auto-steps")
        val first = UssdDialog.awaitNext(ctrl, "", 25_000L)
            ?: return ToolResult(false, "${flow.netName}'s menu didn't open in time. Nothing was bought.", recoverable = false)
        val kind = BankUssd.kind(first.text, first.hasInput, first.masked)
        TelcoSession.opened(flow, first.text, kind)
        Log.i(TAG, "[TELCO] screen: ${kind.name.lowercase()} ${com.tekyerema.core.Privacy.content(first.text.take(120).replace("\n", " | "))}")
        if (kind != BankUssd.ScreenKind.MENU && kind != BankUssd.ScreenKind.FINAL) handOver()
        return ToolResult(true, "${flow.netName}: ${BankUssd.spokenScreen(first.text, speakBalance = false).take(120)}",
            data = JSONObject().put("kind", kind.name.lowercase()), channel = "native")
    }

    private suspend fun reply(args: JSONObject, ctrl: com.tekyerema.accessibility.AccessibilityController): ToolResult {
        val session = TelcoSession.current() ?: return ToolResult(false, "The network's menu isn't open any more. Nothing was bought.", recoverable = false)
        val value = args.str(ARG_TEXT)?.trim().orEmpty()
        val auto = args.optBoolean(ARG_AUTO)
        val more = args.optBoolean(ARG_MORE)
        val now = UssdDialog.awaitCurrent(ctrl)
            ?: return ToolResult(false, "${session.flow.netName}'s menu has closed — it may have timed out. Nothing was bought.", recoverable = false)
        // Never type into a PIN or a payment confirmation (the guide ends there; this is the second lock). And an AUTO
        // press only lands on the screen its row was matched on — a menu that moved on is read afresh, never pressed blind.
        val kindNow = BankUssd.kind(now.text, now.hasInput, now.masked)
        if (kindNow != BankUssd.ScreenKind.MENU || (auto && now.text != session.screen)) {
            TelcoSession.screen(now.text, kindNow, auto = true, more = false)   // a re-read, nobody pressed: not user-driven
            if (kindNow != BankUssd.ScreenKind.MENU && kindNow != BankUssd.ScreenKind.FINAL) handOver()
            return ToolResult(true, "${session.flow.netName}'s screen needs a look first.", data = JSONObject().put("kind", kindNow.name.lowercase()), channel = "native")
        }
        Log.i(TAG, if (auto) "[TELCO] auto '${value}' = ${com.tekyerema.core.Privacy.content(args.str(ARG_ROW) ?: "")}"
            else "[TELCO] reply (${value.length} chars)")
        if (!UssdDialog.answer(ctrl, now, value))
            return ToolResult(false, "I couldn't type into ${session.flow.netName}'s menu. Nothing was bought.", recoverable = false)
        delay(400)
        val next = UssdDialog.awaitNext(ctrl, now.text, 18_000L)
            ?: return ToolResult(false, "${session.flow.netName}'s menu didn't answer in time, so it may have closed. Nothing is paid without your PIN.",
                recoverable = false)
        val kind = BankUssd.kind(next.text, next.hasInput, next.masked)
        TelcoSession.screen(next.text, kind, auto = auto, more = more,
            stepIdx = args.optInt(ARG_STEP, -1), user = !auto, typed = value)
        Log.i(TAG, "[TELCO] screen: ${kind.name.lowercase()} ${com.tekyerema.core.Privacy.content(next.text.take(120).replace("\n", " | "))}")
        if (kind != BankUssd.ScreenKind.MENU && kind != BankUssd.ScreenKind.FINAL) handOver()
        return ToolResult(true, "${session.flow.netName}: ${BankUssd.spokenScreen(next.text, speakBalance = false).take(120)}",
            data = JSONObject().put("kind", kind.name.lowercase()), channel = "native")
    }

    /** A payment confirmation or a PIN: no self-opened microphone, no helper at the PIN. */
    private fun handOver() {
        com.tekyerema.voice.SecureStep.begin()
        com.tekyerema.agent.helper.HelperGate.atPin()
    }

    override fun confirmationPrompt(args: JSONObject): String {
        if (args.optString(ARG_ACTION) != REPLY) {
            val net = args.str(ARG_NET) ?: "the network"
            val what = args.str(ARG_WHAT) ?: "its menu"
            val code = args.str(ARG_CODE)?.let { ", ${TelcoUssd.spokenCode(it)}" } ?: ""
            return "Open $net's menu for $what$code? It's guided: I choose the obvious steps, read you anything else, " +
                "and you confirm any payment and enter your PIN yourself."
        }
        val screen = args.str(ARG_SCREEN)?.takeIf { it.isNotBlank() }
        return (screen?.let { "The screen says “$it”. " } ?: "") + "Enter ${BankUssdTool.spokenEntry(args.optString(ARG_TEXT), screen)} and send it?"
    }
}
