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
    private val language: () -> String,
    /** Offline, English goes to the on-device recognizer too (the captured path): the phone's recognizer has no offline
     *  English pack on the A20e — offline it answered "error 13, no such language" to every listen (2026-09-29). */
    private val isOnline: () -> Boolean = { true }
) : VoiceInput {
    override val id: String get() = "gh_mixed(${pick().id})"

    /** The recognizer for the next listen. Pure given [language]. */
    internal fun pick(): VoiceInput = if (language() == Lang.TWI || !isOnline()) twi else english

    override val caps: InputCaps get() = pick().caps

    override suspend fun listen(spec: ListenSpec, events: ListenEvents): Heard {
        val lang = language()
        if (lang == Lang.TWI) {
            // A yes / no in a Twi conversation is heard by the phone's own recogniser (owner's plan D, 2 October 2026): in
            // the loop test the HCI Lab recogniser wrote "Aane" / "Daabi" as "so" / "fida", and every answer would spend
            // its small daily quota. "Yes", "no", "aane" (heard "Anne"), "daabi" ("Darby") all count — voice/Confirmation.
            // …and so is a phone number or an amount the app asked for (spec.digits): said in English inside Twi, the HCI
            // Lab recogniser wrote "0…" as "zirofftuziro ne tsi sir".
            if ((spec.purpose == ListenPurpose.YES_NO || spec.digits) && isOnline() &&
                com.tekyerema.core.config.AppConfig.bool("speech.twi.yesNoOnPhoneRecognizer", true)) {
                val heard = english.listen(spec.copy(language = Lang.GH_EN), events)
                if (!(heard is Heard.Unavailable && heard.cause in FALL_BACK)) return heard
            }
            return twi.listen(spec.copy(language = Lang.TWI), events)
        }
        val en = spec.copy(language = Lang.GH_EN)
        if (!isOnline()) return twi.listen(en, events)          // on-device English (Whisper) — works with no internet
        val heard = english.listen(en, events)
        // The phone's recognizer couldn't serve this listen (no language pack, unreachable, none installed) BEFORE the user's
        // words were taken: listen again on the phone's own recognizer instead of failing the turn.
        return if (heard is Heard.Unavailable && heard.cause in FALL_BACK) twi.listen(en, events) else heard
    }

    private val FALL_BACK = setOf(Heard.Cause.LANGUAGE, Heard.Cause.OFFLINE, Heard.Cause.NO_RECOGNIZER)

    override suspend fun transcribe(wav: ByteArray): Heard = pick().transcribe(wav)
    override fun cancel() { english.cancel(); twi.cancel() }
    override fun release() { english.release(); twi.release() }
}
