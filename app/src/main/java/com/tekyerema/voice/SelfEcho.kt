package com.tekyerema.voice

/**
 * B-07 guard — did the barge-in mic just hear Tɛkyerɛma's OWN reply? On some phones (seen on the Galaxy A20e) the
 * platform echo canceller doesn't remove TTS played on the media stream, so the reply "interrupts" itself. A heard
 * phrase whose words are mostly inside the text being spoken is an echo, never a new request. Pure → unit-tested.
 */
object SelfEcho {
    private fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }

    /** True when most of what was heard (≥ [ratio] of its words) is in [spoken] — or nothing word-like was heard. */
    fun isEcho(heard: String, spoken: String, ratio: Double = 0.6): Boolean {
        val h = words(heard)
        if (h.isEmpty()) return true
        val s = words(spoken).toSet()
        return h.count { it in s }.toDouble() / h.size >= ratio
    }

    /** The rest of [spoken] from the sentence where the echoed words occur, to resume the reply; "" if not found. */
    fun resumeFrom(heard: String, spoken: String): String {
        val first = words(heard).firstOrNull() ?: return ""
        val m = Regex("(?i)(?<![\\p{L}\\p{N}])" + Regex.escape(first) + "(?![\\p{L}\\p{N}])").find(spoken) ?: return ""
        val sentenceStart = Regex("[.!?]\\s+").findAll(spoken.substring(0, m.range.first)).lastOrNull()?.range?.last?.plus(1) ?: 0
        return spoken.substring(sentenceStart).trim()
    }
}
