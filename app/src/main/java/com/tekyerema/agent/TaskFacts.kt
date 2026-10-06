package com.tekyerema.agent

import java.math.BigDecimal

/**
 * What THIS task knows, and where each fact came from (end-to-end pass, 6 October 2026) — the ground the recipient and the
 * amount of a consequential action must stand on. Built only from the task's own record:
 *
 *  - [said]: the request ([goal]) and every answer the user gave to this task's questions;
 *  - [found]: what this task's own tools returned (a contact look-up, a message read) and the screen in front — a name or
 *    number found there was found, not invented. The arguments a planner PROPOSED are never in it (only what came back);
 *  - [amounts]: the amounts the request or an answer states unambiguously, by the phone's deterministic parsers
 *    (money/MoneyAsk, language/TwiCommands) — never a model's reading of an unclear figure.
 *
 * Nothing from an earlier request is in it: "send 1 cedi to Prince", then "send money to Prince Melanin" — the second
 * task's [amounts] are empty, so its 1 cedi (A20e, 5 October 2026, 23:33) has nothing to stand on. Pure.
 */
class TaskFacts(val goal: String, history: List<String>, screenText: String = "") {
    /** The user's answers in this task, oldest first. */
    val answers: List<String> = com.tekyerema.money.MoneyFlow.answers(history).map { it.second }

    /** The request and every answer, as said. */
    val said: String = (listOf(goal) + answers).joinToString("\n")

    /** What the task's tools returned, and the screen. */
    val found: String = history.filterNot { it.startsWith("ask_user(") }
        .mapNotNull { line -> line.substringAfter(" → ", "").takeIf { it.isNotBlank() } }
        .joinToString("\n") + "\n" + screenText

    /** Amounts in cedis stated in this task — the request's own parse, each answer's, and every clean "figure + unit". */
    val amounts: Set<BigDecimal> by lazy {
        val out = LinkedHashSet<BigDecimal>()
        fun add(v: String?) { v?.toBigDecimalOrNull()?.let { if (it.signum() > 0) out.add(it.stripTrailingZeros()) } }
        val gloss = try { com.tekyerema.language.TwiCommands.gloss(goal) } catch (_: Throwable) { null }
        for (g in listOfNotNull(goal, gloss)) {
            add(try { com.tekyerema.money.MoneyAsk.parse(g)?.amount } catch (_: Throwable) { null })
            // the same number and currency reading the request itself got ("GH 20" → GHS 20, "ten" → 10)
            val numbers = try { com.tekyerema.voice.understand.TelcoTerms.repair(
                com.tekyerema.voice.Normalizer.normalize(g, com.tekyerema.voice.Lang.GH_EN), frame = true) } catch (_: Throwable) { g }
            if (numbers != g) add(try { com.tekyerema.money.MoneyAsk.parse(numbers)?.amount } catch (_: Throwable) { null })
            CLEAN_AMOUNT.findAll(numbers).forEach { m -> add(try { com.tekyerema.money.MoneyAsk.amount(m.value) } catch (_: Throwable) { null }) }
        }
        for (a in answers) add(try { com.tekyerema.money.MoneyAsk.amount(a) } catch (_: Throwable) { null })
        out
    }

    /** [amount] (cedis, as a tool argument) was stated in this task. */
    fun amountStated(amount: String?): Boolean {
        val v = amount?.replace(",", "")?.trim()?.toBigDecimalOrNull() ?: return false
        return amounts.any { it.compareTo(v) == 0 }
    }

    /** [number]'s last nine digits were said in this task, or found by it (a look-up, the screen). */
    fun numberGrounded(number: String?): Boolean {
        val d = number?.filter { it.isDigit() }?.takeLast(9)?.takeIf { it.length == 9 } ?: return false
        fun has(text: String) = text.filter { it.isDigit() }.contains(d) ||
            Regex("\\d[\\d\\s,.-]{8,}\\d").findAll(text).any { m -> m.value.filter { it.isDigit() }.contains(d) }
        return has(said) || has(found)
    }

    /**
     * [name] is someone this task named: said (by sound too — the recogniser's "press malam", "prinsmalane", a first name),
     * a family word the user used, or a name a look-up / the screen in this task returned. Never "a name the app has seen".
     */
    fun personGrounded(name: String?): Boolean {
        val n = name?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (n.count { it.isDigit() } >= 9) return numberGrounded(n)
        if (found.contains(n, ignoreCase = true)) return true
        val words = said.split(Regex("[^\\p{L}\\p{N}'’]+")).filter { it.isNotEmpty() }
        if (said.contains(n, ignoreCase = true)) return true
        for (size in 1..3) for (i in 0..words.size - size) {
            val span = words.subList(i, i + size).joinToString(" ")
            if (com.tekyerema.android.ContactMatcher.score(span, n) >= com.tekyerema.android.ContactMatcher.FLOOR) return true
            if (com.tekyerema.android.ContactMatcher.score(n, span) >= com.tekyerema.android.ContactMatcher.STRONG) return true
        }
        return false
    }

    companion object {
        /** "5 cedis", "GHS 20", "50 pesewas", "2.50 cedis" — a figure with its unit, not an unclear one ("1,5", "2.500"),
         *  and not the second of two bare figures ("2 50 cedis" — 2.50 or 250?). */
        private const val FIG = "(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{1,2})?(?![\\d,.]\\d)"
        private val CLEAN_AMOUNT = Regex("(?i)(?<![\\d.,])(?<!\\d\\s)(?:(?:gh[sc₵¢]|₵)\\s?$FIG|$FIG\\s?(?:ghana\\s+)?(?:cedis?|pesewas?|ghs|ghc)\\b)")
    }
}
