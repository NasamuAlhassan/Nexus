package com.tekyerema.ui.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.VolumeUp
import com.tekyerema.ui.components.ChoiceRow
import com.tekyerema.ui.components.GlassGroup
import com.tekyerema.ui.theme.Spectrum
import com.tekyerema.ui.theme.glass
import com.tekyerema.ui.components.Note
import com.tekyerema.ui.components.PageTitle
import com.tekyerema.ui.components.SettingRow
import com.tekyerema.ui.components.SwitchRow
import com.tekyerema.ui.theme.LocalCalmMotion
import com.tekyerema.ui.theme.Tk
import com.tekyerema.ui.theme.TkMotion

internal enum class Page {
    ROOT, LANGUAGE, VOICE, KEYBOARD, PERMISSIONS, ACCESSIBILITY, HELP,
    CONTROL, PROVIDERS, KEYS, USAGE, DATA, AUDIT,
    PHONE_CARE, MONEY_LIMITS, APP_RULES;

    val parent: Page get() = when (this) {
        ROOT -> ROOT
        PROVIDERS, KEYS, USAGE, DATA, AUDIT, MONEY_LIMITS, APP_RULES -> CONTROL
        else -> ROOT
    }
    val depth: Int get() = if (this == ROOT) 0 else parent.depth + 1
}

/** Language, Voice, Permissions, Accessibility, Privacy (J-15 / H-12 / Q-07 / Q-08), Control (WS-C), Help. */
@Composable
fun SettingsScreen(settings: Settings, modifier: Modifier = Modifier) {
    var page by rememberSaveable { mutableStateOf(Page.ROOT) }
    val version by settings.changes.collectAsState()
    BackHandler(enabled = page != Page.ROOT) { page = page.parent }
    val context = LocalContext.current

    // Deeper pages slide in from the right and back out to it (Material's shared axis X); calm motion cross-fades.
    val calm = LocalCalmMotion.current
    AnimatedContent(
        targetState = page,
        transitionSpec = { TkMotion.sharedAxisX(forward = targetState.depth > initialState.depth, calm = calm) },
        label = "settings page"
    ) { shown -> when (shown) {
        Page.ROOT -> Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            PageTitle("Settings")
            GlassGroup("Voice & language") {
                SettingRow("Language", value = remember(version) { languageLabel(settings.languageMode) },
                    icon = Icons.Outlined.Language, iconTint = Spectrum.Aqua) { page = Page.LANGUAGE }
                SettingRow("Voice", value = remember(version) { rateLabel(settings.speechRate) },
                    icon = Icons.Outlined.RecordVoiceOver, iconTint = Spectrum.Green) { page = Page.VOICE }
                // G-05: the Twi keyboard's own page (setup, options, a box to try it) — reachable from here since 13 Oct 2026.
                SettingRow("Keyboard", value = keyboardLabel(com.tekyerema.ime.rememberKeyboardStatus()),
                    icon = Icons.Outlined.Keyboard, iconTint = Spectrum.Teal, divider = false) { page = Page.KEYBOARD }
            }
            GlassGroup {
                SettingRow("Accessibility", icon = Icons.Outlined.AccessibilityNew, iconTint = Spectrum.Lime, divider = false) { page = Page.ACCESSIBILITY }
            }
            GlassGroup("Safety") {
                SettingRow("Permissions", value = permissionsSummary(context),
                    icon = Icons.Outlined.VerifiedUser, iconTint = Spectrum.Gold) { page = Page.PERMISSIONS }
                SettingRow("Control", value = remember(version) { controlSummary() },
                    icon = Icons.Outlined.Tune, iconTint = Spectrum.Green) { page = Page.CONTROL }
                SettingRow("Phone care", value = remember(version) { phoneCareSummary(settings) },
                    icon = Icons.Outlined.HealthAndSafety, iconTint = Spectrum.Lime, divider = false) { page = Page.PHONE_CARE }
            }
            GlassGroup {
                SettingRow("Help", icon = Icons.Outlined.HelpOutline, iconTint = Spectrum.Green, divider = false) { page = Page.HELP }
            }
        }
        Page.LANGUAGE -> LanguagePage(settings) { page = Page.ROOT }
        Page.VOICE -> VoicePage(settings) { page = Page.ROOT }
        Page.KEYBOARD -> com.tekyerema.ime.TwiKeyboardPage { page = Page.ROOT }
        Page.PERMISSIONS -> PermissionsPage { page = Page.ROOT }
        Page.ACCESSIBILITY -> AccessibilityPage(settings) { page = Page.ROOT }
        Page.HELP -> HelpPage(settings) { page = Page.ROOT }
        Page.CONTROL -> ControlPage(settings, onBack = { page = Page.ROOT }) { page = it }
        Page.PROVIDERS -> ProvidersPage(settings) { page = Page.CONTROL }
        Page.KEYS -> KeysPage(settings) { page = Page.CONTROL }
        Page.USAGE -> UsagePage(settings) { page = Page.CONTROL }
        Page.DATA -> DataPage(settings, onBack = { page = Page.CONTROL }) { page = Page.AUDIT }
        Page.AUDIT -> AuditPage { page = Page.CONTROL }
        Page.PHONE_CARE -> PhoneCarePage(settings) { page = Page.ROOT }
        Page.MONEY_LIMITS -> MoneyLimitsPage(settings) { page = Page.CONTROL }   // money safety round 2
        Page.APP_RULES -> AppRulesPage(settings) { page = Page.CONTROL }
    } }
}

/** The Keyboard row's value: where the Twi keyboard stands on this phone. */
private fun keyboardLabel(status: com.tekyerema.ime.KeyboardStatus): String = when (status) {
    com.tekyerema.ime.KeyboardStatus.IN_USE -> "Twi keyboard in use"
    com.tekyerema.ime.KeyboardStatus.ENABLED_NOT_SELECTED -> "Twi keyboard added"
    com.tekyerema.ime.KeyboardStatus.NOT_ENABLED -> "Add the Twi keyboard"
}

/** "Alerts off" / "2 alerts on" — the Phone care row's value (on screen only). */
private fun phoneCareSummary(s: Settings): String {
    val on = listOf(s.alertMoney, s.alertMissedCalls, s.alertBattery).count { it }
    return if (on == 0) "Alerts off" else if (on == 1) "1 alert on" else "$on alerts on"
}

/** "Normal · cloud on" — the Control row's value. */
private fun controlSummary(): String {
    val op = com.tekyerema.operator.OperatorPolicy
    val mode = when (op.executionMode) {
        com.tekyerema.operator.ExecutionMode.STANDARD -> "Normal"
        com.tekyerema.operator.ExecutionMode.ASK_EVERY_STEP -> "Ask every step"
        com.tekyerema.operator.ExecutionMode.GUIDE_ONLY -> "Guide only"
    }
    return mode + if (op.cloudAllowed) "" else " · offline"
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
        GlassGroup {
            Column(Modifier.selectableGroup()) {
                ChoiceRow("English", "Ghanaian English only.", mode == Settings.LANG_MODE_ENGLISH) { settings.languageMode = Settings.LANG_MODE_ENGLISH }
                ChoiceRow("Twi", "Twi only. Replies in Twi where a Twi voice is available.", mode == Settings.LANG_MODE_TWI) { settings.languageMode = Settings.LANG_MODE_TWI }
                ChoiceRow("English + Twi", "Mix both freely. Tɛkyerɛma follows the language you use.", mode == Settings.LANG_MODE_BOTH, divider = false) { settings.languageMode = Settings.LANG_MODE_BOTH }
            }
        }
    }
}

@Composable
private fun VoicePage(settings: Settings, onBack: () -> Unit) {
    val version by settings.changes.collectAsState()
    val speak = remember(version) { settings.speakAloud }
    val rate = remember(version) { settings.speechRate }
    val profile = remember(version) { settings.profile }
    val privateReading = remember(version) { settings.privateReading }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("Voice", onBack)
        GlassGroup {
            SwitchRow("Read replies aloud", "Turn off if you prefer TalkBack to read them.", speak,
                icon = Icons.Outlined.VolumeUp, iconTint = Spectrum.Green) { settings.speakAloud = it }
            SwitchRow("Private reading", "On the loudspeaker, say who your messages are from, not what they say. " +
                "Say “read them” to hear them. Headphones read them in full.", privateReading, divider = false) { settings.privateReading = it }
        }
        GlassGroup("Experience") { Column(Modifier.selectableGroup()) {
            ChoiceRow("New to Tɛkyerɛma", "Fuller explanations, and more time to finish speaking.", profile == "beginner") { settings.profile = "beginner" }
            ChoiceRow("Experienced", "Short replies, and it answers as soon as you stop.", profile == "expert", divider = false) { settings.profile = "expert" }
        } }
        GlassGroup("Speaking speed") { Column(Modifier.selectableGroup()) {
            ChoiceRow("Slow", selected = rate == 0) { settings.speechRate = 0 }
            ChoiceRow("Normal", selected = rate == 1) { settings.speechRate = 1 }
            ChoiceRow("Fast", selected = rate == 2, divider = false) { settings.speechRate = 2 }
        } }
        Note("You can also say “speak slower”, “keep it short” or “be patient with me”.")
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
        GlassGroup {
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
        SettingRow("Calendar", v(calendar), divider = false) { if (!calendar) launcher.launch(arrayOf(android.Manifest.permission.READ_CALENDAR)) }
        }
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
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("Accessibility", onBack)
        GlassGroup {
        SwitchRow("Sounds", "A short tone when listening starts, ends, and when a task is done.", sounds) { settings.sounds = it }
        SwitchRow("Vibration", "A different tap pattern for listening, done, needs your OK, and errors.", haptics) { settings.haptics = it }
        SwitchRow("Reduce motion", "Keeps the visual still.", reduce) { settings.reduceMotion = it }
        SwitchRow("Floating Talk button", "A button over other apps that starts Tɛkyerɛma. Needs screen control.", floating) { settings.floatingTrigger = it }
        // A-07: the hands-free wake word, switchable by the user (it was only reachable over ADB).
        SwitchRow("“Hey Tɛkyerɛma”", "Listen for the wake phrase so you can talk without touching the phone.", wake) { settings.wakeWordEnabled = it }
        // J-18: the Gemini backup switch moved to Settings › Control › AI provider and model (WS-C), with each provider's
        // data statement next to it.
        // N-11: Samsung puts unused apps to sleep, which silently stops the wake word and background tasks.
        SettingRow("Keep Tɛkyerɛma running", if (Perms.batteryUnrestricted(context)) "On" else "Off", divider = false) { Perms.requestBatteryUnrestricted(context) }
        }
        Note("Text size and TalkBack follow your phone's accessibility settings.")
        val appearance = remember(version) { settings.appearance }
        GlassGroup("Appearance") { Column(Modifier.selectableGroup()) {
            ChoiceRow("Same as the phone", selected = appearance == Settings.APPEARANCE_SYSTEM) { settings.appearance = Settings.APPEARANCE_SYSTEM }
            ChoiceRow("Light", selected = appearance == Settings.APPEARANCE_LIGHT) { settings.appearance = Settings.APPEARANCE_LIGHT }
            ChoiceRow("Dark", selected = appearance == Settings.APPEARANCE_DARK, divider = false) { settings.appearance = Settings.APPEARANCE_DARK }
        } }
    }
}

@Composable
private fun HelpPage(settings: Settings, onBack: () -> Unit) {
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
            "If it can’t make out your answer it asks once more, then leaves it. You can also tap Yes or No.",
            "To try it safely, say “send a practice message”. Nothing is sent."
        ))
        HelpBlock("To stop", listOf(
            "Tap the centre of the screen while Tɛkyerɛma is working. Nothing more happens.",
            "From any app: press volume-down twice, tap the Tɛkyerɛma tile, or press Stop on its notification."
        ))
        GlassGroup {
            var replay by remember { mutableStateOf("") }
            SettingRow("Replay the welcome", value = replay, icon = Icons.Outlined.Replay, iconTint = Spectrum.Lime, divider = false,
                description = "Hear the first-time questions and the practice message again.") {
                replay = if (com.tekyerema.voice.VoiceEngine.startWelcome(replay = true)) "Playing"
                    else "Needs the microphone and Read replies aloud"
            }
        }
    }
}

@Composable
private fun HelpBlock(title: String, lines: List<String>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = com.tekyerema.ui.components.CardMargin, vertical = 6.dp)
        .glass(RoundedCornerShape(24.dp), elevation = 8.dp).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Tk.Text, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(8.dp))
        lines.forEach { Text(it, style = MaterialTheme.typography.bodyLarge, color = Tk.TextSecondary, modifier = Modifier.padding(vertical = 3.dp)) }
    }
}
