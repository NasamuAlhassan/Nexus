package com.tekyerema.agent.research

import android.content.Context
import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.References
import com.tekyerema.agent.RetrievalNeed
import com.tekyerema.agent.search.Source
import com.tekyerema.core.config.AppConfig
import org.json.JSONObject

/**
 * I-03's twin for ACTIONS (research brief §B.5): "open University of Ghana Sakai" names a destination that isn't on the
 * phone. Instead of the planner guessing an app, hunting a browser step by step, or asking "which app?", the engine
 * runs this deterministic pre-step:
 *
 *   Recall   a remembered pick for this referent → open it ("Opening UG Sakai, like last time.") — offline, free;
 *   Research one web_search for "<referent> official website login" → candidates from the sources' host + title
 *            ([Candidates]: guide/aggregator sites dropped, the organisation's own domain preferred);
 *   Decide   T1 thresholds (top ≥ 0.75 and ≥ 0.15 ahead of the next): act — open the https address and say the host;
 *            2–3 close → ask "Which one: A, B, or C?" (one question, most likely first; "the second one", a name, "none",
 *            "stop" all work); nothing → the planner tells the user and offers a browser search;
 *   Remember the pick ([ResolvedChoices]) once the site actually opened and verified.
 * "not that one" right after an open forgets that pick and asks this time, never guessing twice.
 *
 * Returns null when the request isn't a LOCATE, an installed app matches (the app wins), or the phone is offline — the
 * normal route then runs unchanged. Search text never becomes an instruction: only host + title are read from it.
 */
object TargetResearch {
    /** The rationale on the planned open step, so the engine can tell it apart. */
    const val TAG = "local:research"

    private val NOT_THAT = Regex("^(?:no[,.]? )?(?:not that one|not that site|wrong one|wrong site|that's the wrong (?:one|site|page)|" +
        "that is the wrong (?:one|site|page)|that's not it|that is not it|not the right (?:one|site))\\b")

    fun enabled(): Boolean = AppConfig.bool("answers.locate.enabled", true)

    /** What the pre-step needs from the phone — injected so the whole flow is unit-tested. */
    class Deps(
        val store: ChoiceStore,
        val online: Boolean,
        /** One web search → its sources; null when search isn't available (no provider, quota, network). */
        val search: suspend (query: String) -> List<Source>?,
        /** An installed app answers to this name (strict match) — then the app wins. */
        val isLocalApp: (String) -> Boolean,
        /** The package that opens [url] (for the verify claim); null = unknown or a which-app chooser. */
        val handlerFor: (url: String) -> String?,
        /** Ask the user and wait (the engine's askUser); null = unattended, never ask. */
        val ask: (suspend (question: String, spoken: String) -> String?)?,
        val log: (String) -> Unit = {}
    )

    class Plan internal constructor(
        /** A planned open_intent(open_url) step — it goes through the whole pipeline (policy, execute, verify). */
        val open: AgentDecision.CallTool? = null,
        /** A line for the planner's step record when research didn't settle it. */
        val note: String? = null,
        /** End the task here with [End.line] (the user stopped, or never answered). */
        val end: End? = null,
        private val pending: Pending? = null
    ) {
        class End(val stopped: Boolean, val line: String)

        /** After the planned open ran: the spoken line that ends the task, or null (it failed — the planner goes on).
         *  The pick is remembered only when the site verifiably came up. */
        fun after(success: Boolean, verified: Boolean): String? {
            val p = pending ?: return null
            if (!success) return null
            if (verified) p.store.remember(p.referent, "url", p.url, p.label, p.source) else p.store.noteOpened(p.referent, p.url)
            return p.spoken
        }
    }

    internal class Pending(val store: ChoiceStore, val referent: String, val url: String, val label: String, val source: String, val spoken: String)

    /** The search query for a referent. */
    fun query(referent: String): String = "$referent official website login"

    /** [plan], but a failure inside research never fails the task — the planner simply goes on. */
    suspend fun planSafely(goal: String, d: Deps): Plan? = try { plan(goal, d) }
        catch (c: kotlinx.coroutines.CancellationException) { throw c }
        catch (t: Throwable) { d.log("research: skipped (${t.javaClass.simpleName})"); null }

    suspend fun plan(goal: String, d: Deps): Plan? {
        if (!enabled()) return null
        val g = goal.trim().lowercase()
        var forceAsk = false
        var excludeHost: String? = null
        val referent: String
        if (NOT_THAT.containsMatchIn(g)) {
            // "not that one" right after we opened something: forget it, and ask this time (never guess twice).
            val (prev, prevUrl) = d.store.recentOpened() ?: return null
            excludeHost = prevUrl?.let { SafeUrl.host(it) }
            d.store.forget(prev, exact = true)
            d.log("research: the user said the last pick was wrong — forgotten, asking this time")
            referent = prev
            forceAsk = true
        } else {
            referent = RetrievalNeed.locate(goal) ?: return null
            if (d.isLocalApp(referent)) { d.log("research: an installed app answers to it — the app wins"); return null }
            d.store.recall(referent)?.takeIf { it.kind == "url" }?.let { c ->
                val url = SafeUrl.httpsOrNull(c.value)
                if (url != null) {
                    d.log("research: remembered pick (${c.source})")
                    return open(d, referent, url, c.label, "Opening ${c.label}, like last time.", c.source)
                }
            }
        }
        if (!d.online) return null
        val sources = d.search(query(referent))
        if (sources == null) { d.log("research: web search unavailable"); return null }
        val cands = Candidates.from(sources, referent).filter { it.host != excludeHost }
        val outcome = Decide.decide(cands, if (forceAsk) Decide.Tier.CONSEQUENTIAL else Decide.Tier.OPEN)
        d.log("research: ${cands.size} candidate(s) → ${outcome.javaClass.simpleName}")
        return when (outcome) {
            is Decide.Outcome.NoneFound -> Plan(note = "Research: a web search found no official site for \"$referent\". Tell the " +
                "user plainly and offer to search the web in the browser (open_intent web_search) — never guess an address.")
            is Decide.Outcome.Act -> {
                val label = Candidates.label(outcome.pick, referent)
                open(d, referent, outcome.pick.url, label, "Opening $label at ${SafeUrl.spokenHost(outcome.pick.host)}.", "research")
            }
            is Decide.Outcome.Ask -> ask(d, referent, outcome)
        }
    }

    private suspend fun ask(d: Deps, referent: String, outcome: Decide.Outcome.Ask): Plan {
        val labels = distinctLabels(outcome.options, referent)
        val listing = outcome.options.indices.joinToString("; ") { "${labels[it]} = ${outcome.options[it].url}" }
        val ask = d.ask ?: return Plan(note = "Research: several sites could be \"$referent\" ($listing) and the user isn't " +
            "here to choose — don't open any; say which ones fit.")
        References.noteOptions(labels)   // "the second one" also resolves later, at T0
        val question = SpokenOptions.question(labels, outcome.narrowing)
        val answer = ask(question, question)
            ?: return Plan(end = Plan.End(stopped = false, line = "I didn't get an answer, so I stopped. Ask me again when you're ready."))
        return when (val p = OptionAnswer.parse(answer, labels)) {
            is OptionAnswer.Pick.Chosen -> {
                val c = outcome.options[p.index]
                open(d, referent, c.url, labels[p.index], "Opening ${labels[p.index]}.", "asked")
            }
            OptionAnswer.Pick.Stop -> Plan(end = Plan.End(stopped = true, line = "Okay, I've left it."))
            OptionAnswer.Pick.None -> Plan(note = "The user was asked which site they meant for \"$referent\" ($listing) and said none " +
                "of those: \"$answer\". Ask what they meant, or offer a web search — don't open any of those.")
            OptionAnswer.Pick.Unclear -> Plan(note = "The user was asked which site they meant for \"$referent\" ($listing) and " +
                "answered: \"$answer\". Open the one they meant with open_intent open_url; if it's still unclear, ask once more.")
        }
    }

    private fun open(d: Deps, referent: String, url: String, label: String, spoken: String, source: String): Plan {
        val pkg = d.handlerFor(url)
        val call = AgentDecision.CallTool("open_intent", JSONObject().put("template", "open_url").put("url", url), TAG,
            expects = pkg?.let { "foreground_is: $it" })
        return Plan(open = call, pending = Pending(d.store, referent, url, label, source, spoken))
    }

    /** Labels read out as options must differ: a repeated one gets its host added ("UG Sakai at sakai dot u g…"). */
    private fun distinctLabels(options: List<Candidate>, referent: String): List<String> {
        val base = options.map { Candidates.label(it, referent) }
        return base.mapIndexed { i, l ->
            if (base.count { it.equals(l, ignoreCase = true) } > 1) "$l at ${SafeUrl.spokenHost(options[i].host)}" else l
        }
    }

    /** The phone side of [Deps]: the encrypted store, web search, installed apps, and the browser that opens a URL. */
    fun deps(ctx: Context, online: Boolean, ask: (suspend (String, String) -> String?)?, log: (String) -> Unit): Deps = Deps(
        store = ResolvedChoices.store(ctx),
        online = online,
        search = { q ->
            if (com.tekyerema.agent.search.WebSearch.providers(ctx).isEmpty()) null
            else com.tekyerema.agent.search.WebSearch.search(q, ctx)?.sources
        },
        isLocalApp = { ref ->
            try {
                com.tekyerema.android.AppResolver.match(com.tekyerema.android.AppResolver.launchable(ctx), ref, tolerant = false) is
                    com.tekyerema.android.AppResolver.Match.One
            } catch (_: Throwable) { false }
        },
        handlerFor = { url ->
            try {
                val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addCategory(android.content.Intent.CATEGORY_BROWSABLE)
                i.resolveActivity(ctx.packageManager)?.packageName?.takeIf { it != "android" }
            } catch (_: Throwable) { null }
        },
        ask = ask,
        log = log
    )
}
