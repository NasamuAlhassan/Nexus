package com.tekyerema

import com.tekyerema.agent.LocalBrain
import com.tekyerema.voice.understand.MessageBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The messaging-fidelity defects of the 7 October handover (B-01, B-02, B-03): "send a message to X and tell him that …"
 * made the recipient "X and tell him"; the that/saying paths never adapted the recipient's pronouns; a message with no
 * marker word at all went to the planner. The words the user dictates reach the read-back exactly; only a leading
 * third-person pronoun in a tell-form is turned to the recipient.
 */
class MessagingParseTest {
    private fun p(s: String) = LocalBrain.parseSms(s)

    @Test fun `and tell him that - the recipient is the name, the message follows the tell`() {
        val a = p("Send a message to Prince and tell him that he should call me")!!
        assertEquals("Prince", a.contact)
        assertEquals("you should call me", a.message)
        val b = p("send a WhatsApp message to Ama and tell her that her package has arrived")!!
        assertEquals("Ama", b.contact); assertEquals(true, b.whatsApp)
        assertEquals("your package has arrived", b.message)
        val c = p("text Kofi telling him that the meeting moved to 3")!!
        assertEquals("Kofi", c.contact); assertEquals("the meeting moved to 3", c.message)
        val d = p("message 0244123456 and tell them that we are outside")!!
        assertEquals("0244123456", d.number); assertNull(d.contact); assertEquals("we are outside", d.message)
    }

    @Test fun `tell X that - leading he she they or his her their become you your, nothing else is touched`() {
        assertEquals("you should come early", p("tell Ama that she should come early")!!.message)
        assertEquals("you are late", p("tell Kofi that he is late")!!.message)
        assertEquals("you have to bring the charger", p("tell Kofi that he has to bring the charger")!!.message)
        assertEquals("your package arrived", p("tell Kofi that his package arrived")!!.message)
        // first person, or a third person who is not the recipient: word for word
        assertEquals("I will be late", p("tell Ama that I will be late")!!.message)
        assertEquals("Kofi said he is coming", p("tell Ama that Kofi said he is coming")!!.message)
        // "saying" dictates exact words — never adapted, even in the third person
        assertEquals("he should call me", p("text Prince saying he should call me")!!.message)
        assertEquals("you should call me", LocalBrain.adaptForRecipient("he should call me"))
        assertEquals("your turn", LocalBrain.adaptForRecipient("his turn"))
        assertEquals("it is raining", LocalBrain.adaptForRecipient("it is raining"))
    }

    @Test fun `no marker word - a first-person clause after the name is the message`() {
        val a = p("tell Ama I'm on my way")!!
        assertEquals("Ama", a.contact); assertEquals("I'm on my way", a.message)
        val b = p("text Kofi we are leaving now")!!
        assertEquals("Kofi", b.contact); assertEquals("we are leaving now", b.message)
        val c = p("message Prince Melanin please call me when you are free")!!
        assertEquals("Prince Melanin", c.contact); assertEquals("please call me when you are free", c.message)
        val d = p("whatsapp Ama good morning")!!
        assertEquals("Ama", d.contact); assertEquals("good morning", d.message); assertEquals(true, d.whatsApp)
        // not messages to a person
        assertNull(p("tell me I am great"))
        assertNull(p("tell me a joke"))
        assertNull(p("tell us the time"))
    }

    @Test fun `the earlier shapes still parse the same`() {
        assertEquals("I'm on my way", p("text Prince saying I'm on my way")!!.message)
        assertEquals("Prince", p("text Prince saying I'm on my way")!!.contact)
        assertEquals("my brother", p("text my brother saying hi")!!.contact)
        assertEquals("Please call your mother.", p("tell Kwame to call his mother")!!.message)
        assertEquals("I'll call later", p("tell Prince Melanin on WhatsApp that I'll call later")!!.message)
        assertNull(p("text him saying hello"))
    }

    @Test fun `the speech layer protects the same bodies from repairs`() {
        assertEquals("send a message to Prince " to "and tell him that he should call me",
            MessageBody.split("send a message to Prince and tell him that he should call me"))
        assertEquals("tell Ama " to "I'm on my way", MessageBody.split("tell Ama I'm on my way"))
        assertEquals("text Kofi " to "we are leaving at five", MessageBody.split("text Kofi we are leaving at five"))
        // an explicit marker still wins over a first-person word inside the recipient part
        assertEquals("message the guy I met " to "saying hi", MessageBody.split("message the guy I met saying hi"))
        // no name between the verb and the clause: nothing to split
        assertEquals("tell I am here" to "", MessageBody.split("tell I am here"))
        assertEquals("open settings" to "", MessageBody.split("open settings"))
    }
}
