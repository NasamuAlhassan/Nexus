package com.tekyerema

import com.tekyerema.companion.CompanionIntents.Kind
import com.tekyerema.companion.CompanionSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A companion chat is on from its first word until it has been quiet for a while, or the user ends it. */
class CompanionSessionTest {
    private var now = 1_000_000L
    @Before fun setUp() { CompanionSession.resetForTest(); CompanionSession.clock = { now } }
    @After fun tearDown() { CompanionSession.resetForTest() }

    @Test fun not_on_until_started() {
        assertFalse(CompanionSession.active())
        assertNull(CompanionSession.rulesFor("hello"))
    }

    @Test fun on_after_start_and_remembers_what_it_did_last() {
        CompanionSession.start(Kind.TEACH)
        assertTrue(CompanionSession.active())
        assertEquals(Kind.TEACH, CompanionSession.lastKind)
    }

    @Test fun goes_quiet_after_the_idle_time() {
        CompanionSession.start(Kind.CHAT)
        now += CompanionSession.idleMs() - 1
        assertTrue(CompanionSession.active())
        now += 2
        assertFalse(CompanionSession.active())
        assertNull(CompanionSession.rulesFor("hello"))
    }

    @Test fun every_brain_step_renews_the_chat() {
        CompanionSession.start(Kind.CHAT)
        now += CompanionSession.idleMs() - 1_000
        assertNotNull(CompanionSession.rulesFor("my day was fine"))     // renews
        now += CompanionSession.idleMs() - 1_000
        assertTrue(CompanionSession.active())
    }

    @Test fun the_user_can_end_it() {
        CompanionSession.start(Kind.CHAT)
        CompanionSession.end()
        assertFalse(CompanionSession.active())
        assertNull(CompanionSession.lastKind)
    }

    @Test fun lessons_count_up_within_a_chat() {
        assertEquals(0, CompanionSession.nextLesson())
        assertEquals(1, CompanionSession.nextLesson())
    }
}
