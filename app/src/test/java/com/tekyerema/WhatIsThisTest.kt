package com.tekyerema

import com.tekyerema.camera.WhatIsThis
import com.tekyerema.camera.WhatIsThis.Kind
import com.tekyerema.camera.WhatIsThis.Label
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhatIsThisTest {
    private val labels = listOf(
        Label("laptop", "laptop", true), Label("MacBook", "laptop", false), Label("Dell laptop", "laptop", false),
        Label("mobile phone", "mobile phone", true), Label("iPhone", "mobile phone", false), Label("tro-tro (minibus)", "vehicle", false),
        Label("vehicle", "vehicle", true))
    // logit scale/bias like SigLIP's (~117, ~-12.7): a cosine of 0.16 is p ~0.99, 0.08 is p ~0.04
    private val scale = 117.0
    private val bias = -12.7

    @Test fun aClearBrandIsNamed() {
        val a = WhatIsThis.decide(doubleArrayOf(0.10, 0.17, 0.11, 0.05, 0.04, 0.02, 0.03), labels, scale, bias)
        assertEquals(Kind.ITEM, a.kind); assertEquals("MacBook", a.label)
    }

    @Test fun twoBrandsTooCloseGiveTheFamily() {
        val a = WhatIsThis.decide(doubleArrayOf(0.12, 0.165, 0.16, 0.05, 0.04, 0.02, 0.03), labels, scale, bias)
        assertEquals(Kind.FAMILY, a.kind); assertEquals("laptop", a.label)
    }

    @Test fun nothingCloseEnoughIsNotSaid() {
        val a = WhatIsThis.decide(doubleArrayOf(0.02, 0.03, 0.025, 0.028, 0.01, 0.02, 0.0), labels, scale, bias)
        assertEquals(Kind.UNSURE, a.kind); assertNull(a.label)
        assertNull(WhatIsThis.spoken(a))
    }

    @Test fun twoFamiliesNeckAndNeckIsNotSaid() {
        val a = WhatIsThis.decide(doubleArrayOf(0.10, 0.17, 0.10, 0.10, 0.165, 0.02, 0.03), labels, scale, bias)
        assertEquals(Kind.UNSURE, a.kind)
    }

    @Test fun namesAreSpokenNaturally() {
        assertEquals("a MacBook", WhatIsThis.spokenName("MacBook"))
        assertEquals("an iPhone", WhatIsThis.spokenName("iPhone"))
        assertEquals("a tro-tro, a minibus", WhatIsThis.spokenName("tro-tro (minibus)"))
        assertEquals("an okada, a motorbike taxi", WhatIsThis.spokenName("okada (motorbike taxi)"))
        assertEquals("an umbrella", WhatIsThis.spokenName("umbrella"))
        assertEquals("a USB flash drive", WhatIsThis.spokenName("USB flash drive"))
        assertEquals("keys on a key ring", WhatIsThis.spokenName("keys on a key ring"))
        assertEquals("That looks like a laptop, but I can't tell exactly which.",
            WhatIsThis.spoken(WhatIsThis.Answer("laptop", Kind.FAMILY, 0.5)))
    }
}
