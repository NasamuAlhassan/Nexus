package com.tekyerema.camera

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * "What is this?" with no network (28 Oct 2026; campaign/kaggle/whatis-v1): names the thing the camera points at, by brand or kind
 * when it is clearly that ("a MacBook", not "a black laptop"), by family when only that is sure ("a laptop"), or not at all.
 *
 * A SigLIP2 image encoder on the phone turns the centre square into one vector; ~550 label vectors (families and their members,
 * Ghana things included: tro-tro, coal pot, kenkey, Ghana Card...) were made once on Kaggle and ship as a matrix. The best match is
 * said only past the margins of [decide] (mirrored from the kernel, pure → WhatIsThisTest).
 *
 * Assets `whatis/`: whatis_img_int8.onnx (float32 [1,224,224,3] RGB 0..255 → [1,D] unit vector), whatis_text.f16 ([L,D] float16),
 * whatis_labels.json ([{label, family, is_family}]), whatis_meta.json (logit_scale, logit_bias).
 */
class WhatIsThis private constructor(ctx: Context) {
    data class Label(val label: String, val family: String, val isFamily: Boolean)
    enum class Kind { ITEM, FAMILY, UNSURE }
    data class Answer(val label: String?, val kind: Kind, val p: Double)

    private val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
    private val session: ai.onnxruntime.OrtSession
    val labels: List<Label>
    private val text: Array<FloatArray>
    private val scale: Double
    private val bias: Double

    init {
        val a = ctx.assets
        session = env.createSession(a.open("$DIR/whatis_img_int8.onnx").use { it.readBytes() },
            ai.onnxruntime.OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) })
        val arr = JSONArray(a.open("$DIR/whatis_labels.json").use { it.readBytes().decodeToString() })
        labels = (0 until arr.length()).map { i -> arr.getJSONObject(i).let { Label(it.getString("label"), it.getString("family"), it.getBoolean("is_family")) } }
        val meta = JSONObject(a.open("$DIR/whatis_meta.json").use { it.readBytes().decodeToString() })
        scale = meta.optDouble("logit_scale", 1.0); bias = meta.optDouble("logit_bias", 0.0)
        val dim = meta.getInt("dim")
        val raw = ByteBuffer.wrap(a.open("$DIR/whatis_text.f16").use { it.readBytes() }).order(ByteOrder.LITTLE_ENDIAN)
        require(raw.capacity() == labels.size * dim * 2) { "whatis text matrix: ${raw.capacity()} bytes for ${labels.size}x$dim" }
        text = Array(labels.size) { FloatArray(dim) { half(raw.short) } }
    }

    /** One 224×224 RGB centre square (0–255) → what to say about it. */
    fun identify(rgb: FloatArray): Answer {
        require(rgb.size == SIDE * SIDE * 3)
        val t = ai.onnxruntime.OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(rgb), longArrayOf(1, SIDE.toLong(), SIDE.toLong(), 3))
        val e = try { session.run(mapOf(session.inputNames.first() to t)).use { out -> @Suppress("UNCHECKED_CAST") (out[0].value as Array<FloatArray>)[0] } }
            finally { t.close() }
        val cos = DoubleArray(text.size) { i -> var s = 0.0; val row = text[i]; for (k in row.indices) s += row[k] * e[k]; s }
        return decide(cos, labels, scale, bias)
    }

    fun close() = try { session.close() } catch (_: Throwable) {}

    companion object {
        private const val DIR = "whatis"
        const val SIDE = 224

        fun installed(ctx: Context): Boolean = try { ctx.assets.list(DIR)?.contains("whatis_img_int8.onnx") == true } catch (_: Throwable) { false }

        fun create(ctx: Context): WhatIsThis? = try { WhatIsThis(ctx.applicationContext) } catch (t: Throwable) {
            Log.w("Tekyerema", "[WHATIS] unavailable: ${t.message}"); null
        }

        /**
         * The kernel's rule: the best label is said when it is a member (not a family), its sigmoid score ≥ [pMin], and it leads its
         * nearest sibling by [margin] and any other family's best by [margin]/2. Otherwise its family, when that is ≥ [famMin] and still
         * ahead of the other families. Otherwise nothing.
         */
        fun decide(cos: DoubleArray, labels: List<Label>, scale: Double, bias: Double, pMin: Double = 0.06, margin: Double = 0.04, famMin: Double = 0.04): Answer {
            if (cos.isEmpty()) return Answer(null, Kind.UNSURE, 0.0)
            fun p(i: Int) = 1.0 / (1.0 + Math.exp(-(scale * cos[i] + bias)))
            val order = cos.indices.sortedByDescending { cos[it] }
            val best = order[0]
            val fam = labels[best].family
            val sib = order.drop(1).take(30).firstOrNull { labels[it].family == fam && !labels[it].isFamily }
            val other = order.drop(1).take(30).firstOrNull { labels[it].family != fam }
            val leadSib = cos[best] - (sib?.let { cos[it] } ?: -1.0)
            val leadOther = cos[best] - (other?.let { cos[it] } ?: -1.0)
            if (p(best) >= pMin && !labels[best].isFamily && leadSib >= margin && leadOther >= margin / 2) return Answer(labels[best].label, Kind.ITEM, p(best))
            val fi = labels.indexOfFirst { it.isFamily && it.family == fam }
            val pf = maxOf(p(best), if (fi >= 0) p(fi) else 0.0)
            if (pf >= famMin && leadOther >= margin / 2) return Answer(fam, Kind.FAMILY, pf)
            return Answer(null, Kind.UNSURE, p(best))
        }

        /** "okada (motorbike taxi)" → "an okada, a motorbike taxi"; "MacBook" → "a MacBook"; "keys on a key ring" → "keys on a key ring". */
        fun spokenName(label: String): String {
            val main = label.substringBefore(" (").trim()
            val aside = label.substringAfter(" (", "").removeSuffix(")").trim()
            fun art(s: String): String {
                val w = s.split(' ').first().lowercase()
                return when {
                    w in setOf("keys", "jeans", "trousers", "shorts", "sneakers", "sandals", "slippers", "boots", "flip-flops", "scissors",
                        "pliers", "groundnuts", "beans", "noodles", "socks", "glasses", "sunglasses", "crutches", "headphones", "earbuds",
                        "wired", "wireless", "over-ear", "playing", "reading", "steps", "rain", "sand", "soil", "grass", "rice", "gari",
                        "shito", "bread", "salt", "chicken", "beef", "fried", "boiled", "light", "groundnut", "palm", "kontomire", "red",
                        "jollof", "waakye", "banku", "kenkey", "fufu", "kelewele", "popcorn", "cake", "pizza", "spaghetti", "underwear",
                        "african", "kente", "sobolo", "palm", "liquid", "toothpaste", "shea", "body", "hand", "money", "food", "fruit",
                        "medicine", "clothing", "footwear", "furniture", "groceries", "stationery", "tools", "nature", "music", "sport") -> s
                    (w.firstOrNull() in setOf('a', 'e', 'i', 'o', 'u') && w !in setOf("usb", "uniform", "one")) || w == "hour" -> "an $s"
                    else -> "a $s"
                }
            }
            return if (aside.isEmpty()) art(main) else "${art(main)}, ${art(aside)}"
        }

        private val NAMING = Regex("(?i)^(?:(?:please|hey|ok|okay|so)[, ]+)*(?:can you |could you )?(?:tell me )?(?:what(?:'s| is| are)?|wetin|identify|name|describe)\\b" +
            "(?!.*\\b(?:time|date|weather|say|said|written|read|colou?r|price|cost|expire|expiry|balance|number|on or off|switched)\\b)")

        /** A question the offline namer can answer: "what is this", "what am I holding", "what's in front of me" — not "what does it
         *  say" (text), "what colour" (colour mode) or "is the stove on" (needs the cloud). */
        fun isNamingQuestion(q: String): Boolean = NAMING.containsMatchIn(q.trim())

        fun spoken(a: Answer): String? = when (a.kind) {
            Kind.ITEM -> "That looks like ${spokenName(a.label!!)}."
            Kind.FAMILY -> "That looks like ${spokenName(a.label!!)}, but I can't tell exactly which."
            Kind.UNSURE -> null
        }

        private fun half(h: Short): Float {
            val bits = h.toInt() and 0xFFFF
            val s = bits ushr 15
            val e = (bits ushr 10) and 0x1F
            val m = bits and 0x3FF
            val v = when (e) {
                0 -> m / 1024f * Math.pow(2.0, -14.0).toFloat()
                31 -> if (m == 0) Float.POSITIVE_INFINITY else Float.NaN
                else -> (1 + m / 1024f) * Math.pow(2.0, (e - 15).toDouble()).toFloat()
            }
            return if (s == 1) -v else v
        }
    }
}
