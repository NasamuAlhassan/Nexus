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
                    val lastNumber: String? = null,
                    /** The menu brain's question on THIS screen, waiting for the user's answer (cleared by the next screen). */
                    val pending: com.tekyerema.money.ussd.MenuBrain.Move.Ask? = null)

    const val MAX_MS = 10 * 60_000L
    @Volatile private var view: View? = null

    fun opened(flow: TelcoUssd.Flow, screen: String, kind: BankUssd.ScreenKind, now: Long = System.currentTimeMillis()) {
        view = View(flow, screen, kind, now, now)
    }

    /** The brain asked the user something about the current screen. */
    fun ask(q: com.tekyerema.money.ussd.MenuBrain.Move.Ask?) { view = view?.copy(pending = q) }

    /** The user's answer taught the walk a price or a preference — later screens are chosen with it. */
    fun learn(amount: String? = null, prefer: com.tekyerema.money.ussd.MenuBrain.Prefer? = null) {
        view = view?.let { v -> v.copy(flow = v.flow.copy(amount = amount ?: v.flow.amount, prefer = prefer?.name ?: v.flow.prefer)) }
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
                    else it.lastNumber,
                pending = null)
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
    /** What the user's own request said, for the ONE read-back at the open: the amount, and who the bundle is for (a
     *  number, or a contact's name — the same key the engine's "which one?" writes its pick to). */
    const val ARG_AMOUNT = "_amount"
    const val ARG_NUMBER = "number"
    const val ARG_CONTACT = "contact"
    const val ARG_PAY = "_pay"
    private const val FLOW_BALANCE = "data_balance"
    const val OPEN = "open"
    const val REPLY = "reply"
    /** Close the network's dialog at the user's "no" / "cancel" (nothing is typed; closing never pays). */
    const val CLOSE = "close"
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
        // A close reads no next screen: it is verified by the dialog being gone (its own result says which) — the
        // "next screen" check marked every close NOT verified and sent the engine into a WRONG_PAGE → backtrack →
        // "relaunch + replay" recovery a second after the menu had closed at the user's word (QA, 3 October 2026).
        if (args.optString(ARG_ACTION) == CLOSE)
            com.tekyerema.verifier.VerificationResult(result.success && result.data?.optBoolean("gone", true) != false,
                if (result.success) "the network's menu is closed" else "the network's menu couldn't be closed")
        else com.tekyerema.verifier.VerificationResult(result.success && result.data?.has("kind") == true,
            if (result.success) "the network's next screen was read" else "the network's menu didn't answer")

    /** Opening is TIER3 (one clear yes); an auto menu press or a short answer is TIER1; 3+ typed digits (an amount,
     *  a number) are TIER2 — read back first, like the bank's. Only ever raises. */
    fun argTier(args: JSONObject): RiskTier? {
        if (args.optString(ARG_ACTION) == CLOSE) return null
        // Opening the data-BALANCE menu is not confirmed: it is what was asked for, and it buys nothing.
        if (args.optString(ARG_ACTION) != REPLY) return if (args.optString(ARG_FLOW) == FLOW_BALANCE) null else RiskTier.TIER3
        if (args.optBoolean(ARG_AUTO)) return null
        return if (args.optString(ARG_TEXT).count { it.isDigit() } >= 3) RiskTier.TIER2 else null
    }

    override suspend fun preflight(args: JSONObject, ctx: ToolContext): ToolResult? = when (args.str(ARG_ACTION)) {
        OPEN -> if (args.str(ARG_CODE).isNullOrBlank()) ToolResult(false, "No checked menu code to open.", recoverable = false)
            else beneficiary(args, { com.tekyerema.tools.ContactChoice.resolve(ctx.appContext, it) },
                { said, matches -> com.tekyerema.tools.ContactChoice.result(ctx.appContext, said, matches) })
        REPLY -> if (!TelcoSession.active()) ToolResult(false, "The network's menu isn't open any more. Nothing was bought.", recoverable = false)
            else if (args.str(ARG_TEXT).isNullOrBlank()) ToolResult(false, "There's nothing to enter.", recoverable = false) else null
        CLOSE -> null
        else -> ToolResult(false, "This is only for the guided network menu.", recoverable = false)
    }

    /**
     * A bundle for a NAMED person: the name is looked up before the read-back, exactly as a send does it — one match → its
     * number (read back with the name); several → the engine's "which one?"; none or a pronoun → said so. Null = fine
     * (no name, or a number is already there). Never falls back to the user's own phone. Internal: unit-tested.
     */
    internal fun beneficiary(args: JSONObject, resolve: (String) -> List<com.tekyerema.android.ContactResolver.Contact>,
                             several: (String, List<com.tekyerema.android.ContactResolver.Contact>) -> ToolResult): ToolResult? {
        val contact = args.str(ARG_CONTACT)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (args.str(ARG_NUMBER).isNullOrBlank()) {
            if (com.tekyerema.android.ContactResolver.isPronoun(contact))
                return ToolResult(false, "I don't know who “$contact” is — no one was named. Say it again with the name, or the phone number.", recoverable = false)
            val matches = resolve(contact)
            when {
                matches.isEmpty() -> return ToolResult(false, "I couldn't find “$contact” in your contacts, so nothing was bought. " +
                    "Say it again with the name as it's saved, or with the phone number.", recoverable = false)
                matches.size > 1 -> return several(com.tekyerema.tools.ContactChoice.narrow(contact).first, matches)
            }
            args.put(ARG_NUMBER, matches[0].number).put(ARG_CONTACT, matches[0].name.trim())
        }
        val n = com.tekyerema.agent.NumberSpeech.normalize(args.str(ARG_NUMBER))
        if (!(n.length == 10 && n.startsWith("0")))
            return ToolResult(false, "$contact's saved number isn't a ten-digit Ghana mobile number, so I can't buy a bundle for it. Nothing was bought.", recoverable = false)
        args.put(ARG_NUMBER, n)
        return null
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val ctrl = ctx.controller ?: return ToolResult(false, "The accessibility service isn't running, so I can't read the network's menu.", recoverable = false)
        return when (args.str(ARG_ACTION)) {
            OPEN -> open(args, ctx, ctrl)
            REPLY -> reply(args, ctrl, ctx.appContext)
            CLOSE -> close(ctrl)
            else -> ToolResult(false, "This is only for the guided network menu.", recoverable = false)
        }
    }

    private suspend fun open(args: JSONObject, ctx: ToolContext, ctrl: com.tekyerema.accessibility.AccessibilityController): ToolResult {
        val flow = TelcoGuide.flowOf(args) ?: return ToolResult(false, "I don't have a checked code for that.", recoverable = false)
        if (ctx.appContext.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
            return ToolResult(false, "Tɛkyerɛma isn't allowed to make calls, so I can't open ${flow.netName}'s menu. Allow Phone for Tɛkyerɛma in Settings.", recoverable = false)
        TelcoSession.end()
        Log.i(TAG, "[TELCO] opened ${flow.code} (${flow.netName} ${flow.key}) — guided, ${flow.auto.size} auto-steps")
        val first = try { UssdDialog.dial(ctx.appContext, ctrl, flow.code) } catch (t: Throwable) {
            return ToolResult(false, "I couldn't open ${flow.netName}'s menu.", recoverable = false)
        } ?: return ToolResult(false, "${flow.netName}'s menu didn't open in time. Nothing was bought.", recoverable = false)
        val kind = BankUssd.kind(first.text, first.hasInput, first.masked)
        TelcoSession.opened(flow, first.text, kind)
        Log.i(TAG, "[TELCO] screen: ${kind.name.lowercase()} ${com.tekyerema.core.Privacy.content(first.text.take(120).replace("\n", " | "))}")
        if (kind != BankUssd.ScreenKind.MENU && kind != BankUssd.ScreenKind.FINAL) handOver(kind, ctrl, ctx.appContext, flow.netName)
        if (kind == BankUssd.ScreenKind.FINAL) UssdDialog.close(ctrl, first)
        return ToolResult(true, "${flow.netName}: ${BankUssd.spokenScreen(first.text, speakBalance = false).take(120)}",
            data = JSONObject().put("kind", kind.name.lowercase()), channel = "native")
    }

    /** "No" / "cancel" to the network's menu: Tɛkyerɛma closes it itself — a blind user isn't sent to find Cancel. */
    private suspend fun close(ctrl: com.tekyerema.accessibility.AccessibilityController): ToolResult {
        val net = TelcoSession.current()?.flow?.netName ?: "the network"
        val what = if (TelcoSession.current()?.flow?.what?.contains("send money") == true) "Nothing was sent." else "Nothing was bought."
        TelcoSession.end()
        val now = UssdDialog.awaitCurrent(ctrl, 1_500L)
            ?: return ToolResult(true, "Okay — $net's menu is closed. $what", data = JSONObject().put("gone", true), channel = "native")
        val closed = UssdDialog.close(ctrl, now)
        Log.i(TAG, "[TELCO] closed the menu at the user's word (gone=$closed)")
        return ToolResult(true, if (closed) "Okay — I've closed $net's menu. $what" else "Okay — I've stopped. Press Cancel on $net's screen to close it. $what",
            data = JSONObject().put("gone", closed), channel = "native")
    }

    private suspend fun reply(args: JSONObject, ctrl: com.tekyerema.accessibility.AccessibilityController,
                              app: android.content.Context? = null): ToolResult {
        val session = TelcoSession.current() ?: return ToolResult(false, "The network's menu isn't open any more. Nothing was bought.", recoverable = false)
        val value = args.str(ARG_TEXT)?.trim().orEmpty()
        val auto = args.optBoolean(ARG_AUTO)
        val more = args.optBoolean(ARG_MORE)
        val now = UssdDialog.awaitCurrent(ctrl)
            ?: return ToolResult(false, "${session.flow.netName}'s menu has closed — it may have timed out. Nothing was bought.", recoverable = false)
        // Never type into a PIN or a payment confirmation (the guide ends there; this is the second lock). And an AUTO
        // press only lands on the screen its row was matched on — a menu that moved on is read afresh, never pressed blind.
        // The one exception: the confirm key of a payment confirmation the USER just said yes to, by voice, on this very
        // screen ("MTN shows 20 pesewas of airtime for … — shall I confirm it?"); the PIN still comes after, and is theirs.
        val kindNow = BankUssd.kind(now.text, now.hasInput, now.masked)
        val saidYes = !auto && kindNow == BankUssd.ScreenKind.MONEY && now.text == session.screen &&
            session.pending?.kind == com.tekyerema.money.ussd.MenuBrain.Kind.CONFIRM && session.pending.options["yes"] == value
        if ((kindNow != BankUssd.ScreenKind.MENU && !saidYes) || (auto && now.text != session.screen)) {
            TelcoSession.screen(now.text, kindNow, auto = true, more = false)   // a re-read, nobody pressed: not user-driven
            if (kindNow != BankUssd.ScreenKind.MENU && kindNow != BankUssd.ScreenKind.FINAL) handOver(kindNow, ctrl, app, session.flow.netName)
            return ToolResult(true, "${session.flow.netName}'s screen needs a look first.", data = JSONObject().put("kind", kindNow.name.lowercase()), channel = "native")
        }
        Log.i(TAG, if (auto && args.str(ARG_ROW) == null) "[TELCO] confirmed value entered (${value.length} chars)"
            else if (auto) "[TELCO] auto '${value}' = ${com.tekyerema.core.Privacy.content(args.str(ARG_ROW) ?: "")}"
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
        if (kind != BankUssd.ScreenKind.MENU && kind != BankUssd.ScreenKind.FINAL) {
            ledger(app, session.flow, kind)   // first: the watcher that reads the receipt settles THIS entry
            handOver(kind, ctrl, app, session.flow.netName)
        }
        // The network's last word has been taken: its OK box is closed, so the user isn't left to find it.
        if (kind == BankUssd.ScreenKind.FINAL) UssdDialog.close(ctrl, next)
        return ToolResult(true, "${session.flow.netName}: ${BankUssd.spokenScreen(next.text, speakBalance = false).take(120)}",
            data = JSONObject().put("kind", kind.name.lowercase()), channel = "native")
    }

    /** A navigator hand-over that reached the PIN (or the payment confirmation) through the guide is a hand-off like any
     *  other: the ledger entry, today's total, the follow-up look (money/MoneyStatus) — once per session. */
    private fun ledger(app: android.content.Context?, flow: TelcoUssd.Flow, kind: BankUssd.ScreenKind) {
        if (!flow.confirmed || (kind != BankUssd.ScreenKind.PIN && kind != BankUssd.ScreenKind.MONEY)) return
        val what = when {
            flow.what.contains("airtime") -> TxLedger.What.AIRTIME
            flow.what.contains("send money") -> TxLedger.What.SEND
            else -> return
        }
        val at = TelcoSession.current()?.openedAt ?: return
        if (ledgerFor == at) return
        ledgerFor = at
        MoneyStatus.handedOff(app, what, MoneyLimits.parseAmount(flow.amount), flow.number, flow.name, flow.netName.lowercase())
    }

    @Volatile private var ledgerFor = 0L

    /** A payment confirmation or a PIN: no self-opened microphone, no helper at the PIN, the volume keys as the PIN pad —
     *  and the network's last word after it read out (money/ussd/AfterPin): the receipt a blind user cannot see. */
    private fun handOver(kind: BankUssd.ScreenKind, ctrl: com.tekyerema.accessibility.AccessibilityController,
                         app: android.content.Context?, network: String?) {
        if (kind == BankUssd.ScreenKind.PIN) PinPad.arm()
        com.tekyerema.voice.SecureStep.begin()
        com.tekyerema.agent.helper.HelperGate.atPin()
        com.tekyerema.money.ussd.AfterPin.watch(ctrl, app, network?.lowercase())
    }

    override fun confirmationPrompt(args: JSONObject): String {
        if (args.optString(ARG_ACTION) != REPLY) {
            val net = args.str(ARG_NET) ?: "the network"
            val what = args.str(ARG_WHAT) ?: "its menu"
            val code = args.str(ARG_CODE)?.let { ", ${TelcoUssd.spokenCode(it)}" } ?: ""
            // A purchase with its details: ONE read-back that says the amount and who it is for — they are then entered
            // when the network asks, with no second question each (owner, 2 October 2026: "it should not repeatedly
            // confirm information that is already clear from the original instruction").
            val amount = args.str(ARG_AMOUNT)?.let { com.tekyerema.tools.GhanaMobileMoney.cedisPhrase(it) }
            val number = args.str(ARG_NUMBER)?.takeIf { n -> n.count { it.isDigit() } >= 7 }
            val who = number?.let { n ->
                val said = com.tekyerema.agent.NumberSpeech.moneyReadBack(n).first
                args.str(ARG_CONTACT)?.trim()?.takeIf { it.isNotEmpty() }?.let { "$it, on $said" } ?: said
            }
            val pay = when (args.str(ARG_PAY)) { "momo" -> ", paying with Mobile Money"; "airtime" -> ", paying from your airtime"; else -> "" }
            if (args.str(ARG_FLOW) == TelcoUssd.MENU && amount == null && who == null)
                return "Open $net's menu$code? I'll read it to you and choose what you asked for; you confirm any payment " +
                    "and enter your PIN yourself."
            // Airtime pays at once, with no PIN: the read-back says so, because no later step will.
            val airtimeNote = if (args.str(ARG_PAY) == "airtime") " It comes straight off your airtime — there's no PIN step." else ""
            if (amount != null || who != null)
                return "Buy $what" + (amount?.let { " for $it" } ?: "") + (who?.let { ", for $it" } ?: "") + "$pay?\n" +
                    "I'll go through $net's menu and stop for you to confirm the payment and enter your PIN.$airtimeNote"
            return "Open $net's menu for $what$code? I'll choose the steps, ask you only what you need to decide" +
                (if (args.str(ARG_PAY) == "momo") ", and pay with Mobile Money — you enter your PIN yourself." else ", and you confirm any payment and enter your PIN yourself.") +
                airtimeNote
        }
        val screen = args.str(ARG_SCREEN)?.takeIf { it.isNotBlank() }
        return (screen?.let { "The screen says “$it”. " } ?: "") + "Enter ${BankUssdTool.spokenEntry(args.optString(ARG_TEXT), screen)} and send it?"
    }
}
