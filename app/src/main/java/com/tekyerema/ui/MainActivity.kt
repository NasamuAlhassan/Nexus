package com.tekyerema.ui

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tekyerema.agent.AgentEngine
import com.tekyerema.settings.Settings
import com.tekyerema.ui.components.QuietNav
import com.tekyerema.ui.components.Section
import com.tekyerema.ui.history.HistoryScreen
import com.tekyerema.ui.home.HomeContent
import com.tekyerema.ui.onboarding.PermissionsScreen
import com.tekyerema.ui.settings.SettingsScreen
import com.tekyerema.ui.theme.LocalCalmMotion
import com.tekyerema.ui.theme.TekyeremaTheme
import com.tekyerema.ui.theme.TkMotion
import com.tekyerema.ui.theme.pageBackground
import kotlinx.coroutines.launch

/** The app: first launch shows one permissions page, then Home / History / Settings. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContent { TekyeremaTheme { App() } }
        // The page draws its own full-screen background: the window's one under it is pure overdraw on every frame (UI audit,
        // 20 Oct 2026). Dropped once the first frame is up, so the start-up still shows the themed colour, never black.
        window.decorView.post { window.setBackgroundDrawable(null) }
        // A task that operates the phone started from here: get out of its way (to the app underneath, or home) and
        // keep working in the background — the reply is spoken, the notification shows the status (OwnUi).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { OwnUi.stepAside.collect { moveTaskToBack(true) } }
        }
    }

    override fun onStart() { super.onStart(); OwnUi.onShown() }
    override fun onStop() { OwnUi.onHidden(); super.onStop() }

    override fun onResume() {
        super.onResume()
        AgentEngine.refreshReadiness()
    }
}

/** Status and navigation bar icons follow the palette: dark icons over the light theme, light over the dark one. */
@Composable
fun SystemBarsFollowTheme() {
    val dark = com.tekyerema.ui.theme.Tk.Dark
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}

@Composable
private fun App() {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    var onboarded by remember { mutableStateOf(settings.onboardingDone) }
    SystemBarsFollowTheme()

    val calm = LocalCalmMotion.current
    Box(Modifier.fillMaxSize()) {
        // The page light in its OWN offscreen layer, a sibling under the content (UI audit, 20 Oct 2026): it is recorded once
        // and reused as one image while the orb or a card moves, instead of four full-screen gradients redrawn every frame.
        Box(Modifier.matchParentSize()
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .pageBackground())
        Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            // Finishing onboarding fades through into the product rather than cutting to it.
            AnimatedContent(targetState = onboarded, transitionSpec = { TkMotion.fadeThrough(calm) }, label = "onboarding") { done ->
                if (!done) {
                    PermissionsScreen(onDone = { settings.onboardingDone = true; onboarded = true })
                } else {
                    Shell(settings)
                }
            }
        }
    }
}

@Composable
private fun Shell(settings: Settings) {
    var section by rememberSaveable { mutableStateOf(Section.HOME) }
    BackHandler(enabled = section != Section.HOME) { section = Section.HOME }
    val calm = LocalCalmMotion.current

    Column(Modifier.fillMaxSize()) {
        // Top-level destinations fade through (Material): the old page fades quickly, the new one grows into place.
        AnimatedContent(
            targetState = section,
            transitionSpec = { TkMotion.fadeThrough(calm) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            label = "section"
        ) { shown ->
            Box(Modifier.fillMaxSize()) {
                when (shown) {
                    Section.HOME -> HomeContent(settings)
                    Section.HISTORY -> HistoryScreen()
                    Section.SETTINGS -> SettingsScreen(settings)
                }
            }
        }
        QuietNav(current = section, onSelect = { section = it })
    }
}
