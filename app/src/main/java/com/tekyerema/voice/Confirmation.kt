package com.tekyerema.voice

/**
 * Confirmation by voice. When the agent reaches a consequential step it pauses and asks; the
 * user answers by speaking, not by finding a button. This object turns the agent's short prompt
 * ("Call Prince?") into a spoken question and turns the user's reply into a decision.
 *
 * Safety: only a clear, unmixed affirmative counts as YES. Anything mixed ("yes… no wait") or
 * unrelated is UNCLEAR, and the controller asks again once before declining. Pure Kotlin.
 */
object Confirmation {
    /**
     * The user asks to hear the question again ("say that again", "pardon", "repeat", Twi "ka bio", "san ka", "mante"): the
     * whole read-back is said again, and it is not counted as an unclear answer (research, 20 Oct 2026: re-hearing a money
     * read-back is a basic of conversation design; before, "ka bio" used an attempt and gave only "Mante no…"). Pure.
     */
    fun asksToRepeat(vararg said: String?): Boolean = said.any { t ->
        val s = t?.trim()?.lowercase()?.replace(Regex("[.!?,]"), " ")?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        s.isNotEmpty() && REPEAT_ASK.matches(s)
    }
    private val REPEAT_ASK = Regex("^(?:(?:please|sorry|eh|ei|hey)\\s+)*(?:repeat(?: (?:it|that))?|say (?:it|that) again|again|come again|pardon(?: me)?|" +
        "what did you say|i didn'?t (?:hear|get|catch) (?:it|that|you)|(?:san )?ka (?:no )?bio|san ka|mante(?: no| ase)?|mente ase|" +
        // a question about the read-back itself is a request to hear it again (owner's live test, 22 Oct 2026: "what is the
        // number again" was taken as a correction, re-planned by the cloud, and the task ended on a question)
        "what(?:'s| is| was)? (?:the |that )?(?:number|amount|name|reference)(?: again)?|which number|read (?:it|that) again|" +
        "repeat the (?:number|amount|name|reference))(?:\\s+(?:please|mepa wo kyɛw|mepa wo kyew))?$")

    /** HOLD (B-09): "wait" / "hold on" alone — the user needs a moment; the question stays pending. */
    enum class Answer { YES, NO, UNCLEAR, HOLD }

    /** "Call Prince?" → "You want me to call Prince. Should I go ahead?" */
    fun question(prompt: String): String {
        val body = prompt.trim().trimEnd('?', '.', '!')
            .replace(Regex("[?.!]\\s*\\n"), "\n")            // "Send this?\n“msg”": the question mark belongs at the end
            .replace("\n", " — ").replace(Regex("\\s+"), " ")
        if (body.isEmpty()) return "Should I go ahead?"
        val lower = body.replaceFirstChar { it.lowercase() }
        val phrased = if (lower.startsWith("proceed with ")) "you want me to " + lower.removePrefix("proceed with ")
        else "you want me to $lower"
        return phrased.replaceFirstChar { it.uppercase() } + ". Should I go ahead?"
    }

    const val REPEAT = "Sorry, I didn't catch that. Should I go ahead? Say yes or no."
    const val DECLINED_UNCLEAR = "I didn't get a clear yes, so I'll leave it."
    /** The same in a Twi session (it was always said in English — A20e, 4 October 2026): "Okay, I won't do it", a line
     *  the lab voice has already recorded (voice/LabClips — instant, no quota); the re-ask before it said "Mante no". */
    const val DECLINED_UNCLEAR_TWI = "Yoo, merenyɛ."
    const val LISTEN_HINT = "Say yes or no"

    // Whole-word / short-phrase signals. Twi: aane = yes, yoo = okay, kɔ so = go on, yɛ = do it;
    // daabi = no, gyae = stop, mma = don't. ASCII spellings included for ASR variants.
    private val YES = setOf(
        "yes", "yeah", "yep", "yup", "ya", "yah", "sure", "ok", "okay", "alright", "fine", "correct", "right",
        "confirm", "confirmed", "proceed", "continue", "affirmative", "definitely", "absolutely",
        "go ahead", "go on", "do it", "do that", "send it", "call him", "call her", "yes please", "of course",
        "aane", "ane", "yoo", "yo", "kɔ so", "ko so", "yɛ", "ɛyɛ", "eye", "yɛ no", "ye no",
        // "aane" as the phone's English recogniser writes it (a Twi yes/no is heard there — LanguageRoutedInput)
        // [owner: verify on the A20e]
        "anne", "annie", "ani", "aani", "aane aane",
        // …and as it wrote "aane aane" on the A20e (4 October 2026): "honey honey" — the answer was taken as unclear and the
        // Telegram message declined.
        "honey", "honey honey", "hunny", "hani", "anne anne", "annie annie",
        // As the HCI Lab recogniser writes a Twi yes, now that a Twi confirmation is heard there (6 October 2026). Never
        // "so" (the lab's "aane" in the 2 October loop test): "so" is an everyday Twi word — a reply like that is asked again.
        "aan", "aanee", "anee", "yiw", "yiiw", "yɔ"
    )
    private val NO = setOf(
        "no", "nope", "nah", "don't", "dont", "do not", "stop", "cancel", "never mind", "nevermind", "not now",
        "negative", "leave it", "forget it", "abort", "no thanks", "not yet",
        "daabi", "dabi", "gyae", "mma", "ɛnyɛ", "enye", "twa mu", "nnyɛ",
        // "daabi" as the phone's English recogniser writes it [owner: verify on the A20e]
        "darby", "dabby", "daby", "dabbie", "derby", "dobby", "debbie",
        // …and as the HCI Lab recogniser writes "daabi" / "gyae" ("fida" in the 2 October loop test). A no is the safe side.
        "fida", "daabe", "daabɛ", "deabi", "dabe", "gyai", "nnyae", "ntwa", "mmfa"
    )

    // B-09: a reply that is ONLY a request for time. Twi: twɛn (kakra) = wait (a little).
    private val HOLD = setOf(
        "wait", "hold on", "hold up", "one moment", "just a moment", "a moment", "one second", "just a second",
        "give me a second", "give me a moment", "give me a minute", "wait a moment", "wait a minute", "wait please",
        "twɛn", "twen", "twɛn kakra", "twen kakra"
    )

    // Correction markers: a reply carrying one is a change of plan even if it also happens to contain
    // an affirmative verb. This matters for SAFETY — "send it to Kofi instead" contains the bigram
    // "send it" (a YES signal), but approving it would send to the WRONG person. When ambiguous we
    // route to the brain (which re-confirms) rather than execute, because a wrongly-executed
    // consequential action is far worse than one extra confirmation. Kept narrow so a plain
    // "do not"/"don't" decline is NOT caught here (no bare " not ").
    private val REDIRECT = listOf(
        "instead", "rather", "actually", "i meant", "i mean", "make it", "change it", "change that",
        "different", "wrong number", "wrong person", "wrong one", "not him", "not her", "not that",
        "mmom",   // Twi: rather / instead
        // Owner's A20e run (21 Oct 2026): "no let the reference be food" was taken as a plain no and the send was dropped.
        "reference", "let it be", "let the", "change the", "make the", "the amount", "the person", "the name",
        "sesa", "nkyerɛkyerɛmu"   // Twi: change; reference
    )
    private val AMOUNT_AFTER_NO = Regex("^(?:no|nope|nah|daabi|dabi)\\b.*\\b(?:\\d+(?:[.,]\\d+)?|one|two|three|four|five|six|seven|eight|nine|" +
        "ten|twenty|thirty|forty|fifty|hundred)\\s*(?:cedis?|pesewas?|pesos?|ghs|ghc|sidi)\\b")
    /** A bare decline ("no", "daabi") — on a money read-back it asks what to change before dropping the send. */
    fun isBareNo(text: String): Boolean = text.trim().lowercase().replace(Regex("[^\\p{L}\\s]"), " ").trim()
        .matches(Regex("(?:no|nope|nah|daabi|dabi|oh no|no no|daabi daabi)"))
    private val MONEY_PROMPT = Regex("(?i)cedi|pesewa|mobile money|momo|airtime")
    private val STOPPING = Regex("\\b(?:cancel|stop|never mind|nevermind|forget it|leave it|gyae)\\b")

    // The owner's live test (A20e, 14 October 2026): "I said you should send it to Mom and then 2019" corrected the number,
    // but it held "send it" and was taken as YES — the read-back's number (ending 4 1 1 1) went into the MTN menu and only
    // the PIN stopped it. A reply that brings what the read-back doesn't hold is a change of plan, never a yes: a number it
    // doesn't say, "I said …" with more than a bare yes or no after it, or a send/call "to" someone it doesn't name.
    private val I_SAID = Regex("\\b(?:i|i've|i have)\\s+(?:already\\s+)?(?:said|told you)\\b\\s*(.*)$")
    private val TO_SOMEONE = Regex("\\b(?:send|sending|call|calling|pay|paying|give|transfer|text|message)\\b.*?\\b(?:to|for)\\s+" +
        "(?:my\\s+|the\\s+|our\\s+|your\\s+)?([\\p{L}ɛɔ']+)")
    /** "…call Ama" / "text Kofi": for these the person comes straight after the verb. */
    private val CALL_SOMEONE = Regex("\\b(?:call|phone|ring|text|message)\\s+(?:my\\s+|the\\s+|our\\s+)?([\\p{L}ɛɔ']+)")
    private val NOT_SOMEONE = setOf("me", "him", "her", "them", "us", "it", "that", "this", "there", "here", "do", "go", "be",
        "send", "call", "pay", "give", "transfer", "text", "message", "now", "sure")
    private val DIGITS = Regex("\\d+")
    /** A decline that stops ("no, don't send the 50") stays a decline, whatever number it mentions. */
    private val DECLINING = Regex("\\b(?:don'?t|do not|cancel|stop|never ?mind|forget it|leave it|abort|gyae)\\b")

    /** The numbers a reply says that the read-back doesn't (spoken digits counted: "two zero one nine" is 2019). A part of a
     *  longer number the read-back says ("ending 4567" of 0241234567) is not new; a short one must match exactly (5 ≠ 50). */
    internal fun newNumbers(reply: String, prompt: String): List<String> {
        val said = DIGITS.findAll(com.tekyerema.agent.NumberCorrection.digitize(prompt)).map { it.value }.toSet()
        return DIGITS.findAll(com.tekyerema.agent.NumberCorrection.digitize(reply)).map { it.value }
            .filter { r -> r !in said && !(r.length >= 3 && said.any { it.length > r.length && it.contains(r) }) }
            .toList()
    }

    /**
     * A spoken reply during confirmation that is neither a clean yes/no nor mere noise, but a coherent
     * instruction referencing the pending action ("send it to Kofi instead", "make it shorter",
     * "call Ama not Kojo", "actually cancel the message"). Such a reply is handed to the BRAIN to
     * re-plan (§8/§9/§13/§14) rather than discarded as UNCLEAR — this object does NOT interpret the
     * instruction, only decides it is worth routing there. A short filler ("what", "huh", "hmm") is
     * NOT a revision, so the safe "ask once more, then leave it" path still handles genuine mishearing.
     *
     * [prompt] is the read-back being answered: a number or a person the reply adds to it makes the reply a change of plan,
     * whatever yes-word it also holds (null: those two checks are skipped).
     */
    fun looksLikeRevision(text: String, prompt: String? = null): Boolean {
        val norm = text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}ɛɔ'’\\s]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        val ans = interpret(text)
        val redirect = REDIRECT.any { norm.contains(it) }
        // An explicit redirect overrides even a (possibly spurious) yes/no verdict — safety first.
        if (redirect) return true
        // "I said …": a bare "I said yes" / "I said no" is that answer; anything longer re-states the request — a change.
        I_SAID.find(norm)?.let { m ->
            val rest = m.groupValues[1].trim()
            if (rest.split(" ").count { it.isNotBlank() } > 3 || (rest.isNotEmpty() && interpret(rest) == Answer.UNCLEAR)) return true
        }
        if (prompt != null && !DECLINING.containsMatchIn(norm)) {
            if (newNumbers(text, prompt).isNotEmpty()) return true
            val named = prompt.lowercase().replace(Regex("[^\\p{L}\\p{N}ɛɔ'\\s]"), " ").split(Regex("\\s+")).toSet()
            if ((TO_SOMEONE.findAll(norm) + CALL_SOMEONE.findAll(norm)).any { val w = it.groupValues[1]; w !in NOT_SOMEONE && w !in named })
                return true
        }
        // Money safety (2026-09-29): a digit fix ("the middle is 4 5 6", "no, it's 0 5 5 3 2 3 8 0 1 2") is a revision even
        // after a "no" or a "yes" — the corrected action is proposed again and confirmed again, never run on this reply
        // (agent/NumberCorrection). A "no" that stops ("no, cancel") stays a decline.
        if (com.tekyerema.agent.NumberCorrection.looksLikeFix(text)) return true
        // "No, 20 pesewas" / "no, 2 cedis": a new amount after the no is a change of plan, re-proposed and confirmed
        // again — not a decline (QA TR10, 3 October 2026, where the Twi form of it was taken as a plain no).
        if (ans == Answer.NO && AMOUNT_AFTER_NO.containsMatchIn(norm) && !STOPPING.containsMatchIn(norm)) return true
        // Owner, 21 Oct 2026: "make the brain be involved… it would have known what the user wanted and changed the reference".
        // A "no" followed by more than a stop ("no, let the reference be food", "daabi, fa ma Kofi") is the user saying what
        // to change: the planner re-plans with these words and the new read-back is confirmed again — never run on this reply.
        // A bare "no", "no thanks" or "no, cancel / don't" stays a decline.
        if (ans == Answer.NO && !STOPPING.containsMatchIn(norm) && !DECLINING.containsMatchIn(norm)) {
            val afterNo = norm.replace(Regex("^.*?\\b(?:no|nope|nah|daabi|dabi)\\b"), "").trim()
            // On a MONEY read-back even one word after the no is a change for the brain: the Twi recogniser runs "daabi, fa sidi
            // du" together into "daabi fasiidiidum" (A20e, 21 Oct 2026, 06:45) and the send was dropped.
            val minWords = if (prompt != null && MONEY_PROMPT.containsMatchIn(prompt)) 1 else 2
            if (afterNo.split(" ").count { it.isNotBlank() } >= minWords && !Regex("^(?:thanks|thank you|please|o|oo)$").matches(afterNo)) return true
        }
        // A plain decline with no redirect stays a decline; a clean yes stays a yes.
        if (ans != Answer.UNCLEAR) return false
        // "No, don't send it" (a no and a send-ish word) is a decline, never a change of plan.
        if (DECLINING.containsMatchIn(norm) || STOPPING.containsMatchIn(norm)) return false
        // Otherwise: reroute only a coherent phrase (≥3 words), never a one-word filler.
        return norm.split(" ").filter { it.isNotBlank() }.size >= 3
    }

    // "I've finished the secure step" signals. Twi: mawie/awie = I'm done/finished; masã ≈ done.
    private val DONE = setOf(
        "done", "finished", "finish", "complete", "completed", "ready", "i'm done", "im done", "am done",
        "i am done", "that's it", "thats it", "ok done", "okay done", "all done", "go", "go ahead", "continue",
        "mawie", "awie", "masã", "masa", "me wie"
    )

    /** True when the user says they have COMPLETED the secure PIN step (never a PIN itself). Used only to
     *  leave Secure Input Mode; the transcript is never logged. */
    fun isCompletion(text: String): Boolean {
        val norm = text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}ɛɔ'’\\s]"), " ").replace("’", "'")
            .replace(Regex("\\s+"), " ").trim()
        if (norm.isEmpty()) return false
        val tokens = norm.split(" ")
        val grams = HashSet<String>(tokens)
        for (i in 0 until tokens.size - 1) grams.add(tokens[i] + " " + tokens[i + 1])
        return grams.any { it in DONE }
    }

    fun interpret(text: String): Answer {
        val norm = text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}ɛɔ'’\\s]"), " ")
            .replace("’", "'")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (norm.isEmpty()) return Answer.UNCLEAR
        val tokens = norm.split(" ")
        val grams = HashSet<String>(tokens)
        for (i in 0 until tokens.size - 1) grams.add(tokens[i] + " " + tokens[i + 1])
        val yes = grams.any { it in YES }
        val no = grams.any { it in NO }
        val hold = norm.removeSuffix(" please").trim().let { it in HOLD || (it.startsWith("wait ") && tokens.size <= 3) }
        val holdWord = grams.any { it in HOLD }   // "yes… wait" must never count as a yes
        return when {
            yes && no -> Answer.UNCLEAR
            no -> Answer.NO                  // "no, wait" is still a no
            yes && holdWord -> Answer.UNCLEAR
            yes -> Answer.YES
            hold -> Answer.HOLD
            else -> Answer.UNCLEAR
        }
    }
}
