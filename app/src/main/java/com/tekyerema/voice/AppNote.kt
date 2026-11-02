package com.tekyerema.voice

/**
 * Say which app a task is working in — "In WhatsApp." — once per app per task. A sighted user watches the phone move
 * from Tɛkyerɛma into WhatsApp or YouTube Music; a blind user heard nothing until the result (voice-UX polish,
 * 2026-09-29: an audio stand-in for seeing where the phone is). Only for an agent task that actually brings another app
 * to the front ([com.tekyerema.agent.AppSwitch]); the fast path's one-shots already say it ("Opening YouTube."). Never:
 *  - over a confirmation, a question, the secure step or a paused task — only while the agent is THINKING / ACTING and
 *    the voice turn is just waiting on it (no prompt or interjection on the microphone);
 *  - during a Mobile Money PIN step ([SecureStep]) or for the phone's own call / USSD windows (the PIN dialog);
 *  - for Tɛkyerɛma itself, the home screen or the system UI, or an app with no name to say;
 *  - in a task that finishes within [minTaskMs] (its result says it all), or [maxLateMs] after the switch (stale by then).
 * Pure → unit-tested (AppNoteTest).
 */
class AppNote(private val minTaskMs: Long = MIN_TASK_MS, private val maxLateMs: Long = MAX_LATE_MS) {

    /**
     * @param taskMs time since the task started; [nowMs] the clock;
     * @param agentWorking the agent is THINKING or ACTING; [micFree] the voice turn is only waiting on it;
     * @param secureStep a PIN step is under way ([SecureStep.active]).
     */
    data class Moment(
        val pkg: String, val name: String?, val taskMs: Long, val nowMs: Long,
        val agentWorking: Boolean, val micFree: Boolean, val secureStep: Boolean, val title: String? = null
    )

    private val said = HashSet<String>()
    private val firstSeen = HashMap<String, Long>()

    /** The line to say now, or null. A returned line counts as said. */
    fun next(m: Moment, twi: Boolean): String? {
        if (m.pkg in said || skip(m.pkg)) return null
        val label = m.name?.trim()?.takeIf { it.isNotEmpty() && it != m.pkg } ?: return null
        val seen = firstSeen.getOrPut(m.pkg) { m.nowMs }
        if (m.nowMs - seen > maxLateMs) { said += m.pkg; return null }   // too late to be news: drop it for this task
        // A confirmation, question or PIN step is up: it says where we are, and after it the note would be stale — drop it.
        if (!m.agentWorking || m.secureStep) { said += m.pkg; return null }
        // The microphone is taking the user's words (an interjection): hold it for a moment.
        if (!m.micFree || m.taskMs < minTaskMs) return null
        said += m.pkg
        return line(label, twi, m.title)
    }

    companion object {
        const val MIN_TASK_MS = 1_500L
        const val MAX_LATE_MS = 6_000L

        private val NEVER = setOf("com.android.systemui", "android", "com.google.android.permissioncontroller",
            "com.android.permissioncontroller", "com.google.android.packageinstaller", "com.android.packageinstaller")

        /** Tɛkyerɛma, a home screen, the system UI, the phone's call / USSD / PIN windows. */
        fun skip(pkg: String): Boolean = pkg.startsWith("com.tekyerema") || pkg in NEVER || pkg.contains("launcher") ||
            com.tekyerema.accessibility.TelephonyWindows.matches(pkg)

        /** "In WhatsApp." — Twi "Mewɔ WhatsApp mu." [owner: verify Twi]; both editable in locales.json (`appNote`). */
        fun line(label: String, twi: Boolean, title: String? = null): String =
            if (title.isNullOrBlank() || twi) Phrases.get("appNote", twi, if (twi) "Mewɔ {app} mu." else "In {app}.", "app" to label)
            // The screen's title when it adds something (ScreenSummary.title: short, no codes, never a private screen).
            else Phrases.get("appNoteAt", false, "In {app}, {title}.", "app" to label, "title" to title)
    }
}
