package com.tekyerema.agent

import org.json.JSONObject

/**
 * One step the agent REALLY took in run [runSeq] — published in [AgentUiState.steps] where the engine already logs
 * "[PLAN] Step N: tool(args)" and "[ACTION] tool → ok|failed" (docs/assistant-ux/DECISIONS.md UX-1). The assistant surface
 * builds its activity timeline and its result preview from these, so it can only show what actually happened.
 *
 * Privacy: [subject] is WHITELISTED per tool ([AgentSteps.subjectOf]) — an app's name, the words of a web search, a setting —
 * never a message's text, a phone number, an amount or anything typed. No step is recorded during the secure step (the
 * engine does not act there).
 */
data class AgentStep(
    val runSeq: Long,
    /** The engine's step number within the run. */
    val index: Int,
    val tool: String,
    val subject: String? = null,
    val state: State = State.RUNNING,
    /** A web search's sources (title + link), exactly as the search returned them. */
    val sources: List<Source> = emptyList(),
    /** A map link a location step returned. */
    val link: String? = null
) {
    enum class State { RUNNING, DONE, FAILED }
    data class Source(val title: String, val url: String)
}

/** Pure bookkeeping for [AgentUiState.steps] (unit-tested). */
object AgentSteps {
    /** At most this many steps are kept for a run (a long walk shows its latest). */
    const val MAX = 24

    /** A step started in [runSeq]. Steps of an older run are dropped — a list only ever describes one run — and a late step of
     *  an older run never replaces a newer run's list. */
    fun started(list: List<AgentStep>, runSeq: Long, index: Int, tool: String, subject: String?): List<AgentStep> {
        if (list.any { it.runSeq > runSeq }) return list
        val mine = list.filter { it.runSeq == runSeq && it.index != index }
        return (mine + AgentStep(runSeq, index, tool, subject)).takeLast(MAX)
    }

    /** The step [index] of [runSeq] finished. A finish for another run, or for a step never started, changes nothing. */
    fun finished(list: List<AgentStep>, runSeq: Long, index: Int, ok: Boolean,
                 sources: List<AgentStep.Source> = emptyList(), link: String? = null): List<AgentStep> =
        list.map { s ->
            if (s.runSeq == runSeq && s.index == index && s.state == AgentStep.State.RUNNING)
                s.copy(state = if (ok) AgentStep.State.DONE else AgentStep.State.FAILED, sources = sources, link = link ?: s.link)
            else s
        }

    /** What a step may say about itself: per tool, only fields that carry no private content. */
    fun subjectOf(tool: String, args: JSONObject?): String? {
        val a = args ?: return null
        fun s(k: String) = a.optString(k).trim().takeIf { it.isNotEmpty() && it != "null" }
        return when (tool) {
            "open_app", "close_app", "install_app" -> s("app")
            "web_search", "search_web" -> s("query")?.take(80)
            "open_setting" -> s("target")
            "find_contact", "search_contacts" -> s("query") ?: s("contact")
            "make_call", "send_sms", "reply_to_notification" -> s("contact")?.takeIf { c -> c.count { it.isDigit() } < 3 }
            "play_music" -> s("query")?.take(60)
            else -> null
        }
    }

    /** A search's sources from its tool result data ({"sources":[{"title","url"}]}); empty when it has none. */
    fun sourcesOf(data: JSONObject?): List<AgentStep.Source> {
        val arr = data?.optJSONArray("sources") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url").trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) return@mapNotNull null
            AgentStep.Source(o.optString("title").trim().ifEmpty { hostOf(url) }, url)
        }.distinctBy { it.url }.take(6)
    }

    /** A map link from a location step's data ({"link": "https://maps…"}), or null. */
    fun linkOf(data: JSONObject?): String? =
        data?.optString("link")?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("geo:") }

    internal fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore('/').removePrefix("www.").ifEmpty { url }
}
