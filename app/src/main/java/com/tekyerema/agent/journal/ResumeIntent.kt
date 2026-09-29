package com.tekyerema.agent.journal

/**
 * "carry on" / "continue" / "resume" / "continue the task" — the user's word to resume a PAUSED or INTERRUPTED task.
 * Matched here, deterministically: no model is asked what "carry on" means (typed or spoken; the Local Fast Path hands
 * it to the engine, which resumes the journaled task without a planning call). A task is never resumed without it.
 *
 * "continue" also means "read on" while a screen reading is cut into parts (ScreenReadIntent) and "play" to the media
 * keys: the reading keeps its word unless the user names the task, and "continue" is media play only when nothing is
 * resumable (the fast path's old behaviour, unchanged).
 */
object ResumeIntent {
    private val PHRASE = Regex(
        "^(?:(?:hey |okay |ok )?(?:tekyerema|tɛkyerɛma)[, ]+)?(?:please |yes,? |yes please,? )?" +
            "(?:carry on|continue|resume|keep going|go on|pick up|pick it up|finish it|finish the task|toa so|k[ɔo] so)" +
            "(?: (?:with )?(?:the |my |that |this )?(?:task|job|request|errand|what you were doing|it))?" +
            "(?: (?:from )?where (?:i|we|you) (?:left off|stopped))?(?: please)?$")

    /** Names the task itself, so it wins over "continue reading". */
    private val EXPLICIT = Regex("(?:task|job|request|errand|what you were doing|where (?:i|we|you) (?:left off|stopped)|finish)")

    fun norm(t: String): String = t.trim().lowercase().replace('’', '\'').replace(Regex("\\s+"), " ").trimEnd('.', '!', '?', ',').trim()

    fun isRequest(text: String): Boolean = PHRASE.matches(norm(text))

    fun isExplicit(text: String): Boolean = isRequest(text) && EXPLICIT.containsMatchIn(norm(text))

    /**
     * Should [text] resume a journaled task? [hasResumable] = TaskJournal has one; [readingPending] = a screen reading is
     * waiting for "continue" (that reading is what the user just heard, so a bare "continue"/"carry on" goes on reading).
     */
    fun shouldResume(text: String, hasResumable: Boolean, readingPending: Boolean): Boolean =
        hasResumable && isRequest(text) && (!readingPending || isExplicit(text))
}
