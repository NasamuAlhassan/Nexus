package com.tekyerema.agent

/**
 * A money figure in a final answer must have been SEEN (owner, 29 Oct 2026: "it hallucinated the MoMo balance" — myMTN kept the
 * balance hidden as "GHS ****", the taps did nothing, and the planner answered "Your MoMo balance is 57 cedis"). The screen check
 * that catches made-up readings only ran on tasks that tapped nothing; this one runs on every answer that names cedis.
 *
 * Seen means: on the observed screen next to a currency word (GHS / GH₵ / cedi — so a clock's "7:57" is not a balance), or in a
 * tool's own result earlier in the task (a USSD reply, an SMS), or in the user's own request ("send 5 cedis" → "sent 5 cedis").
 * Pure → unit-tested.
 */
object MoneyGround {
    private const val NUM = "\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?"
    private val IN_ANSWER = Regex("(?i)(?:gh(?:s|₵|c)\\s*($NUM)|($NUM)\\s*(?:cedis?|ghs|gh₵|ghc|pesewas?))")
    private val ON_SCREEN = Regex("(?i)(?:gh(?:s|₵|c)\\.?\\s*:?\\s*($NUM)|($NUM)\\s*(?:cedis?|ghs|gh₵|ghc))")
    private val ANY_NUM = Regex(NUM)

    private fun norm(n: String): String = n.replace(",", "").let { s ->
        if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
    }.ifEmpty { "0" }

    /** The money figures an answer states, normalised ("57.00" → "57"). */
    fun figures(answer: String): List<String> =
        IN_ANSWER.findAll(answer).map { m -> norm(m.groupValues[1].ifEmpty { m.groupValues[2] }) }.toList()

    /** The first figure in [answer] that nothing in this task showed, or null when all were seen (or there are none). */
    fun unseen(answer: String, screenText: String, toolResults: List<String>, goal: String): String? {
        val want = figures(answer)
        if (want.isEmpty()) return null
        val seen = HashSet<String>()
        ON_SCREEN.findAll(screenText).forEach { m -> seen.add(norm(m.groupValues[1].ifEmpty { m.groupValues[2] })) }
        (toolResults + goal).forEach { t -> ANY_NUM.findAll(t).forEach { seen.add(norm(it.value)) } }
        return want.firstOrNull { it !in seen }
    }

    /**
     * An UNCONFIRMED answer's specifics — any number, any quoted words — that nothing in the task showed (owner, 29 Oct 2026: "remove
     * those hallucinations"): the first one, or null when every specific was seen. Numbers are matched against the screen, the tool
     * results and the request; quoted words against the screen and the tool results. Pure.
     */
    fun unseenDetail(answer: String, screenText: String, toolResults: List<String>, goal: String): String? {
        val evidence = (listOf(screenText, goal) + toolResults).joinToString(" ")
        val seenNums = ANY_NUM.findAll(evidence).map { norm(it.value) }.toSet()
        ANY_NUM.findAll(answer).map { norm(it.value) }.firstOrNull { it !in seenNums }?.let { return it }
        val ev = evidence.lowercase()
        return Regex("[\"“”]([^\"“”]{2,60})[\"“”]").findAll(answer).map { it.groupValues[1].trim() }
            .firstOrNull { it.isNotBlank() && it.lowercase() !in ev }
    }

    const val NOT_SEEN = "I couldn't see the result on the screen, so I won't guess."

    /** What is said instead of a figure nobody saw. */
    fun honest(goal: String): String =
        if (Regex("(?i)\\bbalance\\b|how much money").containsMatchIn(goal))
            "I couldn't see your balance on the screen — the app didn't show it, so I won't guess. I can check it with the USSD menu instead."
        else "I couldn't see that amount on the screen, so I won't guess it."
}
