package com.tekyerema.live

import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.AgentUiState
import com.tekyerema.ui.assistant.SurfaceModel
import com.tekyerema.voice.VoiceUiState

/**
 * What Tɛkyerɛma is doing, in the island, while a task works in ANOTHER app (owner, 24 Oct 2026: "sometimes it's doing
 * something and the display doesn't show — no pop-up of what I'm doing"). The step label ("Opening WhatsApp", "Typing
 * the message") was only drawn on Tɛkyerɛma's own screen; over WhatsApp or the MoMo menus nothing showed until a question or
 * the reply. The island (ui/island/IslandOverlay) already floats over any app and steps aside for a prompt panel and for
 * Tɛkyerɛma's own screen, so a task is one more live activity there — ahead of a running timer (which keeps running), behind
 * a timer that is done or a turn close by. Its one button stops the task. Pure → TaskLiveTest.
 */
object TaskLive {
    const val ID = "task"
    const val STOP = "stop"
    private val WORKING = setOf(AgentStatus.THINKING, AgentStatus.ACTING, AgentStatus.PAUSED)

    /** The capsule for [st], or null when no task is working (waiting on the user has its own panel; done is the reply). */
    fun of(st: AgentUiState): LiveActivity? {
        if (st.status !in WORKING) return null
        // The step running now; between steps (most of a task: the planner is thinking) the last one done — "Opened Settings"
        // says more than "Working on it" (emulator, 24 Oct 2026: the pill read "Working on it" the whole task).
        val steps = SurfaceModel.stepsOf(st)
        val label = (steps.lastOrNull { it.active }?.label ?: steps.lastOrNull()?.past?.takeIf { it.isNotBlank() })
            ?: SurfaceModel.reduce(VoiceUiState(), st).title.ifBlank { "Working on it" }
        val title = "Nexus is working"
        val request = (st.request ?: st.goal).orEmpty().trim().take(80)
        return LiveActivity(
            id = ID, kind = LiveKind.TASK, title = title, compact = label.take(28),
            detail = request.ifBlank { null }, glyph = if (st.status == AgentStatus.PAUSED) LiveGlyph.PAUSED else LiveGlyph.WALK,
            actions = listOf(LiveAction(STOP, "Stop")),
            spoken = "$title: $label", priority = 1,   // leads a running timer or music while it works; an urgent one (done) still first
        )
    }
}
