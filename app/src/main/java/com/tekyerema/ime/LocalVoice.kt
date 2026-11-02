package com.tekyerema.ime

import java.security.MessageDigest

/**
 * Local languages through the Nexus keyboard, Phase 1 (owner, 2 Nov 2026; the contract is docs/agent/KEYBOARD_LANGUAGES.md).
 * Online only: in a local language the keyboard's microphone sends the speech to our Space (/web/asr), may translate it to
 * English (/web/translate, names and numbers kept by core/TranslateGuard), reads back EXACTLY what it will type, in the
 * language it will be typed in (/web/tts, or the phone's English voice), and then types it. Nexus itself stays English.
 * The pure parts live here (LocalVoiceTest); the network is language/SpaceVoice, the flow [LocalDictation].
 */

/** A language the keyboard can hear. [code] is the Space's own ("twi", "ewe", "kus", "hau"). Every language with a
 *  microphone on the Space is on (owner, 2 Nov 2026: "turn them on"); [warning] is said once, the first time it is picked.
 *  Dagbani has no speech recognition on the Space, so it can't be a dictation language (contract §1). */
data class LocalLang(val code: String, val name: String, val warning: String? = null)

object LocalLanguages {
    val TWI = LocalLang("twi", "Twi")
    val EWE = LocalLang("ewe", "Ewe")
    val KUSAAL = LocalLang("kus", "Kusaal")
    val HAUSA = LocalLang("hau", "Hausa", warning = "Hausa may mishear often for now.")
    val ALL = listOf(TWI, EWE, KUSAAL, HAUSA)

    fun of(code: String?): LocalLang? = ALL.firstOrNull { it.code == code }
}

/** Settings › Keyboard › "Type my words in": the words as heard, English, or ask each time (the default). */
enum class SendMode(val key: String) {
    MINE("mine"), ENGLISH("english"), ASK("ask");
    companion object { fun of(key: String?): SendMode = values().firstOrNull { it.key == key } ?: ASK }
}

/**
 * The phone's code for the Space's fair-use budget (`X-Nexus-Device`, contract §2): SHA-256 of a random per-install id, as 64
 * lower-case hex, and a new id every calendar month (or when the user resets it). Never a phone number, account or IMEI.
 */
object DeviceTag {
    fun hash(id: String): String =
        MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** 2026-11 → 202611: the id is renewed when this changes. */
    fun month(nowMs: Long): Int = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = nowMs }
        .let { it.get(java.util.Calendar.YEAR) * 100 + it.get(java.util.Calendar.MONTH) + 1 }

    fun valid(tag: String): Boolean = tag.length == 64 && tag.all { it in '0'..'9' || it in 'a'..'f' }
}

/** What the local voice is sent to read back: the exact text, with figures in words where that voice drops digits. */
object ReadBack {
    /** The Space's voices take at most 600 characters (it cuts the rest). */
    const val MAX = 600

    fun forVoice(text: String, lang: LocalLang): String {
        val t = text.trim()
        // The Twi voice skips digits and "GH₵": figures become Twi words first (voice/understand/TwiSpeechText, the
        // owner's verified numerals). The other voices get the text as typed for now (Kusaal's has no digits: [owner to listen]).
        val said = if (lang.code == LocalLanguages.TWI.code) com.tekyerema.voice.understand.TwiSpeechText.prepare(t) else t
        return said.take(MAX)
    }

    /** A dictation too long to read back in one go is not typed by this path (the user hears what goes in, all of it). */
    fun tooLong(text: String): Boolean = text.trim().length > MAX
}

/** The few English lines the keyboard says itself (contract §2: one short English line, then English dictation). */
object LocalVoiceLines {
    fun online(lang: LocalLang) = "Online — ${lang.name}"
    const val OFFLINE = "No internet, so voice typing is in English."
    const val LIMITED = "Too many requests for now, so voice typing is in English."
    const val UNAVAILABLE = "The language service isn't answering, so voice typing is in English."
    const val NOT_SAFE = "I couldn't keep a name or number safe in English, so I typed your own words."
    const val TOO_LONG = "That was too long to read back. Please say it in shorter parts."
    fun ask(lang: LocalLang) = "In ${lang.name} or in English?"
}
