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
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.tekyerema.settings.Settings
import com.tekyerema.ui.home.HomeContent
import com.tekyerema.ui.theme.LocalCalmMotion
import com.tekyerema.ui.theme.TekyeremaTheme
import com.tekyerema.ui.theme.Tk
import com.tekyerema.ui.voice.Presence
import com.tekyerema.voice.VoiceEngine
import com.tekyerema.voice.VoiceState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        // "Hey Tɛkyerɛma" at the lock screen must SHOW something (owner 2026-09-30) — the panel appears over the
        // keyguard and wakes the display; the phone stays locked (no keyguard dismiss is requested).
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        val settings = Settings(this)
        setContent { TekyeremaTheme { Arrival(settings, onClose = { finish() }) } }
    }

    override fun onStart() { super.onStart(); OwnUi.onShown() }
    override fun onStop() { OwnUi.onHidden(); super.onStop() }

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
        /** Step aside (see [OwnUi]). The voice turn itself keeps going — the reply is still spoken. */
        fun requestClose() = OwnUi.requestStepAside()
    }
}

/** How long the exit plays before the window goes (a close the user asked for; never a step-aside). */
private const val EXIT_MS = 200

@Composable
private fun Arrival(settings: Settings, onClose: () -> Unit) {
    val calm = LocalCalmMotion.current
    var shown by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { shown = true }
    // The request is made: a task that operates the phone, or a command on the app in front, needs us gone (OwnUi).
    // That close is IMMEDIATE — the agent waits for our window to be gone before it touches the app underneath.
    LaunchedEffect(Unit) { OwnUi.stepAside.collect { onClose() } }
    // A close the user asked for (Back, a tap outside, the screen timing out) plays a short exit first: the circle
    // sinks back and the dimming lifts, then the window goes — it no longer vanishes in one frame.
    val close: () -> Unit = {
        if (!leaving) {
            leaving = true
            scope.launch { if (!calm) delay(EXIT_MS.toLong()); onClose() }
        }
    }
    val visible = shown && !leaving
    // Arrival: the dimming fades up (effects spring) while the presence rises and grows into place on a spatial
    // spring with a touch of overshoot — it "arrives" rather than appears. Calm motion: a fade only.
    val scrim by animateFloatAsState(if (visible) 0.94f else 0f,
        if (visible) spring(1f, 300f) else tween(EXIT_MS, easing = FastOutLinearInEasing), label = "scrim")
    val rise by animateFloatAsState(if (visible) 1f else 0f,
        if (visible) spring(0.72f, 220f) else tween(EXIT_MS, easing = FastOutLinearInEasing), label = "rise")
    val riseY = with(LocalDensity.current) { 56.dp.toPx() }

    // Start listening as soon as we arrive (asking for the microphone first if we must).
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) VoiceEngine.start() else close()
    }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        // C-12: arriving while a task works (the mic free) opens an interjection — pause, status or a correction.
        // A task waiting on the user (a question nobody is listening for): opening Tɛkyerɛma is the reply.
        // Still reading out the last reply: opening Tɛkyerɛma means "I want to talk" — stop reading and listen.
        if (!VoiceEngine.isRunning || VoiceEngine.canInterject || VoiceEngine.canRespond || VoiceEngine.canPreempt) {
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
                // Never close mid-sentence (owner 2026-09-30: "when you are talking it kind of hides") — a repeat,
                // an announcement or a slow paced reading can still be speaking after the state went IDLE.
                while (VoiceEngine.isSpeaking) delay(250)
                if (!VoiceEngine.isRunning) close()
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
        close()
    }
    BackHandler { dismiss() }

    val outsideInteraction = remember { MutableInteractionSource() }
    val scrimColor = Tk.Ink
    com.tekyerema.ui.SystemBarsFollowTheme()
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind { drawRect(scrimColor.copy(alpha = scrim)) }     // draw phase: no recomposition per frame
            // The backdrop is the close control: anywhere outside the circle, any time you are not mid-sentence.
            .clickable(
                interactionSource = outsideInteraction, indication = null, role = Role.Button,
                onClickLabel = if (typing) "Hide keyboard" else "Close Tɛkyerɛma", enabled = !midSentence,
                // While typing, the first outside tap only puts the keyboard away.
                onClick = { if (typing) focusManager.clearFocus() else dismiss() }
            )
            .semantics { contentDescription = if (typing) "Hide keyboard" else "Close Tɛkyerɛma" }
    ) {
        Box(Modifier.fillMaxSize().systemBarsPadding().imePadding().graphicsLayer {
            alpha = rise.coerceIn(0f, 1f)
            if (!calm) {
                val s = 0.88f + 0.12f * rise
                scaleX = s; scaleY = s
                translationY = (1f - rise) * riseY
            }
        }) {
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
