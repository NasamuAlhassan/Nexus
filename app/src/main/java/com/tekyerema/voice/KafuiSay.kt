package com.tekyerema.voice

/**
 * How the Space's Kafui English voice (F5) is told to say our own words. The Space reads an English line as it is written
 * (only its on-device Piper copy has a `say` list, models/ondevice/eng/tts/voice.json), so "Tɛkyerɛma", "MTN" and "cedis"
 * reached it unchanged. OWNER PICKED BY EAR (24 Oct 2026, Temp/kafui_words): "Tetchy rehma", "M T N", "seedies … pesswess".
 * The singulars follow the plurals (not heard). `speech.ghEnglish.say` (word or phrase → spelling) overrides or adds
 * without a rebuild. Whole words, any case. Pure → KafuiSayTest.
 */
object KafuiSay {
    val DEFAULT: Map<String, String> = linkedMapOf(
        "tɛkyerɛma" to "Tetchy rehma", "tekyerema" to "Tetchy rehma",
        "mtn" to "M T N",
        "cedis" to "seedies", "cedi" to "seedy",
        "pesewas" to "pesswess", "pesewa" to "pessweh")

    fun apply(line: String, say: Map<String, String> = configured()): String {
        var s = line
        for ((k, v) in say.entries.sortedByDescending { it.key.length }) {
            if (k.isBlank() || k.startsWith("_") || v.isBlank()) continue
            s = Regex("(?i)(?<![\\p{L}])" + Regex.escape(k) + "(?![\\p{L}])").replace(s, Regex.escapeReplacement(v))
        }
        return s
    }

    private fun configured(): Map<String, String> {
        val extra = try {
            val o = com.tekyerema.core.config.AppConfig.obj("speech.ghEnglish.say")
            o?.keys()?.asSequence()?.associate { it.lowercase() to o.optString(it) } ?: emptyMap()
        } catch (_: Throwable) { emptyMap() }
        return DEFAULT + extra
    }
}
