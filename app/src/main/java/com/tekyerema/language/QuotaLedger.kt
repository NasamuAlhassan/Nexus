package com.tekyerema.language

import java.util.Calendar
import java.util.TimeZone

/**
 * The UG HCI Lab Speech Gateway's free plan is small (2026-09-28 dashboard): ASR 10 requests a day, 50 a week and 30
 * minutes of audio a week; TTS 15 requests a day, 70 a week and 5,000 characters a week; 5 requests a minute per
 * token. Hitting a limit costs a slow failed call and then nothing works until it resets — so the phone keeps its own
 * count per client and stops asking BEFORE the gateway would refuse; the turn goes to the on-device engine instead.
 *
 * Limits come from the client's `limits` in assets/config/language.json. Counts persist ([Store]; SharedPreferences on
 * the phone) so a restart doesn't reset them. Days and weeks are counted in UTC (the gateway's reset time isn't
 * documented — if it differs, the gateway's own quota error still rests the client, see [HttpSpeech.failureOf]).
 * Pure, unit-tested with a fake clock and an in-memory store.
 */
class QuotaLedger(private val store: Store = MemoryStore(), private val clock: () -> Long = { System.currentTimeMillis() }) {

    /** What one call will spend. */
    data class Units(val requests: Int = 1, val audioSec: Double = 0.0, val chars: Int = 0)

    /** A client's plan. 0 = no limit. */
    data class Limits(
        val perMinute: Int = 0, val perDay: Int = 0, val perWeek: Int = 0,
        val audioSecPerWeek: Int = 0, val charsPerWeek: Int = 0,
        /** Keep this many requests of each day/week unspent (headroom for the user's next deliberate turn). */
        val reserve: Int = 0
    ) {
        val isEmpty get() = perMinute == 0 && perDay == 0 && perWeek == 0 && audioSecPerWeek == 0 && charsPerWeek == 0

        companion object {
            fun parse(o: org.json.JSONObject?): Limits = if (o == null) Limits() else Limits(
                perMinute = o.optInt("perMinute", 0), perDay = o.optInt("perDay", 0), perWeek = o.optInt("perWeek", 0),
                audioSecPerWeek = o.optInt("audioSecPerWeek", 0), charsPerWeek = o.optInt("charsPerWeek", 0),
                reserve = o.optInt("reserve", 0).coerceAtLeast(0))
        }
    }

    interface Store {
        fun get(key: String): Long
        fun put(key: String, value: Long)
    }

    class MemoryStore : Store {
        private val m = java.util.concurrent.ConcurrentHashMap<String, Long>()
        override fun get(key: String) = m[key] ?: 0L
        override fun put(key: String, value: Long) { m[key] = value }
    }

    private val minute = java.util.concurrent.ConcurrentHashMap<String, ArrayDeque<Long>>()

    private fun cal(): Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = clock() }
    private fun dayKey() = cal().let { "${it.get(Calendar.YEAR)}-${it.get(Calendar.DAY_OF_YEAR)}" }
    private fun weekKey() = cal().apply { firstDayOfWeek = Calendar.MONDAY; minimalDaysInFirstWeek = 4 }
        .let { "${it.getWeekYear()}w${it.get(Calendar.WEEK_OF_YEAR)}" }

    /** Null when [u] fits in [id]'s remaining quota, else a short reason (logged; no user content). */
    @Synchronized fun refusal(id: String, l: Limits, u: Units): String? {
        if (l.isEmpty) return null
        val now = clock()
        if (l.perMinute > 0) {
            val q = minute[id]?.also { d -> while (d.isNotEmpty() && now - d.first() >= 60_000L) d.removeFirst() }
            if ((q?.size ?: 0) + u.requests > l.perMinute) return "$id: ${l.perMinute} requests a minute used"
        }
        val d = dayKey(); val w = weekKey()
        if (l.perDay > 0 && store.get("$id|req|$d") + u.requests > l.perDay - l.reserve) return "$id: today's ${l.perDay} requests used"
        if (l.perWeek > 0 && store.get("$id|req|$w") + u.requests > l.perWeek - l.reserve) return "$id: this week's ${l.perWeek} requests used"
        if (l.audioSecPerWeek > 0 && store.get("$id|audio|$w") + Math.ceil(u.audioSec).toLong() > l.audioSecPerWeek)
            return "$id: this week's ${l.audioSecPerWeek / 60} minutes of audio used"
        if (l.charsPerWeek > 0 && store.get("$id|chars|$w") + u.chars > l.charsPerWeek) return "$id: this week's ${l.charsPerWeek} characters used"
        return null
    }

    /** Count a call that reached the gateway (success or a refusal it charged for). */
    @Synchronized fun record(id: String, l: Limits, u: Units) {
        if (l.isEmpty) return
        val now = clock()
        if (l.perMinute > 0) minute.getOrPut(id) { ArrayDeque() }.apply { repeat(u.requests) { addLast(now) } }
        val d = dayKey(); val w = weekKey()
        fun add(k: String, v: Long) { if (v > 0) store.put(k, store.get(k) + v) }
        add("$id|req|$d", u.requests.toLong()); add("$id|req|$w", u.requests.toLong())
        add("$id|audio|$w", Math.ceil(u.audioSec).toLong()); add("$id|chars|$w", u.chars.toLong())
    }

    /** The gateway said the day/week is spent even though our count disagrees (another device, a reset we misjudged):
     *  believe it — fill that window so we stop asking until it rolls over. */
    @Synchronized fun exhaust(id: String, l: Limits, window: String) {
        when (window) {
            "day" -> if (l.perDay > 0) store.put("$id|req|${dayKey()}", l.perDay.toLong())
            "week" -> if (l.perWeek > 0) store.put("$id|req|${weekKey()}", l.perWeek.toLong())
        }
    }

    /** "3/10 today, 12/50 this week" — for the startup log and Settings. */
    fun usage(id: String, l: Limits): String = buildString {
        if (l.perDay > 0) append("${store.get("$id|req|${dayKey()}")}/${l.perDay} today")
        if (l.perWeek > 0) append(if (isEmpty()) "" else ", ").append("${store.get("$id|req|${weekKey()}")}/${l.perWeek} this week")
    }.ifBlank { "no limits" }
}
