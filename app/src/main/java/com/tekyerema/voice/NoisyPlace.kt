package com.tekyerema.voice

import com.tekyerema.core.config.AppConfig

/**
 * Noisy-place profile (MASTER_STATUS #40, 2 Nov 2026): a trotro, a market, an office — privacy and audibility at once.
 * When the background stays loud, Nexus switches to: private reading on (who and how many, the words on "read them";
 * notifications/PrivateReading), answers in their short form (core/Taught), and its own voice one volume step up; when it
 * has been quiet a while it switches back. Once per switch it says so: "It's noisy here; I'll keep it short and private."
 *
 * The level is the wake-word loop's own frame loudness (WakeWordService: RMS of each 80 ms frame, the figure its
 * `speech.wake.minRms` gate uses), so it costs nothing extra and runs only while "Hey Tɛkyerɛma" listens. Speech and
 * bangs are loud for a moment; a noisy PLACE is loud all the time — so each second's mean goes into a window and the
 * background is the window's low quantile ([backgroundOf]), not its average.
 *
 * Config (`speech.noisy.*`): enabled (true), enterLevel 0.035 and exitLevel 0.015 (RMS, full scale = 1; first guesses
 * pending a measurement in real places — tune on the phone), enterSec 30, exitSec 90, windowSec 20, quantile 0.2.
 * The detector is pure (NoisyPlaceTest in ScreenSummaryTest's file); [Effects] touches Android.
 */
object NoisyPlace {
    @Volatile var active: Boolean = false
        private set

    fun enabled(): Boolean = AppConfig.bool("speech.noisy.enabled", true)

    /** The detector: feed per-frame loudness; it says when to switch. One instance per listening loop. */
    class Detector(
        private val enterLevel: Double = AppConfig.double("speech.noisy.enterLevel", 0.035),
        private val exitLevel: Double = AppConfig.double("speech.noisy.exitLevel", 0.015),
        private val enterMs: Long = AppConfig.long("speech.noisy.enterSec", 30L) * 1000,
        private val exitMs: Long = AppConfig.long("speech.noisy.exitSec", 90L) * 1000,
        private val windowSec: Int = AppConfig.int("speech.noisy.windowSec", 20),
        private val quantile: Double = AppConfig.double("speech.noisy.quantile", 0.2)
    ) {
        private val seconds = ArrayDeque<Double>()
        private var secStart = -1L
        private var secSum = 0.0
        private var secN = 0
        private var loudSince = -1L
        private var quietSince = -1L
        var noisy = false
            private set

        /** One frame's RMS at [nowMs]. Returns true (now noisy) / false (now quiet) when the state switches, else null. */
        fun feed(rms: Float, nowMs: Long): Boolean? {
            if (secStart < 0) secStart = nowMs
            secSum += rms; secN++
            if (nowMs - secStart < 1000) return null
            seconds.addLast(secSum / secN.coerceAtLeast(1)); secSum = 0.0; secN = 0; secStart = nowMs
            while (seconds.size > windowSec) seconds.removeFirst()
            if (seconds.size < windowSec / 2) return null
            val bg = backgroundOf(seconds, quantile)
            if (!noisy) {
                loudSince = if (bg >= enterLevel) (if (loudSince < 0) nowMs else loudSince) else -1L
                if (loudSince >= 0 && nowMs - loudSince >= enterMs) { noisy = true; quietSince = -1L; return true }
            } else {
                quietSince = if (bg <= exitLevel) (if (quietSince < 0) nowMs else quietSince) else -1L
                if (quietSince >= 0 && nowMs - quietSince >= exitMs) { noisy = false; loudSince = -1L; return false }
            }
            return null
        }
    }

    /** The background level: the [q] quantile of the per-second means (speech and bangs sit above it). Pure. */
    fun backgroundOf(seconds: Collection<Double>, q: Double): Double {
        if (seconds.isEmpty()) return 0.0
        val s = seconds.sorted()
        return s[((s.size - 1) * q.coerceIn(0.0, 1.0)).toInt()]
    }

    const val NOTE_ON = "It's noisy here; I'll keep it short and private."
    const val NOTE_OFF = "It's quieter now; back to normal."

    /** Apply a switch from the detector (WakeWordService calls this; Android side). */
    fun switchTo(noisy: Boolean, app: android.content.Context) {
        if (!enabled() || noisy == active) return
        active = noisy
        try { android.util.Log.i("Tekyerema", "[NOISY] ${if (noisy) "on" else "off"}") } catch (_: Throwable) {}
        Effects.volume(app, up = noisy)
        if (noisy) VoiceEngine.announce(NOTE_ON) else if (AppConfig.bool("speech.noisy.sayOff", false)) VoiceEngine.announce(NOTE_OFF)
    }

    /** The volume step: one up when it gets noisy, one back down when it is quiet again — only if nobody moved it since. */
    private object Effects {
        private var raisedTo = -1

        fun volume(app: android.content.Context, up: Boolean) {
            val am = app.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
            // The assistant voice plays on the media volume group on most phones (voice/AudioFocus: USAGE_ASSISTANT).
            val stream = android.media.AudioManager.STREAM_MUSIC
            try {
                if (up) {
                    am.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_RAISE, 0)
                    raisedTo = am.getStreamVolume(stream)
                } else {
                    if (raisedTo >= 0 && am.getStreamVolume(stream) == raisedTo) am.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_LOWER, 0)
                    raisedTo = -1
                }
            } catch (_: Throwable) {}
        }
    }
}
