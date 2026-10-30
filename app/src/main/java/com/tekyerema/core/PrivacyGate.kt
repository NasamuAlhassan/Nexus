package com.tekyerema.core

import com.tekyerema.core.config.AppConfig

/**
 * The privacy gate in front of the cloud brain (owner, 29 Oct 2026: "privacy-gated"). Before a planning request leaves the phone, the
 * details the cloud doesn't need to KNOW to plan are swapped for tags — a phone number becomes [NUM1], an email [EMAIL1], a long account
 * or card number [ID1] — and the same detail is the same tag everywhere in the request, so the cloud can still tell "the number in the
 * request" is "the number on the screen". Its answer comes back with the tags, and the phone puts the real values back into the action
 * it then carries out itself: the cloud plans with "[NUM1]", the phone dials the number. A PIN, OTP or password never gets this far
 * ([Privacy.maskCredentials] hides it for good, before routing).
 *
 * Rules in code, not a model: what they catch they always catch, and nothing is guessed. Names stay — the planner must find "My
 * Husband" on the screen to tap it. Off: `privacy.gate.enabled` false. Pure → unit-tested.
 */
object PrivacyGate {
    /** A request with its details tagged, and how to put them back. */
    class Sealed(val text: String, private val values: Map<String, String>) {
        val count: Int get() = values.size
        /** [reply] with every tag this request made turned back into its value. */
        fun open(reply: String): String =
            if (values.isEmpty()) reply else TAG.replace(reply) { m -> values[m.value] ?: m.value }
        fun kinds(): String = values.keys.groupingBy { it.trim('[', ']').trimEnd { c -> c.isDigit() } }.eachCount()
            .entries.joinToString(" ") { (k, n) -> "${k.lowercase()}=$n" }
    }

    /** A Ghana number: 0 and nine digits, or +233 / 233 and nine, with single spaces or dashes between digits. */
    private val PHONE = Regex("(?<![\\d+])(?:\\+?233[\\s-]?|0)(?:\\d[\\s-]?){8}\\d(?![\\d])")
    private val EMAIL = Regex("(?i)(?<![\\w.+-])[\\w.+-]{1,64}@[\\w-]{1,63}(?:\\.[\\w-]{1,63})+")
    /** An account, card or wallet number: 11 to 19 digits in a row (a phone number is caught first; ids and timings are shorter). */
    private val LONG_ID = Regex("(?<![\\d])\\d{11,19}(?![\\d])")
    private val TAG = Regex("\\[(?:NUM|EMAIL|ID)\\d+]")

    fun enabled(): Boolean = try { AppConfig.bool("privacy.gate.enabled", true) } catch (_: Throwable) { true }

    /** [text] with its details tagged. The same value (spacing aside) always gets the same tag. */
    fun seal(text: String): Sealed {
        val values = LinkedHashMap<String, String>()     // tag → value
        val tags = HashMap<String, String>()             // normalised value → tag
        var counter = 0
        fun tag(kind: String, raw: String, key: String): String = tags.getOrPut(key) {
            "[$kind${++counter}]".also { values[it] = raw }
        }
        var t = EMAIL.replace(text) { m -> tag("EMAIL", m.value, m.value.lowercase()) }
        t = PHONE.replace(t) { m ->
            val digits = m.value.filter { it.isDigit() }
            tag("NUM", m.value, if (digits.startsWith("233")) "0" + digits.drop(3) else digits)
        }
        t = LONG_ID.replace(t) { m -> tag("ID", m.value, m.value) }
        return Sealed(t, values)
    }
}
