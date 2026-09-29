package com.tekyerema.agent.journal

/**
 * The offer to carry on — made ONCE per pause, by voice (and the shade when it comes unprompted), and never acted on
 * by itself: a blind user must not find the phone operating an app they didn't just ask for (research:
 * agenticness_reliability_state §2.4 "Offer, don't act"; §7 "Resuming GUI tasks automatically" — not recommended).
 *
 * Two moments: the next time the user opens Tɛkyerɛma (the start of a voice turn, through [com.tekyerema.core.Health]'s
 * one-time notices), or when the internet comes back for a task paused by the network (JournalBoot). Whichever comes
 * first marks the pause as offered; "where did I leave off?" can still name it afterwards.
 */
object ResumeOffer {
    /** The resumable task not yet offered, and marks it offered — or null. [networkOnly]: only a network pause. */
    fun take(now: Long = System.currentTimeMillis(), networkOnly: Boolean = false): Pair<TaskRecord, String>? {
        val rec = TaskJournal.resumable(now) ?: return null
        if (rec.offered) return null
        if (networkOnly && rec.pauseReason != PauseReason.NETWORK) return null
        TaskJournal.markOffered(rec.id)
        return rec to line(rec, networkBack = networkOnly)
    }

    /** A resumable task that was not offered yet, without marking it (tests, diagnostics). */
    fun pending(now: Long = System.currentTimeMillis()): TaskRecord? = TaskJournal.resumable(now)?.takeIf { !it.offered }

    private fun quoted(goal: String): String = "“" + goal.trim().trimEnd('.', '!', '?').take(80) + "”"

    /**
     * "Earlier I paused “send Ama a message” because the internet dropped. So far I opened WhatsApp. Say “carry on”
     * when you want me to continue."
     */
    fun line(rec: TaskRecord, networkBack: Boolean = false): String {
        val what = quoted(rec.goal)
        val head = when {
            networkBack -> "The internet is back. Earlier I paused $what."
            rec.state == TaskState.INTERRUPTED -> "Earlier I was working on $what when the app was closed, so it didn't finish."
            else -> "Earlier I paused $what because ${PauseRules.because(rec.pauseReason)}."
        }
        val soFar = rec.progress.takeIf { it.isNotBlank() }?.let { " So far I $it." }.orEmpty()
        return "$head$soFar Say “carry on” when you want me to continue."
    }

    /** For "where did I leave off?": the resumable task in one sentence, or null. */
    fun leftOffLine(now: Long = System.currentTimeMillis()): String? {
        val rec = TaskJournal.resumable(now) ?: return null
        val how = if (rec.state == TaskState.INTERRUPTED) "was interrupted" else "is paused because ${PauseRules.because(rec.pauseReason)}"
        val soFar = rec.progress.takeIf { it.isNotBlank() }?.let { " So far I $it." }.orEmpty()
        return "${quoted(rec.goal).replaceFirstChar { it.uppercaseChar() }} $how.$soFar Say “carry on” to continue it."
    }
}
