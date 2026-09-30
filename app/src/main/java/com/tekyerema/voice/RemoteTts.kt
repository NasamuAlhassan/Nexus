package com.tekyerema.voice

import android.util.Log

/**
 * Routes TTS by language. English → the English voice (the phone's engine today); Twi → the preferred Twi voice.
 * The online Twi and Ghanaian-English voices plug in here — the UG HCI Lab TTS API is the required provider (the
 * Khaya/Abena clients were removed 2026-09-28, owner-approved); which provider fills each slot comes from config
 * ([SpeechProviders], B-18). Directive §28: if the preferred Twi voice is unavailable (offline, or it fails), the
 * assistant must NOT go silent — it falls back to the on-device system engine. Pronunciation is imperfect (an
 * English voice reading Twi), so this is a last resort and is logged; hearing the reply matters more than the accent.
 */
class RoutingTts(
    private val english: TtsProvider,
    private val twi: TtsProvider,
    /** Optional English→Twi localizer, so a Twi session is answered in Twi even when the response text is English
     *  (confirmations, errors, cloud-brain results). Returns null to leave text as-is (already Twi, or no MT).
     *  App-authored Twi strings pass through untouched. None is wired until the HCI Lab offers MT. */
    private val enToTw: (suspend (String) -> String?)? = null,
    /** A Ghanaian-accented English voice, used for English replies when online; the phone's own engine has no
     *  Ghanaian voice. Null ⇒ system engine only. */
    private val ghEnglish: TtsProvider? = null,
    private val isOnline: () -> Boolean = { true }
) : TtsProvider {
    override val name = "routing(en=${english.name}; ghEn=${ghEnglish?.name ?: "-"}; twi=${twi.name})"
    override suspend fun speak(rawText: String, language: String): Boolean {
        // Strip markdown (**bold**, *, _, `, #) the cloud brain sometimes emits: it makes a voice read "asterisk".
        // Replies are spoken, so plain text.
        val text = rawText.replace(Regex("[*_`#]+"), "").replace(Regex("[ \\t]+"), " ").trim()
        // Pause marks (SpokenText) are silences on the system voice only; network voices and the Twi localizer get plain text.
        val plain = SpokenText.plain(text)
        if (language == Lang.TWI) {
            // Localize English response text to Twi so the Twi VOICE speaks Twi WORDS (not English read by
            // a Twi voice). Already-Twi text (e.g. LocalBrain "Mabue no.") is left as-is by enToTw. The language
            // layer's localizer also refuses anything with a quantity or a name it can't keep (ReplyLocalizer).
            val localized = try { enToTw?.invoke(plain)?.takeIf { it.isNotBlank() } }
                catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Exception) { null }
            // X-06: the Twi voice gets the standard spelling (look-alike ɛ/ɔ mapped, tone marks dropped).
            val twiText = com.tekyerema.language.TwiText.canonical(localized ?: plain)
            if (twi.speak(twiText, language)) { Log.i("Tekyerema", "[TTS] spoke Twi via ${twi.name}"); return true }
            Log.w("Tekyerema", "[TTS] Twi voice (${twi.name}) unavailable → English system-engine fallback (${english.name}) (§28)")
            return english.speak(text, Lang.GH_EN)   // fallback: ORIGINAL English text, English voice — never silent
        }
        // English: prefer a Ghanaian-accented voice when online; fall back to the system engine (offline, no
        // provider, or a failure) so we're never silent and never stall offline.
        if (ghEnglish != null && isOnline()) {
            if (ghEnglish.speak(plain, Lang.GH_EN)) { Log.i("Tekyerema", "[TTS] spoke Gh-English via ${ghEnglish.name}"); return true }
            Log.w("Tekyerema", "[TTS] Gh-English voice unavailable → system-engine fallback (${english.name})")
        }
        Log.i("Tekyerema", "[TTS] spoke $language via ${english.name}")
        return english.speak(text, language)
    }
    override fun stop() { english.stop(); twi.stop(); ghEnglish?.stop() }
    override fun shutdown() { english.shutdown(); twi.shutdown(); ghEnglish?.shutdown() }
}
