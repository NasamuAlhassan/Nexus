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
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import com.tekyerema.ui.theme.LocalOrbCalm
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
/** A listen that opens within this long of a spoken reply is a follow-up to it (keeps the answer card, no full-screen listening). */
private const val FOLLOW_UP_MS = 8_000L
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
    val reduceMotion = LocalCalmMotion.current      // Reduce motion OR the system's "Remove animations" OR simple mode: the page's transitions
    val orbCalm = LocalOrbCalm.current              // the orb alone ignores simple mode: it keeps moving (owner, 27 Oct)
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
    val touchExploring = rememberTouchExploring()
    val stopEverything = { com.tekyerema.ui.assistant.StopGuard.press { VoiceEngine.cancel(); AgentEngine.stop() } }   // two taps (StopGuard)
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
    // The assistant surface at the top (ui/assistant) shows the task's steps, questions and result; while it is up the line
    // under the orb doesn't repeat it (it keeps the live words while listening, and the hint).
    var surfaceVisible by remember { mutableStateOf(false) }
    // The orb holds still (its calm path stops drawing once settled) while the surface shows a result or is being moved —
    // unless the orb is the thing saying "listening" (docs/assistant-ux PLAN_V2, stage 7).
    var orbPaused by remember { mutableStateOf(false) }
    val typeFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val phrase = Phrases.forState(presence, voice, agent)
        .let { if (presence == Presence.IDLE && !idleHintFresh) "" else it }
        .ifBlank { if (presence == Presence.IDLE && freshReply) voice.spoken else "" }
        .let { if (surfaceVisible && presence != Presence.LISTENING) "" else it }
    val greet = presence == Presence.IDLE && phrase.isEmpty() && !surfaceVisible
    // Listening fills the screen (2 Nov review): the whole page turns teal, says "Listening" in large type with the words as they
    // are heard, and the ask field gives way to one large Stop — so a low-vision user can tell from arm's length that Nexus is
    // listening. Not while a result card is up: the quiet follow-up listen after an answer must not cover the answer.
    // A FRESH listen only (the mic opened from rest): the quick follow-up listen just after an answer keeps the answer card instead.
    // It used to require "no card up", but the card itself shows "Listening…" for every listen, so the page never appeared
    // (emulator, 2 Nov 2026); the card's plain "Listening…" now steps aside for it (AssistantSurfaceHost.hideListenCard).
    val freshListen = remember(presence) { System.currentTimeMillis() - voice.spokenAt > FOLLOW_UP_MS }
    val listeningFull = presence == Presence.LISTENING && !waitingOnUser && freshListen
    // The recogniser's live words (screen only, never a live region) — not partialHint, which carries our own prompts
    // ("I'm here.", "Uh-huh.") and showed them in quotes as if the user had said them (emulator, 2 Nov 2026).
    val heardWords = voice.livePartial.trim()

    val hint = when {
        notice.isNotEmpty() -> notice
        listeningFull -> "Pause when you've finished"
        voice.state == com.tekyerema.voice.VoiceState.TYPING -> "Type, or tap the orb to talk"
        else -> ""
    }.ifEmpty { when (presence) {
        Presence.IDLE, Presence.DONE -> if (greet) "Tap the circle, or say “${com.tekyerema.ui.Brand.WAKE}”" else "Tap to talk"
        Presence.ERROR -> "Tap to try again"
        Presence.CONFIRMING -> if (waitingOnUser) "Tap to answer" else ""
        Presence.CONFIRM_LISTENING -> com.tekyerema.voice.Confirmation.LISTEN_HINT
        Presence.SPEAKING -> "Tap to interrupt"
        else -> "Tap to stop"
    } }
    val (label, stateText) = when (presence) {
        Presence.IDLE -> "Talk to ${com.tekyerema.ui.Brand.NAME}" to "Ready"
        Presence.LISTENING -> "Stop listening" to "Listening"
        Presence.THINKING -> "Cancel" to "Thinking"
        Presence.ACTING -> "Stop" to phrase.ifBlank { "Working" }
        Presence.CONFIRMING -> (if (waitingOnUser) "Answer" else "Cancel") to "Asking for your answer"
        Presence.CONFIRM_LISTENING -> (if (waitingOnUser) "Answer" else "Cancel") to "Listening for yes or no"
        Presence.SPEAKING -> "Stop talking and listen" to "Speaking"
        Presence.DONE -> "Talk to ${com.tekyerema.ui.Brand.NAME}" to "Done"
        Presence.ERROR -> "Try again" to "Something went wrong"
    }

    Box(modifier.fillMaxSize()) {
    val listenTint by androidx.compose.animation.core.animateFloatAsState(if (listeningFull) 1f else 0f,
        tween(if (reduceMotion) 90 else 280), label = "listening tint")
    if (listenTint > 0f) {
        val dark = Tk.palette.dark
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = listenTint }) {
            drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(
                if (dark) listOf(androidx.compose.ui.graphics.Color(0xFF0D4D44), androidx.compose.ui.graphics.Color(0xFF08302B), androidx.compose.ui.graphics.Color(0xFF04201C))
                else listOf(androidx.compose.ui.graphics.Color(0xFF9FE6D8), androidx.compose.ui.graphics.Color(0xFFC6F0E6), androidx.compose.ui.graphics.Color(0xFFE4F7F1))))
        }
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (showBrand) {
            // The brand steps back while the assistant card holds the top of the screen: under the card it read through the glass as
            // a ghost of "Tɛkyerɛma" beside the card's own title (emulator, 2 Nov 2026), and TalkBack has nothing to find there.
            val brandAlpha by androidx.compose.animation.core.animateFloatAsState(if (surfaceVisible) 0f else 1f,
                tween(if (reduceMotion) 90 else 200), label = "brand")
            BrandRow(Modifier.fillMaxWidth().padding(start = PageMargin, end = PageMargin, top = 14.dp)
                .graphicsLayer { alpha = brandAlpha }
                .then(if (surfaceVisible) Modifier.clearAndSetSemantics { } else Modifier))
        } else Spacer(Modifier.height(28.dp))

        // The orb and the assistant's line travel together as one centred group. While the assistant card holds the top of the
        // screen the group makes room under it (the orb shrinks a little rather than sitting half under the card; redesign 2 Nov).
        val cardRoom by androidx.compose.animation.core.animateDpAsState(if (surfaceVisible) 72.dp else 0.dp,
            tween(if (reduceMotion) 0 else 260), label = "card room")
        // Tap anywhere to talk (design round 2, 2 Nov 2026): at rest, with TalkBack off, the whole middle of Home — the circle, the
        // greeting, the space round them — starts listening, so a low-vision user who doesn't use TalkBack needn't find a button.
        // A plain touch handler: it adds nothing for TalkBack or Switch Access (they keep the one "Talk to Nexus" button).
        val tapAnywhere = micShown(presence) && !surfaceVisible && !touchExploring
        Column(Modifier.weight(1f).fillMaxWidth().padding(top = cardRoom)
            .then(if (tapAnywhere) Modifier.pointerInput(Unit) { detectTapGestures { startListening() } } else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            LivingVoice(
                presence = presence,
                level = { VoiceEngine.level.value },
                reduceMotion = orbCalm || (orbPaused && presence != Presence.LISTENING && presence != Presence.CONFIRM_LISTENING),
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
                    // At rest the orb and the mic both started listening, and TalkBack read "Talk to Nexus" twice (2 Nov review).
                    // While the mic button is there the orb is a picture to TalkBack (a finger still taps it); in every other
                    // state — listening, answering, speaking, working — it is the one control that stops or answers, so it speaks.
                    // (Likewise while listening fills the screen: the wide Stop below is the one "Stop listening".)
                    .then(if (micShown(presence) || listeningFull) Modifier.clearAndSetSemantics { } else Modifier)
                    // The orb is 60 % of its square; the rest is room for its glow. Reporting 80 % of the height (the glow still
                    // draws, centred, into the margins) brings the greeting up under the orb instead of a hand's width below it.
                    .layout { measurable, constraints ->
                        val p = measurable.measure(constraints)
                        val h = (p.height * 0.80f).toInt()
                        layout(p.width, h) { p.place(0, (h - p.height) / 2) }
                    }
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
                if (listeningFull) {
                    // Not announced: TalkBack talking now would be heard by the microphone. The earcon and buzz mark the start.
                    Text("Listening", style = MaterialTheme.typography.displaySmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                        color = Tk.Text, textAlign = TextAlign.Center)
                    if (heardWords.isNotEmpty()) Text("“$heardWords”", style = MaterialTheme.typography.headlineSmall, color = Tk.Text,
                        textAlign = TextAlign.Center, maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 14.dp))
                } else AnimatedContent(
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
                // The confirmation's buttons (Cancel + a verb, with this confirmation's own number) live in the assistant surface at
                // the top now — one place to answer, the same AgentEngine.confirm gate (ui/assistant/AssistantSurface).
            }
        }
        // "Your usual" (Usuals): at rest only — never over a question, a result card, the keyboard or the listening screen.
        val imeUp = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
        AnimatedVisibility(visible = showBrand && micShown(presence) && !surfaceVisible && !imeUp && !listeningFull,
            enter = fadeIn(tween(if (reduceMotion) 90 else 220)), exit = fadeOut(tween(120))) {
            UsualTiles(onRun = { text ->
                focusManager.clearFocus()
                val r = VoiceEngine.submitTyped(text)
                notice = r.notice
            }, modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
        }
        // While listening the ask field steps aside for one Stop the width of the screen. Ending a listen loses nothing, so it
        // is one tap (the two-tap StopGuard is for stopping a task).
        // (Listening over a running task — an interjection — still takes the two taps: Stop there ends the task too.)
        if (listeningFull) BigButton("Stop", onClick = {
                val taskRunning = agent.status == AgentStatus.THINKING || agent.status == AgentStatus.ACTING || agent.status == AgentStatus.PAUSED
                if (taskRunning) stopEverything() else VoiceEngine.cancel()
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).heightIn(min = 72.dp),
            contentDescription = "Stop listening")
        else CommandField(
            onSubmit = { text ->
                val r = VoiceEngine.submitTyped(text)
                notice = r.notice
                r.accepted
            },
            onFocusChanged = { focused -> if (focused) VoiceEngine.beginTyping() else VoiceEngine.endTyping() },
            onMic = if (micShown(presence)) startListening else null,
            onCamera = if (com.tekyerema.core.config.AppConfig.bool("camera.homeButton", true)) {
                { try { context.startActivity(com.tekyerema.camera.CameraReadActivity.intent(context, com.tekyerema.camera.CameraReadActivity.MODE_DESCRIBE)) } catch (_: Throwable) {} }
            } else null,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            focusRequester = typeFocus
        )
        if (footer != null && presence != Presence.CONFIRMING && presence != Presence.CONFIRM_LISTENING) {
            Text(footer, style = MaterialTheme.typography.bodySmall, color = Tk.TextTertiary, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp, bottom = 22.dp))
        } else Spacer(Modifier.height(6.dp))
    }
        // The island (ui/island): what keeps going — a timer, directions, music — at the top of our own screen too; it steps
        // aside while the assistant surface uses the top.
        val live by com.tekyerema.live.LiveActivities.state.collectAsState()
        androidx.compose.animation.AnimatedVisibility(
            visible = live.isNotEmpty() && !surfaceVisible,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 4.dp, start = 14.dp, end = 14.dp),
            enter = if (reduceMotion) androidx.compose.animation.EnterTransition.None else androidx.compose.animation.slideInVertically(androidx.compose.animation.core.spring(0.78f, 190f)) { -it - 60 } +
                androidx.compose.animation.fadeIn(),
            exit = if (reduceMotion) androidx.compose.animation.ExitTransition.None else androidx.compose.animation.slideOutVertically { -it - 60 } + androidx.compose.animation.fadeOut()
        ) {
            androidx.compose.runtime.CompositionLocalProvider(com.tekyerema.ui.assistant.LocalGlass provides
                    com.tekyerema.ui.theme.glassPalette()) {
                com.tekyerema.ui.island.LiveIsland(live, onAction = { id, a -> com.tekyerema.live.LiveActivities.act(id, a) })
            }
        }
        // The whole screen, so a tap outside a finished result can dismiss it (the surface itself sits at the top).
        com.tekyerema.ui.assistant.AssistantSurfaceHost(
            voice = voice, agent = agent, twi = twi,
            onVisible = { surfaceVisible = it },
            hideListenCard = listeningFull,
            readsAloud = speakAloud,
            onOrbPause = { orbPaused = it },
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** TalkBack's touch exploration is on — followed live, so turning TalkBack on or off changes Home at once. */
@Composable
private fun rememberTouchExploring(): Boolean {
    val context = LocalContext.current
    val am = remember(context) { context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager }
    var on by remember { mutableStateOf(am?.let { it.isEnabled && it.isTouchExplorationEnabled } ?: false) }
    DisposableEffect(am) {
        val l = android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener { on = it && am?.isEnabled == true }
        am?.addTouchExplorationStateChangeListener(l)
        onDispose { am?.removeTouchExplorationStateChangeListener(l) }
    }
    return on
}

/** The ask field's mic button shows only at rest; then it, not the orb, is TalkBack's "Talk to Nexus". */
private fun micShown(p: Presence) = p == Presence.IDLE || p == Presence.DONE || p == Presence.ERROR

/** "Good morning" (Twi mode: "Maakye") — with the user's name from the welcome, "Good morning, Ama" — and what to do
 *  next. A heading, so TalkBack can land on it. */
@Composable
private fun Greeting(twi: Boolean, name: String = "") {
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val (en, tw) = when (hour) {
        in 5..11 -> "Good morning" to "Maakye"
        in 12..16 -> "Good afternoon" to "Maaha"
        in 17..22 -> "Good evening" to "Maadwo"
        else -> "Hello" to "Hello"   // the small hours: "Good evening" at 3 AM read wrong (emulator, 2 Nov 2026)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(com.tekyerema.voice.Welcome.withName(if (twi) tw else en, name), style = MaterialTheme.typography.displaySmall, color = Tk.Text, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() })
        Text(if (twi) en else "How can I help?", style = MaterialTheme.typography.titleMedium, color = Tk.TextSecondary,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
}
