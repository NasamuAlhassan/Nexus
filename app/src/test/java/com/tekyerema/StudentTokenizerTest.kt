package com.tekyerema

import com.tekyerema.language.StudentTokenizer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The Kotlin port of the student translator's sentencepiece tokenizer must match the real one on every golden pair. */
class StudentTokenizerTest {
    private val tok = StudentTokenizer(StudentTokenizer.parseTsv(
        File("src/main/assets/mt/student/bpe_merges.tsv").readLines(Charsets.UTF_8).asSequence()))

    @Test fun `matches the real sentencepiece on every golden pair`() = check("golden_tokenizer.json", 42)

    /** Odd Unicode: zero-width and soft-hyphen characters, no-break spaces, decomposed accents, Twi capitals, CRLF, the cedi sign. */
    @Test fun `matches on the Unicode edge cases`() = check("golden_tokenizer_edge.json", 19)

    private fun check(file: String, min: Int) {
        val pairs = JSONObject(File("src/test/resources/mt/$file").readText(Charsets.UTF_8)).getJSONArray("pairs")
        assertTrue(pairs.length() >= min)
        val bad = ArrayList<String>()
        for (i in 0 until pairs.length()) {
            val p = pairs.getJSONObject(i)
            val want = (0 until p.getJSONArray("ids").length()).map { p.getJSONArray("ids").getLong(it) }
            val got = tok.encode(p.getString("text")).toList()
            if (got != want) bad.add("#$i ${p.getString("text").take(40).replace("\n", "\\n")} want=${want.take(14)} got=${got.take(14)}")
        }
        assertEquals(bad.joinToString("\n"), 0, bad.size)
    }
}
