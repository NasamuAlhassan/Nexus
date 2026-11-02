package com.tekyerema.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VpnKey
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tekyerema.operator.Capabilities
import com.tekyerema.operator.Capability
import com.tekyerema.operator.DataControl
import com.tekyerema.operator.Disclosure
import com.tekyerema.operator.ExecutionMode
import com.tekyerema.operator.OperatorCommands
import com.tekyerema.operator.OperatorPolicy
import com.tekyerema.operator.ProviderKeys
import com.tekyerema.operator.WeakModelPolicy
import com.tekyerema.settings.Settings
import com.tekyerema.ui.components.BigButton
import com.tekyerema.ui.components.CardMargin
import com.tekyerema.ui.components.ChoiceRow
import com.tekyerema.ui.components.GlassGroup
import com.tekyerema.ui.components.Hairline
import com.tekyerema.ui.components.IconTile
import com.tekyerema.ui.components.Note
import com.tekyerema.ui.components.PageTitle
import com.tekyerema.ui.components.SettingRow
import com.tekyerema.ui.components.SwitchRow
import com.tekyerema.ui.theme.Spectrum
import com.tekyerema.ui.theme.Tk
import com.tekyerema.ui.theme.glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WS-C — Settings › Control: the user's say over how much Tɛkyerɛma does, which AI it uses, what it may spend and what it
 * keeps. Every control is live (OperatorPolicy is read at the next step), explained in one line, and also reachable by
 * voice. Sub-pages: provider & model, keys, limits & usage, what it keeps, and the activity log ([AuditPage]).
 */
@Composable
internal fun ControlPage(settings: Settings, onBack: () -> Unit, open: (Page) -> Unit) {
    val version by settings.changes.collectAsState()
    val usageTick by OperatorPolicy.changes.collectAsState()
    val op = OperatorPolicy
    val mode = remember(version) { op.executionMode }
    val cloud = remember(version) { op.cloudAllowed }
    val revoked = remember(version) { op.revokedCapabilities }
    val schedules = remember(version) { op.schedulesEnabled }
    val alerts = remember(version) { op.messageAlertsEnabled }
    val routines = remember(version) { op.routinesEnabled }
    val remote = remember(version) { op.remoteControlEnabled }
    val providers = remember(version) { op.providers }
    val usage = remember(usageTick) { op.usageToday() }
    val keys = remember(version) { listOf("groq", "gemini").count { settings.userKey(it).isNotBlank() } }
    var logCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(version) { logCount = withContext(Dispatchers.IO) { com.tekyerema.audit.AuditLog.count() } }

    @Composable
    fun capabilityRow(c: Capability, line: String, icon: ImageVector, tint: androidx.compose.ui.graphics.Color, divider: Boolean = true) =
        SwitchRow(Capabilities.label(c), line, c !in revoked, divider = divider, icon = icon, iconTint = tint) { on ->
            if (on) op.restore(c) else op.revoke(c)
        }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        PageTitle("Control", onBack)
        Note("You decide how much Nexus does on its own, which AI it uses, what it may spend and what it keeps. " +
            "Changes apply straight away.")
        GlassGroup("How Nexus works") {
            Column(Modifier.selectableGroup()) {
                ChoiceRow("Normal", "Acts on its own, and always asks before calls, messages, money and other important steps.",
                    mode == ExecutionMode.STANDARD) { op.executionMode = ExecutionMode.STANDARD }
                ChoiceRow("Ask me every step", "Asks before every tap or change, not only the important ones.",
                    mode == ExecutionMode.ASK_EVERY_STEP) { op.executionMode = ExecutionMode.ASK_EVERY_STEP }
                ChoiceRow("Guide only", "Never touches your phone. Tells you what to do so you can do it yourself.",
                    mode == ExecutionMode.GUIDE_ONLY, divider = false) { op.executionMode = ExecutionMode.GUIDE_ONLY }
            }
        }
        GlassGroup("What it may do for you") {
            capabilityRow(Capability.MESSAGES, "Texts, WhatsApp messages and replies.", Icons.Outlined.ChatBubbleOutline, Spectrum.Teal)
            capabilityRow(Capability.CALLS, "Placing calls. Emergency help always works.", Icons.Outlined.Call, Spectrum.Lime)
            capabilityRow(Capability.MONEY, "Mobile Money and payments.", Icons.Outlined.Payments, Spectrum.Gold)
            // Money safety round 2 (money/MoneyLimits): the most a send and a day may be; raising waits 30 minutes.
            SettingRow("Mobile Money limits", value = remember(version) { moneyLimitsSummary() }, icon = Icons.Outlined.Payments,
                iconTint = Spectrum.Gold, description = "The most you can send at once, and in a day.") { open(Page.MONEY_LIMITS) }
            capabilityRow(Capability.SETTINGS, "Wi-Fi, Bluetooth, brightness, sound mode and other phone settings.", Icons.Outlined.Tune, Spectrum.Aqua)
            capabilityRow(Capability.WEB, "Searching the web for answers.", Icons.Outlined.Public, Spectrum.Green)
            // D8: per-app rules (AppRulesPage) — never touch an app, or only guide there.
            SettingRow("App rules", value = remember(version) { appRulesSummary() }, icon = Icons.Outlined.Apps,
                iconTint = Spectrum.Coral, description = "Apps it must stay out of, or only guide you in.", divider = false) { open(Page.APP_RULES) }
        }
        Note("Whatever you choose here, Nexus always asks before calls, messages and money, and never reads or types a PIN or password.")
        GlassGroup("AI and privacy") {
            SwitchRow("Use the cloud",
                if (cloud) "On: bigger tasks use the online AI below." else "Off: nothing leaves your phone. Simple commands still work; " +
                    "web search, picture descriptions and bigger tasks don't.",
                cloud, icon = if (cloud) Icons.Outlined.Cloud else Icons.Outlined.CloudOff, iconTint = Spectrum.Teal) { op.cloudAllowed = it }
            SettingRow("AI provider and model", value = providers.joinToString(" → ") { Disclosure.name(it) }.ifEmpty { "None" },
                icon = Icons.Outlined.SmartToy, iconTint = Spectrum.Green,
                description = "Which online AI is used, in what order, and what each one does with your data.") { open(Page.PROVIDERS) }
            SettingRow("Your API keys", value = if (keys == 0) "Built-in" else "$keys of yours", icon = Icons.Outlined.VpnKey,
                iconTint = Spectrum.Gold, description = "Use your own account, or your own AI server.") { open(Page.KEYS) }
            SettingRow("Limits and usage", value = "${usage.first} today", icon = Icons.Outlined.DataUsage, iconTint = Spectrum.Lime,
                description = "How much online AI was used today, and the most it may use.", divider = false) { open(Page.USAGE) }
        }
        GlassGroup("Runs by itself") {
            SwitchRow("Schedules", "Reminders and read-outs at times you set.", schedules,
                icon = Icons.Outlined.Schedule, iconTint = Spectrum.Gold) { op.schedulesEnabled = it }
            // Phone care: this switch also silences the money and missed-call alerts (phonecare/PhoneAlerts).
            SwitchRow("Message alerts", "Tells you when someone you chose sends a message, and your money and missed-call alerts.", alerts,
                icon = Icons.Outlined.NotificationsActive, iconTint = Spectrum.Coral) { op.messageAlertsEnabled = it }
            SwitchRow("Routines", "Your own phrases, like “good morning”, that run several steps.", routines,
                icon = Icons.Outlined.Repeat, iconTint = Spectrum.Green) { op.routinesEnabled = it }
            SwitchRow("Computer connection", "Lets a computer you trust, over a USB cable, ask Nexus to do tasks. Anything " +
                "important still needs your OK on this phone.", remote, divider = false,
                icon = Icons.Outlined.Computer, iconTint = Spectrum.Aqua) { op.remoteControlEnabled = it }
        }
        GlassGroup("Your data") {
            SettingRow("Activity log", value = logCount?.let { "$it" }.orEmpty(), icon = Icons.Outlined.History, iconTint = Spectrum.Teal,
                description = "What Nexus proposed, what you allowed and what happened. Any change to it shows.") { open(Page.AUDIT) }
            SettingRow("What Nexus keeps", icon = Icons.Outlined.Inventory2, iconTint = Spectrum.Lime, divider = false,
                description = "See it, choose how long it's kept, export it or delete it.") { open(Page.DATA) }
        }
        Note("You can also say “offline mode”, “ask me before every step”, “normal mode”, “never touch WhatsApp”, " +
            "“what do you keep about me”, “how much have you used today”, “export my history” or “delete everything”.")
    }
}

// ------------------------------------------------------------------------------------------ provider & model

@Composable
internal fun ProvidersPage(settings: Settings, onBack: () -> Unit) {
    val version by settings.changes.collectAsState()
    val op = OperatorPolicy
    val order = remember(version) { op.providers }
    val weak = remember(version) { op.weakModelPolicy }
    val model = remember(version) { settings.groqModel }
    val ownServer = remember(version) { settings.usesOwnServer }
    val cloud = remember(version) { op.cloudAllowed }
    fun choose(p: List<String>) { op.providers = p; settings.allowGeminiBackup = "gemini" in p }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        PageTitle("AI provider and model", onBack)
        Note((if (!cloud) "The cloud is off, so none of these are used right now. " else "") +
            "When a task needs the online AI, Nexus sends what you asked and what's on the screen — never a PIN or password.")
        GlassGroup("Which AI, in what order") {
            Column(Modifier.selectableGroup()) {
                ChoiceRow("Groq, then Gemini", "Gemini is used when Groq can't answer. A photo goes to both (a question at once, a " +
                    "description when Groq is slow).", order == listOf("groq", "gemini")) { choose(listOf("groq", "gemini")) }
                ChoiceRow("Groq only", "Nothing goes to Google — no Gemini for tasks, pictures or searches. Photos go to Groq.",
                    order == listOf("groq")) { choose(listOf("groq")) }
                ChoiceRow("Gemini, then Groq", "Google's Gemini plans first. A photo goes to both.", order == listOf("gemini", "groq")) { choose(listOf("gemini", "groq")) }
                ChoiceRow("Gemini only", "Only Google's Gemini plans your tasks and sees your photos.", order == listOf("gemini"), divider = false) { choose(listOf("gemini")) }
            }
        }
        GlassGroup("What they do with your data") {
            DisclosureRow(Disclosure.GROQ)
            DisclosureRow(Disclosure.GEMINI, divider = false)
        }
        if (ownServer) {
            Note("You're using your own server (${settings.groqModel}). Change it in Your API keys.")
        } else GlassGroup("Groq model") {
            Column(Modifier.selectableGroup()) {
                ChoiceRow("gpt-oss-120b", "The most careful. Recommended.", model == Settings.DEFAULT_GROQ_MODEL) {
                    if (model != Settings.DEFAULT_GROQ_MODEL) { settings.groqModel = Settings.DEFAULT_GROQ_MODEL; noteSetting("set the Groq model to gpt-oss-120b") }
                }
                ChoiceRow("gpt-oss-20b", "Faster, but less careful and easier to mislead.", model == SMALL_MODEL, divider = false) {
                    if (model != SMALL_MODEL) { settings.groqModel = SMALL_MODEL; noteSetting("set the Groq model to gpt-oss-20b") }
                }
            }
        }
        GlassGroup("When the main AI is busy") {
            Column(Modifier.selectableGroup()) {
                ChoiceRow("Ask me first", "Questions and look-ups may use a smaller model (I'll say so); it asks before that model changes anything.", weak == WeakModelPolicy.ASK) { op.weakModelPolicy = WeakModelPolicy.ASK }
                ChoiceRow("Never use a smaller model", "Waits for the main AI instead.", weak == WeakModelPolicy.NEVER) { op.weakModelPolicy = WeakModelPolicy.NEVER }
                ChoiceRow("Use the smaller model", "Carries on without asking.", weak == WeakModelPolicy.ALLOW, divider = false) { op.weakModelPolicy = WeakModelPolicy.ALLOW }
            }
        }
        Note("You can also ask “where does my data go?”.")
    }
}

private const val SMALL_MODEL = "openai/gpt-oss-20b"

/** Changes that live in Settings rather than OperatorPolicy (model, keys, server) go to the activity log too — the fact
 *  only, never a key. Off the main thread. */
private fun noteSetting(words: String) {
    kotlin.concurrent.thread(name = "audit-setting") {
        com.tekyerema.audit.AuditLog.record("user", com.tekyerema.audit.AuditLog.SETTING, words, System.currentTimeMillis())
    }
}

@Composable
private fun DisclosureRow(s: Disclosure.Statement, divider: Boolean = true) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Column(Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text(s.name, style = MaterialTheme.typography.bodyLarge, color = Tk.Text)
            Text(s.text, style = MaterialTheme.typography.bodyMedium, color = Tk.TextSecondary, modifier = Modifier.padding(top = 4.dp))
            Text("Source: ${s.source}", style = MaterialTheme.typography.bodySmall, color = Tk.TextTertiary, modifier = Modifier.padding(top = 4.dp))
        }
        if (divider) Hairline(inset = 20.dp)
    }
}

// ------------------------------------------------------------------------------------------ keys and own server

@Composable
internal fun KeysPage(settings: Settings, onBack: () -> Unit) {
    val version by settings.changes.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        PageTitle("Your API keys", onBack)
        Note("Use your own account with an AI provider. Keys are encrypted on this phone, shown only by their last four " +
            "characters, never written to any log, and sent only to the provider they belong to.")
        KeyCard(settings, "groq", "Groq", version)
        KeyCard(settings, "gemini", "Google Gemini", version)
        OwnServerCard(settings, version)
    }
}

@Composable
private fun KeyCard(settings: Settings, provider: String, name: String, version: Long) {
    val mine = remember(version) { settings.userKey(provider) }
    val builtIn = remember(version) { settings.hasBuiltInKey(provider) }
    var text by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val status = when {
        mine.isNotBlank() -> "Your key ${ProviderKeys.mask(mine)}"
        builtIn -> "Using the built-in key"
        else -> "No key — $name isn't used"
    }
    GlassGroup(name) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text(status, style = MaterialTheme.typography.bodyLarge, color = Tk.Text)
            Spacer(Modifier.height(10.dp))
            SecretField(value = text, onChange = { text = it; message = null }, label = "$name API key", secret = true)
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tk.TextSecondary,
                modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }) }
            Spacer(Modifier.height(12.dp))
            BigButton("Save key", onClick = {
                val problem = ProviderKeys.keyProblem(text)
                if (problem != null) message = problem
                else {
                    settings.setUserKey(provider, text.trim()); text = ""; message = "Saved. $name now uses your key."
                    noteSetting("added your own $name key")
                }
            })
            if (mine.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                BigButton("Remove my key", filled = false, onClick = {
                    settings.setUserKey(provider, ""); text = ""
                    message = if (builtIn) "Removed. $name uses the built-in key again." else "Removed."
                    noteSetting("removed your own $name key")
                })
            }
        }
    }
}

@Composable
private fun OwnServerCard(settings: Settings, version: Long) {
    val active = remember(version) { settings.usesOwnServer }
    var url by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    GlassGroup("Your own AI server (advanced)") {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text(if (active) "In use: ${settings.groqBaseUrl} · ${settings.groqModel}" else "Not in use — the planner uses Groq.",
                style = MaterialTheme.typography.bodyLarge, color = Tk.Text)
            Text("Any OpenAI-compatible server, for example Ollama on your laptop. Only the key you saved for Groq above is " +
                "sent to it — never a built-in one. If your server needs no key, save any word as the key.",
                style = MaterialTheme.typography.bodyMedium, color = Tk.TextSecondary, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(10.dp))
            SecretField(value = url, onChange = { url = it; message = null }, label = "Server address (https://…/v1)", secret = false,
                keyboardType = KeyboardType.Uri)
            Spacer(Modifier.height(8.dp))
            SecretField(value = model, onChange = { model = it; message = null }, label = "Model name", secret = false)
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tk.TextSecondary,
                modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }) }
            Spacer(Modifier.height(12.dp))
            BigButton("Use this server", onClick = {
                val problem = ProviderKeys.serverProblem(url) ?: if (model.isBlank()) "Type the model name too." else null
                if (problem != null) message = problem
                else {
                    settings.setOwnServer(url.trim(), model.trim()); message = "Saved. The planner now uses your server."
                    noteSetting("switched the planner to your own server")
                }
            })
            if (active) {
                Spacer(Modifier.height(8.dp))
                BigButton("Go back to Groq", filled = false, onClick = {
                    settings.setOwnServer(null, null); message = "Back to Groq."
                    noteSetting("switched the planner back to Groq")
                })
            }
        }
    }
}

/** A labelled one-line text field on glass; [secret] hides what is typed (keys are never shown). */
@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, label: String, secret: Boolean,
                        keyboardType: KeyboardType = KeyboardType.Password) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(18.dp)
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Tk.TextSecondary, modifier = Modifier.padding(bottom = 4.dp))
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true, interactionSource = interaction,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Tk.Text),
            cursorBrush = SolidColor(Tk.Cyan),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboardType, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .glass(shape, elevation = 0.dp)
                .border(if (focused) 2.dp else 1.dp, if (focused) Tk.Focus else Tk.Hairline, shape)
                .semantics { contentDescription = label }
                .padding(horizontal = 16.dp, vertical = 16.dp)
        )
    }
}

// ------------------------------------------------------------------------------------------ limits and usage

@Composable
internal fun UsagePage(settings: Settings, onBack: () -> Unit) {
    val version by settings.changes.collectAsState()
    val usageTick by OperatorPolicy.changes.collectAsState()
    val op = OperatorPolicy
    val byProvider = remember(usageTick, version) { op.usageByProvider() }
    val spent = remember(usageTick, version) { op.budgetExhausted() }
    val calls = remember(version) { op.dailyCloudCallCap }
    val tokens = remember(version) { op.dailyTokenCap }
    val steps = remember(version) { op.stepLimit(com.tekyerema.core.config.AppConfig.int("agent.limits.maxSteps", 30)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        PageTitle("Limits and usage", onBack)
        Note(OperatorCommands.usageLine(byProvider, calls, tokens))
        GlassGroup("Today") {
            if (byProvider.isEmpty()) InfoRow("Online AI", "Not used today", divider = false)
            else byProvider.entries.forEachIndexed { i, (p, u) ->
                InfoRow(Disclosure.name(p), "${u.calls} call${if (u.calls == 1) "" else "s"} · ${OperatorCommands.spokenTokens(u.tokens)} tokens",
                    divider = i < byProvider.size - 1)
            }
        }
        if (spent) Note("Today's limit is reached, so Nexus is working on this phone only until midnight.",
            Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        GlassGroup("Most online AI calls a day") {
            Column(Modifier.selectableGroup()) {
                listOf(0, 50, 150, 400).forEachIndexed { i, n ->
                    ChoiceRow(if (n == 0) "No limit" else "$n calls", selected = calls == n, divider = i < 3) { op.dailyCloudCallCap = n }
                }
            }
        }
        GlassGroup("Most tokens a day") {
            Column(Modifier.selectableGroup()) {
                listOf(0, 100_000, 300_000, 1_000_000).forEachIndexed { i, n ->
                    ChoiceRow(if (n == 0) "No limit" else OperatorCommands.spokenTokens(n.toLong()), selected = tokens == n, divider = i < 3) {
                        op.dailyTokenCap = n
                    }
                }
            }
        }
        Note("At a limit, Nexus works on this phone only until midnight; simple commands keep working. A token is about " +
            "three-quarters of a word; one step of a task uses a few thousand.")
        GlassGroup("Most steps for one task") {
            Column(Modifier.selectableGroup()) {
                listOf(15, 30, 45, 60).forEachIndexed { i, n ->
                    ChoiceRow("$n steps", if (n == 30) "The usual." else null, selected = steps == n, divider = i < 3) { op.maxStepsPerTask = n }
                }
            }
        }
    }
}

/** A label and a value that isn't a control (TalkBack reads them together). */
@Composable
private fun InfoRow(label: String, value: String, divider: Boolean = true) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = Tk.Text, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium, color = Tk.TextSecondary)
        }
        if (divider) Hairline(inset = 20.dp)
    }
}

// ------------------------------------------------------------------------------------------ what it keeps

@Composable
internal fun DataPage(settings: Settings, onBack: () -> Unit, openAudit: () -> Unit) {
    val version by settings.changes.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val op = OperatorPolicy
    var refresh by remember { mutableStateOf(0) }
    var items by remember { mutableStateOf<List<DataControl.Item>?>(null) }
    LaunchedEffect(version, refresh) { items = withContext(Dispatchers.IO) { DataControl.items(context) } }
    var includeText by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val configured = com.tekyerema.core.config.AppConfig.int("privacy.retention.historyDays", 90)
    val keep = remember(version) { com.tekyerema.core.Privacy.historyDays() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        PageTitle("What Nexus keeps", onBack)
        Note("All of it stays on this phone, encrypted. Nothing here is sent anywhere unless you export it. Screenshots are never saved.")
        GlassGroup("Kept now") {
            val list = items
            if (list == null) InfoRow("Checking…", "", divider = false)
            else list.forEachIndexed { i, it ->
                InfoRow(it.label, "${it.count}${it.keptFor?.let { k -> " · $k" } ?: ""}", divider = i < list.size - 1)
            }
        }
        GlassGroup("Keep history and the activity log for") {
            Column(Modifier.selectableGroup()) {
                val options = listOf(7, 30, configured).filter { it in 1..configured }.distinct()
                options.forEachIndexed { i, d ->
                    ChoiceRow("$d days", if (d == configured) "The usual." else null, selected = keep == d, divider = i < options.size - 1) {
                        op.historyRetentionDays = if (d == configured) 0 else d
                    }
                }
            }
        }
        GlassGroup("A copy for you") {
            SwitchRow("Include message text", "Off: your own words and messages are left out — only their length is shown.", includeText) { includeText = it }
            SettingRow("Export history and activity log", icon = Icons.Outlined.Share, iconTint = Spectrum.Teal,
                description = "Makes one file and opens the share menu, so you choose where it goes.") {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { com.tekyerema.audit.AuditExport.share(context, includeText) }
                    message = if (ok) null else "Sorry, I couldn't make the file."
                }
            }
            SettingRow("Activity log", icon = Icons.Outlined.History, iconTint = Spectrum.Green, divider = false,
                description = "Read it here, step by step.") { openAudit() }
        }
        val improve = remember(version) { settings.trajectoryConsent }
        val taskLog = remember(version) { settings.fieldLog }
        GlassGroup("Privacy") {
            SwitchRow("Help improve Nexus", "Keep a copy of your tasks on this phone, encrypted, so they can " +
                "be used to improve me. Nothing is sent anywhere. Turning this off deletes them.", improve) {
                settings.trajectoryConsent = it
                if (!it) com.tekyerema.core.telemetry.Trajectories.clear(context)
            }
            SwitchRow("Keep a task log", "A private list of what you asked and how it went. " +
                "Encrypted, on this phone. Turning this off deletes it.", taskLog, divider = false) {
                settings.fieldLog = it
                if (!it) com.tekyerema.core.telemetry.FieldLog.clear(context)
            }
        }
        GlassGroup {
            SettingRow("Delete everything", icon = Icons.Outlined.DeleteForever, iconTint = Spectrum.Coral, divider = false,
                description = "Erases everything listed above. Your settings, keys and emergency contact stay.") { confirmDelete = true }
        }
        message?.let { Note(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        Note("Or say “what do you keep about me”, “export my history”, “forget everything” or “delete everything”.")
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete everything?", modifier = Modifier.semantics { heading() }) },
        text = { Text(DataControl.DELETE_PROMPT.substringBefore(" Say “")) },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                scope.launch {
                    val failed = withContext(Dispatchers.IO) { DataControl.deleteEverything(context) }
                    message = if (failed.isEmpty()) DataControl.DELETED else "Deleted, except: " + DataControl.joinAnd(failed) + "."
                    refresh++
                }
            }) { Text("Delete everything", color = Tk.Danger) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep it") } }
    )
}
