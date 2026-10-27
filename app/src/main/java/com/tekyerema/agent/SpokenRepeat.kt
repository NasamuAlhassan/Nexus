package com.tekyerema.agent

/**
 * What people do when the assistant did not get it the first time: "I said read my screen", "no, I said open WhatsApp",
 * and the sentence said twice in one go ("read my screen read my screen"). A20e offline, 27 Oct 2026, 12:36: "I said read my
 * screen read my screen" matched no local rule and went through the agent. The correction preface and an exact doubling are
 * dropped, so the request is the one the rules know. Only a LEADING preface and only an exact doubling of at least three words
 * (so "very very" or "no no" are never touched). Pure → SpokenRepeatTest.
 */
object SpokenRepeat {
    private val PREFACE = Regex("(?i)^\\s*(?:no[,.]?\\s+)?(?:i\\s+said|i\\s+was\\s+saying|i\\s+meant|i\\s+mean)\\s*[,:]?\\s+")

    fun clean(text: String): String {
        val stripped = PREFACE.replaceFirst(text, "").trim().ifBlank { return text }
        return halved(stripped) ?: stripped
    }

    /** The first half when [s] is the same words twice (ignoring case and punctuation), at least three words each. */
    private fun halved(s: String): String? {
        val words = s.trim().split(Regex("\\s+"))
        if (words.size < 6 || words.size % 2 != 0) return null
        val half = words.size / 2
        fun key(w: String) = w.lowercase().filter { it.isLetterOrDigit() }
        val a = words.subList(0, half); val b = words.subList(half, words.size)
        return if (a.indices.all { key(a[it]) == key(b[it]) } && a.any { key(it).isNotEmpty() }) a.joinToString(" ").trimEnd(',', ';') else null
    }
}
