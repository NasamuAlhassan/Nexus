package com.tekyerema.core

import android.app.KeyguardManager
import android.content.Context

/**
 * The assistant works only on an unlocked phone (owner, 25 Oct 2026: it worked while locked, which is not secure). The
 * check is the lock screen being up ([KeyguardManager.isKeyguardLocked]), so a swipe-only lock counts too. The app never
 * unlocks the phone and never enters a PIN or pattern; the user does that, then asks again.
 */
object DeviceLock {
    /** Spoken and shown when a request arrives on the lock screen. */
    const val LOCKED_LINE = "Your phone is locked. Please unlock it, then ask me again."

    /** Pure decision, so it is unit-tested: refuse when the lock screen is up and the setting is on. */
    fun refuses(locked: Boolean, requireUnlocked: Boolean): Boolean = requireUnlocked && locked

    /** The one question every door asks (wake word, floating icon, voice screen, tile, assistant button): is the
     *  assistant shut because the phone is locked? Nothing wakes, shows or listens while it is. */
    fun blocks(ctx: Context): Boolean =
        refuses(isLocked(ctx), com.tekyerema.core.config.AppConfig.bool("security.requireUnlocked", true))

    fun isLocked(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true
    } catch (_: Throwable) { false }   // a phone that will not say is treated as unlocked, so a failed check never silences the app
}
