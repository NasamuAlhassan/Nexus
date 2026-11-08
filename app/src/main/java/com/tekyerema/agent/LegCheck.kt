package com.tekyerema.agent

/**
 * A request with several parts is not done when its first part is answered. A20e 8 Nov 2026: "Read me the last message Richmond
 * sent on Telegram, then text Prince a one-line summary of it, and remind me about the meeting at twenty-five past ten" ended at
 * the read-out; the text and the reminder were never attempted. Before a reply is accepted, each ACTING part (a message, a call,
 * an alarm or reminder) must have a step of its kind in the task's history; otherwise the brain is told, once, what is still to do.
 * Reading parts are not checked (the answer itself is their evidence). Pure → LegCheckTest.
 */
object LegCheck {
    private class Leg(val noun: String, val cue: Regex, val evidence: Set<String>)

    private val I = RegexOption.IGNORE_CASE
    private val LEGS = listOf(
        Leg("message", Regex("^(?:(?:and|then|also|please)\\s+)*(?:send|text|sms|message|whatsapp|reply|forward|tell(?!\\s+me\\b)|let\\s+\\S+\\s+know)\\b", I),
            setOf("send_sms", "reply_to_notification", "share_text", "type", "set_text")),
        Leg("call", Regex("^(?:(?:and|then|also|please)\\s+)*(?:call|ring|dial|phone)\\b", I), setOf("make_call")),
        Leg("reminder", Regex("^(?:(?:and|then|also|please)\\s+)*(?:remind|set (?:an? |the )?(?:alarm|timer|reminder)|wake me)\\b", I),
            setOf("set_alarm", "set_timer", "create_schedule"))
    )
    private val SENTENCE = Regex("(?<=[.!?])\\s+")

    /** The note for the brain when acting parts of [goal] have no step yet in [history], else null. */
    fun missing(goal: String, history: List<String>): String? {
        val clauses = clauses(goal) ?: return null
        val done = history.mapNotNull { l -> Regex("^([a-z_]+)\\(").find(l)?.groupValues?.get(1)?.takeIf { !l.contains("→ failed") && !l.contains("declined") } }.toSet()
        val missing = ArrayList<String>()
        for (c in clauses.drop(1)) {
            val leg = LEGS.firstOrNull { it.cue.containsMatchIn(c) } ?: continue
            if (done.none { it in leg.evidence }) missing.add("'${c.trim().trimEnd('.', '!')}' (${leg.noun})")
        }
        if (missing.isEmpty()) return null
        return "NOT DONE YET — the request has several parts and you have only done the first. Still to do: " + missing.joinToString("; ") +
            ". Do them now, each with its own tool (send_sms for a text with no app named, make_call, set_alarm for a reminder) — leave this app if " +
            "needed. Then reply with one sentence covering every part."
    }

    /** The request's parts: sentences, then "… and …" / "… then …" clauses; null when it is one part. */
    internal fun clauses(goal: String): List<String>? {
        val parts = goal.trim().split(SENTENCE).flatMap { s -> LocalBrain.splitClauses(s) ?: listOf(s.trim()) }
            .map { it.trim().trim(',').trim() }.filter { it.isNotEmpty() }
        return parts.takeIf { it.size in 2..6 }
    }
}
