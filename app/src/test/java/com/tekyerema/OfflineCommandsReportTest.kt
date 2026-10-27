package com.tekyerema

import com.tekyerema.agent.AgentBrain
import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.BrainContext
import com.tekyerema.agent.FastIntentClassifier
import com.tekyerema.agent.FallbackBrain
import com.tekyerema.agent.LocalBrain
import com.tekyerema.agent.RoutingBrain
import com.tekyerema.core.config.AppConfig
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Which spoken English commands the phone handles with the internet OFF (owner, 27 Oct 2026: "sample commands that will work
 * fully offline"). Each line is tried two ways: the instant fast path (FastIntentClassifier, no model, no network) and, if that
 * does not take it, the offline router with a cloud that is down (RoutingBrain, online = false). It only reports; the list the
 * owner gets is taken from the lines marked LOCAL. Hearing and speaking offline are separate (on-device ASR / voice).
 */
class OfflineCommandsReportTest {
    private class DeadCloud : AgentBrain {
        override suspend fun decide(ctx: BrainContext): AgentDecision = AgentDecision.CannotDo(FallbackBrain.NETWORK_LOST, "no internet")
    }
    @Before fun setUp() = AppConfig.loadFrom(emptyList())
    @After fun tearDown() = AppConfig.loadFrom(emptyList())

    private val groups = linkedMapOf(
        "phone" to listOf("what time is it", "what's the date today", "what day is it", "how much battery do I have", "how much storage do I have",
            "turn the volume up", "volume down", "mute the phone", "turn on the flashlight", "turn off the flashlight", "turn on wifi",
            "turn on bluetooth", "make the screen brighter", "take a screenshot", "lock the screen", "go back", "go home", "show recent apps",
            "scroll down", "where am I"),
        "apps" to listOf("open WhatsApp", "open settings", "open the camera", "open YouTube"),
        "calls and texts" to listOf("call my wife", "call 0552093690", "send a text to my wife saying I'm on my way", "who called me",
            "show my missed calls", "read my last text", "answer the call", "end the call"),
        "reading" to listOf("read my screen", "what does it say", "read this to me", "read my notifications", "read my messages"),
        "time" to listOf("set a timer for 5 minutes", "set an alarm for 7 am", "show my alarms", "stop the timer"),
        "money" to listOf("what's my momo balance", "check my airtime balance", "buy 5 cedis airtime", "buy a data bundle", "buy mashup",
            "check my data balance", "send 20 cedis to my wife", "did my last transfer go through"),
        "camera" to listOf("how much am I holding", "take a photo", "take a selfie"),
        "small answers" to listOf("what is 25 times 4", "spell cedi", "what's the time in London", "play music", "pause the music", "next song"),
        "needs internet" to listOf("what is the weather today", "who is the president of Ghana", "search the web for waakye", "translate hello into Twi")
    )

    @Test fun report() = runBlocking {
        for ((g, lines) in groups) for (text in lines) {
            val fast = try { FastIntentClassifier.classify(text) } catch (t: Throwable) { null }
            val line = if (fast != null) "LOCAL fast-path ${fast::class.simpleName}" else {
                val d = try { RoutingBrain(LocalBrain(), DeadCloud()) { false }.decide(BrainContext(text, JSONObject().put("app", "com.android.launcher").put("elements", JSONArray()), emptyList(), JSONArray(), JSONArray())) } catch (t: Throwable) { null }
                when (d) {
                    is AgentDecision.CallTool -> "LOCAL tool ${d.name} " + d.args.toString().take(60)
                    is AgentDecision.Done -> "ANSWER: " + d.summary.take(90)
                    is AgentDecision.CannotDo -> "NO (${d.reasonClass})"
                    null -> "NO (error)"
                    else -> "NO ($d)".take(60)
                }
            }
            println("OFFLINE [$g] \"$text\" -> $line")
        }
    }
}
