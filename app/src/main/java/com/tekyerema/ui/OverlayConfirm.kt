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
import com.tekyerema.accessibility.TekyeremaAccessibilityService
import com.tekyerema.agent.AgentEngine
import com.tekyerema.voice.ConfirmPhase
import com.tekyerema.voice.Confirmation
import com.tekyerema.voice.PromptSurface
import com.tekyerema.voice.VoiceEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.sin

/**
 * The reply panel drawn OVER whatever app is in front while a task waits on the user: a confirmation (Yes / No), a
 * question (Answer / Stop) or the secure step (Done / Stop). Voice first — the question is spoken and the mic listens
 * ([com.tekyerema.voice.VoiceController]) — and this panel is what makes sure there is always something to answer on
 * (owner, 2026-09-28: "when it needs feedback, sometimes the thing doesn't appear for you to talk back").
 *
 * It follows the agent's state ([showPrompt]/[hidePrompt], driven from VoiceEngine via
 * [com.tekyerema.voice.PromptSurface]). While the accessibility service is connected it is a
 * TYPE_ACCESSIBILITY_OVERLAY: no "display over other apps" permission (onboarding lets the user skip it, and the card
 * then silently never appeared), and Settings/permission screens don't hide it. Without the service it falls back to
 * an application overlay when that permission is granted. The buttons and a spoken answer both go through
 * [AgentEngine.confirm] — the same safety gate. [hideAndWait] removes it before an approved action runs, so a tap
 * never lands on the panel instead of the app.
 */
object OverlayConfirm {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var view: View? = null
    @Volatile private var wmUsed: WindowManager? = null
    private var presence: MiniPresenceView? = null
    private var caption: TextView? = null
    private var scope: CoroutineScope? = null

    private const val INK2 = "#0F1628"
    private const val INK3 = "#2A3558"
    private const val TEXT = "#F4F6FB"
    private const val TEXT2 = "#9AA6C4"
    private const val ATTENTION = "#F5C26B"

    @Volatile private var kind = PromptSurface.Kind.CONFIRM
    /** The panel on screen was put there by the agent's state (so a state change may take it down). */
    @Volatile private var byEngine = false

    fun canDraw(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    /** A panel can be shown at all: the accessibility service is connected, or overlays are allowed. */
    fun canShow(ctx: Context): Boolean = TekyeremaAccessibilityService.live != null || canDraw(ctx)

    /** The panel for what the task is waiting on now. */
    fun showPrompt(ctx: Context, p: PromptSurface.Prompt) = render(ctx, p.text, p.kind, engine = true)

    /** The task stopped waiting: take down the panel it put up (not a card someone else showed). */
    fun hidePrompt(ctx: Context) { main.post { if (byEngine) hideNow() } }

    /** A card for a question at the end of a reply (the task is over; the answer starts a new turn). */
    fun showQuestion(ctx: Context, question: String) = render(ctx, question, PromptSurface.Kind.QUESTION, engine = false)

    fun show(ctx: Context, prompt: String, asking: Boolean = false) =
        render(ctx, prompt, if (asking) PromptSurface.Kind.QUESTION else PromptSurface.Kind.CONFIRM, engine = false)

    private fun render(ctx: Context, prompt: String, kind: PromptSurface.Kind, engine: Boolean) {
        main.post {
            hideNow()
            val svc = TekyeremaAccessibilityService.live
            if (svc == null && !canDraw(ctx)) return@post
            val host: Context = svc ?: ctx
            this.kind = kind
            this.byEngine = engine
            val wm = host.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val d = host.resources.displayMetrics.density
            fun dp(v: Int) = (v * d).toInt()
            val header = PromptSurface.header(kind)
            val secure = kind == PromptSurface.Kind.SECURE

            val card = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(if (secure) 12 else 18), dp(20), dp(14))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(INK2))
                    cornerRadius = dp(28).toFloat()
                    setStroke(dp(1), Color.parseColor(INK3))
                }
                contentDescription = when (kind) {
                    PromptSurface.Kind.CONFIRM -> "Tɛkyerɛma needs your OK. $prompt. ${Confirmation.LISTEN_HINT}."
                    PromptSurface.Kind.QUESTION -> "Tɛkyerɛma is asking. $prompt. Say your answer, or double-tap Answer."
                    PromptSurface.Kind.SECURE -> "Secure step. $prompt. When you've finished, say done or double-tap Done."
                }
            }

            // Top row: the small presence + the question.
            val row = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val mini = MiniPresenceView(host)
            val miniSize = if (secure) 40 else 64
            row.addView(mini, LinearLayout.LayoutParams(dp(miniSize), dp(miniSize)).apply { marginEnd = dp(14) })
            val texts = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(host).apply {
                text = header
                setTextColor(Color.parseColor(ATTENTION))
                typeface = Typeface.DEFAULT_BOLD
                textSize = 11f
                letterSpacing = 0.12f
            })
            texts.addView(TextView(host).apply {
                text = prompt
                setTextColor(Color.parseColor(TEXT))
                textSize = if (secure) 15f else 18f
                setLineSpacing(0f, 1.12f)
                setPadding(0, dp(4), 0, 0)
            })
            val cap = TextView(host).apply {
                text = captionFor(kind, listening = false)
                setTextColor(Color.parseColor(ATTENTION))
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(6), 0, 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            texts.addView(cap)
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(row)

            // The buttons: a parallel path to the voice (a helper, TalkBack, a noisy room), always there to answer on.
            val buttons = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
            fun btn(label: String, description: String, filled: Boolean, hides: Boolean, onClick: () -> Unit) = Button(host).apply {
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
                setOnClickListener { onClick(); if (hides) hideNow() }
            }
            val (left, right) = PromptSurface.buttons(kind)
            val leftBtn = when (kind) {
                PromptSurface.Kind.CONFIRM -> btn(left, "No, don't do it", filled = false, hides = true) { AgentEngine.confirm(false) }
                else -> btn(left, "Stop the task", filled = false, hides = true) { VoiceEngine.cancel() }
            }
            val rightBtn = when (kind) {
                PromptSurface.Kind.CONFIRM -> btn(right, "Yes, do it", filled = true, hides = true) { AgentEngine.confirm(true) }
                // Reopens the microphone for this question; the panel stays until it is answered.
                PromptSurface.Kind.QUESTION -> btn(right, "Answer by voice", filled = true, hides = false) { VoiceEngine.respond() }
                PromptSurface.Kind.SECURE -> btn(right, "Done — I've finished the secure step", filled = true, hides = true) { AgentEngine.secureInputDone() }
            }
            buttons.addView(leftBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
            buttons.addView(rightBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f).apply { marginStart = dp(6) })
            card.addView(buttons)

            val wrapper = LinearLayout(host).apply {
                setPadding(dp(12), 0, dp(12), 0)
                addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                if (Build.VERSION.SDK_INT >= 28) accessibilityPaneTitle = header.lowercase().replaceFirstChar { it.uppercase() }
            }

            val type = when {
                svc != null -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                Build.VERSION.SDK_INT >= 26 -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else -> @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                // NOT_FOCUSABLE keeps the buttons touchable without stealing IME focus (and never makes the panel the
                // active window the agent observes); LAYOUT_IN_SCREEN so our position is absolute (not inset by the
                // IME). A confirmation/question is CENTRED: a bottom card hid behind the soft keyboard while a message
                // was being composed (the Telegram confirm bug). The secure step sits at the TOP, clear of a PIN keypad.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = if (secure) Gravity.TOP else Gravity.CENTER
                if (secure) y = dp(36)
            }
            try {
                wm.addView(wrapper, lp); view = wrapper; wmUsed = wm; presence = mini; caption = cap
                bindToVoice()
                // TalkBack users hear the panel arrive; with replies read aloud the question is already being spoken.
                if (!com.tekyerema.settings.Settings(ctx).speakAloud) wrapper.announceForAccessibility(card.contentDescription)
            } catch (t: Throwable) { view = null; wmUsed = null }
        }
    }

    private fun captionFor(kind: PromptSurface.Kind, listening: Boolean): String = when (kind) {
        PromptSurface.Kind.CONFIRM -> Confirmation.LISTEN_HINT
        PromptSurface.Kind.QUESTION -> if (listening) "Listening for your answer…" else "Tap Answer, or say “Hey Tɛkyerɛma”"
        PromptSurface.Kind.SECURE -> "Say “done”, or tap Done, when you've finished"
    }

    /** Follow the voice phases so the panel reads truthfully. */
    private fun bindToVoice() {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        s.launch {
            VoiceEngine.state.collect { v ->
                when (kind) {
                    PromptSurface.Kind.CONFIRM -> {
                        val phase = v.confirmPhase
                        presence?.phase = phase
                        caption?.text = when (phase) {
                            ConfirmPhase.ASKING -> "Asking…"
                            ConfirmPhase.LISTENING -> if (v.speechDetected) "Listening…" else Confirmation.LISTEN_HINT
                            ConfirmPhase.HEARD -> "Got it"
                            ConfirmPhase.NONE -> Confirmation.LISTEN_HINT
                        }
                    }
                    else -> {
                        val listening = VoiceEngine.isRunning && !VoiceEngine.canRespond
                        presence?.phase = if (listening) ConfirmPhase.LISTENING else ConfirmPhase.ASKING
                        caption?.text = captionFor(kind, listening)
                    }
                }
            }
        }
        s.launch { VoiceEngine.level.collect { presence?.level = it } }
    }

    fun hide(ctx: Context) { main.post { hideNow() } }

    /** Remove the panel and wait until it is gone — before an approved action runs, so its tap lands on the app. */
    suspend fun hideAndWait(ctx: Context) {
        val gone = CompletableDeferred<Unit>()
        main.post { hideNow(); gone.complete(Unit) }
        withTimeoutOrNull(1_000) { gone.await() }
        delay(60)   // the window manager removes the window just after removeView returns
    }

    private fun hideNow() {
        scope?.cancel(); scope = null
        presence = null; caption = null
        byEngine = false
        val v = view ?: return
        try { wmUsed?.removeView(v) } catch (t: Throwable) {}
        view = null; wmUsed = null
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
