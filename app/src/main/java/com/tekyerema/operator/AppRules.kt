package com.tekyerema.operator

import com.tekyerema.android.AppResolver
import com.tekyerema.policy.PolicyConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * The user's rule for one app (D8 "user-editable per-app permissions"). Declared from the least to the most strict, so a
 * later entry is stricter ([AppRules.rank]); no rule at all is "Normal".
 */
enum class AppRule {
    /** Guide only: Tɛkyerɛma reads the app and explains what to tap, but never taps or types there (J-11 guideOnlyApps). */
    GUIDE,
    /** Never touch: not opened, not operated, not read — its screen and notifications stay out of the model (J-11 denyApps). */
    NEVER
}

/** One rule: the package, the app's name when the rule was made (so an uninstalled app is still named), and the rule. */
data class AppRuleEntry(val pkg: String, val label: String, val rule: AppRule)

/**
 * Per-app rules the user sets by voice ("never touch WhatsApp", "only guide me in Chrome") or in Settings › Control ›
 * App rules — pure (unit-tested). They live in [OperatorPolicy] (persisted with the operator's other choices, every
 * change in the activity log) and reach every layer through [PolicyConfig.denyApps] / [PolicyConfig.guideOnlyApps],
 * which return config ∪ user: a user can only make things STRICTER than the app's own policy, never remove a config
 * entry.
 *
 * Emergencies stay exempt: no rule may cover Tɛkyerɛma itself or the phone app / call screen an emergency call goes
 * through ([protectedKind]), and the emergency tool's help text ignores the user's rules (ToolContext.emergency).
 */
object AppRules {
    const val OWN_PACKAGE = "com.tekyerema"

    /**
     * The phone app, its call screen, telecom and the emergency dialer on stock Android, Google and Samsung phones. The
     * phone's own default dialer is added at run time ([phoneExtra], learned by the Android side) — a rule there could
     * stop the agent pressing Call for someone who asked for help.
     */
    val PHONE_PATH = setOf(
        "com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer",
        "com.android.phone", "com.android.server.telecom", "com.android.incallui", "com.samsung.android.incallui",
        "com.android.emergency", "com.google.android.apps.safetyhub", "com.samsung.android.app.telephonyui"
    )

    /** This phone's default dialer and whatever answers ACTION_DIAL (set on the phone; empty in unit tests). */
    @Volatile var phoneExtra: Set<String> = emptySet()

    enum class Protected { SELF, PHONE }

    /** Why [pkg] can't have a rule, or null when it can. */
    fun protectedKind(pkg: String): Protected? = when {
        pkg == OWN_PACKAGE || pkg.startsWith("$OWN_PACKAGE.") -> Protected.SELF
        pkg in PHONE_PATH || pkg in phoneExtra -> Protected.PHONE
        else -> null
    }

    // ------------------------------------------------------------------ strictness
    /** 0 = Normal, then [AppRule] in declaration order. */
    fun rank(r: AppRule?): Int = r?.let { it.ordinal + 1 } ?: 0

    fun stricter(a: AppRule?, b: AppRule?): AppRule? = if (rank(a) >= rank(b)) a else b

    /** Going from [from] to [to] gives the agent MORE room — asked first (a spoken yes, or a Settings dialog). */
    fun isLoosening(from: AppRule?, to: AppRule?): Boolean = rank(to) < rank(from)

    /** The app's own (config) rule for [pkg]. */
    fun configRule(pkg: String, deny: Set<String>, guide: Set<String>): AppRule? = when (pkg) {
        in deny -> AppRule.NEVER
        in guide -> AppRule.GUIDE
        else -> null
    }

    // ------------------------------------------------------------------ storage (one "op.*" key, a JSON list)
    fun encode(rules: Collection<AppRuleEntry>): String? = if (rules.isEmpty()) null else JSONArray().apply {
        rules.forEach { put(JSONObject().put("p", it.pkg).put("l", it.label).put("r", it.rule.name)) }
    }.toString()

    /** Tolerant: anything unreadable is dropped (the safe default is "no user rule" — config still applies), and so is a
     *  rule on a protected package. */
    fun decode(text: String?): LinkedHashMap<String, AppRuleEntry> {
        val out = LinkedHashMap<String, AppRuleEntry>()
        if (text.isNullOrBlank()) return out
        try {
            val a = JSONArray(text)
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val p = o.optString("p").trim()
                val r = AppRule.entries.firstOrNull { it.name == o.optString("r").trim() } ?: continue
                if (p.isEmpty() || protectedKind(p) != null) continue
                out[p] = AppRuleEntry(p, o.optString("l").trim().ifEmpty { p }, r)
            }
        } catch (_: Throwable) { out.clear() }
        return out
    }

    /** One stored change in plain past-tense words for the activity log ("put WhatsApp off limits"). Pure. */
    fun describe(before: String?, after: String?): String {
        val b = decode(before)
        val a = decode(after)
        val parts = ArrayList<String>()
        for ((p, e) in a) if (b[p]?.rule != e.rule) parts.add(when (e.rule) {
            AppRule.NEVER -> "put ${e.label} off limits (never touch)"
            AppRule.GUIDE -> "set ${e.label} to guide only"
        })
        for ((p, e) in b) if (p !in a) parts.add("allowed ${e.label} again")
        return parts.joinToString("; ").ifEmpty { "changed the app rules" }
    }

    // ------------------------------------------------------------------ gates other layers call
    /**
     * Why an app may not even be OPENED (open_app, the fast path's "open …", a tool that launches it), or null. Only
     * "never touch" stops a launch — a guide-only app is opened so the user can be guided in it. [emergency] = the
     * emergency tool's own steps: the user's rules don't apply (config ones still do).
     */
    fun openRefusal(pkg: String?, label: String, emergency: Boolean = false): String? {
        if (pkg == null) return null
        if (!emergency && OperatorPolicy.appRule(pkg) == AppRule.NEVER) return neverOpenLine(label)
        if (pkg in PolicyConfig.configDenyApps()) return "$label is on the list of apps I must not use, so I won't open it."
        return null
    }

    // ------------------------------------------------------------------ words the user hears (no counts, no emoji)
    fun name(r: AppRule?): String = when (r) {
        null -> "Normal"
        AppRule.GUIDE -> "Guide only"
        AppRule.NEVER -> "Never touch"
    }

    fun meaning(r: AppRule?): String = when (r) {
        null -> "Tɛkyerɛma may use it, and still asks before anything important."
        AppRule.GUIDE -> "Reads it and tells you what to tap, but never taps or types there."
        AppRule.NEVER -> "Won't open it, use it or read it, and skips its notifications."
    }

    fun setLine(label: String, rule: AppRule): String = when (rule) {
        AppRule.NEVER -> "Okay. I'll stay out of $label: I won't open it, use it or read it. Say “you can use $label again” to change that."
        AppRule.GUIDE -> "Okay. In $label I'll only guide you: I'll tell you what to tap, and won't tap anything myself. " +
            "Say “you can use $label again” to change that."
    }

    fun alreadyLine(label: String, rule: AppRule): String = when (rule) {
        AppRule.NEVER -> "I'm already staying out of $label."
        AppRule.GUIDE -> "I already only guide you in $label."
    }

    fun clearedLine(label: String): String = "Okay. I can use $label again. I'll still ask you before anything important."

    fun noRuleLine(label: String): String = "There's no rule for $label, so I can already use it."

    fun configLine(label: String, rule: AppRule): String = when (rule) {
        AppRule.NEVER -> "$label is off limits in the app's own settings, so that can't be changed."
        AppRule.GUIDE -> "$label is guide only in the app's own settings, so that can't be changed."
    }

    /** The question before a loosening — ends in "?" so the voice screen listens for the answer. */
    fun askLine(label: String, from: AppRule, to: AppRule?): String = when {
        from == AppRule.NEVER && to == AppRule.GUIDE -> "Right now I stay out of $label completely. Should I read it and guide you there, yes or no?"
        from == AppRule.NEVER -> "You asked me never to touch $label. Should I be allowed to use it again, yes or no?"
        else -> "Right now I only guide you in $label. Should I be allowed to act in it again, yes or no?"
    }

    fun keptLine(label: String, rule: AppRule): String = when (rule) {
        AppRule.NEVER -> "Okay, I'll keep staying out of $label."
        AppRule.GUIDE -> "Okay, I'll keep only guiding you in $label."
    }

    fun protectedLine(label: String, kind: Protected): String = when (kind) {
        Protected.SELF -> "I can't make a rule for Tɛkyerɛma itself. To stop me doing things, say “guide only”."
        Protected.PHONE -> "I can't put $label off limits: I need it to call for help in an emergency."
    }

    fun neverOpenLine(label: String): String =
        "You asked me never to touch $label, so I won't open it. Say “you can use $label again” to change that."

    fun notFoundLine(said: String): String =
        if (said.isBankWord()) "I can't find a bank app on this phone." else "I can't find an app called $said on this phone."

    /** More than one app fits: ask which, with the command to say ([example] builds it from an app's name). */
    fun whichLine(said: String, options: List<String>, example: (String) -> String): String =
        "A few apps match $said: ${DataControl.joinAnd(options.take(3))}. Say which one, like “${example(options.first())}”."

    /** "which apps are off limits?" — the user's rules, then the app's own. */
    fun listLine(user: Collection<AppRuleEntry>, configNever: List<String>, configGuide: List<String>): String {
        val never = user.filter { it.rule == AppRule.NEVER }.map { it.label }
        val guide = user.filter { it.rule == AppRule.GUIDE }.map { it.label }
        if (never.isEmpty() && guide.isEmpty() && configNever.isEmpty() && configGuide.isEmpty())
            return "There are no app rules: I can use any app, and I still ask before anything important. " +
                "Say “never touch” and an app's name to keep me out of it."
        val parts = ArrayList<String>()
        if (never.isNotEmpty()) parts.add("I stay out of ${names(never)}.")
        if (guide.isNotEmpty()) parts.add("In ${names(guide)} I only guide you.")
        if (configNever.isNotEmpty()) parts.add("The app's own settings keep me out of ${names(configNever)}.")
        if (configGuide.isNotEmpty()) parts.add("The app's own settings let me only guide you in ${names(configGuide)}.")
        if (never.isNotEmpty() || guide.isNotEmpty()) parts.add("Say “you can use” and the app's name to lift one of yours.")
        return parts.joinToString(" ")
    }

    /** Up to six names, then "and others" (a spoken list, never a count). */
    private fun names(xs: List<String>): String =
        if (xs.size <= 6) DataControl.joinAnd(xs) else xs.take(5).joinToString(", ") + " and others — see Settings, Control, App rules"

    /** The Control page row's value: "2 apps" / "None" (on screen only). */
    fun summary(userCount: Int, configCount: Int): String {
        val n = userCount + configCount
        return if (n == 0) "None" else "$n app${if (n == 1) "" else "s"}"
    }

    // ------------------------------------------------------------------ naming apps
    private val LEAD = Regex("^(?:my|the|your|this|that) ")
    private val TAIL = Regex(" (?:app|apps|application)$")

    /** "my bank app" → "bank": what the user named, without the filler (for replies). */
    fun cleanName(said: String): String {
        var s = said.trim()
        repeat(2) { s = LEAD.replace(s, "").trim() }
        s = TAIL.replace(s, "").trim()
        return s.ifEmpty { said.trim() }
    }

    private fun String.isBankWord(): Boolean = cleanName(lowercase()) in BANK_WORDS
    private val BANK_WORDS = setOf("bank", "banking", "banks")

    /**
     * Which installed app the user means (pure; [apps] = the launcher list). "my bank app" is a category: every app whose
     * name says bank — one is that app, several is a question (never a coin-flip). Everything else uses [AppResolver]'s
     * matching (aliases, split names; [tolerant] adds the fuzzy rescue for ASR-mangled names — off for a NEW rule, so
     * "don't open that link" can't put LinkedIn off limits).
     */
    fun pick(apps: List<AppResolver.AppInfo>, said: String, tolerant: Boolean = true): AppResolver.Match {
        if (said.isBankWord()) {
            // "Ecobank", "CalBank", "GCB Bank": the word is often joined on, so a plain contains.
            val hits = apps.filter { it.label.contains("bank", ignoreCase = true) }.sortedBy { it.label.lowercase() }
            return when (hits.size) {
                0 -> AppResolver.Match.None
                1 -> AppResolver.Match.One(hits[0])
                else -> AppResolver.Match.Ambiguous(hits)
            }
        }
        return AppResolver.match(apps, said, tolerant)
    }
}
