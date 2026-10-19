package com.tekyerema.voice

/**
 * The yes that approves a confirmation in a KUSAAL session (19 October 2026). The question is asked in Kusaal and the answer
 * is heard by the phone's English recogniser (no Kusaal yes / no is verified yet). A Kusaal word heard by that recogniser can
 * come back as an English "yes"-like word — "okay", "yeah", "sure" — so only a plain English yes approves; anything else is
 * asked again and then declined, never acted on. Pure.
 */
object KusaalYes {
    private val STRICT = Regex("^(?:yes|yes please|yes go ahead|yes do it|go ahead|yes,? go ahead)$")

    fun strict(heard: String): Boolean =
        STRICT.matches(heard.lowercase().replace(Regex("[^a-z ,]"), " ").replace(Regex("\\s+"), " ").trim().trimEnd(','))
}
