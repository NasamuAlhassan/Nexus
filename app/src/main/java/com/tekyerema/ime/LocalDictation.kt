package com.tekyerema.ime

import android.content.Context
import com.tekyerema.core.TranslateGuard
import com.tekyerema.language.SpaceVoice
import com.tekyerema.voice.AsrProvider
import com.tekyerema.voice.Dictation
import com.tekyerema.voice.Transcript
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * One dictation in a local language (contract docs/agent/KEYBOARD_LANGUAGES.md §3), start to finish:
 *  1. the same microphone and pause detection as English dictation (voice/Dictation.run), but the speech goes to the Space's
 *     `/web/asr` in the chosen language;
 *  2. "Type my words in" ([SendMode]): as heard, in English (`/web/translate` with names and numbers kept by
 *     [TranslateGuard] — a slot that doesn't survive means the user's own words are typed instead, never a guess), or asked;
 *  3. the read-back: the exact text that will be typed, in its own language (`/web/tts`, or the phone's English voice) — and
 *     only then is it typed.
 * A 429 or a Space that doesn't answer before anything was heard: one short English line, then English dictation ([Host.fallBack]).
 * Nothing heard, translated or typed is logged (ime/ no-content rule, TwiKeyboardTest); the network is language/SpaceVoice.
 */
class LocalDictation(private val ctx: Context, private val host: Host) {

    /** The keyboard's side. Every call arrives on the main thread. */
    interface Host {
        fun listening(lang: LocalLang)
        fun working(message: String)
        /** Show the two choices ("Twi" / "English") and call [pick] with the one tapped (null: dismissed). */
        fun askMode(lang: LocalLang, pick: (SendMode?) -> Unit)
        /** Put [text] into the field (spacing and capitals as English dictation does). */
        fun type(text: String)
        /** An honest line on the strip and to TalkBack. */
        fun tell(message: String)
        /** The Space can't serve this turn: say [line], then dictate in English. */
        fun fallBack(line: String)
        /** Always last: the mic key back to idle. */
        fun done()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    val voice = ReadBackVoice(ctx)
    /** The "In Twi or in English?" question in each language, translated once per run of the keyboard. */
    private val askLines = HashMap<String, String>()

    val active: Boolean get() = job?.isActive == true

    fun start(lang: LocalLang, mode: SendMode, space: SpaceVoice) {
        if (active) return
        job = scope.launch {
            try { run(lang, mode, space) } finally { host.done() }
        }
    }

    fun cancel() { job?.cancel(); voice.stop() }

    fun release() { cancel(); voice.release() }

    private suspend fun run(lang: LocalLang, mode: SendMode, space: SpaceVoice) {
        // ---- 1. hear
        var refused: SpaceVoice.Reply<String>? = null
        val asr = object : AsrProvider {
            override val name = "space-keyboard"
            override suspend fun transcribe(wav: ByteArray, sessionLanguage: String, forceLanguage: String?): Transcript {
                withContext(Dispatchers.Main) { host.working("Listening done. Writing…") }
                return when (val r = space.asr(wav, lang)) {
                    is SpaceVoice.Reply.Ok -> Transcript(r.value, r.value, lang.code, backend = "space")
                    else -> { refused = r; throw java.io.IOException("") }
                }
            }
        }
        val outcome = withContext(Dispatchers.Default) {
            Dictation.run(
                twi = false,
                engineRunning = try { com.tekyerema.voice.VoiceEngine.isRunning } catch (_: Throwable) { false },
                secure = com.tekyerema.voice.SecureStep.active() || com.tekyerema.core.Privacy.secureStep,
                mic = try { com.tekyerema.voice.VoiceEngine.dictationMic } catch (_: Throwable) { null },
                asr = asr,
                handOver = { com.tekyerema.voice.WakeWordService.handOver() },
                reclaim = { com.tekyerema.voice.WakeWordService.reclaim() },
                onListening = { android.os.Handler(android.os.Looper.getMainLooper()).post { host.listening(lang) } }
            )
        }
        when (refused) {
            SpaceVoice.Reply.Limited -> { host.fallBack(LocalVoiceLines.LIMITED); return }
            is SpaceVoice.Reply.Failed -> { host.fallBack(LocalVoiceLines.UNAVAILABLE); return }
            else -> {}
        }
        val heard = when (outcome) {
            is Dictation.Outcome.Text -> outcome.text
            is Dictation.Outcome.None -> {
                // Our microphone isn't ready (no voice stack yet): the phone's own English route.
                if (outcome.fallbackToSystem) host.fallBack(LocalVoiceLines.UNAVAILABLE) else host.tell(outcome.message)
                return
            }
            Dictation.Outcome.Cancelled -> return
        }

        // ---- 2. which words
        val chosen = if (mode == SendMode.ASK) ask(lang, space) ?: return else mode
        if (chosen == SendMode.MINE) { readBackLocal(heard, lang, space); return }
        host.working("Translating…")
        val g = TranslateGuard.protect(heard, contactNames())
        when (val r = space.translate(g.text, lang.code, "eng")) {
            is SpaceVoice.Reply.Ok -> {
                val english = TranslateGuard.restore(r.value, g)
                if (english == null) {
                    host.tell(LocalVoiceLines.NOT_SAFE)
                    readBackLocal(heard, lang, space)
                } else readBackEnglish(english)
            }
            else -> {
                host.tell("Translation isn't available right now, so I typed your own words.")
                readBackLocal(heard, lang, space)
            }
        }
    }

    /** "In Twi or in English?" — asked in the user's language when the Space can say it, and answered with the two keys. */
    private suspend fun ask(lang: LocalLang, space: SpaceVoice): SendMode? {
        sayAsk(lang, space)
        val pick = suspendCancellableCoroutine<SendMode?> { c -> host.askMode(lang) { if (c.isActive) c.resume(it) } }
        voice.stop()
        return pick
    }

    /** Speak the question while the two keys wait (never blocks the keys). */
    private fun sayAsk(lang: LocalLang, space: SpaceVoice) {
        scope.launch {
            val line = askLines[lang.code] ?: when (val r = space.translate(LocalVoiceLines.ask(lang), "eng", lang.code)) {
                is SpaceVoice.Reply.Ok -> r.value.also { askLines[lang.code] = it }
                else -> null
            }
            if (line == null) { voice.speakEnglish(LocalVoiceLines.ask(lang)) {}; return@launch }
            when (val w = space.tts(ReadBack.forVoice(line, lang), lang)) {
                is SpaceVoice.Reply.Ok -> voice.playWav(w.value) {}
                else -> voice.speakEnglish(LocalVoiceLines.ask(lang)) {}
            }
        }
    }

    private suspend fun readBackLocal(text: String, lang: LocalLang, space: SpaceVoice) {
        if (ReadBack.tooLong(text)) { host.tell(LocalVoiceLines.TOO_LONG); return }
        host.working("Reading it back…")
        when (val w = space.tts(ReadBack.forVoice(text, lang), lang)) {
            is SpaceVoice.Reply.Ok -> awaitThen({ voice.playWav(w.value, it) }) { host.type(text) }
            // The local voice isn't answering: type it, and TalkBack reads what went in (the keyboard's usual announcement).
            else -> host.type(text)
        }
    }

    private suspend fun readBackEnglish(text: String) {
        host.working("Reading it back…")
        awaitThen({ voice.speakEnglish(text, it) }) { host.type(text) }
    }

    private suspend fun awaitThen(speak: (() -> Unit) -> Unit, then: () -> Unit) {
        suspendCancellableCoroutine<Unit> { c -> speak { if (c.isActive) c.resume(Unit) } }
        then()
    }

    /** The contact names [TranslateGuard] keeps out of translation; none without the Contacts permission. Never logged. */
    private suspend fun contactNames(): List<String> = withContext(Dispatchers.IO) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_CONTACTS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return@withContext emptyList()
        try {
            ctx.contentResolver.query(android.provider.ContactsContract.Contacts.CONTENT_URI,
                arrayOf(android.provider.ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, null)?.use { cur ->
                val out = ArrayList<String>()
                while (cur.moveToNext() && out.size < 3000) cur.getString(0)?.trim()?.takeIf { it.length >= 2 }?.let { out += it }
                out
            } ?: emptyList()
        } catch (_: Throwable) { emptyList() }
    }
}
