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
        val matches = ContactResolver.search(ctx.appContext, query)
        // A miss here is an ACCELERATOR miss, NOT proof the person doesn't exist: the phone address book
        // is only one source. Report it as an unsuccessful (but recoverable) shortcut so the planner falls
        // back to the General Operator — e.g. the target app's own in-UI search — instead of concluding
        // the contact is unreachable (hybrid architecture §3, Scenario 4).
        return if (matches.isEmpty())
            ToolResult(false, "No contact in the phone's address book matches '$query'. They may still exist inside a specific app (WhatsApp/Telegram/etc.) under a different name — this shortcut just can't see that.", channel = "native", recoverable = true)
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

        val response = withTimeoutOrNull(30_000L) {
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
        return if (response != null) ToolResult(true, "USSD $code replied: $response", channel = "native")
        else ToolResult(false, "USSD $code did not return a response (network refused, timed out, or SIM/carrier not supported).")
    }

    override fun confirmationPrompt(args: JSONObject) = "Run USSD code ${args.str("code")}?"
}
