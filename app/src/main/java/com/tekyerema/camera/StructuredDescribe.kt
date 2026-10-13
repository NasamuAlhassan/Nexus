package com.tekyerema.camera

import org.json.JSONObject

/**
 * P5-07 (R-A4, D-019, 13 October 2026) — the describer's answer as a small contract instead of free prose: what it sees,
 * the text it can actually read, and — the part that matters most to a blind user — when it should not answer at all.
 * VizWiz's lesson: an abstention instruction raised Gemini's abstention recall from 0.14 to 0.51; a confident wrong guess
 * about a note or a pill is worse than "I can't read it". The on-device OCR of the same picture is the authority for exact
 * strings: a figure the model says it read that the OCR did not is spoken hedged, never as fact. Pure, unit-tested; the
 * cloud call and the OCR stay in the activity.
 */
object StructuredDescribe {
    enum class Reason { BLUR, DARK, FRAMING, NOT_VISIBLE, UNSAFE_TO_GUESS }

    /** [structured] = the model honoured the JSON contract; false means prose was all that came back. */
    data class Parsed(val description: String, val visibleText: String, val reason: Reason?, val structured: Boolean)

    /** The describe prompt: strict JSON, plain words, and the VizWiz safety lines. */
    val PROMPT: String = "A blind person is pointing their phone camera at something. Answer ONLY with one JSON object, no " +
        "markdown and no other text, with exactly these keys: \"description\" (what is in front of the camera, two short " +
        "sentences in plain words, the main thing first), \"visible_text\" (any words, numbers or labels you can actually " +
        "read, exactly as written, else an empty string), \"unanswerable_reason\" (null when you can describe it; otherwise " +
        "one of \"blur\" for a picture too blurry to make out, \"dark\" for one too dark, \"framing\" when the subject is cut " +
        "off or too far away, \"not_visible\" when what matters is not in the picture, \"unsafe_to_guess\" for money amounts, " +
        "medicine names or doses you cannot read with certainty — then keep the description to what you can see). " +
        GeminiVision.SAFETY

    private val FENCE = Regex("```(?:json)?\\s*|```", RegexOption.IGNORE_CASE)

    /** The first JSON object in [text] (a code fence or stray words around it are ignored); prose when there is none. */
    fun parse(text: String): Parsed {
        val clean = FENCE.replace(text, "").trim()
        val start = clean.indexOf('{'); val end = clean.lastIndexOf('}')
        if (start >= 0 && end > start) {
            val obj = try { JSONObject(clean.substring(start, end + 1)) } catch (_: Throwable) { null }
            if (obj != null && obj.has("description")) {
                val reason = obj.opt("unanswerable_reason")?.toString()?.trim()?.lowercase()
                    ?.takeIf { it.isNotEmpty() && it != "null" && it != "none" }
                    ?.let { r -> Reason.entries.firstOrNull { it.name.lowercase() == r.replace(' ', '_').replace('-', '_') } }
                return Parsed(
                    description = obj.optString("description").trim(),
                    visibleText = obj.optString("visible_text").trim().takeIf { it != "null" }.orEmpty(),
                    reason = reason, structured = true)
            }
        }
        return Parsed(clean.ifBlank { text.trim() }, "", null, structured = false)
    }

    /** What is said when the model will not describe, per reason. */
    fun abstention(reason: Reason): String = when (reason) {
        Reason.BLUR -> "The picture is too blurry for me to describe. Hold the phone still and try again."
        Reason.DARK -> "It's too dark for me to see. Turn a light on, or try again."
        Reason.FRAMING -> "I can't see enough of it. Move the phone back a little and try again."
        Reason.NOT_VISIBLE -> "What you're asking about isn't in the picture."
        Reason.UNSAFE_TO_GUESS -> "I can see it, but I won't guess at that — it could be money or medicine. Hold it closer and still, and I'll read the text instead."
    }

    /**
     * The sentence(s) to speak. An abstention is spoken as such (with the description only when it still says something
     * about the scene). Text the model read is added when the OCR agrees or no OCR is available; when the OCR read
     * something clearly different it is hedged, never stated.
     */
    fun spoken(p: Parsed, ocrText: String?): String {
        if (p.reason != null) {
            val line = abstention(p.reason)
            val extra = p.description.takeIf { p.reason != Reason.BLUR && p.reason != Reason.DARK && it.isNotBlank() && it.length <= 200 }
            return if (extra != null) "$line $extra" else line
        }
        val base = p.description.ifBlank { "I couldn't make out what's in the picture." }
        // The text it read, on one line and cut at a word (a long notice is read in full by the text mode, not here).
        val raw = p.visibleText.replace(Regex("\\s+"), " ").trim()
        val read = if (raw.length <= 160) raw else raw.take(160).substringBeforeLast(' ') + "…"
        if (read.isBlank() || base.contains(read, ignoreCase = true)) return base
        return if (ocrText != null && diverges(read, ocrText)) "$base I think it says “$read”, but I couldn't read that clearly."
        else "$base It says: “$read”."
    }

    /**
     * True when the model's text is not borne out by the OCR's: any figure the model "read" that the OCR did not is enough
     * (a note, a dose, a reference — the figure is what the user will act on); otherwise fewer than half of its longer words
     * appear in the OCR text. Nothing to compare → not divergent.
     */
    internal fun diverges(visibleText: String, ocrText: String): Boolean {
        val modelNums = numbers(visibleText); val modelWords = words(visibleText)
        if (modelNums.isEmpty() && modelWords.isEmpty()) return false
        val ocrNums = numbers(ocrText); val ocrWords = words(ocrText)
        if (modelNums.any { it !in ocrNums }) return true
        if (modelWords.isEmpty()) return false
        if (ocrWords.isEmpty()) return true
        return modelWords.count { it in ocrWords }.toDouble() / modelWords.size < 0.5
    }

    private fun numbers(s: String): Set<String> = Regex("\\d+(?:[.,]\\d+)?").findAll(s).map { it.value.replace(',', '.') }.toSet()
    private fun words(s: String): Set<String> = s.lowercase().split(Regex("[^\\p{L}]+")).filter { it.length >= 4 }.toSet()

    /** Parse, log the shape (never the words), and return what to say. */
    fun spokenFor(text: String, ocrText: String?, tag: String = "Tekyerema"): String {
        val p = parse(text)
        val divergent = p.reason == null && p.visibleText.isNotBlank() && ocrText != null && diverges(p.visibleText.take(120), ocrText)
        android.util.Log.i(tag, "[CAMERA] describe structured=${p.structured} reason=${p.reason?.name?.lowercase() ?: "none"} " +
            "visibleText=${p.visibleText.length} ocr=${ocrText?.length ?: -1} divergent=$divergent")
        return spoken(p, ocrText)
    }
}
