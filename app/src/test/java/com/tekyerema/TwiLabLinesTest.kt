package com.tekyerema

import com.tekyerema.android.ContactResolver
import com.tekyerema.language.EntityTags
import com.tekyerema.language.HttpSpeech
import com.tekyerema.language.TwiText
import com.tekyerema.tools.ContactChoice
import com.tekyerema.voice.ConfirmTemplates
import com.tekyerema.voice.ConfirmTemplates.Spec
import com.tekyerema.voice.SpokenText
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every FIXED Twi line the app speaks in a Twi conversation — the confirmation frames, the "who do you want?" question,
 * the re-asks, pause / wait / secure-step lines — keyed EXACTLY as the lab voice looks a clip up (HttpTtsProvider:
 * plain → canonical → parts → "twi|<part>"). campaign/twi_lab_clips.py records them once in the HCI Lab's voice into
 * assets/voice/twi_lab/ (owner, 3 October 2026: the lab voice is the good one; make it instant). Writes
 * build/twi_lab_lines.json, and reports which lines have no clip yet.
 */
class TwiLabLinesTest {
    /** The keys the lab voice uses for a line handed to it (RoutingTts → HttpTtsProvider). */
    private fun keys(line: String): List<String> =
        HttpSpeech.parts(TwiText.canonical(SpokenText.plain(TwiText.canonical(EntityTags.display(line)))).trim(), 250).map { "twi|$it" }

    /** The Twi pieces of a template line (English stretches are the English voice's). */
    private fun twiPieces(line: String) = EntityTags.segments(line).filter { !it.second }.map { it.first }

    /** Pieces that are the same whatever the slots hold — two fillings of the same template, intersected. */
    private fun fixed(a: Spec, b: Spec): Set<String> = twiPieces(ConfirmTemplates.twi(a)).toSet() intersect twiPieces(ConfirmTemplates.twi(b)).toSet()

    private val sources = linkedMapOf<String, String>()   // line → where it comes from

    private fun add(line: String, from: String) { if (line.isNotBlank()) sources.putIfAbsent(line, from) }

    @Test fun `list every fixed Twi line`() {
        val k = Spec.Kind.entries
        fixed(Spec(Spec.Kind.CALL, name = "Ama", number = "0244000011"), Spec(Spec.Kind.CALL, number = "0551234567")).forEach { add(it, "ConfirmTemplates CALL") }
        fixed(Spec(Spec.Kind.SEND_MONEY, name = "Ama", number = "0244000011", amount = "5"), Spec(Spec.Kind.SEND_MONEY, number = "0551234567", amount = "0.5"))
            .forEach { add(it, "ConfirmTemplates SEND_MONEY") }
        fixed(Spec(Spec.Kind.SEND_MONEY, name = "Ama"), Spec(Spec.Kind.SEND_MONEY, number = "0551234567")).forEach { add(it, "ConfirmTemplates SEND_MONEY (no amount)") }
        fixed(Spec(Spec.Kind.BUY_AIRTIME, amount = "5"), Spec(Spec.Kind.BUY_AIRTIME, amount = "2")).forEach { add(it, "ConfirmTemplates BUY_AIRTIME") }
        twiPieces(ConfirmTemplates.twi(Spec(Spec.Kind.BUY_AIRTIME))).forEach { add(it, "ConfirmTemplates BUY_AIRTIME (no amount)") }
        fixed(Spec(Spec.Kind.SEND_MESSAGE, name = "Ama"), Spec(Spec.Kind.SEND_MESSAGE, name = "Kofi")).forEach { add(it, "ConfirmTemplates SEND_MESSAGE") }
        twiPieces(ConfirmTemplates.twi(Spec(Spec.Kind.SEND_MESSAGE))).forEach { add(it, "ConfirmTemplates SEND_MESSAGE (no name)") }
        twiPieces(ConfirmTemplates.twi(Spec(Spec.Kind.GENERIC))).forEach { add(it, "ConfirmTemplates GENERIC") }
        // English message words: "Nkra no se:" then the words in English, then "Ka aane anaa daabi."
        fixed(Spec(Spec.Kind.SEND_MESSAGE, name = "Ama", message = "I am coming"), Spec(Spec.Kind.SEND_MESSAGE, name = "Kofi", message = "See you soon"))
            .forEach { add(it, "ConfirmTemplates SEND_MESSAGE (English words)") }
        check(k.isNotEmpty())
        val people = listOf(ContactResolver.Contact("Prince Melanin", "0552093690"), ContactResolver.Contact("Prince Ice P", "0536523611"),
            ContactResolver.Contact("Princess", "0538185281"), ContactResolver.Contact("Prince Tesano", "0553011385"))
        twiPieces(ContactChoice.build("Prince", people).twiQuestion).forEach { add(it, "ContactChoice") }
        // Spoken whole, from voice/VoiceController (keep in step with it)
        listOf("Mante. San ka bio." to "askByVoice re-ask", "Mante no. Ka aane anaa daabi." to "confirmByVoice re-ask",
            "Yoo, twɛn. Sɛ woawie a, ka aane anaa daabi." to "confirm hold", "Yoo, merenyɛ." to "declined",
            "Mɛtwɛn. Sɛ woawie a, ka 'Hey Tɛkyerɛma'." to "question parked", "Matwɛn. Ka kɔ so na mɛtoa so." to "pause",
            "Yoo, mɛtoa so." to "resume", "Yoo, mɛsesa." to "steer", "Yoo, twɛn." to "hold ack", "Mereyɛ ho adwuma da." to "progress",
            "Sɛ wowie a, ka sɛ 'awie'." to "secure remind",
            "Yei yɛ ahotoso adeyɛ. Fa wo MoMo PIN no hyɛ wo fɔn no mu ankasa. Merenhwɛ screen no. Sɛ wowie a, ka sɛ 'awie'." to "secure MoMo PIN",
            "Yei yɛ ahotoso adeyɛ. Wo ankasa hyɛ mu wɔ wo fɔn no so. Merenhwɛ screen no. Sɛ wowie a, ka sɛ 'awie'." to "secure step")
            .forEach { (l, f) -> add(l, "VoiceController $f") }

        val out = JSONArray()
        val dir = listOf(java.io.File("src/main/assets/voice/twi_lab"), java.io.File("app/src/main/assets/voice/twi_lab")).firstOrNull { it.parentFile?.isDirectory == true }
        val missing = ArrayList<String>()
        for ((line, from) in sources) for (key in keys(line)) {
            val file = com.tekyerema.language.TtsDiskCache.name(key) + ".wav"
            if (dir == null || !java.io.File(dir, file).isFile) missing += key
            out.put(JSONObject().put("text", key.removePrefix("twi|")).put("key", key).put("file", file).put("from", from))
        }
        assertTrue("the frames hold no digits (those are the English voice's)", sources.keys.none { s -> s.any { it.isDigit() } })
        listOf(java.io.File("../build"), java.io.File("build")).firstOrNull { it.isDirectory }?.let { java.io.File(it, "twi_lab_lines.json").writeText(out.toString(1)) }
        println("Twi lab lines: ${out.length()}, without a clip yet: ${missing.size}")
    }
}
