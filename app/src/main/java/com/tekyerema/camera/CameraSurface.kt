package com.tekyerema.camera

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tekyerema.ui.assistant.DarkGlass
import com.tekyerema.ui.assistant.GlassPill
import com.tekyerema.ui.assistant.GlassTier
import com.tekyerema.ui.assistant.GlassTiers
import com.tekyerema.ui.assistant.LightMood
import com.tekyerema.ui.assistant.LocalGlass
import com.tekyerema.ui.assistant.PillTone
import com.tekyerema.ui.assistant.glassBody
import com.tekyerema.ui.glass.DEFAULT_LIGHT_DEGREES
import com.tekyerema.ui.theme.LocalCalmMotion
import com.tekyerema.ui.theme.Spectrum

/*
 * The camera screen's floating layer (owner, 23 Oct 2026: "a nice design to match the whole build"): the picture fills the
 * screen and the one liquid-glass material of the rest of the app (docs/liquid-glass/DESIGN.md) floats over it — a capsule at
 * the top that says what the camera is doing (and the user's question), and the answer card at the bottom. No outlines: the
 * state is light — teal glowing from inside while it looks and thinks, warm for a moment when the answer arrives, faint coral
 * when it can't. A soft flash marks the moment the photo is taken, for anyone who can see a little. Dark glass always: what
 * is behind is a live picture, never our own page. Everything here is already spoken by the activity, so the words are hidden
 * from TalkBack; only Close is a control.
 */

/** Where the camera is in its one job. */
enum class CameraPhase { LOOKING, WORKING, ANSWER, FAILED }

/** The state the activity writes and the surface reads (main thread). */
@Stable
class CameraUi {
    var label by mutableStateOf("")
    var question by mutableStateOf<String?>(null)
    var text by mutableStateOf("")
    var phase by mutableStateOf(CameraPhase.LOOKING)
    /** Bumped when a photo is taken: the flash plays once per bump. */
    var shutter by mutableStateOf(0)
}

@Composable
fun CameraSurface(ui: CameraUi, closeLabel: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val calm = remember { com.tekyerema.ui.theme.systemAnimatorScale(context) <= 0f }
    val tier = remember { GlassTiers.detect(context, calm) }
    val glass = DarkGlass
    val mood = when (ui.phase) {
        CameraPhase.LOOKING -> LightMood.LISTENING
        CameraPhase.WORKING -> LightMood.WORKING
        CameraPhase.ANSWER -> LightMood.SUCCESS
        CameraPhase.FAILED -> LightMood.FAILURE
    }
    // Looking and thinking breathe; an answer or a failure glows once and settles (calm motion: steady, no breathing).
    val breathing = rememberInfiniteTransition(label = "camera light")
    val breath by breathing.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1_400, easing = LinearEasing), RepeatMode.Reverse), label = "breath")
    val sweepState = breathing.animateFloat(0f, 360f, infiniteRepeatable(tween(2_800, easing = LinearEasing)), label = "sweep")
    // Read in the draw phase only (glassBody calls it per frame): the surface never recomposes for the moving light.
    val sweep: () -> Float = { sweepState.value }
    val settle = remember { Animatable(1f) }
    LaunchedEffect(ui.phase, ui.text) {
        if (ui.phase == CameraPhase.ANSWER || ui.phase == CameraPhase.FAILED) {
            settle.snapTo(1f)
            if (!calm) settle.animateTo(0.25f, tween(1_600, delayMillis = 400))
        } else settle.snapTo(1f)
    }
    val light: () -> Float = {
        when (ui.phase) {
            CameraPhase.LOOKING, CameraPhase.WORKING -> if (calm) 1f else breath
            else -> settle.value
        }
    }
    val flash = remember { Animatable(0f) }
    LaunchedEffect(ui.shutter) {
        if (ui.shutter > 0 && !calm) { flash.snapTo(0.38f); flash.animateTo(0f, tween(420)) }
    }

    CompositionLocalProvider(LocalGlass provides glass, LocalCalmMotion provides calm) {
        Box(
            Modifier
                .fillMaxSize()
                // A tap anywhere closes, as before the glass was here (the root view keeps TalkBack's "double-tap to close").
                .pointerInput(Unit) { detectTapGestures { onClose() } }
                // Shade at the top and bottom of the picture so the glass reads over a bright scene.
                .drawBehind {
                    drawRect(Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.42f), 0.22f to Color.Transparent,
                        0.62f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f)))
                    if (flash.value > 0f) drawRect(Color.White.copy(alpha = flash.value))
                }
        ) {
            StatusCapsule(ui, mood, light, sweep, tier, calm, Modifier.align(Alignment.TopCenter))
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp).widthIn(max = 560.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (ui.text.isNotBlank()) AnswerCard(ui, mood, light, sweep, tier, calm)
                Spacer(Modifier.size(12.dp))
                GlassPill(closeLabel, onClick = onClose, tone = PillTone.QUIET, tier = tier, compact = true)
            }
        }
    }
}

@Composable
private fun StatusCapsule(ui: CameraUi, mood: LightMood, light: () -> Float, sweep: () -> Float, tier: GlassTier, calm: Boolean, modifier: Modifier) {
    val g = LocalGlass.current
    Row(
        modifier
            .clearAndSetSemantics { }   // already spoken by the activity; only Close is a control
            .statusBarsPadding()
            .padding(top = 8.dp, start = 24.dp, end = 24.dp)
            .widthIn(max = 520.dp)
            .glassBody(23.dp, g, mood = { mood }, light = light, sweep = sweep, glowShift = { 0f }, tier = tier,
                tilt = { DEFAULT_LIGHT_DEGREES })
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // The state's light, small: teal while looking or thinking, warm with an answer, coral when it couldn't.
        val dot = when (mood) {
            LightMood.SUCCESS -> g.warm
            LightMood.FAILURE -> g.fail
            else -> Spectrum.Teal
        }
        Box(Modifier.size(8.dp).drawBehind {
            val a = if (calm) 1f else light().coerceIn(0.4f, 1f)
            drawCircle(dot.copy(alpha = 0.30f * a), radius = size.minDimension * 1.1f)
            drawCircle(dot.copy(alpha = a))
        })
        Column(Modifier.weight(1f, fill = false)) {
            Text(ui.label, color = g.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ui.question?.let { q ->
                Text(q, color = g.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun AnswerCard(ui: CameraUi, mood: LightMood, light: () -> Float, sweep: () -> Float, tier: GlassTier, calm: Boolean) {
    val g = LocalGlass.current
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.46f).dp
    val radius = 34.dp
    Box(
        Modifier
            .clearAndSetSemantics { }   // already spoken by the activity (TalkBack reading it again would double every result)
            .fillMaxWidth()
            .heightIn(max = maxH)
            .glassBody(radius, g, mood = { mood }, light = light, sweep = sweep, glowShift = { 0f }, tier = tier)
            .clip(RoundedCornerShape(radius))
    ) {
        AnimatedContent(
            targetState = ui.text,
            transitionSpec = {
                if (calm) fadeIn(tween(140)) togetherWith fadeOut(tween(90))
                else (fadeIn(tween(240, delayMillis = 60)) + slideInVertically(tween(280)) { it / 10 }) togetherWith fadeOut(tween(120))
            },
            label = "camera answer"
        ) { text ->
            Text(
                text,
                color = if (ui.phase == CameraPhase.WORKING) g.textSecondary else g.text,
                // Large: this card is for people who can see a little (the voice has already said it).
                fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp)
            )
        }
    }
}
