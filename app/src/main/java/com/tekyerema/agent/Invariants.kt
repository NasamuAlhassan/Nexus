package com.tekyerema.agent

import org.json.JSONObject

/**
 * Mistakes that must not happen, whatever brain decided (owner, 3 Nov 2026: "some mistakes shouldn't be made at all").
 *
 * Every decision passes here before it is acted on: the cloud planner's, the Space model's, the on-phone model's, a planned
 * step's. A model can be taught to be careful; these are not left to teaching:
 *
 *  1. A call, text or money action stands on a recipient and an amount from THIS task ([FinalActionGuard]) — for every
 *     brain, not only the cloud one.
 *  2. In a chat app, the agent never opens a CHANNEL, GROUP or BOT the user didn't ask for. A misheard "Richmond" once
 *     opened a public broadcast channel (A20e, 2 Nov 2026, audit/real-requests-2026-11-03 r41/r43).
 *  3. In a chat app, on a request to talk to someone, the agent never opens a chat with a person the user didn't name
 *     (not said, not by sound, not found by a look-up): it asks "I found “X”. Is that who you mean?" once.
 *
 * A rule that asks is asked at most once per target; the user's answer decides (yes → go on; anything else → ask who, then
 * stop honestly). Pure but for config → InvariantsTest.
 */
object Invariants {
    private val GROUPISH_REQUEST = Regex("(?i)\\b(channel|group|bot|community|page|broadcast)\\b")
    private val SUBSCRIBERS = Regex("(?i)\\b[\\d.,]+\\s*[km]?\\s+(?:subscribers?|members?|followers?)\\b")
    private val BOTISH = Regex("(?i)(?:^|\\s)(?:bot|channel)(?:\\s|$)|bot$")
    private val CONTROL_WORDS = setOf("chats", "calls", "status", "updates", "communities", "community", "search", "new chat",
        "contacts", "settings", "archived", "back", "more options", "send", "attach", "camera", "emoji", "voice message", "menu",
        "navigate up", "new message", "start chat", "messages", "inbox", "all", "unread", "favourites", "favorites", "groups",
        "cancel", "ok", "done", "close", "clear", "edit", "call", "video call", "info", "mute", "delete", "share", "forward", "reply")

    /** [grounding] = false for the engine's own steps (a read-back the user corrected, a chain): they stand on what the user
     *  just approved or said, which the task's facts don't hold yet; the other rules still apply. */
    fun apply(decision: AgentDecision, goal: String, history: List<String>, screen: JSONObject?, grounding: Boolean = true): AgentDecision {
        if (!com.tekyerema.core.config.AppConfig.bool("agent.invariants.enabled", true)) return decision
        val app = screen?.optString("app")?.takeIf { it.isNotBlank() && it != "unknown" }
        val guarded = if (!grounding) decision else try {
            FinalActionGuard.apply(decision, goal, history, screen?.toString().orEmpty(), app)
        } catch (_: Throwable) { decision }
        val c = guarded as? AgentDecision.CallTool ?: return guarded
        // 5. a request to send money is never turned into a text, a call or a dial by a brain (A20e, 3 Nov 2026: the 2B planned
        //    an SMS composer and a dial of 130 for "send 1 cedi to Prince"). Money goes through Mobile Money's own menu only.
        if (grounding && c.name in NOT_MONEY_CHANNELS && isMoneySend(goal)) {
            log("[INVARIANT] a money request turned into ${c.name} — refused")
            return AgentDecision.CannotDo("unsafe", "That's a money request, and I only send money through Mobile Money's own menu, " +
                "which reads it back for your yes and stops at your PIN. Say it again as: send, the amount, to, the name.")
        }
        // 4. a redo of the last request never starts with the same action that was wrong (agent/Rephrase)
        if (Rephrase.repeats(goal, c.name, c.args))
            return askOnce(history, guarded, "Last time I did that, and it wasn't what you wanted. What should I do instead?",
                "the same action as the try the user just corrected")
        if (c.name != "tap" || !com.tekyerema.policy.TargetSemantics.isChatPackage(app)) return guarded
        val label = targetLabel(c.args, screen)?.trim()?.takeIf { it.isNotEmpty() } ?: return guarded
        // What the user said and what this task's look-ups returned — NOT the screen: the tapped name is always on it.
        val facts = TaskFacts(goal, history, "")
        val name = label.lines().first().trim().take(60)

        // 2. a channel / group / bot the user didn't ask for
        if (looksGroupish(label) && !GROUPISH_REQUEST.containsMatchIn(facts.said)) {
            return askOnce(history, guarded, "“$name” is a channel or a group, not a person. Is that what you want to open?",
                "a channel or group the user didn't ask for")
        }
        // 3. a person the user didn't name, while the request is to reach someone
        if (reachesSomeone(goal) && looksLikeName(name) && !facts.personGrounded(name)) {
            return askOnce(history, guarded, "I found “$name”. Is that who you mean?", "a person the user didn't name")
        }
        return guarded
    }

    private val NOT_MONEY_CHANNELS = setOf("send_sms", "make_call", "open_intent", "reply_to_notification", "run_ussd_unchecked")

    internal fun isMoneySend(goal: String): Boolean = try {
        com.tekyerema.money.MoneyAsk.parse(goal)?.kind == com.tekyerema.money.MoneyAsk.Kind.SEND
    } catch (_: Throwable) { false }

    /** The request is to send to, call or reply to someone (not to read or check messages). */
    internal fun reachesSomeone(goal: String): Boolean =
        REACH.containsMatchIn(goal) && !READING.containsMatchIn(goal)
    private val REACH = Regex("(?i)\\b(send|text|tell(?!\\s+me\\b)|reply|call|ring|dial|forward|share|message\\s+\\w|dm)\\b")
    private val READING = Regex("(?i)\\b(read|check|what|any new|show me|did|who sent|latest from)\\b")

    /** The label of what a tap would touch: the element by id, or the label the planner gave. */
    internal fun targetLabel(args: JSONObject, screen: JSONObject?): String? {
        val id = if (args.has("element_id")) args.optInt("element_id", -1) else -1
        if (id >= 0) {
            val els = screen?.optJSONArray("elements")
            if (els != null) for (i in 0 until els.length()) {
                val e = els.optJSONObject(i) ?: continue
                if (e.optInt("id", -2) == id) return e.optString("label").takeIf { it.isNotBlank() }
            }
        }
        return args.optString("label").takeIf { it.isNotBlank() && it != "null" }
    }

    internal fun looksGroupish(label: String): Boolean = SUBSCRIBERS.containsMatchIn(label) ||
        label.lines().any { l -> BOTISH.containsMatchIn(l.trim()) }

    /** One to four words of letters, not one of the chat apps' own controls: a person's (or a chat's) name. */
    internal fun looksLikeName(s: String): Boolean {
        val t = s.trim().trimEnd(',', '.', ':')
        if (t.lowercase() in CONTROL_WORDS) return false
        val words = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 4) return false
        if (t.any { it.isDigit() }) return false
        return words.all { w -> w.any { it.isLetter() } } && t.first().isUpperCase()
    }

    /**
     * The question once; then the user's answer decides: yes → the action goes on; otherwise the agent asks who they meant
     * (once) and then stops honestly. Never a loop, never a silent guess.
     */
    private fun askOnce(history: List<String>, action: AgentDecision, question: String, why: String): AgentDecision {
        val answer = com.tekyerema.money.MoneyFlow.answers(history).lastOrNull { it.first == question }?.second
        if (answer != null) {
            if (com.tekyerema.voice.Confirmation.interpret(answer) == com.tekyerema.voice.Confirmation.Answer.YES) return action
            val who = "Who do you want? Say the name as it's saved in your phone."
            if (history.any { it.startsWith("ask_user(\"$who\")") })
                return AgentDecision.CannotDo("unclear", "I couldn't find the person you meant, so I didn't open any chat.")
            return AgentDecision.CallTool("ask_user", JSONObject().put("question", who), "invariant: $why — asked who, never guessed")
        }
        if (history.any { it.startsWith("ask_user(\"$question\")") }) return action
        log("[INVARIANT] $why — asking first")
        return AgentDecision.CallTool("ask_user", JSONObject().put("question", question), "invariant: $why — asked, never assumed")
    }

    private fun log(s: String) = try { android.util.Log.i("Tekyerema", s) } catch (_: Throwable) {}
}
