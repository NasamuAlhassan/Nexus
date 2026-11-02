package com.tekyerema.companion

/**
 * Companion mode (MASTER_STATUS #21): "a smart friend that keeps things brief". This file is the WORDS the fast path
 * recognises for it — pure, Android-free, unit-tested (CompanionIntentsTest). Every pattern is a WHOLE utterance
 * (anchored), so a compound request ("let's talk and call Ama") never matches and still reaches the agent; only the
 * distress words are looked for anywhere in a sentence, because those must never be missed.
 *
 * What it does NOT do: classify greetings ("how are you") or open questions — the brain answers those, and while a
 * companion chat is on ([CompanionSession]) it answers them as a friend.
 */
object CompanionIntents {
    enum class Kind(val id: String) {
        /** "let's talk", "I'm bored": a local opener, then the brain carries the chat. */
        CHAT("chat"),
        /** "let's talk about football", "tell me a joke": no opener — straight to the brain, in companion mode. */
        CHAT_TOPIC("chat_topic"),
        /** "brief me", "what's my day like": the daily brief, assembled on the phone. */
        BRIEF("brief"),
        /** "teach me something", then "another one": a small lesson from the phone's own bank. */
        TEACH("teach"),
        /** "how am I doing with money this week": a gentle spending note from the ledger. */
        MONEY("money"),
        /** "are you a human?": an honest, fixed answer. */
        HONEST("honest"),
        /** "I'm so lonely", "I feel like giving up": warm, and the trusted helper offered. */
        DISTRESS("distress"),
        /** Self-harm words: warm, the helper, the emergency line. */
        CRISIS("crisis"),
        /** "that's enough", "thanks for the chat" (only while a chat is on). */
        END("end"),
        /** "stop the spending notes" / "bring the spending notes back". */
        NOTES_OFF("notes_off"),
        NOTES_ON("notes_on"),
        /** "brief me every morning at 7": the daily brief on a clock ([BriefSchedule]). */
        BRIEF_SET("brief_set"),
        /** "stop the morning brief". */
        BRIEF_OFF("brief_off");

        companion object { fun of(id: String?): Kind? = values().firstOrNull { it.id == id } }
    }

    /** Lower-case, one space, the user's apostrophes straight, no closing punctuation. */
    fun norm(raw: String): String = raw.trim().lowercase().replace('’', '\'').replace('‘', '\'')
        .replace(Regex("\\s+"), " ").trimEnd('.', '!', '?', ',', ' ').trim()

    /** The greeting and politeness in front of a request: "hey nexus, please …", "chale …". */
    private val LEAD = Regex("^(?:(?:hey|hi|hello|ok|okay|so|chale|abeg|please|kindly|yo|ei)[, ]+)*(?:nexus[, ]+)?(?:(?:please|kindly)[, ]+)?")
    private val TAIL = Regex("(?:[, ]+(?:please|now|for me|nexus|my dear))+$")

    internal fun core(raw: String): String = norm(raw).replaceFirst(LEAD, "").replace(TAIL, "").trim()

    /** Is [raw] (a whole utterance or a schedule's goal) a request for the day brief? No distress check, no schedule parsing. */
    fun isBrief(raw: String): Boolean { val g = core(raw); return g.isNotEmpty() && BRIEF.any { it.matches(g) } }

    // ---------------------------------------------------------------- chat
    private const val A_BIT = "(?: (?:a bit|a little|small|for a (?:bit|while|minute|moment)|for some time))?"
    private val CHAT = listOf(
        Regex("^(?:let'?s|let us|lets) (?:just )?(?:talk|chat|gist|have a (?:chat|talk|gist))$A_BIT$"),
        Regex("^(?:can|could|may) we (?:just )?(?:talk|chat|gist|have a (?:chat|talk))$A_BIT$"),
        Regex("^(?:talk|chat|gist) (?:to|with) me$A_BIT$"),
        Regex("^i (?:just )?(?:want|wanna|would like|need|feel like) (?:to )?(?:talk|chat|gist|have a (?:chat|talk))(?: (?:with|to) you)?$A_BIT$"),
        Regex("^(?:keep me company|entertain me|be my (?:friend|company)|stay with me)$"),
        Regex("^(?:i(?:'?m| am| dey| feel)? )?(?:so |very |really |too |just |plenty |feeling )*(?:bored|bore)(?: today| now| again| small)?$"),
        Regex("^(?:i have|i got|there is|there'?s) nothing to do(?: today| now)?$"),
        Regex("^boredom is killing me$")
    )

    /** "let's talk about football" — the topic is the brain's. */
    private val CHAT_ABOUT = listOf(
        Regex("^(?:let'?s|let us|lets|can we|could we) (?:just )?(?:talk|chat|gist) about .{2,80}$"),
        Regex("^(?:tell me|give me|say|ask me|let me hear)(?: a| an| one| another)?(?: small| quick| short| funny| clean)? (?:joke|riddle|proverb|story|funny story|puzzle)(?: small)?$"),
        Regex("^(?:make me laugh|cheer me up|say something (?:nice|funny|kind) to me)(?: small)?$"),
        Regex("^do you know any (?:jokes|riddles|proverbs|stories)$")
    )

    // ---------------------------------------------------------------- brief
    private val BRIEF = listOf(
        Regex("^brief me(?: on my day| for today| on today)?$"),
        Regex("^(?:give|read) me (?:my |a |the )?(?:quick |short |little )?(?:daily |morning |day |day'?s )?(?:brief|briefing|rundown|run down|summary)(?: for today| of my day)?$"),
        Regex("^(?:my |the )?(?:daily|morning|day) (?:brief|briefing)$"),
        Regex("^(?:catch me up|run me through|take me through|walk me through) (?:on |with |through )?(?:my |the )?(?:day|today)$"),
        Regex("^what'?s my day (?:like|looking like|going to be like)(?: today)?$"),
        Regex("^what does my day look like(?: today)?$"),
        Regex("^how'?s my day (?:looking|going to be|shaping up)(?: today)?$"),
        Regex("^how does my day look(?: today)?$"),
        Regex("^what'?s up with my day(?: today)?$"),
        Regex("^what'?s (?:up|happening|going on) (?:with me )?today$"),
        Regex("^tell me (?:about )?my day(?: today)?$")
    )

    // ---------------------------------------------------------------- teach
    private val TEACH = listOf(
        Regex("^teach me (?:something|a thing|a new thing|something new|something useful|a lesson|one thing|a little|a bit)$"),
        Regex("^tell me something (?:new|interesting|useful|i don'?t know|i should know)$"),
        Regex("^give me (?:a |one )?(?:small |quick |short |little )?(?:lesson|tip|trick)(?: today)?$"),
        Regex("^(?:i want to|i'?d like to|let me|let'?s|i wanna) learn something(?: new)?$"),
        Regex("^(?:lesson|tip) of the day$"),
        Regex("^(?:word|proverb) of the day$")
    )
    /** Only right after a lesson. */
    private val TEACH_MORE = Regex("^(?:another(?: one| lesson)?|one more|teach me another(?: one)?|next(?: one| lesson)|more(?: please)?|again)$")

    // ---------------------------------------------------------------- money
    private val MONEY = listOf(
        Regex("^how am i doing (?:with|on) (?:my )?(?:money|spending|budget)(?: (?:this week|today|so far|this month))?$"),
        Regex("^how'?s my spending(?: (?:this week|today|so far|this month))?$"),
        Regex("^how is my spending(?: (?:this week|today|so far|this month))?$"),
        Regex("^(?:did|have) i (?:spend|spent) (?:a lot|plenty|too much|much)(?: (?:today|this week|so far))?$"),
        Regex("^am i spending too much(?: (?:this week|today))?$"),
        Regex("^(?:check|review) my spending(?: (?:this week|today))?$")
    )

    // ---------------------------------------------------------------- honesty
    private const val WHO = "(?:a |an )?(?:real |actual |live )?(?:person|human|human being|robot|machine|bot|ai|computer|somebody)"
    private val HONEST = listOf(
        Regex("^(?:are you|is that you|am i (?:talking|speaking) (?:to|with)) $WHO(?: or $WHO)?$"),
        Regex("^(?:is (?:this|that) |is there )$WHO(?: talking| speaking)?(?: to me)?$"),
        Regex("^(?:are you|you are) (?:really )?(?:ai|artificial intelligence)$"),
        Regex("^who am i (?:talking|speaking) (?:to|with)$")
    )

    // ---------------------------------------------------------------- ending a chat
    private val END = Regex("^(?:that'?s|that is) (?:enough|all)(?: for (?:now|today))?(?: thank(?:s| you))?$|" +
        "^(?:enough|stop) (?:talking|chatting|teaching)$|^(?:let'?s|let us) (?:stop|end)(?: (?:talking|chatting|here))?$|" +
        "^thank(?:s| you)(?: so much| very much)?(?: for (?:the |this |our )?(?:chat|talk|company|listening|lesson|gist|time))?$|" +
        "^(?:no more|enough) (?:lessons|teaching)$|^i'?m done (?:talking|chatting)$|^(?:good ?night|goodnight|bye|goodbye|good bye|talk (?:to you )?later|see you(?: later)?)$")

    // ---------------------------------------------------------------- notes switch
    private val NOTES_OFF = listOf(
        Regex("^(?:stop|no more|turn off|switch off|skip|cut) (?:the |my |those )?(?:spending|money) (?:notes?|nudges?|reminders?|tips?|mentions?)(?: in (?:my |the )?(?:brief|briefing|day))?$"),
        Regex("^(?:don'?t|do not) (?:mention|tell me about|talk about|remind me about) (?:my )?(?:spending|money)(?: in (?:my |the )?(?:brief|briefing|day))?$"),
        Regex("^leave (?:my )?(?:spending|money) out of (?:my |the )?(?:brief|briefing|day)$")
    )
    private val NOTES_ON = listOf(
        Regex("^(?:turn on|switch on|bring back|start|resume|give me) (?:the |my |those )?(?:spending|money) (?:notes?|nudges?|reminders?|tips?)(?: again| back)?$"),
        Regex("^(?:you can |please )?(?:mention|tell me about) (?:my )?(?:spending|money) (?:again|in (?:my |the )?(?:brief|briefing|day))$")
    )

    /**
     * The companion request in [raw], or null. [chatting]: a companion chat is on ([CompanionSession.active]) — only then
     * can "that's enough" end it. [last]: what the chat did last ([CompanionSession.lastKind]) — only after a lesson
     * does "another one" ask for another lesson.
     */
    fun classify(raw: String, chatting: Boolean = false, last: Kind? = null): Kind? {
        val g = core(raw)
        if (g.isEmpty()) return null
        // Distress first, anywhere in the sentence: it must never be taken for a command (or missed).
        when (CompanionGuard.level(raw)) {
            CompanionGuard.Level.CRISIS -> return Kind.CRISIS
            CompanionGuard.Level.LOW -> return Kind.DISTRESS
            CompanionGuard.Level.NONE -> {}
        }
        if (NOTES_OFF.any { it.matches(g) }) return Kind.NOTES_OFF
        if (NOTES_ON.any { it.matches(g) }) return Kind.NOTES_ON
        // The brief on a clock: "stop the morning brief", "brief me every morning at 7" (whole utterances; a compound request
        // such as "… at 7 and call Ama" leaves a goal that is not the brief, so it stays the schedule path's).
        if (BriefSchedule.isOff(g)) return Kind.BRIEF_OFF
        if (BriefSchedule.parseSet(raw) != null) return Kind.BRIEF_SET
        if (HONEST.any { it.matches(g) }) return Kind.HONEST
        if (last == Kind.TEACH && chatting && TEACH_MORE.matches(g)) return Kind.TEACH
        if (chatting && END.matches(g)) return Kind.END
        if (BRIEF.any { it.matches(g) }) return Kind.BRIEF
        if (MONEY.any { it.matches(g) }) return Kind.MONEY
        if (TEACH.any { it.matches(g) }) return Kind.TEACH
        if (CHAT.any { it.matches(g) }) return Kind.CHAT
        if (CHAT_ABOUT.any { it.matches(g) }) return Kind.CHAT_TOPIC
        return null
    }

    /** "this week" in a spending question (else: today). */
    fun wantsWeek(raw: String): Boolean = Regex("\\b(?:this week|week|so far this week|last 7 days)\\b").containsMatchIn(norm(raw))
}
