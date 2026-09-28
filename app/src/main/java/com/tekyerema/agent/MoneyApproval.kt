package com.tekyerema.agent

import org.json.JSONObject

/**
 * J-02 — once the user approves a Mobile-Money operation (recipient + amount), the USSD menu steps that follow run
 * without re-asking (an interactive USSD session times out). That approval covers ONLY what was read back: a later
 * step that types a DIFFERENT number or amount into the dialog must be confirmed again. Menu choices ("1", "2"),
 * the approved number/amount, and non-numeric text (a reference) stay within the approval. Pure → unit-tested.
 */
class MoneyApproval {
    private val numbers = HashSet<String>()
    private val amounts = HashSet<Double>()

    fun approve(args: JSONObject) {
        for (k in listOf("number", "_number", "contact", "text")) {
            val digits = args.optString(k).filter { it.isDigit() }
            if (digits.length >= 7) numbers.add(tail(digits))
        }
        for (k in listOf("amount", "_amount", "text")) args.optString(k).replace(",", "").trim().toDoubleOrNull()?.let { amounts.add(it) }
    }

    /** True when [toolName]([args]) stays inside what the user already approved. */
    fun covers(toolName: String, args: JSONObject): Boolean {
        if (toolName != "type") return true                      // taps on OK / Send / menu rows of the approved flow
        val text = args.optString("text").trim()
        val digits = text.filter { it.isDigit() }
        if (digits.isEmpty()) return true                         // a reference / narration
        if (text.length <= 2 && digits.length == text.length) return true   // a menu choice
        if (digits.length >= 7 && tail(digits) in numbers) return true
        val amount = text.replace(",", "").replace(Regex("(?i)(ghs|gh₵|₵|cedis?)"), "").trim().toDoubleOrNull()
        return amount != null && amounts.any { Math.abs(it - amount) < 0.0001 }
    }

    /** Ghana numbers appear as 024…, +233 24…, 233 24… — compare the last 9 digits. */
    private fun tail(d: String) = d.takeLast(9)
}
