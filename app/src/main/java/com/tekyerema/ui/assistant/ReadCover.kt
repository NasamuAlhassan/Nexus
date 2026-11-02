package com.tekyerema.ui.assistant

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tekyerema.notifications.ReadAloudScreen
import com.tekyerema.ui.theme.answerSized

/**
 * The screen side of private reading (owner's order, 2 Nov 2026; notifications/ReadAloudScreen): with Settings › Privacy ›
 * "Hide messages on screen" on, an answer that quotes messages being read aloud is drawn as who and when only.
 * Null when the switch is off or [text] quotes no message read recently.
 */
@Composable
internal fun rememberReadCover(text: String): ReadAloudScreen.Cover? {
    val context = LocalContext.current
    return remember(text) {
        val on = try { com.tekyerema.settings.Settings(context).hideReadMessages } catch (_: Throwable) { false }
        if (on) ReadAloudScreen.coverFor(text) else null
    }
}

/**
 * "Reading 3 messages aloud", then one line per message — "Ama · WhatsApp · 10 minutes ago" — and a quiet note that the words
 * are hidden. One TalkBack node (the voice is reading the words already; TalkBack must not read them too).
 */
@Composable
internal fun ReadCoverBody(cover: ReadAloudScreen.Cover, speaking: Boolean, modifier: Modifier = Modifier) {
    val title = if (speaking) cover.title else cover.doneTitle
    Column(modifier.clearAndSetSemantics { contentDescription = cover.description.replaceFirst(cover.title, title) }) {
        Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold).answerSized(), color = GlassColors.Text)
        cover.lines.take(6).forEach { l ->
            Text(l.shown, style = MaterialTheme.typography.bodyLarge.answerSized(), color = GlassColors.TextSecondary,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        }
        if (cover.lines.size > 6) Text("and ${cover.lines.size - 6} more", style = MaterialTheme.typography.bodyMedium,
            color = GlassColors.TextSecondary, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.VisibilityOff, contentDescription = null, tint = GlassColors.TextTertiary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Words hidden on screen", style = MaterialTheme.typography.labelMedium, color = GlassColors.TextTertiary)
        }
    }
}
