package com.tekyerema.tools

import com.tekyerema.notifications.TekyeremaNotificationListener
import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.RiskTier
import org.json.JSONObject

/**
 * E-17 — act on a notification without opening its app: dismiss it, or press one of its buttons ("Mark as read",
 * "Archive", "Call back"…). Dismissing is TIER1; pressing any other button is raised to TIER2 by the policy
 * ([com.tekyerema.policy.PolicyConfig.argTier]) because a button can delete, archive or call. Buttons that need
 * typed text are left to reply_to_notification, which carries the message through its own read-back.
 */
object NotificationActionTool : Tool {
    override val name = "notification_action"
    override val description = "Dismiss a notification, or press one of its buttons (as listed by read_notifications), by sender or app."
    override val scope = CapabilityScope.COMMS_SEND
    override val riskTier = RiskTier.TIER1
    override val isAccelerator = true
    override val params = listOf(
        ToolParam("match", "string", "Sender, app or words of the notification."),
        ToolParam("action", "string", "'dismiss', or the button's label, e.g. 'Mark as read'.")
    )

    override suspend fun preflight(args: JSONObject, ctx: ToolContext): ToolResult? {
        if (!com.tekyerema.ui.Perms.notifications(ctx.appContext))
            return ToolResult(false, "Notification access is off, so I can't act on notifications.", recoverable = false)
        val rec = ctx.notifications.find(args.str("match"))
            ?: return ToolResult(false, "I couldn't find a notification matching '${args.str("match")}'.")
        val want = args.str("action")?.trim().orEmpty()
        if (!want.equals("dismiss", true) && rec.actions.none { it.title?.toString()?.equals(want, true) == true })
            return ToolResult(false, "That notification has no '$want' button. It offers: " +
                rec.actions.mapNotNull { it.title?.toString() }.joinToString(", ").ifEmpty { "no buttons" } + ".")
        return null
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val rec = ctx.notifications.find(args.str("match"))
            ?: return ToolResult(false, "I couldn't find that notification any more.")
        val want = args.str("action")?.trim().orEmpty()
        if (want.equals("dismiss", true)) {
            val ok = TekyeremaNotificationListener.dismiss(rec.key)
            if (ok) ctx.notifications.remove(rec.key)
            return if (ok) ToolResult(true, "Dismissed the ${rec.spokenSource} notification.", channel = "native")
            else ToolResult(false, "I couldn't dismiss it — notification access isn't connected.", recoverable = false)
        }
        val action = rec.actions.firstOrNull { it.title?.toString()?.equals(want, true) == true }
            ?: return ToolResult(false, "That button is gone.")
        if (action.remoteInputs?.isNotEmpty() == true)
            return ToolResult(false, "That button needs typed text — use reply_to_notification for replies.")
        return try {
            action.actionIntent.send()
            ToolResult(true, "Pressed \"$want\" on the ${rec.spokenSource} notification.", channel = "native")
        } catch (t: Throwable) {
            ToolResult(false, "The app refused that button: ${t.message}")
        }
    }

    override fun confirmationPrompt(args: JSONObject): String =
        "Press \"${args.str("action")}\" on the ${args.str("match")} notification?"
}
