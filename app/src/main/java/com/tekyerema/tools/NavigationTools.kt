package com.tekyerema.tools

import android.content.Intent
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.android.AppResolver
import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.RiskTier
import com.tekyerema.verifier.VerificationResult
import com.tekyerema.verifier.Verify
import org.json.JSONObject

/**
 * A primitive realized through the more reliable native channel (plan §7.1): a person would tap
 * the launcher icon; a launcher Intent is exact and policy-preferred. Resolves by user-meaningful
 * name — no package-specific task logic.
 */
object OpenAppTool : Tool {
    override val name = "open_app"
    override val description = "Bring an installed app to the foreground by its name (e.g. 'Messages', 'Settings', 'Chrome')."
    override val scope = CapabilityScope.DEVICE_CONTROL
    override val riskTier = RiskTier.TIER1
    override val params = listOf(ToolParam("app", "string", "The app name (or package) to open.")) + EXPECTS_PARAM
    override val verifiesOutcome = true
    override val isAccelerator = true

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val query = args.str("app")?.trim().orEmpty()
        if (query.isEmpty()) return ToolResult(false, "No app name provided.")
        val app = when (val m = AppResolver.resolveDetailed(ctx.appContext, query)) {
            is AppResolver.Match.One -> m.app
            is AppResolver.Match.Ambiguous ->  // §1.10 — near-tie, don't guess which app the user meant
                return ToolResult(false, "I found more than one app close to '$query': " +
                    m.options.joinToString(", ") { it.label } + ". Which one?", needsDisambiguation = true)
            AppResolver.Match.None ->
                return ToolResult(false, "Could not find an installed app matching '$query'.")
        }
        val intent = AppResolver.launchIntent(ctx.appContext, app.packageName)
            ?: return ToolResult(false, "No launch intent for ${app.label}.")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.appContext.startActivity(intent)
            ToolResult(true, "Opened ${app.label}", JSONObject().put("package", app.packageName), channel = "intent")
        } catch (t: Throwable) {
            ToolResult(false, "Failed to open ${app.label}: ${t.message}")
        }
    }

    override suspend fun verify(
        args: JSONObject, ctx: ToolContext, before: UiSnapshot?, after: UiSnapshot?, result: ToolResult
    ): VerificationResult {
        val pkg = result.data?.optString("package").orEmpty()
        if (pkg.isEmpty()) return VerificationResult(false, result.message)
        val (ok, fg) = Verify.pollForegroundContains(ctx.controller, pkg)
        return VerificationResult(ok, if (ok) "foreground is $fg" else "foreground is $fg, expected $pkg")
    }
}
