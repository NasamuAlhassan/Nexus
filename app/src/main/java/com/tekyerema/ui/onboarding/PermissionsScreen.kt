package com.tekyerema.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.tekyerema.ui.theme.Tk

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
                if (step == 3 && Perms.overlay(context)) onDone()
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        step = if (Perms.screenControl(context)) 3 else 2
    }

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
            "Say what you need. Tɛkyerɛma listens, understands, and does it for you — in English or Twi.",
            style = MaterialTheme.typography.bodyLarge, color = Tk.TextSecondary
        )
        Spacer(Modifier.height(36.dp))

        if (step == 1) {
            Text("To work for you, it needs", style = MaterialTheme.typography.titleLarge, color = Tk.Text,
                modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(10.dp))
            Hairline()
            Reason("Microphone", "to hear what you ask")
            Reason("Screen control", "to open apps and tap for you")
            Reason("Calls and contacts", "to call the people you name")
            Reason("Notifications", "to read your messages and reply")
            Spacer(Modifier.height(36.dp))
            BigButton("Allow and continue", onClick = { launcher.launch(Perms.runtimePermissions()) })
        } else if (step == 2) {
            Text("One more thing", style = MaterialTheme.typography.titleLarge, color = Tk.Text,
                modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(10.dp))
            Text(
                "Screen control is how Tɛkyerɛma opens apps and taps for you. Turn it on for Tɛkyerɛma in the next screen, then come back.",
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
        } else {
            // REL-P0-1 — without "display over other apps", opening apps / placing calls / driving MoMo from
            // the background is silently blocked, and the confirmation card can't float over other apps.
            Text("Let it act for you", style = MaterialTheme.typography.titleLarge, color = Tk.Text,
                modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(10.dp))
            Text(
                "So Tɛkyerɛma can open apps, place calls, and show its \"yes or no\" over whatever you're doing, turn on \"Display over other apps\" for Tɛkyerɛma, then come back.",
                style = MaterialTheme.typography.bodyLarge, color = Tk.TextSecondary
            )
            Spacer(Modifier.height(36.dp))
            BigButton("Turn on display over other apps", onClick = { Perms.openOverlaySettings(context) })
            Spacer(Modifier.height(12.dp))
            BigButton("Continue anyway", onClick = onDone, filled = false)
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Reason(name: String, why: String) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$name, $why" }) {
        Column(Modifier.padding(vertical = 14.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, color = Tk.Text)
            Text(why, style = MaterialTheme.typography.bodyMedium, color = Tk.TextSecondary, modifier = Modifier.padding(top = 2.dp))
        }
        Hairline()
    }
}
