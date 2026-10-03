package com.tekyerema

import com.tekyerema.language.TwiCommands
import com.tekyerema.voice.Normalizer
import org.json.JSONObject
import org.junit.Test

/**
 * Scores a recogniser's GOLD transcripts the way the phone uses them (3 October 2026): through the phone's own repair
 * ([Normalizer.forTranslation]) and Twi commands ([TwiCommands.gloss]) — does the transcript lead to the SAME command as
 * what the owner actually said, and do the numbers survive? Reads audit/twi-plan-2026-10-02/gold_hyps_*.json
 * (`{"<system>": {"rows": [{"id","gold","hyp"}]}}`, e.g. the Kaggle diagnosis output), writes gold_command_scores.json.
 * Runs only where that untracked folder exists.
 */
class TwiGoldCommandScoreTest {
    private val digits = Regex("\\d+(?:\\.\\d+)?")

    @Test fun `score offline transcripts by the command they lead to`() {
        val dir = listOf(java.io.File("../audit/twi-plan-2026-10-02"), java.io.File("audit/twi-plan-2026-10-02")).firstOrNull { it.isDirectory } ?: return
        val inputs = dir.listFiles { f -> f.name.startsWith("gold_hyps_") && f.name.endsWith(".json") }.orEmpty()
        if (inputs.isEmpty()) return
        val out = JSONObject()
        for (f in inputs) {
            val all = JSONObject(f.readText())
            for (system in all.keys()) {
                val rows = all.getJSONObject(system).optJSONArray("rows") ?: continue
                var cmdGold = 0; var cmdSame = 0; var numGold = 0; var numSame = 0
                val misses = org.json.JSONArray()
                for (i in 0 until rows.length()) {
                    val r = rows.getJSONObject(i)
                    val gold = r.getString("gold"); val hyp = r.optString("hyp")
                    val want = TwiCommands.gloss(gold)
                    if (want != null) {
                        cmdGold++
                        val got = TwiCommands.gloss(hyp)
                        if (got == want) cmdSame++ else misses.put("${r.getString("id")}: want '$want' got '${got ?: "-"}' (heard '$hyp')")
                    }
                    val wantNums = digits.findAll(Normalizer.forTranslation(gold)).map { it.value }.toList()
                    if (wantNums.isNotEmpty()) {
                        numGold++
                        if (digits.findAll(Normalizer.forTranslation(hyp)).map { it.value }.toList() == wantNums) numSame++
                    }
                }
                out.put("${f.nameWithoutExtension}/$system", JSONObject()
                    .put("commands_same", "$cmdSame/$cmdGold").put("numbers_same", "$numSame/$numGold").put("misses", misses))
            }
        }
        java.io.File(dir, "gold_command_scores.json").writeText(out.toString(1))
    }
}
