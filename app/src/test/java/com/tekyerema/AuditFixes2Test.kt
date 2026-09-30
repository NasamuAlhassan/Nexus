package com.tekyerema

import com.tekyerema.notifications.NotificationRecord
import com.tekyerema.notifications.NotificationStore
import com.tekyerema.tools.intOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Regressions from the 2026-09-30 audit (half 2): the reply target is a NAME's conversation, chosen once; numbers parse or stay null. */
class AuditFixes2Test {

    private fun rec(key: String, pkg: String, title: String?, sender: String?, app: String?, t: Long, reply: Boolean = true, ongoing: Boolean = false) =
        NotificationRecord(key, pkg, title, "hi", t, canReply = reply, replyAction = null, appLabel = app, sender = sender, ongoing = ongoing)

    @Before fun fresh() { NotificationStore.clearForTest() }

    @Test fun `Ama matches Ama, not Amanda — and a group where Ama spoke is found by name`() {
        NotificationStore.put(rec("k1", "com.whatsapp", "Amanda", "Amanda", "WhatsApp", 1))
        NotificationStore.put(rec("k2", "com.whatsapp", "Ama", "Ama", "WhatsApp", 2))
        assertEquals(listOf("k2"), NotificationStore.replyables("Ama").map { it.key })
        // A group titled "Church" where Ama spoke last is found by her name — the caller then reads the GROUP back.
        NotificationStore.clearForTest()
        NotificationStore.put(rec("g1", "com.whatsapp", "Church", "Ama", "WhatsApp", 3))
        assertEquals(listOf("g1"), NotificationStore.replyables("Ama").map { it.key })
    }

    @Test fun `two conversations matching the words are AMBIGUOUS, not newest-wins`() {
        NotificationStore.put(rec("k1", "com.whatsapp", "Ama", "Ama", "WhatsApp", 1))
        NotificationStore.put(rec("k2", "com.samsung.messaging", "Ama", "Ama", "Messages", 2))
        assertEquals(2, NotificationStore.replyables("Ama").size)
        // A bare app name with several chats is every one of them — the tool asks which.
        NotificationStore.put(rec("k3", "com.whatsapp", "Kofi", "Kofi", "WhatsApp", 3))
        assertTrue(NotificationStore.replyables("WhatsApp").size >= 2)
    }

    @Test fun `ongoing notifications are never reply targets`() {
        NotificationStore.put(rec("o1", "com.whatsapp", "WhatsApp", null, "WhatsApp", 9, ongoing = true))
        NotificationStore.put(rec("k1", "com.whatsapp", "Ama", "Ama", "WhatsApp", 1))
        assertEquals(listOf("k1"), NotificationStore.replyables(null).map { it.key })
    }

    @Test fun `an argument that is not a whole number stays null instead of becoming 0`() {
        val o = JSONObject().put("hour", "7am").put("ok", "7").put("n", 5)
        assertNull(o.intOrNull("hour"))         // used to be 0 → a midnight alarm
        assertEquals(7, o.intOrNull("ok"))
        assertEquals(5, o.intOrNull("n"))
        assertNull(o.intOrNull("missing"))
    }
}
