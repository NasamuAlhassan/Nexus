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

    /** For "how much money": one JSON entry per physical note or coin, with the box around its edges; the phone decides
     *  the count ([MoneyTally]) and does the sum ([spokenMoney]) — never the model's count or arithmetic. */
    // History: the 22 Oct prompt asked for {value, count} and an example with count 2 made one note two; with count 1 and
    // "count each paper once" a single 10 was still counted as two on 24 Oct. A count the phone cannot check is gone.
    const val MONEY = """
Count the Ghana cedi money in this photo for a blind person. JSON only, no other text:
{"notes":[{"kind":"note","value_ghs":20,"box":[310,120,690,880],"sure":true}],"unreadable":0,"other_currency":"","confidence":"high","fix":""}
"notes" has ONE entry per PHYSICAL piece of paper or coin: three 10 cedi notes are three entries, one note is one entry.
box is that piece's WHOLE outline — its edges, not a numeral — as [ymin, xmin, ymax, xmax] on a 0 to 1000 scale.
A note prints its value SEVERAL times (in the corners, the middle and on the back): that is still one note, one entry, one
box around the whole paper. Notes: GH₵1, 2, 5, 10, 20, 50, 100, 200. Coins: 1, 5, 10, 20, 50 pesewas (0.01 to 0.5) and
GH₵1, GH₵2. value_ghs comes from the printed numeral. sure is false when you cannot clearly read that piece's value.
A note or coin whose numeral you cannot see goes in "unreadable", not in notes. confidence is "low" if the photo is blurred
or dark or the money overlaps. fix is one short instruction ("Spread the notes out", "Turn the note over"), else "".
No money: notes [] and fix "Hold the money about a hand away." Other currency: name it in other_currency.
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

    /** One line for a blind listener: what the phone counted ([MoneyTally]), the sum done here, doubt said plainly. Null
     *  when [json] is unusable. */
    fun spokenMoney(json: String): String? {
        val o = MoneyTally.obj(json) ?: return null
        val tally = MoneyTally.of(json) ?: return null
        val unreadable = o.optInt("unreadable", 0).coerceIn(0, 200)
        val low = o.optString("confidence").equals("low", ignoreCase = true)
        val advice = o.optString("fix").trim().ifEmpty { o.optString("note").trim() }
        val other = o.optString("other_currency").trim()
        if (tally.isEmpty) {
            if (other.isNotEmpty()) return "That doesn't look like cedis. I think it's $other."
            return "I don't see any money. " + (advice.ifEmpty { "Hold the notes flat in front of the camera, about a hand away." })
        }
        // Notes lying on each other can't be counted honestly: no total, one instruction (owner's demo, 24 Oct 2026).
        if (tally.unstable) return "Some of the notes overlap, so I can't count them for sure. Spread the notes out flat so I " +
            "can see each one, then ask me again."
        val parts = tally.counts.entries.sortedByDescending { it.key.first }.map { (k, n) ->
            val (value, note) = k
            val what = if (value < 1) "${(value * 100).toInt()} pesewa ${if (note) "note" else "coin"}"
                else "${fmt(value)} cedi ${if (note) "note" else "coin"}"
            "${COUNT.getOrElse(n) { n.toString() }} ${if (n == 1) what else "${what}s"}" + if (k in tally.doubtful) " (not sure)" else ""
        }
        val doubt = tally.doubtful.isNotEmpty() || low
        // A total only when every note was read (research: never a confident sum over a note it couldn't see).
        val sentence = "I see " + join(parts) + ". " + when {
            unreadable > 0 -> "There ${if (unreadable == 1) "is one more I can't read" else "are $unreadable more I can't read"}, so I can't give the total yet."
            doubt -> "That's about ${spokenTotal(tally.total)} in all."
            else -> "That's ${spokenTotal(tally.total)} in all."
        }
        return if ((doubt || unreadable > 0) && advice.isNotEmpty()) "$sentence $advice" else sentence
    }

    /** The phone's total of a money answer, or null when it isn't one or its notes overlap (no honest total). Pure. */
    fun moneyTotal(json: String): Double? = MoneyTally.of(json)?.takeIf { !it.unstable }?.total

    /** Two models' money answers for the same photo: null when they count the same notes (or either isn't a count), else the
     *  honest line. Compared note by note, not by total: two 10s and one 20 are not four 5s. */
    fun moneyDisagreement(a: String, b: String, line: String = "I can't be sure of the total — I counted it two ways. Hold the " +
        "money flat, one note at a time, and ask me again."): String? {
        val ta = MoneyTally.of(a) ?: return null
        val tb = MoneyTally.of(b) ?: return null
        if (ta.unstable || tb.unstable) return null          // spokenMoney already says to spread the notes out
        return if (ta.counts != tb.counts) line else null
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
