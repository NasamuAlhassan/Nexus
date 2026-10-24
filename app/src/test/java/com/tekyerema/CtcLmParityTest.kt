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
 * on this machine (`TEK_PARITY_DIR`, else build/offline_lm_all/offline_lm or campaign/evals/offline_lm). The LM half runs
 * when `twi_phone.arpa` sits beside `parity/`.
 */
class CtcLmParityTest {
    private val root: File? = listOfNotNull(System.getenv("TEK_PARITY_DIR"), "../build/offline_lm_all/offline_lm", "../campaign/evals/offline_lm")
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

    private fun norm(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    @Test fun matchesPyctcdecodeOnTheParityClips() {
        assumeTrue("parity folder not on this machine", root != null)
        val dir = File(root!!, "parity")
        val (vocab, blank) = OrtCtcAsr.readVocab(File(dir, "vocab.json").readText())
        val clips = JSONObject(File(dir, "manifest.json").readText()).getJSONArray("clips")
        val arpa = File(root, "twi_phone.arpa").takeIf { it.isFile }
        val lm = arpa?.bufferedReader()?.useLines { NgramLm.parseArpa(it) }
        val beam = CtcLmDecoder(vocab, blank, lm)
        var greedyOk = 0; var lmOk = 0; var lmN = 0; var ms = 0L
        for (i in 0 until clips.length()) {
            val c = clips.getJSONObject(i)
            val src = c.getJSONObject("sources").getJSONObject("phone")
            val logits = npy(File(dir, src.getString("logits")))
            if (norm(OrtCtcAsr.greedy(logits, vocab, blank)) == norm(src.getString("greedy"))) greedyOk++
            else println("greedy differs ${c.getString("id")}: ${OrtCtcAsr.greedy(logits, vocab, blank)} | ${src.getString("greedy")}")
            val want = src.optString("lm_pruned", "")
            if (lm != null && want.isNotEmpty()) {
                lmN++
                val t0 = System.nanoTime()
                val got = beam.decode(logits)
                ms += (System.nanoTime() - t0) / 1_000_000
                if (norm(got) == norm(want)) lmOk++ else println("LM differs ${c.getString("id")}:\n  ours $got\n  pyct $want")
            }
        }
        println("[parity] greedy $greedyOk/${clips.length()}; LM ${if (lm == null) "no ARPA" else "$lmOk/$lmN, ${if (lmN > 0) ms / lmN else 0} ms a clip on this JVM"}")
        assertEquals(clips.length(), greedyOk)
        if (lmN > 0) assertTrue("LM parity $lmOk/$lmN", lmOk * 10 >= lmN * 9)
    }
}
