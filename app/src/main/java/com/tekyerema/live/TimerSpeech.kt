package com.tekyerema.live

/** What a user says to Tɛkyerɛma's own timer. Pure (unit-tested). */
object TimerSpeech {
    enum class Match { STOP, LEFT, ADD_MINUTE }

    private val STOP_RINGING = Regex("^(?:(?:ok(?:ay)?|alright|yes|thank you|thanks)[ ,]*)*(?:stop|dismiss|enough|quiet|silence|shut up|ok(?:ay)?|i'?m up|got it|done)(?: (?:it|that|the (?:timer|alarm|sound|noise)))?(?: please| now)?[.!]?$")
    private val LEFT = Regex("^(?:how (?:much|long)(?: time)?(?: is| 's)? (?:left|remaining)(?: on (?:the|my) timer)?|(?:what'?s|what is) (?:the )?time left(?: on (?:the|my) timer)?|time left|timer status|how'?s (?:the|my) timer)\\??$")
    private val ADD = Regex("^(?:add|give me|another|one more)(?: (?:a|one|1))? (?:more )?minute(?: more)?(?: to (?:the|my) timer)?(?: please)?$")

    /** [ringing]: a timer is going off now; [running]: one counts down. Null = not about the timer. */
    fun match(said: String, ringing: Boolean, running: Boolean): Match? {
        val s = said.trim().lowercase().replace('’', '\'').trimEnd('.', '!')
        if (ringing && STOP_RINGING.matches(s)) return Match.STOP
        if ((running || ringing) && ADD.matches(s)) return Match.ADD_MINUTE
        if (running && LEFT.matches(s)) return Match.LEFT
        return null
    }
}
