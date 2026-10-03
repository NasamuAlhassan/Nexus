package com.tekyerema.language

/**
 * A reply given in Twi while the app is WAITING — at a read-back ("daabi, soma pesewa aduonu": no, send 20 pesewas) or
 * on one of its own questions ("pesewa du" for "how much?") — in the English the revise and answer paths understand.
 * Pure. On the A20e (QA TR10 / TR12, 3 October 2026) the Twi correction was taken as a plain "no" and the Twi amount
 * answer was asked again "in cedis", because both paths only knew English words.
 *
 *  - "daabi" / "aane" alone and anything that isn't Twi are returned unchanged (the yes/no words are known there).
 *  - A no-word followed by a Twi command becomes "no, <English>"; a bare "send <amount>" becomes just the amount
 *    ("no, 20 pesewas"), which the revise path reads as a new amount for the same recipient.
 *  - An answer that the Twi normaliser turns into figures and units ("pesewa du" → "10 pesewas") is returned so.
 */
object TwiReply {
    private val NO_LEAD = Regex("^\\s*(?:daabi|dabi|ɛnyɛ\\s+saa|nnyɛ|mmom)[,!.\\s]+", RegexOption.IGNORE_CASE)
    private val SEND_ONLY = Regex("^send (\\d+(?:\\.\\d+)?\\s+(?:cedis?|pesewas?))$")
    private val HAS_AMOUNT = Regex("\\d+(?:\\.\\d+)?\\s+(?:cedis?|pesewas?)")

    /** [isContact]: a saved contact's name (TwiCommands' plausibility check). */
    fun toEnglish(reply: String, isContact: (String) -> Boolean = { false }): String {
        val t = reply.trim()
        if (t.isEmpty()) return reply
        val norm = com.tekyerema.voice.Normalizer.forTranslation(t).trim()
        val lead = NO_LEAD.find(norm)
        val rest = (if (lead != null) norm.substring(lead.range.last + 1) else norm).trim()
        if (rest.isEmpty()) return reply                       // "daabi" alone: a no, as it was
        val glossed = TwiCommands.gloss(rest, isContact)
        val english = when {
            glossed != null -> SEND_ONLY.find(glossed)?.groupValues?.get(1) ?: glossed
            // "pesewa du" → "10 pesewas": the normaliser's figures and units are the answer
            HAS_AMOUNT.containsMatchIn(rest) && rest != t -> rest
            else -> return reply
        }
        return if (lead != null) "no, $english" else english
    }
}
