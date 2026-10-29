package com.tekyerema

import com.tekyerema.language.TwiKeywords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Offline Twi the exact patterns miss, guessed from its key words (owner, 29 Oct 2026). */
class TwiKeywordsTest {
    private val contacts = setOf("kofi", "my wife", "prince melanin")
    private fun g(s: String) = TwiKeywords.guess(s) { it.lowercase() in contacts }

    @Test fun `a message someone sent, however the recogniser spells it`() {
        assertEquals("read the message my wife sent", g("kenkran nkran no a me yere so ma me no"))
        assertEquals("read the message my wife sent", g("kɛnkan nkoraa bi a meyere de abrɛ me"))
        assertEquals("read the message my wife sent", g("nkrataa a me yere de kɔmaa me no"))
        assertEquals("read the message my husband sent", g("tie nkra a me kunu de somaa me"))
        assertEquals("read the message Kofi sent", g("kenkan nkra bi a kofi de brɛɛ me"))
        assertEquals("read my messages", g("kankan me nkra nyinaa"))
    }

    @Test fun `money only with an amount and a person, and the balance`() {
        assertEquals("send 5 cedis to my wife", g("me pɛ sɛ me soma sidi 5 kɔma me yere"))
        assertEquals("send 1 cedi to my husband", g("fa sika baako ma me kunu"))
        assertNull(g("soma sika ma me yere"))                 // no amount: not guessed (the money flow is reached the exact way)
        assertNull(g("soma sidi 5"))                          // no person
        assertEquals("check my mobile money balance", g("me momo sika no aka sɛn"))
    }

    @Test fun `never a call, a message to send, or nothing in particular`() {
        assertNull(g("frɛɛ me yere seesei"))
        assertNull(g("soma nkra kɔma me yere"))               // sending a message: its words are needed, not guessed
        assertNull(g("ɛte sɛn"))
        assertNull(g("me yere"))
        assertNull(g("kenkran"))
    }

    @Test fun `the time`() {
        assertEquals("what's the time", g("bere bɛn na ɛyɛ seesei"))
    }
}
