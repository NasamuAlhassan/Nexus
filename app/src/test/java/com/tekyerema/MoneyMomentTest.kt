package com.tekyerema

import com.tekyerema.money.MoneyMoment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** "This is money" (2 Nov 2026): one flag for the earcon, the gold edge and the whole-flow curtain. */
class MoneyMomentTest {
    private val events = mutableListOf<String>()

    @Before fun setUp() {
        MoneyMoment.reset()
        MoneyMoment.onStart = { events += "earcon" }
        MoneyMoment.onChange = { events += "change:$it" }
    }
    @After fun tearDown() = MoneyMoment.reset()

    @Test fun the_money_tools_and_nothing_else() {
        for (t in listOf("momo_send", "mobile_money", "bank_ussd", "telco_ussd", "ussd_navigate", "run_ussd"))
            assertTrue(t, MoneyMoment.isMoneyTool(t))
        for (t in listOf("send_sms", "make_call", "open_app", "money_status", null)) assertFalse("$t", MoneyMoment.isMoneyTool(t))
    }

    @Test fun one_earcon_for_the_whole_flow_from_read_back_to_pin() {
        MoneyMoment.hold(MoneyMoment.TASK, expire = false)       // the read-back
        MoneyMoment.hold(MoneyMoment.PIN, expire = false)        // the PIN step, same flow: no second earcon
        assertTrue(MoneyMoment.active.value)
        MoneyMoment.release(MoneyMoment.PIN)
        assertTrue("the task still holds it", MoneyMoment.active.value)
        MoneyMoment.release(MoneyMoment.TASK)
        assertFalse(MoneyMoment.active.value)
        assertEquals(listOf("earcon", "change:true", "change:false"), events)
    }

    @Test fun releasing_what_was_never_held_changes_nothing() {
        MoneyMoment.release(MoneyMoment.TASK)   // every task's finish() releases, money or not
        assertFalse(MoneyMoment.active.value)
        assertTrue(events.isEmpty())
    }

    @Test fun a_stale_hold_is_dropped_but_a_newer_one_is_kept() {
        MoneyMoment.hold(MoneyMoment.TASK, now = 1_000L, expire = false)
        MoneyMoment.hold(MoneyMoment.TASK, now = 2_000L, expire = false)   // a second money read-back in the same task
        MoneyMoment.releaseIfStale(MoneyMoment.TASK, 1_000L)               // the first hold's timer fires
        assertTrue(MoneyMoment.active.value)
        MoneyMoment.releaseIfStale(MoneyMoment.TASK, 2_000L)
        assertFalse(MoneyMoment.active.value)
    }
}
