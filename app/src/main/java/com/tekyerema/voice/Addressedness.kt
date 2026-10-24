package com.tekyerema.voice

/**
 * "Is this addressed to me?" — the gate in front of a turn Tɛkyerɛma opened BY ITSELF.
 *
 * Why (a judge's session, A20e 2026-09-29): after each reply the microphone reopens for a follow-up
 * ([VoiceController.followUp], the question tail), and it picked up the conversation around the phone and answered it
 * as a request. "I will eat today." → "Okay, enjoy your meal!"; "I thought this will make it the the head is right" →
 * "Could you clarify what you would like to adjust or do?"; a garbled "…share my ask the share my ask the share my…" →
 * "What would you like to share?". Each clarifying question reopened the mic, so the room kept the conversation going.
 *
 * Conservative by design — a missed request costs the user a tap, answering the room costs their trust:
 *  - a turn the user started (tap, typed, a button, opening Tɛkyerɛma, a clear "Hey Tɛkyerɛma") is ALWAYS a request;
 *  - the answer to a question or confirmation the task is waiting on never comes here (the answer path already handles
 *    unclear answers);
 *  - only a listen Tɛkyerɛma opened by itself ([Origin.FOLLOW_UP]) or a wake that only just cleared the bar
 *    ([Origin.WAKE_MARGINAL]) is judged. It is kept when it looks like something said TO the phone: a fast-path intent, an
 *    imperative or a question, a short answer ("yes", "the second one"), a number, an app / contact / "my mother", a Twi
 *    command, or a reference back to the last turn ("and him", "what about tomorrow"). Narration and small talk
 *    ("I will eat today", "thank you"), fragments ("at the same") and repetition loops are ignored — no model call, no reply.
 *  - [questionPending]: the reply just asked something ("What would you like to share?") — an answer there is often a
 *    bare phrase ("my location", "Kofi"), so any phrase with real content is kept; narration, small talk, fragments and
 *    loops are still ignored (they are how the room kept the judge's session going).
 *
 * Pure → unit-tested against every transcript of that session (AddressednessTest). [check] wires the live signals.
 */
object Addressedness {

    enum class Origin(val gated: Boolean, val label: String) {
        /** Tap, mic button, opening Tɛkyerɛma, a trigger: the user asked to talk. */
        USER(false, "user"),
        /** Typed text. */
        TYPED(false, "typed"),
        /** The reply to a question / confirmation / secure step the task is waiting on (askByVoice, confirmByVoice). */
        ANSWER(false, "answer"),
        /** A clear "Hey Tɛkyerɛma". */
        WAKE(false, "wake"),
        /** A wake whose score only just cleared the bar — it may have been the TV or the room ([WakeSignal]). */
        WAKE_MARGINAL(true, "wake"),
        /** The microphone Tɛkyerɛma reopened by itself after a reply (conversational follow-up, the question tail). */
        FOLLOW_UP(true, "follow-up")
    }

    /** [reason] is a fixed word (never the user's words), safe for release logs. */
    data class Verdict(val addressed: Boolean, val reason: String)

    /**
     * Live wiring for [VoiceController]: the fast-path classifier and the phone's contact / app names (empty in unit tests
     * and without the contacts permission). [alt] = the recognizer's raw words when they differ from the normalized [text]
     * (either one addressed to us keeps the turn). Never throws — a failure keeps the turn (fail open, as before this gate).
     */
    fun check(text: String, origin: Origin, questionPending: Boolean, alt: String? = null): Verdict {
        if (!origin.gated) return Verdict(true, "user-started")
        return try {
            val names = try { SpeechVocabulary.current().entries.map { it.text } } catch (_: Throwable) { emptyList() }
            fun one(s: String): Verdict {
                val fast = try { com.tekyerema.agent.FastIntentClassifier.classify(s) != null } catch (_: Throwable) { false }
                return judge(s, origin, questionPending, fast, names)
            }
            val v = one(text)
            if (v.addressed || alt.isNullOrBlank() || alt.trim() == text.trim()) v else one(alt).takeIf { it.addressed } ?: v
        } catch (_: Throwable) { Verdict(true, "error") }
    }

    /**
     * @param fastIntent the Local Fast Path would handle [text] (time, battery, open an app, take a selfie…);
     * @param names contact display names and app labels on the phone.
     */
    fun judge(
        text: String, origin: Origin, questionPending: Boolean = false,
        fastIntent: Boolean = false, names: Collection<String> = emptyList()
    ): Verdict {
        if (!origin.gated) return Verdict(true, "user-started")
        val all = tokens(text)
        if (all.isEmpty()) return Verdict(false, "empty")
        val t = all.joinToString(" ")
        // Said to Tɛkyerɛma by name, or a cry for help: always heard.
        if (all.any { it in NAMES_OF_US } || all.zipWithNext().any { (a, b) -> "$a $b" in NAME_PAIRS }) return Verdict(true, "named")
        if (EMERGENCY.containsMatchIn(t)) return Verdict(true, "emergency")
        // A phrase said over and over ("share my ask the share my ask the share my…") is the recognizer looping on
        // background talk, whatever words it contains.
        if (isLoop(all)) return Verdict(false, "repetition")
        if (SHORT_ANSWER.matches(t)) return Verdict(true, "short answer")
        val w = stripLead(all)
        if (w.isEmpty()) return Verdict(false, "filler")
        val s = w.joinToString(" ")
        if (SHORT_ANSWER.matches(s)) return Verdict(true, "short answer")
        val app = APPS.containsMatchIn(t)
        val person = RELATION.containsMatchIn(t) || namesContact(t, all, names)
        val digits = t.count { it.isDigit() } >= 3
        // "I will eat today", "I thought this will make it…", "we went to the market": a statement, not a request —
        // unless it carries one ("I would like to make a call to 055…", "I'm looking for Kofi's number").
        if (w.first() in SUBJECTS) {
            val rest = requestAfterMarker(s) ?: return Verdict(false, "narration")
            val verb = rest.firstOrNull()
            return if (verb != null && verb in LIFE_VERBS && !app && !person && !digits) Verdict(false, "narration")
            else Verdict(true, "request")
        }
        if (fastIntent) return Verdict(true, "fast path")
        if (digits) return Verdict(true, "number")
        if (SMALL_TALK.matches(s)) return Verdict(false, "small talk")
        if (isQuestion(w, text.trim().endsWith("?"))) return Verdict(true, "question")
        if (isImperative(w)) return Verdict(true, "imperative")
        // "also tomorrow", "same for Kofi", "and her?": the last turn, continued. (A bare "and …" only with a pronoun and
        // kept short — room talk starts with "and" all the time.)
        if (all.first() in BACK_REF && w.any { it !in STOP }) return Verdict(true, "follows on")
        if (all.first() == "and" && w.size <= 3 && w.any { it in OBJECT_PRONOUNS }) return Verdict(true, "follows on")
        // "My mother is coming", "the food was nice": about something, not asking for anything (an app or a phone feature
        // named — "my WhatsApp is not working" — is kept below).
        if (!questionPending && !app && STATEMENT.containsMatchIn(s)) return Verdict(false, "narration")
        if (app || person) return Verdict(true, "names an app or person")
        // "weather in Accra", "the news": a topic asked for in a word or two.
        if (w.size <= 4 && w.any { it in TOPICS }) return Verdict(true, "topic")
        if (isTwiCommand(w, t)) return Verdict(true, "twi")
        if (questionPending) {
            val content = w.count { it !in STOP }
            return if (content >= 1 && content.toDouble() / w.size >= 0.3) Verdict(true, "answer")
            else Verdict(false, "fragment")
        }
        return Verdict(false, "no request")
    }

    // ------------------------------------------------------------------ pieces (pure)

    internal fun tokens(text: String): List<String> =
        text.lowercase().replace('’', '\'').replace(Regex("[^\\p{L}\\p{N}'\\s]"), " ")
            .split(Regex("\\s+")).map { it.trim('\'') }.filter { it.isNotBlank() }

    /** A loop: some two-word run three times, or a three-word run twice in a long, repetitive utterance. Needs ≥ 10 words
     *  so a short request said twice ("send money to 055 send money to 0553238") is never one. */
    internal fun isLoop(w: List<String>): Boolean {
        if (w.size < 10) return false
        if (w.windowed(2).groupingBy { it }.eachCount().values.any { it >= 3 }) return true
        return w.windowed(3).groupingBy { it }.eachCount().values.any { it >= 2 } && w.toSet().size.toDouble() / w.size < 0.6
    }

    /** Drop leading fillers and discourse words ("okay so please …", "and then …", "I mean …") — what follows is the request. */
    internal fun stripLead(w: List<String>): List<String> {
        var i = 0
        while (i < w.size) {
            val two = if (i + 1 < w.size) w[i] + " " + w[i + 1] else ""
            when {
                two in LEAD_PAIRS -> i += 2
                w[i] in LEAD_WORDS -> i++
                else -> break
            }
        }
        return w.drop(i)
    }

    /** The words after a first-person request marker ("i would like to | make a call…"), or null when there is none. */
    private fun requestAfterMarker(s: String): List<String>? {
        val m = REQUEST_MARKER.find(s) ?: return null
        return s.substring(m.range.last + 1).trim().split(" ").filter { it.isNotBlank() }
    }

    private fun isQuestion(w: List<String>, asked: Boolean): Boolean {
        val first = w.first()
        if (first in WH) return true
        if (first in AUX && w.size >= 2 && w[1] in AUX_SUBJECT) return true
        if (w.any { it in TWI_QUESTION }) return true
        // A recognizer's "?" on something that isn't narration (checked before this) or small talk.
        return asked && w.size >= 2
    }

    /** "Call Ama", "look up the cedi rate", "quickly send it" — an imperative at the start (or right after one word). */
    private fun isImperative(w: List<String>): Boolean {
        val first = w.first()
        if (first in VERBS) return true
        if (first == "look" && w.getOrNull(1) in setOf("up", "for")) return true
        return w.getOrNull(1) in CORE_VERBS && w.size >= 3
    }

    private fun namesContact(t: String, all: List<String>, names: Collection<String>): Boolean {
        if (names.isEmpty()) return false
        val words = all.toHashSet()
        val padded = " $t "
        for (n in names) {
            val nt = tokens(n)
            if (nt.isEmpty()) continue
            // A whole name ("kofi mensah"), or a first name that is a real name (not "home", "me", "call").
            if (nt.size > 1 && padded.contains(" " + nt.joinToString(" ") + " ")) return true
            val first = nt.first()
            if (first.length >= 3 && first !in STOP && first !in VERBS && first !in SUBJECTS && first !in COMMON_WORDS &&
                first in words) return true
        }
        return false
    }

    private fun isTwiCommand(w: List<String>, t: String): Boolean {
        // "mesrɛ wo, …" = please
        val lead = if ((w.first() == "mesrɛ" || w.first() == "mesre") && w.size > 2) w[2] else w.first()
        if (lead in TWI_COMMAND) return true
        if (TWI_WANT.containsMatchIn(t)) return true
        return t.any { it in "ɛɔŋ" } && lead in TWI_COMMAND_WEAK
    }

    // ------------------------------------------------------------------ word lists

    private val NAMES_OF_US = setOf("tekyerema", "tɛkyerɛma", "tekyerɛma", "tɛkyerema", "chekyerema", "tekyirema",
        // what both English recognisers wrote for the owner saying the name (models session, 24 Oct 2026, asr_eng_owner
        // W01–W04: 0 of 4 right in every system) — none is an English word
        "grema", "dechema", "techma", "hatechema", "tertrumah", "tequirema")
    /** …and the two-word forms from the same clips ("tetch ramah", "the germa"). */
    private val NAME_PAIRS = setOf("tetch ramah", "the germa")

    private val EMERGENCY = Regex("\\b(?:help me|i need help|emergency|ambulance|police|i(?:'ve| have) fallen|mmoa|boa me)\\b")

    private val LEAD_WORDS = setOf("okay", "ok", "alright", "so", "now", "please", "kindly", "and", "also", "then", "um", "uh",
        "er", "erm", "ehm", "eh", "oh", "ah", "well", "hey", "actually", "just", "right", "yo")
    private val LEAD_PAIRS = setOf("i mean", "i think", "i guess", "i said", "i meant", "you know", "all right")

    /** Whole-utterance answers — kept even with no question pending ("the second one" after a list). */
    private val SHORT_ANSWER = Regex("^(?:" +
        "yes|yeah|yea|yep|yup|ya|yah|sure|of course|okay|ok|fine|correct|right|that's right|exactly|go ahead|do it|" +
        "no|nope|nah|not now|no thanks|no thank you|not yet|later|maybe|" +
        "i think so|i don't think so|i don't know|i'm not sure|" +
        "continue|go on|carry on|keep going|more|read more|tell me more|next|again|repeat|say that again|" +
        "(?:the )?(?:first|second|third|fourth|fifth|last|next|previous|other|same|top|bottom|latest|newest|oldest|new|old) one|" +
        "the (?:first|second|third|fourth|fifth|last|latest)|this one|that one|both|both of them|neither|none|all of them|" +
        "(?:number|option) (?:one|two|three|four|five|\\d+)|" +
        "aane|daabi|yoo|ɛyɛ|eye|mepene|me pene|ampa" +
        ")(?: please| thanks| thank you)?$")

    /** Pleasantries: nothing to do, and a chatty reply to them is exactly what the room got. */
    private val SMALL_TALK = Regex("^(?:" +
        "thank you|thanks|thank you very much|thank you so much|thanks a lot|medaase|you're welcome|" +
        "good morning|good afternoon|good evening|good night|hello|hi|hey|bye|bye bye|goodbye|see you|see you later|" +
        "nice|cool|wow|great|awesome|good|very good|oh okay|hmm|mm|uh huh|lol|haha|i see|really|seriously|sorry|excuse me|" +
        "how are you|how are you doing|how is it going|how's it going|how far|what's up|wassup|" +
        "maakye|maaha|maadwo|wo ho te sɛn|wo ho te sen|ɛte sɛn|ete sen|me ho yɛ|i'm fine|i am fine|fine thank you|amen|god bless you" +
        ")$")

    /** A statement's subject: "I …", "we …", "he …", "people …". */
    private val SUBJECTS = setOf("i", "i'm", "im", "i've", "ive", "i'll", "i'd", "we", "we're", "we'll", "we've", "we'd", "he",
        "he's", "she", "she's", "they", "they're", "people", "somebody", "someone", "everybody", "everyone")

    /** "I want to / would like to / need …": a request said in the first person. ("I can…", "I will…" are statements;
     *  "can I…" is a question and is kept as one. "We need to buy rice" is the room — a user asks for themselves.) */
    private val REQUEST_MARKER = Regex("^i (?:really |just )?(?:want|wanna|need|would like|would love)(?: you)?(?: to)?\\b|" +
        "^i'd (?:like|love)(?: you)?(?: to)?\\b|^(?:i'm|i am|im) (?:trying to|looking for)\\b")

    /** After "I want to …": living, not asking ("I want to eat", "I need to sleep"). */
    private val LIFE_VERBS = setOf("eat", "sleep", "rest", "drink", "bath", "bathe", "shower", "go", "leave", "sit", "cook",
        "pray", "wash", "relax", "pee", "wee", "dance", "cry", "laugh", "die", "marry", "work", "study", "be")

    /** "My mother is coming", "the food was nice", "this thing has…". */
    private val STATEMENT = Regex("^(?:my|our|his|her|their|the|this|that|these|those|it|it's|its|there|you|you're) " +
        "(?:\\S+ ){0,2}?(?:is|isn't|was|wasn't|are|aren't|were|said|says|has|had|will|went|came|told|did|does|looks|seems)\\b")

    private val WH = setOf("what", "what's", "whats", "when", "when's", "where", "where's", "who", "who's", "whom", "whose",
        "which", "why", "how", "how's", "hows")
    private val AUX = setOf("is", "are", "am", "can", "could", "would", "will", "do", "does", "did", "have", "has", "should",
        "shall", "may", "might", "was", "were", "isn't", "aren't", "don't", "didn't", "won't", "can't")
    private val AUX_SUBJECT = setOf("you", "i", "it", "there", "my", "the", "this", "that", "we", "he", "she", "they", "your",
        "a", "an", "any", "anyone", "anybody", "somebody", "someone", "me")

    /** Imperatives a phone assistant hears. (Leaves out verbs room talk starts with and a phone rarely hears: "like",
     *  "keep", "try", "bring", "use".) */
    private val VERBS = setOf(
        "call", "ring", "phone", "dial", "text", "message", "sms", "whatsapp", "email", "mail", "send", "reply", "respond",
        "forward", "tell", "ask", "read", "open", "launch", "start", "close", "quit", "exit", "play", "pause", "stop", "resume",
        "skip", "rewind", "show", "find", "search", "check", "turn", "switch", "set", "remind", "schedule", "wake",
        "take", "snap", "capture", "record", "describe", "identify", "scan", "navigate", "scroll", "swipe", "tap", "press",
        "click", "select", "choose", "pick", "type", "write", "note", "share", "save", "delete", "remove", "erase", "clear",
        "answer", "hang", "decline", "reject", "mute", "unmute", "lower", "raise", "increase", "decrease", "reduce", "boost",
        "download", "install", "uninstall", "update", "upgrade", "buy", "pay", "transfer", "top", "load", "recharge", "withdraw",
        "deposit", "cancel", "listen", "watch", "repeat", "say", "speak", "translate", "spell", "calculate", "convert", "add",
        "create", "make", "get", "give", "help", "enable", "disable", "activate", "deactivate", "lock", "unlock",
        "connect", "disconnect", "pair", "forget", "undo", "zoom", "copy", "paste", "subscribe", "unsubscribe",
        "block", "unblock", "report", "sign", "log", "join", "visit", "order", "book", "locate", "track",
        "change", "move", "rename", "edit", "fix", "reset", "restart", "reboot", "refresh", "list", "summarise", "summarize",
        "explain", "define", "teach", "invite", "accept", "confirm", "allow", "dismiss", "hide", "expand", "go", "brighten",
        "dim", "silence", "vibrate", "remember", "put",
        // said as commands on their own: "louder", "slower"
        "louder", "softer", "quieter", "brighter", "dimmer", "faster", "slower")
    /** The unmistakable ones, also kept one word in ("quickly call Ama", "mummy text Kofi"). */
    private val CORE_VERBS = setOf("call", "text", "message", "send", "read", "open", "play", "dial", "reply", "remind", "describe",
        "whatsapp", "search", "turn", "switch")

    /** "also …", "same for …", "plus …": the last turn continued. */
    private val BACK_REF = setOf("also", "same", "plus")
    private val OBJECT_PRONOUNS = setOf("him", "her", "it", "them", "that", "this", "those", "these", "there", "one")

    /** Apps, phone features and money words — naming one is talking about the phone. */
    private val APPS = Regex("\\b(?:whatsapp|youtube|facebook|messenger|instagram|tiktok|telegram|chrome|browser|gmail|" +
        "maps|camera|gallery|settings|calculator|alarm|calendar|contacts|dialer|play ?store|momo|mobile money|mtn|" +
        "vodafone|telecel|airteltigo|chatgpt|spotify|boomplay|audiomack|jiji|uber|bolt|yango|snapchat|bluetooth|" +
        "wi-?fi|wifi|flashlight|torch|battery|volume|brightness|notifications?|ringtone|hotspot|airplane mode|" +
        "cedis?|pesewas?|airtime|data bundle|playlist|music|selfie)\\b")

    private val TOPICS = setOf("weather", "forecast", "news", "headlines", "time", "date", "rate", "scores", "score")

    private val RELATION = Regex("\\bmy (?:mum|mom|mummy|mommy|mother|mama|maame|dad|daddy|father|papa|wife|husband|brother|" +
        "sister|son|daughter|uncle|auntie|aunty|aunt|grandma|grandmother|grandpa|grandfather|nana|helper)\\b")

    /** A contact saved as an everyday word must not make every sentence "about a contact". */
    private val COMMON_WORDS = setOf("home", "work", "office", "shop", "taxi", "school", "church", "mum", "mom", "dad", "love",
        "baby", "boss", "doctor", "hospital", "police", "bank", "the", "and", "you", "now")

    /** Words with no content of their own — an utterance of only these ("at the same") says nothing. */
    private val STOP = setOf("a", "an", "the", "at", "in", "on", "of", "to", "for", "with", "from", "by", "and", "or", "but",
        "so", "is", "are", "was", "were", "be", "been", "am", "it", "its", "it's", "this", "that", "these", "those", "same",
        "one", "some", "any", "i", "you", "he", "she", "we", "they", "me", "him", "her", "us", "them", "my", "your", "his",
        "our", "their", "do", "did", "does", "have", "has", "had", "will", "would", "can", "could", "shall", "should", "not",
        "no", "yes", "if", "then", "there", "here", "just", "like", "know", "um", "uh", "eh", "oh", "ah", "okay", "ok", "even",
        "very", "too", "also", "as", "what", "which", "who")

    /** Twi imperatives that are not English words: kenkan (read), bue (open), frɛ (call), soma (send), kyerɛ (show/tell)… */
    private val TWI_COMMAND = setOf("kenkan", "bue", "buei", "buee", "frɛ", "frɛɛ", "fre", "soma", "kyerɛ", "kyere", "dum",
        "sɔ", "hwehwɛ", "hwehwe", "twerɛ", "twere", "gyae", "twɛn", "sesa", "boa", "mmoa", "kasa", "hwɛ", "yi", "fa", "mepɛ",
        "mepe", "wopɛ")
    /** Short Twi imperatives that are also English words ("to", "ma", "san") — only with Twi letters elsewhere. */
    private val TWI_COMMAND_WEAK = setOf("to", "ma", "san", "ka", "kɔ", "gye", "bɔ", "yɛ", "hyɛ", "tɔ", "so")
    // (Lookarounds, not \b: \b is ASCII-only on newer JVMs and would never match after "ɛ".)
    private val TWI_WANT = Regex("(?<!\\p{L})(?:me ?pɛ sɛ|me ?pe se|mepɛ|mepa)(?!\\p{L})")
    private val TWI_QUESTION = setOf("ɛhe", "dɛn", "hena", "sɛn", "bɛn", "adɛn")
}

/**
 * How the last "Hey Tɛkyerɛma" scored, for the turn it opens ([WakeWordService] → [VoiceController.start]). A wake that
 * only just cleared the bar may have been the TV or the room (A-07: they reach 0.98-1.00 on the A20e), so its turn is
 * gated like a follow-up ([Addressedness.Origin.WAKE_MARGINAL]): a request still goes through; narration does not.
 * `wake.marginalBelow` (default [DEFAULT_MARGINAL]) — tune from the `[WAKE] 'Hey Tɛkyerɛma' detected (hey=…)` lines.
 */
object WakeSignal {
    const val DEFAULT_MARGINAL = 0.997f
    /** A wake older than this didn't open the turn being started (the voice screen takes a second or two to come up;
     *  kept short so a tap a little later is never mistaken for the wake). */
    const val WINDOW_MS = 5_000L
    @Volatile private var at = 0L
    @Volatile private var peak = 1f

    fun fired(peakScore: Float, now: Long = System.currentTimeMillis()) { peak = peakScore; at = now }

    /** The origin of a turn starting now — and the wake is forgotten (one wake opens one turn). */
    fun consume(now: Long = System.currentTimeMillis(), marginalBelow: Float = marginalBelow()): Addressedness.Origin {
        val recent = at != 0L && now - at in 0..WINDOW_MS
        val p = peak
        at = 0L
        return when {
            !recent -> Addressedness.Origin.USER
            p < marginalBelow -> Addressedness.Origin.WAKE_MARGINAL
            else -> Addressedness.Origin.WAKE
        }
    }

    fun marginalBelow(): Float = try {
        com.tekyerema.core.config.AppConfig.double("wake.marginalBelow", DEFAULT_MARGINAL.toDouble()).toFloat()
    } catch (_: Throwable) { DEFAULT_MARGINAL }
}
