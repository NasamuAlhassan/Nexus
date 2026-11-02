package com.tekyerema.teach

import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot

/** What the phone side of a replay can do. The real one is [ReplayHostAndroid]; the tests use a fake. */
interface ReplayHost {
    /** The screen in front, after it has settled; null when it can't be read. */
    suspend fun snapshot(): UiSnapshot?
    fun foregroundPackage(): String?
    /** A PIN / password / code / robot-check screen. */
    fun isPrivate(snap: UiSnapshot): Boolean
    /** Why an app may not be operated (the user's deny list / guide-only list), or null. */
    fun appBlocked(pkg: String?): String?
    /** Open the app of an OPEN_APP step (through the same policy as the agent's open_app). */
    suspend fun openApp(step: TeachStep): StepRun
    /** Do [step] on [element] (null for a scroll): policy first, then the tool. A step the policy would ask about is
     *  NOT done — [StepRun.NeedsYes]. */
    suspend fun act(step: TeachStep, element: UiElement?, snap: UiSnapshot): StepRun
    /** Look for a label that isn't on this screen (scrolling the list, as a tap does); the screen afterwards, or null. */
    suspend fun reveal(step: TeachStep, snap: UiSnapshot): UiSnapshot?
    suspend fun pause(ms: Long)
    fun cancelled(): Boolean = false
}

sealed class StepRun {
    object Ok : StepRun()
    data class Failed(val message: String) : StepRun()
    /** The policy would ask the user first (a send, a payment, a save, a consent…). */
    data class NeedsYes(val prompt: String) : StepRun()
    data class Blocked(val reason: String) : StepRun()
}

enum class HandOverWhy { MISSING, WRONG_SCREEN, NEEDS_YES, BLOCKED, FAILED, NEEDS_TEXT, UNREADABLE, UNNAMED }

sealed class ReplayResult {
    data class Done(val ran: Int, val skipped: Int, val endsAtPrivate: Boolean) : ReplayResult()
    /** Stopped before step [index]; the normal agent takes the rest ([remaining]) — [line] is what the user hears first. */
    data class HandOver(val index: Int, val why: HandOverWhy, val line: String, val remaining: List<TeachStep>) : ReplayResult()
    /** A private screen is in front at step [index]: nothing is read or done; the user takes over. */
    data class Private(val index: Int) : ReplayResult()
    object Cancelled : ReplayResult()
}

/**
 * Teach mode — replay. Walks a taught skill step by step; before EVERY step it checks the screen still matches (same
 * app, the element found by its id or label — [StepMatcher]) and then lets the host do it through the normal policy.
 *
 * It never taps blindly and never skips a read-back:
 *  - an element it cannot find → stop, "I couldn't find <label>", the rest goes to the agent as a goal;
 *  - a step the policy would ask about (send, pay, save, consent — or one recorded as sensitive) → stop BEFORE it; the
 *    agent does it and asks, with its usual read-back;
 *  - a private screen → stop, say nothing about it, the user takes over.
 * Pure given a [ReplayHost] → unit-tested with a fake one.
 */
object ReplayEngine {
    private const val SCREEN_TRIES = 3
    private const val SCREEN_WAIT_MS = 700L

    suspend fun run(skill: TeachSkill, host: ReplayHost): ReplayResult {
        val steps = skill.steps
        var ran = 0
        var skipped = 0
        var i = 0
        fun over(why: HandOverWhy, line: String) = ReplayResult.HandOver(i, why, line, steps.drop(i))
        while (i < steps.size) {
            if (host.cancelled()) return ReplayResult.Cancelled
            val s = steps[i]
            if (s.kind == StepKind.OPEN_APP) {
                if (host.foregroundPackage() == s.pkg) { skipped++; i++; continue }
                host.appBlocked(s.pkg)?.let { return over(HandOverWhy.BLOCKED, "I can't use ${StepWords.appWords(s)}.") }
                when (val r = host.openApp(s)) {
                    StepRun.Ok -> ran++
                    is StepRun.NeedsYes -> return over(HandOverWhy.NEEDS_YES, "I need your yes to open ${StepWords.appWords(s)}.")
                    is StepRun.Blocked -> return over(HandOverWhy.BLOCKED, "I can't open ${StepWords.appWords(s)}.")
                    is StepRun.Failed -> return over(HandOverWhy.FAILED, "I couldn't open ${StepWords.appWords(s)}.")
                }
                i++; continue
            }
            // ---- Back / Home: a system key, no element. Back must be pressed in the app it was taught in (a Back anywhere else
            // leaves the wrong app); Home works from anywhere. Private and denied screens stop it like any other step.
            if (s.kind.isKey) {
                val keySnap = (if (s.kind == StepKind.BACK) screenFor(s, host) else host.snapshot())
                    ?: return over(HandOverWhy.UNREADABLE, "I couldn't read the screen.")
                if (host.isPrivate(keySnap)) return ReplayResult.Private(i)
                host.appBlocked(keySnap.appPackage)?.let { return over(HandOverWhy.BLOCKED, "I can't use this app.") }
                if (s.kind == StepKind.BACK && keySnap.appPackage != s.pkg) return over(HandOverWhy.WRONG_SCREEN, "This isn't the screen I expected.")
                when (host.act(s, null, keySnap)) {
                    StepRun.Ok -> ran++
                    is StepRun.NeedsYes -> return over(HandOverWhy.NEEDS_YES, "This step needs your yes.")
                    is StepRun.Blocked -> return over(HandOverWhy.BLOCKED, "I'm not allowed to do that step.")
                    is StepRun.Failed -> return over(HandOverWhy.FAILED, "That step didn't work.")
                }
                i++; continue
            }
            // ---- the screen must be the one the step was taught on
            var snap = screenFor(s, host) ?: return over(HandOverWhy.UNREADABLE, "I couldn't read the screen.")
            if (host.isPrivate(snap)) return ReplayResult.Private(i)
            host.appBlocked(snap.appPackage)?.let { return over(HandOverWhy.BLOCKED, "I can't use this app.") }
            if (s.unnamed) return over(HandOverWhy.UNNAMED, "One step was a button I never got a name for.")
            // A scroll that only brought the next element into view is skipped when it is already in view.
            if (s.kind == StepKind.SCROLL && s.optional) {
                val next = steps.getOrNull(i + 1)
                if (next != null && next.kind != StepKind.SCROLL && StepMatcher.find(next, snap) is StepMatcher.Match.Found) { skipped++; i++; continue }
            }
            var match = StepMatcher.find(s, snap)
            if (match is StepMatcher.Match.Missing && s.kind != StepKind.SCROLL) {
                host.reveal(s, snap)?.let { snap = it; match = StepMatcher.find(s, snap) }
            }
            val found = when (val m = match) {
                is StepMatcher.Match.Found -> m
                is StepMatcher.Match.WrongApp -> return over(HandOverWhy.WRONG_SCREEN, "This isn't the screen I expected.")
                is StepMatcher.Match.Missing ->
                    if (s.kind == StepKind.SCROLL) { skipped++; i++; continue }
                    else return over(HandOverWhy.MISSING, "I couldn't find ${m.what}.")
            }
            if (host.isPrivate(snap)) return ReplayResult.Private(i)
            if (s.kind == StepKind.TYPE && s.text == null)
                return over(HandOverWhy.NEEDS_TEXT, "I need you to tell me what to type in the ${StepWords.boxName(s)} box.")
            // A step recorded as one the policy asks about is never done here, whatever the live check says.
            if (s.sensitive) return over(HandOverWhy.NEEDS_YES, "This step needs your yes.")
            when (val r = host.act(s, found.element, snap)) {
                StepRun.Ok -> ran++
                is StepRun.NeedsYes -> return over(HandOverWhy.NEEDS_YES, "This step needs your yes.")
                is StepRun.Blocked -> return over(HandOverWhy.BLOCKED, "I'm not allowed to do that step.")
                is StepRun.Failed -> return over(HandOverWhy.FAILED, "That step didn't work.")
            }
            i++
        }
        return ReplayResult.Done(ran, skipped, skill.endsAtPrivate)
    }

    /** The screen for [s]: read again a few times while the wrong app is still in front (an app takes a moment to open). */
    private suspend fun screenFor(s: TeachStep, host: ReplayHost): UiSnapshot? {
        var snap: UiSnapshot? = null
        repeat(SCREEN_TRIES) { n ->
            snap = host.snapshot()
            val ok = snap?.let { it.appPackage == s.pkg && it.elements.isNotEmpty() } == true
            if (ok) return snap
            if (n < SCREEN_TRIES - 1) host.pause(SCREEN_WAIT_MS)
        }
        return snap?.takeIf { it.appPackage != null }
    }
}
