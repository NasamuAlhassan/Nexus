package com.tekyerema.agent

/**
 * The words the user actually SAID, for the thinking brains (owner, 3 Nov 2026).
 *
 * The speech layer turns a transcript into a "canonical" goal for the phone's rules: number words become digits, money and
 * network words are repaired, names are matched to contacts. That suits the rules (an amount, a phone number) but it put
 * words in the user's mouth for the brain: "one time events" reached it as "1 time events", "please use that one" as
 * "please use that 1". The brain is a language model: it understands "one" in context better than any rewrite can.
 *
 * So the brains read what was said (names still corrected, voice/LanguagePipeline asSaid) and, when the rules' reading
 * differs, that reading as a second line — the model decides. The rule layer and every safety check keep the canonical goal.
 * Pure but for one remembered utterance → HeardWordsTest.
 */
object HeardWords {
    private class Note(val canonical: String, val asSaid: String, val at: Long)

    @Volatile private var last: Note? = null
    private const val FRESH_MS = 10 * 60_000L

    /** An utterance reached the agent as [canonical]; the user said [asSaid]. Blank / same → nothing to remember. */
    @Synchronized
    fun note(canonical: String, asSaid: String?, now: Long = System.currentTimeMillis()) {
        val said = asSaid?.trim().orEmpty()
        last = if (said.isEmpty() || same(said, canonical)) null else Note(canonical.trim(), said, now)
    }

    /** What the user said, when [canonical] is the noted utterance and it is fresh; null otherwise. */
    @Synchronized
    fun asSaid(canonical: String?, now: Long = System.currentTimeMillis()): String? {
        val n = last ?: return null
        if (canonical == null || now - n.at > FRESH_MS) return null
        return if (same(canonical, n.canonical)) n.asSaid else null
    }

    /**
     * The goal line(s) for a brain's prompt: the words as said, plus the rules' reading when it differs
     * ("(The phone read it as: …)"). Just [goal] when nothing was rewritten.
     */
    fun goalForBrain(goal: String, now: Long = System.currentTimeMillis()): String {
        val said = asSaid(goal, now) ?: return goal
        return "$said\n(The phone's reading, numbers as digits: $goal)"
    }

    fun clear() { last = null }

    private fun same(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)
}
