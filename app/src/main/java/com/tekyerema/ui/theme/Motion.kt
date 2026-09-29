package com.tekyerema.ui.theme

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * Tɛkyerɛma's motion system (docs/agent/research/overnight_ui_motion.md).
 *
 * Springs, not durations: the damping ratios and stiffnesses are Material 3 Expressive's motion-physics tokens, so
 * everything moves with the same physical character. SPATIAL springs move things (position, size, scale) and may
 * overshoot a little; EFFECTS springs fade and recolour and never overshoot.
 *
 * Calm motion ([LocalCalmMotion]): on when the user turns on Settings › Accessibility › Reduce motion OR the phone's
 * own "Remove animations" (ANIMATOR_DURATION_SCALE = 0, WCAG 2.3.3). Calm never means "no feedback": spatial moves
 * become short cross-fades (opacity is not motion), and the living visual holds still in a distinct shape per state.
 */
object TkMotion {
    // Material 3 Expressive spring tokens (damping ratio / stiffness).
    fun <T> fastSpatial(): FiniteAnimationSpec<T> = spring(0.6f, 800f)
    fun <T> defaultSpatial(): FiniteAnimationSpec<T> = spring(0.8f, 380f)
    fun <T> slowSpatial(): FiniteAnimationSpec<T> = spring(0.8f, 200f)
    fun <T> fastEffects(): FiniteAnimationSpec<T> = spring(1f, 3800f)
    fun <T> defaultEffects(): FiniteAnimationSpec<T> = spring(1f, 1600f)
    fun <T> slowEffects(): FiniteAnimationSpec<T> = spring(1f, 800f)

    /** The release after a press: a touch bouncier than [fastSpatial], so a button "springs back". */
    fun <T> release(): FiniteAnimationSpec<T> = spring(0.45f, 600f)

    private fun offsetSpatial(): FiniteAnimationSpec<IntOffset> = spring(0.8f, 380f, IntOffset(1, 1))
    private fun sizeSpatial(): FiniteAnimationSpec<IntSize> = spring(0.8f, 380f, IntSize(1, 1))

    /** Calm: a quick cross-fade instead of any movement. */
    private fun calmSwap(): ContentTransform = fadeIn(tween(160)) togetherWith fadeOut(tween(100))

    /**
     * Top-level destinations (Home / History / Settings): Material's "fade through". The old page fades quickly;
     * the new one fades in and grows from 94 % on a spatial spring.
     */
    fun fadeThrough(calm: Boolean): ContentTransform =
        if (calm) calmSwap()
        else (fadeIn(tween(210, delayMillis = 70)) + scaleIn(spring(0.8f, 380f), initialScale = 0.94f)) togetherWith
            fadeOut(tween(90))

    /**
     * Going deeper (a settings page, the next onboarding step) or back: Material's "shared axis X". The pages slide a
     * short way — a fifth of the width, not all of it — so the eye follows without a big sweep.
     */
    fun sharedAxisX(forward: Boolean, calm: Boolean): ContentTransform {
        if (calm) return calmSwap()
        val dir = if (forward) 1 else -1
        val enter: EnterTransition = slideInHorizontally(offsetSpatial()) { w -> dir * w / 5 } + fadeIn(tween(200, delayMillis = 50))
        val exit: ExitTransition = slideOutHorizontally(offsetSpatial()) { w -> -dir * w / 5 } + fadeOut(tween(90))
        return ContentTransform(enter, exit, sizeTransform = SizeTransform(clip = false) { _, _ -> sizeSpatial() })
    }

    /** The assistant's line changing: the old line lifts away, the new one rises into place. */
    fun lineChange(calm: Boolean): ContentTransform {
        if (calm) return ContentTransform(fadeIn(tween(160)), fadeOut(tween(100)),
            sizeTransform = SizeTransform(clip = false) { _, _ -> snapSize() })
        val enter = slideInVertically(offsetSpatial()) { h -> h / 3 } + fadeIn(tween(220, delayMillis = 60))
        val exit = slideOutVertically(offsetSpatial()) { h -> -h / 4 } + fadeOut(tween(110))
        return ContentTransform(enter, exit, sizeTransform = SizeTransform(clip = false) { _, _ -> sizeSpatial() })
    }

    private fun snapSize(): FiniteAnimationSpec<IntSize> = tween(0)
}

/** Pure policy (unit-tested): the in-app setting OR the system's animator scale of 0. */
object MotionPolicy {
    fun calm(userReduceMotion: Boolean, systemAnimatorScale: Float): Boolean = userReduceMotion || systemAnimatorScale <= 0f
}

/** True when this screen should use calm motion. Provided by [TekyeremaTheme]. */
val LocalCalmMotion = compositionLocalOf { false }

/** The system animator duration scale ("Remove animations" sets it to 0). */
fun systemAnimatorScale(context: Context): Float = try {
    android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
} catch (_: Throwable) { 1f }

/** The system animator scale, kept current while the screen is up (the user may flip it in Quick Settings). */
@Composable
fun rememberSystemAnimatorScale(): Float {
    val context = LocalContext.current
    var scale by remember(context) { mutableStateOf(systemAnimatorScale(context)) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { scale = systemAnimatorScale(context) }
        }
        val uri = android.provider.Settings.Global.getUriFor(android.provider.Settings.Global.ANIMATOR_DURATION_SCALE)
        try { context.contentResolver.registerContentObserver(uri, false, observer) } catch (_: Throwable) {}
        onDispose { try { context.contentResolver.unregisterContentObserver(observer) } catch (_: Throwable) {} }
    }
    return scale
}

/**
 * Touch feedback through the system's own haptic vocabulary (Android haptics principles: predefined constants, not
 * one-shot buzzes; the system "Touch feedback" setting is honoured). CONFIRM/REJECT exist from Android 11; older
 * phones get the ordinary key tick.
 */
enum class TkHaptic { TAP, CONFIRM, REJECT }

fun View.tkHaptic(kind: TkHaptic) {
    val constant = when (kind) {
        TkHaptic.CONFIRM -> if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        TkHaptic.REJECT -> if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.VIRTUAL_KEY
        TkHaptic.TAP -> HapticFeedbackConstants.VIRTUAL_KEY
    }
    try { performHapticFeedback(constant) } catch (_: Throwable) {}
}
