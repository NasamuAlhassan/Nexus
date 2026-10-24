package com.tekyerema

import com.tekyerema.voice.CtcLmDecoder
import com.tekyerema.voice.NgramLm
import com.tekyerema.voice.OrtCtcAsr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

/** The phone's Twi beam search with a word language model (C-03 on the phone, 24 Oct 2026). Parity with pyctcdecode is
 *  checked separately on the models session's logits; these pin the mechanics. */
class CtcLmDecoderTest {
    private val vocab = arrayOf("[PAD]", "|", "d", "u", "a", "s", "i")
    private val arpa = """
        \data\
        ngram 1=5
        ngram 2=2

        \1-grams:
        -1.0	<s>	-0.3
        -1.0	</s>
        -0.5	du	-0.2
        -3.0	da	-0.2
        -2.0	sidi	-0.4
        -5.0	<unk>

        \2-grams:
        -0.1	sidi du
        -0.2	<s> sidi

        \end\
    """.trimIndent()
    private val lm = NgramLm.parseArpa(arpa.lineSequence())

    /** One frame: [probs] by token, the rest share what is left. */
    private fun frame(vararg probs: Pair<String, Double>): FloatArray {
        val p = DoubleArray(vocab.size) { 1e-4 }
        for ((t, v) in probs) p[vocab.indexOf(t)] = v
        return FloatArray(vocab.size) { ln(p[it]).toFloat() }
    }
    private fun spell(word: String) = word.map { frame(it.toString() to 0.99) }

    @Test fun arpaScoresWithBackoff() {
        assertEquals(-0.1f, lm.log10p(listOf("sidi"), "du"), 1e-6f)
        assertEquals(-0.4f + -3.0f, lm.log10p(listOf("sidi"), "da"), 1e-6f)   // backoff(sidi) + P(da)
        assertEquals(-5.0f, lm.log10p(emptyList(), "kofi"), 1e-6f)
        assertTrue(lm.isWord("sidi")); assertFalse(lm.isWord("sid"))
        assertTrue(lm.hasPrefix("si")); assertFalse(lm.hasPrefix("x"))
    }

    @Test fun clearSpeechIsWhatGreedyHears() {
        val logits = (spell("sidi") + listOf(frame("|" to 0.99)) + spell("du")).toTypedArray()
        assertEquals(OrtCtcAsr.greedy(logits, vocab, 0), CtcLmDecoder(vocab, 0, lm).decode(logits))
        assertEquals("sidi du", CtcLmDecoder(vocab, 0, null).decode(logits))
    }

    @Test fun theLanguageModelSettlesAnUnclearSound() {
        // "sidi d?" — the vowel is a near tie that leans to "a"; the words around it say "du".
        val logits = (spell("sidi") + listOf(frame("|" to 0.99), frame("d" to 0.99), frame("a" to 0.52, "u" to 0.47))).toTypedArray()
        assertEquals("sidi da", OrtCtcAsr.greedy(logits, vocab, 0))
        assertEquals("sidi du", CtcLmDecoder(vocab, 0, lm).decode(logits))
    }

    @Test fun repeatsNeedABlankBetween() {
        val logits = arrayOf(frame("d" to 0.99), frame("d" to 0.99), frame("u" to 0.99))
        assertEquals("du", CtcLmDecoder(vocab, 0, null).decode(logits))
    }

    @Test fun nothingHeardIsEmpty() {
        assertEquals("", CtcLmDecoder(vocab, 0, lm).decode(arrayOf(frame("[PAD]" to 0.99), frame("[PAD]" to 0.99))))
    }
}
