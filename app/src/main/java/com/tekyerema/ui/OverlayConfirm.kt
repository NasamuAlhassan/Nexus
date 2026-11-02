package com.tekyerema.ui

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.tekyerema.accessibility.TekyeremaAccessibilityService
import com.tekyerema.agent.AgentEngine
import com.tekyerema.ui.assistant.ChoiceGate
import com.tekyerema.ui.assistant.PromptPanel
import com.tekyerema.ui.theme.TekyeremaTheme
import com.tekyerema.voice.Confirmation
import com.tekyerema.voice.PromptSurface
import com.tekyerema.voice.VoiceEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The reply panel drawn OVER whatever app is in front while a task waits on the user: a confirmation (No / the verb), a
 * question (its choices, Stop / Type / Speak) or the secure step (Stop / Done). Voice first — the question is spoken and the
 * mic listens ([com.tekyerema.voice.VoiceController]) — and this panel is what makes sure there is always something to answer
 * on (owner, 2026-09-28: "when it needs feedback, sometimes the thing doesn't appear for you to talk back").
 *
 * It is the in-app surface's glass ([PromptPanel], DECISIONS V2-12): Compose in this window, on a lifecycle of its own.
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
    @Volatile private var owner: PanelOwner? = null
    /** True while the panel is really on screen: the in-app assistant surface then doesn't draw the same prompt under it
     *  (it sits in the same place, and TalkBack would read the prompt twice). False when it couldn't be drawn. */
    val onScreen = kotlinx.coroutines.flow.MutableStateFlow(false)

    @Volatile private var kind = PromptSurface.Kind.CONFIRM
    /** The panel on screen was put there by the agent's state (so a state change may take it down). */
    @Volatile private var byEngine = false

    fun canDraw(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    /** The status bar's height in pixels (a LAYOUT_IN_SCREEN window at the top must start below it). */
    private fun topInset(ctx: Context): Int {
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) ctx.resources.getDimensionPixelSize(id) else (24 * ctx.resources.displayMetrics.density).toInt()
    }

    /** A panel can be shown at all: the accessibility service is connected, or overlays are allowed. */
    fun canShow(ctx: Context): Boolean = TekyeremaAccessibilityService.live != null || canDraw(ctx)

    /** The panel for what the task is waiting on now — unless Tɛkyerɛma's own screen is in front, where the assistant surface
     *  shows the prompt itself (DECISIONS V2-4); then it is held and shown the moment our screen leaves. */
    fun showPrompt(ctx: Context, p: PromptSurface.Prompt) {
        val app = ctx.applicationContext
        main.post {
            if (appSurface) { held = p; heldCtx = app; if (byEngine) hideNow(); return@post }
            held = null
            shown = p
            renderNow(app, p.text, p.kind, engine = true, seq = p.seq)
        }
    }

    /** The task stopped waiting: take down the panel it put up (not a card someone else showed), and forget a held one. */
    fun hidePrompt(ctx: Context) { main.post { held = null; shown = null; if (byEngine) hideNow() } }

    /** True while Tɛkyerɛma's own screen is resumed with its assistant surface (set by the surface host). */
    @Volatile private var appSurface = false
    /** Tɛkyerɛma's own screen is in front (its surface shows replies and prompts itself). */
    fun appSurfaceShowing(): Boolean = appSurface
    /** The task's prompt while our surface shows it instead of the panel; null when none is waiting. */
    @Volatile private var held: PromptSurface.Prompt? = null
    @Volatile private var heldCtx: Context? = null
    /** The task prompt the panel is showing now (to hand over to the surface when our screen comes to the front). */
    @Volatile private var shown: PromptSurface.Prompt? = null

    /**
     * The surface host's lifecycle: [showing] = our screen is resumed (its surface shows the task's prompts), false = it has
     * left (paused, another tab, closed). Leaving brings a still-waiting prompt straight back as the panel, so a prompt is
     * never hidden from the user wherever they are.
     */
    fun setAppSurface(ctx: Context, showing: Boolean) {
        val app = ctx.applicationContext
        main.post {
            if (appSurface == showing) return@post
            appSurface = showing
            com.tekyerema.ui.island.IslandOverlay.appFront.value = showing   // our screen draws its own island
            if (showing) {
                if (view != null && byEngine) { held = shown; heldCtx = app; hideNow() }
            } else held?.let { p ->
                held = null; shown = p
                renderNow(heldCtx ?: app, p.text, p.kind, engine = true, seq = p.seq)
            }
        }
    }

    /** A card for a question at the end of a reply (the task is over; the answer starts a new turn). */
    fun showQuestion(ctx: Context, question: String) = render(ctx, question, PromptSurface.Kind.QUESTION, engine = false)

    fun show(ctx: Context, prompt: String, asking: Boolean = false) =
        render(ctx, prompt, if (asking) PromptSurface.Kind.QUESTION else PromptSurface.Kind.CONFIRM, engine = false)

    /** @param seq the confirmation this panel's Yes answers (J-02); a panel not drawn for a pending confirmation
     *  ([show], the debug hook) gets [ConfirmGate.NO_CONFIRMATION], so its Yes can never approve anything. */
    private fun render(ctx: Context, prompt: String, kind: PromptSurface.Kind, engine: Boolean,
                       seq: Long = com.tekyerema.policy.ConfirmGate.NO_CONFIRMATION) {
        main.post { renderNow(ctx, prompt, kind, engine, seq) }
    }

    /** [render]'s body, run now on the main thread: callers already on it ([showPrompt], [setAppSurface]) keep their order
     *  against [hidePrompt] — a posted render could otherwise draw a prompt the task had already resolved. */
    private fun renderNow(ctx: Context, prompt: String, kind: PromptSurface.Kind, engine: Boolean, seq: Long) {
        hideNow()
        val svc = TekyeremaAccessibilityService.live
        if (svc == null && !canDraw(ctx)) return
        val host: Context = svc ?: ctx
        this.kind = kind
        this.byEngine = engine
        val wm = host.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = host.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val header = PromptSurface.header(kind)
        // What TalkBack hears as the panel arrives (unless our own voice is already reading the question aloud).
        val summary = when (kind) {
            PromptSurface.Kind.CONFIRM -> "Nexus needs your OK. $prompt. ${Confirmation.LISTEN_HINT}."
            PromptSurface.Kind.QUESTION -> "Nexus is asking. $prompt. Say your answer, or double-tap Speak. Say stop to cancel."
            PromptSurface.Kind.SECURE -> "Secure step. $prompt. When you've finished, say done or double-tap Done."
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
            // IME). Where it sits: below (a bottom card once hid behind the soft keyboard — the Telegram confirm bug).
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            // Every prompt at the TOP, under the status bar — where the in-app assistant surface lives, clear of the soft
            // keyboard (the reason it had been centred) and of a PIN pad (docs/assistant-ux, Stage 5). The glass keeps its
            // own small margin (room for its soft shadow in light mode).
            gravity = Gravity.TOP
            y = topInset(host) + dp(2)
        }

        val panel = ComposeView(host)
        val life = PanelOwner()
        val typing = mutableStateOf(false)
        val notice = mutableStateOf<String?>(null)
        fun hideSoon() { main.post { if (view === panel) hideNow() } }
        // Type: the answer by keyboard, on the panel itself (owner, 2 October 2026: "when it needs feedback, you can't type" —
        // the only text box was on Tɛkyerɛma's own screen, which is not in front while a task runs). The window takes focus
        // only while it is typed into, and never at the secure step: a box there could be mistaken for where the PIN goes.
        fun setTyping(on: Boolean) {
            if (view !== panel || typing.value == on || !PromptSurface.canType(kind)) return
            if (on) VoiceEngine.quietForTyping()     // stop reading, close the answer microphone; the prompt stays waiting
            typing.value = on
            notice.value = null
            lp.flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                else lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            lp.softInputMode = if (on) WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                else WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
            try { wm.updateViewLayout(panel, lp) } catch (_: Throwable) {}
        }
        fun submitTyped(text: String): Boolean {
            val r = VoiceEngine.submitTyped(text)
            // Not an answer this prompt can take ("Type yes or no — or use the buttons"): say why, keep the box.
            notice.value = if (r.accepted) r.notice.takeIf { it.isNotBlank() } else r.notice.ifBlank { "I couldn't use that — try again" }
            if (!r.accepted) panel.announceForAccessibility(notice.value)
            return r.accepted
        }
        val p = PromptSurface.Prompt(kind, prompt, seq)
        panel.setViewTreeLifecycleOwner(life)
        panel.setViewTreeSavedStateRegistryOwner(life)
        if (Build.VERSION.SDK_INT >= 28) panel.accessibilityPaneTitle = header.lowercase().replaceFirstChar { it.uppercase() }
        panel.setContent {
            TekyeremaTheme {
                PromptPanel(
                    prompt = p, typing = typing.value, notice = notice.value, typeHint = typeHint(kind),
                    // J-02: the answers are for THIS confirmation's number (a panel not drawn for a pending confirmation has
                    // NO_CONFIRMATION, so its verb can approve nothing); the verb counts only once ConfirmGate has armed it.
                    onConfirm = { approved, s -> AgentEngine.confirm(approved, s); hideSoon() },
                    // A choice is that answer only while the question is still asked (ChoiceGate), and once (the set locks).
                    onChoice = { c -> if (ChoiceGate.accepts(AgentEngine.state.value.status, engineQuestion = engine)) VoiceEngine.submitTyped(c) },
                    // Reopens the microphone for this question; the panel stays until it is answered.
                    onAnswerByVoice = { VoiceEngine.respond() },
                    onType = { setTyping(true) },
                    onSecureDone = { AgentEngine.secureInputDone(); hideSoon() },
                    onStopTask = { com.tekyerema.ui.assistant.StopGuard.press { VoiceEngine.cancel(); hideSoon() } },
                    onSubmitTyped = { submitTyped(it) },
                    onCloseTyping = { setTyping(false) }
                )
            }
        }
        try {
            life.start()
            wm.addView(panel, lp); view = panel; wmUsed = wm; owner = life
            onScreen.value = true
            // TalkBack users hear the panel arrive; with replies read aloud the question is already being spoken.
            if (!com.tekyerema.settings.Settings(ctx).speakAloud) panel.post { panel.announceForAccessibility(summary) }
        } catch (t: Throwable) { life.destroy(); view = null; wmUsed = null; owner = null }
    }

    private fun typeHint(kind: PromptSurface.Kind): String = if (kind == PromptSurface.Kind.CONFIRM) "Type yes, no, or what to change" else "Type your answer"

    fun hide(ctx: Context) { main.post { hideNow() } }

    /** A voice turn's own question card is done: take it down — never the task's panel. A late call from the previous turn
     *  removed the NEW task's panel, and nothing showed it again (owner, 24 Oct 2026: "sometimes no pop-up"). */
    fun hideAsking(ctx: Context) { main.post { if (byEngine) android.util.Log.i("Tekyerema", "[PANEL] kept the task's panel (a turn's hide)") else hideNow() } }

    /** Remove the panel and wait until it is gone — before an approved action runs, so its tap lands on the app. */
    suspend fun hideAndWait(ctx: Context) {
        val gone = CompletableDeferred<Unit>()
        main.post { hideNow(); gone.complete(Unit) }
        withTimeoutOrNull(1_000) { gone.await() }
        delay(60)   // the window manager removes the window just after removeView returns
    }

    private fun hideNow() {
        byEngine = false
        onScreen.value = false
        val v = view ?: return
        try { wmUsed?.removeView(v) } catch (t: Throwable) {}
        owner?.destroy()
        view = null; wmUsed = null; owner = null
    }
}

/** The panel's own lifecycle (Compose in a window no Activity owns): resumed while it is on screen, destroyed after. */
private class PanelOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    fun start() { saved.performRestore(null); registry.currentState = Lifecycle.State.RESUMED }
    fun destroy() { if (registry.currentState != Lifecycle.State.INITIALIZED) registry.currentState = Lifecycle.State.DESTROYED }
}
