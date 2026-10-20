package com.tekyerema.agent

import com.tekyerema.core.config.AppConfig
import com.tekyerema.core.config.FeatureFlags
import com.tekyerema.tools.Tool

/**
 * D-02 — deferred tool loading. Every agent step re-sends the tool schema, and Groq's free tier allows ~8k tokens a
 * MINUTE, so sending all ~30 schemas (~4k tokens) every step capped the agent at about one step a minute per key.
 * Each step now carries: the universal screen primitives (always), the capability GROUPS the request plainly needs
 * (comms, money, device, gestures…), every tool already used in this task, and anything the model discovered with
 * `find_tool`. Groups and keywords are data (`tools.search.*`) with the defaults below. Flag D-02 off = all tools.
 */
object ToolSelector {
    /** `web_search` is core: the system prompt tells the model to look up any fact it isn't sure of. Loaded only by
     *  keyword, it was missing on a follow-up ("I meant mimics…", A20e 2026-09-28) — the model said "there's no
     *  web_search tool" and spent a step on find_tool. Its schema is ~60 tokens. */
    val DEFAULT_CORE = listOf("read_state", "wait", "tap", "long_press", "swipe", "scroll", "type", "key_event",
        "open_app", "cannot_do", "find_tool", "ask_user", "web_search", "tap_sequence")

    private data class Group(val tools: List<String>, val keywords: Regex)

    private val DEFAULT_GROUPS = linkedMapOf(
        // I-02 / I-03: time-sensitive questions load the web search tool (already core by default; this keeps it for a
        // config that trims `tools.search.core`).
        "web" to Group(
            listOf("web_search"),
            Regex("(?i)\\b(news|headlines|latest|today|tonight|tomorrow|this week|weekend|price|prices|exchange rate|rate|weather|" +
                "forecast|rain|temperature|score|scores|won|result|results|fixture|fixtures|current|currently|open now|opening hours|" +
                "near me|search|google|look up|nnɛ|ɔkyena|nsɛm)\\b")
        ),
        "comms" to Group(
            listOf("make_call", "reply_to_notification", "read_notifications", "search_contacts", "find_contact",
                "search_messages", "send_sms", "call_control", "call_log", "read_sms", "notification_action",
                "open_intent", "create_contact", "share_text", "share_screenshot"),
            // "phone" and "number" alone loaded all 15 comms schemas for "open settings and go to about phone" and "tell me the
            // android version" (P1-05, 13 October 2026): a call, a text or a contact is named by its own word; "tell" counts
            // only when someone is told, not "tell me".
            Regex("(?i)\\b(call|calls|called|ring|dial|answer|hang ?up|text|sms|message|messages|reply|whatsapp|chat|" +
                "notification|notifications|contact|contacts|send|missed|inbox|telegram|share|screenshot|frɛ|fre|nkra)\\b|" +
                "\\btell\\b(?!\\s+(?:me|us)\\b)|\\bphone\\s+(?:call|number)\\b|\\bthis\\s+number\\b|\\bthat\\s+number\\b|" +
                // "phone Kofi" / "please phone my mum": the verb, at the start of a request or after a joining word — not
                // "about phone" or "phone settings" (PromptCacheTest: "phone Kofi" loads the same block as "call Ama").
                "(?:^|\\n|\\b(?:please|can you|could you|and|then|now)\\s+)phone\\s+(?!(?:settings?|app|storage|info|information|status|is|was)\\b)\\w")
        ),
        "money" to Group(
            listOf("mobile_money", "momo_send", "run_ussd"),
            // "cash": the owner's "Send cash to 055…" (A20e 2026-09-29) loaded no money tool, so the planner hunted for
            // a "Mobile Money" app instead of walking *170#.
            Regex("(?i)(\\bmomo\\b|mobile money|\\bmoney\\b|\\bcash\\b|\\bcedis?\\b|\\bghs\\b|pesewa|transfer|\\bpay\\b|airtime|bundle|" +
                "balance|ussd|\\*\\d|sika)")
        ),
        // P1-05 token diet (13 October 2026): the old single "device" group carried 15 schemas and loaded WHOLE on any word
        // from "settings" to "song" — "open settings and go to about phone" sent 29 tools (~5,000 prompt tokens a step).
        // Split by what a request is about; a tool outside the loaded groups is still one find_tool away.
        "settings" to Group(
            listOf("open_setting", "location"),
            Regex("(?i)\\b(setting|settings|wi-?fi|bluetooth|mobile data|airplane|flight mode|location|where am i|brightness|" +
                "dark mode|dark theme|theme|font|text size|battery saver|do not disturb|dnd|hotspot|nfc|rotation|auto-?rotate)\\b")
        ),
        "alarm" to Group(
            listOf("set_alarm", "cancel_alarm", "set_timer", "show_alarms"),
            Regex("(?i)\\b(alarm|alarms|timer|timers|wake me|remind|reminder|countdown|stopwatch)\\b")
        ),
        "volume" to Group(
            listOf("set_volume", "volume_up", "volume_down"),
            Regex("(?i)\\b(volume|louder|quieter|softer|sound|mute|unmute|silent)\\b")
        ),
        "walk" to Group(
            listOf("walk_directions"),
            Regex("(?i)\\b(directions?|navigate|navigation|route|walk me|take me|get to|way to|nearest|closest|where is the|how far|how long to get|distance (?:to|from))\\b")
        ),
        "media" to Group(
            listOf("play_music"),
            Regex("(?i)\\b(music|song|songs|play|playlist|podcast|radio|youtube|spotify|boomplay|audiomack)\\b")
        ),
        "calendar" to Group(
            listOf("calendar"),
            Regex("(?i)\\b(calendar|event|events|meeting|meetings|appointment|appointments|schedule|agenda)\\b")
        ),
        "share" to Group(
            listOf("create_contact", "clipboard", "share_text", "open_intent"),
            Regex("(?i)\\b(copy|paste|clipboard|share|save (?:the )?(?:number|contact)|add (?:a )?contact|new contact|directions|" +
                "navigate|map|maps|dial|email|e-mail|google|link|website|url)\\b")
        ),
        "gesture" to Group(
            listOf("drag", "pinch", "set_range", "scroll_to_find", "wait_for_element", "node_action", "read_all"),
            Regex("(?i)\\b(drag|move|zoom|pinch|slider|slide|brightness|seek|range|rearrange|find|look for|search for|expand|collapse|" +
                "whole|entire|article|rest of|wait for|archive|mark as read|select|copy|paste)\\b")
        )
    )

    fun enabled(): Boolean = FeatureFlags.isEnabled("D-02", true) && AppConfig.bool("tools.search.enabled", true)

    /**
     * Names of the tools to send this step, in a cache-stable order: the core block, then each loaded group in group
     * order, then tools added during the task in the order they arrived.
     * @param text the goal plus recent conversation (what the request is about)
     */
    fun select(text: String, used: Set<String>, discovered: Set<String>, available: List<String>): List<String> {
        if (!enabled()) return available
        // N-03: Groq's prompt cache matches an exact PREFIX (per organization, i.e. per key here, for 2 h), and the tool
        // list sits right after the system prompt. Two rules keep that prefix long:
        //  - ACROSS tasks: the core block comes first, always in the same order, and each group after it as a block. In
        //    registry order a group's tools were interleaved with the core ones (drag and pinch between swipe and scroll,
        //    the comms tools before web_search), so a task that loaded a different group diverged from the last one a
        //    few tools in; now every task shares system prompt + core tools (~2.6k tokens), whatever else it loads.
        //  - WITHIN a task: a tool loaded mid-task (find_tool, or used) goes on the END in arrival order, so the prefix
        //    before it stays byte-identical (Manus, "Context Engineering", 2025: append-only context; the Groq docs:
        //    changing tool definitions or their order breaks the cache).
        val have = available.toSet()
        val base = LinkedHashSet<String>()
        AppConfig.stringList("tools.search.core", DEFAULT_CORE).filterTo(base) { it in have }
        for ((_, g) in groups()) if (g.keywords.containsMatchIn(text)) g.tools.filterTo(base) { it in have }
        val extras = LinkedHashSet<String>().apply { addAll(discovered); addAll(used) }.filter { it !in base && it in have }
        return base.toList() + extras
    }

    /**
     * What the selector reads for a step: the goal, plus the last turns of the conversation ONLY when the goal leans on
     * them — short, or carrying a pronoun / "again" / "the same". A new, self-contained request after a money walk used to
     * load the comms and money blocks from the previous turns ("open settings and go to about phone…" went out with 37
     * tools instead of 19, ≈1,100 prompt tokens a step for nothing — emulator, 13 October 2026).
     */
    fun contextFor(goal: String, recentTurns: List<String>): String {
        val g = goal.trim()
        val words = g.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val leans = words.size <= 4 || FOLLOW_UP.containsMatchIn(g)
        return if (leans && recentTurns.isNotEmpty()) g + " " + recentTurns.joinToString(" ") else g
    }
    private val FOLLOW_UP = Regex("(?i)\\b(it|that|this|those|these|them|him|her|there|same|again|also|too|another|more|instead|rather|previous|last one)\\b")

    /** N-03: a tool schema array rearranged into [names]' order (the registry emits its own order); entries not named
     *  keep their place after the named ones. */
    fun inOrder(schema: org.json.JSONArray, names: List<String>, nameOf: (org.json.JSONObject) -> String?): org.json.JSONArray {
        val rank = names.withIndex().associate { it.value to it.index }
        val items = (0 until schema.length()).mapNotNull { schema.optJSONObject(it) }
        return org.json.JSONArray(items.sortedBy { rank[nameOf(it)] ?: Int.MAX_VALUE })
    }

    private fun groups(): Map<String, Group> {
        val o = AppConfig.obj("tools.search.groups") ?: return DEFAULT_GROUPS
        val out = LinkedHashMap(DEFAULT_GROUPS)
        for (k in o.keys()) {
            val g = o.optJSONObject(k) ?: continue
            val tools = g.optJSONArray("tools")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: out[k]?.tools ?: continue
            val kw = g.optString("keywords").takeIf { it.isNotBlank() }?.let { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }
                ?: out[k]?.keywords ?: continue
            out[k] = Group(tools, kw)
        }
        return out
    }

    /** `find_tool`: the best matches for [query] among [candidates] by word overlap with name and description. */
    fun search(query: String, candidates: List<Tool>, limit: Int = 4): List<Tool> {
        val words = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.toSet()
        if (words.isEmpty()) return emptyList()
        return candidates.filter { !it.hiddenFromModel }.map { t ->
            val hay = (t.name.replace('_', ' ') + " " + t.description).lowercase()
            t to words.count { w -> hay.contains(w) || (w.length > 4 && hay.contains(w.dropLast(1))) }
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(limit).map { it.first }
    }
}
