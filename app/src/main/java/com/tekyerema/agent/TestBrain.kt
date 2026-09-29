package com.tekyerema.agent

import android.content.Context
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * The scenarios this brain can deterministically drive. Each generates the same typed primitive
 * calls a real AgentBrain would — the SAME AgentEngine → TargetResolver → PolicyEngine →
 * ActionQueue → Tools → Android → Perception → Verify pipeline executes; only the source of the
 * plan differs. This is Track A infrastructure testing, never evidence about LLM behaviour.
 */
enum class TestScenario {
    OPEN_SETTINGS, OPEN_MESSAGES, READ_STATE, SET_ALARM, READ_NOTIFICATIONS,
    CALL_CONTACT, DIRECT_REPLY, STOP_MID_TASK, OVERLAPPING_GOALS, STATE_RACE,
    TYPE_POLICY_ESCALATION,
    // Added with the universal-operator rebuild:
    LONG_PRESS, SWIPE, DRAG, SCROLL_FALLBACK, VISION_ROUTE, EXPECTS_VERIFY, PROVENANCE_TAG,
    CANNOT_DO, INVALID_COORDINATES, KEY_VOLUME,
    // Completion-gating (the brightness-hallucination class): a Done must not be COMPLETED unless
    // the substantive state change verified.
    DONE_AFTER_UNVERIFIED_MUTATION, DONE_PURE_OBSERVATION, DONE_CLEAN,
    // Hybrid execution: an accelerator MISS must not end the task — the plan continues via the
    // General Operator (directive §3/§4, Tests B/C/D).
    ACCELERATOR_MISS_FALLBACK,
    // Consequential read-back: with an SMS DRAFT already open (opened over ADB), tap Send → the policy must ask,
    // naming WHO and WHAT. The harness declines — nothing is ever sent.
    SEND_READBACK,
    // Workstream B: a wrong turn (an unexpected app comes to the front) → the engine backs out to the last verified screen
    // by itself (Backtracker, the ladder's BACK rung) — a device check for the agenticness evidence, 2026-09-29.
    BACKTRACK
}

/**
 * TEST-ONLY. Never ships as the production brain and must never be used to claim the real LLM
 * is reliable — it proves the shared execution infrastructure works under deterministic,
 * reproducible plans, at zero API cost. AgentEngine logs "Brain: TEST (<scenario>)".
 *
 * Scenarios reference real apps/labels ONLY as test fixtures (a scripted plan needs a concrete
 * screen to act on); nothing here is production logic and no production code branches on an app.
 */
class TestBrain(
    private val scenario: TestScenario,
    private val appContext: Context
) : AgentBrain {
    private var step = 0

    override suspend fun decide(ctx: BrainContext): AgentDecision {
        step++
        return when (scenario) {
            TestScenario.OPEN_SETTINGS -> when (step) {
                1 -> tool("open_app", JSONObject().put("app", "Settings"), expects = "foreground_is: settings")
                // Step 2 proves a legitimate cross-app follow-up is NOT rejected by the state-race guard.
                2 -> tool("read_state", JSONObject())
                else -> done("open_settings scenario complete")
            }
            TestScenario.BACKTRACK -> when (step) {
                1 -> tool("open_app", JSONObject().put("app", "Settings"), expects = "foreground_is: settings")
                // The wrong turn, on purpose: a TAP that lands in another app (Samsung Settings' "Device care" item opens Samsung's Device care app; not Google
                // Play services) while the step expected to stay on Settings' own page. (open_app is a deliberate app switch
                // and is never a wrong page, so it can't stand in for this.)
                2 -> tool("tap", JSONObject().put("label", "Device care"), expects = "element_present: Connections")
                3 -> tool("read_state", JSONObject())
                else -> done("backtrack scenario complete")
            }
            TestScenario.OPEN_MESSAGES -> when (step) {
                1 -> tool("open_app", JSONObject().put("app", "Messages"), expects = "foreground_is: messaging")
                else -> done("open_messages scenario complete")
            }
            TestScenario.READ_STATE -> when (step) {
                1 -> tool("read_state", JSONObject())
                else -> done("read_state scenario complete")
            }
            TestScenario.SET_ALARM -> when (step) {
                1 -> tool("set_alarm", JSONObject().put("hour", 6).put("minute", 0), expects = "element_present: 06:00")
                else -> done("set_alarm scenario complete")
            }
            TestScenario.READ_NOTIFICATIONS -> when (step) {
                1 -> tool("read_notifications", JSONObject())
                else -> done("read_notifications scenario complete")
            }
            TestScenario.CALL_CONTACT -> when (step) {
                // Tier 2 — exercises the real PolicyEngine confirmation gate (confirm via --es confirm).
                1 -> tool("make_call", JSONObject().put("contact", "Prince"))
                else -> done("call_contact scenario complete")
            }
            TestScenario.SEND_READBACK -> when (step) {
                // re-proposed if the engine re-observes (e.g. the draft wasn't in front yet when step 1 was planned)
                1, 2, 3 -> tool("tap", JSONObject().put("label", "Send SMS"), expects = "element_absent: Send SMS")
                else -> done("send_readback scenario complete")
            }
            TestScenario.DIRECT_REPLY -> when (step) {
                1 -> tool("reply_to_notification", JSONObject().put("sender", "Prince").put("message", "I'll call him after class"))
                else -> done("direct_reply scenario complete")
            }
            TestScenario.STOP_MID_TASK -> when (step) {
                1 -> tool("open_app", JSONObject().put("app", "Settings"), expects = "foreground_is: settings")
                // Long enough for an external STOP to arrive. If Stop works, step 3 is never reached.
                2 -> tool("wait", JSONObject().put("seconds", 5))
                3 -> tool("read_state", JSONObject())
                else -> done("stop_mid_task scenario complete (should not be reached if Stop worked)")
            }
            TestScenario.OVERLAPPING_GOALS -> when (step) {
                1 -> tool("wait", JSONObject().put("seconds", 6))
                else -> done("overlapping_goals scenario complete")
            }
            TestScenario.STATE_RACE -> when (step) {
                1 -> tool("open_app", JSONObject().put("app", "Settings"), "establish screen A", expects = "foreground_is: settings")
                2 -> {
                    // The actual race, reproduced deterministically: AgentEngine captured `before` =
                    // screen A (Settings). Between "the model observed A" and "the action executes",
                    // something EXTERNAL changes the foreground to B. The tap below must go through
                    // the real pipeline and be refused as stale, never blindly dispatched.
                    AgentEngine.perturbForegroundForTest()
                    delay(700) // let the launcher become genuinely foreground before the tap executes
                    tool("tap", JSONObject().put("label", "Search"), "race: foreground changed to the launcher underneath this decision — must be refused")
                }
                else -> done("state_race scenario complete")
            }
            TestScenario.TYPE_POLICY_ESCALATION -> when (step) {
                // Reproduces the round-6 finding: type(submit=true) into a real message-compose field
                // must escalate to Tier 2 from the RESOLVED TARGET's semantics — no tool-name switch.
                1 -> tool("open_app", JSONObject().put("app", "WhatsApp"), expects = "foreground_is: whatsapp")
                2 -> tool("tap", JSONObject().put("label", "Search"), "policy-test: open search")
                3 -> {
                    val searchField = findElement(ctx.screen) { _, role, label -> role.contains("Edit", true) || label?.contains("Search", true) == true }
                    tool("type", JSONObject().put("element_id", searchField ?: -1).put("text", "Prince").put("submit", true), "policy-test: search a known contact")
                }
                4 -> tool("tap", JSONObject().put("label", "Prince Melanin"), "policy-test: open the real conversation")
                5 -> {
                    val rx = Regex("\\b(message|reply|chat|comment|caption|post)\\b", RegexOption.IGNORE_CASE)
                    val msgId = findElement(ctx.screen) { _, _, label -> label?.let(rx::containsMatchIn) == true }
                    tool("type", JSONObject().put("element_id", msgId ?: -1).put("text", "[POLICY TEST — DO NOT SEND]").put("submit", true),
                        "policy-test: THE CHECK — must require Tier-2 confirmation before executing")
                }
                else -> done("type_policy_escalation scenario complete")
            }
            TestScenario.LONG_PRESS -> when (step) {
                1 -> tool("open_app", JSONObject().put("app", "Settings"), expects = "foreground_is: settings")
                2 -> {
                    // Any labelled element: proves node ACTION_LONG_CLICK → timed-gesture fallback dispatches.
                    val id = findElement(ctx.screen) { _, _, label -> label != null && label.length in 3..30 }
                    tool("long_press", JSONObject().put("element_id", id ?: -1).put("duration_ms", 700), "primitive: long_press")
                }
                3 -> tool("key_event", JSONObject().put("key", "back"))
                else -> done("long_press scenario complete")
            }
            TestScenario.SWIPE -> when (step) {
                1 -> tool("open_app", JSONObject().put("app", "Settings"), expects = "foreground_is: settings")
                2 -> tool("swipe", JSONObject().put("direction", "up"), "primitive: swipe (content scrolls)", expects = "element_present: Settings")
                3 -> tool("swipe", JSONObject().put("direction", "down"), "primitive: swipe back")
                else -> done("swipe scenario complete")
            }
            TestScenario.DRAG -> when (step) {
                1 -> tool("open_app", JSONObject().put("app", "Settings"), expects = "foreground_is: settings")
                2 -> {
                    // A continuous multi-point stroke between two labelled elements: proves the chained
                    // StrokeDescription path dispatches end-to-end (outcome on Settings is a scroll).
                    val ids = findElements(ctx.screen, 2) { _, _, label -> label != null && label.length in 3..30 }
                    tool("drag", JSONObject().put("element_id", ids.getOrElse(1) { -1 }).put("to_element_id", ids.getOrElse(0) { -1 }).put("duration_ms", 900), "primitive: drag")
                }
                else -> done("drag scenario complete")
            }
            TestScenario.SCROLL_FALLBACK -> when (step) {
                // On the launcher (no scrollable list node in most launchers' page view) the scroll must
                // fall through to the gesture channel rather than fail with "no scrollable element".
                1 -> tool("key_event", JSONObject().put("key", "home"))
                2 -> tool("scroll", JSONObject().put("direction", "right"), "primitive: scroll on a surface with no scroll action")
                else -> done("scroll_fallback scenario complete")
            }
            TestScenario.VISION_ROUTE -> when (step) {
                1 -> tool("key_event", JSONObject().put("key", "home"))
                // Requests the vision route explicitly: screenshot → on-device OCR → source=vision elements.
                2 -> tool("read_state", JSONObject().put("vision", true).put("reason", "track-A: exercise the OCR path"))
                3 -> {
                    // Tap a vision element by coordinates INSIDE its bounds — the only legal use of x,y.
                    val v = firstVisionBounds(ctx.screen)
                    if (v == null) done("vision_route: no vision elements were produced (see PERCEIVE log)")
                    else tool("tap", JSONObject().put("x", (v[0] + v[2]) / 2).put("y", (v[1] + v[3]) / 2), "tap a vision element by coordinates")
                }
                4 -> tool("key_event", JSONObject().put("key", "home"))
                else -> done("vision_route scenario complete")
            }
            TestScenario.EXPECTS_VERIFY -> when (step) {
                1 -> tool("open_app", JSONObject().put("app", "Settings"), expects = "foreground_is: settings")            // true claim → verified
                2 -> tool("read_state", JSONObject(), expects = "element_present: Connections")                          // perception: default verify
                3 -> tool("key_event", JSONObject().put("key", "back"), expects = "foreground_is: com.definitely.not.installed") // false claim → NOT verified
                4 -> tool("key_event", JSONObject().put("key", "home"), expects = "screen changed")                       // vacuous → NOT verified
                else -> done("expects_verify scenario complete")
            }
            TestScenario.PROVENANCE_TAG -> when (step) {
                1 -> tool("open_app", JSONObject().put("app", "Settings"), expects = "foreground_is: settings")
                2 -> tool("tap", JSONObject().put("label", "Search"), expects = "element_present: Search")
                3 -> {
                    // Type a value COPIED FROM THE SCREEN (a visible label) into the search field: the
                    // POLICY log must show provenance: text=untrusted(copied from screen). It stays
                    // Tier 1 (device-control search box) — tagged, not escalated (plan §13/§24).
                    val field = findElement(ctx.screen) { _, role, _ -> role.contains("Edit", true) }
                    val label = firstLabel(ctx.screen) { it.length in 6..24 && !it.contains("Search", true) } ?: "Connections"
                    tool("type", JSONObject().put("element_id", field ?: -1).put("text", label), "provenance-test: payload copied from screen", expects = "field_contains: ${field ?: -1} | $label")
                }
                4 -> tool("key_event", JSONObject().put("key", "back"))
                else -> done("provenance_tag scenario complete")
            }
            TestScenario.CANNOT_DO -> when (step) {
                1 -> tool("read_state", JSONObject())
                2 -> AgentDecision.CannotDo("resource_unavailable", "[TEST] scripted honest stop: the requested thing does not exist on this device")
                else -> done("cannot_do scenario complete (should not be reached)")
            }
            TestScenario.INVALID_COORDINATES -> when (step) {
                // Coordinates the model 'invented' (no vision element there) must be REJECTED before the gate.
                1 -> tool("tap", JSONObject().put("x", 5).put("y", 5), "must be rejected: not inside any vision element")
                else -> done("invalid_coordinates scenario complete")
            }
            TestScenario.KEY_VOLUME -> when (step) {
                1 -> tool("key_event", JSONObject().put("key", "volume_up"))
                2 -> tool("key_event", JSONObject().put("key", "volume_down"))
                else -> done("key_volume scenario complete")
            }
            TestScenario.DONE_AFTER_UNVERIFIED_MUTATION -> when (step) {
                // A state-changing action whose expectation is deliberately false → will NOT verify.
                // Then Done. The engine MUST report COMPLETED_UNVERIFIED, not COMPLETED — the
                // hallucination guard. (open_app first so there is a verified mutation too, proving
                // it's the LAST/substantive one that matters, not merely 'some action verified'.)
                1 -> tool("open_app", JSONObject().put("app", "Settings"), expects = "foreground_is: settings")
                2 -> tool("tap", JSONObject().put("label", "Search"), expects = "foreground_is: com.definitely.not.installed")
                else -> done("[TEST] I have completed the task successfully.") // a lie; must be flagged UNVERIFIED
            }
            TestScenario.DONE_PURE_OBSERVATION -> when (step) {
                // No state change at all — a pure query/answer task. Done is legitimately COMPLETED.
                1 -> tool("read_state", JSONObject())
                else -> done("[TEST] The screen shows the home launcher.")
            }
            TestScenario.DONE_CLEAN -> when (step) {
                // Substantive mutation verifies, then a read, then Done → COMPLETED (must NOT be
                // wrongly downgraded — guards against the gate over-flagging good runs).
                1 -> tool("open_app", JSONObject().put("app", "Settings"), expects = "foreground_is: settings")
                2 -> tool("read_state", JSONObject())
                else -> done("[TEST] Settings is open.")
            }
            TestScenario.ACCELERATOR_MISS_FALLBACK -> when (step) {
                // 1) An accelerator that MISSES: no such contact. The engine must log a `recovery:` hint,
                //    bump acceleratorMisses, and — crucially — NOT end the task or flag it failed/cannot_do.
                1 -> tool("search_contacts", JSONObject().put("query", "Zznobody7788Q"),
                    "accelerator miss (no such contact) — must NOT end the task; fall back to the General Operator")
                // 2) The plan continues (state preserved): establish the environment.
                2 -> tool("open_app", JSONObject().put("app", "Settings"), "continue after the miss", expects = "foreground_is: settings")
                // 3) General Operator takes over with a universal primitive.
                3 -> tool("read_state", JSONObject())
                // Completion is reachable despite the earlier accelerator miss (COMMS_READ miss is non-mutating).
                else -> done("[TEST] accelerator_miss_fallback complete — miss recovered, task continued via the General Operator")
            }
        }
    }

    /** Scans ctx.screen's raw JSON (id/role/label per element) for the first match. */
    private fun findElement(screen: JSONObject, predicate: (id: Int, role: String, label: String?) -> Boolean): Int? =
        findElements(screen, 1, predicate).firstOrNull()

    private fun findElements(screen: JSONObject, max: Int, predicate: (id: Int, role: String, label: String?) -> Boolean): List<Int> {
        val out = ArrayList<Int>()
        val elements = screen.optJSONArray("elements") ?: return out
        for (i in 0 until elements.length()) {
            val el = elements.optJSONObject(i) ?: continue
            val id = el.optInt("id", -1)
            val role = el.optString("role")
            val label = if (el.has("label")) el.optString("label") else null
            if (predicate(id, role, label)) { out.add(id); if (out.size >= max) break }
        }
        return out
    }

    private fun firstLabel(screen: JSONObject, predicate: (String) -> Boolean): String? {
        val elements = screen.optJSONArray("elements") ?: return null
        for (i in 0 until elements.length()) {
            val l = elements.optJSONObject(i)?.optString("label") ?: continue
            if (l.isNotEmpty() && predicate(l)) return l
        }
        return null
    }

    private fun firstVisionBounds(screen: JSONObject): IntArray? {
        val elements = screen.optJSONArray("elements") ?: return null
        for (i in 0 until elements.length()) {
            val el = elements.optJSONObject(i) ?: continue
            if (el.optString("source") != "vision") continue
            val b = el.optJSONArray("bounds") ?: continue
            if (b.length() == 4) return intArrayOf(b.optInt(0), b.optInt(1), b.optInt(2), b.optInt(3))
        }
        return null
    }

    private fun tool(name: String, args: JSONObject, rationale: String? = null, expects: String? = null) =
        AgentDecision.CallTool(name, args, rationale ?: "test:${scenario.name}#$step", expects)

    private fun done(msg: String) = AgentDecision.Done("[TEST] $msg")

}
