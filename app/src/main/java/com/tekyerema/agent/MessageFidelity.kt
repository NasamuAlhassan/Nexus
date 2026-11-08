package com.tekyerema.agent

import com.tekyerema.voice.understand.MessageBody

/**
 * The words of a message are the USER's (S-05, handover 7 October 2026: "nothing checks that the message text the planner
 * writes is faithful to the user's words"). When the request dictates the message — "text Ama saying I'm on my way",
 * "tell Kofi that he should call me", "message Prince: meeting moved to 3" — the text that goes out must be those words:
 *
 *  - a planner `send_sms` / `reply_to_notification` whose text says something else is sent with the dictated words instead
 *    (the planner's capitalisation or punctuation of the same words is left alone — [sameWords]);
 *  - a Send tap in a chat whose composer holds different words is read back with both, so the user decides.
 *
 * A request that only ASKS for a message ("tell Ama to call me", "let Kofi know I'm late" without a marker) dictates
 * nothing here; the planner composes, and the read-back still quotes the text. Pure, Android-free, unit-tested.
 */
object MessageFidelity {
    /** The dictated words [raw], and [adapted] for a tell-form ("tell Kofi that he should call me" → "you should call me"). */
    data class Dictated(val raw: String, val adapted: String) {
        /** What goes out when the planner's text is not the user's words — the user's words, TIDIED ([tidy]): the same words
         *  with sentences, capitals and a full stop, the recogniser's fillers dropped. Never other words. */
        val preferred: String get() = tidy(adapted)
        fun matches(text: String?): Boolean = text != null && (sameWords(text, raw) || sameWords(text, adapted) || sameWords(text, preferred))
    }

    /**
     * Dictated words as a message a person would send (owner, A20e 14 Oct 2026 00:29: "I'm there I have issues with the file
     * right in fixing it" went out exactly as heard — "it didn't reframe the message I wanted well"). Only shape, never
     * content: the recogniser's fillers go ("um", "uh", "you know", a leading "so"/"okay"), a new first-person clause after
     * a word starts a new sentence ("I'm there I have issues" → "I'm there. I have issues"), "i" is "I", the first letter
     * is a capital, and it ends with a full stop. Words that could be meant ("right", "like") are kept. Pure.
     */
    fun tidy(words: String): String {
        var t = words.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return t
        t = FILLER.replace(t, " ").replace(Regex("\\s+"), " ").trim()
        t = LEAD_FILLER.replace(t, "")
        t = Regex("\\s+,").replace(t, ",")
        // A new clause after a word (no punctuation between) starts a new sentence — unless the word before it joins clauses.
        t = CLAUSE_START.replace(t) { m ->
            val before = m.groupValues[1]
            // group 2 is only looked at (not consumed); group 3 is the clause's first letter, which the match did consume
            if (before.lowercase() in JOINERS) m.value else "$before. ${m.groupValues[3]}"
        }
        t = Regex("(?i)(^|[.!?]\\s+|\\s)i(?=['’\\s]|$)").replace(t) { m -> m.groupValues[1] + "I" }
        t = t.replaceFirstChar { it.uppercaseChar() }
        t = Regex("([.!?])\\s+(\\p{Ll})").replace(t) { m -> m.groupValues[1] + " " + m.groupValues[2].uppercase() }
        t = t.trimEnd(',', ' ')
        return if (t.last() in ".!?…") t else "$t."
    }

    // Only sounds that are never words ("um", "uh"); "okay", "yes", "you know" may be part of the message and stay.
    private val FILLER = Regex("(?i)(?<![\\p{L}'])(?:um+|uh+|erm+|hmm+)(?![\\p{L}'])")
    private val LEAD_FILLER = Regex("(?i)^(?:(?:so|like)[,\\s]+)+(?=\\p{L})")
    /** A first-person clause that starts after a word with no punctuation between: a new sentence. */
    private val CLAUSE_START = Regex("(?i)(\\b[\\p{L}\\p{N}'’]+)\\s+(?=(i'm|i am|i'll|i will|i've|i have|i was|i can't|i cannot|i need|i don't|i didn't|" +
        "we're|we are|we'll|we will|we have)\\b)(\\S)")
    /** Words that join a clause to what follows ("I think I'm late", "sorry I'm late"): no sentence break after them. */
    private val JOINERS = setOf("that", "if", "because", "and", "but", "so", "or", "when", "while", "where", "whether", "think", "know",
        "hope", "said", "say", "says", "sure", "maybe", "wish", "guess", "believe", "as", "then", "what", "who", "how", "why", "since",
        "unless", "until", "after", "before", "once", "though", "although", "like", "told", "tell", "mean", "feel", "see", "sorry",
        "yes", "no", "okay", "ok", "well")

    private val LEADING_MARKER = Regex("(?i)^(?:saying|that\\s+says|which\\s+says|and\\s+say|(?:and\\s+)?tell(?:ing)?\\s+(?:him|her|them)(?:\\s+that)?|" +
        "telling\\s+(?:him|her|them)|with\\s+the\\s+message|the\\s+message\\s+is|message\\s+is|that)\\b\\s*|^[:\"“]\\s*")
    private val TELL_FORM = Regex("(?i)\\btell(?:ing)?\\s+(?:him|her|them)\\b|^\\s*(?:please\\s+|kindly\\s+)?tell\\s")
    private val TRAILING_QUOTE = Regex("[\"”']+$")

    /** A second instruction after the body ("…, and then remind me about the meeting"): never part of the message. */
    private val SECOND_INSTRUCTION = Regex("(?i)[,;]?\\s*\\b(?:and\\s+then|then|and\\s+also|also|after\\s+that|and)\\s+" +
        "(?:remind|set|call|ring|text|message|open|tell\\s+me|send|play|check|read|wake|schedule|turn|go|put|cancel)\\b")
    /** A body that starts with punctuation or a conjunction is a fragment of the instruction, not a dictated message. */
    private val BAD_START = Regex("(?i)^(?:[\\p{P}\\s]|(?:and|then|but|also|or|so|the one|a one)\\b)")

    /** The words the request dictates for the message, or null when it dictates none. The dictation ends where a second
     *  instruction starts, and a fragment ("…, and then remind me…" — A20e 8 Nov 2026) dictates nothing. */
    fun dictated(goal: String): Dictated? {
        val g = goal.trim()
        if (!MessageBody.isMessaging(g)) return null
        val (head, body) = MessageBody.split(g)
        if (body.isBlank()) return null
        var words = LEADING_MARKER.replace(body.trim(), "").trim().replace(TRAILING_QUOTE, "").trim().trimEnd('.', '!')
            .trim().takeIf { it.isNotEmpty() } ?: return null
        SECOND_INSTRUCTION.find(words)?.let { words = words.substring(0, it.range.first).trim().trimEnd(',', ';', '.') }
        if (words.isEmpty() || BAD_START.containsMatchIn(words)) return null
        val tellForm = TELL_FORM.containsMatchIn(head) || TELL_FORM.containsMatchIn(body.substring(0, body.length - words.length))
        return Dictated(words, if (tellForm) LocalBrain.adaptForRecipient(words) else words)
    }

    /**
     * The words dictated for THIS recipient when the request dictates several messages ("tell Richmond … that I'll be there at half
     * past ten, and text Prince to please start without me" — A20e 8 Nov 2026: Prince's text was replaced by Richmond's words).
     * One dictated body → as [dictated]; several → the clause that names [recipient]; several and no recipient match → null
     * (the planner's own text stands rather than the wrong person's words).
     */
    fun dictatedFor(goal: String, recipient: String?): Dictated? {
        val parts = LegCheck.clauses(goal) ?: return dictated(goal)
        val cands = parts.mapNotNull { c -> dictated(c)?.let { c to it } }
        if (cands.size <= 1) return cands.firstOrNull()?.second ?: dictated(goal)
        val who = recipient?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.lowercase()?.filter { it.isLetterOrDigit() }?.takeIf { it.length >= 2 } ?: return null
        return cands.firstOrNull { it.first.lowercase().contains(who) }?.second
    }

    /** The same words, ignoring case, punctuation, quotes and spacing. */
    fun sameWords(a: String, b: String): Boolean = norm(a) == norm(b) && norm(a).isNotEmpty()
    private fun norm(s: String) = s.lowercase().replace(Regex("[’']"), "'").replace(Regex("[^\\p{L}\\p{N}' ]+"), " ")
        .replace(Regex("\\s+"), " ").trim()

    /**
     * Is [text] the dictated message, POLISHED — the same meaning written properly — rather than a different message? D-027
     * (owner, A20e, 14 Oct 2026: "it didn't reframe the message I wanted well"): the planner may fix recognition slips,
     * grammar, punctuation and person, and split run-ons; it may not add facts. Measured on content words (function words,
     * fillers and common auxiliaries don't count): nothing new beyond one light word, and most of what was said kept. A
     * near-spelling (an edit of two letters, a shared stem) is the same word ("fixing"/"fix", "issue"/"issues"). Pure.
     */
    fun faithful(text: String?, d: Dictated): Boolean {
        if (text.isNullOrBlank()) return false
        if (d.matches(text)) return true
        val said = content(d.raw) + content(d.adapted)
        val wrote = content(text)
        if (wrote.isEmpty()) return false
        val added = wrote.filter { w -> said.none { same(it, w) } }
        val kept = content(d.raw).count { s -> wrote.any { same(it, s) } }
        val saidCount = content(d.raw).size
        return added.size <= 1 && added.size * 5 <= wrote.size.coerceAtLeast(1) + 1 && (saidCount == 0 || kept * 10 >= saidCount * 6)
    }

    private fun content(s: String): Set<String> = norm(s).split(' ').map { it.trim('\'') }
        .filter { it.length > 1 && it !in LIGHT }.toSet()
    private fun same(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length >= 4 && b.length >= 4 && a.take(4) == b.take(4)) return true
        return a.length >= 5 && b.length >= 5 && com.tekyerema.core.TextDistance.levenshtein(a, b) <= 2
    }
    /** Words a polish may add or drop without changing what is said. */
    private val LIGHT = setOf("i", "i'm", "im", "am", "is", "are", "was", "were", "be", "been", "being", "the", "a", "an", "and", "but",
        "so", "or", "to", "of", "in", "on", "at", "with", "for", "from", "it", "it's", "its", "that", "this", "these", "those", "my", "your",
        "me", "you", "we", "we're", "us", "our", "he", "she", "they", "them", "his", "her", "their", "have", "has", "having", "had", "i've",
        "do", "does", "did", "don't", "will", "i'll", "would", "can", "could", "should", "just", "now", "some", "any", "right", "very",
        "really", "please", "okay", "ok", "there", "here", "also", "still", "currently", "already", "then", "um", "uh", "like", "well",
        "yeah", "yes", "hi", "hello", "by", "as", "if", "not", "no", "all", "about", "up", "out", "get", "got", "getting")

    /** The text a planner's messaging tool call should carry: the planner's own when it is the dictated words, written
     *  properly ([faithful]); the user's words, tidied, when it says something else. */
    fun enforced(toolName: String, text: String?, goal: String, recipient: String? = null): String? {
        if (toolName !in TEXT_TOOLS) return text
        val d = dictatedFor(goal, recipient) ?: return text
        return when {
            d.matches(text) -> tidy(text!!)
            faithful(text, d) -> text
            else -> d.preferred
        }
    }

    /** The disclosure for a Send tap whose composer holds a DIFFERENT message from the one dictated (not the same words
     *  polished — [faithful]); null when they agree. */
    fun composerNotice(composer: String?, goal: String): String? {
        val d = dictatedFor(goal, null) ?: return null   // several dictated messages: no notice — the composer may hold another person's
        if (composer.isNullOrBlank() || faithful(composer, d)) return null
        return "The message in the box says “${composer.trim().take(120)}” — you said “${d.adapted.take(120)}”."
    }

    val TEXT_TOOLS = setOf("send_sms", "reply_to_notification")
}
