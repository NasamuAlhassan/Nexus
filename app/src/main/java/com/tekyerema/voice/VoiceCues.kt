package com.tekyerema.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.PI
import kotlin.math.sin

/**
 * Non-visual state cues: the interface communicates state through motion AND sound, so a blind
 * user hears every transition. [listening] fires the moment the microphone is open (the capture
 * keeps a VAD guard window under the tone), so the user can speak as soon as they hear it.
 * The default no-op keeps the controller unit-testable.
 */
interface VoiceCues {
    fun listening() {}
    fun captured() {}
    /** K-03: only for a VERIFIED completion. */
    fun done() {}
    /** K-03: the agent finished but couldn't confirm the outcome — never the "done" sound. */
    fun unverified() {}
    fun needsOk() {}
    fun error() {}
    /** K-02: a soft "still working" tick during a long task. */
    fun progress() {}
}

/** Synthesized earcons (no audio assets) plus a light haptic, each individually switchable. */
class AndroidVoiceCues(
    context: Context,
    private val soundsOn: () -> Boolean,
    private val hapticsOn: () -> Boolean
) : VoiceCues {
    private val app = context.applicationContext
    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= 31) (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    } catch (_: Throwable) { null }

    // B-14: one earcon AND one haptic pattern per state, so the vocabulary is learnable by sound or by touch alone.
    // Tones are `ux.earcons.<cue>` ([[hz, ms], …]) and haptics `ux.haptics.<cue>` ([off, on, off, on …] ms) in config.
    override fun listening() = cue("listening", listOf(659.3 to 70, 880.0 to 90), 0.22, longArrayOf(0, 12))
    override fun captured() = cue("captured", listOf(880.0 to 55), 0.16, longArrayOf(0, 10))
    override fun done() = cue("done", listOf(880.0 to 70, 659.3 to 110), 0.22, longArrayOf(0, 35))
    override fun unverified() = cue("unverified", listOf(740.0 to 90, 740.0 to 90), 0.16, longArrayOf(0, 20, 120, 20))
    override fun needsOk() = cue("needsOk", listOf(587.3 to 90, 0.0 to 40, 587.3 to 90), 0.2, longArrayOf(0, 30, 90, 30))
    override fun error() = cue("error", listOf(330.0 to 110, 0.0 to 50, 294.0 to 140), 0.18, longArrayOf(0, 60, 80, 60, 80, 60))
    override fun progress() = cue("progress", listOf(523.3 to 40), 0.1, longArrayOf(0, 8))

    private fun cue(name: String, tones: List<Pair<Double, Int>>, gain: Double, pattern: LongArray) {
        haptic(configPattern(name) ?: pattern)
        val notes = configTones(name) ?: tones
        // At night the earcons come down with the voice (QuietHours) — half as loud, same shapes.
        val g = gain * (if (QuietHours.factor() < 1f) 0.5 else 1.0)
        inBackground { play(notes, g) }
    }

    private fun configTones(name: String): List<Pair<Double, Int>>? {
        val a = com.tekyerema.core.config.AppConfig.node("ux.earcons.$name") as? org.json.JSONArray ?: return null
        return (0 until a.length()).mapNotNull { i -> a.optJSONArray(i)?.let { it.optDouble(0) to it.optInt(1) } }.takeIf { it.isNotEmpty() }
    }

    private fun configPattern(name: String): LongArray? {
        val a = com.tekyerema.core.config.AppConfig.node("ux.haptics.$name") as? org.json.JSONArray ?: return null
        return LongArray(a.length()) { a.optLong(it) }.takeIf { it.size >= 2 }
    }

    private fun inBackground(block: () -> Unit) { if (soundsOn()) Thread(block, "tk-earcon").start() }

    /** Needs android.permission.VIBRATE (declared since B-14 — without it every vibrate() threw and was swallowed). */
    private fun haptic(pattern: LongArray) {
        if (!hapticsOn()) return
        if (vibrator == null) return
        // An accessibility vibration, not a touch one (core/Haptics — the Infinix drops touch vibrations).
        com.tekyerema.core.Haptics.waveform(app, pattern)
    }

    /** Sine notes (Hz to ms; 0 Hz = rest) with a soft attack/release, played synchronously. */
    private fun play(notes: List<Pair<Double, Int>>, gain: Double) {
        val sr = 44_100
        val total = notes.sumOf { it.second } * sr / 1000
        val pcm = ShortArray(total)
        var pos = 0
        for ((hz, ms) in notes) {
            val n = ms * sr / 1000
            val ramp = minOf(n / 4, sr * 12 / 1000).coerceAtLeast(1)
            for (i in 0 until n) {
                val env = when {
                    i < ramp -> i / ramp.toDouble()
                    i > n - ramp -> (n - i) / ramp.toDouble()
                    else -> 1.0
                }
                val v = if (hz <= 0.0) 0.0 else sin(2 * PI * hz * i / sr) * env * gain
                pcm[pos++] = (v * Short.MAX_VALUE).toInt().toShort()
            }
        }
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(sr)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        } catch (_: Throwable) { return }
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep((total * 1000L / sr) + 30)
        } catch (_: Throwable) {
        } finally {
            try { track.stop(); track.release() } catch (_: Throwable) {}
        }
    }
}
