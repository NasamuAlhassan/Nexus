package com.tekyerema.camera

import android.os.Handler
import com.tekyerema.voice.Lang
import com.tekyerema.voice.VoiceEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The camera and document readers' lines in a Twi conversation (owner, 20 Oct 2026: "sometimes in Twi, English still speaks"):
 * said by the app's own reply voice in Twi — what was read is put into Twi first ([com.tekyerema.voice.RoutingTts]) — instead of
 * the phone's English engine the readers use otherwise. Lines play in order; a flushing line drops what is queued and stops the
 * one being said, as the readers' own engine does. Main thread only.
 */
class TwiReaderVoice(private val scope: CoroutineScope, private val main: Handler) {
    private var job: Job? = null
    private val queue = ArrayDeque<Pair<String, (() -> Unit)?>>()

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
            try { VoiceEngine.speakIn(line, Lang.TWI) } catch (c: CancellationException) { throw c } catch (_: Throwable) {}
            main.post { job = null; then?.invoke(); if (job == null) next() }
        }
    }
}
