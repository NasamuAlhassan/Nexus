package com.tekyerema.learning

/**
 * The words of learning from mistakes, offline and pure (unit-tested: FailureCoreTest). Two jobs:
 *  - [classify]: the user's own controls — "learn from your mistakes", "stop learning from your mistakes", "forget my mistakes",
 *    "share my mistakes for training", "what mistakes have you kept";
 *  - [isCorrection]: "no, that's wrong", "that's not what I asked", "you got it wrong" — the user telling Nexus a finished task was
 *    not right. Only ever read after a task, to attach the (redacted) words to that task's note.
 */
object FailureParse {
    enum class Cmd { ON, OFF, FORGET, SHARE, STATUS }

    private const val OWNER = "(?:your|the|my|our)"

    private val ON = Regex("^(?:(?:start|begin|keep) )?(?:learn|learning) from $OWNER mistakes$|^turn on (?:learning from (?:$OWNER )?mistakes|mistake learning)$")
    private val OFF = Regex("^(?:stop|quit|end) (?:learning|learn) from $OWNER mistakes$|" +
        "^(?:don't|do not|dont) (?:keep )?(?:learn|learning) from $OWNER mistakes$|^turn off (?:learning from (?:$OWNER )?mistakes|mistake learning)$")
    private val FORGET = Regex("^(?:forget|delete|erase|clear|remove|wipe)(?: all)?(?: of)? $OWNER mistakes$|" +
        "^(?:forget|delete|erase|clear) what you (?:learned|learnt) from (?:your )?mistakes$")
    private val SHARE = Regex("^(?:share|export|send|give)(?: me)?(?: all)?(?: of)? $OWNER mistakes\\b.*$|" +
        "^(?:share|export) what you (?:learned|learnt) from (?:your )?mistakes\\b.*$")
    private val STATUS = Regex("^(?:what|which) mistakes\\b.*$|^how many mistakes\\b.*$|^what (?:have you|did you) (?:learned|learnt|learn) from (?:your )?mistakes$")

    /** Lower-case words only, curly quotes straight, no punctuation, no "please" / "Nexus" around it. */
    internal fun norm(raw: String): String = raw.lowercase().replace('’', '\'')
        .replace(Regex("[^\\p{L}\\p{N}'\\s]"), " ").replace(Regex("\\s+"), " ").trim()
        .replace(Regex("^(?:(?:hey|ok|okay) )?(?:nexus )?(?:please )?"), "").removeSuffix(" please").trim()

    /** The command in [raw], or null when it is not about learning from mistakes. */
    fun classify(raw: String): Cmd? {
        val t = norm(raw)
        if (t.isEmpty() || !t.contains("mistake")) return null
        return when {
            ON.matches(t) -> Cmd.ON
            OFF.matches(t) -> Cmd.OFF
            FORGET.matches(t) -> Cmd.FORGET
            STATUS.matches(t) -> Cmd.STATUS
            SHARE.matches(t) && t.split(' ').size <= 14 -> Cmd.SHARE
            else -> null
        }
    }

    private const val LEAD = "(?:(?:no|nope|wait|oh no|hey|sorry) ?)*"
    private val CORRECTION = listOf(
        Regex("^${LEAD}(?:that's|that is|thats|this is|it's|it is|that was) (?:not what i (?:asked|wanted|said|meant|told you)(?: for)?|" +
            "(?:all |totally |completely |so )?wrong|not right|incorrect|not it|not correct|the wrong (?:one|thing|app|person|name|number))\\b.*$"),
        Regex("^${LEAD}you (?:got|did|have got) (?:it|that|this) (?:all )?wrong\\b.*$"),
        Regex("^${LEAD}(?:wrong|wrong one|wrong app|wrong person|not that one|not that|not this one)$"),
        Regex("^${LEAD}i (?:didn't|did not|never) ask for (?:that|this)\\b.*$"),
        Regex("^(?:no|nope) i (?:said|meant|wanted|asked for)\\b.*$")
    )

    /** True when [raw] is the user saying the last task was wrong (short utterances only). */
    fun isCorrection(raw: String): Boolean {
        val t = norm(raw)
        if (t.isEmpty() || t.split(' ').size > 16) return false
        return CORRECTION.any { it.matches(t) }
    }
}
