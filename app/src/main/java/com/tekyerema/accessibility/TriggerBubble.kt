package com.tekyerema.accessibility

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import com.tekyerema.ui.VoiceActivity
import kotlin.math.abs

/**
 * The persistent accessibility trigger: a 60 dp brand-blue disc with the Tɛkyerɛma mark, drawn as
 * an accessibility overlay over every app while screen control is on. One tap starts the voice
 * experience on top of whatever the user is doing. Draggable along the screen edge; hidden while
 * Tɛkyerɛma itself is in front. TalkBack reads it as the button "Talk to Tɛkyerɛma".
 */
object TriggerBubble {
    private const val PREFS = "tekyerema_trigger"
    private val main = Handler(Looper.getMainLooper())
    private var view: TriggerView? = null
    private var lp: WindowManager.LayoutParams? = null

    fun show(service: AccessibilityService) = main.post {
        if (view != null) return@post
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = service.resources.displayMetrics.density
        val size = (72 * d).toInt()                                  // 60 dp disc inside a 72 dp touch target
        val prefs = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val (sw, sh) = screenSize(wm, service)
            x = prefs.getInt("x", sw - size)
            y = prefs.getInt("y", (sh * 0.62f).toInt())
        }
        val v = TriggerView(service) { launch(service) }
        v.attachDrag(wm, params, prefs)
        try { wm.addView(v, params); view = v; lp = params } catch (_: Throwable) { view = null }
    }

    fun hide(service: AccessibilityService) = main.post {
        val v = view ?: return@post
        try { (service.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Throwable) {}
        view = null; lp = null
    }

    fun setVisible(visible: Boolean) = main.post { view?.visibility = if (visible) View.VISIBLE else View.GONE }

    /** Start the arrive-on-top voice screen. Our overlay is a visible window, so this is allowed from the background. */
    fun launch(ctx: Context) {
        try {
            ctx.startActivity(Intent(ctx, VoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {}
    }

    private fun screenSize(wm: WindowManager, ctx: Context): Pair<Int, Int> =
        if (android.os.Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.let { it.width() to it.height() }
        else ctx.resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    /** Paints the mark; a strong outline keeps it visible on any app background. */
    @SuppressLint("ViewConstructor")
    private class TriggerView(ctx: Context, private val onTap: () -> Unit) : View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = Color.WHITE }
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 0, 0, 0) }
        // The Tɛkyerɛma logo, drawn clipped to the disc (replaces the old hand-drawn ear+pulse mark).
        private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true; isDither = true }
        private val logoBitmap: android.graphics.Bitmap? =
            try { android.graphics.BitmapFactory.decodeResource(ctx.resources, com.tekyerema.R.drawable.tk_logo) } catch (_: Throwable) { null }
        private val clip = Path(); private val dst = android.graphics.RectF()

        init {
            contentDescription = "Talk to Tɛkyerɛma"
            isClickable = true
            isFocusable = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = "android.widget.Button"
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, "Talk to Tɛkyerɛma"))
        }

        override fun performClick(): Boolean { super.performClick(); onTap(); return true }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val cx = w / 2f; val cy = h / 2f
            val disc = 30f * d                                       // 60 dp disc
            // Dark disc base — matches the logo's backdrop and shows if the bitmap can't be decoded.
            fill.shader = LinearGradient(cx - disc, cy - disc, cx + disc, cy + disc,
                Color.parseColor("#111F45"), Color.parseColor("#1B49C8"), Shader.TileMode.CLAMP)
        }

        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f; val r = 30f * d
            c.drawCircle(cx, cy + 2 * d, r + 1.5f * d, shadow)
            c.drawCircle(cx, cy, r, fill)                            // dark disc base / fallback
            logoBitmap?.let { bmp ->
                val save = c.save()
                clip.reset(); clip.addCircle(cx, cy, r, Path.Direction.CW); c.clipPath(clip)
                dst.set(cx - r, cy - r, cx + r, cy + r)              // square dest = the disc bounds
                c.drawBitmap(bmp, null, dst, logoPaint)
                c.restoreToCount(save)
            }
            c.drawCircle(cx, cy, r - outline.strokeWidth / 2, outline) // white ring on top
        }

        /** Tap = launch; drag = move, then snap to the nearest edge and remember the spot. */
        @SuppressLint("ClickableViewAccessibility")
        fun attachDrag(wm: WindowManager, lp: WindowManager.LayoutParams, prefs: android.content.SharedPreferences) {
            val slop = ViewConfiguration.get(context).scaledTouchSlop
            var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false; true }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX; val dy = e.rawY - downY
                        if (moved || abs(dx) > slop || abs(dy) > slop) {
                            moved = true
                            lp.x = (startX + dx).toInt(); lp.y = (startY + dy).toInt()
                            try { wm.updateViewLayout(this, lp) } catch (_: Throwable) {}
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!moved && e.actionMasked == MotionEvent.ACTION_UP) performClick()
                        else {
                            val (sw, sh) = screenSize(wm, context)
                            lp.x = if (lp.x + width / 2 < sw / 2) 0 else sw - width
                            lp.y = lp.y.coerceIn(0, sh - height)
                            try { wm.updateViewLayout(this, lp) } catch (_: Throwable) {}
                            prefs.edit().putInt("x", lp.x).putInt("y", lp.y).apply()
                        }
                        true
                    }
                    else -> false
                }
            }
        }
    }
}
