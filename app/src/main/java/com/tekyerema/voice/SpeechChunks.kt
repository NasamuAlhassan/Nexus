package com.tekyerema.voice

/**
 * B-06 — split a reply into sentence-sized chunks for the TTS queue. The system engine synthesises a whole utterance
 * before it starts speaking, so a long reply sent in one piece waits for all of it; queued sentences start after the
 * first one. Sentences are packed up to [maxChars]; an over-long sentence is split at commas, then at spaces.
 */
object SpeechChunks {
    private val SENTENCE_END = Regex("(?<=[.!?;:])\\s+")

    fun split(text: String, maxChars: Int = 180): List<String> {
        val t = text.trim()
        if (t.length <= maxChars) return if (t.isEmpty()) emptyList() else listOf(t)
        val pieces = t.split(SENTENCE_END).flatMap { s -> if (s.length <= maxChars) listOf(s) else breakLong(s, maxChars) }
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (p in pieces) {
            if (cur.isNotEmpty() && cur.length + 1 + p.length > maxChars) { out.add(cur.toString()); cur.clear() }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(p)
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private fun breakLong(s: String, maxChars: Int): List<String> {
        val out = ArrayList<String>()
        var rest = s
        while (rest.length > maxChars) {
            val window = rest.substring(0, maxChars)
            val cut = listOf(window.lastIndexOf(", "), window.lastIndexOf(' ')).firstOrNull { it > maxChars / 3 } ?: maxChars
            out.add(rest.substring(0, if (cut == maxChars) cut else cut + 1).trim())
            rest = rest.substring(if (cut == maxChars) cut else cut + 1).trim()
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }
}
