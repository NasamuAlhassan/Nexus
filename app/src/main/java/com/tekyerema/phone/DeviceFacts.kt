package com.tekyerema.phone

import android.content.Context
import android.content.Intent

/**
 * Facts the phone knows by itself, answered on the phone (28 Oct 2026: "how many apps do I have" went to the planner, which opened
 * Settings and scrolled for 40 s without an answer). [handle] returns the line, or null when the request is not one of these.
 */
object DeviceFacts {
    private val APPS = Regex("(?i)^(?:please |tell me )?how many (?:apps|applications)(?: do i have| are(?: there| installed)?(?: on (?:my|this|the) phone)?| on (?:my|this|the) phone| have i (?:got|installed)| is installed)?$")

    fun isAppCount(text: String): Boolean = APPS.matches(text.trim().trimEnd('.', '!', '?'))

    fun handle(ctx: Context, text: String): String? {
        if (!isAppCount(text)) return null
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val n = try { pm.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName }.distinct().size } catch (_: Throwable) { return null }
        return "You have $n apps you can open on this phone. Say “open” and an app's name to open one."
    }
}
