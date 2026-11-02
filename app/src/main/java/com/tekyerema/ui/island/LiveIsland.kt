package com.tekyerema.ui.island

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Straight
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TurnLeft
import androidx.compose.material.icons.rounded.TurnRight
import androidx.compose.material.icons.rounded.TurnSlightLeft
import androidx.compose.material.icons.rounded.TurnSlightRight
import androidx.compose.material.icons.rounded.UTurnLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tekyerema.live.LiveActivity
import com.tekyerema.live.LiveGlyph
import com.tekyerema.live.LiveKind
import com.tekyerema.ui.assistant.GlassPill
import com.tekyerema.ui.assistant.GlassTier
import com.tekyerema.ui.assistant.LocalGlass
import com.tekyerema.ui.assistant.PillTone
import com.tekyerema.ui.glass.GlassDepth
import com.tekyerema.ui.glass.GlassShape
import com.tekyerema.ui.glass.LiquidStyles
import com.tekyerema.ui.glass.liquidGlass
import com.tekyerema.ui.theme.LocalCalmMotion
import com.tekyerema.ui.theme.Spectrum
import kotlinx.coroutines.delay

/**
 * The island (owner, 15 Oct 2026: "You see how Dynamic Island of iPhones work … it will have the live direction"): a
 * capsule of liquid glass just under the status bar showing what keeps going — a timer's countdown with its ring, the next
 * turn and how far, the song playing. A tap opens it into a card with its buttons; it opens by itself when something needs
 * the user (a timer done, a turn a few metres ahead) and closes again a few seconds later. One shape that morphs, never two
 * cards swapped. Decoration carries no semantics: TalkBack hears one button with the activity's words and its actions.
 */
@Composable
fun LiveIsland(
    activities: List<LiveActivity>,
    onAction: (id: String, action: String) -> Unit,
    modifier: Modifier = Modifier,
    tier: GlassTier = GlassTier.FULL,
) {
    val lead = activities.firstOrNull() ?: return
    val g = LocalGlass.current
    val calm = LocalCalmMotion.current
    var open by remember(lead.id) { mutableStateOf(lead.urgent) }
    // Something needs the user: open; otherwise a card left open folds back into the capsule after a while.
    LaunchedEffect(lead.id, lead.urgent) { if (lead.urgent) open = true }
    LaunchedEffect(open, lead.urgent, lead.id) { if (open && !lead.urgent) { delay(7_000); open = false } }

    // The capsule is a control (glass); the OPEN card is read — "Time's up", a turn, a caller — so it takes the reading material,
    // opaque unless the user chose Glass: at 0.95 the app's own title showed through its words ("Timers" under "Time's up",
    // emulator 2 Nov 2026).
    val style = LiquidStyles.of(g.dark, if (open) com.tekyerema.ui.theme.Look.readingMode() else com.tekyerema.ui.theme.glassMode()).let { s ->
        // Denser than our pages: over another app's text, words on glass must never read as "text on top of text" (NN/g).
        if (s.border != null || (open && com.tekyerema.ui.theme.Look.opaqueReading)) s
        else s.copy(fill = s.fill.copy(alpha = if (open) (if (g.dark) 0.95f else 0.96f) else (if (g.dark) 0.93f else 0.92f)))
    }
    val tilt = com.tekyerema.ui.glass.rememberTiltLight(tier == GlassTier.FULL && !calm)
    val radius by animateDpAsState(if (open) 30.dp else 23.dp, if (calm) tween(0) else spring(0.82f, 170f), label = "islandRadius")
    val pulse = if (lead.urgent && !calm && tier == GlassTier.FULL) rememberInfiniteTransition(label = "islandPulse")
        .animateFloat(0.10f, 0.26f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "pulse") else null
    val glowColor = when (lead.kind) {
        LiveKind.TIMER -> if (g.dark) Color(0xFFFFB77A) else Color(0xFFB0560C)
        LiveKind.NAVIGATION -> Spectrum.Teal
        LiveKind.MUSIC -> Spectrum.Aqua
        else -> Spectrum.Green
    }
    val text = g.text
    val secondary = g.textSecondary

    Box(
        modifier
            .widthIn(max = 560.dp)
            // The glass BEFORE animateContentSize: that one clips what follows it to its bounds, and the soft shadow, drawn
            // outside the shape, was cut into a pale rectangle round the corners.
            .liquidGlass(style, GlassShape.Rounded(radius), GlassDepth.FLOATING, lightDegrees = { tilt.value },
                glow = { pulse?.let { glowColor.copy(alpha = it.value) } ?: if (lead.urgent) glowColor.copy(alpha = 0.18f) else Color.Transparent })
            .animateContentSize(if (calm) tween(0) else spring(0.82f, 170f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { open = !open }
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = lead.spoken
                stateDescription = if (open) "Open" else "Closed"
                onClick(label = if (open) "Close" else "Open") { open = !open; true }
                customActions = lead.actions.map { a -> CustomAccessibilityAction(a.label) { onAction(lead.id, a.id); true } }
            }
    ) {
        AnimatedContent(targetState = open, transitionSpec = {
            if (calm) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
            else fadeIn(tween(180, delayMillis = 70)) togetherWith fadeOut(tween(90))
        }, label = "island") { isOpen ->
            if (!isOpen) Compact(lead, activities.size - 1, text, glowColor)
            else Expanded(lead, text, secondary, glowColor, tier) { a -> onAction(lead.id, a) }
        }
    }
}

@Composable
private fun Compact(a: LiveActivity, others: Int, text: Color, accent: Color) {
    Row(
        Modifier.heightIn(min = 46.dp).padding(start = 12.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Glyph(a, 26.dp, text, accent)
        Spacer(Modifier.width(10.dp))
        Text(a.compact, style = numbers(17.sp, FontWeight.SemiBold), color = text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 220.dp))
        if (others > 0) {
            Spacer(Modifier.width(10.dp))
            Canvas(Modifier.size(7.dp)) { drawCircle(accent.copy(alpha = 0.9f)) }
        }
    }
}

@Composable
private fun Expanded(a: LiveActivity, text: Color, secondary: Color, accent: Color, tier: GlassTier, act: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().widthIn(min = 300.dp).padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(a, 34.dp, text, accent)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(a.title, style = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
                    color = text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                a.detail?.let { Text(it, style = TextStyle(fontSize = 15.sp, lineHeight = 20.sp), color = secondary, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            }
            // The big figure (a countdown, a distance) — only when it is one ("Time's up" is already the title).
            if ((a.kind == LiveKind.TIMER || a.kind == LiveKind.NAVIGATION) && a.compact.any { it.isDigit() }) {
                Spacer(Modifier.width(12.dp))
                Text(a.compact, style = numbers(26.sp, FontWeight.SemiBold), color = text, maxLines = 1)
            }
        }
        a.progress?.let { p ->
            Spacer(Modifier.height(14.dp))
            Canvas(Modifier.fillMaxWidth().height(5.dp)) {
                val r = size.height / 2f
                drawRoundRect(text.copy(alpha = 0.14f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
                drawRoundRect(accent, size = Size(size.width * p.coerceIn(0f, 1f), size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
            }
        }
        if (a.actions.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            if (a.kind == LiveKind.MUSIC) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                // Music: the transport as icons (a word like "Pause" wrapped in a narrow pill), each a labelled button.
                a.actions.forEach { act0 -> TransportButton(act0, text, accent) { act(act0.id) } }
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                a.actions.forEach { act0 ->
                    GlassPill(act0.label, onClick = { act(act0.id) }, modifier = Modifier.weight(1f),
                        tone = if (act0.primary) PillTone.PRIMARY else PillTone.QUIET, tier = tier, compact = true)
                }
            }
        }
    }
}

/** A music control: a round glass button with its icon; TalkBack hears its label ("Pause", "Next"). */
@Composable
private fun TransportButton(a: com.tekyerema.live.LiveAction, tint: Color, accent: Color, onClick: () -> Unit) {
    val g = LocalGlass.current
    val base = LiquidStyles.of(g.dark, com.tekyerema.ui.theme.glassMode())
    val icon = when (a.id) {
        "prev" -> Icons.Rounded.SkipPrevious
        "next" -> Icons.Rounded.SkipNext
        else -> if (a.label == "Pause") Icons.Rounded.Pause else Icons.Rounded.PlayArrow
    }
    val size = if (a.primary) 60.dp else 52.dp
    Box(
        Modifier.size(size)
            .liquidGlass(base.copy(grain = 0f, glint = base.glint * 0.6f), GlassShape.Capsule, GlassDepth.FLAT,
                tint = if (base.border != null) null else if (a.primary) accent.copy(alpha = 0.90f)
                    else if (g.dark) Color.White.copy(alpha = 0.11f) else Color(0xFF0E1B1C).copy(alpha = 0.065f))
            .clickable(role = Role.Button, onClickLabel = a.label, onClick = onClick)
            .semantics { contentDescription = a.label },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (a.primary) Color(0xFF032420) else tint, modifier = Modifier.size(if (a.primary) 32.dp else 28.dp))
    }
}

/** The activity's mark: an icon, with the timer's or the walk's progress as a ring around it. */
@Composable
private fun Glyph(a: LiveActivity, size: androidx.compose.ui.unit.Dp, tint: Color, accent: Color) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        a.progress?.takeIf { a.kind == LiveKind.TIMER }?.let { p ->
            Canvas(Modifier.size(size)) {
                val sw = 2.5.dp.toPx()
                drawArc(tint.copy(alpha = 0.16f), 0f, 360f, false, topLeft = Offset(sw / 2, sw / 2), size = Size(this.size.width - sw, this.size.height - sw), style = Stroke(sw))
                drawArc(accent, -90f, 360f * (1f - p), false, topLeft = Offset(sw / 2, sw / 2), size = Size(this.size.width - sw, this.size.height - sw),
                    style = Stroke(sw, cap = StrokeCap.Round))
            }
        }
        Icon(iconOf(a.glyph), contentDescription = null, tint = if (a.urgent) accent else tint,
            modifier = Modifier.size(if (a.kind == LiveKind.TIMER && a.progress != null) size * 0.58f else size * 0.82f))
    }
}

private fun iconOf(g: LiveGlyph): ImageVector = when (g) {
    LiveGlyph.TIMER -> Icons.Rounded.Timer
    LiveGlyph.ARROW_LEFT -> Icons.Rounded.TurnLeft
    LiveGlyph.ARROW_RIGHT -> Icons.Rounded.TurnRight
    LiveGlyph.ARROW_STRAIGHT -> Icons.Rounded.Straight
    LiveGlyph.ARROW_SLIGHT_LEFT -> Icons.Rounded.TurnSlightLeft
    LiveGlyph.ARROW_SLIGHT_RIGHT -> Icons.Rounded.TurnSlightRight
    LiveGlyph.ARROW_UTURN -> Icons.Rounded.UTurnLeft
    LiveGlyph.ARRIVE -> Icons.Rounded.Place
    LiveGlyph.WALK -> Icons.Rounded.DirectionsWalk
    LiveGlyph.MUSIC -> Icons.Rounded.MusicNote
    LiveGlyph.PAUSED -> Icons.Rounded.Pause
    LiveGlyph.CALL -> Icons.Rounded.Call
    LiveGlyph.BELL -> Icons.Rounded.NotificationsActive
}

/** Figures that don't jiggle as a countdown ticks (tabular numbers). */
private fun numbers(size: androidx.compose.ui.unit.TextUnit, weight: FontWeight) =
    TextStyle(fontSize = size, fontWeight = weight, fontFeatureSettings = "tnum", letterSpacing = (-0.3).sp)
