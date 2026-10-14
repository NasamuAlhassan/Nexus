package com.tekyerema.agent.recovery

import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.Progress
import com.tekyerema.core.config.AppConfig
import org.json.JSONObject

/**
 * A screen, reduced to what identifies it across two readings: app, title and the set of tree labels (element ids and
 * vision text are left out — ids are renumbered every reading and OCR is noisy).
 */
data class ScreenKey(val pkg: String?, val title: String?, val labels: Set<String>) {
    /** Same app, same title (when both have one), and most labels shared. */
    fun similar(o: ScreenKey, min: Double = MIN_OVERLAP): Boolean {
        if (pkg != o.pkg) return false
        if (!title.isNullOrBlank() && !o.title.isNullOrBlank() && title != o.title) return false
        if (labels.isEmpty() && o.labels.isEmpty()) return true
        val union = (labels + o.labels).size
        return union > 0 && labels.intersect(o.labels).size.toDouble() / union >= min
    }

    companion object {
        const val MIN_OVERLAP = 0.6
        fun of(s: UiSnapshot): ScreenKey =
            ScreenKey(s.appPackage, s.screenTitle?.lowercase()?.trim()?.ifEmpty { null }, Progress.labelsOf(s).toSet())
    }
}

/**
 * Workstream B — engine-driven backtracking (docs/agent/research/agenticness_mobile_agents.md §5; BacktrackAgent,
 * BEAP-Agent). After every verified NAVIGATION step the screen is pushed as a checkpoint (bounded). When a step lands
 * on the wrong page, the ENGINE — not a text nudge — presses Back (≤ [maxPresses]) until a checkpoint matches; failing
 * that it relaunches the app and replays only the verified, label-addressed navigation that led to the last
 * checkpoint (through the normal pipeline, one checked step at a time).
 *
 * Never back past a consequential effect: a send / call / payment / confirmed step or typed text is a [barrier] — every
 * checkpoint before it is forgotten, so nothing can "go back" across it; a ledger effect newer than the oldest
 * checkpoint does the same ([effectsSince], Workstream A's EffectLedger). Never on a phone/USSD session (Back cancels it)
 * or over a typed draft.
 */
class Backtracker(
    private val maxPresses: Int = AppConfig.int("agent.backtrack.maxPresses", 3).coerceIn(1, 5),
    private val maxDepth: Int = AppConfig.int("agent.backtrack.maxDepth", 8).coerceIn(2, 16),
    /** Did a consequential effect get recorded (EffectLedger) at or after this time? */
    private val effectsSince: (Long) -> Boolean = { false },
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    /** One verified screen, and the label-addressed step that led to it (null for the task's first screen). */
    data class Checkpoint(val key: ScreenKey, val appName: String?, val via: AgentDecision.CallTool?, val at: Long)

    private val stack = ArrayList<Checkpoint>()
    val checkpoints: List<Checkpoint> get() = stack.toList()
    var barriers = 0; private set

    /** The first screen of the task (only if nothing is recorded yet). */
    fun base(s: UiSnapshot) { if (stack.isEmpty() && s.appPackage != null) stack.add(Checkpoint(ScreenKey.of(s), s.appName, null, clock())) }

    /**
     * A navigation step VERIFIED and landed on [after]. [via] is the step as it can be replayed (label-addressed, or
     * open_app), or null if it can't be. A screen already on the stack isn't pushed twice: we're back on it.
     */
    fun onVerifiedNavigation(after: UiSnapshot, via: AgentDecision.CallTool?) {
        if (after.appPackage == null) return
        val key = ScreenKey.of(after)
        val i = stack.indexOfLast { it.key.similar(key) }
        if (i >= 0) { while (stack.size > i + 1) stack.removeAt(stack.lastIndex); return }
        stack.add(Checkpoint(key, after.appName, via, clock()))
        while (stack.size > maxDepth) stack.removeAt(0)
    }

    /** A consequential effect happened (or text was typed): nothing before [now] can be gone back to. */
    fun barrier(now: UiSnapshot?) {
        stack.clear()
        barriers++
        if (now != null) base(now)
    }

    /** Is there a verified screen to go back to from [current]? */
    fun canBacktrack(current: UiSnapshot): Boolean {
        val k = ScreenKey.of(current)
        return stack.isNotEmpty() && !stack.last().key.similar(k)
    }

    interface Ops {
        /** Press Back once. */
        suspend fun back(): Boolean
        /** Look at the screen after it settles; null = a secure/credential screen (stop at once, observe nothing). */
        suspend fun observe(): UiSnapshot?
    }

    sealed class Result {
        object AlreadyThere : Result()
        data class Reached(val to: Checkpoint, val presses: Int, val screen: UiSnapshot) : Result()
        /** Back didn't find it: relaunch and replay these verified navigation steps (engine queues them). */
        data class Replay(val steps: List<AgentDecision.CallTool>, val presses: Int, val to: Checkpoint) : Result()
        data class NotReached(val presses: Int, val why: String) : Result()
        data class Refused(val why: String) : Result()
    }

    /**
     * Go back to the most recent checkpoint from [current]. [draftTexts]: text this task typed — a field still holding
     * it would be lost. Runs the Back presses through [ops]; pure otherwise.
     */
    suspend fun run(current: UiSnapshot, ops: Ops, draftTexts: Collection<String> = emptyList()): Result {
        val oldest = stack.firstOrNull() ?: return Result.Refused("no verified screen to go back to")
        if (effectsSince(oldest.at)) { barrier(current); return Result.Refused("something consequential happened since — I won't go back past it") }
        if (onPhoneSession(current.appPackage)) return Result.Refused("Back would cancel the phone/USSD session")
        if (draftAtRisk(current, draftTexts)) return Result.Refused("the text typed on this screen would be lost")
        val here = ScreenKey.of(current)
        if (stack.last().key.similar(here)) return Result.AlreadyThere
        var presses = 0
        var screen = current
        while (presses < maxPresses) {
            if (!ops.back()) break
            presses++
            screen = ops.observe() ?: return Result.NotReached(presses, "a secure screen appeared")
            val k = ScreenKey.of(screen)
            val i = stack.indexOfLast { it.key.similar(k) }
            if (i >= 0) {
                while (stack.size > i + 1) stack.removeAt(stack.lastIndex)
                return Result.Reached(stack[i], presses, screen)
            }
            // Out of the task's apps onto the home screen: more Backs do nothing useful.
            if (stack.none { it.key.pkg == screen.appPackage } && screen.appPackage != current.appPackage) break
            if (onPhoneSession(screen.appPackage)) break
        }
        val target = stack.last()
        // Only a route THIS task verified is replayed. The screen the task merely started on (checkpoint 0, no step of ours)
        // is not: "turn off the data and turn on the Wi-Fi" began on YouTube, the first toggle went wrong, and the engine
        // relaunched YouTube in the middle of a settings task (A20e, 14 Oct 2026 00:31).
        // (The home screen is the exception: pressing Home is harmless and is where a lost task should start again.)
        if (stack.all { it.via == null } && !isHome(target.appName ?: "", target.key.pkg))
            return Result.NotReached(presses, "no step of this task to replay")
        val replay = replayTo(target) ?: return Result.NotReached(presses, "no replayable route to ${target.appName ?: target.key.pkg}")
        return Result.Replay(replay, presses, target)
    }

    /**
     * The verified navigation that leads from an app launch to [target]: open_app, then label-addressed taps. Null when
     * any step on the way isn't pure navigation or there is no launch to start from.
     */
    internal fun replayTo(target: Checkpoint): List<AgentDecision.CallTool>? {
        val end = stack.indexOf(target).takeIf { it >= 0 } ?: return null
        val start = (end downTo 0).firstOrNull { stack[it].via?.name == "open_app" && stack[it].key.pkg == target.key.pkg }
            ?: return (if (end == 0 && target.appName != null) listOf(launch(target.appName, target.key.pkg)) else null)
        val out = ArrayList<AgentDecision.CallTool>()
        out.add(launch(stack[start].via!!.args.optString("app").ifBlank { target.appName ?: return null }, target.key.pkg))
        for (i in start + 1..end) {
            val v = stack[i].via ?: return null
            if (!replayable(v)) return null
            out.add(AgentDecision.CallTool(v.name, JSONObject(v.args.toString()), REPLAY_TAG, v.expects))
        }
        return out.take(maxDepth)
    }

    private fun openApp(app: String, pkg: String?) =
        AgentDecision.CallTool("open_app", JSONObject().put("app", app), REPLAY_TAG, pkg?.let { "foreground_is: $it" })

    /** The step that brings [app] back: the Home key for the launcher — "open_app(the home screen)" is no app and failed as
     *  "not installed" on the Pixel emulator (13 October 2026) — else open_app by name. */
    private fun launch(app: String, pkg: String?): AgentDecision.CallTool =
        if (isHome(app, pkg)) AgentDecision.CallTool("key_event", JSONObject().put("key", "home"), REPLAY_TAG, pkg?.let { "foreground_is: $it" })
        else openApp(app, pkg)

    private fun isHome(app: String, pkg: String?): Boolean =
        app.equals("the home screen", ignoreCase = true) || app.equals("home screen", ignoreCase = true) || pkg?.lowercase()?.contains("launcher") == true

    companion object {
        const val REPLAY_TAG = "engine:replay"

        /** A step that can be replayed blind: a tap/scroll addressed by label (ids don't survive), nothing typed. */
        fun replayable(d: AgentDecision.CallTool): Boolean = when (d.name) {
            "tap" -> d.args.optString("label").isNotBlank() && !d.args.has("x")
            "scroll", "swipe" -> !d.args.has("element_id") && !d.args.has("x")
            else -> false
        }

        /** The step as it can be replayed later: a tap on a resolved tree element becomes a tap by its label. */
        fun replayForm(d: AgentDecision.CallTool, resolvedLabel: String?, fromTree: Boolean): AgentDecision.CallTool? = when (d.name) {
            "open_app" -> d
            "tap" -> when {
                d.args.optString("label").isNotBlank() -> d
                resolvedLabel != null && fromTree && resolvedLabel.isNotBlank() ->
                    AgentDecision.CallTool("tap", JSONObject().put("label", resolvedLabel), d.rationale, d.expects)
                else -> null
            }
            "scroll", "swipe" -> if (replayable(d)) d else null
            else -> null
        }

        internal fun onPhoneSession(pkg: String?): Boolean {
            val p = pkg?.lowercase() ?: return false
            return p == "com.android.phone" || p.contains("dialer") || p.contains("incall") || p.contains("telecom") || p.contains("stk")
        }

        internal fun draftAtRisk(s: UiSnapshot, typed: Collection<String>): Boolean =
            typed.any { t -> t.isNotBlank() && s.elements.any { it.editable && it.label?.contains(t.trim().take(40), true) == true } }
    }
}
