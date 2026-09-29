package com.tekyerema

import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.agent.Progress.Verdict
import com.tekyerema.agent.recovery.DialogDetector
import com.tekyerema.agent.recovery.OutcomeClass
import com.tekyerema.agent.recovery.OutcomeClassifier
import com.tekyerema.verifier.Expectation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Workstream B — outcome classes: each kind of failure is told apart so it gets its own fix. */
class OutcomeClassTest {

    private fun el(id: Int, label: String, role: String = "TextView", clickable: Boolean = true, editable: Boolean = false) =
        UiElement(id, role, label, Box(0, id * 100, 100, id * 100 + 90), clickable = clickable, editable = editable)

    private fun screen(pkg: String, vararg labels: String, window: String? = null) =
        UiSnapshot(pkg, null, labels.mapIndexed { i, l -> el(i, l) }, 0L, windowType = window)

    private val camera = screen("com.sec.android.app.camera", "Shutter", "Gallery", "Switch camera", "Mode", "Photo", "Video",
        "Settings", "Flash", "Timer", "Ratio", "Zoom", "More")

    private fun step(tool: String = "tap", verdict: Verdict = Verdict.UNVERIFIED, success: Boolean = true, before: UiSnapshot = camera,
                     after: UiSnapshot? = null, claim: Expectation? = Expectation.ElementPresent("Saved"), message: String = "",
                     recoverable: Boolean = true, needsUser: Boolean = false, targetMissing: Boolean = false) =
        OutcomeClassifier.Step(tool, verdict, success, recoverable, needsUser, message, targetMissing, claim, before, after,
            launcherPkg = "com.sec.android.app.launcher")

    @Test fun verdicts_map_to_classes() {
        assertEquals(OutcomeClass.OK, OutcomeClassifier.classify(step(verdict = Verdict.VERIFIED, after = camera)))
        assertEquals(OutcomeClass.OBSERVED, OutcomeClassifier.classify(step(tool = "read_state", verdict = Verdict.OBSERVED)))
        assertEquals(OutcomeClass.NO_EFFECT, OutcomeClassifier.classify(step(verdict = Verdict.NO_CHANGE, after = camera)))
        assertEquals(OutcomeClass.CLAIM_NOT_MET, OutcomeClassifier.classify(step(after = screen("com.sec.android.app.camera", "Shutter", "Portrait"))))
        assertEquals(OutcomeClass.PARTIAL, OutcomeClassifier.classify(step(verdict = Verdict.PARTIAL, after = screen("com.sec.android.app.camera", "A", "B", "C"))))
    }

    @Test fun tool_failures_are_split_into_not_found_blocked_and_failed() {
        assertEquals(OutcomeClass.NOT_FOUND, OutcomeClassifier.classify(step(verdict = Verdict.FAILED, success = false,
            message = "couldn't find an item labelled 'Wi-Fi calling' anywhere in this list (scrolled to its end)")))
        assertEquals(OutcomeClass.NOT_FOUND, OutcomeClassifier.classify(step(verdict = Verdict.FAILED, success = false, targetMissing = true)))
        assertEquals(OutcomeClass.BLOCKED, OutcomeClassifier.classify(step(verdict = Verdict.FAILED, success = false,
            message = "Tɛkyerɛma doesn't have permission to read your SMS", recoverable = false)))
        assertEquals(OutcomeClass.BLOCKED, OutcomeClassifier.classify(step(tool = "open_app", verdict = Verdict.FAILED, success = false,
            message = "No app called 'Jiji' is installed", needsUser = true)))
        assertEquals(OutcomeClass.FAILED, OutcomeClassifier.classify(step(verdict = Verdict.FAILED, success = false, message = "Could not tap element 4.")))
    }

    @Test fun an_unexpected_app_is_a_wrong_page_but_an_intended_switch_is_not() {
        val home = screen("com.sec.android.app.launcher", "Phone", "Messages", "Chrome", "Play Store", "Camera", "Gallery",
            "Settings", "Clock", "Calendar", "Contacts", "Galaxy Store", "YouTube", "Maps")
        val wrong = step(after = home)
        assertEquals(OutcomeClass.WRONG_PAGE, OutcomeClassifier.classify(wrong))
        // open_app is meant to switch apps; a claim naming the new app says it was intended; the shade isn't an app.
        assertEquals(OutcomeClass.CLAIM_NOT_MET, OutcomeClassifier.classify(step(tool = "open_app", after = home)))
        assertFalse(OutcomeClassifier.unexpectedApp(step(after = home, claim = Expectation.ForegroundIs("launcher"))))
        assertFalse(OutcomeClassifier.unexpectedApp(step(after = screen("com.android.systemui", "Wi-Fi", "Bluetooth"))))
        assertFalse(OutcomeClassifier.unexpectedApp(step(tool = "key_event", after = home)))
    }

    @Test fun a_carrier_popup_over_the_camera_is_a_dialog_not_a_wrong_page() {
        // Seen on the A20e: after a send, a carrier "your SMS cost…" dialog covered the camera.
        val popup = UiSnapshot("com.android.phone", null, listOf(
            el(0, "Your last SMS cost GHS 0.05. Balance GHS 2.10", clickable = false), el(1, "OK", role = "Button")), 0L)
        assertEquals(OutcomeClass.DIALOG, OutcomeClassifier.classify(step(after = popup)))
        val d = DialogDetector.appeared(camera, popup)!!
        assertEquals("OK", d.dismiss!!.label)
        assertTrue(d.text!!.startsWith("Your last SMS cost"))
        assertFalse(d.permission)
    }

    @Test fun an_in_app_dialog_that_looks_like_new_content_is_still_a_dialog() {
        val before = screen("com.whatsapp", "Chats", "Ama", "Kofi", "Status", "Calls")
        val rate = UiSnapshot("com.whatsapp", null, listOf(el(0, "Enjoying WhatsApp?", clickable = false),
            el(1, "Not now", role = "Button"), el(2, "Rate", role = "Button")), 0L)
        assertEquals(OutcomeClass.DIALOG, OutcomeClassifier.classify(step(verdict = Verdict.PARTIAL, before = before, after = rate)))
        assertEquals("Not now", DialogDetector.appeared(before, rate)!!.dismiss!!.label)
    }

    @Test fun the_dismiss_choice_never_acts() {
        fun b(vararg labels: String) = labels.mapIndexed { i, l -> el(i, l, role = "Button") }
        assertEquals("OK", DialogDetector.pickDismiss(b("OK"))!!.label)                 // a single-button info popup
        assertEquals("Cancel", DialogDetector.pickDismiss(b("Cancel", "OK"))!!.label)   // "OK" may confirm something
        assertEquals("Cancel", DialogDetector.pickDismiss(b("Delete", "Cancel"))!!.label)
        assertEquals("Not now", DialogDetector.pickDismiss(b("Update", "Not now"))!!.label)
        assertNull(DialogDetector.pickDismiss(b("Send", "Continue")))
        assertNull(DialogDetector.pickDismiss(b("Yes", "OK")))
    }

    @Test fun permission_prompts_and_forms_are_not_auto_dismissed() {
        val perm = UiSnapshot("com.google.android.permissioncontroller", null, listOf(
            el(0, "Allow Camera to take pictures and record video?", clickable = false),
            el(1, "While using the app", role = "Button"), el(2, "Only this time", role = "Button"), el(3, "Don't allow", role = "Button")), 0L)
        val d = DialogDetector.appeared(camera, perm)!!
        assertTrue(d.permission)
        assertNull(d.dismiss)
        val form = UiSnapshot("com.google.android.youtube", null, listOf(el(0, "Name", editable = true), el(1, "Cancel", role = "Button"),
            el(2, "Create", role = "Button")), 0L)
        assertNull(DialogDetector.appeared(screen("com.google.android.youtube", "Library"), form))
    }

    @Test fun a_dialog_the_step_claimed_is_not_an_interruption() {
        val before = screen("com.android.settings", "Storage", "Clear data", "Clear cache")
        val confirm = UiSnapshot("com.android.settings", null, listOf(el(0, "Clear app data?", clickable = false),
            el(1, "Cancel", role = "Button"), el(2, "OK", role = "Button")), 0L)
        assertNull(DialogDetector.appeared(before, confirm, Expectation.ElementPresent("Clear app data?")))
        assertNotNull(DialogDetector.appeared(before, confirm, Expectation.ElementPresent("Storage cleared")))
        // Buttons that were already on screen before the step aren't a new popup.
        assertNull(DialogDetector.appeared(confirm, confirm))
    }
}
