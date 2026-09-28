package com.tekyerema

import com.tekyerema.language.AudioSink
import com.tekyerema.language.FailureKind
import com.tekyerema.language.HttpAsrProvider
import com.tekyerema.language.HttpReply
import com.tekyerema.language.HttpSpeech
import com.tekyerema.language.HttpSpeechSpec
import com.tekyerema.language.HttpTtsProvider
import com.tekyerema.language.ProviderGuard
import com.tekyerema.language.ProviderHealth
import com.tekyerema.language.QuotaLedger
import com.tekyerema.language.SpeechTransport
import com.tekyerema.voice.AsrException
import com.tekyerema.voice.Lang
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The UG HCI Lab Speech Gateway (owner's dashboard, 2026-09-28): the real request shapes from its docs, and the free
 * plan's small quotas counted on the phone so a spent quota never costs a call.
 */
class HciLabGatewayTest {
    private val secrets = mapOf("SPEECH_HCILAB_TOKEN" to "speech_live_test")

    private fun client(id: String): HttpSpeechSpec {
        val dir = listOf(java.io.File("src/main/assets/config"), java.io.File("app/src/main/assets/config")).first { it.isDirectory }
        val clients = JSONObject(java.io.File(dir, "language.json").readText()).getJSONObject("providers").getJSONObject("speech").getJSONObject("clients")
        return HttpSpeechSpec.parse(id, clients.getJSONObject(id))!!
    }

    private class FakeTransport(var reply: (Request) -> HttpReply) : SpeechTransport {
        val requests = ArrayList<Request>()
        override suspend fun send(request: Request, connectMs: Long, callMs: Long): HttpReply { requests.add(request); return reply(request) }
    }
    private class FakeSink : AudioSink {
        var plays = 0
        override suspend fun play(audio: ByteArray): Boolean { plays++; return true }
        override fun stop() {}
    }
    private fun wav(): HttpReply = HttpReply(200, ByteArray(2_000) { 1 }, "audio/wav", null)
    private fun body(r: Request) = Buffer().also { r.body!!.writeTo(it) }.readUtf8()

    @Test fun the_shipped_clients_follow_the_gateway_docs() {
        val asr = client("hcilab_asr"); val tts = client("hcilab_tts")
        assertNull("usable once the token is in local.properties", HttpSpeech.unusableReason(asr, secrets))
        assertEquals("auth key placeholder not set", HttpSpeech.unusableReason(tts, emptyMap()))   // no token → not built
        val a = HttpSpeech.asrRequest(asr, secrets, ByteArray(100), "twi")
        assertEquals("https://lab-subscription-platform.vercel.app/api/v1/asr", a.url.toString())
        assertEquals("Bearer speech_live_test", a.header("Authorization"))
        assertTrue(body(a).contains("name=\"file\"; filename=\"speech.wav\""))
        val t = HttpSpeech.ttsRequest(tts, secrets, "Akwaaba", "twi")
        assertEquals("https://lab-subscription-platform.vercel.app/api/v1/tts", t.url.toString())
        val j = JSONObject(body(t))
        assertEquals("Akwaaba", j.getString("text")); assertEquals("ss", j.getString("model_type")); assertEquals("PT", j.getString("speaker"))
        assertFalse("no undocumented language field", j.has("language"))
        assertEquals(10, asr.limits.perDay); assertEquals(15, tts.limits.perDay); assertEquals(250, tts.maxChars)
    }

    @Test fun the_transcript_is_found_whatever_the_reply_calls_it() {
        val asr = client("hcilab_asr")
        for (b in listOf("""{"transcript":"Bue WhatsApp"}""", """{"text":"Bue WhatsApp"}""", """{"request_id":"r","data":{"text":"Bue WhatsApp"}}"""))
            assertEquals(b, "Bue WhatsApp", HttpSpeech.parseText(asr, HttpReply(200, b.toByteArray(), "application/json", null)))
    }

    @Test fun a_named_quota_error_rests_the_client_until_its_window_resets() {
        val asr = client("hcilab_asr")
        fun err(code: String) = HttpReply(429, """{"request_id":"req_1","error":{"code":"$code","message":"…"}}""".toByteArray(), "application/json", null)
        val week = HttpSpeech.failureOf(asr, err("weekly_quota_exceeded"))!!
        assertEquals(FailureKind.QUOTA, week.kind); assertEquals("week", week.window); assertTrue(week.retryAfterMs!! >= 3_600_000L)
        val day = HttpSpeech.failureOf(asr, err("daily_quota_exceeded"), now = 23L * 3_600_000L)!!
        assertEquals("day", day.window); assertEquals(3_600_000L, day.retryAfterMs)   // an hour to UTC midnight
        assertEquals(60_000L, HttpSpeech.failureOf(asr, err("rate_limited"))!!.retryAfterMs)
    }

    @Test fun the_ledger_stops_before_the_gateway_would_refuse() {
        var now = 1_790_000_000_000L
        val led = QuotaLedger(clock = { now })
        val l = QuotaLedger.Limits(perMinute = 5, perDay = 10, perWeek = 50, audioSecPerWeek = 1800)
        repeat(5) { assertNull(led.refusal("a", l, QuotaLedger.Units(audioSec = 3.0))); led.record("a", l, QuotaLedger.Units(audioSec = 3.0)) }
        assertTrue("5 a minute", led.refusal("a", l, QuotaLedger.Units())!!.contains("minute"))
        now += 61_000L
        repeat(5) { assertNull(led.refusal("a", l, QuotaLedger.Units())); led.record("a", l, QuotaLedger.Units()) }
        now += 61_000L
        assertTrue("10 a day", led.refusal("a", l, QuotaLedger.Units())!!.contains("today"))
        now += 24 * 3_600_000L
        assertNull("a new day", led.refusal("a", l, QuotaLedger.Units()))
        assertTrue("30 minutes of audio a week", led.refusal("a", l, QuotaLedger.Units(audioSec = 1_790.0))!!.contains("minutes"))
        led.exhaust("a", l, "day")
        assertTrue("the gateway said the day is spent", led.refusal("a", l, QuotaLedger.Units())!!.contains("today"))
    }

    @Test fun a_spent_quota_makes_no_call_and_asr_falls_back() = runBlocking {
        val t = FakeTransport { HttpReply(200, """{"transcript":"x"}""".toByteArray(), "application/json", null) }
        val led = QuotaLedger()
        val spec = client("hcilab_asr")
        led.exhaust(spec.id, spec.limits, "day")
        val asr = HttpAsrProvider(spec, { secrets }, t, ProviderGuard(ProviderHealth("x"), 30_000L), led)
        assertFalse(asr.available())
        try { asr.transcribe(ByteArray(3_000), Lang.TWI, null); fail("expected fallback") } catch (_: AsrException) {}
        assertTrue("no network call", t.requests.isEmpty())
    }

    @Test fun long_twi_replies_are_left_to_the_phones_voice_and_short_ones_cached() = runBlocking {
        val t = FakeTransport { wav() }
        val sink = FakeSink()
        val tts = HttpTtsProvider(client("hcilab_tts"), { secrets }, t, ProviderGuard(ProviderHealth("y"), 30_000L), sink, QuotaLedger())
        assertFalse("over 250 characters = more than one request", tts.speak("Mepa wo kyɛw ".repeat(30), Lang.TWI))
        assertTrue(t.requests.isEmpty())
        assertTrue(tts.speak("Mepa wo kyɛw, twɛn kakra.", Lang.TWI))
        assertTrue(tts.speak("Mepa wo kyɛw, twɛn kakra.", Lang.TWI))
        assertEquals("the second time comes from the cache", 1, t.requests.size)
        assertEquals(2, sink.plays)
        assertFalse("English stays on the phone", tts.speak("Hello", Lang.GH_EN))
    }

    @Test fun text_is_split_at_sentence_ends_within_the_limit() {
        val parts = HttpSpeech.parts("Ɛte sɛn? " + "Me ho yɛ. ".repeat(40), 250)
        assertTrue(parts.all { it.length <= 250 })
        assertEquals("Ɛte sɛn? " + "Me ho yɛ. ".repeat(40), parts.joinToString(" ") + " ")
    }
}
