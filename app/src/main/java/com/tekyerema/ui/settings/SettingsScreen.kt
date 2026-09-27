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

private enum class Page { ROOT, LANGUAGE, VOICE, PERMISSIONS, ACCESSIBILITY, HELP }

/** Exactly five things: Language, Voice, Permissions, Accessibility, Help. */
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
            SettingRow("Permissions", value = permissionsSummary(context)) { page = Page.PERMISSIONS }
            SettingRow("Accessibility") { page = Page.ACCESSIBILITY }
            SettingRow("Help") { page = Page.HELP }
        }
        Page.LANGUAGE -> LanguagePage(settings) { page = Page.ROOT }
        Page.VOICE -> VoicePage(settings) { page = Page.ROOT }
        Page.PERMISSIONS -> PermissionsPage { page = Page.ROOT }
        Page.ACCESSIBILITY -> AccessibilityPage(settings) { page = Page.ROOT }
        Page.HELP -> HelpPage { page = Page.ROOT }
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
    val enVoice = remember(version) { settings.abenaEnglishVoice }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("Voice", onBack)
        Hairline(inset = PageMargin)
        SwitchRow("Read replies aloud", "Turn off if you prefer TalkBack to read them.", speak) { settings.speakAloud = it }
        Overline("Ghanaian-English voice")
        Hairline(inset = PageMargin)
        Column(Modifier.selectableGroup()) {
            ChoiceRow("Akua (female)", "Ghanaian-accented English.", selected = enVoice == "akua_eng") { settings.abenaEnglishVoice = "akua_eng" }
            ChoiceRow("Kwabena (male)", "Ghanaian-accented English.", selected = enVoice == "kwabena_eng") { settings.abenaEnglishVoice = "kwabena_eng" }
        }
        Overline("Speaking speed")
        Hairline(inset = PageMargin)
        Column(Modifier.selectableGroup()) {
            ChoiceRow("Slow", selected = rate == 0) { settings.speechRate = 0 }
            ChoiceRow("Normal", selected = rate == 1) { settings.speechRate = 1 }
            ChoiceRow("Fast", selected = rate == 2) { settings.speechRate = 2 }
        }
        Note("English and Twi use a Ghanaian voice online (Abena, Khaya as backup). Offline, English uses your phone's voice.")
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("Accessibility", onBack)
        Hairline(inset = PageMargin)
        SwitchRow("Sounds", "A short tone when listening starts, ends, and when a task is done.", sounds) { settings.sounds = it }
        SwitchRow("Vibration", "A light tap with each change.", haptics) { settings.haptics = it }
        SwitchRow("Reduce motion", "Keeps the visual still.", reduce) { settings.reduceMotion = it }
        SwitchRow("Floating Talk button", "A button over other apps that starts Tɛkyerɛma. Needs screen control.", floating) { settings.floatingTrigger = it }
        Note("Text size and TalkBack follow your phone's accessibility settings.")
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
        HelpBlock("Talk from anywhere", listOf(
            "Use the floating Talk button, or the accessibility button in your navigation bar, from any app.",
            "Tɛkyerɛma listens straight away. Tap anywhere outside the circle to close it."
        ))
        HelpBlock("Before anything risky", listOf(
            "For calls, messages, money and settings that matter, Tɛkyerɛma asks you out loud — “Should I go ahead?” — and waits for you to say yes or no.",
            "If it can’t make out your answer it asks once more, then leaves it. You can also tap Yes or No."
        ))
        HelpBlock("To stop", listOf("Tap the centre of the screen while Tɛkyerɛma is working. Nothing more happens."))
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
