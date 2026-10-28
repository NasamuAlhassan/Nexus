package com.tekyerema.money

import android.content.Context
import com.tekyerema.core.config.AppConfig

/**
 * The finale demo's money channel (owner, 28 Oct 2026, after the judges' feedback): MTN-focused — ONLINE, money is done in MTN's own
 * app on the phone, the brain driving it; OFFLINE, the USSD menus (*170#). Until now every money request went to USSD and the planner
 * was kept out of the app (it "free-drove the myMTN app" in the 1 Oct audit). In app mode [MoneyFlow] and [com.tekyerema.agent.LocalBrain]
 * step aside for MoMo transactions and the planner gets [rule] — where to start, what to ask before opening the app, where to stop.
 *
 * `money.onlineChannel`: "app" (default) or "ussd" (the old behaviour). `money.app.packages`: the MTN apps tried, first installed wins
 * (a trailing `*` is a prefix). Airtime-balance checks stay on USSD (quick, not a transaction).
 */
object MtnApp {
    private val DEFAULT_PACKAGES = listOf("com.mtn.momo*", "com.mtn.mobilemoney*", "com.mtn.mymtn*", "com.mtn.*")

    /** A MoMo transaction or MoMo balance in words: the requests app mode takes. */
    val GOAL = Regex("(?i)\\b(?:send|transfer|pay|paid|withdraw|cash ?out|momo|mobile money|airtime|credit|bundle|data|balance|recharge|top ?up)\\b")

    @Volatile private var cached: Pair<Long, String?>? = null

    /** The installed MTN app's package, or null (checked at most once a minute). */
    fun installed(ctx: Context): String? {
        val now = System.currentTimeMillis()
        cached?.let { (at, p) -> if (now - at < 60_000L) return p }
        val wanted = AppConfig.stringList("money.app.packages", DEFAULT_PACKAGES)
        val pkgs = try {
            @Suppress("DEPRECATION") ctx.packageManager.getInstalledApplications(0).map { it.packageName }
        } catch (_: Throwable) { emptyList() }
        val hit = wanted.firstNotNullOfOrNull { w -> pkgs.firstOrNull { p -> matches(p, w) } }
        cached = now to hit
        return hit
    }

    internal fun matches(pkg: String, pattern: String): Boolean =
        if (pattern.endsWith("*")) pkg.startsWith(pattern.dropLast(1)) else pkg == pattern

    /** Online, app mode on, the app installed: MoMo transactions go to the app. Offline → USSD, always. */
    fun useApp(ctx: Context? = com.tekyerema.agent.AgentEngine.appContext()): Boolean {
        if (AppConfig.string("money.onlineChannel", "app") != "app") return false
        if (!com.tekyerema.core.NetworkMonitor.isOnline()) return false
        return ctx != null && installed(ctx) != null
    }

    /** The planner's guide for a money request in app mode, or null when app mode is off or the request is not about money. */
    fun ruleFor(goal: String): String? = if (GOAL.containsMatchIn(goal) && useApp()) rule() else null

    /** `money.app.rule` replaces the built-in guide (so the screen labels can be corrected without a build). */
    fun rule(): String = AppConfig.string("money.app.rule", "").ifBlank { RULE }

    const val RULE = "MTN APP (online money — the demo's way): do Mobile Money in MTN's own app, not USSD and not run_ussd. " +
        "BEFORE opening it, have every fact: for a send, WHO (a saved contact or a number) and HOW MUCH — ask_user once for what is " +
        "missing; for airtime or a bundle, how much and for whom (self by default). Open the app with open_app, wait for its home " +
        "screen, and go straight to the task's own button (Send money / Transfer, Airtime & Bundles, Balance) — never browse offers, " +
        "banners, ads or 'Explore'. Dismiss a pop-up or promo with its close / Skip / Not now control, never its main button. " +
        "Fill each field from the facts, choosing the network/wallet the app offers for MTN. When the app shows the recipient's " +
        "NAME and the amount on a summary screen, ask_user to confirm with the name, number and amount read back, before the final " +
        "Send/Confirm; a different name than expected → tell the user and stop. The PIN is the user's: when the app asks for it, " +
        "say so and stop — never type, read or guess it. Balance: the app may hide it behind an eye / 'tap to view' control that " +
        "asks for the PIN — then the user enters it. If the app logs out, shows an error or won't load, tell the user plainly " +
        "and offer the USSD way (*170#)."
}
