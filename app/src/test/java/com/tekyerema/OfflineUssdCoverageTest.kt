package com.tekyerema

import com.tekyerema.agent.AgentDecision
import com.tekyerema.core.config.AppConfig
import com.tekyerema.money.MoneyPlan
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Coverage of the on-phone money plan (owner feedback, 27 Oct 2026: the money flows must not wait for the internet). Prints, for
 * everyday wordings, whether the phone decides ([MoneyPlan.decide], no model) or the request would go to the cloud planner. It
 * only reports; the pinned behaviour lives in MoneyFlowTest / TelcoGuideTest.
 */
class OfflineUssdCoverageTest {
    @Before fun setUp() = AppConfig.loadFrom(emptyList())
    @After fun tearDown() = AppConfig.loadFrom(emptyList())

    private val groups = mapOf(
        "balance" to listOf("what's my momo balance", "check my balance", "how much money do I have", "momo balance", "my balance",
            "check my mobile money balance", "what is my balance"),
        "airtime balance" to listOf("check my airtime balance", "how much airtime do I have", "airtime balance", "how much credit do I have"),
        "data" to listOf("how much data do I have left", "check my data", "data balance", "buy a data bundle", "buy 5 cedis data",
            "buy 1gb data", "I want to buy data", "buy me some data"),
        "mashup" to listOf("buy mashup", "mashup 10 cedis", "buy 10 cedis mashup", "I want a mashup bundle"),
        "airtime" to listOf("buy 5 cedis airtime", "top up 10 cedis", "recharge my phone with 5 cedis", "buy airtime", "buy airtime for my wife",
            "load 2 cedis credit"),
        "send" to listOf("send 20 cedis to my wife", "send money to Ama", "transfer 50 to Kofi", "send 10 cedis", "pay Kofi 5 cedis"),
        "other" to listOf("what is my mtn number", "show my last transactions", "did it go through", "cash out 50 cedis", "pay a bill",
            "borrow airtime", "I want a loan", "check my offers", "what are my offers", "cancel my last transaction")
    )

    @Test fun report() {
        val miss = mutableListOf<String>()
        for ((g, goals) in groups) for (goal in goals) {
            val d = try { com.tekyerema.money.TelcoGuide.decide(goal, emptyList(), null, "mtn") ?: MoneyPlan.decide(goal, emptyList()) } catch (t: Throwable) { null }
            val what = when (d) { null -> "-> CLOUD"; is AgentDecision.CallTool -> "local ${d.name} ${d.args.toString().take(70)}"; else -> "local ${d::class.simpleName}" }
            println("COVER [$g] \"$goal\" $what")
            if (d == null) miss += goal
        }
        println("COVER MISSES (${miss.size}): $miss")
    }
}
