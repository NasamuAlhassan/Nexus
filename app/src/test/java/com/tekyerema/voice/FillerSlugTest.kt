package com.tekyerema.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FillerSlugTest {
    @Test fun slugIsLowerCaseWordsJoinedByUnderscores() {
        assertEquals("let_me_check_that_for_you", FillerPlayer.slug("Let me check that for you."))
        assertEquals("okay_i_m_on_it", FillerPlayer.slug("Okay, I'm on it."))
    }

    /** Every default filler has its Kafui clip in assets (scripts/make_fillers.py), so none falls back to the British voice. */
    @Test fun everyDefaultFillerHasAClip() {
        val dir = listOf(File("src/main/assets/fillers"), File("app/src/main/assets/fillers")).first { it.isDirectory }
        for (p in FillerPlayer.DEFAULT_EN) assertTrue("no clip for \"$p\"", File(dir, FillerPlayer.slug(p) + ".wav").isFile)
    }
}
