package com.tekyerema

import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.AgentUiState
import com.tekyerema.live.LiveActivities
import com.tekyerema.live.LiveActivity
import com.tekyerema.live.LiveKind
import com.tekyerema.live.TaskLive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The island says what a task is doing over other apps (owner, 24 Oct 2026: "no pop-up of what I'm doing"). */
class TaskLiveTest {
    @Test fun aWorkingTaskShowsAndAWaitingOrFinishedOneDoesNot() {
        for (s in listOf(AgentStatus.THINKING, AgentStatus.ACTING, AgentStatus.PAUSED))
            assertNotNull(s.name, TaskLive.of(AgentUiState(status = s, goal = "send hi to Ama on WhatsApp"), twi = false))
        for (s in listOf(AgentStatus.IDLE, AgentStatus.WAITING_CONFIRMATION, AgentStatus.ASKING, AgentStatus.SECURE_INPUT,
                AgentStatus.COMPLETED, AgentStatus.STOPPED, AgentStatus.FAILED))
            assertNull(s.name, TaskLive.of(AgentUiState(status = s, goal = "x"), twi = false))
    }

    @Test fun itCarriesTheRequestAStopButtonAndATwiTitle() {
        val a = TaskLive.of(AgentUiState(status = AgentStatus.ACTING, goal = "send hi to Ama on WhatsApp"), twi = false)!!
        assertEquals(LiveKind.TASK, a.kind)
        assertEquals("send hi to Ama on WhatsApp", a.detail)
        assertEquals(TaskLive.STOP, a.actions.single().id)
        assertTrue(TaskLive.of(AgentUiState(status = AgentStatus.ACTING, goal = "x"), twi = true)!!.title.contains("ɛ"))
    }

    @Test fun itLeadsARunningTimerButNotOneThatIsDone() {
        LiveActivities.clear()
        val task = TaskLive.of(AgentUiState(status = AgentStatus.ACTING, goal = "open settings"), twi = false)!!
        LiveActivities.upsert(LiveActivity("timer-1", LiveKind.TIMER, "Timer", "4:32"))
        LiveActivities.upsert(task)
        assertEquals("the running timer keeps running, the task leads", TaskLive.ID, LiveActivities.lead()!!.id)
        assertEquals(2, LiveActivities.state.value.size)
        LiveActivities.upsert(LiveActivity("timer-1", LiveKind.TIMER, "Timer", "Done", urgent = true))
        assertEquals("a timer that is done comes first", "timer-1", LiveActivities.lead()!!.id)
        LiveActivities.clear()
    }
}
