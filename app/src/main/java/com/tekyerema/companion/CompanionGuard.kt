package com.tekyerema.companion

/**
 * Companion mode's hard rules, as code (pure, unit-tested in CompanionGuardTest):
 *  - [level] — does the user sound distressed? Self-harm words (CRISIS) are found anywhere in a sentence; low mood
 *    ("I'm so lonely", "I feel like giving up") only as a short statement about oneself, so an ordinary command that
 *    happens to hold a sad word ("read my messages, I'm alone in the car") is never taken for one.
 *  - [reply] — the warm answer: no diagnosis, no lecture, a person the user trusts first (their helper when one is set),
 *    the emergency line when they may hurt themselves. Spoken by the fast path, never made up by a model.
 *  - [HONEST_AI], [closing] — fixed, honest lines.
 *
 * No phone number is invented here. The emergency line is the one the app already uses (`os.emergency.numbers.emergency`,
 * default 112). TODO(owner): Ghana's mental-health helpline is NOT in the code base — supply a verified number in
 * `companion.helpline.number` (and its name in `companion.helpline.name`) and it is added to the crisis reply.
 */
object CompanionGuard {
    enum class Level { NONE, LOW, CRISIS }

    private fun n(raw: String): String = raw.trim().lowercase().replace('’', '\'').replace('‘', '\'').replace(Regex("\\s+"), " ")

    // Self-harm and suicide words: serious wherever they appear in the sentence.
    private val CRISIS = listOf(
        Regex("\\bkill(?:ing)? myself\\b"),
        Regex("\\bsuicid(?:e|al)\\b"),
        Regex("\\b(?:end|ending|take|taking) (?:my|my own) (?:own )?life\\b"),
        Regex("\\b(?:i )?(?:want|wanna|would like|wish|need) (?:to )?(?:die|be dead)\\b"),
        Regex("\\bwish (?:i|that i) (?:was|were) dead\\b"),
        Regex("\\b(?:don'?t|do not|no longer|not) want to (?:live|be alive|wake up)(?: anymore| any more)?\\b"),
        Regex("\\bno (?:reason|point) (?:in |to )?(?:live|living|life|go on|going on|continue|continuing)\\b"),
        Regex("\\bbetter off (?:dead|without me)\\b"),
        Regex("\\b(?:hurt|harm|cut|injure) myself\\b"),
        Regex("\\bwish (?:i|that i) (?:would not|wouldn'?t|did not|didn'?t|will not|won'?t) wake up\\b"),
        Regex("\\bwish (?:i|that i) (?:was|were) gone\\b"),
        Regex("\\bbetter if i (?:was|were) gone\\b"),
        Regex("\\bwish to disappear\\b"),
        Regex("\\bi(?:'?m| am) a burden\\b"),
        Regex("\\bend it all\\b")
    )

    // Low mood: a short statement about oneself ("i feel so lonely", "i'm hopeless", "i feel like giving up").
    private const val FEEL = "(?:i(?:'?m| am)|i feel|i'?m feeling|i am feeling|i feel so|i'?ve been feeling)"
    private const val DEG = "(?:so |very |really |too |just |completely |totally )*"
    private val LOW = listOf(
        Regex("^$FEEL $DEG(?:lonely|alone|hopeless|worthless|useless|depressed|empty|miserable|broken|unloved|invisible)(?: (?:tonight|today|now|again|lately))?$"),
        Regex("^$FEEL $DEG(?:sad|down|low|heavy|overwhelmed|scared|afraid|anxious)(?: (?:tonight|today|now|again|lately|in my heart))?$"),
        Regex("^i feel like (?:giving up|giving in|quitting everything|i can'?t go on|i can'?t cope|i should give up)$"),
        Regex("^i (?:want|wanna) to give up(?: on (?:life|everything))?$"),
        Regex("^i(?:'?m| am) thinking (?:of|about) giving up(?: on (?:life|everything))?$"),
        Regex("^i (?:can'?t|cannot|can not) (?:go on|cope|take it|take this|take it anymore|take this anymore|do this anymore|carry on)$"),
        Regex("^(?:nobody|no one) (?:cares|loves me|likes me|cares about me|is there for me)$"),
        Regex("^i have (?:nobody|no one|no friends)(?: (?:left|to talk to))?$"),
        Regex("^i(?:'?m| am) (?:tired of|sick of|fed up with) (?:everything|life|living|it all)$"),
        Regex("^my heart is (?:heavy|broken)$"),
        Regex("^i feel (?:so )?(?:alone|lonely)(?: tonight| today| now)?$"),
        Regex("^i don'?t feel (?:good|okay|ok|well)(?: in my heart)?(?: today| tonight)?$")
    )

    fun level(raw: String): Level {
        val g = n(raw)
        if (g.isEmpty()) return Level.NONE
        if (CRISIS.any { it.containsMatchIn(g) }) return Level.CRISIS
        val s = g.trimEnd('.', '!', '?', ',', ' ').replaceFirst(Regex("^(?:hey |hi |hello |ok |okay |so |chale |nexus )+"), "")
        // A long sentence is a story or a command with a sad word in it, not this statement.
        if (s.split(' ').size <= 14 && LOW.any { it.matches(s) }) return Level.LOW
        return Level.NONE
    }

    /** "1 1 2" — a number said digit by digit. */
    fun digits(number: String): String = number.filter { it.isLetterOrDigit() }.toList().joinToString(" ")

    /**
     * The warm reply. [helper]: the name of the user's trusted helper, or null. [emergency]: the emergency number (digits).
     * [helpline] / [helplineName]: a verified mental-health line the owner configured, else null — never made up.
     * [pick]: which of the wordings (tests pass a fixed one).
     */
    fun reply(level: Level, helper: String?, emergency: String = "112", helpline: String? = null, helplineName: String? = null,
              pick: Int = 0): String {
        val who = helper?.trim()?.takeIf { it.isNotEmpty() }
        val offer = if (who != null) "If you'd like, say “call my helper” and I'll ring $who."
            else "Is there a person you trust, a family member or a friend, you could talk to today?"
        return when (level) {
            Level.CRISIS -> {
                val line = helpline?.trim()?.takeIf { it.isNotEmpty() }?.let { num ->
                    " You can also call ${helplineName?.trim()?.takeIf { it.isNotEmpty() } ?: "the helpline"} on ${digits(num)}."
                }.orEmpty()
                "I'm really glad you told me, and I'm sorry you're hurting. You matter. Please don't stay alone with this: " +
                    (if (who != null) "say “call my helper” and I'll ring $who, or " else "") + "tell a person you trust right now, " +
                    "someone who can be with you. If you might hurt yourself, call ${digits(emergency)} now.$line " +
                    "I'm an AI, so I can't replace a person, but I'm here with you. Are you safe right now?"
            }
            Level.LOW -> {
                val open = LOW_OPENERS[pick.mod(LOW_OPENERS.size)]
                "$open $offer"
            }
            Level.NONE -> ""
        }
    }

    private val LOW_OPENERS = listOf(
        "I'm sorry you're feeling this way. I'm here, and I'm listening.",
        "That sounds heavy, and I'm glad you said it out loud. I'm here with you.",
        "Thank you for telling me. You don't have to carry this alone."
    )

    /** Asked "are you a human?": plain, warm, short. */
    const val HONEST_AI = "I'm an AI assistant on your phone, not a person. I do care about being useful to you, and I'm happy to chat. What would you like to talk about?"

    /** The end of a chat: no guilt, no pull to stay. */
    fun closing(raw: String): String {
        val g = n(raw)
        return when {
            g.contains("night") -> "Good night. Sleep well."
            g.contains("bye") || g.contains("later") || g.contains("see you") -> "Okay, take care. I'm here whenever you need me."
            else -> "Anytime. I'm here whenever you want to talk."
        }
    }

    /** The guidance that rides with the cloud brain while a chat is on ([CompanionSession.rulesFor]). Short on purpose. */
    const val CHAT_RULES =
        "COMPANION CHAT: the user wants company, not a phone task. Answer as a warm, brief friend: 1-3 short plain sentences (under 30 words), " +
        "no lists, no lectures, ending with ONE easy question or a small offer. Match their energy and wording. Reply in plain text as the final " +
        "answer; call no tool unless they ask for a phone action, and then do it as usual. " +
        "You are an AI: if asked, say so plainly; never claim to be human, to have a body or a family, to love them, or to promise to always be there " +
        "(say you care and are glad to help). Never guilt them, never ask them to stay or come back, never push spending, a purchase, a deal, gambling or " +
        "borrowing. Don't invent facts, news, scores or what the phone knows. No diagnosis: for health, suggest a clinic. Stay neutral on politics. " +
        "If they sound sad, lonely, hopeless or unsafe: be warm, suggest a person they trust, and tell them “call my helper” rings their helper. " +
        "Their words stay between you and them."
}
