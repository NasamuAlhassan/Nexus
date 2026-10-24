package com.tekyerema

import com.tekyerema.voice.CtcLmDecoder
import com.tekyerema.voice.NgramLm
import com.tekyerema.voice.OrtCtcAsr
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The phone's Twi beam search against pyctcdecode on the models session's parity clips (campaign/evals/offline_lm/, 24 Oct
 * 2026): 30 DEV clips of the phone's own int8 model, with pyctcdecode's greedy and LM texts. Skipped when the folder is not
 * on this machine (`TEK_PARITY_DIR`, else campaign/evals/offline_lm). The LM half runs when the phone ARPAs sit beside
 * `parity/`; it is judged on WER against the references, the strings are printed.
 */
class CtcLmParityTest {
    private val root: File? = listOfNotNull(System.getenv("TEK_PARITY_DIR"), "../campaign/evals/offline_lm", "../build/offline_lm_all/offline_lm")
        .map { File(it) }.firstOrNull { File(it, "parity/manifest.json").isFile }

    private fun npy(f: File): Array<FloatArray> {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val major = b.get(6).toInt()
        val headerLen = if (major == 1) b.getShort(8).toInt() and 0xffff else b.getInt(8)
        val start = if (major == 1) 10 else 12
        val header = String(f.readBytes(), start, headerLen, Charsets.ISO_8859_1)
        require("'<f4'" in header && "'fortran_order': False" in header) { "unexpected npy: $header" }
        val (t, v) = Regex("'shape': \\((\\d+), (\\d+)\\)").find(header)!!.destructured
        b.position(start + headerLen)
        return Array(t.toInt()) { FloatArray(v.toInt()) { b.float } }
    }

    /** Word-level edit distance between the reference words and [hyp] (for WER). */
    private fun wordErrors(ref: List<String>, hyp: String): Int {
        val h = hyp.split(' ').filter { it.isNotEmpty() }
        val d = IntArray(h.size + 1) { it }
        for (i in 1..ref.size) {
            var prev = d[0]; d[0] = i
            for (j in 1..h.size) { val t = d[j]; d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (ref[i - 1] == h[j - 1]) 0 else 1); prev = t }
        }
        return d[h.size]
    }

    private fun norm(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    @Test fun greedyMatchesOnThePhoneLogits() {
        assumeTrue("parity folder not on this machine", root != null)
        val dir = File(root!!, "parity")
        val (vocab, blank) = OrtCtcAsr.readVocab(File(dir, "vocab.json").readText())
        val clips = JSONObject(File(dir, "manifest.json").readText()).getJSONArray("clips")
        var ok = 0
        for (i in 0 until clips.length()) {
            val src = clips.getJSONObject(i).getJSONObject("sources").getJSONObject("phone")
            val got = OrtCtcAsr.greedy(npy(File(dir, src.getString("logits"))), vocab, blank)
            if (norm(got) == norm(src.getString("greedy"))) ok++ else println("greedy differs: $got | ${src.getString("greedy")}")
        }
        println("[parity] greedy $ok/${clips.length()}")
        assertEquals(clips.length(), ok)
    }

    /** The phone ARPA (`lm_pruned`, its own settings) and the recommended smaller one (`lm_pruned_bestdev`). */
    @Test fun lmBeamMatchesPyctcdecodeForBothPhoneArpas() {
        assumeTrue("parity folder not on this machine", root != null)
        val dir = File(root!!, "parity")
        val settings = JSONObject(File(dir, "settings.json").readText())
        val best = settings.getJSONObject("lm_pruned_bestdev")
        val runs = listOf(
            Triple("lm_pruned", settings.getString("lm_pruned_file"), Triple(settings.getDouble("alpha"), settings.getDouble("beta"), settings.getInt("beam_width"))),
            Triple("lm_pruned_bestdev", best.getString("file"), Triple(best.getDouble("alpha"), best.getDouble("beta"), best.getInt("beam"))))
        val (vocab, blank) = OrtCtcAsr.readVocab(File(dir, "vocab.json").readText())
        val clips = JSONObject(File(dir, "manifest.json").readText()).getJSONArray("clips")
        var ran = 0
        val misses = ArrayList<String>()
        for ((field, file, cfg) in runs) {
            val arpa = File(root, file).takeIf { it.isFile } ?: continue
            ran++
            val t0 = System.nanoTime()
            val lm = arpa.bufferedReader().useLines { NgramLm.parseArpa(it) }
            val loadMs = (System.nanoTime() - t0) / 1_000_000
            val dec = CtcLmDecoder(vocab, blank, lm, alpha = cfg.first.toFloat(), beta = cfg.second.toFloat(), beamWidth = cfg.third)
            var ok = 0; var n = 0; var ms = 0L
            var errOurs = 0; var errPy = 0; var errGreedy = 0; var words = 0
            for (i in 0 until clips.length()) {
                val c = clips.getJSONObject(i)
                val src = c.getJSONObject("sources").getJSONObject("phone")
                val want = src.optString(field, "")
                if (want.isEmpty()) continue
                n++
                val logits = npy(File(dir, src.getString("logits")))
                val t1 = System.nanoTime()
                val got = dec.decode(logits)
                ms += (System.nanoTime() - t1) / 1_000_000
                val ref = norm(c.optString("reference_norm", "")).split(' ').filter { it.isNotEmpty() }
                words += ref.size
                errOurs += wordErrors(ref, norm(got)); errPy += wordErrors(ref, norm(want))
                errGreedy += wordErrors(ref, norm(OrtCtcAsr.greedy(logits, vocab, blank)))
                if (norm(got) == norm(want)) ok++ else println("[$field] ${c.getString("id")}\n  ours ${norm(got)}\n  pyct ${norm(want)}")
            }
            println("[parity] $field ($file, alpha ${cfg.first} beta ${cfg.second} beam ${cfg.third}): $ok/$n exact; " +
                "ARPA load $loadMs ms, ${if (n > 0) ms / n else 0} ms a clip on this JVM; WER greedy ${"%.4f".format(errGreedy.toDouble() / words)} " +
                "ours ${"%.4f".format(errOurs.toDouble() / words)} pyctcdecode ${"%.4f".format(errPy.toDouble() / words)} ($words words)")
            // Parity is judged on accuracy against the references (24 Oct 2026: 21/30 strings identical, the rest one near-tie
            // word each, at equal or lower WER than pyctcdecode): no worse than pyctcdecode by half a point, and better than greedy.
            if (n == 0 || errOurs > errPy + words / 200 || errOurs >= errGreedy) misses += "$field: WER ours $errOurs, pyctcdecode $errPy, greedy $errGreedy of $words"
        }
        assumeTrue("no phone ARPA beside parity/", ran > 0)
        assertTrue(misses.joinToString("; "), misses.isEmpty())
    }
}
