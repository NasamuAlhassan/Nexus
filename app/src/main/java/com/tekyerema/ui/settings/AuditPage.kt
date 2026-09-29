package com.tekyerema.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.GppGood
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tekyerema.audit.AuditChain
import com.tekyerema.audit.AuditLog
import com.tekyerema.audit.AuditView
import com.tekyerema.ui.components.CardMargin
import com.tekyerema.ui.components.IconTile
import com.tekyerema.ui.components.Note
import com.tekyerema.ui.components.PageTitle
import com.tekyerema.ui.theme.Spectrum
import com.tekyerema.ui.theme.Tk
import com.tekyerema.ui.theme.glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WS-C — the activity log, readable: whether the log is intact (its hash chain checks out), then one glass card per
 * request, newest first, each step on its own line — what was proposed, what the policy said, the user's answer to
 * confirmation #N, the outcome and whether it was checked on screen, plus who decided it. Each line is one TalkBack stop.
 */
@Composable
internal fun AuditPage(onBack: () -> Unit) {
    var cards by remember { mutableStateOf<List<AuditView.TaskCard>?>(null) }
    var check by remember { mutableStateOf<AuditChain.Check?>(null) }
    LaunchedEffect(Unit) {
        val (c, k) = withContext(Dispatchers.IO) { AuditView.group(AuditLog.all()) to AuditLog.verify() }
        cards = c; check = k
    }
    val shape = RoundedCornerShape(22.dp)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { PageTitle("Activity log", onBack) }
        item {
            val c = check
            Row(
                Modifier.fillMaxWidth().padding(horizontal = CardMargin).glass(shape, elevation = 8.dp)
                    .padding(horizontal = 16.dp, vertical = 14.dp).semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconTile(if (c == null || c.intact) Icons.Outlined.GppGood else Icons.Outlined.GppMaybe,
                    if (c == null || c.intact) Spectrum.Mint else Spectrum.Rose)
                Spacer(Modifier.width(14.dp))
                Text(if (c == null) "Checking the log…" else AuditView.chainLine(c), style = MaterialTheme.typography.bodyLarge,
                    color = if (c == null || c.intact) Tk.Text else Tk.Danger, modifier = Modifier.weight(1f))
            }
        }
        item {
            Note("Each entry is linked to the one before it, so a change to any of them shows here and in an exported copy. " +
                "When you export, the phone notes where the log ended, so a copy changed later can be told apart. " +
                "Kept for ${com.tekyerema.core.Privacy.historyDays()} days.")
        }
        val list = cards
        if (list != null && list.isEmpty()) item { Note("Nothing yet. What Tɛkyerɛma does for you will appear here.") }
        if (list != null) items(list, key = { it.task.toString() + it.time }) { card -> TaskCardView(card, shape) }
    }
}

@Composable
private fun TaskCardView(card: AuditView.TaskCard, shape: RoundedCornerShape) {
    val time = SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()).format(Date(card.time))
    val (icon, tint) = when {
        card.source == "fast" -> Icons.Outlined.Bolt to Spectrum.Gold
        card.source == "user" -> Icons.Outlined.Tune to Spectrum.Cyan
        card.source == "trigger" -> Icons.Outlined.Schedule to Spectrum.Rose
        card.source.startsWith("remote") -> Icons.Outlined.Computer to Spectrum.Azure
        else -> Icons.Outlined.AutoAwesome to Spectrum.Violet
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = CardMargin).glass(shape, elevation = 8.dp).padding(horizontal = 16.dp, vertical = 14.dp)) {
        val header = "${AuditView.sourceLabel(card.source)}, $time: ${card.title}" + (card.status?.let { ". $it" } ?: "")
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = header }, verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, tint)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(card.title, style = MaterialTheme.typography.bodyLarge, color = Tk.Text, maxLines = 3,
                    modifier = Modifier.semantics { heading() })
                Text(listOfNotNull(AuditView.sourceLabel(card.source), time, card.status).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = Tk.TextSecondary, modifier = Modifier.padding(top = 2.dp))
            }
        }
        card.steps.forEach { s ->
            Spacer(Modifier.height(10.dp))
            val detail = listOfNotNull(
                s.decidedBy.takeIf { it.isNotEmpty() }?.let { "decided by $it" },
                s.tier.takeIf { it.isNotEmpty() }?.let { "risk ${it.removePrefix("TIER")}" },
                s.provenance.takeIf { it.isNotEmpty() }?.let { "from untrusted text" },
                s.reason.takeIf { s.outcome == "blocked" || s.outcome == "not run" }
            ).joinToString(" · ")
            Column(Modifier.fillMaxWidth().padding(start = 54.dp).semantics(mergeDescendants = true) {
                contentDescription = "Step ${s.step}: ${s.sentence()}" + if (detail.isNotEmpty()) ". $detail" else ""
            }) {
                Text("${s.step}. ${s.sentence()}", style = MaterialTheme.typography.bodyMedium, color = Tk.Text)
                if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = Tk.TextSecondary,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
        card.notes.forEach { n ->
            Spacer(Modifier.height(6.dp))
            Text(n, style = MaterialTheme.typography.bodyMedium, color = Tk.TextSecondary, modifier = Modifier.padding(start = 54.dp))
        }
    }
}
