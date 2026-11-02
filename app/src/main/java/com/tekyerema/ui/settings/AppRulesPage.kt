package com.tekyerema.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.tekyerema.android.AppResolver
import com.tekyerema.operator.AppRule
import com.tekyerema.operator.AppRuleVoice
import com.tekyerema.operator.AppRules
import com.tekyerema.operator.OperatorPolicy
import com.tekyerema.policy.PolicyConfig
import com.tekyerema.settings.Settings
import com.tekyerema.ui.components.ChoiceRow
import com.tekyerema.ui.components.GlassGroup
import com.tekyerema.ui.components.Hairline
import com.tekyerema.ui.components.IconTile
import com.tekyerema.ui.components.Note
import com.tekyerema.ui.components.PageTitle
import com.tekyerema.ui.components.SettingRow
import com.tekyerema.ui.theme.Spectrum
import com.tekyerema.ui.theme.Tk
import com.tekyerema.ui.theme.glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * D8 — Settings › Control › App rules: the user's own per-app rules, on screen (the same rules as by voice, [AppRuleVoice]).
 * Every installed app can be Normal, Guide only or Never touch. A stricter choice applies at once; one that gives
 * Tɛkyerɛma more room (lifting a rule) is asked first in a dialog — the screen's version of the spoken yes. Rules set in
 * the app's own policy are shown as "set by the app" and can't be removed; Tɛkyerɛma itself and the phone app (the
 * emergency call path) can't have a rule. Every change reaches the activity log (PrefsOperatorStore).
 *
 * TalkBack: each app row reads "WhatsApp, Never touch, button"; the chooser is a radio group ("Guide only, radio button,
 * selected"); what changed is announced (live region).
 */
@Composable
internal fun AppRulesPage(settings: Settings, onBack: () -> Unit) {
    val version by settings.changes.collectAsState()
    val tick by OperatorPolicy.changes.collectAsState()
    val context = LocalContext.current
    val rules = remember(version, tick) { OperatorPolicy.appRules.values.sortedBy { it.label.lowercase() } }
    val configDeny = remember(version) { PolicyConfig.configDenyApps() }
    val configGuide = remember(version) { PolicyConfig.configGuideOnlyApps() - configDeny }
    var apps by remember { mutableStateOf<List<AppResolver.AppInfo>?>(null) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            AppRuleVoice.learnPhonePath(context)   // this phone's dialer can't have a rule either
            try { AppResolver.launchable(context) } catch (_: Throwable) { emptyList() }
                .filter { AppRules.protectedKind(it.packageName) != AppRules.Protected.SELF }
                .sortedBy { it.label.lowercase() }
        }
    }
    fun labelOf(pkg: String) = apps?.firstOrNull { it.packageName == pkg }?.label
        ?: AppRuleVoice.labelFor(context, pkg, emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var choosing by remember { mutableStateOf<AppResolver.AppInfo?>(null) }
    var loosening by remember { mutableStateOf<Pair<AppResolver.AppInfo, AppRule?>?>(null) }
    var said by remember { mutableStateOf("") }

    /** A stricter rule at once; a looser one only after the dialog's yes. */
    fun choose(app: AppResolver.AppInfo, rule: AppRule?) {
        choosing = null
        val from = OperatorPolicy.appRule(app.packageName)
        when {
            from == rule -> {}
            AppRules.isLoosening(from, rule) -> loosening = app to rule
            OperatorPolicy.setAppRule(app.packageName, app.label, rule) -> said = AppRules.setLine(app.label, rule!!).substringBefore(" Say “")
            else -> said = AppRules.protectedLine(app.label, AppRules.protectedKind(app.packageName) ?: AppRules.Protected.PHONE)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        PageTitle("App rules", onBack)
        Note("Keep Nexus out of an app, or let it only guide you there. A new rule starts at once; lifting one asks you first. " +
            "Emergency help always works.")
        if (said.isNotEmpty()) Note(said, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        GlassGroup("Your rules") {
            if (rules.isEmpty()) FixedRow("No rules yet", "Nexus may use any app, and still asks before anything important.",
                null, Icons.Outlined.Apps, Spectrum.Lime, divider = false)
            else rules.forEachIndexed { i, r ->
                SettingRow(r.label, value = AppRules.name(r.rule), icon = ruleIcon(r.rule), iconTint = ruleTint(r.rule),
                    description = AppRules.meaning(r.rule), divider = i < rules.lastIndex) {
                    choosing = AppResolver.AppInfo(r.label, r.pkg)
                }
            }
        }
        if (configDeny.isNotEmpty() || configGuide.isNotEmpty()) {
            val fixed = configDeny.map { it to AppRule.NEVER } + configGuide.map { it to AppRule.GUIDE }
            GlassGroup("Set by the app") {
                fixed.forEachIndexed { i, (pkg, rule) ->
                    FixedRow(labelOf(pkg), "Set by the app, so it can't be changed here.", AppRules.name(rule), Icons.Outlined.Lock,
                        ruleTint(rule), divider = i < fixed.lastIndex)
                }
            }
        }
        GlassGroup("Add or change a rule") {
            SearchField(query) { query = it }
            val list = apps
            when {
                list == null -> FixedRow("Finding your apps…", null, null, Icons.Outlined.Apps, Spectrum.Teal, divider = false)
                else -> {
                    val q = query.trim()
                    val shown = if (q.isEmpty()) list else list.filter { it.label.contains(q, ignoreCase = true) }
                    if (shown.isEmpty()) FixedRow("No app called “$q”", null, null, Icons.Outlined.Apps, Spectrum.Teal, divider = false)
                    shown.forEachIndexed { i, app ->
                        val last = i == shown.lastIndex
                        val config = AppRules.configRule(app.packageName, configDeny, configGuide)
                        when {
                            AppRules.protectedKind(app.packageName) == AppRules.Protected.PHONE ->
                                FixedRow(app.label, "Always allowed: needed for emergency calls.", AppRules.name(null), Icons.Outlined.Call,
                                    Spectrum.Lime, divider = !last)
                            config == AppRule.NEVER ->
                                FixedRow(app.label, "Set by the app, so it can't be changed here.", AppRules.name(config), Icons.Outlined.Lock,
                                    ruleTint(config), divider = !last)
                            else -> {
                                val mine = rules.firstOrNull { it.pkg == app.packageName }?.rule
                                val now = AppRules.stricter(config, mine)
                                SettingRow(app.label, value = AppRules.name(now), icon = ruleIcon(now), iconTint = ruleTint(now),
                                    divider = !last) { choosing = app }
                            }
                        }
                    }
                }
            }
        }
        Note("You can also say “never touch WhatsApp”, “only guide me in Chrome”, “you can use WhatsApp again” or " +
            "“which apps are off limits”.")
    }

    choosing?.let { app ->
        val mine = OperatorPolicy.appRule(app.packageName)
        val config = AppRules.configRule(app.packageName, configDeny, configGuide)
        AlertDialog(
            onDismissRequest = { choosing = null },
            title = { Text(app.label, modifier = Modifier.semantics { heading() }) },
            text = {
                Column(Modifier.selectableGroup()) {
                    // A config "guide only" can't be undone here: Normal isn't offered below it.
                    if (config == null) ChoiceRow(AppRules.name(null), AppRules.meaning(null), selected = mine == null) { choose(app, null) }
                    ChoiceRow(AppRules.name(AppRule.GUIDE), AppRules.meaning(AppRule.GUIDE), selected = AppRules.stricter(config, mine) == AppRule.GUIDE) {
                        choose(app, if (config == AppRule.GUIDE) null else AppRule.GUIDE)
                    }
                    ChoiceRow(AppRules.name(AppRule.NEVER), AppRules.meaning(AppRule.NEVER), selected = mine == AppRule.NEVER, divider = false) {
                        choose(app, AppRule.NEVER)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = null }) { Text("Close") } }
        )
    }

    loosening?.let { (app, to) ->
        val from = OperatorPolicy.appRule(app.packageName) ?: AppRule.GUIDE
        // What will apply afterwards: the app's own rule may still hold.
        val after = AppRules.stricter(AppRules.configRule(app.packageName, configDeny, configGuide), to)
        AlertDialog(
            onDismissRequest = { loosening = null },
            title = { Text(if (after == null) "Let Nexus use ${app.label} again?" else "Let Nexus read and guide you in ${app.label}?",
                modifier = Modifier.semantics { heading() }) },
            text = { Text(AppRules.askLine(app.label, from, after).substringBefore(", yes or no?") + "?") },
            confirmButton = {
                TextButton(onClick = {
                    loosening = null
                    said = if (!OperatorPolicy.setAppRule(app.packageName, app.label, to))
                        AppRules.protectedLine(app.label, AppRules.protectedKind(app.packageName) ?: AppRules.Protected.PHONE)
                    else if (after == null) AppRules.clearedLine(app.label)
                    else AppRules.setLine(app.label, after).substringBefore(" Say “")
                }) { Text("Yes, allow it") }
            },
            dismissButton = {
                TextButton(onClick = { loosening = null; said = AppRules.keptLine(app.label, from) }) { Text("Keep the rule") }
            }
        )
    }
}

private fun ruleIcon(r: AppRule?): ImageVector = when (r) {
    AppRule.NEVER -> Icons.Outlined.Block
    AppRule.GUIDE -> Icons.Outlined.Visibility
    null -> Icons.Outlined.Apps
}

private fun ruleTint(r: AppRule?) = when (r) {
    AppRule.NEVER -> Spectrum.Coral
    AppRule.GUIDE -> Spectrum.Gold
    null -> Spectrum.Teal
}

/** A row that isn't a control — a rule the user can't change here. TalkBack reads it as one item, state included. */
@Composable
private fun FixedRow(label: String, description: String?, value: String?, icon: ImageVector, tint: androidx.compose.ui.graphics.Color,
                     divider: Boolean = true) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { if (value != null) stateDescription = value }) {
        Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, tint)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge, color = Tk.Text)
                if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = Tk.TextSecondary,
                    modifier = Modifier.padding(top = 2.dp))
            }
            if (value != null) {
                Spacer(Modifier.width(12.dp))
                Text(value, style = MaterialTheme.typography.bodyMedium, color = Tk.TextSecondary)
            }
        }
        if (divider) Hairline(inset = 74.dp)
    }
}

/** The app search box at the top of the list (filters as you type). */
@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true, interactionSource = interaction,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Tk.Text),
            cursorBrush = SolidColor(Tk.Cyan),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .glass(shape, elevation = 0.dp)
                .border(if (focused) 2.dp else 1.dp, if (focused) Tk.Focus else Tk.Hairline, shape)
                .semantics { contentDescription = "Search apps" }
                .padding(horizontal = 16.dp, vertical = 16.dp),
            decorationBox = { inner ->
                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text("Search apps", style = MaterialTheme.typography.bodyLarge, color = Tk.TextTertiary)
                    inner()
                }
            }
        )
    }
    Hairline(inset = 20.dp)
}

/** The Control page row's value: "2 apps" / "None" — the user's rules plus the app's own. */
internal fun appRulesSummary(): String =
    AppRules.summary(OperatorPolicy.appRules.size, (PolicyConfig.configDenyApps() + PolicyConfig.configGuideOnlyApps()).size)
