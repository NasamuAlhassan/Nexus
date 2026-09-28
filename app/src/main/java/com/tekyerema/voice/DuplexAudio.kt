package com.tekyerema.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.tekyerema.core.config.AppConfig

/**
 * B-07 — full-duplex audio for barge-in: our reply and the listening mic on the VOICE-COMMUNICATION path, so the
 * phone's echo canceller can remove our own voice from what the mic hears.
 *
 * Why (A20e, 2026-09-28): the barge-in mic recorded with VOICE_COMMUNICATION + AcousticEchoCanceler, but the reply
 * played on the assistant (media) stream, and every long reply interrupted itself. The platform AEC only takes the
 * voice-communication PLAYBACK path as its echo reference: AOSP requires the AEC on the VOICE_COMMUNICATION capture
 * path, and full-duplex apps (WebRTC; react-native-audio-api#1254) found that media-stream playback "leaks into the
 * microphone uncancelled" unless the output uses USAGE_VOICE_COMMUNICATION — with MODE_IN_COMMUNICATION for the device
 * to engage its voice-processing chain.
 *
 * For one interruptible reply this: enters MODE_IN_COMMUNICATION, forces the loudspeaker (voice-communication audio
 * otherwise goes to the EARPIECE — a blind user would hear a whisper), sets the call volume to match the level the
 * reply normally plays at, and flags [active] so [AudioFocus.attributes] plays the reply with USAGE_VOICE_COMMUNICATION.
 * [end] restores everything. Never during a call or a ringing phone (only from MODE_NORMAL), and not with a headset
 * (no echo to cancel; a Bluetooth headset would drop to the low-quality call profile). If the process dies mid-reply
 * the system restores the audio mode itself (AudioService releases a dead client's mode). Behind the B-07 flag and
 * `speech.bargeIn.duplex`.
 */
class DuplexAudio(context: Context) {
    enum class Plan { ENTER, SKIP_OFF, SKIP_NOT_IDLE, SKIP_HEADSET }

    private val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val app = context.applicationContext
    private var savedMode = AudioManager.MODE_NORMAL
    private var savedSpeaker = false
    private var savedCallVolume = -1

    /** Enter duplex for one reply. False = not entered (off, not idle, a headset); barge-in may still run as before. */
    @Suppress("DEPRECATION")   // isSpeakerphoneOn: the API-30 (A20e) way; API 31+ uses the communication device
    fun begin(): Boolean {
        val m = am ?: return false
        if (active) return true
        val plan = try { plan(AppConfig.bool("speech.bargeIn.duplex", true), m.mode, headset(m)) } catch (_: Throwable) { Plan.SKIP_OFF }
        if (plan != Plan.ENTER) { Log.i("Tekyerema", "[VOICE] barge-in duplex: $plan"); return false }
        return try {
            savedMode = m.mode
            savedSpeaker = m.isSpeakerphoneOn
            savedCallVolume = m.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            m.mode = AudioManager.MODE_IN_COMMUNICATION
            toSpeaker(m, true)
            if (AppConfig.bool("speech.bargeIn.matchVolume", true)) matchCallVolume(m)
            active = true
            Log.i("Tekyerema", "[VOICE] barge-in duplex: on (voice-communication path, loudspeaker)")
            true
        } catch (t: Throwable) {
            Log.w("Tekyerema", "[VOICE] barge-in duplex unavailable: ${t.message}")
            restore(m)
            false
        }
    }

    /** Leave duplex: the mode, loudspeaker and call volume go back to what they were. */
    fun end() {
        val m = am ?: return
        if (!active) return
        active = false
        restore(m)
        Log.i("Tekyerema", "[VOICE] barge-in duplex: off")
    }

    private fun restore(m: AudioManager) {
        // API 31+: our communication device is simply cleared (the platform default returns); ≤ 30: the old flag back.
        try { toSpeaker(m, if (Build.VERSION.SDK_INT >= 31) false else savedSpeaker) } catch (_: Throwable) {}
        try { if (savedCallVolume >= 0) m.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedCallVolume, 0) } catch (_: Throwable) {}
        try { m.mode = savedMode } catch (_: Throwable) {}
        savedCallVolume = -1
    }

    @Suppress("DEPRECATION")   // setSpeakerphoneOn on API ≤ 30
    private fun toSpeaker(m: AudioManager, on: Boolean) {
        if (Build.VERSION.SDK_INT >= 31) {
            if (on) m.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { m.setCommunicationDevice(it) }
            else m.clearCommunicationDevice()
        } else {
            m.isSpeakerphoneOn = on
        }
    }

    /** The reply normally plays on the media stream (the accessibility stream under TalkBack); the call stream has its
     *  own volume. Without this the reply would suddenly be much louder or quieter than every other line. */
    private fun matchCallVolume(m: AudioManager) {
        val from = if (ScreenReader.active(app)) AudioManager.STREAM_ACCESSIBILITY else AudioManager.STREAM_MUSIC
        val callMin = if (Build.VERSION.SDK_INT >= 28) m.getStreamMinVolume(AudioManager.STREAM_VOICE_CALL) else 1
        val idx = matchedIndex(m.getStreamVolume(from), m.getStreamMaxVolume(from), callMin, m.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL))
        m.setStreamVolume(AudioManager.STREAM_VOICE_CALL, idx, 0)
    }

    private fun headset(m: AudioManager): Boolean = m.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADSETS }

    companion object {
        /** A duplex reply is playing: speak on the voice-communication usage ([AudioFocus.attributes]). */
        @Volatile var active = false
            private set

        private val HEADSETS = buildSet {
            add(AudioDeviceInfo.TYPE_WIRED_HEADSET); add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP); add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            add(AudioDeviceInfo.TYPE_USB_HEADSET)
            add(26 /* TYPE_BLE_HEADSET, API 31 */)
        }

        /** Pure: whether to enter duplex. Only from a normal, idle audio mode — never over a call or a ringtone. */
        fun plan(enabled: Boolean, mode: Int, headset: Boolean): Plan = when {
            !enabled -> Plan.SKIP_OFF
            mode != AudioManager.MODE_NORMAL -> Plan.SKIP_NOT_IDLE
            headset -> Plan.SKIP_HEADSET
            else -> Plan.ENTER
        }

        /** Pure: the call-volume index at the same fraction of its range as [index] of [max]. */
        fun matchedIndex(index: Int, max: Int, callMin: Int, callMax: Int): Int {
            if (max <= 0 || callMax <= 0) return callMax.coerceAtLeast(callMin)
            return Math.round(index.toFloat() / max * callMax).coerceIn(callMin, callMax)
        }
    }
}
