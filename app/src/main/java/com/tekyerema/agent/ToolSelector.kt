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
                "open_intent", "create_contact", "share_text"),
            Regex("(?i)\\b(call|calls|called|phone|ring|dial|answer|hang ?up|text|sms|message|messages|reply|whatsapp|chat|" +
                "notification|notifications|contact|contacts|number|send|tell|missed|inbox|frɛ|fre|nkra)\\b")
        ),
        "money" to Group(
            listOf("mobile_money", "momo_send", "run_ussd"),
            Regex("(?i)(\\bmomo\\b|mobile money|\\bmoney\\b|\\bcedis?\\b|\\bghs\\b|pesewa|transfer|\\bpay\\b|airtime|bundle|" +
                "balance|ussd|\\*\\d|sika)")
        ),
        "device" to Group(
            listOf("set_alarm", "set_timer", "set_volume", "volume_up", "volume_down", "open_setting", "play_music",
                "show_alarms", "calendar", "create_contact", "location", "clipboard", "share_text", "open_intent"),
            Regex("(?i)\\b(alarm|alarms|timer|wake me|remind|reminder|volume|louder|quieter|sound|mute|wi-?fi|bluetooth|" +
                "data|airplane|location|where am i|setting|settings|music|song|play|calendar|event|meeting|appointment|" +
                "schedule|copy|paste|clipboard|share|save (?:the )?(?:number|contact)|add (?:a )?contact|directions|navigate|" +
                "map|maps|dial|email|e-mail|youtube|google|link)\\b")
        ),
        "gesture" to Group(
            listOf("drag", "pinch", "set_range", "scroll_to_find", "wait_for_element", "node_action", "read_all"),
            Regex("(?i)\\b(drag|move|zoom|pinch|slider|slide|brightness|seek|range|rearrange|find|look for|search for|expand|collapse|" +
                "whole|entire|article|rest of|wait for|archive|mark as read|select|copy|paste)\\b")
        )
    )

    fun enabled(): Boolean = FeatureFlags.isEnabled("D-02", true) && AppConfig.bool("tools.search.enabled", true)

    /**
     * Names of the tools to send this step, in registry order.
     * @param text the goal plus recent conversation (what the request is about)
     */
    fun select(text: String, used: Set<String>, discovered: Set<String>, available: List<String>): List<String> {
        if (!enabled()) return available
        val base = LinkedHashSet<String>()
        base += AppConfig.stringList("tools.search.core", DEFAULT_CORE)
        for ((name, g) in groups()) if (g.keywords.containsMatchIn(text)) base += g.tools
        // N-03: Groq's prompt cache matches an exact PREFIX, and the tool list sits right after the system prompt — a
        // tool loaded mid-task (find_tool) used to be slotted in at its registry position, changing every tool after it
        // and so every token after that. The task's base set keeps its order; later additions go on the END, in the
        // order they arrived, so the prefix before them stays byte-identical (Manus, "Context Engineering", 2025:
        // append-only context; the Groq docs: changing tool definitions or their order breaks the cache).
        val extras = LinkedHashSet<String>().apply { addAll(discovered); addAll(used) }.filter { it !in base && it in available }
        return available.filter { it in base } + extras
    }

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
        return candidates.map { t ->
            val hay = (t.name.replace('_', ' ') + " " + t.description).lowercase()
            t to words.count { w -> hay.contains(w) || (w.length > 4 && hay.contains(w.dropLast(1))) }
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(limit).map { it.first }
    }
}
