package com.tekyerema.core.config

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * X-01 / N-13 — registers every built catalog feature ([BUILT_FEATURES], generated from FEATURE_STATUS.json) with
 * the [CapabilityRegistry], adding the runtime gates the table can't express: the accessibility service or
 * notification access being enabled, hardware (a camera flash), and feature-to-feature dependencies.
 * Called once from TekyeremaApp.onCreate, right after [AppConfig.load].
 */
object Registrations {
    /** Hardware/runtime gates beyond API level, permissions and the two system services. */
    private val extraChecks: Map<String, (Context) -> Boolean> = mapOf(
        "E-08" to { ctx -> ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH) },
        "E-24" to { ctx -> ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) },
        "M-03" to { ctx -> ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) },
    )

    /** Feature-to-feature dependencies (a feature is unavailable while a dependency is). */
    private val dependsOn: Map<String, List<String>> = mapOf(
        "E-16" to listOf("E-15"),
        "F-06" to listOf("F-05"),
        "F-08" to listOf("F-05"),
        "M-03" to listOf("E-24"),
        "N-12" to listOf("X-10"),
    )

    /** Built but shipped switched off; flags.json (or an override) turns them on. */
    private val defaultOff: Set<String> = emptySet()

    @Volatile private var done = false

    fun registerAll() {
        if (done) return
        for (f in BUILT_FEATURES) {
            val extra = extraChecks[f.id]
            val check: ((Context) -> Boolean)? =
                if (!f.needsAccessibility && !f.needsNotificationAccess && extra == null) null
                else { ctx ->
                    (!f.needsAccessibility || SystemChecks.accessibilityEnabled(ctx)) &&
                        (!f.needsNotificationAccess || SystemChecks.notificationAccess(ctx)) &&
                        (extra?.invoke(ctx) ?: true)
                }
            CapabilityRegistry.register(
                Capability(
                    featureId = f.id,
                    name = f.name,
                    minApi = f.minApi,
                    permissions = f.permissions,
                    dependsOn = dependsOn[f.id].orEmpty(),
                    defaultEnabled = f.id !in defaultOff,
                    check = check
                )
            )
        }
        done = true
    }
}

/** System-state probes shared by capability checks (no UI dependencies). */
object SystemChecks {
    fun accessibilityEnabled(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.startsWith(ctx.packageName + "/") }
    }

    fun notificationAccess(ctx: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
}
