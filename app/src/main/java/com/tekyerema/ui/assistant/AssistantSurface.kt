package com.tekyerema.ui.assistant

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tekyerema.agent.AgentEngine
import com.tekyerema.agent.AgentStep
import com.tekyerema.agent.AgentUiState
import com.tekyerema.agent.AgentStatus
import com.tekyerema.policy.ConfirmGate
import com.tekyerema.ui.components.BigButton
import com.tekyerema.ui.theme.LocalCalmMotion
import com.tekyerema.ui.theme.Tk
import com.tekyerema.ui.theme.TkHaptic
import com.tekyerema.ui.theme.TkMotion
import com.tekyerema.ui.theme.glass
import com.tekyerema.voice.PromptSurface
import com.tekyerema.voice.VoiceEngine
import com.tekyerema.voice.VoiceState
import com.tekyerema.voice.VoiceUiState
import kotlinx.coroutines.delay

/**
 * The assistant surface (docs/assistant-ux): ONE top-anchored glass card that shows what the agent is really doing — the
 * steps of the current run, the question or confirmation it is waiting on, then the result — and morphs between those
 * without being dismissed and re-created. State comes only from [SurfaceModel.reduce]; this file draws it and turns taps into
 * the same calls the rest of the app already makes (AgentEngine.confirm with its sequence number, VoiceEngine.submitTyped,
 * VoiceEngine.respond / start / hush / cancel). No tap here approves anything except the explicit primary button of a
 * confirmation; dismissing never approves.
 *
 * Hosted by [com.tekyerema.ui.home.HomeContent] (Home and the arrive-on-top Voice screen). Over other apps the waiting
 * panel is [com.tekyerema.ui.OverlayConfirm] (DECISIONS UX-2).
 */
/** What the user did to the surface, kept for the process (both screens that host the surface share it, and a screen
 *  recreated after a rotation or a return doesn't bring back a result the user closed). */
object SurfaceStore {
    val local = kotlinx.coroutines.flow.MutableStateFlow(SurfaceModel.Local())
    fun update(f: (SurfaceModel.Local) -> SurfaceModel.Local) { local.value = f(local.value) }
}

@Composable
fun AssistantSurfaceHost(
    voice: VoiceUiState,
    agent: AgentUiState,
    twi: Boolean,
    /** Focus the typing field (a question answered by keyboard, a follow-up typed). */
    onTypeRequest: () -> Unit,
    modifier: Modifier = Modifier,
    /** The surface's visibility, for the host to quieten what would repeat it (the line under the orb). */
    onVisible: (Boolean) -> Unit = {},
    /** The user's "read replies aloud": our own voice then says results and prompts, so TalkBack isn't also told them. */
    readsAloud: Boolean = false
) {
    val context = LocalContext.current
    val local by SurfaceStore.local.collectAsState()
    var expandedKey by remember { mutableStateOf("") }
    // A clock that ticks only when a result's freshness may run out (no per-frame work).
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val inRun = agent.status == AgentStatus.THINKING || agent.status == AgentStatus.ACTING || agent.status == AgentStatus.PAUSED ||
        agent.status == AgentStatus.ASKING || agent.status == AgentStatus.WAITING_CONFIRMATION || agent.status == AgentStatus.SECURE_INPUT
    // What the last committed frame saw (written only after a frame commits, in the SideEffect below).
    val prev = remember { PrevFrame(voice.lastRaw, voice.state, agent.runSeq, voice.spokenAt, inRun) }
    // The panel over other apps (OverlayConfirm) also shows a question or confirmation over our own screen, in this same
    // place; while it is really up — and for the same prompt after it was answered on it — the surface doesn't draw the
    // prompt a second time (TalkBack would read it twice; UX-10). If the panel couldn't be drawn (no accessibility service,
    // no overlay permission), the surface shows the prompt itself.
    val panelUp by com.tekyerema.ui.OverlayConfirm.onScreen.collectAsState()
    val panelPrompt = remember { arrayOf("") }
    val reduced = SurfaceModel.reduce(voice, agent, local, now = maxOf(now, voice.spokenAt), twi = twi)
    val promptKey = reduced.waiting?.let { "${it.kind}|${it.seq}|${it.text}" }
    val state = if (reduced.mode == SurfaceModel.Mode.WAITING && (panelUp || promptKey == panelPrompt[0])) reduced.copy(mode = SurfaceModel.Mode.HIDDEN)
        else reduced
    SideEffect {
        if (panelUp && promptKey != null) panelPrompt[0] = promptKey
        // A NEW request replaces what was on screen BEFORE it (Siri replaces its snippet; UX-6): the user starts talking, or a
        // new request is typed or heard, while no task was running. What the previous frame had is dismissed — never "now":
        // a quick answer that arrives in the same frame as its request must still show (review, 14 Oct 2026). An answer to
        // the agent's own question comes during a run, so it never dismisses anything.
        val newInput = voice.lastRaw.isNotBlank() && voice.lastRaw != prev.lastRaw
        val startedTalking = voice.state == VoiceState.LISTENING && prev.voiceState != VoiceState.LISTENING
        if ((newInput || startedTalking) && !prev.inRun) {
            val run = prev.runSeq; val at = prev.spokenAt
            SurfaceStore.update { l -> l.copy(dismissedRun = run, dismissedAt = maxOf(l.dismissedAt, at)) }
        }
        prev.lastRaw = voice.lastRaw; prev.voiceState = voice.state; prev.runSeq = agent.runSeq; prev.spokenAt = voice.spokenAt; prev.inRun = inRun
    }
    // TalkBack is told what changed through one polite live node (UX-7) — except what our own voice already says aloud:
    // results and prompts when "read replies aloud" is on. Decided by the setting, not by the voice's moment-to-moment state,
    // so switching it back on can't make TalkBack repeat a result the voice has just read.
    val announce = when (state.mode) {
        SurfaceModel.Mode.PRESENTING, SurfaceModel.Mode.FAILED, SurfaceModel.Mode.CANCELLED, SurfaceModel.Mode.WAITING -> !readsAloud
        else -> true
    }
    LaunchedEffect(state.key, state.mode) {
        now = System.currentTimeMillis()
        if (state.mode == SurfaceModel.Mode.PRESENTING || state.mode == SurfaceModel.Mode.FAILED || state.mode == SurfaceModel.Mode.CANCELLED) {
            delay(SurfaceModel.FRESH_MS + 1_000); now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(state.visible) { onVisible(state.visible) }

    fun open(uri: String) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
    }
    AssistantSurface(
        state = state,
        expanded = expandedKey == state.key,
        twi = twi,
        modifier = modifier,
        onExpand = { e -> expandedKey = if (e) state.key else "" },
        onDismiss = {
            if (state.speaking) VoiceEngine.hush()
            val t = System.currentTimeMillis()
            SurfaceStore.update { l -> if (state.key.startsWith("reply:")) l.copy(dismissedReplyAt = voice.spokenAt, dismissedAt = t)
                else l.copy(dismissedRun = state.runSeq, dismissedAt = t) }
            expandedKey = ""
        },
        announce = announce,
        onStopTask = { VoiceEngine.cancel(); AgentEngine.stop() },
        onStopSpeaking = { VoiceEngine.hush() },
        onConfirm = { approved, seq -> AgentEngine.confirm(approved, seq) },
        // A choice is that answer only while the agent is still asking (ChoiceGate): a late or double tap must never reach a
        // confirmation, where the label would be read as a typed yes/no or a change.
        onChoice = { choice -> if (ChoiceGate.accepts(AgentEngine.state.value.status, engineQuestion = true)) VoiceEngine.submitTyped(choice) },
        onAnswerByVoice = { VoiceEngine.respond() },
        onType = onTypeRequest,
        onSecureDone = { AgentEngine.secureInputDone() },
        onRetry = { goal -> VoiceEngine.submitTyped(goal) },
        onFollowUp = { VoiceEngine.start() },
        onOpenUrl = { open(it) },
        onOpenMaps = { query, link -> open(link ?: MapsLink.of(query)) }
    )
}

/** What the previous committed frame of the surface host saw (plain fields, written only in its SideEffect). */
private class PrevFrame(var lastRaw: String, var voiceState: VoiceState, var runSeq: Long, var spokenAt: Long, var inRun: Boolean)

/**
 * Whether a tapped choice may be sent as the answer. Only while the agent is still asking (a question from a task), or while
 * nothing waits at all (a question at the end of a reply starts a new turn) — never while a confirmation or the secure step
 * waits: a typed label there is read as "yes", "no" or a change to the confirmation (review, 14 Oct 2026). Pure.
 */
object ChoiceGate {
    fun accepts(status: AgentStatus, engineQuestion: Boolean): Boolean = when (status) {
        AgentStatus.WAITING_CONFIRMATION, AgentStatus.SECURE_INPUT -> false
        AgentStatus.ASKING -> true
        else -> !engineQuestion
    }
}

/** A maps search the phone's maps app runs near the user ("geo:0,0?q=…"); nothing about the place is invented here. */
object MapsLink {
    fun of(query: String): String = "geo:0,0?q=" + java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
}

@Composable
fun AssistantSurface(
    state: SurfaceModel.State,
    expanded: Boolean,
    twi: Boolean,
    modifier: Modifier = Modifier,
    onExpand: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onStopTask: () -> Unit,
    onStopSpeaking: () -> Unit,
    onConfirm: (Boolean, Long) -> Unit,
    onChoice: (String) -> Unit,
    onAnswerByVoice: () -> Unit,
    onType: () -> Unit,
    onSecureDone: () -> Unit,
    onRetry: (String) -> Unit,
    onFollowUp: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenMaps: (String, String?) -> Unit,
    announce: Boolean = true
) {
    val calm = LocalCalmMotion.current
    AnimatedVisibility(
        visible = state.visible,
        // A readable width in landscape and on tablets (UX-8); the host centres it.
        modifier = modifier.widthIn(max = 560.dp),
        // Enters from the top and leaves the way it came (HIG Motion: dismiss on the entry axis); calm = a fade only.
        enter = if (calm) fadeIn(tween(160)) else fadeIn(tween(220)) + slideInVertically(spring(0.8f, 380f, IntOffset(1, 1))) { -it / 3 },
        exit = if (calm) fadeOut(tween(120)) else fadeOut(tween(160)) + slideOutVertically(spring(1f, 900f, IntOffset(1, 1))) { -it / 4 }
    ) {
        val summary = spokenSummary(state)
        // At most 80 % of the screen's height: the header stays put and the body scrolls (UX-8 — large text, a long question).
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .background(Tk.palette.ink.copy(alpha = 0.95f), RoundedCornerShape(28.dp))
                .glass(RoundedCornerShape(28.dp), strong = true, elevation = 10.dp)
                .clip(RoundedCornerShape(28.dp))
                .animateContentSize(if (calm) tween(0) else spring(0.9f, 420f, IntSize(1, 1)))
                .semantics {
                    paneTitle = "Tɛkyerɛma"
                    stateDescription = if (expanded) "Expanded" else "Compact"
                }
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Header(state, expanded, summary, onExpand, onDismiss, onStopTask, onStopSpeaking, announce)
            // Each new state starts at its top.
            val bodyScroll = remember(state.key, state.mode) { ScrollState(0) }
            AnimatedContent(
                targetState = state,
                modifier = Modifier.weight(1f, fill = false).verticalScroll(bodyScroll),
                contentKey = { "${it.key}|${it.mode}|${it.waiting?.seq}" },
                transitionSpec = {
                    (if (calm) fadeIn(tween(160)) togetherWith fadeOut(tween(90))
                    else (fadeIn(tween(220, delayMillis = 60)) + slideInVertically(spring(0.8f, 380f, IntOffset(1, 1))) { it / 8 }) togetherWith
                        fadeOut(tween(110))) using SizeTransform(clip = false) { _, _ -> if (calm) tween(0) else spring(0.9f, 420f, IntSize(1, 1)) }
                },
                label = "surface body"
            ) { s ->
                Body(s, expanded, twi, onExpand, onDismiss, onConfirm, onChoice, onAnswerByVoice, onType, onSecureDone, onStopTask,
                    onRetry, onFollowUp, onOpenUrl, onOpenMaps)
            }
        }
    }
}

/** The one line TalkBack reads when the surface changes: the title and, for a result or a question, its words. */
internal fun spokenSummary(s: SurfaceModel.State): String = when {
    s.waiting != null -> "${s.title}. ${s.waiting.text}"
    s.preview is SurfaceModel.Preview.Answer -> "${s.title}. ${s.preview.summary}"
    s.preview is SurfaceModel.Preview.Action -> "${s.title}. ${s.preview.summary}"
    s.preview is SurfaceModel.Preview.Problem -> "${s.title}. ${s.preview.message}"
    else -> s.title
}

@Composable
private fun Header(
    s: SurfaceModel.State, expanded: Boolean, summary: String,
    onExpand: (Boolean) -> Unit, onDismiss: () -> Unit, onStopTask: () -> Unit, onStopSpeaking: () -> Unit,
    announce: Boolean
) {
    val working = s.mode == SurfaceModel.Mode.PROCESSING || s.mode == SurfaceModel.Mode.WORKING
    val presenting = s.mode == SurfaceModel.Mode.PRESENTING || s.mode == SurfaceModel.Mode.FAILED || s.mode == SurfaceModel.Mode.CANCELLED
    // "More" only when expanding shows something more: the whole answer, the steps taken, or a title cut short (a "Stopped"
    // card offered More with nothing behind it — emulator, 14 Oct 2026).
    val longTitle = s.title.length > 44
    val canExpand = when (val p = s.preview) {
        is SurfaceModel.Preview.Answer -> presenting && (p.body.length > p.summary.length || s.steps.isNotEmpty() || longTitle)
        is SurfaceModel.Preview.Action -> presenting && (s.steps.isNotEmpty() || longTitle)
        is SurfaceModel.Preview.Problem -> false
        null -> s.mode == SurfaceModel.Mode.WORKING && s.steps.count { !it.active } > SurfaceModel.COMPACT_STEPS
    }
    // At large text the title gets its own line and the controls sit under it (at 200 % "More" and "Done" left the title
    // two words wide — emulator, 14 Oct 2026).
    val stacked = LocalDensity.current.fontScale >= 1.5f
    val title: @Composable (Modifier) -> Unit = { m ->
        // One stable, polite live node carries the meaning; the rows below are read on demand, never announced.
        Text(
            s.title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = Tk.Text,
            maxLines = if (expanded || stacked) 4 else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = m.semantics {
                heading()
                // The real title — the step running now, "Paused", or the result with its words — politely announced as it
                // changes (in the app, live steps are few: a knowledge question's searches). Not announced when our own voice
                // says it (results and prompts with "read replies aloud" on): TalkBack would say the same words over it.
                if (announce) liveRegion = LiveRegionMode.Polite
                contentDescription = if (working) s.title else summary
            }
        )
    }
    val actions: @Composable () -> Unit = {
        when {
            working -> SmallAction("Stop", "Stop the task", onStopTask)
            s.speaking -> SmallAction("Quiet", "Stop reading — the result stays here", onStopSpeaking)
            canExpand -> SmallAction(if (expanded) "Less" else "More", if (expanded) "Show less" else "Show more") { onExpand(!expanded) }
        }
        if (presenting) SmallAction("Done", "Done — close this result", onDismiss)
    }
    if (stacked) Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Glyph(glyphOf(s), active = working && !s.paused, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            title(Modifier.weight(1f))
        }
        Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) { actions() }
    } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Glyph(glyphOf(s), active = working && !s.paused, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        title(Modifier.weight(1f))
        actions()
    }
}

@Composable
private fun Body(
    s: SurfaceModel.State, expanded: Boolean, twi: Boolean,
    onExpand: (Boolean) -> Unit, onDismiss: () -> Unit,
    onConfirm: (Boolean, Long) -> Unit, onChoice: (String) -> Unit, onAnswerByVoice: () -> Unit, onType: () -> Unit,
    onSecureDone: () -> Unit, onStopTask: () -> Unit,
    onRetry: (String) -> Unit, onFollowUp: () -> Unit, onOpenUrl: (String) -> Unit, onOpenMaps: (String, String?) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        when (s.mode) {
            // The step running now is the title; the rows are the steps before it (one row repeated the title).
            SurfaceModel.Mode.WORKING, SurfaceModel.Mode.PROCESSING -> Steps(s.steps.filterNot { it.active }, expanded)
            SurfaceModel.Mode.WAITING -> s.waiting?.let { Waiting(it, twi, onConfirm, onChoice, onAnswerByVoice, onType, onSecureDone, onStopTask) }
            else -> when (val p = s.preview) {
                is SurfaceModel.Preview.Answer -> AnswerPreview(p, s.steps, expanded, onExpand, onFollowUp, onOpenUrl, onOpenMaps)
                is SurfaceModel.Preview.Action -> ActionPreview(p, s.steps, expanded, onFollowUp)
                is SurfaceModel.Preview.Problem -> ProblemPreview(p, s.mode == SurfaceModel.Mode.CANCELLED, onRetry, onDismiss)
                null -> {}
            }
        }
    }
}

// ------------------------------------------------------------------ working

@Composable
private fun Steps(steps: List<SurfaceModel.StepView>, expanded: Boolean) {
    if (steps.isEmpty()) return
    val shown = if (expanded) steps else steps.takeLast(SurfaceModel.COMPACT_STEPS)
    Column(Modifier.padding(top = 10.dp, start = 34.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        shown.forEach { step -> key(step.key) { StepRow(step) } }
    }
}

@Composable
private fun key(k: String, content: @Composable () -> Unit) = androidx.compose.runtime.key(k) { content() }

@Composable
private fun StepRow(step: SurfaceModel.StepView) {
    val (glyph, color) = when {
        step.active -> Glyph.ACTIVE to Tk.Text
        step.state == AgentStep.State.DONE -> Glyph.DONE to Tk.TextSecondary
        step.state == AgentStep.State.FAILED -> Glyph.FAILED to Tk.TextSecondary
        else -> Glyph.NEUTRAL to Tk.TextTertiary
    }
    val stateWord = when {
        step.active -> "in progress"
        step.state == AgentStep.State.DONE -> "done"
        step.state == AgentStep.State.FAILED -> "didn't work"
        else -> ""
    }
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) { stateDescription = stateWord }) {
        Glyph(glyph, active = step.active, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(10.dp))
        Text(step.label, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ------------------------------------------------------------------ waiting on the user

@Composable
private fun Waiting(
    w: SurfaceModel.Waiting, twi: Boolean,
    onConfirm: (Boolean, Long) -> Unit, onChoice: (String) -> Unit, onAnswerByVoice: () -> Unit, onType: () -> Unit,
    onSecureDone: () -> Unit, onStopTask: () -> Unit
) {
    Column(Modifier.padding(top = 10.dp)) {
        val shown = ScreenText.of(w.text)
        Text(shown, style = MaterialTheme.typography.bodyLarge, color = Tk.Text,
            modifier = Modifier.semantics { contentDescription = shown })
        Spacer(Modifier.height12())
        // The same spoken words as the panel over other apps (OverlayConfirm), in the user's one language.
        val stopWords = if (twi) "Gyae adwuma no" else "Stop the task"
        when (w.kind) {
            PromptSurface.Kind.CONFIRM -> {
                val (no, yes) = ConfirmWords.buttons(w.text, twi)
                // The verb counts only once this confirmation has been on screen for a moment (ConfirmGate.armed, as on the
                // panel over other apps): a tap meant for the card as it arrives can't approve anything. Before that the
                // button gives a plain tick, not the confirm buzz.
                val shownAt = remember(w.seq) { android.os.SystemClock.uptimeMillis() }
                var armed by remember(w.seq) { mutableStateOf(false) }
                LaunchedEffect(w.seq) { delay(ConfirmGate.armMs()); armed = true }
                ButtonRow(listOf(no, yes), listOf(1f, 1.4f)) { i, m ->
                    // J-02: these answer THIS confirmation (its own number), never whatever is pending at tap time.
                    if (i == 0) BigButton(no, onClick = { onConfirm(false, w.seq) }, modifier = m, filled = false,
                        contentDescription = if (twi) "Daabi, nnyɛ" else "$no — don't do it", haptic = TkHaptic.REJECT)
                    else BigButton(yes, onClick = { if (ConfirmGate.armed(shownAt, android.os.SystemClock.uptimeMillis())) onConfirm(true, w.seq) },
                        modifier = m, contentDescription = if (twi) "Aane, yɛ" else "$yes — do it",
                        haptic = if (armed) TkHaptic.CONFIRM else TkHaptic.TAP)
                }
            }
            PromptSurface.Kind.QUESTION -> {
                val choices = QuestionChoices.of(w.text)
                if (choices.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEach { c -> BigButton(c, onClick = { onChoice(c) }, modifier = Modifier.fillMaxWidth(), filled = false,
                        contentDescription = (if (twi) "Bua: " else "Answer: ") + c) }
                }
                if (choices.isNotEmpty()) Spacer(Modifier.height12())
                val (stop, answer) = PromptSurface.buttons(PromptSurface.Kind.QUESTION, twi)
                val type = if (twi) "Twerɛ" else "Type"
                ButtonRow(listOf(stop, type, answer), listOf(1f, 1f, 1.3f)) { i, m ->
                    when (i) {
                        0 -> BigButton(stop, onClick = onStopTask, modifier = m, filled = false, contentDescription = stopWords)
                        1 -> BigButton(type, onClick = onType, modifier = m, filled = false,
                            contentDescription = if (twi) "Twerɛ wo mmuae" else "Type your answer")
                        else -> BigButton(answer, onClick = onAnswerByVoice, modifier = m,
                            contentDescription = if (twi) "Bua wɔ kasa mu" else "Answer by voice")
                    }
                }
            }
            PromptSurface.Kind.SECURE -> {
                val (stop, done) = PromptSurface.buttons(PromptSurface.Kind.SECURE, twi)
                ButtonRow(listOf(stop, done), listOf(1f, 1.4f)) { i, m ->
                    if (i == 0) BigButton(stop, onClick = onStopTask, modifier = m, filled = false, contentDescription = stopWords)
                    else BigButton(done, onClick = onSecureDone, modifier = m,
                        contentDescription = if (twi) "Mawie" else "I've finished entering it")
                }
            }
        }
    }
}

// ------------------------------------------------------------------ results

@Composable
private fun AnswerPreview(
    p: SurfaceModel.Preview.Answer, steps: List<SurfaceModel.StepView>, expanded: Boolean,
    onExpand: (Boolean) -> Unit, onFollowUp: () -> Unit, onOpenUrl: (String) -> Unit, onOpenMaps: (String, String?) -> Unit
) {
    Column(Modifier.padding(top = 10.dp)) {
        val text = ScreenText.of(if (expanded) p.body else p.summary)
        // Expanded = the whole answer; the card's body scrolls (UX-8), so no inner scroll fights it.
        Box(Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = if (expanded) "Show less" else "Show the whole answer",
                enabled = p.body.length > p.summary.length) { onExpand(!expanded) }) {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = Tk.Text,
                maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
        }
        if (p.sources.isNotEmpty()) {
            Spacer(Modifier.height12())
            Text("Sources", style = MaterialTheme.typography.labelMedium, color = Tk.TextTertiary,
                modifier = Modifier.semantics { heading() })
            Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                p.sources.forEach { src -> SourceChip(src.title, "Open source: ${src.title}") { onOpenUrl(src.url) } }
            }
        }
        if (expanded && steps.isNotEmpty()) {
            Spacer(Modifier.height12())
            Text("What I did", style = MaterialTheme.typography.labelMedium, color = Tk.TextTertiary, modifier = Modifier.semantics { heading() })
            Steps(steps, expanded = true)
        }
        Spacer(Modifier.height12())
        val follow = "Ask a follow-up"
        if (p.mapLink != null || p.mapQuery != null) ButtonRow(listOf("Open in Maps", follow), listOf(1f, 1f)) { i, m ->
            if (i == 0) BigButton("Open in Maps", onClick = { onOpenMaps(p.mapQuery ?: "", p.mapLink) }, modifier = m, filled = false,
                contentDescription = "Open ${p.mapQuery ?: "the place"} in your maps app")
            else BigButton(follow, onClick = onFollowUp, modifier = m, filled = false, contentDescription = "Ask a follow-up question by voice")
        } else BigButton(follow, onClick = onFollowUp, modifier = Modifier.fillMaxWidth(), contentDescription = "Ask a follow-up question by voice")
    }
}

@Composable
private fun ActionPreview(p: SurfaceModel.Preview.Action, steps: List<SurfaceModel.StepView>, expanded: Boolean, onFollowUp: () -> Unit) {
    Column(Modifier.padding(top = 10.dp)) {
        Text(ScreenText.of(p.summary), style = MaterialTheme.typography.bodyLarge, color = Tk.Text)
        if (!p.verified) Text("I couldn't confirm it finished — please check.", style = MaterialTheme.typography.bodyMedium,
            color = Tk.Attention, modifier = Modifier.padding(top = 6.dp))
        if (expanded && steps.isNotEmpty()) {
            Spacer(Modifier.height12())
            Text("What I did", style = MaterialTheme.typography.labelMedium, color = Tk.TextTertiary, modifier = Modifier.semantics { heading() })
            Steps(steps, expanded = true)
        }
        Spacer(Modifier.height12())
        BigButton("Ask a follow-up", onClick = onFollowUp, modifier = Modifier.fillMaxWidth(), filled = false,
            contentDescription = "Ask a follow-up question by voice")
    }
}

@Composable
private fun ProblemPreview(p: SurfaceModel.Preview.Problem, cancelled: Boolean, onRetry: (String) -> Unit, onDismiss: () -> Unit) {
    Column(Modifier.padding(top = 10.dp)) {
        Text(ScreenText.of(p.message), style = MaterialTheme.typography.bodyLarge, color = Tk.Text)
        Spacer(Modifier.height12())
        val retry = p.retryGoal
        if (retry != null && !cancelled) BigButton("Try again", onClick = { onRetry(retry) }, modifier = Modifier.fillMaxWidth(),
            contentDescription = "Try the same request again")
        else if (retry != null) BigButton("Start it again", onClick = { onRetry(retry) }, modifier = Modifier.fillMaxWidth(), filled = false,
            contentDescription = "Start the same request again")
    }
}

// ------------------------------------------------------------------ small parts

@Composable
private fun SmallAction(label: String, description: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(interaction, indication = null, role = Role.Button, onClickLabel = description, onClick = onClick)
            // ONE node for TalkBack: the spoken label replaces the visible word on the clickable node itself (as BigButton does,
            // Gate G). A plain `semantics` here made a second, unclickable node under it (emulator dump, 14 Oct 2026).
            .clearAndSetSemantics { contentDescription = description }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Tk.Cyan)
    }
}

@Composable
private fun SourceChip(title: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .glass(RoundedCornerShape(24.dp), elevation = 0.dp)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .clearAndSetSemantics { contentDescription = description }   // one clickable node (see SmallAction)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(title.take(32), style = MaterialTheme.typography.labelLarge, color = Tk.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun Modifier.height12(): Modifier = this.then(Modifier.padding(top = 12.dp))

/** Buttons side by side — or one under the other, in the same order, when a word of theirs wouldn't fit its share of the
 *  width at the user's text size (UX-8: large text is common among the people this app is for). */
@Composable
private fun ButtonRow(labels: List<String>, weights: List<Float>, button: @Composable (index: Int, modifier: Modifier) -> Unit) {
    val screen = LocalConfiguration.current.screenWidthDp.toFloat()
    val inner = minOf(screen - 24f, 560f) - 36f   // the host's margins, the card's width cap, the card's padding
    if (ButtonFit.sideBySide(labels, weights, inner, LocalDensity.current.fontScale))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            labels.indices.forEach { i -> button(i, Modifier.weight(weights[i])) }
        }
    else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.indices.forEach { i -> button(i, Modifier.fillMaxWidth()) }
    }
}

/**
 * Whether a row of buttons fits side by side at the user's text size: each button gets its weight's share of the width and
 * needs its padding plus its longest word (a word never breaks mid-way; a label of several words may wrap). Pure.
 */
object ButtonFit {
    /** About one character of the buttons' 17 sp semibold label at 100 % text, in dp — a little generous: stack early. */
    private const val CHAR_DP = 10f
    /** BigButton's horizontal padding, both sides. */
    private const val PAD_DP = 48f

    fun sideBySide(labels: List<String>, weights: List<Float>, widthDp: Float, fontScale: Float, gapDp: Float = 10f): Boolean {
        if (labels.size < 2 || weights.size != labels.size) return true
        val total = weights.sum()
        if (total <= 0f) return true
        val free = widthDp - gapDp * (labels.size - 1)
        return labels.indices.all { i ->
            val longest = labels[i].split(' ').maxOf { it.length }
            free * weights[i] / total >= PAD_DP + longest * CHAR_DP * fontScale
        }
    }
}

internal enum class Glyph { ACTIVE, DONE, FAILED, NEUTRAL, ASK, RESULT, STOPPED }

internal fun glyphOf(s: SurfaceModel.State): Glyph = when (s.mode) {
    SurfaceModel.Mode.PROCESSING, SurfaceModel.Mode.WORKING -> if (s.paused) Glyph.NEUTRAL else Glyph.ACTIVE
    SurfaceModel.Mode.WAITING -> Glyph.ASK
    SurfaceModel.Mode.FAILED -> Glyph.FAILED
    SurfaceModel.Mode.CANCELLED -> Glyph.STOPPED
    SurfaceModel.Mode.PRESENTING -> if ((s.preview as? SurfaceModel.Preview.Action)?.verified == false) Glyph.NEUTRAL else Glyph.RESULT
    SurfaceModel.Mode.HIDDEN -> Glyph.NEUTRAL
}

/**
 * The small sign beside a title or a step. Only an ACTIVE glyph moves — a slow breath of opacity, nothing that loops in
 * space — and in calm motion it holds still (its meaning is also in the words beside it).
 */
@Composable
internal fun Glyph(g: Glyph, active: Boolean, modifier: Modifier = Modifier) {
    val calm = LocalCalmMotion.current
    val accent = Tk.Cyan
    val muted = Tk.TextTertiary
    val attention = Tk.Attention
    val ok = Tk.Success
    // The breath is read in the layer, not in composition: a layer update a frame, never a recomposition of the card.
    val breath = if (active && !calm) rememberInfiniteTransition(label = "breath")
        .animateFloat(0.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "breath alpha") else null
    Canvas(modifier.clearAndSetSemantics { }.graphicsLayer { this.alpha = breath?.value ?: 1f }) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        val stroke = Stroke(width = r * 0.22f, cap = StrokeCap.Round)
        when (g) {
            Glyph.ACTIVE -> { drawCircle(accent.copy(alpha = 0.25f), r, c); drawCircle(accent, r * 0.5f, c) }
            Glyph.RESULT -> drawCircle(accent, r * 0.55f, c)
            Glyph.DONE -> {
                drawLine(ok, Offset(c.x - r * 0.55f, c.y), Offset(c.x - r * 0.1f, c.y + r * 0.45f), stroke.width, StrokeCap.Round)
                drawLine(ok, Offset(c.x - r * 0.1f, c.y + r * 0.45f), Offset(c.x + r * 0.6f, c.y - r * 0.45f), stroke.width, StrokeCap.Round)
            }
            Glyph.FAILED, Glyph.STOPPED -> {
                drawLine(attention, Offset(c.x - r * 0.45f, c.y - r * 0.45f), Offset(c.x + r * 0.45f, c.y + r * 0.45f), stroke.width, StrokeCap.Round)
                drawLine(attention, Offset(c.x + r * 0.45f, c.y - r * 0.45f), Offset(c.x - r * 0.45f, c.y + r * 0.45f), stroke.width, StrokeCap.Round)
            }
            Glyph.ASK -> { drawCircle(attention.copy(alpha = 0.25f), r, c); drawCircle(attention, r * 0.5f, c) }
            Glyph.NEUTRAL -> drawCircle(muted, r * 0.4f, c, style = stroke)
        }
    }
}

/** A line built for the voice, as the screen shows it: its pause marks (voice/SpokenText) become line breaks. Pure. */
object ScreenText {
    private val AROUND_BREAK = Regex("[ \\t]*\\n[ \\t]*")
    private val MANY_BREAKS = Regex("\\n{3,}")
    /** The voice spaces digits out ("ending 3 6 1 1"); on screen a group stays on one line (no-break spaces). */
    private val SPACED_DIGITS = Regex("(?<=\\b\\d) (?=\\d\\b)")
    fun of(text: String): String = text.replace(com.tekyerema.voice.SpokenText.BREAK, '\n').replace(com.tekyerema.voice.SpokenText.PAUSE, '\n')
        .replace(AROUND_BREAK, "\n").replace(MANY_BREAKS, "\n\n").replace(SPACED_DIGITS, " ").trim()
}

/** A confirmation's buttons: Cancel and a VERB that says what will happen ("Send", "Call", "Buy") — HIG Snippets. Pure. */
object ConfirmWords {
    private val VERBS = listOf("Send", "Call", "Buy", "Pay", "Open", "Turn", "Delete", "Reply", "Text", "Share", "Post", "Install",
        "Save", "Set", "Book", "Order", "Transfer", "Top up", "Dial", "Remove", "Clear", "Start")
    /** "… Send it?" / "… Call him?" — the verb of a closing question. */
    private val CLOSING = Regex("(?i)\\b([a-z]+(?: up)?)\\s+(?:it|this|that|them|him|her)\\s*\\?\\s*$")

    fun buttons(text: String, twi: Boolean): Pair<String, String> {
        if (twi) return PromptSurface.buttons(PromptSurface.Kind.CONFIRM, twi = true).let { it[0] to it[1] }
        val plain = ScreenText.of(text).trim()
        // The closing question names the act most exactly ("…The message: "…". Send it?" → Send); else the first word.
        val closing = CLOSING.find(plain)?.groupValues?.get(1)
        val first = plain.trimStart('“', '"').lowercase()
        val verb = VERBS.firstOrNull { v -> closing.equals(v, ignoreCase = true) }
            ?: VERBS.firstOrNull { v -> first.startsWith(v.lowercase() + " ") || first.startsWith(v.lowercase() + "?") }
        return "Cancel" to (verb ?: "Yes, do it")
    }
}

/**
 * The choices a question names, for tapping instead of saying: "Which Prince: Prince Tesano AG youth Sec, Prince Ice P, or
 * Prince Alhassan ending 323? Or someone else?" → the three names. Only clear lists of 2–5 short options; anything else
 * (open questions, money amounts, a single yes/no) shows no buttons and is answered by voice or keyboard. Pure.
 */
object QuestionChoices {
    fun of(question: String): List<String> {
        val q = question.trim()
        // "Which X: a, b, or c?" / "Which X do you want? a, or b?"
        val list = Regex("^(?i)which\\b[^:?]{0,60}[:?]\\s*(.+?)\\?").find(q)?.groupValues?.get(1) ?: return emptyList()
        val parts = list.split(Regex(",\\s*(?:or\\s+)?|\\s+or\\s+")).map { it.trim().trimEnd('.', ',') }.filter { it.isNotEmpty() }
        if (parts.size !in 2..5) return emptyList()
        if (parts.any { it.length > 48 || it.count { ch -> ch.isDigit() } >= 7 }) return emptyList()
        return parts.distinct()
    }
}
