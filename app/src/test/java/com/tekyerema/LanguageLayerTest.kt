package com.tekyerema

import com.tekyerema.core.config.AppConfig
import com.tekyerema.language.AudioSink
import com.tekyerema.language.FailureKind
import com.tekyerema.language.HttpAsrProvider
import com.tekyerema.language.HttpReply
import com.tekyerema.language.HttpSpeech
import com.tekyerema.language.HttpSpeechSpec
import com.tekyerema.language.HttpTranslationProvider
import com.tekyerema.language.HttpTtsProvider
import com.tekyerema.language.LanguageLayer
import com.tekyerema.language.PlainLatinVoice
import com.tekyerema.language.ProviderFailure
import com.tekyerema.language.ProviderGuard
import com.tekyerema.language.ProviderHealth
import com.tekyerema.language.SpeechTransport
import com.tekyerema.voice.AsrException
import com.tekyerema.voice.HybridAsr
import com.tekyerema.voice.Lang
import com.tekyerema.voice.RoutingTts
import com.tekyerema.voice.SpeechToRaw
import com.tekyerema.voice.TtsProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The language layer's network half (B-18): config-driven REST clients, health/quota guard, registry assembly and
 * routing. No network — a fake [SpeechTransport] records each request and replays a canned reply.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LanguageLayerTest {

    @After fun resetConfig() = AppConfig.loadFrom(emptyList())

    private val secrets = mapOf("SPEECH_LAB_URL" to "https://lab.example.test/v1/asr", "SPEECH_LAB_KEY" to "k-123")

    private class FakeTransport(var reply: (Request) -> HttpReply) : SpeechTransport {
        val requests = ArrayList<Request>()
        override suspend fun send(request: Request, connectMs: Long, callMs: Long): HttpReply { requests.add(request); return reply(request) }
    }

    private fun json(code: Int, body: String, retryAfter: String? = null) =
        HttpReply(code, body.toByteArray(), "application/json", retryAfter)

    private fun bodyText(r: Request): String = Buffer().also { r.body!!.writeTo(it) }.readUtf8()

    private fun spec(extra: String = "", kind: String = "asr", languages: String = """{"twi":"tw","gh_en":"en"}"""): HttpSpeechSpec =
        HttpSpeechSpec.parse("lab", JSONObject("""
        {"kind":"$kind","enabled":true,"endpoint":"${'$'}{SPEECH_LAB_URL}",
         "auth":{"header":"x-api-key","key":"${'$'}{SPEECH_LAB_KEY}"},
         "languages":$languages $extra}""".trimIndent()))!!

    private fun guard(h: ProviderHealth = ProviderHealth("lab")) = ProviderGuard(h, 30_000L)

    // ---------------------------------------------------------------- spec + placeholders

    @Test fun placeholders_resolve_from_secrets_and_missing_ones_disable_the_client() {
        assertEquals("https://x/y", HttpSpeech.resolve("${'$'}{A}/y", mapOf("A" to "https://x")))
        assertNull(HttpSpeech.resolve("${'$'}{MISSING}/y", emptyMap()))
        assertNull(HttpSpeech.resolve("${'$'}{BLANK}", mapOf("BLANK" to "  ")))
        assertNull(HttpSpeech.unusableReason(spec(), secrets))
        assertEquals("disabled", HttpSpeech.unusableReason(spec().copy(enabled = false), secrets))
        val noKey = HttpSpeech.unusableReason(spec(), secrets - "SPEECH_LAB_KEY")!!
        assertTrue(noKey, noKey.contains("key"))
        // plain http is refused unless explicitly allowed; the reason never echoes a secret value
        val http = mapOf("SPEECH_LAB_URL" to "http://lan.test/asr", "SPEECH_LAB_KEY" to "k-123")
        val why = HttpSpeech.unusableReason(spec(), http)!!
        assertTrue(why.contains("https")); assertFalse(why.contains("k-123"))
        assertNull(HttpSpeech.unusableReason(spec().copy(allowHttp = true), http))
    }

    @Test fun multipart_asr_request_carries_audio_language_and_auth_header() {
        val wav = ByteArray(100) { 7 }
        val r = HttpSpeech.asrRequest(spec(), secrets, wav, "tw")
        assertEquals("https://lab.example.test/v1/asr", r.url.toString())
        assertEquals("k-123", r.header("x-api-key"))
        val body = bodyText(r)
        assertTrue(body.contains("name=\"file\"; filename=\"speech.wav\""))
        assertTrue(body.contains("name=\"language\"") && body.contains("tw"))
    }

    @Test fun query_language_raw_body_and_bearer_prefix() {
        val s = HttpSpeechSpec.parse("k", JSONObject("""
            {"kind":"asr","enabled":true,"endpoint":"https://api.test/asr/v3/transcribe",
             "auth":{"header":"Authorization","prefix":"Bearer ","key":"${'$'}{SPEECH_LAB_KEY}"},
             "languages":{"twi":"twi"},"request":{"body":"raw","language":"query","audioMime":"audio/wav"}}""".trimIndent()))!!
        val r = HttpSpeech.asrRequest(s, secrets, ByteArray(10), "twi")
        assertEquals("twi", r.url.queryParameter("language"))
        assertEquals("Bearer k-123", r.header("Authorization"))
        assertEquals("audio/wav", r.body!!.contentType().toString())
        assertEquals(10L, r.body!!.contentLength())
    }

    @Test fun json_base64_asr_and_pair_format_translation_requests() {
        val s = spec(""","request":{"body":"json","audioField":"audio","fields":{"model":"akan-v1","beam":2}}""")
        val o = JSONObject(bodyText(HttpSpeech.asrRequest(s, secrets, byteArrayOf(1, 2, 3), "tw")))
        assertEquals("AQID", o.getString("audio"))
        assertEquals("tw", o.getString("language"))
        assertEquals("akan-v1", o.getString("model")); assertEquals(2, o.getInt("beam"))
        val mt = HttpSpeechSpec.parse("mt", JSONObject("""
            {"kind":"mt","enabled":true,"endpoint":"https://mt.test/translate","languages":{"gh_en":"eng","twi":"twi"},
             "request":{"textField":"in","languageField":"lang","pairFormat":"{from}-{to}"}}""".trimIndent()))!!
        val m = JSONObject(bodyText(HttpSpeech.mtRequest(mt, secrets, "Hello", "eng", "twi")))
        assertEquals("Hello", m.getString("in")); assertEquals("eng-twi", m.getString("lang"))
    }

    // ---------------------------------------------------------------- responses + errors

    @Test fun responses_parse_by_path_bare_string_text_and_audio() {
        val s = spec(""","response":{"path":"results.0.transcript","languagePath":"lang"}""")
        val reply = json(200, """{"results":[{"transcript":" Frɛ Kofi "}],"lang":"tw"}""")
        assertEquals("Frɛ Kofi", HttpSpeech.parseText(s, reply))
        assertEquals(Lang.TWI, HttpSpeech.parseLanguage(s, reply))
        assertEquals("Mema wo akye", HttpSpeech.parseText(spec(), json(200, "\"Mema wo akye\"")))   // bare JSON string
        assertEquals("plain", HttpSpeech.parseText(spec(""","response":{"kind":"text"}"""), json(200, "plain")))
        try { HttpSpeech.parseText(spec(), json(200, """{"other":1}""")); fail() }
        catch (e: ProviderFailure) { assertEquals(FailureKind.BAD_RESPONSE, e.kind) }
        // audio: raw bytes pass; a JSON error body on a 200 is never played; base64 (data URI) decodes
        val tts = spec(kind = "tts")
        assertEquals(100, HttpSpeech.parseAudio(tts, HttpReply(200, ByteArray(100), "audio/wav", null)).size)
        try { HttpSpeech.parseAudio(tts, json(200, """{"error":"quota"}""")); fail() } catch (e: ProviderFailure) {}
        val b64 = spec(""","response":{"kind":"json","path":"data.audio"}""", kind = "tts")
        assertEquals(3, HttpSpeech.parseAudio(b64, json(200, """{"data":{"audio":"data:audio/wav;base64,AQID"}}""")).size)
    }

    @Test fun http_errors_are_classified_for_the_health_guard() {
        val s = spec()
        assertNull(HttpSpeech.failureOf(s, json(200, "{}")))
        assertEquals(FailureKind.AUTH, HttpSpeech.failureOf(s, json(401, "{}"))!!.kind)
        assertEquals(FailureKind.QUOTA, HttpSpeech.failureOf(s, json(403, "{}"))!!.kind)
        val q = HttpSpeech.failureOf(s, json(429, "{}", retryAfter = "30"))!!
        assertEquals(FailureKind.QUOTA, q.kind); assertEquals(30_000L, q.retryAfterMs)
        assertEquals(FailureKind.TRANSIENT, HttpSpeech.failureOf(s, json(503, "{}"))!!.kind)
        assertEquals(FailureKind.BAD_RESPONSE, HttpSpeech.failureOf(s, json(400, "{}"))!!.kind)
    }

    // ---------------------------------------------------------------- health + guard

    @Test fun health_rests_on_quota_at_once_and_on_transient_after_two() {
        var now = 1_000L
        val h = ProviderHealth("g", clock = { now })
        h.onFailure(FailureKind.TRANSIENT); assertTrue("one blip is tolerated", h.available())
        h.onFailure(FailureKind.TRANSIENT); assertFalse("two in a row rest it", h.available())
        now += 60_001; assertTrue(h.available())
        h.onFailure(FailureKind.QUOTA, retryAfterMs = 5_000); assertFalse(h.available())
        now += 5_001; assertTrue("Retry-After honoured", h.available())
        h.onFailure(FailureKind.AUTH); assertFalse(h.available())
        h.onSuccess(); assertTrue("a success closes the breaker", h.available())
    }

    @Test fun guard_fails_fast_when_resting_and_times_out_as_transient() = runTest {
        val h = ProviderHealth("g", clock = { testScheduler.currentTime })
        h.onFailure(FailureKind.QUOTA)
        var called = false
        try { guard(h).run { called = true }; fail() } catch (e: ProviderFailure) { assertEquals(FailureKind.UNAVAILABLE, e.kind) }
        assertFalse("no call while resting", called)
        val fresh = ProviderHealth("t", clock = { testScheduler.currentTime })
        try { ProviderGuard(fresh, 1_000L).run { delay(5_000) }; fail() }
        catch (e: ProviderFailure) { assertEquals(FailureKind.TRANSIENT, e.kind) }
        assertEquals(FailureKind.TRANSIENT, fresh.lastFailure)
    }

    @Test fun a_user_stop_is_not_held_against_the_provider() = runTest {
        val h = ProviderHealth("c")
        val job = async { ProviderGuard(h, 60_000L).run { awaitCancellation() } }
        advanceTimeBy(10); job.cancel()
        try { job.await(); fail() } catch (e: CancellationException) {}
        assertNull(h.lastFailure); assertTrue(h.available())
    }

    // ---------------------------------------------------------------- providers end to end

    @Test fun http_asr_runs_the_local_pipeline_on_the_transcript() = runTest {
        val t = FakeTransport { json(200, """{"text":"Soma sidi aduonu anum kɔ Kofi"}""") }
        val asr = HttpAsrProvider(spec(), { secrets }, t, guard())
        val tr = asr.transcribe(ByteArray(10), Lang.GH_EN, Lang.TWI)
        assertEquals(Lang.TWI, tr.language)
        assertEquals("Soma cedi 25 kɔ Kofi", tr.canonical)
        assertEquals("http:lab", tr.backend)
        assertTrue("the Twi code is sent", bodyText(t.requests[0]).contains("name=\"language\""))
    }

    @Test fun http_asr_uses_the_provider_reported_language_in_auto_mode() = runTest {
        val s = spec(""","response":{"path":"text","languagePath":"language"}""", languages = """{"twi":"tw","gh_en":"en","auto":"auto"}""")
        val asr = HttpAsrProvider(s, { secrets }, FakeTransport { json(200, """{"text":"Bue WhatsApp","language":"tw"}""") }, guard())
        val tr = asr.transcribe(ByteArray(10), Lang.GH_EN, null)
        assertEquals(Lang.TWI, tr.language)
        assertTrue(tr.reason.startsWith("provider-reported"))
    }

    @Test fun http_asr_failures_surface_as_asr_exceptions_and_rest_the_provider() = runTest {
        val h = ProviderHealth("lab")
        val t = FakeTransport { json(429, """{"error":"quota"}""", retryAfter = "600") }
        val asr = HttpAsrProvider(spec(), { secrets }, t, ProviderGuard(h, 30_000L))
        try { asr.transcribe(ByteArray(10), Lang.TWI, Lang.TWI); fail() } catch (e: AsrException) {}
        assertFalse(asr.available())
        try { asr.transcribe(ByteArray(10), Lang.TWI, Lang.TWI); fail() } catch (e: AsrException) {}
        assertEquals("the resting provider is not called again", 1, t.requests.size)
        // a language the client doesn't list is refused without a call
        val enOnly = HttpAsrProvider(spec(languages = """{"gh_en":"en"}"""), { secrets }, t, guard())
        try { enOnly.transcribe(ByteArray(10), Lang.TWI, Lang.TWI); fail() } catch (e: AsrException) {}
        assertEquals(1, t.requests.size)
    }

    private class FakeSink : AudioSink {
        val played = ArrayList<Int>()
        override suspend fun play(audio: ByteArray): Boolean { played.add(audio.size); return true }
        override fun stop() {}
    }

    @Test fun http_tts_caches_fixed_lines_and_declines_unsupported_languages() = runTest {
        val t = FakeTransport { HttpReply(200, ByteArray(200), "audio/wav", null) }
        val sink = FakeSink()
        val tts = HttpTtsProvider(spec(kind = "tts", languages = """{"twi":"tw"}"""), { secrets }, t, guard(), sink)
        assertTrue(tts.speak("Yoo.", Lang.TWI)); assertTrue(tts.speak("Yoo.", Lang.TWI))
        assertEquals("second time from the cache", 1, t.requests.size)
        assertEquals(2, sink.played.size)
        assertFalse("no English voice here → the chain falls back", tts.speak("Okay.", Lang.GH_EN))
        // the Twi voice receives the standard spelling (Greek ε → ɛ, tone marks dropped)
        tts.speak("Mefrε Kwabɛ́na", Lang.TWI)
        assertTrue(JSONObject(bodyText(t.requests.last())).getString("text") == "Mefrɛ Kwabɛna")
        // failure → false (never throws into the voice turn)
        t.reply = { json(500, "{}") }
        assertFalse(tts.speak("Something new", Lang.TWI))
    }

    @Test fun http_translation_returns_null_on_failure() = runTest {
        val s = spec(""","response":{"path":"translation"}""", kind = "mt")
        val ok = HttpTranslationProvider(s, { secrets }, FakeTransport { json(200, """{"translation":"Mema wo akye"}""") }, guard())
        assertEquals("Mema wo akye", ok.translate("Good morning", Lang.GH_EN, Lang.TWI))
        val bad = HttpTranslationProvider(s, { secrets }, FakeTransport { json(503, "{}") }, guard())
        // (a line translated before may come from the phone's own cache — MtDiskCache; a new one fails cleanly)
        assertNull(bad.translate("Good evening, a line never translated", Lang.GH_EN, Lang.TWI))
    }

    // ---------------------------------------------------------------- registry + routing

    private fun shippedConfig(): List<Pair<String, String>> {
        val dir = listOf(java.io.File("src/main/assets/config"), java.io.File("app/src/main/assets/config")).first { it.isDirectory }
        return dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }.map { "config/${it.name}" to it.readText() }
    }

    /** 2026-09-28: the owner has an HCI Lab gateway account — its ASR/TTS clients ship ENABLED but are only built when
     *  the token is in local.properties; with no token the app stays fully on-device, and English never goes out. */
    @Test fun shipped_config_builds_only_the_hcilab_clients_and_only_with_its_token() {
        AppConfig.loadFrom(shippedConfig())
        val clients = AppConfig.obj("providers.speech.clients")
        assertNotNull("language.json ships the HCI Lab clients", clients)
        val none = LanguageLayer.build(clients, emptyMap(), FakeTransport { json(200, "{}") }, { FakeSink() })
        assertTrue("no token → no network client", none.asr.isEmpty() && none.tts.isEmpty() && none.mt.isEmpty())
        assertNull(LanguageLayer.onlineAsr(Lang.TWI, none.asr, cloudAllowed = true))
        val r = LanguageLayer.build(clients, mapOf("SPEECH_HCILAB_TOKEN" to "speech_live_x"), FakeTransport { json(200, "{}") }, { FakeSink() })
        assertEquals(setOf("hcilab_asr"), r.asr.keys)
        assertEquals(setOf("hcilab_tts"), r.tts.keys)
        assertTrue("the gateway has no translation", r.mt.isEmpty())
        // owner, 16 Oct 2026: the lab paused — Twi hearing and voice are the Khaya web platform's (its clients stay built)
        assertEquals(listOf("khaya_web_asr"), AppConfig.stringList("providers.speech.asr.online.twi", emptyList()))
        assertEquals(listOf("khaya_web_tts", "system"), AppConfig.stringList("providers.speech.tts.twi", emptyList()))
        assertTrue("English stays on the phone", AppConfig.stringList("providers.speech.asr.online.gh_en", listOf("x")).isEmpty())
        assertNull("never at a PIN screen / offline", LanguageLayer.onlineAsr(Lang.TWI, r.asr, cloudAllowed = false))
    }

    /** Owner, 2 October 2026: translation back — Google first (Twi = "ak"), Khaya second — each built only with its own key,
     *  the key in a header (never the URL), and both directions routed. */
    @Test fun shipped_translators_are_google_then_khaya_and_need_their_keys() = runTest {
        AppConfig.loadFrom(shippedConfig())
        val clients = AppConfig.obj("providers.speech.clients")
        // Our own Spaces first (free), then Khaya, then Google — each only built with its own local.properties entry.
        // 15 Oct 2026: the Khaya web platform first (~0.6 s; the Space took 3–8 s), then our Spaces, the old Khaya, Google.
        assertEquals(listOf("khaya_web_mt", "space_mt", "nllb_mt", "khaya_mt", "google_mt"), AppConfig.stringList("providers.speech.mt.twiToEn", emptyList()))
        assertEquals(listOf("khaya_web_mt", "space_mt", "nllb_mt", "khaya_mt", "google_mt"), AppConfig.stringList("providers.speech.mt.enToTwi", emptyList()))
        val nllb = FakeTransport { json(200, """{"translation":"Send ten cedis to Ama"}""") }
        val n = LanguageLayer.build(clients, mapOf("SPEECH_NLLB_URL" to "https://x-tekyerema-translate.hf.space/translate",
            "SPEECH_NLLB_TOKEN" to "n-key"), nllb, { FakeSink() })
        assertEquals(setOf("nllb_mt"), n.mt.keys)
        assertEquals("Send ten cedis to Ama", n.mt.getValue("nllb_mt").translate("Soma sidi du ma Ama", Lang.TWI, Lang.GH_EN))
        val nb = JSONObject(bodyText(nllb.requests.single()))
        assertEquals("twi_Latn", nb.getString("source")); assertEquals("eng_Latn", nb.getString("target"))
        assertEquals("Bearer n-key", nllb.requests.single().header("Authorization"))
        val google = FakeTransport { json(200, """{"data":{"translations":[{"translatedText":"Send ten cedis to Ama"}]}}""") }
        val r = LanguageLayer.build(clients, mapOf("SPEECH_GOOGLE_TRANSLATE_KEY" to "g-key"), google, { FakeSink() })
        assertEquals(setOf("google_mt"), r.mt.keys)   // no Khaya key → no Khaya client
        assertEquals("Send ten cedis to Ama", r.mt.getValue("google_mt").translate("Soma sidi du ma Ama", Lang.TWI, Lang.GH_EN))
        val req = google.requests.single()
        assertEquals("g-key", req.header("X-goog-api-key"))
        assertFalse("the key never rides in the URL", req.url.toString().contains("g-key"))
        val body = JSONObject(bodyText(req))
        assertEquals("Soma sidi du ma Ama", body.getString("q"))
        assertEquals("ak", body.getString("source")); assertEquals("en", body.getString("target")); assertEquals("text", body.getString("format"))
        // Khaya: {"in", "lang":"tw-en"} → a bare JSON string, its own header.
        val khaya = FakeTransport { json(200, "\"Send ten cedis to Ama\"") }
        val k = LanguageLayer.build(clients, mapOf("SPEECH_KHAYA_KEY" to "k-key"), khaya, { FakeSink() })
        assertEquals("Send ten cedis to Ama", k.mt.getValue("khaya_mt").translate("Soma sidi du ma Ama", Lang.TWI, Lang.GH_EN))
        val kb = JSONObject(bodyText(khaya.requests.single()))
        assertEquals("tw-en", kb.getString("lang")); assertEquals("Soma sidi du ma Ama", kb.getString("in"))
        assertEquals("k-key", khaya.requests.single().header("Ocp-Apim-Subscription-Key"))
        // The Khaya web platform: {"in", "lang":"eng-twi"} → a bare JSON string, a Bearer key.
        val web = FakeTransport { json(200, "\"Wowɔ nkrasɛm bi a efi Kofi Demo hɔ.\"") }
        val w = LanguageLayer.build(clients, mapOf("SPEECH_KHAYA_WEB_KEY" to "w-key"), web, { FakeSink() })
        assertEquals("Wowɔ nkrasɛm bi a efi Kofi Demo hɔ.", w.mt.getValue("khaya_web_mt").translate("You have a message from Kofi Demo.", Lang.GH_EN, Lang.TWI))
        val wb = JSONObject(bodyText(web.requests.single()))
        assertEquals("eng-twi", wb.getString("lang")); assertEquals("You have a message from Kofi Demo.", wb.getString("in"))
        assertEquals("Bearer w-key", web.requests.single().header("Authorization"))
        // Both keys: Google is asked first.
        val both = LanguageLayer.build(clients, mapOf("SPEECH_GOOGLE_TRANSLATE_KEY" to "g", "SPEECH_KHAYA_KEY" to "k"), google, { FakeSink() })
        assertEquals("khaya_mt", LanguageLayer.mtIds(Lang.TWI, Lang.GH_EN).first { it in both.mt })
    }

    /** Our language Space speaks Gradio's two-step API; its answer is a JSON STRING with "translation" and "covered"
     *  (the live reply of 2 October 2026, word for word). A "covered": false pass-through is no translation at all. */
    @Test fun the_language_space_is_called_the_gradio_way() = runTest {
        AppConfig.loadFrom(shippedConfig())
        val clients = AppConfig.obj("providers.speech.clients")
        val space = FakeTransport { r ->
            if (r.method == "POST") json(200, """{"event_id":"ev1"}""")
            else HttpReply(200, ("event: complete\ndata: [\"{\\\"translation\\\": \\\"Send money to Kwame\\\", \\\"covered\\\": true, " +
                "\\\"src\\\": \\\"twi\\\", \\\"tgt\\\": \\\"eng\\\", \\\"engine\\\": \\\"nllb600m-v1\\\"}\"]\n\n").toByteArray(), "text/event-stream", null)
        }
        val r = LanguageLayer.build(clients, mapOf("SPEECH_SPACE_URL" to "https://princealhassannasamu-tekyerema-lang.hf.space"), space, { FakeSink() })
        assertEquals(setOf("space_mt"), r.mt.keys)
        assertEquals("Send money to Kwame", r.mt.getValue("space_mt").translate("Soma sika ma Kwame", Lang.TWI, Lang.GH_EN))
        val (call, result) = space.requests
        assertEquals("https://princealhassannasamu-tekyerema-lang.hf.space/gradio_api/call/translate", call.url.toString())
        assertEquals("""{"data":["Soma sika ma Kwame","twi","eng"]}""", bodyText(call))
        assertEquals("https://princealhassannasamu-tekyerema-lang.hf.space/gradio_api/call/translate/ev1", result.url.toString())
        // Passed through untranslated (the Space's MT off) → null, so the planner gets the Twi with a hint instead.
        val off = FakeTransport { rq -> if (rq.method == "POST") json(200, """{"event_id":"e"}""")
            else HttpReply(200, "event: complete\ndata: [\"{\\\"translation\\\": \\\"Soma\\\", \\\"covered\\\": false}\"]\n".toByteArray(), null, null) }
        val r2 = LanguageLayer.build(clients, mapOf("SPEECH_SPACE_URL" to "https://x.hf.space"), off, { FakeSink() })
        assertNull(r2.mt.getValue("space_mt").translate("Soma", Lang.TWI, Lang.GH_EN))
    }

    /** Money in Twi goes to the translator in plain form — "sidi du" was "a dime" / "cents" in every model on 2 October 2026. */
    @Test fun twi_money_is_made_plain_before_translation() {
        assertEquals("Soma 10 cedis ma Ama", com.tekyerema.voice.Normalizer.forTranslation("Soma sidi du ma Ama"))
        assertEquals("Tɔ data 3 cedis", com.tekyerema.voice.Normalizer.forTranslation("Tɔ data sidi mmiɛnsa"))
        assertEquals("Tɔ kredit 1 cedi ma me", com.tekyerema.voice.Normalizer.forTranslation("Tɔ kredit sidi baako ma me"))
        assertEquals("Frɛ Kofi", com.tekyerema.voice.Normalizer.forTranslation("Frɛ Kofi"))
    }

    /** The time and date here are the phone's clock, never a web page (the owner's Twi test: "4:04 pm, 29 September" at 18:22, 2 October). */
    @Test fun the_time_here_is_never_searched() {
        val now = java.util.Calendar.getInstance().apply { set(2026, java.util.Calendar.OCTOBER, 2, 18, 22) }
        assertEquals("It's 6:22 pm, by this phone's clock.", com.tekyerema.tools.WebSearchTool.localNow("current time in Accra Ghana", now))
        assertEquals("It's 6:22 pm, by this phone's clock.", com.tekyerema.tools.WebSearchTool.localNow("what time is it now", now))
        assertEquals("Today is Friday 2 October 2026.", com.tekyerema.tools.WebSearchTool.localNow("today's date", now))
        assertNull("another city's time is a real search", com.tekyerema.tools.WebSearchTool.localNow("current time in London", now))
        assertNull(com.tekyerema.tools.WebSearchTool.localNow("history of the Ghana cedi", now))
        // …and the Twi ways of asking never leave the phone at all.
        for (q in listOf("ɛberɛ bɛn", "bere bɛn na ɛyɛ", "mmerɛ bɛn"))
            assertEquals(q, com.tekyerema.agent.FastIntent.Time, com.tekyerema.agent.FastIntentClassifier.classify(q))
    }

    @Test fun enabled_clients_register_by_id_and_route_per_language() = runTest {
        AppConfig.loadFrom(listOf("config/t.json" to """{"providers":{"speech":{"asr":{"online":{"twi":["lab"],"gh_en":["gone","lab"]}}}}}"""))
        val clients = JSONObject("""{"lab":${JSONObject(mapOf("kind" to "asr", "enabled" to true, "endpoint" to "${'$'}{SPEECH_LAB_URL}",
            "languages" to mapOf("twi" to "tw")))},"_note":"x","nokey":{"kind":"asr","enabled":true,"endpoint":"${'$'}{NOPE}"},
            "weird":{"kind":"video","enabled":true}}""")
        val health = HashMap<String, ProviderHealth>()
        val r = LanguageLayer.build(clients, secrets, FakeTransport { json(200, """{"text":"Bue"}""") }, { FakeSink() },
            health = { g -> health.getOrPut(g) { ProviderHealth(g) } })
        assertEquals(setOf("lab"), r.asr.keys)
        assertTrue(r.skipped.containsKey("nokey") && r.skipped.containsKey("weird"))
        assertNotNull(LanguageLayer.onlineAsr(Lang.TWI, r.asr, cloudAllowed = true))
        assertNotNull("unknown ids are skipped, the rest still route", LanguageLayer.onlineAsr(Lang.GH_EN, r.asr, cloudAllowed = true))
        assertNull("offline or at a secure (PIN) screen, audio stays on the phone",
            LanguageLayer.onlineAsr(Lang.TWI, r.asr, cloudAllowed = false))
        health.getValue("lab").onFailure(FailureKind.QUOTA)
        assertNull("a resting provider is skipped → on-device", LanguageLayer.onlineAsr(Lang.TWI, r.asr, cloudAllowed = true))
    }

    @Test fun hybrid_asr_with_an_http_twi_client_escalates_twi_and_keeps_english_on_device() = runTest {
        val t = FakeTransport { json(200, """{"text":"Frɛ Ama"}""") }
        val lab = HttpAsrProvider(spec(""","fixedLanguage":"twi" """), { secrets }, t, guard())
        class Dev(val raw: String) : SpeechToRaw {
            override val name = "dev"; override val ready = true
            override suspend fun rawTranscript(wav: ByteArray, forceLanguage: String?) = raw
        }
        // auto mode: on-device hears Twi → escalated to the HTTP Twi client
        val tw = HybridAsr(Dev("frɛ Ama wɔ WhatsApp so"), onlineFor = { if (it == Lang.TWI) lab else null })
        assertEquals("http:lab", tw.transcribe(ByteArray(10), Lang.GH_EN, null).backend)
        // auto mode: on-device hears English → stays on-device, the network is not touched
        val before = t.requests.size
        val en = HybridAsr(Dev("call my brother please"), onlineFor = { if (it == Lang.TWI) lab else null })
        assertEquals("dev", en.transcribe(ByteArray(10), Lang.GH_EN, null).backend)
        assertEquals(before, t.requests.size)
    }

    // ---------------------------------------------------------------- voices

    private class RecTts(override val name: String, val accept: Set<String>) : TtsProvider {
        val said = ArrayList<Pair<String, String>>()
        override suspend fun speak(text: String, language: String): Boolean { said.add(text to language); return language in accept }
        override fun stop() {}
        override fun shutdown() {}
    }

    @Test fun twi_falling_back_to_the_phone_voice_is_read_in_plain_letters_and_english_is_untouched() = runTest {
        val phone = RecTts("system", setOf(Lang.GH_EN))
        val voice = PlainLatinVoice(phone)
        val routing = RoutingTts(english = voice, twi = voice)
        assertTrue(routing.speak("Merefrɛ Kwabɛna.", Lang.TWI))
        assertEquals("Merefre Kwabena.", phone.said.last().first)
        assertTrue(routing.speak("Calling Kwame now.", Lang.GH_EN))
        assertEquals("Calling Kwame now.", phone.said.last().first)
    }

    @Test fun reply_localizer_output_reaches_the_twi_voice_and_failures_speak_the_original() = runTest {
        val twiVoice = RecTts("lab", setOf(Lang.TWI))
        val phone = RecTts("system", setOf(Lang.GH_EN))
        val routing = RoutingTts(english = phone, twi = twiVoice, enToTw = { if (it == "Good morning") "Mema wo akye" else null })
        routing.speak("Good morning", Lang.TWI)
        assertEquals("Mema wo akye", twiVoice.said.last().first)
        // No translation for an ENGLISH line → the English voice says it (2 October 2026: English read by the Twi voice
        // sounded wrong and spent the HCI Lab's 15-a-day quota). App-authored Twi still goes to the Twi voice.
        routing.speak("Opening WhatsApp now", Lang.TWI)
        assertEquals("no translation → the English voice", "Opening WhatsApp now", phone.said.last().first)
        routing.speak("Mabue no.", Lang.TWI)
        assertEquals("Mabue no.", twiVoice.said.last().first)
        val throwing = RoutingTts(english = phone, twi = twiVoice, enToTw = { throw IllegalStateException("boom") })
        assertTrue("a translator crash never silences the reply", throwing.speak("Done", Lang.TWI))
    }
}
