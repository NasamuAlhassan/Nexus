package com.tekyerema.voice

import android.util.Log
import kotlinx.coroutines.async

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
    /** The WHOLE English reply into Twi, long text in pieces ([com.tekyerema.language.ReplyLocalizer.toTwiAll]). */
    private val enToTwAll: (suspend (String) -> String?)? = null,
    /** A Ghanaian-accented English voice, used for English replies when online; the phone's own engine has no
     *  Ghanaian voice. Null ⇒ system engine only. */
    private val ghEnglish: TtsProvider? = null,
    private val isOnline: () -> Boolean = { true },
    /** Tracks whether the Twi voice works, and decides when the user is told it doesn't (once per session). */
    private val twiStatus: TwiVoiceStatus = TwiVoiceStatus.shared,
    /** Voices for further languages by code — "kus" → the Space's Kusaal voice, then the on-device one (owner, 19 October
     *  2026). A line such a voice cannot say falls to the English voice, as Twi's does: never silent. */
    private val others: Map<String, TtsProvider> = emptyMap(),
    /** English → that language for [others] (Kusaal: the Space's translator, with Twi's rules — names and amounts as tags,
     *  amounts said by the English voice). Null answer = the English voice reads the English line. */
    private val toOthers: Map<String, suspend (String) -> String?> = emptyMap(),
    /** The Ghanaian-English network voice (Kafui Dey on our Space, 19 October 2026): on/off, read at speak time so
     *  `set_config speech.ghEnglish.enabled false` turns it off at once. */
    private val ghEnabled: () -> Boolean = { com.tekyerema.core.config.AppConfig.bool("speech.ghEnglish.enabled", true) },
    /** How long a reply waits for the network voice's FIRST sentence before the phone's voice says the line instead; the
     *  next sentences are fetched while the one before plays, each within [ghNextWaitMs]. */
    private val ghFirstWaitMs: () -> Long = { com.tekyerema.core.config.AppConfig.long("speech.ghEnglish.firstWaitMs", 6_000L) },
    private val ghNextWaitMs: () -> Long = { com.tekyerema.core.config.AppConfig.long("speech.ghEnglish.nextWaitMs", 9_000L) },
    private val clock: () -> Long = { System.currentTimeMillis() }
) : TtsProvider {
    override val name = "routing(en=${english.name}; ghEn=${ghEnglish?.name ?: "-"}; twi=${twi.name}" +
        others.entries.joinToString("") { "; ${it.key}=${it.value.name}" } + ")"
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
        others[language]?.let { voice -> return speakOther(voice, text, plain, language) }
        if (language == Lang.TWI) {
            // Localize English response text to Twi so the Twi VOICE speaks Twi WORDS (not English read by
            // a Twi voice). Already-Twi text (e.g. LocalBrain "Mabue no.") is left as-is by enToTw. The language
            // layer's localizer also refuses anything with a quantity or a name it can't keep (ReplyLocalizer).
            // An English reply goes straight to the whole-reply translator (one wait, not the narrow one first and then the
            // whole one: that could be 6 + 7 s of silence — reliability audit, 20 Oct 2026).
            val wholeReply = noEnglish() && enToTwAll != null && com.tekyerema.language.ReplyLocalizer.englishForVoice(plain)
            val localized = if (wholeReply) null else try { enToTw?.invoke(plain)?.takeIf { it.isNotBlank() } }
                catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Exception) { null }
            // ONE voice (owner, 4 October 2026) and no figure the lab voice would skip: amounts, numbers and phone numbers as
            // Twi words, English stretches put into Twi (voice/understand/TwiSpeechText). A line with neither is unchanged.
            val oneVoice = com.tekyerema.core.config.AppConfig.bool("speech.twi.oneVoice", true)
            val line = (localized ?: plain).let { l ->
                if (oneVoice && (localized != null || !com.tekyerema.language.ReplyLocalizer.englishForVoice(plain)))
                    com.tekyerema.voice.understand.TwiSpeechText.prepare(l) else l
            }
            // An ENGLISH line that couldn't be put into Twi ("The time is 22:25") is spoken by the English voice — never
            // English read by the Twi voice, which sounded wrong and spent the small daily Twi quota (2 October 2026).
            // (…and a list of names — "Which Prince: Prince Melanin, Prince Alhassan ending 690…" — is English too: no Twi
            // letter or word in it, and English words around the names. The A20e, 3 October 2026, spent a Twi call on it.)
            if (localized == null && com.tekyerema.language.ReplyLocalizer.englishForVoice(plain)) {
                // Owner, 20 Oct 2026: everything said in Twi is Twi, the fallback too. An English reply (a message read aloud, a
                // web answer) is put into Twi piece by piece; if that fails the Twi voice says so in Twi (the words stay on the
                // screen). `speech.twi.noEnglishFallback` false = the old way (the English voice reads it).
                if (com.tekyerema.core.config.AppConfig.bool("speech.twi.noEnglishFallback", true)) {
                    val all = try { enToTwAll?.invoke(plain)?.takeIf { it.isNotBlank() } }
                        catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Exception) { null }
                    val say = all?.let { com.tekyerema.voice.understand.TwiSpeechText.prepare(it) } ?: com.tekyerema.core.config.AppConfig.string("speech.twi.cannotTranslate", "Mintumi nkyerɛ eyi ase seesei. San bisa bio.")
                    Log.i("Tekyerema", "[TTS] an English line in a Twi conversation → ${if (all != null) "translated, the Twi voice" else "the Twi notice (no translation)"}")
                    val twiText = com.tekyerema.language.TwiText.canonical(com.tekyerema.language.EntityTags.display(say))
                    if (twi.speak(twiText, language)) { twiStatus.onSpoke(); return true }
                    // Never the English words read by the Twi voice after this (that cost a second network wait, then silence).
                    Log.w("Tekyerema", "[TTS] no Twi voice could say the translated line — it stays on the screen")
                    return false
                } else {
                    Log.i("Tekyerema", "[TTS] an English line in a Twi conversation → the English voice")
                    return speakEnglishPiece(plain)
                }
            }
            // Owner's option B (2 October 2026): amounts and phone numbers inside a Twi line are spoken by the ENGLISH voice
            // ("Wopɛ sɛ mesoma | five Ghana cedis | ma Ama?") — the Twi voice says the Twi words.
            if (com.tekyerema.language.EntityTags.hasEnglishSpans(line)) {
                val spoken = speakStitched(line, language)
                if (spoken != null) return spoken
            }
            // X-06: the Twi voice gets the standard spelling (look-alike ɛ/ɔ mapped, tone marks dropped).
            val twiText = com.tekyerema.language.TwiText.canonical(com.tekyerema.language.EntityTags.display(line))
            if (twi.speak(twiText, language)) {
                twiStatus.onSpoke(); Log.i("Tekyerema", "[TTS] spoke Twi via ${(twi as? FallbackTts)?.lastVoice ?: twi.name}"); return true
            }
            // Never silent, and never unexplained (audit 2026-10-01, L16): the first time a Twi reply has to be read by
            // the English voice the user is told so, once per session; then the reply itself.
            val why = (twi as? VoiceAvailability)?.whyUnavailable()
            // The Twi voice is there but declined THIS line (too long for one request, say): no "there's no Twi voice on
            // this phone" notice — that was false on the A20e (3 October 2026). The line is still said, in English.
            val declinedOnly = twi is VoiceAvailability && why == null
            val tell = !declinedOnly && twiStatus.onUnavailable(why)
            Log.w("Tekyerema", "[TTS] Twi voice (${twi.name}) unavailable${why?.let { " — $it" } ?: ""} → English system-engine fallback (${english.name}) (§28)")
            // In Twi, no English (owner, 20 Oct 2026): the line is on the screen; with the phone's own Twi voice in the chain this
            // only happens when every Twi voice failed. `speech.twi.noEnglishFallback` false = the old English read-out.
            if (noEnglish()) { Log.w("Tekyerema", "[TTS] no Twi voice could say this line — shown, not read in English"); return false }
            if (tell) {
                val told = english.speak(TwiVoiceStatus.NOTICE, Lang.GH_EN)
                Log.i("Tekyerema", "[TTS] Twi-voice notice spoken=$told (once per session; ${twiStatus.status()})")
            }
            val ok = english.speak(text, Lang.GH_EN)   // fallback: the ORIGINAL text, English voice — never silent
            Log.i("Tekyerema", "[TTS] spoke the Twi reply with the English voice (${english.name}) ok=$ok")
            return ok
        }
        // English: prefer the Ghanaian-accented network voice when online (Kafui Dey on our Space); the English chain (the own
        // on-device voice, the phone's engine) says the line — or what's left of it — when that voice is off, resting, slow
        // or fails, so English is never silent and never waits long.
        val gh = speakGhEnglish(plain)
        if (gh == GhResult.SPOKE) return true
        if (gh == GhResult.STOPPED) return false
        if (gh is GhResult.Partly) {
            Log.w("Tekyerema", "[TTS] the Gh-English voice stopped mid-reply — ${english.name} finishes it")
            return english.speak(gh.rest, Lang.GH_EN)
        }
        Log.i("Tekyerema", "[TTS] spoke $language via ${english.name}")
        return english.speak(text, language)
    }

    /** What the network English voice did with a line. */
    private sealed class GhResult {
        object SPOKE : GhResult()
        /** Not used at all (off, offline, resting, no first sentence in time): the English chain says the whole line. */
        object NOT_USED : GhResult()
        /** Playback ended early (the user stopped it): nothing more is said. */
        object STOPPED : GhResult()
        /** The first sentences were said; [rest] wasn't fetched in time. */
        class Partly(val rest: String) : GhResult()
    }

    @Volatile private var ghRestUntil = 0L
    @Volatile private var ghSlowTrips = 0

    /**
     * The line in the Ghanaian-English network voice, sentence by sentence: the first sentence is fetched within
     * [ghFirstWaitMs]; each next one is fetched while the one before plays. A first sentence that misses its time rests the
     * voice (1 min, doubling to 10) — a cancelled call is not held against the provider by its own breaker, and a sleeping or
     * broken Space must not cost every reply the wait. Pure routing; the clips come from [PrefetchTts].
     */
    private suspend fun speakGhEnglish(plain: String): GhResult {
        val gh = ghEnglish ?: return GhResult.NOT_USED
        if (!isOnline() || !ghEnabled() || plain.isBlank()) return GhResult.NOT_USED
        // While the network voice "rests" after a slow first sentence, a line the phone ALREADY HOLDS (the app's fixed lines are kept on
        // disk, HttpProviders.TtsDiskCache) is still played: the first sentence gets a quarter of a second, which a cache hit needs
        // and a real fetch cannot meet — and that miss costs no further rest (council review, 19 Oct 2026).
        val resting = clock() < ghRestUntil
        if (resting && gh !is PrefetchTts) return GhResult.NOT_USED
        if (gh !is PrefetchTts) {
            if (gh.speak(plain, Lang.GH_EN)) { Log.i("Tekyerema", "[TTS] spoke Gh-English via ${gh.name}"); return GhResult.SPOKE }
            return GhResult.NOT_USED
        }
        val pieces = sentences(plain)
        if (pieces.isEmpty()) return GhResult.NOT_USED
        if (com.tekyerema.BuildConfig.DEV_HOOKS) pieces.forEach { Log.d("Tekyerema", "[TTS-LINE] eng|$it") }   // the exact keys, for pre-generating fixed lines
        val t0 = clock()
        return kotlinx.coroutines.coroutineScope {
            fun fetchAsync(i: Int, waitMs: Long) = async {
                kotlinx.coroutines.withTimeoutOrNull(waitMs) { gh.fetch(pieces[i], Lang.GH_EN) }
            }
            var next = fetchAsync(0, if (resting) 250L else ghFirstWaitMs())
            for (i in pieces.indices) {
                val clips = next.await()
                if (clips == null) {
                    if (i == 0 && resting) return@coroutineScope GhResult.NOT_USED   // not held by the phone: the English chain speaks, rest unchanged
                    if (i == 0) {
                        ghSlowTrips++
                        ghRestUntil = clock() + (60_000L shl (ghSlowTrips - 1).coerceAtMost(4)).coerceAtMost(10 * 60_000L)
                        Log.w("Tekyerema", "[TTS] Gh-English voice ${gh.name}: no first sentence in ${clock() - t0} ms — " +
                            "the English chain speaks; network voice rests ${(ghRestUntil - clock()) / 1000}s")
                        return@coroutineScope GhResult.NOT_USED
                    }
                    return@coroutineScope GhResult.Partly(pieces.drop(i).joinToString(" "))
                }
                if (i == 0) {
                    ghSlowTrips = 0
                    Log.i("Tekyerema", "[TTS] Gh-English voice ${gh.name}: first sentence ready in ${clock() - t0} ms (${pieces.size} sentence(s))")
                }
                if (i + 1 < pieces.size) next = fetchAsync(i + 1, ghNextWaitMs())
                if (!gh.play(clips)) {
                    next.cancel()
                    Log.i("Tekyerema", "[TTS] Gh-English playback ended early (stopped)")
                    return@coroutineScope GhResult.STOPPED
                }
            }
            Log.i("Tekyerema", "[TTS] spoke Gh-English via ${gh.name} in ${clock() - t0} ms")
            GhResult.SPOKE
        }
    }

    /**
     * A line in [others]' language. A reply is English (the planner, the app's own lines): it is put into the language first
     * ([toOthers]); a line the translator can't be trusted with is said by the English voice — never English read by the
     * Kusaal voice. Text already in the language (no English in it) is said as it is. Amounts and numbers marked for the
     * English voice are said by it, between the language's own pieces.
     */
    private suspend fun speakOther(voice: TtsProvider, text: String, plain: String, language: String): Boolean {
        val englishLine = com.tekyerema.language.ReplyLocalizer.looksEnglish(plain)
        val line: String? = when {
            englishLine -> toOthers[language]?.let { localize ->
                try { localize(plain)?.takeIf { it.isNotBlank() } }
                catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Exception) { null }
            }
            com.tekyerema.language.ReplyLocalizer.englishForVoice(plain) -> null   // a list of names: the English voice
            else -> plain
        }
        if (line == null) {
            Log.i("Tekyerema", "[TTS] an English line in a ${Lang.label(language)} conversation → the English voice")
            return speakEnglishPiece(plain, network = true)
        }
        if (com.tekyerema.language.EntityTags.hasEnglishSpans(line)) {
            val pieces = com.tekyerema.language.EntityTags.segments(line)
            for ((i, piece) in pieces.withIndex()) {
                val (words, isEnglish) = piece
                val ok = if (isEnglish) speakEnglishPiece(words, network = true) else voice.speak(words, language)
                if (!ok && !isEnglish) {
                    Log.w("Tekyerema", "[TTS] the ${Lang.label(language)} voice stopped mid-line — finishing it in English")
                    return if (i == 0) speakEnglishPiece(plain, network = true)
                        else english.speak(pieces.drop(i).joinToString(" ") { it.first }, Lang.GH_EN)
                }
            }
            Log.i("Tekyerema", "[TTS] spoke $language with English amounts (${pieces.size} pieces) via ${voiceName(voice)}")
            return true
        }
        if (voice.speak(com.tekyerema.language.EntityTags.display(line), language)) {
            Log.i("Tekyerema", "[TTS] spoke $language via ${voiceName(voice)}"); return true
        }
        Log.w("Tekyerema", "[TTS] $language voice ${voice.name} could not speak; the English voice reads the line")
        return speakEnglishPiece(plain, network = true)
    }

    private fun voiceName(v: TtsProvider) = (v as? FallbackTts)?.lastVoice ?: v.name

    /** A line or a piece of one for the English voice: the network English voice first when [network] (or the Twi-line
     *  setting) allows, then the English chain. */
    private suspend fun speakEnglishPiece(text: String, network: Boolean): Boolean {
        if (network) when (val gh = speakGhEnglish(SpokenText.plain(text))) {
            GhResult.SPOKE -> return true
            GhResult.STOPPED -> return false
            is GhResult.Partly -> return english.speak(gh.rest, Lang.GH_EN)
            GhResult.NOT_USED -> {}
        }
        return english.speak(text, Lang.GH_EN)
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
            // Owner, 20 Oct 2026: in Twi everything is Twi — an English piece (an amount, a name) is put into Twi words and said
            // by the Twi voice too (`speech.twi.noEnglishFallback` false = the old English pieces).
            val ok = if (isEnglish && !noEnglish()) speakEnglishPiece(text)
                else twi.speak(com.tekyerema.language.TwiText.canonical(
                    if (isEnglish) com.tekyerema.voice.understand.TwiSpeechText.prepare(text) else text), language).also { if (it) spokeTwi = true }
            if (!ok && !isEnglish) {
                if (!spokeTwi && i == pieces.indexOfFirst { !it.second }) return null
                val rest = pieces.drop(i).joinToString(" ") { it.first }
                if (noEnglish()) { Log.w("Tekyerema", "[TTS] the Twi voice stopped mid-line — not finished in English"); return false }
                Log.w("Tekyerema", "[TTS] the Twi voice stopped mid-line — finishing it in English")
                return english.speak(rest, Lang.GH_EN)
            }
        }
        if (spokeTwi) twiStatus.onSpoke()
        Log.i("Tekyerema", "[TTS] spoke Twi with English amounts (${pieces.size} pieces: ${pieces.count { it.second }} English)")
        return true
    }

    /** An English piece of a TWI line (an amount, or a line the translator couldn't take). Twi's demo behaviour is kept: the
     *  English chain, unless `speech.ghEnglish.inTwiLines` lets the network English voice say these pieces too. */
    private fun noEnglish() = com.tekyerema.core.config.AppConfig.bool("speech.twi.noEnglishFallback", true)

    private suspend fun speakEnglishPiece(text: String): Boolean =
        speakEnglishPiece(text, network = com.tekyerema.core.config.AppConfig.bool("speech.ghEnglish.inTwiLines", false))

    companion object {
        private val SENTENCE_END = Regex("(?<=[.!?])\\s+")

        /** A line as the network voice fetches it: sentence by sentence — a short one (< 40 chars) joined to the next, so
         *  "Okay." isn't a request of its own — each at most 180 chars. Pure. */
        internal fun sentences(text: String): List<String> {
            val out = ArrayList<String>()
            var carry = ""
            for (s in SENTENCE_END.split(text.trim()).map { it.trim() }.filter { it.isNotEmpty() }) {
                val joined = if (carry.isEmpty()) s else "$carry $s"
                if (joined.length < 40) { carry = joined; continue }
                out += SpeechChunks.split(joined, 180); carry = ""
            }
            if (carry.isNotEmpty()) { if (out.isNotEmpty() && out.last().length + carry.length < 180) out[out.size - 1] = out.last() + " " + carry else out += carry }
            return out
        }
    }

    override fun stop() { english.stop(); twi.stop(); ghEnglish?.stop(); others.values.forEach { it.stop() } }
    override fun shutdown() { english.shutdown(); twi.shutdown(); ghEnglish?.shutdown(); others.values.forEach { it.shutdown() } }
}
