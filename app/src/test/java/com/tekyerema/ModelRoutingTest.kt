package com.tekyerema

import com.tekyerema.agent.AgentBrain
import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.BrainContext
import com.tekyerema.agent.FallbackBrain
import com.tekyerema.agent.ModelRoles
import com.tekyerema.agent.OpenAiCompatBrain
import com.tekyerema.agent.ProviderCooling
import com.tekyerema.agent.routing.ModelRouting
import com.tekyerema.agent.routing.QuotaAware
import com.tekyerema.agent.routing.RoutePolicy
import com.tekyerema.agent.routing.TaskRoute
import com.tekyerema.operator.OperatorPolicy
import com.tekyerema.operator.WeakModelPolicy
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Workstream B — quota-aware routing: pin per task, wait don't downgrade, Gemini before 20b, weak model only per policy. */
class ModelRoutingTest {

    @After fun resetPolicy() {
        OperatorPolicy.cloudAllowed = true
        OperatorPolicy.providers = listOf("groq", "gemini")
        OperatorPolicy.dailyCloudCallCap = 0
        OperatorPolicy.dailyTokenCap = 0
        OperatorPolicy.weakModelPolicy = WeakModelPolicy.ASK
    }

    private fun ctx(simple: Boolean = false, effort: String? = null, history: List<String> = emptyList()) =
        BrainContext("send Ama the photo on WhatsApp", JSONObject(), history, JSONArray(), JSONArray(), simple = simple, effort = effort)

    private val PACE = AgentDecision.Fail("The AI service is busy right now (every key cooling on this model).")
    private val DAILY = AgentDecision.Fail("The AI service is rate-limited right now (daily quota on every key). Please try again later.")
    private val GEMINI_DAILY = AgentDecision.Fail("Gemini HTTP 429: daily quota used up")
    private val ok = AgentDecision.CallTool("open_app", JSONObject().put("app", "WhatsApp"), null)

    /** A Groq-like brain: scripted capable answers, its own weak model, and a fake "ready in" clock. */
    private class FakeGroq(vararg script: AgentDecision, var readyIn: Long? = null, val weak: AgentDecision = AgentDecision.Done("weak answer"))
        : AgentBrain, QuotaAware {
        private val q = script.toList(); var calls = 0; var weakCalls = 0
        override suspend fun decide(ctx: BrainContext): AgentDecision = q[minOf(calls++, q.size - 1)]
        override fun capableReadyInMs(now: Long): Long? = readyIn
        override val weakModel: String? = "openai/gpt-oss-20b"
        override suspend fun decideWeak(ctx: BrainContext): AgentDecision { weakCalls++; return weak }
    }

    private class Script(vararg ds: AgentDecision) : AgentBrain {
        private val q = ds.toList(); var calls = 0
        override suspend fun decide(ctx: BrainContext): AgentDecision = q[minOf(calls++, q.size - 1)]
    }

    private fun fallback(vararg brains: AgentBrain, route: TaskRoute, slept: MutableList<Long> = ArrayList(), budget: Boolean = false) =
        FallbackBrain(brains.toList(), networkRetryDelayMs = 1, linkCheck = { null }, watchdogMs = 0, health = ProviderCooling { 90_000L },
            route = route, budgetExhausted = { budget }, sleep = { slept.add(it) }, clock = { 0L })

    private fun route(policy: WeakModelPolicy, unattended: Boolean = false) =
        TaskRoute(RoutePolicy(weakModelPolicy = policy, complexMaxWaitMs = 20_000, tpmResetMaxWaitMs = 60_000, maxWeakSteps = 5), unattended)

    // ---------------------------------------------------------------- the policy table (pure)

    @Test fun weak_model_policy_table() {
        fun d(p: WeakModelPolicy, effort: String? = null, hasWeak: Boolean = true, r: TaskRoute = route(p)) = ModelRouting.weakDecision(r, effort, hasWeak)
        assertEquals(ModelRouting.NO_CAPABLE_MODEL, (d(WeakModelPolicy.NEVER) as ModelRouting.Weak.Pause).reasonClass)
        assertEquals(ModelRouting.Weak.Use, d(WeakModelPolicy.ALLOW))
        assertEquals(ModelRouting.Weak.Ask, d(WeakModelPolicy.ASK))
        val asked = route(WeakModelPolicy.ASK).apply { weakAsked = true }
        assertTrue(d(WeakModelPolicy.ASK, r = asked) is ModelRouting.Weak.Pause)
        val approved = route(WeakModelPolicy.ASK).apply { weakApproved = true }
        assertEquals(ModelRouting.Weak.Use, d(WeakModelPolicy.ASK, r = approved))
        // Never for a recovery step, never past the degraded budget, never asking nobody, never without a weak model.
        assertTrue(d(WeakModelPolicy.ALLOW, effort = "high") is ModelRouting.Weak.Pause)
        assertTrue(d(WeakModelPolicy.ALLOW, r = route(WeakModelPolicy.ALLOW).apply { weakSteps = 5 }) is ModelRouting.Weak.Pause)
        assertTrue(d(WeakModelPolicy.ASK, r = route(WeakModelPolicy.ASK, unattended = true)) is ModelRouting.Weak.Pause)
        assertEquals(ModelRouting.QUOTA_EXHAUSTED, (d(WeakModelPolicy.ALLOW, hasWeak = false) as ModelRouting.Weak.Pause).reasonClass)
    }

    @Test fun the_users_answer_to_the_weak_model_question() {
        assertEquals(true, ModelRouting.weakAnswer("carry on"))
        assertEquals(true, ModelRouting.weakAnswer("yes use the smaller one"))
        assertEquals(false, ModelRouting.weakAnswer("pause until it's back"))
        assertEquals(false, ModelRouting.weakAnswer("no"))
        assertEquals(true, ModelRouting.weakAnswer("no problem, carry on"))
        assertEquals(false, ModelRouting.weakAnswer("carry on? no, wait"))
        assertNull(ModelRouting.weakAnswer("hmm"))
        val h = listOf("ask_user(\"${ModelRouting.ASK_QUESTION}\") → the user answered: \"carry on\"")
        assertEquals("carry on", ModelRouting.pendingAnswer(h))
        assertNull(ModelRouting.pendingAnswer(listOf("ask_user(\"Which Ama?\") → the user answered: \"Mensah\"")))
    }

    @Test fun cloud_off_and_provider_choice_shape_the_chain() {
        assertEquals(listOf("groq", "gemini"), ModelRouting.allowedChain(listOf("groq", "gemini"), listOf("gemini", "groq"), true))
        assertEquals(listOf("groq"), ModelRouting.allowedChain(listOf("groq", "gemini"), listOf("groq"), true))
        assertTrue(ModelRouting.allowedChain(listOf("groq", "gemini"), listOf("groq", "gemini"), false).isEmpty())
        OperatorPolicy.cloudAllowed = false
        assertTrue(ModelRoles.plannerChainAllowed().isEmpty())
        OperatorPolicy.cloudAllowed = true
        OperatorPolicy.providers = listOf("groq")
        assertEquals(listOf("groq"), ModelRoles.plannerChainAllowed())
    }

    @Test fun the_capable_models_never_include_the_weak_one() {
        assertEquals(listOf("openai/gpt-oss-120b"), OpenAiCompatBrain.capableOf("openai/gpt-oss-120b", listOf("openai/gpt-oss-20b"), listOf("openai/gpt-oss-20b")))
        assertEquals(listOf("big", "other-big"), OpenAiCompatBrain.capableOf("big", listOf("openai/gpt-oss-20b", "other-big"), listOf("openai/gpt-oss-20b")))
        assertEquals("openai/gpt-oss-20b", OpenAiCompatBrain.weakOf("openai/gpt-oss-120b", listOf("openai/gpt-oss-20b"), "openai/gpt-oss-20b", listOf("openai/gpt-oss-20b")))
        assertNull(OpenAiCompatBrain.weakOf("openai/gpt-oss-20b", emptyList(), "openai/gpt-oss-20b", listOf("openai/gpt-oss-20b")))
    }

    // ---------------------------------------------------------------- the chain under the policy

    @Test fun over_budget_nothing_goes_to_the_cloud() = runBlocking {
        val g = FakeGroq(ok)
        val d = fallback(g, route = route(WeakModelPolicy.ALLOW), budget = true).decide(ctx())
        assertEquals(ModelRouting.BUDGET_EXHAUSTED, (d as AgentDecision.CannotDo).reasonClass)
        assertEquals(0, g.calls)
    }

    @Test fun the_budget_is_counted_from_recorded_usage() = runBlocking {
        OperatorPolicy.dailyCloudCallCap = 1_000_000
        val (before, _) = OperatorPolicy.usageToday()
        OperatorPolicy.dailyCloudCallCap = before + 2
        OperatorPolicy.recordUsage("groq", 5000); assertFalse(OperatorPolicy.budgetExhausted())
        OperatorPolicy.recordUsage("groq", 5000); assertTrue(OperatorPolicy.budgetExhausted())
        val d = FallbackBrain(listOf(FakeGroq(ok)), networkRetryDelayMs = 1, linkCheck = { null }, watchdogMs = 0,
            health = ProviderCooling { 90_000L }, route = route(WeakModelPolicy.ALLOW)).decide(ctx())   // the real OperatorPolicy check
        assertEquals(ModelRouting.BUDGET_EXHAUSTED, (d as AgentDecision.CannotDo).reasonClass)
    }

    @Test fun the_first_provider_that_answers_is_pinned_for_the_task() = runBlocking {
        val r = route(WeakModelPolicy.NEVER)
        val groq = FakeGroq(DAILY, DAILY)              // 120b out for the day
        val gemini = Script(ok, ok)
        val fb = fallback(groq, gemini, route = r)
        assertEquals(ok, fb.decide(ctx()))
        assertEquals(1, r.pinnedIndex)
        assertEquals("script", r.pinnedName)   // the chain entry's name ("groq" / "gemini" for the real brains)
        fb.decide(ctx())
        assertEquals("the pinned provider goes first", 1, groq.calls)
        assertEquals(2, gemini.calls)
    }

    @Test fun a_per_minute_limit_is_waited_out_before_any_downgrade() = runBlocking {
        val r = route(WeakModelPolicy.ALLOW)
        val groq = FakeGroq(PACE, ok, readyIn = 30_000L)
        val gemini = Script(GEMINI_DAILY)
        val slept = ArrayList<Long>()
        val d = fallback(groq, gemini, route = r, slept = slept).decide(ctx())
        assertEquals(ok, d)
        assertEquals(listOf(30_250L), slept)
        assertEquals(0, groq.weakCalls)
        assertFalse(r.degraded)
    }

    @Test fun gemini_is_tried_before_the_weak_model_on_a_complex_step() = runBlocking {
        val groq = FakeGroq(DAILY)
        val gemini = Script(ok)
        assertEquals(ok, fallback(groq, gemini, route = route(WeakModelPolicy.ALLOW)).decide(ctx()))
        assertEquals(0, groq.weakCalls)
    }

    @Test fun never_policy_pauses_instead_of_using_the_weak_model() = runBlocking {
        val groq = FakeGroq(DAILY)
        val d = fallback(groq, Script(GEMINI_DAILY), route = route(WeakModelPolicy.NEVER)).decide(ctx())
        assertEquals(ModelRouting.NO_CAPABLE_MODEL, (d as AgentDecision.CannotDo).reasonClass)
        assertEquals(0, groq.weakCalls)
    }

    @Test fun ask_policy_asks_once_and_carries_on_or_pauses_on_the_answer() = runBlocking {
        val r = route(WeakModelPolicy.ASK)
        val groq = FakeGroq(DAILY)
        val fb = fallback(groq, Script(GEMINI_DAILY), route = r)
        val ask = fb.decide(ctx()) as AgentDecision.CallTool
        assertEquals("ask_user", ask.name)
        assertEquals(ModelRouting.ASK_QUESTION, ask.args.getString("question"))
        assertEquals(ModelRouting.ASK_TAG, ask.rationale)
        val answered = listOf("ask_user(\"${ModelRouting.ASK_QUESTION}\") → the user answered: \"carry on\"")
        assertEquals(AgentDecision.Done("weak answer"), fb.decide(ctx(history = answered)))
        assertTrue(r.weakApproved && r.degraded)
        assertEquals(1, groq.weakCalls)
        assertTrue(r.disclosure().contains("smaller backup AI"))
        assertEquals("", r.disclosure())   // said once
        // Another task, where the user says pause.
        val r2 = route(WeakModelPolicy.ASK)
        val fb2 = fallback(FakeGroq(DAILY), Script(GEMINI_DAILY), route = r2)
        fb2.decide(ctx())
        val paused = fb2.decide(ctx(history = listOf("ask_user(\"${ModelRouting.ASK_QUESTION}\") → the user answered: \"pause until it's back\"")))
        assertEquals(ModelRouting.NO_CAPABLE_MODEL, (paused as AgentDecision.CannotDo).reasonClass)
    }

    @Test fun allow_policy_carries_on_but_never_for_a_recovery_step() = runBlocking {
        val r = route(WeakModelPolicy.ALLOW)
        val groq = FakeGroq(DAILY)
        val fb = fallback(groq, Script(GEMINI_DAILY), route = r)
        assertEquals(AgentDecision.Done("weak answer"), fb.decide(ctx()))
        assertTrue(r.degraded)
        val d = fb.decide(ctx(effort = "high"))
        assertEquals(ModelRouting.NO_CAPABLE_MODEL, (d as AgentDecision.CannotDo).reasonClass)
        assertEquals(1, groq.weakCalls)
    }

    @Test fun simple_steps_keep_the_old_chain_and_a_quota_miss_is_quota_exhausted() = runBlocking {
        val d = fallback(Script(PACE), route = route(WeakModelPolicy.NEVER)).decide(ctx(simple = true))
        assertEquals(ModelRouting.QUOTA_EXHAUSTED, (d as AgentDecision.CannotDo).reasonClass)
        // Without a route the chain behaves exactly as before (a plain Fail).
        val old = FallbackBrain(listOf(Script(PACE)), networkRetryDelayMs = 1, linkCheck = { null }, watchdogMs = 0).decide(ctx())
        assertTrue(old is AgentDecision.Fail)
    }

    @Test fun network_failures_are_still_network_failures_under_routing() = runBlocking {
        val dns = AgentDecision.Fail("LLM request failed: Unable to resolve host \"api.groq.com\"")
        val d = fallback(Script(dns), route = route(WeakModelPolicy.ALLOW)).decide(ctx())
        assertEquals(FallbackBrain.NETWORK_LOST, (d as AgentDecision.CannotDo).reasonClass)
    }
}
