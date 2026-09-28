package com.tekyerema.tools

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.RiskTier
import com.tekyerema.settings.Settings
import org.json.JSONObject

/**
 * L-04 — "I need help": after ONE spoken confirmation, call the user's emergency contact straight away, then text
 * them the user's location while the call connects. Calls and SMS go over the mobile network, so this works with no
 * internet. The contact is chosen by the user ("set my emergency contact to Ama"); with none set, it says how.
 * Ghana's public emergency lines are separate (the fast path puts 112/193/191/192 on the dial pad — Android never
 * lets an app dial those itself).
 */
object EmergencyTool : Tool {
    override val name = "emergency"
    override val description = "Call the user's emergency contact and text them the user's location. Only when the user urgently asks for help."
    override val scope = CapabilityScope.COMMS_SEND
    override val riskTier = RiskTier.TIER2
    override val isAccelerator = true
    override val params = emptyList<ToolParam>()

    override suspend fun preflight(args: JSONObject, ctx: ToolContext): ToolResult? {
        val s = Settings(ctx.appContext)
        if (s.emergencyContactNumber.isBlank())
            return ToolResult(false, "You haven't chosen an emergency contact yet. Say “set my emergency contact to” and a name, " +
                "or say “call an ambulance” for the public line.", recoverable = false)
        return null
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val s = Settings(ctx.appContext)
        val number = s.emergencyContactNumber
        val who = s.emergencyContactName.ifBlank { number }
        if (number.isBlank()) return ToolResult(false, "No emergency contact is set.", recoverable = false)
        // 1. The call first — it matters most.
        val canCall = ctx.appContext.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val called = try {
            ctx.appContext.startActivity(Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Throwable) { false }
        // 2. Then the location text, while the call connects.
        val link = try {
            LocationTool.execute(JSONObject().put("action", "link"), ctx).data?.optString("link")?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) { null }
        val text = "I need help." + (link?.let { " My location: $it" } ?: "") + " (sent by Tɛkyerɛma)"
        val sms = try { SendSmsTool.execute(JSONObject().put("number", number).put("message", text), ctx) } catch (_: Throwable) { null }
        val texted = sms?.data?.optBoolean("sent", false) == true
        val callLine = when {
            !called -> "I couldn't start the call to $who."
            canCall -> "Calling $who now."
            else -> "I've put $who's number on the dial pad — press Call."
        }
        val textLine = when {
            texted && link != null -> " I texted them your location."
            texted -> " I texted them that you need help, but I couldn't get your location."
            else -> " I couldn't send the text."
        }
        return ToolResult(called, callLine + textLine, data = JSONObject().put("called", called).put("texted", texted), channel = "native")
    }

    override fun confirmationPrompt(args: JSONObject): String = "Call your emergency contact and text them your location?"
}
