package com.tekyerema

import android.graphics.Bitmap
import com.tekyerema.accessibility.AccessibilityController
import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.agent.LocalFastRouter
import com.tekyerema.agent.ScreenReadCursor
import com.tekyerema.agent.ScreenReadout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Black-box audit on the A20e, 2026-10-01 — the fast-path fixes that are not classifier rules:
 *  - the notification shade was left open after every Quick Settings action (one Back closes only one level on One UI);
 *  - "continue" after a one-part screen reading was taken for media Play.
 */
class AuditFastPathTest {
    /** A phone whose shade needs [levels] Back presses to close, over [app]. Back with no shade open goes back IN the app. */
    private class ShadePhone(var levels: Int, private val app: String = "com.whatsapp") : AccessibilityController {
        val actions = ArrayList<String>()
        var backsInsideTheApp = 0
        override val ready = true
        override fun activeWindowPackage(): String? = if (levels > 0) AccessibilityController.SYSTEM_UI else app
        override fun globalAction(action: String): Boolean {
            actions.add(action)
            if (action == "back") { if (levels > 0) levels-- else backsInsideTheApp++ }
            return true
        }
        override fun foregroundPackage(): String? = app
        override fun priorForegroundPackage(): String? = null
        override fun screenSize(): Pair<Int, Int> = 720 to 1560
        override suspend fun waitForSettle(timeoutMs: Long) {}
        override suspend fun buildSnapshot() = UiSnapshot(activeWindowPackage(), null, emptyList(), 0L)
        override suspend fun captureScreenshot(): Bitmap? = null
        override suspend fun clickElement(id: Int) = false
        override suspend fun longClickElement(id: Int) = false
        override suspend fun setText(id: Int, text: String, submit: Boolean) = false
        override suspend fun scrollElement(id: Int, direction: String) = false
        override suspend fun focusElement(id: Int) = false
        override suspend fun setRangeValue(id: Int, value: Float) = false
        override fun boundsOf(id: Int): Box? = null
        override fun lastScreenshotFailure(): String? = null
        override suspend fun tap(x: Int, y: Int, durationMs: Long) = false
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = false
        override suspend fun drag(points: List<Pair<Int, Int>>, durationMs: Long) = false
        override suspend fun pinch(cx: Int, cy: Int, startGap: Int, endGap: Int, durationMs: Long) = false
        override fun adjustVolume(direction: String) = false
    }

    @Test fun the_shade_is_closed_all_the_way_on_a_phone_where_one_back_closes_one_level() {
        // One UI: Quick Settings → (Back) → the notification list → (Back) → the app.
        val samsung = ShadePhone(levels = 2)
        assertTrue(runBlocking { samsung.closeShade() })
        assertEquals(listOf("back", "back"), samsung.actions)
        assertEquals(0, samsung.levels)
        assertEquals("never a Back inside the app underneath", 0, samsung.backsInsideTheApp)
    }

    @Test fun one_level_takes_one_back_and_no_shade_takes_none() {
        val pixel = ShadePhone(levels = 1)
        assertTrue(runBlocking { pixel.closeShade() })
        assertEquals(listOf("back"), pixel.actions)

        val none = ShadePhone(levels = 0)
        assertTrue(runBlocking { none.closeShade() })
        assertTrue("no shade in front: nothing is pressed", none.actions.isEmpty())
    }

    @Test fun a_shade_that_will_not_close_is_reported_not_claimed() {
        val stuck = ShadePhone(levels = 9)
        assertFalse(runBlocking { stuck.closeShade() })
        assertEquals("three tries, then stop", 3, stuck.actions.size)
    }

    @Test fun reading_on_down_a_screen_keeps_the_shape_of_a_first_reading() {
        val items = listOf("Display", "Brightness • Eye comfort shield • Navigation bar", "Wallpaper.", "Themes", "Home screen", "Lock screen")
        val parts = LocalFastRouter.readingParts(items, 60)
        assertTrue("several parts: $parts", parts.size >= 2)
        // Every part but the last invites "continue"; the last does not.
        for (p in parts.dropLast(1)) assertTrue(p, p.endsWith(ScreenReadout.MORE))
        assertFalse(parts.last().endsWith(ScreenReadout.MORE))
        // Every item is read once, as a sentence, in order.
        val all = parts.joinToString(" ") { it.removeSuffix(ScreenReadout.MORE) }
        assertEquals("Display. Brightness • Eye comfort shield • Navigation bar. Wallpaper. Themes. Home screen. Lock screen.", all)
        // And the cursor carries the rest, so the NEXT "continue" goes on reading (the agent's reader takes it from there).
        ScreenReadCursor.clear()
        assertEquals(parts.first(), ScreenReadCursor.start(parts, "com.android.settings|null"))
        assertTrue(ScreenReadCursor.pending)
        assertEquals(parts[1], ScreenReadCursor.next("com.android.settings|null"))
        ScreenReadCursor.clear()
    }

    @Test fun a_short_remainder_is_one_part_with_no_invitation() {
        assertEquals(listOf("About phone. Software update."), LocalFastRouter.readingParts(listOf("About phone", " Software update "), 480))
        assertTrue(LocalFastRouter.readingParts(listOf("", "  "), 480).isEmpty())
    }
}
