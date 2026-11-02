package com.tekyerema

import com.tekyerema.agent.FastIntent
import com.tekyerema.agent.FastIntentClassifier
import com.tekyerema.operator.ExecutionMode
import com.tekyerema.operator.FastGate
import com.tekyerema.operator.FastHold
import com.tekyerema.operator.OperatorPolicy
import com.tekyerema.phonecare.AlertInbox
import com.tekyerema.phonecare.PhoneAlerts
import com.tekyerema.phonecare.PhoneCareIntents
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phone care by voice (phonecare/PhoneCareIntents) through the real fast-path classifier: the self-test, the alert
 * switches, "read it" — each a whole utterance, none shadowing an older fast intent.
 */
class PhoneCareIntentsTest {
    private fun c(s: String) = FastIntentClassifier.classify(s)
    @After fun reset() { PhoneAlerts.inbox.clear() }

    @Test fun check_my_phone_phrasings() {
        for (s in listOf("Check my phone", "Check my phone.", "Is everything working?", "check your setup", "Run a check",
            "Why aren't you working?", "Tekyerema, check my phone please", "What's wrong with my phone?", "phone check",
            "self test", "Is my phone okay?", "Is something wrong?", "run a self-test", "can you check everything"))
            assertEquals(s, FastIntent.CheckPhone, c(s))
    }

    @Test fun a_compound_request_still_reaches_the_agent() {
        assertNotEquals(FastIntent.CheckPhone, c("check my phone and call Ama"))
        assertFalse(c("tell me when I receive money and read my messages") is FastIntent.AlertSwitch)
    }

    @Test fun alert_switches_by_voice() {
        fun sw(kind: String, on: Boolean) = FastIntent.AlertSwitch(kind, on)
        assertEquals(sw("money", true), c("Tell me when I receive money."))
        assertEquals(sw("money", true), c("let me know when someone sends me money"))
        assertEquals(sw("money", true), c("Please alert me whenever I get momo"))
        assertEquals(sw("money", true), c("turn on money alerts"))
        assertEquals(sw("money", false), c("Stop telling me about money."))
        assertEquals(sw("money", false), c("turn off money alerts"))
        assertEquals(sw("missed_call", true), c("Tell me about missed calls"))
        assertEquals(sw("missed_call", true), c("let me know when I miss a call"))
        assertEquals(sw("missed_call", false), c("stop telling me about missed calls"))
        assertEquals(sw("battery", true), c("Warn me when my battery is low."))
        assertEquals(sw("battery", true), c("tell me when the battery gets low"))
        assertEquals(sw("battery", false), c("stop warning me about my battery"))
        assertEquals(sw("balance", true), c("include my balance in money alerts"))
        assertEquals(sw("balance", true), c("say my balance in money alerts"))
        assertEquals(sw("balance", false), c("don't tell me my balance"))
        assertEquals(sw("private", true), c("keep my alerts private"))
        assertEquals(sw("private", false), c("turn off private alerts"))
    }

    /** Only explicit words may LOOSEN privacy (review 2026-09-29): a follow-up question must never flip a standing switch. */
    @Test fun loose_phrasings_never_loosen_privacy() {
        for (s in listOf("tell me my balance too", "read my balance as well", "tell me my balance", "what is my balance"))
            assertFalse(s, c(s) is FastIntent.AlertSwitch)
        // "read my alerts out loud" READS them; private alerts stay on.
        assertEquals(FastIntent.ReadAlert, c("read my alerts out loud"))
        assertEquals(FastIntent.ReadAlert, c("read my alerts aloud please"))
    }

    @Test fun older_fast_intents_keep_their_meaning() {
        assertEquals(FastIntent.Battery, c("what's my battery"))
        assertEquals(FastIntent.Battery, c("check my battery"))
        assertEquals(FastIntent.CallHistory(missedOnly = true), c("any missed calls?"))
        assertEquals(FastIntent.CallHistory(missedOnly = false), c("did anyone call me"))
        // Asking to HEAR the missed calls is still the call history, not the alert switch.
        assertEquals(FastIntent.CallHistory(missedOnly = true), c("tell me about my missed calls"))
        assertEquals(FastIntent.CallHistory(missedOnly = true), c("tell me about any missed calls"))
        assertEquals(FastIntent.WatchSender("Ama", on = true), c("tell me when Ama messages me"))
        assertEquals(FastIntent.WatchSender("Ama", on = false), c("stop telling me about Ama"))
        assertEquals(FastIntent.SaveHelp("battery"), c("my battery is low"))
        // "tell me my balance" is a Mobile Money question for the agent, not a switch; "money alerts" alone is not a command.
        assertFalse(c("tell me my balance") is FastIntent.AlertSwitch)
        assertFalse(c("money alerts") is FastIntent.AlertSwitch)
    }

    @Test fun read_it_means_the_alert_only_right_after_a_private_one() {
        // No private alert just now: "read it" keeps its old meaning (the screen — the agent's reader).
        assertNotEquals(FastIntent.ReadAlert, c("read it"))
        val now = System.currentTimeMillis()
        PhoneAlerts.inbox.put(AlertInbox.Item("money", "You've received 50 cedis from Kofi Mensah.", now, announcedPrivately = true, expiresAt = now + 60_000L))
        assertEquals(FastIntent.ReadAlert, c("Read it."))
        assertEquals(FastIntent.ReadAlert, c("yes, read it"))
        assertEquals(FastIntent.ReadAlert, PhoneCareIntents.classify("tell me", readItPending = true))
        assertNotEquals(FastIntent.ReadAlert, PhoneCareIntents.classify("tell me", readItPending = false))
        // By name, any time.
        PhoneAlerts.inbox.clear()
        assertEquals(FastIntent.ReadAlert, c("read my alerts"))
        assertEquals(FastIntent.ReadAlert, c("Any alerts?"))
        assertEquals(FastIntent.ReadAlert, c("what was that alert"))
    }

    @Test fun switch_confirmations_say_why_it_cant_work_yet() {
        assertEquals("Okay. I'll tell you when you receive Mobile Money, but not your balance.", PhoneCareIntents.switchLine("money", true))
        assertEquals("Okay. I'll stop telling you about money.", PhoneCareIntents.switchLine("money", false))
        assertTrue(PhoneCareIntents.switchLine("money", true, notificationAccess = false)
            .endsWith("But I can't see your notifications yet. To fix it: in Nexus, open Settings, Permissions, Read messages."))
        assertTrue(PhoneCareIntents.switchLine("missed_call", true, messageAlertsOn = false).contains("message alerts are switched off in Settings, Control"))
        assertTrue(PhoneCareIntents.switchLine("balance", true, moneyOn = false).contains("Money alerts are off."))
        assertEquals("Okay. I'll warn you when your battery gets low.", PhoneCareIntents.switchLine("battery", true, notificationAccess = false))
        assertEquals("Okay. I'll stop telling you about missed calls.", PhoneCareIntents.switchLine("missed_call", false, notificationAccess = false))
    }

    @Test fun the_users_mode_never_blocks_these() {
        // Configuring Tɛkyerɛma's own alerts or running a read-only check is not "touching the phone" (no action words).
        for (mode in ExecutionMode.entries) for (i in listOf(FastIntent.CheckPhone, FastIntent.ReadAlert, FastIntent.AlertSwitch("money", true)))
            assertEquals("$mode $i", FastHold.Proceed, FastGate.check(i, isAction = false, op = OperatorPolicy.View(mode = mode)))
    }
}
