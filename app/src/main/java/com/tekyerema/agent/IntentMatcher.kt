package com.tekyerema.agent

import android.content.Context
import android.util.Log

/**
 * The offline meaning matcher (owner, 2026-09-30: "the local brain works, but it is not that smart"). When the rules
 * (FastIntent + LocalBrain) don't recognise a request, the words are compared by MEANING with a bank of ways people say
 * each phone command — "how much juice is left" is the battery, "on the torch" is the flashlight, "I can't hear" is volume
 * up — and the matching command runs through the same rules. docs/agent/research/offline_agentic_models.md (quick win 1).
 *
 * Model: all-MiniLM-L6-v2 int8 (sentence-transformers, Apache-2.0, 23 MB) on the ONNX Runtime the wake word already ships;
 * the bank's vectors were made by the SAME file (campaign/intent/build_bank.py). ~30–100 ms a request.
 *
 * Deciding (thresholds from the held-out check in campaign/intent/test.json): ACT only with a clear winner — a score of
 * at least `act` AND a lead of `margin` over the best OTHER command, because opposites (brighter/darker, on/off) sit close
 * together; otherwise, above `ask`, "Did you mean …?"; below it, nothing (the usual offline answer). A "yes" to "did you
 * mean" teaches it that wording ([learn]) — the next time it acts at once.
 */
class IntentMatcher private constructor(private val ctx: Context) {

    sealed class Meaning {
        data class Act(val canonical: String, val score: Float) : Meaning()
        data class Ask(val canonical: String, val score: Float) : Meaning()
    }

    private val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
    private val session: ai.onnxruntime.OrtSession
    private val vocab: Map<String, Int>
    private val rows: List<Pair<String, String>>          // phrase → canonical
    private val bank: FloatArray                          // rows × dim, unit vectors
    private val dim: Int
    private val act: Float
    private val margin: Float
    private val ask: Float

    init {
        val opts = ai.onnxruntime.OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
        session = env.createSession(ctx.assets.open("$DIR/minilm.onnx").use { it.readBytes() }, opts)
        vocab = ctx.assets.open("$DIR/vocab.txt").bufferedReader().readLines().withIndex().associate { (i, t) -> t to i }
        val meta = org.json.JSONObject(ctx.assets.open("$DIR/bank.json").bufferedReader().readText())
        dim = meta.getInt("dim"); act = meta.getDouble("act").toFloat(); margin = meta.getDouble("margin").toFloat()
        ask = meta.getDouble("ask").toFloat()
        val a = meta.getJSONArray("rows")
        rows = (0 until a.length()).map { a.getJSONObject(it).let { r -> r.getString("p") to r.getString("c") } }
        val bytes = ctx.assets.open("$DIR/bank.f32").use { it.readBytes() }
        bank = FloatArray(bytes.size / 4).also { java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
        require(bank.size == rows.size * dim) { "bank size ${bank.size} != ${rows.size} x $dim" }
    }

    /** The unit vector of [text] (mean of the model's token vectors — the sentence-transformers recipe). */
    fun embed(text: String): FloatArray {
        val ids = WordPiece.encode(text, vocab)
        val shape = longArrayOf(1, ids.size.toLong())
        val input = LongArray(ids.size) { ids[it].toLong() }
        // Built one by one so a failure mid-way closes what was already made (audit 2026-09-30, half 1: the map
        // literal leaked the earlier tensors when a later createTensor threw).
        val tensors = LinkedHashMap<String, ai.onnxruntime.OnnxTensor>()
        try {
            tensors["input_ids"] = ai.onnxruntime.OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(input), shape)
            tensors["attention_mask"] = ai.onnxruntime.OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(LongArray(ids.size) { 1L }), shape)
            tensors["token_type_ids"] = ai.onnxruntime.OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(LongArray(ids.size)), shape)
            session.run(tensors).use { out ->
                @Suppress("UNCHECKED_CAST")
                val h = (out[0].value as Array<Array<FloatArray>>)[0]
                val v = FloatArray(dim)
                for (t in h) for (d in 0 until dim) v[d] += t[d]
                var n = 0.0
                for (d in 0 until dim) { v[d] /= h.size; n += v[d] * v[d] }
                val norm = Math.sqrt(n).toFloat().takeIf { it > 0f } ?: 1f
                for (d in 0 until dim) v[d] /= norm
                return v
            }
        } finally { tensors.values.forEach { it.close() } }
    }

    fun match(text: String): Meaning? {
        personal(text)?.let { return Meaning.Act(it, 1f) }
        val q = embed(text)
        val scores = FloatArray(rows.size) { r -> var s = 0f; val o = r * dim; for (d in 0 until dim) s += q[d] * bank[o + d]; s }
        return decide(rows.map { it.second }, scores, act, margin, ask)
    }

    // ---- personal wordings: "yes" to a "did you mean" teaches the phrase (stays on the phone)

    private val prefs = ctx.getSharedPreferences("tekyerema_intent_personal", Context.MODE_PRIVATE)
    private fun personal(text: String): String? = prefs.getString(key(text), null)
    fun learn(text: String, canonical: String) { prefs.edit().putString(key(text), canonical).apply() }
    /** "Forget everything" forgets the learned wordings too — they are the user's own words. */
    fun forgetPersonal() { prefs.edit().clear().apply() }
    private fun key(text: String) = text.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    companion object {
        private const val DIR = "intent"
        @Volatile private var instance: IntentMatcher? = null
        @Volatile private var failedAt = 0L

        /** Loaded on first use (≈ 0.3 s); null if the assets are missing or the model can't load (then: the usual
         *  reply). A failure is retried after a minute — one OOM while the 660 MB recogniser was loading used to
         *  kill the matcher until restart (audit 2026-09-30, half 1). */
        fun get(ctx: Context): IntentMatcher? {
            instance?.let { return it }
            if (failedAt != 0L && System.currentTimeMillis() - failedAt < 60_000L) return null
            return synchronized(this) {
                instance ?: try { IntentMatcher(ctx.applicationContext).also { instance = it; failedAt = 0L } }
                catch (t: Throwable) { failedAt = System.currentTimeMillis(); Log.w("Tekyerema", "[MEANING] matcher unavailable: ${t.message}"); null }
            }
        }

        /** The rule, pure: best phrase's command; act on a clear winner, ask on a near one. */
        internal fun decide(canonicals: List<String>, scores: FloatArray, act: Float, margin: Float, ask: Float): Meaning? {
            var best = -1
            for (i in scores.indices) if (best < 0 || scores[i] > scores[best]) best = i
            if (best < 0) return null
            val top = canonicals[best]
            var other = -1f
            for (i in scores.indices) if (canonicals[i] != top && scores[i] > other) other = scores[i]
            val s = scores[best]
            return when {
                s >= act && s - other >= margin -> Meaning.Act(top, s)
                s >= ask -> Meaning.Ask(top, s)
                else -> null
            }
        }

        // ---- "did you mean": the one suggestion waiting for a yes (a minute at most)
        private data class Pending(val said: String, val canonical: String, val at: Long)
        @Volatile private var pending: Pending? = null
        fun suggest(said: String, canonical: String, now: Long = System.currentTimeMillis()) { pending = Pending(said, canonical, now) }
        /** The waiting suggestion as (what was said, the command), cleared once taken; null when none or stale. */
        fun takePending(now: Long = System.currentTimeMillis()): Pair<String, String>? {
            val p = pending ?: return null
            pending = null
            return if (now - p.at <= 60_000L) p.said to p.canonical else null
        }
        fun hasPending(now: Long = System.currentTimeMillis()): Boolean = pending?.let { now - it.at <= 60_000L } == true
    }
}

/**
 * BERT uncased WordPiece, as the model was trained (and as tokenizer.json does it): clean + lowercase + strip accents,
 * split on spaces and punctuation, greedy longest-match pieces with "##", [CLS] … [SEP], at most 64 tokens. Pure; the
 * ids are unit-tested against the reference tokenizer (src/test/resources/intent_token_probe.json).
 */
object WordPiece {
    private const val CLS = "[CLS]"; private const val SEP = "[SEP]"; private const val UNK = "[UNK]"
    private const val MAX_TOKENS = 64

    fun encode(text: String, vocab: Map<String, Int>): IntArray {
        val out = ArrayList<Int>()
        out += vocab.getValue(CLS)
        loop@ for (word in basic(text)) {
            val pieces = pieces(word, vocab)
            for (p in pieces) { if (out.size >= MAX_TOKENS - 1) break@loop; out += p }
        }
        out += vocab.getValue(SEP)
        return out.toIntArray()
    }

    /** Clean, lowercase, strip accents, split on whitespace and punctuation. */
    internal fun basic(text: String): List<String> {
        val cleaned = StringBuilder()
        for (c in text) {
            if (c.code == 0 || c.code == 0xFFFD || (Character.isISOControl(c) && c != '\t' && c != '\n' && c != '\r')) continue
            cleaned.append(if (Character.isWhitespace(c)) ' ' else c)
        }
        val lower = cleaned.toString().lowercase()
        val stripped = java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
        val words = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() { if (cur.isNotEmpty()) { words += cur.toString(); cur.clear() } }
        for (c in stripped) when {
            c == ' ' -> flush()
            isPunct(c) || isCjk(c) -> { flush(); words += c.toString() }
            else -> cur.append(c)
        }
        flush()
        return words
    }

    private fun pieces(word: String, vocab: Map<String, Int>): List<Int> {
        if (word.length > 100) return listOf(vocab.getValue(UNK))
        val out = ArrayList<Int>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found: Int? = null
            while (start < end) {
                val sub = (if (start > 0) "##" else "") + word.substring(start, end)
                vocab[sub]?.let { found = it }
                if (found != null) break
                end--
            }
            if (found == null) return listOf(vocab.getValue(UNK))
            out += found!!
            start = end
        }
        return out
    }

    private fun isPunct(c: Char): Boolean {
        val cp = c.code
        if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
        return when (Character.getType(c).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> true
            else -> false
        }
    }

    private fun isCjk(c: Char): Boolean {
        val cp = c.code
        return cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0xF900..0xFAFF
    }
}
