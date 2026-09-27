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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.tekyerema.history.HistoryEntry
import com.tekyerema.history.HistoryStore
import com.tekyerema.ui.components.Hairline
import com.tekyerema.ui.components.Overline
import com.tekyerema.ui.components.PageMargin
import com.tekyerema.ui.components.PageTitle
import com.tekyerema.ui.theme.Tk
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Today / Yesterday / a date, then "10:42 AM — Called Prince". That is all. */
@Composable
fun HistoryScreen(modifier: Modifier = Modifier) {
    val entries by HistoryStore.entries.collectAsState()
    val groups = entries.groupBy { dayLabel(it.timeMs) }

    Column(modifier.fillMaxSize()) {
        PageTitle("History")
        if (entries.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = PageMargin, vertical = 24.dp)) {
                Text("Nothing yet.", style = MaterialTheme.typography.titleLarge, color = Tk.Text)
                Text("What you ask Tɛkyerɛma to do will appear here.", style = MaterialTheme.typography.bodyLarge,
                    color = Tk.TextSecondary, modifier = Modifier.padding(top = 6.dp))
            }
            return
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            groups.forEach { (day, list) ->
                item(key = "h-$day") { Overline(day) }
                itemsIndexed(list, key = { _, e -> e.timeMs.toString() + e.title }) { i, e ->
                    HistoryRow(e)
                    if (i < list.lastIndex) Hairline(inset = PageMargin + 84.dp)
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(e: HistoryEntry) {
    val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(e.timeMs))
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .padding(horizontal = PageMargin, vertical = 14.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$time, ${e.title}" + e.outcome.label.let { if (it.isEmpty()) "" else ", $it" }
            },
        verticalAlignment = Alignment.Top
    ) {
        Text(
            time, color = Tk.TextSecondary, modifier = Modifier.width(76.dp).padding(top = 3.dp),
            style = MaterialTheme.typography.bodySmall.merge(TextStyle(fontFeatureSettings = "tnum"))
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, style = MaterialTheme.typography.bodyLarge, color = Tk.Text)
            // §P1 — state the real outcome, not a blanket "Not completed". Empty for a clean completion.
            if (e.outcome.label.isNotEmpty())
                Text(e.outcome.label, style = MaterialTheme.typography.bodySmall, color = Tk.TextTertiary, modifier = Modifier.padding(top = 2.dp))
        }
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
