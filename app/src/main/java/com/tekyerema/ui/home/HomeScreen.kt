package com.tekyerema.ui.home

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tekyerema.agent.AgentEngine
import com.tekyerema.agent.AgentStatus
import com.tekyerema.settings.Settings
import com.tekyerema.ui.Perms
import com.tekyerema.ui.components.BigButton
import com.tekyerema.ui.components.BrandRow
import com.tekyerema.ui.components.CommandField
import com.tekyerema.ui.components.PageMargin
import com.tekyerema.ui.theme.LocalCalmMotion
import com.tekyerema.ui.theme.Tk
import com.tekyerema.ui.theme.TkHaptic
import com.tekyerema.ui.voice.LivingVoice
import com.tekyerema.ui.voice.Phrases
import com.tekyerema.ui.voice.Presence
import com.tekyerema.ui.voice.presenceOf
import com.tekyerema.voice.VoiceEngine
import kotlinx.coroutines.delay
import java.util.Calendar

/** How long a spoken reply stays on Home before an idle screen greets instead. */
private const val REPLY_FRESH_MS = 3 * 60_000L
/** How long a transient idle notice ("Cancelled.") stays. */
private const val IDLE_HINT_MS = 20_000L

/**
 * Home is the voice experience, and nothing else: the orb, one line from the assistant (or a greeting when there is
 * nothing to say), and — only when the agent needs an OK — two large answer buttons. The typed-input pill floats at
 * the foot with its mic button.
 *
 * [showBrand] is off in the arrive-on-top screen. [circularHitArea] limits the orb's touch target to its circle so
 * that, on the arrive-on-top screen, everything around it is "outside".
 */
@Composable
fun HomeContent(
    settings: Settings,
    modifier: Modifier = Modifier,
    showBrand: Boolean = true,
    circularHitArea: Boolean = false,
    footer: String? = null,
    onPresence: (Presence) -> Unit = {},
    /** First-run voice welcome (voice/Welcome.kt) — the app's own Home only, never the arrive-on-top screen someone opened
     *  to ask for something. */
    offerWelcome: Boolean = showBrand
) {
    val context = LocalContext.current
    val voice by VoiceEngine.state.collectAsState()
    val agent by AgentEngine.state.collectAsState()
    // The mic level is NOT collected here: the orb reads it once per frame, so a talking user no longer recomposes
    // this whole screen on every audio buffer.
    val settingsVersion by settings.changes.collectAsState()
    val reduceMotion = LocalCalmMotion.current      // Reduce motion OR the system's "Remove animations"
    val speakAloud = remember(settingsVersion) { settings.speakAloud }
    val speechRate = remember(settingsVersion) { settings.speechRate }
    val twi = remember(settingsVersion) { settings.languageMode == Settings.LANG_MODE_TWI }
    val name = remember(settingsVersion) { settings.preferredName }   // "Good morning, Ama" (voice/Welcome.kt)
    // First-run voice welcome: the first time Home opens after the permissions page, a few skippable questions by voice.
    // Never a gate — any request ends it — and marked done as it starts (VoiceEngine.startWelcome).
    if (offerWelcome) LaunchedEffect(Unit) {
        if (!settings.welcomeDone && Perms.mic(context)) { delay(900); VoiceEngine.startWelcome() }
    }

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
        focusManager.clearFocus()   // tapping the orb while the keyboard is up: keyboard down, mic up
        if (Perms.mic(context)) VoiceEngine.start() else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    val stopEverything = { VoiceEngine.cancel(); AgentEngine.stop() }
    // The task is waiting on the USER (a question, a yes/no, the PIN step): a tap on the orb is how a person answers, so
    // it opens the mic for that answer — it used to be the kill switch here. A judge on the A20e (2026-09-29) was asked
    // for the MoMo number, tapped the orb to answer, and the tap stopped the task; the answer then arrived as a brand-new
    // request with no money context ("by with ceremony"). "Stop" by voice, and the panel's Cancel, still end it.
    val waitingOnUser = agent.status == AgentStatus.ASKING || agent.status == AgentStatus.WAITING_CONFIRMATION ||
        agent.status == AgentStatus.SECURE_INPUT

    // Typed input (an alternative way in): the text enters the agent through the same entry point as an ASR
    // transcript. It is never dropped: typed while a task works it goes to that task, typed while a reply is read it
    // stops the reading (C-12). A brief notice says so when it went somewhere other than a new turn.
    var notice by remember { mutableStateOf("") }
    LaunchedEffect(notice) { if (notice.isNotEmpty()) { delay(4000); notice = "" } }
    DisposableEffect(Unit) { onDispose { VoiceEngine.endTyping() } }

    // A reply stays for a few minutes after it was spoken; after that an idle Home greets instead of showing a stale
    // "Calculator is open." hours later.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(voice.spokenAt) {
        val left = voice.spokenAt + REPLY_FRESH_MS - System.currentTimeMillis()
        if (left > 0) delay(left + 50)
        now = System.currentTimeMillis()
    }
    val freshReply = voice.spoken.isNotBlank() && now - voice.spokenAt < REPLY_FRESH_MS
    // An idle notice ("Cancelled.", "I didn't hear anything.") is a moment too: it gave way to nothing and stayed on
    // screen for hours (A20e, 2026-09-29). It shows for a short while, then the greeting returns.
    val hintAt = remember(voice.partialHint) { System.currentTimeMillis() }
    LaunchedEffect(voice.partialHint) {
        if (voice.partialHint.isNotBlank()) { delay(IDLE_HINT_MS + 50); now = System.currentTimeMillis() }
    }
    val idleHintFresh = now - hintAt < IDLE_HINT_MS || System.currentTimeMillis() - hintAt < IDLE_HINT_MS
    val phrase = Phrases.forState(presence, voice, agent)
        .let { if (presence == Presence.IDLE && !idleHintFresh) "" else it }
        .ifBlank { if (presence == Presence.IDLE && freshReply) voice.spoken else "" }
    val greet = presence == Presence.IDLE && phrase.isEmpty()

    val hint = when {
        notice.isNotEmpty() -> notice
        voice.state == com.tekyerema.voice.VoiceState.TYPING -> "Type, or tap the orb to talk"
        else -> ""
    }.ifEmpty { when (presence) {
        Presence.IDLE, Presence.DONE -> if (greet) "Tap the orb, or say “Hey Tɛkyerɛma”" else "Tap to talk"
        Presence.ERROR -> "Tap to try again"
        Presence.CONFIRMING -> if (waitingOnUser) "Tap to answer" else ""
        Presence.CONFIRM_LISTENING -> com.tekyerema.voice.Confirmation.LISTEN_HINT
        Presence.SPEAKING -> "Tap to interrupt"
        else -> "Tap to stop"
    } }
    val (label, stateText) = when (presence) {
        Presence.IDLE -> "Talk to Tɛkyerɛma" to "Ready"
        Presence.LISTENING -> "Stop listening" to "Listening"
        Presence.THINKING -> "Cancel" to "Thinking"
        Presence.ACTING -> "Stop" to phrase.ifBlank { "Working" }
        Presence.CONFIRMING -> (if (waitingOnUser) "Answer" else "Cancel") to "Asking for your answer"
        Presence.CONFIRM_LISTENING -> (if (waitingOnUser) "Answer" else "Cancel") to "Listening for yes or no"
        Presence.SPEAKING -> "Stop talking and listen" to "Speaking"
        Presence.DONE -> "Talk to Tɛkyerɛma" to "Done"
        Presence.ERROR -> "Try again" to "Something went wrong"
    }

    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (showBrand) {
            BrandRow(Modifier.fillMaxWidth().padding(start = PageMargin - 6.dp, end = PageMargin, top = 14.dp))
        } else Spacer(Modifier.height(28.dp))

        // The orb and the assistant's line travel together as one centred group.
        Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            LivingVoice(
                presence = presence,
                level = { VoiceEngine.level.value },
                reduceMotion = reduceMotion,
                contentDescription = label,
                stateDescription = stateText,
                circularHitArea = circularHitArea,
                onTap = {
                    when {
                        presence == Presence.IDLE || presence == Presence.DONE || presence == Presence.ERROR -> startListening()
                        // A question is waiting: the tap answers it (already listening → nothing more to start).
                        waitingOnUser -> if (Perms.mic(context)) { focusManager.clearFocus(); VoiceEngine.respond() }
                            else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        // Tapping while it reads a reply means "stop, I want to talk" (Gemini-style). While a task works
                        // this is still the kill switch (see interrupt()).
                        presence == Presence.SPEAKING -> if (Perms.mic(context)) { focusManager.clearFocus(); VoiceEngine.interrupt() } else stopEverything()
                        else -> stopEverything()
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false).aspectRatio(1f, matchHeightConstraintsFirst = true)
            )

            // The assistant's line — or, with nothing to say, a greeting. Announced to TalkBack for transient states;
            // a reply spoken by our own voice is not also announced, so nothing is said twice.
            val announce = !(speakAloud && (presence == Presence.SPEAKING || presence == Presence.DONE))
            val imeOpen = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
            Column(
                Modifier.fillMaxWidth().heightIn(min = if (imeOpen) 48.dp else 128.dp).padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                AnimatedContent(
                    targetState = greet && !imeOpen,
                    transitionSpec = { fadeIn(tween(if (reduceMotion) 120 else 320)) togetherWith fadeOut(tween(160)) },
                    label = "greeting"
                ) { showGreeting ->
                    if (showGreeting) Greeting(twi, name)
                    else AssistantLine(presence, phrase, announce = announce, calm = reduceMotion, speechRate = speechRate)
                }
                HintLine(
                    hint,
                    color = if (presence == Presence.CONFIRM_LISTENING || notice.isNotEmpty()) Tk.Attention else Tk.TextSecondary,
                    announce = notice.isNotEmpty(),
                    modifier = Modifier.padding(top = if (phrase.isEmpty() && !greet) 0.dp else 10.dp)
                )
                // Buttons stay as a quiet parallel path (a helper, TalkBack, a noisy room); voice is primary. Yes / No
                // only for a real confirmation. They rise into place on a spring (calm: a plain fade) and answer with
                // the system's confirm / reject haptics.
                AnimatedVisibility(
                    visible = (presence == Presence.CONFIRMING || presence == Presence.CONFIRM_LISTENING) &&
                        agent.status == com.tekyerema.agent.AgentStatus.WAITING_CONFIRMATION,
                    enter = if (reduceMotion) fadeIn(tween(160))
                        else fadeIn(tween(200, delayMillis = 80)) + expandVertically(spring(0.8f, 380f, IntSize(1, 1))) +
                            slideInVertically(spring(0.6f, 800f, IntOffset(1, 1))) { it / 2 },
                    exit = if (reduceMotion) fadeOut(tween(100))
                        else fadeOut(tween(120)) + shrinkVertically(spring(1f, 1600f, IntSize(1, 1)))
                ) {
                    Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // J-02: the buttons answer the confirmation this card is showing, not whatever is pending at tap time.
                        val seq = agent.confirmationSeq
                        BigButton("No", onClick = { AgentEngine.confirm(false, seq) }, modifier = Modifier.weight(1f), filled = false,
                            contentDescription = "No, don't do it", haptic = TkHaptic.REJECT)
                        BigButton("Yes, do it", onClick = { AgentEngine.confirm(true, seq) }, modifier = Modifier.weight(1.4f),
                            haptic = TkHaptic.CONFIRM)
                    }
                }
            }
        }
        CommandField(
            onSubmit = { text ->
                val r = VoiceEngine.submitTyped(text)
                notice = r.notice
                r.accepted
            },
            onFocusChanged = { focused -> if (focused) VoiceEngine.beginTyping() else VoiceEngine.endTyping() },
            onMic = if (presence == Presence.IDLE || presence == Presence.DONE || presence == Presence.ERROR) startListening else null,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )
        if (footer != null && presence != Presence.CONFIRMING && presence != Presence.CONFIRM_LISTENING) {
            Text(footer, style = MaterialTheme.typography.bodySmall, color = Tk.TextTertiary, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp, bottom = 22.dp))
        } else Spacer(Modifier.height(6.dp))
    }
}

/** "Good morning" (Twi mode: "Maakye") — with the user's name from the welcome, "Good morning, Ama" — and what to do
 *  next. A heading, so TalkBack can land on it. */
@Composable
private fun Greeting(twi: Boolean, name: String = "") {
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val (en, tw) = when (hour) {
        in 5..11 -> "Good morning" to "Maakye"
        in 12..16 -> "Good afternoon" to "Maaha"
        else -> "Good evening" to "Maadwo"
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(com.tekyerema.voice.Welcome.withName(if (twi) tw else en, name), style = MaterialTheme.typography.displaySmall, color = Tk.Text, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() })
        Text(if (twi) en else "How can I help?", style = MaterialTheme.typography.titleMedium, color = Tk.TextSecondary,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
}
