package com.tekyerema

import com.tekyerema.language.EntityTags
import com.tekyerema.language.TwiCommands
import com.tekyerema.voice.Normalizer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/**
 * Writes what each stage of the Twi chain makes of real HCI Lab transcripts and demo phrases (repair → phone command →
 * tags), for the PC-side check that sends the tagged text through our translator Space
 * (audit/twi-plan-2026-10-02/chain.py). Runs only where that untracked folder exists.
 */
class TwiChainDumpTest {
    private val inputs = listOf(
        // the HCI Lab's own transcripts (owner's GOLD recordings and the 2 Oct test)
        "me ɛtam no akasa", "somatensidisma ziroffvtziro ne tsi sir", "so masi di anua ama", "soma no ma no sidi mmienu",
        "to masi di mmienu ma me yere", "wopɛ sɛ mesoma sidi anua kwaa me", "frɛ kwame", "kofi mɛsege",
        "kɔmmu na soma sidi anua ama", "ɛno yɛ sidi du mmienu",
        // demo phrases as typed / clean speech
        "Soma sidi anum ma Ama", "Frɛ Kofi", "Twerɛ nkra kɔ ma Ama sɛ meba", "Mepɛ sɛ mesoma cedi du ma Kofi",
        "Ka kyerɛ Kwame sɛ meba anɔpa", "Soma sidi du kɔ 0552093690", "Me kredit aka sɛn?", "ɛberɛ bɛn")

    @Test fun `dump the chain`() {
        val dir = listOf(java.io.File("../audit/twi-plan-2026-10-02"), java.io.File("audit/twi-plan-2026-10-02")).firstOrNull { it.isDirectory } ?: return
        val out = JSONArray()
        for (s in inputs) {
            val repaired = Normalizer.forTranslation(s)
            val command = TwiCommands.gloss(s)
            val tagged = EntityTags.protect(repaired)
            out.put(JSONObject().put("heard", s).put("repaired", repaired).put("phone_command", command ?: JSONObject.NULL)
                .put("tagged", tagged.text).put("tags", JSONArray(tagged.entities.map { "${it.tag}=${it.value}" })))
        }
        java.io.File(dir, "chain_in.json").writeText(out.toString(1))
    }
}
