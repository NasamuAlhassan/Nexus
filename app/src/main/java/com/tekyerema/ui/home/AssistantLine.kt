package com.tekyerema.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tekyerema.ui.theme.Tk
import com.tekyerema.ui.theme.TkMotion
import com.tekyerema.ui.voice.Presence

private data class Line(val key: String, val text: String, val reveal: Boolean, val secondary: Boolean)

/**
 * The assistant's line, fluid: a new line rises into place as the old one lifts away, the same words update in
 * place ([LineMotion.key]), and a spoken reply is revealed at speaking pace behind a soft ink edge.
 *
 * Accessibility: what TalkBack gets never moves. The semantics sit on a stable wrapper holding the WHOLE phrase from
 * its first frame (a polite live region when [announce]), and the animated text beneath is cleared from the tree —
 * so a reveal is never read word by word, and a transition never exposes two copies of the line.
 */
@Composable
fun AssistantLine(
    presence: Presence,
    phrase: String,
    announce: Boolean,
    calm: Boolean,
    speechRate: Int,
    modifier: Modifier = Modifier
) {
    val line = Line(LineMotion.key(presence, phrase), phrase, LineMotion.reveals(presence), presence == Presence.IDLE)
    Box(
        modifier.fillMaxWidth().clearAndSetSemantics {
            if (phrase.isNotEmpty()) {
                text = AnnotatedString(phrase)
                if (announce) liveRegion = LiveRegionMode.Polite
            }
        },
        contentAlignment = Alignment.TopCenter
    ) {
        AnimatedContent(
            targetState = line,
            contentKey = { it.key },
            transitionSpec = { TkMotion.lineChange(calm) },
            contentAlignment = Alignment.TopCenter,
            label = "assistant line"
        ) { l ->
            if (l.text.isEmpty()) Spacer(Modifier.fillMaxWidth())
            else RevealText(l.text, revealAsked = l.reveal && !calm, cps = LineMotion.charsPerSecond(speechRate), secondary = l.secondary)
        }
    }
}

/** The small hint under the line ("Tap to talk"): a quiet cross-fade, never a slide. Semantics as [AssistantLine]. */
@Composable
fun HintLine(hint: String, color: Color, announce: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().clearAndSetSemantics {
            if (hint.isNotEmpty()) {
                text = AnnotatedString(hint)
                if (announce) liveRegion = LiveRegionMode.Polite
            }
        },
        contentAlignment = Alignment.TopCenter
    ) {
        AnimatedContent(
            targetState = hint to color,
            contentKey = { it.first },
            transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(120)) },
            contentAlignment = Alignment.TopCenter,
            label = "hint"
        ) { (h, c) ->
            if (h.isNotEmpty()) Text(h, style = MaterialTheme.typography.bodyMedium, color = c, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun RevealText(text: String, revealAsked: Boolean, cps: Float, secondary: Boolean) {
    // Light UI (ui.glassLite, owner 20 Oct 2026: "make everything fast"): the whole line at once — the word-by-word reveal
    // redraws an offscreen layer every frame while the voice speaks, which an entry phone's GPU feels.
    val reveal = revealAsked && !remember { com.tekyerema.ui.glass.glassLite() }
    // I-08: a long answer keeps ALL its words (smaller type, up to 8 lines) instead of being cut at 3 lines.
    val long = text.length > 90
    val style = if (long) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium
    val color by animateColorAsState(if (secondary) Tk.TextSecondary else Tk.Text, TkMotion.slowEffects(), label = "line colour")
    val len = text.length.toFloat()
    val shown = remember(text) { Animatable(if (reveal) 0f else len) }
    LaunchedEffect(text, reveal) {
        if (shown.value >= len) return@LaunchedEffect
        if (reveal) shown.animateTo(len, tween(LineMotion.revealMillis(len - shown.value, cps), easing = LinearEasing))
        else shown.animateTo(len, tween(240, easing = FastOutSlowInEasing))   // speech ended early: finish now
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val edge = with(LocalDensity.current) { 40.dp.toPx() }
    val edgeBrush = remember(edge) { Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, startX = 0f, endX = edge) }
    Text(
        text = text,
        style = style,
        color = color,
        textAlign = TextAlign.Center,
        maxLines = if (long) 8 else 3,
        onTextLayout = { layout = it },
        modifier = Modifier
            .fillMaxWidth()
            // Offscreen only while revealing: the erase below must punch through the text, not the screen behind it.
            .graphicsLayer { compositingStrategy = if (shown.value < len) CompositingStrategy.Offscreen else CompositingStrategy.Auto }
            .drawWithContent {
                drawContent()
                val l = layout ?: return@drawWithContent
                val at = shown.value
                if (at < len) eraseUnrevealed(l, at, edge, edgeBrush)
            }
    )
}

/**
 * Hide what hasn't been "said" yet: the rest of the current line behind a soft [edge]-wide gradient, and every line
 * below it. Draw-phase only (the text is laid out once), so the reveal costs a few rectangles per frame.
 */
private fun DrawScope.eraseUnrevealed(l: TextLayoutResult, shown: Float, edge: Float, edgeBrush: Brush) {
    val n = l.layoutInput.text.length
    if (n == 0) return
    val idx = shown.toInt().coerceIn(0, n - 1)
    val frac = (shown - idx).coerceIn(0f, 1f)
    val line = l.getLineForOffset(idx).coerceAtMost(l.lineCount - 1)
    val x0 = l.getHorizontalPosition(idx, true)
    val x1 = if (idx + 1 < n && l.getLineForOffset(idx + 1) == line) l.getHorizontalPosition(idx + 1, true) else l.getLineRight(line)
    val x = x0 + (x1 - x0) * frac
    val top = l.getLineTop(line)
    val bottom = l.getLineBottom(line)
    val h = bottom - top
    translate(left = x) {
        drawRect(edgeBrush, topLeft = Offset(0f, top), size = Size(edge, h), blendMode = BlendMode.DstOut)
    }
    val restW = (size.width - x - edge).coerceAtLeast(0f)
    if (restW > 0f) drawRect(Color.Black, topLeft = Offset(x + edge, top), size = Size(restW, h), blendMode = BlendMode.DstOut)
    if (bottom < size.height) drawRect(Color.Black, topLeft = Offset(0f, bottom), size = Size(size.width, size.height - bottom),
        blendMode = BlendMode.DstOut)
}
