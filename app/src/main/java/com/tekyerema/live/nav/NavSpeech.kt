package com.tekyerema.live.nav

/** What a user says to walking directions while they run. Pure (unit-tested). */
object NavSpeech {
    enum class Match { STOP, REPEAT, HOW_FAR }

    private const val LEAD = "^(?:(?:ok(?:ay)?|please|now|so|and)\\s+)*"
    private val STOP = Regex("$LEAD(?:stop|end|cancel|quit|turn off|close)(?: the| my)? (?:directions?|navigation|navigating|guidance|route|walk)(?: please| now)?$")
    private val REPEAT = Regex("$LEAD(?:repeat(?: the| that)?(?: directions?| instruction| turn)?|say (?:that|it) again|(?:what'?s|what is) (?:the |my )?next (?:turn|step|direction)|where do i (?:turn|go)(?: next)?|which way(?: now)?|next turn)\\??$")
    private val HOW_FAR = Regex("$LEAD(?:how (?:far|long)(?: is it| is that| do i have| to go| left| remaining)*(?: (?:still|now))?|are we there yet|how much (?:further|farther|more))\\??$")

    fun match(said: String, active: Boolean): Match? {
        if (!active) return null
        val s = said.trim().lowercase().replace('’', '\'').trimEnd('.', '!')
        return when {
            STOP.matches(s) -> Match.STOP
            REPEAT.matches(s) -> Match.REPEAT
            HOW_FAR.matches(s) -> Match.HOW_FAR
            else -> null
        }
    }
}
