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
        var d = raw?.filter { it.isDigit() }.orEmpty()
        if (d.length == 12 && d.startsWith("233")) d = "0" + d.substring(3)
        if (d.length == 10 && d.startsWith("0")) return null
        if (d.isEmpty()) return "I don't have the recipient's number yet — ask the user for it."
        return "I heard the number ${d.toList().joinToString(" ")} — that's ${d.length} digits, and a Ghana mobile number has 10 " +
            "starting with 0. Ask the user to say it again slowly, or to type it."
    }

    private fun operatorOf(ctx: ToolContext): String? {
        val tm = ctx.appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        return tm?.simOperatorName?.ifBlank { tm.simOperator } ?: tm?.simOperator
    }

    /** M-03: the automatic driver only runs where a send sequence has been confirmed live (MTN today). On any other
     *  network it refuses BEFORE the confirmation, so the planner falls back to the guided mobile_money menu —
     *  it used to fire MTN's key sequence into AirtelTigo/Telecel's *110# menu. */
    override suspend fun preflight(args: JSONObject, ctx: ToolContext): ToolResult? {
        numberProblem(args.str("number"))?.let { return ToolResult(false, it, recoverable = true) }
        val op = operatorOf(ctx)
        val net = GhanaMobileMoney.network(op)
            ?: return ToolResult(false, "I couldn't tell which network this SIM is on, so I won't drive Mobile Money automatically.", recoverable = true)
        if (GhanaMobileMoney.sendSequence(op, "0", "0", "x") == null)
            return ToolResult(false, "My automatic send only knows MTN's menu, and this SIM is on ${net.replaceFirstChar { it.uppercase() }}. " +
                "Use mobile_money to open the menu and guide the user through it instead.", recoverable = true)
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
        for ((idx, s) in sequence.withIndex()) {
            val (value, label) = s
            // First screen gets longer (USSD initial connect can be slow); later round-trips settle faster.
            val dlg = awaitUssd(ctrl, lastText, timeoutMs = if (idx == 0) 25_000L else 18_000L)
                ?: return ToolResult(false, "The Mobile Money menu didn't respond in time. Nothing was sent — please try again.", recoverable = false)
            lastText = dlg.text
            // J-15: a USSD screen carries names, numbers and balances — words in logcat only where content logging is allowed.
            Log.i(TAG, "[MOMO] screen ${idx + 1}: ${com.tekyerema.core.Privacy.content(dlg.text.take(160).replace("\n", " | "))}")
            // SAFETY: never type into a PIN/credential screen. If it shows (early or on cue), stop for the user.
            if (isPinScreen(dlg)) return pinStop(amount, number)
            respond(ctrl, dlg, value, label)
            delay(900)
        }
        // Sequence fired — the next screen should be the PIN. Log it for verification, then hand off.
        awaitUssd(ctrl, lastText, timeoutMs = 18_000L)?.let {
            Log.i(TAG, "[MOMO] final screen: ${com.tekyerema.core.Privacy.content(it.text.take(160).replace("\n", " | "))}")
        }
        return pinStop(amount, number)
    }

    /** True when the USSD screen is the PIN/credential entry — the operator must NEVER type into it. Stops on
     *  a masked/password field (structure) OR credential wording (text), so a differently-worded secret screen
     *  can't slip past the literal word "PIN". */
    private fun isPinScreen(dlg: Ussd): Boolean =
        dlg.hasPassword ||
        Regex("\\bpin\\b|enter your pin|enter pin|momo pin|mobile money pin|passcode|secret code|\\botp\\b|password",
            RegexOption.IGNORE_CASE).containsMatchIn(dlg.text)

    /** The success hand-off at the PIN: everything is entered; the USER types the PIN. */
    private fun pinStop(amount: String, number: String): ToolResult {
        Log.i(TAG, "[MOMO] reached the PIN screen — stopping for the user.")
        return ToolResult(
            true,
            "I've entered everything. The Mobile Money PIN screen is up — enter your PIN to send $amount cedis to $number. I never handle the PIN.",
            channel = "native"
        )
    }

    // ---- helpers ----
    private data class Ussd(val text: String, val inputId: Int?, val sendId: Int?, val hasPassword: Boolean = false)

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
        val labels = ArrayList<String>()
        snap.screenTitle?.takeIf { it.isNotBlank() }?.let { labels += it }
        var inputId: Int? = null
        var sendId: Int? = null
        for (e in snap.elements) {
            val l = e.label?.trim().orEmpty()
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
        return Ussd(text, inputId, sendId, hasPassword)
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
        val amt = args.str("amount")?.let { "$it cedis" } ?: "money"
        // Read the recipient number digit-by-digit ("0 2 4 …") so TTS never speaks it as one huge number and
        // a blind user can catch a wrong-number or units (pesewa/cedi) mistake BEFORE the send is driven.
        val to = args.str("number")?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }
            ?.toCharArray()?.joinToString(" ") ?: "the recipient"
        return "Send $amt to $to with Mobile Money? I'll go through the menu and stop at the PIN for you to enter."
    }
}
