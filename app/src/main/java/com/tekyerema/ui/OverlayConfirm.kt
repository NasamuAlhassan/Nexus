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
import com.tekyerema.ui.theme.TkHaptic
import com.tekyerema.ui.theme.tkHaptic
import com.tekyerema.ui.voice.Follower
import com.tekyerema.ui.voice.StrobeGuard
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
    private const val ATTENTION_EDGE = "#59F5C26B"   // 35 % amber
    @Volatile private var calm = false

    @Volatile private var kind = PromptSurface.Kind.CONFIRM
    /** The panel on screen was put there by the agent's state (so a state change may take it down). */
    @Volatile private var byEngine = false

    fun canDraw(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    /** A panel can be shown at all: the accessibility service is connected, or overlays are allowed. */
    fun canShow(ctx: Context): Boolean = TekyeremaAccessibilityService.live != null || canDraw(ctx)

    /** The panel for what the task is waiting on now. */
    fun showPrompt(ctx: Context, p: PromptSurface.Prompt) = render(ctx, p.text, p.kind, engine = true, seq = p.seq)

    /** The task stopped waiting: take down the panel it put up (not a card someone else showed). */
    fun hidePrompt(ctx: Context) { main.post { if (byEngine) hideNow() } }

    /** A card for a question at the end of a reply (the task is over; the answer starts a new turn). */
    fun showQuestion(ctx: Context, question: String) = render(ctx, question, PromptSurface.Kind.QUESTION, engine = false)

    fun show(ctx: Context, prompt: String, asking: Boolean = false) =
        render(ctx, prompt, if (asking) PromptSurface.Kind.QUESTION else PromptSurface.Kind.CONFIRM, engine = false)

    /** @param seq the confirmation this panel's Yes answers (J-02); a panel not drawn for a pending confirmation
     *  ([show], the debug hook) gets [ConfirmGate.NO_CONFIRMATION], so its Yes can never approve anything. */
    private fun render(ctx: Context, prompt: String, kind: PromptSurface.Kind, engine: Boolean,
                       seq: Long = com.tekyerema.policy.ConfirmGate.NO_CONFIRMATION) {
        main.post {
            hideNow()
            // J-02 tapjacking guard: the Yes only counts once the panel has been up for a moment (ConfirmGate.armed).
            val shownAt = android.os.SystemClock.uptimeMillis()
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
            // Reduce motion (in the app) or "Remove animations" (the phone): the panel fades in and holds still.
            calm = com.tekyerema.settings.Settings(ctx).reduceMotion || com.tekyerema.ui.theme.systemAnimatorScale(ctx) <= 0f

            val card = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(if (secure) 12 else 18), dp(20), dp(14))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(INK2))
                    cornerRadius = dp(28).toFloat()
                    // A faint amber edge ties the panel to "needs your OK" (the colour the living visual turns).
                    setStroke(dp(1), Color.parseColor(ATTENTION_EDGE))
                }
                contentDescription = when (kind) {
                    PromptSurface.Kind.CONFIRM -> "Tɛkyerɛma needs your OK. $prompt. ${Confirmation.LISTEN_HINT}."
                    PromptSurface.Kind.QUESTION -> "Tɛkyerɛma is asking. $prompt. Say your answer, or double-tap Answer. Say stop to cancel."
                    PromptSurface.Kind.SECURE -> "Secure step. $prompt. When you've finished, say done or double-tap Done."
                }
            }

            // Top row: the small presence + the question.
            val row = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val mini = MiniPresenceView(host, calm)
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
            fun btn(label: String, description: String, filled: Boolean, hides: Boolean, guarded: Boolean = false,
                    haptic: TkHaptic = TkHaptic.TAP, onClick: () -> Unit) = Button(host).apply {
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
                // A press squashes the button a little and it springs back on release (calm: no movement). Returns
                // false, so the Button's own click and accessibility handling are untouched.
                if (!calm) setOnTouchListener { v, e ->
                    when (e.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN ->
                            v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90L).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                            v.animate().scaleX(1f).scaleY(1f).setDuration(260L).setInterpolator(android.view.animation.OvershootInterpolator(2.5f)).start()
                    }
                    false
                }
                setOnClickListener {
                    // A guarded (approving) button ignores a tap that lands before the panel has been up for a moment.
                    if (guarded && !com.tekyerema.policy.ConfirmGate.armed(shownAt, android.os.SystemClock.uptimeMillis())) return@setOnClickListener
                    it.tkHaptic(haptic)
                    onClick(); if (hides) hideNow()
                }
            }
            val (left, right) = PromptSurface.buttons(kind)
            val leftBtn = when (kind) {
                PromptSurface.Kind.CONFIRM -> btn(left, "No, don't do it", filled = false, hides = true, haptic = TkHaptic.REJECT) { AgentEngine.confirm(false, seq) }
                else -> btn(left, "Stop the task", filled = false, hides = true, haptic = TkHaptic.REJECT) { VoiceEngine.cancel() }
            }
            val rightBtn = when (kind) {
                PromptSurface.Kind.CONFIRM -> btn(right, "Yes, do it", filled = true, hides = true, guarded = true, haptic = TkHaptic.CONFIRM) { AgentEngine.confirm(true, seq) }
                // Reopens the microphone for this question; the panel stays until it is answered.
                PromptSurface.Kind.QUESTION -> btn(right, "Answer by voice", filled = true, hides = false) { VoiceEngine.respond() }
                PromptSurface.Kind.SECURE -> btn(right, "Done — I've finished the secure step", filled = true, hides = true, haptic = TkHaptic.CONFIRM) { AgentEngine.secureInputDone() }
            }
            buttons.addView(leftBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
            // Type: the answer by keyboard, on the panel itself (owner, 2 October 2026: "when it needs feedback, you
            // can't type" — the only text box was on Tɛkyerɛma's own screen, which is not in front while a task runs).
            // Never at the secure step: a box there could be mistaken for where the PIN goes.
            var startTyping: () -> Unit = {}
            if (PromptSurface.canType(kind)) buttons.addView(
                btn("Type", "Type your answer instead of saying it", filled = false, hides = false) { startTyping() },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f).apply { marginStart = dp(2); marginEnd = dp(2) })
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
            // Entrance: the card grows into place from 90 % and settles with a slight overshoot (scale, not a slide:
            // the window is exactly the card's size, so a slide would be clipped at its edge). The secure step grows
            // from its top edge, out of the way of a PIN pad. 380 ms — well inside the Yes button's 600 ms arming
            // delay (ConfirmGate), so the button is still by the time a tap can count. Alpha starts just above 0: a
            // view at alpha 0 is not "visible to user", and TalkBack could skip the panel's first moment.
            card.alpha = 0.02f
            if (!calm) {
                card.scaleX = 0.9f; card.scaleY = 0.9f
                if (secure) card.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.pivotY = 0f }
            }
            startTyping = typing@{
                if (view !== wrapper) return@typing
                VoiceEngine.quietForTyping()     // stop reading, close the answer microphone; the prompt stays waiting
                buttons.visibility = View.GONE
                val field = android.widget.EditText(host).apply {
                    hint = if (kind == PromptSurface.Kind.CONFIRM) "Type yes, no, or what to change" else "Type your answer"
                    contentDescription = hint
                    setTextColor(Color.parseColor(TEXT)); setHintTextColor(Color.parseColor(TEXT2))
                    textSize = 17f
                    isSingleLine = true
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                    minHeight = dp(48); minimumHeight = dp(48)
                    setPadding(dp(14), 0, dp(14), 0)
                    background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(Color.TRANSPARENT); setStroke(dp(1), Color.parseColor(INK3)) }
                }
                val imm = host.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                fun submit() {
                    val text = field.text?.toString().orEmpty().trim()
                    if (text.isEmpty()) return
                    val r = VoiceEngine.submitTyped(text)
                    if (r.accepted) {
                        try { imm?.hideSoftInputFromWindow(field.windowToken, 0) } catch (_: Throwable) {}
                        field.isEnabled = false
                        if (r.notice.isNotBlank()) setCaption(r.notice)
                    } else {
                        // Not an answer this prompt can take ("Type yes or no — or use the buttons"): say why, keep the box.
                        setCaption(r.notice.ifBlank { "I couldn't use that — try again" })
                        wrapper.announceForAccessibility(r.notice.ifBlank { "I couldn't use that — try again" })
                    }
                }
                field.setOnEditorActionListener { _, action, _ ->
                    if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEND || action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { submit(); true } else false
                }
                val send = btn("Send", "Send what you typed", filled = true, hides = false, haptic = TkHaptic.CONFIRM) { submit() }
                val typeRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0) }
                typeRow.addView(field, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
                typeRow.addView(send, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                card.addView(typeRow)
                // Back closes the box and brings the buttons back (the panel is focusable only while it is typed into).
                field.setOnKeyListener { _, code, ev ->
                    if (code == android.view.KeyEvent.KEYCODE_BACK && ev.action == android.view.KeyEvent.ACTION_UP) {
                        try { imm?.hideSoftInputFromWindow(field.windowToken, 0) } catch (_: Throwable) {}
                        card.removeView(typeRow); buttons.visibility = View.VISIBLE
                        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        lp.gravity = Gravity.CENTER; lp.y = 0
                        try { wm.updateViewLayout(wrapper, lp) } catch (_: Throwable) {}
                        true
                    } else false
                }
                // Focusable now (so the keyboard can attach), and at the TOP: a centred card sits under the keyboard on a
                // small screen.
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                lp.gravity = Gravity.TOP; lp.y = dp(36)
                try { wm.updateViewLayout(wrapper, lp) } catch (_: Throwable) {}
                setCaption(if (kind == PromptSurface.Kind.CONFIRM) "Type yes, no, or what to change" else "Type your answer")
                field.requestFocus()
                field.postDelayed({ try { imm?.showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) } catch (_: Throwable) {} }, 120L)
            }
            try {
                wm.addView(wrapper, lp); view = wrapper; wmUsed = wm; presence = mini; caption = cap
                card.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(if (calm) 150L else 380L)
                    .setInterpolator(if (calm) android.view.animation.DecelerateInterpolator() else android.view.animation.OvershootInterpolator(1.1f))
                    .start()
                bindToVoice()
                // TalkBack users hear the panel arrive; with replies read aloud the question is already being spoken.
                if (!com.tekyerema.settings.Settings(ctx).speakAloud) wrapper.announceForAccessibility(card.contentDescription)
            } catch (t: Throwable) { view = null; wmUsed = null }
        }
    }

    private fun captionFor(kind: PromptSurface.Kind, listening: Boolean): String = when (kind) {
        PromptSurface.Kind.CONFIRM -> Confirmation.LISTEN_HINT
        // The way out is taught where it's needed (Abena study, 3 October 2026: "say … bye bye" in its dialogs) — both
        // words work in either language (voice/ControlVerbs).
        PromptSurface.Kind.QUESTION -> if (listening) "Listening… say “stop” or “gyae” to cancel" else "Tap Answer, or say “Hey Tɛkyerɛma”"
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
                        setCaption(when (phase) {
                            ConfirmPhase.ASKING -> "Asking…"
                            ConfirmPhase.LISTENING -> if (v.speechDetected) "Listening…" else Confirmation.LISTEN_HINT
                            ConfirmPhase.HEARD -> "Got it"
                            ConfirmPhase.NONE -> Confirmation.LISTEN_HINT
                        })
                    }
                    else -> {
                        val listening = VoiceEngine.isRunning && !VoiceEngine.canRespond
                        presence?.phase = if (listening) ConfirmPhase.LISTENING else ConfirmPhase.ASKING
                        setCaption(captionFor(kind, listening))
                    }
                }
            }
        }
        s.launch { VoiceEngine.level.collect { presence?.level = it } }
    }

    /** A new caption fades up instead of swapping in a single frame (calm: it just changes). */
    private fun setCaption(text: String) {
        val c = caption ?: return
        if (c.text?.toString() == text) return
        c.text = text
        if (!calm) { c.alpha = 0.25f; c.animate().alpha(1f).setDuration(200L).start() }
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
     * A 64 dp echo of the living visual in amber, on the same spring physics ([com.tekyerema.ui.voice.Spring]):
     * a soft amber glow beating slowly (0.5 Hz — "waiting for you"), three rings that turn while asking, open and
     * ripple with the microphone (inner ring first) while listening, and close with a small ring of light when the
     * answer is heard. Calm motion: the same shapes, held still.
     */
    private class MiniPresenceView(ctx: Context, private val calm: Boolean) : View(ctx) {
        var phase: ConfirmPhase = ConfirmPhase.ASKING
            set(v) {
                if (v != field && v == ConfirmPhase.HEARD) heardAt = t
                field = v; openS.target = openFor(v); invalidate()
            }
        var level: Float = 0f
            set(v) { field = v; if (calm) return; invalidate() }

        private fun openFor(p: ConfirmPhase) = when (p) { ConfirmPhase.LISTENING -> 0.45f; ConfirmPhase.HEARD -> 0f; else -> 0.18f }

        private var t = 0f
        private var lastNs = 0L
        private var heardAt = -10f
        private val angles = floatArrayOf(0f, 61f, 122f)
        private val openS = com.tekyerema.ui.voice.Spring(openFor(ConfirmPhase.ASKING), 160f, 0.72f)
        private val rings = arrayOf(com.tekyerema.ui.voice.Spring(0f, 520f, 0.45f), com.tekyerema.ui.voice.Spring(0f, 330f, 0.45f),
            com.tekyerema.ui.voice.Spring(0f, 200f, 0.45f))
        private val voice = Follower(0.03f, 0.18f)
        private val voiceGlow = Follower(StrobeGuard.GLOW_ATTACK_SEC, StrobeGuard.GLOW_RELEASE_SEC)
        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4000L; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { tick() }
        }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val threadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val rect = RectF()
        private val thread = Path()
        private val amber = Color.parseColor("#F5C26B")

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); if (!calm) animator.start() }
        override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            // Gradients are built once per size and reused every frame.
            val cx = w / 2f; val cy = h / 2f
            val glowR = minOf(w, h) * 0.49f
            glowPaint.shader = android.graphics.RadialGradient(cx, cy, glowR,
                intArrayOf(Color.argb(110, 245, 194, 107), Color.argb(40, 245, 194, 107), Color.TRANSPARENT),
                floatArrayOf(0f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP)
            val r = minOf(w, h) * 0.36f
            val tw = r * 2.6f; val x0 = cx - tw / 2f
            threadPaint.shader = android.graphics.LinearGradient(x0, 0f, x0 + tw, 0f,
                intArrayOf(Color.TRANSPARENT, amber, amber, Color.TRANSPARENT), floatArrayOf(0f, 0.2f, 0.8f, 1f),
                android.graphics.Shader.TileMode.CLAMP)
        }

        private fun tick() {
            val now = System.nanoTime()
            val dt = if (lastNs == 0L) 0f else ((now - lastNs) / 1e9f).coerceAtMost(0.05f)
            lastNs = now
            t += dt
            val drive = if (phase == ConfirmPhase.LISTENING) level.coerceIn(0f, 1f) else 0f
            voice.step(drive, dt); voiceGlow.step(drive, dt)
            openS.step(dt)
            rings[0].target = voice.value
            for (i in 1 until rings.size) rings[i].target = rings[i - 1].value
            for (s in rings) s.step(dt)
            val speed = if (phase == ConfirmPhase.LISTENING) 34f else 22f
            for (i in angles.indices) angles[i] = (angles[i] + (if (i % 2 == 0) 1f else -1f) * speed * dt) % 360f
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density
            val cx = width / 2f; val cy = height / 2f
            val r = minOf(width, height) * 0.36f
            val open = if (calm) openFor(phase) else openS.value.coerceIn(0f, 1f)
            val heard = if (calm) 0f else (1f - (t - heardAt) / 0.7f).coerceIn(0f, 1f)
            val twoPi = 2f * PI.toFloat()
            // glow: a slow heartbeat, brighter (strobe-guarded) with the voice and when the answer lands
            val beat = if (calm) 0.5f else 0.5f + 0.5f * sin(twoPi * StrobeGuard.CONFIRM_BEAT_HZ * t)
            glowPaint.alpha = (150 + 50 * beat + 55 * voiceGlow.value + 60 * heard).toInt().coerceIn(0, 255)
            c.drawCircle(cx, cy, minOf(width, height) * 0.49f, glowPaint)
            // thread, faded at both ends
            threadPaint.strokeWidth = 1.6f * d; threadPaint.alpha = 210
            thread.reset()
            val w = r * 2.6f; val x0 = cx - w / 2
            val lvl = voice.value
            for (j in 0..40) {
                val u = j / 40f; val x = x0 + u * w
                val win = sin(PI.toFloat() * u)
                val y = cy + win * (r * 0.45f * lvl) * sin(twoPi * (u * 5f - t * 2.5f)) + d * 0.6f * sin(twoPi * (u + t / 6f))
                if (j == 0) thread.moveTo(x, y) else thread.lineTo(x, y)
            }
            c.drawPath(thread, threadPaint)
            // rings (index 0 = outermost), each pushed out by its link of the voice chain
            val factors = floatArrayOf(1.0f, 0.82f, 0.64f)
            val widths = floatArrayOf(1.6f, 2.4f, 3.6f)
            val alphas = intArrayOf(110, 170, 240)
            for (i in factors.indices) {
                val chain = rings[2 - i].value
                val rr = r * factors[i] * (1f + chain * 0.10f + 0.03f * heard)
                val sweep = (360f * (1f - open * (0.45f + 0.15f * (2 - i)))).coerceIn(0f, 360f)
                paint.strokeWidth = widths[i] * d; paint.color = amber; paint.alpha = (alphas[i] + 15 * heard).toInt().coerceAtMost(255)
                rect.set(cx - rr, cy - rr, cx + rr, cy + rr)
                c.drawArc(rect, angles[i] + i * 61f, sweep, false, paint)
            }
            // heard: a ring of light released outward
            if (heard > 0f) {
                val e = 1f - heard
                paint.strokeWidth = 1.5f * d; paint.color = Color.WHITE; paint.alpha = (200 * heard * heard).toInt()
                val rr = r * (0.7f + 0.65f * (1f - (1f - e) * (1f - e)))
                c.drawCircle(cx, cy, rr, paint)
            }
        }
    }
}
