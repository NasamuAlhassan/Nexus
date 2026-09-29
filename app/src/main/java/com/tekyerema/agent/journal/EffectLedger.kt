package com.tekyerema.agent.journal

/**
 * CONTRACT (docs/agent/AGENTIC_ARCHITECTURE.md §Effects) — every consequential action (send, call, pay, install,
 * delete, account change) is written here BEFORE it runs, so a send whose confirmation never came back is known as
 * "maybe sent" across tasks and process restarts, and is checked in the world before anything is redone.
 *
 * Stub: in memory only. Workstream A replaces the storage (encrypted, fsync'd before dispatch) and adds the world
 * checks (SMS provider, call log, PackageManager, the chat on screen).
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
    val evidence: String? = null
)

object EffectLedger {
    private val rows = java.util.concurrent.CopyOnWriteArrayList<Effect>()

    fun normalizeTarget(t: String): String = t.lowercase().filter { it.isLetterOrDigit() }
    fun hash(payload: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(payload.lowercase().replace(Regex("\\s+"), " ").trim().toByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)

    fun intend(kind: EffectKind, target: String, payload: String, taskId: String?, now: Long = System.currentTimeMillis()): Effect =
        Effect("e$now-${rows.size}", taskId, kind, normalizeTarget(target), hash(payload), EffectState.INTENDED, now).also { rows.add(it) }

    fun mark(id: String, state: EffectState, evidence: String? = null) {
        val i = rows.indexOfFirst { it.id == id }
        if (i >= 0) rows[i] = rows[i].copy(state = state, evidence = evidence ?: rows[i].evidence)
    }

    /** The same consequence (kind + target + payload) done or possibly done within [withinMs], or null. */
    fun recentSame(kind: EffectKind, target: String, payload: String, withinMs: Long, now: Long = System.currentTimeMillis()): Effect? {
        val t = normalizeTarget(target); val h = hash(payload)
        return rows.lastOrNull { it.kind == kind && it.target == t && it.payloadHash == h && now - it.at <= withinMs &&
            it.state != EffectState.FAILED }
    }

    fun all(): List<Effect> = rows.toList()
}
