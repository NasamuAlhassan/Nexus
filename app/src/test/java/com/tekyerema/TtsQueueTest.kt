package com.tekyerema

import com.tekyerema.voice.Utterances
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-06 / B-15 — one TextToSpeech instance, several callers. Each speak() used to install its own progress listener, so
 * the caller it replaced (a progress line talked over by a confirmation question) never heard that its line was
 * flushed and stayed suspended for good; its later cancellation then stopped whatever was speaking by then.
 */
class TtsQueueTest {
    private fun call(vararg ids: String, ends: MutableList<Pair<String, Boolean>>, name: String) =
        Utterances.Call(ids.toList()) { ok -> ends += name to ok }

    @Test fun a_flushed_caller_is_released_not_left_hanging() {
        val ends = mutableListOf<Pair<String, Boolean>>()
        val u = Utterances()
        val progress = call("p0", ends = ends, name = "progress")
        val question = call("q0", "q1", ends = ends, name = "question")
        u.begin(progress)
        u.begin(question)                               // QUEUE_FLUSH drops the progress line
        assertEquals(listOf("progress" to false), ends)
        assertTrue(u.isCurrent(question))

        u.listener.onStop("p0", false)                  // the engine's late onStop for the flushed line: already handled
        u.listener.onDone("q0")                         // first chunk of two: not finished yet
        assertEquals(1, ends.size)
        u.listener.onDone("q1")
        assertEquals(listOf("progress" to false, "question" to true), ends)
        u.listener.onStop("q1", true)                   // anything after the end is ignored — resumed exactly once
        assertEquals(2, ends.size)
    }

    @Test fun a_stop_ends_only_the_caller_that_owns_the_line() {
        val ends = mutableListOf<Pair<String, Boolean>>()
        val u = Utterances()
        val a = call("a0", ends = ends, name = "a")
        u.begin(a)
        u.listener.onStop("zz", true)                   // somebody else's id
        assertTrue(ends.isEmpty())
        u.listener.onError("a0", -1)
        assertEquals(listOf("a" to false), ends)
        u.finish(a, true)                               // cancellation after the end: no second resume
        assertEquals(1, ends.size)
    }

    @Test fun first_audio_is_reported_for_the_first_chunk_only() {
        var firsts = 0
        val u = Utterances()
        u.begin(Utterances.Call(listOf("x0", "x1"), onFirstAudio = { firsts++ }) {})
        u.listener.onStart("x0"); u.listener.onStart("x1")
        assertEquals(1, firsts)
    }
}
