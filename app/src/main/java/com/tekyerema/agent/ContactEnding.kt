package com.tekyerema.agent

/**
 * The owner's live MoMo test (A20e, 14 October 2026): asked "Send 1 cedi to Mom, ending 4 1 1 1?", he answered "no, I want
 * you to send it to my mom, like it's ending — I think 2019", then "I said you should send it to Mom and then 2019". The
 * correction went to the planner, which opened the Contacts app and offered the same number again. These helpers read the
 * ending a correction names, and what the user says when none of the offered contacts is the one. Pure.
 */
object ContactEnding {
    private val ENDING = Regex("\\b(?:ending|ends?|end)\\b(?:\\s+(?:with|in|on|and|i|think|is|it|its|it's|like|maybe|um|uh|the|number)){0,6}\\s+(\\d{2,9})\\b")
    private val SHORT_RUN = Regex("\\b(\\d{3,6})\\b(?!\\s*(?:cedis?|pesewas?|ghs|ghc|gh₵|minutes?|hours?|o'?clock))")

    /**
     * The digits [reply] says the number ends with: "ending 2019", "it ends with 0 1 9", "like it's ending — I think 2019";
     * or, in a correction about a person, a lone 3–6 digit run that isn't an amount ("send it to Mom and then 2019").
     * Null when it names none.
     */
    fun digits(reply: String): String? {
        val t = NumberCorrection.digitize(reply)
        ENDING.find(t)?.let { return it.groupValues[1] }
        return SHORT_RUN.findAll(t).map { it.groupValues[1] }.toList().singleOrNull()
    }

    private val NONE_OF_THEM = Regex("(?i)\\b(?:none of (?:them|those|these)|neither|not (?:any of )?(?:them|those|these)|another|" +
        "other one|different one|someone else|somebody else|not the right one|not in the list|it'?s not (?:there|them|any of them))\\b")

    /** "None of them — there's another Dad": the person isn't among the offered contacts (no name given with it). */
    fun noneOfThem(answer: String): Boolean = NONE_OF_THEM.containsMatchIn(answer)

    /** A whole phone number in [answer] (9+ digits, spoken digits counted), normalised; null when there is none. */
    fun wholeNumber(answer: String): String? {
        val run = Regex("\\d{9,}").find(NumberCorrection.digitize(answer).replace(" ", ""))?.value ?: return null
        return NumberSpeech.normalize(run).takeIf { it.length >= 9 }
    }
}
