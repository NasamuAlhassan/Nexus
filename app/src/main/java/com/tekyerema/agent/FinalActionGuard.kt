package com.tekyerema.agent

import org.json.JSONObject

/**
 * The last check before a cloud planner's call, text or money action is acted on (end-to-end pass, 6 October 2026).
 * The policy layer still asks the user to approve every one of them; this decides whether it may even be PROPOSED:
 *
 *  - its recipient (a contact name or a number) is grounded in THIS task ([TaskFacts]: said, answered, or found by a look-up
 *    or on the screen) — never an example from the planner's instructions, an earlier task's person, or a guess;
 *  - its amount (money tools) is one the user stated in this task, by the phone's own parsers — a figure the parsers left
 *    unresolved ("two fifty", "1,5") or one from an earlier request is never the planner's to fill in.
 *
 * Fails → the action is replaced by a plain question for the missing fact ("How much do you want to send to Prince?",
 * "Who should I send it to?"), asked at most twice per task; then the task stops honestly. Nothing is guessed. Local
 * decisions (the phone's own parsers) are grounded by construction and don't pass through here. Pure.
 */
object FinalActionGuard {
    /** Tools that call, text or move money — and where their recipient / amount are. */
    private val PERSON_ARGS = mapOf(
        "make_call" to listOf("contact", "number"),
        "send_sms" to listOf("contact", "number"),
        "reply_to_notification" to listOf("sender"),
        "ussd_navigate" to listOf("contact", "number"),
        "momo_send" to listOf("number"),
        "mobile_money" to listOf("_recipient", "_number"),
        "telco_ussd" to listOf("contact", "number"))
    private val AMOUNT_ARGS = mapOf("ussd_navigate" to "amount", "momo_send" to "amount", "mobile_money" to "_amount", "telco_ussd" to "amount")

    sealed class Verdict {
        object Ok : Verdict()
        data class Missing(val what: String, val question: String) : Verdict()
    }

    fun check(name: String, args: JSONObject, facts: TaskFacts): Verdict {
        val keys = PERSON_ARGS[name] ?: return Verdict.Ok
        val values = keys.mapNotNull { k -> args.optString(k).trim().takeIf { it.isNotEmpty() && it != "null" } }
        // A recipient the planner put in that this task never named, answered or found.
        val ungrounded = values.firstOrNull { v -> if (v.count(Char::isDigit) >= 9) !facts.numberGrounded(v) else !facts.personGrounded(v) }
        if (ungrounded != null) return Verdict.Missing("recipient", whoQuestion(name))
        AMOUNT_ARGS[name]?.let { k ->
            val amt = args.optString(k).trim().takeIf { it.isNotEmpty() && it != "null" }
            if (amt != null && !facts.amountStated(amt)) {
                val who = values.firstOrNull { it.none(Char::isDigit) }
                return Verdict.Missing("amount", "How much do you want to send" + (who?.let { " to $it" } ?: "") + "?")
            }
        }
        return Verdict.Ok
    }

    /**
     * A figure the planner types into a phone / USSD menu window (where, after the user approved a money walk, menu steps
     * are carried without another question): a menu choice (one or two digits) is the menu's; a phone number must be
     * grounded; anything else is an amount, and must be one the user stated in this task. Not a figure → not ours to judge.
     */
    internal fun typed(text: String, facts: TaskFacts): Verdict {
        val t = text.trim()
        if (!Regex("^\\+?[\\d .,-]+$").matches(t)) return Verdict.Ok
        val digits = t.count(Char::isDigit)
        // a menu choice: one digit, or the menus' own "more" / "back" codes — "50" is an amount, not a choice
        if ((digits == 1 && t.length == 1) || t in MENU_CODES) return Verdict.Ok
        if (digits >= 9) return if (facts.numberGrounded(t)) Verdict.Ok else Verdict.Missing("recipient", whoQuestion("ussd_navigate"))
        return if (facts.amountStated(t)) Verdict.Ok else Verdict.Missing("amount", "How much do you want to send?")
    }

    private val MENU_CODES = setOf("00", "98", "99")

    /**
     * S-05 — a planner text whose words are not the ones the user dictated goes out with the user's words
     * ([MessageFidelity]); the read-back then quotes what is really sent. The planner's own capitalisation or
     * punctuation of the same words is kept. Pure.
     */
    internal fun withUsersWords(proposed: AgentDecision.CallTool, goal: String): AgentDecision {
        // A chat's composer (`type`): when the planner typed the dictated words as heard, the TIDIED words go instead — the
        // same words with sentences and a full stop (MessageFidelity.tidy; owner, A20e 14 Oct 2026). Any other typed text
        // (a contact's name in a search box) is not touched.
        if (proposed.name == "type") {
            val own = proposed.args.optString("text").takeIf { it.isNotBlank() } ?: return proposed
            // Several dictated messages in one request (one per person): the typed words are left as the planner wrote them —
            // never swapped for another recipient's words (A20e 8 Nov 2026).
            val d = MessageFidelity.dictatedFor(goal, null) ?: return proposed
            // The planner typed the words as heard: they go out tidied (sentences, capitals, a full stop). A polished version
            // of the same message (MessageFidelity.faithful) is kept as written. Anything else typed in this task — a name in
            // a search box — shares no words with the message and is left alone.
            val kept = when {
                d.matches(own) -> MessageFidelity.tidy(own)
                MessageFidelity.faithful(own, d) -> own
                else -> return proposed
            }
            if (kept == own) return proposed
            android.util.Log.i("Tekyerema", "[GUARD] type: the dictated words go out tidied (sentences, capitals, a full stop)")
            return proposed.copy(args = JSONObject(proposed.args.toString()).put("text", kept),
                rationale = (proposed.rationale ?: "") + " [message text tidied]")
        }
        if (proposed.name !in MessageFidelity.TEXT_TOOLS) return proposed
        val key = if (proposed.args.has("message")) "message" else if (proposed.args.has("text")) "text" else return proposed
        val own = proposed.args.optString(key).takeIf { it.isNotBlank() } ?: return proposed
        val to = listOf("contact", "sender", "number").firstNotNullOfOrNull { k -> proposed.args.optString(k).trim().takeIf { it.isNotEmpty() && it != "null" } }
        val kept = MessageFidelity.enforced(proposed.name, own, goal, to) ?: return proposed
        if (kept == own) return proposed
        android.util.Log.i("Tekyerema", "[GUARD] ${proposed.name}: the planner's message was not the user's words — sending the words as dictated")
        val args = JSONObject(proposed.args.toString()).put(key, kept)
        return proposed.copy(args = args, rationale = (proposed.rationale ?: "") + " [message text replaced by the user's own words]")
    }

    private fun whoQuestion(tool: String) = when (tool) {
        "make_call" -> "Who do you want to call? Say the name as it's saved, or the phone number."
        "send_sms", "reply_to_notification" -> "Who should the message go to? Say the name as it's saved, or the phone number."
        else -> "Who should I send it to? Say the name as it's saved, or the phone number."
    }

    /**
     * [proposed] as it may be acted on: unchanged, or the question for what is missing, or — when that question was
     * already asked twice in this task — an honest stop. [history] / [goal] / [screenText] are the task's own record.
     */
    fun apply(proposed: AgentDecision, goal: String, history: List<String>, screenText: String = "", appPackage: String? = null): AgentDecision {
        if (proposed !is AgentDecision.CallTool) return proposed
        val typedInMenu = proposed.name == "type" && com.tekyerema.accessibility.TelephonyWindows.matches(appPackage)
        // A chat composer's text (a `type` outside a phone menu): the dictated words, tidied or polished (D-027).
        if (proposed.name !in PERSON_ARGS && !typedInMenu) return if (proposed.name == "type") withUsersWords(proposed, goal) else proposed
        if (!com.tekyerema.core.config.AppConfig.bool("agent.finalActionGuard", true)) return proposed
        val facts = TaskFacts(goal, history, screenText)
        val v = if (typedInMenu) typed(proposed.args.optString("text"), facts) else check(proposed.name, proposed.args, facts)
        if (v !is Verdict.Missing) return withUsersWords(proposed, goal)
        val asked = history.count { it.startsWith("ask_user(\"${v.question}\")") }
        android.util.Log.i("Tekyerema", "[GUARD] ${proposed.name}: the ${v.what} isn't from this request — " +
            if (asked >= 2) "stopping" else "asking")
        if (asked >= 2) return AgentDecision.CannotDo("unclear",
            "I couldn't tell the ${v.what} from what you said, so nothing was done. Say the whole request again when you're ready.")
        return AgentDecision.CallTool("ask_user", JSONObject().put("question", v.question),
            "the ${v.what} wasn't said in this request — asked, never guessed")
    }
}
