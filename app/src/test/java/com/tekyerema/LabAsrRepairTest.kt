package com.tekyerema

import com.tekyerema.language.TwiCommands
import com.tekyerema.voice.Lang
import com.tekyerema.voice.Normalizer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The UG HCI Lab recogniser on the owner's own GOLD Twi recordings (audit/lab-asr-2026-10-02, 2 October 2026): what it
 * writes, and the repairs that turn it back into what was said. The transcripts below are its real output.
 */
class LabAsrRepairTest {
    private fun twi(s: String) = Normalizer.normalize(s, Lang.TWI)

    @Test fun `zero as the recogniser hears it is a digit beside a numeral, and only there`() {
        assertTrue(twi("sro baako mmienu mmeɛnsa anan anum").startsWith("0"))
        assertTrue(twi("sio mmienu anan").startsWith("0"))
        assertTrue(twi("sere anum anan").startsWith("0"))
        assertEquals("sere", twi("sere"))                 // alone it is an ordinary word
        assertTrue(twi("mmeɛnsa").contains("3"))
    }

    @Test fun `money words come back`() {
        assertTrue(twi("sidi anua").contains("5"))        // "cedi anum" heard as "sidi anua", 3 of 4 times
        assertFalse(twi("anua").contains("5"))             // not without a currency word before it
        assertTrue(twi("pasea aduonum").let { it.contains("pesewa") && it.contains("50") })
        assertTrue(twi("sidi du ne pɛ soa aduonum").let { it.contains("pesewa") && it.contains("50") && it.contains("10") })
        assertTrue(twi("to masi di mmienu ma me yere").contains("cedi 2"))
        assertTrue(twi("ɛno yɛ sidi du mmienu").endsWith("cedi 12"))   // "dumienu" heard in two words
    }

    @Test fun `the owner's first Twi request is understood - me etam no akasa was me airtime no aka sen`() {
        assertEquals("check my airtime balance", TwiCommands.gloss("me ɛtam no akasa"))
        assertEquals("message", twi("mesege"))
        assertEquals("me ɛtam no akasa".let { "me ɛtam no akasa" }, "me ɛtam no akasa")   // the raw transcript is kept elsewhere
        // "akasa" alone is "has spoken" — never turned into "how much" without airtime / credit / money before it.
        assertEquals("ɔno akasa", twi("ɔno akasa"))
    }

    /** Re-scores the three saved HCI Lab transcripts through the repairs, writing them next to the evidence — no new call.
     *  Runs only where the (untracked) audit folder exists. */
    @Test fun `rescore the saved transcripts`() {
        val dir = listOf(java.io.File("../audit/lab-asr-2026-10-02"), java.io.File("audit/lab-asr-2026-10-02")).firstOrNull { it.isDirectory } ?: return
        val res = JSONObject(java.io.File(dir, "lab_asr_results.json").readText())
        val refs = JSONObject(java.io.File(dir, "refs.json").readText())
        val out = JSONObject()
        for (g in res.keys()) {
            val hyp = JSONObject(res.getJSONObject(g).getString("raw")).getString("transcription")
            val pairs = refs.getJSONObject(g).getJSONArray("refs")
            val ref = (0 until pairs.length()).joinToString(" ") { pairs.getJSONArray(it).getString(1) }
            out.put(g, JSONObject().put("hyp_raw", hyp).put("hyp_repaired", twi(hyp)).put("ref_normalized", twi(ref)))
        }
        java.io.File(dir, "rescore.json").writeText(out.toString(1))
    }
}
