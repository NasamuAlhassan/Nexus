package com.tekyerema.agent.pocket

/** A request as the model sees it: ids plus, per token, the character range of the REQUEST it came from (0,0 for specials). */
class PocketEncoding(val ids: IntArray, val offsets: IntArray, val seq: IntArray) {
    val size get() = ids.size
    fun start(j: Int) = offsets[2 * j]
    fun end(j: Int) = offsets[2 * j + 1]
}

/**
 * BERT uncased WordPiece with character offsets, reproducing the training-time call
 *   tokenizer(request[:600], ' | '.join(last 2 context lines, each last 300 chars) or '-',
 *             truncation=<spec>, max_length=128, return_offsets_mapping=True, return_token_type_ids=False)
 * (run_pocket.py `encode`; spec.json "input_format"). Same clean / lowercase / strip-accents / punctuation split as
 * agent/WordPiece (IntentMatcher.kt), which has no offsets and a 64-token single-sentence limit, hence this variant.
 * Pure -> PocketBrainTest.
 */
class PocketTokenizer(private val vocab: Map<String, Int>, private val spec: PocketSpec) {

    private class Tok(val id: Int, val start: Int, val end: Int)

    /** The context string exactly as training built it. [lines] are older -> newer ("User: ...", "Nexus: ..."). */
    fun contextText(lines: List<String>): String =
        lines.takeLast(spec.contextLines).joinToString(" | ") { it.takeLast(spec.contextCharCap) }.ifEmpty { "-" }

    fun encode(request: String, context: String): PocketEncoding {
        val a = tokens(request.take(spec.requestCharCap)).toMutableList()
        val b = tokens(context).toMutableList()
        val budget = spec.maxLen - 3
        if (a.size + b.size > budget) {
            if (spec.truncation == "only_second") {
                if (a.size > budget) { while (a.size > budget) a.removeAt(a.size - 1) }
                while (a.size + b.size > budget && b.isNotEmpty()) b.removeAt(b.size - 1)
            } else {
                while (a.size + b.size > budget) { if (a.size > b.size) a.removeAt(a.size - 1) else b.removeAt(b.size - 1) }
            }
        }
        val n = a.size + b.size + 3
        val ids = IntArray(n); val off = IntArray(2 * n); val seq = IntArray(n) { -1 }
        var k = 0
        ids[k++] = spec.clsId
        for (t in a) { ids[k] = t.id; off[2 * k] = t.start; off[2 * k + 1] = t.end; seq[k] = 0; k++ }
        ids[k++] = spec.sepId
        for (t in b) { ids[k] = t.id; off[2 * k] = t.start; off[2 * k + 1] = t.end; seq[k] = 1; k++ }
        ids[k] = spec.sepId
        return PocketEncoding(ids, off, seq)
    }

    private fun tokens(text: String): List<Tok> {
        // 1. clean + lowercase + strip accents, remembering which original character each kept character came from
        val nc = StringBuilder(); val st = ArrayList<Int>(); val en = ArrayList<Int>()
        for (i in text.indices) {
            val c = text[i]
            if (c.code == 0 || c.code == 0xFFFD || (Character.isISOControl(c) && c != '\t' && c != '\n' && c != '\r')) continue
            var s = if (Character.isWhitespace(c)) " " else c.toString()
            if (spec.doLowerCase) {
                s = s.lowercase()
                s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            }
            for (ch in s) { nc.append(ch); st.add(i); en.add(i + 1) }
        }
        // 2. split into words on whitespace and punctuation (each punctuation mark is a word)
        val out = ArrayList<Tok>()
        var ws = -1
        fun word(from: Int, to: Int) {
            if (to - from > 100) { out.add(Tok(unk(), st[from], en[to - 1])); return }
            val pieces = ArrayList<Tok>()
            var s = from
            while (s < to) {
                var e = to; var found = -1
                while (s < e) {
                    val sub = (if (s > from) "##" else "") + nc.substring(s, e)
                    val id = vocab[sub]
                    if (id != null) { found = id; break }
                    e--
                }
                if (found < 0) { out.add(Tok(unk(), st[from], en[to - 1])); return }
                pieces.add(Tok(found, st[s], en[e - 1]))
                s = e
            }
            out.addAll(pieces)
        }
        for (i in 0 until nc.length) {
            val c = nc[i]
            when {
                c == ' ' -> { if (ws >= 0) { word(ws, i); ws = -1 } }
                isPunct(c) || isCjk(c) -> { if (ws >= 0) { word(ws, i); ws = -1 }; word(i, i + 1) }
                else -> if (ws < 0) ws = i
            }
        }
        if (ws >= 0) word(ws, nc.length)
        return out
    }

    private fun unk() = vocab["[UNK]"] ?: 100

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

    companion object {
        /** vocab.txt (one token per line) or a HuggingFace tokenizer.json (model.vocab). */
        fun loadVocab(text: String, isJson: Boolean): Map<String, Int> =
            if (isJson) {
                val v = org.json.JSONObject(text).getJSONObject("model").getJSONObject("vocab")
                HashMap<String, Int>(v.length() * 2).also { m -> for (k in v.keys()) m[k] = v.getInt(k) }
            } else text.lines().withIndex().filter { it.value.isNotEmpty() }.associate { (i, t) -> t to i }
    }
}
