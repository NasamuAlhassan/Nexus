package com.tekyerema

import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.BrainContext
import com.tekyerema.agent.DirectAnswer
import com.tekyerema.agent.FastIntentClassifier
import com.tekyerema.agent.LocalBrain
import com.tekyerema.agent.LogEntry
import com.tekyerema.agent.RunStats
import com.tekyerema.agent.ScreenReadIntent
import com.tekyerema.agent.TestScenario
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [E] The device suite (scripts/device_suite.py + scripts/device_scenarios.json) scores logcat on the phone. These
 * checks keep its expectations honest OFF the phone, against the same pure routers the app runs:
 *  - a scenario that expects the fast path is classified to that intent by [FastIntentClassifier];
 *  - one that expects the agent (or a direct answer, or an on-device screen read) is NOT grabbed by the fast path,
 *    and [DirectAnswer] / [ScreenReadIntent] / [LocalBrain] agree with the route it expects;
 *  - the log formats the runner parses ([RunStats.summary], the [LogEntry.Kind] names) still exist.
 * A drift then fails here in seconds, not as a confusing device failure. Scenarios marked `known_issue` document a
 * bug on purpose and are skipped.
 */
class DeviceScenarioOracleTest {

    private val doc: JSONObject by lazy {
        val f = listOf("../scripts/device_scenarios.json", "scripts/device_scenarios.json").map(::File).firstOrNull { it.isFile }
            ?: error("scripts/device_scenarios.json not found from ${File(".").absolutePath}")
        JSONObject(f.readText())
    }

    private fun scenarios(): List<JSONObject> {
        val arr = doc.getJSONArray("scenarios")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    /** Typed-request scenarios that describe current behaviour (placeholders and known bugs excluded). */
    private fun typed(): List<JSONObject> = scenarios().filter {
        it.optString("request").isNotBlank() && !it.has("known_issue") && !it.optString("request").contains("{")
    }

    private fun routes(sc: JSONObject): List<String> {
        val r = sc.optJSONObject("expect")?.opt("route") ?: return emptyList()
        return if (r is JSONArray) (0 until r.length()).map { r.getString(it) } else listOf(r.toString())
    }

    private fun strings(sc: JSONObject, key: String): List<String> {
        val v = sc.optJSONObject("expect")?.opt(key) ?: return emptyList()
        return if (v is JSONArray) (0 until v.length()).map { v.getString(it) } else listOf(v.toString())
    }

    /**
     * The phone classifies with Android's ICU regex engine, where `\b` is Unicode-aware; these tests run on JDK 19+,
     * where `\b` follows ASCII `\w` (JDK-8264160) — so "san kɔ" (`k[ɔo]\b`) is BACK on the phone but unmatched here.
     * The app's Twi patterns accept the plain vowel too (`[ɔo]`, `[ɛe]`), so a phrase is also tried with ɔ→o / ɛ→e,
     * which is what ICU's word boundary sees as equivalent.
     */
    private fun classifyLikeAndroid(req: String): com.tekyerema.agent.FastIntent? =
        FastIntentClassifier.classify(req) ?: FastIntentClassifier.classify(
            req.replace('ɔ', 'o').replace('Ɔ', 'O').replace('ɛ', 'e').replace('Ɛ', 'E'))

    private fun localDecision(goal: String): AgentDecision {
        val screen = JSONObject().put("app", "com.android.settings").put("appName", "Settings")
            .put("perception", JSONObject().put("source", "tree")).put("elements", JSONArray())
        return runBlocking { LocalBrain().decide(BrainContext(goal, screen, emptyList(), JSONArray(), JSONArray())) }
    }

    @Test fun scenario_file_has_every_category_and_enough_scenarios() {
        val cats = scenarios().map { it.getString("category") }.toSet()
        for (c in listOf("fast_path", "screen_read", "direct_question", "web_search", "navigation", "prompts", "safety",
                "step_aside", "multi_app", "twi", "infra", "consequential"))
            assertTrue("no scenario in category $c", c in cats)
        assertTrue(scenarios().count { it.getString("safety") == "safe" } >= 40)
    }

    @Test fun fast_path_expectations_match_the_classifier() {
        val checked = typed().filter { "fast" in routes(it) }.onEach { sc ->
            val req = sc.getString("request")
            val want = sc.getJSONObject("expect").optString("fast_intent", ".+")
            val got = classifyLikeAndroid(req)?.name
            assertTrue("${sc.getString("id")}: '$req' classified as $got, the scenario expects fast intent $want",
                got != null && Regex(want).matches(got))
        }
        assertTrue(checked.size >= 20)
    }

    @Test fun agent_expectations_are_not_grabbed_by_the_fast_path() {
        val agentish = setOf("agent", "not_fast", "direct", "local", "cloud")
        val checked = typed().filter { sc ->
            routes(sc).any { it in agentish } ||
                (routes(sc).isEmpty() && (strings(sc, "tools_used") + strings(sc, "tools_any")).isNotEmpty())
        }.onEach { sc ->
            val req = sc.getString("request")
            val got = classifyLikeAndroid(req)
            assertEquals("${sc.getString("id")}: '$req' would be taken by the fast path as ${got?.name}", null, got)
        }
        assertTrue(checked.size >= 15)
    }

    @Test fun direct_expectations_match_the_direct_answer_router() {
        for (sc in typed()) {
            val req = sc.getString("request")
            val direct = DirectAnswer.isKnowledgeQuestion(req)
            if ("direct" in routes(sc)) assertTrue("${sc.getString("id")}: '$req' is not a direct question", direct)
            // A screen read or an on-device step must never be answered without looking.
            if ("local" in routes(sc) || "read_screen" in strings(sc, "tools_used"))
                assertTrue("${sc.getString("id")}: '$req' would be answered without looking at the screen", !direct)
        }
    }

    @Test fun on_device_expectations_are_handled_by_the_local_brain() {
        // "no [ROUTE] line" means the on-device brain takes the first step itself instead of deferring to the cloud.
        val onDevice = typed().filter { sc -> strings(sc, "log_absent_regex").any { it.contains("ROUTE") } && sc.getString("category") != "safety" }
        assertTrue("too few on-device scenarios: ${onDevice.size}", onDevice.size >= 4)
        for (sc in onDevice) {
            val req = sc.getString("request")
            val want = strings(sc, "tools_used") + strings(sc, "tools_any")
            val d = localDecision(req)
            assertTrue("${sc.getString("id")}: LocalBrain deferred '$req' ($d)", d is AgentDecision.CallTool)
            if (want.isNotEmpty()) assertTrue("${sc.getString("id")}: LocalBrain chose ${(d as AgentDecision.CallTool).name}, want one of $want",
                (d as AgentDecision.CallTool).name in want)
            if ("read_screen" in want) assertTrue("${sc.getString("id")}: '$req' is not a screen-read phrasing",
                ScreenReadIntent.isRead(req) || ScreenReadIntent.isContinue(req))
        }
    }

    @Test fun infra_scenarios_name_real_test_scenarios() {
        val names = TestScenario.entries.map { it.name }.toSet()
        for (sc in scenarios().filter { it.has("scenario") })
            assertTrue("${sc.getString("id")}: no TestScenario ${sc.getString("scenario")}", sc.getString("scenario") in names)
    }

    @Test fun run_stats_summary_keeps_the_format_the_runner_parses() {
        // scripts/device_suite.py parse_stats(): key=value tokens, verified=a/b, decideMs(avg/max)=x/y, maps in {…}.
        val s = RunStats(steps = 4, actions = 3, verifiedTrue = 2, verifiedFalse = 1, confirmations = 1,
            decideLatencyMs = listOf(100L, 300L), channels = mapOf("node" to 2)).summary()
        for (re in listOf("\\bsteps=4\\b", "\\bactions=3\\b", "\\bverified=2/3\\b", "\\bconfirmations=1\\b",
                "decideMs\\(avg/max\\)=200/300", "channels=\\{node=2\\}", "\\bblocked=0\\b"))
            assertTrue("[STATS] format changed: '$s' lacks /$re/", Regex(re).containsMatchIn(s))
        assertTrue(Regex("decideMs\\(avg/max\\)=0/0").containsMatchIn(RunStats().summary()))
    }

    @Test fun log_kinds_the_runner_parses_exist() {
        val kinds = LogEntry.Kind.entries.map { it.name }.toSet()
        for (k in listOf("USER", "PLAN", "ACTION", "VERIFY", "POLICY", "CONFIRM", "RESULT", "ERROR", "INFO", "STATS"))
            assertTrue("LogEntry.Kind.$k is gone — scripts/device_suite.py classify() reads it", k in kinds)
    }
}
