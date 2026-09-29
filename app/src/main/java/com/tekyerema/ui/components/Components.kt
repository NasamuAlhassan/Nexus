package com.tekyerema.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tekyerema.ui.theme.LocalCalmMotion
import com.tekyerema.ui.theme.Tk
import com.tekyerema.ui.theme.TkHaptic
import com.tekyerema.ui.theme.TkMotion
import com.tekyerema.ui.theme.tkHaptic
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Horizontal page margin, everywhere. */
val PageMargin = 24.dp

/** A pressed primary button: a cool grey under Ink text (≈14:1). */
private val PressedFill = Color(0xFFD3DAEA)
/** The navigation indicator pill: a lift of the ink, decorative (the label and icon carry the contrast). */
private val NavPill = Color(0xFF1B2547)

// ---------------------------------------------------------------------------------------- brand

/**
 * The Tɛkyerɛma mark drawn live: the white ear spiral and the cyan pulse from the supplied icon,
 * on a transparent background. Identical geometry to res/drawable/ic_launcher_foreground.xml.
 */
@Composable
fun BrandMark(size: Dp, modifier: Modifier = Modifier, ear: Color = Color.White, pulse: Color = Tk.Cyan) {
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        val s = this.size.minDimension / 108f
        fun ease(a: Float, b: Float, t: Float) = a + (b - a) * (1f - cos(PI.toFloat() * t)) / 2f
        fun rAt(th: Float) = when {
            th <= 270f -> ease(13.3f, 19.5f, (th - 165f) / 105f)
            th <= 360f -> ease(19.5f, 19.3f, (th - 270f) / 90f)
            else -> ease(19.3f, 12.7f, (th - 360f) / 76f)
        }
        val earPath = Path()
        var th = 165f
        var first = true
        while (th <= 436f) {
            val r = rAt(th); val a = th * PI.toFloat() / 180f
            val x = (54f + r * cos(a)) * s; val y = (53f + r * sin(a)) * s
            if (first) { earPath.moveTo(x, y); first = false } else earPath.lineTo(x, y)
            th += 2f
        }
        drawPath(earPath, ear, style = Stroke(width = 6.2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val pts = listOf(22.2f to 47.9f, 33.5f to 47.9f, 36.6f to 40.2f, 43.7f to 55.6f, 48.9f to 42.7f, 54.0f to 48.9f, 56.6f to 47.9f, 73.5f to 47.9f)
        val pulsePath = Path().apply {
            pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * s, y * s) else lineTo(x * s, y * s) }
        }
        drawPath(pulsePath, pulse, style = Stroke(width = 1.65f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Mark + wordmark, top-left, read by TalkBack as one heading. */
@Composable
fun BrandRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { heading(); contentDescription = "Tɛkyerɛma" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        BrandMark(size = 36.dp)
        Spacer(Modifier.width(2.dp))
        Text("Tɛkyerɛma", style = MaterialTheme.typography.titleSmall, color = Tk.Text)
    }
}

// ----------------------------------------------------------------------------------- navigation

enum class Section(val label: String) { HOME("Home"), HISTORY("History"), SETTINGS("Settings") }

/** Material "history" glyph (not in the core icon set). */
val HistoryIcon: ImageVector by lazy {
    ImageVector.Builder("History", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.White), pathFillType = PathFillType.NonZero) {
            moveTo(13f, 3f); curveToRelative(-4.97f, 0f, -9f, 4.03f, -9f, 9f)
            lineTo(1f, 12f); lineToRelative(3.89f, 3.89f); lineToRelative(0.07f, 0.14f); lineTo(9f, 12f); lineTo(6f, 12f)
            curveToRelative(0f, -3.87f, 3.13f, -7f, 7f, -7f); reflectiveCurveToRelative(7f, 3.13f, 7f, 7f)
            reflectiveCurveToRelative(-3.13f, 7f, -7f, 7f); curveToRelative(-1.93f, 0f, -3.68f, -0.79f, -4.94f, -2.06f)
            lineToRelative(-1.42f, 1.42f); curveTo(8.27f, 19.99f, 10.51f, 21f, 13f, 21f)
            curveToRelative(4.97f, 0f, 9f, -4.03f, 9f, -9f); reflectiveCurveToRelative(-4.03f, -9f, -9f, -9f); close()
            moveTo(12f, 8f); verticalLineToRelative(5f); lineToRelative(4.28f, 2.54f); lineToRelative(0.72f, -1.21f)
            lineToRelative(-3.5f, -2.08f); lineTo(13.5f, 8f); lineTo(12f, 8f); close()
        }
    }.build()
}

/**
 * Three quiet destinations under a hairline. Large targets; the current one is brighter and sits on a pill that
 * grows out from the icon on a spring (Material's navigation indicator), so the change of place is felt, not just seen.
 */
@Composable
fun QuietNav(current: Section, onSelect: (Section) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Hairline()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).semantics { contentDescription = "Navigation" },
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Section.entries.forEach { s -> NavItem(s, s == current) { onSelect(s) } }
        }
    }
}

@Composable
private fun RowScope.NavItem(section: Section, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val calm = LocalCalmMotion.current
    val tint by animateColorAsState(if (selected) Tk.Text else Tk.TextTertiary, TkMotion.defaultEffects(), label = "nav tint")
    // 0 → 1 on a bouncy spatial spring: the pill overshoots its width a touch, then settles (calm: it just fades).
    val grow by animateFloatAsState(if (selected) 1f else 0f,
        if (calm) TkMotion.fastEffects() else TkMotion.fastSpatial(), label = "nav pill")
    Column(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(14.dp))
            .then(if (focused) Modifier.border(2.dp, Tk.Focus, RoundedCornerShape(14.dp)) else Modifier)
            .selectable(selected = selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected; contentDescription = section.label }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier.size(width = 60.dp, height = 32.dp).drawBehind {
                val g = grow.coerceIn(0f, 1.08f)
                if (g > 0.01f) {
                    val w = size.width * (if (calm) 1f else g)
                    drawRoundRect(NavPill.copy(alpha = grow.coerceIn(0f, 1f)), topLeft = Offset((size.width - w) / 2f, 0f),
                        size = androidx.compose.ui.geometry.Size(w, size.height), cornerRadius = CornerRadius(size.height / 2f))
                }
            },
            contentAlignment = Alignment.Center
        ) {
            when (section) {
                Section.HOME -> Icon(Icons.Outlined.Home, null, tint = tint, modifier = Modifier.size(22.dp))
                Section.HISTORY -> Icon(HistoryIcon, null, tint = tint, modifier = Modifier.size(22.dp))
                Section.SETTINGS -> Icon(Icons.Outlined.Settings, null, tint = tint, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(section.label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

// ------------------------------------------------------------------------------------ primitives

@Composable
fun Hairline(modifier: Modifier = Modifier, inset: Dp = 0.dp) {
    Box(modifier.fillMaxWidth().padding(start = inset).height(1.dp).background(Tk.Hairline))
}

/**
 * Editorial page header: an optional back control on its own line, then a large left-aligned
 * title. Titles are TalkBack headings.
 */
@Composable
fun PageTitle(text: String, onBack: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    // K-17: the page title is also the pane title, so TalkBack announces "Voice", "Permissions"… on arrival.
    Column(modifier.fillMaxWidth().semantics { paneTitle = text }) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp, top = 4.dp).size(52.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = Tk.Text)
            }
        } else Spacer(Modifier.height(24.dp))
        Text(
            text, style = MaterialTheme.typography.displaySmall, color = Tk.Text,
            modifier = Modifier.semantics { heading() }.padding(start = PageMargin, end = PageMargin, top = 8.dp, bottom = 16.dp)
        )
    }
}

/** Small uppercase section name with wide tracking. */
@Composable
fun Overline(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelMedium, color = Tk.TextSecondary,
        modifier = modifier.padding(start = PageMargin, end = PageMargin, top = 26.dp, bottom = 10.dp).semantics { heading() }
    )
}

/** Body copy under a title or at the foot of a list. */
@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Tk.TextSecondary,
        modifier = modifier.padding(horizontal = PageMargin, vertical = 10.dp))
}

/**
 * Shared row chrome: full-width, 68 dp minimum, white focus outline, hairline below. A press lights the row with a
 * soft ink wash that fades back on release (there was no touch response at all), drawn in the draw phase only.
 */
@Composable
private fun RowShell(
    modifier: Modifier,
    interaction: MutableInteractionSource,
    divider: Boolean,
    content: @Composable RowScope.() -> Unit
) {
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val wash by animateFloatAsState(if (pressed) 1f else 0f,
        if (pressed) TkMotion.fastEffects() else TkMotion.slowEffects(), label = "row press")
    Column {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .drawBehind { if (wash > 0.01f) drawRect(Tk.Ink2.copy(alpha = wash)) }
                .then(if (focused) Modifier.border(2.dp, Tk.Focus) else Modifier)
                .defaultMinSize(minHeight = 68.dp)
                .padding(horizontal = PageMargin, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
        if (divider) Hairline(inset = PageMargin)
    }
}

/** A settings row: label left, optional value right. TalkBack reads "label, value, button". */
@Composable
fun SettingRow(label: String, value: String? = null, divider: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    RowShell(
        modifier = Modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        interaction = interaction, divider = divider
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Tk.Text, modifier = Modifier.weight(1f))
        if (value != null) {
            Spacer(Modifier.width(16.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium, color = Tk.TextSecondary, textAlign = TextAlign.End)
        }
    }
}

@Composable
fun SwitchRow(label: String, description: String? = null, checked: Boolean, divider: Boolean = true, onChange: (Boolean) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    RowShell(
        modifier = Modifier.toggleable(value = checked, interactionSource = interaction, indication = null, role = Role.Switch, onValueChange = onChange),
        interaction = interaction, divider = divider
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = Tk.Text)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = Tk.TextSecondary, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Tk.Ink, checkedTrackColor = Tk.Cyan,
                uncheckedThumbColor = Tk.TextSecondary, uncheckedTrackColor = Color.Transparent, uncheckedBorderColor = Tk.TextTertiary
            )
        )
    }
}

@Composable
fun ChoiceRow(label: String, description: String? = null, selected: Boolean, divider: Boolean = true, onSelect: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val calm = LocalCalmMotion.current
    // The dot pops in on a bouncy spring (calm: it appears); the ring recolours with it.
    val dot by animateFloatAsState(if (selected) 1f else 0f,
        if (calm) TkMotion.fastEffects() else TkMotion.fastSpatial(), label = "radio dot")
    val ring by animateColorAsState(if (selected) Tk.Cyan else Tk.TextTertiary, TkMotion.defaultEffects(), label = "radio ring")
    RowShell(
        modifier = Modifier.selectable(selected = selected, interactionSource = interaction, indication = null, role = Role.RadioButton, onClick = onSelect),
        interaction = interaction, divider = divider
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = Tk.Text)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = Tk.TextSecondary, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(16.dp))
        Box(
            Modifier.size(24.dp).border(2.dp, ring, CircleShape).drawBehind {
                val d = dot.coerceAtLeast(0f)
                if (d > 0.01f) drawCircle(Tk.Cyan, radius = 6.dp.toPx() * (if (calm) 1f else d), alpha = d.coerceAtMost(1f))
            }
        )
    }
}

/**
 * The one big action on a page (60 dp, full width). Filled = primary, outlined = secondary.
 *
 * It is physical: a press squashes it a little on a fast spring and it springs back when released (calm motion: it
 * only darkens), and a tap answers with the system's haptic for that kind of action ([haptic]).
 */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    contentDescription: String? = null,
    haptic: TkHaptic = TkHaptic.TAP
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val calm = LocalCalmMotion.current
    val view = LocalView.current
    val shape = RoundedCornerShape(30.dp)
    val scale by animateFloatAsState(if (pressed && !calm) 0.95f else 1f,
        if (pressed) TkMotion.fastSpatial() else TkMotion.release(), label = "button press")
    // Pressed: the white button dims to a cool grey (Ink text still ~14:1); the outlined one fills with ink.
    val fill by animateColorAsState(
        when { filled && pressed -> PressedFill; filled -> Tk.Text; pressed -> Tk.Ink2; else -> Color.Transparent },
        TkMotion.fastEffects(), label = "button fill")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .drawBehind { drawRect(fill) }
            .border(if (focused) 3.dp else 1.5.dp, if (focused) Tk.Cyan else if (filled) Tk.Text else Tk.TextSecondary, shape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button,
                onClick = { view.tkHaptic(haptic); onClick() })
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription }
            .padding(horizontal = 24.dp, vertical = 17.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium,
            color = if (filled) Tk.Ink else Tk.Text, textAlign = TextAlign.Center)
    }
}

// --------------------------------------------------------------------------------- command field

/**
 * The small typed-input surface at the foot of the voice screens. Secondary by design: one
 * line, a quiet pill, a send affordance that appears only once there is text. Submitting sends
 * the text to the SAME agent entry point an ASR transcript uses (see VoiceController.submitText).
 *
 * [onSubmit] returns true if the text was accepted; the field then clears and the keyboard hides.
 * [onFocusChanged] lets the caller pause the microphone while the keyboard is open.
 */
@Composable
fun CommandField(
    onSubmit: (String) -> Boolean,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(26.dp)
    val submit = {
        if (onSubmit(text)) { text = ""; keyboard?.hide(); focusManager.clearFocus() }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(Tk.Ink2)
            .border(if (focused) 2.dp else 1.dp, if (focused) Tk.Focus else Tk.Hairline, shape)
            .padding(start = 20.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 14.dp)
                .onFocusChanged { onFocusChanged(it.isFocused) }
                .semantics { contentDescription = "Type a command to Tɛkyerɛma" },
            interactionSource = interaction,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Tk.Text),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Tk.Cyan),
            // No autocorrect: what reaches the agent must be exactly what was typed (Twi words like
            // "bue" would otherwise be silently rewritten by the keyboard).
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Send,
                capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                autoCorrectEnabled = false
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { submit() }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) Text("Type a command…", style = MaterialTheme.typography.bodyMedium, color = Tk.TextTertiary)
                    inner()
                }
            }
        )
        if (text.isNotBlank()) {
            IconButton(onClick = submit, modifier = Modifier.size(48.dp)) {   // A11Y-M4: 48dp Android min touch target
                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Send command", tint = Tk.Cyan, modifier = Modifier.size(24.dp))
            }
        } else Spacer(Modifier.width(14.dp))
    }
}
