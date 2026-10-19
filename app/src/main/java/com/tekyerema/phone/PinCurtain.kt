package com.tekyerema.phone

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlinx.coroutines.launch

/**
 * Demo step 4 (owner, 14 Oct 2026): "sensitive screen content, including the balance, is hidden or masked during PIN entry".
 * While the PIN step lasts (voice/SecureStep) the screen is covered by a dark curtain — the PIN, the network's lines, the
 * balance on its answer are not visible to anyone looking at the phone (a stage, a projector, a shoulder). The curtain takes
 * no touches (they pass to the dialog underneath) and is invisible to TalkBack, so a blind user enters the PIN exactly as
 * before (the volume keys, or the dialog's own keys); it lifts once the network's dialog has left the screen (so the balance
 * on the answer stays covered too), and at the latest [MAX_MS] after the step. `pin.curtain` = false turns it off.
 */
object PinCurtain {
    private const val TAG = "Tekyerema"
    private const val MAX_MS = 180_000L
    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var view: View? = null
    @Volatile private var shownAt = 0L

    fun show() {
        try {
            if (!com.tekyerema.core.config.AppConfig.bool("pin.curtain", true)) return
            main.post { showNow() }
        } catch (_: Throwable) {}   // JVM tests: no main looper
    }

    /** The step ended: lift the curtain once the phone's dialog is gone (checked every half second, at most 20 s). */
    fun hideWhenDialogGone() {
        try { main.post { watch(0) } } catch (_: Throwable) {}
    }

    fun hideNow() {
        try { main.post { removeNow("now") } } catch (_: Throwable) {}
    }

    private fun watch(round: Int) {
        if (view == null) return
        val svc = com.tekyerema.accessibility.TekyeremaAccessibilityService.live
        // The window list, not the last event's package: a widget's update on the home screen is not the dialog closing.
        val phoneUp = svc?.phoneWindowPresent() == true
        if (!phoneUp || round >= 40) { removeNow(if (phoneUp) "time" else "dialog gone"); return }
        main.postDelayed({ watch(round + 1) }, 500)
    }

    private fun showNow() {
        if (view != null) return
        val svc = com.tekyerema.accessibility.TekyeremaAccessibilityService.live ?: return
        try {
            val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val d = svc.resources.displayMetrics.density
            val text = TextView(svc).apply {
                text = "Secure step\nThe screen is hidden while you enter your PIN."
                setTextColor(Color.parseColor("#C8D3DE")); textSize = 18f; gravity = Gravity.CENTER
                setLineSpacing(0f, 1.25f); setPadding((28 * d).toInt(), 0, (28 * d).toInt(), 0)
            }
            val curtain = FrameLayout(svc).apply {
                setBackgroundColor(Color.parseColor("#FF050A10"))   // opaque: at 95% the dialog's words still showed through
                addView(text, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT)
            wm.addView(curtain, lp)
            view = curtain; shownAt = System.currentTimeMillis()
            listenForPower(svc)
            Log.i(TAG, "[PIN] screen curtain on")
            main.postDelayed({ if (view === curtain && System.currentTimeMillis() - shownAt >= MAX_MS) removeNow("max") }, MAX_MS)
        } catch (t: Throwable) { Log.w(TAG, "[PIN] curtain unavailable: ${t.message}") }
    }

    /** Owner, 19 Oct 2026: Home and Back don't leave the network's PIN dialog, so the power button does — pressing it while the
     *  curtain is up cancels the payment (nothing is typed or sent; closing the dialog never pays). The close is tried as the
     *  screen goes off and again when the phone is unlocked, until the dialog is gone. */
    @Volatile private var powerReceiver: android.content.BroadcastReceiver? = null
    @Volatile private var closePending = false
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    private fun listenForPower(svc: com.tekyerema.accessibility.TekyeremaAccessibilityService) {
        if (powerReceiver != null) return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: android.content.Intent) {
                if (i.action == android.content.Intent.ACTION_SCREEN_OFF && view != null) cancelByPower(svc)
                else if (closePending) closeDialog(svc)
            }
        }
        val f = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_OFF); addAction(android.content.Intent.ACTION_USER_PRESENT)
        }
        try { svc.registerReceiver(r, f); powerReceiver = r } catch (t: Throwable) { Log.w(TAG, "[PIN] power-button cancel unavailable: ${t.message}") }
    }

    @Volatile private var lastCancelAt = 0L

    /** The user pressed Cancel on the network's own dialog at the PIN step (TekyeremaAccessibilityService): the same ending as the
     *  power button — nothing was sent, the open hand-off is dropped, no "PIN sent" and no "no word from the network" later. */
    fun onUserCancelled() {
        val now = System.currentTimeMillis()
        if (now - lastCancelAt < 8_000) return
        lastCancelAt = now
        Log.i(TAG, "[PIN] the user pressed Cancel on the network's dialog — nothing is sent")
        try { com.tekyerema.money.ussd.AfterPin.cancel() } catch (_: Throwable) {}
        try { com.tekyerema.money.TxLedger.abandon() } catch (_: Throwable) {}
        try { com.tekyerema.money.ussd.PushedPin.cancel() } catch (_: Throwable) {}
        try { com.tekyerema.voice.SecureStep.end() } catch (_: Throwable) {}
        try { com.tekyerema.voice.VoiceEngine.announce("Cancelled. Nothing was sent.") } catch (_: Throwable) {}
    }

    private fun cancelByPower(svc: com.tekyerema.accessibility.TekyeremaAccessibilityService) {
        lastCancelAt = System.currentTimeMillis()   // the dialog's Cancel it clicks next is not a second cancel
        Log.i(TAG, "[PIN] power button pressed at the PIN — cancelling; nothing is sent")
        closePending = true
        try { com.tekyerema.money.ussd.AfterPin.cancel() } catch (_: Throwable) {}
        try { com.tekyerema.money.TxLedger.abandon() } catch (_: Throwable) {}
        try { com.tekyerema.money.TelcoSession.end() } catch (_: Throwable) {}
        try { com.tekyerema.money.ussd.PushedPin.cancel() } catch (_: Throwable) {}
        try { com.tekyerema.agent.AgentEngine.stop() } catch (_: Throwable) {}
        try { com.tekyerema.voice.SecureStep.end() } catch (_: Throwable) {}
        closeDialog(svc)
    }

    private fun closeDialog(svc: com.tekyerema.accessibility.TekyeremaAccessibilityService) {
        scope.launch {
            val now = com.tekyerema.money.UssdDialog.awaitCurrent(svc, 1_500L)
            if (now == null) {
                // The screen is off: the window list is not readable — keep trying when it is unlocked (USER_PRESENT), not before.
                val on = try { (svc.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive } catch (_: Throwable) { true }
                if (on) { closePending = false; stopListening(svc) }
                return@launch
            }
            val closed = com.tekyerema.money.UssdDialog.close(svc, now)
            Log.i(TAG, "[PIN] dialog closed by the power button (gone=$closed)")
            if (closed) {
                closePending = false; stopListening(svc)
                try { com.tekyerema.voice.VoiceEngine.announce("Cancelled. Nothing was sent.") } catch (_: Throwable) {}
            }
        }
    }

    private fun stopListening(svc: Context) {
        val r = powerReceiver ?: return
        powerReceiver = null
        try { svc.unregisterReceiver(r) } catch (_: Throwable) {}
    }

    private fun removeNow(why: String) {
        val v = view ?: return
        view = null
        if (!closePending) try { stopListening(v.context) } catch (_: Throwable) {}
        try { (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Throwable) {}
        if (why == "dialog gone") try { com.tekyerema.agent.helper.HelperGate.dialogGone() } catch (_: Throwable) {}
        Log.i(TAG, "[PIN] screen curtain off ($why)")
    }
}
