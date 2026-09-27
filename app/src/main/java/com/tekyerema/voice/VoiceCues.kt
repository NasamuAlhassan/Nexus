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
    fun done() {}
    fun needsOk() {}
    fun error() {}
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

    override fun listening() { haptic(strong = false); inBackground { play(listOf(659.3 to 70, 880.0 to 90), gain = 0.22) } }
    override fun captured() { haptic(strong = false); inBackground { play(listOf(880.0 to 55), gain = 0.16) } }
    override fun done() { haptic(strong = true); inBackground { play(listOf(880.0 to 70, 659.3 to 110), gain = 0.22) } }
    override fun needsOk() { haptic(strong = true); inBackground { play(listOf(587.3 to 90, 0.0 to 40, 587.3 to 90), gain = 0.2) } }
    override fun error() { haptic(strong = true); inBackground { play(listOf(330.0 to 110, 0.0 to 50, 294.0 to 140), gain = 0.18) } }

    private fun inBackground(block: () -> Unit) { if (soundsOn()) Thread(block, "tk-earcon").start() }

    private fun haptic(strong: Boolean) {
        if (!hapticsOn()) return
        val v = vibrator ?: return
        try {
            val effect = if (Build.VERSION.SDK_INT >= 29)
                VibrationEffect.createPredefined(if (strong) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_TICK)
            else VibrationEffect.createOneShot(if (strong) 30 else 15, VibrationEffect.DEFAULT_AMPLITUDE)
            v.vibrate(effect)
        } catch (_: Throwable) {}
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
