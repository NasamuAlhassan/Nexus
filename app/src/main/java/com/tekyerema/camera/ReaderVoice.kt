package com.tekyerema.camera

import android.os.Handler
import com.tekyerema.voice.VoiceEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The camera and document readers' lines, said by the app's own reply voice in the language the user set — the Ghanaian
 * English voice (Kafui) in English, the Twi voice in Twi (what was read is put into Twi first, [com.tekyerema.voice.RoutingTts]).
 * Owner, 20 Oct 2026: "sometimes in Twi, English still speaks"; 21 Oct: "the camera … the voice is not our Ghanaian accent —
 * everything that comes of Tɛkyerɛma in the voice the user set". Lines play in order; a flushing line drops what is queued and
 * stops the one being said, as the readers' own engine does. A line the app voice could not say goes to [fallback] (the
 * phone's engine) — never silence. Main thread only.
 */
class ReaderVoice(
    private val scope: CoroutineScope,
    private val main: Handler,
    private val fallback: ((String, (() -> Unit)?) -> Unit)? = null,
) {
    private var job: Job? = null
    private val queue = ArrayDeque<Pair<String, (() -> Unit)?>>()

    /** A line is being said (or waits): the readers hold their hints and guidance while it is. */
    val speaking: Boolean get() = job != null || queue.isNotEmpty()

    fun say(line: String, flush: Boolean, then: (() -> Unit)?) {
        if (flush) stop()
        queue.addLast(line to then)
        if (job == null) next()
    }

    fun stop() {
        queue.clear()
        job?.cancel(); job = null
    }

    private fun next() {
        val (line, then) = queue.removeFirstOrNull() ?: run { job = null; return }
        job = scope.launch {
            val askedAt = VoiceEngine.lastRequestAt
            val ok = try { VoiceEngine.speakIn(line, VoiceEngine.language()) } catch (c: CancellationException) { throw c } catch (_: Throwable) { false }
            val me = coroutineContext[Job]
            main.post {
                // Stopped or flushed while this waited for the main thread: the old line's follow-up and fallback are void, and the newer
                // line's job must not be forgotten (it could then be neither stopped nor waited for) — audit, 22 Oct 2026.
                if (job !== me) return@post
                job = null
                val fb = fallback
                // Twi never falls back to the phone's English engine (speech.twi.noEnglishFallback): its own lines say why.
                // A line cut short by a new request is over: "false" from the reply voice also means "stopped" (RoutingTts), and the
                // phone's engine read it again from the start over the new request (audit, 22 Oct 2026).
                if (!ok && fb != null && VoiceEngine.language() != com.tekyerema.voice.Lang.TWI && VoiceEngine.lastRequestAt == askedAt) fb(line, then) else then?.invoke()
                if (job == null) next()
            }
        }
    }

    companion object {
        /** The app's voice is up and the readers should use it (`camera.appVoice`, default on). */
        fun use(): Boolean = VoiceEngine.ready() && com.tekyerema.core.config.AppConfig.bool("camera.appVoice", true)

        /**
         * Words printed on a page or screen, in English, to be read in a Twi conversation with no internet: the translator cannot
         * be reached, and the Twi voice would only say "I can't put this into Twi" and nothing of the page. The page's own English
         * is read by the phone's English voice instead (`camera.twi.readEnglishOffline`, default on) — the words, not a reply.
         * [owner to decide: reverses "no English in a Twi conversation" for printed text only, and only offline]
         */
        fun englishOffline(line: String): Boolean = try {
            com.tekyerema.core.config.AppConfig.bool("camera.twi.readEnglishOffline", true) &&
                VoiceEngine.language() == com.tekyerema.voice.Lang.TWI &&
                !com.tekyerema.core.NetworkMonitor.isOnline() &&
                com.tekyerema.language.ReplyLocalizer.englishForVoice(line)
        } catch (_: Throwable) { false }
    }
}
