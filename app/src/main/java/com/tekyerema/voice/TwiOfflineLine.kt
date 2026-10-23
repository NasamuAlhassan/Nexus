package com.tekyerema.voice

/**
 * Offline, in Twi, an on-phone line in English has no Twi (the translator is online only): the Twi voice said "Mintumi nkyerɛ
 * eyi ase seesei. San bisa bio." even after the action had worked, and the user asked again (audit, 22 Oct 2026). A short Twi
 * done / not-done line is said instead — but "done" only for an action the phone really carried out ([acted]), never for a
 * question, a line that opens the mic for an answer, or a line that merely reads as fine ("No contact named Kofi."), and "not
 * done" only for a reported failure (models session's review, 23 Oct 2026). Everything else keeps the line as it was. Pure.
 */
object TwiOfflineLine {
    enum class Pick { DONE, FAILED }

    fun pick(twi: Boolean, systemVoice: Boolean, hasTwiLine: Boolean, online: Boolean, englishOnly: Boolean,
             acted: Boolean, ok: Boolean, listenAfter: Boolean, spoken: String, guide: Boolean = false): Pick? {
        if (!twi || !systemVoice || hasTwiLine || online || !englishOnly || guide) return null
        if (listenAfter || spoken.trimEnd().endsWith("?")) return null
        return when {
            acted && ok -> Pick.DONE
            !ok -> Pick.FAILED
            else -> null
        }
    }
}
