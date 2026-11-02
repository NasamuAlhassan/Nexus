package com.tekyerema.ui.home

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tekyerema.history.HistoryStore
import com.tekyerema.ui.theme.Tk
import com.tekyerema.ui.theme.TkHaptic
import com.tekyerema.ui.theme.glass
import com.tekyerema.ui.theme.tkHaptic
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Calendar

/** Today's tiles and the user's removals, kept across restarts (Usuals: the order holds for the day). */
object UsualStore {
    private const val PREFS = "nexus_usual"
    private const val KEY_TILES = "tiles"
    private const val KEY_DAY = "day"
    private const val KEY_HIDDEN = "hidden"
    private const val KEY_ON = "on"
    @Volatile private var prefs: android.content.SharedPreferences? = null
    /** Bumped when the user removes a tile, so Home recomputes at once. */
    val version = MutableStateFlow(0)

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun today(nowMs: Long = System.currentTimeMillis()): Int =
        Calendar.getInstance().apply { timeInMillis = nowMs }.let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) }

    fun tiles(): List<String> = prefs?.getString(KEY_TILES, "").orEmpty().split('\n').filter { it.isNotBlank() }
    fun day(): Int = prefs?.getInt(KEY_DAY, 0) ?: 0
    fun hidden(): Set<String> = prefs?.getStringSet(KEY_HIDDEN, emptySet()).orEmpty()

    fun save(keys: List<String>, day: Int) {
        prefs?.edit()?.putString(KEY_TILES, keys.joinToString("\n"))?.putInt(KEY_DAY, day)?.apply()
    }

    /** The user's switch (Settings › Accessibility › Home): on by default. */
    var enabled: Boolean
        get() = prefs?.getBoolean(KEY_ON, true) ?: true
        set(v) { prefs?.edit()?.putBoolean(KEY_ON, v)?.apply(); version.value++ }

    /** Bring back every tile the user removed (they return when they qualify again). */
    fun restore() {
        prefs?.edit()?.remove(KEY_HIDDEN)?.apply()
        version.value++
    }

    fun hide(key: String) {
        prefs?.edit()?.putStringSet(KEY_HIDDEN, hidden() + key)?.putString(KEY_TILES, tiles().filter { it != key }.joinToString("\n"))?.apply()
        version.value++
    }
}

/**
 * "Your usual" on Home: up to three tiles, each a request this person often makes, done with one tap (Usuals explains the
 * rules). A tap sends the tile's words exactly as if typed; a money request still meets its read-back and the PIN step, and
 * anything with an amount never becomes a tile. Long-press (TalkBack: the "Remove from your usual" action) removes one for good.
 *
 * Three side by side at normal text sizes; at large text (≥ 1.3× font) they stack, two at most, full width — a label must never
 * be cut to a few letters.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UsualTiles(onRun: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    remember(context) { UsualStore.init(context) }
    val all by HistoryStore.entries.collectAsState()
    val removed by UsualStore.version.collectAsState()
    var tiles by remember { mutableStateOf(emptyList<Usuals.Usual>()) }
    LaunchedEffect(all, removed) {
        val now = System.currentTimeMillis()
        val day = UsualStore.today(now)
        val real = all.filterNot { HistoryStore.isTestEntry(it.title) }
        val ranked = Usuals.rank(real, now, UsualStore.hidden())
        val settled = Usuals.settle(UsualStore.tiles(), ranked, newDay = UsualStore.day() != day)
        UsualStore.save(settled.map { it.key }, day)
        tiles = settled
    }
    if (tiles.isEmpty() || !UsualStore.enabled) return
    val stacked = LocalDensity.current.fontScale >= 1.3f
    val shown = if (stacked) tiles.take(2) else tiles
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text("YOUR USUAL", style = MaterialTheme.typography.labelMedium, color = Tk.TextSecondary,
            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp).semantics { heading() })
        if (stacked) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            shown.forEach { u -> UsualTile(u, wide = true, onRun = onRun, modifier = Modifier.fillMaxWidth()) }
        } else Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // One height for the row: the tallest label sets it, so three tiles never stand at different heights.
            shown.forEach { u -> UsualTile(u, wide = false, onRun = onRun, modifier = Modifier.weight(1f).fillMaxHeight()) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UsualTile(u: Usuals.Usual, wide: Boolean, onRun: (String) -> Unit, modifier: Modifier) {
    val view = LocalView.current
    var menu by remember { mutableStateOf(false) }
    val (icon, tint) = com.tekyerema.ui.history.kindOf(u.label)
    val shape = RoundedCornerShape(20.dp)
    val run = { view.tkHaptic(TkHaptic.CONFIRM); onRun(u.label) }
    val remove = { UsualStore.hide(u.key) }
    Box(modifier) {
        val inner: @Composable () -> Unit = {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            if (wide) Spacer(Modifier.width(14.dp)) else Spacer(Modifier.size(6.dp))
            Text(u.label, style = MaterialTheme.typography.titleSmall, color = Tk.Text,
                textAlign = if (wide) TextAlign.Start else TextAlign.Center, maxLines = if (wide) 2 else 3, overflow = TextOverflow.Ellipsis)
        }
        val m = Modifier.fillMaxWidth().then(if (wide) Modifier else Modifier.fillMaxHeight()).heightIn(min = if (wide) 64.dp else 88.dp)
            .glass(shape, strong = true, elevation = 6.dp)
            .combinedClickable(onClick = run, onLongClick = { view.tkHaptic(TkHaptic.TAP); menu = true })
            // One node: the request's words, a button; double-tap runs it, the action removes it.
            .clearAndSetSemantics {
                contentDescription = u.label
                role = Role.Button
                onClick(label = "Do this now") { run(); true }
                onLongClick(label = "Remove from your usual") { remove(); true }
                customActions = listOf(CustomAccessibilityAction("Remove from your usual") { remove(); true })
            }
            .padding(horizontal = if (wide) 14.dp else 8.dp, vertical = 12.dp)
        if (wide) Row(m, verticalAlignment = Alignment.CenterVertically) { inner() }
        else Column(m, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { inner() }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Remove from your usual", style = MaterialTheme.typography.bodyLarge) },
                onClick = { menu = false; remove() }, modifier = Modifier.heightIn(min = 56.dp))
        }
    }
}
