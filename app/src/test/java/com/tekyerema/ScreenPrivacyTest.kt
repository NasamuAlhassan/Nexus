package com.tekyerema

import com.tekyerema.accessibility.AccessibilityController
import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.PerceptionRoute
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.core.Redact
import com.tekyerema.core.config.AppConfig
import com.tekyerema.policy.ScreenPrivacy
import com.tekyerema.tools.ReadAllTool
import com.tekyerema.tools.ReadScreenTool
import com.tekyerema.tools.ToolContext
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** J-11 deny list, J-10 one-time codes and the Secure Input boundary for the tools that return screen text. */
class ScreenPrivacyTest {
    @After fun reset() = AppConfig.loadFrom(emptyList())

    private fun el(id: Int, label: String?, editable: Boolean = false, password: Boolean = false) =
        UiElement(id, "TextView", label, Box(0, id * 100, 500, id * 100 + 80), editable = editable, password = password)
    private fun snap(pkg: String, vararg e: UiElement, announcements: List<String> = emptyList()) =
        UiSnapshot(pkg, "Title", e.toList(), 0L, PerceptionRoute("tree", "test"), appName = "App", announcements = announcements)

    /** A controller that only answers buildSnapshot with [s] (and never scrolls). */
    private fun controller(s: UiSnapshot): AccessibilityController =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AccessibilityController::class.java)) { _, m, _ ->
            when (m.name) {
                "buildSnapshot" -> s
                "getReady" -> true
                "scrollElement" -> false
                else -> null
            }
        } as AccessibilityController

    private val deny = setOf("com.bank.app")
    private val guide = setOf("com.gov.portal")

    @Test fun a_denied_app_is_neither_operated_nor_read() {
        for (reader in listOf("read_state", "read_all", "read_screen", "scroll_to_find", "wait_for_element"))
            assertEquals(reader, ScreenPrivacy.DENIED, ScreenPrivacy.appRule(reader, grounded = false, mutating = false, "com.bank.app", deny, guide))
        assertEquals(ScreenPrivacy.DENIED, ScreenPrivacy.appRule("tap", grounded = true, mutating = true, "com.bank.app", deny, guide))
        // Tools that don't look at the screen are not about the app in front (the time, an SMS the user asked for).
        assertNull(ScreenPrivacy.appRule("read_sms", grounded = false, mutating = false, "com.bank.app", deny, guide))
        assertNull(ScreenPrivacy.appRule("read_state", grounded = false, mutating = false, "com.whatsapp", deny, guide))
        assertNull(ScreenPrivacy.appRule("read_state", grounded = false, mutating = false, null, deny, guide))
    }

    @Test fun a_guide_only_app_is_read_but_not_acted_on() {
        assertEquals(ScreenPrivacy.GUIDE_ONLY, ScreenPrivacy.appRule("tap", grounded = true, mutating = true, "com.gov.portal", deny, guide))
        assertNull(ScreenPrivacy.appRule("read_all", grounded = false, mutating = false, "com.gov.portal", deny, guide))
    }

    @Test fun a_blanked_denied_screen_keeps_only_which_app_it_is() {
        val b = ScreenPrivacy.blank(snap("com.bank.app", el(0, "Balance GH₵ 5,000"), announcements = listOf("Transfer sent")))
        assertEquals("com.bank.app", b.appPackage)
        assertTrue(b.elements.isEmpty())
        assertNull(b.screenTitle)
        assertTrue("toasts are screen content too", b.announcements.isEmpty())
        val json = b.toCompactJson().toString()
        assertFalse(json.contains("5,000")); assertFalse(json.contains("Transfer sent"))
    }

    @Test fun one_time_codes_on_screen_never_reach_the_model() {
        val s = snap("com.google.android.apps.messaging",
            el(0, "MTN MoMo: Your verification code is 482913. Do not share it."),
            el(1, "Kofi: Pay GH₵ 50.00 to 0244123456 for the rent"),
            announcements = listOf("Your login code: 7731"))
        val json = s.toCompactJson().toString()
        assertFalse(json, json.contains("482913"))
        assertFalse(json, json.contains("7731"))
        assertTrue(json, json.contains(Redact.HIDDEN))
        assertTrue("ordinary numbers stay", json.contains("0244123456") && json.contains("50.00"))
        val summary = s.summarize()   // read_state's result
        assertFalse(summary, summary.contains("482913"))
        // The element itself is unchanged, so it can still be tapped.
        assertTrue(s.elements[0].label!!.contains("482913"))
    }

    @Test fun notifications_from_a_denied_app_are_not_captured() {
        assertFalse(Redact.deniedPackage("com.bank.app"))
        AppConfig.loadFrom(listOf("policy.json" to """{"policy":{"denyApps":["com.bank.app"]}}"""))
        assertTrue(Redact.deniedPackage("com.bank.app"))
        assertTrue(Redact.deniedPackage("com.google.android.apps.authenticator2"))   // the built-in list still holds
        assertFalse(Redact.deniedPackage("com.whatsapp"))
    }

    @Test fun read_all_never_reads_a_credential_screen_or_a_denied_app() = runBlocking {
        val ctxApp = android.content.ContextWrapper(null)
        val pin = snap("com.android.phone", el(0, "Enter MoMo PIN"), el(1, "4821", editable = true))
        val r1 = ReadAllTool.execute(JSONObject(), ToolContext(ctxApp, controller(pin), com.tekyerema.notifications.NotificationStore))
        assertEquals(ReadScreenTool.PRIVATE, r1.message)
        assertFalse(r1.message.contains("4821"))

        AppConfig.loadFrom(listOf("policy.json" to """{"policy":{"denyApps":["com.bank.app"]}}"""))
        val bank = snap("com.bank.app", el(0, "Balance GH₵ 5,000"))
        val r2 = ReadAllTool.execute(JSONObject(), ToolContext(ctxApp, controller(bank), com.tekyerema.notifications.NotificationStore))
        assertFalse(r2.message, r2.message.contains("5,000"))

        val ok = snap("com.android.chrome", el(0, "Kakum National Park is in the Central Region"), el(1, "Your code is 5512 for sign-in"))
        val r3 = ReadAllTool.execute(JSONObject(), ToolContext(ctxApp, controller(ok), com.tekyerema.notifications.NotificationStore))
        assertTrue(r3.message, r3.message.contains("Kakum"))
        assertFalse("J-10 in read_all too", r3.message.contains("5512"))
    }
}
