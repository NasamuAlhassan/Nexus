package com.tekyerema.voice

/**
 * The recogniser's hesitation sounds at the EDGES of a request — "eh read my message please eh" — dropped before the phone's
 * own understanding runs (rehearsal, 15 Oct 2026: with them the fast path missed, and the cloud read every notification
 * instead of the message; "eh send money to him" lost the money request). Only sounds that are never words, and only at the
 * start or the end: an "eh" inside the words ("tell Kofi eh I'm coming") stays, and so does "please". Pure.
 */
object EdgeFillers {
    private const val SOUND = "(?:e+h+|e+h+m+|u+m+|u+h+|e+r+m*|a+h+|h+m+|m{2,})"
    private val LEAD = Regex("(?i)^(?:$SOUND[\\s,.!?…-]+)+")
    private val TAIL = Regex("(?i)(?:[\\s,.…-]+$SOUND)+[\\s.!?…]*$")

    fun strip(s: String): String {
        val t = s.replace(LEAD, "").replace(TAIL, "").trim()
        return t.ifEmpty { s }
    }
}
