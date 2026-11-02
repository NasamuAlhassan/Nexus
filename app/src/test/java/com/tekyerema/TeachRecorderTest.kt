package com.tekyerema

import com.tekyerema.teach.RawEvent
import com.tekyerema.teach.StepKind
import com.tekyerema.teach.StopReason
import com.tekyerema.teach.TeachRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Teach mode: the user's own taps and typing become semantic steps — and anything private ends the recording. */
class TeachRecorderTest {
    private val app = "com.mtn.momo"

    private fun click(label: String?, t: Long, viewId: String? = null, pkg: String = app, type: RawEvent.Type = RawEvent.Type.CLICK) =
        RawEvent(type, pkg, t, appName = "MyMTN", screenTitle = "Home", role = "Button", label = label, viewId = viewId, launchable = true)

    private fun typed(text: String, t: Long, hint: String? = "Search", viewId: String? = "$app:id/search", pkg: String = app, password: Boolean = false) =
        RawEvent(RawEvent.Type.TEXT_CHANGED, pkg, t, appName = "MyMTN", role = "EditText", hint = hint, viewId = viewId,
            editable = true, password = password, text = text, launchable = true)

    private fun rec(): TeachRecorder = TeachRecorder().also { it.start(0L) }

    @Test fun a_click_becomes_open_app_then_a_semantic_step() {
        val r = rec()
        assertTrue(r.onEvent(click("Data", 1000L, "$app:id/data_tile")) is TeachRecorder.Result.Recorded)
        val steps = r.steps()
        assertEquals(listOf(StepKind.OPEN_APP, StepKind.CLICK), steps.map { it.kind })
        val c = steps[1]
        assertEquals("Data", c.label); assertEquals("$app:id/data_tile", c.viewId); assertEquals("Button", c.role)
        assertEquals(app, c.pkg); assertEquals("MyMTN", c.appName); assertEquals("Home", c.screenTitle)
    }

    @Test fun a_system_dialog_tap_is_never_a_step() {
        // Emulator, 2 Nov 2026: tapping "Wait" on "System UI isn't responding" became step 1 of a taught skill.
        val r = rec()
        assertEquals(TeachRecorder.Result.Ignored, r.onEvent(click("Wait", 500L, pkg = "android")))
        assertEquals(TeachRecorder.Result.Ignored, r.onEvent(click("Allow", 700L, pkg = "com.google.android.permissioncontroller")))
        assertTrue(r.onEvent(click("Data", 1000L, "$app:id/data_tile")) is TeachRecorder.Result.Recorded)
        assertTrue(r.steps().none { it.label == "Wait" || it.label == "Allow" })
    }

    @Test fun the_same_press_reported_twice_is_one_step() {
        val r = rec()
        r.onEvent(click("Data", 1000L)); r.onEvent(click("Data", 1100L))
        assertEquals(2, r.steps().size)   // open + one click
        r.onEvent(click("Data", 2000L))   // a real second press, later
        assertEquals(3, r.steps().size)
    }

    @Test fun keystrokes_make_one_type_step_and_the_words_are_not_in_it() {
        val r = rec()
        r.onEvent(typed("d", 1000L)); r.onEvent(typed("da", 1100L)); r.onEvent(typed("data", 1200L))
        val steps = r.steps()
        assertEquals(listOf(StepKind.OPEN_APP, StepKind.TYPE), steps.map { it.kind })
        val t = steps[1]
        assertEquals(4, t.textLength); assertNull(t.text); assertEquals("Search", t.hint)
        // The words are only held in memory, for the privacy question.
        assertEquals("data", r.drafts().last().typed)
    }

    @Test fun tapping_into_a_box_and_typing_is_one_step() {
        val r = rec()
        r.onEvent(click(null, 1000L, "$app:id/search").copy(editable = true, hint = "Search"))
        r.onEvent(typed("x", 1500L))
        assertEquals(listOf(StepKind.OPEN_APP, StepKind.TYPE), r.steps().map { it.kind })
    }

    @Test fun a_boxes_own_text_is_never_its_name() {
        val r = rec()
        r.onEvent(typed("secret words", 1000L, hint = null, viewId = null).copy(label = "secret words"))
        assertNull(r.steps().last().label)
    }

    @Test fun a_password_field_stops_the_recording_and_keeps_no_text() {
        val r = rec()
        r.onEvent(click("Data", 1000L))
        val res = r.onEvent(typed("hunter2", 2000L, hint = "Password", password = true))
        assertEquals(TeachRecorder.Result.Stopped(StopReason.PRIVATE), res)
        assertFalse(r.recording)
        assertTrue(r.steps().none { it.kind == StepKind.TYPE })
        assertTrue(r.drafts().all { it.typed == null })
        // Nothing more is taken after the stop.
        assertEquals(TeachRecorder.Result.Ignored, r.onEvent(click("Next", 3000L)))
    }

    @Test fun a_box_that_names_a_pin_or_code_stops_the_recording() {
        for ((hint, id) in listOf("Enter PIN" to null, "Enter your OTP" to null, null to "com.bank:id/otp_input", "Passcode" to null)) {
            val r = rec()
            val res = r.onEvent(typed("1234", 1000L, hint = hint, viewId = id))
            assertEquals("$hint $id", TeachRecorder.Result.Stopped(StopReason.PRIVATE), res)
            assertTrue(r.steps().isEmpty())
        }
    }

    @Test fun a_search_box_is_not_mistaken_for_a_credential() {
        val r = rec()
        assertTrue(r.onEvent(typed("passwords", 1000L, hint = "Search")) is TeachRecorder.Result.Recorded)
    }

    @Test fun the_phones_own_dialogs_stop_the_recording_without_being_read() {
        for (pkg in listOf("com.android.phone", "com.samsung.android.incallui", "com.google.android.dialer")) {
            val r = rec()
            r.onEvent(click("Data", 1000L))
            assertEquals(TeachRecorder.Result.Stopped(StopReason.PHONE_DIALOG), r.onEvent(typed("1234", 2000L, pkg = pkg, hint = null, viewId = null)))
            assertEquals(2, r.steps().size)
        }
    }

    @Test fun a_secure_step_or_a_denied_app_stops_the_recording() {
        val r = rec()
        assertEquals(TeachRecorder.Result.Stopped(StopReason.PRIVATE), r.onEvent(click("Data", 1000L), secureNow = true))
        val r2 = rec()
        assertEquals(TeachRecorder.Result.Stopped(StopReason.DENIED_APP), r2.onEvent(click("Data", 1000L).copy(denied = true)))
        assertTrue(r2.steps().isEmpty())
    }

    @Test fun our_own_screens_the_keyboard_and_the_home_screen_are_not_steps() {
        val r = rec()
        assertEquals(TeachRecorder.Result.Ignored, r.onEvent(click("Talk", 1000L, pkg = "com.tekyerema")))
        assertEquals(TeachRecorder.Result.Ignored, r.onEvent(click("q", 1100L).copy(isIme = true)))
        assertEquals(TeachRecorder.Result.Ignored, r.onEvent(click("WhatsApp", 1200L).copy(isShell = true)))
        assertTrue(r.steps().isEmpty())
    }

    @Test fun an_app_coming_to_the_front_is_an_open_app_step_once() {
        val r = rec()
        val w = RawEvent(RawEvent.Type.WINDOW_CHANGED, app, 1000L, appName = "MyMTN", launchable = true)
        assertTrue(r.onEvent(w) is TeachRecorder.Result.Recorded)
        assertEquals(TeachRecorder.Result.Ignored, r.onEvent(w.copy(timeMs = 1500L)))
        // a dialog from a package with no launcher icon is not "open that app"
        assertEquals(TeachRecorder.Result.Ignored, r.onEvent(w.copy(pkg = "com.android.permissioncontroller", launchable = false, timeMs = 2000L)))
        assertEquals(listOf(StepKind.OPEN_APP), r.steps().map { it.kind })
    }

    @Test fun the_screen_moving_on_right_after_typing_marks_the_field_submitted() {
        val r = rec()
        r.onEvent(typed("data", 1000L))
        r.onEvent(RawEvent(RawEvent.Type.WINDOW_CHANGED, app, 2000L, launchable = true))
        assertTrue(r.steps().last().submit)
    }

    @Test fun scrolls_are_merged_and_optional_and_a_first_look_around_is_ignored() {
        val r = rec()
        val s = RawEvent(RawEvent.Type.SCROLLED, app, 500L, scrollDy = 30)
        assertEquals(TeachRecorder.Result.Ignored, r.onEvent(s))           // nothing done in the app yet
        r.onEvent(click("Data", 1000L))
        r.onEvent(s.copy(timeMs = 1500L)); r.onEvent(s.copy(timeMs = 1600L)); r.onEvent(s.copy(timeMs = 1700L))
        val steps = r.steps()
        assertEquals(listOf(StepKind.OPEN_APP, StepKind.CLICK, StepKind.SCROLL), steps.map { it.kind })
        assertEquals("down", steps.last().direction); assertTrue(steps.last().optional)
        r.onEvent(s.copy(timeMs = 1800L, scrollDy = -30))
        assertEquals("up", r.steps().last().direction)
    }

    @Test fun steps_that_would_send_or_pay_are_flagged() {
        val r = rec()
        r.onEvent(click("Data", 1000L)); r.onEvent(click("Send", 2000L))
        val steps = r.steps()
        assertFalse(steps[1].sensitive)
        assertTrue(steps[2].sensitive)
    }

    @Test fun private_screen_found_afterwards_drops_the_steps_done_on_it() {
        val r = rec()
        r.onEvent(click("Send money", 1000L)); r.onEvent(click("5", 5000L)); r.onEvent(click("7", 5600L))
        r.stopForPrivacy(StopReason.PRIVATE, sinceMs = 5000L)
        assertFalse(r.recording)
        assertEquals(listOf("Send money"), r.steps().mapNotNull { it.label })
    }

    @Test fun a_typed_step_before_a_private_screen_loses_its_held_text() {
        val r = rec()
        r.onEvent(typed("1234", 1000L, hint = "Amount"))
        r.stopForPrivacy(StopReason.PRIVATE, sinceMs = 9000L)
        assertTrue(r.drafts().all { it.typed == null })
    }

    @Test fun too_many_steps_and_too_long_a_recording_stop_it() {
        val r = TeachRecorder(maxSteps = 3).also { it.start(0L) }
        r.onEvent(click("A", 1000L)); r.onEvent(click("B", 2000L))     // open + A + B reaches the cap
        assertEquals(TeachRecorder.Result.Stopped(StopReason.TOO_LONG), r.onEvent(click("C", 3000L)))
        val r2 = TeachRecorder(maxMs = 60_000L).also { it.start(0L) }
        assertEquals(TeachRecorder.Result.Stopped(StopReason.TIMEOUT), r2.onEvent(click("A", 61_000L)))
    }

    @Test fun nothing_is_recorded_when_it_is_off_and_discard_forgets_everything() {
        val r = TeachRecorder()
        assertEquals(TeachRecorder.Result.Ignored, r.onEvent(click("Data", 1000L)))
        r.start(0L); r.onEvent(typed("abc", 1000L)); r.discard()
        assertTrue(r.steps().isEmpty()); assertFalse(r.recording)
    }
}
