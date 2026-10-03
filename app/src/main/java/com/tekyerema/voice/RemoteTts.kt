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
    private val isOnline: () -> Boolean = { true },
    /** Tracks whether the Twi voice works, and decides when the user is told it doesn't (once per session). */
    private val twiStatus: TwiVoiceStatus = TwiVoiceStatus.shared
) : TtsProvider {
    override val name = "routing(en=${english.name}; ghEn=${ghEnglish?.name ?: "-"}; twi=${twi.name})"
    override suspend fun speak(rawText: String, language: String): Boolean {
        NowSpeaking.begin()
        try { return speakInner(rawText, language) } finally { NowSpeaking.end() }
    }

    private suspend fun speakInner(rawText: String, language: String): Boolean {
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
            val line = localized ?: plain
            // An ENGLISH line that couldn't be put into Twi ("The time is 22:25") is spoken by the English voice — never
            // English read by the Twi voice, which sounded wrong and spent the small daily Twi quota (2 October 2026).
            // (…and a list of names — "Which Prince: Prince Melanin, Prince Alhassan ending 690…" — is English too: no Twi
            // letter or word in it, and English words around the names. The A20e, 3 October 2026, spent a Twi call on it.)
            if (localized == null && com.tekyerema.language.ReplyLocalizer.englishForVoice(plain)) {
                Log.i("Tekyerema", "[TTS] an English line in a Twi conversation → the English voice")
                return speakEnglishPiece(plain)
            }
            // Owner's option B (2 October 2026): amounts and phone numbers inside a Twi line are spoken by the ENGLISH voice
            // ("Wopɛ sɛ mesoma | five Ghana cedis | ma Ama?") — the Twi voice says the Twi words.
            if (com.tekyerema.language.EntityTags.hasEnglishSpans(line)) {
                val spoken = speakStitched(line, language)
                if (spoken != null) return spoken
            }
            // X-06: the Twi voice gets the standard spelling (look-alike ɛ/ɔ mapped, tone marks dropped).
            val twiText = com.tekyerema.language.TwiText.canonical(com.tekyerema.language.EntityTags.display(line))
            if (twi.speak(twiText, language)) { twiStatus.onSpoke(); Log.i("Tekyerema", "[TTS] spoke Twi via ${twi.name}"); return true }
            // Never silent, and never unexplained (audit 2026-10-01, L16): the first time a Twi reply has to be read by
            // the English voice the user is told so, once per session; then the reply itself.
            val why = (twi as? VoiceAvailability)?.whyUnavailable()
            // The Twi voice is there but declined THIS line (too long for one request, say): no "there's no Twi voice on
            // this phone" notice — that was false on the A20e (3 October 2026). The line is still said, in English.
            val declinedOnly = twi is VoiceAvailability && why == null
            val tell = !declinedOnly && twiStatus.onUnavailable(why)
            Log.w("Tekyerema", "[TTS] Twi voice (${twi.name}) unavailable${why?.let { " — $it" } ?: ""} → English system-engine fallback (${english.name}) (§28)")
            if (tell) {
                val told = english.speak(TwiVoiceStatus.NOTICE, Lang.GH_EN)
                Log.i("Tekyerema", "[TTS] Twi-voice notice spoken=$told (once per session; ${twiStatus.status()})")
            }
            val ok = english.speak(text, Lang.GH_EN)   // fallback: the ORIGINAL text, English voice — never silent
            Log.i("Tekyerema", "[TTS] spoke the Twi reply with the English voice (${english.name}) ok=$ok")
            return ok
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
    /**
     * A Twi line with English stretches, spoken piece by piece in order: Twi pieces by the Twi voice, English pieces
     * (amounts, numbers) by the Ghanaian-English voice when online, else the phone's own. Each Twi piece is cached by the
     * Twi voice, so a template's fixed words cost the daily quota once. Null when the FIRST Twi piece can't be spoken
     * (the caller then falls back as for any Twi line); a later failure finishes the line in English — never silent.
     */
    private suspend fun speakStitched(line: String, language: String): Boolean? {
        val pieces = com.tekyerema.language.EntityTags.segments(line)
        var spokeTwi = false
        for ((i, piece) in pieces.withIndex()) {
            val (text, isEnglish) = piece
            val ok = if (isEnglish) speakEnglishPiece(text)
                else twi.speak(com.tekyerema.language.TwiText.canonical(text), language).also { if (it) spokeTwi = true }
            if (!ok && !isEnglish) {
                if (!spokeTwi && i == pieces.indexOfFirst { !it.second }) return null
                val rest = pieces.drop(i).joinToString(" ") { it.first }
                Log.w("Tekyerema", "[TTS] the Twi voice stopped mid-line — finishing it in English")
                return english.speak(rest, Lang.GH_EN)
            }
        }
        if (spokeTwi) twiStatus.onSpoke()
        Log.i("Tekyerema", "[TTS] spoke Twi with English amounts (${pieces.size} pieces: ${pieces.count { it.second }} English)")
        return true
    }

    private suspend fun speakEnglishPiece(text: String): Boolean {
        if (ghEnglish != null && isOnline() && ghEnglish.speak(text, Lang.GH_EN)) return true
        return english.speak(text, Lang.GH_EN)
    }

    override fun stop() { english.stop(); twi.stop(); ghEnglish?.stop() }
    override fun shutdown() { english.shutdown(); twi.shutdown(); ghEnglish?.shutdown() }
}
