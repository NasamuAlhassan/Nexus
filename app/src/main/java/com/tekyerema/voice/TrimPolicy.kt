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
}
