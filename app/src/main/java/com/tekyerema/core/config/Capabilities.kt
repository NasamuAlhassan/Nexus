package com.tekyerema.core.config

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * X-10 — feature flags, read from `config/flags.json`:
 *   { "schema_version": 1, "flags": { "A-08": { "enabled": true, "min_api": 24 }, … } }
 * A feature with no entry uses the default its [Capability] declares. Flags are data, so a feature can be
 * switched off for the screening without a code change (and, later, remotely via the override document).
 */
object FeatureFlags {
    /** WS-C: a feature the user switched off in Settings › Control (schedules, message alerts, routines) stays off —
     *  the user can only switch a feature OFF here, never on. */
    fun isEnabled(featureId: String, default: Boolean = true): Boolean =
        !com.tekyerema.operator.OperatorPolicy.featureSwitchedOff(featureId) && AppConfig.bool("flags.$featureId.enabled", default)

    fun minApi(featureId: String, default: Int): Int = AppConfig.int("flags.$featureId.min_api", default)
}

/**
 * X-01 / N-13 — one registration per catalog feature (docs/agent/FEATURE_STATUS.md uses the same IDs). A feature is
 * AVAILABLE only when its flag is on, the OS is new enough, its runtime permissions are granted, its extra
 * hardware/runtime check passes and every feature it depends on is available — so behaviour on the Android 11
 * target degrades by feature, never by crash.
 */
data class Capability(
    val featureId: String,
    val name: String,
    val minApi: Int = 26,
    val permissions: List<String> = emptyList(),
    val dependsOn: List<String> = emptyList(),
    val defaultEnabled: Boolean = true,
    /** Extra runtime/hardware gate (e.g. a torch exists, notification access granted). */
    val check: ((Context) -> Boolean)? = null
)

object CapabilityRegistry {
    private val byId = LinkedHashMap<String, Capability>()

    @Synchronized fun register(c: Capability) { byId[c.featureId] = c }
    fun get(featureId: String): Capability? = byId[featureId]
    fun all(): List<Capability> = byId.values.toList()

    /** Why a feature is unavailable ("flag off", "needs API 31", "permission …"), or null when it is available. */
    fun blocker(ctx: Context?, featureId: String, sdk: Int = Build.VERSION.SDK_INT, seen: Set<String> = emptySet()): String? {
        val c = byId[featureId] ?: return "not registered"
        if (!FeatureFlags.isEnabled(featureId, c.defaultEnabled)) return "flag off"
        val minApi = FeatureFlags.minApi(featureId, c.minApi)
        if (sdk < minApi) return "needs API $minApi (device is $sdk)"
        if (ctx != null) {
            c.permissions.firstOrNull { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }?.let { return "permission $it not granted" }
            if (c.check?.invoke(ctx) == false) return "runtime check failed"
        }
        for (d in c.dependsOn) {
            if (d in seen) return "dependency cycle via $d"
            blocker(ctx, d, sdk, seen + featureId)?.let { return "depends on $d ($it)" }
        }
        return null
    }

    fun isAvailable(ctx: Context?, featureId: String): Boolean = blocker(ctx, featureId) == null

    /** One line per registered feature — for logs, `dump_capabilities` and the run report. */
    fun describe(ctx: Context?): List<String> = byId.values.map { c ->
        "${c.featureId} ${c.name}: " + (blocker(ctx, c.featureId) ?: "available")
    }
}
