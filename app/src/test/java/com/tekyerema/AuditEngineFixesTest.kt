package com.tekyerema

import com.tekyerema.agent.CannotDoSpeech
import com.tekyerema.agent.References
import com.tekyerema.agent.ReviseTurn
import com.tekyerema.agent.RoutingBrain
import com.tekyerema.core.Privacy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A20e black-box audit 2026-10-01 — corrections at a read-back, the stale "him", refusals said to the user, PINs in requests. */
class AuditEngineFixesTest {
    @After fun reset() = References.clear()

    private fun sms(contact: String = "Prince Ice P", message: String = "hello from the audit", via: String? = "whatsapp") =
        JSONObject().put("message", message).put("contact", contact).also { if (via != null) it.put("via", via) }

    // ---- ReviseTurn (C09, F08, K01)

    @Test fun a_changed_recipient_is_applied_to_the_pending_message() {
        val r = ReviseTurn.patch("send_sms", sms(), "no, send it to Prince Melanin instead")
        assertNotNull(r)
        assertEquals("Prince Melanin", r!!.args.getString("contact"))
        assertEquals("hello from the audit", r.args.getString("message"))
        assertEquals("whatsapp", r.args.getString("via"))
        assertTrue(r.what, r.what.contains("Prince Melanin"))
        assertEquals("Ama", ReviseTurn.patch("make_call", JSONObject().put("contact", "Kofi"), "call Ama instead")!!.args.getString("contact"))
        assertEquals("Ama Mensah", ReviseTurn.patch("send_sms", sms(), "No, Ama Mensah")!!.args.getString("contact"))
    }

    @Test fun a_changed_amount_is_applied_to_the_pending_money_action() {
        val money = JSONObject().put("_kind", "send_money").put("_amount", "5").put("_recipient", "Prince Melanin")
        val r = ReviseTurn.patch("mobile_money", money, "make it 2 cedis")
        assertEquals("2", r!!.args.getString("_amount"))
        assertEquals("Prince Melanin", r.args.getString("_recipient"))
        assertEquals("the amount to 2 cedis", r.what)
        val nav = JSONObject().put("number", "0552093690").put("amount", "20")
        assertEquals("0.5", ReviseTurn.patch("ussd_navigate", nav, "no, 50 pesewas")!!.args.getString("amount"))
        assertEquals("10", ReviseTurn.patch("ussd_navigate", nav, "change it to 10")!!.args.getString("amount"))
        assertEquals("1 cedi", ReviseTurn.patch("ussd_navigate", nav, "make it one cedi")!!.what.substringAfter("to "))
    }

    @Test fun changed_words_and_a_changed_app() {
        val r = ReviseTurn.patch("send_sms", sms("Richmond's Dad", "I will arrive later"), "actually change that to I will arrive at 7 PM")
        assertEquals("I will arrive at 7 PM", r!!.args.getString("message"))
        assertEquals("Richmond's Dad", r.args.getString("contact"))
        assertEquals("on my way", ReviseTurn.patch("send_sms", sms(), "say on my way instead")!!.args.getString("message"))
        assertFalse(ReviseTurn.patch("send_sms", sms(), "send it by SMS instead")!!.args.has("via"))
        assertEquals("whatsapp", ReviseTurn.patch("send_sms", sms(via = null), "on WhatsApp instead")!!.args.getString("via"))
        val both = ReviseTurn.patch("send_sms", sms(via = null), "send it to Ama on WhatsApp instead")!!
        assertEquals("Ama", both.args.getString("contact")); assertEquals("whatsapp", both.args.getString("via"))
    }

    @Test fun unclear_corrections_go_to_the_planner() {
        assertNull("could be the words or the person", ReviseTurn.patch("send_sms", sms(), "change that to Prince Melanin"))
        assertNull("not a recipient", ReviseTurn.patch("send_sms", sms(), "no, that's wrong"))
        assertNull("a stop is not a correction", ReviseTurn.patch("send_sms", sms(), "cancel it"))
        assertNull("a name for a number-only tool", ReviseTurn.patch("momo_send", JSONObject().put("number", "055").put("amount", "1"), "send it to Kofi instead"))
        assertNull("an app this tool can't use", ReviseTurn.patch("send_sms", sms(), "send it on Telegram instead"))
        assertNull("a whole new request", ReviseTurn.patch("send_sms", sms(), "send it to Ama saying I am late and then call Kofi"))
        assertNull("no amount on a message", ReviseTurn.patch("send_sms", sms(), "make it 2 cedis"))
    }

    // ---- RoutingBrain.pendingRevision

    @Test fun a_correction_the_engine_could_not_apply_waits_for_the_planner() {
        val note = "You proposed: \"WhatsApp Kofi…\". The user did NOT approve it — they said: \"no, that's wrong\". Treat that as a correction…"
        assertTrue(RoutingBrain.pendingRevision(listOf(note)))
        assertTrue(RoutingBrain.pendingRevision(listOf("search_contacts(query=Kofi) → success; verified=n/a; ok", note)))
        assertFalse("a step ran since", RoutingBrain.pendingRevision(listOf(note, "send_sms(contact=Ama) → success; verified=true; sent")))
        assertFalse("applied by the engine", RoutingBrain.pendingRevision(listOf(
            "You proposed: \"…\". The user corrected the amount to 2 cedis; it is being proposed again with that change and will be confirmed again.")))
        assertFalse(RoutingBrain.pendingRevision(emptyList()))
    }

    // ---- References (C07, E2E1, K01, K02)

    @Test fun him_is_only_replaced_when_the_request_opens_with_it() {
        assertEquals("call Ama back", References.rewrite("call her back", person = "Ama"))
        assertEquals("please text Ama I'm late", References.rewrite("please text him I'm late", person = "Ama"))
        assertEquals("send Ama a WhatsApp message saying hi", References.rewrite("send him a WhatsApp message saying hi", person = "Ama"))
        assertNull("the person is in the sentence", References.rewrite("tell Prince I'll call him later", person = "Prince Melanin"))
        assertNull(References.rewrite("find Richmond, and send him the screenshot and a summary", person = "Prince Melanin"))
        assertNull(References.rewrite("call him", person = null))
    }

    @Test fun the_remembered_person_expires_and_a_lookup_replaces_it() {
        References.notePerson("Prince Melanin")
        assertEquals("Prince Melanin", References.recentPerson())
        assertNull("stale after the window", References.recentPerson(System.currentTimeMillis() + References.personTtlMs() + 1_000))
        assertEquals("Richmond's Dad", References.noteLookup("Matches for 'Richmond's Dad': Richmond's Dad — 0246168805"))
        assertEquals("Richmond's Dad", References.recentPerson())
        assertEquals("0246168805", References.lastNumber)
        assertNull("several matches are options, not a person",
            References.noteLookup("Matches for 'Prince': Prince Ice P — +233536523611; Princess — +233538185281"))
        assertEquals("Richmond's Dad", References.recentPerson())
        References.clear()
        assertNull(References.recentPerson())
    }

    @Test fun a_bare_pronoun_is_never_looked_up_as_a_contact() {
        for (w in listOf("him", "Her", "them.", "that person", "ɔno")) assertTrue(w, com.tekyerema.android.ContactResolver.isPronoun(w))
        for (w in listOf("Kim", "Himmat", "Prince Melanin", "Theo")) assertFalse(w, com.tekyerema.android.ContactResolver.isPronoun(w))
    }

    // ---- CannotDoSpeech (SEC01, SEC02)

    @Test fun a_refusal_is_said_to_the_user_not_about_them() {
        assertEquals("I never handle PINs or passwords, and I don't keep them. You enter those yourself.",
            CannotDoSpeech.forUser("unsafe_or_uncertain", "User asked for their mobile money PIN, which is sensitive personal security information."))
        assertEquals("I can't do that safely, so I've left it.",
            CannotDoSpeech.forUser("unsafe_or_uncertain", "The user wants to delete every contact."))
        assertEquals("I can't do that.", CannotDoSpeech.forUser("something_new", "no detail given"))
        val own = "I couldn't find a Wi-Fi switch on this screen."
        assertEquals("a line already said to the user is kept", own, CannotDoSpeech.forUser("no_accessible_ui", own))
    }

    // ---- share_screenshot (E2E): old captures don't pile up, and nothing else in the folder is touched

    @Test fun old_shared_screenshots_are_pruned_and_the_users_export_is_kept() {
        val dir = java.nio.file.Files.createTempDirectory("exports").toFile()
        try {
            val export = java.io.File(dir, "tekyerema-export.json").apply { writeText("{}") }
            val shots = (1..5).map { i -> java.io.File(dir, "screenshot-$i.jpg").apply { writeText("x"); setLastModified(1_000_000L * i) } }
            assertEquals(3, com.tekyerema.tools.ShareScreenshotTool.prune(dir, keep = 2))
            assertTrue(export.exists())
            assertEquals(listOf(false, false, false, true, true), shots.map { it.exists() })
            assertEquals(0, com.tekyerema.tools.ShareScreenshotTool.prune(dir, keep = 2))
        } finally { dir.deleteRecursively() }
    }

    @Test fun the_share_tool_is_registered_for_the_planner_only() {
        val dir = listOf(java.io.File("src/main/assets/config"), java.io.File("app/src/main/assets/config")).first { it.isDirectory }
        com.tekyerema.core.config.AppConfig.loadFrom(dir.listFiles { f -> f.name.endsWith(".json") }!!.map { "config/${it.name}" to it.readText() })
        try {
            val tool = com.tekyerema.tools.defaultTools().first { it.name == "share_screenshot" }
            assertTrue(com.tekyerema.tools.ToolManifest.mayCall(tool, "planner"))
            assertFalse("never from an unattended trigger", com.tekyerema.tools.ToolManifest.mayCall(tool, com.tekyerema.tools.ToolManifest.CALLER_TRIGGER))
        } finally { com.tekyerema.core.config.AppConfig.loadFrom(emptyList()) }
    }

    // ---- Privacy.maskCredentials (SEC02)

    @Test fun a_credential_inside_a_request_is_hidden() {
        assertEquals("my mobile money PIN is [hidden], remember it for next time",
            Privacy.maskCredentials("my mobile money PIN is 4821, remember it for next time"))
        assertEquals("the pin: [hidden]", Privacy.maskCredentials("the pin: 4 8 2 1"))
        assertEquals("my OTP is [hidden]", Privacy.maskCredentials("my OTP is 493021"))
        assertEquals("the password is [hidden] ok", Privacy.maskCredentials("the password is hunter2 ok"))
        for (untouched in listOf("send 5 cedis to 0552093690", "the middle is 4 5 6", "call 0244123456",
                "what is my mobile money PIN", "open the password field", "buy 1 cedi airtime", "dial star 124 hash"))
            assertEquals(untouched, Privacy.maskCredentials(untouched))
    }
}
