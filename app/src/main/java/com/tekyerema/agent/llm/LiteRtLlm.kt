package com.tekyerema.agent.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.tekyerema.agent.OnDeviceLlm
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * The on-device planner's runtime: Google's LiteRT-LM running a `.litertlm` model from the app's files (never the APK) on
 * the CPU — arm64 phones and the x86_64 emulator alike. One engine per process, loaded on the first request (seconds), on
 * one dedicated thread that owns it, as LiteRT-LM requires; a conversation per planner step, so every step sees exactly the
 * benchmark's single-turn prompt. Decoding is greedy, and constrained to the step's reply schema when one is given.
 * A timeout cancels the step (null: the router says it couldn't). Logs carry timings and sizes, never prompt text.
 */
class LiteRtLlm(context: Context, private val modelPath: String) : OnDeviceLlm {
    override val id: String = File(modelPath).nameWithoutExtension
    private val cacheDir = File(context.cacheDir, "litertlm").apply { mkdirs() }.absolutePath
    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "tekyerema-llm") }.asCoroutineDispatcher()
    @Volatile private var engine: Engine? = null
    @Volatile private var current: Conversation? = null

    private fun engine(): Engine {
        engine?.let { return it }
        val t0 = System.currentTimeMillis()
        val e = Engine(EngineConfig(modelPath = modelPath, backend = Backend.CPU(), cacheDir = cacheDir))
        e.initialize()
        Log.i(TAG, "[LLM] loaded $id in ${System.currentTimeMillis() - t0} ms")
        engine = e
        return e
    }

    override suspend fun complete(prompt: String, maxTokens: Int, timeoutMs: Long): String? =
        chat("", prompt, null, maxTokens, timeoutMs)

    override suspend fun chat(system: String, user: String, jsonSchema: String?, maxTokens: Int, timeoutMs: Long): String? {
        // The generation blocks the model's own thread, so a coroutine timeout can't interrupt it: a watchdog on another
        // thread cancels the native process instead, and the blocked call then returns.
        var timedOut = false
        val watchdog = timer.schedule({ timedOut = true; cancel() }, timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        try {
            return withContext(thread) {
                try {
                    val c = engine().createConversation(ConversationConfig(
                        systemInstruction = if (system.isBlank()) null else Contents.of(system),
                        samplerConfig = SamplerConfig(1, 1.0, 0.0, 0),
                        maxOutputToken = maxTokens,
                            thinkingConfig = ThinkingConfig(false, 0),
                        enableResponseFormat = jsonSchema != null
                    ))
                    current = c
                    try {
                        val reply = c.sendMessage(user, responseFormat = jsonSchema?.let { ResponseFormat.json(it) })
                        if (timedOut) null else reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                    } finally {
                        current = null
                        c.close()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "[LLM] $id ${if (timedOut) "timed out after $timeoutMs ms" else "failed: ${t.javaClass.simpleName}"}")
                    null
                }
            }
        } finally {
            watchdog.cancel(false)
        }
    }

    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tekyerema-llm-watchdog").apply { isDaemon = true } }

    /** One task's conversation: the system prompt is read once, every [LlmSession.send] adds one turn on top of what the
     *  engine already holds (LiteRT-LM keeps the conversation's KV between turns). Created lazily on the model's thread. */
    override fun openSession(system: String, maxTokens: Int): com.tekyerema.agent.LlmSession = object : com.tekyerema.agent.LlmSession {
        private var conv: Conversation? = null
        @Volatile private var held = 0
        override val tokens: Int get() = held

        override suspend fun send(user: String, jsonSchema: String?, timeoutMs: Long): String? {
            var timedOut = false
            var watchdog: java.util.concurrent.ScheduledFuture<*>? = null
            try {
                return withContext(thread) {
                    try {
                        val e = engine()
                        // The step's clock starts once the model is loaded: a load is not a step (emulator, 1 Nov 2026: a
                        // 5-minute load ate the step's whole budget and the cancel landed mid-generation).
                        watchdog = timer.schedule({ timedOut = true; Log.w(TAG, "[LLM] $id watchdog: cancelling after $timeoutMs ms"); cancel() },
                            timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                        val t0 = System.currentTimeMillis()
                        val c = conv ?: e.createConversation(ConversationConfig(
                            systemInstruction = Contents.of(system),
                            samplerConfig = SamplerConfig(1, 1.0, 0.0, 0),
                            maxOutputToken = maxTokens,
                            thinkingConfig = ThinkingConfig(false, 0),
                            enableResponseFormat = jsonSchema != null
                        )).also { conv = it; Log.i(TAG, "[LLM] $id conversation opened in ${System.currentTimeMillis() - t0} ms") }
                        current = c
                        val t1 = System.currentTimeMillis()
                        Log.i(TAG, "[LLM] $id sending a ${user.length}-char turn (schema=${jsonSchema != null})")
                        val reply = c.sendMessage(user, responseFormat = jsonSchema?.let { ResponseFormat.json(it) })
                        held = try { c.getTokenCount() } catch (_: Throwable) { held + (user.length + 200) / 4 }
                        Log.i(TAG, "[LLM] $id reply in ${System.currentTimeMillis() - t1} ms, conversation holds $held tokens")
                        val text = reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                        // Debug builds: the reply's start, so an unparsed answer explains itself (a model reply, never user data).
                        if (com.tekyerema.BuildConfig.DEBUG) Log.i(TAG, "[LLM] $id reply: " + text.take(160).replace('\n', ' '))
                        if (timedOut) null else text
                    } catch (t: Throwable) {
                        Log.w(TAG, "[LLM] $id ${if (timedOut) "timed out after $timeoutMs ms" else "failed: ${t.javaClass.simpleName}: ${t.message?.take(160)}"}")
                        null
                    } finally {
                        current = null
                    }
                }
            } finally {
                watchdog?.cancel(false)
            }
        }

        override fun close() {
            val c = conv ?: return
            conv = null
            // Closed on the model's thread, after any step still in flight there.
            thread.executor.execute { try { c.close() } catch (_: Throwable) {} }
        }
    }

    /** "Stop" from the user: abandon the step in flight. */
    fun cancel() = try { current?.cancelProcess() } catch (_: Throwable) {}

    /** Memory pressure: free the engine; the next request loads it again. */
    fun release() {
        try { current?.cancelProcess() } catch (_: Throwable) {}
        try { engine?.close() } catch (_: Throwable) {}
        engine = null
    }

    companion object { private const val TAG = "Tekyerema" }
}
