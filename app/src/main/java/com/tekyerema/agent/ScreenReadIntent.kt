package com.tekyerema.agent

/**
 * "Read the screen" in the words people actually use. Owner (A20e, 2026-09-28): "for the reading of screens, I don't
 * know why it doesn't work offline." Only a few phrasings were recognised locally ("read my screen", "what's on my
 * screen"); "read this", "read it to me", "what does it say", "read the whole page" fell through to the cloud model —
 * fine online, "I don't know that command" offline, and "what does it say" was even treated as a general-knowledge
 * question answered without looking. Reading needs no internet: the accessibility tree and the OCR are on the phone.
 *
 * Whole-utterance patterns, so "read this and then call Ama" (a compound task) and "read my messages" (notifications)
 * don't match. Pure, unit-tested.
 */
object ScreenReadIntent {
    private const val LEAD = "^(?:(?:please|kindly|can you|could you|would you|will you|hey \\S+)\\s+)*"
    private const val TAIL = "(?:\\s+(?:to me|for me|aloud|out loud|please|now))*$"
    private const val WHAT = "(?:it|this|that|here|everything|all of it|the rest|(?:the |my |this )?screen|(?:the |this )?page|(?:the |this )?text|" +
        "(?:this|that) message|(?:the |this )?whole (?:page|screen|thing)|what(?:'s| is) (?:here|on (?:the |my |this )?(?:screen|page)))"

    private val READ = listOf(
        // "read this", "read me the screen", "read out the whole page", "kenkan"
        Regex("$LEAD(?:read|kenkan)(?:\\s+(?:me|out))?(?:\\s+$WHAT)?$TAIL"),
        // "what does it say", "what does the screen say"
        Regex("${LEAD}what does (?:it|this|that|the screen|my screen|the page|this page|this message|that message) say$TAIL"),
        // "what's written here", "what is written on the screen"
        Regex("${LEAD}what(?:'s| is) written(?: (?:here|there|on (?:the |my |this )?(?:screen|page)))?$TAIL")
    )
    private val CONTINUE = Regex("^(?:continue|go on|keep (?:going|reading)|read (?:more|on|the rest)|more|next|carry on|k[ɔo] so|toa so)(?: please)?$")

    private fun norm(goal: String) = goal.trim().lowercase().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ").replace(",", "")

    /** Read what is on the screen, word for word. */
    fun isRead(goal: String): Boolean = norm(goal).let { g -> g.isNotEmpty() && READ.any { it.matches(g) } }

    /** Go on with a reading that was cut into parts ("continue", "go on", "read more", "kɔ so"). */
    fun isContinue(goal: String): Boolean = CONTINUE.matches(norm(goal))
}
