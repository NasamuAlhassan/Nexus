package com.tekyerema.voice.io

import com.tekyerema.voice.Lang

/**
 * Profile `gh_mixed` (the owner's choice, 2026-09-29): **English → the phone's recognizer (Google, en-GH); Twi → the UG
 * HCI Lab** (the captured-audio path: HCI Lab ASR online, the on-device Twi Whisper when the lab is unreachable or its
 * daily quota is spent). Replies follow the same split through RoutingTts (English: the system voice; Twi: HCI Lab TTS).
 *
 * Each listen goes to ONE recognizer, chosen before the mic opens by [language] (the user's language setting, else the
 * session language). Google's English recognizer can't hear Twi, so reaching Twi is explicit — "speak Twi" / "kasa Twi",
 * the welcome, or Settings — never a guess. [caps] follow the recognizer that the next listen will use, so the controller's
 * timing, secure-step and barge-in decisions match it.
 */
class LanguageRoutedInput(
    private val english: VoiceInput,
    private val twi: VoiceInput,
    private val language: () -> String
) : VoiceInput {
    override val id: String get() = "gh_mixed(${pick().id})"

    /** The recognizer for the next listen. Pure given [language]. */
    internal fun pick(): VoiceInput = if (language() == Lang.TWI) twi else english

    override val caps: InputCaps get() = pick().caps

    override suspend fun listen(spec: ListenSpec, events: ListenEvents): Heard {
        val lang = language()
        val input = if (lang == Lang.TWI) twi else english
        return input.listen(spec.copy(language = if (lang == Lang.TWI) Lang.TWI else Lang.GH_EN), events)
    }

    override suspend fun transcribe(wav: ByteArray): Heard = pick().transcribe(wav)
    override fun cancel() { english.cancel(); twi.cancel() }
    override fun release() { english.release(); twi.release() }
}
