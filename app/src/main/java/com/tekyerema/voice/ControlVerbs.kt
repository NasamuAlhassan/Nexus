package com.tekyerema.voice

/**
 * C-12 — what the user says WHILE a task runs: stop it, pause it, carry on, ask what it's doing, or (anything else)
 * a correction folded into the running task (C-19). Pure → unit-tested. Twi: gyae = stop, twɛn = wait,
 * kɔ so = go on, woreyɛ dɛn = what are you doing.
 */
object ControlVerbs {
    enum class Verb { STOP, PAUSE, RESUME, STATUS, STEER }

    private val STOP = Regex("^(?:stop|cancel|never mind|nevermind|forget it|abort|stop it|stop that|gyae|gyae no)$")
    private val PAUSE = Regex("^(?:pause|wait|hold on|hold|hang on|one moment|twɛn|twen)$")
    private val RESUME = Regex("^(?:continue|resume|go on|carry on|keep going|go ahead|kɔ so|ko so)$")
    private val STATUS = Regex("^(?:what are you doing|what(?:'s| is) happening|status|where are you|how far|are you done|" +
        "is it done|how long|what's taking so long|woreyɛ dɛn|woreye den)$")

    fun classify(text: String): Verb {
        val t = text.lowercase().replace(Regex("[^\\p{L}\\p{N}ɛɔ'\\s]"), " ").replace(Regex("\\s+"), " ").trim()
            .removePrefix("please ").removeSuffix(" please").trim()
        return when {
            STOP.matches(t) -> Verb.STOP
            PAUSE.matches(t) -> Verb.PAUSE
            RESUME.matches(t) -> Verb.RESUME
            STATUS.matches(t) -> Verb.STATUS
            else -> Verb.STEER
        }
    }
}
