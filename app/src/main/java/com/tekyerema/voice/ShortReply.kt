package com.tekyerema.voice

import com.tekyerema.core.config.AppConfig

/**
 * Short answers by default (owner item, 2 Nov 2026; agreed with the brain session). The planners' done-summaries run
 * long — the teacher's median is 55 words, about 20 s of speech, and the local models copy it — so at "normal" verbosity
 * a reply is spoken up to about [maxWords] words, cut only at a sentence end, then "Say more for the rest." The rest
 * waits here; "more" / "go on" / "continue" / "tell me more" speaks the next piece the same way.
 *
 * Never cut (the caller passes `exact` for the first two):
 *  - exact readings (screen, document, camera, messages) and anything asked for in full ("read it all", "in detail");
 *  - a reply that ends in a question — ask_user, a read-back, "shall I…?": a cut question can't be answered;
 *  - a safety line (scam, fraud, PIN, warning, danger, emergency, "don't send / never share…");
 *  - a rest of one short sentence: saying it costs less than "Say more for the rest."
 * "detailed" verbosity speaks everything; "brief" keeps its first sentence (VoiceController.spokenFor). The honesty tail
 * is added after the cut, never dropped. Off with `speech.reply.shortByDefault=false`; size `speech.reply.maxWords`.
 */
object ShortReply {
    const val TAIL = "Say more for the rest."
    private const val TTL_MS = 120_000L

    fun enabled(): Boolean = AppConfig.bool("speech.reply.shortByDefault", true)
    fun maxWords(): Int = AppConfig.int("speech.reply.maxWords", 30).coerceAtLeast(10)

    private val SAFETY = Regex("(?i)\\b(?:scam|scammer|fraud|careful|warning|pin|danger|emergency|" +
        "(?:don'?t|do not|never) (?:send|share|give|pay|open))\\b")
    private val FULL = Regex("(?i)\\b(?:read (?:it |them |this |that )?all|all of it|the whole (?:thing|message|story|text|answer)|" +
        "tell me everything|everything about|(?:explain|describe|tell me)\\b.*\\bin (?:full |more )?detail|in detail|in full|" +
        "full (?:details|story|answer|summary)|word for word|don'?t (?:cut|shorten)|long version)\\b")
    private val MORE = Regex("(?i)^\\s*(?:(?:say|tell me|give me|read) more|more please|the rest|what else|and then)[.!?]?\\s*$")
    // A sentence ends at . ! ? followed by a capital, a digit or a quote — not after Mr. / Dr. / St. / No.
    private val SENTENCE_END = Regex("(?<!\\b(?:Mr|Mrs|Ms|Dr|St|No|Prof|Rev|Hon)\\.)(?<=[.!?…])\\s+(?=[\\p{Lu}\\d\"“'])")

    data class Cut(val head: String, val rest: String?)

    /** The words the user said asked for the whole answer. */
    fun askedInFull(goal: String?): Boolean = !goal.isNullOrBlank() && FULL.containsMatchIn(goal)

    /** "more", "go on", "continue", "tell me more"… — the next piece of a cut reply. */
    fun isMore(text: String): Boolean = com.tekyerema.agent.ScreenReadIntent.isContinue(text) || MORE.matches(text)

    /** [text] cut to about [max] words at a sentence end; `rest` is null when nothing was cut. */
    fun cut(text: String, max: Int = maxWords()): Cut {
        val t = text.trim()
        if (t.isEmpty() || t.endsWith("?") || SAFETY.containsMatchIn(t)) return Cut(t, null)
        val sentences = t.split(SENTENCE_END).map { it.trim() }.filter { it.isNotEmpty() }
        if (sentences.size < 2) return Cut(t, null)
        var words = 0
        var n = 0
        for (s in sentences) {
            val w = count(s)
            if (n > 0 && words + w > max) break   // the first sentence always goes whole (SpokenReply already split long ones)
            words += w; n++
        }
        if (n >= sentences.size) return Cut(t, null)
        val rest = sentences.drop(n)
        if (rest.size == 1 && count(rest[0]) <= 8) return Cut(t, null)
        return Cut(sentences.take(n).joinToString(" "), rest.joinToString(" "))
    }

    private fun count(s: String) = s.split(Regex("\\s+")).count { it.isNotBlank() }

    // ---------------------------------------------------------------- the rest, waiting for "more"

    private var rest: String? = null
    private var until = 0L

    /** Keep [r] for "more" (null clears). */
    @Synchronized fun hold(r: String?, now: Long = System.currentTimeMillis()) {
        rest = r?.takeIf { it.isNotBlank() }; until = if (rest == null) 0L else now + TTL_MS
    }

    val pending: Boolean @Synchronized get() = rest != null && System.currentTimeMillis() <= until

    /** The next piece to say (with the tail again when more is left), or null when nothing is waiting. */
    @Synchronized fun next(now: Long = System.currentTimeMillis()): String? {
        val r = rest ?: return null
        if (now > until) { clear(); return null }
        val c = cut(r)
        hold(c.rest, now)
        return if (c.rest != null) "${c.head} $TAIL" else c.head
    }

    @Synchronized fun clear() { rest = null; until = 0L }
}
