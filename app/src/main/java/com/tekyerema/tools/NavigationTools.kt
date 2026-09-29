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
/** "the Zorblax app" → "Zorblax": how to say a requested app's name back to the user. */
internal fun spokenAppName(query: String): String =
    query.trim().replace(Regex("(?i)^(the|my)\\s+"), "").replace(Regex("(?i)\\s+(app|application)$"), "").ifBlank { query.trim() }

/** Up to three installed app names closest to a name that wasn't found (shared words or letters), for "did you mean…". */
internal fun closestApps(context: android.content.Context, query: String): List<String> =
    closestNames(spokenAppName(query), try { AppResolver.launchable(context).map { it.label } } catch (_: Throwable) { emptyList() })

/** Pure (unit-tested): rank [labels] by shared words, then shared character bigrams, with [query]. */
internal fun closestNames(query: String, labels: List<String>, max: Int = 3): List<String> {
    fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() || it == ' ' }
    val q = norm(query)
    val qWords = q.split(' ').filter { it.length >= 3 }.toSet()
    val qBigrams = q.replace(" ", "").windowed(2).toSet()
    return labels.distinct().map { l ->
        val n = norm(l)
        val words = n.split(' ').count { it.length >= 3 && (it in qWords || qWords.any { w -> w.startsWith(it) || it.startsWith(w) }) }
        val bigrams = n.replace(" ", "").windowed(2).toSet()
        val overlap = if (bigrams.isEmpty() || qBigrams.isEmpty()) 0.0 else (bigrams intersect qBigrams).size.toDouble() / minOf(bigrams.size, qBigrams.size)
        l to (words * 2 + overlap)
    }.filter { it.second >= 0.6 }.sortedByDescending { it.second }.take(max).map { it.first }   // 0.5 let "zorblax" → "Galaxy Store"
}

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
            AppResolver.Match.None -> {
                // Seen on the A20e: a misheard name ("product video game") sent the agent into the Galaxy Store and its
                // Samsung-account sign-in. An app that isn't installed is a question for the user, not a store trip.
                val close = closestApps(ctx.appContext, query)
                return ToolResult(false, "I couldn't find an app called ${spokenAppName(query)} on this phone. " +
                    "It isn't installed, or the name was misheard. Ask the user which app they meant (ask_user)" +
                    (if (close.isNotEmpty()) " — closest installed: ${close.joinToString(", ")}" else "") +
                    ". Do NOT open an app store, sign in or install anything unless the user asked to install an app — " +
                    "if they did, use install_app (never drive the Play Store by hand).",
                    JSONObject().put("notInstalled", query), needsDisambiguation = true, recoverable = false)
            }
        }
        // D8 / J-11: an app the user said "never touch" (or the app's own deny list) isn't opened at all — and the model is
        // told not to reach it another way (the home-screen icon); inside it the loop would blank and block it anyway.
        launchRefusal(app.packageName, app.label)?.let {
            return ToolResult(false, "$it Don't open it any other way either — tell the user that.", recoverable = false)
        }
        val intent = AppResolver.launchIntent(ctx.appContext, app.packageName)
            ?: return ToolResult(false, "No launch intent for ${app.label}.")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val prevFg = ctx.controller?.foregroundPackage()   // ACTIVITY-level, to compare like with like in verify()
        return try {
            ctx.appContext.startActivity(intent)
            ToolResult(true, "Opened ${app.label}", JSONObject().put("package", app.packageName).put("prevFg", prevFg ?: ""),
                channel = "intent")
        } catch (t: Throwable) {
            ToolResult(false, "Failed to open ${app.label}: ${t.message}")
        }
    }

    override suspend fun verify(
        args: JSONObject, ctx: ToolContext, before: UiSnapshot?, after: UiSnapshot?, result: ToolResult
    ): VerificationResult {
        val pkg = result.data?.optString("package").orEmpty()
        if (pkg.isEmpty()) return VerificationResult(false, result.message)
        val prevFg = result.data?.optString("prevFg")?.ifBlank { null }
        val (ok, fg) = Verify.pollLaunched(ctx.controller, pkg, prevFg = prevFg, attempts = 16, intervalMs = 300L)
        return VerificationResult(ok, if (ok) "foreground is $fg" else "foreground is $fg, expected $pkg")
    }
}
