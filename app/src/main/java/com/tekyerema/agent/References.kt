package com.tekyerema.agent

/**
 * C-22 / F-12 / B-12 — small local language helpers so follow-ups and on-screen references stay at T0 (no cloud call):
 *  - [References]: "call her back", "text him", "the second one" → the person or option they point at;
 *  - [ScreenEntities]: phone numbers visible on the screen ("call this number");
 *  - [DictationCleanup]: dictated text without "um"/"uh" fillers or stuttered repeats (nothing else is changed —
 *    quoted words stay verbatim).
 * All pure → unit-tested.
 */
object References {
    /** The last person named in a call / message / result, and the last list of options offered to choose from. */
    @Volatile var lastPerson: String? = null; private set
    @Volatile var lastOptions: List<String> = emptyList(); private set

    /** E-23: the last phone number found in something read out (a message, a notification). */
    @Volatile var lastNumber: String? = null; private set

    /** When [lastPerson] was noted (wall clock). "Him" is whoever was just talked about — not a recipient from an hour ago. */
    @Volatile private var lastPersonAt: Long = 0L

    /** How long a remembered person stays the answer to "him"/"her" (A20e audit 2026-10-01: a recipient from an earlier,
     *  unrelated request was substituted for "him" long after, and across a conversation reset). */
    internal fun personTtlMs(): Long = try {
        // 15 minutes (owner's demo, 14 Oct 2026): the call, the message, a send with its PIN and the reply all mean the same "him".
        com.tekyerema.core.config.AppConfig.int("references.personTtlSeconds", 900) * 1000L
    } catch (_: Throwable) { 900_000L }

    /** The remembered person, only while still recent. */
    fun recentPerson(now: Long = System.currentTimeMillis()): String? =
        lastPerson?.takeIf { now - lastPersonAt in 0..personTtlMs() }

    fun notePerson(name: String?) {
        name?.trim()?.takeIf { it.length in 2..60 && !it.all { c -> c.isDigit() || c == '+' } }
            ?.let { lastPerson = it; lastPersonAt = System.currentTimeMillis() }
    }

    /** One contact came back from a look-up ("Matches for 'x': Richmond's Dad — 0246168805"): that is who "him" means next.
     *  Several matches are options, not a person. Pure parse; returns the name noted, or null. */
    fun noteLookup(toolMessage: String): String? {
        val body = toolMessage.substringAfter(": ", "").trim()
        if (body.isEmpty() || ';' in body) return null
        val name = body.substringBefore(" — ").substringBefore(" - ").trim().trimEnd('.')
        if (name.length !in 2..60 || name.any { it == '\n' }) return null
        notePerson(name)
        body.substringAfter(" — ", "").takeIf { it.isNotBlank() }?.let { noteNumber(it) }
        return name
    }
    fun noteOptions(options: List<String>) { if (options.size >= 2) lastOptions = options.take(6) }
    fun noteNumber(number: String?) { number?.filter { it.isDigit() || it == '+' }?.takeIf { it.count(Char::isDigit) in 9..13 }?.let { lastNumber = it } }

    /** H-12: "forget that" / "forget everything". */
    /** Where the last message read out came from: "WhatsApp" | "SMS" (null = unknown, or an app a reply can't go back to). */
    @Volatile var lastChannel: String? = null; private set

    /** A message was just read out ("Your last WhatsApp message is from Ama…"): a bare "reply …" answers that person there. */
    fun noteRead(person: String?, channel: String?) {
        notePerson(person)
        lastChannel = channel?.takeIf { it == "WhatsApp" || it == "SMS" }
    }

    fun clear() { lastPerson = null; lastPersonAt = 0L; lastOptions = emptyList(); lastNumber = null; lastChannel = null }

    /** H-12: "forget Ama" — drop her as the remembered person / option. */
    fun forget(who: String) {
        val w = who.trim()
        if (w.isEmpty()) return
        if (lastPerson?.contains(w, true) == true) lastPerson = null
        lastOptions = lastOptions.filterNot { it.contains(w, true) }
    }

    /** "Multiple contacts match 'ama' (…): Ama Mensah (024…); Ama Boateng (055…)" → [Ama Mensah, Ama Boateng]. */
    fun optionsFrom(message: String): List<String> {
        val body = message.substringAfter(':', "").substringBefore(". Which").substringBefore("Which one")
        return body.split(';', ',').map { it.substringBefore('(').trim().trimEnd('.') }.filter { it.length in 2..60 }
    }

    private val ORDINALS = mapOf("first" to 0, "1st" to 0, "second" to 1, "2nd" to 1, "third" to 2, "3rd" to 2,
        "fourth" to 3, "4th" to 3, "fifth" to 4, "5th" to 4)
    private val ORDINAL = Regex("\\bthe (first|1st|second|2nd|third|3rd|fourth|4th|fifth|5th|last) one\\b", RegexOption.IGNORE_CASE)
    // Only when the request OPENS with the verb and the pronoun ("call her back", "please text him I'm late"). A pronoun
    // later in the sentence has its person in the sentence itself — "tell Prince I'll call him later", "find Richmond and
    // send him the screenshot" — and swapping in whoever was remembered corrupted the goal (audit C07, E2E1).
    private val PRONOUN = Regex("^(?:(?:please|kindly|just|now|ok(?:ay)?|can you|could you|will you|i want to|i'd like to)\\s+)*" +
        "(call|ring|phone|text|message|sms|reply to|tell|answer|write to|send)\\s+(him|her|them)\\b", RegexOption.IGNORE_CASE)
    // "this number" is the one on the screen (LocalBrain reads it there); "that number" is the one just read out.
    private val THAT_NUMBER = Regex("\\b(?:call|ring|phone|dial|text|message|sms|save)\\s+(that number)\\b", RegexOption.IGNORE_CASE)

    /** The goal with its reference replaced, or null when there is nothing (resolvable) to replace. */
    /** What the last [rewrite] filled in from an earlier turn — (the words said, what they were taken as) — so the read-back
     *  can say it once ("Taking 'him' as Ama, from before."). Null when the last rewrite changed nothing. */
    @Volatile var lastFilled: Pair<String, String>? = null; private set

    fun rewrite(goal: String, person: String? = recentPerson(), options: List<String> = lastOptions, number: String? = lastNumber,
                channel: String? = lastChannel): String? {
        lastFilled = null
        THAT_NUMBER.find(goal)?.let { m ->
            val n = number ?: return null
            lastFilled = m.groups[1]!!.value to n
            return goal.replaceRange(m.groups[1]!!.range, n)
        }
        ORDINAL.find(goal)?.let { m ->
            val key = m.groupValues[1].lowercase()
            val idx = if (key == "last") options.lastIndex else ORDINALS[key] ?: return null
            val pick = options.getOrNull(idx) ?: return null
            lastFilled = m.value to pick
            return goal.replaceRange(m.range, pick)
        }
        PRONOUN.find(goal.trim())?.let { m ->
            val who = person ?: return null
            lastFilled = m.groups[2]!!.value to who
            return goal.trim().replaceRange(m.groups[2]!!.range, who)
        }
        // Demo journey (A20e 2026-09-30): "read my last WhatsApp message" → "reply saying I'm on my way". The words are
        // read back with the person and the number before anything is sent (LocalBrain.parseSms → send_sms, TIER2).
        BARE_REPLY.find(goal.trim())?.let { m ->
            val who = person ?: return null
            val via = when (channel) { "WhatsApp" -> " on WhatsApp"; "SMS" -> ""; else -> return null }
            lastFilled = "reply" to who
            return "reply to $who$via saying ${m.groupValues[1].trim()}"
        }
        return null
    }

    /** "reply saying I'm on my way" / "reply: yes" / "reply back with thank you" — not "reply to Ama …" (that names someone). */
    private val BARE_REPLY = Regex("^(?:please )?(?:reply|respond|answer)(?: back)?(?!\\s+to\\b)(?:\\s+(?:saying|with|that))?\\s*:?\\s+(.+)$",
        RegexOption.IGNORE_CASE)
}

object ScreenEntities {
    private val PHONE = Regex("(?<![\\d])(?:\\+?233[\\s-]?|0)(?:\\d[\\s-]?){8,9}\\d(?![\\d])")

    /** Distinct phone numbers visible in [labels], digits only (a leading + kept), in screen order. */
    fun phoneNumbers(labels: List<String>): List<String> =
        labels.flatMap { l -> PHONE.findAll(l).map { it.value.filter { c -> c.isDigit() || c == '+' } } }.distinct()
}

object DictationCleanup {
    private val FILLERS = Regex("(?i)(?<![\\p{L}])(?:um+|uh+|erm+|er|hmm+)(?![\\p{L}])[,.]?\\s*")
    // Only stutters on short function words ("I I will", "the the"): "had had" or "that that" can be real English.
    private val STUTTER = Regex("(?i)\\b(i|the|a|an|to|and|we|you|it|is|my|me)(\\s+\\1\\b)+")

    fun clean(text: String): String {
        val t = text.replace(FILLERS, "").replace(STUTTER, "$1").replace(Regex("\\s{2,}"), " ").trim()
        return t.ifEmpty { text.trim() }
    }
}
