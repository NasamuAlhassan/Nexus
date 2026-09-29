package com.tekyerema.ime

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
 * G-05 — "Add the Twi keyboard": Settings › Twi keyboard. Self-contained (ime/ owns it; ui/ only links to it), built
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
    var habit by remember { mutableStateOf(prefs.textingHabit) }
    var learn by remember { mutableStateOf(prefs.learnWords) }
    var trial by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("Twi keyboard", onBack)
        TwiKeyboardSetupCard(status)
        Overline("Options")
        Hairline(inset = PageMargin)
        SwitchRow("Big keys", "Taller keys and larger letters.", big) { prefs.bigKeys = it; big = it }
        SwitchRow("Vibrate on each key", null, vibrate) { prefs.vibrate = it; vibrate = it }
        SwitchRow("Sound on each key", null, sound) { prefs.sound = it; sound = it }
        SwitchRow("Word suggestions", "Offers Twi words above the keys as you type, with ɛ and ɔ in place. Tap one to use it.", suggest) {
            prefs.suggestions = it; suggest = it
        }
        SwitchRow("Turn 3 into ɛ and c into ɔ", "In Twi, when you finish a word typed the texting way, like mey3 or wc. " +
            "Press delete straight after to undo it.", habit) { prefs.textingHabit = it; habit = it }
        SwitchRow("Learn my words", "Remembers new words you type, on this phone only, to suggest them later. Never in " +
            "password or PIN boxes. Turning this off deletes them.", learn) {
            prefs.learnWords = it; learn = it
            if (!it) LearnedWordStore.clear(context)
        }
        Overline("Try it")
        OutlinedTextField(
            value = trial, onValueChange = { trial = it },
            label = { Text("Type here to try the keyboard") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = PageMargin),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Tk.Text, unfocusedTextColor = Tk.Text, focusedBorderColor = Tk.Cyan,
                unfocusedBorderColor = Tk.TextTertiary, focusedLabelColor = Tk.Cyan, unfocusedLabelColor = Tk.TextSecondary,
                cursorColor = Tk.Cyan)
        )
        Note("The keyboard keeps what you type on this phone. It never sends it anywhere and keeps no history. " +
            "Voice typing uses your phone's own speech service, and is off in password and PIN boxes. " +
            "The suggested Twi words come from Tɛkyerɛma's own Twi and are still being checked by a Twi speaker.")
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
            KeyboardStatus.NOT_ENABLED -> BigButton("Add the Twi keyboard", onClick = { KeyboardSetup.openKeyboardList(context) })
            KeyboardStatus.ENABLED_NOT_SELECTED -> {
                BigButton("Use the Twi keyboard", onClick = { KeyboardSetup.showPicker(context) })
                Spacer(Modifier.height(12.dp))
                BigButton("Open the keyboard list", onClick = { KeyboardSetup.openKeyboardList(context) }, filled = false)
            }
            KeyboardStatus.IN_USE -> BigButton("Choose another keyboard", onClick = { KeyboardSetup.showPicker(context) }, filled = false)
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** The value for a "Twi keyboard" row on the Settings root (Not added / Added, not in use / In use). */
@Composable
fun rememberTwiKeyboardStatusLabel(): String = KeyboardSetupText.statusLabel(rememberKeyboardStatus())

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
