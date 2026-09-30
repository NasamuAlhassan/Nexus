package com.tekyerema

import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.BrainContext
import com.tekyerema.agent.FastIntentClassifier
import com.tekyerema.agent.IntentMatcher
import com.tekyerema.agent.LocalBrain
import com.tekyerema.agent.WordPiece
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The offline meaning matcher (agent/IntentMatcher): its tokenizer, its bank and its act/ask rule. */
class IntentMatcherTest {

    private val assets = File("src/main/assets/intent")
    private val vocab by lazy { File(assets, "vocab.txt").readLines().withIndex().associate { (i, t) -> t to i } }

    @Test fun `WordPiece gives the reference tokenizer's ids`() {
        val probe = JSONArray(javaClass.classLoader!!.getResource("intent_token_probe.json")!!.readText())
        for (i in 0 until probe.length()) {
            val p = probe.getJSONObject(i)
            val want = p.getJSONArray("ids").let { a -> IntArray(a.length()) { a.getInt(it) } }
            assertEquals(p.getString("text"), want.toList(), WordPiece.encode(p.getString("text"), vocab).toList())
        }
    }

    @Test fun `a long request is cut at 64 tokens, still closed by SEP`() {
        val ids = WordPiece.encode("read ".repeat(200), vocab)
        assertEquals(64, ids.size)
        assertEquals(101, ids.first()); assertEquals(102, ids.last())
    }

    @Test fun `every bank command is one the phone already runs offline`() {
        val bank = JSONObject(File(assets, "bank.json").readText())
        val rows = bank.getJSONArray("rows")
        val canon = (0 until rows.length()).map { rows.getJSONObject(it).getString("c") }.toSortedSet()
        val dim = bank.getInt("dim")
        assertEquals(rows.length().toLong() * dim * 4, File(assets, "bank.f32").length())
        val unrun = canon.filter { c ->
            FastIntentClassifier.classify(c) == null && runBlocking {
                LocalBrain().decide(BrainContext(c, JSONObject(), emptyList(), JSONArray(), JSONArray())).let {
                    it !is AgentDecision.CallTool && it !is AgentDecision.Done
                }
            }
        }
        assertTrue("bank commands nothing runs offline: $unrun", unrun.isEmpty())
    }

    /** Opposites sit close in meaning (the matcher could only ask) — a complaint says the direction, so a rule decides it. */
    @Test fun `complaints about the sound or the screen go the right way`() {
        val want = mapOf(
            "the screen is too dim" to "BRIGHTNESS_UP", "the screen is too dark" to "BRIGHTNESS_UP",
            "the screen is too bright" to "BRIGHTNESS_DOWN", "brightness down" to "BRIGHTNESS_DOWN",
            "the volume is too low" to "VOLUME_UP", "the sound is too soft" to "VOLUME_UP",
            "the volume is too high" to "VOLUME_DOWN", "it's too loud" to "VOLUME_DOWN", "the music is too loud" to "VOLUME_DOWN")
        for ((say, intent) in want) assertEquals(say, intent, FastIntentClassifier.classify(say)?.name)
    }

    @Test fun `acts only on a clear winner, asks on a near one, else nothing`() {
        val c = listOf("volume up", "volume up", "volume down", "what time is it")
        fun d(vararg s: Float) = IntentMatcher.decide(c, s, act = 0.70f, margin = 0.08f, ask = 0.55f)
        assertEquals(IntentMatcher.Meaning.Act("volume up", 0.82f), d(0.82f, 0.60f, 0.70f, 0.1f))
        // Opposites close together: never act — ask.
        assertEquals(IntentMatcher.Meaning.Ask("volume up", 0.82f), d(0.82f, 0.60f, 0.78f, 0.1f))
        // A second phrase of the SAME command is not a rival.
        assertEquals(IntentMatcher.Meaning.Act("volume up", 0.82f), d(0.82f, 0.81f, 0.5f, 0.1f))
        assertEquals(IntentMatcher.Meaning.Ask("what time is it", 0.6f), d(0.1f, 0.1f, 0.1f, 0.6f))
        assertNull(d(0.3f, 0.2f, 0.5f, 0.54f))
    }

    @Test fun `a did-you-mean waits one minute and is taken once`() {
        IntentMatcher.suggest("how much juice is left", "how much battery do I have", now = 1_000L)
        assertTrue(IntentMatcher.hasPending(now = 30_000L))
        assertEquals("how much juice is left" to "how much battery do I have", IntentMatcher.takePending(now = 30_000L))
        assertNull(IntentMatcher.takePending(now = 30_001L))
        IntentMatcher.suggest("x", "go back", now = 0L)
        assertNull(IntentMatcher.takePending(now = 61_000L))
    }
}
