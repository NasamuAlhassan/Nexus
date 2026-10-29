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
    private val DEFAULT_PACKAGES = listOf("com.mtngh.mymtn", "com.mtngh.*", "com.mtn.momo*", "com.mtn.mobilemoney*", "com.mtn.mymtn*", "com.mtn.*")

    /** A MoMo transaction or MoMo balance in words: the requests app mode takes. */
    val GOAL = Regex("(?i)\\b(?:send|transfer|pay|paid|withdraw|cash ?out|momo|mobile money|airtime|credit|bundle|data|balance|recharge|top ?up|" +
        "just ?(?:4|for) ?(?:u|you)|mashup|offers?|kokrokoo|midnight|how much money|money (?:do i have|left|have i got))\\b")

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

    private val REF_FIELD = Regex("(?i)\\b(?:reference|ref|note|narration|description|purpose|remark|message)\\b")
    private val NO_REF = Regex("(?i)\\b(?:no reference|none|nothing|leave it (?:empty|blank)|no note|skip it)\\b")

    /**
     * The planner typing into a reference / note field of MTN's app something the user never said → the question to ask instead
     * (owner, 28 Oct 2026: "the reference" — it typed "Transfer" by itself). Null when it's fine (said, or not a reference field).
     */
    fun referenceQuestion(tool: String, args: org.json.JSONObject, screen: org.json.JSONObject, said: String): String? {
        if (tool != "type") return null
        val app = screen.optString("app")
        if (!(app.startsWith("com.mtngh.") || app.startsWith("com.mtn."))) return null
        val id = args.optInt("element_id", -1)
        val els = screen.optJSONArray("elements") ?: return null
        val label = (0 until els.length()).map { els.getJSONObject(it) }.firstOrNull { it.optInt("id", -2) == id }?.optString("label").orEmpty()
        if (!REF_FIELD.containsMatchIn(label)) return null
        val text = args.optString("text").trim()
        if (text.isEmpty() || said.contains(text, ignoreCase = true) || NO_REF.containsMatchIn(said)) return null
        return "What should the reference say? You can say none."
    }

    /** The planner's guide for a money request in app mode, or null when app mode is off or the request is not about money. */
    fun ruleFor(goal: String): String? = if (GOAL.containsMatchIn(goal) && useApp()) rule() + appName() + (who(goal)?.let { " $it" } ?: "") else null

    /** The installed app's own name for open_app (the planner guessed "MTN Mobile Money" and found nothing). */
    private fun appName(): String {
        val ctx = com.tekyerema.agent.AgentEngine.appContext() ?: return ""
        val pkg = installed(ctx) ?: return ""
        val label = try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Throwable) { return "" }
        return " The MTN app on this phone is \"$label\": open it with open_app({\"app\":\"$label\"}) — no other name."
    }

    @Volatile private var whoCache: Pair<String, String?>? = null

    /**
     * WHO, settled on the phone before the planner asks (28 Oct 2026, Infinix: "send 5 cedis to my wife" was asked "What is your
     * wife's phone number or contact name?" three times while "call my wife" found the saved "My Wife" at once). The person named
     * in [goal] looked up in the contacts: one match → its name and number are the facts; several → ask which, by name; none → ask
     * for the number. Null when no person is named (a number, or nobody).
     */
    internal fun who(goal: String, lookup: ((String) -> List<com.tekyerema.android.ContactResolver.Contact>)? = null): String? {
        whoCache?.let { (g, line) -> if (g == goal && lookup == null) return line }
        val ask = try { MoneyAsk.parse(goal) } catch (_: Throwable) { null }
        val name = ask?.name?.trim()?.takeIf { it.isNotEmpty() && ask.number == null } ?: return null
        val find = lookup ?: { n: String -> com.tekyerema.agent.AgentEngine.appContext()?.let { c ->
            try { com.tekyerema.android.ContactResolver.resolve(c, n) } catch (_: Throwable) { emptyList() } } ?: emptyList() }
        val hits = find(name).distinctBy { it.number.filter { c -> c.isDigit() }.takeLast(9) }
        val line = when (hits.size) {
            0 -> "WHO: \"$name\" is not in the phone's contacts — ask_user for the phone number."
            1 -> "WHO is settled: \"$name\" is the saved contact ${hits[0].name}, number ${hits[0].number} — type that number; do not ask who."
            else -> "WHO: \"$name\" matches several contacts (${hits.take(4).joinToString(", ") { it.name }}) — ask_user which one, by name."
        }
        if (lookup == null) whoCache = goal to line
        return line
    }

    /** `money.app.rule` replaces the built-in guide (so the screen labels can be corrected without a build). */
    fun rule(): String = AppConfig.string("money.app.rule", "").ifBlank { RULE }

    const val RULE = "MTN APP (online money — the demo's way): do Mobile Money in MTN's own app, not USSD and not run_ussd. " +
        "BEFORE opening it, have every fact: for a send, WHO (a saved contact or a number) and HOW MUCH — ask_user once for what is " +
        "missing; for airtime or a bundle, how much and for whom (self by default). Open the app with open_app, wait for its home " +
        "screen, and go straight to the task's own button — in myMTN: the 'MoMo' tab, then 'Send MoMo' for a send (recipient number, " +
        "amount, reference, then Continue), 'Show MoMo balance' for the balance; elsewhere Send money / Transfer, Airtime & " +
        "Bundles, Balance — never browse offers, " +
        "banners, ads or 'Explore'. Dismiss a pop-up or promo with its close / Skip / Not now control, never its main button. " +
        "Fill each field from the facts, choosing the network/wallet the app offers for MTN. A REFERENCE / note / description field is " +
        "the user's words only: if they gave none, ask_user what it should say (they may say none) — never invent one. Before the " +
        "Continue / Send / Next that follows the filled form (the PIN comes right after it), ask_user to confirm with the number or " +
        "name, the amount and the reference read back. When the app shows the recipient's " +
        "NAME and the amount on a summary screen, ask_user to confirm with the name, number and amount read back, before the final " +
        "Send/Confirm; a different name than expected → tell the user and stop. The PIN is the user's: when the app asks for it, " +
        "say so and stop — never type, read or guess it. Balance: the app may hide it behind an eye / 'tap to view' control that " +
        "asks for the PIN — then the user enters it. If the app logs out, shows an error or won't load, tell the user plainly " +
        "and offer the USSD way (*170#)."
}
