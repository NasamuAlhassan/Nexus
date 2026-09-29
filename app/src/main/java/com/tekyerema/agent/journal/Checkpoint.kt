package com.tekyerema.agent.journal

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the loop needs to pick a task back up (research: agenticness_reliability_state, "Checkpoint"). Only what the
 * loop already holds in memory — nothing is re-derived:
 *  - the compacted step history (the planner's memory between stateless calls), bounded to [MAX_HISTORY] lines;
 *  - TaskMemory's fields (current app/screen, known entities, recent problems);
 *  - the last VERIFIED screen (app, title, signature) — on resume it answers "is the phone where I left it?", but the
 *    screen is always observed again first; the saved one is never acted on;
 *  - the loop counters that gate completion honestly (mutating/verified counts…), so a resumed Done is not graded on
 *    half the evidence;
 *  - third-party text read so far and where instruction-like text was seen (provenance, J-06/J-07), so a payload copied
 *    from a message before the pause is still treated as untrusted after it.
 * Deliberately NOT kept: approvals (a "yes" given before the pause is void — consequential steps are confirmed again),
 * queued tap_sequence steps, loop-guard transition counts.
 */
data class Checkpoint(
    val step: Int,
    val at: Long,
    val history: List<String>,
    val memory: JSONObject? = null,
    val lastVerifiedApp: String? = null,
    val lastVerifiedTitle: String? = null,
    /** [sigHash] of the last verified screen's signature (never the labels themselves). */
    val lastVerifiedSig: String? = null,
    val mutatingCount: Int = 0,
    val mutatingVerified: Int = 0,
    val partialCount: Int = 0,
    val lastMutatingVerified: Boolean? = null,
    val failStreak: Int = 0,
    val usedRead: Boolean = false,
    val usedTools: List<String> = emptyList(),
    val discoveredTools: List<String> = emptyList(),
    val untrustedRead: List<String> = emptyList(),
    val injectionSource: String? = null,
    /** Plain past-tense words for the verified state changes so far ("opened WhatsApp", "tapped “Ama”"). */
    val done: List<String> = emptyList()
) {
    fun toJson(): String = JSONObject()
        .put("v", 1).put("step", step).put("at", at)
        .put("history", JSONArray(history.takeLast(MAX_HISTORY).map { it.take(MAX_LINE) }))
        .put("memory", memory ?: JSONObject.NULL)
        .put("lastApp", lastVerifiedApp ?: JSONObject.NULL).put("lastTitle", lastVerifiedTitle ?: JSONObject.NULL)
        .put("lastSig", lastVerifiedSig ?: JSONObject.NULL)
        .put("mutating", mutatingCount).put("mutatingVerified", mutatingVerified).put("partial", partialCount)
        .put("lastMutatingVerified", lastMutatingVerified ?: JSONObject.NULL).put("failStreak", failStreak)
        .put("usedRead", usedRead)
        .put("usedTools", JSONArray(usedTools)).put("discoveredTools", JSONArray(discoveredTools))
        .put("untrusted", JSONArray(untrustedRead.takeLast(3).map { it.take(600) }))
        .put("injection", injectionSource ?: JSONObject.NULL)
        .put("done", JSONArray(done.takeLast(6)))
        .toString()

    companion object {
        const val MAX_HISTORY = 12
        const val MAX_LINE = 400

        /** A screen signature is long (every label); only its hash is needed to compare screens. */
        fun sigHash(sig: String): String = EffectLedger.sha(sig).take(16)

        private fun JSONObject.strOrNull(k: String): String? = if (has(k) && !isNull(k)) optString(k) else null
        private fun JSONArray?.strings(): List<String> = this?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()

        fun fromJson(s: String?): Checkpoint? {
            if (s.isNullOrBlank()) return null
            return try {
                val o = JSONObject(s)
                Checkpoint(
                    step = o.optInt("step"), at = o.optLong("at"),
                    history = o.optJSONArray("history").strings(),
                    memory = o.optJSONObject("memory"),
                    lastVerifiedApp = o.strOrNull("lastApp"), lastVerifiedTitle = o.strOrNull("lastTitle"),
                    lastVerifiedSig = o.strOrNull("lastSig"),
                    mutatingCount = o.optInt("mutating"), mutatingVerified = o.optInt("mutatingVerified"),
                    partialCount = o.optInt("partial"),
                    lastMutatingVerified = if (o.has("lastMutatingVerified") && !o.isNull("lastMutatingVerified"))
                        o.optBoolean("lastMutatingVerified") else null,
                    failStreak = o.optInt("failStreak"), usedRead = o.optBoolean("usedRead"),
                    usedTools = o.optJSONArray("usedTools").strings(), discoveredTools = o.optJSONArray("discoveredTools").strings(),
                    untrustedRead = o.optJSONArray("untrusted").strings(), injectionSource = o.strOrNull("injection"),
                    done = o.optJSONArray("done").strings()
                )
            } catch (_: Throwable) { null }
        }
    }
}
