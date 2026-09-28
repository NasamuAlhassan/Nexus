package com.tekyerema.voice

import com.tekyerema.core.config.AppConfig

/**
 * Pacing long readings for the ear. The screen reader (F-01) used to speak ~480-character parts: about 30 seconds of
 * monologue at the normal rate before "There's more — say continue", with no way to say "stop" in between (barge-in is
 * off on the A20e). Voice guidelines keep a turn short and page long content with an explicit "more" (Amazon Alexa
 * design guide: brevity, one breath, chunked lists); screen-reader users set their own pace (the rate setting).
 *
 * So a part is sized by TIME, not characters — about [partSeconds] of speech at the user's speech rate — and the reply
 * that invites "continue" re-opens the microphone on its own (VoiceController), so going on is one word, and "stop" /
 * "that's enough" ends it. Pure → unit-tested.
 */
object ReadingPace {
    /** The reply voice's speech-rate multiplier (VoiceEngine keeps it in step with the Voice settings). */
    @Volatile var rate: Float = 1.0f

    /** Characters spoken in about `speech.reading.partSeconds` (default 20 s) at [rate]. The system English voice says
     *  roughly 15 characters a second at 1.0 (`speech.reading.charsPerSecond`). */
    fun partChars(rate: Float = this.rate): Int {
        val seconds = AppConfig.int("speech.reading.partSeconds", 20)
        val cps = AppConfig.int("speech.reading.charsPerSecond", 15)
        return (seconds * cps * rate.coerceIn(0.5f, 2.0f)).toInt().coerceIn(MIN_CHARS, MAX_CHARS)
    }

    /** The reply ends by inviting the user to go on ("… There's more — say “continue”."). */
    fun invitesContinue(reply: String): Boolean = INVITE.containsMatchIn(reply.trim())

    /** Right after a reply, "stop" / "that's enough" / "be quiet" means "no more", not a new command (a bare "stop" used
     *  to reach the fast path as a media STOP key). */
    fun isEnough(text: String): Boolean {
        if (ControlVerbs.classify(text) == ControlVerbs.Verb.STOP) return true
        val t = text.lowercase().replace(Regex("[^\\p{L}\\p{N}ɛɔ'\\s]"), " ").replace(Regex("\\s+"), " ").trim()
        return ENOUGH.matches(t)
    }

    private const val MIN_CHARS = 160
    private const val MAX_CHARS = 600
    private val INVITE = Regex("(?i)say [“\"']?(continue|go on)[”\"']?[.!]?$")
    private val ENOUGH = Regex("^(?:(?:ok(?:ay)?|thanks|thank you) )?(?:that'?s (?:enough|all|fine)|enough|be quiet|quiet|stop reading|stop talking|shut up|no more)(?: thanks| thank you| please)?$")
}
