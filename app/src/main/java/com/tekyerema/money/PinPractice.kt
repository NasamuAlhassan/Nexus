package com.tekyerema.money

import kotlin.random.Random

/**
 * Settings › Practise typing a PIN — a made-up 4-digit number to type on a keypad laid out like the phone's own, so a
 * blind user gets quick with TalkBack's explore-by-touch before a real Mobile Money PIN screen, which closes if you are
 * slow (research recommendation #10, docs/agent/research/secure_payment_auth.md: "time limits force asking others").
 *
 * Practice numbers only. Nothing typed is stored, logged, spoken to the network or sent anywhere, and the number is
 * never a real-PIN shape (no repeated-digit or 1234-style runs). Pure → unit-tested.
 */
object PinPractice {
    const val LENGTH = 4
    /** A good-enough time for one PIN on a USSD screen — coaching, not a rule. */
    const val QUICK_MS = 10_000L

    /** A practice number: at least three different digits, never an ascending/descending run. */
    fun target(random: Random = Random.Default): String {
        while (true) {
            val d = List(LENGTH) { random.nextInt(10) }
            val run = d.zipWithNext().all { (a, b) -> b == a + 1 } || d.zipWithNext().all { (a, b) -> b == a - 1 }
            if (d.toSet().size >= 3 && !run) return d.joinToString("")
        }
    }

    fun spoken(digits: String): String = digits.toList().joinToString(" ")

    fun prompt(target: String): String = "Type ${spoken(target)}."

    fun progress(typed: String): String = "${typed.length} of $LENGTH typed."

    data class Result(val correct: Boolean, val line: String)

    /** The spoken verdict once [LENGTH] digits are in; [ms] = first key to last. */
    fun check(target: String, typed: String, ms: Long): Result = if (typed == target) {
        val s = "%.1f".format(java.util.Locale.US, ms / 1000.0)
        Result(true, if (ms <= QUICK_MS) "Correct, in $s seconds. That's quick enough for a Mobile Money PIN screen."
            else "Correct, in $s seconds. Try another and aim for under ${QUICK_MS / 1000} seconds — the PIN screen closes if you wait too long.")
    } else Result(false, "Not quite — you typed ${spoken(typed)}. The number was ${spoken(target)}. Try again.")
}
