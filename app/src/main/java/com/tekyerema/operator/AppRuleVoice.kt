package com.tekyerema.operator

import android.content.Context
import com.tekyerema.android.AppResolver
import com.tekyerema.policy.PolicyConfig

/**
 * D8 by voice, on the local fast path (through [OperatorCommands], no model): "never touch WhatsApp" / "don't open my bank
 * app" / "stay out of MoMo" → Never touch; "only guide me in Chrome" / "just tell me what to do in Jiji" → Guide only;
 * "you can use WhatsApp again" → the rule is lifted; "which apps are off limits?" → the list.
 *
 * A new or stricter rule applies at once. Anything that gives the agent MORE room (lifting a rule, Never → Guide) is
 * asked first and needs a spoken yes within a minute — a misheard sentence must never open an app back up. The
 * classification, the decision and the pending question are pure (unit-tested); only [handle] touches Android (the app
 * list and this phone's dialer).
 */
object AppRuleVoice {
    // ------------------------------------------------------------------ classification (pure)
    private const val APP = "(.{2,40}?)"
    private const val TAIL = "(?: for me)?(?: again| ever| any more| anymore)?(?: please)?"
    private val NEVER_SAID = listOf(
        Regex("^(?:please )?(?:never|don't|dont|do not) (?:ever )?(?:touch|open|go into|go in to|use anything in|" +
            "do anything in) $APP$TAIL$"),
        Regex("^(?:please )?stop (?:touching|opening|going into) $APP$TAIL$"),
        Regex("^(?:please )?(?:stay|keep) (?:out of|away from) $APP$TAIL$"),
        Regex("^(?:please )?(?:make|put|set|mark) $APP (?:as )?off[- ]limits$"),
        Regex("^$APP (?:is|should be) off[- ]limits(?: (?:to|for) you)?$"),
        Regex("^you (?:can't|cannot|can not|mustn't|must not|may not|shouldn't|should not) (?:touch|open|use|go into) $APP$TAIL$")
    )
    // (No "just explain it in Twi", no "tell me what to do WITH the rice": only "in / on / inside" an app.)
    private val GUIDE_SAID = listOf(
        Regex("^(?:please )?(?:only|just) (?:guide me|tell me what to (?:do|tap|press)) (?:in|on|inside|when i'?m in|when i use) $APP$"),
        Regex("^(?:please )?(?:in|on|inside) $APP (?:only|just) (?:guide me|tell me what to (?:do|tap|press))$"),
        Regex("^(?:please )?(?:make|put|set|switch|change) $APP (?:to |on |as |into )?guide(?:-| )?only(?: mode)?$"),
        Regex("^$APP (?:is|should be) guide(?:-| )?only$"),
        Regex("^(?:please )?guide(?:-| )?only (?:in|on|for) $APP$"),
        Regex("^(?:please )?(?:don't|do not|never) (?:tap|press|type) anything (?:in|on) $APP(?: just (?:tell|guide) me(?: what to do)?)?$")
    )
    // "again" is required where the words alone could mean something else ("you can use the camera now", "allow
    // notifications", "unblock Kofi").
    private val CLEAR_SAID = listOf(
        Regex("^(?:please )?you (?:can|may) (?:use|touch|open|go into|act in|do things in|work in) $APP(?: for me)? (?:again|from now on)(?: please)?$"),
        Regex("^(?:please )?(?:remove|delete|clear|cancel|drop|lift) (?:the |my )?(?:app )?(?:rule|limit|block|restriction)s? " +
            "(?:on|for|about) $APP$"),
        Regex("^$APP (?:is not|isn't|is no longer) off[- ]limits(?: any more| anymore)?$"),
        Regex("^(?:please )?stop (?:only )?guiding me (?:in|on) $APP$"),
        Regex("^(?:please )?allow $APP again$")
    )
    private val LIST_SAID = listOf(
        Regex("^(?:which|what) apps? (?:are|is) (?:off[- ]limits|guide(?:-| )?only|blocked|banned|restricted)(?: (?:for|to) you)?$"),
        Regex("^(?:which|what) apps? (?:can't|cannot|can not|won't|will not|don't|do not) you (?:touch|open|use|go into)$"),
        Regex("^(?:which|what) apps? (?:do|will) you (?:stay out of|not touch|avoid|keep out of)$"),
        Regex("^(?:what are|list|tell me|read me|read out) (?:my |the |your )?app rules$"),
        Regex("^(?:which|what) apps? (?:have i|did i) (?:blocked|banned|put off[- ]limits|made off[- ]limits)$")
    )

    /** Words that aren't an app: "don't touch my phone" is Guide-only MODE, "never touch my money" the MONEY switch, and
     *  "don't open it" / "stay out of this" are about something else — none of those may become an app rule. */
    private val NOT_AN_APP = Regex("^(?:(?:my|the|this|your) )?(?:phone|mobile|device|money|cash|data|settings|stuff|things|" +
        "messages|calls|screen|internet|web|cloud|anything|everything|anything else|nothing|it|that|this|them|those|these|there|" +
        "here|me|people|anyone)$")
    /** A request that goes on after the app ("don't open WhatsApp, open Telegram") isn't a rule. */
    private val NOT_ONE_NAME = Regex("\\b(?:and|but|then|instead|open|call|send|text|message|play|search|tell|use|because|so)\\b")

    private fun app(m: MatchResult?): String? = m?.groupValues?.get(1)?.trim()
        ?.takeIf { it.split(' ').size <= 5 && !NOT_AN_APP.matches(it) && !NOT_ONE_NAME.containsMatchIn(it) }

    /** [g] = OperatorCommands.normalize'd words; null = not an app-rule command. */
    fun classify(g: String): OpCommand? {
        if (LIST_SAID.any { it.matches(g) }) return OpCommand.AppRulesList
        for (r in NEVER_SAID) app(r.matchEntire(g))?.let { return OpCommand.AppRuleSet(it, AppRule.NEVER) }
        for (r in GUIDE_SAID) app(r.matchEntire(g))?.let { return OpCommand.AppRuleSet(it, AppRule.GUIDE) }
        for (r in CLEAR_SAID) app(r.matchEntire(g))?.let { return OpCommand.AppRuleSet(it, null) }
        return null
    }

    /** The command to say for [rule] on [label] — for "which one?" examples. */
    fun phrase(rule: AppRule?, label: String): String = when (rule) {
        AppRule.NEVER -> "never touch $label"
        AppRule.GUIDE -> "only guide me in $label"
        null -> "you can use $label again"
    }

    // ------------------------------------------------------------------ the pending "yes or no" (pure state)
    /** A loosening waiting for the user's yes: [to] = the user's rule after it (null = none), [after] = what will apply
     *  (the app's own rule may still hold), [from] = what applies now. */
    data class Pending(val pkg: String, val label: String, val from: AppRule, val to: AppRule?, val after: AppRule?, val at: Long)

    @Volatile private var pending: Pending? = null
    private val YES = Regex("^(?:yes|yeah|yep|yes please|aane|ok|okay|sure|go ahead|do it|confirm|allow it|" +
        "yes (?:you can|go ahead|do it|allow it|please|that's fine|that is fine))$")
    private val NO = Regex("^(?:no|nope|cancel|stop|don't|do not|never mind|daabi|keep it|leave it)\\b.*$")

    /** [now] is a monotonic clock (elapsedRealtime on the phone), so a clock change can't reopen the question. */
    fun pendingAt(now: Long): Pending? = pending?.takeIf { now - it.at in 0..OperatorCommands.CONFIRM_WINDOW_MS }

    internal fun clearPending() { pending = null }

    /**
     * The answer to a pending loosening, or null when nothing is pending or the words aren't an answer — then the
     * question lapses (nothing loosened) and the words are handled as usual.
     */
    fun answer(raw: String, now: Long): String? {
        val p = pendingAt(now) ?: run { pending = null; return null }
        val g = OperatorCommands.normalize(raw)
        return when {
            YES.matches(g) -> {
                pending = null
                if (OperatorPolicy.setAppRule(p.pkg, p.label, p.to)) doneLine(p.label, p.after)
                else AppRules.protectedLine(p.label, AppRules.protectedKind(p.pkg) ?: AppRules.Protected.PHONE)
            }
            NO.matches(g) -> { pending = null; AppRules.keptLine(p.label, p.from) }
            else -> { pending = null; null }
        }
    }

    // ------------------------------------------------------------------ the decision (pure)
    /**
     * What [cmd] does, and the line to say. [apps] = the launcher list; [configDeny]/[configGuide] = the app's own lists;
     * [labelOf] names a config package. A tightening is applied here; a loosening only sets the pending question.
     */
    fun decide(
        cmd: OpCommand,
        apps: List<AppResolver.AppInfo>,
        now: Long,
        configDeny: Set<String> = PolicyConfig.configDenyApps(),
        configGuide: Set<String> = PolicyConfig.configGuideOnlyApps(),
        labelOf: (String) -> String = { pkg -> apps.firstOrNull { it.packageName == pkg }?.label ?: pkg }
    ): String? {
        val op = OperatorPolicy
        if (cmd == OpCommand.AppRulesList)
            return AppRules.listLine(op.appRules.values.sortedBy { it.label.lowercase() },
                configDeny.map(labelOf).sorted(), (configGuide - configDeny).map(labelOf).sorted())
        val set = cmd as? OpCommand.AppRuleSet ?: return null
        val said = AppRules.cleanName(set.app)
        // Lifting a rule: the user's own rules are searched first, so an app that has since been uninstalled can be freed.
        val pool = if (set.rule == null) op.appRules.values.map { AppResolver.AppInfo(it.label, it.pkg) } + apps else apps
        val app = when (val m = AppRules.pick(pool.distinctBy { it.packageName }, set.app, tolerant = set.rule == null)) {
            is AppResolver.Match.One -> m.app
            is AppResolver.Match.Ambiguous -> return AppRules.whichLine(said, m.options.map { it.label }.distinct()) { phrase(set.rule, it) }
            // "never touch Twitter" with no Twitter: say so. The looser forms ("just tell me what to do in the morning",
            // "you can use the camera again" with no such app or rule) aren't clearly about an app — left to the rest.
            AppResolver.Match.None -> return if (set.rule == AppRule.NEVER) AppRules.notFoundLine(said) else null
        }
        AppRules.protectedKind(app.packageName)?.let { return AppRules.protectedLine(app.label, it) }
        val want = set.rule
        val config = AppRules.configRule(app.packageName, configDeny, configGuide)
        // What applies now and what would apply after: the stricter of the app's own rule and the user's.
        val before = AppRules.stricter(config, op.appRule(app.packageName))
        val after = AppRules.stricter(config, want)
        return when {
            AppRules.rank(after) > AppRules.rank(before) -> {   // stricter: at once
                pending = null
                op.setAppRule(app.packageName, app.label, want)
                AppRules.setLine(app.label, want!!)
            }
            AppRules.rank(after) < AppRules.rank(before) -> {   // more room for the agent: asked first
                OperatorCommands.clearDelete()   // one question at a time
                pending = Pending(app.packageName, app.label, before!!, want, after, now)
                AppRules.askLine(app.label, before, after)
            }
            // Nothing would change. The app's own rule can't be loosened by anything the user says.
            config != null && AppRules.rank(want) < AppRules.rank(config) -> AppRules.configLine(app.label, config)
            want == null -> AppRules.noRuleLine(app.label)
            else -> AppRules.alreadyLine(app.label, want)
        }
    }

    /** What the user hears once a rule changed: [after] = the rule that applies now (config included). */
    private fun doneLine(label: String, after: AppRule?): String = if (after == null) AppRules.clearedLine(label) else AppRules.setLine(label, after)

    // ------------------------------------------------------------------ Android
    /** Learn this phone's own dialer (the emergency call path), so no rule can cover it. Cheap; safe to call often. */
    fun learnPhonePath(ctx: Context) {
        val found = HashSet<String>()
        try {
            (ctx.getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager)?.defaultDialerPackage?.let { found.add(it) }
        } catch (_: Throwable) {}
        try {
            android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:112"))
                .resolveActivity(ctx.packageManager)?.packageName?.takeIf { it != "android" }?.let { found.add(it) }
        } catch (_: Throwable) {}
        if (found.isNotEmpty()) AppRules.phoneExtra = AppRules.phoneExtra + found
    }

    /** [cmd] on the phone: the launcher list (re-read once when the name isn't there — it may have just been installed). */
    fun handle(ctx: Context, cmd: OpCommand, monotonic: Long): String? {
        learnPhonePath(ctx)
        val apps = try { AppResolver.launchable(ctx) } catch (_: Throwable) { emptyList() }
        val labelOf: (String) -> String = { pkg -> labelFor(ctx, pkg, apps) }
        val line = decide(cmd, apps, monotonic, labelOf = labelOf)
        if (cmd !is OpCommand.AppRuleSet || (line != null && !line.startsWith("I can't find"))) return line
        val fresh = try { AppResolver.launchable(ctx, fresh = true) } catch (_: Throwable) { return line }
        return decide(cmd, fresh, monotonic, labelOf = labelOf)
    }

    /** An app's name for a package (the launcher list, else the package manager, else the package id). */
    fun labelFor(ctx: Context, pkg: String, apps: List<AppResolver.AppInfo>): String =
        apps.firstOrNull { it.packageName == pkg }?.label ?: try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().takeIf { it.isNotBlank() }
        } catch (_: Throwable) { null } ?: pkg
}
