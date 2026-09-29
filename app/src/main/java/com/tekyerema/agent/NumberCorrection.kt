package com.tekyerema.agent

import org.json.JSONObject

/**
 * Correcting ONE group of a number while it is being confirmed — "the middle is 4 5 6", "the last four is 8 0 1 1",
 * "the first part is 054", "change 323 to 332", or the whole number again ("it's 0 5 5 3 2 3 8 0 1 1"). Pure.
 *
 * Why: the read-back is grouped 3-3-4 ([NumberSpeech]) so a user can catch a misheard group; making them say all ten
 * digits again to fix three is where the next mishearing comes from (research: african_voice_and_protocols.md,
 * recommendation 1 — "read back in 3-3-4 groups and let the user correct one group"). The fix is applied HERE,
 * exactly, from the words — a model never "repairs" digits — and the corrected action is always confirmed again
 * in full ([revise]; AgentEngine's revise path).
 *
 * Returns null whenever the words aren't clearly a digit fix that fits the number (a different length, an ambiguous
 * "change 5 to 6", something about the amount or the message): the reply then goes to the planner as an ordinary
 * revision, which proposes again — and that is confirmed again too.
 */
object NumberCorrection {

    private val UNITS = mapOf(
        "zero" to "0", "nought" to "0", "nil" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4",
        "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9",
        // Twi digit words (the Twi normaliser usually turns these into digits already). [owner: verify with a native speaker]
        "hwee" to "0", "baako" to "1", "mmienu" to "2", "abien" to "2", "mmiɛnsa" to "3", "mmiensa" to "3", "abiɛsa" to "3",
        "anan" to "4", "anum" to "5", "nsia" to "6", "nson" to "7", "nwɔtwe" to "8", "nwotwe" to "8", "nkron" to "9"
    )
    /** "oh"/"o" is a zero only next to other digits — "oh, the middle is 456" starts with an interjection. */
    private val OH = setOf("oh", "o")

    /** A reply about the amount, the message or the reference is not a number fix, even when it holds digits. */
    private val NOT_THE_NUMBER = Regex("(?i)\\b(?:cedis?|pesewas?|ghs|amount|money|message|words|saying|" +
        "reference|ref|minutes?|hours?|o'?clock)\\b|₵")
    /** A reply that stops rather than corrects stays a decline ([looksLikeFix]). */
    private val STOP = Regex("(?i)\\b(?:don'?t|do not|cancel|stop|never ?mind|forget it|leave it|abort)\\b")

    // These run on [digitize]d text: spoken digits are digit runs by then.
    private val POSITION = Regex(
        "\\b(first|second|third|middle|centre|center|last|final|end|start|beginning|front)\\b" +
            "(?:\\s+([2-6])(?=\\s))?" +                                   // "the last 4 …" (a count, digitised)
            "(?:\\s+(?:part|group|bit|section|set|block|piece|digits?|numbers?|ones?))?" +
            "(?:\\s+(?:of\\s+(?:the\\s+|it|that)?\\s*(?:number)?))?" +
            "[^\\d]{0,40}?(\\d+)")
    private val ENDS_WITH = Regex("\\b(?:ends?|ending|finish(?:es)?)\\s+(?:with|in)\\s+(\\d+)")
    private val STARTS_WITH = Regex("\\b(?:starts?|starting|begins?|beginning)\\s+(?:with|from)\\s+(\\d+)")
    private val CHANGE = listOf(
        // change 323 to 332 / replace 323 with 332 / make 323 332
        Regex("\\b(?:change|replace|swap|correct|make|fix)\\s+(?:the\\s+)?(\\d+)\\s+(?:to|with|into|as|for|by)?\\s*(\\d+)") to false,
        // 323 should be 332 / 323 is wrong, it's 332 / 323 is meant to be 332
        Regex("(\\d+)\\s+(?:should be|must be|is meant to be|is supposed to be|is wrong\\s*,?\\s*(?:it'?s|it is|it should be)?)\\s*(\\d+)") to false,
        // not 323, 332 / not 323 but 332
        Regex("\\bnot\\s+(\\d+)\\s*,?\\s*(?:but|it'?s|it is)?\\s*(\\d+)") to false,
        // 332 not 323 / 332 instead of 323
        Regex("(\\d+)\\s*,?\\s+(?:not|instead of|rather than)\\s+(\\d+)") to true
    )

    /**
     * The reply as lowercase words with every spoken digit made a digit and neighbouring digits joined into one run:
     * "oh double five three" → "0553". A comma stays a token (" , ") so "not 323, 332" keeps two runs; a count right
     * after a position word stays its own run ("the last four 8 0 1 1" → "the last 4 8011"); "one" after a position
     * word is the word, not a digit ("the first one is 054").
     */
    internal fun digitize(utterance: String): String {
        val words = utterance.lowercase()
            .replace(Regex("[’`]"), "'")
            .replace(Regex("(?<=\\d)[-.](?=\\d)"), " ")
            .replace(",", " , ")
            .replace(Regex("[^\\p{L}\\p{N}', ]"), " ")
            .split(Regex("\\s+")).filter { it.isNotBlank() }
        // First pass: digit words → digits; double/triple X → XX/XXX.
        val toks = ArrayList<String>()
        var i = 0
        while (i < words.size) {
            val w = words[i]
            val next = words.getOrNull(i + 1)?.let { UNITS[it] ?: it.takeIf { n -> n.length == 1 && n[0].isDigit() } }
            when {
                (w == "double" || w == "triple") && next != null -> { toks += next.repeat(if (w == "double") 2 else 3); i += 2; continue }
                w == "one" && toks.lastOrNull() in POSITION_WORDS -> toks += w
                UNITS.containsKey(w) -> toks += UNITS.getValue(w)
                else -> toks += w
            }
            i++
        }
        // "oh" next to digits is a zero.
        for (k in toks.indices) if (toks[k] in OH) {
            val prevDigit = toks.getOrNull(k - 1)?.all { it.isDigit() } == true
            val nextDigit = toks.getOrNull(k + 1)?.let { it.all { c -> c.isDigit() } || it in OH } == true
            if (prevDigit || nextDigit) toks[k] = "0"
        }
        // Join neighbouring digit tokens — except a count (2–6) right after a position word ("last 4 8011").
        val out = ArrayList<String>()
        for (t in toks) {
            val prev = out.lastOrNull()
            val isDigits = t.all { it.isDigit() }
            val prevIsDigits = prev != null && prev.all { it.isDigit() }
            val prevIsCount = prevIsDigits && out.size >= 2 && out[out.size - 2] in POSITION_WORDS && prev!!.length == 1 && prev[0] in '2'..'6'
            if (isDigits && prevIsDigits && !prevIsCount) out[out.lastIndex] = prev + t else out += t
        }
        return out.joinToString(" ")
    }

    private val POSITION_WORDS = setOf("first", "second", "third", "middle", "centre", "center", "last", "final", "end", "start", "beginning", "front")

    /**
     * The number after the fix in [utterance], or null when it isn't a fix we can apply exactly. [original] may be
     * written any way (+233…, spaces); the result is digits in the same form as the normalised original (0…).
     */
    fun apply(original: String, utterance: String): String? {
        val orig = NumberSpeech.normalize(original)
        if (orig.length < 7) return null
        if (NOT_THE_NUMBER.containsMatchIn(utterance)) return null
        val t = digitize(utterance)
        change(orig, t)?.let { return it }
        position(orig, t)?.let { return it }
        return whole(orig, t)
    }

    /** "change 323 to 332": the old digits must appear exactly once in the number; same length (a fix, not an insert). */
    private fun change(orig: String, t: String): String? {
        for ((re, newFirst) in CHANGE) {
            val m = re.find(t) ?: continue
            val (a, b) = m.groupValues[1] to m.groupValues[2]
            val (old, new) = if (newFirst) b to a else a to b
            if (old.length < 2 || old.length != new.length || old == new) continue
            val at = orig.indexOf(old)
            if (at < 0 || orig.indexOf(old, at + 1) >= 0) continue   // absent or ambiguous
            return orig.substring(0, at) + new + orig.substring(at + old.length)
        }
        return null
    }

    /** "the middle is 456", "the last four is 8011", "the first part is 054", "it ends with 8011". */
    private fun position(orig: String, t: String): String? {
        ENDS_WITH.find(t)?.let { m -> return replaceEnd(orig, m.groupValues[1]) }
        STARTS_WITH.find(t)?.let { m -> return replaceStart(orig, m.groupValues[1]) }
        val m = POSITION.find(t) ?: return null
        val where = m.groupValues[1]
        var count = m.groupValues[2].toIntOrNull()
        var digits = m.groupValues[3]
        // "the last 4 5 6 7": what looked like a count was the group's first digit.
        if (count != null && count != digits.length) { digits = count.toString() + digits; count = null }
        val groups = NumberSpeech.groups(orig)
        if (groups.size < 2) return null
        // Where each group starts in the number.
        val starts = groups.runningFold(0) { acc, g -> acc + g.length }.dropLast(1)
        val idx = when (where) {
            "first", "start", "beginning", "front" -> 0
            "second" -> 1
            "middle", "centre", "center" -> if (groups.size == 3) 1 else return null   // "middle" of 4 groups is unclear
            "third" -> 2
            else -> groups.lastIndex                                                  // last / final / end
        }
        if (idx > groups.lastIndex) return null
        return when {
            digits.length == groups[idx].length -> orig.substring(0, starts[idx]) + digits + orig.substring(starts[idx] + digits.length)
            // "the last two are 11", "the first four is 0554": an END says how many digits it covers.
            idx == 0 && count != null -> replaceStart(orig, digits)
            idx == groups.lastIndex && count != null -> replaceEnd(orig, digits)
            else -> null
        }
    }

    private fun replaceEnd(orig: String, d: String): String? =
        if (d.length in 1 until orig.length) orig.dropLast(d.length) + d else null

    private fun replaceStart(orig: String, d: String): String? =
        if (d.length in 1 until orig.length) d + orig.drop(d.length) else null

    /** The whole number said again: one run the length of a Ghana mobile number (or of the original), in a short reply
     *  ("it's 0 5 5 3 2 3 8 0 1 1", "no, 0553238011") — not a number inside a longer instruction. */
    private fun whole(orig: String, t: String): String? {
        val (runs, otherWords) = runsAcrossCommas(t)
        val full = runs.map { NumberSpeech.normalize(it) }.filter { it.length == orig.length && (orig.length != 10 || it.startsWith("0")) }
        if (full.size != 1) return null
        return if (otherWords <= 6) full[0] else null
    }

    /** Digit runs with the group commas between them closed up ("055 , 323 , 8011" → "0553238011"), and how many other
     *  words the reply has. */
    private fun runsAcrossCommas(t: String): Pair<List<String>, Int> {
        val joined = t.replace(Regex("(?<=\\d) , (?=\\d)"), "")
        val runs = Regex("\\d+").findAll(joined).map { it.value }.toList()
        val other = joined.split(' ').count { w -> w.isNotBlank() && w != "," && !w.all { it.isDigit() } }
        return runs to other
    }

    /**
     * Words that read as a digit fix, judged on the words alone (the voice layer doesn't know the pending number):
     * a position fix, a "change X to Y", or a whole number said again — and nothing that stops ("don't", "cancel").
     * Such a reply is a revision even when it starts with "no" ("no, the last four is 8012"): it is re-proposed and
     * confirmed again, never run. See [com.tekyerema.voice.Confirmation.looksLikeRevision].
     */
    fun looksLikeFix(utterance: String): Boolean {
        if (STOP.containsMatchIn(utterance) || NOT_THE_NUMBER.containsMatchIn(utterance)) return false
        val t = digitize(utterance)
        if (ENDS_WITH.containsMatchIn(t) || STARTS_WITH.containsMatchIn(t)) return true
        POSITION.find(t)?.let { m -> if (m.groupValues[3].length >= 2 || m.groupValues[2].isNotEmpty()) return true }
        if (CHANGE.any { (re, _) -> re.find(t)?.let { it.groupValues[1].length >= 2 } == true }) return true
        val (runs, otherWords) = runsAcrossCommas(t)
        return runs.count { NumberSpeech.normalize(it).length >= 9 } == 1 && otherWords <= 6
    }

    // ---------------------------------------------------------------- the engine's hook

    /** The corrected action's arguments and its new number ([changed] false: the user repeated what was already there). */
    data class Revised(val args: JSONObject, val number: String, val changed: Boolean)

    /** Which argument carries the number each confirmable action reaches (the ones whose read-back is grouped). */
    private val NUMBER_ARG = mapOf("momo_send" to "number", "mobile_money" to "_number", "make_call" to "number", "send_sms" to "number")
    /** Arguments a tool derived from the OLD number in its preflight (the read-back text) — dropped so they're rebuilt. */
    private val DERIVED = mapOf("send_sms" to listOf("_to"))

    /**
     * For a pending [tool] confirmation, the arguments with the number fixed by [utterance], or null when this tool has
     * no number argument or the words aren't a fix that fits. The caller proposes the corrected action AGAIN — through
     * the whole gate, read back in full — never runs it on this reply.
     */
    fun revise(tool: String, args: JSONObject, utterance: String): Revised? {
        val key = NUMBER_ARG[tool] ?: return null
        val old = args.optString(key).takeIf { args.has(key) && !args.isNull(key) && it.isNotBlank() } ?: return null
        val fixed = apply(old, utterance) ?: return null
        val copy = JSONObject(args.toString())
        copy.put(key, fixed)
        DERIVED[tool]?.forEach { copy.remove(it) }
        return Revised(copy, fixed, fixed != NumberSpeech.normalize(old))
    }
}
