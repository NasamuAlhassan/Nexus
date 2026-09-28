package com.tekyerema

import com.tekyerema.accessibility.AccessibilityController
import com.tekyerema.verifier.Verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** Did an app launch take effect? (Verify.pollLaunched — used by the fast path and the agent's open_app.) */
class LaunchVerifyTest {
    /** A controller whose foreground package steps through [seq] (the last value repeats). */
    private fun fg(vararg seq: String?): AccessibilityController {
        var i = 0
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AccessibilityController::class.java)) { _, m, _ ->
            when (m.name) {
                "foregroundPackage", "activeWindowPackage" -> seq[minOf(i++, seq.size - 1)]
                else -> null
            }
        } as AccessibilityController
    }

    private fun poll(c: AccessibilityController, prev: String?) =
        runBlocking { Verify.pollLaunched(c, "com.android.settings", prev, attempts = 10, intervalMs = 1) }

    @Test fun target_in_front_is_launched() {
        val (ok, f) = poll(fg("com.google.android.apps.nexuslauncher", "com.android.settings"), "com.google.android.apps.nexuslauncher")
        assertTrue(ok); assertEquals("com.android.settings", f)
    }

    @Test fun the_apps_task_showing_a_helper_package_is_launched() {
        // Settings' task resumed on its search page (another package) — held steady → the launch took effect.
        val (ok, f) = poll(fg("com.google.android.settings.intelligence"), "com.google.android.apps.nexuslauncher")
        assertTrue(ok); assertEquals("com.google.android.settings.intelligence", f)
    }

    @Test fun nothing_changed_is_not_launched() {
        assertFalse(poll(fg("com.android.chrome"), "com.android.chrome").first)
    }

    @Test fun unknown_prior_screen_needs_the_target_itself() {
        assertFalse(poll(fg("com.android.chrome"), null).first)
        assertTrue(poll(fg(null, "com.android.settings"), null).first)
    }

    @Test fun a_passing_flicker_is_not_enough() {
        // one poll of something else, then back to the prior app → not a launch
        assertFalse(poll(fg("com.android.systemui", "com.android.chrome"), "com.android.chrome").first)
    }
}
