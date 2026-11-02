package com.tekyerema.voice

import com.tekyerema.settings.Settings
import com.tekyerema.voice.io.Hypothesis
import com.tekyerema.voice.io.YesNo

/**
 * First-run voice welcome — "a ritual, not a gate": the user's request always comes first (OpenClaw's first run,
 * docs/agent/research/openclaw.md), with a safe practice for people new to smartphones
 * (docs/agent/research/assistive_tech_blind.md).
 *
 * The first time Home opens after the permissions page, Tɛkyerɛma asks by voice, one at a time: what to call the user,
 * English or Twi, and whether its speaking speed is right; then offers a practice text that is never sent, and ends with
 * one line of what the user can say. Every question is skippable ("skip", "later", silence); a request said instead of
 * an answer ("open WhatsApp", "what's the time") ends the welcome and runs as usual; "stop" ends it. It is marked done as
 * it starts, so it never repeats (Settings › Replay the welcome runs it again).
 *
 * [classify] + [step] are a pure state machine and [run] (with [Practice.run]) a driver over [Io] — both unit-tested with
 * fakes. VoiceController supplies the real voice (VoiceController.startWelcome) and [WelcomeHost] saves the answers.
 * English only: no Twi phrase table has these lines yet — a native speaker should write them (locales.json keys welcome*).
 */
object Welcome {
    enum class Step { NAME, LANGUAGE, RATE, RATE_WHICH, PRACTICE }

    /** One listen: the words (canonical, as heard, and the recognizer's other guesses), nothing, or the user cancelled. */
    sealed interface Reply {
        data class Text(val text: String, val raw: String = text, val alternatives: List<String> = emptyList()) : Reply
        data object Silence : Reply
        data object Cancelled : Reply
    }

    /** What a reply means at a step. */
    sealed interface Input {
        data object Silence : Input
        data object Skip : Input
        data object Stop : Input
        data object Unclear : Input
        data class Command(val text: String) : Input
        data class Name(val name: String) : Input
        data class Language(val mode: String) : Input
        /** "slower" | "faster" (the H-08 preference values) | "keep". */
        data class Rate(val value: String) : Input
        /** A bare "no" to "is this speed okay?": which way is asked next. */
        data object RateNo : Input
        data object Practise : Input
    }

    /** What an answer changes. The host applies it ([WelcomeHost.apply]); [StartPractice] runs [Practice]. */
    sealed interface Effect {
        data class SetName(val name: String) : Effect
        data class SetLanguage(val mode: String) : Effect
        data class SetRate(val value: String) : Effect
        data object StartPractice : Effect
    }

    sealed interface Outcome {
        data object Finished : Outcome
        data object Stopped : Outcome
        data object Cancelled : Outcome
        /** A request said instead of an answer — the caller runs it as a normal turn. */
        data class Command(val text: String) : Outcome
    }

    /** The machine's answer to one input: lines to say, effects, and where to go ([repeat] = ask the same question again). */
    data class Turn(
        val say: List<String> = emptyList(),
        val effects: List<Effect> = emptyList(),
        val next: Step? = null,
        val end: Outcome? = null,
        val repeat: Boolean = false
    )

    /** The voice the driver talks through. */
    interface Io {
        suspend fun say(text: String)
        /** Say [question], then listen once ([yesNo]: a yes/no answer; [patient]: after "wait"). */
        suspend fun ask(question: String, yesNo: Boolean = false, patient: Boolean = false): Reply
        /** The "needs your OK" cue before a confirmation. */
        fun needsOk() {}
        /** Apply [effect]; returns a line to say (the speed change's own "Okay, I'll speak slowly."), or null. */
        fun apply(effect: Effect): String? = null
    }

    // ------------------------------------------------------------------ what is said
    const val INTRO = "Hello, I'm Nexus. Let me ask you a few quick things. Say skip to move on, or just ask me for something."
    const val CLOSING = "You can ask me to call someone, send a message, play music, or check your MoMo. Say 'what can you do' any time."
    const val SORRY = "Sorry, I didn't catch that. "
    const val OKAY = "Okay."
    const val KEEP_SPEED = "Okay, I'll keep this speed."

    fun question(step: Step): String = when (step) {
        Step.NAME -> "What should I call you?"
        Step.LANGUAGE -> "Should I speak English or Twi?"
        Step.RATE -> "Is this speed okay, or should I talk slower or faster?"
        Step.RATE_WHICH -> "Slower or faster?"
        Step.PRACTICE -> "Want to practise? Say 'send a practice message'."
    }

    /** Honest about the voice: under the English-only voice layer a Twi choice can't change how it sounds yet. */
    fun languageAck(mode: String, twiVoice: Boolean): String = when (mode) {
        Settings.LANG_MODE_TWI -> if (twiVoice) "Okay, Twi." else "Okay, Twi where I can. For now my voice on this phone speaks English."
        Settings.LANG_MODE_BOTH -> "Okay, I'll follow the language you use." + if (twiVoice) "" else " For now my voice speaks English."
        else -> "Okay, English."
    }

    // ------------------------------------------------------------------ the machine

    private fun after(step: Step): Step? = when (step) {
        Step.NAME -> Step.LANGUAGE
        Step.LANGUAGE -> Step.RATE
        Step.RATE, Step.RATE_WHICH -> Step.PRACTICE
        Step.PRACTICE -> null
    }

    /** One transition. [retried]: this question was already asked again once — an unclear answer now moves on. */
    fun step(step: Step, input: Input, twiVoice: Boolean = false, retried: Boolean = false): Turn = when (input) {
        Input.Stop -> Turn(say = listOf(OKAY), end = Outcome.Stopped)
        is Input.Command -> Turn(end = Outcome.Command(input.text))
        Input.Silence, Input.Skip -> Turn(next = after(step))
        Input.Unclear -> if (!retried && step != Step.PRACTICE) Turn(repeat = true, next = step) else Turn(next = after(step))
        is Input.Name -> Turn(say = listOf("Nice to meet you, ${input.name}."), effects = listOf(Effect.SetName(input.name)), next = after(step))
        is Input.Language -> Turn(say = listOf(languageAck(input.mode, twiVoice)), effects = listOf(Effect.SetLanguage(input.mode)), next = after(step))
        is Input.Rate -> if (input.value == "keep") Turn(say = listOf(KEEP_SPEED), next = after(step))
            else Turn(effects = listOf(Effect.SetRate(input.value)), next = after(step))
        Input.RateNo -> Turn(next = Step.RATE_WHICH)
        Input.Practise -> Turn(effects = listOf(Effect.StartPractice), next = after(step))
    }

    /** What [reply] means at [step]. Per step, an answer is looked for before a request: "call me Ama" is a name, not a call. */
    fun classify(step: Step, reply: Reply, isCommand: (String) -> Boolean = ::looksLikeCommand): Input {
        val r = reply as? Reply.Text ?: return Input.Silence
        val g = norm(r.text)
        if (g.isEmpty()) return Input.Silence
        if (isStop(g)) return Input.Stop
        when (step) {
            Step.NAME -> {
                introducedName(r.raw)?.let { return Input.Name(it) }
                if (SKIP.matches(g)) return Input.Skip
                if (isCommand(r.text)) return Input.Command(r.text)
                bareName(r.raw)?.let { return Input.Name(it) }
            }
            Step.LANGUAGE -> {
                language(g)?.let { return Input.Language(it) }
                if (SKIP.matches(g)) return Input.Skip
                if (isCommand(r.text)) return Input.Command(r.text)
            }
            Step.RATE, Step.RATE_WHICH -> {
                rate(g)?.let { return Input.Rate(it) }
                if (RE_NO.matches(g)) return if (step == Step.RATE) Input.RateNo else Input.Rate("keep")
                if (SKIP.matches(g)) return Input.Skip
                if (isCommand(r.text)) return Input.Command(r.text)
            }
            Step.PRACTICE -> {
                if (Practice.matches(g) || Confirmation.interpret(g) == Confirmation.Answer.YES) return Input.Practise
                if (SKIP.matches(g)) return Input.Skip
                if (isCommand(r.text)) return Input.Command(r.text)
            }
        }
        return Input.Unclear
    }

    // ------------------------------------------------------------------ the driver

    /**
     * Ask the questions through [io] until the end. Two silences in a row mean nobody is answering: the rest is skipped
     * and the closing line said, so the welcome never talks on at an empty room.
     */
    suspend fun run(io: Io, twiVoice: Boolean = false, isCommand: (String) -> Boolean = ::looksLikeCommand): Outcome {
        io.say(INTRO)
        var step: Step? = Step.NAME
        var question = question(Step.NAME)
        var retried = false
        var silences = 0
        while (step != null) {
            val here = step
            val reply = io.ask(question)
            if (reply is Reply.Cancelled) return Outcome.Cancelled
            val input = classify(here, reply, isCommand)
            silences = if (input == Input.Silence) silences + 1 else 0
            if (silences >= 2) break
            val turn = step(here, input, twiVoice, retried)
            if (turn.repeat) { retried = true; question = SORRY + question(here); continue }
            for (e in turn.effects) {
                if (e == Effect.StartPractice) {
                    val p = Practice.run(io)
                    if (p == Outcome.Cancelled || p == Outcome.Stopped) return p
                } else io.apply(e)?.takeIf { it.isNotBlank() }?.let { io.say(it) }
            }
            turn.say.forEach { io.say(it) }
            turn.end?.let { return it }
            retried = false
            step = turn.next
            step?.let { question = question(it) }
        }
        io.say(CLOSING)
        return Outcome.Finished
    }

    // ------------------------------------------------------------------ understanding answers

    /** Lower-case words, apostrophes kept, punctuation and a leading wake word dropped. */
    fun norm(text: String): String = text.lowercase().replace('’', '\'').replace('‘', '\'')
        .replace(Regex("[^\\p{L}\\p{N}'\\s]"), " ").replace(Regex("\\s+"), " ").trim()
        .replace(Regex("^(?:hey )?(?:tekyerema|tɛkyerɛma)\\s*"), "").trim()

    fun isStop(text: String): Boolean = STOP.matches(norm(text))

    private val STOP = Regex("^(?:(?:please|ok|okay|no) )?(?:stop|cancel|quit|exit|end|enough|that's enough|thats enough|be quiet|" +
        "shut up|go away|finish|stop (?:it|this|that|now|talking|asking|the questions|the welcome)|no more(?: questions)?)" +
        "(?: please| now| thanks| thank you)?$")
    private val SKIP = Regex("^(?:(?:ok|okay|um|uh|er|hmm) )?(?:skip(?: it| this| that| this one| this question)?|later|maybe later|not now|" +
        "next|next question|pass|move on|no|nope|nah|no thanks|no thank you|i don't know|i dont know|don't know|dont know|dunno|" +
        "never mind|nevermind|whatever|none|nothing|not really|i'm not sure|im not sure|not sure)(?: please| thanks| thank you)?$")
    private val RE_NO = Regex("^(?:no|nope|nah|not really|no it's not|no it isn't|it's not)$")

    // ---- name
    private val NAME_INTRO = Regex("^(?:(?:hi|hello|hey|okay|ok|well|um|uh|so)\\s+)?(?:my name is|my name's|my names|name is|name's|" +
        "i am|i'm|im|call me|you can call me|just call me|please call me|they call me|people call me|it's|its|it is|" +
        // Twi "me din de …" (my name is …) — [owner: verify with a native speaker]
        "me din de|me din ne)\\s+(.+)$")
    private val NOT_NAME = setOf(
        "a", "an", "the", "not", "no", "yes", "yeah", "ok", "okay", "fine", "good", "great", "well", "sure", "sorry", "here", "ready",
        "busy", "tired", "hungry", "done", "thanks", "thank", "you", "me", "my", "it", "is", "what", "who", "how", "why", "where",
        "when", "that", "this", "just", "very", "so", "too", "and", "or", "but", "um", "uh", "hmm", "er", "hello", "hi", "hey", "skip",
        "later", "stop", "english", "twi", "please", "going", "doing", "from", "in", "on", "at", "to", "of", "for", "with", "nothing",
        "anything", "something", "whatever", "fast", "slow", "faster", "slower", "again", "repeat", "pardon", "huh", "blind",
        "back", "home", "time", "battery", "music", "weather", "news", "open", "call", "send"
    )
    private val TOKEN = Regex("^[\\p{L}][\\p{L}'-]{0,19}$")

    /** "My name is Ama", "call me Kofi", "I'm Nana Yaw" → the name; null when there's no such opening. */
    fun introducedName(raw: String): String? {
        val m = NAME_INTRO.matchEntire(norm(raw)) ?: return null
        return cleanName(m.groupValues[1], raw)
    }

    /** A reply that is only a name: one to three name-like words ("Ama", "Kofi Mensah"). */
    fun bareName(raw: String): String? = cleanName(norm(raw), raw)

    private fun cleanName(words: String, raw: String): String? {
        val tokens = words.replace(Regex("\\b(?:please|thank you|thanks)\\b"), " ").trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty() || tokens.size > 3) return null
        if (tokens.any { !TOKEN.matches(it) || it in NOT_NAME }) return null
        // The recognizer's own capitals where the word can be found as heard ("McCarthy"), else a capital first letter.
        val asHeard = Regex("[\\p{L}'’-]+").findAll(raw).map { it.value.replace('’', '\'') }.toList()
        return tokens.joinToString(" ") { t ->
            (asHeard.firstOrNull { it.equals(t, ignoreCase = true) } ?: t).replaceFirstChar { it.uppercaseChar() }
        }
    }

    // ---- language
    private val BOTH = Regex("\\b(?:both|mix|mixed|either|any|whichever|doesn't matter|does not matter|don't mind|dont mind)\\b")
    private val TWI = Regex("\\b(?:twi|akan|asante)\\b")
    // "brofo" / "borɔfo" is Twi for English.
    private val ENGLISH = Regex("\\b(?:english|brofo|borɔfo|borofo)\\b")

    fun language(g: String): String? {
        val tw = TWI.containsMatchIn(g); val en = ENGLISH.containsMatchIn(g)
        return when {
            (tw && en) || BOTH.containsMatchIn(g) -> Settings.LANG_MODE_BOTH
            tw -> Settings.LANG_MODE_TWI
            en -> Settings.LANG_MODE_ENGLISH
            else -> null
        }
    }

    // ---- speaking speed (values are FastIntent.Preference("rate", …)'s)
    private val SLOWER_FIRST = Regex("\\b(?:too|so|very|a bit|a little) (?:fast|quick)\\b|\\bnot so fast\\b|\\bslow down\\b")
    private val FASTER_FIRST = Regex("\\b(?:too|so|very|a bit|a little) slow\\b|\\bspeed up\\b|\\bhurry up\\b")
    private val SLOWER = Regex("\\b(?:slow|slower|slowly|more slowly)\\b")
    private val FASTER = Regex("\\b(?:fast|faster|quick|quicker|quickly)\\b")
    private val KEEP = Regex("\\b(?:yes|yeah|yep|ok|okay|fine|good|great|perfect|alright|all right|right|normal|nice|cool|lovely|" +
        "keep it|just right|no change|same|it's okay|that's okay)\\b")

    fun rate(g: String): String? = when {
        SLOWER_FIRST.containsMatchIn(g) -> "slower"
        FASTER_FIRST.containsMatchIn(g) -> "faster"
        SLOWER.containsMatchIn(g) && !FASTER.containsMatchIn(g) -> "slower"
        FASTER.containsMatchIn(g) && !SLOWER.containsMatchIn(g) -> "faster"
        KEEP.containsMatchIn(g) -> "keep"
        else -> null
    }

    // ---- a request instead of an answer
    private val COMMAND_LEAD = Regex("^(?:(?:please|kindly|ok|okay|hey|so)\\s+)*(?:open|launch|start|call|phone|ring|dial|send|text|message|" +
        "whatsapp|e-?mail|play|pause|read|check|turn|switch|set|show|find|search|look|tell|what|what's|whats|when|where|who|who's|" +
        "why|how|is|are|do|does|did|can|could|will|would|remind|take|go|navigate|pay|buy|transfer|reply|answer|hang|describe|help|" +
        "increase|decrease|raise|lower|mute|unmute|lock|unlock|scroll|close|delete|share|book|order|translate|spell|convert|" +
        "schedule|give|put|make)\\b")

    /**
     * Is [text] a request rather than an answer? A request verb or question word with at least one more word ("open
     * WhatsApp", "what's the time" — a lone "what?" is a mishearing, not a question), or anything the local fast path
     * recognises. The check runs only after the step's own answers were looked for.
     */
    fun looksLikeCommand(text: String): Boolean {
        val g = norm(text)
        if (g.isEmpty()) return false
        if (COMMAND_LEAD.containsMatchIn(g) && g.split(' ').size >= 2) return true
        return try { com.tekyerema.agent.FastIntentClassifier.classify(g) != null } catch (_: Throwable) { false }
    }

    // ------------------------------------------------------------------ the greeting

    /** What the user asked to be called (Settings.preferredName; kept current by VoiceEngine) — for spoken greetings. */
    @Volatile var userName: String = ""

    /** "Good morning" → "Good morning, Ama"; unchanged without a name. */
    fun withName(greeting: String, name: String = userName): String =
        if (name.isBlank()) greeting else "$greeting, ${name.trim()}"
}

/**
 * "Send a practice message": a REHEARSAL of the spoken confirmation for a text, to a made-up "Practice contact". It asks
 * what the message should say, then the same question, the same "needs your OK" cue and the same yes/no reading as a
 * real text (Confirmation / YesNo — a wait, a repeat, a safe decline), and always ends by saying nothing was sent. It
 * never reaches the agent or any tool, so nothing CAN be sent (VoiceController intercepts the phrase before the fast path).
 */
object Practice {
    const val CONTACT = "Practice contact"
    const val DEFAULT_MESSAGE = "Hello, this is a practice message"
    const val START = "Let's practise sending a text. Nothing will really be sent."
    const val ASK_MESSAGE = "What should the message say?"
    const val ONLY_PRACTICE = "That was only practice — nothing was sent."
    const val AFTER_YES = "For a real message, I send it only after your yes."

    private val TRIGGER = Regex("^(?:(?:please|ok|okay|yes|yeah|sure|let's|lets|let me|i want to|i'd like to|can i|can we)\\s+)*" +
        "(?:send (?:a |the |me a )?practi[cs]e (?:message|text|sms)|practi[cs]e(?: sending)?(?: a)?(?: (?:message|text))?|" +
        "(?:do|try) (?:a |the )?practi[cs]e(?: message| text| run)?)(?:\\s+please)?$")

    fun matches(text: String): Boolean = TRIGGER.matches(Welcome.norm(text))

    /** The real SMS confirmation's shape (SendSmsTool.confirmationPrompt), for the made-up contact. */
    fun prompt(message: String): String = "Text $CONTACT: \"$message\"?"

    suspend fun run(io: Welcome.Io): Welcome.Outcome {
        io.say(START)
        val message = when (val r = io.ask(ASK_MESSAGE)) {
            Welcome.Reply.Cancelled -> return Welcome.Outcome.Cancelled
            Welcome.Reply.Silence -> DEFAULT_MESSAGE
            is Welcome.Reply.Text -> {
                if (Welcome.isStop(r.text)) { io.say(ONLY_PRACTICE); return Welcome.Outcome.Stopped }
                r.raw.trim().trimEnd('.', '!', '?', ' ').take(160).ifBlank { DEFAULT_MESSAGE }
            }
        }
        io.needsOk()
        var question = Confirmation.question(prompt(message))
        var attempt = 0
        var holds = 0
        var patient = false
        while (attempt < 2) {
            val r = io.ask(question, yesNo = true, patient = patient)
            if (r == Welcome.Reply.Cancelled) return Welcome.Outcome.Cancelled
            val answer = (r as? Welcome.Reply.Text)?.let { t -> YesNo.decide(t.text, t.alternatives.map { Hypothesis(it, null) }) }
                ?: Confirmation.Answer.UNCLEAR
            when (answer) {
                Confirmation.Answer.YES -> { io.say("Okay."); io.say("$ONLY_PRACTICE $AFTER_YES"); return Welcome.Outcome.Finished }
                Confirmation.Answer.NO -> { io.say("Okay, I won't."); io.say(ONLY_PRACTICE); return Welcome.Outcome.Finished }
                Confirmation.Answer.HOLD -> if (++holds <= 3) {
                    patient = true
                    question = Phrases.get("confirmHold", false, "Okay, take your time. Say yes or no when you're ready.")
                } else { if (attempt == 0) question = Confirmation.REPEAT; attempt++ }
                Confirmation.Answer.UNCLEAR -> { if (attempt == 0) question = Confirmation.REPEAT; attempt++ }
            }
        }
        // Two unclear answers: the real flow does nothing and says so — and so does the practice.
        io.say(Confirmation.DECLINED_UNCLEAR)
        io.say(ONLY_PRACTICE)
        return Welcome.Outcome.Finished
    }
}

/** What the welcome may change (VoiceEngine's settings). */
interface WelcomeHost {
    /** A Twi voice is really wired (the "ghana" voice layer); false = the phone speaks English only for now. */
    val twiVoice: Boolean
    /** Apply [effect]; returns a line to say, or null. */
    fun apply(effect: Welcome.Effect): String?
}

/** The real host: the answers go into [settings]; the speed goes through the H-08 preference ([applyRate] = the fast
 *  path's own handler, which also says what changed). */
class SettingsWelcomeHost(
    private val settings: Settings,
    override val twiVoice: Boolean,
    private val applyRate: (String) -> String?
) : WelcomeHost {
    override fun apply(effect: Welcome.Effect): String? = when (effect) {
        is Welcome.Effect.SetName -> { settings.preferredName = effect.name; Welcome.userName = effect.name; null }
        is Welcome.Effect.SetLanguage -> {
            settings.languageMode = effect.mode
            // The habitual language follows an explicit choice; "both" leaves it to what the user speaks.
            when (effect.mode) {
                Settings.LANG_MODE_TWI -> { settings.sessionLanguage = Lang.TWI; VoiceEngine.warmTranslator() }
                Settings.LANG_MODE_ENGLISH -> settings.sessionLanguage = Lang.GH_EN
            }
            null
        }
        is Welcome.Effect.SetRate -> applyRate(effect.value)
        Welcome.Effect.StartPractice -> null
    }
}
