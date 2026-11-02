package com.tekyerema.ui.assistant

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tekyerema.ui.theme.LocalCalmMotion
import com.tekyerema.ui.theme.Tk
import com.tekyerema.voice.PromptSurface
import com.tekyerema.voice.VoiceEngine
import kotlinx.coroutines.delay

/**
 * The prompt over other apps ([com.tekyerema.ui.OverlayConfirm]) as the same living glass as the in-app surface — the owner on
 * the A20e, 14 October 2026: the confirmation over the MTN screen was "the old preview … not the new liquid glass design"
 * (DECISIONS V2-12). Only the look moved: the body is the surface's own [Waiting] (the verb pill counts only after ConfirmGate's
 * arming delay and answers this confirmation's own number; voice cues only from the real pipeline), and every answer goes
 * where the old panel sent it. It arrives the surface's way — down from the top on its spring, a touch of scale, the light
 * sweeping the top edge once — and follows the phone's light or dark mode.
 */
@Composable
internal fun PromptPanel(
    prompt: PromptSurface.Prompt,
    twi: Boolean,
    typing: Boolean,
    notice: String?,
    typeHint: String,
    onConfirm: (Boolean, Long) -> Unit,
    onChoice: (String) -> Unit,
    onAnswerByVoice: () -> Unit,
    onType: () -> Unit,
    onSecureDone: () -> Unit,
    onStopTask: () -> Unit,
    onSubmitTyped: (String) -> Boolean,
    onCloseTyping: () -> Unit
) {
    val context = LocalContext.current
    val calm = LocalCalmMotion.current
    val tier = remember(calm) { GlassTiers.detect(context, calm) }
    val glass = com.tekyerema.ui.theme.glassPalette()
    val moneyOn by com.tekyerema.money.MoneyMoment.active.collectAsState()
    val voice by VoiceEngine.state.collectAsState()
    val waiting = remember(prompt) { SurfaceModel.Waiting(prompt.kind, prompt.text, prompt.seq) }
    val hasChoices = remember(prompt.text) { prompt.kind == PromptSurface.Kind.QUESTION && QuestionChoices.of(prompt.text).isNotEmpty() }

    // The light follows the microphone only while the pipeline really listens for the answer; otherwise it rests.
    val listening = VoiceReflect.cue(prompt.kind, voice, hasChoices, twi) is VoiceReflect.Cue.Listening
    val currentMood by rememberUpdatedState(if (listening) LightMood.LISTENING else LightMood.CALM)
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

    // Arrival, as the surface arrives: down from the top on its spring (SurfaceMotion.Arrive) with a little scale, then the
    // light sweeps the top edge once. Alpha starts just above 0: a node at alpha 0 is not "visible to user", and TalkBack could
    // skip the panel's first moment. 320 ms or so — inside ConfirmGate's arming delay, so the verb pill is still when it counts.
    val appear = remember { Animatable(if (calm) 1f else 0f) }
    val arrival = remember { Animatable(if (calm || tier == GlassTier.FLAT) 1f else 0f) }
    // A new window's first frame is the slow one (composition, text layout): the spring starts once it has been drawn, or a
    // slow phone shows its opening already gone — on the emulator the glass jumped from half-way to settled.
    LaunchedEffect(Unit) {
        // Flowing down from the top edge with a soft liquid settle (owner, 15 Oct 2026: "a nice animation that flows from the top").
        if (appear.value < 1f) { withFrameNanos { }; withFrameNanos { }; appear.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 190f)) }
    }
    LaunchedEffect(Unit) { if (arrival.value < 1f) { delay(120); arrival.animateTo(1f, tween(760)) } }

    val tilt = com.tekyerema.ui.glass.rememberTiltLight(tier == GlassTier.FULL && !calm)
    val title = if (twi) PromptSurface.header(prompt.kind, true).lowercase().replaceFirstChar { it.uppercase() }
        else SurfaceModel.titleOfWaiting(prompt.kind, prompt.text)
    val radius = 34.dp
    // The words and choices may scroll at very large text; the panel never grows past the screen.
    val maxBody = (LocalConfiguration.current.screenHeightDp * 0.72f).dp

    CompositionLocalProvider(LocalGlass provides glass) {
        Box(
            Modifier
                .fillMaxWidth()
                // room below for the glass's own shadow (the window wraps its content)
                .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 26.dp)
                .widthIn(max = 560.dp)
                .graphicsLayer {
                    val a = appear.value
                    alpha = (0.02f + 1.6f * a).coerceIn(0.02f, 1f)
                    translationY = -(1f - a) * (size.height + 24.dp.toPx())
                    val s = 0.92f + 0.08f * a.coerceAtMost(1.04f)
                    scaleX = s; scaleY = 0.86f + 0.14f * a.coerceAtMost(1.04f)
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
                // (the glass draws its own soft shadow, outside its shape only — ui/glass/LiquidGlass)
                .glassBody(radius, glass, mood = { currentMood },
                    light = { if (currentMood == LightMood.LISTENING) 0.25f + 0.75f * micLevel.floatValue else 0.22f },
                    sweep = { 0f }, glowShift = { 0f }, tier = tier, arrival = { arrival.value }, tilt = { tilt.value })
                // A money read-back over the network's screens wears the gold edge too (MoneyMoment, as on the assistant card).
                .then(if (moneyOn) Modifier.moneyEdge(radius, moneyEdgeColour(glass)) else Modifier)
                .clip(if (com.tekyerema.ui.glass.glassLite()) androidx.compose.foundation.shape.RoundedCornerShape(radius) else ContinuousShape(radius))
                .semantics { paneTitle = title }
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .animateContentSize(if (calm) tween(0) else SurfaceMotion.Grow)
                    .padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StateMark(SurfaceModel.State(runSeq = 0L, mode = SurfaceModel.Mode.WAITING), Modifier.size(12.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 19.sp,
                        letterSpacing = (-0.2).sp), color = GlassColors.Text, modifier = Modifier.semantics { heading() })
                }
                Column(Modifier.heightIn(max = maxBody).verticalScroll(rememberScrollState())) {
                    if (!typing) Waiting(waiting, voice, resolving = false, reaction = Reaction(), twi = twi, tier = tier,
                        onConfirm = onConfirm, onChoice = onChoice, onAnswerByVoice = onAnswerByVoice, onType = onType,
                        onSecureDone = onSecureDone, onStopTask = onStopTask,
                        onTypeConfirm = if (prompt.kind == PromptSurface.Kind.CONFIRM) onType else null)
                    else Column(Modifier.padding(top = 10.dp)) {
                        Text(ScreenText.of(prompt.text), style = MaterialTheme.typography.bodyLarge, color = GlassColors.Text)
                        // Back closes the box and brings the pills back (the window takes focus only while it is typed into).
                        Box(Modifier.onPreviewKeyEvent { e ->
                            if (e.key == Key.Back && e.type == KeyEventType.KeyUp) { onCloseTyping(); true } else false
                        }) {
                            FollowUpField(placeholder = typeHint, twi = twi, tier = tier, onSubmit = onSubmitTyped,
                                onSpeak = { onCloseTyping(); if (prompt.kind == PromptSurface.Kind.QUESTION) onAnswerByVoice() })
                        }
                    }
                    notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = GlassColors.Warm, modifier = Modifier.padding(top = 10.dp).semantics { liveRegion = LiveRegionMode.Polite }) }
                }
            }
        }
    }
}
