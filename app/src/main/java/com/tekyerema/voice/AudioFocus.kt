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
class AudioFocus(context: Context) {
    enum class Kind { SPEAK, LISTEN }

    private val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val app = context.applicationContext
    @Volatile private var held: AudioFocusRequest? = null

    /** K-07: when a screen reader drives the phone, speak on the ACCESSIBILITY stream (TalkBack's volume, and the
     *  platform treats it as accessibility audio) instead of the assistant stream. */
    fun attributes(kind: Kind): AudioAttributes = AudioAttributes.Builder()
        .setUsage(
            if (kind == Kind.SPEAK && ScreenReader.active(app)) AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
            else AudioAttributes.USAGE_ASSISTANT
        )
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    fun acquire(kind: Kind) {
        val manager = am ?: return
        if (!AppConfig.bool("speech.audioFocus.enabled", true)) return
        if (kind == Kind.LISTEN && !AppConfig.bool("speech.audioFocus.pauseMediaWhileListening", true)) return
        release()
        val gain = if (kind == Kind.SPEAK) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
        val req = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(attributes(kind))
            .setOnAudioFocusChangeListener { }
            .build()
        try { if (manager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) held = req } catch (_: Throwable) {}
    }

    fun release() {
        val req = held ?: return
        held = null
        try { am?.abandonAudioFocusRequest(req) } catch (_: Throwable) {}
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
