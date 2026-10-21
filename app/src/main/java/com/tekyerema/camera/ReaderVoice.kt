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
            val ok = try { VoiceEngine.speakIn(line, VoiceEngine.language()) } catch (c: CancellationException) { throw c } catch (_: Throwable) { false }
            main.post {
                job = null
                val fb = fallback
                // Twi never falls back to the phone's English engine (speech.twi.noEnglishFallback): its own lines say why.
                if (!ok && fb != null && VoiceEngine.language() != com.tekyerema.voice.Lang.TWI) fb(line, then) else then?.invoke()
                if (job == null) next()
            }
        }
    }

    companion object {
        /** The app's voice is up and the readers should use it (`camera.appVoice`, default on). */
        fun use(): Boolean = VoiceEngine.ready() && com.tekyerema.core.config.AppConfig.bool("camera.appVoice", true)
    }
}
