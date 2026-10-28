package com.tekyerema

import com.tekyerema.camera.WhatIsThis
import com.tekyerema.camera.WhatIsThis.Kind
import com.tekyerema.camera.WhatIsThis.Label
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatIsThisTest {
    private val labels = listOf(
        Label("laptop", "laptop", true), Label("MacBook", "laptop", false), Label("Dell laptop", "laptop", false),
        Label("mobile phone", "mobile phone", true), Label("iPhone", "mobile phone", false), Label("tro-tro (minibus)", "vehicle", false),
        Label("vehicle", "vehicle", true))
    private val scale = 112.0          // SigLIP2's logit scale: a cosine lead of 0.02 is ~9.4x the mass

    @Test fun aClearBrandIsNamed() {
        val a = WhatIsThis.decide(doubleArrayOf(0.10, 0.17, 0.11, 0.05, 0.04, 0.02, 0.03), labels, scale)
        assertEquals(Kind.ITEM, a.kind); assertEquals("MacBook", a.label)
    }

    @Test fun twoBrandsTooCloseGiveTheFamily() {
        val a = WhatIsThis.decide(doubleArrayOf(0.165, 0.164, 0.164, 0.05, 0.04, 0.02, 0.03), labels, scale)   // the family label itself on top
        assertEquals(Kind.FAMILY, a.kind); assertEquals("laptop", a.label)
    }

    @Test fun twoFamiliesNeckAndNeckIsNotSaid() {
        val a = WhatIsThis.decide(doubleArrayOf(0.10, 0.17, 0.10, 0.10, 0.17, 0.02, 0.03), labels, scale, a = 0.6, b = 0.6)
        assertEquals(Kind.UNSURE, a.kind); assertNull(a.label)
        assertNull(WhatIsThis.spoken(a))
    }

    @Test fun namesAreSpokenNaturally() {
        assertEquals("a MacBook", WhatIsThis.spokenName("MacBook"))
        assertEquals("an iPhone", WhatIsThis.spokenName("iPhone"))
        assertEquals("a tro-tro, a minibus", WhatIsThis.spokenName("tro-tro (minibus)"))
        assertEquals("an okada, a motorbike taxi", WhatIsThis.spokenName("okada (motorbike taxi)"))
        assertEquals("an umbrella", WhatIsThis.spokenName("umbrella"))
        assertEquals("a USB flash drive", WhatIsThis.spokenName("USB flash drive"))
        assertEquals("keys on a key ring", WhatIsThis.spokenName("keys on a key ring"))
        assertEquals("a kitchen item", WhatIsThis.spokenName("kitchen item"))
        assertEquals("a hand", WhatIsThis.spokenName("hand"))
        assertEquals("That looks like a laptop, but I can't tell exactly which.",
            WhatIsThis.spoken(WhatIsThis.Answer("laptop", Kind.FAMILY, 0.5)))
    }

    @Test fun onlyNamingQuestionsGoToTheOfflineNamer() {
        for (q in listOf("What is this?", "what am I holding", "What's in front of me?", "can you tell me what this is", "identify this", "what are these"))
            assertTrue(q, WhatIsThis.isNamingQuestion(q))
        for (q in listOf("what does this say", "what colour is this shirt", "is the stove on", "when does this milk expire", "read this label", "what is the price"))
            assertFalse(q, WhatIsThis.isNamingQuestion(q))
    }
}
