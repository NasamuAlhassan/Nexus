package com.tekyerema.agent

/**
 * Compact, structured per-run progress state (Phase II P0). So the brain doesn't have to reconstruct
 * "where am I, what have I learned" from a flat growing transcript every step, AgentEngine keeps this
 * small scratchpad and renders a short block atop the prompt. Populated deterministically (current app/
 * screen, known entities from resolved consequential args, recent failures). Additive: brains that ignore
 * `progress` (LocalBrain / TestBrain) behave exactly as before.
 */
class TaskMemory {
    private var currentApp: String = ""
    private var currentScreen: String = ""
    private val knownEntities = LinkedHashMap<String, String>()
    private val recentFailures = ArrayDeque<String>()

    fun noteScreen(app: String?, screen: String?) {
        app?.takeIf { it.isNotBlank() }?.let { currentApp = it }
        screen?.takeIf { it.isNotBlank() }?.let { currentScreen = it }
    }

    /** Record a durable fact the goal named (recipient, number, amount, contact…) so it survives step-to-step. */
    fun noteEntity(key: String, value: String?) {
        val v = value?.trim().orEmpty()
        if (v.isNotEmpty()) knownEntities[key] = v.take(48)
    }

    fun noteFailure(msg: String?) {
        val m = msg?.trim().orEmpty()
        if (m.isEmpty()) return
        recentFailures.addLast(m.take(120))
        while (recentFailures.size > 3) recentFailures.removeFirst()
    }

    /** A short prompt block, or "" when there is nothing worth adding. */
    fun render(): String {
        val parts = mutableListOf<String>()
        val loc = buildString {
            if (currentApp.isNotBlank()) append("app=").append(currentApp.substringAfterLast('.'))
            if (currentScreen.isNotBlank()) { if (isNotEmpty()) append(" · "); append("screen=").append(currentScreen) }
        }
        if (loc.isNotBlank()) parts.add(loc)
        if (knownEntities.isNotEmpty())
            parts.add("known: " + knownEntities.entries.joinToString(", ") { "${it.key}=${it.value}" })
        if (recentFailures.isNotEmpty())
            parts.add("recent problems: " + recentFailures.joinToString("; "))
        return parts.joinToString("\n")
    }
}
