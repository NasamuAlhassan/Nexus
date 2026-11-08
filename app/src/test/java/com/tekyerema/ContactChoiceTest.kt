package com.tekyerema

import com.tekyerema.agent.References
import com.tekyerema.android.ContactResolver.Contact
import com.tekyerema.tools.ContactChoice
import com.tekyerema.tools.ReplyTarget
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A20e audit 2026-10-01 (C15, C07, F07): "call Prince" with fourteen matches produced a question cut mid-word, asked
 * twice, without the likely people, and with five identical "Prince Alhassan"s. And C17: "reply to the last message"
 * must find the sender, and say so when that sender can't be replied to.
 */
class ContactChoiceTest {
    // The phone's own "Prince" matches (numbers as stored — some with +233, some with 0, one bare).
    private val princes = listOf(
        Contact("Prince Tesano AG youth Sec", "+233 55 301 1385"),
        Contact("Prince Ice P", "+233536523611"),
        Contact("Princess", "+233538185281"),
        Contact("Prince Melanin", "+233552093690"),
        Contact("Prince Alhassan", "050 879 7323"),
        Contact("Prince Alhassan", "055 991 6720"),
        Contact("Prince Alhassan", "0552093690"),
        Contact("Prince Alhassan", "552093690")          // the same entry saved a second time, without the 0
    )

    private fun tail(n: String) = n.filter { it.isDigit() }.takeLast(9)

    @Test fun at_most_three_options_recent_people_first_twins_told_apart() {
        val last = mapOf("559916720" to 900L, "552093690" to 500L)
        val c = ContactChoice.build("Prince", princes) { last[tail(it)] ?: 0L }
        assertEquals(listOf("Prince Alhassan ending 6720", "Prince Melanin", "Prince Alhassan ending 3690"), c.options.map { it.label })
        assertEquals("Which Prince: Prince Alhassan ending 6720, Prince Melanin, or Prince Alhassan ending 3690? Or someone else?", c.question)
        assertEquals("one saved twice is one person: 7 people, 3 read out", 4, c.more)
        assertTrue("a listener can hold it", c.question.length < 130)
        // The planner-facing line keeps the shape the engine already reads its options from.
        assertEquals(c.options.map { it.label }, References.optionsFrom(c.message))
        assertTrue(c.message, c.message.length <= 160)
        assertEquals("055 991 6720", c.options[0].number)
    }

    @Test fun the_question_has_its_own_twi_wording_names_for_the_english_voice() {
        val c = ContactChoice.build("Prince", princes)
        val en = com.tekyerema.language.EntityTags.EN_OPEN; val close = com.tekyerema.language.EntityTags.EN_CLOSE
        assertEquals("Hena na wopɛ? ${en}Prince Tesano AG youth Sec$close, ${en}Prince Ice P$close anaa ${en}Princess$close. Anaa obi foforɔ?", c.twiQuestion)
        // the Twi words (the frame, "anaa" — or) are Twi; each name its own English piece (the line said "or" in Twi on the
        // A20e, 5 October 2026)
        assertEquals(listOf(false, true, false, true, false, true, false),
            com.tekyerema.language.EntityTags.segments(c.twiQuestion).map { it.second })
        assertEquals("Hena na wopɛ? ${en}Prince Tesano AG youth Sec$close, ${en}Prince Ice P$close anaa ${en}Princess$close. Anaa obi foforɔ?",
            ContactChoice.build("my brother", princes).twiQuestion)
    }

    @Test fun without_any_call_history_the_address_book_order_is_kept() {
        val c = ContactChoice.build("Prince", princes)
        assertEquals(listOf("Prince Tesano AG youth Sec", "Prince Ice P", "Princess"), c.options.map { it.label })
        assertTrue(c.question.endsWith("Or someone else?"))
        val two = ContactChoice.build("Ama", listOf(Contact("Ama Mensah", "0244123456"), Contact("Ama Boateng", "0551234567")))
        assertEquals("Which Ama: Ama Mensah, or Ama Boateng?", two.question)
        assertEquals(0, two.more)
    }

    @Test fun an_offered_option_resolves_back_to_its_one_entry() {
        assertEquals("Prince Alhassan" to "6720", ContactChoice.narrow("Prince Alhassan ending 6720"))
        assertEquals("Prince Alhassan" to "0559916720", ContactChoice.narrow("Prince Alhassan on 055 991 6720"))
        assertEquals("Ama" to null, ContactChoice.narrow("Ama"))
        assertEquals("Auntie on 5" to null, ContactChoice.narrow("Auntie on 5"))
        val alhassans = princes.filter { it.name == "Prince Alhassan" }
        assertEquals(listOf("055 991 6720"), ContactChoice.withEnding(alhassans, "720").map { it.number })
        assertEquals(listOf("055 991 6720"), ContactChoice.withEnding(alhassans, "0559916720").map { it.number })
        assertEquals(2, ContactChoice.withEnding(alhassans, "690").size)                  // the twice-saved entry…
        assertEquals(1, ContactChoice.distinct(ContactChoice.withEnding(alhassans, "690")).size)   // …is one person
    }

    @Test fun the_picked_option_goes_back_into_the_same_tool_call() {
        val c = ContactChoice.build("Prince", princes) { if (tail(it) == "559916720") 9L else 0L }
        val data = JSONObject().put("choices", org.json.JSONArray(c.options.map {
            JSONObject().put("label", it.label).put("name", it.name).put("number", it.number) }))
            .put("options", org.json.JSONArray(c.options.map { it.label }))
        assertEquals(c.options.map { it.label }, ContactChoice.optionsOf(data))
        val args = JSONObject().put("contact", "Prince")
        assertTrue(ContactChoice.apply(args, data, "prince alhassan ending 6720"))
        assertEquals("Prince Alhassan", args.getString("contact")); assertEquals("055 991 6720", args.getString("number"))
        assertFalse(ContactChoice.apply(JSONObject(), data, "Kofi"))
        assertTrue(ContactChoice.optionsOf(null).isEmpty())
    }

    @Test fun a_reply_to_the_last_message_finds_the_sender_or_says_why_not() {
        for (c in listOf("last sender", "the last message", "The Last Text", "the sender", "most recent message"))
            assertTrue(c, ReplyTarget.meansLastSender(c))
        for (c in listOf("Ama", "Last Born", "Message Centre", null)) assertFalse("$c", ReplyTarget.meansLastSender(c))
        assertTrue(ReplyTarget.isServiceSender("MobileMoney"))
        assertTrue(ReplyTarget.isServiceSender("1355"))
        assertFalse(ReplyTarget.isServiceSender("0244123456"))
        assertFalse(ReplyTarget.isServiceSender("+233 24 412 3456"))
        val line = ReplyTarget.cannotReplyLine("MobileMoney")
        assertTrue(line, line.contains("MobileMoney") && line.contains("can't receive replies") && line.contains("haven't sent"))
    }
}
