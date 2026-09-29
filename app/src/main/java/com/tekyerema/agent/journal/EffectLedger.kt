package com.tekyerema.agent.journal

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * CONTRACT (docs/agent/AGENTIC_ARCHITECTURE.md §Effects) — every consequential action (send, call, pay, install,
 * delete, account change) is written here BEFORE it runs, so a send whose confirmation never came back is known as
 * "maybe sent" across tasks and process restarts, and is checked in the world before anything is redone.
 *
 * Workstream A: one encrypted file (`filesDir/tasks/effects.enc`, [com.tekyerema.core.SecureStore]). [intend] writes the
 * INTENDED row and fsyncs it before it returns — the engine calls it immediately before the tool runs, so a crash
 * between the tap and the next write still leaves "this may have happened" on disk. On the next cold start ([load]) an
 * INTENDED or DISPATCHED row is UNKNOWN until reconciled with the world ([WorldCheck]). This replaces the per-task,
 * in-memory D-09 set and G-21 "check first" set in AgentEngine (audit §3 gaps 1–4): the key is the CONSEQUENCE —
 * kind + normalized target + payload hash — not `tool + args.toString()`, so a GUI send and a re-worded retry map to the
 * same row, and it survives the task and the process.
 *
 * Privacy: the payload is never stored, only its hash; [Effect.label] (who it went to, as the user would say it) is
 * encrypted at rest like every other file that holds a person's words. Rows expire after `agent.effects.retentionMs`.
 */
enum class EffectKind { SMS, CHAT_MESSAGE, CALL, PAYMENT, INSTALL, DELETE, ACCOUNT, OTHER }

enum class EffectState { INTENDED, DISPATCHED, CONFIRMED, FAILED, UNKNOWN }

data class Effect(
    val id: String,
    val taskId: String?,
    val kind: EffectKind,
    /** Who/what it acts on: a number, a contact, a chat title, a package. */
    val target: String,
    /** A hash of the normalized payload (never the payload itself). */
    val payloadHash: String,
    val state: EffectState,
    val at: Long,
    val evidence: String? = null,
    // ---- Workstream A additions ----
    /** How to name the target to the user ("Ama") — the normalized [target] is for matching only. */
    val label: String? = null,
    /** The tool that carried it out ("send_sms", "tap"…), for the audit trail. */
    val tool: String? = null,
    val updatedAt: Long = at
)

object EffectLedger {
    private const val FILE_NAME = "effects.enc"
    private const val MAX_ROWS = 200
    private val lock = Any()
    private val rows = ArrayList<Effect>()
    @Volatile private var file: File? = null
    @Volatile private var counter = 0

    /** False when the last write could not reach the disk (no Keystore key): the row is then in memory only. */
    @Volatile var lastWriteDurable: Boolean = true; private set

    /** The dedupe window: the same consequence within this long is "already done" (default 30 minutes). */
    fun dedupeWindowMs(): Long = com.tekyerema.core.config.AppConfig.long("agent.effects.dedupeWindowMs", 30 * 60_000L)
    private fun retentionMs(): Long = com.tekyerema.core.config.AppConfig.long("agent.effects.retentionMs", 48 * 3_600_000L)

    fun normalizeTarget(t: String): String = t.lowercase().filter { it.isLetterOrDigit() }

    /** Case, punctuation and spacing don't make a different message ("See you at 6." = "see you at 6"). */
    fun normalizePayload(p: String): String =
        p.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(' ').filter { it.isNotEmpty() }.joinToString(" ")

    fun hash(payload: String): String = sha(normalizePayload(payload)).take(24)

    fun sha(s: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** SMS and a chat message are the same consequence to the person receiving it (a native SMS vs the Messages app). */
    fun family(k: EffectKind): EffectKind = if (k == EffectKind.SMS) EffectKind.CHAT_MESSAGE else k

    /**
     * Cold start: read the ledger from [dir]. A row still INTENDED or DISPATCHED was cut off mid-flight (the tap may have
     * landed before the process died) → UNKNOWN, reconciled before anything is redone. Old rows expire.
     */
    fun load(dir: File, now: Long = System.currentTimeMillis()): Int = synchronized(lock) {
        file = File(dir, FILE_NAME)
        rows.clear()
        com.tekyerema.core.SecureStore.read(file!!)?.let { rows.addAll(parse(it)) }
        var unknown = 0
        for (i in rows.indices) if (rows[i].state == EffectState.INTENDED || rows[i].state == EffectState.DISPATCHED) {
            rows[i] = rows[i].copy(state = EffectState.UNKNOWN, updatedAt = now,
                evidence = (rows[i].evidence?.let { "$it; " } ?: "") + "the app stopped before the outcome was known")
            unknown++
        }
        pruneLocked(now)
        persistLocked()
        unknown
    }

    /** Record that [kind] on [target] with [payload] is about to happen — synced to disk before this returns. */
    fun intend(kind: EffectKind, target: String, payload: String, taskId: String?, now: Long = System.currentTimeMillis()): Effect =
        intend(kind, target, payload, taskId, now, label = null, tool = null)

    fun intend(kind: EffectKind, target: String, payload: String, taskId: String?, now: Long, label: String?, tool: String?): Effect =
        synchronized(lock) {
            val e = Effect("e$now-${counter++}", taskId, kind, normalizeTarget(target), hash(payload), EffectState.INTENDED, now,
                label = label?.take(60), tool = tool, updatedAt = now)
            rows.add(e)
            pruneLocked(now)
            persistLocked()
            e
        }

    fun mark(id: String, state: EffectState, evidence: String? = null) = synchronized(lock) {
        val i = rows.indexOfFirst { it.id == id }
        if (i >= 0) {
            rows[i] = rows[i].copy(state = state, evidence = evidence?.take(160) ?: rows[i].evidence, updatedAt = System.currentTimeMillis())
            persistLocked()
        }
    }

    /** The same consequence (kind + target + payload) done or possibly done within [withinMs], or null. */
    fun recentSame(kind: EffectKind, target: String, payload: String, withinMs: Long, now: Long = System.currentTimeMillis()): Effect? =
        recentSameHash(kind, normalizeTarget(target), hash(payload), withinMs, now)

    /** As [recentSame], with the target already normalized and the payload already hashed. SMS ≡ chat message. */
    fun recentSameHash(kind: EffectKind, normTarget: String, payloadHash: String, withinMs: Long, now: Long): Effect? =
        synchronized(lock) {
            rows.lastOrNull {
                family(it.kind) == family(kind) && it.target == normTarget && it.payloadHash == payloadHash &&
                    now - it.at in 0..withinMs && it.state != EffectState.FAILED
            }
        }

    fun get(id: String): Effect? = synchronized(lock) { rows.firstOrNull { it.id == id } }

    fun forTask(taskId: String): List<Effect> = synchronized(lock) { rows.filter { it.taskId == taskId } }

    fun all(): List<Effect> = synchronized(lock) { rows.toList() }

    /** TEST ONLY: forget the in-memory rows as a new process would (the file on disk stays). */
    internal fun resetForTest() = synchronized(lock) { rows.clear(); file = null; lastWriteDurable = true }

    private fun pruneLocked(now: Long) {
        val keep = retentionMs()
        rows.removeAll { now - it.updatedAt > keep }
        while (rows.size > MAX_ROWS) rows.removeAt(0)
    }

    private fun persistLocked() {
        val f = file ?: run { lastWriteDurable = false; return }
        val arr = JSONArray()
        rows.forEach { arr.put(toJson(it)) }
        // SecureStore.write fsyncs the temp file before the atomic rename (AtomicFile semantics). False = no Keystore key:
        // the row stays in memory (it still dedupes in this process), never on disk in the clear.
        lastWriteDurable = com.tekyerema.core.SecureStore.write(f, arr.toString())
    }

    private fun toJson(e: Effect) = JSONObject()
        .put("id", e.id).put("task", e.taskId ?: JSONObject.NULL).put("kind", e.kind.name).put("target", e.target)
        .put("hash", e.payloadHash).put("state", e.state.name).put("at", e.at).put("evidence", e.evidence ?: JSONObject.NULL)
        .put("label", e.label ?: JSONObject.NULL).put("tool", e.tool ?: JSONObject.NULL).put("updatedAt", e.updatedAt)

    private fun JSONObject.strOrNull(k: String): String? = if (has(k) && !isNull(k)) optString(k) else null

    private fun parse(text: String): List<Effect> = try {
        val arr = JSONArray(text)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            try {
                Effect(o.getString("id"), o.strOrNull("task"),
                    runCatching { EffectKind.valueOf(o.getString("kind")) }.getOrDefault(EffectKind.OTHER),
                    o.getString("target"), o.getString("hash"),
                    runCatching { EffectState.valueOf(o.getString("state")) }.getOrDefault(EffectState.UNKNOWN),
                    o.getLong("at"), o.strOrNull("evidence"), o.strOrNull("label"), o.strOrNull("tool"), o.optLong("updatedAt", o.getLong("at")))
            } catch (_: Throwable) { null }
        }
    } catch (_: Throwable) { emptyList() }
}
