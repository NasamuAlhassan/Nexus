package com.tekyerema

import com.tekyerema.voice.GhanaWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * voice/GhanaWords.kt against what the shipped offline recogniser REALLY wrote for the owner's 92 recordings (72 gold +
 * 20 eval clips; `asr/shipped_parakeet_owner_clips_2026-10-02.tsv`, produced by build/asr_bench/hot_test2.py).
 *
 * This is the measurement quoted in docs/agent/research/ghanaian_accent_speech_2026-10-02.md. It is IN-SAMPLE: the
 * rules were written from these clips. What it guards is (a) that the rules keep doing what they were written to do
 * and (b) that they change nothing else — no clip without one of the words may be touched.
 */
class GhanaWordsOnRealOutputTest {
    private class Row(val id: String, val said: String, val wrote: String)

    private val rows: List<Row> = javaClass.classLoader!!.getResourceAsStream("asr/shipped_parakeet_owner_clips_2026-10-02.tsv")!!
        .bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        .map { it.split("\t") }.map { Row(it[1], it[2], it[3]) }

    private val terms = listOf("cedi", "pesewa", "momo", "whatsapp")
    private fun fold(s: String) = s.lowercase().replace('ɛ', 'e').replace('ɔ', 'o')
    private fun recall(term: String, out: (Row) -> String): Pair<Int, Int> {
        val with = rows.filter { term in fold(it.said) }
        return with.count { term in fold(out(it)) } to with.size
    }

    @Test fun `the fixture is the 92 clips`() = assertEquals(92, rows.size)

    @Test fun `as written by the recogniser the money words are mostly lost`() {
        assertEquals(7 to 20, recall("cedi") { it.wrote })
        assertEquals(0 to 3, recall("pesewa") { it.wrote })
        assertEquals(0 to 1, recall("momo") { it.wrote })
        assertEquals(0 to 3, recall("whatsapp") { it.wrote })
    }

    @Test fun `after the repairs they are there`() {
        val fixed = { r: Row -> GhanaWords.fix(r.wrote) }
        val cedi = recall("cedi", fixed)
        println("cedi ${cedi.first}/${cedi.second}, pesewa ${recall("pesewa", fixed)}, momo ${recall("momo", fixed)}, whatsapp ${recall("whatsapp", fixed)}")
        assertTrue("cedis: $cedi", cedi.first >= 18 && cedi.second == 20)
        assertEquals(3 to 3, recall("pesewa", fixed))
        assertEquals(1 to 1, recall("momo", fixed))
        assertEquals(3 to 3, recall("whatsapp", fixed))
    }

    @Test fun `a clip that has none of the words is never changed`() {
        val touched = rows.filter { r -> terms.none { it in fold(r.said) } && GhanaWords.fix(r.wrote) != r.wrote }
        assertTrue(touched.joinToString("; ") { "${it.id}: '${it.wrote}' → '${GhanaWords.fix(it.wrote)}'" }, touched.isEmpty())
    }

    @Test fun `no repair puts a money word where none was said`() {
        val invented = rows.filter { r -> terms.any { it in fold(GhanaWords.fix(r.wrote)) && it !in fold(r.said) } }
        assertTrue(invented.joinToString("; ") { "${it.id}: said '${it.said}', became '${GhanaWords.fix(it.wrote)}'" }, invented.isEmpty())
    }
}
