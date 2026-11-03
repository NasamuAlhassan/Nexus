package com.tekyerema.agent

/**
 * Brain first (owner, 3 Nov 2026: "if I tell you to fix it, you'll fix just these commands… the AI brain would have understood and
 * take the right actions"). The quick on-phone layer (FastIntentClassifier) matches words INSIDE sentences; on the A20e "how can I
 * move from where I am to the Great Hall" hit "where I am" and answered the user's location, never reaching the brain.
 *
 * Rule: when a capable brain is reachable (our Space planner online, or the on-phone model on a phone that can run it), the quick
 * layer answers only a COMMAND-SIZED sentence — a few words once politeness is taken off ("could you…", "please", "for me"). A
 * sentence that carries more (a destination, a second part, a reason) goes to the brain, which reads the whole meaning. With no
 * brain (a small phone offline) the quick layer keeps doing its best, as before: it is all there is.
 * No per-command rule anywhere here: one size test for every sentence. Pure → BrainFirstTest.
 */
object BrainFirst {
    private val LEAD = Regex("^(?:(?:hey |ok |okay )?(?:nexus|tɛkyerɛma|tekyerema)[, ]+)?(?:(?:so|okay|ok|please|kindly|now|um|uh|hey)[, ]+)*" +
        "(?:(?:could|can|would|will) you(?: please)? |i (?:want|need|would like|'d like) you to |i want to |i'd like to |help me |let me )?")
    private val TAIL = Regex("(?:[, ]+(?:please|for me|to me|now|right now|thanks|thank you|quickly))+$")

    /** The sentence's own words once politeness is off: "could you read the most recent sms to me" → "read the most recent sms". */
    fun core(text: String): String {
        var g = text.trim().lowercase().replace('’', '\'').replace(Regex("\\s+"), " ").trimEnd('.', '!', '?', ',').trim()
        g = g.replace(LEAD, "").replace(TAIL, "").trim()
        return g
    }

    /** True when the sentence is a plain command the quick layer may answer even with a brain at hand. */
    fun commandSized(text: String, maxWords: Int = 6): Boolean {
        val c = core(text)
        if (c.isEmpty()) return true
        return c.split(' ').size <= maxWords
    }

    /** The quick layer steps aside for the brain: a brain is reachable AND the sentence is more than a command. */
    fun deferToBrain(text: String, brainReachable: Boolean, maxWords: Int = 6): Boolean =
        brainReachable && !commandSized(text, maxWords)
}
