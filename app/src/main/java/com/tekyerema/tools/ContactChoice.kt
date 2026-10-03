package com.tekyerema.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import com.tekyerema.android.ContactResolver
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Call Prince" when fourteen contacts match (A20e audit 2026-10-01, C15 / C07 / F07): the old preflight listed every
 * name in one sentence, the question the user heard was cut mid-word and asked twice, the likely people were not among
 * the three offered, and five entries were all "Prince Alhassan" with nothing to tell them apart.
 *
 * One short question a listener can answer:
 *  - the same person saved twice (same name, same number) is one option;
 *  - at most three options, the people contacted most recently first (call log), otherwise the address-book order;
 *  - two entries with the same name are told apart by the last three digits ("Prince Alhassan ending 323");
 *  - when more fit than are read out, the question ends "…or someone else?".
 * The answer "Prince Alhassan ending 323" (or "… on 055 991 6720", the older wording) resolves back to that one entry
 * ([resolve]). The question and its options ride in the result's data so the engine can ask it directly — no model call.
 * [build] and [narrow] are pure → ContactChoiceTest.
 */
object ContactChoice {
    data class Option(val label: String, val name: String, val number: String)

    /** [twiQuestion]: the same question for a Twi conversation, made here — no translator. The translator never took it
     *  (a line of names doesn't "look English"), so in Twi mode the A20e showed it in English and read it with the Twi
     *  voice (3 October 2026). The Twi frame is said by the Twi voice; the names and digits by the English voice. */
    data class Choice(val question: String, val message: String, val options: List<Option>, val more: Int, val twiQuestion: String = "")

    const val MAX_OPTIONS = 3

    private fun tail(number: String, n: Int = 9) = number.filter { it.isDigit() }.takeLast(n)

    /** The same name on the same number (saved twice, or once with +233 and once with 0) is one person. */
    fun distinct(matches: List<ContactResolver.Contact>): List<ContactResolver.Contact> =
        matches.distinctBy { it.name.trim().lowercase() to tail(it.number) }

    /**
     * @param said what the user called the person ("Prince"); @param matches every contact that fits;
     * @param lastContact when each number was last called / calling (0 = never, or unknown).
     */
    fun build(said: String, matches: List<ContactResolver.Contact>, lastContact: (String) -> Long = { 0L }): Choice {
        val all = distinct(matches)
        // Stable: people never contacted keep the address-book order after those who were.
        val ranked = all.withIndex().sortedWith(compareByDescending<IndexedValue<ContactResolver.Contact>> { lastContact(it.value.number) }
            .thenBy { it.index }).map { it.value }
        val shown = ranked.take(MAX_OPTIONS)
        val options = shown.map { c ->
            val twins = all.count { it.name.trim().equals(c.name.trim(), ignoreCase = true) } > 1
            Option(if (twins) "${c.name.trim()} ending ${tail(c.number, 3)}" else c.name.trim(), c.name.trim(), c.number)
        }
        val more = all.size - shown.size
        val labels = options.map { it.label }
        val list = when (labels.size) {
            0 -> ""
            1 -> labels[0]
            else -> labels.dropLast(1).joinToString(", ") + ", or " + labels.last()
        }
        val who = said.trim().ifEmpty { "one" }
        // "Which my brother: …?" is not English — a "my …" is asked as "Which one is your brother: …?" (QA R4_TR13, 3 October 2026).
        val question = (if (who.lowercase().startsWith("my ")) "Which one is your ${who.substring(3).trim()}: $list?" else "Which $who: $list?") +
            if (more > 0) " Or someone else?" else ""
        // The planner-facing line keeps the shape References.optionsFrom reads ("…: A, B, C. Which one?").
        val message = "More than one contact matches $who: ${labels.joinToString(", ")}" +
            (if (more > 0) " (and $more more)" else "") + ". Which one?"
        // "Hena na wopɛ?" (who do you want?) — a FIXED frame, recorded once in the lab's voice (voice/LabClips); the names
        // are the English voice's. [owner: verify the Twi]
        val frame = "Hena na wopɛ?"
        val twi = if (labels.isEmpty()) "" else frame + " " + com.tekyerema.language.EntityTags.EN_OPEN + list + "." +
            com.tekyerema.language.EntityTags.EN_CLOSE + (if (more > 0) " Anaa obi foforɔ?" else "")
        return Choice(question, message, options, more, twi)
    }

    private val ENDING = Regex("(?i)^(.+?)\\s+(?:ending(?:\\s+in)?|on)\\s+([+\\d][\\d\\s]{1,16})$")

    /** "Prince Alhassan ending 323" → ("Prince Alhassan", "323"); a plain name → (name, null). Pure. */
    fun narrow(said: String): Pair<String, String?> {
        val m = ENDING.matchEntire(said.trim()) ?: return said.trim() to null
        val digits = m.groupValues[2].filter { it.isDigit() }
        return if (digits.length >= 2) m.groupValues[1].trim() to digits else said.trim() to null
    }

    /** Of [matches], those whose number ends with [digits] (a +233 / 0 prefix doesn't matter). Pure. */
    fun withEnding(matches: List<ContactResolver.Contact>, digits: String): List<ContactResolver.Contact> {
        val d = digits.filter { it.isDigit() }.takeLast(9)
        return matches.filter { it.number.filter(Char::isDigit).endsWith(d) }
    }

    /** The contacts [said] means: an option label from [build] picks its one entry; duplicates of one person collapse. */
    fun resolve(context: Context, said: String): List<ContactResolver.Contact> {
        val (name, digits) = narrow(said)
        if (digits != null) {
            val hit = withEnding(ContactResolver.resolve(context, name), digits)
            if (hit.isNotEmpty()) return distinct(hit).distinctBy { tail(it.number) }
        }
        return distinct(ContactResolver.resolve(context, said))
    }

    /** The preflight result for several matches: short message for the planner, the question + options as data. */
    fun result(context: Context, said: String, matches: List<ContactResolver.Contact>): ToolResult {
        val c = build(said, matches, recency(context, matches))
        val choices = JSONArray()
        c.options.forEach { choices.put(JSONObject().put("label", it.label).put("name", it.name).put("number", it.number)) }
        return ToolResult(false, c.message, data = JSONObject().put("ask", c.question).put("askTwi", c.twiQuestion)
            .put("options", JSONArray(c.options.map { it.label })).put("choices", choices).put("more", c.more),
            needsDisambiguation = true)
    }

    /** The option labels in a [result]'s data (empty when it carries none). */
    fun optionsOf(data: JSONObject?): List<String> {
        val a = data?.optJSONArray("options") ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    }

    /** Put the picked option into a call / text tool's arguments, so the same tool call can simply run again. */
    fun apply(args: JSONObject, data: JSONObject?, pickedLabel: String): Boolean {
        val a = data?.optJSONArray("choices") ?: return false
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("label").equals(pickedLabel.trim(), ignoreCase = true)) {
                args.put("contact", o.optString("name")).put("number", o.optString("number"))
                return true
            }
        }
        return false
    }

    /** When each matched number last appeared in the call log (any direction). Unreadable → everyone 0 (order kept). */
    private fun recency(context: Context, matches: List<ContactResolver.Contact>): (String) -> Long {
        val last = HashMap<String, Long>()
        try {
            if (context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
                val wanted = matches.mapTo(HashSet()) { tail(it.number) }
                context.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                    null, null, "${CallLog.Calls.DATE} DESC")?.use { c ->
                    var rows = 0
                    while (rows++ < 400 && c.moveToNext() && last.size < wanted.size) {
                        val t = tail(c.getString(0).orEmpty())
                        if (t in wanted && t !in last) last[t] = c.getLong(1)
                    }
                }
            }
        } catch (_: Throwable) {}
        return { number -> last[tail(number)] ?: 0L }
    }
}

/**
 * Who a "reply to the last message" would go to. A text from a name ("MobileMoney", "MTN") or a short code cannot be
 * answered by SMS — replying costs airtime and reaches nobody — so that is said instead of proposed (audit C17). Pure.
 */
object ReplyTarget {
    /** True for a sender ID with letters, or a short code (fewer than 7 digits). */
    fun isServiceSender(address: String): Boolean {
        val a = address.trim()
        return a.isEmpty() || a.any { it.isLetter() } || a.count { it.isDigit() } < 7
    }

    private val LAST_SENDER = Regex("(?i)^(?:the\\s+)?(?:(?:last|latest|newest|previous|most recent)\\s+(?:sender|message|text|sms)|sender(?:\\s+of\\s+the\\s+last\\s+(?:message|text|sms))?)$")

    /** "last sender", "the last message", "the sender" — a contact argument that means whoever texted last. */
    fun meansLastSender(contact: String?): Boolean = contact?.trim()?.let { LAST_SENDER.matches(it) } == true

    fun cannotReplyLine(address: String): String {
        val who = CallLogFormat.spokenNumber(address)
        return "Your last text is from $who — a service sender that can't receive replies, so I haven't sent anything. " +
            "Tell the user that; don't open Messages to try."
    }
}
