package com.tekyerema.tools

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.view.accessibility.AccessibilityManager

/**
 * Two protections around a Mobile Money send, from the payment-safety research (2026-09-29,
 * docs/agent/research/secure_payment_auth.md):
 *  - **On a call**: the commonest MoMo scam in Ghana is a caller talking the victim through a send ("I sent it to you by
 *    mistake, send it back", a fake investigator). A send asked for during a call says so in the read-back.
 *  - **PIN on the loudspeaker**: TalkBack speaks the keys a blind user touches, and the USSD box is plain text — with no
 *    earphones, people nearby can hear the PIN. The hand-off line says so, only when a screen reader is on and nothing
 *    private is connected (EN 301 549 5.1.3.8/9: private data only to private listening).
 * The decisions are pure; the probes are thin.
 */
object MoneyPrivacy {
    fun callWarning(onCall: Boolean): String? = if (!onCall) null else
        "You're on a phone call right now. If someone on the call is telling you to send this money, stop — that's how most Mobile Money scams work."

    fun pinTip(screenReaderOn: Boolean, privateAudio: Boolean): String? = if (!screenReaderOn || privateAudio) null else
        "The phone is on its loudspeaker, and TalkBack may read your PIN digits aloud — use earphones, or make sure no one is near."

    // ---- probes (no permission needed) ----
    fun onCall(ctx: Context): Boolean = try {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        am?.mode == AudioManager.MODE_IN_CALL || am?.mode == AudioManager.MODE_IN_COMMUNICATION
    } catch (_: Throwable) { false }

    private val PRIVATE_OUTPUTS = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_USB_HEADSET)

    fun privateAudio(ctx: Context): Boolean = try {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.any { it.type in PRIVATE_OUTPUTS } == true
    } catch (_: Throwable) { false }

    /** A spoken-feedback accessibility service other than Tɛkyerɛma (TalkBack, Samsung's TalkBack, Jieshuo…). */
    fun screenReaderOn(ctx: Context): Boolean = try {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        am?.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN)
            ?.any { it.resolveInfo?.serviceInfo?.packageName != ctx.packageName } == true
    } catch (_: Throwable) { false }
}
