package com.tekyerema.safety

/** The decisions of the danger alert that need no phone: whom to dial, whom to text, what the text says. Pure → DangerPlanTest. */
object DangerPlan {
    /** Numbers to try, in order. A test number replaces them all, so a rehearsal can never reach a real emergency line. */
    fun numbers(configured: List<String>, testNumber: String): List<String> {
        val test = testNumber.filter { it.isDigit() || it == '+' }
        if (test.isNotEmpty()) return listOf(test)
        return configured.map { it.filter { c -> c.isDigit() || c == '+' } }.filter { it.isNotEmpty() }.distinct()
    }

    /** The emergency contact first, then the trusted contacts; one entry per phone number, at most [max]. */
    fun recipients(emergency: Pair<String, String>?, trusted: List<Pair<String, String>>, max: Int = 4): List<Pair<String, String>> {
        val seen = HashSet<String>()
        return (listOfNotNull(emergency) + trusted).filter { (_, n) -> n.isNotBlank() && seen.add(n.filter { it.isDigit() }.takeLast(9)) }.take(max)
    }

    /** The text sent to each recipient. [link] is a map link, or null when no position could be found in time. */
    fun message(link: String?, accuracyM: Int?, time: String, manual: Boolean = false): String {
        val where = if (link != null) " My location: $link" + (accuracyM?.let { " (within about $it m)" } ?: "") else " I could not get my location."
        return "EMERGENCY: I may be in danger and " + (if (manual) "pressed SOS." else "used my danger code word.") + "$where Please call me, or call the police on 191 or 112. " +
            "Sent automatically by Tɛkyerɛma at $time."
    }

    /** What is said when the code word is heard: the cancel window and how to cancel it. */
    fun warning(seconds: Int, manual: Boolean = false): String =
        (if (manual) "S O S. " else "Danger code heard. ") +
            "Calling for help in $seconds seconds. Say cancel, or press Cancel on the notification, to stop."
}
