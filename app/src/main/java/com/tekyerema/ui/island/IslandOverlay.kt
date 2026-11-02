package com.tekyerema.ui.island

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.tekyerema.accessibility.TekyeremaAccessibilityService
import com.tekyerema.live.LiveActivities
import com.tekyerema.ui.OverlayConfirm
import com.tekyerema.ui.assistant.DarkGlass
import com.tekyerema.ui.assistant.GlassTiers
import com.tekyerema.ui.assistant.LightGlass
import com.tekyerema.ui.assistant.LocalGlass
import com.tekyerema.ui.theme.Tk
import com.tekyerema.ui.theme.TekyeremaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The island over other apps: a small window at the top centre, under the status bar, present only while something is
 * live (live/LiveActivities). It steps aside while a confirmation card is on screen (that card is the more important thing
 * at the top) and while Tɛkyerɛma's own screen is in front (which draws the island itself). The window wraps the island,
 * so touches beside it go to the app below.
 */
object IslandOverlay {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var view: ComposeView? = null
    private var wm: WindowManager? = null
    private var owner: IslandOwner? = null
    private var visible: MutableTransitionState<Boolean>? = null
    private val removeLater = Runnable { removeNow() }
    /** The accessibility service whose window holds the island (null = an app overlay window). */
    private var hostSvc: Any? = null

    /** Tɛkyerɛma's own screen is in front (it shows the island in its own layout). */
    val appFront = MutableStateFlow(false)

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        // Called again when the accessibility service (re)connects: the shown/hidden flow does not change then, so look now —
        // an island that failed to attach, or whose window died with the old service, comes back (owner, 24 Oct 2026).
        if (job?.isActive == true) { main.post { if (wanted()) attach(app) }; return }
        job = scope.launch {
            combine(LiveActivities.state, OverlayConfirm.onScreen, appFront) { acts, panel, front -> acts.isNotEmpty() && !panel && !front }
                .distinctUntilChanged()
                .collect { show -> if (show) attach(app) else detach() }
        }
    }

    private fun wanted() = LiveActivities.state.value.isNotEmpty() && !OverlayConfirm.onScreen.value && !appFront.value

    private fun attach(ctx: Context) {
        main.removeCallbacks(removeLater)
        // The window belonged to an accessibility service that has since restarted: it is gone from the screen.
        if (view != null && hostSvc != null && hostSvc !== TekyeremaAccessibilityService.live) {
            android.util.Log.i("Tekyerema", "[ISLAND] the service restarted — attaching the island again"); removeNow()
        }
        visible?.let { it.targetState = true; if (view != null) return }
        val svc = TekyeremaAccessibilityService.live
        if (svc == null && !Settings.canDrawOverlays(ctx)) { android.util.Log.i("Tekyerema", "[ISLAND] not shown: no screen control and no overlay permission"); return }
        val host: Context = svc ?: ctx
        val manager = host.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val type = when {
            svc != null -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            Build.VERSION.SDK_INT >= 26 -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // Just below the status bar (owner's decision, 2 Nov 2026): never over the clock, the battery or the camera cutout. The
            // status bar's own height (it includes the cutout band on phones that have one). It may cover a sliver of an app's
            // title; the owner chose that over hiding the phone's own status.
            y = topInset(host)
        }
        val state = MutableTransitionState(false).apply { targetState = true }
        visible = state
        val simple = com.tekyerema.ui.theme.calmUi()
        val tier = GlassTiers.detect(ctx, calm = false)
        val panel = ComposeView(host)
        val life = IslandOwner()
        panel.setViewTreeLifecycleOwner(life)
        panel.setViewTreeSavedStateRegistryOwner(life)
        if (Build.VERSION.SDK_INT >= 28) panel.accessibilityPaneTitle = "Live"
        panel.setContent {
            TekyeremaTheme {
                CompositionLocalProvider(LocalGlass provides com.tekyerema.ui.theme.glassPalette()) {
                    val acts by LiveActivities.state.collectAsState()
                    // Room around the island for its soft shadow (the window wraps it).
                    Box(Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 22.dp)) {
                        AnimatedVisibility(
                            visibleState = state,
                            enter = if (simple) androidx.compose.animation.EnterTransition.None else slideInVertically(spring(0.78f, 190f)) { -it - 60 } + fadeIn(tween(160)) +
                                scaleIn(spring(0.78f, 190f), initialScale = 0.86f, transformOrigin = TransformOrigin(0.5f, 0f)),
                            exit = if (simple) androidx.compose.animation.ExitTransition.None else slideOutVertically(tween(220)) { -it - 60 } + fadeOut(tween(180)) +
                                scaleOut(tween(220), targetScale = 0.9f, transformOrigin = TransformOrigin(0.5f, 0f))
                        ) {
                            LiveIsland(acts, onAction = { id, a -> LiveActivities.act(id, a) }, tier = tier)
                        }
                    }
                }
            }
        }
        try {
            life.start()
            manager.addView(panel, lp)
            view = panel; wm = manager; owner = life; hostSvc = svc
        } catch (t: Throwable) { life.destroy(); visible = null; android.util.Log.w("Tekyerema", "[ISLAND] not shown: ${t.javaClass.simpleName}") }
    }

    private fun detach() {
        val s = visible ?: return
        s.targetState = false
        main.removeCallbacks(removeLater)
        main.postDelayed(removeLater, 320)   // after the island has risen out of sight
    }

    private fun removeNow() {
        val v = view ?: return
        try { wm?.removeView(v) } catch (_: Throwable) {}
        owner?.destroy()
        view = null; wm = null; owner = null; visible = null; hostSvc = null
    }

    private fun topInset(ctx: Context): Int {
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) ctx.resources.getDimensionPixelSize(id) else (24 * ctx.resources.displayMetrics.density).toInt()
    }
}

/** The island window's own lifecycle (Compose in a window no Activity owns). */
private class IslandOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    fun start() { saved.performRestore(null); registry.currentState = Lifecycle.State.RESUMED }
    fun destroy() { if (registry.currentState != Lifecycle.State.INITIALIZED) registry.currentState = Lifecycle.State.DESTROYED }
}
