package com.tekyerema.ui

import android.Manifest
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.tekyerema.settings.Settings
import com.tekyerema.ui.home.HomeContent
import com.tekyerema.ui.theme.TekyeremaTheme
import com.tekyerema.ui.theme.Tk
import com.tekyerema.ui.voice.Presence
import com.tekyerema.voice.VoiceEngine
import com.tekyerema.voice.VoiceState
import kotlinx.coroutines.delay

/**
 * The arrive-on-top experience. Launched by the accessibility trigger from anywhere on the phone:
 * the current app dims, the living voice fades up over it, and listening starts immediately.
 *
 * Leaving is easy: tap anywhere outside the circle (the whole backdrop is one big "Close" control
 * for TalkBack too), or press Back. The only time an outside tap is ignored is while the user is
 * mid-sentence, so a brush of the screen never cuts them off. A task already running continues
 * in the background; a reply is still spoken.
 *
 * Own task (see the manifest) so closing returns to whatever the user was doing.
 */
class VoiceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        val settings = Settings(this)
        setContent { TekyeremaTheme { Arrival(settings, onClose = { finish() }) } }
    }

    override fun onDestroy() {
        // Leaving the screen never leaves a microphone open; a task already running continues.
        when (VoiceEngine.state.value.state) {
            VoiceState.LISTENING -> VoiceEngine.cancel()
            VoiceState.TYPING -> VoiceEngine.endTyping()
            else -> {}
        }
        super.onDestroy()
    }

    companion object {
        internal val closeRequests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        /** Step aside for an app the user asked to open (seen on the A20e: a fast-path launch stayed BEHIND this
         *  screen, the "is it open?" check timed out after 6 s and the agent opened the app twice more). The voice
         *  turn itself keeps going — the reply is still spoken. */
        fun requestClose() { closeRequests.tryEmit(Unit) }
    }
}

@Composable
private fun Arrival(settings: Settings, onClose: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    LaunchedEffect(Unit) { VoiceActivity.closeRequests.collect { onClose() } }   // an app is being opened over us
    val scrim by animateFloatAsState(if (shown) 0.94f else 0f, tween(420, easing = FastOutSlowInEasing), label = "scrim")
    val scale by animateFloatAsState(if (shown) 1f else 0.86f, tween(520, easing = FastOutSlowInEasing), label = "scale")

    // Start listening as soon as we arrive (asking for the microphone first if we must).
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) VoiceEngine.start() else onClose()
    }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        // C-12: arriving while a task works (the mic free) opens an interjection — pause, status or a correction.
        // A task waiting on the user (a question nobody is listening for): opening Tɛkyerɛma is the reply.
        if (!VoiceEngine.isRunning || VoiceEngine.canInterject || VoiceEngine.canRespond) {
            // A11Y-H2: when a screen reader is driving, let its window announcement finish before opening the
            // (gained) mic, so TalkBack's own speech isn't captured as a command.
            if (Perms.talkBack(context)) delay(1200)
            if (Perms.mic(context)) VoiceEngine.start() else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Stay for the conversation, then get out of the way. After Tɛkyerɛma answers we don't close on
    // the user (§7): when replies are read aloud (a spoken back-and-forth), we listen once more for a
    // natural follow-up — the agent's cross-turn memory carries the context, so it feels like one
    // conversation (§32), not isolated commands. Silence ends it and the screen closes. Never closes
    // while the keyboard is open, nor mid-task/mid-confirmation (those presences aren't DONE/IDLE).
    var presence by remember { mutableStateOf(Presence.IDLE) }
    val voice by VoiceEngine.state.collectAsState()
    val typing = voice.state == VoiceState.TYPING
    val conversational = remember { settings.speakAloud }
    LaunchedEffect(presence, typing) {
        if (typing) return@LaunchedEffect
        when (presence) {
            Presence.DONE -> {
                delay(1600)   // let the spoken reply finish before re-opening the mic
                if (conversational && Perms.mic(context) && !VoiceEngine.isRunning) VoiceEngine.followUp()
                // If not conversational (visual/TalkBack), the IDLE that follows closes us.
            }
            // A11Y-H3: a screen-reader user explores by touch (slower) — don't dismiss the panel out from under them.
            // I-08: the answer card stays long enough to be read (≈60 ms a character, up to 15 s).
            Presence.IDLE -> {
                val readMs = (voice.spoken.length * 60L).coerceAtMost(15_000L)
                delay(maxOf(if (Perms.talkBack(context)) 12000L else 4000L, readMs))
                if (!VoiceEngine.isRunning && Perms.mic(context)) onClose()
            }
            else -> {}
        }
    }

    // Mid-sentence: answering a request OR answering a yes/no question — never cut off either.
    val midSentence = voice.speechDetected
    val focusManager = LocalFocusManager.current
    val dismiss = {
        when (presence) {
            Presence.LISTENING, Presence.SPEAKING, Presence.ERROR -> VoiceEngine.cancel()   // close the mic / stop talking
            else -> {}                                                                  // a running task keeps going
        }
        onClose()
    }
    BackHandler { dismiss() }

    val outsideInteraction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Tk.Ink.copy(alpha = scrim))
            // The backdrop is the close control: anywhere outside the circle, any time you are not mid-sentence.
            .clickable(
                interactionSource = outsideInteraction, indication = null, role = Role.Button,
                onClickLabel = if (typing) "Hide keyboard" else "Close Tɛkyerɛma", enabled = !midSentence,
                // While typing, the first outside tap only puts the keyboard away.
                onClick = { if (typing) focusManager.clearFocus() else dismiss() }
            )
            .semantics { contentDescription = if (typing) "Hide keyboard" else "Close Tɛkyerɛma" }
    ) {
        Box(Modifier.fillMaxSize().systemBarsPadding().imePadding().graphicsLayer { alpha = scrim / 0.94f; scaleX = scale; scaleY = scale }) {
            HomeContent(
                settings,
                showBrand = false,
                circularHitArea = true,
                footer = when { midSentence -> null; typing -> "Tap outside to hide the keyboard"; else -> "Tap anywhere outside to close" },
                onPresence = { presence = it }
            )
        }
    }
}
