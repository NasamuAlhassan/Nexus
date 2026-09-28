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

    /**
     * True when [toolName]([args]) stays inside what the user already approved. Only STEPS INSIDE the approved USSD
     * dialog are covered: typing a menu choice / the approved number / amount / a reference, and pressing its buttons
     * (Send, OK, Reply). Anything that starts a NEW dial or send — run_ussd with another code, make_call, send_sms,
     * momo_send, a tap on a dialer keypad digit or a Call button (which can dial "*170*1*1*<someone else>*500#") — is
     * confirmed again: it used to be waved through because every non-`type` tool counted as covered.
     * @param targetLabel the resolved target's label (the engine passes it; falls back to the `label` argument).
     */
    fun covers(toolName: String, args: JSONObject, targetLabel: String? = null): Boolean {
        if (toolName != "type") {
            if (toolName !in DIALOG_STEPS) return false
            val label = (targetLabel ?: args.optString("label")).trim()
            // An unnamed control, a keypad key or a phone number, or a Call / Dial button is not a dialog step.
            if (label.isEmpty() || KEYPAD.matches(label) || DIALS.containsMatchIn(label)) return false
            return true                                           // OK / Send / Reply / menu rows of the approved flow
        }
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

    private companion object {
        /** Tools that press a control inside the dialog on screen (not tools that dial or send by themselves). */
        val DIALOG_STEPS = setOf("tap", "long_press", "node_action")
        /** A keypad key ("5", "*", "#") or a number typed on a keypad / shown as a phone number row. */
        val KEYPAD = Regex("^[0-9*#+][0-9*#+\\s().\\-]*$")
        val DIALS = Regex("(?i)\\b(call|dial|video call|voice call)\\b")
    }
}
