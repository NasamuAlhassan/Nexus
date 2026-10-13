package com.tekyerema.ui.settings

import android.os.SystemClock
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tekyerema.money.PinPractice
import com.tekyerema.ui.components.BigButton
import com.tekyerema.ui.components.Note
import com.tekyerema.ui.components.PageTitle
import com.tekyerema.ui.theme.Tk

/**
 * Settings › Practise typing a PIN (money/PinPractice): a made-up number to type on a keypad laid out like the phone's —
 * 1 2 3 on top, 0 at the bottom in the middle — so a blind user can learn TalkBack's explore-by-touch typing before a
 * real Mobile Money PIN screen. What to type, progress and the verdict are a live region (TalkBack reads them out).
 * Nothing typed here is kept, logged or sent; it is never for a real PIN, and the page says so first.
 */
@Composable
internal fun PinPracticePage(onBack: () -> Unit) {
    var target by remember { mutableStateOf(PinPractice.target()) }
    var typed by remember { mutableStateOf("") }
    var started by remember { mutableLongStateOf(0L) }
    var said by remember { mutableStateOf(PinPractice.prompt(target)) }

    // A live region only speaks when its text changes: alternate a trailing space to repeat the same line.
    fun announce(line: String) { said = if (said == line) "$line " else line }
    fun fresh() { target = PinPractice.target(); typed = ""; announce(PinPractice.prompt(target)) }
    fun press(d: Char) {
        if (typed.length >= PinPractice.LENGTH) typed = ""
        if (typed.isEmpty()) started = SystemClock.uptimeMillis()
        typed += d
        if (typed.length < PinPractice.LENGTH) { announce(PinPractice.progress(typed)); return }
        val r = PinPractice.check(target, typed, SystemClock.uptimeMillis() - started)
        if (r.correct) { target = PinPractice.target(); typed = ""; announce("${r.line} Next: ${PinPractice.prompt(target)}") }
        else { typed = ""; announce(r.line) }
    }
    fun delete() { if (typed.isNotEmpty()) { typed = typed.dropLast(1); announce(PinPractice.progress(typed)) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        PageTitle("Practise typing a PIN", onBack)
        Note("Practice only — never type your real PIN here. Nothing you type is saved or sent. The keys are laid out " +
            "like the phone's keypad: 1 2 3 on top, 0 at the bottom in the middle.")
        Note(said, Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
        Text("●".repeat(typed.length) + "○".repeat(PinPractice.LENGTH - typed.length),
            Modifier.fillMaxWidth().padding(vertical = 8.dp), color = Tk.Text, fontSize = 28.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        val rows = listOf("123", "456", "789")
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            rows.forEach { r -> Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) { r.forEach { d -> Key("$d", "$d") { press(d) } } } }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Key("⌫", "Delete") { delete() }
                Key("0", "0") { press('0') }
                Key("↻", "Hear the number again") { announce(PinPractice.prompt(target)) }
            }
        }
        Spacer(Modifier.height(20.dp))
        Column(Modifier.padding(horizontal = 20.dp)) { BigButton("New number", onClick = { fresh() }, filled = false) }
        Note("Tips: in TalkBack settings, Keyboard echo set to None stops keys being read after you type them, and " +
            "“lift to type” types the key under your finger when you let go. With earphones in, no one nearby hears your digits.")
    }
}

@Composable
private fun Key(label: String, spoken: String, onClick: () -> Unit) {
    Box(
        Modifier.size(84.dp).clip(CircleShape).border(2.dp, Tk.Hairline, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            // The spoken name replaces the digit's text for TalkBack (else "five. 5. Button"); click and role stay (Gate G, 13 Oct 2026).
            .clearAndSetSemantics { contentDescription = spoken },
        contentAlignment = Alignment.Center
    ) { Text(label, color = Tk.Text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge) }
}
