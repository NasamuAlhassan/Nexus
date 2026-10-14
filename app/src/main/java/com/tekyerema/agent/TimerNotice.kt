package com.tekyerema.agent

/**
 * Whether the Clock still shows a running timer after "stop the timer". Android's DISMISS_TIMER only silences a timer that is
 * RINGING; one still counting down keeps going — on the A20e (14 October 2026) Samsung Clock took the request, changed
 * nothing, and the reply said the timer had been stopped. The Clock's own ongoing notification tells: a timer still there →
 * the agent stops it on screen. Words that name a stopwatch or an alarm are not a timer; anything else that stays ongoing is
 * treated as one (the agent then looks, rather than the reply claiming a stop that didn't happen). Pure.
 */
object TimerNotice {
    private val TIMER = Regex("(?i)\\btimers?\\b")
    private val NOT_A_TIMER = Regex("(?i)\\b(?:stopwatch|alarms?|upcoming)\\b")

    /** [ongoing] = the words of each ongoing notification the Clock shows (channel, category, title, text). */
    fun stillRunning(ongoing: List<String>): Boolean = ongoing.any { w -> TIMER.containsMatchIn(w) || !NOT_A_TIMER.containsMatchIn(w) }

    /** The control that ends a running timer on the Clock's timer screen — Samsung "Cancel", Google "Delete"/"Reset", others
     *  "Stop" — matched whole (never "Stopwatch", never "Pause", which only holds it). [elements] = (id, label). */
    private val STOP_WORDS = listOf("cancel", "stop", "delete", "delete timer", "reset", "stop timer", "cancel timer")
    /** The timer screen still has a timer: its Pause (running) or Resume (paused) control is there. */
    fun showsRunning(labels: List<String?>): Boolean = labels.any { val l = it.orEmpty().trim().lowercase(); l == "pause" || l == "resume" }

    fun stopButton(elements: List<Pair<Int, String?>>): Int? {
        val norm = elements.map { (id, l) -> id to l.orEmpty().trim().lowercase() }
        for (w in STOP_WORDS) norm.firstOrNull { it.second == w }?.let { return it.first }
        return null
    }
}
