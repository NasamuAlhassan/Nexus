package com.tekyerema.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HighlightOff
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.tekyerema.ui.components.Hairline
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tekyerema.history.HistoryEntry
import com.tekyerema.history.HistoryStore
import com.tekyerema.history.Outcome
import com.tekyerema.ui.components.CardMargin
import com.tekyerema.ui.components.IconTile
import com.tekyerema.ui.components.Overline
import com.tekyerema.ui.components.PageTitle
import com.tekyerema.ui.theme.LocalCalmMotion
import com.tekyerema.ui.theme.Spectrum
import com.tekyerema.ui.theme.Tk
import com.tekyerema.ui.theme.glass
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val CardShape = RoundedCornerShape(22.dp)

/**
 * What Nexus did, newest first: one pane per day (Today / Yesterday / a date, a heading TalkBack can jump between), a row per
 * task inside it — an icon for what the task was for, the request, the time and an honest outcome in words.
 *
 * Redesign 2 Nov 2026: a card per task made a long stack of separate boxes (and a separate focus stop with its own edge for each);
 * one pane per day is quieter and reads like a list. No "AI" glyphs: a request with no clearer kind shows a speaking head.
 */
@Composable
fun HistoryScreen(modifier: Modifier = Modifier) {
    val all by HistoryStore.entries.collectAsState()
    val entries = remember(all) { all.filterNot { HistoryStore.isTestEntry(it.title) } }
    val groups = remember(entries) { entries.groupBy { dayLabel(it.timeMs) }.toList() }

    Column(modifier.fillMaxSize()) {
        PageTitle("History")
        if (entries.isEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = CardMargin, vertical = 8.dp).glass(CardShape).padding(20.dp)
                    .semantics(mergeDescendants = true) { },
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconTile(Icons.Outlined.History, Spectrum.Teal)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Nothing yet", style = MaterialTheme.typography.titleLarge, color = Tk.Text)
                    Text("What you ask ${com.tekyerema.ui.Brand.NAME} to do will appear here.", style = MaterialTheme.typography.bodyMedium,
                        color = Tk.TextSecondary, modifier = Modifier.padding(top = 4.dp))
                }
            }
            return
        }
        // A task finishing while History is open slides the list down and fades its row in, instead of jumping (full motion).
        val calm = LocalCalmMotion.current
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            groups.forEach { (day, list) ->
                item(key = "h-$day") { Overline(day, if (calm) Modifier else Modifier.animateItem()) }
                item(key = "g-$day") {
                    Column(
                        (if (calm) Modifier else Modifier.animateItem())
                            .fillMaxWidth().padding(horizontal = CardMargin).glass(CardShape, elevation = 8.dp).clip(CardShape)
                    ) {
                        list.forEachIndexed { i, e -> HistoryRow(e, divider = i < list.lastIndex) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(e: HistoryEntry, divider: Boolean) {
    val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(e.timeMs))
    val (icon, tint) = kindOf(e.title)
    val detail = listOf(time, e.outcome.label).filter { it.isNotEmpty() }.joinToString(" · ")
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp)
                // One sentence replaces the texts for TalkBack: a merging node that also carries a description is read with its
                // children as well ("10:44 AM, What is on my screen. What is on my screen. 10:44 AM"), see QuietNav (Gate G, 13 Oct 2026).
                .clearAndSetSemantics {
                    contentDescription = "${e.title}, $time" + e.outcome.label.let { if (it.isEmpty()) "" else ", $it" }
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconTile(icon, tint)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(e.title, style = MaterialTheme.typography.bodyLarge, color = Tk.Text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(detail, color = Tk.TextSecondary, modifier = Modifier.padding(top = 3.dp),
                    style = MaterialTheme.typography.bodySmall.merge(TextStyle(fontFeatureSettings = "tnum")))
            }
            Spacer(Modifier.width(10.dp))
            // The outcome is in the words above; the mark repeats it as a shape (a tick, a warning, a stop square, a cross), never
            // as colour alone.
            val (mark, markTint) = when (e.outcome) {
                Outcome.COMPLETED -> Icons.Outlined.CheckCircle to Tk.Success
                Outcome.UNVERIFIED -> Icons.Outlined.ErrorOutline to Tk.Attention
                Outcome.STOPPED -> Icons.Outlined.StopCircle to Tk.TextTertiary
                else -> Icons.Outlined.HighlightOff to Tk.TextTertiary
            }
            Icon(mark, contentDescription = null, tint = markTint, modifier = Modifier.size(22.dp))
        }
        if (divider) Hairline(inset = 70.dp)
    }
}

/** The kind of task, from the words of its title: an icon for what it was for and a colour from the mark's palette. */
private fun kindOf(title: String): Pair<ImageVector, Color> {
    val t = title.lowercase()
    fun has(vararg w: String) = w.any { Regex("\\b$it").containsMatchIn(t) }
    return when {
        has("cedi", "momo", "mobile money", "airtime", "bundle", "balance", "pay", "transfer") -> Icons.Outlined.Payments to Spectrum.Green
        has("call", "phone", "dial") -> Icons.Outlined.Call to Spectrum.Lime
        has("message", "text", "sms", "whatsapp", "sent", "send", "repl", "email", "telegram") -> Icons.Outlined.ChatBubbleOutline to Spectrum.Teal
        has("alarm", "timer", "remind", "wake") -> Icons.Outlined.Alarm to Spectrum.Gold
        has("music", "song", "play", "youtube", "skip") -> Icons.Outlined.MusicNote to Spectrum.Aqua
        has("photo", "selfie", "camera", "picture", "look") -> Icons.Outlined.PhotoCamera to Spectrum.Aqua
        has("screen", "read", "describe", "what is on", "what's on") -> Icons.Outlined.Visibility to Spectrum.Teal
        has("install", "download") -> Icons.Outlined.Download to Spectrum.Aqua
        has("wi-fi", "wifi", "bluetooth", "volume", "bright", "torch", "flashlight", "setting", "sound", "mode") -> Icons.Outlined.Tune to Spectrum.Aqua
        has("open", "launch", "went to", "go to") -> Icons.Outlined.Apps to Spectrum.Green
        else -> Icons.Outlined.RecordVoiceOver to Spectrum.Green
    }
}

private fun dayLabel(timeMs: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = timeMs }
    fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (sameDay(now, then)) return "Today"
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (sameDay(yesterday, then)) return "Yesterday"
    return SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(timeMs))
}
