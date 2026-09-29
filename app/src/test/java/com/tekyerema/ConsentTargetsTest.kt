package com.tekyerema

import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.PerceptionRoute
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.core.config.AppConfig
import com.tekyerema.policy.ActionSemantics
import com.tekyerema.policy.PolicyConfig
import com.tekyerema.policy.RiskTier
import com.tekyerema.policy.TargetSemantics
import com.tekyerema.policy.TargetSemantics.Consent
import com.tekyerema.tools.defaultTools
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Account, install, permission and subscription controls are the user's to press: signing in or up, SSO / OAuth
 * ("Continue as …"), installing, granting a permission, starting a trial — each needs a fresh, explicit yes.
 */
class ConsentTargetsTest {
    @After fun reset() = AppConfig.loadFrom(emptyList())

    private fun btn(id: Int, label: String, role: String = "Button", clickable: Boolean = true) =
        UiElement(id, role, label, Box(0, id * 100, 600, id * 100 + 90), clickable = clickable)
    private fun tier(label: String, role: String = "Button") = TargetSemantics.classify("tap", btn(1, label, role), null, false).tier
    private val screen = UiSnapshot("com.example.app", null, emptyList(), 0L, PerceptionRoute("tree", "test"))

    @Test fun signing_in_up_and_sharing_an_account_are_confirmed() {
        for (l in listOf("Sign in", "Sign in with Google", "Log in", "Login", "Sign up", "Register", "Create account",
                "Create an account", "Continue with Google", "Continue with Facebook", "Link a device", "Use another account"))
            assertEquals(l, RiskTier.TIER2, tier(l))
        // SSO / OAuth with the person's full name — longer than a plain caption, caught because it LEADS with the words.
        assertEquals(RiskTier.TIER2, tier("Continue as Prince Nasamu Alhassan"))
        assertEquals(RiskTier.TIER2, tier("Continue as Ama", role = "View"))
        assertEquals(Consent.ACCOUNT, TargetSemantics.consentKind("Continue as Prince Nasamu Alhassan", btn(1, "x")))
    }

    @Test fun installing_activating_and_permissions_are_confirmed() {
        for (l in listOf("Install", "Install anyway", "Activate", "Activate this device admin app", "Grant", "Authorize",
                "Authorise", "Approve", "While using the app", "Only this time", "Pair", "Permit drawing over other apps"))
            assertEquals(l, RiskTier.TIER2, tier(l))
        assertEquals(Consent.INSTALL, TargetSemantics.consentKind("Install"))
        assertEquals(Consent.PERMISSION, TargetSemantics.consentKind("While using the app"))
        assertEquals(Consent.REMOVE, TargetSemantics.consentKind("Clear storage"))
        assertEquals(RiskTier.TIER2, tier("Clear data"))
        assertEquals(RiskTier.TIER2, tier("Deactivate"))
    }

    /** A20e 2026-09-29: the fallback model tapped the Play Store's "Use biometrics" card while looking for search. */
    @Test fun biometric_and_unlock_setup_is_confirmed() {
        for (l in listOf("Use biometrics", "Use biometrics - fingerprint or face -", "Fingerprints", "Face recognition",
                "Screen lock", "Require authentication", "Turn on 2-Step Verification", "Create a passkey"))
            assertEquals(l, RiskTier.TIER2, tier(l))
        assertEquals(Consent.SECURITY, TargetSemantics.consentKind("Use biometrics"))
        assertEquals(null, TargetSemantics.consentKind("Search apps & games"))
    }

    @Test fun trials_upgrades_and_renewals_are_spends() {
        for (l in listOf("Start free trial", "Start your 7-day free trial", "Try it free", "Upgrade", "Upgrade to Premium",
                "Renew", "Pre-order", "Go Premium"))
            assertEquals(l, RiskTier.TIER3, tier(l))
    }

    @Test fun ordinary_navigation_is_not_consent() {
        for (l in listOf("Continue", "Use without an account", "Settings", "Battery", "No thanks", "Signed in as Ama",
                "Uninstalled apps", "Repair", "Kakum National Park", "Open"))
            assertNull(l, tier(l))
        // A message row that merely MENTIONS installing is navigation (tapped to open and read it).
        assertNull(TargetSemantics.classify("tap", btn(2, "Kofi: did you install the new app I sent you yesterday?", role = "TextView"),
            null, false).tier)
        // A model-requested label with no resolved element still counts when it leads with consent.
        assertEquals(RiskTier.TIER2, TargetSemantics.classify("tap", null, "Continue as Prince Nasamu Alhassan", false).tier)
    }

    @Test fun the_read_back_says_what_pressing_it_means() {
        val acct = ActionSemantics.confirmation("tap", btn(1, "Continue as Prince"), null, screen)!!
        assertTrue(acct, acct.contains("“Continue as Prince”") && acct.contains("account"))
        assertTrue(ActionSemantics.confirmation("tap", btn(1, "Install"), null, screen)!!.contains("installs"))
        assertTrue(ActionSemantics.confirmation("tap", btn(1, "While using the app"), null, screen)!!.contains("permission"))
        assertTrue(ActionSemantics.confirmation("tap", btn(1, "Start free trial"), null, screen)!!.contains("charge you money"))
        // Existing read-backs are unchanged.
        assertEquals("Allow “Allow”?", ActionSemantics.confirmation("tap", btn(1, "Allow"), null, screen))
    }

    @Test fun an_app_defined_consent_action_is_confirmed() {
        val node = defaultTools().first { it.name == "node_action" }
        assertEquals(RiskTier.TIER2, PolicyConfig.argTier(node, JSONObject().put("custom", "Install")))
        assertEquals(RiskTier.TIER2, PolicyConfig.argTier(node, JSONObject().put("custom", "Sign in")))
        assertNull(PolicyConfig.argTier(node, JSONObject().put("custom", "Mark as read")))
    }

    /** Device suite (A20e): "save a new contact" through the Contacts app's own screens reached "Save" unconfirmed. */
    @Test fun saving_a_filled_form_is_confirmed_and_read_back() {
        fun field(id: Int, text: String?, hint: String, password: Boolean = false) =
            UiElement(id, "EditText", text, Box(0, id * 100, 700, id * 100 + 80), editable = true, clickable = true, hint = hint, password = password)
        val save = btn(9, "Save")
        val form = UiSnapshot("com.samsung.android.app.contacts", "Create contact",
            listOf(field(1, "Zz Tekyerema Test", "Name"), field(2, "020 000 0000", "Phone"), field(3, null, "Email"), save), 0L, PerceptionRoute("tree", "test"))
        assertEquals(RiskTier.TIER2, TargetSemantics.classify("tap", save, null, false, form).tier)
        val rb = ActionSemantics.confirmation("tap", save, null, form)!!
        assertTrue(rb, rb.startsWith("Save this?") && rb.contains("Name: Zz Tekyerema Test") && rb.contains("Phone: 020 000 0000"))
        // A "Save" with nothing typed (a settings screen) doesn't ask.
        val plain = UiSnapshot("com.android.settings", "Display", listOf(save), 0L, PerceptionRoute("tree", "test"))
        assertTrue(TargetSemantics.classify("tap", save, null, false, plain).tier == null)
        // A password value is never read back.
        val login = UiSnapshot("com.example", "Sign in", listOf(field(1, "ama", "Username"), field(2, "hunter22", "Password", password = true), save), 0L,
            PerceptionRoute("tree", "test"))
        assertFalse(ActionSemantics.confirmation("tap", save, null, login)!!.contains("hunter22"))
    }

    /** Device suite: every Calculator digit asked for a yes (the dialer-keypad rule fired on any number pad). */
    @Test fun only_a_dialer_keypad_can_dial() {
        fun keypad(pkg: String, extra: List<UiElement> = emptyList()) = UiSnapshot(pkg, null,
            (0..9).map { btn(it, it.toString()) } + extra, 0L, PerceptionRoute("tree", "test"))
        val seven = btn(7, "7")
        assertTrue("calculator", TargetSemantics.classify("tap", seven, null, false, keypad("com.sec.android.app.popupcalculator")).tier == null)
        assertEquals(RiskTier.TIER2, TargetSemantics.classify("tap", seven, null, false, keypad("com.samsung.android.dialer")).tier)
        assertEquals("a Call button makes it a dialer", RiskTier.TIER2,
            TargetSemantics.classify("tap", seven, null, false, keypad("com.example.voip", listOf(btn(20, "Call")))).tier)
    }
}
