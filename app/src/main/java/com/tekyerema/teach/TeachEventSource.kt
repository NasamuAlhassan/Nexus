package com.tekyerema.teach

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.tekyerema.accessibility.TelephonyWindows
import com.tekyerema.core.Sanitize
import com.tekyerema.policy.ScreenPrivacy
import java.util.concurrent.ConcurrentHashMap

/**
 * Teach mode — turns an `AccessibilityEvent` (what the user just did) into a [RawEvent]. Android-only glue, kept thin; the
 * decisions are all in [TeachRecorder] (pure, tested).
 *
 * Privacy at the door: for the phone's own windows (the USSD / PIN dialog, the dialer) and for apps on the deny list the
 * event's node is NEVER opened — only the package goes on, and the recorder stops on that alone. A password field's text
 * is never copied. The text of an ordinary box is passed on only to be held in memory until the user is asked about it.
 */
object TeachEventSource {
    private val appNames = ConcurrentHashMap<String, String>()
    private val launchable = ConcurrentHashMap<String, Boolean>()
    @Volatile private var homePkg: String? = null

    @Suppress("DEPRECATION")   // AccessibilityNodeInfo.recycle()
    fun toRaw(service: AccessibilityService, e: AccessibilityEvent): RawEvent? {
        val type = when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> RawEvent.Type.CLICK
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> RawEvent.Type.LONG_CLICK
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> RawEvent.Type.TEXT_CHANGED
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> RawEvent.Type.SCROLLED
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> RawEvent.Type.WINDOW_CHANGED
            else -> return null
        }
        val pkg = e.packageName?.toString() ?: return null
        if (pkg == service.packageName) return null
        val now = System.currentTimeMillis()   // the clock the session and recorder use
        val pm = service.packageManager
        if (pkg == defaultIme(service)) return RawEvent(type, pkg, now, isIme = true)
        val telephony = TelephonyWindows.matches(pkg)
        val denied = ScreenPrivacy.isDenied(pkg)
        if (telephony || denied) return RawEvent(type, pkg, now, denied = denied)   // the node is not opened
        val shell = pkg == "com.android.systemui" || pkg == homePackage(pm)
        val src = try { e.source } catch (_: Throwable) { null }
        try {
            val editable = src?.isEditable == true
            val password = e.isPassword || src?.isPassword == true
            val role = (src?.className ?: e.className)?.toString()?.substringAfterLast('.')?.takeIf { it.isNotBlank() }
            val viewId = try { src?.viewIdResourceName } catch (_: Throwable) { null }
            val hint = if (editable) try { src?.hintText?.toString()?.takeIf { it.isNotBlank() } } catch (_: Throwable) { null } else null
            val label = when (type) {
                // A box's own text is its contents, not its name.
                RawEvent.Type.TEXT_CHANGED -> Sanitize.label(src?.contentDescription?.toString())
                RawEvent.Type.CLICK, RawEvent.Type.LONG_CLICK ->
                    if (editable) Sanitize.label(src?.contentDescription?.toString()) else clickLabel(src, e)
                else -> null
            }
            val text = if (type == RawEvent.Type.TEXT_CHANGED && !password) e.text?.joinToString("")?.takeIf { it.isNotEmpty() } else null
            var dy = 0; var dx = 0
            if (type == RawEvent.Type.SCROLLED) {
                if (Build.VERSION.SDK_INT >= 28) { dx = e.scrollDeltaX; dy = e.scrollDeltaY } else dy = 1
            }
            val title = if (type == RawEvent.Type.WINDOW_CHANGED || type == RawEvent.Type.CLICK) try { src?.window?.title?.toString() } catch (_: Throwable) { null } else null
            return RawEvent(type, pkg, now, appName = appName(pm, pkg), screenTitle = title, role = role, label = label, viewId = viewId,
                hint = hint, editable = editable, password = password, text = text, scrollDx = dx, scrollDy = dy,
                isShell = shell, launchable = launchable.getOrPut(pkg) { pm.getLaunchIntentForPackage(pkg) != null })
        } finally {
            try { src?.recycle() } catch (_: Throwable) {}
        }
    }

    /**
     * The user pressed Back or Home: a [RawEvent] for the key-up the service's key filter saw, in the app that was in front
     * ([pkg]). Back makes no click, and the window change after it can't be told from an app moving on by itself, so the key
     * is the only reliable signal. Null for any other key or for our own screen. Nothing on screen is opened or read.
     */
    fun keyToRaw(service: AccessibilityService, keyCode: Int, pkg: String?): RawEvent? {
        val type = when (keyCode) {
            android.view.KeyEvent.KEYCODE_BACK -> RawEvent.Type.KEY_BACK
            android.view.KeyEvent.KEYCODE_HOME -> RawEvent.Type.KEY_HOME
            else -> return null
        }
        if (pkg == null || pkg == service.packageName) return null
        val now = System.currentTimeMillis()
        if (pkg == defaultIme(service)) return RawEvent(type, pkg, now, isIme = true)
        val denied = ScreenPrivacy.isDenied(pkg)
        if (TelephonyWindows.matches(pkg) || denied) return RawEvent(type, pkg, now, denied = denied)
        val pm = service.packageManager
        return RawEvent(type, pkg, now, appName = appName(pm, pkg),
            isShell = pkg == "com.android.systemui" || pkg == homePackage(pm),
            launchable = launchable.getOrPut(pkg) { pm.getLaunchIntentForPackage(pkg) != null })
    }

    /** What a tap was on: the node's text or description, else the first short caption below it (a row's title). */
    private fun clickLabel(src: AccessibilityNodeInfo?, e: AccessibilityEvent): String? {
        Sanitize.label(src?.text?.toString())?.let { return it.take(60) }
        Sanitize.label(src?.contentDescription?.toString())?.let { return it.take(60) }
        Sanitize.label(e.contentDescription?.toString())?.let { return it.take(60) }
        Sanitize.label(e.text?.joinToString(" "))?.let { return it.take(60) }
        return if (src == null) null else captionBelow(src, 0)?.take(60)
    }

    private fun captionBelow(n: AccessibilityNodeInfo, depth: Int): String? {
        for (i in 0 until minOf(n.childCount, 6)) {
            val c = try { n.getChild(i) } catch (_: Throwable) { null } ?: continue
            val t = Sanitize.label(c.text?.toString()) ?: Sanitize.label(c.contentDescription?.toString())
            if (t != null && !c.isEditable) return t
            if (depth < 2) captionBelow(c, depth + 1)?.let { return it }
        }
        return null
    }

    private fun defaultIme(service: AccessibilityService): String? = try {
        android.provider.Settings.Secure.getString(service.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
    } catch (_: Throwable) { null }

    private fun homePackage(pm: PackageManager): String? = homePkg ?: try {
        pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName?.takeIf { it != "android" }?.also { homePkg = it }
    } catch (_: Throwable) { null }

    private fun appName(pm: PackageManager, pkg: String): String? = appNames[pkg] ?: try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().takeIf { it.isNotBlank() && it != pkg }?.also { appNames[pkg] = it }
    } catch (_: Throwable) { null }
}
