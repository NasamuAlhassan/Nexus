package com.tekyerema.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.TelephonyManager
import android.util.Log
import com.tekyerema.accessibility.AccessibilityController
import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.RiskTier
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * DETERMINISTIC Mobile-Money send driver (owner-requested, demo). After the user has SPOKEN and CONFIRMED the
 * recipient number + amount + reference (extracted by the brain), this walks the MTN *170# send-money menu
 * WITHOUT the LLM in the per-step loop — so it beats the USSD session timeout the LLM path hits.
 *
 * SAFETY (non-negotiable, matches the product's boundary):
 *  - It NEVER enters the PIN. The instant a screen mentions a PIN it STOPS and hands control to the user.
 *  - It is NOT blind: it READS each USSD screen and only responds to screens it recognises. Any unexpected
 *    screen → STOP (so a changed/promo/error menu can't make it press a money key wrongly).
 *  - Money only moves when the USER enters the PIN, and MTN shows the final amount+recipient on that screen.
 *  - That screen is READ and said first (2026-09-29, [MtnConfirm]): MTN's own words — the recipient's registered name,
 *    amount, fee, total. If the amount (or a shown number) isn't what the user confirmed, or it isn't the PIN prompt,
 *    the driver presses Cancel and says what MTN showed. Nothing is handed over unchecked.
 *  - Every screen is logged ([MOMO] …) so a live 10-pesewa test surfaces MTN's exact wording for tuning.
 *
 * This is a FIRST DRAFT written without live testing (real money) — it MUST be validated with one 10-pesewa
 * run before any recorded/live use. If a screen isn't matched it fails safe (stops), never sends a wrong key.
 */
object MoMoSendTool : Tool {
    override val name = "momo_send"
    override val description =
        "Send money via Mobile Money: after the recipient number + amount + reference are confirmed, deterministically walk the *170# send-money menu (reading each screen) and STOP at the PIN for the user. Never types the PIN. Use only when the number, amount and reference are all known and confirmed."
    override val scope = CapabilityScope.COMMS_SEND
    override val riskTier = RiskTier.TIER3   // highest confirmation tier; PIN is never automated
    override val isAccelerator = true
    override val params = listOf(
        ToolParam("number", "string", "Recipient phone number (digits)."),
        ToolParam("amount", "string", "Cedi amount, e.g. 0.1 for 10 pesewas."),
        ToolParam("reference", "string", "Payment reference / narration.", required = false)
    )

    private const val TAG = "Tekyerema"

    /**
     * What is wrong with a recipient number, or null when it is a Ghana mobile number (10 digits from 0; "233…" folds
     * to 0…). A judge's spoken numbers came through as 7, 9 and 11 digits (A20e 2026-09-29) — a misheard number must
     * be said again before any read-back, never confirmed and dialled. The text goes back to the planner and the user.
     */
    internal fun numberProblem(raw: String?): String? {
        val d = com.tekyerema.agent.NumberSpeech.normalize(raw)
        if (d.length == 10 && d.startsWith("0")) return null
        if (d.isEmpty()) return "I don't have the recipient's number yet — ask the user for it."
        return "I heard the number ${com.tekyerema.agent.NumberSpeech.spoken(d)} — that's ${d.length} digits, and a Ghana mobile number has 10 " +
            "starting with 0. Ask the user to say it again slowly, or to type it."
    }

    /** Set by [preflight]: the SIM's network key, so the read-back can say when the number is on another network. */
    internal const val ARG_SIM = "_sim"
    /** Set by [preflight]: the phone was on a call when the send was asked for (scam shield, [MoneyPrivacy]). */
    internal const val ARG_ON_CALL = "_on_call"
    /** Set by [preflight]: the user has never sent Mobile Money to this number on this phone (money safety round 2,
     *  money/KnownRecipients) — the read-back says so. */
    internal const val ARG_NEW_RECIPIENT = "_new_recipient"

    private fun operatorOf(ctx: ToolContext): String? {
        val tm = ctx.appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        return tm?.simOperatorName?.ifBlank { tm.simOperator } ?: tm?.simOperator
    }

    /** M-03: the automatic driver only runs where a send sequence has been confirmed live (MTN today). On any other
     *  network it refuses BEFORE the confirmation, so the planner falls back to the guided mobile_money menu —
     *  it used to fire MTN's key sequence into AirtelTigo/Telecel's *110# menu. */
    override suspend fun preflight(args: JSONObject, ctx: ToolContext): ToolResult? {
        // Money safety round 2 (2026-09-29): the user's own spending limit is checked first — a send over it is never read
        // back for a yes; the refusal ends the task (RoutingBrain.moneyEnd → money/MoneyPlan.limitEnd).
        com.tekyerema.money.MoneyLimits.preflight(args.str("amount"))?.let { (line, final) -> return ToolResult(false, line, recoverable = !final) }
        numberProblem(args.str("number"))?.let { return ToolResult(false, it, recoverable = true) }
        // What execute TYPES is what preflight CHECKED: "+233…" folds to the 0-form and "50 pesewas" / "GHS 5" to the
        // plain figure — the raw words used to be typed into *170# and MTN's screen then never matched (audit half 2 #10).
        args.put("number", com.tekyerema.agent.NumberSpeech.normalize(args.str("number")))
        com.tekyerema.money.MoneyLimits.parseAmount(args.str("amount"))?.let { args.put("amount", it.toPlainString()) }
            ?: return ToolResult(false, "I couldn't make out the amount \"${args.str("amount")}\" in cedis — ask the user to say it again.", recoverable = true)
        val op = operatorOf(ctx)
        val net = GhanaMobileMoney.network(op)
            ?: return ToolResult(false, "I couldn't tell which network this SIM is on, so I won't drive Mobile Money automatically.", recoverable = true)
        if (GhanaMobileMoney.sendSequence(op, "0", "0", "x") == null)
            return ToolResult(false, "My automatic send only knows MTN's menu, and this SIM is on ${net.replaceFirstChar { it.uppercase() }}. " +
                "Use mobile_money to open the menu and guide the user through it instead.", recoverable = true)
        args.put(ARG_SIM, net)   // the read-back says so when the number is on another network (money safety, 2026-09-29)
        if (MoneyPrivacy.onCall(ctx.appContext)) args.put(ARG_ON_CALL, true)   // the read-back warns about call-guided scams
        // Round 2: a number never paid before says so (set or cleared — a corrected number is checked again).
        if (com.tekyerema.money.KnownRecipients.isKnown(args.str("number"))) args.remove(ARG_NEW_RECIPIENT) else args.put(ARG_NEW_RECIPIENT, true)
        return null
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val number = args.str("number")?.filter { it.isDigit() }.orEmpty()
        val amount = args.str("amount")?.trim().orEmpty()
        val reference = args.str("reference")?.trim().ifBlankOrNull("Payment")
        if (number.length < 7) return ToolResult(false, "I don't have a valid recipient number to send to.")
        if (amount.isEmpty()) return ToolResult(false, "I don't have an amount to send.")
        if (ctx.appContext.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
            return ToolResult(false, "CALL_PHONE permission isn't granted, so I can't open Mobile Money.")
        val ctrl = ctx.controller ?: return ToolResult(false, "The accessibility service isn't running, so I can't drive the menu.")

        val operator = operatorOf(ctx)
        val root = GhanaMobileMoney.rootUssd(operator)
            ?: return ToolResult(false, "I couldn't tell which network this SIM is on, so I won't guess a Mobile Money code.", recoverable = true)
        // M-03: the network's confirmed sequence, or refuse (preflight normally catches this before confirmation).
        val keys = GhanaMobileMoney.sendSequence(operator, number, amount, reference)
            ?: return ToolResult(false, "My automatic send only knows MTN's menu. Nothing was sent.", recoverable = true)

        // Open the interactive USSD session.
        try {
            ctx.appContext.startActivity(
                Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(root))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (t: Throwable) {
            return ToolResult(false, "Couldn't open Mobile Money: ${t.message}", recoverable = true)
        }
        // SEC-P1-4: recipient number + amount are money-sensitive — log the full detail only in debug (the
        // 10-pesewa live-tuning test runs a debug build); release logs a redacted number.
        if (com.tekyerema.BuildConfig.DEBUG) Log.i(TAG, "[MOMO] opened $root — driving send-money to $number, amount $amount, ref '$reference'")
        else Log.i(TAG, "[MOMO] opened $root — driving send-money to …${number.takeLast(3)}, amount $amount")

        // FIXED OFFLINE SEQUENCE (owner-requested): NO screen-reading-to-decide. We fire the exact MTN Send
        // Money keypresses in order; the only screen inspection is (a) waiting for each USSD dialog to be
        // ready (timing) and (b) the PIN safety-stop — we NEVER type into a PIN screen. [owner: this order
        // must match your live *170# Send Money flow — a wrong/extra step moves real money.]
        // Owner-confirmed live MTN sequence: 1, 1, number, number (re-entered to confirm), amount, reference, PIN —
        // now data (connectors.mobileMoney.networks.<net>.sendSequence), with the same default in code.
        val sequence = keys.mapIndexed { i, v -> v to "step ${i + 1}" }
        var lastText = ""
        // Round 2 (2026-09-29): a new walk replaces any send waiting for "try again"; a TIMEOUT below keeps this one for it
        // (never a Cancel or a mismatch — money/MoMoRetry). Nothing is ever retried without the user asking and confirming.
        com.tekyerema.money.MoMoRetry.clear()
        for ((idx, s) in sequence.withIndex()) {
            val (value, label) = s
            // First screen gets longer (USSD initial connect can be slow); later round-trips settle faster.
            val dlg = awaitUssd(ctrl, lastText, timeoutMs = if (idx == 0) 25_000L else 18_000L)
                ?: return timedOut(number, amount, args.str("reference"), com.tekyerema.money.MoMoRetry.MENU_TIMEOUT)
            lastText = dlg.text
            // J-15: a USSD screen carries names, numbers and balances — words in logcat only where content logging is allowed.
            Log.i(TAG, "[MOMO] screen ${idx + 1}: ${com.tekyerema.core.Privacy.content(dlg.text.take(160).replace("\n", " | "))}")
            // SAFETY: never type into a PIN/credential screen. If it shows (early or on cue), MTN's confirmation is
            // checked against what the user confirmed before the PIN is handed over (atConfirmation).
            if (isPinScreen(dlg)) return atConfirmation(ctrl, dlg, amount, number, pinScreen = isPinPrompt(dlg), tip = pinTip(ctx))
                .also { if (it.success) handedOff(ctx, amount, number, it.data?.optString("registeredName")) }
            respond(ctrl, dlg, value, label)
            delay(900)
        }
        // Sequence fired — the next screen should be MTN's confirmation + PIN prompt. It is READ and checked; with no
        // readable screen nothing is handed over (money safety, 2026-09-29: it used to say "enter your PIN" regardless).
        val final = awaitUssd(ctrl, lastText, timeoutMs = 18_000L)
            ?: return timedOut(number, amount, args.str("reference"), com.tekyerema.money.MoMoRetry.CONFIRM_TIMEOUT)
        Log.i(TAG, "[MOMO] final screen: ${com.tekyerema.core.Privacy.content(final.text.take(160).replace("\n", " | "))}")
        return atConfirmation(ctrl, final, amount, number, pinScreen = isPinPrompt(final), tip = pinTip(ctx))
            .also { if (it.success) handedOff(ctx, amount, number, it.data?.optString("registeredName")) }   // round 2: limits, ledger, receipt
    }

    /** The walk timed out (the menu or MTN's confirmation never came): nothing was sent, and "try again" can re-propose
     *  this exact send — through the whole read-back again (money/MoMoRetry). */
    private fun timedOut(number: String, amount: String, reference: String?, line: String): ToolResult {
        com.tekyerema.money.MoMoRetry.remember(number, amount, reference?.trim())
        return ToolResult(false, line, recoverable = false)
    }

    /** Round 2: the send reached the PIN hand-off — it counts toward today's limit, the number is now one the user has paid,
     *  and MTN's "You have sent …" message in the next minutes is read as a receipt (money/). */
    private fun handedOff(ctx: ToolContext?, amount: String, number: String, registeredName: String? = null) {
        // Finance audit (2026-10-01): one place for a hand-off (money/MoneyStatus) — today's total, the ledger entry whose
        // outcome the network's own message settles, and the follow-up look. The number is "one you have paid" only then.
        com.tekyerema.money.MoneyStatus.handedOff(ctx?.appContext, com.tekyerema.money.TxLedger.What.SEND,
            com.tekyerema.money.MoneyLimits.parseAmount(amount), number,
            registeredName?.takeIf { it.isNotBlank() }?.let { MtnConfirm.speakName(it) },
            ctx?.let { GhanaMobileMoney.network(operatorOf(it)) } ?: "mtn",
            registered = registeredName?.takeIf { it.isNotBlank() })
    }

    /**
     * Money safety (2026-09-29): at MTN's confirmation / PIN screen, speak MTN's OWN words — the recipient's registered
     * name, amount, fee, total — before the user types the PIN. The owner's live send showed "Transfer to AMA MENSAH …"
     * and the name was never said; it is the best check a blind user has against a misheard number. If the amount on the
     * screen isn't the confirmed amount, a shown number isn't the confirmed number, the amount can't be found, or this
     * isn't the PIN screen, press Cancel (always safe) and say what MTN showed — nothing is handed over. The PIN is
     * never typed, seen or logged either way ([pinStop] semantics).
     */
    private suspend fun atConfirmation(ctrl: AccessibilityController, dlg: Ussd, amount: String, number: String, pinScreen: Boolean,
                                       tip: String? = null): ToolResult =
        when (val v = MtnConfirm.verdict(dlg.text, amount, number, pinScreen)) {
            is MtnConfirm.Verdict.HandOff -> pinStop(v.spoken + (tip?.let { " $it" } ?: ""), MtnConfirm.data(v.screen))
            is MtnConfirm.Verdict.Cancel -> {
                Log.i(TAG, "[MOMO] confirmation doesn't match what the user confirmed — pressing Cancel")
                val pressed = cancelDialog(ctrl, dlg)
                ToolResult(false, MtnConfirm.cancelledLine(v.why, pressed), data = MtnConfirm.data(v.screen).put("cancelled", pressed),
                    channel = "native", recoverable = false)
            }
        }

    /** The loudspeaker PIN tip for this phone right now ([MoneyPrivacy]), or null. */
    private fun pinTip(ctx: ToolContext): String? =
        MoneyPrivacy.pinTip(MoneyPrivacy.screenReaderOn(ctx.appContext), MoneyPrivacy.privateAudio(ctx.appContext))

    /** Press the dialog's Cancel (Back if it has none) and check the dialog went. True when it is gone. Cancelling a
     *  USSD session never moves money. */
    private suspend fun cancelDialog(ctrl: AccessibilityController, dlg: Ussd): Boolean {
        try { dlg.cancelId?.let { ctrl.clickElement(it) } ?: ctrl.globalAction("back") } catch (_: Throwable) {}
        delay(900)
        var now = readUssd(ctrl)
        if (now != null && now.text == dlg.text) {
            try { ctrl.globalAction("back") } catch (_: Throwable) {}
            delay(900)
            now = readUssd(ctrl)
        }
        return now == null || now.text != dlg.text
    }

    /** True when the USSD screen is the PIN/credential entry — the operator must NEVER type into it. Stops on
     *  a masked/password field (structure) OR credential wording (text), so a differently-worded secret screen
     *  can't slip past the literal word "PIN". */
    private fun isPinScreen(dlg: Ussd): Boolean =
        isPinPrompt(dlg) ||
        // Fail closed (2026-09-29): a payment screen's words (a GHS figure + total / fee / tax / confirm) stop the walk too,
        // whatever the prompt says — then MTN's screen is checked, and only a real PIN prompt is handed over.
        com.tekyerema.policy.TargetSemantics.isMoneyConfirmationText(dlg.text)

    /** The PIN prompt itself: a masked field or PIN wording. Only this is handed to the user with "enter your PIN" —
     *  a payment screen that wants something else ("1. Confirm") is cancelled, since the instruction would be wrong. */
    private fun isPinPrompt(dlg: Ussd): Boolean =
        dlg.hasPassword ||
        Regex("\\bpin\\b|enter your pin|enter pin|momo pin|mobile money pin|\\benter mm\\b|passcode|secret code|\\botp\\b|password",
            RegexOption.IGNORE_CASE).containsMatchIn(dlg.text)

    /** The success hand-off at the PIN: everything is entered; the USER types the PIN. [line] is MTN's confirmation as
     *  checked by [MtnConfirm]; [data] carries the registered name etc. for later layers. */
    private fun pinStop(line: String, data: JSONObject): ToolResult {
        Log.i(TAG, "[MOMO] reached the PIN screen — stopping for the user.")
        com.tekyerema.voice.SecureStep.begin()   // no self-opened mic while the PIN is typed (TalkBack speaks the keys)
        return ToolResult(true, line, data = data, channel = "native")
    }

    // ---- helpers ----
    private data class Ussd(val text: String, val inputId: Int?, val sendId: Int?, val hasPassword: Boolean = false, val cancelId: Int? = null)

    /** Wait for the NEXT USSD dialog after a submit. We do NOT rely on the text differing — two consecutive
     *  MTN prompts can be worded identically (enter number, then RE-ENTER the number to confirm). Instead we
     *  wait for the previous dialog to CLEAR first: pressing Send dismisses it and the dialer shows a no-input
     *  "USSD code running…"/blank state (readUssd returns null there), THEN the next dialog with an input field
     *  appears. Text-change is kept as a fallback for a transition too fast to catch the gap. */
    private suspend fun awaitUssd(ctrl: AccessibilityController, prevText: String, timeoutMs: Long): Ussd? {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        var sawGap = prevText.isBlank()   // first screen: there's no previous dialog to clear
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            delay(500)
            val u = readUssd(ctrl)
            if (u == null) { sawGap = true; continue }   // no input field = running/blank = the gap between screens
            if (u.text.isBlank()) continue
            if (sawGap || u.text != prevText) return u    // a fresh dialog after the gap (or genuinely new text)
        }
        return null
    }

    private suspend fun readUssd(ctrl: AccessibilityController): Ussd? {
        val snap = try { ctrl.buildSnapshot() } catch (t: Throwable) { return null }
        // Only the phone app's own window is a USSD dialog. Without this, the app UNDERNEATH (in front before the
        // dialog appears, or after a cancel) was read as the USSD screen — and the walk typed the number and amount
        // into, say, a chat box and pressed its Send (audit 2026-09-30, half 2 #2).
        if (!com.tekyerema.accessibility.TelephonyWindows.matches(snap.appPackage)) return null
        val labels = ArrayList<String>()
        snap.screenTitle?.takeIf { it.isNotBlank() }?.let { labels += it }
        var inputId: Int? = null
        var sendId: Int? = null
        var cancelId: Int? = null
        for (e in snap.elements) {
            val l = e.label?.trim().orEmpty()
            // The dialog's Cancel — pressed when MTN's confirmation doesn't match what the user confirmed (still read as text).
            if (e.clickable && !e.editable && cancelId == null && Regex("^(cancel|dismiss)$", RegexOption.IGNORE_CASE).matches(l)) cancelId = e.id
            if (e.editable && inputId == null) inputId = e.id
            else if (e.clickable && Regex("^(send|ok|submit|reply)$", RegexOption.IGNORE_CASE).matches(l)) { if (sendId == null) sendId = e.id }
            else if (l.isNotBlank() && !e.editable) labels += l
        }
        // A real USSD menu/entry dialog ALWAYS carries the choice/entry field. WITHOUT it this is the launcher
        // (home icons like "Phone" would false-match a keyword), the dialer's transient "USSD code running…"
        // progress, or some other screen — NOT our dialog. Return null so awaitUssd keeps polling for the real
        // menu instead of misfiring on a home-screen icon or a loading spinner. [fix: the old fallback returned
        // the home screen as a USSD screen, which typed into nothing and then aborted on "USSD code running…".]
        if (inputId == null) return null
        val text = labels.joinToString("\n").trim()
        // Surface whether ANY field on this screen is masked/password — the PIN entry is masked even when its
        // wording lacks the literal word "PIN". The PIN-stop below treats a masked field as a hard stop.
        val hasPassword = snap.elements.any { it.password }
        return Ussd(text, inputId, sendId, hasPassword, cancelId)
    }

    /** Type [value] into the dialog input and press Send (falls back to the field's own submit action). */
    private suspend fun respond(ctrl: AccessibilityController, dlg: Ussd, value: String, step: String) {
        Log.i(TAG, "[MOMO] → '${com.tekyerema.core.Privacy.content(value)}'  ($step)")   // J-15: number, amount, reference
        val id = dlg.inputId
        if (id == null) { Log.w(TAG, "[MOMO] no input field for '$step'"); return }
        val sid = dlg.sendId
        if (sid != null) { ctrl.setText(id, value, submit = false); delay(250); ctrl.clickElement(sid) }
        else ctrl.setText(id, value, submit = true)
    }

    /** Find the menu option number preceding a keyword line, e.g. "1. Send Money" → "1". Null if not found. */
    private fun menuOption(text: String, keyword: Regex): String? {
        for (line in text.split("\n")) {
            if (keyword.containsMatchIn(line.lowercase()))
                Regex("^\\s*(\\d+)").find(line)?.let { return it.groupValues[1] }
        }
        return null
    }

    private fun String?.ifBlankOrNull(default: String) = this?.takeIf { it.isNotBlank() } ?: default

    override fun confirmationPrompt(args: JSONObject): String {
        val amt = args.str("amount")?.let { GhanaMobileMoney.cedisPhrase(it) } ?: "money"
        // The reference is part of what is sent, so it is part of what is read back (A20e audit 2026-10-01, F09).
        val ref = GhanaMobileMoney.referencePhrase(args.str("reference"))
        // Read the recipient number in the groups people check it in ("0 5 5, 3 2 3, 8 0 1 1") with its network, so a
        // blind user can catch a wrong-number or units (pesewa/cedi) mistake BEFORE the send is driven — and hear when the
        // number isn't on a network we know, or is on another network than the SIM (money safety, 2026-09-29).
        val raw = args.str("number")?.takeIf { n -> n.any { it.isDigit() } }
            ?: return "Send $amt with Mobile Money$ref? I'll go through the menu and stop at the PIN for you to enter."
        val (to, netWarn) = com.tekyerema.agent.NumberSpeech.moneyReadBack(raw, args.str(ARG_SIM))
        val warn = listOfNotNull(netWarn, firstTimeWarning(args), MoneyPrivacy.callWarning(args.optBoolean(ARG_ON_CALL)))
            .joinToString(" ").ifEmpty { null }
        return "Send $amt to $to, with Mobile Money$ref?\n" + (warn?.let { "$it " } ?: "") +
            "I'll go through the menu and stop at the PIN for you to enter."
    }

    /** Round 2 (secure_payment_auth.md rec. 7): "You haven't sent money to this number before." when preflight found it
     *  new — the scam script ("send it back") always pays a number the victim never paid. */
    internal fun firstTimeWarning(args: JSONObject): String? =
        if (args.optBoolean(ARG_NEW_RECIPIENT)) com.tekyerema.money.KnownRecipients.FIRST_TIME_LINE else null

    /** The read-back's "check the number" line, or null — a structured (Twi) read-back can't carry it, so the English
     *  sentence is spoken whenever there is one (AgentEngine.confirmSpecFor). */
    fun networkWarning(args: JSONObject): String? = listOfNotNull(
        args.str("number")?.takeIf { n -> n.any { it.isDigit() } }
            ?.let { com.tekyerema.agent.NumberSpeech.moneyReadBack(it, args.str(ARG_SIM)).second },
        firstTimeWarning(args),
        MoneyPrivacy.callWarning(args.optBoolean(ARG_ON_CALL))).joinToString(" ").ifEmpty { null }
}
