package com.tekyerema

import com.tekyerema.agent.FastIntent
import com.tekyerema.agent.FastIntentClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P5-02: a picture the user already has goes to the photo picker + reader; "read this label" still means the live camera. */
class ReadPhotoIntentTest {
    private fun c(s: String) = FastIntentClassifier.classify(s)

    @Test fun `a picture that exists somewhere is picked, not filmed`() {
        assertEquals(FastIntent.ReadPhoto("text"), c("read a photo from my gallery"))
        assertEquals(FastIntent.ReadPhoto("text"), c("read the picture I took"))
        assertEquals(FastIntent.ReadPhoto("text"), c("read the last screenshot"))
        assertEquals(FastIntent.ReadPhoto("text"), c("pick a picture from my gallery"))   // "pick a picture and read it" is compound → the agent, by design
        assertEquals(FastIntent.ReadPhoto("text"), c("read a photo from my files"))
        assertEquals(FastIntent.ReadPhoto("describe"), c("describe a picture from my gallery"))
        assertEquals(FastIntent.ReadPhoto("describe"), c("what is in the latest photo"))
    }

    @Test fun `the live camera forms are unchanged`() {
        assertEquals(FastIntent.Camera("text"), c("read this label"))
        assertEquals(FastIntent.Camera("text"), c("what does this sign say"))
        assertTrue(c("what's in front of me") is FastIntent.Camera)
    }
}
