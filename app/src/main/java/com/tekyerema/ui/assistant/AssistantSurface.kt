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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
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
@Composable
fun AssistantSurfaceHost(
    voice: VoiceUiState,
    agent: AgentUiState,
    twi: Boolean,
    /** Focus the typing field (a question answered by keyboard, a follow-up typed). */
    onTypeRequest: () -> Unit,
    modifier: Modifier = Modifier,
    /** The surface's visibility, for the host to quieten what would repeat it (the line under the orb). */
    onVisible: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    var local by remember { mutableStateOf(SurfaceModel.Local()) }
    var expandedKey by remember { mutableStateOf("") }
    // A clock that ticks only when a result's freshness may run out (no per-frame work).
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val inRun = agent.status == AgentStatus.THINKING || agent.status == AgentStatus.ACTING || agent.status == AgentStatus.PAUSED ||
        agent.status == AgentStatus.ASKING || agent.status == AgentStatus.WAITING_CONFIRMATION || agent.status == AgentStatus.SECURE_INPUT
    // A NEW request replaces the previous result (Siri replaces its snippet): when the user starts talking with no task running.
    LaunchedEffect(voice.state) {
        if (voice.state == VoiceState.LISTENING && !inRun) {
            val t = System.currentTimeMillis()
            local = local.copy(dismissedRun = agent.runSeq, dismissedAt = t)
            now = t
        }
    }
    val state = SurfaceModel.reduce(voice, agent, local, now = maxOf(now, voice.spokenAt), twi = twi)
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
            local = if (state.key.startsWith("reply:")) local.copy(dismissedReplyAt = voice.spokenAt, dismissedAt = t)
                else local.copy(dismissedRun = state.runSeq, dismissedAt = t)
            expandedKey = ""
        },
        onStopTask = { VoiceEngine.cancel(); AgentEngine.stop() },
        onStopSpeaking = { VoiceEngine.hush() },
        onConfirm = { approved, seq -> AgentEngine.confirm(approved, seq) },
        onChoice = { choice -> VoiceEngine.submitTyped(choice) },
        onAnswerByVoice = { VoiceEngine.respond() },
        onType = onTypeRequest,
        onSecureDone = { AgentEngine.secureInputDone() },
        onRetry = { goal -> VoiceEngine.submitTyped(goal) },
        onFollowUp = { VoiceEngine.start() },
        onOpenUrl = { open(it) },
        onOpenMaps = { query, link -> open(link ?: MapsLink.of(query)) }
    )
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
    onOpenMaps: (String, String?) -> Unit
) {
    val calm = LocalCalmMotion.current
    AnimatedVisibility(
        visible = state.visible,
        modifier = modifier,
        // Enters from the top and leaves the way it came (HIG Motion: dismiss on the entry axis); calm = a fade only.
        enter = if (calm) fadeIn(tween(160)) else fadeIn(tween(220)) + slideInVertically(spring(0.8f, 380f, IntOffset(1, 1))) { -it / 3 },
        exit = if (calm) fadeOut(tween(120)) else fadeOut(tween(160)) + slideOutVertically(spring(1f, 900f, IntOffset(1, 1))) { -it / 4 }
    ) {
        val summary = spokenSummary(state)
        Column(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(28.dp), strong = true, elevation = 10.dp)
                .clip(RoundedCornerShape(28.dp))
                .animateContentSize(if (calm) tween(0) else spring(0.9f, 420f, IntSize(1, 1)))
                .semantics {
                    paneTitle = "Tɛkyerɛma"
                    stateDescription = if (expanded) "Expanded" else "Compact"
                }
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Header(state, expanded, summary, onExpand, onDismiss, onStopTask, onStopSpeaking)
            AnimatedContent(
                targetState = state,
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
    onExpand: (Boolean) -> Unit, onDismiss: () -> Unit, onStopTask: () -> Unit, onStopSpeaking: () -> Unit
) {
    val working = s.mode == SurfaceModel.Mode.PROCESSING || s.mode == SurfaceModel.Mode.WORKING
    val presenting = s.mode == SurfaceModel.Mode.PRESENTING || s.mode == SurfaceModel.Mode.FAILED || s.mode == SurfaceModel.Mode.CANCELLED
    val canExpand = presenting || (s.mode == SurfaceModel.Mode.WORKING && s.steps.size > SurfaceModel.COMPACT_STEPS)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Glyph(glyphOf(s), active = working, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        // One stable, polite live node carries the meaning; the rows below are read on demand, never announced.
        Text(
            s.title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = Tk.Text,
            maxLines = if (expanded) 4 else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics {
                heading()
                liveRegion = LiveRegionMode.Polite
                contentDescription = summary
            }
        )
        when {
            working -> SmallAction("Stop", "Stop the task", onStopTask)
            s.speaking -> SmallAction("Quiet", "Stop reading — the result stays here", onStopSpeaking)
            canExpand -> SmallAction(if (expanded) "Less" else "More", if (expanded) "Show less" else "Show more") { onExpand(!expanded) }
        }
        if (presenting) SmallAction("Done", "Done — close this result", onDismiss)
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
            SurfaceModel.Mode.WORKING, SurfaceModel.Mode.PROCESSING -> Steps(s.steps, expanded)
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
        Text(w.text, style = MaterialTheme.typography.bodyLarge, color = Tk.Text,
            modifier = Modifier.semantics { contentDescription = w.text })
        Spacer(Modifier.height12())
        when (w.kind) {
            PromptSurface.Kind.CONFIRM -> {
                val (no, yes) = ConfirmWords.buttons(w.text, twi)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // J-02: these answer THIS confirmation (its own number), never whatever is pending at tap time.
                    BigButton(no, onClick = { onConfirm(false, w.seq) }, modifier = Modifier.weight(1f), filled = false,
                        contentDescription = "$no — don't do it", haptic = TkHaptic.REJECT)
                    BigButton(yes, onClick = { onConfirm(true, w.seq) }, modifier = Modifier.weight(1.4f), haptic = TkHaptic.CONFIRM)
                }
            }
            PromptSurface.Kind.QUESTION -> {
                val choices = QuestionChoices.of(w.text)
                if (choices.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEach { c -> BigButton(c, onClick = { onChoice(c) }, modifier = Modifier.fillMaxWidth(), filled = false,
                        contentDescription = "Answer: $c") }
                }
                if (choices.isNotEmpty()) Spacer(Modifier.height12())
                val (stop, answer) = PromptSurface.buttons(PromptSurface.Kind.QUESTION, twi)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigButton(stop, onClick = onStopTask, modifier = Modifier.weight(1f), filled = false, contentDescription = "Stop the task")
                    BigButton("Type", onClick = onType, modifier = Modifier.weight(1f), filled = false, contentDescription = "Type your answer")
                    BigButton(answer, onClick = onAnswerByVoice, modifier = Modifier.weight(1.3f), contentDescription = "Answer by voice")
                }
            }
            PromptSurface.Kind.SECURE -> {
                val (stop, done) = PromptSurface.buttons(PromptSurface.Kind.SECURE, twi)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigButton(stop, onClick = onStopTask, modifier = Modifier.weight(1f), filled = false, contentDescription = "Stop the task")
                    BigButton(done, onClick = onSecureDone, modifier = Modifier.weight(1.4f), contentDescription = "I've finished entering it")
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
        val text = if (expanded) p.body else p.summary
        Box(Modifier.fillMaxWidth().heightIn(max = if (expanded) 360.dp else 160.dp)
            .then(if (expanded) Modifier.verticalScroll(rememberScrollState()) else Modifier)
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val maps = p.mapLink != null || p.mapQuery != null
            if (maps) BigButton("Open in Maps", onClick = { onOpenMaps(p.mapQuery ?: "", p.mapLink) }, modifier = Modifier.weight(1f),
                filled = false, contentDescription = "Open ${p.mapQuery ?: "the place"} in your maps app")
            BigButton("Ask a follow-up", onClick = onFollowUp, modifier = Modifier.weight(1f), filled = !maps,
                contentDescription = "Ask a follow-up question by voice")
        }
    }
}

@Composable
private fun ActionPreview(p: SurfaceModel.Preview.Action, steps: List<SurfaceModel.StepView>, expanded: Boolean, onFollowUp: () -> Unit) {
    Column(Modifier.padding(top = 10.dp)) {
        Text(p.summary, style = MaterialTheme.typography.bodyLarge, color = Tk.Text)
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
        Text(p.message, style = MaterialTheme.typography.bodyLarge, color = Tk.Text)
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
            .semantics { contentDescription = description }
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
            .semantics { contentDescription = description }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(title.take(32), style = MaterialTheme.typography.labelLarge, color = Tk.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun Modifier.height12(): Modifier = this.then(Modifier.padding(top = 12.dp))

internal enum class Glyph { ACTIVE, DONE, FAILED, NEUTRAL, ASK, RESULT, STOPPED }

internal fun glyphOf(s: SurfaceModel.State): Glyph = when (s.mode) {
    SurfaceModel.Mode.PROCESSING, SurfaceModel.Mode.WORKING -> Glyph.ACTIVE
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
    val alpha = if (active && !calm) {
        val t = rememberInfiniteTransition(label = "breath")
        t.animateFloat(0.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "breath alpha").value
    } else 1f
    Canvas(modifier.clearAndSetSemantics { }.graphicsLayer { this.alpha = alpha }) {
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

/** A confirmation's buttons: Cancel and a VERB that says what will happen ("Send", "Call", "Buy") — HIG Snippets. Pure. */
object ConfirmWords {
    private val VERBS = listOf("Send", "Call", "Buy", "Pay", "Open", "Turn", "Delete", "Reply", "Text", "Share", "Post", "Install",
        "Save", "Set", "Book", "Order", "Transfer", "Top up", "Dial", "Remove", "Clear", "Start")

    fun buttons(text: String, twi: Boolean): Pair<String, String> {
        if (twi) return PromptSurface.buttons(PromptSurface.Kind.CONFIRM, twi = true).let { it[0] to it[1] }
        val first = text.trim().trimStart('“', '"').lowercase()
        val verb = VERBS.firstOrNull { v -> first.startsWith(v.lowercase() + " ") || first.startsWith(v.lowercase() + "?") }
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
