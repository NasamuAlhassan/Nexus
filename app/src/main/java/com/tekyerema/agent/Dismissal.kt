package com.tekyerema.agent

/**
 * "No thanks", "never mind", "no, it's fine", "nothing", "forget it": the user wants NOTHING done. A20e, 6 Nov 2026:
 *  - offline, the pocket brain asked and the answer "no, it's fine" was re-read as a request, which asked again — 30 empty
 *    steps and 18 s of silence ("Reached the step limit");
 *  - online, "no thanks" said after a finished turn became a goal for the cloud brain, which opened Calendar and tapped "6".
 * A dismissal ends the turn with "Okay." before any brain sees it. Pure → DismissalTest.
 */
object Dismissal {
    private val WORD = "(?:no|nope|nah|no thanks|no thank you|never ?mind|forget it|leave it|nothing|nothing else|" +
        "it's fine|it is fine|that's fine|that's all|i'm fine|i am fine|not now|maybe later|later|cancel|cancel that|stop|don't|don't bother|" +
        "never|not needed|no need|skip it|drop it)"
    private val DISMISSAL = Regex("(?i)^\\s*(?:(?:oh|ah|um|uh|okay|ok|please|just)[ ,]+)?$WORD(?:[ ,.!]+$WORD)*(?:[ ,]+(?:please|thanks|thank you|for now|then))?[ .!]*$")

    /** The words mean "do nothing" — the whole utterance, not a "no" inside a request ("no, send it to Kofi" is a request). */
    fun isDismissal(text: String?): Boolean {
        val t = text?.trim().orEmpty()
        if (t.isEmpty() || t.length > 60) return false
        return DISMISSAL.matches(t)
    }

    const val REPLY = "Okay."
}
