package com.tekyerema.agent

import com.tekyerema.voice.Phrases

/**
 * The quick answers in Twi, written once instead of machine-translated (research, 20 Oct 2026: "The time is 3:15 PM" came back
 * as "mmiɛnsa duanum PM"; and offline there is no translator, so "Abɔ sɛn?" heard on the phone had no answer). Figures stay
 * figures here: the Twi voice says them as Twi number words (voice/understand/TwiSpeechText) and the screen shows them.
 * Phrasing after Harvard ELIAS / learnakan (abɔ + part of the day + nnɔn + hour + "apa ho simma" + minutes; "ɔha mu nkyɛmu"
 * for percent). [owner: a native speaker to verify — every line is a Phrases key, so the wording can change without code.]
 * Pure.
 */
object TwiInfoLines {
    /** The part of the day a Twi speaker names with the hour (24 h clock). */
    fun partOfDay(hour24: Int): String = when (hour24) {
        in 5..11 -> "anɔpa"
        in 12..15 -> "awia"
        in 16..18 -> "anwummerɛ"
        else -> "anadwo"
    }

    fun time(hour24: Int, minute: Int): String {
        val h12 = ((hour24 + 11) % 12) + 1
        val part = partOfDay(hour24)
        val hour = if (h12 == 1) "dɔn baako" else "nnɔn $h12"
        return if (minute == 0) Phrases.get("twiTimeExact", true, "Abɔ {part} {hour}.", "part" to part, "hour" to hour)
        else Phrases.get("twiTime", true, "Abɔ {part} {hour} apa ho simma {min}.", "part" to part, "hour" to hour, "min" to minute.toString())
    }

    fun battery(pct: Int, charging: Boolean): String =
        if (charging) Phrases.get("twiBatteryCharging", true, "Wo batri no aka ɔha mu nkyɛmu {pct}, na ɛregye charge.", "pct" to pct.toString())
        else Phrases.get("twiBattery", true, "Wo batri no aka ɔha mu nkyɛmu {pct}.", "pct" to pct.toString())
}
