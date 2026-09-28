package com.tekyerema.voice

import android.content.Context
import android.util.Log
import com.tekyerema.core.config.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Instant "thinking" backchannel (conversational fillers). While the brain works, a blind user hears NOTHING — the
 * visual thinking animation is invisible to them, so silence reads as "it didn't hear me". This says a short phrase
 * ("One moment please.") the moment a task starts, randomized so it never gets repetitive, and at most one more on a
 * long wait.
 *
 * It speaks in the SAME voice as the replies — the phone's own engine, offline — through its OWN TTS instance, so
 * stopping a filler can never cut off a reply or a confirmation prompt. (The old pre-recorded Abena-voiced clips were
 * removed on 2026-09-28 with the rest of the Abena integration.) Phrases come from config `ux.fillers.en`; a Twi session
 * plays the soft progress tone instead unless verified Twi phrases are configured in `ux.fillers.twi` (the phone's
 * English voice reads Twi badly). `ux.fillers.enabled` switches fillers off.
 *
 * SAFETY: every filler means "I'm working on it", NEVER "done" — a blind user must never mistake a filler for a
 * completed action (a money transfer, a sent message). It is stopped the instant a real reply or a confirmation
 * prompt is spoken.
 */
class FillerPlayer(
    appContext: Context,
    private val scope: CoroutineScope,
    /** The non-speech progress tone, for sessions without spoken filler phrases. */
    private val cue: () -> Unit = {},
    /** Speech rate and pitch, matched to the reply voice. */
    private val voice: () -> Pair<Float, Float> = { 1.0f to 1.0f }
) {
    private val tts by lazy { AndroidTts(appContext) }
    private var job: Job? = null
    @Volatile private var active = false
    private var last: String? = null

    // Owner (2026-09-28): "the fillers are not required always" — a quick answer doesn't need one. So a filler is only
    // spoken when the wait is long enough to feel like silence ([delayMs] with no reply yet), and only one: longer
    // waits are covered by the progress check-ins (K-02).
    private fun delayMs() = AppConfig.long("ux.fillers.delayMs", 2500L)
    private fun maxChain() = AppConfig.int("ux.fillers.maxChain", 1)
    private fun gapMs() = AppConfig.long("ux.fillers.gapMs", 3500L)

    /** The phrases for [language], or empty when that language has none (⇒ the progress tone). */
    fun phrases(language: String): List<String> = if (language == Lang.TWI)
        AppConfig.stringList("ux.fillers.twi", emptyList())
    else AppConfig.stringList("ux.fillers.en", DEFAULT_EN)

    /** Begin the backchannel for [language]. A fresh call restarts cleanly (used at the start of every turn). */
    fun start(language: String) {
        stop()
        if (!AppConfig.bool("ux.fillers.enabled", true)) return
        active = true
        job = scope.launch {
            delay(delayMs())   // an answer that arrives first cancels this job: no filler at all
            for (i in 0 until maxChain()) {
                if (!active) return@launch
                val pool = phrases(language)
                if (pool.isEmpty()) { cue(); Log.i("Tekyerema", "[FILLER] tone #${i + 1} lang=$language") }
                else {
                    val line = pool.filter { it != last }.ifEmpty { pool }.random()
                    last = line
                    val (rate, pitch) = voice()
                    tts.setRate(rate); tts.setPitch(pitch)
                    Log.i("Tekyerema", "[FILLER] say #${i + 1} lang=$language")
                    try { tts.speak(line, Lang.GH_EN) } catch (_: Throwable) {}
                }
                if (i < maxChain() - 1) delay(gapMs())
            }
        }
    }

    /** QA: say every phrase of [language] in order (`--es play_fillers twi|en`). */
    fun audition(language: String) {
        stop()
        active = true
        job = scope.launch {
            val pool = phrases(language)
            if (pool.isEmpty()) { cue(); Log.i("Tekyerema", "[FILLER] audition lang=$language: no phrases — progress tone"); return@launch }
            for ((i, line) in pool.withIndex()) {
                if (!active) return@launch
                Log.i("Tekyerema", "[FILLER] audition ${i + 1}/${pool.size} lang=$language")
                try { tts.speak(line, Lang.GH_EN) } catch (_: Throwable) {}
                delay(450)
            }
        }
    }

    /** Stop immediately — the moment the real reply / confirmation is about to be spoken, and on cancel. Only this
     *  player's own TTS instance is stopped. */
    fun stop() {
        active = false
        job?.cancel(); job = null
        try { tts.stop() } catch (_: Throwable) {}
    }

    fun release() { stop(); try { tts.shutdown() } catch (_: Throwable) {} }

    companion object {
        val DEFAULT_EN = listOf(
            "One moment please.", "Let me check that for you.", "Alright, let me take a look.", "Okay, I'm on it.",
            "Just a moment please.", "Let me find that for you.", "Hold on a moment.", "Sure, working on that now."
        )
    }
}
