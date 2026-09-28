package com.tekyerema.language

import com.tekyerema.voice.TtsProvider

/**
 * B-17/B-20 — wraps a voice that only knows English letters (the phone's own engine). Before this, a Twi reply that
 * fell back to that voice ("Mabue no." is fine, but "Merefrɛ Kwabɛna") had its ɛ/ɔ spelled out or skipped; now
 * they are read as the nearest plain vowel ([TwiText.forEnglishVoice]). English text has none of these letters and
 * passes through untouched, so English replies are byte-for-byte what they were.
 */
class PlainLatinVoice(private val inner: TtsProvider) : TtsProvider {
    override val name: String get() = inner.name
    override suspend fun speak(text: String, language: String): Boolean = inner.speak(TwiText.forEnglishVoice(text), language)
    override fun stop() = inner.stop()
    override fun shutdown() = inner.shutdown()
}
