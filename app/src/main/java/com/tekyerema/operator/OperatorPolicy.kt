package com.tekyerema.operator

import com.tekyerema.policy.CapabilityScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * CONTRACT (docs/agent/AGENTIC_ARCHITECTURE.md §Sovereignty) — the user/operator's standing choices that every layer
 * honours: how much the agent may do on its own, which providers and models it may use, what it may spend, and whether
 * remote control is allowed. No setting can lower the safety floor (confirmations for consequential actions, Secure
 * Input, the deny list) — these only restrict further.
 *
 * Workstream C: backed by the app's preferences through [OperatorStore] (initialised in TekyeremaApp before anything
 * reads it), changed by Settings › Control and by voice ([OperatorCommands]), and read live: a change applies to the
 * very next decision. Who reads what:
 *  - [com.tekyerema.policy.PolicyEngine]: [executionMode], [revokedCapabilities], [cloudUsable] (modes and scopes only
 *    ever restrict — see PolicyEngine.restrict).
 *  - The router (Workstream B): [cloudAllowed], [providers] (+ order), [weakModelPolicy], [budgetExhausted].
 *    [com.tekyerema.agent.RouterTiers] already drops the cloud tiers when [cloudUsable] is false.
 *  - The MCP server (Workstream D): [remoteControlEnabled], [revokedScopes].
 *  - The agent loop: [stepLimit] (maxStepsPerTask).
 *  - Everything that reads or drives another app (the loop's ScreenPrivacy.appRule, open_app, the fast path, app-driving
 *    tools, the MCP screen view): [appRules], through PolicyConfig.denyApps / guideOnlyApps (config ∪ user).
 * Usage is recorded in ONE place — [noteModelCall], called by Telemetry.noteLlm for every model call — so brains must
 * not call [recordUsage] again (that would count every call twice; `OperatorUsageSingleSiteTest` guards it).
 */
enum class ExecutionMode {
    /** Explains what to do; never touches the phone. */
    GUIDE_ONLY,
    /** Asks before every step that changes anything. */
    ASK_EVERY_STEP,
    /** Acts on its own; asks before anything consequential (the default and the floor). */
    STANDARD
}

/** May a weaker fallback model take over a complex task? */
enum class WeakModelPolicy { NEVER, ASK, ALLOW }

/**
 * What the user can switch off, in their words. Finer than [CapabilityScope]: a WhatsApp "Send" tap and send_sms are
 * both MESSAGES, and a call is CALLS although both are COMMS_SEND ([Capabilities] classifies each action). Switching
 * one off only ever BLOCKS; emergency help is never switched off.
 */
enum class Capability(val scope: CapabilityScope?) {
    MESSAGES(CapabilityScope.COMMS_SEND),
    CALLS(CapabilityScope.COMMS_SEND),
    MONEY(CapabilityScope.FINANCIAL),
    SETTINGS(CapabilityScope.SETTINGS),
    WEB(CapabilityScope.WEB)
}

/** Where the choices live: the app's preferences on the phone ([PrefsOperatorStore]), a map in unit tests. */
interface OperatorStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/** In-memory store (unit tests; also the fallback before [OperatorPolicy.init]). */
class MemoryOperatorStore : OperatorStore {
    val values = HashMap<String, String>()
    @Synchronized override fun get(key: String): String? = values[key]
    @Synchronized override fun put(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
}

object OperatorPolicy {
    /** Provider ids the router knows (models.json `models.planner.chain`). */
    val KNOWN_PROVIDERS = listOf("groq", "gemini")
    const val MIN_STEPS = 5
    const val MAX_STEPS = 60
    const val DEFAULT_STEPS = 30

    @Volatile private var store: OperatorStore? = null
    private val _changes = MutableStateFlow(0L)
    /** Bumped on every change, so screens can re-read. */
    val changes: StateFlow<Long> get() = _changes

    // ------------------------------------------------------------------ the choices (backing fields + persisted setters)
    @Volatile private var mode = ExecutionMode.STANDARD
    @Volatile private var cloud = true
    @Volatile private var providerOrder: List<String> = KNOWN_PROVIDERS
    @Volatile private var weak = WeakModelPolicy.ASK
    @Volatile private var steps = DEFAULT_STEPS
    @Volatile private var stepsChosen = false
    @Volatile private var capCalls = 0
    @Volatile private var capTokens = 0
    @Volatile private var remote = false
    @Volatile private var revoked: Set<Capability> = emptySet()
    @Volatile private var schedules = true
    @Volatile private var alerts = true
    @Volatile private var routines = true
    @Volatile private var retentionDays = 0
    @Volatile private var appRuleMap: Map<String, AppRuleEntry> = emptyMap()

    var executionMode: ExecutionMode
        get() = mode
        set(v) { mode = v; persist(K_MODE, v.name) }

    /** false = nothing leaves the phone (on-device answers and actions only). */
    var cloudAllowed: Boolean
        get() = cloud
        set(v) { cloud = v; persist(K_CLOUD, v.toString()) }

    /** Providers the router may use, in order of preference (known ids only, no repeats; empty = none). */
    var providers: List<String>
        get() = providerOrder
        set(v) { providerOrder = normalizeProviders(v); persist(K_PROVIDERS, providerOrder.joinToString(",")) }

    var weakModelPolicy: WeakModelPolicy
        get() = weak
        set(v) { weak = v; persist(K_WEAK, v.name) }

    /** The most steps one task may take (5..60). Until the user picks one, the configured limit applies ([stepLimit]). */
    var maxStepsPerTask: Int
        get() = steps
        set(v) { steps = v.coerceIn(MIN_STEPS, MAX_STEPS); stepsChosen = true; persist(K_STEPS, steps.toString()) }

    /** 0 = no cap. When reached, the agent falls back to on-device only (never a silent provider switch). */
    var dailyCloudCallCap: Int
        get() = capCalls
        set(v) { capCalls = v.coerceAtLeast(0); persist(K_CAP_CALLS, capCalls.toString()) }

    var dailyTokenCap: Int
        get() = capTokens
        set(v) { capTokens = v.coerceAtLeast(0); persist(K_CAP_TOKENS, capTokens.toString()) }

    var remoteControlEnabled: Boolean
        get() = remote
        set(v) { remote = v; persist(K_REMOTE, v.toString()) }

    /** The capabilities the user switched off ([Capability]). */
    var revokedCapabilities: Set<Capability>
        get() = revoked
        set(v) { revoked = v.toSet(); persist(K_REVOKED, revoked.joinToString(",") { it.name }) }

    /** Switch [c] off / back on in one step (a Settings toggle and a voice command at once can't undo each other). */
    @Synchronized fun revoke(c: Capability) { revokedCapabilities = revoked + c }
    @Synchronized fun restore(c: Capability) { revokedCapabilities = revoked - c }

    /**
     * Contract view of [revokedCapabilities] as scopes: a scope counts as revoked when every capability under it is off
     * (COMMS_SEND = messages AND calls). Setting it REPLACES the whole set — every capability under the scopes given is
     * off, every other one on — so a caller that only wants to add a restriction should use [revoke].
     */
    var revokedScopes: Set<CapabilityScope>
        get() = CapabilityScope.entries.filter { s ->
            val under = Capability.entries.filter { it.scope == s }
            under.isNotEmpty() && under.all { it in revoked }
        }.toSet()
        set(v) { revokedCapabilities = Capability.entries.filter { it.scope in v }.toSet() }

    /** Schedules (A-13) and proactive read-outs (A-11) may run by themselves. */
    var schedulesEnabled: Boolean
        get() = schedules
        set(v) { schedules = v; persist(K_SCHEDULES, v.toString()) }

    /** "Tell me when Ama messages" rules (A-12) may announce by themselves. */
    var messageAlertsEnabled: Boolean
        get() = alerts
        set(v) { alerts = v; persist(K_ALERTS, v.toString()) }

    /** Taught routines ("when I say good morning …", H-02) may run. */
    var routinesEnabled: Boolean
        get() = routines
        set(v) { routines = v; persist(K_ROUTINES, v.toString()) }

    /** How long history and the activity log are kept, in days; 0 = the app's default. Can only SHORTEN it ([retentionDays]). */
    var historyRetentionDays: Int
        get() = retentionDays
        set(v) { retentionDays = v.coerceAtLeast(0); persist(K_RETENTION, retentionDays.toString()) }

    /**
     * D8: the user's own per-app rules (package → [AppRuleEntry]), on top of the app's config lists — PolicyConfig.denyApps /
     * guideOnlyApps return the union, so these only ever make things stricter than config ([AppRules]).
     */
    val appRules: Map<String, AppRuleEntry> get() = appRuleMap

    /** The user's rule for [pkg] (config rules not included — see PolicyConfig for the union). */
    fun appRule(pkg: String?): AppRule? = pkg?.let { appRuleMap[it]?.rule }

    /** Packages the user gave [rule] (never a protected package — see [AppRules.protectedKind]). */
    fun userApps(rule: AppRule): Set<String> =
        appRuleMap.values.filter { it.rule == rule && AppRules.protectedKind(it.pkg) == null }.mapTo(HashSet()) { it.pkg }

    /**
     * Set [pkg]'s rule ([rule] null = remove it). Tightening needs nothing more; a LOOSENING must have been confirmed by
     * the caller first (a spoken yes — AppRuleVoice — or the Settings dialog). Refused (false) for Tɛkyerɛma itself and
     * the emergency call path. One synchronized step, so a Settings tap and a voice command can't undo each other.
     */
    @Synchronized fun setAppRule(pkg: String, label: String, rule: AppRule?): Boolean {
        val p = pkg.trim()
        if (p.isEmpty() || AppRules.protectedKind(p) != null) return false
        val next = LinkedHashMap(appRuleMap)
        if (rule == null) next.remove(p) else next[p] = AppRuleEntry(p, label.trim().ifEmpty { p }, rule)
        appRuleMap = next
        persist(K_APP_RULES, AppRules.encode(next.values))
        return true
    }

    // ------------------------------------------------------------------ derived rules
    /** The cloud may be used right now: the user allows it and today's budget isn't spent. */
    fun cloudUsable(now: Long = System.currentTimeMillis()): Boolean = cloud && !budgetExhausted(now)

    /** May the router use [provider] right now? */
    fun providerAllowed(provider: String, now: Long = System.currentTimeMillis()): Boolean =
        cloudUsable(now) && provider.lowercase() in providerOrder

    /** The step limit for a task: the user's choice when they made one, else [configured]; always within 5..60. */
    fun stepLimit(configured: Int): Int = (if (stepsChosen) steps else configured).coerceIn(MIN_STEPS, MAX_STEPS)

    /** A catalog feature the user switched off here (FeatureFlags consults this: a user can switch off, never on). */
    fun featureSwitchedOff(featureId: String): Boolean = when (featureId) {
        "A-13", "A-11" -> !schedules
        "A-12" -> !alerts
        "H-02" -> !routines
        else -> false
    }

    /** The history/audit retention to use given the app's [configuredDays]: the user may only shorten it (a configured
     *  0 or less is left exactly as configured). */
    fun retentionFor(configuredDays: Int): Int =
        if (configuredDays > 0 && retentionDays in 1 until configuredDays) retentionDays else configuredDays

    /** What the policy engine needs, read once per decision. */
    data class View(
        val mode: ExecutionMode = ExecutionMode.STANDARD,
        val revoked: Set<Capability> = emptySet(),
        val cloudAllowed: Boolean = true,
        val budgetSpent: Boolean = false
    ) {
        val cloudUsable: Boolean get() = cloudAllowed && !budgetSpent
    }

    fun view(now: Long = System.currentTimeMillis()): View = View(mode, revoked, cloud, budgetExhausted(now))

    // ------------------------------------------------------------------ usage and budgets (per local day, persisted)
    data class ProviderUsage(val calls: Int, val tokens: Long)

    private var usageDay = Long.MIN_VALUE
    private val usage = LinkedHashMap<String, ProviderUsage>()
    /** Offset from UTC in ms at [now] (test seam; the phone's time zone by default). */
    @Volatile internal var zoneOffset: (Long) -> Int = { java.util.TimeZone.getDefault().getOffset(it) }

    /** The model that answered most recently ("groq/openai/gpt-oss-120b", "gemini"), for the audit's "decided by". */
    @Volatile var lastModel: String? = null; private set

    fun dayOf(now: Long): Long = Math.floorDiv(now + zoneOffset(now), 86_400_000L)

    @Synchronized fun recordUsage(provider: String, tokenCount: Int, now: Long = System.currentTimeMillis()) {
        rollOver(now)
        val id = providerId(provider)
        val u = usage[id] ?: ProviderUsage(0, 0)
        usage[id] = ProviderUsage(u.calls + 1, u.tokens + tokenCount.coerceAtLeast(0))
        persist(K_USAGE, usageJson(), notify = false)
        _changes.value = _changes.value + 1
    }

    /** Today's totals: (calls, tokens). */
    @Synchronized fun usageToday(now: Long = System.currentTimeMillis()): Pair<Int, Long> {
        rollOver(now)
        return usage.values.sumOf { it.calls } to usage.values.sumOf { it.tokens }
    }

    /** Today's usage per provider id, in the order first used. */
    @Synchronized fun usageByProvider(now: Long = System.currentTimeMillis()): Map<String, ProviderUsage> {
        rollOver(now)
        return LinkedHashMap(usage)
    }

    @Synchronized fun budgetExhausted(now: Long = System.currentTimeMillis()): Boolean {
        val (calls, tokens) = usageToday(now)
        return (capCalls > 0 && calls >= capCalls) || (capTokens > 0 && tokens >= capTokens)
    }

    /**
     * Every model call reports here (Telemetry.noteLlm): an answered call ([tokens] non-null) counts toward today's usage;
     * a failed attempt (timeout, 429) costs nothing at the provider and is not counted.
     */
    fun noteModelCall(providerModel: String, tokens: Int?, now: Long = System.currentTimeMillis()) {
        if (tokens == null) return
        lastModel = providerModel
        recordUsage(providerModel, tokens, now)
    }

    private fun rollOver(now: Long) {
        val d = dayOf(now)
        if (d != usageDay) { usageDay = d; usage.clear() }
    }

    /** "groq/openai/gpt-oss-120b" → "groq". */
    fun providerId(provider: String): String = provider.substringBefore('/').trim().lowercase().ifEmpty { "unknown" }

    private fun usageJson(): String {
        val p = JSONObject()
        usage.forEach { (k, v) -> p.put(k, org.json.JSONArray().put(v.calls).put(v.tokens)) }
        return JSONObject().put("day", usageDay).put("p", p).toString()
    }

    private fun loadUsage(text: String?) {
        usage.clear(); usageDay = Long.MIN_VALUE
        if (text.isNullOrBlank()) return
        try {
            val o = JSONObject(text)
            usageDay = o.optLong("day", Long.MIN_VALUE)
            val p = o.optJSONObject("p") ?: return
            for (k in p.keys()) p.optJSONArray(k)?.let { a -> usage[k] = ProviderUsage(a.optInt(0), a.optLong(1)) }
        } catch (_: Throwable) { usage.clear(); usageDay = Long.MIN_VALUE }
    }

    // ------------------------------------------------------------------ persistence
    /** Load the user's choices from [s] (TekyeremaApp.onCreate, before anything reads them). */
    @Synchronized fun init(s: OperatorStore) {
        store = s
        load(s)
        // Money safety round 2: the user's Mobile Money limits and paid-numbers list live in the same store (money/).
        com.tekyerema.money.MoneyLimits.init(s)
        com.tekyerema.money.KnownRecipients.init(s)
        com.tekyerema.money.TxLedger.init(s)
        _changes.value = _changes.value + 1
    }

    /** Back to today's behaviour with nothing persisted (unit tests; [init] with a fresh store to persist again). */
    @Synchronized fun resetForTest(s: OperatorStore? = null) {
        store = null
        mode = ExecutionMode.STANDARD; cloud = true; providerOrder = KNOWN_PROVIDERS; weak = WeakModelPolicy.ASK
        steps = DEFAULT_STEPS; stepsChosen = false; capCalls = 0; capTokens = 0; remote = false; revoked = emptySet()
        schedules = true; alerts = true; routines = true; retentionDays = 0; appRuleMap = emptyMap()
        usage.clear(); usageDay = Long.MIN_VALUE; lastModel = null
        zoneOffset = { java.util.TimeZone.getDefault().getOffset(it) }
        com.tekyerema.money.MoneyLimits.resetForTest(); com.tekyerema.money.KnownRecipients.resetForTest()   // round 2 (money/)
        com.tekyerema.money.TxLedger.resetForTest()
        if (s != null) init(s)
    }

    private fun load(s: OperatorStore) {
        fun bool(k: String, d: Boolean) = s.get(k)?.toBooleanStrictOrNull() ?: d
        fun int(k: String, d: Int) = s.get(k)?.toIntOrNull() ?: d
        mode = s.get(K_MODE)?.let { v -> ExecutionMode.entries.firstOrNull { it.name == v } } ?: ExecutionMode.STANDARD
        cloud = bool(K_CLOUD, true)
        providerOrder = s.get(K_PROVIDERS)?.let { normalizeProviders(it.split(',')) } ?: KNOWN_PROVIDERS
        weak = s.get(K_WEAK)?.let { v -> WeakModelPolicy.entries.firstOrNull { it.name == v } } ?: WeakModelPolicy.ASK
        val st = s.get(K_STEPS)?.toIntOrNull()
        stepsChosen = st != null; steps = (st ?: DEFAULT_STEPS).coerceIn(MIN_STEPS, MAX_STEPS)
        capCalls = int(K_CAP_CALLS, 0).coerceAtLeast(0)
        capTokens = int(K_CAP_TOKENS, 0).coerceAtLeast(0)
        remote = bool(K_REMOTE, false)
        revoked = s.get(K_REVOKED).orEmpty().split(',').mapNotNull { v -> Capability.entries.firstOrNull { it.name == v.trim() } }.toSet()
        schedules = bool(K_SCHEDULES, true)
        alerts = bool(K_ALERTS, true)
        routines = bool(K_ROUTINES, true)
        retentionDays = int(K_RETENTION, 0).coerceAtLeast(0)
        appRuleMap = AppRules.decode(s.get(K_APP_RULES))
        loadUsage(s.get(K_USAGE))
    }

    private fun persist(key: String, value: String?, notify: Boolean = true) {
        try { store?.put(key, value) } catch (_: Throwable) {}
        if (notify) _changes.value = _changes.value + 1
    }

    fun normalizeProviders(v: List<String>): List<String> =
        v.map { it.trim().lowercase() }.filter { it in KNOWN_PROVIDERS }.distinct()

    /**
     * A change to one stored choice in plain past-tense words, for the activity log ("switched the cloud off"); null for
     * the usage counters (not a choice). [before] = the stored value it replaced (the app rules are described as the
     * difference: "put WhatsApp off limits"). Pure.
     */
    fun describeChange(key: String, value: String?, before: String? = null): String? {
        fun onOff(v: String?) = if (v == "true") "on" else "off"
        fun limit(what: String, v: String?) = v?.toIntOrNull()?.takeIf { it > 0 }?.let { "set the daily $what limit to $it" } ?: "removed the daily $what limit"
        return when (key) {
            K_MODE -> "set how I work to " + when (value) {
                ExecutionMode.GUIDE_ONLY.name -> "guide only"
                ExecutionMode.ASK_EVERY_STEP.name -> "ask before every step"
                else -> "normal"
            }
            K_CLOUD -> "switched the cloud ${onOff(value)}"
            K_PROVIDERS -> value.orEmpty().split(',').filter { it.isNotBlank() }
                .let { p -> if (p.isEmpty()) "switched off every online AI" else "set the online AI to " + p.joinToString(", then ") { Disclosure.name(it) } }
            K_WEAK -> "set what happens when the main AI is busy to " + when (value) {
                WeakModelPolicy.NEVER.name -> "never use a smaller model"
                WeakModelPolicy.ALLOW.name -> "use the smaller model"
                else -> "ask first"
            }
            K_STEPS -> "set the most steps for one task to $value"
            K_CAP_CALLS -> limit("call", value)
            K_CAP_TOKENS -> limit("token", value)
            K_REMOTE -> "switched the computer connection (remote control) ${onOff(value)}"
            K_REVOKED -> value.orEmpty().split(',').mapNotNull { v -> Capability.entries.firstOrNull { it.name == v.trim() } }
                .let { off -> if (off.isEmpty()) "allowed everything again" else "switched off: " + off.joinToString(", ") { Capabilities.label(it) } }
            K_SCHEDULES -> "switched schedules ${onOff(value)}"
            K_ALERTS -> "switched message alerts ${onOff(value)}"
            K_ROUTINES -> "switched routines ${onOff(value)}"
            K_RETENTION -> value?.toIntOrNull()?.takeIf { it > 0 }?.let { "set history to be kept for $it days" } ?: "set history back to the usual time"
            K_APP_RULES -> AppRules.describe(before, value)
            else ->com.tekyerema.money.MoneyLimits.describeChange(key, value)   // round 2: Mobile Money limits (null otherwise)
        }
    }

    /** A snapshot of the choices for the data export (no keys, nothing personal). */
    fun toJson(now: Long = System.currentTimeMillis()): JSONObject {
        val (calls, tokens) = usageToday(now)
        return JSONObject()
            .put("executionMode", mode.name).put("cloudAllowed", cloud)
            .put("providers", org.json.JSONArray(providerOrder)).put("weakModelPolicy", weak.name)
            .put("maxStepsPerTask", if (stepsChosen) steps else JSONObject.NULL)
            .put("dailyCloudCallCap", capCalls).put("dailyTokenCap", capTokens)
            .put("remoteControlEnabled", remote)
            .put("revokedCapabilities", org.json.JSONArray(revoked.map { it.name }))
            .put("schedulesEnabled", schedules).put("messageAlertsEnabled", alerts).put("routinesEnabled", routines)
            .put("historyRetentionDays", retentionDays)
            .put("appRules", org.json.JSONArray(appRuleMap.values.map { JSONObject().put("package", it.pkg).put("rule", it.rule.name) }))
            .put("usageToday", JSONObject().put("calls", calls).put("tokens", tokens))
    }

    const val K_MODE = "op.mode"
    const val K_CLOUD = "op.cloud"
    const val K_PROVIDERS = "op.providers"
    const val K_WEAK = "op.weak"
    const val K_STEPS = "op.maxSteps"
    const val K_CAP_CALLS = "op.capCalls"
    const val K_CAP_TOKENS = "op.capTokens"
    const val K_REMOTE = "op.remote"
    const val K_REVOKED = "op.revoked"
    const val K_SCHEDULES = "op.schedules"
    const val K_ALERTS = "op.alerts"
    const val K_ROUTINES = "op.routines"
    const val K_RETENTION = "op.retentionDays"
    /** D8: the user's per-app rules ([AppRules.encode]). */
    const val K_APP_RULES = "op.appRules"
    const val K_USAGE = "op.usage"
}
