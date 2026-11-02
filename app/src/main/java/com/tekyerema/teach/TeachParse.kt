package com.tekyerema.teach

/**
 * Teach mode — what the user says, as commands. Pure → unit-tested. English only (like the rest of the offline brain).
 *
 *   start   "watch me", "let me show you how to check my data bundle", "teach you", "record my steps"
 *   stop    "stop", "that's it", "I'm done" — optionally with the name: "stop, call it data bundle"
 *   name    "call it data bundle" / "data bundle" (when a name is being asked for)
 *   run     "data bundle", "do data bundle", "run my data bundle skill"
 *   list    "what skills do I have"
 *   delete  "delete the data bundle skill"
 *   steps   "what does data bundle do"
 */
object TeachParse {
    private fun norm(raw: String) = raw.trim().lowercase().replace('’', '\'').replace(Regex("\\s+"), " ")
        .replace(Regex("^(?:hey |okay |ok )?(?:tekyerema|tɛkyerɛma|nexus)[, ]+"), "").replace(Regex("^please "), "")
        .replace(Regex("[,;]? (?:please|thanks|thank you)$"), "").trimEnd('.', '!', '?', ',').trim()

    /** A name as it will be said again: lowercase, trimmed, no quotes or a leading "the"/"my". */
    fun cleanName(s: String): String = s.trim().trim('"', '“', '”', '\'', '‘', '’', ',', ':', '.').lowercase()
        .replace(Regex("\\s+"), " ").replace(Regex("^(?:the |my |a |an )"), "").replace(Regex(" skill$"), "").trim()

    // ---------------------------------------------------------------- start
    private val START_PLAIN = Regex("^(?:watch me|watch what i do|watch this|look at what i do|learn from me|teach mode|start teach(?:ing)?(?: mode)?|" +
        "start learning|start recording|record my steps|record these steps|record this|i want to teach you|i will teach you|i'll teach you|" +
        "teach you something|let me teach you(?: something)?|let me show you(?: something)?|i'll show you|i will show you|show you something|" +
        "i want to show you something)$")
    private val START_HOW = Regex("^(?:(?:let me|i'll|i will|i want to|i'd like to) (?:show|teach) you(?: how)?(?: to)?|(?:show|teach) you how to|" +
        "watch me (?:as i )?(?:to )?|watch how i|let me show you how to) ?(.*)$")

    /** The task named in "let me show you how to check my data bundle" ("check my data bundle"), "" for none, null = not a start. */
    fun parseStart(raw: String): String? {
        val g = norm(raw)
        if (START_PLAIN.matches(g)) return ""
        val m = START_HOW.matchEntire(g) ?: return null
        val task = m.groupValues[1].trim()
        if (task.isEmpty() && !g.contains("show") && !g.contains("teach") && !g.contains("watch")) return null
        return task
    }

    // ---------------------------------------------------------------- stop / name / cancel
    private val STOP = "(?:stop(?: (?:recording|teaching|watching|the recording))?|that'?s it|that is it|that'?s all|that is all|i'?m done|i am done|" +
        "i'?m finished|i am finished|finished|all done|end (?:recording|teaching)|finish(?: recording)?)"
    private val NAME_LEAD = "(?:(?:and )?(?:then )?(?:call|name|save|label) (?:it|this|that)(?: as| to| by)?|(?:and )?(?:it'?s|it is|its) called|(?:and )?the name is|(?:and )?name is|called)"
    private val STOP_ONLY = Regex("^$STOP$")
    private val STOP_NAMED = Regex("^$STOP[, ]+$NAME_LEAD ?(.+)$")
    private val NAME_ONLY = Regex("^$NAME_LEAD ?(.+)$")
    private val CANCEL = Regex("^(?:cancel|never ?mind|forget it|forget that|discard(?: it| that| this)?|throw (?:it|that) away|don'?t save(?: it)?)(?: (?:recording|teaching|that|this|it))?$")

    sealed class Stop { object Plain : Stop(); data class Named(val name: String) : Stop() }

    fun parseStop(raw: String): Stop? {
        val g = norm(raw)
        if (STOP_ONLY.matches(g)) return Stop.Plain
        STOP_NAMED.matchEntire(g)?.let { return Stop.Named(cleanName(it.groupValues[1])) }
        return null
    }

    /** "call it data bundle" → "data bundle"; with [bareOk] a bare phrase is a name too (the answer to "what shall I call it?"). */
    fun parseName(raw: String, bareOk: Boolean): String? {
        val g = norm(raw)
        NAME_ONLY.matchEntire(g)?.let { return cleanName(it.groupValues[1]).takeIf { n -> n.isNotEmpty() } }
        return if (bareOk) cleanName(g).takeIf { it.isNotEmpty() } else null
    }

    fun isCancel(raw: String) = CANCEL.matches(norm(raw))

    // ---------------------------------------------------------------- run / list / delete / steps
    private val RUN_LEAD = Regex("^(?:(?:run|do|use|replay|repeat|perform|play back) (?:my |the )?)?")
    private val SKILL_TAIL = Regex("(?: skill)?$")
    /** "start" / "launch" / "open" / "play" are the phone's own commands: with them the word "skill" must be said. */
    private val PHONE_LEAD = Regex("^(?:start|launch|open|play) ")
    private val SAID_SKILL = Regex(" skill$")

    /** The skill named by the whole utterance, or null. */
    fun matchRun(raw: String, names: Collection<String>): String? {
        if (names.isEmpty()) return null
        val g = norm(raw).replace(",", "")
        if (PHONE_LEAD.containsMatchIn(g)) {
            if (!SAID_SKILL.containsMatchIn(g)) return names.firstOrNull { it == g }
            val core = g.replace(PHONE_LEAD, "").replace(Regex("^(?:my |the )"), "").replace(SAID_SKILL, "").trim()
            return names.firstOrNull { it == core }
        }
        val core = g.replace(RUN_LEAD, "").replace(SKILL_TAIL, "").trim()
        return names.firstOrNull { it == g || it == core }
    }

    private val LIST = Regex("^(?:what|which) skills (?:do i have|have i (?:got|taught you|shown you)|did i (?:teach|show) you|do you know|have you learned|have you learnt)$|" +
        "^what (?:have i|did i) (?:taught|teach|shown|show) you$|" +
        "^(?:list|read|tell me) (?:all )?(?:of )?my (?:taught )?skills$|^what are my skills$")
    fun isList(raw: String) = LIST.matches(norm(raw))

    private val DELETE = Regex("^(?:delete|remove|forget|erase) (?:the |my )?(?:skill )?(.{2,40}?)(?: skill)?$")
    /** "delete the data bundle skill" → "data bundle" — only a name the caller then finds among its skills. */
    fun parseDelete(raw: String, names: Collection<String>): String? {
        if (names.isEmpty()) return null
        val n = DELETE.matchEntire(norm(raw))?.let { cleanName(it.groupValues[1]) } ?: return null
        return names.firstOrNull { it == n }
    }

    private val STEPS = Regex("^(?:what (?:does|do) (?:my |the )?(.{2,40}?)(?: skill)? do|what (?:are|is) (?:in|the steps (?:of|in|for)) (?:my |the )?(.{2,40}?)(?: skill)?|" +
        "read (?:me )?(?:the )?steps (?:of|in|for) (?:my |the )?(.{2,40}?)(?: skill)?|how does (?:my |the )?(.{2,40}?)(?: skill)? work)$")
    fun parseSteps(raw: String, names: Collection<String>): String? {
        val m = STEPS.matchEntire(norm(raw)) ?: return null
        val n = (1..4).map { m.groupValues[it] }.firstOrNull { it.isNotBlank() }?.let(::cleanName) ?: return null
        return names.firstOrNull { it == n }
    }
}
