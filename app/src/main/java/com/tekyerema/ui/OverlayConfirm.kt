package com.tekyerema.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.tekyerema.agent.AgentEngine
import com.tekyerema.voice.ConfirmPhase
import com.tekyerema.voice.Confirmation
import com.tekyerema.voice.VoiceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * The confirmation card drawn OVER whatever app the agent is operating. Voice first: the engine
 * asks the question aloud and listens for yes/no ([com.tekyerema.voice.VoiceController]); this
 * card is what a sighted or low-vision user sees meanwhile — a small living presence in amber,
 * the question, and a caption that follows the phases ("Say yes or no" / "Listening…").
 *
 * The two buttons are a quiet parallel path (a helper, TalkBack, a noisy room). Both they and the
 * spoken answer call [AgentEngine.confirm] — the same safety gate. If the "display over other
 * apps" permission isn't granted, show() no-ops and Home carries the same experience.
 */
object OverlayConfirm {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var view: View? = null
    private var presence: MiniPresenceView? = null
    private var caption: TextView? = null
    private var scope: CoroutineScope? = null

    private const val INK2 = "#0F1628"
    private const val INK3 = "#2A3558"
    private const val TEXT = "#F4F6FB"
    private const val TEXT2 = "#9AA6C4"
    private const val ATTENTION = "#F5C26B"

    @Volatile private var asking = false

    fun canDraw(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    /** A visible card while the agent is ASKING the user a question (needs a spoken answer, not yes/no) —
     *  e.g. "What's Prince's number?". Mirrors the confirmation card so the user always SEES that Tɛkyerɛma
     *  is waiting for them, even when the agent handed off to another app. */
    fun showQuestion(ctx: Context, question: String) = show(ctx, question, asking = true)

    fun show(ctx: Context, prompt: String, asking: Boolean = false) {
        if (!canDraw(ctx)) return
        this.asking = asking
        main.post {
            hideNow(ctx)
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val d = ctx.resources.displayMetrics.density
            fun dp(v: Int) = (v * d).toInt()

            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(18), dp(20), dp(14))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(INK2))
                    cornerRadius = dp(28).toFloat()
                    setStroke(dp(1), Color.parseColor(INK3))
                }
                contentDescription = if (asking) "Tɛkyerɛma is asking. $prompt. Please answer." else "Tɛkyerɛma needs your OK. $prompt. ${Confirmation.LISTEN_HINT}."
            }

            // Top row: the small presence + the question.
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val mini = MiniPresenceView(ctx)
            row.addView(mini, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(14) })
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(ctx).apply {
                text = if (asking) "TƐKYERƐMA IS ASKING" else "NEEDS YOUR OK"
                setTextColor(Color.parseColor(ATTENTION))
                typeface = Typeface.DEFAULT_BOLD
                textSize = 11f
                letterSpacing = 0.12f
            })
            texts.addView(TextView(ctx).apply {
                text = prompt
                setTextColor(Color.parseColor(TEXT))
                textSize = 18f
                setLineSpacing(0f, 1.12f)
                setPadding(0, dp(4), 0, 0)
            })
            val cap = TextView(ctx).apply {
                text = if (asking) "Listening for your answer…" else Confirmation.LISTEN_HINT
                setTextColor(Color.parseColor(ATTENTION))
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(6), 0, 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            texts.addView(cap)
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(row)

            // Quiet fallback buttons.
            val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
            fun btn(label: String, description: String, filled: Boolean, onClick: () -> Unit) = Button(ctx).apply {
                text = label
                contentDescription = description
                isAllCaps = false
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(if (filled) "#070A15" else TEXT2))
                minHeight = dp(48); minimumHeight = dp(48)
                background = GradientDrawable().apply {
                    cornerRadius = dp(24).toFloat()
                    if (filled) setColor(Color.parseColor(TEXT)) else { setColor(Color.TRANSPARENT); setStroke(dp(1), Color.parseColor(INK3)) }
                }
                setOnClickListener { onClick(); hideNow(ctx) }
            }
            if (asking) {
                // A question needs a spoken answer, not yes/no — just offer a way to stop.
                buttons.addView(btn("Cancel", "Cancel", filled = false) { VoiceEngine.cancel() },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            } else {
                buttons.addView(btn("No", "No, don't do it", filled = false) { AgentEngine.confirm(false) },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
                buttons.addView(btn("Yes, do it", "Yes, do it", filled = true) { AgentEngine.confirm(true) },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f).apply { marginStart = dp(6) })
            }
            card.addView(buttons)

            val wrapper = LinearLayout(ctx).apply {
                setPadding(dp(12), 0, dp(12), 0)
                addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }

            val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                // NOT_FOCUSABLE keeps the buttons touchable without stealing IME focus; LAYOUT_IN_SCREEN so
                // our position is absolute (not inset by the IME). We CENTER the card: a bottom card is hidden
                // behind the soft keyboard while a message is being composed (the Telegram confirm bug — the
                // confirmation DID fire but the card sat under the keyboard), whereas a centred card is always
                // visible above the app and clear of the keyboard.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.CENTER }
            try {
                wm.addView(wrapper, lp); view = wrapper; presence = mini; caption = cap
                bindToVoice()
            } catch (t: Throwable) { view = null }
        }
    }

    /** Follow the voice confirmation phases so the card reads truthfully. */
    private fun bindToVoice() {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        s.launch {
            VoiceEngine.state.collect { v ->
                val phase = v.confirmPhase
                presence?.phase = if (asking) ConfirmPhase.LISTENING else phase
                if (asking) { caption?.text = "Listening for your answer…"; return@collect }
                caption?.text = when (phase) {
                    ConfirmPhase.ASKING -> "Asking…"
                    ConfirmPhase.LISTENING -> if (v.speechDetected) "Listening…" else Confirmation.LISTEN_HINT
                    ConfirmPhase.HEARD -> "Got it"
                    ConfirmPhase.NONE -> Confirmation.LISTEN_HINT
                }
            }
        }
        s.launch { VoiceEngine.level.collect { presence?.level = it } }
    }

    fun hide(ctx: Context) { main.post { hideNow(ctx) } }

    private fun hideNow(ctx: Context) {
        scope?.cancel(); scope = null
        presence = null; caption = null
        val v = view ?: return
        try { (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (t: Throwable) {}
        view = null
    }

    /**
     * A 64 dp echo of the living visual in amber: three arcs that turn slowly while asking, open
     * and follow the microphone level while listening, and close when the answer is heard.
     */
    private class MiniPresenceView(ctx: Context) : View(ctx) {
        var phase: ConfirmPhase = ConfirmPhase.ASKING
            set(v) { field = v; invalidate() }
        var level: Float = 0f
            set(v) { field = v; invalidate() }
        private var t = 0f
        private val start = System.nanoTime()
        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4000L; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { t = (System.nanoTime() - start) / 1e9f; invalidate() }
        }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val rect = RectF()
        private val thread = Path()
        private val amber = Color.parseColor("#F5C26B")

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
        override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density
            val cx = width / 2f; val cy = height / 2f
            val r = minOf(width, height) * 0.36f
            val open = when (phase) { ConfirmPhase.LISTENING -> 0.45f; ConfirmPhase.HEARD -> 0f; else -> 0.18f }
            val lvl = if (phase == ConfirmPhase.LISTENING) level else 0f
            // thread
            paint.strokeWidth = 1.6f * d; paint.color = amber; paint.alpha = 200
            thread.reset()
            val w = r * 2.6f; val x0 = cx - w / 2
            for (j in 0..40) {
                val u = j / 40f; val x = x0 + u * w
                val win = sin(PI.toFloat() * u)
                val y = cy + win * (r * 0.45f * lvl) * sin(2f * PI.toFloat() * (u * 5f - t * 2.5f)) + d * 0.6f * sin(2f * PI.toFloat() * (u + t / 6f))
                if (j == 0) thread.moveTo(x, y) else thread.lineTo(x, y)
            }
            c.drawPath(thread, paint)
            // rings
            val factors = floatArrayOf(1.0f, 0.82f, 0.64f)
            val widths = floatArrayOf(1.6f, 2.4f, 3.6f)
            val alphas = intArrayOf(110, 170, 240)
            for (i in factors.indices) {
                val rr = r * factors[i] * (1f + lvl * 0.08f)
                val sweep = 360f * (1f - open * (0.45f + 0.15f * (2 - i)))
                val dir = if (i % 2 == 0) 1f else -1f
                val start = i * 61f + dir * t * 22f
                paint.strokeWidth = widths[i] * d; paint.color = amber; paint.alpha = alphas[i]
                rect.set(cx - rr, cy - rr, cx + rr, cy + rr)
                c.drawArc(rect, start, sweep, false, paint)
            }
        }
    }
}
