package com.tekyerema.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Reads and opens every permission Tɛkyerɛma relies on — the Permissions page and first launch share this. */
object Perms {
    fun granted(ctx: Context, permission: String) =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    fun mic(ctx: Context) = granted(ctx, Manifest.permission.RECORD_AUDIO)
    fun calls(ctx: Context) = granted(ctx, Manifest.permission.CALL_PHONE) && granted(ctx, Manifest.permission.READ_CONTACTS)
    fun overlay(ctx: Context) = Settings.canDrawOverlays(ctx)
    fun notifications(ctx: Context) =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    /** True when our AccessibilityService is enabled in system settings (the "screen control" permission). */
    fun screenControl(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.startsWith(ctx.packageName + "/") }
    }

    /** A11Y-H1 — true when a screen reader with explore-by-touch (TalkBack, etc.) is driving the device, so
     *  our auto-mic, auto-close and spoken replies must coordinate with it instead of colliding. */
    fun talkBack(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as? android.view.accessibility.AccessibilityManager ?: return false
        return am.isEnabled && am.isTouchExplorationEnabled
    }

    /** The runtime permissions requested on first launch (POST_NOTIFICATIONS only exists on 33+). */
    fun runtimePermissions(): Array<String> {
        val base = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS)
        return if (Build.VERSION.SDK_INT >= 33) base + Manifest.permission.POST_NOTIFICATIONS else base
    }

    fun openAccessibilitySettings(ctx: Context) =
        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun openNotificationAccess(ctx: Context) =
        ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun openOverlaySettings(ctx: Context) =
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )

    /** §C.1 — true when the app is exempt from Doze/battery optimisation (so a session isn't killed). */
    fun batteryUnrestricted(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = ctx.getSystemService(android.os.PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    /** Ask the OS to exempt us from battery optimisation (user confirms in the system dialog). */
    fun requestBatteryUnrestricted(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            @android.annotation.SuppressLint("BatteryLife")
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (_: Throwable) {
            // Some OEMs hide the direct request; fall back to the general battery-optimisation list.
            ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun openAppSettings(ctx: Context) =
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
}
