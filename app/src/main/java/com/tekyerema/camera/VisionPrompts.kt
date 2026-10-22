package com.tekyerema.camera

import org.json.JSONObject

/**
 * The camera answering the user's OWN question about what is in front of them (owner, 20 Oct 2026: "point at something and it
 * tells me the details… how much am I holding… the app should be smart and understand the request"). The Be My AI pattern:
 * one photo, the user's words, a short spoken answer, then follow-up questions on the same photo (VisionQa).
 *
 * Money is special: the model returns the notes it sees as JSON and the phone does the sum and the wording, so a total is
 * never the model's arithmetic and a doubtful note is said as doubtful. Pure (unit-tested).
 */
object VisionPrompts {
    /** For any question (research, 20 Oct 2026: Be My AI / Seeing AI / Lookout studies — answer the goal first, facts only,
     *  one fix when the photo is bad, never invent text, safety rules for medicine, people and roads). */
    fun forQuestion(question: String): String = """
You are the eyes of a blind person in Ghana. They pointed their phone's back camera and asked: "${question.trim().take(300)}"
This is a PHOTO from their camera (never call it "the screen"). Answer THAT question first, in one or two short spoken sentences (at most four, and only if they ask to describe
everything). Plain words, no markdown, never "the image shows". Then, only if useful, the main thing and any important
text read exactly as written. Directions are from the user's side (left, right, up, down); distances in hand-widths or steps.
If what they asked about is cut off, too close, dark or blurred, say what is wrong and give ONE fix ("Move the phone a
little to the right", "Hold it further away", "Turn the box over") instead of guessing.
Read only characters you can actually see; never complete words, numbers or dates; say "I can read … but not …".
Say it plainly when sure, "it looks like" when fairly sure, "I can't tell" otherwise; no percentages.
Money: Ghana cedi notes GH₵1, 2, 5, 10, 20, 50, 100, 200; coins 1, 5, 10, 20, 50 pesewas, GH₵1, GH₵2; name each by its
printed numeral; never say whether money is genuine; name other currencies (dollar, CFA, naira).
When money is what they are holding or asking about, START with the amount ("A 50 cedi note." / "Two 20 cedi notes, 40 cedis.")
and stop there unless asked for more; colours and pictures only if they ask to describe it. Never read serial numbers.
Medicine: read the name, strength and instructions exactly; never suggest a dose; if unclear, say to check with a pharmacist.
People: how many, where, what they are doing; never identify anyone or guess age or ethnicity.
Never say a road is safe to cross. Keep Ghanaian names and Twi letters (ɛ, ɔ) exactly as printed.
""".trim()

    /** For "how much money": the notes as JSON only; the phone adds them up ([spokenMoney]) — never the model's sum. */
    const val MONEY = """
Count the Ghana cedi money in this photo for a blind person. JSON only, no other text:
{"items":[{"kind":"note","value_ghs":20,"count":2}],"unreadable":0,"other_currency":"","confidence":"high","fix":""}
Notes: GH₵1, 2, 5, 10, 20, 50, 100, 200. Coins: 1, 5, 10, 20, 50 pesewas (0.01 to 0.5) and GH₵1, GH₵2. value_ghs comes from
the printed numeral. Count every physical note and coin, overlapping ones included. A note or coin whose numeral you cannot
see goes in "unreadable", not in items. confidence is "low" if the photo is blurred or dark or the money overlaps.
fix is one short instruction ("Spread the notes out", "Turn the note over"), else "". No money: items [] and fix
"Hold the money about a hand away." Other currency: name it in other_currency.
"""

    private val MONEY_Q = Regex("(?i)\\b(how much (?:money|cash|cedis?)|how much (?:am i|i'm|i am) holding|how many cedis?|" +
        "count (?:the |my |this )?(?:money|notes?|cash|cedis?)|what (?:note|notes|money|cash|coin|coins)|which (?:note|coin)|" +
        "cash|cedis?|money|sika|pesewas?)\\b|^how much is (?:this|that|it)$")

    private val SMALL_PRINT = Regex("(?i)\\b(read|says?|written|label|text|words|expir\\w*|date|best before|use by|sell by|ingredients|" +
        "dose|dosage|instructions?|price|receipt|number|name)\\b")

    /** The photo's long side for this question: small print needs pixels; a scene or money reads as well at 768 and uploads
     *  in a third of the time (research: 768 px is Gemini's tile; text accuracy grows with resolution). */
    fun maxSide(question: String?, money: Boolean): Int = when {
        money -> 768
        question != null && SMALL_PRINT.containsMatchIn(question) -> 1280
        else -> 768
    }

    /** The question is about money in hand (counted by [MONEY]). */
    fun isMoneyQuestion(question: String?): Boolean = question != null && MONEY_Q.containsMatchIn(question)

    /** One line for a blind listener: what the model saw, the sum done here, doubt said plainly. Null when [json] is unusable. */
    fun spokenMoney(json: String): String? {
        val o = try { JSONObject(json.substring(json.indexOf('{').coerceAtLeast(0), json.lastIndexOf('}') + 1)) } catch (_: Throwable) { return null }
        val items = o.optJSONArray("items") ?: return null
        val unreadable = o.optInt("unreadable", 0).coerceIn(0, 200)
        val low = o.optString("confidence").equals("low", ignoreCase = true)
        val fix = o.optString("fix").trim().ifEmpty { o.optString("note").trim() }
        data class Item(val value: Double, val count: Int, val note: Boolean, val sure: Boolean)
        val list = (0 until items.length()).mapNotNull { i ->
            val it = items.optJSONObject(i) ?: return@mapNotNull null
            val v = it.optDouble("value_ghs", Double.NaN)
            val n = it.optInt("count", 1).coerceIn(1, 200)
            if (v.isNaN() || v <= 0) null else Item(v, n, it.optString("kind", "note") != "coin", it.optBoolean("sure", true))
        }
        val other = o.optString("other_currency").trim()
        val advice = fix
        if (list.isEmpty()) {
            if (other.isNotEmpty()) return "That doesn't look like cedis. I think it's $other."
            return "I don't see any money. " + (advice.ifEmpty { "Hold the notes flat in front of the camera, about a hand away." })
        }
        val parts = list.sortedByDescending { it.value }.map { item ->
            val what = if (item.value < 1) "${(item.value * 100).toInt()} pesewa ${if (item.note) "note" else "coin"}"
                else "${fmt(item.value)} cedi ${if (item.note) "note" else "coin"}"
            "${COUNT.getOrElse(item.count) { item.count.toString() }} ${if (item.count == 1) what else "${what}s"}" + if (!item.sure) " (not sure)" else ""
        }
        val total = list.sumOf { it.value * it.count }
        val doubt = list.any { !it.sure } || low
        // A total only when every note was read (research: never a confident sum over a note it couldn't see).
        val sentence = "I see " + join(parts) + ". " + when {
            unreadable > 0 -> "There ${if (unreadable == 1) "is one more I can't read" else "are $unreadable more I can't read"}, so I can't give the total yet."
            doubt -> "That's about ${spokenTotal(total)} in all."
            else -> "That's ${spokenTotal(total)} in all."
        }
        return if ((doubt || unreadable > 0) && advice.isNotEmpty()) "$sentence $advice" else sentence
    }

    /** A plain answer cleaned for speech (no markdown, no stray quotes). */
    fun spoken(text: String): String = text.replace(Regex("[*_`#>]+"), "").replace(Regex("\\s+"), " ").trim().trim('"')

    /** "two 20 cedi notes", not "2 20 cedi notes". */
    private val COUNT = listOf("no", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")

    private fun fmt(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else "%.2f".format(java.util.Locale.US, v)

    private fun spokenTotal(total: Double): String {
        val cedis = Math.floor(total + 1e-9).toLong()
        val pesewas = Math.round((total - cedis) * 100)
        return when {
            cedis == 0L -> "$pesewas pesewas"
            pesewas == 0L -> "$cedis ${if (cedis == 1L) "cedi" else "cedis"}"
            else -> "$cedis ${if (cedis == 1L) "cedi" else "cedis"} $pesewas pesewas"
        }
    }

    private fun join(parts: List<String>): String = when (parts.size) {
        1 -> parts[0]
        2 -> "${parts[0]} and ${parts[1]}"
        else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    }
}
