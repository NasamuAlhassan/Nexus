package com.tekyerema.agent

/**
 * Hybrid online/offline router (directive §14/§36). One brain the engine talks to; internally it prefers
 * the on-device [LocalBrain] (fast path, works offline) and escalates to a cloud brain only when the local
 * brain cannot ground the request AND the network is available.
 *
 *  - local returns a CallTool/Done → use it (fast path, no cloud round-trip, §15).
 *  - local defers (can't work it out) or the goal needs the internet:
 *      • online + a cloud brain configured → hand the WHOLE remaining task to the cloud operator
 *        (sticky, so multi-step planning stays coherent);
 *      • offline (or no cloud) → refuse honestly: "I need an internet connection for that", or nudge the
 *        user toward a simpler local phrasing. Never silently fail, never fabricate (§12/§33).
 *
 * Pure of Android (connectivity is injected as [isOnline]) → unit-tested with fake brains.
 */
class RoutingBrain(
    private val local: AgentBrain,
    private val cloud: AgentBrain?,
    /** Optional goal rewrite BEFORE handing to the cloud operator — used to translate a natural Twi
     *  request to English, since the cloud brain can't plan from Twi. Returns null to keep the goal as-is
     *  (English, or translation unavailable). Applied once per task; the (possibly translated) goal is
     *  reused for every sticky cloud step so multi-step planning stays coherent. LocalBrain still handles
     *  command-style Twi directly (and answers in Twi) — this only fires when it DEFERS to the cloud.
     *  (Kept before [isOnline] so existing trailing-lambda callers `RoutingBrain(local, cloud){online}` work.) */
    private val goalForCloud: (suspend (String) -> String?)? = null,
    /** When true (and online with a cloud brain), route EVERY request to the cloud brain — skip the on-device
     *  LocalBrain fast path entirely. Used to make the relay (Claude) the brain for all requests, not just
     *  the escalated ones. Defaults false so normal local-first behaviour is unchanged. */
    private val cloudFirst: () -> Boolean = { false },
    /** The installed app a compound goal is about, as (label, package) — or null. When the local brain defers a
     *  task that names one, its FIRST step is to open it locally (no cloud round-trip); the cloud takes over
     *  from inside the app. Injected (needs PackageManager) so this class stays Android-free. */
    private val appForGoal: ((String) -> Pair<String, String>?)? = null,
    private val isOnline: () -> Boolean
) : AgentBrain {

    companion object {
        /** C-17: the engine's history line for the previous step reports a failure, a rejection or an unverified result. */
        fun deviated(history: List<String>): Boolean {
            val last = history.lastOrNull() ?: return false
            return last.contains("verified=false") || Regex("→ (?:fail|rejected|refused|BLOCKED|NOT )").containsMatchIn(last)
        }
    }

    @Volatile private var stickyCloud = false
    @Volatile private var cloudGoal: String? = null   // cached (possibly translated) goal for the cloud

    private fun cloudCtx(ctx: BrainContext): BrainContext =
        cloudGoal?.takeIf { it != ctx.goal }?.let { ctx.copy(goal = it) } ?: ctx

    /** Lightweight Twi signal (§4.6) — Twi-specific letters or a few unambiguous stems. Pure, no Android. */
    private fun looksTwi(s: String): Boolean =
        s.any { it in "ɛɔŋʋɩ" } || Regex("\\b(frɛ\\w*|bue\\w*|kɔ|sɛ|mepɛ|kenkan|nkra|aane|daabi|wopɛ)\\b", RegexOption.IGNORE_CASE).containsMatchIn(s)

    override suspend fun decide(ctx: BrainContext): AgentDecision {
        // Cloud-first: hand EVERYTHING to the cloud brain (the relay = Claude), skipping LocalBrain. Sticky so
        // multi-step planning stays coherent; the (optional) Twi→English rewrite still runs once.
        // N-10: not on a weak connection — the on-device brain answers what it can there without a slow round-trip.
        // C-01: with the local_brain tier switched off, every request goes to the cloud operator the same way.
        val skipLocal = !RouterTiers.enabled(RouterTiers.LOCAL)
        if (!stickyCloud && (cloudFirst() || skipLocal) && cloud != null && isOnline() && (skipLocal || !isWeak())) {
            android.util.Log.i("Tekyerema", "[ROUTE] cloud-first: all requests → cloud brain '${ctx.goal.take(60)}'")
            stickyCloud = true
            if (cloudGoal == null) cloudGoal = goalForCloud?.invoke(ctx.goal)?.takeIf { it.isNotBlank() }
        }
        if (stickyCloud && cloud != null) return cloud.decide(cloudCtx(ctx))

        // C-17: the local brain's last step failed or didn't verify — a deviation from its routine plan. Hand the rest
        // of the task to the cloud operator (sticky) instead of letting the rules try the same thing again.
        if (cloud != null && isOnline() && deviated(ctx.history) &&
            com.tekyerema.core.config.AppConfig.bool("router.escalation.onDeviation", true)) {
            android.util.Log.i("Tekyerema", "[ROUTE] local step deviated → escalating to cloud brain mid-task")
            stickyCloud = true
            if (cloudGoal == null) cloudGoal = cloudGoalFor(ctx.goal)
            return cloud.decide(cloudCtx(ctx))
        }

        val d = local.decide(ctx)
        val escalatable = d is AgentDecision.CannotDo && (d.reasonClass == LocalBrain.DEFER || d.reasonClass == LocalBrain.NETWORK)
        if (!escalatable) return d   // CallTool / Done / a genuine local CannotDo

        val needsNet = (d as AgentDecision.CannotDo).reasonClass == LocalBrain.NETWORK
        if (cloud != null && isOnline()) {
            // Step one of a task about a named app: open it here — deterministic, ~0 ms instead of a round-trip.
            if (!stickyCloud && ctx.history.isEmpty()) appForGoal?.invoke(ctx.goal)?.let { (label, pkg) ->
                if (ctx.screen.optString("app") != pkg) {
                    android.util.Log.i("Tekyerema", "[ROUTE] goal names $label → opening it locally first, then the cloud brain")
                    stickyCloud = true
                    if (cloudGoal == null) cloudGoal = cloudGoalFor(ctx.goal)
                    return AgentDecision.CallTool("open_app", org.json.JSONObject().put("app", label), "local:goal names $label",
                        expects = "foreground_is: $pkg")
                }
            }
            if (!stickyCloud)   // log the escalation ONCE per task so it's visible live during the demo
                android.util.Log.i("Tekyerema", "[ROUTE] local ${d.reasonClass} '${ctx.goal.take(60)}' → escalating to cloud brain")
            stickyCloud = true
            if (cloudGoal == null) cloudGoal = cloudGoalFor(ctx.goal)
            return cloud.decide(cloudCtx(ctx))
        }
        // offline or no cloud brain configured — never silently do nothing (§A.4), and this is a FAILURE,
        // not a completion, so it must be CannotDo → status CANNOT_DO in history, never COMPLETED (§1.2).
        // The capability list is generated from the intents LocalBrain ACTUALLY handles (§1.3), so we never
        // advertise something (e.g. "check notifications") that no offline intent provides.
        return AgentDecision.CannotDo(
            if (needsNet) "network_unavailable" else "offline_unknown_command",
            if (needsNet) "I don't have internet, so I can't do that one right now."
            else "I'm offline and I don't know that command yet. Offline I can " + LocalBrain.offlineCapabilities() + "."
        )
    }

    private fun isWeak(): Boolean = try { com.tekyerema.core.NetworkMonitor.isWeak() } catch (_: Throwable) { false }

    /** The goal as the cloud should see it (computed once per task). §4.6 — do NOT replace the user's Twi with the
     *  machine translation: Khaya MT can mangle it (observed: "…tell Prince to come home" → "…asks print to clean
     *  houses") and the LLM then plans off garbage. Give the model BOTH its own reading of the Twi AND the rough
     *  translation as a hint. (Translation only fires for Twi, so `translated != null` ⇒ the goal is Twi.) If MT is
     *  unavailable but the goal is Twi, still send the raw Twi with an interpret hint. null ⇒ use the goal as-is. */
    private suspend fun cloudGoalFor(goal: String): String? {
        val translated = goalForCloud?.invoke(goal)?.takeIf { it.isNotBlank() }
        return when {
            translated != null ->
                "The user spoke Asante Twi: \"$goal\"\n" +
                "A rough machine translation (it can be WRONG — trust the Twi if they disagree): \"$translated\"\n" +
                "Interpret the Twi yourself and act on it; do NOT type it verbatim."
            looksTwi(goal) -> "Asante Twi (interpret and act, do not type verbatim): $goal"
            else -> null
        }
    }
}
