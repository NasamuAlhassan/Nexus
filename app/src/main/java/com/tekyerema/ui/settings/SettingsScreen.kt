package com.tekyerema.ui.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.tekyerema.settings.Settings
import com.tekyerema.ui.Perms
import com.tekyerema.ui.components.ChoiceRow
import com.tekyerema.ui.components.Hairline
import com.tekyerema.ui.components.Note
import com.tekyerema.ui.components.Overline
import com.tekyerema.ui.components.PageMargin
import com.tekyerema.ui.components.PageTitle
import com.tekyerema.ui.components.SettingRow
import com.tekyerema.ui.components.SwitchRow
import com.tekyerema.ui.theme.Tk

private enum class Page { ROOT, LANGUAGE, VOICE, PERMISSIONS, ACCESSIBILITY, PRIVACY, HELP, KEYBOARD }

/** Six things: Language, Voice, Permissions, Accessibility, Privacy (J-15 / H-12 / Q-07 / Q-08), Help. */
@Composable
fun SettingsScreen(settings: Settings, modifier: Modifier = Modifier) {
    var page by rememberSaveable { mutableStateOf(Page.ROOT) }
    val version by settings.changes.collectAsState()
    BackHandler(enabled = page != Page.ROOT) { page = Page.ROOT }
    val context = LocalContext.current

    when (page) {
        Page.ROOT -> Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            PageTitle("Settings")
            Hairline(inset = PageMargin)
            SettingRow("Language", value = remember(version) { languageLabel(settings.languageMode) }) { page = Page.LANGUAGE }
            SettingRow("Voice", value = remember(version) { rateLabel(settings.speechRate) }) { page = Page.VOICE }
            // G-05: the Twi keyboard's setup and options live in ime/ (TwiKeyboardPage).
            SettingRow("Twi keyboard", value = com.tekyerema.ime.rememberTwiKeyboardStatusLabel()) { page = Page.KEYBOARD }
            SettingRow("Permissions", value = permissionsSummary(context)) { page = Page.PERMISSIONS }
            SettingRow("Accessibility") { page = Page.ACCESSIBILITY }
            SettingRow("Privacy") { page = Page.PRIVACY }
            SettingRow("Help") { page = Page.HELP }
        }
        Page.LANGUAGE -> LanguagePage(settings) { page = Page.ROOT }
        Page.VOICE -> VoicePage(settings) { page = Page.ROOT }
        Page.PERMISSIONS -> PermissionsPage { page = Page.ROOT }
        Page.ACCESSIBILITY -> AccessibilityPage(settings) { page = Page.ROOT }
        Page.PRIVACY -> PrivacyPage(settings) { page = Page.ROOT }
        Page.HELP -> HelpPage { page = Page.ROOT }
        Page.KEYBOARD -> com.tekyerema.ime.TwiKeyboardPage { page = Page.ROOT }
    }
}

private fun languageLabel(mode: String) = when (mode) {
    Settings.LANG_MODE_ENGLISH -> "English"
    Settings.LANG_MODE_TWI -> "Twi"
    else -> "English + Twi"
}

private fun rateLabel(rate: Int) = when (rate) { 0 -> "Slow"; 2 -> "Fast"; else -> "Normal" }

@Composable
private fun permissionsSummary(context: android.content.Context): String {
    val tick by rememberResumeTick()
    return remember(tick) {
        val on = listOf(Perms.mic(context), Perms.screenControl(context), Perms.calls(context), Perms.notifications(context), Perms.overlay(context)).count { it }
        if (on == 5) "All on" else "$on of 5 on"
    }
}

/** Re-reads system-level permission state whenever the user comes back from Settings. */
@Composable
private fun rememberResumeTick(): androidx.compose.runtime.State<Int> {
    val owner = LocalLifecycleOwner.current
    val tick = remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick.intValue++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return tick
}

// -------------------------------------------------------------------------------------- pages

@Composable
private fun LanguagePage(settings: Settings, onBack: () -> Unit) {
    val version by settings.changes.collectAsState()
    val mode = remember(version) { settings.languageMode }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("Language", onBack)
        Note("Choose how you want to speak to Tɛkyerɛma.")
        Spacer(Modifier.height(10.dp))
        Hairline(inset = PageMargin)
        Column(Modifier.selectableGroup()) {
            ChoiceRow("English", "Ghanaian English only.", mode == Settings.LANG_MODE_ENGLISH) { settings.languageMode = Settings.LANG_MODE_ENGLISH }
            ChoiceRow("Twi", "Twi only. Replies in Twi where a Twi voice is available.", mode == Settings.LANG_MODE_TWI) { settings.languageMode = Settings.LANG_MODE_TWI }
            ChoiceRow("English + Twi", "Mix both freely. Tɛkyerɛma follows the language you use.", mode == Settings.LANG_MODE_BOTH) { settings.languageMode = Settings.LANG_MODE_BOTH }
        }
    }
}

@Composable
private fun VoicePage(settings: Settings, onBack: () -> Unit) {
    val version by settings.changes.collectAsState()
    val speak = remember(version) { settings.speakAloud }
    val rate = remember(version) { settings.speechRate }
    val pitch = remember(version) { settings.speechPitch }
    val detail = remember(version) { settings.verbosity }
    val patience = remember(version) { settings.vadStrategy }
    val profile = remember(version) { settings.profile }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("Voice", onBack)
        Hairline(inset = PageMargin)
        SwitchRow("Read replies aloud", "Turn off if you prefer TalkBack to read them.", speak) { settings.speakAloud = it }
        // K-11: one choice sets how much is said and how long Tɛkyerɛma waits for you.
        Overline("Experience")
        Hairline(inset = PageMargin)
        Column(Modifier.selectableGroup()) {
            ChoiceRow("New to Tɛkyerɛma", "Fuller explanations, and more time to finish speaking.", profile == "beginner") { settings.profile = "beginner" }
            ChoiceRow("Experienced", "Short replies, and it answers as soon as you stop.", profile == "expert") { settings.profile = "expert" }
            ChoiceRow("Custom", "Set detail and patience below.", profile != "beginner" && profile != "expert") { settings.profile = "custom" }
        }
        Overline("Speaking speed")
        Hairline(inset = PageMargin)
        Column(Modifier.selectableGroup()) {
            ChoiceRow("Slow", selected = rate == 0) { settings.speechRate = 0 }
            ChoiceRow("Normal", selected = rate == 1) { settings.speechRate = 1 }
            ChoiceRow("Fast", selected = rate == 2) { settings.speechRate = 2 }
        }
        Overline("Voice pitch")
        Hairline(inset = PageMargin)
        Column(Modifier.selectableGroup()) {
            ChoiceRow("Lower", selected = pitch == 0) { settings.speechPitch = 0 }
            ChoiceRow("Normal", selected = pitch == 1) { settings.speechPitch = 1 }
            ChoiceRow("Higher", selected = pitch == 2) { settings.speechPitch = 2 }
        }
        Overline("How much it says")
        Hairline(inset = PageMargin)
        Column(Modifier.selectableGroup()) {
            ChoiceRow("Brief", "Just the result, and a soft tick while it works.", detail == "brief") { settings.verbosity = "brief"; settings.profile = "custom" }
            ChoiceRow("Normal", selected = detail == "normal") { settings.verbosity = "normal"; settings.profile = "custom" }
            ChoiceRow("Detailed", "Explains more and tells you what it's doing.", detail == "detailed") { settings.verbosity = "detailed"; settings.profile = "custom" }
        }
        Overline("Waiting for you to finish")
        Hairline(inset = PageMargin)
        Column(Modifier.selectableGroup()) {
            ChoiceRow("Quick", "Answers as soon as you pause.", patience == "quick") { settings.vadStrategy = "quick"; settings.profile = "custom" }
            ChoiceRow("Normal", selected = patience == "normal") { settings.vadStrategy = "normal"; settings.profile = "custom" }
            ChoiceRow("Patient", "Waits through longer pauses.", patience == "patient") { settings.vadStrategy = "patient"; settings.profile = "custom" }
        }
        Note("Tɛkyerɛma speaks with your phone's voice. You can also say “speak slower”, “keep it short” or “be patient with me”.")
    }
}

@Composable
private fun PermissionsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val tick by rememberResumeTick()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    val mic = remember(tick) { Perms.mic(context) }
    val screen = remember(tick) { Perms.screenControl(context) }
    val calls = remember(tick) { Perms.calls(context) }
    val notif = remember(tick) { Perms.notifications(context) }
    val overlay = remember(tick) { Perms.overlay(context) }
    fun v(on: Boolean) = if (on) "On" else "Off"

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("Permissions", onBack)
        Note("Tap one that is off to turn it on.")
        Spacer(Modifier.height(10.dp))
        Hairline(inset = PageMargin)
        SettingRow("Microphone", v(mic)) { if (!mic) launcher.launch(arrayOf(android.Manifest.permission.RECORD_AUDIO)) }
        SettingRow("Screen control", v(screen)) { if (!screen) Perms.openAccessibilitySettings(context) }
        SettingRow("Calls and contacts", v(calls)) {
            if (!calls) launcher.launch(arrayOf(android.Manifest.permission.CALL_PHONE, android.Manifest.permission.READ_CONTACTS))
        }
        SettingRow("Read messages", v(notif)) { if (!notif) Perms.openNotificationAccess(context) }
        SettingRow("Show over other apps", v(overlay)) { if (!overlay) Perms.openOverlaySettings(context) }
        // W4: the phone-action permissions, each requested on its own so one refusal doesn't block the rest.
        val texts = remember(tick) { Perms.granted(context, android.Manifest.permission.SEND_SMS) && Perms.granted(context, android.Manifest.permission.READ_SMS) }
        val callLog = remember(tick) { Perms.granted(context, android.Manifest.permission.READ_CALL_LOG) && Perms.granted(context, android.Manifest.permission.ANSWER_PHONE_CALLS) }
        val location = remember(tick) { Perms.granted(context, android.Manifest.permission.ACCESS_FINE_LOCATION) }
        val calendar = remember(tick) { Perms.granted(context, android.Manifest.permission.READ_CALENDAR) }
        SettingRow("Text messages", v(texts)) {
            if (!texts) launcher.launch(arrayOf(android.Manifest.permission.SEND_SMS, android.Manifest.permission.READ_SMS))
        }
        SettingRow("Call history and answering", v(callLog)) {
            if (!callLog) launcher.launch(arrayOf(android.Manifest.permission.READ_CALL_LOG, android.Manifest.permission.ANSWER_PHONE_CALLS,
                android.Manifest.permission.READ_PHONE_STATE))
        }
        SettingRow("Location", v(location)) {
            if (!location) launcher.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        SettingRow("Calendar", v(calendar)) { if (!calendar) launcher.launch(arrayOf(android.Manifest.permission.READ_CALENDAR)) }
        Note(
            "Screen control lets Tɛkyerɛma open apps and tap for you. Read messages lets it read and reply to your " +
                "notifications. Show over other apps lets it ask for your OK without leaving what you were doing."
        )
    }
}

@Composable
private fun AccessibilityPage(settings: Settings, onBack: () -> Unit) {
    val version by settings.changes.collectAsState()
    val haptics = remember(version) { settings.haptics }
    val sounds = remember(version) { settings.sounds }
    val reduce = remember(version) { settings.reduceMotion }
    val floating = remember(version) { settings.floatingTrigger }
    val wake = remember(version) { settings.wakeWordEnabled }
    val gemini = remember(version) { settings.allowGeminiBackup }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("Accessibility", onBack)
        Hairline(inset = PageMargin)
        SwitchRow("Sounds", "A short tone when listening starts, ends, and when a task is done.", sounds) { settings.sounds = it }
        SwitchRow("Vibration", "A different tap pattern for listening, done, needs your OK, and errors.", haptics) { settings.haptics = it }
        SwitchRow("Reduce motion", "Keeps the visual still.", reduce) { settings.reduceMotion = it }
        SwitchRow("Floating Talk button", "A button over other apps that starts Tɛkyerɛma. Needs screen control.", floating) { settings.floatingTrigger = it }
        // A-07: the hands-free wake word, switchable by the user (it was only reachable over ADB).
        SwitchRow("“Hey Tɛkyerɛma”", "Listen for the wake phrase so you can talk without touching the phone.", wake) { settings.wakeWordEnabled = it }
        // J-18: keep requests off a provider whose free tier may use them to improve its products.
        SwitchRow("Backup AI (Google Gemini)", "Used only when the main AI is busy. Google may use these requests to improve its products.", gemini) { settings.allowGeminiBackup = it }
        // N-11: Samsung puts unused apps to sleep, which silently stops the wake word and background tasks.
        SettingRow("Keep Tɛkyerɛma running", if (Perms.batteryUnrestricted(context)) "On" else "Off") { Perms.requestBatteryUnrestricted(context) }
        Note("Text size and TalkBack follow your phone's accessibility settings. On Samsung phones, also add Tɛkyerɛma to " +
            "Settings › Battery › Background usage limits › Never sleeping apps.")
    }
}

/** J-15 / H-12 / Q-07 / Q-08: what Tɛkyerɛma keeps, the two opt-ins, and deleting it. */
@Composable
private fun PrivacyPage(settings: Settings, onBack: () -> Unit) {
    val version by settings.changes.collectAsState()
    val improve = remember(version) { settings.trajectoryConsent }
    val taskLog = remember(version) { settings.fieldLog }
    val context = LocalContext.current
    var historyCleared by remember { mutableStateOf(false) }
    var memoryCleared by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("Privacy", onBack)
        Hairline(inset = PageMargin)
        SwitchRow("Help improve Tɛkyerɛma", "Keep a copy of your tasks — what was on the screen and what I did — on this phone, " +
            "encrypted, so they can be used to improve me. Nothing is sent anywhere. Turning this off deletes them.", improve) {
            settings.trajectoryConsent = it
            if (!it) com.tekyerema.core.telemetry.Trajectories.clear(context)
        }
        SwitchRow("Keep a task log", "A private list of what you asked and how it went, to check how well I work. " +
            "Encrypted, on this phone. Turning this off deletes it.", taskLog) {
            settings.fieldLog = it
            if (!it) com.tekyerema.core.telemetry.FieldLog.clear(context)
        }
        SettingRow("Delete history", if (historyCleared) "Deleted" else "") {
            com.tekyerema.history.HistoryStore.clear()
            com.tekyerema.audit.AuditLog.clear()
            com.tekyerema.core.telemetry.Telemetry.clear()
            com.tekyerema.agent.AgentEngine.clearTraces()
            historyCleared = true
        }
        SettingRow("Delete memory", if (memoryCleared) "Deleted" else "") {
            com.tekyerema.agent.AgentEngine.resetConversation()
            com.tekyerema.agent.References.clear()
            com.tekyerema.notifications.MessageIndex.clear()
            com.tekyerema.core.telemetry.Trajectories.clear(context)
            memoryCleared = true
        }
        Note("History is kept for ${com.tekyerema.core.Privacy.historyDays()} days and messages for a week, all encrypted on " +
            "this phone. Screenshots are never saved. You can also say “what do you remember”, “forget everything” or " +
            "“clear my history”.")
    }
}

@Composable
private fun HelpPage(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        PageTitle("Help", onBack)
        HelpBlock(
            "Just say what you need",
            listOf("“Call Prince.”", "“Read my WhatsApp messages.”", "“Reply to Prince that I’ll call him after class.”",
                "“Set an alarm for 6 AM.”", "“Turn on Wi-Fi.”", "“How much credit do I have?”")
        )
        HelpBlock("More you can say", listOf(
            "“Who called me?” · “Read my last text.” · “Answer the call.” · “Hang up.”",
            "“Where am I?” · “Am I online?” · “What's playing?” · “What's on my calendar today?”",
            "“Put my phone on vibrate.” · “Take a screenshot.” · “Show my alarms.”",
            "“Speak slower.” · “Keep it short.” · “Be patient with me.”"
        ))
        HelpBlock("Talk from anywhere", listOf(
            "Use the floating Talk button, the accessibility button in your navigation bar, or say “Hey Tɛkyerɛma”.",
            "Press volume-down twice quickly, or add the Tɛkyerɛma tile to your Quick Settings.",
            "Tɛkyerɛma listens straight away. Tap anywhere outside the circle to close it."
        ))
        HelpBlock("Before anything risky", listOf(
            "For calls, messages, money and settings that matter, Tɛkyerɛma asks you out loud — “Should I go ahead?” — and waits for you to say yes or no.",
            "If it can’t make out your answer it asks once more, then leaves it. You can also tap Yes or No."
        ))
        HelpBlock("To stop", listOf(
            "Tap the centre of the screen while Tɛkyerɛma is working. Nothing more happens.",
            "From any app: press volume-down twice, tap the Tɛkyerɛma tile, or press Stop on its notification."
        ))
    }
}

@Composable
private fun HelpBlock(title: String, lines: List<String>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = PageMargin, vertical = 14.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Tk.Text, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(8.dp))
        lines.forEach { Text(it, style = MaterialTheme.typography.bodyLarge, color = Tk.TextSecondary, modifier = Modifier.padding(vertical = 3.dp)) }
    }
}
