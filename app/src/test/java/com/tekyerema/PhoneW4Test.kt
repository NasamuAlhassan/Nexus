package com.tekyerema

import com.tekyerema.notifications.NotificationRecord
import com.tekyerema.notifications.NotificationStore
import com.tekyerema.policy.PolicyConfig
import com.tekyerema.policy.RiskTier
import com.tekyerema.tools.SetAlarmTool
import com.tekyerema.tools.defaultTools
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** W4: E-05 repeat days, E-15 notification naming, E-17 button tiers. */
class PhoneW4Test {
    @Test fun alarm_repeat_specs_become_calendar_days() {
        val cal = java.util.Calendar::class.java
        assertEquals(7, SetAlarmTool.repeatDays("every day")!!.size)
        assertEquals(5, SetAlarmTool.repeatDays("weekdays")!!.size)
        assertEquals(listOf(java.util.Calendar.SATURDAY, java.util.Calendar.SUNDAY), SetAlarmTool.repeatDays("weekends"))
        assertEquals(listOf(java.util.Calendar.MONDAY, java.util.Calendar.FRIDAY), SetAlarmTool.repeatDays("mon and fri"))
        assertNull(SetAlarmTool.repeatDays(null)); assertNull(SetAlarmTool.repeatDays("someday"))
        assertTrue(cal.name.isNotEmpty())
    }

    @Test fun notifications_are_named_by_app_and_sender_and_found_by_either() {
        val r = NotificationRecord("k1", "com.whatsapp", "Family group", "Dinner at 7", 1L, true, null,
            appLabel = "WhatsApp", sender = "Ama")
        assertEquals("WhatsApp — Ama", r.spokenSource)
        NotificationStore.put(r)
        assertEquals("k1", NotificationStore.find("ama")?.key)
        assertEquals("k1", NotificationStore.find("whatsapp")?.key)
        assertEquals("k1", NotificationStore.findReplyable("Ama")?.key)
        NotificationStore.remove("k1")
        assertNull(NotificationStore.get("k1"))
    }

    @Test fun pressing_a_notification_button_is_confirmed_but_dismissing_is_not() {
        val t = defaultTools().first { it.name == "notification_action" }
        assertNull(PolicyConfig.argTier(t, JSONObject().put("action", "dismiss")))
        assertEquals(RiskTier.TIER2, PolicyConfig.argTier(t, JSONObject().put("action", "Archive")))
    }
}
