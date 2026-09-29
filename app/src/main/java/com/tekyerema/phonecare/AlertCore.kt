package com.tekyerema.phonecare

import com.tekyerema.core.config.AppConfig

/**
 * Low battery at 15 % and 5 % (config `alerts.battery.thresholds`), each ONCE per discharge. Pure.
 *
 * A threshold is re-armed only when the battery climbs back above it — while charging at once, otherwise with a small
 * [hysteresis] so a reading that wobbles 15 → 16 → 15 isn't a second warning; a phone charged while switched off
 * (no process to see the charger) is re-armed by the level alone. Starting already below both (a fresh install at 4 %)
 * gives the lower, more urgent warning only.
 */
object BatteryAlerts {
    data class Step(val announce: Int?, val announced: Set<Int>)

    val DEFAULT_THRESHOLDS = listOf(15, 5)

    fun thresholds(): List<Int> {
        val a = AppConfig.node("alerts.battery.thresholds") as? org.json.JSONArray
        val list = if (a == null) emptyList() else (0 until a.length()).mapNotNull { i ->
            when (val v = a.opt(i)) { is Number -> v.toInt(); is String -> v.trim().toIntOrNull(); else -> null }
        }
        return list.filter { it in 1..99 }.distinct().ifEmpty { DEFAULT_THRESHOLDS }
    }

    fun step(level: Int, charging: Boolean, announced: Set<Int>, thresholds: List<Int> = DEFAULT_THRESHOLDS, hysteresis: Int = 3): Step {
        if (level !in 0..100) return Step(null, announced)
        val kept = announced.filter { t -> level <= t + (if (charging) 0 else hysteresis) }.toSet()
        if (charging) return Step(null, kept)
        val due = thresholds.filter { level <= it && it !in kept }
        if (due.isEmpty()) return Step(null, kept)
        val t = due.min()
        return Step(t, kept + thresholds.filter { it >= t })
    }

    /** "Your battery is at 15 percent. Plug in your charger soon." — "now" at the lowest threshold. Pure. */
    fun line(level: Int, threshold: Int, lowest: Int): String =
        if (threshold <= lowest) AppConfig.phrase("alerts.phrases.batteryCritical",
            "Your battery is at {level} percent. Plug in your charger now, or the phone will switch off.", "level" to level.toString())
        else AppConfig.phrase("alerts.phrases.batteryLow", "Your battery is at {level} percent. Plug in your charger soon.", "level" to level.toString())

    fun encode(s: Set<Int>): String = s.sorted().joinToString(",")
    fun decode(s: String?): Set<Int> = s.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
}

/**
 * Each alert is spoken ONCE, and not too often. Pure (clock passed in):
 *  - [firstTime]: a signature seen before (the SMS app re-posting the same message, a dialer updating its notification,
 *    the re-post after a reboot — the signatures are kept across restarts) is not news;
 *  - [mayAnnounce]: at most one alert of a type per [minGapMs], and [maxPerHour] alerts in all.
 */
class AlertGate(private val maxSeen: Int = 64) {
    private val seen = LinkedHashSet<String>()
    private val spokenAt = ArrayDeque<Long>()
    private val lastByType = HashMap<String, Long>()

    @Synchronized fun firstTime(signature: String): Boolean {
        if (!seen.add(signature)) return false
        while (seen.size > maxSeen) seen.remove(seen.first())
        return true
    }

    @Synchronized fun mayAnnounce(type: String, now: Long, minGapMs: Long, maxPerHour: Int): Boolean {
        while (spokenAt.isNotEmpty() && now - spokenAt.first() >= 3_600_000L) spokenAt.removeFirst()
        if (spokenAt.size >= maxPerHour) return false
        val last = lastByType[type]
        if (last != null && now - last in 0 until minGapMs) return false
        spokenAt.addLast(now)
        lastByType[type] = now
        return true
    }

    private val recent = HashMap<String, Long>()

    /** The same event seen within [windowMs] under another notification (a Mobile Money app's push AND its SMS for one
     *  payment; a dialer's group summary and its child): true = a repeat, stay quiet. Records [key] otherwise. */
    @Synchronized fun repeatWithin(key: String, now: Long, windowMs: Long): Boolean {
        recent.entries.removeAll { now - it.value !in 0 until windowMs }
        if (recent.containsKey(key)) return true
        recent[key] = now
        return false
    }

    @Synchronized fun seenList(): List<String> = seen.toList()
    @Synchronized fun restore(list: List<String>) { list.forEach { seen.add(it) }; while (seen.size > maxSeen) seen.remove(seen.first()) }
}

/**
 * The alerts not (fully) spoken — a private one on the loudspeaker ("You have a money alert. Say 'read it'…"), or one
 * held back by quiet hours or a busy user — for "read it" / "read my alerts". Memory only: an amount or a balance never
 * goes to disk. Pure (clock passed in).
 */
class AlertInbox(private val max: Int = 5) {
    /** [line] as it would be spoken; for a money credit, [money] so the line is rebuilt with the user's CURRENT balance
     *  choice when it is read. Kept until [expiresAt]. */
    data class Item(val type: String, val line: String, val at: Long, val announcedPrivately: Boolean, val expiresAt: Long,
                    val money: MoneyAlerts.Event.Received? = null)

    private val items = ArrayList<Item>()

    @Synchronized fun put(item: Item) {
        items.removeAll { it.line == item.line }
        items.add(item)
        while (items.size > max) items.removeAt(0)
    }

    /** A private alert was announced within [windowMs] — so a bare "read it" means this alert, not the screen. */
    @Synchronized fun readItPending(now: Long, windowMs: Long): Boolean = items.any { it.announcedPrivately && now - it.at in 0..windowMs }

    /** The waiting alerts not yet expired, oldest first (then cleared). */
    @Synchronized fun take(now: Long): List<Item> {
        val fresh = items.filter { now <= it.expiresAt }
        items.clear()
        return fresh
    }

    @Synchronized fun clear() = items.clear()
    @Synchronized fun size() = items.size

    companion object {
        /**
         * When a held alert expires: [ttlMs] after the quiet window ENDS when [now] is inside it (an alert held at 23:00
         * is still there for "read my alerts" in the morning), else [ttlMs] from [now]. Pure.
         */
        fun expiry(now: Long, ttlMs: Long, window: com.tekyerema.agent.triggers.QuietHours.Window?, zone: java.util.TimeZone): Long {
            if (window == null) return now + ttlMs
            val m = com.tekyerema.agent.triggers.QuietHours.minuteOfDay(now, zone)
            if (!com.tekyerema.agent.triggers.QuietHours.contains(window, m)) return now + ttlMs
            val untilEnd = ((window.endMin - m) % 1440 + 1440) % 1440
            return now + untilEnd * 60_000L + ttlMs
        }
    }
}
