package com.tekyerema

import com.tekyerema.core.config.AppConfig
import com.tekyerema.language.AudioSink
import com.tekyerema.language.HttpReply
import com.tekyerema.language.HttpTtsProvider
import com.tekyerema.language.LanguageLayer
import com.tekyerema.language.ProviderGuard
import com.tekyerema.language.ProviderHealth
import com.tekyerema.language.SpeechTransport
import com.tekyerema.voice.Lang
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner's Gemini Kore voice for English replies (29 Oct 2026): the shipped client, its request and its answer. */
class GeminiTtsTest {
    @After fun resetConfig() = AppConfig.loadFrom(emptyList())

    private class FakeTransport(var reply: (Request) -> HttpReply) : SpeechTransport {
        val requests = ArrayList<Request>()
        override suspend fun send(request: Request, connectMs: Long, callMs: Long): HttpReply { requests.add(request); return reply(request) }
    }

    private class FakeSink : AudioSink {
        val played = ArrayList<Int>()
        override suspend fun play(audio: ByteArray): Boolean { played.add(audio.size); return true }
        override fun stop() {}
    }

    private fun shippedConfig(): List<Pair<String, String>> {
        val dir = listOf(java.io.File("src/main/assets/config"), java.io.File("app/src/main/assets/config")).first { it.isDirectory }
        return dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }.map { "config/${it.name}" to it.readText() }
    }

    private val wav = ByteArray(400) { (it % 7).toByte() }
    private val b64 = java.util.Base64.getEncoder().encodeToString(wav)

    /** The interactions API's answer: the audio sits in steps → model_output → content. */
    private fun answer() = HttpReply(200, """{"id":"i-1","steps":[{"type":"thought"},{"type":"model_output","content":[{"type":"audio","mime_type":"audio/wav","data":"$b64"}]}]}""".toByteArray(), "application/json", null)

    private fun body(r: Request): JSONObject = JSONObject(Buffer().also { r.body!!.writeTo(it) }.readUtf8())

    private fun gemini(t: SpeechTransport, sink: AudioSink): HttpTtsProvider {
        AppConfig.loadFrom(shippedConfig())
        val r = LanguageLayer.build(AppConfig.obj("providers.speech.clients"), mapOf("SPEECH_GEMINI_TTS_KEY" to "g-key"), t, { sink },
            health = { ProviderHealth(it) })
        return r.tts["gemini_tts"] as HttpTtsProvider
    }

    @Test fun shipped_config_puts_kore_first_for_english_only_with_its_key() {
        AppConfig.loadFrom(shippedConfig())
        assertEquals(listOf("gemini_tts", "space_tts_eng"), AppConfig.stringList("providers.speech.tts.ghEnglish", emptyList()))
        val clients = AppConfig.obj("providers.speech.clients")
        val none = LanguageLayer.build(clients, emptyMap(), FakeTransport { answer() }, { FakeSink() })
        assertFalse("no key → no Gemini client (Kafui stays the English voice)", none.tts.containsKey("gemini_tts"))
        val with = LanguageLayer.build(clients, mapOf("SPEECH_GEMINI_TTS_KEY" to "g-key"), FakeTransport { answer() }, { FakeSink() })
        assertTrue(with.tts.containsKey("gemini_tts"))
        // Twi is untouched: the lab's voice alone online, no race
        assertEquals(listOf("hcilab_tts", "space_tts", "system"), AppConfig.stringList("providers.speech.tts.twi", emptyList()))
        assertEquals(0L, AppConfig.long("providers.speech.ttsHedgeMs", -1L))
    }

    @Test fun request_carries_kore_the_style_and_the_text_verbatim() = runTest {
        val t = FakeTransport { answer() }
        val sink = FakeSink()
        val tts = gemini(t, sink)
        assertTrue(tts.speak("Good morning. Welcome to Tɛkyerɛma.", Lang.GH_EN))
        assertEquals(listOf(400), sink.played)
        val req = t.requests.single()
        assertEquals("https://generativelanguage.googleapis.com/v1beta/interactions", req.url.toString())
        assertEquals("g-key", req.header("x-goog-api-key"))
        val b = body(req)
        assertEquals("gemini-3.8-flash-tts", b.getString("model"))
        assertEquals("Kore", b.getJSONObject("generation_config").getJSONArray("speech_config").getJSONObject(0).getString("voice"))
        assertEquals("audio", b.getJSONObject("response_format").getString("type"))
        val part = b.getJSONArray("input").getJSONObject(0).getJSONArray("content").getJSONObject(0)
        assertEquals("our own name in its real spelling (no Kafui respelling)", "Good morning. Welcome to Tɛkyerɛma.", part.getString("text"))
        val style = part.getJSONArray("annotations").getJSONObject(0)
        assertEquals("speech_metadata", style.getString("type"))
        assertTrue(style.getString("style").startsWith("Speak in a natural, contemporary Ghanaian English accent."))
        // a fixed line is not fetched twice
        assertTrue(tts.speak("Good morning. Welcome to Tɛkyerɛma.", Lang.GH_EN))
        assertEquals(1, t.requests.size)
    }

    @Test fun errors_and_empty_answers_never_play_and_never_throw() = runTest {
        val t = FakeTransport { HttpReply(429, """{"error":{"code":429}}""".toByteArray(), "application/json", null) }
        val sink = FakeSink()
        val tts = gemini(t, sink)
        assertFalse(tts.speak("Your balance is four hundred cedis.", Lang.GH_EN))
        t.reply = { HttpReply(200, """{"steps":[{"type":"model_output","content":[{"type":"text","text":"sorry"}]}]}""".toByteArray(), "application/json", null) }
        assertFalse("no audio in the reply → the phone's voice speaks", tts.speak("A new line to say.", Lang.GH_EN))
        assertTrue(sink.played.isEmpty())
        assertFalse("English only: a Twi line is not this voice's", tts.speak("Mema wo akye.", Lang.TWI))
    }
}
