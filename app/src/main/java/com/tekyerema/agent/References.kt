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

    fun notePerson(name: String?) { name?.trim()?.takeIf { it.length in 2..60 && !it.all { c -> c.isDigit() || c == '+' } }?.let { lastPerson = it } }
    fun noteOptions(options: List<String>) { if (options.size >= 2) lastOptions = options.take(6) }
    fun noteNumber(number: String?) { number?.filter { it.isDigit() || it == '+' }?.takeIf { it.count(Char::isDigit) in 9..13 }?.let { lastNumber = it } }

    /** "Multiple contacts match 'ama' (…): Ama Mensah (024…); Ama Boateng (055…)" → [Ama Mensah, Ama Boateng]. */
    fun optionsFrom(message: String): List<String> {
        val body = message.substringAfter(':', "").substringBefore(". Which").substringBefore("Which one")
        return body.split(';', ',').map { it.substringBefore('(').trim().trimEnd('.') }.filter { it.length in 2..60 }
    }

    private val ORDINALS = mapOf("first" to 0, "1st" to 0, "second" to 1, "2nd" to 1, "third" to 2, "3rd" to 2,
        "fourth" to 3, "4th" to 3, "fifth" to 4, "5th" to 4)
    private val ORDINAL = Regex("\\bthe (first|1st|second|2nd|third|3rd|fourth|4th|fifth|5th|last) one\\b", RegexOption.IGNORE_CASE)
    private val PRONOUN = Regex("\\b(call|ring|phone|text|message|sms|reply to|tell|answer|write to|send)\\s+(him|her|them)\\b", RegexOption.IGNORE_CASE)
    // "this number" is the one on the screen (LocalBrain reads it there); "that number" is the one just read out.
    private val THAT_NUMBER = Regex("\\b(?:call|ring|phone|dial|text|message|sms|save)\\s+(that number)\\b", RegexOption.IGNORE_CASE)

    /** The goal with its reference replaced, or null when there is nothing (resolvable) to replace. */
    fun rewrite(goal: String, person: String? = lastPerson, options: List<String> = lastOptions, number: String? = lastNumber): String? {
        THAT_NUMBER.find(goal)?.let { m ->
            val n = number ?: return null
            return goal.replaceRange(m.groups[1]!!.range, n)
        }
        ORDINAL.find(goal)?.let { m ->
            val key = m.groupValues[1].lowercase()
            val idx = if (key == "last") options.lastIndex else ORDINALS[key] ?: return null
            val pick = options.getOrNull(idx) ?: return null
            return goal.replaceRange(m.range, pick)
        }
        PRONOUN.find(goal)?.let { m ->
            val who = person ?: return null
            return goal.replaceRange(m.groups[2]!!.range, who)
        }
        return null
    }
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
