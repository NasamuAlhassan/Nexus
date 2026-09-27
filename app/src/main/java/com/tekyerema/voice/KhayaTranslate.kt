package com.tekyerema.voice

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Twi→English translation via Ghana NLP's Khaya MT (/v1/translate, lang="tw-en"). Used ONLY to hand a
 * NATURAL Twi request to the cloud operator, which can't plan from Twi text (proven on-device: it typed
 * the Twi words into WhatsApp search). LocalBrain still handles command-style Twi directly and answers in
 * Twi; this step only fires when the local brain defers a Twi goal to the cloud. Best-effort: returns null
 * (⇒ caller keeps the original goal) when the text isn't Twi, the key is missing, or the call fails — never
 * throws into the agent loop. MT quality on complex sentences is limited (no strong Twi MT exists), so this
 * improves simple/moderate Twi but won't perfectly render long multi-clause requests.
 */
class KhayaTranslate(
    private val baseUrl: () -> String,
    private val apiKey: () -> String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    // §10.2 — cache translations so the FIXED strings (confirmations, errors, capability lines) that recur
    // every session aren't re-sent to Khaya each time. Bounded LRU; keyed by lang+source text. Cuts quota ~3×.
    private val cache = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, String>): Boolean = size > 256
    })

    /** @return English translation, or null to leave the goal unchanged (not Twi / no key / failure). */
    suspend fun toEnglish(text: String): String? = translate(text, "tw-en", onlyIf = { looksTwi(it) }, tag = "Twi→En")

    /** English→Twi, so a Twi session is ANSWERED in Twi (§21). Skips text that already looks Twi (no-op)
     *  and returns null on failure so the caller keeps the original. Used to localize the app's spoken
     *  responses (confirmations, errors, cloud results) into Twi before the Khaya voice speaks them. */
    suspend fun toTwi(text: String): String? = translate(text, "en-tw", onlyIf = { !looksTwi(it) }, tag = "En→Twi")

    private suspend fun translate(text: String, lang: String, onlyIf: (String) -> Boolean, tag: String): String? =
        withContext(Dispatchers.IO) {
            val key = apiKey()
            if (key.isBlank() || text.isBlank() || !onlyIf(text)) return@withContext null
            val cacheKey = "$lang|$text"
            cache[cacheKey]?.let { Log.i("Tekyerema", "[MT] $tag cache-hit"); return@withContext it }
            try {
                val url = baseUrl().trimEnd('/') + "/v1/translate"
                val body = JSONObject().put("in", text).put("lang", lang).toString()
                val req = Request.Builder().url(url)
                    .addHeader("Ocp-Apim-Subscription-Key", key)
                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                client.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) { Log.w("Tekyerema", "[MT] Khaya $lang HTTP ${resp.code}"); return@withContext null }
                    val out = parse(raw)
                    Log.i("Tekyerema", "[MT] $tag: '${text.take(50)}' -> '${out.take(50)}'")
                    out.ifBlank { null }?.also { cache[cacheKey] = it }   // §10.2 memoize fixed strings
                }
            } catch (e: Exception) { Log.w("Tekyerema", "[MT] translate failed: ${e.message}"); null }
        }

    private fun parse(body: String): String {
        val s = body.trim()
        return try {
            when {
                s.startsWith("{") -> JSONObject(s).let { j ->
                    j.optString("translation").ifBlank { j.optString("text").ifBlank { j.optString("out") } } }.trim()
                s.startsWith("\"") -> org.json.JSONTokener(s).nextValue().toString().trim()   // bare JSON string
                else -> s
            }
        } catch (_: Throwable) { s }
    }

    companion object {
        // Twi-specific letters (ɛ/ɔ/ŋ) or unambiguous Twi words → worth translating for the cloud. Avoids
        // English-ambiguous tokens ("me"/"wo"/"so") so an English goal is never mis-sent to the translator.
        private val TWI = Regex("[ɛɔŋ]|\\b(mepɛ|ɔka|kyerɛ|ɔmmra|wasap|frɛ|bue|buei|kenkan|aduonu|baako|mmienu|akwaaba|medaase)\\b", RegexOption.IGNORE_CASE)
        fun looksTwi(s: String): Boolean = TWI.containsMatchIn(s)
    }
}
