package com.tekyerema

import com.tekyerema.money.fraud.ScamScore
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The scam-message warning (MASTER_STATUS #13, 2 Nov 2026): Python/Kotlin parity of the model, the Ghana cues. */
class ScamScoreTest {
    private fun file(rel: String) = listOf(File(rel), File("app/$rel")).first { it.exists() }
    private val model by lazy { ScamScore.Model.parse(file("src/main/assets/fraud/sms_baseline.json").readText()) }

    @Test fun kotlin_scores_match_the_python_trainer() {
        val cases = JSONArray(file("src/test/resources/fraud/parity.json").readText())
        assertTrue(cases.length() >= 20)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val text = c.getString("text")
            assertEquals(text, c.getInt("buckets"), ScamScore.bucketsOf(text, model.buckets).size)
            assertEquals(text, c.getDouble("score"), model.score(text), 1e-6)
        }
    }

    @Test fun the_hash_is_fnv1a_32() {
        assertEquals(0x811C9DC5L, ScamScore.fnv1a(""))
        assertEquals(0xE40C292CL, ScamScore.fnv1a("a"))
        assertEquals(listOf("send", "zzcur", "zznum", "to", "zzphone", "zzurl"),
            ScamScore.tokens("Send ₵50 to 024 123 4567 http://x.co/a"))
    }

    @Test fun ghana_scams_warn_with_a_reason() {
        val scams = mapOf(
            "Pls i sent 150 cedis to ur number by mistake. Send it back now i beg" to ScamScore.Cue.WRONG_SEND,
            "Hello this is MTN. You sent money wrongly. Please return GHS 300 to 050 111 0922 now" to ScamScore.Cue.WRONG_SEND,
            "Your account will be BLOCKED today. Reply with your PIN and the code we sent to verify" to ScamScore.Cue.ASKS_PIN,
            "MTN PROMO: You have won GHS 10,000 in the Mega Draw. Send GHS 50 processing fee to claim" to ScamScore.Cue.PRIZE_FEE,
            "Police CID: your son is in custody. Pay bail GHS 800 to MoMo 0245 550 188 now" to ScamScore.Cue.AUTHORITY_PAY,
            "Your MoMo wallet is suspended. Verify at www.momo-gh.xyz today" to ScamScore.Cue.LINK_ACTION)
        for ((text, cue) in scams) {
            val v = ScamScore.check(text, model)
            assertTrue(text, v.warn)
            assertTrue(text, cue in v.cues)
            assertNotNull(v.line)
            assertTrue(v.line!!, v.line!!.startsWith("Careful: this may be a scam."))
        }
    }

    @Test fun genuine_messages_stay_quiet() {
        for (text in listOf(
                "Confirmed. You have received GHS 150.00 from KWASI OWUSU 024 *** *310. Current Balance GHS 152.40. Transaction ID 7731",
                "Reversal successful. GHS 80.00 returned to your wallet. Reference 55820134. Current Balance GHS 233.60. Fee GHS 0.00.",
                "Bring kenkey when you come", "The cloth is ready", "Call me when you're free")) {
            val v = ScamScore.check(text, model)
            assertFalse(text, v.warn)
            assertNull(v.line)
        }
        // MTN's own advice names the PIN but asks for nothing.
        assertTrue(ScamScore.cues("MTN will never ask for your PIN. Do not share your PIN or OTP with anyone.").isEmpty())
        assertTrue(ScamScore.cues("Never send your PIN to anyone.").isEmpty())
    }

    @Test fun the_spoken_warning_comes_before_the_words_and_never_on_the_network_sender() {
        val scam = "Pls i sent 150 cedis to ur number by mistake. Send it back now i beg"
        val line = com.tekyerema.money.fraud.ScamWarning.before("024 955 0033", scam)
        assertNotNull(line)
        assertFalse(line!!, line.contains("150") || line.contains("024"))          // the reason never quotes the message
        assertNull(com.tekyerema.money.fraud.ScamWarning.before("MobileMoney", scam))  // MoMo's own sender id: never flagged
        assertNull(com.tekyerema.money.fraud.ScamWarning.before("Ama", "Bring kenkey when you come"))
        val read = com.tekyerema.notifications.MessageReading.item(com.tekyerema.notifications.MessageReading.Msg("024 955 0033", null, null, scam))
        assertTrue(read, read.indexOf("Careful: this may be a scam.") in 0 until read.indexOf("by mistake"))
    }

    @Test fun without_a_model_the_cues_still_work() {
        assertTrue(ScamScore.check("I sent you 20 cedis by mistake, please send it back", null).warn)
        assertFalse(ScamScore.check("See you at church", null).warn)
    }
}
