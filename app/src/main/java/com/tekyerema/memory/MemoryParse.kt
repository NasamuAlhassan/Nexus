package com.tekyerema.memory

/** What the user asked of their memory, in so many words (pure; see [MemoryCommands] for what is done about it). */
sealed class MemCmd {
    object Summary : MemCmd()
    data class About(val name: String) : MemCmd()
    object Usual : MemCmd()
    object ForgetAll : MemCmd()
    data class ForgetKind(val kind: MemKind) : MemCmd()
    data class ForgetAbout(val name: String) : MemCmd()
    data class SetPref(val key: String, val value: String) : MemCmd()
    data class SetFact(val subject: String, val value: String) : MemCmd()
    /** "that's wrong, it is X" ([needsRecent]: only "no, it is X" — counts only just after something was remembered). */
    data class Correct(val text: String, val needsRecent: Boolean) : MemCmd()
    /** [kind] null = every category. */
    data class Switch(val kind: MemKind?, val on: Boolean) : MemCmd()
    object More : MemCmd()
    /** A fact kept in the user's own words ("the meeting with Richmond is in N Block, room 3", "Richmond is only on Telegram"). */
    data class Note(val text: String) : MemCmd()
}

/** Whole-utterance patterns only: a longer or compound request never matches and still reaches the agent. */
object MemoryParse {
    private fun norm(raw: String): String =
        raw.trim().replace('’', '\'').replace(Regex("""\s+"""), " ").trimEnd('.', '!', '?').trim()
            .replace(Regex("""(?i)^(?:please|okay|ok|hey)[, ]+"""), "").trim()

    private val SUMMARY = Regex("""(?i)^(?:what do you (?:remember|know|keep) about me|what have you (?:remembered|learned|saved|kept)(?: about me)?|what do you remember|(?:review|show me|list) (?:my |your )?memor(?:y|ies)|what(?:'s| is) in your memory)$""")
    private val ABOUT = Regex("""(?i)^what do you (?:remember|know) about (?!(?:me|you|it|this|that|them|him|her|us)$)(.{2,40})$""")
    private val COMPOUND = Regex("""(?i)\b(?:and|then|but)\b|[,;]""")
    private val USUAL =Regex("""(?i)^(?:what (?:do|did) i (?:usually|often|normally|always) (?:ask|say|request)(?: you| you for)?|what are my (?:usual|common|frequent) (?:tasks|requests))$""")
    private val FORGET_ALL = Regex("""(?i)^(?:forget everything(?: about me| you (?:remember|know|keep)(?: about me)?)?|(?:clear|erase|wipe|delete) (?:your |my )?memory|forget all (?:that|of it|about me))$""")
    private val FORGET_CONTACTS = Regex("""(?i)^(?:forget|clear|delete|erase) (?:my |the )?(?:contacts? history|frequent contacts|(?:who|the people) i (?:call|contact|text|message)(?: most| often)?|contacts i (?:use|call) (?:most|often))$""")
    private val FORGET_TASKS = Regex("""(?i)^(?:forget|clear|delete|erase) (?:my |the )?(?:(?:common|usual|frequent|repeated) (?:tasks|requests)|tasks history|what i (?:usually|often) ask)$""")
    private val FORGET_PREFS = Regex("""(?i)^(?:forget|clear|delete|erase) (?:my )?(?:saved )?preferences$""")
    private val FORGET_FACTS = Regex("""(?i)^(?:(?:forget|clear|delete|erase) (?:the )?(?:things|facts) (?:i (?:told|taught) you|about me)|forget what i told you)$""")
    private val FORGET_ABOUT = Regex("""(?i)^forget (?:everything about |all about |about )?((?!(?:that|it|this|everything|all|my|the|what|those|these)\b).{2,40})$""")
    private const val WHAT = """(contacts?|who i (?:call|contact|text|message)|tasks|requests|what i ask|preferences|facts|things i (?:say|tell you)|anything|everything|things)"""
    private val STOP = Regex("""(?i)^(?:stop|quit|don't|do not|no longer) (?:remembering|keeping|tracking|learning|saving|noting)(?: track of)? (?:my |the |any )?$WHAT(?: history)?$""")
    private val START = Regex("""(?i)^(?:start|resume|begin|go back to) (?:remembering|keeping|tracking|learning|saving|noting)(?: track of)? (?:my |the )?$WHAT(?: history)?$""")
    private val CORRECT = Regex("""(?i)^(?:no,? |sorry,? |hmm,? )?(?:that(?:'s| is) (?:wrong|not right|not correct|incorrect)|you(?:'re| are) wrong)[,.]? (?:it(?:'s| is) |it should be |actually )?(.{1,60})$""")
    private val CORRECT_SHORT = Regex("""(?i)^no,? it(?:'s| is) (.{1,60})$""")
    private val MORE = Regex("""(?i)^(?:more|continue|go on|keep going|carry on|next|the rest|yes(?: please)?|read more|tell me more)$""")
    private val REMEMBER = Regex("""(?i)^(?:remember|note|know) (?:that )?(.+)$""")
    /** "No calendar. Just keep this in mind: …" — the lead that refuses an event before the note (A20e 8 Nov 2026). */
    private val LEAD_NO = Regex("""(?i)^(?:no[,.]?\s+)?(?:no |not (?:in )?(?:the |a |my )?|don'?t (?:put|add|save|make) (?:it|this|that|an?) (?:in|to|as) (?:the |my |an? )?)(?:calendar|reminder|event|alarm)s?[.,!]?\s+""")
    /** A fact to keep as said: "remember (that) …", "keep (this) in mind: …", "note (that) …", "don't forget (that) …" — never
     *  "remember TO …" (that is a reminder) and never a relationship ("Ama is my mother", the contact-checked path). */
    private val NOTE = Regex("""(?i)^(?:just\s+|please\s+)*(?:remember|note|keep (?:this |it |that )?in mind|bear in mind|make a note|note down|don'?t forget|keep in mind)(?: that| this|:)?[,:]?\s+(?!to\b)(.{4,120})$""")
    /** "Richmond is only on Telegram" / "Ama is on WhatsApp only": where a person can be reached. */
    private val ONLY_ON = Regex("""(?i)^([\p{L}' .-]{2,40}?) (?:is|are) (?:only (?:on|reachable on|available on) (whatsapp|telegram|signal|messenger|sms|text)|(?:on|reachable on|available on) (whatsapp|telegram|signal|messenger|sms|text) only)$""")

    private val RATE = Regex("""(?i)^i (?:prefer|like|want|need) (?:you to )?(?:(?:speak|talk|read|go|speech|voice|reading)(?: speed)? )?(slow|slowly|slower|fast|faster|quick|quickly|normal)(?: speech| speed| voice| pace| reading)?$""")
    private val LANG = Regex("""(?i)^i (?:prefer|like|want) (?:you to (?:speak|talk|reply|answer)(?: in| to me in)? |to (?:hear|talk|speak) (?:in )?)?(twi|english)(?: for (messages|calls|money|reading|news|replies))?$""")
    private val VERB = Regex("""(?i)^i (?:prefer|like|want) (short|brief|long|detailed|more detailed|more detail)(?: answers| replies| responses)?$""")
    private val FACT_MY = Regex("""(?i)^my ([\p{L}' ]{2,30}?) (?:is|are) (.{1,60})$""")
    private val FACT_I = Regex("""(?i)^i ((?:live|work|study|stay|worship|attend|teach|farm|sell|use|have|own)(?: (?:in|at|as|from|near|with))?|am(?: a| an| from)?|was born(?: in)?|like|love|enjoy|hate|dislike) (.{1,60})$""")

    data class PrefReq(val key: String, val value: String)
    data class FactReq(val subject: String, val value: String)

    /** "i prefer slow speech" / "i prefer Twi for messages" / "i like short answers" → the preference. */
    fun parsePref(body: String): PrefReq? {
        val b = body.trim().trimEnd('.', '!', '?')
        RATE.matchEntire(b)?.let { m ->
            val w = m.groupValues[1].lowercase()
            return PrefReq("rate", if (w.startsWith("slow")) "slow" else if (w == "normal") "normal" else "fast")
        }
        LANG.matchEntire(b)?.let { m ->
            val ctx = m.groupValues[2].lowercase()
            return PrefReq("language" + if (ctx.isNotEmpty()) ":$ctx" else "", m.groupValues[1].lowercase())
        }
        VERB.matchEntire(b)?.let { m ->
            return PrefReq("verbosity", if (m.groupValues[1].lowercase().let { it == "short" || it == "brief" }) "brief" else "detailed")
        }
        return null
    }

    /** "my church is Holy Trinity" / "I live in Accra" → (subject, value). A relation word is NOT a fact: [Relations] owns those. */
    fun parseFact(body: String): FactReq? {
        val b = body.trim().trimEnd('.', '!', '?')
        FACT_MY.matchEntire(b)?.let { m ->
            val subject = m.groupValues[1].trim().lowercase()
            if (com.tekyerema.android.Relations.canonical(subject) != null) return null
            return FactReq("my $subject", m.groupValues[2].trim())
        }
        FACT_I.matchEntire(b)?.let { m -> return FactReq("i " + m.groupValues[1].lowercase(), m.groupValues[2].trim()) }
        return null
    }

    private fun kindOf(what: String): MemKind? {
        val w = what.lowercase()
        return when {
            w.startsWith("contact") || w.startsWith("who i") -> MemKind.CONTACT
            w == "tasks" || w == "requests" || w == "what i ask" -> MemKind.TASK
            w == "preferences" -> MemKind.PREF
            w == "facts" || w.startsWith("things i") -> MemKind.FACT
            else -> null   // anything / everything / things
        }
    }

    fun classify(raw: String): MemCmd? {
        val g = norm(raw)
        if (g.isEmpty()) return null
        if (SUMMARY.matches(g)) return MemCmd.Summary
        if (USUAL.matches(g)) return MemCmd.Usual
        ABOUT.matchEntire(g)?.let { m ->
            // "what do you remember about me and call Ama" is two requests, not a name.
            if (COMPOUND.containsMatchIn(m.groupValues[1])) return null
            return MemCmd.About(m.groupValues[1].trim().removePrefix("my ").trim())
        }
        if (FORGET_ALL.matches(g)) return MemCmd.ForgetAll
        if (FORGET_CONTACTS.matches(g)) return MemCmd.ForgetKind(MemKind.CONTACT)
        if (FORGET_TASKS.matches(g)) return MemCmd.ForgetKind(MemKind.TASK)
        if (FORGET_PREFS.matches(g)) return MemCmd.ForgetKind(MemKind.PREF)
        if (FORGET_FACTS.matches(g)) return MemCmd.ForgetKind(MemKind.FACT)
        STOP.matchEntire(g)?.let { return MemCmd.Switch(kindOf(it.groupValues[1]), on = false) }
        START.matchEntire(g)?.let { return MemCmd.Switch(kindOf(it.groupValues[1]), on = true) }
        CORRECT.matchEntire(g)?.let { return MemCmd.Correct(it.groupValues[1].trim(), needsRecent = false) }
        CORRECT_SHORT.matchEntire(g)?.let { return MemCmd.Correct(it.groupValues[1].trim(), needsRecent = true) }
        if (MORE.matches(g)) return MemCmd.More
        val gn = g.replace(LEAD_NO, "").trim()
        REMEMBER.matchEntire(gn)?.let { m ->
            // "remember that Ama is my mother" / "my boss is Kofi" are relationships: the contact-checked path owns them.
            if (com.tekyerema.android.Relations.parseSet(raw) != null) return null
            val body = m.groupValues[1].trim()
            parsePref(body)?.let { return MemCmd.SetPref(it.key, it.value) }
            parseFact(body)?.let { return MemCmd.SetFact(it.subject, it.value) }
            // A fuller statement said to be remembered is kept as said ("the meeting with Richmond is in N Block, room 3, at half
            // past ten" — A20e 8 Nov 2026: it went to the brain, which made a wrong calendar event). "Remember to …" is a reminder,
            // and a short "remember that the meeting is at nine" may be one too: those stay with the agent (the brain has `remember`).
            if (!body.startsWith("to ", ignoreCase = true) && body.length in 4..120 && body.split(' ').size >= 6) return MemCmd.Note(body)
            return null
        }
        NOTE.matchEntire(gn)?.let { m ->
            if (com.tekyerema.android.Relations.parseSet(raw) != null) return null
            val body = m.groupValues[1].trim()
            parsePref(body)?.let { return MemCmd.SetPref(it.key, it.value) }
            parseFact(body)?.let { return MemCmd.SetFact(it.subject, it.value) }
            return MemCmd.Note(body)
        }
        ONLY_ON.matchEntire(gn)?.let { return MemCmd.Note(gn) }
        FORGET_ABOUT.matchEntire(g)?.let { return MemCmd.ForgetAbout(it.groupValues[1].trim().removeSuffix("'s messages").removeSuffix(" messages").trim()) }
        return null
    }

    // ---------------------------------------------------------------- the answer to "erase it all?"
    enum class Wipe { YES, NO, OTHER }
    private val WIPE_YES = Regex("""(?i)^(?:yes|yeah|yep|aane)(?: please)?(?:,? (?:forget|erase|delete|clear) (?:it|everything|it all|all of it))?$|^(?:forget|erase|clear) (?:it|everything|it all)$""")
    private val WIPE_NO = Regex("""(?i)^(?:no|nope|cancel|stop|don't|do not|never mind|daabi|keep it|keep everything)\b.*$""")

    fun answerToWipe(raw: String): Wipe {
        val g = norm(raw).replace(",", "")
        return when { WIPE_YES.matches(g) -> Wipe.YES; WIPE_NO.matches(g) -> Wipe.NO; else -> Wipe.OTHER }
    }
}
