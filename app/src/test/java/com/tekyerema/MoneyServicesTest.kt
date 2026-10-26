package com.tekyerema

import com.tekyerema.agent.BrainPrompts
import com.tekyerema.money.MoneyAsk
import com.tekyerema.money.MoneyServices
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner, 26 Oct 2026: "send 5 cedis to my yello save" was heard "yellow save" and searched the contacts — Yello Save is a service. */
class MoneyServicesTest {
    @Test fun the_recognisers_spellings_all_name_the_service() {
        for (s in listOf("my yello save", "my yellow save", "my yelo safe", "yolo saving", "Yello Savings", "my susu", "my savings"))
            assertTrue(s, MoneyServices.NAME.containsMatchIn(s))
        for (s in listOf("Ama", "Kofi Mensah", "my yellow shirt", "my wife", "yellow"))
            assertFalse(s, MoneyServices.NAME.containsMatchIn(s))
    }

    @Test fun the_local_parser_leaves_a_service_to_the_planner_but_still_sends_to_a_person() {
        assertNull(MoneyAsk.parse("send 5 cedis to my yello save"))
        assertNull(MoneyAsk.parse("send five cedis to my yellow save"))
        assertNull(MoneyAsk.parse("transfer 20 cedis to my savings"))
        assertNotNull(MoneyAsk.parse("send 5 cedis to Ama"))
        assertNotNull(MoneyAsk.parse("send 50 cedis to my wife"))
    }

    @Test fun the_planner_is_told_what_a_service_is_only_when_the_request_names_one() {
        val rules = BrainPrompts.goalRules("send 5 cedis to my yello save")!!
        assertTrue(rules, rules.contains("menu_goal") && rules.contains("never momo_send"))
        val plain = BrainPrompts.goalRules("send 5 cedis to Ama")
        assertTrue(plain == null || !plain.contains("MoMo SERVICES"))
    }
}
