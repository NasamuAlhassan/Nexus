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
    val cacheEntries: Int
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
                cacheEntries = o.optInt("cacheEntries", if (kind == Kind.ASR) 0 else 48)
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
    private val PLACEHOLDER = Regex("\\$\\{([A-Za-z0-9_.]+)}")

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

    private fun putFields(o: JSONObject, spec: HttpSpeechSpec, secrets: Map<String, String>) {
        for ((k, v) in spec.fields) o.put(k, if (v is String) resolve(v, secrets) ?: "" else v)
    }

    private fun fieldValue(v: Any, secrets: Map<String, String>): String =
        if (v is String) resolve(v, secrets) ?: "" else v.toString()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** Turn a non-2xx reply into a classified failure (null = success). */
    fun failureOf(spec: HttpSpeechSpec, reply: HttpReply): ProviderFailure? {
        val c = reply.code
        if (c in 200..299) return null
        val retry = reply.retryAfter?.trim()?.toLongOrNull()?.takeIf { it in 1..86_400 }?.times(1000)
        return when {
            c == 401 -> ProviderFailure(FailureKind.AUTH, "${spec.id} HTTP 401 (check the key in local.properties)")
            // Azure-APIM-style gateways (Khaya's, for one) answer an exhausted subscription with 403; either way the
            // account is refusing us, so rest it rather than retrying every turn.
            c == 403 || c == 429 -> ProviderFailure(FailureKind.QUOTA, "${spec.id} HTTP $c (quota or access)", retry)
            c == 408 || c >= 500 -> ProviderFailure(FailureKind.TRANSIENT, "${spec.id} HTTP $c")
            else -> ProviderFailure(FailureKind.BAD_RESPONSE, "${spec.id} HTTP $c (request shape? check the client config)")
        }
    }

    /** The text answer (transcript / translation). Throws [ProviderFailure] BAD_RESPONSE when it isn't there. */
    fun parseText(spec: HttpSpeechSpec, reply: HttpReply): String {
        val raw = reply.text().trim()
        return when (spec.responseKind) {
            "text" -> if (raw.startsWith("\"")) (JSONTokener(raw).nextValue() as? String ?: raw).trim() else raw
            else -> {
                val root = try { JSONTokener(raw).nextValue() } catch (e: Exception) { throw bad(spec, "reply is not JSON") }
                if (root is String) return root.trim()   // a bare JSON string is a common MT answer
                when (val v = at(root, spec.responsePath)) {
                    is String -> v.trim()
                    is JSONArray -> (0 until v.length()).mapNotNull { v.opt(it) as? String }.joinToString(" ").trim()
                    else -> throw bad(spec, "no text at '${spec.responsePath}'")
                }
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

    private fun bad(spec: HttpSpeechSpec, why: String) = ProviderFailure(FailureKind.BAD_RESPONSE, "${spec.id}: $why")
}
