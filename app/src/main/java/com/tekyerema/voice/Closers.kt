package com.tekyerema.voice

import kotlin.random.Random

/**
 * The small words around a reply, varied so Tɛkyerɛma never sounds like a recording (micro_details.md quick wins 1, 5, 8;
 * Apple's "people hear responses repeatedly" rule). Like [Acknowledgements]: rotating pools, never the same line twice in
 * a row, Twi when the session is Twi. Pure Kotlin.
 */
object Closers {
    private val doneEn = listOf("Done.", "All set.", "That's done.", "Finished.", "Sorted.")
    // Twi: "I'm done." / "It's done." / "It's fine."
    private val doneTw = listOf("Mawie.", "Ayɛ.", "Ɛyɛ.")
    // A warm word before the result of a LONG task (>10 s) — the small breath a person takes ("Alright — it's sent.").
    private val openEn = listOf("Alright —", "Okay —", "Right —")
    private val openTw = listOf("Yoo —", "Ɛyɛ —")

    private val last = HashMap<String, Int>()

    private fun next(key: String, pool: List<String>, rnd: Random): String {
        var i = rnd.nextInt(pool.size)
        if (pool.size > 1 && i == last[key]) i = (i + 1 + rnd.nextInt(pool.size - 1)) % pool.size
        last[key] = i
        return pool[i]
    }

    /** A varied "Done." */
    fun done(twi: Boolean, rnd: Random = Random.Default): String = next(if (twi) "d_tw" else "d_en", if (twi) doneTw else doneEn, rnd)

    /** "Alright — <line>" before a long task's result; a line that already opens warmly (or is empty) is left alone. */
    fun warm(line: String, twi: Boolean, rnd: Random = Random.Default): String {
        if (line.isBlank() || ALREADY_WARM.containsMatchIn(line)) return line
        return next(if (twi) "o_tw" else "o_en", if (twi) openTw else openEn, rnd) + " " +
            line.replaceFirstChar { it.lowercase() }
    }

    private val ALREADY_WARM = Regex("^(?:alright|okay|ok\\b|right|yoo|sorry|hm)", RegexOption.IGNORE_CASE)

    /**
     * The "I didn't hear anything" line, escalating instead of repeating word for word (Google's error guidance:
     * first a short reprompt, then ONE example, then end quietly — the caller goes silent from the third).
     */
    fun noInput(consecutive: Int): String? = when {
        consecutive <= 1 -> "I didn't hear anything. Tap and try again."
        consecutive == 2 -> "Still nothing. You can say, for example, “what time is it” — or type your request."
        else -> null   // rest quietly; the user knows how to reach us by now
    }
}

/**
 * "Say that again." — a blind user's reflex when a line went by too fast (micro_details.md quick win 2; VoiceOver
 * repeats on demand). Matched BEFORE the fast path so the last reply is simply spoken again, never re-run. Pure.
 */
object RepeatIntent {
    private val RE = Regex("^(?:please )?(?:say (?:that|it) again|repeat(?: (?:that|it))?|what did you (?:just )?say|" +
        "come again|pardon(?: me)?|i (?:didn'?t|did not) (?:hear|catch|get) (?:that|it|you)|" +
        // Twi: "ka bio" / "san ka bio" / "ka no bio" (say it again), "mante" (I didn't hear) [owner: verify]
        "(?:san )?ka(?: no)? bio|san ka|mante(?: ase)?)(?: please)?$")

    fun matches(text: String): Boolean =
        RE.matches(text.lowercase().trim().trimEnd('.', '!', '?', ',').replace(Regex("\\s+"), " "))
}

/**
 * At night the phone speaks more softly (micro_details.md quick win 4 — the inverse of Alexa's Adaptive Volume;
 * Siri lowers its voice in quiet places). Voice at ~65%, earcons at half, between 21:00 and 06:59. The confirmation
 * words themselves never change — only the loudness. `ux.quietHours.enabled` false turns it off.
 */
object QuietHours {
    fun factor(hour: Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)): Float {
        if (!com.tekyerema.core.config.AppConfig.bool("ux.quietHours.enabled", true)) return 1f
        return if (hour >= 21 || hour < 7) 0.65f else 1f
    }
}

/**
 * Coaching that shrinks: an instruction tail ("You can say cancel to stop.") is helpful the first times and noise the
 * fiftieth (micro_details.md quick win 7 — Siri: "consider variety … people hear responses repeatedly"). After [FULL]
 * hearings of a given tail, the short form (often nothing) is used. Counts survive restarts when [store] is set.
 */
object Coaching {
    const val FULL = 5
    @Volatile var store: android.content.SharedPreferences? = null
    private val counts = HashMap<String, Int>()

    fun tail(key: String, full: String, short: String = ""): String {
        val n = synchronized(counts) {
            val v = (store?.getInt(key, 0) ?: counts.getOrDefault(key, 0)) + 1
            counts[key] = v
            store?.edit()?.putInt(key, v)?.apply()
            v
        }
        return if (n <= FULL) full else short
    }

    fun resetForTest() { synchronized(counts) { counts.clear() }; store = null }
}
