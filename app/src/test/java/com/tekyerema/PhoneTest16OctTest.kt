package com.tekyerema

import com.tekyerema.agent.TaskFacts
import com.tekyerema.language.FailureKind
import com.tekyerema.language.ProviderHealth
import com.tekyerema.voice.AsrProvider
import com.tekyerema.voice.FallbackAsr
import com.tekyerema.voice.Lang
import com.tekyerema.voice.Normalizer
import com.tekyerema.voice.Transcript
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner's first test of the Khaya build on the A20e (16 Oct 2026, audit/phone-2026-10-16): what went wrong. */
class PhoneTest16OctTest {
    private fun twi(s: String) = Normalizer.normalize(s, Lang.TWI)

    @Test fun `Khaya's 5seed is is five cedis and the money check finds the amount`() {
        val goal = twi("Soma 5seed is ma 0552093690.")       // Khaya's real transcript of "Soma sidi anum ma 0552093690"
        assertTrue(goal, goal.contains("5 cedis"))
        assertTrue(TaskFacts(goal, emptyList()).amountStated("5"))
        assertTrue(twi("soma seed is du ma Ama").contains("10"))
        assertEquals("ɔno akasa", twi("ɔno akasa"))           // ordinary Twi untouched
    }

    private class Fake(override val name: String, val ms: Long, val text: String?) : AsrProvider {
        var calls = 0
        override suspend fun transcribe(wav: ByteArray, sessionLanguage: String, forceLanguage: String?): Transcript {
            calls++
            delay(ms)
            return Transcript(text ?: throw java.io.IOException("timeout"), text, Lang.TWI, backend = name)
        }
    }

    @Test fun `a slow first recogniser gets a head start, then the second is asked beside it`() = runBlocking {
        val lab = Fake("lab", 1_000, "lab")
        val khaya = Fake("khaya", 50, "khaya")
        assertEquals("khaya", FallbackAsr(listOf(lab, khaya), hedgeMs = { 100 }).transcribe(ByteArray(0), Lang.TWI, null).raw)
        // a healthy lab inside its head start wins and Khaya is never asked
        val fastLab = Fake("lab", 20, "lab"); val idle = Fake("khaya", 20, "khaya")
        assertEquals("lab", FallbackAsr(listOf(fastLab, idle), hedgeMs = { 200 }).transcribe(ByteArray(0), Lang.TWI, null).raw)
        assertEquals(0, idle.calls)
        // the lab failing early goes straight to Khaya; both failing is an error
        assertEquals("khaya", FallbackAsr(listOf(Fake("lab", 10, null), Fake("khaya", 10, "khaya")), hedgeMs = { 500 })
            .transcribe(ByteArray(0), Lang.TWI, null).raw)
        val failed = runCatching { FallbackAsr(listOf(Fake("a", 10, null), Fake("b", 300, null)), hedgeMs = { 50 })
            .transcribe(ByteArray(0), Lang.TWI, null) }
        assertTrue(failed.isFailure)
        // the slow lab answering first after Khaya fails is still used
        assertEquals("lab", FallbackAsr(listOf(Fake("lab", 300, "lab"), Fake("khaya", 10, null)), hedgeMs = { 100 })
            .transcribe(ByteArray(0), Lang.TWI, null).raw)
    }

    @Test fun `one timeout rests a service at once, a refusal still needs two`() {
        var now = 0L
        val h = ProviderHealth("hcilab_tts", clock = { now })
        h.onFailure(FailureKind.TRANSIENT, timedOut = true)
        assertFalse(h.available())
        now += 2 * 60_000L
        assertTrue(h.available())
        val r = ProviderHealth("x", clock = { now })
        r.onFailure(FailureKind.TRANSIENT)
        assertTrue(r.available())
        r.onFailure(FailureKind.TRANSIENT)
        assertFalse(r.available())
    }

    private class Voice(override val name: String, val ms: Long, val ok: Boolean) : com.tekyerema.voice.PrefetchTts {
        val played = mutableListOf<String>()
        override suspend fun fetch(text: String, language: String): List<ByteArray>? { delay(ms); return if (ok) listOf(ByteArray(1)) else null }
        override suspend fun play(clips: List<ByteArray>): Boolean { played += name; return true }
        override suspend fun speak(text: String, language: String) = fetch(text, language)?.let { play(it) } ?: false
        override fun stop() {}
        override fun shutdown() {}
    }

    @Test fun `a slow lab voice gets a head start, then the first audio back is played and only it`() = runBlocking {
        val lab = Voice("lab", 1_000, true); val khaya = Voice("khaya", 30, true)
        val chain = com.tekyerema.voice.FallbackTts(listOf(lab, khaya), hedgeMs = { 100 })
        assertTrue(chain.speak("Wopɛ sɛ me frɛ no?", Lang.TWI))
        assertEquals(listOf("khaya"), khaya.played); assertTrue(lab.played.isEmpty())
        assertEquals("khaya", chain.lastVoice)
        // the lab inside its head start speaks; Khaya is never fetched
        val fast = Voice("lab", 10, true); val spare = Voice("khaya", 10, true)
        assertTrue(com.tekyerema.voice.FallbackTts(listOf(fast, spare), hedgeMs = { 200 }).speak("x", Lang.TWI))
        assertEquals(listOf("lab"), fast.played); assertTrue(spare.played.isEmpty())
        // both network voices failing falls to the phone's voice
        val phone = Voice("system", 0, true)
        assertTrue(com.tekyerema.voice.FallbackTts(listOf(Voice("lab", 10, false), Voice("khaya", 10, false), phone), hedgeMs = { 50 })
            .speak("x", Lang.TWI))
        assertEquals(listOf("system"), phone.played)
    }

    @Test fun `a quoted message that ends its sentence keeps one full stop after translation`() {
        val e = listOf(com.tekyerema.language.EntityTags.Entity("[Q1]", com.tekyerema.language.EntityTags.Kind.QUANTITY, "Please call me."))
        assertEquals("So wode saa nkra yi kɔma no? Please call me.",
            com.tekyerema.language.EntityTags.restore("So wode saa nkra yi kɔma no? [Q1].", e))
        assertEquals("Ɔkae sɛ Hello... ", com.tekyerema.language.EntityTags.restore("Ɔkae sɛ [Q1].. ",
            listOf(com.tekyerema.language.EntityTags.Entity("[Q1]", com.tekyerema.language.EntityTags.Kind.QUANTITY, "Hello."))))
    }

    @Test fun `Khaya's rate limit rests its account a minute, a refusal still rests it long`() {
        val dir = listOf(java.io.File("src/main/assets/config"), java.io.File("app/src/main/assets/config")).first { it.isDirectory }
        val clients = org.json.JSONObject(java.io.File(dir, "language.json").readText())
            .getJSONObject("providers").getJSONObject("speech").getJSONObject("clients")
        val spec = com.tekyerema.language.HttpSpeechSpec.parse("khaya_web_mt", clients.getJSONObject("khaya_web_mt"))!!
        fun reply(code: Int, body: String) = com.tekyerema.language.HttpReply(code, body.toByteArray(), "application/json", null)
        val rate = com.tekyerema.language.HttpSpeech.failureOf(spec, reply(429, """{"detail":"RATE_LIMIT_EXCEEDED"}"""))!!
        assertEquals(FailureKind.QUOTA, rate.kind); assertEquals(60_000L, rate.retryAfterMs)
        assertEquals(60_000L, com.tekyerema.language.HttpSpeech.failureOf(spec, reply(429, "busy"))!!.retryAfterMs)
        assertEquals(null, com.tekyerema.language.HttpSpeech.failureOf(spec, reply(403, "{}"))!!.retryAfterMs)   // → the long quota rest
    }

    @Test fun `only a client marked restOnTimeout rests on its first timeout`()= kotlinx.coroutines.test.runTest {
        val lab = ProviderHealth("hcilab_asr")
        runCatching { com.tekyerema.language.ProviderGuard(lab, 1_000L, restOnTimeout = true).run { delay(10_000L); "late" } }
        assertFalse("the lab rests at once", lab.available())
        val khaya = ProviderHealth("khaya_web")      // shared by translation, hearing and voice
        runCatching { com.tekyerema.language.ProviderGuard(khaya, 1_000L).run { delay(10_000L); "late" } }
        assertTrue("one timeout doesn't rest a shared account", khaya.available())
    }
}
