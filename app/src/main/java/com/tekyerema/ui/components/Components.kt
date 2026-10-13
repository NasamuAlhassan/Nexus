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
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
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
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tekyerema.ui.theme.LocalCalmMotion
import com.tekyerema.ui.theme.Spectrum
import com.tekyerema.ui.theme.Tk
import com.tekyerema.ui.theme.TkHaptic
import com.tekyerema.ui.theme.TkMotion
import com.tekyerema.ui.theme.glass
import com.tekyerema.ui.theme.tkHaptic
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Horizontal page margin, everywhere. */
val PageMargin = 24.dp
/** Glass cards sit a little wider than the text margin, so their text lines up with page titles. */
val CardMargin = 16.dp
private val CardShape = RoundedCornerShape(26.dp)

// ---------------------------------------------------------------------------------------- brand

/**
 * The Tɛkyerɛma mark drawn live: the ear spiral and the cyan pulse from the supplied icon, on a transparent
 * background. Identical geometry to res/drawable/ic_launcher_foreground.xml. The ear follows the text colour, so the
 * mark reads on both palettes.
 */
@Composable
fun BrandMark(size: Dp, modifier: Modifier = Modifier, ear: Color = Tk.Text, pulse: Color = Tk.Cyan) {
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

/** Mark + wordmark, top-left, read by TalkBack as one heading ("Tɛkyerɛma, heading" — the wordmark is the text; a second
 *  contentDescription would be read as well, see NavItem). */
@Composable
fun BrandRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        BrandMark(size = 34.dp)
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
 * The tab bar: a floating pane of glass (Liquid Glass's "controls float above content"). The current place sits on a
 * soft capsule of the accent that grows on a spring; large targets (64 dp tall), a label under every icon.
 */
@Composable
fun QuietNav(current: Section, onSelect: (Section) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(34.dp), strong = true, elevation = 18.dp)
                .padding(horizontal = 6.dp, vertical = 5.dp)
                // Gate G (TalkBack 15 on the emulator, 13 October 2026): a description on this Row made the bar itself a
                // TalkBack stop that read "Navigation" and took the screen's first focus; a selectable group is what a tab
                // row is, and TalkBack then counts the tabs for the user.
                .selectableGroup(),
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
    val grow by animateFloatAsState(if (selected) 1f else 0f,
        if (calm) TkMotion.fastEffects() else TkMotion.fastSpatial(), label = "nav capsule")
    val capsule = Tk.Cyan.copy(alpha = if (Tk.Dark) 0.22f else 0.14f)
    val focus = Tk.Focus
    Column(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(28.dp))
            .drawBehind {
                val g = grow.coerceIn(0f, 1.08f)
                if (g > 0.01f) {
                    val w = size.width * (if (calm) 1f else (0.55f + 0.45f * g))
                    drawRoundRect(capsule.copy(alpha = capsule.alpha * grow.coerceIn(0f, 1f)),
                        topLeft = Offset((size.width - w) / 2f, 0f),
                        size = androidx.compose.ui.geometry.Size(w, size.height), cornerRadius = CornerRadius(size.height / 2f))
                }
            }
            .then(if (focused) Modifier.border(2.dp, focus, RoundedCornerShape(28.dp)) else Modifier)
            // No contentDescription here: Compose hands a described, merging node's description to TalkBack as an extra
            // child next to the label, and TalkBack read "History. History. Tab" (measured, 13 October 2026). The label
            // Text below is the tab's name; `selectable` already carries the selected state and the Tab role.
            .selectable(selected = selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (section) {
            Section.HOME -> Icon(Icons.Outlined.Home, null, tint = tint, modifier = Modifier.size(24.dp))
            Section.HISTORY -> Icon(HistoryIcon, null, tint = tint, modifier = Modifier.size(24.dp))
            Section.SETTINGS -> Icon(Icons.Outlined.Settings, null, tint = tint, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(2.dp))
        Text(section.label, style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, color = tint)
    }
}

// ------------------------------------------------------------------------------------ primitives

@Composable
fun Hairline(modifier: Modifier = Modifier, inset: Dp = 0.dp) {
    Box(modifier.fillMaxWidth().padding(start = inset).height(1.dp).background(Tk.Hairline))
}

@Composable
fun PageTitle(text: String, onBack: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().semantics { paneTitle = text }) {
        if (onBack != null) {
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Back", onClick = onBack)
                    .padding(start = PageMargin, end = PageMargin, top = 16.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, tint = Tk.Text,
                    modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Text(text, style = MaterialTheme.typography.displaySmall, color = Tk.Text,
                    modifier = Modifier.semantics { heading() })
            }
        } else {
            Spacer(Modifier.height(20.dp))
            Text(
                text, style = MaterialTheme.typography.displaySmall, color = Tk.Text,
                modifier = Modifier.semantics { heading() }.padding(start = PageMargin, end = PageMargin, top = 10.dp, bottom = 14.dp)
            )
        }
    }
}

/** Small uppercase section name with wide tracking. */
@Composable
fun Overline(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelMedium, color = Tk.TextSecondary,
        modifier = modifier.padding(start = PageMargin + 4.dp, end = PageMargin, top = 22.dp, bottom = 8.dp).semantics { heading() }
    )
}

/** Body copy under a title or at the foot of a list. */
@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Tk.TextSecondary,
        modifier = modifier.padding(horizontal = PageMargin + 4.dp, vertical = 10.dp))
}

/**
 * A group of rows on one pane of glass, with an optional section name above it. Rows inside draw their own inset
 * dividers; pass `divider = false` on the last one.
 */
@Composable
fun GlassGroup(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) Overline(title) else Spacer(Modifier.height(8.dp))
        Column(
            Modifier.fillMaxWidth().padding(horizontal = CardMargin).glass(CardShape, elevation = 10.dp).clip(CardShape),
            content = content
        )
    }
}

/**
 * A small rounded tile holding a row's icon, tinted with one of the spectrum colours on a soft backing of the same —
 * the quick visual anchor iOS Settings uses, sized for low vision (40 dp).
 */
@Composable
fun IconTile(icon: ImageVector, tint: Color, modifier: Modifier = Modifier) {
    val backing = tint.copy(alpha = Tk.palette.tileBacking + if (Tk.Dark) 0.06f else 0f)
    Box(
        modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(backing),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (Tk.Dark) tint else lerpDarker(tint), modifier = Modifier.size(22.dp))
    }
}

/** On the pale palette the spectrum hues are deepened so the glyph keeps ≥3:1 against its tile. */
private fun lerpDarker(c: Color): Color = androidx.compose.ui.graphics.lerp(c, Color(0xFF0D1326), 0.28f)

/**
 * Shared row chrome inside a glass group: 64 dp minimum, a focus outline, an inset hairline below. A press lights the
 * row with a soft wash that fades on release, drawn in the draw phase only.
 */
@Composable
private fun RowShell(
    modifier: Modifier,
    interaction: MutableInteractionSource,
    divider: Boolean,
    dividerInset: Dp = 20.dp,
    content: @Composable RowScope.() -> Unit
) {
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val wash by animateFloatAsState(if (pressed) 1f else 0f,
        if (pressed) TkMotion.fastEffects() else TkMotion.slowEffects(), label = "row press")
    val washColor = Tk.Ink2
    val focus = Tk.Focus
    Column {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .drawBehind { if (wash > 0.01f) drawRect(washColor.copy(alpha = washColor.alpha * wash)) }
                .then(if (focused) Modifier.border(2.dp, focus, RoundedCornerShape(18.dp)) else Modifier)
                .defaultMinSize(minHeight = 64.dp)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
        if (divider) Hairline(inset = dividerInset)
    }
}

/** A settings row: optional icon tile, label, value, chevron. TalkBack reads "label, value, button". */
@Composable
fun SettingRow(
    label: String,
    value: String? = null,
    divider: Boolean = true,
    icon: ImageVector? = null,
    iconTint: Color = Spectrum.Teal,
    description: String? = null,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    RowShell(
        modifier = Modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        interaction = interaction, divider = divider, dividerInset = if (icon != null) 74.dp else 20.dp
    ) {
        if (icon != null) { IconTile(icon, iconTint); Spacer(Modifier.width(16.dp)) }
        // A long value ("Groq → Google Gemini") goes under the description: on the right it squeezed the title and
        // description into a one-word-per-line column (Control page, A20e 2026-09-29). A short one stays on the right, capped.
        val longValue = (value?.length ?: 0) > 14
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = Tk.Text)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = Tk.TextSecondary,
                modifier = Modifier.padding(top = 2.dp))
            if (longValue) Text(value!!, style = MaterialTheme.typography.bodyMedium, color = Tk.TextSecondary,
                modifier = Modifier.padding(top = 4.dp))
        }
        if (!value.isNullOrEmpty() && !longValue) {
            Spacer(Modifier.width(12.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium, color = Tk.TextSecondary, textAlign = TextAlign.End,
                maxLines = 2, modifier = Modifier.widthIn(max = 132.dp))
        }
        Spacer(Modifier.width(4.dp))
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = Tk.TextTertiary, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun SwitchRow(
    label: String,
    description: String? = null,
    checked: Boolean,
    divider: Boolean = true,
    icon: ImageVector? = null,
    iconTint: Color = Spectrum.Teal,
    onChange: (Boolean) -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    RowShell(
        modifier = Modifier.toggleable(value = checked, interactionSource = interaction, indication = null, role = Role.Switch, onValueChange = onChange),
        interaction = interaction, divider = divider, dividerInset = if (icon != null) 74.dp else 20.dp
    ) {
        if (icon != null) { IconTile(icon, iconTint); Spacer(Modifier.width(16.dp)) }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = Tk.Text)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = Tk.TextSecondary, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = Tk.Cyan, checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Tk.TextSecondary, uncheckedTrackColor = Tk.Ink2, uncheckedBorderColor = Tk.TextTertiary
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
    val fill = Tk.Cyan
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
                if (d > 0.01f) drawCircle(fill, radius = 6.dp.toPx() * (if (calm) 1f else d), alpha = d.coerceAtMost(1f))
            }
        )
    }
}

/**
 * The one big action on a page (60 dp). Filled = primary (the text colour as a solid capsule — white on dark,
 * ink on light), outlined = secondary glass.
 *
 * It is physical: a press squashes it a little on a fast spring and it springs back (calm motion: it only darkens),
 * and a tap answers with the system's haptic for that kind of action ([haptic]).
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
    val solid = Tk.Text
    val fill by animateColorAsState(
        when { filled && pressed -> solid.copy(alpha = 0.82f); filled -> solid; pressed -> Tk.Ink2; else -> Color.Transparent },
        TkMotion.fastEffects(), label = "button fill")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (filled) Modifier else Modifier.glass(shape, elevation = 6.dp))
            .clip(shape)
            .drawBehind { drawRect(fill) }
            .border(if (focused) 3.dp else 0.dp, if (focused) Tk.Cyan else Color.Transparent, shape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button,
                onClick = { view.tkHaptic(haptic); onClick() })
            // A spoken label replaces the visible text for TalkBack ("No, don't do it", not "No, don't do it. No."): the
            // click and the Button role come from the modifier before this one and stay (Gate G, 13 October 2026).
            .then(if (contentDescription != null) Modifier.clearAndSetSemantics { this.contentDescription = contentDescription } else Modifier)
            .padding(horizontal = 24.dp, vertical = 17.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            color = if (filled) Tk.Ink else Tk.Text, textAlign = TextAlign.Center)
    }
}

// --------------------------------------------------------------------------------- command field

/**
 * The typed-input pill at the foot of the voice screens: a floating pane of glass with a mic button (voice is still
 * the first way in) that turns into Send once there is text. Submitting sends the text to the SAME agent entry point
 * an ASR transcript uses (see VoiceController.submitText).
 *
 * [onSubmit] returns true if the text was accepted; the field then clears and the keyboard hides.
 * [onFocusChanged] lets the caller pause the microphone while the keyboard is open.
 */
@Composable
fun CommandField(
    onSubmit: (String) -> Boolean,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onMic: (() -> Unit)? = null
) {
    var text by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(30.dp)
    val submit = {
        if (onSubmit(text)) { text = ""; keyboard?.hide(); focusManager.clearFocus() }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .glass(shape, strong = true, elevation = 12.dp)
            .border(if (focused) 2.dp else 0.dp, if (focused) Tk.Focus else Color.Transparent, shape)
            .padding(start = 22.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 16.dp)
                .onFocusChanged { onFocusChanged(it.isFocused) }
                .semantics { contentDescription = "Type a command to Tɛkyerɛma" },
            interactionSource = interaction,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Tk.Text),
            cursorBrush = SolidColor(Tk.Cyan),
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
                    // The placeholder is for the eyes: TalkBack already says "Edit box. Type a command to Tɛkyerɛma" from the
                    // field's description, and read this on top of it (13 October 2026).
                    if (text.isEmpty()) Text("Ask Tɛkyerɛma…", style = MaterialTheme.typography.bodyLarge, color = Tk.TextTertiary,
                        modifier = Modifier.clearAndSetSemantics { })
                    inner()
                }
            }
        )
        val accent = Tk.Cyan
        val onAccent = Tk.OnAccent
        when {
            text.isNotBlank() -> IconButton(onClick = submit, modifier = Modifier.size(48.dp)) {   // A11Y-M4: 48dp min target
                Box(Modifier.size(40.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Send command", tint = onAccent, modifier = Modifier.size(20.dp))
                }
            }
            // Gate G audit (emulator accessibility tree, 13 October 2026): this button read as "Button" — the glyph is drawn, not an Icon.
            onMic != null -> IconButton(onClick = onMic, modifier = Modifier.size(48.dp).semantics { contentDescription = "Talk to Tɛkyerɛma" }) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(
                        Brush.linearGradient(listOf(Spectrum.Aqua, Spectrum.Green), start = Offset.Zero, end = Offset(90f, 90f))
                    ),
                    contentAlignment = Alignment.Center
                ) { MicGlyph(Color.White, Modifier.size(20.dp)) }
            }
            else -> Spacer(Modifier.width(14.dp))
        }
    }
}

/** A microphone glyph (not in the core icon set), drawn so it needs no extra dependency. Silent for TalkBack: the button
 *  around it carries the label (it used to add "Talk" — "Talk to Tɛkyerɛma. Talk. Button", measured 13 October 2026). */
@Composable
private fun MicGlyph(color: Color, modifier: Modifier) {
    Canvas(modifier.clearAndSetSemantics { }) {
        val w = size.width; val h = size.height
        val stroke = Stroke(width = w * 0.1f, cap = StrokeCap.Round)
        drawRoundRect(color, topLeft = Offset(w * 0.34f, h * 0.06f), size = androidx.compose.ui.geometry.Size(w * 0.32f, h * 0.52f),
            cornerRadius = CornerRadius(w * 0.16f))
        drawArc(color, 0f, 180f, false, topLeft = Offset(w * 0.2f, h * 0.24f), size = androidx.compose.ui.geometry.Size(w * 0.6f, h * 0.5f), style = stroke)
        drawLine(color, Offset(w * 0.5f, h * 0.74f), Offset(w * 0.5f, h * 0.92f), strokeWidth = w * 0.1f, cap = StrokeCap.Round)
    }
}
