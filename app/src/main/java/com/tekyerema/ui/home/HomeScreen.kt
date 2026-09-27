package com.tekyerema.ui.home

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tekyerema.agent.AgentEngine
import com.tekyerema.agent.AgentStatus
import com.tekyerema.settings.Settings
import com.tekyerema.ui.Perms
import com.tekyerema.ui.components.BigButton
import com.tekyerema.ui.components.BrandRow
import com.tekyerema.ui.components.CommandField
import com.tekyerema.ui.components.PageMargin
import com.tekyerema.ui.theme.Tk
import com.tekyerema.ui.voice.LivingVoice
import com.tekyerema.ui.voice.Phrases
import com.tekyerema.ui.voice.Presence
import com.tekyerema.ui.voice.presenceOf
import com.tekyerema.voice.VoiceEngine
import kotlinx.coroutines.delay

/**
 * Home is the voice experience: brand, the living visual, one short line from the assistant,
 * and — only when the agent needs an OK — two large answer buttons. Nothing else.
 *
 * [showBrand] is off in the arrive-on-top screen. [circularHitArea] limits the visual's touch
 * target to its circle so that, on the arrive-on-top screen, everything around it is "outside".
 */
@Composable
fun HomeContent(
    settings: Settings,
    modifier: Modifier = Modifier,
    showBrand: Boolean = true,
    circularHitArea: Boolean = false,
    footer: String? = null,
    onPresence: (Presence) -> Unit = {}
) {
    val context = LocalContext.current
    val voice by VoiceEngine.state.collectAsState()
    val agent by AgentEngine.state.collectAsState()
    val level by VoiceEngine.level.collectAsState()
    val settingsVersion by settings.changes.collectAsState()
    val reduceMotion = remember(settingsVersion) { settings.reduceMotion }
    val speakAloud = remember(settingsVersion) { settings.speakAloud }

    // "Done" is a moment, not a state the engine keeps: hold it briefly after a completed turn.
    val raw = presenceOf(voice, agent)
    var prev by remember { mutableStateOf(raw) }
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(raw) {
        val wasActive = prev == Presence.SPEAKING || prev == Presence.ACTING || prev == Presence.THINKING
        prev = raw
        if (raw == Presence.IDLE && wasActive && agent.status == AgentStatus.COMPLETED) {
            done = true; delay(1800); done = false
        } else done = false
    }
    val forced by com.tekyerema.ui.voice.UiDebug.forcedPresence.collectAsState()
    val presence = forced ?: if (done) Presence.DONE else raw
    LaunchedEffect(presence) { onPresence(presence) }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) VoiceEngine.start()
    }
    val focusManager = LocalFocusManager.current
    val startListening = {
        focusManager.clearFocus()   // tapping the visual while the keyboard is up: keyboard down, mic up
        if (Perms.mic(context)) VoiceEngine.start() else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    val stopEverything = { VoiceEngine.cancel(); AgentEngine.stop() }

    // Typed input (diagnostic / alternative input): the text enters the agent through the same
    // entry point as an ASR transcript. A brief notice covers the one case it is refused: busy.
    var notice by remember { mutableStateOf("") }
    LaunchedEffect(notice) { if (notice.isNotEmpty()) { delay(2500); notice = "" } }
    DisposableEffect(Unit) { onDispose { VoiceEngine.endTyping() } }

    val phrase = Phrases.forState(presence, voice, agent).ifBlank {
        if (presence == Presence.IDLE) voice.spoken else ""
    }
    val hint = when {
        notice.isNotEmpty() -> notice
        voice.state == com.tekyerema.voice.VoiceState.TYPING -> "Type, or tap the circle to talk"
        else -> ""
    }.ifEmpty { when (presence) {
        Presence.IDLE, Presence.DONE -> "Tap to talk"
        Presence.ERROR -> "Tap to try again"
        Presence.CONFIRMING -> ""
        Presence.CONFIRM_LISTENING -> com.tekyerema.voice.Confirmation.LISTEN_HINT
        else -> "Tap to stop"
    } }
    val (label, stateText) = when (presence) {
        Presence.IDLE -> "Talk to Tɛkyerɛma" to "Ready"
        Presence.LISTENING -> "Stop listening" to "Listening"
        Presence.THINKING -> "Cancel" to "Thinking"
        Presence.ACTING -> "Stop" to phrase.ifBlank { "Working" }
        Presence.CONFIRMING -> "Cancel" to "Asking for your answer"
        Presence.CONFIRM_LISTENING -> "Cancel" to "Listening for yes or no"
        Presence.SPEAKING -> "Stop" to "Speaking"
        Presence.DONE -> "Talk to Tɛkyerɛma" to "Done"
        Presence.ERROR -> "Try again" to "Something went wrong"
    }

    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (showBrand) {
            BrandRow(Modifier.fillMaxWidth().padding(start = PageMargin - 6.dp, end = PageMargin, top = 18.dp, bottom = 2.dp))
        } else Spacer(Modifier.height(28.dp))

        // The visual and the assistant's line travel together as one centred group.
        Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            LivingVoice(
                presence = presence,
                level = level,
                reduceMotion = reduceMotion,
                contentDescription = label,
                stateDescription = stateText,
                circularHitArea = circularHitArea,
                onTap = {
                    when (presence) {
                        Presence.IDLE, Presence.DONE, Presence.ERROR -> startListening()
                        else -> stopEverything()
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false).aspectRatio(1f, matchHeightConstraintsFirst = true)
            )

            // The assistant's line. Announced to TalkBack for transient states; when a reply is
            // spoken by our own voice it is not also announced, so nothing is said twice.
            val announce = !(speakAloud && (presence == Presence.SPEAKING || presence == Presence.DONE))
            val imeOpen = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
            Column(
                Modifier.fillMaxWidth().heightIn(min = if (imeOpen) 48.dp else 120.dp).padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                if (phrase.isNotEmpty()) Text(
                    text = phrase,
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (presence == Presence.IDLE) Tk.TextSecondary else Tk.Text,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth().semantics { if (announce) liveRegion = LiveRegionMode.Polite }
                )
                if (hint.isNotEmpty()) {
                    Text(hint, style = MaterialTheme.typography.bodyMedium,
                        color = if (presence == Presence.CONFIRM_LISTENING) Tk.Attention else Tk.TextSecondary, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = if (phrase.isEmpty()) 0.dp else 10.dp))
                }
                // Buttons stay as a quiet parallel path (a helper, TalkBack, a noisy room); voice is primary.
                AnimatedVisibility(visible = presence == Presence.CONFIRMING || presence == Presence.CONFIRM_LISTENING, enter = fadeIn(), exit = fadeOut()) {
                    Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BigButton("No", onClick = { AgentEngine.confirm(false) }, modifier = Modifier.weight(1f), filled = false,
                            contentDescription = "No, don't do it")
                        BigButton("Yes, do it", onClick = { AgentEngine.confirm(true) }, modifier = Modifier.weight(1.4f))
                    }
                }
            }
        }
        CommandField(
            onSubmit = { text ->
                val accepted = VoiceEngine.submitText(text)
                if (!accepted && text.isNotBlank()) notice = "Still busy — tap the circle to stop first"
                accepted
            },
            onFocusChanged = { focused -> if (focused) VoiceEngine.beginTyping() else VoiceEngine.endTyping() },
            modifier = Modifier.padding(horizontal = PageMargin, vertical = 6.dp)
        )
        if (footer != null && presence != Presence.CONFIRMING && presence != Presence.CONFIRM_LISTENING) {
            Text(footer, style = MaterialTheme.typography.bodySmall, color = Tk.TextTertiary, textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 22.dp))
        } else Spacer(Modifier.height(8.dp))
    }
}
