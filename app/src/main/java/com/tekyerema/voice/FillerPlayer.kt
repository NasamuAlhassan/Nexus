package com.tekyerema.voice

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tekyerema.R

/**
 * Instant, offline "thinking" backchannel (conversational fillers). While the ASR + brain + TTS round-trips
 * run, a blind user hears NOTHING — the visual thinking animation is invisible to them, so silence reads as
 * "it didn't hear me". This plays a short, pre-synthesized filler ("Hmm.", "One moment.", "Ma me nhwɛ.") from
 * res/raw the instant a turn starts, randomized so it never gets repetitive, and keeps a gentle backchannel
 * going (bounded) until the real reply is ready — the same trick Siri/Alexa use to mask API latency.
 *
 * Local playback only (bundled WAVs), never the network, so it lands in milliseconds and works even if
 * Abena/Khaya are down or out of credits. The clips are pre-synthesized ONCE with the Abena voice.
 *
 * SAFETY: every filler means "I'm working on it", NEVER "done" — a blind user must never mistake a filler for
 * a completed action (a money transfer, a sent message). It is stopped the instant a real reply or a
 * confirmation prompt is spoken. [owner: verify the Twi filler phrasings with a native Asante Twi speaker.]
 */
class FillerPlayer(private val appContext: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val twi = intArrayOf(
        R.raw.filler_twi_1, R.raw.filler_twi_2, R.raw.filler_twi_3, R.raw.filler_twi_4,
        R.raw.filler_twi_5, R.raw.filler_twi_6, R.raw.filler_twi_7, R.raw.filler_twi_8
    )
    private val en = intArrayOf(
        R.raw.filler_en_1, R.raw.filler_en_2, R.raw.filler_en_3, R.raw.filler_en_4,
        R.raw.filler_en_5, R.raw.filler_en_6, R.raw.filler_en_7, R.raw.filler_en_8
    )

    @Volatile private var active = false
    private var mp: MediaPlayer? = null
    private var lastRes = 0
    private var played = 0
    // Owner feedback: the backchannel was "too much" — 6 clips at 300ms apart is ~14s of near-constant
    // chatter. Keep it to a brief reassurance: ONE filler up front, at most ONE more on a long wait, spaced
    // well apart so silence (with the visual thinking animation) carries the rest.
    private val maxChain = 2        // at most two "thinking" fillers per turn
    private val gapMs = 3500L       // a generous pause between them, so it feels occasional, not chatty

    /** Begin the backchannel for [language]. A fresh call restarts cleanly (used at the start of every turn). */
    fun start(language: String) {
        main.post {
            stopInternal()
            active = true; played = 0
            playNext(language)
        }
    }

    /** QA: play EVERY clip of [language] in order (ignores the random/maxChain logic), so the owner can audition
     *  the set and flag any that sound wrong. Triggered over ADB (`--es play_fillers twi|en`). */
    fun audition(language: String) {
        main.post {
            stopInternal(); active = true
            val list = if (language == Lang.TWI) twi else en
            auditionAt(list, 0, language)
        }
    }

    private fun auditionAt(list: IntArray, idx: Int, language: String) {
        if (!active || idx >= list.size) { active = false; return }
        try {
            val m = MediaPlayer.create(appContext, list[idx])
            if (m == null) { auditionAt(list, idx + 1, language); return }
            m.setOnCompletionListener { p ->
                try { p.release() } catch (_: Throwable) {}; if (mp === p) mp = null
                if (active) main.postDelayed({ auditionAt(list, idx + 1, language) }, 450)
            }
            m.setOnErrorListener { p, _, _ ->
                try { p.release() } catch (_: Throwable) {}; if (mp === p) mp = null
                if (active) main.postDelayed({ auditionAt(list, idx + 1, language) }, 450); true
            }
            mp = m; m.start()
            Log.i("Tekyerema", "[FILLER] audition ${idx + 1}/${list.size} lang=$language dur=${m.duration}ms")
        } catch (t: Throwable) { Log.w("Tekyerema", "[FILLER] audition failed: ${t.message}") }
    }

    /** Stop immediately — called the moment the real reply / confirmation is about to be spoken, and on cancel.
     *  `active` is cleared SYNCHRONOUSLY so no further clip can chain in the window before the reply speaks. */
    fun stop() {
        active = false
        main.post { stopInternal() }
    }

    fun release() {
        active = false
        main.post { stopInternal() }
    }

    private fun playNext(language: String) {
        if (!active || played >= maxChain) return
        val pool = if (language == Lang.TWI) twi else en
        // random, but never the same clip twice in a row (the "don't be boring" requirement)
        val choices = pool.filter { it != lastRes }.ifEmpty { pool.toList() }
        val res = choices.random()
        lastRes = res; played++
        try {
            val m = MediaPlayer.create(appContext, res) ?: return
            m.setOnCompletionListener { p ->
                try { p.release() } catch (_: Throwable) {}
                if (mp === p) mp = null
                if (active) main.postDelayed({ playNext(language) }, gapMs)
            }
            m.setOnErrorListener { p, _, _ -> try { p.release() } catch (_: Throwable) {}; if (mp === p) mp = null; true }
            mp = m
            m.start()
            Log.i("Tekyerema", "[FILLER] play #$played lang=$language dur=${m.duration}ms")
        } catch (t: Throwable) {
            Log.w("Tekyerema", "[FILLER] play failed: ${t.message}")
        }
    }

    private fun stopInternal() {
        main.removeCallbacksAndMessages(null)
        mp?.let { try { it.stop() } catch (_: Throwable) {}; try { it.release() } catch (_: Throwable) {} }
        mp = null
    }
}
