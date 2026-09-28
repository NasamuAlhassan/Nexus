package com.tekyerema.agent.triggers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tekyerema.core.config.AppConfig
import com.tekyerema.core.config.FeatureFlags
import org.json.JSONArray
import java.util.Calendar
import java.util.TimeZone

/** A-11 — a spoken offer tied to an app: when [packageName] has been in front for a moment, say [line]. */
data class Offer(val packageName: String, val line: String)

/**
 * A-11 — proactive suggestions. Rules come from config `proactive.rules`
 * (`[{"package": "com.mtn.momo*", "line": "Want me to check your MoMo balance?"}]`; a trailing `*` matches a package
 * prefix). Flag A-11 is OFF by default. When on: after the app has stayed in front for `proactive.dwellMs` (3 s), the
 * line is spoken ONCE per app per day — never during a task / voice turn / call, never in quiet hours. It is an offer
 * only: nothing is done unless the user then asks in a normal turn (which goes through every usual gate).
 */
object ProactiveRules {
    const val FEATURE = "A-11"
    private const val DAY_KEY_PREFIX = "proactive_day_"

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    /** Main-thread state: the app in front now and the pending dwell timer. */
    private var foreground: String? = null
    private var pending: Runnable? = null

    // ---------------------------------------------------------------- pure

    fun parseOffers(arr: JSONArray?): List<Offer> {
        if (arr == null) return emptyList()
        val out = ArrayList<Offer>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val pkg = o.optString("package", o.optString("packageName", "")).trim()
            val line = o.optString("line", "").trim()
            if (pkg.isNotEmpty() && line.isNotEmpty()) out.add(Offer(pkg, line))
        }
        return out
    }

    fun offers(): List<Offer> = parseOffers(AppConfig.node("proactive.rules") as? JSONArray)

    /** Exact package, or a prefix rule ending in "*" (or "…"). The first matching rule wins. */
    fun find(offers: List<Offer>, pkg: String): Offer? = offers.firstOrNull { o ->
        val p = o.packageName
        when {
            p.endsWith("*") || p.endsWith("…") -> pkg.startsWith(p.dropLast(1))
            else -> p == pkg
        }
    }

    /** yyyyMMdd in [zone] — "once per app per day" bookkeeping. */
    fun dayStamp(nowMillis: Long, zone: TimeZone = TimeZone.getDefault()): Int {
        val c = Calendar.getInstance(zone).apply { timeInMillis = nowMillis }
        return c.get(Calendar.YEAR) * 10_000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
    }

    // ---------------------------------------------------------------- event

    /**
     * Call on every foreground-app change (the accessibility service's TYPE_WINDOW_STATE_CHANGED package of the
     * active app window; repeated calls for the same package are cheap no-ops). Any thread.
     */
    fun onForeground(ctx: Context, pkg: String) {
        val app = ctx.applicationContext
        handler.post { onForegroundMain(app, pkg) }
    }

    private fun onForegroundMain(app: Context, pkg: String) {
        if (pkg.isBlank() || pkg == foreground) return
        foreground = pkg
        pending?.let { handler.removeCallbacks(it) }
        pending = null
        if (!FeatureFlags.isEnabled(FEATURE, false)) return
        if (pkg == app.packageName) return
        val offer = find(offers(), pkg) ?: return
        if (offeredToday(app, pkg)) return
        val r = Runnable { pending = null; if (foreground == pkg) speakOffer(app, pkg, offer) }
        pending = r
        handler.postDelayed(r, AppConfig.long("proactive.dwellMs", 3_000L).coerceAtLeast(0L))
    }

    private fun offeredToday(ctx: Context, pkg: String): Boolean =
        TriggerGate.prefs(ctx).getInt(DAY_KEY_PREFIX + pkg, 0) == dayStamp(System.currentTimeMillis())

    private fun speakOffer(app: Context, pkg: String, offer: Offer) {
        try {
            if (!FeatureFlags.isEnabled(FEATURE, false)) return
            if (offeredToday(app, pkg)) return
            if (QuietHours.isQuiet(System.currentTimeMillis())) return
            if (TriggerGate.userBusy(app)) return   // not over a task, a voice turn or a call (not recorded: a later visit may offer)
            TriggerGate.prefs(app).edit().putInt(DAY_KEY_PREFIX + pkg, dayStamp(System.currentTimeMillis())).apply()
            Log.i(TriggerGate.TAG, "[TRIGGER] A-11 offer for $pkg")
            TriggerGate.speak(app, offer.line, post = false)
        } catch (t: Throwable) {
            Log.w(TriggerGate.TAG, "[TRIGGER] A-11 offer failed: ${t.message}")
        }
    }
}
