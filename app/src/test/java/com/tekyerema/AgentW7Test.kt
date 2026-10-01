package com.tekyerema

import com.tekyerema.agent.DirectAnswer
import com.tekyerema.core.config.AppConfig
import com.tekyerema.tools.ToolManifest
import com.tekyerema.tools.defaultTools
import com.tekyerema.voice.ControlVerbs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** W7: I-01 direct answers, C-12 control verbs, D-08 callers. */
class AgentW7Test {
    @After fun reset() = AppConfig.loadFrom(emptyList())

    @Test fun knowledge_questions_skip_the_screen_but_phone_questions_do_not() {
        for (q in listOf("What is the capital of Ghana?", "Who wrote Things Fall Apart", "How many pesewas are in a cedi?",
                "Explain inflation", "Why is the sky blue?", "Tell me a joke", "Who is the president of Ghana?",
                // audit 2026-10-01 (R04): a price comparison is research even when it names data bundles
                "Compare MTN and Telecel data bundle prices and tell me which is cheaper"))
            assertTrue(q, DirectAnswer.isKnowledgeQuestion(q))
        for (q in listOf("What is my balance?", "Who called me?", "What's on my screen?", "Open WhatsApp",
                "What is this?", "How do I turn on WiFi", "Is my phone locked?", "Send Ama a text",
                "Can you start the journey to legon Hall", "Can you navigate to Accra Mall",
                "Where is the route to Kumasi", "Can you show me directions to the airport"))
            assertFalse(q, DirectAnswer.isKnowledgeQuestion(q))
    }

    @Test fun control_verbs_while_a_task_runs() {
        assertEquals(ControlVerbs.Verb.STOP, ControlVerbs.classify("Stop!"))
        assertEquals(ControlVerbs.Verb.STOP, ControlVerbs.classify("never mind"))
        assertEquals(ControlVerbs.Verb.PAUSE, ControlVerbs.classify("hold on"))
        assertEquals(ControlVerbs.Verb.RESUME, ControlVerbs.classify("carry on please"))
        assertEquals(ControlVerbs.Verb.STATUS, ControlVerbs.classify("What are you doing?"))
        assertEquals(ControlVerbs.Verb.STEER, ControlVerbs.classify("make it 50 cedis"))
        assertEquals(ControlVerbs.Verb.STEER, ControlVerbs.classify("send it to Kofi instead"))
    }

    @Test fun unattended_tasks_cannot_use_consequential_tools() {
        val dir = listOf(java.io.File("src/main/assets/config"), java.io.File("app/src/main/assets/config")).first { it.isDirectory }
        AppConfig.loadFrom(dir.listFiles { f -> f.name.endsWith(".json") }!!.map { "config/${it.name}" to it.readText() })
        val tools = defaultTools().associateBy { it.name }
        for (n in listOf("make_call", "send_sms", "momo_send", "mobile_money", "run_ussd", "reply_to_notification", "emergency", "create_contact", "share_text"))
            assertFalse("$n must not run unattended", ToolManifest.mayCall(tools.getValue(n), ToolManifest.CALLER_TRIGGER))
        for (n in listOf("read_notifications", "read_state", "call_log", "calendar"))
            assertTrue("$n may run unattended", ToolManifest.mayCall(tools.getValue(n), ToolManifest.CALLER_TRIGGER))
    }
}
