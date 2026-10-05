package com.tekyerema.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.view.accessibility.AccessibilityManager
import com.tekyerema.core.config.AppConfig

/**
 * B-15 — audio focus around our own audio. While Tɛkyerɛma SPEAKS, other audio ducks (transient, may-duck); while it
 * LISTENS, music pauses (transient-exclusive) so the recognizer hears the user and not the song. Released as soon as
 * the utterance or capture ends, so the user's media resumes on its own.
 */
class AudioFocus(context: Context, private val onLost: () -> Unit = {}) {
    enum class Kind { SPEAK, LISTEN }

    companion object {
        /** Another app took the audio outright (a ringing call, an alarm, a voice note) — not a short duck (a
         *  notification sound, TalkBack), which we speak under. Pure, unit-tested. */
        fun mustStop(change: Int): Boolean =
            change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
    }

    private val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val app = context.applicationContext
    @Volatile private var held: AudioFocusRequest? = null

    /** K-07: when a screen reader drives the phone, speak on the ACCESSIBILITY stream (TalkBack's volume, and the
     *  platform treats it as accessibility audio) instead of the assistant stream. B-07: while a barge-in reply plays
     *  full-duplex ([DuplexAudio]), speak on the VOICE-COMMUNICATION usage — the only playback the platform echo
     *  canceller takes as its reference, so the listening mic doesn't hear our own voice as the user. */
    fun attributes(kind: Kind): AudioAttributes = AudioAttributes.Builder()
        .setUsage(
            when {
                kind == Kind.SPEAK && DuplexAudio.active -> AudioAttributes.USAGE_VOICE_COMMUNICATION
                kind == Kind.SPEAK && ScreenReader.active(app) -> AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
                // The phone's own Assistant volume at 0 is silence for every word we say — on the A20e (4 October 2026)
                // it sat at 0 on the speaker while media, TTS and accessibility were at 15, and the user heard no reply at
                // all. A blind user can't find that slider: speak on the accessibility stream they can hear instead.
                kind == Kind.SPEAK && assistantSilent() -> AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
                else -> AudioAttributes.USAGE_ASSISTANT
            }
        )
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    @Volatile private var saidSilent = false

    /** The Assistant stream (AudioSystem.STREAM_ASSISTANT = 11, API 29+) is muted while the accessibility one isn't. */
    private fun assistantSilent(): Boolean {
        val manager = am ?: return false
        val silent = try {
            manager.getStreamVolume(STREAM_ASSISTANT) == 0 && manager.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY) > 0
        } catch (_: Throwable) { false }
        if (silent && !saidSilent) android.util.Log.w("Tekyerema", "[TTS] the phone's Assistant volume is 0 — speaking on the accessibility stream")
        saidSilent = silent
        return silent
    }
    private val STREAM_ASSISTANT = 11

    /** Request focus for [kind]; returns the request so the caller can release exactly its own ([release]). */
    fun acquire(kind: Kind): AudioFocusRequest? {
        val manager = am ?: return null
        if (!AppConfig.bool("speech.audioFocus.enabled", true)) return null
        if (kind == Kind.LISTEN && !AppConfig.bool("speech.audioFocus.pauseMediaWhileListening", true)) return null
        release()
        val gain = if (kind == Kind.SPEAK) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
        val req = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(attributes(kind))
            // A reply kept talking over an incoming call's ringtone: stop it when the audio is taken from us.
            .setOnAudioFocusChangeListener { change -> if (kind == Kind.SPEAK && mustStop(change)) try { onLost() } catch (_: Throwable) {} }
            .build()
        try { if (manager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { held = req; return req } } catch (_: Throwable) {}
        return null
    }

    /** Abandon the focus we hold now. */
    fun release() {
        val req = held ?: return
        held = null
        try { am?.abandonAudioFocusRequest(req) } catch (_: Throwable) {}
    }

    /** Abandon [token] only if it is still the request we hold — an older utterance ending must not take the focus of
     *  the newer one that replaced it (the music came back up under the reply). */
    fun release(token: AudioFocusRequest?) {
        if (token == null || held !== token) return
        release()
    }
}

/** K-07 — is a screen reader (TalkBack or similar, explore-by-touch) driving the phone? Cached, kept current by
 *  AccessibilityManager listeners (the API 33 services-state listener is unavailable on API 30). */
object ScreenReader {
    @Volatile private var cached: Boolean? = null
    @Volatile private var watching = false

    fun active(ctx: Context): Boolean {
        cached?.let { return it }
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        watch(am)
        return (am.isEnabled && am.isTouchExplorationEnabled).also { cached = it }
    }

    private fun watch(am: AccessibilityManager) {
        if (watching) return
        watching = true
        try {
            am.addTouchExplorationStateChangeListener { cached = am.isEnabled && it }
            am.addAccessibilityStateChangeListener { cached = it && am.isTouchExplorationEnabled }
        } catch (_: Throwable) { watching = false }
    }
}
