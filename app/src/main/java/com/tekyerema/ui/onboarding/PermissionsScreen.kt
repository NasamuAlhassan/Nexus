package com.tekyerema.ui.onboarding

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.tekyerema.ui.Perms
import com.tekyerema.ui.components.BigButton
import com.tekyerema.ui.components.BrandMark
import com.tekyerema.ui.components.Hairline
import com.tekyerema.ui.components.PageMargin
import com.tekyerema.ui.theme.LocalCalmMotion
import com.tekyerema.ui.theme.Tk
import com.tekyerema.ui.theme.TkMotion

/**
 * First launch: one page that says why, one button that asks. Then the product.
 * Step 1 requests the runtime permissions; step 2 (same page) sends the user to turn on screen
 * control, and finishes by itself when they come back with it on.
 */
@Composable
fun PermissionsScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(if (Perms.mic(context)) 2 else 1) }

    // Advance automatically as each system permission is turned on (the user returns from system settings).
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, step) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                // Overlay ("display over other apps") is the background-activity-launch exemption that lets
                // Tɛkyerɛma open apps, place calls and drive MoMo from its service — without it those launches
                // are silently blocked (REL-P0-1), so onboarding now asks for it after screen control.
                if (step == 2 && Perms.screenControl(context)) step = 3
                if (step == 3 && Perms.overlay(context)) step = 4
                // K-10: notification access (reading and replying to messages) is the last step.
                if (step == 4 && Perms.notifications(context)) onDone()
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    // K-10: each step is SPOKEN too (unless TalkBack is reading the screen) — a blind user without a screen reader
    // otherwise meets a silent page.
    androidx.compose.runtime.LaunchedEffect(step) {
        if (!com.tekyerema.voice.ScreenReader.active(context)) com.tekyerema.voice.VoiceEngine.announce(when (step) {
            1 -> "Welcome to Nexus. To work for you it needs the microphone, calls, contacts, messages, location and calendar. " +
                "Tap the big button at the bottom of the screen, then allow each one."
            2 -> "Next, turn on screen control. Tap the button near the bottom; it opens Accessibility settings. Choose Nexus, " +
                "switch it on, then come back."
            3 -> "Next, let Nexus show over other apps. Tap the button near the bottom, switch it on, then come back."
            else -> "Last step: let Nexus read your notifications, so it can read and reply to your messages. " +
                "Tap the button near the bottom, switch it on, then come back. Or tap Skip."
        })
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        step = if (Perms.screenControl(context)) 3 else 2
    }
    val calm = LocalCalmMotion.current

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = PageMargin)) {
        Spacer(Modifier.height(28.dp))
        BrandMark(size = 56.dp, modifier = Modifier.padding(start = 0.dp))
        Spacer(Modifier.height(28.dp))
        Text(
            "Your phone,\nby voice.", style = MaterialTheme.typography.displaySmall, color = Tk.Text,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Say what you need. Nexus listens, understands, and does it for you.",
            style = MaterialTheme.typography.bodyLarge, color = Tk.TextSecondary
        )
        Spacer(Modifier.height(36.dp))

        // Each step slides on from the right as the last one is done (calm motion: a cross-fade).
        AnimatedContent(
            targetState = step,
            transitionSpec = { TkMotion.sharedAxisX(forward = targetState > initialState, calm = calm) },
            label = "onboarding step"
        ) { shown -> Column {
        if (shown == 1) {
            Text("To work for you, it needs", style = MaterialTheme.typography.titleLarge, color = Tk.Text,
                modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(10.dp))
            Hairline()
            Reason("Microphone", "to hear what you ask")
            Reason("Screen control", "to open apps and tap for you")
            Reason("Calls and contacts", "to call the people you name, answer calls and save new numbers")
            Reason("Text messages and call history", "to read and send texts and tell you who called")
            Reason("Location", "to tell you where you are when you ask")
            Reason("Calendar", "to tell you what's on today")
            Reason("Notifications", "to read your messages and reply")
            Spacer(Modifier.height(36.dp))
            BigButton("Allow and continue", onClick = { launcher.launch(Perms.runtimePermissions()) })
        } else if (shown == 2) {
            Text("One more thing", style = MaterialTheme.typography.titleLarge, color = Tk.Text,
                modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(10.dp))
            Text(
                "Screen control is how Nexus opens apps and taps for you. Turn it on for Nexus in the next screen, then come back.",
                style = MaterialTheme.typography.bodyLarge, color = Tk.TextSecondary
            )
            Spacer(Modifier.height(36.dp))
            BigButton("Turn on screen control", onClick = { Perms.openAccessibilitySettings(context) })
            // §C.1 — offer the battery-optimisation exemption so a session isn't killed mid-task by Doze.
            if (!Perms.batteryUnrestricted(context)) {
                Spacer(Modifier.height(12.dp))
                BigButton("Keep running in the background", onClick = { Perms.requestBatteryUnrestricted(context) }, filled = false)
            }
            Spacer(Modifier.height(12.dp))
            BigButton("Not now", onClick = { step = 3 }, filled = false)
        } else if (shown == 3) {
            // REL-P0-1 — without "display over other apps", opening apps / placing calls / driving MoMo from
            // the background is silently blocked, and the confirmation card can't float over other apps.
            Text("Let it act for you", style = MaterialTheme.typography.titleLarge, color = Tk.Text,
                modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(10.dp))
            Text(
                "So Nexus can open apps, place calls, and show its \"yes or no\" over whatever you're doing, turn on \"Display over other apps\" for Nexus, then come back.",
                style = MaterialTheme.typography.bodyLarge, color = Tk.TextSecondary
            )
            Spacer(Modifier.height(36.dp))
            BigButton("Turn on display over other apps", onClick = { Perms.openOverlaySettings(context) })
            Spacer(Modifier.height(12.dp))
            BigButton("Continue anyway", onClick = { step = 4 }, filled = false)
        } else {
            Text("Read your messages", style = MaterialTheme.typography.titleLarge, color = Tk.Text,
                modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(10.dp))
            Text(
                "So Nexus can read your messages and notifications to you, and reply for you, turn on notification access for Nexus, then come back.",
                style = MaterialTheme.typography.bodyLarge, color = Tk.TextSecondary
            )
            Spacer(Modifier.height(36.dp))
            BigButton("Turn on notification access", onClick = { Perms.openNotificationAccess(context) })
            Spacer(Modifier.height(12.dp))
            BigButton("Skip for now", onClick = onDone, filled = false)
        }
        } }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Reason(name: String, why: String) {
    // clearAndSetSemantics, not a description on a merging node: TalkBack would read the texts a second time (Gate G, 13 Oct 2026).
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "$name, $why" }) {
        Column(Modifier.padding(vertical = 14.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, color = Tk.Text)
            Text(why, style = MaterialTheme.typography.bodyMedium, color = Tk.TextSecondary, modifier = Modifier.padding(top = 2.dp))
        }
        Hairline()
    }
}
