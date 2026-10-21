package com.tekyerema.money.ussd

import android.content.Context
import android.util.Log
import com.tekyerema.accessibility.AccessibilityController
import com.tekyerema.money.UssdDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * MTN's confirmation AFTER the PIN ("1. Confirm 2. Cancel" on a Mashup, a bundle, a pushed payment) — owner, 21 Oct 2026: "after
 * the PIN I'll be asked to confirm again by pressing 1 or 2… our app doesn't notice that". The screen is read out and the user
 * answers with a key: VOLUME UP confirms (the app enters 1 and sends), the POWER button cancels (as at the PIN). It never goes
 * on by itself — the PIN is not consent to whatever MTN shows next (a fee, another bundle). An unanswered screen times out at
 * MTN's end with nothing sent.
 */
object AfterPinConfirm {
    private const val TAG = "Tekyerema"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class Pending(val ctrl: AccessibilityController, val screen: UssdDialog.Screen, val until: Long)
    @Volatile private var pending: Pending? = null
    @Volatile private var cancelOnScreenOn = false
    private var receiver: android.content.BroadcastReceiver? = null

    private val OPTION_ONE = Regex("(?m)(?:^|\\s)1\\s*[).:-]?\\s*\\p{L}")
    private val CONFIRMISH = Regex("(?i)\\b(confirm|accept|proceed|yes|agree|continue|approve)\\b")
    private val PIN_ASK = Regex("(?i)\\b(pin|password|passcode|otp|code)\\b")

    /** A post-PIN menu that asks to confirm: an option "1" with a confirm-like word, and no PIN or code asked for. Pure. */
    fun looksLikeConfirm(text: String): Boolean = !PIN_ASK.containsMatchIn(text) && OPTION_ONE.containsMatchIn(text) && CONFIRMISH.containsMatchIn(text)

    val armed: Boolean get() = pending?.let { android.os.SystemClock.uptimeMillis() < it.until } == true

    /** Wait for the user's key on [screen]; the power button cancels (also when it turns the screen off). */
    internal fun arm(ctrl: AccessibilityController, screen: UssdDialog.Screen, app: Context?) {
        pending = Pending(ctrl, screen, android.os.SystemClock.uptimeMillis() + 90_000L)
        cancelOnScreenOn = false
        if (app != null && receiver == null) {
            val r = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context, i: android.content.Intent) {
                    when (i.action) {
                        android.content.Intent.ACTION_SCREEN_OFF -> if (armed) { Log.i(TAG, "[MONEY] power button at MTN's confirm — cancelling"); cancelOnScreenOn = true; cancelNow() }
                        android.content.Intent.ACTION_USER_PRESENT, android.content.Intent.ACTION_SCREEN_ON -> if (cancelOnScreenOn) cancelNow()
                    }
                }
            }
            val f = android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_SCREEN_OFF); addAction(android.content.Intent.ACTION_SCREEN_ON); addAction(android.content.Intent.ACTION_USER_PRESENT)
            }
            try { app.applicationContext.registerReceiver(r, f); receiver = r } catch (t: Throwable) { Log.w(TAG, "[MONEY] power cancel unavailable: ${t.message}") }
        }
        Log.i(TAG, "[MONEY] MTN asks to confirm after the PIN — waiting for volume up (confirm) or power (cancel)")
    }

    /** Accessibility key filter: VOLUME UP confirms while armed (both edges consumed). */
    fun onKey(keyCode: Int, action: Int): Boolean {
        if (!armed || keyCode != android.view.KeyEvent.KEYCODE_VOLUME_UP) return false
        if (action == android.view.KeyEvent.ACTION_UP) confirmNow()
        return true
    }

    private fun confirmNow() {
        val p = pending ?: return
        pending = null
        Log.i(TAG, "[MONEY] the user confirmed MTN's screen with volume up — entering 1")
        scope.launch {
            val ok = try { UssdDialog.answer(p.ctrl, p.screen, "1") } catch (_: Throwable) { false }
            try { com.tekyerema.voice.VoiceEngine.announce(if (ok) "Confirmed. Waiting for MTN." else "I couldn't press confirm. Press 1 and Send on the screen.") } catch (_: Throwable) {}
        }
    }

    private fun cancelNow() {
        val p = pending ?: return
        scope.launch {
            // With the screen off the window can't be read: tried again when it comes back on (USER_PRESENT / SCREEN_ON).
            val now = try { UssdDialog.awaitCurrent(p.ctrl, 1_500L) } catch (_: Throwable) { null }
            val closed = now != null && try { UssdDialog.close(p.ctrl, now) } catch (_: Throwable) { false }
            if (closed) {
                pending = null; cancelOnScreenOn = false
                try { AfterPin.cancel() } catch (_: Throwable) {}
                try { com.tekyerema.money.TxLedger.abandon() } catch (_: Throwable) {}
                try { com.tekyerema.voice.SecureStep.end() } catch (_: Throwable) {}
                try { com.tekyerema.voice.VoiceEngine.announce("Cancelled. Nothing was sent.") } catch (_: Throwable) {}
            }
        }
    }

    fun disarm() { pending = null; cancelOnScreenOn = false }
}
