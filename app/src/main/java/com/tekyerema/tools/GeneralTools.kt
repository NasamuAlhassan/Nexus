package com.tekyerema.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import com.tekyerema.android.ContactResolver
import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.RiskTier
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

/** General contact lookup by name OR number — reliable structured access, no UI navigation needed. */
object SearchContactsTool : Tool {
    override val name = "search_contacts"
    override val description =
        "Search the phone's contacts by a person's name OR by a phone number (reverse lookup: 'who owns this number'). Returns matching contacts."
    override val scope = CapabilityScope.COMMS_READ
    override val riskTier = RiskTier.TIER0
    override val isAccelerator = true
    override val params = listOf(ToolParam("query", "string", "A name or a phone number to look up."))

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val query = args.str("query")?.trim().orEmpty()
        if (query.isEmpty()) return ToolResult(false, "No search query provided.")
        if (ctx.appContext.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            return ToolResult(false, "Contacts permission not granted. Ask the user to grant it.")
        // The same person saved twice (once with the 0, once without) is one match — the A20e listed "0552093690" and
        // "552093690" as two choices for Prince Alhassan (3 October 2026).
        val matches = ContactChoice.distinct(ContactResolver.search(ctx.appContext, query))
        // A miss here is an ACCELERATOR miss, NOT proof the person doesn't exist: the phone address book
        // is only one source. Report it as an unsuccessful (but recoverable) shortcut so the planner falls
        // back to the General Operator — e.g. the target app's own in-UI search — instead of concluding
        // the contact is unreachable (hybrid architecture §3, Scenario 4).
        return if (matches.isEmpty())
            // For a CALL or an SMS only the address book can supply a number, so the user is asked for it; the person is
            // looked for inside a chat app only when the user named that app (A20e audit 2026-10-01, C16: a missing
            // contact for a phone call sent the planner into WhatsApp's Meta AI search).
            ToolResult(false, "No contact in the phone's address book matches '$query'. For a call or a text, ask the user for the number (ask_user). " +
                "Only if the user named a chat app (WhatsApp, Telegram…) look for the person in that app's own chat list.", channel = "native", recoverable = true)
        else ToolResult(true, "Matches for '$query': " + matches.joinToString("; ") { "${it.name} — ${it.number}" }, channel = "native")
    }
}

/** Run a USSD code (e.g. MTN Ghana airtime *124#, data *138#) and return the network's response text. */
object RunUssdTool : Tool {
    override val name = "run_ussd"
    override val description =
        "Dial a USSD code (like *124# for MTN airtime balance) and return the network's reply text. Use for airtime/data/balance checks."
    override val scope = CapabilityScope.COMMS_SEND
    override val riskTier = RiskTier.TIER2
    override val isAccelerator = true
    override val params = listOf(ToolParam("code", "string", "The USSD code including * and #, e.g. *124#."))

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val code = args.str("code")?.trim().orEmpty()
        if (code.isEmpty()) return ToolResult(false, "No USSD code provided.")
        if (ctx.appContext.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
            return ToolResult(false, "CALL_PHONE permission not granted. Ask the user to grant it.")
        val tm = ctx.appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return ToolResult(false, "Telephony not available on this device.")

        suspend fun ask(timeoutMs: Long) = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<String?> { cont ->
                try {
                    val handler = Handler(Looper.getMainLooper())
                    tm.sendUssdRequest(code, object : TelephonyManager.UssdResponseCallback() {
                        override fun onReceiveUssdResponse(t: TelephonyManager, request: String, resp: CharSequence) {
                            if (cont.isActive) cont.resume(resp.toString())
                        }
                        override fun onReceiveUssdResponseFailed(t: TelephonyManager, request: String, failureCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }, handler)
                } catch (t: Throwable) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
        // A refusal that comes back at once is usually the network's momentary hiccup: ask once more (a timeout is not retried).
        val t0 = android.os.SystemClock.elapsedRealtime()
        var response = ask(30_000L)
        if (response == null && android.os.SystemClock.elapsedRealtime() - t0 < 8_000L) { kotlinx.coroutines.delay(1_500L); response = ask(25_000L) }
        // Paced and in words (money/UssdSpeech): the carrier's run-on blob was unfollowable read raw (owner, 2026-09-30).
        // The network's checked balance code gets the short answer (airtime first, then data); any other code is read in full.
        val balance = code.replace(Regex("\\s"), "") in com.tekyerema.policy.PolicyConfig.freeReadOnlyCodes()
        return if (response != null) ToolResult(true, (if (balance) com.tekyerema.money.UssdSpeech.airtimeBalance(response) else null)
            ?: com.tekyerema.money.UssdSpeech.forEar(response), channel = "native")
        else ToolResult(false, "USSD $code did not return a response (network refused, timed out, or SIM/carrier not supported). Do not dial it in the Phone app or search the screens: tell the user the network did not answer and offer to try again.")
    }

    /** The network's reply IS the outcome — a one-shot code changes nothing on screen, and the screen-based verifier
     *  called a successful check "no_effect", so the router escalated and the cloud re-did it through the dialer
     *  (A20e 2026-09-30). */
    override val verifiesOutcome = true
    override suspend fun verify(args: JSONObject, ctx: ToolContext, before: com.tekyerema.accessibility.UiSnapshot?,
                                after: com.tekyerema.accessibility.UiSnapshot?, result: ToolResult): com.tekyerema.verifier.VerificationResult =
        com.tekyerema.verifier.VerificationResult(result.success,
            if (result.success) "the network replied" else "the network didn't reply")

    /** Said in the user's terms, with the code spoken key by key — "Run USSD code *124#?" asked a blind user to approve
     *  a code they never said (audit F01). */
    override fun confirmationPrompt(args: JSONObject): String {
        val code = args.str("code")?.trim().orEmpty()
        val keys = code.map { c -> when (c) { '*' -> "star"; '#' -> "hash"; else -> c.toString() } }.joinToString(" ")
        return when (code) {
            "*124#" -> "Check your airtime balance? I'll dial $keys — it's free."
            else -> "Dial the short code $keys?"
        }
    }
}
