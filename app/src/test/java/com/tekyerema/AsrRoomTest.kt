package com.tekyerema

import com.tekyerema.voice.AsrMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Making room for offline English (owner, 14 Oct 2026: "free up the memory space"). */
class AsrRoomTest {
    @Test fun only_the_users_own_apps_are_closed_never_the_ones_kept() {
        val installed = listOf("com.whatsapp" to false, "com.snapchat.android" to false, "com.tekyerema" to false,
            "com.sec.android.app.launcher" to true, "com.android.systemui" to true, "com.chrome.beta" to false, "com.whatsapp" to false)
        val keep = setOf("com.tekyerema", "com.chrome.beta", "com.sec.android.app.launcher")
        assertEquals(listOf("com.whatsapp", "com.snapchat.android"), AsrMemory.appsToClose(installed, keep))
    }

    @Test fun the_small_backup_model_fits_where_the_big_one_does_not() {
        // The A20e on 14 Oct 2026: ~650 MB available. The 630 MB Parakeet needs ~1.5 GB; Whisper base.en (154 MB) fits.
        val avail = 650L * AsrMemory.MB
        assertEquals(AsrMemory.Verdict.RELEASE_THEN_LOAD, AsrMemory.decide(avail, false, 630L * AsrMemory.MB, alreadyReleased = false).verdict)
        assertEquals(AsrMemory.Verdict.LOAD, AsrMemory.decide(avail, false, 154L * AsrMemory.MB, alreadyReleased = false).verdict)
    }

    @Test fun the_spoken_line_says_what_to_do() {
        assertTrue(AsrMemory.SPOKEN.contains("Restart the phone or type"))
    }
}
