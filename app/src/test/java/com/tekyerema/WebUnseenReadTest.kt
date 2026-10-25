package com.tekyerema

import com.tekyerema.accessibility.AccessibilityController
import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.PerceptionRoute
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.agent.SendReport
import com.tekyerema.tools.ReadScreenTool
import com.tekyerema.tools.ToolContext
import com.tekyerema.verifier.Verify
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * P2b (25 Oct 2026): a browser whose page never reached the accessibility tree (Chrome's "Web View" with nothing inside)
 * must not be read back as if the toolbar were the page — a login form can be behind it, and it is neither read nor
 * recognised. The honest answer says it cannot confirm the page; the launch check never takes the home screen as proof.
 */
class WebUnseenReadTest {
    private fun el(id: Int, label: String) = UiElement(id, "TextView", label, Box(0, id * 100, 500, id * 100 + 80))
    private fun chromeToolbarOnly(missing: Boolean) = UiSnapshot(
        "com.android.chrome", null,
        listOf(el(0, "Web View"), el(1, "Open the home page"), el(2, "New tab"), el(3, "More options")),
        0L, PerceptionRoute("tree", "test"), appName = "Chrome", webContentMissing = missing
    )

    private fun controller(s: UiSnapshot): AccessibilityController =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AccessibilityController::class.java)) { _, m, _ ->
            when (m.name) { "buildSnapshot" -> s; "getReady" -> true; else -> null }
        } as AccessibilityController

    private fun read(s: UiSnapshot) = runBlocking {
        ReadScreenTool.execute(JSONObject(), ToolContext(android.content.ContextWrapper(null), controller(s), com.tekyerema.notifications.NotificationStore))
    }

    @Test fun a_bare_web_page_is_not_read_back_as_the_toolbar() {
        val r = read(chromeToolbarOnly(missing = true))
        assertEquals(ReadScreenTool.WEB_UNSEEN, r.message)
        assertTrue(r.data?.optBoolean("web_unseen") == true)
        assertFalse(r.message.contains("New tab"))
        assertFalse(r.message.contains("Open the home page"))
    }

    @Test fun the_honest_line_carries_its_own_doubt_so_no_second_one_is_added() {
        assertEquals(ReadScreenTool.WEB_UNSEEN, SendReport.unverified(ReadScreenTool.WEB_UNSEEN))
    }

    @Test fun a_page_that_did_reach_the_tree_is_read_as_before() {
        val r = read(chromeToolbarOnly(missing = false))
        assertTrue(r.message, r.message.contains("New tab"))
        assertTrue(r.data?.optBoolean("web_unseen") != true)
    }

    // --- open-app: the home screen is never proof that the app opened ---
    private fun fg(vararg seq: String?): AccessibilityController {
        var i = 0
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AccessibilityController::class.java)) { _, m, _ ->
            when (m.name) { "foregroundPackage", "activeWindowPackage" -> seq[minOf(i++, seq.size - 1)]; else -> null }
        } as AccessibilityController
    }
    private val launcher = "com.google.android.apps.nexuslauncher"

    @Test fun the_launcher_in_front_does_not_prove_a_launch() {
        // Settings was in front, the app never came up, the home screen did: three polls of "something else".
        val without = runBlocking { Verify.pollLaunched(fg(launcher), "com.google.android.apps.youtube.music", "com.android.settings", attempts = 10, intervalMs = 1) }
        assertTrue("old behaviour: the launcher counted", without.first)
        val with = runBlocking { Verify.pollLaunched(fg(launcher), "com.google.android.apps.youtube.music", "com.android.settings", attempts = 10, intervalMs = 1, notEvidence = setOf(launcher)) }
        assertFalse(with.first)
    }

    @Test fun the_app_itself_still_proves_it_even_when_the_launcher_came_first() {
        val r = runBlocking { Verify.pollLaunched(fg(launcher, launcher, "com.google.android.apps.youtube.music"), "com.google.android.apps.youtube.music", "com.android.settings", attempts = 10, intervalMs = 1, notEvidence = setOf(launcher)) }
        assertTrue(r.first)
    }

    @Test fun a_helper_screen_of_the_app_still_counts_with_the_launcher_excluded() {
        val r = runBlocking { Verify.pollLaunched(fg("com.google.android.gms"), "com.google.android.apps.youtube.music", "com.android.settings", attempts = 10, intervalMs = 1, notEvidence = setOf(launcher)) }
        assertTrue(r.first)
    }
}
