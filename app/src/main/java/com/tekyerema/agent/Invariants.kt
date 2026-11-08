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
 *  4. A redo of the last request never starts with the action the user just corrected (agent/Rephrase).
 *  5. A money request is never turned into a text, a call or a dial.
 *  6. A message goes through the app the user named ("WhatsApp Kofi …" never leaves as an SMS, nor the other way).
 *  7. A reply never says a message was sent while the screen says it was not ("Not sent. Tap to retry.").
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
        "cancel", "ok", "done", "close", "clear", "edit", "call", "video call", "info", "mute", "delete", "share", "forward", "reply",
        // A20e 6–8 Nov 2026: Telegram's and Messages' own controls were asked about as people ("I found “Go back”. Is that who you mean?")
        "go back", "profile", "search chats", "global search", "pending", "new group", "saved messages", "open camera", "add",
        "attach file", "voice note", "sticker", "gif", "emoji, stickers, and gifs", "message", "type a message",
        // the speaker prefixes of chat bubbles as the planner reads them (ChatBubbles: "You: …" / "Them: …") are not people
        "you", "them", "me", "myself")
    /** The element's kind as the tree names it: a control, a field, a tab or an image is never a person's row. */
    private val NOT_A_PERSON_ROLE = Regex("(?i)(Button|ImageButton|ImageView|EditText|AutoCompleteTextView|SearchView|Switch|CheckBox|RadioButton|Tab\\w*|ActionMenuItemView|Toolbar|SeekBar|ProgressBar)$")
    /** TalkBack-style role suffixes inside a label: "Send, Button", "Chats, tab, selected", "Back, double tap to activate". */
    private val ROLE_SUFFIX = Regex("(?i),\\s*(button|tab|selected|not selected|double tap\\b.*|heading|image|edit box|switch|checkbox)\\s*$")

    /** [grounding] = false for the engine's own steps (a read-back the user corrected, a chain): they stand on what the user
     *  just approved or said, which the task's facts don't hold yet; the other rules still apply. */
    fun apply(decision: AgentDecision, goal: String, history: List<String>, screen: JSONObject?, grounding: Boolean = true): AgentDecision {
        if (!com.tekyerema.core.config.AppConfig.bool("agent.invariants.enabled", true)) return decision
        val app = screen?.optString("app")?.takeIf { it.isNotBlank() && it != "unknown" }
        val guarded = if (!grounding) decision else try {
            FinalActionGuard.apply(decision, goal, history, screen?.toString().orEmpty(), app)
        } catch (_: Throwable) { decision }
        // 7. never "I sent it" while the screen says it was not sent (v5 2B bench, 3 Nov 2026: "I sent your message to Richmond."
        //    over "Not sent. Tap to retry."). A blind user can't see the red mark; the reply is the only report they get.
        if (guarded is AgentDecision.Done) {
            val failed = notSentOnScreen(screen, com.tekyerema.policy.ActionSemantics.lastTyped(history))
            if (failed != null && claimsSent(guarded.summary)) {
                log("[INVARIANT] a done that claims sent while the screen says not sent — refused")
                return AgentDecision.CannotDo("failed", "The message did not go: the screen says “$failed”. Nothing was sent. " +
                    "Ask me to try again when you're ready.")
            }
            return guarded
        }
        val c = guarded as? AgentDecision.CallTool ?: return guarded
        // 5. a request to send money is never turned into a text, a call or a dial by a brain (A20e, 3 Nov 2026: the 2B planned
        //    an SMS composer and a dial of 130 for "send 1 cedi to Prince"). Money goes through Mobile Money's own menu only.
        if (grounding && c.name in NOT_MONEY_CHANNELS && isMoneySend(goal)) {
            log("[INVARIANT] a money request turned into ${c.name} — refused")
            return AgentDecision.CannotDo("unsafe", "That's a money request, and I only send money through Mobile Money's own menu, " +
                "which reads it back for your yes and stops at your PIN. Say it again as: send, the amount, to, the name.")
        }
        // 6. a message goes through the app the user named: "WhatsApp Kofi …" is never sent as an SMS (v5 2B bench, 3 Nov 2026:
        //    it dropped via=whatsapp on 4 of 6 WhatsApp sends). Only when the user said one channel and not the other.
        if (c.name == "send_sms") {
            val said = TaskFacts(goal, history, "").said
            val wa = WHATSAPP.containsMatchIn(said); val sms = SMS.containsMatchIn(said)
            val via = c.args.optString("via").lowercase()
            if (wa && !sms && !via.contains("whatsapp")) {
                log("[INVARIANT] the user named WhatsApp — send_sms via whatsapp")
                return c.copy(args = JSONObject(c.args.toString()).put("via", "whatsapp"))
            }
            if (sms && !wa && via.contains("whatsapp")) {
                log("[INVARIANT] the user asked for a text message — send_sms via sms")
                return c.copy(args = JSONObject(c.args.toString()).put("via", "sms"))
            }
        }
        // 4. a redo of the last request never starts with the same action that was wrong (agent/Rephrase)
        if (Rephrase.repeats(goal, c.name, c.args))
            return askOnce(history, guarded, "Last time I did that, and it wasn't what you wanted. What should I do instead?",
                "the same action as the try the user just corrected")
        if (c.name != "tap" || !com.tekyerema.policy.TargetSemantics.isChatPackage(app)) return guarded
        val label = targetLabel(c.args, screen)?.trim()?.takeIf { it.isNotEmpty() } ?: return guarded
        // What the user said and what this task's look-ups returned — NOT the screen: the tapped name is always on it.
        val facts = TaskFacts(goal, history, "")
        // "Send, Button" is the Send control, not a person called Send: the name is the label up to its first separator (SeenTarget).
        val name = SeenTarget.nameOf(label).take(60)
        val element = targetElement(c.args, screen)

        // 2. a channel / group / bot the user didn't ask for
        if (looksGroupish(label) && !GROUPISH_REQUEST.containsMatchIn(facts.said)) {
            return askOnce(history, guarded, "“$name” is a channel or a group, not a person. Is that what you want to open?",
                "a channel or group the user didn't ask for")
        }
        // 3. a person the user didn't name, while the request is to reach someone — only when what is tapped can BE a person
        //    (a chat/contact row), never the app's controls, fields or tabs, nor the words the agent itself just typed
        //    (A20e 8 Nov 2026: "I found “Go back” / “Send, Button”. Is that who you mean?" after the message had gone).
        if (reachesSomeone(goal) && personRow(element, label, history) && looksLikeName(name) && !facts.personGrounded(name)) {
            return askOnce(history, guarded, "I found “$name”. Is that who you mean?", "a person the user didn't name")
        }
        // 3b. reading what a NAMED person sent, but the tapped chat is someone (or something) else — A20e 6 Nov 2026: asked for
        //     Richmond's last Telegram message, the brain opened "Learn Python Coding" and reported its post as his.
        if (!reachesSomeone(goal) && readsFromSomeone(goal) && personRow(element, label, history)) {
            val chat = SeenTarget.nameOf(label)
            if (looksLikeName(chat) && !facts.personGrounded(chat))
                return askOnce(history, guarded, "I found “$chat”. Is that whose messages you mean?", "a chat that isn't the person named")
        }
        return guarded
    }

    private val NOT_SENT = Regex("(?i)\\b(not sent|message failed|failed to send|sending failed|send failed|couldn.t send|could not send|not delivered|tap to (?:retry|try again))\\b")
    private val SENT_CLAIM = Regex("(?i)\\b(sent|delivered|texted|messaged|went through)\\b")
    private val NOT_CLAIM = Regex("(?i)\\b(not|n.t|never|couldn.t|failed|unable)\\b[^.]{0,20}\\b(sent|send|deliver|go|went)")

    /** The failure marker on screen ("Not sent"), on the message just typed when it is known; null when nothing failed. */
    internal fun notSentOnScreen(screen: JSONObject?, typed: String?): String? {
        val els = screen?.optJSONArray("elements") ?: return null
        val head = typed?.trim()?.take(24)?.lowercase()
        for (i in 0 until els.length()) {
            val label = els.optJSONObject(i)?.optString("label").orEmpty()
            val m = NOT_SENT.find(label) ?: continue
            if (head != null && head.length >= 4 && !label.lowercase().contains(head)) continue
            return m.value.replaceFirstChar { it.uppercase() }
        }
        return null
    }

    internal fun claimsSent(summary: String): Boolean = SENT_CLAIM.containsMatchIn(summary) && !NOT_CLAIM.containsMatchIn(summary)

    private val WHATSAPP =Regex("(?i)\\bwhats\\s?app\\b")
    private val SMS = Regex("(?i)\\b(sms|text message|normal text|by text)\\b")

    private val NOT_MONEY_CHANNELS =setOf("send_sms", "make_call", "open_intent", "reply_to_notification", "run_ussd_unchecked")

    internal fun isMoneySend(goal: String): Boolean = try {
        com.tekyerema.money.MoneyAsk.parse(goal)?.kind == com.tekyerema.money.MoneyAsk.Kind.SEND
    } catch (_: Throwable) { false }

    /** The request is to send to, call or reply to someone (not to read or check messages). */
    internal fun reachesSomeone(goal: String): Boolean =
        REACH.containsMatchIn(goal) && !READING.containsMatchIn(goal)
    private val REACH = Regex("(?i)\\b(send|text|tell(?!\\s+me\\b)|reply|call|ring|dial|forward|share|message\\s+\\w|dm)\\b")
    private val READING = Regex("(?i)\\b(read|check|what|any new|show me|did|who sent|latest from)\\b")

    /** The request is to read what a particular person sent ("the last message Richmond sent me", "messages from Ama",
     *  "what did Kofi say", "did Prince text me"). */
    internal fun readsFromSomeone(goal: String): Boolean = READS_FROM.containsMatchIn(goal)
    private val READS_FROM = Regex("(?i)\\b(?:message|messages|text|texts|chat|voice notes?|photos?|videos?|links?)\\b.{0,40}\\b(?:from|sent|by)\\b|" +
        "\\bsent me\\b|\\bwhat did \\w+ (?:say|send|write)\\b|\\bdid \\w+ (?:send|text|message|write|reply)\\b")

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

    /** The screen element a tap touches, by element_id (null for a label-only tap or an unknown id). */
    internal fun targetElement(args: JSONObject, screen: JSONObject?): JSONObject? {
        val id = if (args.has("element_id")) args.optInt("element_id", -1) else -1
        if (id < 0) return null
        val els = screen?.optJSONArray("elements") ?: return null
        for (i in 0 until els.length()) {
            val e = els.optJSONObject(i) ?: continue
            if (e.optInt("id", -2) == id) return e
        }
        return null
    }

    /**
     * Whether the tapped thing can be a person at all: a chat or contact row. False for the app's own controls (Button,
     * ImageButton, Switch…), fields, tabs and selected items, for TalkBack-style role suffixes in the label ("Send, Button"),
     * for anything inside a list that isn't a row, and for the words the agent itself typed a moment ago. Unknown kinds
     * (a bare ViewGroup with a name) still count — the question is cheap, a wrong chat is not.
     */
    internal fun personRow(element: JSONObject?, label: String, history: List<String>): Boolean {
        if (ROLE_SUFFIX.containsMatchIn(label.lines().first())) return false
        val typed = com.tekyerema.policy.ActionSemantics.lastTyped(history)
        if (typed != null && typed.length >= 4 && label.contains(typed.take(24), ignoreCase = true)) return false
        if (element == null) return true
        if (NOT_A_PERSON_ROLE.containsMatchIn(element.optString("role"))) return false
        val acts = element.optJSONArray("actions")
        if (acts != null && (0 until acts.length()).any { acts.optString(it) == "type" }) return false
        val flags = element.optJSONArray("flags")
        if (flags != null && (0 until flags.length()).any { flags.optString(it) in setOf("selected", "checkable", "heading") }) return false
        val structure = element.optString("structure")
        return structure.isEmpty() || structure.startsWith("row ")
    }

    /** A chat-list row whose preview is the chat's own name doing something ("Learn Python Coding pinned a photo"): a group or
     *  channel's system line, never a person's message (Telegram, A20e 6 Nov 2026). */
    private val PINNED = Regex("(?i)^([^,\\n]{2,60}),.*\\b\\1 (?:pinned|posted|added|removed|changed)\\b")

    internal fun looksGroupish(label: String): Boolean = SUBSCRIBERS.containsMatchIn(label) || PINNED.containsMatchIn(label) ||
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
