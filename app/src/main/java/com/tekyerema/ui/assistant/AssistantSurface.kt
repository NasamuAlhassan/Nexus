package com.tekyerema.ui.assistant

import com.tekyerema.ui.theme.answerSized

import android.content.Intent
import android.net.Uri
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.tekyerema.agent.AgentEngine
import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.AgentStep
import com.tekyerema.agent.AgentUiState
import com.tekyerema.policy.ConfirmGate
import com.tekyerema.ui.OverlayConfirm
import com.tekyerema.ui.assistant.SurfaceGesture.Rest
import com.tekyerema.ui.assistant.SurfaceGesture.Settle
import com.tekyerema.ui.assistant.SurfaceModel.Mode
import com.tekyerema.ui.theme.LocalCalmMotion
import com.tekyerema.ui.theme.Spectrum
import com.tekyerema.ui.theme.Tk
import com.tekyerema.ui.theme.TkHaptic
import com.tekyerema.voice.PromptSurface
import com.tekyerema.voice.VoiceEngine
import com.tekyerema.voice.VoiceState
import com.tekyerema.voice.VoiceUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * The assistant surface, v2 (docs/assistant-ux/PLAN_V2.md, VISUAL_REFERENCE.md): one soft glass object at the top of
 * Tɛkyerɛma's screen. It grows out of the top, breathes only while the agent really works, listens visibly while the voice
 * pipeline listens, reshapes between working → waiting → result → expanded → input, and melts away with a push up or a tap
 * outside. State comes only from [SurfaceModel.reduce] and the voice pipeline; taps turn into the same calls the rest of the
 * app makes (AgentEngine.confirm with its sequence number, VoiceEngine.submitTyped behind ChoiceGate, VoiceEngine.start /
 * respond / hush / cancel). No gesture approves, cancels or answers anything (SurfaceGesture; DECISIONS V2-2).
 */

/** What the user did to the surface, kept for the process (both screens that host it share it; a recreated screen doesn't
 *  bring back a result the user closed). */
object SurfaceStore {
    val local = kotlinx.coroutines.flow.MutableStateFlow(SurfaceModel.Local())
    fun update(f: (SurfaceModel.Local) -> SurfaceModel.Local) { local.value = f(local.value) }
}

/** What the previous committed frame of the surface host saw (plain fields, written only in its SideEffect). */
private class PrevFrame(var lastRaw: String, var voiceState: VoiceState, var runSeq: Long, var spokenAt: Long, var inRun: Boolean)

/** The prompt that was last on screen, for showing what the engine decided about it (written only in SideEffect). */
private class LastPrompt(var state: SurfaceModel.State? = null, var since: Long = 0L, var choices: List<String> = emptyList())

/** What the engine decided about the prompt that was just on screen — read from its own next state, never assumed. */
internal data class Reaction(val decision: VoiceReflect.Decision? = null, val chosen: String? = null)

@Composable
fun AssistantSurfaceHost(
    voice: VoiceUiState,
    agent: AgentUiState,
    twi: Boolean,
    modifier: Modifier = Modifier,
    /** The surface's visibility, for the host to quieten what would repeat it (the line under the orb). */
    onVisible: (Boolean) -> Unit = {},
    /** Home is showing its own full-screen "Listening" (HomeContent): the card's plain "Listening…" steps aside, never anything else. */
    hideListenCard: Boolean = false,
    /** The user's "read replies aloud": our own voice then says results and prompts, so TalkBack isn't also told them. */
    readsAloud: Boolean = false,
    /** True while the surface shows a result or is being moved: the Home orb holds still behind it (stage 7). */
    onOrbPause: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val calm = LocalCalmMotion.current
    val tier = remember(calm) { GlassTiers.detect(context, calm) }
    val local by SurfaceStore.local.collectAsState()
    // A clock that ticks only when a result's freshness may run out (no per-frame work).
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val inRun = agent.status == AgentStatus.THINKING || agent.status == AgentStatus.ACTING || agent.status == AgentStatus.PAUSED ||
        agent.status == AgentStatus.ASKING || agent.status == AgentStatus.WAITING_CONFIRMATION || agent.status == AgentStatus.SECURE_INPUT
    val prev = remember { PrevFrame(voice.lastRaw, voice.state, agent.runSeq, voice.spokenAt, inRun) }
    val last = remember { LastPrompt() }

    // V2-4: while this screen is resumed, the task's prompts live on this surface and the panel over other apps steps back;
    // the moment the screen leaves, a still-waiting prompt comes straight back as the panel (OverlayConfirm.setAppSurface).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val app = context.applicationContext
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> OverlayConfirm.setAppSurface(app, true)
                Lifecycle.Event.ON_PAUSE -> OverlayConfirm.setAppSurface(app, false)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); OverlayConfirm.setAppSurface(app, false) }
    }
    // The panel can still be up for a moment (before the hand-over above lands): never draw the same prompt twice.
    val panelUp by OverlayConfirm.onScreen.collectAsState()

    val reduced = SurfaceModel.reduce(voice, agent, local, now = maxOf(now, voice.spokenAt), twi = twi)
    // Resolving: the engine has its answer (prompt cleared) but hasn't moved on — the prompt stays, inactive, until the next
    // state says what was decided (so the reaction can be seen and the shape never blinks).
    val lastPrompt = last.state
    val resolving = reduced.mode != Mode.WAITING && lastPrompt != null && lastPrompt.runSeq == agent.runSeq &&
        (agent.status == AgentStatus.WAITING_CONFIRMATION || agent.status == AgentStatus.ASKING)
    val state = when {
        reduced.mode == Mode.WAITING && panelUp -> reduced.copy(mode = Mode.HIDDEN)
        hideListenCard && reduced.listenOnly -> reduced.copy(mode = Mode.HIDDEN)
        resolving -> lastPrompt!!
        else -> reduced
    }
    // The engine's decision about the prompt that was just on screen (voice or touch alike).
    val reaction = if (lastPrompt != null && reduced.mode != Mode.WAITING) when (lastPrompt.waiting?.kind) {
        PromptSurface.Kind.CONFIRM -> Reaction(decision = VoiceReflect.decision(agent.status, agent.log, last.since))
        PromptSurface.Kind.QUESTION -> Reaction(chosen = VoiceReflect.chosen(last.choices, agent.log, last.since))
        else -> Reaction()
    } else Reaction()

    SideEffect {
        if (reduced.mode == Mode.WAITING) {
            if (last.state?.waiting?.seq != reduced.waiting?.seq || last.state?.waiting?.text != reduced.waiting?.text) {
                last.since = System.currentTimeMillis()
                last.choices = reduced.waiting?.let { if (it.kind == PromptSurface.Kind.QUESTION) QuestionChoices.of(it.text) else emptyList() }.orEmpty()
            }
            last.state = reduced
        } else if (!resolving && reduced.runSeq != last.state?.runSeq) last.state = null
        // A NEW request replaces what was on screen BEFORE it (UX-6): what the previous frame had is dismissed — never "now":
        // a quick answer that arrives in the same frame as its request must still show. Only real words count — the mic
        // merely opening (a follow-up listen after a reply) dismissed the reply the moment it appeared, so the answer fell
        // back to the middle of Home (owner on the A20e, 14 Oct 2026).
        val newInput = voice.lastRaw.isNotBlank() && voice.lastRaw != prev.lastRaw
        if (newInput && !prev.inRun) {
            val run = prev.runSeq; val at = prev.spokenAt
            SurfaceStore.update { l -> l.copy(dismissedRun = run, dismissedAt = maxOf(l.dismissedAt, at)) }
        }
        prev.lastRaw = voice.lastRaw; prev.voiceState = voice.state; prev.runSeq = agent.runSeq; prev.spokenAt = voice.spokenAt; prev.inRun = inRun
    }
    // One polite live node (UX-7) — except what our own voice already says aloud (results and prompts with read-aloud on).
    val announce = when (state.mode) {
        Mode.PRESENTING, Mode.FAILED, Mode.CANCELLED, Mode.WAITING -> !readsAloud
        else -> true
    }
    LaunchedEffect(state.key, state.mode) {
        now = System.currentTimeMillis()
        if (state.mode == Mode.PRESENTING || state.mode == Mode.FAILED || state.mode == Mode.CANCELLED) {
            delay(SurfaceModel.FRESH_MS + 1_000); now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(state.visible) { onVisible(state.visible) }

    fun open(uri: String) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
    }
    CompositionLocalProvider(LocalReadsAloud provides readsAloud) { GlassSurface(
        state = state,
        voice = voice,
        resolving = resolving,
        reaction = reaction,
        twi = twi,
        tier = tier,
        announce = announce,
        modifier = modifier,
        onOrbPause = onOrbPause,
        commitDismiss = {
            if (state.speaking) VoiceEngine.hush()
            val t = System.currentTimeMillis()
            SurfaceStore.update { l -> if (state.key.startsWith("reply:")) l.copy(dismissedReplyKey = state.key, dismissedAt = t)
                else l.copy(dismissedRun = state.runSeq, dismissedAt = t) }
        },
        onStopTask = { StopGuard.press { VoiceEngine.cancel(); AgentEngine.stop() } },
        onStopSpeaking = { VoiceEngine.hush() },
        onConfirm = { approved, seq -> AgentEngine.confirm(approved, seq) },
        // A choice is that answer only while the agent is still asking (ChoiceGate).
        onChoice = { choice -> if (ChoiceGate.accepts(AgentEngine.state.value.status, engineQuestion = true)) VoiceEngine.submitTyped(choice) },
        onAnswerByVoice = { VoiceEngine.respond() },
        onSecureDone = { AgentEngine.secureInputDone() },
        onRetry = { goal -> VoiceEngine.submitTyped(goal) },
        onSpeak = { VoiceEngine.start() },
        onSubmitText = { text -> VoiceEngine.submitTyped(text).accepted },
        onOpenUrl = { open(it) },
        onOpenMaps = { query, link -> open(link ?: MapsLink.of(query)) }
    ) }
}

/** The user's "read replies aloud", for a result's "Say again" (shown only when our own voice reads replies). */
private val LocalReadsAloud = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * The object itself, with its gestures. The finger offset lives in [dragPx] (read in the layer and draw phases only, so a drag
 * never recomposes the content); releases settle through [SurfaceGesture.settle] with a critically damped spring that a new
 * touch interrupts; an expanded answer scrolls first and hands its leftover to the surface (nested scroll).
 */
@Composable
private fun GlassSurface(
    state: SurfaceModel.State,
    voice: VoiceUiState,
    resolving: Boolean,
    reaction: Reaction,
    twi: Boolean,
    tier: GlassTier,
    announce: Boolean,
    modifier: Modifier,
    onOrbPause: (Boolean) -> Unit,
    commitDismiss: () -> Unit,
    onStopTask: () -> Unit,
    onStopSpeaking: () -> Unit,
    onConfirm: (Boolean, Long) -> Unit,
    onChoice: (String) -> Unit,
    onAnswerByVoice: () -> Unit,
    onSecureDone: () -> Unit,
    onRetry: (String) -> Unit,
    onSpeak: () -> Unit,
    onSubmitText: (String) -> Boolean,
    onOpenUrl: (String) -> Unit,
    onOpenMaps: (String, String?) -> Unit
) {
    val calm = LocalCalmMotion.current
    val density = LocalDensity.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    // Each new state starts compact: a result that arrives after a typed answer doesn't open with the field showing.
    var rest by remember(state.key, state.mode) { mutableStateOf(Rest.COMPACT) }
    val dragPx = remember { mutableFloatStateOf(0f) }
    var heightPx by remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var crossed by remember { mutableStateOf(false) }
    // One gesture, one release: the nested fill callbacks and the drag's own stop could each report the same release.
    var gestureLive by remember { mutableStateOf(false) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(state.key) { leaving = false; dragPx.floatValue = 0f }

    val expandable = when (val p = state.preview) {
        is SurfaceModel.Preview.Answer -> p.body.length > p.summary.length || state.steps.isNotEmpty() || state.title.length > 44
        is SurfaceModel.Preview.Action -> state.steps.isNotEmpty() || state.title.length > 44
        is SurfaceModel.Preview.Problem -> false
        null -> state.mode == Mode.WORKING && state.steps.count { !it.active } > SurfaceModel.COMPACT_STEPS
    }
    val allow = if (resolving) SurfaceGesture.Allow(false, false, false) else SurfaceGesture.allow(state.mode, expandable)
    // The follow-up field also opens for typing an answer to the agent's question (Type), never by a gesture on a prompt.
    val typingAnswer = state.mode == Mode.WAITING && state.waiting?.kind == PromptSurface.Kind.QUESTION && rest == Rest.INPUT

    fun toDp(px: Float) = px / density.density
    fun visualPx(): Float = with(density) {
        SurfaceGesture.visualOffset(toDp(dragPx.floatValue), allowUp = allow.dismiss || rest != Rest.COMPACT,
            allowDown = allow.followUp || (allow.expand && rest == Rest.COMPACT)).dp.toPx()
    }
    fun animateDrag(target: Float, velocity: Float, then: () -> Unit = {}) {
        settleJob?.cancel()
        // A critically damped spring still overshoots once if flung at its target faster than √k × the distance: cap it.
        val cap = 26f * kotlin.math.abs(target - dragPx.floatValue)
        val v = velocity.coerceIn(-cap, cap)
        settleJob = scope.launch {
            if (calm) dragPx.floatValue = target
            else animate(dragPx.floatValue, target, v, SurfaceMotion.Settle) { value, _ -> dragPx.floatValue = value }
            then()
        }
    }
    fun dismiss(velocity: Float = 0f) {
        if (!allow.dismiss || leaving) return
        android.util.Log.i("Tekyerema", "[SURFACE] dismiss (${if (velocity == 0f) "tap or action" else "swipe"})")
        leaving = true
        animateDrag(-(heightPx + with(density) { 48.dp.toPx() }), velocity) { commitDismiss() }
    }
    fun onDelta(delta: Float) {
        gestureLive = true
        dragPx.floatValue += delta
        val now = SurfaceGesture.crossed(rest, toDp(dragPx.floatValue), toDp(heightPx), allow)
        if (now && !crossed) try {
            view.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                else HapticFeedbackConstants.CLOCK_TICK)
        } catch (_: Throwable) {}
        crossed = now
    }
    fun onRelease(velocity: Float) {
        dragging = false
        crossed = false
        if (!gestureLive) return
        gestureLive = false
        val s = SurfaceGesture.settle(rest, toDp(dragPx.floatValue), toDp(velocity), toDp(heightPx), allow)
        // Numbers only (no words): how a release was read — the emulator and phone tests check gestures against it.
        android.util.Log.i("Tekyerema", "[SURFACE] release drag=${toDp(dragPx.floatValue).toInt()}dp v=${toDp(velocity).toInt()}dp/s " +
            "h=${toDp(heightPx).toInt()}dp rest=$rest dismiss=${allow.dismiss} → $s")
        if (s == Settle.DISMISS) { dismiss(velocity); return }
        rest = SurfaceGesture.next(rest, s)
        animateDrag(0f, velocity)
    }
    val nested = remember(allow, rest) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val d = dragPx.floatValue
                if (source != NestedScrollSource.UserInput || d == 0f) return Offset.Zero
                // The surface is displaced: bring it back before the answer scrolls.
                val nd = if (d > 0f) max(0f, d + available.y) else min(0f, d + available.y)
                dragPx.floatValue = nd
                return Offset(0f, nd - d)
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
                if (!dragging) { dragging = true; settleJob?.cancel() }
                onDelta(available.y)
                return Offset(0f, available.y)
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (dragPx.floatValue == 0f) return Velocity.Zero
                onRelease(available.y); return available
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (dragPx.floatValue == 0f) return Velocity.Zero
                onRelease(available.y); return available
            }
        }
    }
    BackHandler(enabled = state.visible && SurfaceGesture.backCollapses(rest)) { rest = Rest.COMPACT }
    val dismissNow by rememberUpdatedState { dismiss() }
    val orbPause = state.visible && (dragging || rest != Rest.COMPACT ||
        state.mode == Mode.PRESENTING || state.mode == Mode.FAILED || state.mode == Mode.CANCELLED)
    LaunchedEffect(orbPause) { onOrbPause(orbPause) }

    // ---------------------------------------------------------------- the light: real state only
    val listening = (state.mode == Mode.WAITING && VoiceReflect.cue(state.waiting!!.kind, voice, false, twi) is VoiceReflect.Cue.Listening) ||
        (state.mode == Mode.PROCESSING && voice.state == com.tekyerema.voice.VoiceState.LISTENING)
    val mood = when {
        state.mode == Mode.WAITING -> if (listening) LightMood.LISTENING else LightMood.CALM
        state.mode == Mode.PROCESSING && listening -> LightMood.LISTENING
        (state.mode == Mode.WORKING || state.mode == Mode.PROCESSING) && !state.paused -> LightMood.WORKING
        state.mode == Mode.FAILED || state.mode == Mode.CANCELLED -> LightMood.FAILURE
        state.mode == Mode.PRESENTING && (state.preview as? SurfaceModel.Preview.Action)?.verified != false -> LightMood.SUCCESS
        else -> LightMood.CALM
    }
    val currentMood by rememberUpdatedState(mood)
    val micLevel = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(listening) {
        if (!listening) { micLevel.floatValue = 0f; return@LaunchedEffect }
        // Lite (UI audit, 21 Oct 2026): ~15 updates a second and only on a visible change — a 60 fps write here redrew the window
        // every frame while listening and cancelled the orb's 30 fps cap.
        val lite = com.tekyerema.ui.glass.glassLite()
        var last = 0L
        while (true) withFrameNanos { now ->
            if (!lite || now - last >= 66_000_000L) {
                last = now
                val v = micLevel.floatValue + (VoiceEngine.level.value.coerceIn(0f, 1f) - micLevel.floatValue) * (if (lite) 0.6f else 0.3f)
                if (!lite || kotlin.math.abs(v - micLevel.floatValue) > 0.02f) micLevel.floatValue = v
            }
        }
    }
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(state.key, mood) {
        when (mood) {
            LightMood.SUCCESS, LightMood.FAILURE -> if (calm || tier == GlassTier.FLAT) pulse.snapTo(0f)
                else { pulse.snapTo(1f); pulse.animateTo(0f, tween(900)) }   // one small moment, then calm
            else -> pulse.snapTo(0f)
        }
    }
    val sweepLive = mood == LightMood.WORKING && tier == GlassTier.FULL && !calm
    val sweep = if (sweepLive) rememberInfiniteTransition(label = "edge sweep")
        .animateFloat(0f, 360f, infiniteRepeatable(tween(5200, easing = LinearEasing), RepeatMode.Restart), label = "sweep") else null
    val arrival = remember(state.key) { Animatable(if (calm || tier == GlassTier.FLAT) 1f else 0f) }
    LaunchedEffect(state.key) { if (arrival.value < 1f) { delay(120); arrival.animateTo(1f, tween(760)) } }
    val lightLevel: () -> Float = {
        when (currentMood) {
            LightMood.LISTENING -> 0.25f + 0.75f * micLevel.floatValue
            LightMood.WORKING -> 0.55f
            LightMood.SUCCESS -> 0.30f + 0.70f * pulse.value
            LightMood.FAILURE -> 0.45f + 0.45f * pulse.value
            LightMood.CALM -> 0.22f
        }
    }

    // ---------------------------------------------------------------- the object
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
    val radius = 34.dp
    // The glass follows the phone's light or dark mode (owner, A20e, 14 Oct 2026; DECISIONS V2-11).
    val glass = com.tekyerema.ui.theme.glassPalette()
    // "This is money" (owner's item 6, 2 Nov 2026; the state is the brain's MoneyMoment, with its own earcon): while a money flow
    // runs — read-back, confirm, the network's menus, the PIN step — the card wears a gold edge, so it never looks like any other card.
    val moneyOn by com.tekyerema.money.MoneyMoment.active.collectAsState()
    val moneyEdge = moneyEdgeColour(glass)
    Box(modifier.fillMaxSize()) {
        // Tap outside a FINISHED result dismisses it, and the tap stops there (the orb or field underneath doesn't also get
        // it; DECISIONS V2-3). Invisible to TalkBack, which has the surface's "Dismiss" action instead.
        if (state.visible && allow.dismiss && !leaving) Box(
            Modifier.matchParentSize().pointerInput(state.key) {
                // A real TAP only: a press that lifts within touch slop. A swipe that starts outside is not a tap (detectTap…
                // counted any press lifted inside the layer, so a drag across Home dismissed the result — emulator, 14 Oct).
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val up = waitForUpOrCancellation()
                    if (up != null) {
                        up.consume()
                        if ((up.position - down.position).getDistance() < viewConfiguration.touchSlop) dismissNow()
                    }
                }
            }.clearAndSetSemantics { }
        )
        AnimatedVisibility(
            visible = state.visible,
            modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 12.dp, vertical = 6.dp).widthIn(max = 560.dp),
            enter = if (calm) fadeIn(tween(160)) else fadeIn(tween(240)) +
                slideInVertically(SurfaceMotion.Arrive) { -it / 2 } +
                scaleIn(SurfaceMotion.ArriveScale, initialScale = 0.94f, transformOrigin = TransformOrigin(0.5f, 0f)),
            exit = if (leaving) ExitTransition.None else if (calm) fadeOut(tween(120))
                else fadeOut(tween(180)) + slideOutVertically(SurfaceMotion.Leave) { -it / 2 }
        ) {
            CompositionLocalProvider(LocalGlass provides glass) {
                val summary = spokenSummary(state)
                // OUTER, still: it reads the finger (draggable + nested scroll) in coordinates that don't move with the glass.
                // Measured inside the moving glass, a 100 dp flick read as 41 dp at 0 dp/s and never dismissed (emulator log).
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxHeight)
                        .onSizeChanged { heightPx = it.height.toFloat().coerceAtLeast(1f) }
                        .nestedScroll(nested)
                        .draggable(
                            state = rememberDraggableState { d -> onDelta(d) },
                            orientation = Orientation.Vertical,
                            // A touch during a settle catches the glass at once, mid-flight (no touch slop to wait out).
                            startDragImmediately = settleJob?.isActive == true,
                            onDragStarted = { settleJob?.cancel(); dragging = true; gestureLive = true },
                            onDragStopped = { v -> onRelease(v) }
                        )
                        .semantics {
                            paneTitle = "Nexus"
                            stateDescription = when (rest) { Rest.COMPACT -> "Compact"; Rest.EXPANDED -> "Expanded"; Rest.INPUT -> "Expanded, with a text field" }
                        }
                ) {
                    // INNER: the glass itself, moved by the finger offset (layer phase only: a drag never recomposes it).
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                val off = visualPx()
                                translationY = off
                                val up = (-off / heightPx).coerceIn(0f, 1f)
                                alpha = 1f - up * 0.7f
                                val swell = (off / heightPx).coerceIn(0f, 1f) * 0.018f
                                scaleX = 1f + swell; scaleY = 1f + swell
                                transformOrigin = TransformOrigin(0.5f, 0f)
                            }
                            // (the glass draws its own soft shadow, outside its shape only — ui/glass/LiquidGlass)
                            .glassBody(radius, glass, mood = { currentMood }, light = lightLevel, sweep = { sweep?.value ?: 0f },
                                glowShift = { visualPx() * 0.25f }, tier = tier, arrival = { arrival.value })
                            .then(if (moneyOn) Modifier.moneyEdge(radius, moneyEdge) else Modifier)
                            .clip(if (com.tekyerema.ui.glass.glassLite()) androidx.compose.foundation.shape.RoundedCornerShape(radius) else ContinuousShape(radius))
                            .handle(glass.idleBar) { allow.followUp && rest == Rest.COMPACT }
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .animateContentSize(if (calm) tween(0) else SurfaceMotion.Grow)
                                .graphicsLayer { translationY = -visualPx() * 0.05f }   // the content trails the glass a touch
                                .padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = if (allow.followUp && rest == Rest.COMPACT) 22.dp else 18.dp)
                        ) {
                            Header(state, resolving, summary, announce, twi, allow, expandable, rest,
                                onRest = { rest = it }, onDismiss = { dismiss() }, onStopTask = onStopTask, onStopSpeaking = onStopSpeaking, tier = tier)
                            val bodyScroll = remember(state.key, state.mode, rest) { ScrollState(0) }
                            AnimatedContent(
                                targetState = state,
                                // Scrolls only when the text really overflows; otherwise every drag goes to the still outer box.
                                modifier = Modifier.weight(1f, fill = false).verticalScroll(bodyScroll, enabled = bodyScroll.maxValue > 0),
                                contentKey = { "${it.key}|${it.mode}|${it.waiting?.seq}" },
                                transitionSpec = {
                                    val wasPrompt = initialState.mode == Mode.WAITING
                                    (if (calm) fadeIn(tween(160)) togetherWith fadeOut(tween(90))
                                    else (fadeIn(tween(240, delayMillis = if (wasPrompt) 160 else 60)) +
                                        slideInVertically(tween(280)) { it / 10 }) togetherWith fadeOut(tween(if (wasPrompt) 320 else 120))) using
                                        SizeTransform(clip = false) { _, _ -> if (calm) tween(0) else SurfaceMotion.Grow }
                                },
                                label = "surface body"
                            ) { s ->
                                Body(s, voice, resolving && s.key == state.key, reaction, rest, twi, tier,
                                    onToggle = { if (expandable && !s.mode.isPrompt()) rest = if (rest == Rest.COMPACT) Rest.EXPANDED else Rest.COMPACT },
                                    onConfirm = onConfirm, onChoice = onChoice, onAnswerByVoice = onAnswerByVoice,
                                    onType = { rest = Rest.INPUT }, onSecureDone = onSecureDone, onStopTask = onStopTask,
                                    onRetry = onRetry, onOpenUrl = onOpenUrl, onOpenMaps = onOpenMaps)
                            }
                            if (rest == Rest.INPUT && (allow.followUp || typingAnswer)) FollowUpField(
                                placeholder = if (typingAnswer) (if (twi) "Twerɛ wo mmuae" else "Type your answer") else "Ask a follow-up",
                                twi = twi, tier = tier,
                                onSubmit = { text -> onSubmitText(text).also { ok -> if (ok) rest = Rest.COMPACT } },
                                onSpeak = { if (typingAnswer) onAnswerByVoice() else onSpeak() }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun Mode.isPrompt() = this == Mode.WAITING


// ------------------------------------------------------------------ header

@Composable
private fun Header(
    s: SurfaceModel.State, resolving: Boolean, summary: String, announce: Boolean, twi: Boolean,
    allow: SurfaceGesture.Allow, expandable: Boolean, rest: Rest,
    onRest: (Rest) -> Unit, onDismiss: () -> Unit, onStopTask: () -> Unit, onStopSpeaking: () -> Unit, tier: GlassTier
) {
    val working = s.mode == Mode.PROCESSING || s.mode == Mode.WORKING
    // At large text the title gets its own line and Stop sits under it (UX-8).
    val stacked = LocalDensity.current.fontScale >= 1.5f
    // Every gesture has a TalkBack / switch-access equivalent on the title (mission v2): Dismiss, Expand, Collapse, Ask a
    // follow-up, Stop reading. The title is also the one polite live node (UX-7).
    val actions = buildList {
        if (allow.dismiss) add(CustomAccessibilityAction("Dismiss") { onDismiss(); true })
        if (expandable && rest == Rest.COMPACT) add(CustomAccessibilityAction("Expand") { onRest(Rest.EXPANDED); true })
        if (rest != Rest.COMPACT) add(CustomAccessibilityAction("Collapse") { onRest(Rest.COMPACT); true })
        if (allow.followUp && rest != Rest.INPUT) add(CustomAccessibilityAction("Ask a follow-up") { onRest(Rest.INPUT); true })
        if (s.speaking) add(CustomAccessibilityAction("Stop reading") { onStopSpeaking(); true })
    }
    val title: @Composable (Modifier) -> Unit = { m ->
        Text(
            s.title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 19.sp, letterSpacing = (-0.2).sp),
            color = GlassColors.Text,
            maxLines = if (rest != Rest.COMPACT || stacked) 4 else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = m.semantics {
                heading()
                if (announce) liveRegion = LiveRegionMode.Polite
                contentDescription = if (working) s.title else summary
                customActions = actions
            }
        )
    }
    val stop: @Composable () -> Unit = {
        if (working && !resolving) GlassPill(if (twi) "Gyae" else "Stop", onClick = onStopTask, compact = true, tier = tier,
            contentDescription = if (twi) "Gyae adwuma no" else "Stop the task", haptic = TkHaptic.REJECT)
    }
    if (stacked) Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StateMark(s, Modifier.size(12.dp)); Spacer(Modifier.width(12.dp)); title(Modifier.weight(1f))
        }
        Row(Modifier.align(Alignment.End)) { stop() }
    } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StateMark(s, Modifier.size(12.dp)); Spacer(Modifier.width(12.dp)); title(Modifier.weight(1f)); stop()
    }
}

/** The small mark before the title: a soft dot whose tone says the state (its meaning is also in the words). Only a working
 *  surface's dot breathes, and only in full motion. */
@Composable
internal fun StateMark(s: SurfaceModel.State, modifier: Modifier) {
    val calm = LocalCalmMotion.current
    val working = (s.mode == Mode.WORKING || s.mode == Mode.PROCESSING) && !s.paused
    val color = when (s.mode) {
        Mode.WAITING -> GlassColors.Warm
        Mode.FAILED, Mode.CANCELLED -> GlassColors.Fail
        Mode.PRESENTING -> if ((s.preview as? SurfaceModel.Preview.Action)?.verified == false) GlassColors.TextTertiary else GlassColors.Accent
        else -> if (s.paused) GlassColors.TextTertiary else GlassColors.Accent
    }
    val breath = if (working && !calm && !com.tekyerema.ui.glass.glassLite()) rememberInfiniteTransition(label = "mark")
        .animateFloat(0.45f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "mark alpha") else null
    Canvas(modifier.clearAndSetSemantics { }.graphicsLayer { alpha = breath?.value ?: 1f }) {
        drawCircle(color.copy(alpha = 0.28f), size.minDimension / 2f)
        drawCircle(color, size.minDimension / 4f)
    }
}

// ------------------------------------------------------------------ body

@Composable
private fun Body(
    s: SurfaceModel.State, voice: VoiceUiState, resolving: Boolean, reaction: Reaction, rest: Rest, twi: Boolean, tier: GlassTier,
    onToggle: () -> Unit,
    onConfirm: (Boolean, Long) -> Unit, onChoice: (String) -> Unit, onAnswerByVoice: () -> Unit, onType: () -> Unit,
    onSecureDone: () -> Unit, onStopTask: () -> Unit,
    onRetry: (String) -> Unit, onOpenUrl: (String) -> Unit, onOpenMaps: (String, String?) -> Unit
) {
    val expanded = rest != Rest.COMPACT
    Column(Modifier.fillMaxWidth()) {
        when (s.mode) {
            // The step running now is the title; below it, the steps before it — a calm list, past tense once done.
            Mode.WORKING, Mode.PROCESSING -> Reveal(0, s.key) { Steps(s.steps.filterNot { it.active }, expanded) }
            Mode.WAITING -> s.waiting?.let { Waiting(it, voice, resolving, reaction, twi, tier, onConfirm, onChoice, onAnswerByVoice, onType, onSecureDone, onStopTask) }
            else -> when (val p = s.preview) {
                is SurfaceModel.Preview.Answer -> AnswerBody(s.key, p, s.steps, expanded, tier, twi, onToggle, onOpenUrl, onOpenMaps)
                is SurfaceModel.Preview.Action -> ActionBody(s.key, p, s.steps, expanded, onToggle)
                is SurfaceModel.Preview.Problem -> ProblemBody(s.key, p, s.mode == Mode.CANCELLED, tier, onRetry)
                null -> {}
            }
        }
    }
}

/** Content arrives progressively: each block fades up a little after the one before (never a layout jump). */
@Composable
private fun Reveal(index: Int, key: Any, content: @Composable () -> Unit) {
    val calm = LocalCalmMotion.current
    val a = remember(key) { Animatable(if (calm) 1f else 0f) }
    LaunchedEffect(key) { if (a.value < 1f) { delay(80L + index * SurfaceMotion.STAGGER_MS); a.animateTo(1f, tween(260)) } }
    Box(Modifier.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 6.dp.toPx() }) { content() }
}

@Composable
private fun Steps(steps: List<SurfaceModel.StepView>, expanded: Boolean) {
    if (steps.isEmpty()) return
    val shown = if (expanded) steps else steps.takeLast(SurfaceModel.COMPACT_STEPS)
    Column(Modifier.padding(top = 10.dp, start = 24.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        shown.forEach { step -> androidx.compose.runtime.key(step.key) { StepLine(step) } }
    }
}

@Composable
private fun StepLine(step: SurfaceModel.StepView) {
    val done = step.state == AgentStep.State.DONE
    val failed = step.state == AgentStep.State.FAILED
    val accent = GlassColors.Accent; val fail = GlassColors.Fail; val tertiary = GlassColors.TextTertiary
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {
        stateDescription = when { step.active -> "in progress"; done -> "done"; failed -> "didn't work"; else -> "" }
    }) {
        Canvas(Modifier.size(10.dp).clearAndSetSemantics { }) {
            val r = size.minDimension / 2f
            when {
                done -> {
                    val w = r * 0.42f
                    drawLine(accent, Offset(r * 0.25f, r), Offset(r * 0.8f, r * 1.55f), w, StrokeCap.Round)
                    drawLine(accent, Offset(r * 0.8f, r * 1.55f), Offset(r * 1.8f, r * 0.4f), w, StrokeCap.Round)
                }
                failed -> drawCircle(fail, r * 0.55f)
                else -> drawCircle(tertiary, r * 0.5f, style = Stroke(r * 0.3f))
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(if (done) step.past else step.label, style = MaterialTheme.typography.bodyMedium,
            color = if (done) GlassColors.TextSecondary else GlassColors.TextTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ------------------------------------------------------------------ waiting on the user

@Composable
internal fun Waiting(
    w: SurfaceModel.Waiting, voice: VoiceUiState, resolving: Boolean, reaction: Reaction, twi: Boolean, tier: GlassTier,
    onConfirm: (Boolean, Long) -> Unit, onChoice: (String) -> Unit, onAnswerByVoice: () -> Unit, onType: () -> Unit,
    onSecureDone: () -> Unit, onStopTask: () -> Unit,
    /** The panel over other apps also lets a confirmation be typed (owner, 2 October 2026); null: no Type pill. */
    onTypeConfirm: (() -> Unit)? = null
) {
    val choices = remember(w.text) { if (w.kind == PromptSurface.Kind.QUESTION) QuestionChoices.of(w.text) else emptyList() }
    val cue = if (resolving) VoiceReflect.Cue.None else VoiceReflect.cue(w.kind, voice, choices.isNotEmpty(), twi)
    val stopWords = if (twi) "Gyae adwuma no" else "Stop the task"
    Column(Modifier.padding(top = 10.dp)) {
        // A call, a text or a payment leads with the person (PersonPreview); the prompt's full words follow, smaller.
        val facts = remember(w.text) { if (w.kind == PromptSurface.Kind.CONFIRM) ConfirmFacts.parse(w.text) else null }
        Reveal(0, w.seq to w.text) {
            val shown = ScreenText.of(w.text)
            Column {
                facts?.let { f ->
                    PersonPreview(f)
                    // An announced call: the short window it waits for a "stop", running out as a thin line of light.
                    if (f.announced) {
                        val left = remember(w.seq) { Animatable(1f) }
                        LaunchedEffect(w.seq) {
                            left.animateTo(0f, tween(com.tekyerema.policy.ConfirmTiers.windowMs(w.text).toInt(), easing = LinearEasing))
                        }
                        val bar = GlassColors.Accent
                        val track = GlassColors.Text
                        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().padding(top = 12.dp).height(4.dp)) {
                            val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)
                            drawRoundRect(track.copy(alpha = 0.12f), cornerRadius = r)
                            drawRoundRect(bar, size = androidx.compose.ui.geometry.Size(size.width * left.value, size.height), cornerRadius = r)
                        }
                    }
                    Spacer(Modifier.padding(top = 12.dp))
                }
                Text(shown, style = (if (facts != null) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge).answerSized(),
                    color = if (facts != null) GlassColors.TextSecondary else GlassColors.Text, modifier = Modifier.semantics { contentDescription = shown })
            }
        }
        Spacer(Modifier.padding(top = 12.dp))
        Reveal(1, w.seq to w.text) {
            when (w.kind) {
                PromptSurface.Kind.CONFIRM -> Column {
                    val (no, _) = ConfirmWords.buttons(w.text, twi)
                    val verb = ConfirmWords.label(w.text, twi)
                    // The verb counts only once the confirmation has been up for ConfirmGate.armMs(): the pill fills as that
                    // real delay runs and is not tappable before (the same rule as the panel over other apps).
                    val shownAt = remember(w.seq) { android.os.SystemClock.uptimeMillis() }
                    val arm = remember(w.seq) { Animatable(0f) }
                    var armed by remember(w.seq) { mutableStateOf(false) }
                    LaunchedEffect(w.seq) {
                        arm.animateTo(1f, tween(ConfirmGate.armMs().toInt(), easing = LinearEasing)); armed = true
                    }
                    ButtonRow(listOf(no, verb), listOf(1f, 1.4f)) { i, m ->
                        // J-02: these answer THIS confirmation (its own number), never whatever is pending at tap time.
                        if (i == 0) GlassPill(no, onClick = { onConfirm(false, w.seq) }, modifier = m, tone = PillTone.QUIET,
                            available = !resolving, chosen = reaction.decision == VoiceReflect.Decision.DECLINED,
                            dimmed = reaction.decision == VoiceReflect.Decision.APPROVED,
                            contentDescription = if (twi) "Daabi, nnyɛ" else "$no — don't do it", haptic = TkHaptic.REJECT, tier = tier)
                        else GlassPill(verb, onClick = { if (ConfirmGate.armed(shownAt, android.os.SystemClock.uptimeMillis())) onConfirm(true, w.seq) },
                            modifier = m, tone = PillTone.PRIMARY, arming = { arm.value }, available = armed && !resolving,
                            chosen = reaction.decision == VoiceReflect.Decision.APPROVED,
                            dimmed = reaction.decision == VoiceReflect.Decision.DECLINED,
                            contentDescription = if (twi) "Aane, yɛ" else "$verb — do it", haptic = TkHaptic.CONFIRM, tier = tier)
                    }
                    // Over other apps the answer can also be typed: a small quiet pill under the two that decide.
                    // (not for an announced call: it goes ahead by itself; "stop" or Cancel is all it takes)
                    if (onTypeConfirm != null && facts?.announced != true) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                        GlassPill(if (twi) "Twerɛ" else "Type", onClick = onTypeConfirm, compact = true, available = !resolving,
                            contentDescription = if (twi) "Twerɛ wo mmuae" else "Type your answer instead of saying it", tier = tier)
                    }
                }
                PromptSurface.Kind.QUESTION -> Column {
                    if (choices.isNotEmpty()) {
                        ChoiceSet(choices, chosen = reaction.chosen, twi = twi, onChoice = onChoice, tier = tier)
                        Spacer(Modifier.padding(top = 12.dp))
                    }
                    val speakLabel = if (twi) "Kasa" else "Speak"
                    val labels = buildList { add(if (twi) "Gyae" else "Stop"); add(if (twi) "Twerɛ" else "Type"); if (cue !is VoiceReflect.Cue.Listening) add(speakLabel) }
                    ButtonRow(labels, labels.map { 1f }) { i, m ->
                        when (i) {
                            0 -> GlassPill(labels[0], onClick = onStopTask, modifier = m, available = !resolving, contentDescription = stopWords,
                                haptic = TkHaptic.REJECT, tier = tier)
                            1 -> GlassPill(labels[1], onClick = onType, modifier = m, available = !resolving,
                                contentDescription = if (twi) "Twerɛ wo mmuae" else "Type your answer", tier = tier)
                            else -> GlassPill(labels[2], onClick = onAnswerByVoice, modifier = m, tone = PillTone.PRIMARY, available = !resolving,
                                contentDescription = if (twi) "Bua wɔ kasa mu" else "Answer by voice", tier = tier)
                        }
                    }
                }
                PromptSurface.Kind.SECURE -> {
                    val (stop, done) = PromptSurface.buttons(PromptSurface.Kind.SECURE, twi)
                    ButtonRow(listOf(stop, done), listOf(1f, 1.4f)) { i, m ->
                        if (i == 0) GlassPill(stop, onClick = onStopTask, modifier = m, contentDescription = stopWords, haptic = TkHaptic.REJECT, tier = tier)
                        else GlassPill(done, onClick = onSecureDone, modifier = m, tone = PillTone.PRIMARY,
                            contentDescription = if (twi) "Mawie" else "I've finished entering it", tier = tier)
                    }
                }
            }
        }
        // Listening, heard, asking again — only what the voice pipeline is really doing; voice and touch reach the same decision.
        // Below the pills, so they never move when the microphone opens or closes: a tap aimed at one must not land on its
        // neighbour (emulator, 14 Oct 2026 — the line appearing above pushed the pills down and a tap meant for Type hit Cancel).
        VoiceCueRow(cue, level = { VoiceEngine.level.value }, twi = twi)
    }
}

// ------------------------------------------------------------------ results

@Composable
private fun AnswerBody(
    key: String, p: SurfaceModel.Preview.Answer, steps: List<SurfaceModel.StepView>, expanded: Boolean, tier: GlassTier, twi: Boolean,
    onToggle: () -> Unit, onOpenUrl: (String) -> Unit, onOpenMaps: (String, String?) -> Unit
) {
    Column(Modifier.padding(top = 10.dp)) {
        Reveal(0, key) {
            val text = ScreenText.of(if (expanded) p.body else p.summary)
            // Compact holds 8 lines (it held 4 and cut a sentence the voice had said in full — review, 2 Nov 2026); whenever words
            // are still hidden a visible "Show all" says so, not only a tap on the text that nothing pointed to.
            var cut by remember(text, expanded) { mutableStateOf(false) }
            val readsAloud = LocalReadsAloud.current
            Column {
                // The one figure the answer is about, in large type above it (KeyFigure; none when it isn't clear which). Drawn
                // only: the sentence below holds the same figure and is what TalkBack reads, so nothing is said twice.
                val figure = remember(p.summary) { KeyFigure.of(p.summary) }
                if (figure != null) BigFigure(figure.shown, Modifier.padding(bottom = 6.dp))
                Text(text, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp).answerSized(), color = GlassColors.Text,
                    maxLines = if (expanded) Int.MAX_VALUE else 8, overflow = TextOverflow.Ellipsis,
                    onTextLayout = { r -> cut = r.hasVisualOverflow },
                    modifier = Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                        role = Role.Button, onClickLabel = if (expanded) "Show less" else "Show the whole answer", onClick = onToggle))
                val more = cut || (!expanded && p.body.length > p.summary.length + 2)
                // "Say again" (2 Nov review — the commonest wish of blind users is control over what was just said; ASSETS 2018,
                // CHI 2026): the same as saying "again" or pressing volume-up twice, now something a finger can find.
                if (more || readsAloud) Row(verticalAlignment = Alignment.CenterVertically) {
                    if (readsAloud) Text(
                        "Say again", style = MaterialTheme.typography.labelLarge, color = GlassColors.Accent,
                        modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { VoiceEngine.repeatLast() }
                            .padding(top = 12.dp, bottom = 8.dp, end = 24.dp)
                            .semantics { contentDescription = "Say the answer again" })
                    if (more) Text(
                        "Show all", style = MaterialTheme.typography.labelLarge, color = GlassColors.Accent,
                        modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onToggle)
                            .padding(top = 12.dp, bottom = 8.dp, end = 16.dp)
                            .semantics { contentDescription = "Show the whole answer" })
                }
            }
        }
        if (p.sources.isNotEmpty()) Reveal(1, key) { Sources(p.sources, onOpenUrl) }
        if (expanded && steps.isNotEmpty()) Reveal(2, key) { Column { WhatIDid(); Steps(steps, expanded = true) } }
        if (p.mapLink != null || p.mapQuery != null) Reveal(3, key) {
            GlassPill(if (twi) "Kwan no" else "Open directions", onClick = { onOpenMaps(p.mapQuery ?: "", p.mapLink) },
                modifier = Modifier.padding(top = 12.dp), tone = PillTone.PRIMARY, tier = tier,
                contentDescription = "Open ${p.mapQuery ?: "the place"} in your maps app")
        }
    }
}

/** The gold of a money flow: bright gold on dark glass, a deep gold on light (≥ 3:1 against the card, a UI edge's minimum), the
 *  pair's own attention colour when a reading-colour pair is chosen. */
internal fun moneyEdgeColour(g: GlassPalette): Color = when {
    com.tekyerema.ui.theme.Look.pair() != null -> g.warm
    g.dark -> Spectrum.Gold
    else -> Color(0xFF8A5A00)
}

/** A 3 dp edge drawn over the card's own light, inside its shape. */
internal fun Modifier.moneyEdge(radius: androidx.compose.ui.unit.Dp, colour: Color): Modifier = this.drawWithContent {
    drawContent()
    val w = 3.dp.toPx()
    drawRoundRect(colour, topLeft = Offset(w / 2, w / 2), size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx() - w / 2), style = androidx.compose.ui.graphics.drawscope.Stroke(w))
}

/**
 * A figure in large type (KeyFigure): 52 sp, capped so the phone's own large-text setting can't push it past 80 sp on screen
 * (at 200 % a long amount would wrap mid-number). Tabular digits; hidden from TalkBack (the sentence carries it).
 */
@Composable
internal fun BigFigure(shown: String, modifier: Modifier = Modifier) {
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val size = minOf(52f, 80f / fontScale).sp
    Text(shown, style = TextStyle(fontSize = size, lineHeight = size * 1.12f, fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp, fontFeatureSettings = "tnum"),
        color = GlassColors.Text, maxLines = 2, modifier = modifier.clearAndSetSemantics { })
}

/** Sources as a quiet line of names — the search's own links — each a 48 dp target, not a chip. */
@Composable
private fun Sources(sources: List<AgentStep.Source>, onOpenUrl: (String) -> Unit) {
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("From", style = MaterialTheme.typography.labelMedium, color = GlassColors.TextTertiary)
        sources.take(3).forEachIndexed { i, src ->
            Text(
                (if (i == 0) " " else " · ") + src.title.take(24),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                color = GlassColors.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = "Open source: ${src.title}") { onOpenUrl(src.url) }
                    .clearAndSetSemantics { contentDescription = "Open source: ${src.title}" }   // one clickable node
                    .padding(vertical = 14.dp)
            )
        }
    }
}

@Composable
private fun WhatIDid() {
    Text("What I did", style = MaterialTheme.typography.labelMedium, color = GlassColors.TextTertiary,
        modifier = Modifier.padding(top = 12.dp).semantics { heading() })
}

@Composable
private fun ActionBody(key: String, p: SurfaceModel.Preview.Action, steps: List<SurfaceModel.StepView>, expanded: Boolean, onToggle: () -> Unit) {
    Column(Modifier.padding(top = 10.dp)) {
        Reveal(0, key) {
            Text(ScreenText.of(p.summary), style = MaterialTheme.typography.bodyLarge.answerSized(), color = GlassColors.Text,
                modifier = Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                    role = Role.Button, onClickLabel = if (expanded) "Show less" else "Show what I did", onClick = onToggle))
        }
        if (!p.verified) Reveal(1, key) {
            Text("I couldn't confirm it finished — please check.", style = MaterialTheme.typography.bodyMedium,
                color = GlassColors.Warm, modifier = Modifier.padding(top = 6.dp))
        }
        if (expanded && steps.isNotEmpty()) Reveal(2, key) { Column { WhatIDid(); Steps(steps, expanded = true) } }
    }
}

@Composable
private fun ProblemBody(key: String, p: SurfaceModel.Preview.Problem, cancelled: Boolean, tier: GlassTier, onRetry: (String) -> Unit) {
    Column(Modifier.padding(top = 10.dp)) {
        Reveal(0, key) { Text(ScreenText.of(p.message), style = MaterialTheme.typography.bodyLarge, color = GlassColors.Text) }
        val retry = p.retryGoal
        if (retry != null) Reveal(1, key) {
            GlassPill(if (cancelled) "Start it again" else "Try again", onClick = { onRetry(retry) }, modifier = Modifier.padding(top = 12.dp),
                tone = if (cancelled) PillTone.QUIET else PillTone.PRIMARY, tier = tier,
                contentDescription = if (cancelled) "Start the same request again" else "Try the same request again")
        }
    }
}

// ------------------------------------------------------------------ the follow-up field

/** Revealed by a pull down (or "Ask a follow-up" for TalkBack): a glass field and a mic. It submits through the same entry as
 *  the field at the foot of Home (VoiceEngine.submitTyped); an answer to the agent's question goes the same way. */
@Composable
internal fun FollowUpField(placeholder: String, twi: Boolean, tier: GlassTier, onSubmit: (String) -> Boolean, onSpeak: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val fieldColor = LocalGlass.current.field
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: Throwable) {} }
    fun send() { val t = text.trim(); if (t.isNotEmpty() && onSubmit(t)) text = "" }
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(percent = 50))
                .drawBehind { drawRect(fieldColor) }
                .padding(horizontal = 18.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (text.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = GlassColors.TextTertiary)
            BasicTextField(
                value = text, onValueChange = { text = it },
                textStyle = TextStyle(color = GlassColors.Text, fontSize = 17.sp),
                cursorBrush = SolidColor(Spectrum.Aqua),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = placeholder }
            )
        }
        Spacer(Modifier.width(10.dp))
        if (text.isNotBlank()) GlassPill(if (twi) "Soma" else "Send", onClick = { send() }, compact = true, tone = PillTone.PRIMARY, tier = tier,
            contentDescription = "Send what you typed", haptic = TkHaptic.CONFIRM)
        else GlassPill(if (twi) "Kasa" else "Speak", onClick = onSpeak, compact = true, tier = tier, contentDescription = "Ask by voice")
    }
}

// ------------------------------------------------------------------ button rows (large text, UX-8)

/** Buttons side by side — or one under the other, in the same order, when a word of theirs wouldn't fit its share of the
 *  width at the user's text size. */
@Composable
internal fun ButtonRow(labels: List<String>, weights: List<Float>, button: @Composable (index: Int, modifier: Modifier) -> Unit) {
    val screen = LocalConfiguration.current.screenWidthDp.toFloat()
    val inner = minOf(screen - 24f, 560f) - 36f
    if (ButtonFit.sideBySide(labels, weights, inner, LocalDensity.current.fontScale))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            labels.indices.forEach { i -> button(i, Modifier.weight(weights[i])) }
        }
    else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.indices.forEach { i -> button(i, Modifier.fillMaxWidth()) }
    }
}
