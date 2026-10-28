package com.tekyerema

import com.tekyerema.android.ContactResolver.Contact
import com.tekyerema.money.MtnApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "send 5 cedis to my wife" in MTN's app: who is settled from the contacts, not asked (Infinix, 28 Oct 2026). */
class MtnAppWhoTest {
    private val book = mapOf("my wife" to listOf(Contact("My Wife", "055 209 3690")),
        "prince" to listOf(Contact("Prince A", "0241111111"), Contact("Prince B", "0242222222")))
    private val lookup = { n: String -> book[n.lowercase()] ?: emptyList() }

    @Test fun oneContactIsTheFact() {
        val w = MtnApp.who("send 5 cedis to my wife", lookup)!!
        assertTrue(w, w.startsWith("WHO is settled") && "My Wife" in w && "055 209 3690" in w)
    }

    @Test fun severalAreAskedByNameAndNoneAsksTheNumber() {
        assertTrue(MtnApp.who("send 5 cedis to Prince", lookup)!!.contains("several contacts (Prince A, Prince B)"))
        assertTrue(MtnApp.who("send 5 cedis to Kwabena", lookup)!!.contains("not in the phone's contacts"))
    }

    @Test fun aNumberNeedsNoLookup() {
        assertNull(MtnApp.who("send 5 cedis to 0552093690", lookup))
        assertNull(MtnApp.who("what's my momo balance", lookup))
    }
}
