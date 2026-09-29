package com.tekyerema.phonecare

import com.tekyerema.core.config.AppConfig

/**
 * "Missed call from your mother" — the dialer's missed-call notification, read on the phone (no model, no new
 * permission: notification access, plus the call log only when READ_CALL_LOG is already granted).
 *
 * Only a SAVED contact is announced: a bare number, "Unknown", "Private number" or a spam label stays silent (a blind
 * user can't screen strangers, and a missed stranger is not worth interrupting for). When the user taught a relation
 * ("remember that Ama Mensah is my mother", [com.tekyerema.android.Relations]) the relation is said instead of the name.
 * Parsing is pure and unit-tested; dialers phrase it "Missed call" + name, name + "Missed call", "2 missed calls", or
 * "Missed call from Kofi".
 */
object MissedCalls {
    /** [callers] as the dialer shows them (names or numbers); [count] when the dialer says how many (1 otherwise). */
    data class Missed(val callers: List<String>, val count: Int)

    private val MISSED = Regex("(?i)\\bmissed\\s+(?:voice\\s+|video\\s+|whatsapp\\s+)?calls?\\b")
    private val FROM = Regex("(?i)\\bmissed\\s+(?:voice\\s+|video\\s+)?calls?\\s+from\\s+(.{1,80})$")
    private val COUNT = Regex("(?i)\\b(\\d{1,3})\\s+missed\\s+(?:voice\\s+|video\\s+)?calls\\b")
    private val UNKNOWN = Regex("(?i)^(?:unknown|unknown (?:caller|number)|private(?: number)?|restricted(?: number)?|no caller id|withheld|" +
        "anonymous|blocked|voicemail|missed calls?)$")
    /** Caller-ID labels: "Suspected spam caller", "Spam risk", "Scam likely". */
    private val SPAM = Regex("(?i)\\b(?:spam|scam|fraud|telemarketer)\\b")
    /** "Kofi at 10:32", "Ama on Tuesday", "Esi yesterday" → the name only. */
    private val TRAILING_WHEN = Regex("(?i)\\s+(?:at|on)\\s+\\d.*$|\\s+(?:at|on)\\s+(?:mon|tue|wed|thu|fri|sat|sun)\\w*.*$|\\s+(?:today|yesterday|just now)\\b.*$|\\s+·.*$")
    private val PHONE_LIKE = Regex("^[+\\d\\s().-]{5,}$")

    val DEFAULT_PACKAGES = listOf("com.samsung.android.dialer", "com.google.android.dialer", "com.android.dialer",
        "com.android.server.telecom", "com.samsung.android.incallui", "com.android.phone")

    fun packages(): List<String> = AppConfig.stringList("alerts.missedCall.packages", DEFAULT_PACKAGES)

    /** The missed call a dialer notification describes, or null when it isn't one. Pure. */
    fun parse(title: String?, text: String?): Missed? {
        val t = title?.trim().orEmpty()
        val x = text?.trim().orEmpty()
        val inTitle = MISSED.containsMatchIn(t)
        val inText = MISSED.containsMatchIn(x)
        if (!inTitle && !inText) return null
        val count = (COUNT.find(t) ?: COUNT.find(x))?.groupValues?.get(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val named = FROM.find(t)?.groupValues?.get(1) ?: FROM.find(x)?.groupValues?.get(1)
        val raw = named ?: when {
            inTitle && !inText -> x
            inText && !inTitle -> t
            else -> ""
        }
        return Missed(split(raw), count)
    }

    /** "Kofi Mensah, Ama (2)" → ["Kofi Mensah", "Ama"]. */
    private fun split(raw: String): List<String> = raw.split(Regex("\\s*(?:,|;|\\band\\b|\\n)\\s*"))
        .map { it.replace(TRAILING_WHEN, "").replace(Regex("\\s*\\(\\d+\\)$"), "").trim().trimEnd('.', '!', ' ') }
        .filter { it.isNotEmpty() }

    /** A saved contact's name, not a number / "Unknown" / a spam label / our placeholder for a hidden code. Pure. */
    fun isSavedName(name: String?): Boolean {
        val n = name?.trim().orEmpty()
        if (n.isEmpty() || n.none { it.isLetter() }) return false
        if (PHONE_LIKE.matches(n) || UNKNOWN.matches(n) || SPAM.containsMatchIn(n) || n.contains(com.tekyerema.core.Redact.HIDDEN)) return false
        return n.length <= 60
    }

    /** The relation the user taught for [name] ("mother" for "Ama Mensah"), matched without case or extra spaces. Pure. */
    fun relationFor(name: String, relations: Map<String, String>): String? {
        val n = name.trim().replace(Regex("\\s+"), " ")
        return relations.entries.firstOrNull { (_, contact) -> contact.trim().replace(Regex("\\s+"), " ").equals(n, ignoreCase = true) }?.key
    }

    /** "Missed call from your mother." / "Missed calls from Kofi Mensah and Ama." — no counts spoken (owner rule). Pure. */
    fun line(callers: List<String>, relations: Map<String, String>, several: Boolean): String {
        val said = callers.distinctBy { it.lowercase() }.take(3).map { c -> relationFor(c, relations)?.let { "your $it" } ?: c }
        val who = when (said.size) {
            0 -> return AppConfig.phrase("alerts.phrases.missedCallAnyone", "You have a missed call.")
            1 -> said[0]
            else -> said.dropLast(1).joinToString(", ") + " and " + said.last()
        }
        return if (several || said.size > 1) AppConfig.phrase("alerts.phrases.missedCalls", "Missed calls from {who}.", "who" to who)
        else AppConfig.phrase("alerts.phrases.missedCall", "Missed call from {who}.", "who" to who)
    }
}
