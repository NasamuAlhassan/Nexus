package com.tekyerema

import com.tekyerema.tools.MoMoSendTool
import com.tekyerema.tools.MoneyPrivacy
import com.tekyerema.voice.SecureStep
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Payment-safety research, 2026-09-29 (docs/agent/research/secure_payment_auth.md). */
class SecureMoneyStepTest {
    @After fun reset() = SecureStep.end()

    /** While the PIN is typed (TalkBack speaks the keys) the app never opens the mic by itself. */
    @Test fun the_pin_step_lasts_until_the_phone_dialog_closes_or_times_out() {
        assertFalse(SecureStep.active())
        SecureStep.begin(now = 1_000_000L)
        assertTrue(SecureStep.active(now = 1_010_000L))
        SecureStep.onForeground("com.android.phone", now = 1_020_000L)          // still the USSD dialog
        assertTrue(SecureStep.active(now = 1_020_000L))
        SecureStep.onForeground("com.tekyerema", now = 1_021_000L)              // our own screen stepping aside
        assertTrue(SecureStep.active(now = 1_021_000L))
        SecureStep.onForeground("com.sec.android.app.launcher", now = 1_030_000L) // the dialog is gone
        assertFalse(SecureStep.active(now = 1_030_000L))
        SecureStep.begin(now = 2_000_000L)
        SecureStep.onForeground("com.sec.android.app.launcher", now = 2_001_000L) // the hand-off's own window change: too soon
        assertTrue(SecureStep.active(now = 2_001_000L))
        assertFalse(SecureStep.active(now = 2_000_000L + SecureStep.MAX_MS + 1))  // never longer than a USSD session
    }

    @Test fun a_send_asked_for_during_a_call_warns_about_call_guided_scams() {
        assertNull(MoneyPrivacy.callWarning(false))
        assertTrue(MoneyPrivacy.callWarning(true)!!.contains("If someone on the call is telling you to send this money, stop"))
        val args = JSONObject().put("number", "0244123456").put("amount", "5").put(MoMoSendTool.ARG_SIM, "mtn")
        assertFalse(MoMoSendTool.confirmationPrompt(args).contains("phone call"))
        args.put(MoMoSendTool.ARG_ON_CALL, true)
        val prompt = MoMoSendTool.confirmationPrompt(args)
        assertTrue(prompt, prompt.contains("You're on a phone call right now"))
        assertTrue(prompt, prompt.trimEnd().endsWith("stop at the PIN for you to enter."))
        assertTrue(MoMoSendTool.networkWarning(args)!!.contains("phone call"))   // forces the English read-back that carries it
    }

    @Test fun the_pin_tip_is_only_for_a_screen_reader_on_the_loudspeaker() {
        assertNull(MoneyPrivacy.pinTip(screenReaderOn = false, privateAudio = false))   // a sighted user types silently
        assertNull(MoneyPrivacy.pinTip(screenReaderOn = true, privateAudio = true))     // earphones: private
        assertEquals(true, MoneyPrivacy.pinTip(screenReaderOn = true, privateAudio = false)?.contains("earphones"))
    }
}
