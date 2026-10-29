package com.tekyerema.language

import com.tekyerema.voice.Lang
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * B-18/X-05 — one REST client shape for any speech or translation API, described entirely by config
 * (`providers.speech.clients.<id>` in assets/config/language.json). A new provider — the UG HCI Lab API first — is a
 * config entry plus keys in local.properties, not a new class. Covers the shapes these APIs actually use:
 *
 *  - audio up as multipart form (`file=@speech.wav`), base64 inside JSON, or the raw request body;
 *  - text up as JSON (`{"text": …, "language": …}`) or form fields;
 *  - the language as a query parameter, a body field, or not at all; MT pairs as one field (`"en-tw"`) or two;
 *  - answers as JSON (dotted path, e.g. `results.0.transcript`), a bare string, or raw audio bytes / base64 audio;
 *  - any auth header (`Authorization: Bearer …`, `x-api-key`, `Ocp-Apim-Subscription-Key`).
 *
 * Endpoints, keys and header values are `${NAME}` placeholders resolved from local.properties `SPEECH_*` entries
 * (BuildConfig.SPEECH_SECRETS_JSON) — no URL or key is ever committed. A client whose placeholders don't resolve, or
 * that isn't `"enabled": true`, is never built, so the default install makes no network speech call at all.
 *
 * Everything here is pure (request in, request out; bytes in, text out) and unit-tested with a fake transport.
 */
data class HttpSpeechSpec(
    val id: String,
    val kind: Kind,
    val enabled: Boolean,
    val endpoint: String,
    val authHeader: String?,
    val authPrefix: String,
    val authKey: String?,
    val headers: Map<String, String>,
    /** App language → the API's code. A language missing here is unsupported by this client. "auto" = no hint. */
    val languages: Map<String, String>,
    /** When the client only speaks one language (a Twi-only recogniser), the language its output is in. */
    val fixedLanguage: String?,
    /** "query" | "body" | "none" — where the language code goes. */
    val languageIn: String,
    val languageField: String,
    /** MT: `"{from}-{to}"` puts both codes in [languageField]; blank uses [fromField] and [toField]. */
    val pairFormat: String,
    val fromField: String,
    val toField: String,
    /** "multipart" | "json" | "raw" (raw = audio bytes as the whole body; ASR only). */
    val body: String,
    val audioField: String,
    val audioFileName: String,
    val audioMime: String,
    val textField: String,
    /** Extra constant fields (model id, speaker id…); string values may hold placeholders. */
    val fields: Map<String, Any>,
    /** "json" | "text" | "audio". */
    val responseKind: String,
    val responsePath: String,
    /** ASR: where the API reports the language it heard (optional); mapped back through [languages]. */
    val languagePath: String?,
    val connectMs: Long,
    val callMs: Long,
    val quotaGroup: String,
    /** Plain http is refused unless explicitly allowed (a LAN test server); keys never travel in clear by accident. */
    val allowHttp: Boolean,
    val cacheEntries: Int,
    /** The plan's quotas, counted on the phone ([QuotaLedger]) so a spent quota costs no call. */
    val limits: QuotaLedger.Limits = QuotaLedger.Limits(),
    /** TTS: the most characters one request may carry (the HCI Lab gateway: 250); 0 = no limit. */
    val maxChars: Int = 0,
    /** TTS: a reply needing more than this many requests is left to the phone's own voice (saves a small quota). */
    val maxParts: Int = 1,
    /** "rest" (one request, one answer) | "gradio" — a Gradio Space's two-step API: POST `/gradio_api/call/<api>` with
     *  `{"data":[…]}` → an event id, then GET `/gradio_api/call/<api>/<id>` → the answer as a server-sent event. */
    val dialect: String = "rest",
    /** Gradio: the endpoint's api_name (`translate`). */
    val api: String = "translate",
    /** One timeout rests this client's account at once (the HCI Lab, 16 Oct 2026) — for a client with a backup after it
     *  whose account isn't shared with other roles; others rest after two failures in a row ([ProviderHealth]). */
    val restOnTimeout: Boolean = false
) {
    enum class Kind { ASR, TTS, MT }

    companion object {
        /** Parse one client entry. Unknown kinds return null (skipped and logged by the builder). */
        fun parse(id: String, o: JSONObject): HttpSpeechSpec? {
            val kind = when (o.optString("kind").lowercase()) {
                "asr" -> Kind.ASR; "tts" -> Kind.TTS; "mt", "translate", "translation" -> Kind.MT; else -> return null
            }
            val auth = o.optJSONObject("auth") ?: JSONObject()
            val req = o.optJSONObject("request") ?: JSONObject()
            val res = o.optJSONObject("response") ?: JSONObject()
            val t = o.optJSONObject("timeouts") ?: JSONObject()
            val defaultBody = if (kind == Kind.ASR) "multipart" else "json"
            val defaultResponse = if (kind == Kind.TTS) "audio" else "json"
            return HttpSpeechSpec(
                id = id,
                kind = kind,
                enabled = o.optBoolean("enabled", false),
                endpoint = o.optString("endpoint").trim(),
                authHeader = auth.optString("header").trim().ifBlank { null },
                authPrefix = auth.optString("prefix", ""),
                authKey = auth.optString("key").trim().ifBlank { null },
                headers = stringMap(o.optJSONObject("headers")),
                languages = stringMap(o.optJSONObject("languages")),
                fixedLanguage = o.optString("fixedLanguage").trim().ifBlank { null },
                languageIn = req.optString("language", "body").lowercase(),
                languageField = req.optString("languageField", "language"),
                pairFormat = req.optString("pairFormat", ""),
                fromField = req.optString("fromField", "source"),
                toField = req.optString("toField", "target"),
                body = req.optString("body", defaultBody).lowercase(),
                audioField = req.optString("audioField", if (kind == Kind.ASR) "file" else "audio"),
                audioFileName = req.optString("fileName", "speech.wav"),
                audioMime = req.optString("audioMime", "audio/wav"),
                textField = req.optString("textField", "text"),
                fields = anyMap(req.optJSONObject("fields")),
                responseKind = res.optString("kind", defaultResponse).lowercase(),
                responsePath = res.optString("path", if (kind == Kind.TTS) "audio" else "text"),
                languagePath = res.optString("languagePath").trim().ifBlank { null },
                connectMs = t.optLong("connectMs", 5_000L),
                callMs = t.optLong("callMs", if (kind == Kind.TTS) 20_000L else 15_000L),
                quotaGroup = o.optString("quotaGroup").trim().ifBlank { id },
                allowHttp = o.optBoolean("allowHttp", false),
                cacheEntries = o.optInt("cacheEntries", if (kind == Kind.ASR) 0 else 48),
                limits = QuotaLedger.Limits.parse(o.optJSONObject("limits")),
                maxChars = req.optInt("maxChars", 0).coerceAtLeast(0),
                maxParts = req.optInt("maxParts", 1).coerceAtLeast(1),
                dialect = o.optString("dialect", "rest").trim().lowercase().ifBlank { "rest" },
                api = req.optString("api", "translate").trim().ifBlank { "translate" },
                restOnTimeout = o.optBoolean("restOnTimeout", false)
            )
        }

        private fun stringMap(o: JSONObject?): Map<String, String> {
            o ?: return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (k in o.keys()) if (!k.startsWith("_")) o.optString(k).takeIf { it.isNotBlank() }?.let { out[k] = it }
            return out
        }

        private fun anyMap(o: JSONObject?): Map<String, Any> {
            o ?: return emptyMap()
            val out = LinkedHashMap<String, Any>()
            for (k in o.keys()) if (!k.startsWith("_")) o.opt(k)?.takeIf { it != JSONObject.NULL }?.let { out[k] = it }
            return out
        }
    }
}

/** What came back from the wire. */
class HttpReply(val code: Int, val body: ByteArray, val contentType: String?, val retryAfter: String?) {
    fun text(): String = String(body, Charsets.UTF_8)
}

/** Sends one request; the OkHttp implementation lives in [OkHttpSpeechTransport], tests use a fake. */
fun interface SpeechTransport {
    suspend fun send(request: Request, connectMs: Long, callMs: Long): HttpReply
}

/** The pure core: placeholders, request building, response parsing, error classification. */
object HttpSpeech {
    // The "}" is escaped: Android's ICU regex rejects a bare one (the whole speech-client layer failed to start on the
    // A20e, while Java — the unit tests — accepted it). AndroidRegexCompatTest now checks every literal for this.
    private val PLACEHOLDER = Regex("\\$\\{([A-Za-z0-9_.]+)\\}")

    /** Replace `${NAME}` from [secrets]; null when any placeholder has no (non-blank) value. */
    fun resolve(template: String, secrets: Map<String, String>): String? {
        var missing = false
        val out = PLACEHOLDER.replace(template) { m ->
            val v = secrets[m.groupValues[1]]?.trim()
            if (v.isNullOrEmpty()) { missing = true; "" } else v
        }
        return if (missing) null else out
    }

    /** Null when the spec can be used with these secrets, else the reason (logged; never contains a secret). */
    fun unusableReason(spec: HttpSpeechSpec, secrets: Map<String, String>): String? {
        if (!spec.enabled) return "disabled"
        val url = resolve(spec.endpoint, secrets) ?: return "endpoint placeholder not set in local.properties"
        if (url.isBlank()) return "no endpoint"
        val parsed = url.toHttpUrlOrNull() ?: return "endpoint is not a URL"
        if (!parsed.isHttps && !spec.allowHttp) return "endpoint must be https (set allowHttp for a LAN test server)"
        if (spec.authHeader != null && spec.authKey != null && resolve(spec.authKey, secrets) == null) return "auth key placeholder not set"
        for ((k, v) in spec.headers) if (resolve(v, secrets) == null) return "header $k placeholder not set"
        for ((k, v) in spec.fields) if (v is String && resolve(v, secrets) == null) return "field $k placeholder not set"
        if (spec.kind == HttpSpeechSpec.Kind.ASR && spec.body !in setOf("multipart", "json", "raw")) return "unknown request body '${spec.body}'"
        if (spec.kind != HttpSpeechSpec.Kind.ASR && spec.body !in setOf("multipart", "json")) return "unknown request body '${spec.body}'"
        return null
    }

    /** The API's code for an app language, or null when the client doesn't support it. A null language = auto. */
    fun languageCode(spec: HttpSpeechSpec, lang: String?): String? = spec.languages[lang ?: Lang.AUTO]

    fun asrRequest(spec: HttpSpeechSpec, secrets: Map<String, String>, wav: ByteArray, langCode: String?): Request {
        val body = when (spec.body) {
            "raw" -> wav.toRequestBody(spec.audioMime.toMediaType())
            "json" -> JSONObject().apply {
                put(spec.audioField, java.util.Base64.getEncoder().encodeToString(wav))
                if (langCode != null && spec.languageIn == "body") put(spec.languageField, langCode)
                putFields(this, spec, secrets)
            }.toString().toRequestBody(JSON)
            else -> MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                addFormDataPart(spec.audioField, spec.audioFileName, wav.toRequestBody(spec.audioMime.toMediaType()))
                if (langCode != null && spec.languageIn == "body") addFormDataPart(spec.languageField, langCode)
                for ((k, v) in spec.fields) addFormDataPart(k, fieldValue(v, secrets))
            }.build()
        }
        return build(spec, secrets, body, if (spec.languageIn == "query") langCode?.let { mapOf(spec.languageField to it) } else null)
    }

    fun ttsRequest(spec: HttpSpeechSpec, secrets: Map<String, String>, text: String, langCode: String): Request =
        textRequest(spec, secrets, text, mapOf(spec.languageField to langCode))

    fun mtRequest(spec: HttpSpeechSpec, secrets: Map<String, String>, text: String, fromCode: String, toCode: String): Request {
        val lang = if (spec.pairFormat.isNotBlank())
            mapOf(spec.languageField to spec.pairFormat.replace("{from}", fromCode).replace("{to}", toCode))
        else mapOf(spec.fromField to fromCode, spec.toField to toCode)
        return textRequest(spec, secrets, text, lang)
    }

    private fun textRequest(spec: HttpSpeechSpec, secrets: Map<String, String>, text: String, lang: Map<String, String>): Request {
        val inBody = spec.languageIn == "body"
        val body = when (spec.body) {
            "multipart" -> MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                addFormDataPart(spec.textField, text)
                if (inBody) for ((k, v) in lang) addFormDataPart(k, v)
                for ((k, v) in spec.fields) addFormDataPart(k, fieldValue(v, secrets))
            }.build()
            else -> JSONObject().apply {
                put(spec.textField, text)
                if (inBody) for ((k, v) in lang) put(k, v)
                putFields(this, spec, secrets)
            }.toString().toRequestBody(JSON)
        }
        return build(spec, secrets, body, if (spec.languageIn == "query") lang else null)
    }

    private fun build(spec: HttpSpeechSpec, secrets: Map<String, String>, body: okhttp3.RequestBody, query: Map<String, String>?): Request {
        val base = (resolve(spec.endpoint, secrets) ?: error("${spec.id}: endpoint not configured")).toHttpUrlOrNull()
            ?: error("${spec.id}: endpoint is not a URL")
        val url = base.newBuilder().apply { query?.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val rb = Request.Builder().url(url).post(body)
        if (spec.authHeader != null && spec.authKey != null)
            rb.header(spec.authHeader, spec.authPrefix + (resolve(spec.authKey, secrets) ?: error("${spec.id}: key not configured")))
        for ((k, v) in spec.headers) rb.header(k, resolve(v, secrets) ?: error("${spec.id}: header $k not configured"))
        return rb.build()
    }

    // ---- Google Gemini TTS (dialect "gemini"): the owner's Kore voice for English replies (29 Oct 2026)

    /**
     * `POST <endpoint>` (…/v1beta/interactions) with the text as a verbatim transcript, the delivery in a `speech_metadata`
     * style annotation and the prebuilt voice in `speech_config`: fields `model`, `voice`, `style` (config). Unary: the
     * answer is one WAV (24 kHz mono 16-bit) — fetched sentence by sentence by [com.tekyerema.voice.RoutingTts].
     */
    fun geminiTtsRequest(spec: HttpSpeechSpec, secrets: Map<String, String>, text: String): Request {
        val field = { k: String -> spec.fields[k]?.let { fieldValue(it, secrets) }.orEmpty() }
        val part = JSONObject().put("type", "text").put("text", text)
        field("style").takeIf { it.isNotBlank() }?.let { part.put("annotations", JSONArray().put(JSONObject().put("type", "speech_metadata").put("style", it))) }
        val body = JSONObject()
            .put("model", field("model"))
            .put("input", JSONArray().put(JSONObject().put("type", "user_input").put("content", JSONArray().put(part))))
            .put("response_format", JSONObject().put("type", "audio"))
            .put("generation_config", JSONObject().put("speech_config", JSONArray().put(JSONObject().put("voice", field("voice")))))
        val url = (resolve(spec.endpoint, secrets) ?: error("${spec.id}: endpoint not configured")).toHttpUrlOrNull()
            ?: error("${spec.id}: endpoint is not a URL")
        return authorised(spec, secrets, Request.Builder().url(url).post(body.toString().toRequestBody(JSON)))
    }

    /** The audio of a Gemini TTS answer: the first `{"type": "audio", "data": <base64>}` anywhere in the reply
     *  (`steps[] → model_output → content[]`; searched rather than pathed, so a reshuffled envelope still plays). */
    fun parseGeminiAudio(spec: HttpSpeechSpec, reply: HttpReply): ByteArray {
        val root = try { JSONTokener(reply.text().trim()).nextValue() } catch (e: Exception) { throw bad(spec, "reply is not JSON") }
        fun find(o: Any?): String? = when (o) {
            is JSONObject -> if (o.optString("type") == "audio" && o.optString("data").isNotBlank()) o.optString("data")
                else o.keys().asSequence().firstNotNullOfOrNull { find(o.opt(it)) }
            is JSONArray -> (0 until o.length()).firstNotNullOfOrNull { find(o.opt(it)) }
            else -> null
        }
        val b64 = find(root) ?: throw bad(spec, "no audio in the reply")
        val audio = try { java.util.Base64.getMimeDecoder().decode(b64) } catch (e: IllegalArgumentException) { throw bad(spec, "audio is not base64") }
        if (audio.size < 64) throw bad(spec, "audio reply too short (${audio.size} bytes)")
        return audio
    }

    // ---- Gradio Spaces (dialect "gradio"): our own language Space, PrinceAlhassanNasamu/tekyerema-lang (2 October 2026)

    /** Step 1: `POST <endpoint>/gradio_api/call/<api>` with `{"data": [text, from, to]}` → `{"event_id": …}`. */
    fun gradioCallRequest(spec: HttpSpeechSpec, secrets: Map<String, String>, data: JSONArray): Request {
        val base = (resolve(spec.endpoint, secrets) ?: error("${spec.id}: endpoint not configured")).trimEnd('/')
        val url = ("$base/gradio_api/call/${spec.api}").toHttpUrlOrNull() ?: error("${spec.id}: endpoint is not a URL")
        return authorised(spec, secrets, Request.Builder().url(url).post(JSONObject().put("data", data).toString().toRequestBody(JSON)))
    }

    /** Step 2: `GET <endpoint>/gradio_api/call/<api>/<event id>` → the answer as a server-sent event stream. */
    fun gradioResultRequest(spec: HttpSpeechSpec, secrets: Map<String, String>, eventId: String): Request {
        val base = (resolve(spec.endpoint, secrets) ?: error("${spec.id}: endpoint not configured")).trimEnd('/')
        val url = ("$base/gradio_api/call/${spec.api}/$eventId").toHttpUrlOrNull() ?: error("${spec.id}: bad event id")
        return authorised(spec, secrets, Request.Builder().url(url).get())
    }

    private fun authorised(spec: HttpSpeechSpec, secrets: Map<String, String>, rb: Request.Builder): Request {
        if (spec.authHeader != null && spec.authKey != null)
            rb.header(spec.authHeader, spec.authPrefix + (resolve(spec.authKey, secrets) ?: error("${spec.id}: key not configured")))
        for ((k, v) in spec.headers) rb.header(k, resolve(v, secrets) ?: error("${spec.id}: header $k not configured"))
        return rb.build()
    }

    /** The event id from step 1. Throws [ProviderFailure] BAD_RESPONSE when there is none. */
    fun gradioEventId(spec: HttpSpeechSpec, reply: HttpReply): String =
        try { JSONObject(reply.text()).optString("event_id") } catch (_: Exception) { "" }
            .ifBlank { throw bad(spec, "no event_id") }

    /**
     * The text answer from step 2's event stream ("event: complete" then "data: [<output>]"). Our Space returns its JSON as
     * a STRING output (`"{\"translation\": …, \"covered\": true}"`): parsed, [HttpSpeechSpec.responsePath] picks the text,
     * and `"covered": false` (the Space passed the text through untranslated) is null — never a fake translation.
     */
    fun parseGradio(spec: HttpSpeechSpec, stream: String): String? {
        var event = ""
        for (line in stream.lineSequence()) {
            val l = line.trimEnd('\r')
            if (l.startsWith("event:")) event = l.removePrefix("event:").trim()
            else if (l.startsWith("data:") && event == "complete") {
                val arr = try { JSONArray(l.removePrefix("data:").trim()) } catch (e: Exception) { throw bad(spec, "complete event is not a list") }
                val first = arr.opt(0) ?: return null
                val obj = when (first) {
                    is JSONObject -> first
                    is String -> if (first.trimStart().startsWith("{")) try { JSONObject(first) } catch (_: Exception) { null } else return first.trim().ifBlank { null }
                    else -> null
                } ?: return null
                if (!obj.optBoolean("covered", true)) return null
                for (path in spec.responsePath.split('|').map { it.trim() }) (at(obj, path) as? String)?.let { return it.trim().ifBlank { null } }
                return null
            } else if (l.startsWith("data:") && event == "error") throw bad(spec, "the Space reported an error")
        }
        throw bad(spec, "no complete event")
    }

    private fun putFields(o: JSONObject, spec: HttpSpeechSpec, secrets: Map<String, String>) {
        for ((k, v) in spec.fields) o.put(k, if (v is String) resolve(v, secrets) ?: "" else v)
    }

    private fun fieldValue(v: Any, secrets: Map<String, String>): String =
        if (v is String) resolve(v, secrets) ?: "" else v.toString()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** Turn a non-2xx reply into a classified failure (null = success). */
    fun failureOf(spec: HttpSpeechSpec, reply: HttpReply, now: Long = System.currentTimeMillis()): ProviderFailure? {
        val c = reply.code
        if (c in 200..299) return null
        val retry = reply.retryAfter?.trim()?.toLongOrNull()?.takeIf { it in 1..86_400 }?.times(1000)
        // The HCI Lab gateway names the limit it hit: {"error":{"code":"weekly_quota_exceeded",…}}. Rest until THAT window
        // can have reset — a spent day or week isn't worth retrying every 15 minutes (each try is a slow failed call).
        val code = errorCode(reply)
        if (code != null && ("quota" in code || "limit" in code || "rate" in code)) {
            val window = when { "week" in code -> "week"; "day" in code || "daily" in code -> "day"; else -> null }
            val rest = retry ?: when (window) {
                "week" -> 12 * 60 * 60_000L
                "day" -> msUntilUtcMidnight(now)
                else -> 60_000L   // a per-minute rate limit
            }
            return ProviderFailure(FailureKind.QUOTA, "${spec.id} HTTP $c ($code)", rest, window)
        }
        return when {
            c == 401 -> ProviderFailure(FailureKind.AUTH, "${spec.id} HTTP 401 (check the key in local.properties)")
            // Azure-APIM-style gateways (Khaya's, for one) answer an exhausted subscription with 403; either way the
            // account is refusing us, so rest it rather than retrying every turn.
            c == 403 -> ProviderFailure(FailureKind.QUOTA, "${spec.id} HTTP $c (quota or access)", retry)
            // a bare 429 is "too many just now": rest a minute, not the 15 minutes of a spent quota — Khaya's account
            // carries Twi hearing, voice AND translation, so a long rest took all of Twi with it (16 Oct 2026)
            c == 429 -> ProviderFailure(FailureKind.QUOTA, "${spec.id} HTTP 429 (too many requests)", retry ?: 60_000L)
            c == 408 || c >= 500 -> ProviderFailure(FailureKind.TRANSIENT, "${spec.id} HTTP $c")
            else -> ProviderFailure(FailureKind.BAD_RESPONSE, "${spec.id} HTTP $c (request shape? check the client config)")
        }
    }

    /** `error.code` of a JSON error body, lower-cased, or null. */
    fun errorCode(reply: HttpReply): String? = try {
        val o = JSONTokener(reply.text().trim()).nextValue() as? JSONObject
        // the HCI Lab: {"error":{"code":…}}; Khaya's FastAPI backend: {"detail":"RATE_LIMIT_EXCEEDED"} / {"error":"…"}
        (o?.optJSONObject("error")?.optString("code")?.takeIf { it.isNotBlank() }
            ?: (o?.opt("detail") as? String) ?: (o?.opt("error") as? String))
            ?.trim()?.lowercase()?.ifBlank { null }
    } catch (_: Exception) { null }

    fun msUntilUtcMidnight(now: Long): Long {
        val day = 24 * 60 * 60_000L
        return (day - now.mod(day)).coerceAtLeast(60_000L)
    }

    /** A TTS text split into parts of at most [max] characters, at sentence ends, else between words. */
    fun parts(text: String, max: Int): List<String> {
        val t = text.trim()
        if (max <= 0 || t.length <= max) return listOf(t)
        val out = ArrayList<String>()
        var cur = StringBuilder()
        fun flush() { if (cur.isNotBlank()) out += cur.toString().trim(); cur = StringBuilder() }
        for (sentence in t.split(Regex("(?<=[.!?…])\\s+"))) {
            val pieces = if (sentence.length <= max) listOf(sentence) else sentence.split(Regex("\\s+"))
            for (p in pieces) {
                if (cur.isNotEmpty() && cur.length + 1 + p.length > max) flush()
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(if (p.length > max) p.take(max) else p)
            }
            if (sentence.length > max) flush()
        }
        flush()
        return out
    }

    /** The text answer (transcript / translation). Throws [ProviderFailure] BAD_RESPONSE when it isn't there. */
    fun parseText(spec: HttpSpeechSpec, reply: HttpReply): String {
        val raw = reply.text().trim()
        return when (spec.responseKind) {
            "text" -> if (raw.startsWith("\"")) (JSONTokener(raw).nextValue() as? String ?: raw).trim() else raw
            else -> {
                val root = try { JSONTokener(raw).nextValue() } catch (e: Exception) { throw bad(spec, "reply is not JSON") }
                if (root is String) return root.trim()   // a bare JSON string is a common MT answer
                // "a|b|c": the first path that holds text — for an API whose reply field isn't documented yet.
                for (path in spec.responsePath.split('|').map { it.trim() }) when (val v = at(root, path)) {
                    is String -> return v.trim()
                    is JSONArray -> return (0 until v.length()).mapNotNull { v.opt(it) as? String }.joinToString(" ").trim()
                    else -> {}
                }
                throw bad(spec, "no text at '${spec.responsePath}'")
            }
        }
    }

    /** The language the API says it heard, as an app code (via [HttpSpeechSpec.languages] reversed), or null. */
    fun parseLanguage(spec: HttpSpeechSpec, reply: HttpReply): String? {
        val path = spec.languagePath ?: return null
        val root = try { JSONTokener(reply.text().trim()).nextValue() } catch (_: Exception) { return null }
        val code = (at(root, path) as? String)?.trim()?.lowercase() ?: return null
        spec.languages.entries.firstOrNull { it.value.lowercase() == code && it.key != Lang.AUTO }?.let { return it.key }
        return code.takeIf { it == Lang.TWI || it == Lang.GH_EN }
    }

    /** The audio answer (TTS). Raw bytes, or base64 (optionally a data: URI) at the JSON path. */
    fun parseAudio(spec: HttpSpeechSpec, reply: HttpReply): ByteArray {
        if (spec.responseKind == "audio") {
            val ct = reply.contentType?.lowercase().orEmpty()
            // A JSON or text body on a 200 is an error message from the API, not sound — never "play" it.
            if ("json" in ct || ct.startsWith("text/")) throw bad(spec, "expected audio, got $ct")
            if (reply.body.size < 64) throw bad(spec, "audio reply too short (${reply.body.size} bytes)")
            return reply.body
        }
        val root = try { JSONTokener(reply.text().trim()).nextValue() } catch (e: Exception) { throw bad(spec, "reply is not JSON") }
        val b64 = (at(root, spec.responsePath) as? String)?.substringAfter("base64,")?.trim()
            ?: throw bad(spec, "no audio at '${spec.responsePath}'")
        return try { java.util.Base64.getMimeDecoder().decode(b64) } catch (e: IllegalArgumentException) { throw bad(spec, "audio is not base64") }
    }

    /** Dotted-path lookup into parsed JSON: `results.0.alternatives.0.transcript`. Empty path = the root. */
    fun at(root: Any?, path: String): Any? {
        var cur: Any? = root
        if (path.isBlank()) return cur
        for (part in path.split('.')) {
            cur = when (cur) {
                is JSONObject -> cur.opt(part)
                is JSONArray -> part.toIntOrNull()?.let { cur.opt(it) }
                else -> null
            }
            if (cur == null || cur == JSONObject.NULL) return null
        }
        return cur
    }

    /** The voice file's URL from a Gradio TTS event stream (`event: complete` / `data: [{"url": …}]`); throws BAD_RESPONSE when absent. */
    fun gradioFileUrl(spec: HttpSpeechSpec, stream: String): String {
        var event = ""
        for (line in stream.lineSequence()) {
            val l = line.trimEnd('\r')
            if (l.startsWith("event:")) event = l.removePrefix("event:").trim()
            else if (l.startsWith("data:") && event == "complete") {
                val arr = try { JSONArray(l.removePrefix("data:").trim()) } catch (e: Exception) { throw bad(spec, "complete event is not a list") }
                (arr.optJSONObject(0)?.optString("url"))?.takeIf { it.startsWith("https://") }?.let { return it }
            }
        }
        throw bad(spec, "no audio file in the reply")
    }

    private fun bad(spec: HttpSpeechSpec, why: String) = ProviderFailure(FailureKind.BAD_RESPONSE, "${spec.id}: $why")
}
