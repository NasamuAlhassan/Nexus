package com.tekyerema

import com.tekyerema.core.config.AppConfig
import com.tekyerema.tools.ShareScreenshotTool
import com.tekyerema.tools.ToolManifest
import com.tekyerema.tools.defaultTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Owner, 26 Oct 2026: "take a selfie and send to someone on Telegram" only opened Telegram — no tool could attach a photo. */
class SendPhotoToolTest {
    private fun loadConfig() {
        val dir = listOf(File("src/main/assets/config"), File("app/src/main/assets/config")).first { it.isDirectory }
        AppConfig.loadFrom(dir.listFiles { f -> f.name.endsWith(".json") }!!.map { "config/${it.name}" to it.readText() })
    }

    @Test fun send_photo_loads_for_a_photo_to_send_and_never_runs_unattended() {
        loadConfig()
        val all = defaultTools().map { it.name }
        fun sel(goal: String) = com.tekyerema.agent.ToolSelector.select(goal, emptySet(), emptySet(), all)
        assertTrue("send_photo" in sel("take a selfie and send it to Ama on Telegram"))
        assertTrue("send_photo" in sel("send my wife a photo of this on WhatsApp"))
        assertFalse("send_photo" in sel("open chrome"))
        // both are offered; the descriptions tell the brain which is for what (it chooses, no keyword rule)
        assertTrue("take_photo" in sel("take a selfie and send it to Prince on telegram"))
        val tool = defaultTools().single { it.name == "send_photo" }
        assertFalse(ToolManifest.mayCall(tool, ToolManifest.CALLER_TRIGGER))
        assertTrue(ToolManifest.mayCall(tool, ToolManifest.CALLER_PLANNER))
    }

    @Test fun take_photo_points_the_planner_to_send_photo() {
        assertTrue(defaultTools().single { it.name == "take_photo" }.description.contains("send_photo"))
    }

    /** Infinix traces 12:51 and 12:52: "… on telegram" opened Telegram as step one, and the selfie was never taken. */
    @Test fun the_app_a_capture_goal_sends_to_is_not_where_it_starts() {
        fun c(g: String) = com.tekyerema.agent.AppMention.candidates(g)
        // several actions: the order is the planner's, whatever the actions are (no photo vocabulary involved)
        assertTrue(c("Take a selfie and send it to Prince on telegram").isEmpty())
        assertTrue(c("take a photo of this and send it to my wife on whatsapp").isEmpty())
        assertTrue(c("find the receipt then email it to Ama using gmail").isEmpty())
        assertTrue(c("read my last message, then reply on telegram").isEmpty())
        // unchanged: one action in an app, and an explicit "open X and …"
        assertEquals(listOf("telegram"), c("send Prince a message on telegram"))
        assertEquals(listOf("youtube"), c("play highlife music on YouTube"))
        assertEquals(listOf("camera"), c("open the camera and take a selfie"))
    }

    /** Infinix trace 17:45: Telegram's "Do you want to send this message to Prince?" box gave a bare read-back, the gate
     *  refused five Send taps and the task ended "I cannot complete sending". It now asks, naming who and what. */
    @Test fun a_shared_photos_send_box_is_read_back_with_who_and_what() {
        fun el(id: Int, label: String, clickable: Boolean = false) = com.tekyerema.accessibility.UiElement(
            id, "TextView", label, com.tekyerema.accessibility.Box(0, 0, 10, 10), clickable = clickable)
        val snap = com.tekyerema.accessibility.UiSnapshot(
            "org.telegram.messenger", null,
            listOf(el(1, "Send message"), el(2, "Do you want to send this message to Prince?"), el(3, "Cancel", true), el(4, "Send", true)),
            0L, com.tekyerema.accessibility.PerceptionRoute("tree", "test"), appName = "Telegram")
        val sem = com.tekyerema.policy.ActionSemantics
        // what was handed over comes from the task's own history, not from the app's wording
        val history = listOf("take_photo({camera=front}) → fail [a11y]; verified=false; x",
            "send_photo({app=Telegram, camera=front}) → success [intent]; verified=true; I took the selfie and opened Telegram")
        assertEquals("photo", sem.handedOver(history))
        assertEquals(null, sem.handedOver(history.take(1)))
        assertEquals("screenshot", sem.handedOver(listOf("share_screenshot({app=Gmail}) → success [intent]; verified=true; ok")))
        assertEquals("Send this photo to Prince on Telegram?", sem.confirmation("tap", snap.elements[3], null, snap, handedOver = "photo"))
        // another app, other words: the same read-back
        val other = com.tekyerema.accessibility.UiSnapshot("com.example.mail", null,
            listOf(el(1, "Share"), el(2, "Send attachment to ama@example.com?"), el(4, "Send", true)),
            0L, com.tekyerema.accessibility.PerceptionRoute("tree", "test"), appName = "Mail")
        assertEquals("Send this screenshot to ama@example.com on Mail?", sem.confirmation("tap", other.elements[2], null, other, handedOver = "screenshot"))
        // nothing handed over earlier in the task: the read-back never claims a photo
        assertFalse(sem.confirmation("tap", snap.elements[3], null, snap)!!.contains("photo"))
    }

    /** Infinix trace 17:45: "Tap Shutter in Camera?" was asked for a request that said "take a selfie". */
    @Test fun the_shutter_is_not_asked_when_the_user_asked_for_the_photo() {
        val ask = com.tekyerema.policy.TargetSemantics::photoShutterAskedFor
        assertTrue(ask("Shutter", "take a selfie and send it to Prince on telegram"))
        assertTrue(ask("Take photo", "snap a picture of this"))
        // a goal that doesn't ask for a picture, a video record button, or another control: still asked
        assertFalse(ask("Shutter", "open the camera"))
        assertFalse(ask("Shutter", "what's on my screen"))
        assertFalse(ask("Record", "take a selfie"))
        assertFalse(ask("Send", "take a selfie and send it to Prince"))
        assertFalse(ask(null, "take a selfie"))
    }

    /** Photos and screenshots share cache/exports: each prunes only its own kind. */
    @Test fun pruning_photos_leaves_screenshots_alone() {
        val dir = Files.createTempDirectory("exports").toFile()
        listOf("photo-1.jpg", "photo-2.jpg", "photo-3.jpg", "screenshot-1.jpg").forEachIndexed { i, n ->
            File(dir, n).apply { writeText("x"); setLastModified(1_000L * (i + 1)) }
        }
        assertEquals(2, ShareScreenshotTool.prune(dir, 1, prefix = "photo-"))
        assertEquals(setOf("photo-3.jpg", "screenshot-1.jpg"), dir.list()!!.toSet())
        dir.deleteRecursively()
    }
}
