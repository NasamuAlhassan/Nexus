package com.tekyerema.ime

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.tekyerema.ui.components.BigButton
import com.tekyerema.ui.components.Hairline
import com.tekyerema.ui.components.Note
import com.tekyerema.ui.components.Overline
import com.tekyerema.ui.components.PageMargin
import com.tekyerema.ui.components.PageTitle
import com.tekyerema.ui.components.SwitchRow
import com.tekyerema.ui.theme.Tk

/**
 * G-05 — "Add the Nexus keyboard": Settings › Keyboard. Self-contained (ime/ owns it; ui/ only links to it), built
 * from the shared row components so it looks like the rest of Settings. The steps are also SPOKEN when the page opens
 * or the user comes back from the system screen, unless TalkBack is reading the page (the onboarding K-10 pattern).
 */
@Composable
fun TwiKeyboardPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { KeyboardPrefs(context) }
    val status = rememberKeyboardStatus()
    var big by remember { mutableStateOf(prefs.bigKeys) }
    var vibrate by remember { mutableStateOf(prefs.vibrate) }
    var sound by remember { mutableStateOf(prefs.sound) }
    var suggest by remember { mutableStateOf(prefs.suggestions) }
    var swipe by remember { mutableStateOf(prefs.swipeTyping) }
    var learn by remember { mutableStateOf(prefs.learnWords) }
    var trial by remember { mutableStateOf("") }
    var voiceLang by remember { mutableStateOf(prefs.voiceLanguage) }
    var sendMode by remember { mutableStateOf(prefs.sendMode) }
    var resetSaid by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("Keyboard", onBack)
        TwiKeyboardSetupCard(status)
        // In one pane like every other settings page (redesign, 2 Nov 2026: the options lay loose on the page with hairlines).
        com.tekyerema.ui.components.GlassGroup("Options") {
            SwitchRow("Big keys", "Taller keys and larger letters.", big) { prefs.bigKeys = it; big = it }
            SwitchRow("Vibrate on each key", null, vibrate) { prefs.vibrate = it; vibrate = it }
            SwitchRow("Sound on each key", null, sound) { prefs.sound = it; sound = it }
            SwitchRow("Word suggestions", "Offers words above the keys as you type. Tap one to use it.", suggest) {
                prefs.suggestions = it; suggest = it
            }
            SwitchRow("Swipe to type", "Slide your finger from letter to letter and lift: the word goes in, and other " +
                "words it could be wait above the keys. Slide up from a top-row key for its number.", swipe) {
                prefs.swipeTyping = it; swipe = it
            }
            SwitchRow("Learn my words", "Remembers new words you type, on this phone only, to suggest them later. Never in " +
                "password or PIN boxes. Turning this off deletes them.", learn, divider = false) {
                prefs.learnWords = it; learn = it
                if (!it) LearnedWordStore.clear(context)
            }
        }
        // Local languages (owner, 2 Nov 2026; docs/agent/KEYBOARD_LANGUAGES.md): the microphone listens in English on the phone,
        // or in a local language online through our language service. Also on the keyboard: long-press the microphone.
        com.tekyerema.ui.components.GlassGroup("Voice typing language") {
            Column(Modifier.selectableGroup()) {
                val langs = listOf<LocalLang?>(null) + LocalLanguages.ALL
                langs.forEachIndexed { i, l ->
                    com.tekyerema.ui.components.ChoiceRow(l?.name ?: "English",
                        if (l == null) "On the phone." else "Online: what you say is sent to be written." + (l.warning?.let { " $it" } ?: ""),
                        selected = voiceLang == l, divider = i < langs.lastIndex) { prefs.voiceLanguage = l; voiceLang = l }
                }
            }
        }
        if (voiceLang != null) com.tekyerema.ui.components.GlassGroup("Type my words in") {
            Column(Modifier.selectableGroup()) {
                val name = voiceLang?.name ?: "my language"
                com.tekyerema.ui.components.ChoiceRow(name, "Your words, as you said them.", selected = sendMode == SendMode.MINE) {
                    prefs.sendMode = SendMode.MINE; sendMode = SendMode.MINE }
                com.tekyerema.ui.components.ChoiceRow("English", "Translated. Names and numbers are kept as you said them.",
                    selected = sendMode == SendMode.ENGLISH) { prefs.sendMode = SendMode.ENGLISH; sendMode = SendMode.ENGLISH }
                com.tekyerema.ui.components.ChoiceRow("Ask each time", "Choose after you speak.", selected = sendMode == SendMode.ASK,
                    divider = false) { prefs.sendMode = SendMode.ASK; sendMode = SendMode.ASK }
            }
        }
        if (voiceLang != null) com.tekyerema.ui.components.GlassGroup {
            com.tekyerema.ui.components.SettingRow("Reset this phone's code", value = if (resetSaid) "Done" else null,
                description = "A random code, renewed every month, that shares the language service fairly between phones. " +
                    "It is not your number or account.", divider = false) { prefs.resetDevice(); resetSaid = true }
        }
        Note("Before anything is typed, the keyboard reads it back to you, in the language it will be typed in.")
        Overline("Try it")
        OutlinedTextField(
            value = trial, onValueChange = { trial = it },
            label = { Text("Type here to try the keyboard") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = PageMargin),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Tk.Text, unfocusedTextColor = Tk.Text, focusedBorderColor = Tk.Cyan,
                unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedContainerColor = Tk.Ink2,
                focusedContainerColor = Tk.Ink2, focusedLabelColor = Tk.Cyan, unfocusedLabelColor = Tk.TextSecondary,
                cursorColor = Tk.Cyan)
        )
        Note("The keyboard keeps what you type on this phone. It never sends it anywhere and keeps no history. " +
            "Voice typing in English uses your phone; in another language it goes online to our language service, which " +
            "keeps no recording or text. Voice typing is off in password and PIN boxes. " +
            "The suggested words are a public list of common English words, kept on the phone.")
        Spacer(Modifier.height(24.dp))
    }
}

/** Status, the spoken-friendly steps, and the one button for the step the user is on. Usable on its own (onboarding). */
@Composable
fun TwiKeyboardSetupCard(status: KeyboardStatus = rememberKeyboardStatus()) {
    val context = LocalContext.current
    LaunchedEffect(status) {
        if (!com.tekyerema.voice.ScreenReader.active(context)) com.tekyerema.voice.VoiceEngine.announce(KeyboardSetupText.steps(status))
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = PageMargin)) {
        Text(KeyboardSetupText.statusLabel(status), style = MaterialTheme.typography.titleLarge, color = Tk.Text,
            modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(8.dp))
        Text(KeyboardSetupText.steps(status), style = MaterialTheme.typography.bodyLarge, color = Tk.TextSecondary)
        Spacer(Modifier.height(20.dp))
        when (status) {
            KeyboardStatus.NOT_ENABLED -> BigButton("Add the keyboard", onClick = { KeyboardSetup.openKeyboardList(context) })
            KeyboardStatus.ENABLED_NOT_SELECTED -> {
                BigButton("Use this keyboard", onClick = { KeyboardSetup.showPicker(context) })
                Spacer(Modifier.height(12.dp))
                BigButton("Open the keyboard list", onClick = { KeyboardSetup.openKeyboardList(context) }, filled = false)
            }
            KeyboardStatus.IN_USE -> BigButton("Choose another keyboard", onClick = { KeyboardSetup.showPicker(context) }, filled = false)
        }
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * The keyboard's setup state, re-read when the user comes back from the system keyboard list (ON_RESUME) and when the
 * keyboard chooser — a dialog over our window, which does not pause the activity — closes (window focus returns).
 */
@Composable
fun rememberKeyboardStatus(): KeyboardStatus {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = LocalView.current
    val tick = remember { mutableIntStateOf(0) }
    DisposableEffect(owner, view) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick.intValue++ }
        val focus = android.view.ViewTreeObserver.OnWindowFocusChangeListener { has -> if (has) tick.intValue++ }
        owner.lifecycle.addObserver(obs)
        view.viewTreeObserver.addOnWindowFocusChangeListener(focus)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(focus)
        }
    }
    return remember(tick.intValue) { KeyboardSetup.status(context) }
}
