package com.tekyerema.language

import com.tekyerema.ime.KeyboardPrefs
import com.tekyerema.ime.LocalLang
import com.tekyerema.ime.ReadBack

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The keyboard's small client for our language Space (kept OUT of ime/, which must never touch the network or log —
 * TwiKeyboardTest guards that; only local-language mode, chosen by the user, calls this) (contract docs/agent/KEYBOARD_LANGUAGES.md §2): speech to text,
 * translation and the local voice. JSON POST to `/web/asr`, `/web/translate`, `/web/tts`, with `X-Nexus-Key` (the app's
 * key, debug builds) and `X-Nexus-Device` ([DeviceTag]). Nothing it sends or gets back is logged (ime/ no-content rule).
 * Every call returns a [Reply]: the value, [Reply.Limited] for a 429, or [Reply.Failed] for anything else (offline, a timeout,
 * a Space error) — the keyboard then says one short English line and dictates in English.
 */
class SpaceVoice(private val base: String, private val headers: Map<String, String>) {

    sealed class Reply<out T> {
        data class Ok<T>(val value: T) : Reply<T>()
        object Limited : Reply<Nothing>()
        data class Failed(val why: String) : Reply<Nothing>()
    }

    suspend fun asr(wav: ByteArray, lang: LocalLang): Reply<String> = withContext(Dispatchers.IO) {
        val body = JSONObject().put("wav_b64", android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP)).put("lang", lang.code)
        // The first call per language after a Space restart takes 5–12 s (contract §2).
        when (val r = post("/web/asr", body, ASR_TIMEOUT_MS)) {
            is Http.Ok -> text(r.bytes, "text")
            is Http.Status -> if (r.code == 429) Reply.Limited else Reply.Failed("asr ${r.code}")
            is Http.Error -> Reply.Failed(r.why)
        }
    }

    suspend fun translate(text: String, src: String, tgt: String): Reply<String> = withContext(Dispatchers.IO) {
        val body = JSONObject().put("text", text.take(1500)).put("src", src).put("tgt", tgt)
        when (val r = post("/web/translate", body, MT_TIMEOUT_MS)) {
            is Http.Ok -> text(r.bytes, "translation")
            is Http.Status -> if (r.code == 429) Reply.Limited else Reply.Failed("translate ${r.code}")
            is Http.Error -> Reply.Failed(r.why)
        }
    }

    /** The local voice saying [text] (figures already in words — [ReadBack.forVoice]) → WAV bytes. */
    suspend fun tts(text: String, lang: LocalLang): Reply<ByteArray> = withContext(Dispatchers.IO) {
        val body = JSONObject().put("text", text.take(ReadBack.MAX)).put("lang", lang.code)
        when (val r = post("/web/tts", body, TTS_TIMEOUT_MS)) {
            is Http.Ok -> if (r.bytes.size > 44) Reply.Ok(r.bytes) else Reply.Failed("tts empty")
            is Http.Status -> if (r.code == 429) Reply.Limited else Reply.Failed("tts ${r.code}")
            is Http.Error -> Reply.Failed(r.why)
        }
    }

    private fun text(bytes: ByteArray, field: String): Reply<String> = try {
        val t = JSONObject(String(bytes, Charsets.UTF_8)).optString(field).trim()
        if (t.isEmpty()) Reply.Failed("empty $field") else Reply.Ok(t)
    } catch (_: Throwable) { Reply.Failed("unreadable reply") }

    private sealed class Http {
        class Ok(val bytes: ByteArray) : Http()
        class Status(val code: Int) : Http()
        class Error(val why: String) : Http()
    }

    private fun post(path: String, body: JSONObject, timeoutMs: Int): Http {
        val c = try { URL(base + path).openConnection() as HttpURLConnection } catch (t: Throwable) { return Http.Error(t.javaClass.simpleName) }
        return try {
            c.requestMethod = "POST"
            c.connectTimeout = CONNECT_TIMEOUT_MS
            c.readTimeout = timeoutMs
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code in 200..299) Http.Ok(c.inputStream.use { it.readBytes() }) else Http.Status(code)
        } catch (t: Throwable) {
            Http.Error(t.javaClass.simpleName)
        } finally { c.disconnect() }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val ASR_TIMEOUT_MS = 25_000
        private const val MT_TIMEOUT_MS = 20_000
        private const val TTS_TIMEOUT_MS = 20_000
        const val KEY_HEADER = "X-Nexus-Key"
        const val DEVICE_HEADER = "X-Nexus-Device"

        /** The Space address from local.properties (SPEECH_SPACE_URL, the host the app's other Space calls use), without a
         *  path; null when there is none or it isn't https. */
        fun baseUrl(): String? {
            val raw = try { LanguageLayer.secrets()["SPEECH_SPACE_URL"] } catch (_: Throwable) { null }
            return normalize(raw)
        }

        /** Pure: "https://x.hf.space/web/ask/" → "https://x.hf.space"; anything not https → null. */
        fun normalize(raw: String?): String? {
            val t = raw?.trim().orEmpty().trimEnd('/')
            if (!t.startsWith("https://", ignoreCase = true) || t.length <= "https://".length) return null
            val u = try { URL(t) } catch (_: Throwable) { return null }
            return "https://" + u.host + (if (u.port > 0) ":${u.port}" else "")
        }

        /** The client for this phone, or null when there is no Space address or no internet. */
        fun forPhone(ctx: Context, prefs: KeyboardPrefs): SpaceVoice? {
            val base = baseUrl() ?: return null
            if (!(try { com.tekyerema.core.NetworkMonitor.isOnline() } catch (_: Throwable) { true })) return null
            val h = LinkedHashMap<String, String>()
            (try { com.tekyerema.BuildConfig.SPACE_APP_KEY } catch (_: Throwable) { "" }).trim().takeIf { it.isNotEmpty() }?.let { h[KEY_HEADER] = it }
            h[DEVICE_HEADER] = prefs.deviceTag(System.currentTimeMillis())
            return SpaceVoice(base, h)
        }
    }
}
