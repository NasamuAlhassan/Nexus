package com.tekyerema.ui.assistant

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tekyerema.ui.theme.LocalCalmMotion
import com.tekyerema.ui.glass.GlassDepth
import com.tekyerema.ui.glass.GlassShape
import com.tekyerema.ui.glass.LiquidStyles
import com.tekyerema.ui.glass.liquidGlass
import com.tekyerema.ui.theme.Spectrum
import com.tekyerema.ui.theme.TkHaptic
import com.tekyerema.ui.theme.tkHaptic
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** QUIET: the cancel/secondary act, a faint tonal glass. PRIMARY: the act being decided, lit by tone and light — never a solid
 *  colour block. */
enum class PillTone { QUIET, PRIMARY }

/**
 * A glass pill that belongs to the surface (docs/assistant-ux/PLAN_V2.md, stage 4): translucent tonal fill, a faint top-edge
 * highlight, a soft inner light; on press a quick scale-down with a spring release, a light bloom where the finger touched,
 * and a haptic tick. Used only where a real decision or action exists.
 *
 * [arming] (0..1, read in the draw phase) shows the real arming delay filling the pill; [available] stays false until it has
 * run, and until then a tap does nothing (and TalkBack hears the pill as not yet available). [chosen] marks the pill whose act
 * the engine has just taken (voice or touch alike); [dimmed] quiets the others.
 */
@Composable
fun GlassPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: PillTone = PillTone.QUIET,
    arming: () -> Float = { 1f },
    available: Boolean = true,
    chosen: Boolean = false,
    dimmed: Boolean = false,
    contentDescription: String? = null,
    haptic: TkHaptic = TkHaptic.TAP,
    tier: GlassTier = GlassTier.FULL,
    /** A smaller pill (the header's Stop, the field's mic): drawn 40 dp tall, still a 48 dp touch target. */
    compact: Boolean = false
) {
    val calm = LocalCalmMotion.current
    val g = LocalGlass.current
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val scale = remember { Animatable(1f) }
    val bloom = remember { Animatable(1f) }       // 0 → 1 after each press; 1 = no bloom showing
    var pressAt by remember { mutableStateOf(Offset.Unspecified) }
    val settle = remember { Animatable(if (chosen) 1f else 0f) }
    val fade = remember { Animatable(if (dimmed) 0.38f else 1f) }

    LaunchedEffect(interaction, calm) {
        interaction.interactions.collect { i ->
            when (i) {
                is PressInteraction.Press -> {
                    pressAt = i.pressPosition
                    if (!calm) launch { scale.animateTo(0.96f, SurfaceMotion.Press) }
                    if (!calm && tier == GlassTier.FULL) launch { bloom.snapTo(0f); bloom.animateTo(1f, tween(460)) }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> if (!calm) launch { scale.animateTo(1f, SurfaceMotion.Release) }
            }
        }
    }
    LaunchedEffect(chosen) { if (calm) settle.snapTo(if (chosen) 1f else 0f) else settle.animateTo(if (chosen) 1f else 0f, tween(220)) }
    LaunchedEffect(dimmed) { val t = if (dimmed) 0.38f else 1f; if (calm) fade.snapTo(t) else fade.animateTo(t, tween(240)) }

    val shape = RoundedCornerShape(percent = 50)
    val label = contentDescription ?: text
    val base = LiquidStyles.of(g.dark, com.tekyerema.ui.theme.glassMode())
    val pillStyle = base.copy(specular = base.specular * 0.7f, edge = base.edge * 0.85f, glint = base.glint * 0.6f, grain = 0f)
    val accent = if (g.dark) Color(0xFF2FD8C2) else Color(0xFF14B8A3)
    val quietTint = if (g.dark) Color.White.copy(alpha = 0.11f) else Color(0xFF0E1B1C).copy(alpha = 0.065f)
    val onAccent = Color(0xFF032420)
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = if (compact) 40.dp else 56.dp)
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value; alpha = fade.value }
            // Liquid glass (ui/glass/LiquidGlass): a pill inside the card is a FILL, not glass on glass (Apple's layering rule);
            // the primary act is tinted glass — the colour in the body, dark ink on it for contrast — never a flat block.
            .liquidGlass(
                style = pillStyle,
                shape = GlassShape.Capsule,
                depth = GlassDepth.FLAT,
                tint = if (pillStyle.border != null) null else if (tone == PillTone.PRIMARY) accent.copy(alpha = 0.90f) else quietTint,
                press = { val b = bloom.value; if (b < 1f) 1f - b else 0f },
                pressAt = { pressAt }
            )
            .clip(shape)
            .drawWithCache {
                val armBand = Color.Black.copy(alpha = if (g.dark) 0.34f else 0.22f)
                onDrawBehind {
                    if (tone == PillTone.PRIMARY) {
                        // The arming delay: the not-yet-ready part of the pill stays dim, and the light fills it from the left.
                        val a = arming().coerceIn(0f, 1f)
                        if (a < 1f) drawRect(armBand, topLeft = Offset(size.width * a, 0f), size = Size(size.width * (1f - a), size.height))
                    }
                    if (settle.value > 0f && tone != PillTone.PRIMARY) drawRect(accent.copy(alpha = 0.85f * settle.value))
                }
            }
            .clickable(interactionSource = interaction, indication = null, enabled = available, role = Role.Button,
                onClickLabel = label, onClick = { view.tkHaptic(haptic); onClick() })
            // One node for TalkBack: the spoken label replaces the visible text; the click, the Button role and "disabled"
            // until the arming delay has run come from the clickable before this (Gate G, as in BigButton).
            .clearAndSetSemantics {
                this.contentDescription = label
                if (chosen) selected = true
                if (!available) stateDescription = "Available in a moment"
            }
            .padding(horizontal = if (compact) 16.dp else 22.dp, vertical = if (compact) 8.dp else 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = when {
                pillStyle.border != null -> g.text
                tone == PillTone.PRIMARY || chosen -> onAccent
                else -> g.text
            },
            textAlign = TextAlign.Center, maxLines = 2)
    }
}

/**
 * The options of a "Which…?" question as a neat set of glass pills: they arrive with a light stagger, and when the engine has
 * taken one (by voice or by touch) that one settles bright while the others fade away. A tap is that answer only while the
 * agent is still asking (ChoiceGate, applied by the caller).
 */
@Composable
fun ChoiceSet(
    choices: List<String>,
    chosen: String?,
    twi: Boolean,
    onChoice: (String) -> Unit,
    tier: GlassTier,
    modifier: Modifier = Modifier
) {
    val calm = LocalCalmMotion.current
    // One answer per question: after a tap the set goes inactive at once (a double tap must never send a second answer that
    // the next question could receive), until the engine's own reading of the answer arrives as [chosen].
    var tapped by remember(choices) { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEachIndexed { i, c ->
            val arrive = remember(c) { Animatable(if (calm) 1f else 0f) }
            LaunchedEffect(c) { if (!calm) { delay(i * SurfaceMotion.STAGGER_MS.toLong()); arrive.animateTo(1f, tween(240)) } }
            GlassPill(
                text = c,
                onClick = { if (tapped == null) { tapped = c; onChoice(c) } },
                modifier = Modifier.fillMaxWidth().graphicsLayer {
                    alpha = arrive.value; translationY = (1f - arrive.value) * 10.dp.toPx()
                },
                chosen = chosen != null && chosen.equals(c, ignoreCase = true),
                dimmed = chosen != null && !chosen.equals(c, ignoreCase = true),
                available = chosen == null && tapped == null,
                contentDescription = (if (twi) "Bua: " else "Answer: ") + c,
                tier = tier
            )
        }
    }
}
