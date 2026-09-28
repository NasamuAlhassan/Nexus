package com.tekyerema.ui

import com.tekyerema.accessibility.TekyeremaAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tɛkyerɛma's own screens getting out of the way. Owner (A20e, 2026-09-28): "I open the app, I want to do something…
 * immediately I finish the prompt, it should just close the app and start thinking." The screen used to stay up
 * showing "Thinking…" until the agent pushed it away with Back/Home — and it decided whether we were still in front
 * from window events, which the translucent voice screen never produces for the app under it, so it pressed Back and
 * Home up to eight times into the user's app while believing we were still showing.
 *
 * The two screens a request is made from (VoiceActivity, MainActivity) report their own visibility here
 * ([onShown]/[onHidden]) and step aside on request ([stepAside]: the voice screen finishes, the main screen moves to
 * the back), so [leave] can ask and then KNOW when they are gone.
 */
object OwnUi {
    private val shown = AtomicInteger(0)
    private val _stepAside = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Collected by the two request screens: get out of the way now. */
    val stepAside: SharedFlow<Unit> = _stepAside

    val isShowing: Boolean get() = shown.get() > 0

    fun onShown() { shown.incrementAndGet() }

    fun onHidden() {
        if (shown.decrementAndGet() <= 0) {
            shown.set(0)
            // The app under the translucent voice screen never announces itself; don't let "ours" linger as the
            // foreground the agent and the checks read.
            TekyeremaAccessibilityService.live?.forgetOwnForeground()
        }
    }

    /** Ask our screens to step aside without waiting (e.g. a fast-path launch that must land in front). */
    fun requestStepAside() { if (isShowing) _stepAside.tryEmit(Unit) }

    /** Ask our screens to step aside and wait until they have. True when none is showing any more. */
    suspend fun leave(timeoutMs: Long = 1_500): Boolean {
        if (!isShowing) return true
        _stepAside.tryEmit(Unit)
        return withTimeoutOrNull(timeoutMs) { while (isShowing) delay(50); true } ?: false
    }
}
