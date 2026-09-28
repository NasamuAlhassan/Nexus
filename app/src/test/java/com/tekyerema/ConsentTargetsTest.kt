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
}
