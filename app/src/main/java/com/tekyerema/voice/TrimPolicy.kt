package com.tekyerema.voice

import android.content.ComponentCallbacks2

/**
 * When Android's memory-trim signal should make the app drop a loaded on-device model (a voice, a recogniser). Phone log,
 * 24 Oct 2026 (A20e, 2.7 GB): "running low" arrives every few seconds, and the app dropped the English voice and recogniser
 * on each one — a voice used seconds earlier, which then took 10 s to load again for the next line. A model used within
 * [graceMs] survives the mild "running low" signal; "running critical", or the app no longer in front (background levels),
 * still release everything. (The recogniser's own memory guard frees voices on demand when it needs the room, unchanged.)
 * `speech.trim.graceMs`; 0 = the old behaviour. Pure → TrimPolicyTest.
 */
object TrimPolicy {
    fun shouldRelease(level: Int, msSinceLastUse: Long, graceMs: Long): Boolean {
        if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return false
        if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return false
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) return true
        return graceMs <= 0L || msSinceLastUse !in 0 until graceMs   // RUNNING_LOW: only what has sat unused
    }

    /**
     * The same question for the SMALL English voice (64 MB), which the 650 MB recogniser cannot be allowed to evict every turn:
     * on the A20e offline (27 Oct 2026) "running critical" arrived after each answer, the voice was dropped at once and the next
     * line waited 6-9 s for it to load. A voice used within [graceMs] stays through "running low" AND "running critical" (the
     * app is still in front, being used); it goes when it has sat unused, or when the app itself is no longer in front (a background level).
     * The recogniser's own memory guard still frees it on demand when a model really needs the room.
     */
    fun shouldReleaseSmallVoice(level: Int, msSinceLastUse: Long, graceMs: Long): Boolean {
        if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return false
        if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return false
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) return true
        return graceMs <= 0L || msSinceLastUse !in 0 until graceMs
    }
}
