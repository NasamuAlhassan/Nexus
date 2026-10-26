package com.tekyerema.accessibility

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import com.tekyerema.agent.AgentEngine
import com.tekyerema.core.config.AppConfig
import com.tekyerema.money.PinPad

/**
 * The screen as a PIN pad (owner, 26 Oct 2026): the same counting as the volume keys ([PinPad]), for a phone whose keys are
 * worn or a user who finds the screen easier. A TAP anywhere is Volume DOWN (one more for the digit), a LONG PRESS is Volume
 * UP (the counted digit goes in; nothing counted is a 0), a TWO-FINGER TAP is the held key (take one back).
 *
 * One transparent, full-screen accessibility overlay that exists ONLY while the pad is armed and the network's PIN screen is
 * the secure step ([PinPad.handles]): [watch] polls that, adds the layer when it holds and removes it the moment it stops, so
 * it never sits over anything else. While it is up the dialog under it takes no touches (no stray keypad press, no Cancel
 * tapped by accident); Back still cancels, and THREE fingers hand the screen back to the dialog's own keys for this PIN step
 * (for someone who would rather use the real keypad). Silent like the keys: no sound, no log of a digit, nothing stored.
 * TalkBack's own touch exploration may take single touches first — a phone test, not a promise.
 */
object PinTouchPad {
    private val main = Handler(Looper.getMainLooper())
    private var view: PadView? = null
    private var watching = false
    /** Three fingers were put down: no layer for the rest of this PIN step (cleared when the pad is armed again). */
    private var suspended = false
    private const val POLL_MS = 300L

    /** Follow [PinPad.handles] while the pad is armed: layer up when the PIN step is open, gone when it isn't. */
    fun watch() { main.post { suspended = false; if (!watching) { watching = true; tick() } } }

    /** The screen goes back to the network's own dialog until the pad is armed again. */
    private fun suspend() { suspended = true; hide() }

    private fun tick() {
        val armed = PinPad.isArmed()
        val want = armed && !suspended && PinPad.touchEnabled() && PinPad.handles()
        if (want && view == null) show()
        if (!want && view != null) hide()
        if (armed || view != null) main.postDelayed({ tick() }, POLL_MS) else watching = false
    }

    private fun show() {
        val service = TekyeremaAccessibilityService.live ?: return
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        val v = PadView(service)
        try { wm.addView(v, params); view = v } catch (_: Throwable) { view = null }
    }

    private fun hide() {
        val v = view ?: return
        view = null
        v.cancelPending()
        try { (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Throwable) {}
    }

    private class PadView(context: Context) : View(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop * 2f
        private val longMs get() = AppConfig.long("pin.touchPad.longPressMs", 600L)
        private var downAt = 0L
        private var downX = 0f
        private var downY = 0f
        private var moved = 0f
        private var pointers = 0
        private var fired = false
        private val longPress = Runnable {
            if (!fired && pointers == 1 && moved <= slop) { fired = true; deliver(PinPad.Gesture.LONG_PRESS) }
        }

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        fun cancelPending() { removeCallbacks(longPress) }

        private fun deliver(g: PinPad.Gesture) {
            val ctrl = AgentEngine.currentController() ?: return
            PinPad.onGesture(ctrl, g, SystemClock.uptimeMillis())
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = e.eventTime; downX = e.x; downY = e.y; moved = 0f; pointers = 1; fired = false
                    postDelayed(longPress, longMs)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    pointers = maxOf(pointers, e.pointerCount); removeCallbacks(longPress)
                    if (pointers >= 3) { performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); post { suspend() } }
                }
                MotionEvent.ACTION_MOVE -> moved = maxOf(moved, Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()).toFloat())
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longPress)
                    if (!fired) PinPad.classify(e.eventTime - downAt, pointers, moved, slop, longMs)?.let { deliver(it) }
                }
                MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
            }
            return true   // every touch on the PIN screen is the pad's while the layer is up
        }
    }
}
