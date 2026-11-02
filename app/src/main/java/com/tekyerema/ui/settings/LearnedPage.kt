package com.tekyerema.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tekyerema.memory.LearnedData
import com.tekyerema.ui.Brand
import com.tekyerema.ui.components.GlassGroup
import com.tekyerema.ui.components.Note
import com.tekyerema.ui.components.PageTitle
import com.tekyerema.ui.components.SettingRow
import com.tekyerema.ui.theme.Spectrum
import com.tekyerema.ui.theme.Tk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "What Nexus has learned" (owner's order, 2 Nov 2026; the data is the brain session's memory/LearnedData): the skills the user
 * showed it once (teach mode) and the names the user gave unlabelled buttons (label memory) — each visible and removable here, as
 * by voice ("forget the data bundle skill", "forget the button names for WhatsApp"). Every store is an encrypted file, so reading
 * and deleting run off the main thread. A removal always asks first: a taught skill took the user real effort to make.
 */
@Composable
internal fun LearnedPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var skills by remember { mutableStateOf<List<String>?>(null) }
    var buttons by remember { mutableStateOf<Map<String, List<String>>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var said by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf<Ask?>(null) }

    LaunchedEffect(reload) {
        val loaded = withContext(Dispatchers.IO) {
            val s = try { LearnedData.taughtSkills(context) } catch (_: Throwable) { emptyList() }
            val b = try { LearnedData.buttonNames(context) } catch (_: Throwable) { emptyMap() }
            s to b
        }
        skills = loaded.first; buttons = loaded.second
    }

    fun remove(a: Ask) {
        asking = null
        scope.launch {
            val line = withContext(Dispatchers.IO) {
                try {
                    when (a) {
                        is Ask.Skill -> if (LearnedData.deleteSkill(context, a.name)) "Removed the skill “${a.name}”." else "That skill was already gone."
                        is Ask.App -> LearnedData.forgetButtons(context, a.label).let { n ->
                            if (n > 0) "Forgot ${names(n)} in ${a.label}." else "Nothing to forget in ${a.label}." }
                        Ask.AllButtons -> LearnedData.forgetAllButtons(context).let { n -> if (n > 0) "Forgot all ${names(n)}." else "Nothing to forget." }
                    }
                } catch (_: Throwable) { "Couldn't remove it. Please try again." }
            }
            said = line
            reload++
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        PageTitle("What ${Brand.NAME} has learned", onBack)
        Note("Kept on this phone only. Remove anything here, or by voice.")
        if (said.isNotEmpty()) Note(said, Modifier.semantics { liveRegion = LiveRegionMode.Polite })

        val s = skills
        GlassGroup("Skills you showed me") {
            when {
                s == null -> InfoRow("Loading…")
                s.isEmpty() -> InfoRow("None yet", "Say “watch me”, then do the task once. ${Brand.NAME} learns the steps and can do it for you next time.")
                else -> s.forEachIndexed { i, name ->
                    SettingRow(name, value = "Remove", icon = Icons.Outlined.School, iconTint = Spectrum.Green,
                        divider = i < s.lastIndex) { asking = Ask.Skill(name) }
                }
            }
        }

        val b = buttons
        GlassGroup("Names you gave buttons") {
            when {
                b == null -> InfoRow("Loading…")
                b.isEmpty() -> InfoRow("None yet", "When a button has no name, tell ${Brand.NAME} what it is, and it will remember.")
                else -> {
                    val apps = b.entries.toList()
                    apps.forEachIndexed { i, (app, list) ->
                        val shown = list.take(4).joinToString(", ") + if (list.size > 4) ", and ${list.size - 4} more" else ""
                        SettingRow(app, value = "${list.size}", description = shown, icon = Icons.Outlined.TouchApp, iconTint = Spectrum.Teal,
                            divider = i < apps.lastIndex || apps.size > 1) { asking = Ask.App(app, list.size) }
                    }
                    if (apps.size > 1) SettingRow("Forget all button names", icon = Icons.Outlined.DeleteOutline, iconTint = Spectrum.Coral,
                        divider = false) { asking = Ask.AllButtons }
                }
            }
        }
        Note("By voice: “what have I taught you”, “what button names do you know”, “forget the button names for WhatsApp”.")
    }

    asking?.let { a ->
        val (title, body, yes) = when (a) {
            is Ask.Skill -> Triple("Remove the skill “${a.name}”?", "${Brand.NAME} will no longer know how to do it. You can show it again any time.", "Remove")
            is Ask.App -> Triple("Forget the button names in ${a.label}?", "${Brand.NAME} will forget ${names(a.count)} you gave buttons in ${a.label}.", "Forget")
            Ask.AllButtons -> Triple("Forget all button names?", "${Brand.NAME} will forget every name you gave a button, in every app.", "Forget all")
        }
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(title, modifier = Modifier.semantics { heading() }) },
            text = { Text(body) },
            confirmButton = { TextButton(onClick = { remove(a) }) { Text(yes) } },
            dismissButton = { TextButton(onClick = { asking = null }) { Text("Keep") } }
        )
    }
}

private sealed class Ask {
    data class Skill(val name: String) : Ask()
    data class App(val label: String, val count: Int) : Ask()
    object AllButtons : Ask()
}

private fun names(n: Int) = if (n == 1) "1 button name" else "$n button names"

/** A line that isn't a control (empty or loading): read by TalkBack as one item. */
@Composable
private fun InfoRow(label: String, description: String? = null) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(horizontal = 20.dp, vertical = 14.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Tk.Text)
        if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = Tk.TextSecondary,
            modifier = Modifier.padding(top = 2.dp))
    }
}
