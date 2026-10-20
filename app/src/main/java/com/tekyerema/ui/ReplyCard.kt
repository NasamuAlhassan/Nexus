package com.tekyerema.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.tekyerema.accessibility.TekyeremaAccessibilityService

/**
 * A small card with the reply's words over whatever app is open, while the reply is spoken and for a moment after.
 *
 * Owner, 20 Oct 2026: "sometimes it's responding but nothing shows". Over other apps only a QUESTION or a CONFIRMATION had a panel
 * (OverlayConfirm); a plain answer — the wife's message, the time, "Cancelled" — was spoken and, unless Tɛkyerɛma's own screen was in
 * front, never shown. In Twi / Kusaal the first line is the language the voice speaks and the second the English it came from.
 *
 * An accessibility overlay (no "display over other apps" permission needed), glass-like (no outline, soft translucent white, large
 * dark text), a tap dismisses it, it takes no focus, and it leaves a prompt panel or Tɛkyerɛma's own screen alone. TalkBack: when the
 * reply is read aloud by our own voice the card is hidden from it (it would read twice); with read-aloud off the card is a polite
 * live region. `ui.replyCard` = false turns it off.
 */
object ReplyCard {
    private const val TAG = "Tekyerema"
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var view: View? = null
    @Volatile private var hideAt: Runnable? = null

    /** Show (or update) the card with [text]; [readAloud] = our own voice is saying it. */
    fun show(text: String, readAloud: Boolean) {
        if (text.isBlank()) return
        try { main.post { showNow(text, readAloud) } } catch (_: Throwable) {}   // JVM tests: no main looper
    }

    /** Take the card down [afterMs] from now (long enough to read what was said); a new [show] cancels it. */
    fun hideAfter(afterMs: Long) {
        try {
            main.post {
                hideAt?.let(main::removeCallbacks)
                val r = Runnable { hideNow() }
                hideAt = r
                main.postDelayed(r, afterMs.coerceIn(1_500L, 20_000L))
            }
        } catch (_: Throwable) {}
    }

    fun hide() { try { main.post { hideNow() } } catch (_: Throwable) {} }

    private fun hideNow() {
        hideAt?.let(main::removeCallbacks); hideAt = null
        val v = view ?: return
        view = null
        try { (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Throwable) {}
    }

    private fun showNow(text: String, readAloud: Boolean) {
        if (!com.tekyerema.core.config.AppConfig.bool("ui.replyCard", true)) return
        val svc = TekyeremaAccessibilityService.live ?: return
        // Tɛkyerɛma's own screen shows the reply itself; a question / confirmation panel owns the top of the screen.
        if (OverlayConfirm.appSurfaceShowing() || OverlayConfirm.onScreen.value) { hideNow(); return }
        hideAt?.let(main::removeCallbacks); hideAt = null
        try {
            val d = svc.resources.displayMetrics.density
            val lines = text.split("\n", limit = 2)
            val card = LinearLayout(svc).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((20 * d).toInt(), (16 * d).toInt(), (20 * d).toInt(), (16 * d).toInt())
                background = GradientDrawable().apply { setColor(Color.parseColor("#F2F7FAFC")); cornerRadius = 28 * d }
                elevation = 10 * d
                isClickable = true
                setOnClickListener { hideNow() }
                importantForAccessibility = if (readAloud) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_YES
                accessibilityLiveRegion = if (readAloud) View.ACCESSIBILITY_LIVE_REGION_NONE else View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            card.addView(TextView(svc).apply {
                this.text = lines[0]
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f); setTextColor(Color.parseColor("#14202B"))
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); maxLines = 6; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (lines.size > 1 && lines[1].isNotBlank()) card.addView(TextView(svc).apply {
                this.text = lines[1]
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setTextColor(Color.parseColor("#566574"))
                setPadding(0, (6 * d).toInt(), 0, 0); maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val id = svc.resources.getIdentifier("status_bar_height", "dimen", "android")
            val top = (if (id > 0) svc.resources.getDimensionPixelSize(id) else (24 * d).toInt()) + (8 * d).toInt()
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP; y = top; x = 0
                horizontalMargin = 0.03f
            }
            val old = view
            if (old != null) try { wm.removeView(old) } catch (_: Throwable) {}
            wm.addView(card, lp)
            view = card
        } catch (t: Throwable) { Log.w(TAG, "[REPLY-CARD] unavailable: ${t.message}") }
    }
}
