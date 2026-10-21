package com.tekyerema.camera

import android.util.Log
import com.tekyerema.core.NetworkMonitor
import com.tekyerema.core.config.AppConfig
import com.tekyerema.settings.Settings

/*
 * Follow-up questions on the camera's last photo — the Be My AI pattern (vision_features.md §1 #3): the first
 * caption is what works, the chat about it ("what colour is the shirt?") is what users love. The photo the
 * describe mode just sent is HELD, in memory only, so a question asked moments later can go back to the cloud
 * with the SAME image. The pure parts ([LastCapture] expiry, [VisionLadder.plan], [VisionQa.isFollowUp]) are
 * unit-tested; the activity and LocalFastRouter only move bytes and speech.
 */

/**
 * The last JPEG sent for a description. Memory only — never written to disk — and short-lived on purpose: the
 * photo is the user's own room or hands. It is cleared when the camera screen closes (CameraReadActivity), and
 * expires by itself after `vision.qa.keepMs` in case that close never came.
 */
object LastCapture {
    @Volatile private var jpeg: ByteArray? = null
    @Volatile private var heldAt = 0L

    private fun configKeepMs(): Long = AppConfig.long("vision.qa.keepMs", 180_000L)

    /** The describe mode holds each photo the moment it is encoded. */
    fun hold(bytes: ByteArray, nowMs: Long = System.currentTimeMillis()) {
        jpeg = bytes
        heldAt = nowMs
    }

    /** The held photo, or null once it has expired (expiry drops the bytes too). */
    fun get(nowMs: Long = System.currentTimeMillis(), keepMs: Long = configKeepMs()): ByteArray? {
        val held = jpeg ?: return null
        if (nowMs - heldAt > keepMs) {
            clear()
            return null
        }
        return held
    }

    fun has(nowMs: Long = System.currentTimeMillis()): Boolean = get(nowMs) != null

    /** The camera screen closed, or the user's cloud consent changed: nothing to ask about any more. */
    fun clear() {
        jpeg = null
        heldAt = 0L
    }
}

/**
 * The cloud rungs for one photo, in order (vision_features.md §4): Gemini first (already wired, cheap image
 * tokens), Groq's vision model as the retry for keyless or quota-out days. The honest offline line stays with
 * the caller — this ladder only ever returns what a model actually said, or why it couldn't.
 */
object VisionLadder {
    private const val TAG = "Tekyerema"

    enum class Rung { GEMINI, GROQ }

    /** Which rungs are worth trying, in order. Pure, so the fallback order is unit-tested. */
    fun plan(geminiKey: String, groqKeys: List<String>,
             order: String = com.tekyerema.core.config.AppConfig.string("camera.vision.order", "groq,gemini")): List<Rung> {
        // Groq first (A20e, 21 Oct 2026: Groq answered a camera question in 1.35 s; Gemini missed its 7 s deadline);
        // `camera.vision.order` "gemini,groq" = the old order.
        val have = buildList {
            if (geminiKey.isNotBlank()) add(Rung.GEMINI)
            if (groqKeys.any { it.isNotBlank() }) add(Rung.GROQ)
        }
        val wanted = order.lowercase().split(',').mapNotNull { n -> Rung.values().firstOrNull { it.name.lowercase() == n.trim() } }
        return wanted.filter { it in have } + have.filter { it !in wanted }
    }

    /** Walk the plan until a rung answers. The caller has already checked the user's cloud consent (J-18).
     *  [structured] (P5-07): Gemini is asked for the JSON contract at low resolution; Groq gets the same prompt as text. */
    suspend fun describe(jpeg: ByteArray, prompt: String, settings: Settings, structured: Boolean = false): VisionResult {
        var last: VisionResult = VisionResult.Failed("no vision key")
        val rungs = plan(settings.geminiApiKey, settings.groqApiKeys)
        for ((i, rung) in rungs.withIndex()) {
            val call: suspend () -> VisionResult = {
                when (rung) {
                    Rung.GEMINI -> GeminiVision.describe(jpeg, settings.geminiApiKey,
                        com.tekyerema.agent.ModelRoles.model("vision", settings.model), prompt, structured)   // N-06
                    Rung.GROQ -> GroqVision.describe(jpeg, settings.groqApiKeys, settings.groqBaseUrl, prompt)
                }
            }
            // A slow first rung hands over instead of holding the user (`camera.vision.firstRungMs`, 20 Oct 2026).
            last = if (i < rungs.lastIndex)
                kotlinx.coroutines.withTimeoutOrNull(com.tekyerema.core.config.AppConfig.long("camera.vision.firstRungMs", 7_000L)) { call() }
                    ?: VisionResult.Failed("slower than the first-rung deadline")
            else call()
            if (last is VisionResult.Described) return last
            if (i < rungs.lastIndex)
                Log.w(TAG, "[CAMERA] $rung couldn't describe (${(last as VisionResult.Failed).reason}) → ${rungs[i + 1]}")
        }
        return last
    }
}

/**
 * The seam the fast path calls: does this turn read as a question about the held photo, and the answer for it.
 * The routing itself stays minimal — LocalFastRouter asks [isFollowUp] only AFTER every other fast rule declined
 * the words, and only while [LastCapture] holds a photo, so "what time is it" keeps its own answer.
 */
object VisionQa {
    private const val TAG = "Tekyerema"

    // A question shape, not an imperative — "call mama" and "open WhatsApp" must never go to the photo.
    private val RE_QUESTION = Regex("^(?:what|which|who|whose|where|when|is|are|was|were|does|do|did|" +
        "how (?:many|much|old|big|tall)|can you (?:see|tell|read|count))\\b.{2,120}$")
    // Words that make it a device or messaging matter even in question shape ("is my battery low",
    // "what's the weather"). A cloud image request costs real quota, so doubt goes to the agent, not the photo.
    private val RE_NOT_PHOTO = Regex("\\b(?:call(?:s|ed)?|phone|ring|dial|text(?:s|ed)?|sms|message(?:s|d)?|" +
        "send|sent|open|launch|play|pause|remind(?:er)?|alarm|timer|time|date|volume|brightness|screenshot|" +
        "weather|news|wifi|wi-fi|internet|bluetooth|battery|data|momo|balance|notification(?:s)?|email(?:s|ed)?)\\b")

    /** Pure: do these words read as a follow-up question about the held photo? The caller checks [LastCapture.has]. */
    fun isFollowUp(text: String): Boolean {
        val g = text.trim().lowercase().replace('’', '\'').replace(Regex("\\s+"), " ")
            .trimEnd('.', '!', '?').trim()
        if (g.isEmpty() || g.split(' ').size > 14) return false   // a long turn is a task, not a glance back
        return RE_QUESTION.matches(g) && !RE_NOT_PHOTO.containsMatchIn(g)
    }

    /**
     * Answer [question] with the held photo: Gemini → Groq → the honest line. Null means "not ours after all"
     * (the photo expired, or the user has since switched the cloud backup off) — the caller falls through to
     * the agent unchanged.
     */
    suspend fun ask(question: String, settings: Settings): String? {
        val jpeg = LastCapture.get() ?: return null
        // J-18 binds the follow-up exactly as it bound the describe: no consent, no pixels off the phone.
        if (!settings.allowGeminiBackup) {
            LastCapture.clear()
            return null
        }
        if (!NetworkMonitor.isOnline())
            return AppConfig.string("camera.phrases.qaOffline", "I can't check the photo without the internet.")
        return when (val r = VisionLadder.describe(jpeg, GeminiVision.qaPrompt(question), settings)) {
            is VisionResult.Described -> r.text
            is VisionResult.Failed -> {
                Log.w(TAG, "[CAMERA] follow-up failed: ${r.reason}")
                AppConfig.string("camera.phrases.qaFailed", "I couldn't get an answer about the photo just now.")
            }
        }
    }
}
