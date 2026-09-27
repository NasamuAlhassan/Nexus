package com.tekyerema.agent

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.tekyerema.accessibility.AccessibilityController
import com.tekyerema.accessibility.Perception
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.android.ContactResolver
import com.tekyerema.voice.ConfirmTemplates.Spec
import com.tekyerema.core.Provenance
import com.tekyerema.notifications.NotificationStore
import com.tekyerema.policy.PolicyEngine
import com.tekyerema.policy.PolicyOutcome
import com.tekyerema.policy.TargetSemantics
import com.tekyerema.settings.Settings
import com.tekyerema.ui.OverlayConfirm
import com.tekyerema.tools.ResolveOutcome
import com.tekyerema.tools.ResolvedTarget
import com.tekyerema.tools.Target
import com.tekyerema.tools.TargetResolver
import com.tekyerema.tools.ToolContext
import com.tekyerema.tools.ToolRegistry
import com.tekyerema.tools.boolOrDefault
import com.tekyerema.tools.str
import com.tekyerema.tools.defaultTools
import com.tekyerema.verifier.Expects
import com.tekyerema.verifier.OutcomeVerifier
import com.tekyerema.verifier.Verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/**
 * The operator loop (plan §5/§9/§10):
 *   observe → decide (LLM) → resolve target → policy gate → stale check → execute → observe →
 *   verify expected outcome → (history feeds recovery) → report | cannot-do.
 *
 * The LLM decides everything about WHAT to do. This object guarantees HOW: one action at a time,
 * every action gated, every grounded action checked against a current observation, every
 * outcome verified against a fresh one, every decision logged and measured.
 */
object AgentEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var controller: AccessibilityController? = null
    private var appContext: Context? = null
    private var settings: Settings? = null
    private lateinit var registry: ToolRegistry
    private val policy = PolicyEngine()
    private var initialized = false
    private var currentJob: Job? = null
    // The user's reply to a pending confirmation. APPROVE/DECLINE come from buttons or a spoken
    // yes/no; REVISE carries a spoken sentence that is neither ("send it to Kofi instead", "make it
    // shorter", "actually call Ama") — a correction/replacement the BRAIN re-plans, so confirmation
    // is a conversation, not a binary gate (§8/§9/§13/§14). No hardcoded command table: the words are
    // handed to the LLM with the pending proposal in context.
    private sealed interface ConfirmReply {
        data object Approve : ConfirmReply
        data object Decline : ConfirmReply
        data class Revise(val text: String) : ConfirmReply
    }
    private var confirmChannel = Channel<ConfirmReply>(Channel.CONFLATED)
    // Secure Input Mode: the user signals (voice "done", or the debug done hook) that they have finished
    // entering the MoMo PIN so observation can safely resume. false = cancelled/stopped. Fresh per run.
    private var secureChannel = Channel<Boolean>(Channel.CONFLATED)
    @Volatile private var traceFile: java.io.File? = null

    private val _state = MutableStateFlow(AgentUiState())
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    // Bounded, not unlimited: the single tuning knob for recovery room (plan §12). Raised from 20
    // on round-5 evidence; adjusted only by evidence.
    private const val MAX_STEPS = 30
    private const val TAG = "Tekyerema"

    // Cross-turn CONVERSATION memory (bounded) so the brain can resolve references, corrections and
    // follow-ups across utterances. Process-scoped like the rest of this singleton. Not the per-run
    // `history` (which is intra-task tool steps). Cleared by resetConversation() on a fresh session.
    private val conversation = ArrayDeque<String>()
    private fun pushConvo(s: String) { synchronized(conversation) { conversation.addLast(s.take(400)); while (conversation.size > 12) conversation.removeFirst() } }
    private fun conversationSnapshot(): List<String> = synchronized(conversation) { conversation.toList() }
    /** Wipe conversation memory — call when starting a genuinely new session/topic. */
    fun resetConversation() { synchronized(conversation) { conversation.clear() } }

    fun init(context: Context) {
        if (!initialized) {
            appContext = context.applicationContext
            settings = Settings(context.applicationContext)
            registry = ToolRegistry(defaultTools())
            initialized = true
        }
        refreshReadiness()
    }

    fun attachController(c: AccessibilityController) { controller = c; refreshReadiness() }
    fun detachController() { controller = null; refreshReadiness() }
    /** Read-only access to the attached AccessibilityController (for the Local Fast Path's back/home/etc.). */
    fun currentController(): AccessibilityController? = controller

    fun refreshReadiness() {
        val s = settings
        val brain = when {
            s == null -> "not configured"
            s.hasGroqKey -> "OpenAI-compat (${hostOf(s.groqBaseUrl)}, ${s.groqModel})"
            s.geminiApiKey.isNotBlank() -> "Gemini (${s.model})"
            else -> "not configured (no key)"
        }
        _state.update {
            it.copy(serviceReady = controller?.ready == true, hasApiKey = s?.hasAnyBrain == true, brain = brain)
        }
    }

    fun submitGoal(goal: String) {
        if (!initialized) return
        val g = goal.trim()
        if (g.isEmpty()) return
        if (currentJob?.isActive == true) {
            log(LogEntry.Kind.INFO, "Busy — finish or stop the current task first.")
            return
        }
        confirmChannel = Channel(Channel.CONFLATED)
        secureChannel = Channel(Channel.CONFLATED)
        // Mark this run non-terminal SYNCHRONOUSLY, before launching. runTask sets THINKING only once the
        // background coroutine starts (a few ms later); a voice/UI collector that begins waiting in that gap
        // would otherwise match the PREVIOUS run's lingering terminal state when the new goal equals the old
        // one — speaking the stale result while this fresh run proceeds unwatched (and could hit an ignored
        // confirmation gate). Setting THINKING here closes that window.
        _state.update { it.copy(status = AgentStatus.THINKING, goal = g) }
        currentJob = scope.launch { runTask(g) }
    }

    /**
     * DEBUG/TEST ONLY. Runs a deterministic [TestScenario] through the exact same pipeline as a
     * real goal — only the plan's source differs. Logs "Brain: TEST (<scenario>)", never conflated
     * with a real model result (Track A, plan §22).
     */
    fun submitTestScenario(scenario: TestScenario) {
        if (!initialized) return
        if (currentJob?.isActive == true) {
            log(LogEntry.Kind.INFO, "Busy — finish or stop the current task first.")
            return
        }
        val ctxApp = appContext ?: return
        confirmChannel = Channel(Channel.CONFLATED)
        secureChannel = Channel(Channel.CONFLATED)
        currentJob = scope.launch {
            runTask("[TEST SCENARIO] ${scenario.name}", forcedBrain = TestBrain(scenario, ctxApp), forcedLabel = "TEST (${scenario.name})")
        }
    }

    /** TEST-ONLY (STATE_RACE): reliably change the live foreground from a background context.
     *  startActivity() from our backgrounded process is dropped by Android's background-activity-start
     *  restriction; the accessibility HOME action is not, so this faithfully creates the race the guard
     *  must catch. Not part of the agent's action space. */
    internal fun perturbForegroundForTest() { controller?.globalAction("home") }

    fun confirm(approved: Boolean) { confirmChannel.trySend(if (approved) ConfirmReply.Approve else ConfirmReply.Decline) }

    /** A spoken reply to a pending confirmation that is NOT a plain yes/no — a correction, a changed
     *  recipient/app/message, or a different request. The pending action is not executed; the brain
     *  re-plans with this instruction and the prior proposal in conversation memory (§8/§9/§13/§14). */
    fun revise(text: String) { val t = text.trim(); if (t.isNotEmpty()) confirmChannel.trySend(ConfirmReply.Revise(t)) }

    /** The user has finished the secure step (entered their MoMo PIN) and said "done". Resumes observation
     *  ONLY after re-checking that we are no longer on a credential screen (§9/§10). */
    fun secureInputDone() { secureChannel.trySend(true) }

    fun stop() {
        currentJob?.cancel()
        confirmChannel.trySend(ConfirmReply.Decline)
        secureChannel.trySend(false)   // unblock a pending Secure Input wait without resuming observation
        appContext?.let { OverlayConfirm.hide(it) }
        _state.update { it.copy(status = AgentStatus.STOPPED, pendingConfirmation = null, pendingConfirmationTwi = null) }
        log(LogEntry.Kind.INFO, "Stopped by user.")
    }

    private suspend fun runTask(goal: String, forcedBrain: AgentBrain? = null, forcedLabel: String? = null) {
        val ctxApp = appContext ?: return
        val set = settings ?: return
        clearLog()
        openTrace(goal)
        setGoal(goal)
        log(LogEntry.Kind.USER, goal)
        // Conversation memory: snapshot the PRIOR turns for this run's brain context, then record this
        // user turn for the NEXT one. Skipped for TEST scenarios (forcedBrain) so they stay isolated.
        val convo = conversationSnapshot()
        if (forcedBrain == null) pushConvo("User: $goal")
        setStatus(AgentStatus.THINKING)

        // Hybrid brain (directive §14): the on-device LocalBrain handles simple/offline goals directly;
        // it escalates to the configured cloud brain only when it can't ground the request AND we're
        // online. Offline with no local match → an honest refusal, never a silent cloud call or a
        // fabricated answer. This means the operator ALWAYS has a working brain, even with no key/network.
        val online = com.tekyerema.core.NetworkMonitor.isOnline()
        val brain: AgentBrain = forcedBrain ?: run {
            // Cloud fallback CHAIN — use every credential: Groq (keys rotated inside OpenAiCompatBrain)
            // first, then Gemini, so a drained free-tier quota isn't a dead end (demo-readiness fix).
            val cloudChain = buildList {
                if (set.hasGroqKey) add(OpenAiCompatBrain(set))
                if (set.geminiApiKey.isNotBlank()) add(GeminiBrain(set))
            }
            val cloud: AgentBrain? = when (cloudChain.size) {
                0 -> null; 1 -> cloudChain[0]; else -> FallbackBrain(cloudChain)
            }
            // Twi→English rewrite before the cloud operator was provided by Khaya MT, removed 2026-09-27.
            // Until the HCI Lab MT is wired into this seam there is no cloud Twi→English rewrite (null ⇒ goal
            // unchanged); LocalBrain still answers command-Twi in Twi, and English planning is unaffected.
            val twiToEn: (suspend (String) -> String?)? = null
            RoutingBrain(LocalBrain(), cloud, twiToEn, cloudFirst = { set.brainCloudFirst }) { com.tekyerema.core.NetworkMonitor.isOnline() }
        }
        val cloudName = buildList {
            if (set.hasGroqKey) add("Groq/${set.groqModel}×${set.groqApiKeys.size}keys")
            if (set.geminiApiKey.isNotBlank()) add("Gemini/${set.model}")
        }.joinToString(" → ").ifEmpty { "none" }
        val brainLabel = forcedLabel ?: "Hybrid on-device" +
            (if (online && cloudName != "none") " + cloud:$cloudName" else " (offline${if (cloudName == "none") ", no cloud key" else ""})")
        log(LogEntry.Kind.INFO, "Brain: $brainLabel")

        // The operator must never act on its OWN UI. When a goal is submitted from Tɛkyerɛma's own
        // screen, the first observation would be our app (input box, Send, permission buttons) and the
        // model could act on it. Hand off first — but §1.22: do NOT slam HOME, which throws away the app
        // the user actually wants operated (they trigger us as an overlay ON TOP of, say, WhatsApp). Send
        // BACK to dismiss our own surface and reveal what was underneath; only if we're STILL on our own
        // package after that do we fall back to HOME. Generic: keyed on our own package, no app logic.
        if (forcedBrain == null) ensureOffOwnUi(ctxApp)

        val geminiTools = registry.functionDeclarations()
        val openAiTools = registry.openAiTools()
        val history = ArrayList<String>()
        var stats = RunStats()
        // Completion gating (plan §11 hardening): a Done is only COMPLETED if the goal's
        // SUBSTANTIVE outcome was actually verified. We track device-MUTATING actions (anything
        // that isn't pure observation) separately from reads, so a trailing verified read can
        // never bless an unverified state change — the brightness-hallucination class.
        var mutatingCount = 0
        var mutatingVerified = 0
        var lastMutatingVerified: Boolean? = null
        var lastCallKey: String? = null         // for unnecessary-action detection
        var lastResultKey: String? = null
        var repeatCount = 0                      // REL-P2-1: consecutive identical (call,result) steps → break the no-progress loop
        var noProgressStreak = 0                 // REL-P2 stall: consecutive mutating steps that neither verified nor changed the screen
        var visionNext = false                  // the model asked for vision via read_state; carry it into the next observation
        var usedRead = false                    // §C.3 — did the run read the screen? (grounds a screen-derived answer)
        // Mobile-Money / USSD: the user approves the transfer (recipient + amount) ONCE; after that we must
        // NOT re-ask at every menu step — an interactive USSD session times out, and the user chose guided
        // navigation. Once approved, subsequent steps ON THE DIALER/USSD surface proceed without a fresh
        // confirmation. A PIN/credential/secure field is a separate, harder gate (TargetSemantics → BLOCK),
        // so this never lets a PIN be typed; the brain is also told to stop at the PIN and has none to type.
        var moneyOpApproved = false
        try {
            for (step in 1..MAX_STEPS) {
                coroutineContext.ensureActive()
                var before = Perception.observe(controller, visionNext)
                visionNext = false
                // Never hand our OWN UI to the brain: our command box is an editable field, and the model
                // will type the user's request INTO it (or every grounded action is refused as self-UI),
                // wasting steps. If we're still on our screen, step off and re-observe the real app behind us.
                if (before.appPackage == ctxApp.packageName) {
                    ensureOffOwnUi(ctxApp)
                    before = Perception.observe(controller, false)
                }
                // ===== SECURE INPUT BOUNDARY (MoMo PIN / password / OTP) =====
                // If this is a credential-entry screen, Tɛkyerɛma must go BLIND: this observation must NOT be
                // logged with content, serialized, or handed to the brain. We detect from structure only (no
                // PIN value is read out or recorded), discard the snapshot, and SUSPEND the loop until the user
                // has entered the secret and said "done". No further observe/OCR/decide happens while we wait,
                // so the PIN never enters the LLM/observation/log pipeline. On resume we re-check the screen
                // (§10) — we do NOT reason about anything that happened while blind.
                if (TargetSemantics.isCredentialScreen(before)) {
                    // Deliberately log a SAFE label only — never the screen text.
                    log(LogEntry.Kind.POLICY, "SENSITIVE_INPUT_DETECTED — Secure Input Mode: pausing observation, brain and logging until you finish.")
                    val resumed = awaitSecureInput()
                    if (!resumed) { finish(AgentStatus.STOPPED, stats); return }
                    continue   // re-observe a FRESH screen; the boundary re-checks it before any brain call
                }
                log(LogEntry.Kind.PERCEIVE, "source=${before.perception.source} reason=${before.perception.reason}" +
                    (if (before.strippedHiddenText > 0) " strippedHidden=${before.strippedHiddenText}" else "") +
                    (if (before.secureWindow) " secureWindow=true" else ""))
                stats = stats.copy(steps = step, perceptionRoutes = bump(stats.perceptionRoutes, before.perception.source))
                setStatus(AgentStatus.THINKING)

                val t0 = SystemClock.uptimeMillis()
                val decision = brain.decide(BrainContext(goal, before.toCompactJson(), history, geminiTools, openAiTools, convo))
                stats = stats.copy(decideLatencyMs = stats.decideLatencyMs + (SystemClock.uptimeMillis() - t0))

                when (decision) {
                    is AgentDecision.Done -> {
                        val ratio = if (mutatingCount == 0) 1.0 else mutatingVerified.toDouble() / mutatingCount
                        // §C.3 — a screen-DERIVED answer must be grounded in what was actually observed. If the
                        // run read the screen and the spoken answer asserts a specific number that is NOT on the
                        // last observed screen, it is not verified (the "weather 27°C with no temperature on
                        // screen" hallucination). Only applies when a read happened — general Q&A is untouched.
                        val ungroundedRead = usedRead && mutatingCount == 0 && !answerGroundedInScreen(decision.summary, before)
                        // COMPLETED only if: (a) the task changed nothing (a pure observation/answer
                        // task — success is having observed the answer), OR (b) the MOST RECENT
                        // state-changing action verified AND the run's overall verified ratio isn't
                        // poor. Otherwise the claim is not backed by evidence → COMPLETED_UNVERIFIED.
                        val substantiveOk = lastMutatingVerified == true && (mutatingCount < 3 || ratio >= 0.5)
                        // A clarification QUESTION ("…send to Logolo saying '…'?") asserts NO state change, so it
                        // is never "unverified" — it's a clean completion. Treating it as COMPLETED_UNVERIFIED
                        // (because an earlier step didn't verify) both spoke a wrong "…but I couldn't confirm it"
                        // tail AND stopped the voice layer's ask-card/mic-reopen (which keys off a trailing "?").
                        val isQuestion = decision.summary.trim().endsWith("?")
                        if (isQuestion || (!ungroundedRead && (mutatingCount == 0 || substantiveOk))) {
                            log(LogEntry.Kind.RESULT, decision.summary)
                            pushConvo("Tɛkyerɛma: ${decision.summary}")
                            finish(AgentStatus.COMPLETED, stats); return
                        }
                        val why = when {
                            ungroundedRead -> "answer asserts detail not present on the observed screen"
                            lastMutatingVerified != true -> "the most recent state-changing action did not verify"
                            else -> "only $mutatingVerified of $mutatingCount state-changing actions verified (ratio ${"%.2f".format(ratio)})"
                        }
                        // §1.7: keep the engineering diagnostic in the LOG only; the RESULT entry (which is
                        // spoken) stays a PLAIN sentence. VoiceController appends the honest "— but I couldn't
                        // confirm it" for COMPLETED_UNVERIFIED, so the user never hears the jargon prefix.
                        log(LogEntry.Kind.INFO, "unverified completion: $why (outcome not backed by observation)")
                        log(LogEntry.Kind.RESULT, decision.summary)
                        pushConvo("Tɛkyerɛma: ${decision.summary}")
                        finish(AgentStatus.COMPLETED_UNVERIFIED, stats); return
                    }
                    is AgentDecision.CannotDo -> {
                        log(LogEntry.Kind.RESULT, "CANNOT DO [${decision.reasonClass}]: ${decision.detail}")
                        pushConvo("Tɛkyerɛma: ${decision.detail}")
                        finish(AgentStatus.CANNOT_DO, stats); return
                    }
                    is AgentDecision.Fail -> {
                        log(LogEntry.Kind.ERROR, decision.reason)
                        pushConvo("Tɛkyerɛma: I couldn't do that.")
                        finish(AgentStatus.FAILED, stats); return
                    }
                    is AgentDecision.Malformed -> {
                        stats = stats.copy(malformedResponses = stats.malformedResponses + 1)
                        log(LogEntry.Kind.ERROR, "Malformed step: the reply described an action as text instead of calling a tool: ${decision.text.take(160)}")
                        history.add("Your last reply described an action in text instead of calling the tool — nothing happened. Call the tool.")
                        continue
                    }
                    is AgentDecision.CallTool -> {
                        val tool = registry.get(decision.name)
                        if (tool == null) {
                            log(LogEntry.Kind.ERROR, "Unknown tool '${decision.name}'.")
                            history.add("Tried unknown tool ${decision.name}")
                            continue
                        }
                        val expectation = Expects.parse(decision.expects)
                        // Mutating = changes device state (anything that isn't pure observation).
                        // Scope-based, so it stays generic — no per-tool flag, no app knowledge.
                        val mutating = tool.scope != com.tekyerema.policy.CapabilityScope.PERCEIVE &&
                            tool.scope != com.tekyerema.policy.CapabilityScope.COMMS_READ
                        if (tool.scope == com.tekyerema.policy.CapabilityScope.PERCEIVE) usedRead = true   // §C.3
                        log(LogEntry.Kind.PLAN, "Step $step: ${decision.name}(${shortArgs(decision.args)})" +
                            (decision.expects?.let { " expects[$it]" } ?: "") + (decision.rationale?.let { " $it" } ?: ""))
                        if (Expects.isVacuous(expectation) && tool.scope != com.tekyerema.policy.CapabilityScope.PERCEIVE)
                            stats = stats.copy(vacuousExpects = stats.vacuousExpects + 1)

                        // ---- target resolution (static, side-effect free) — before the gate ----
                        val target: Target? = if (tool.grounded) TargetResolver.parse(decision.args) else null
                        var resolved: ResolvedTarget? = null
                        var requestedLabel: String? = (target as? Target.ByLabel)?.label
                        if (target != null) {
                            when (val r = TargetResolver.resolveStatic(target, before)) {
                                is ResolveOutcome.Found -> resolved = r.target
                                is ResolveOutcome.Invalid -> {
                                    log(LogEntry.Kind.ACTION, "${decision.name} → rejected: ${r.detail}")
                                    history.add("${decision.name}(${shortArgs(decision.args)}) → rejected; ${r.detail}")
                                    continue
                                }
                                is ResolveOutcome.OnlyWeak, is ResolveOutcome.NotFound -> { /* label may be revealed by scrolling at execute time */ }
                            }
                        }

                        // ---- policy: target semantics + provenance, keyed on the resolved target, not the tool name ----
                        val cls = TargetSemantics.classify(decision.name, resolved?.element, requestedLabel, decision.args.boolOrDefault("submit", false), before)
                        val tags = Provenance.tagArgs(decision.args, goal, before)
                        val untrusted = Provenance.hasUntrusted(tags)
                        if (untrusted) log(LogEntry.Kind.POLICY, "provenance: ${Provenance.describe(tags)}")
                        if (cls.tier != null) log(LogEntry.Kind.POLICY, "target semantics: ${cls.reason} → ${cls.tier}")
                        val pd = policy.evaluate(tool, decision.args, hasUntrustedParams = untrusted, targetTier = cls.tier, hardBlock = cls.block)
                        when (pd.outcome) {
                            PolicyOutcome.BLOCK -> {
                                stats = stats.copy(blocked = stats.blocked + 1)
                                log(LogEntry.Kind.POLICY, "Blocked: ${pd.reason}")
                                history.add("${decision.name}(${shortArgs(decision.args)}) → BLOCKED by policy: ${pd.reason}. Do not retry it; if the goal needs it, call cannot_do.")
                                // A blocked substantive action did not happen: a Done right after must not read as verified.
                                if (mutating) { mutatingCount++; lastMutatingVerified = false }
                                continue
                            }
                            PolicyOutcome.CONFIRM -> {
                              if (moneyOpApproved && isDialerNavigation(before)) {
                                // The transfer (recipient + amount) was already approved once; the user chose guided
                                // navigation, so don't re-ask at every USSD menu step (it would time out). The PIN is
                                // still hard-blocked by TargetSemantics, and the brain is told to stop at the PIN.
                                log(LogEntry.Kind.POLICY, "Mobile Money already approved — continuing this menu step without asking again.")
                              } else {
                                // §safety (2026-09-27 audit): momo_send takes the SAME single structural read-back as every
                                // other consequential action — it is NOT bypassed. The brain gathers number + amount + reference,
                                // then the engine speaks ONE "Send ₵X to <number, digit-by-digit>? yes/no" (English + a Twi
                                // digit-by-digit rendering via confirmSpecFor) and waits. This is a single confirmation, not a
                                // per-menu-step nag (the deterministic driver still walks *170# itself and stops at the PIN),
                                // so it stays fast while closing the unintended-send / on-screen-injection path for a blind user.
                                // §19 semantic safety: describe the REAL consequence (recipient +
                                // message / callee) rather than "Tap element N?". Falls back to the
                                // policy prompt, then a semantic description, then the tool default.
                                val semantic = com.tekyerema.policy.ActionSemantics.confirmation(
                                    decision.name, resolved?.element, requestedLabel, before,
                                    decision.args.boolOrDefault("submit", false), decision.args.str("text")
                                )
                                // Prefer the action-describing sentence (§19); the policy's default is
                                // just tool.confirmationPrompt(args), so semantic never hides a safety note.
                                val basePrompt = semantic ?: pd.confirmationPrompt ?: tool.confirmationPrompt(decision.args)
                                // §1.12 — a blind user must hear WHO and WHICH NUMBER a call will go to
                                // before it happens; resolve the contact now and name the concrete target +
                                // number, not just the (possibly mis-heard) requested string.
                                val prompt = enrichCallConfirmation(decision.name, decision.args, basePrompt, ctxApp)
                                // §A.1 — a Twi rendering of the SAME action, built from structure (verbatim
                                // name + digit-by-digit number), for the core confirmable intents. Null for
                                // anything we can't render structurally → the English line is used.
                                val promptTwi = confirmSpecFor(decision.name, decision.args, ctxApp)
                                    ?.let { com.tekyerema.voice.ConfirmTemplates.twi(it) }
                                stats = stats.copy(confirmations = stats.confirmations + 1)
                                log(LogEntry.Kind.CONFIRM, prompt)
                                pushConvo("Tɛkyerɛma proposed: $prompt")   // so a follow-up correction can reference it
                                _state.update { it.copy(status = AgentStatus.WAITING_CONFIRMATION, pendingConfirmation = prompt, pendingConfirmationTwi = promptTwi) }
                                // Also float the same Approve/Decline over whatever app is in front,
                                // so the user needn't switch back to Tɛkyerɛma. Both routes call confirm().
                                OverlayConfirm.show(ctxApp, prompt)
                                val reply = confirmChannel.receive()
                                OverlayConfirm.hide(ctxApp)
                                _state.update { it.copy(pendingConfirmation = null, pendingConfirmationTwi = null) }
                                when (reply) {
                                    is ConfirmReply.Decline -> {
                                        log(LogEntry.Kind.POLICY, "Declined by user.")
                                        finish(AgentStatus.STOPPED, stats); return
                                    }
                                    is ConfirmReply.Revise -> {
                                        // §8/§9/§13/§14 — the user answered with an instruction, not yes/no. Don't run the
                                        // pending action; re-plan. The brain sees this note + the proposal (in conversation
                                        // memory) and adjusts recipient/app/message/number, or cancels, then proposes again
                                        // (which confirms again). No action leaks: nothing consequential runs without a fresh yes.
                                        log(LogEntry.Kind.POLICY, "User revised instead of approving: \"${reply.text}\"")
                                        pushConvo("User: ${reply.text}")
                                        history.add(
                                            "You proposed: \"$prompt\". The user did NOT approve it — they said: \"${reply.text}\". " +
                                            "Treat that as a correction or replacement of that pending action: change the recipient, app, " +
                                            "message wording, or number as they asked (or drop it if they no longer want it), then take the " +
                                            "next step toward the corrected action. Do NOT re-propose the identical action."
                                        )
                                        // The proposed action never ran (the user redirected before execution), so it is
                                        // NOT counted as an unverified mutation — that would wrongly taint a later Done.
                                        setStatus(AgentStatus.THINKING)   // lets the confirmation observer re-arm for the next proposal
                                        continue
                                    }
                                    is ConfirmReply.Approve -> {
                                        log(LogEntry.Kind.POLICY, "Confirmed by user.")
                                        // The first approval of a Mobile-Money / dialer action unlocks the rest of the
                                        // USSD menu navigation for this run (guided navigation) — never the PIN.
                                        if (decision.name == "mobile_money" || isDialerNavigation(before)) moneyOpApproved = true
                                        // §safety (2026-09-27 audit) — the target was resolved BEFORE this multi-second
                                        // confirmation wait; the screen can reflow (new message, keyboard, scroll) while the
                                        // user answers by voice, leaving the cached node/bounds pointing at whatever moved
                                        // into that rectangle → a wrong-target tap/send. Re-observe and re-resolve against a
                                        // FRESH snapshot; abort if a credential screen appeared, the app changed, or the
                                        // target can no longer be found. Scoped to a pre-resolved node (the only stale case).
                                        if (tool.grounded && resolved != null && target != null) {
                                            val fresh = Perception.observe(controller, false)
                                            if (TargetSemantics.isCredentialScreen(fresh)) {
                                                log(LogEntry.Kind.POLICY, "A credential screen appeared during confirmation — not acting.")
                                                history.add("${decision.name}(${shortArgs(decision.args)}) → not executed; a credential screen appeared while you confirmed.")
                                                continue
                                            }
                                            if (fresh.appPackage != before.appPackage) {
                                                log(LogEntry.Kind.ACTION, "${decision.name} → refused: screen changed during confirmation (was '${before.appPackage}', now '${fresh.appPackage}').")
                                                history.add("${decision.name}(${shortArgs(decision.args)}) → refused; the screen changed while you were confirming — re-observe and retry.")
                                                continue
                                            }
                                            when (val rr = TargetResolver.resolveStatic(target, fresh)) {
                                                is ResolveOutcome.Found -> { resolved = rr.target; before = fresh }
                                                else -> {
                                                    log(LogEntry.Kind.ACTION, "${decision.name} → refused: the target moved or disappeared during confirmation.")
                                                    history.add("${decision.name}(${shortArgs(decision.args)}) → refused; the target moved after you confirmed — re-observe and retry.")
                                                    continue
                                                }
                                            }
                                        }
                                    }
                                }
                              }
                            }
                            PolicyOutcome.ALLOW -> { /* proceed */ }
                        }

                        // ---- never operate our own control surface (self-exclusion) ----
                        if (tool.grounded && before.appPackage == ctxApp.packageName) {
                            val msg = "That screen is Tɛkyerɛma's own UI, not a target app — open the app you need first."
                            log(LogEntry.Kind.ACTION, "${decision.name} → refused: $msg")
                            history.add("${decision.name}(${shortArgs(decision.args)}) → refused; $msg")
                            if (mutating) { mutatingCount++; lastMutatingVerified = false }
                            continue
                        }
                        // ---- stale-state check for EVERY grounded primitive (plan §10 step 2) ----
                        if (tool.grounded) {
                            val livePkg = controller?.foregroundPackage()
                            if (!Verify.screenStillCurrent(before, livePkg)) {
                                stats = stats.copy(staleRefusals = stats.staleRefusals + 1)
                                val msg = "Screen changed (was '${before.appPackage}', now '$livePkg') before this action executed — re-observe and retry."
                                log(LogEntry.Kind.ACTION, "${decision.name} → refused: $msg")
                                history.add("${decision.name}(${shortArgs(decision.args)}) → refused (stale); $msg")
                                if (mutating) { mutatingCount++; lastMutatingVerified = false }
                                continue
                            }
                        }

                        // ---- execute (serialized) ----
                        setStatus(AgentStatus.ACTING)
                        val toolCtx = ToolContext(ctxApp, controller, NotificationStore, observedBefore = before, resolvedTarget = resolved)
                        val result = ActionQueue.run { tool.execute(decision.args, toolCtx) }
                        stats = stats.copy(actions = stats.actions + 1, channels = bump(stats.channels, result.channel))
                        log(LogEntry.Kind.ACTION, "${decision.name} → ${if (result.success) "ok" else "failed"} [${result.channel}]: ${result.message}")
                        if (decision.name == "read_state" && result.data?.optBoolean("vision") == true) visionNext = true

                        // ---- observe again, verify the CLAIM against a fresh observation ----
                        // Perception-only tools don't change the device: their own observation stands.
                        // Everything else is observed again, on the same route the decision used, and the
                        // claim is re-checked over a short window — a launched app or a sent message can
                        // take a moment to appear, and a single instant snapshot is a race (round 9 finding).
                        var afterSig: String? = null   // REL-P2 stall: post-action screen signature (null on PERCEIVE/secure → not counted)
                        val vr: com.tekyerema.verifier.VerificationResult
                        if (tool.scope == com.tekyerema.policy.CapabilityScope.PERCEIVE) {
                            vr = tool.verify(decision.args, toolCtx, before, before, result)
                        } else {
                            controller?.waitForSettle(800)   // REL-P1-3: cap the pre-verify settle (default 2500ms starves on animated/busy screens)
                            // SECURE INPUT BOUNDARY (verify path): a mutating action can LAND on a credential
                            // screen — the MoMo PIN prompt appears as the RESULT of confirming the amount, so it
                            // is first seen HERE as `after`, not at the top of the loop. observeForVerify inspects
                            // the raw a11y tree BEFORE any screenshot/OCR (Perception.observe can auto-escalate to
                            // a screenshot even when vision isn't requested) and never captures a credential
                            // screen. If we land on one we suspend verification; the top-of-loop boundary enters
                            // Secure Input Mode on the next iteration. Keeps a PIN/password out of screenshots+OCR.
                            var after = observeForVerify(controller, before.perception.usedVision)
                            if (TargetSemantics.isCredentialScreen(after)) {
                                vr = com.tekyerema.verifier.VerificationResult(true,
                                    "reached a secure-input screen — verification suspended, screen not observed")
                            } else {
                                var toolDefault = tool.verify(decision.args, toolCtx, before, after, result)
                                var v = OutcomeVerifier.verify(expectation, before, after, result.success, toolDefault, tool.verifiesOutcome)
                                var polls = 0
                                while (!v.verified && result.success && !Expects.isVacuous(expectation) && polls < 3) {   // REL-P1-3: 5→3 re-polls
                                    kotlinx.coroutines.delay(500)
                                    after = observeForVerify(controller, before.perception.usedVision)
                                    if (TargetSemantics.isCredentialScreen(after)) break
                                    toolDefault = tool.verify(decision.args, toolCtx, before, after, result)
                                    v = OutcomeVerifier.verify(expectation, before, after, result.success, toolDefault, tool.verifiesOutcome)
                                    polls++
                                }
                                afterSig = after.signature()   // REL-P2 stall: capture the post-action screen signature
                                vr = if (TargetSemantics.isCredentialScreen(after))
                                    com.tekyerema.verifier.VerificationResult(true, "reached a secure-input screen — verification suspended, screen not observed")
                                else if (polls > 0) v.copy(detail = v.detail + " (after ${polls} re-observation${if (polls > 1) "s" else ""})") else v
                            }
                        }
                        log(LogEntry.Kind.VERIFY, if (vr.verified) "verified — ${vr.detail}" else "NOT verified — ${vr.detail}")
                        if (tool.scope != com.tekyerema.policy.CapabilityScope.PERCEIVE) {
                            stats = if (vr.verified) stats.copy(verifiedTrue = stats.verifiedTrue + 1) else stats.copy(verifiedFalse = stats.verifiedFalse + 1)
                        }
                        // Track substantive (state-changing) outcomes separately: a trailing verified
                        // READ can never bless an unverified state change (the brightness class).
                        if (mutating) {
                            mutatingCount++
                            if (vr.verified) mutatingVerified++
                            lastMutatingVerified = vr.verified
                        }

                        // ---- REL-P2 no-progress STALL detection (research §4c) — generalizes the exact-repeat guard:
                        // a mutating action that ran but neither VERIFIED nor CHANGED the screen (identical signature)
                        // is making no progress, even when the model varies its action each step (a dead Compose/WebView
                        // control). Conservative — requires BOTH signals so a state-only change (checkbox) or a verified
                        // step never false-aborts. Nudge at 3; soft-abort (honest CANNOT_DO) at 5, well before MAX_STEPS.
                        if (mutating) {
                            val stalled = result.success && !vr.verified && afterSig != null && afterSig == before.signature()
                            if (stalled) noProgressStreak++ else noProgressStreak = 0
                            if (noProgressStreak == 3) history.add(
                                "The screen has NOT changed across the last 3 actions and none verified — you are stuck. Stop repeating; " +
                                "try a fundamentally different route (scroll, the app's own search, Back, a different control), or call cannot_do.")
                            if (noProgressStreak >= 5) {
                                log(LogEntry.Kind.INFO, "no-progress soft budget hit ($noProgressStreak stalled mutating steps) — stopping honestly.")
                                pushConvo("Tɛkyerɛma: I couldn't make progress on that.")
                                finish(AgentStatus.CANNOT_DO, stats); return
                            }
                        }

                        // ---- unnecessary-action detection: same call, same result as the previous step ----
                        val callKey = decision.name + shortArgs(decision.args)
                        val resultKey = result.message.take(200)
                        if (callKey == lastCallKey && resultKey == lastResultKey) {
                            stats = stats.copy(unnecessaryActions = stats.unnecessaryActions + 1)
                            repeatCount++
                            // REL-P2-1: an action repeating with the IDENTICAL result is making no progress (ACTION_CLICK
                            // can return "dispatched" while nothing changes on custom/Compose/WebView controls). After 2
                            // repeats, nudge the brain hard to change approach rather than burning the step budget.
                            if (repeatCount >= 2) history.add(
                                "That exact action produced the identical result ${repeatCount + 1} times in a row — it is changing " +
                                "nothing. Do NOT repeat it. Try a DIFFERENT element or approach (scroll, the app's own search, another " +
                                "control), or call cannot_do if the goal is truly blocked.")
                        } else repeatCount = 0
                        lastCallKey = callKey; lastResultKey = resultKey

                        // ---- recovery framing (hybrid architecture §3/§4/§9): a FAILED action never ends the
                        // task by itself. Distinguish an ACCELERATOR miss (a shortcut that didn't apply — fall
                        // back to the General Operator UI) from a general-operator primitive failure (re-observe
                        // and try another approach) from a genuine dead-end. This is generic: it keys on
                        // tool.isAccelerator + result.recoverable, never on any app. ----
                        val recKind = Recovery.classify(tool.isAccelerator, result.success, result.recoverable)
                        if (Recovery.isAcceleratorMiss(recKind)) stats = stats.copy(acceleratorMisses = stats.acceleratorMisses + 1)
                        val recovery = Recovery.hint(decision.name, recKind)
                        if (recovery.isNotEmpty()) log(LogEntry.Kind.INFO, "recovery:${recovery}")

                        history.add(
                            "${decision.name}(${shortArgs(decision.args)}) → ${if (result.success) "success" else "fail"} [${result.channel}]; " +
                                "verified=${vr.verified} (${vr.detail.take(100)}); ${result.message.take(140)}${recovery}"
                        )
                    }
                }
            }
            log(LogEntry.Kind.ERROR, "Reached the step limit without completing the task.")
            finish(AgentStatus.FAILED, stats)
        } catch (ce: CancellationException) {
            log(LogEntry.Kind.STATS, stats.summary())
            setStatus(AgentStatus.STOPPED)
            throw ce
        } catch (t: Throwable) {
            log(LogEntry.Kind.ERROR, "Error: ${t.message}")
            finish(AgentStatus.FAILED, stats)
        }
    }

    private fun finish(status: AgentStatus, stats: RunStats) {
        log(LogEntry.Kind.STATS, stats.summary())
        _state.update { it.copy(status = status, lastStats = stats.summary()) }
    }

    private fun bump(m: Map<String, Int>, k: String): Map<String, Int> = m + (k to ((m[k] ?: 0) + 1))

    /** Best-effort host extraction for the brain label — falls back to the raw URL if unparseable. */
    private fun hostOf(url: String): String =
        try { java.net.URI(url).host ?: url } catch (t: Throwable) { url }

    private fun shortArgs(args: JSONObject): String = args.toString().take(160)

    /**
     * Secure Input Mode wait (§4/§6/§8): become blind while the user enters their MoMo PIN. Sets
     * SECURE_INPUT — the voice layer then speaks the secure instruction and listens for "done" — and
     * SUSPENDS here until the user signals completion (or the run is stopped). While suspended NOTHING
     * observes the screen: no Perception.observe, no OCR, no brain.decide, no screen logging. Returns to
     * THINKING on completion so the caller re-observes a FRESH screen and the boundary re-checks it (§10).
     * Returns false if cancelled/stopped (never resumes observation in that case).
     */
    private suspend fun awaitSecureInput(): Boolean {
        setStatus(AgentStatus.SECURE_INPUT)
        val done = secureChannel.receive()
        if (done) setStatus(AgentStatus.THINKING)
        return done
    }

    /** True when the current screen is the phone/dialer/USSD surface — where an APPROVED Mobile-Money
     *  transfer's menu steps run. Used only to skip re-confirming AFTER the transfer was approved once
     *  (moneyOpApproved); a PIN/credential/secure field is blocked separately by TargetSemantics. */
    private fun isDialerNavigation(before: UiSnapshot): Boolean {
        val pkg = before.appPackage?.lowercase() ?: return false
        return pkg == "com.android.phone" || pkg.contains("dialer") || pkg.contains("incall") || pkg.contains("telecom")
    }

    /**
     * §1.12 — for a call confirmation, name the RESOLVED target and its number so a user who can't see the
     * dialer knows exactly who/what will be called. A direct number is read digit-by-digit; a contact name
     * is resolved to a single match and spoken as "<name> on <digits>". If the name is ambiguous or unknown
     * we keep the base prompt and let execute ask / fail honestly — we never invent a number here.
     */
    private fun enrichCallConfirmation(name: String, args: JSONObject, base: String, ctxApp: Context): String {
        if (name != "make_call") return base
        val number = args.str("number")?.filter { it.isDigit() || it == '+' }
        if (!number.isNullOrBlank()) return "Call ${spokenDigits(number)}?"
        val contact = args.str("contact")?.trim().orEmpty()
        if (contact.isEmpty()) return base
        val matches = try { ContactResolver.resolve(ctxApp, contact) } catch (t: Throwable) { emptyList() }
        return if (matches.size == 1) "Call ${matches[0].name} on ${spokenDigits(matches[0].number)}?" else base
    }

    /**
     * §C.3 — is a screen-derived answer grounded in what was observed? An answer with no specific numeric
     * claim is grounded (nothing to fabricate). If it asserts numbers (temperatures, prices, counts, times),
     * every such number must appear in the last observed screen text; otherwise the answer is ungrounded
     * (the "high 27 °C" with no temperature on screen case). Deliberately narrow — only numeric claims —
     * so ordinary descriptive summaries are not falsely flagged.
     */
    private fun answerGroundedInScreen(summary: String, screen: UiSnapshot): Boolean {
        val onScreen = (screen.summarize() + " " + screen.elements.joinToString(" ") { it.label ?: "" })
        val onScreenLc = onScreen.lowercase()
        // §P2.6 — a screen-derived answer must be supported by observed text. Check TWO classes of specific
        // claim (both must appear on screen); ordinary descriptive wording is not flagged:
        //  1) numbers (temperatures/prices/counts) — the "27 °C with no temperature on screen" case;
        //  2) quoted phrases — the answer claims verbatim screen text ("the button says 'Send'").
        val nums = Regex("\\d+").findAll(summary).map { it.value }.toSet()
        val screenNums = Regex("\\d+").findAll(onScreen).map { it.value }.toSet()
        if (!nums.all { it in screenNums }) return false
        val quoted = Regex("[\"“”']([^\"“”']{2,40})[\"“”']").findAll(summary).map { it.groupValues[1].lowercase().trim() }.toList()
        if (quoted.any { it.isNotBlank() && it !in onScreenLc }) return false
        return true
    }

    /** Read a phone number as separate characters so it is unambiguous when spoken ("0 2 4 …"). */
    private fun spokenDigits(number: String): String =
        number.map { if (it == '+') "plus" else it.toString() }.joinToString(" ")

    /**
     * §A.1 — structured confirmation slots for the core confirmable intents, so the Twi confirmation can be
     * rendered from a template (name verbatim, number/amount digit-by-digit) rather than machine-translated.
     * Returns null for actions we can't render structurally (the English line is spoken instead).
     */
    private fun confirmSpecFor(name: String, args: JSONObject, ctxApp: Context): Spec? {
        return when (name) {
            "make_call" -> {
                val number = args.str("number")?.filter { it.isDigit() || it == '+' }
                if (!number.isNullOrBlank()) return Spec(Spec.Kind.CALL, number = number)
                val contact = args.str("contact")?.trim().orEmpty()
                if (contact.isEmpty()) return null
                val m = try { ContactResolver.resolve(ctxApp, contact) } catch (t: Throwable) { emptyList() }
                if (m.size == 1) Spec(Spec.Kind.CALL, name = m[0].name, number = m[0].number) else null
            }
            // Mobile Money (§A.2) carries confirmation slots so the readback names the resolved recipient
            // and amount in the user's language.
            "mobile_money" -> when (args.str("_kind")) {
                "send_money" -> Spec(Spec.Kind.SEND_MONEY, name = args.str("_recipient"), number = args.str("_number"), amount = args.str("_amount"))
                "buy_airtime" -> Spec(Spec.Kind.BUY_AIRTIME, amount = args.str("_amount"))
                else -> null
            }
            // Deterministic *170# send driver: read back the CEDI amount + recipient number digit-by-digit so a
            // units (pesewa/cedi) or wrong-number mistake is caught BEFORE it drives the menu.
            "momo_send" -> Spec(Spec.Kind.SEND_MONEY, number = args.str("number"), amount = args.str("amount"))
            // §A.1 — the recipient must be named in the readback. ReplyToNotificationTool declares `sender`
            // (+ `message`); the old `_title` arg never existed, so the Twi confirmation asked "send a message
            // to them?" without naming who. Use the real args so the safety-critical recipient is spoken.
            "reply_to_notification" -> Spec(Spec.Kind.SEND_MESSAGE, name = args.str("sender"), message = args.str("message"))
            else -> null
        }
    }

    /**
     * Step OFF Tɛkyerɛma's own UI before observing/acting. Our command box is an editable field: if the model
     * sees it, it types the user's request INTO it (then the action is refused as self-UI — a wasted step and
     * a confusing "type … and submit?" prompt). Our UI also launches in its own task, so a single BACK can
     * miss. Retry: BACK to dismiss; if still on us, bring a REAL app to front — the app the user was last on
     * (relaunching it reliably pushes our screen to the background) or HOME — and VERIFY we left before giving
     * up. Keyed only on our own package; no per-app logic (plan §1.22).
     */
    private suspend fun ensureOffOwnUi(ctxApp: Context) {
        repeat(4) {
            if (controller?.foregroundPackage() != ctxApp.packageName) return
            controller?.globalAction("back")
            controller?.waitForSettle(1000)
            if (controller?.foregroundPackage() != ctxApp.packageName) {
                log(LogEntry.Kind.INFO, "Dismissed Tɛkyerɛma's own screen to reveal the app underneath before operating.")
                return
            }
            // Still on our own screen — bring a real app forward. The app the user was last on is most likely
            // what they mean ("read my screen" while in Messages → Messages); fall back to HOME.
            val prior = controller?.priorForegroundPackage()?.takeIf { it != ctxApp.packageName }
            val relaunched = prior?.let { pkg ->
                try {
                    ctxApp.packageManager.getLaunchIntentForPackage(pkg)
                        ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        ?.also { ctxApp.startActivity(it) } != null
                } catch (t: Throwable) { false }
            } ?: false
            if (relaunched) {
                controller?.waitForSettle(1500)
                if (controller?.foregroundPackage() != ctxApp.packageName) {
                    log(LogEntry.Kind.INFO, "Returned to the app you were on ($prior) before operating.")
                    return
                }
            } else {
                controller?.globalAction("home")
                controller?.waitForSettle(1200)
            }
        }
        if (controller?.foregroundPackage() == ctxApp.packageName)
            log(LogEntry.Kind.INFO, "Still on Tɛkyerɛma's own screen after retries — close it and try again if the next step is refused.")
    }

    /**
     * Observe for the VERIFY step while honoring the Secure Input boundary. Perception.observe may screenshot
     * + OCR even when vision is not requested (auto-escalation on a foregrounded screen with 0 actionable
     * elements), so before any capture we inspect the RAW a11y tree — a snapshot that never screenshots. If it
     * is a credential-entry screen (MoMo PIN / password / OTP) we return that tree as-is and NEVER capture it;
     * the caller suspends verification and the top-of-loop boundary enters Secure Input Mode next iteration.
     */
    private suspend fun observeForVerify(controller: AccessibilityController?, wantVision: Boolean): com.tekyerema.accessibility.UiSnapshot {
        val rawTree = try { controller?.takeIf { it.ready }?.buildSnapshot() } catch (t: Throwable) { null }
        if (rawTree != null && TargetSemantics.isCredentialScreen(rawTree)) return rawTree
        return Perception.observe(controller, wantVision)
    }

    private fun setStatus(s: AgentStatus) = _state.update { it.copy(status = s) }
    private fun setGoal(g: String) = _state.update { it.copy(goal = g) }
    private fun clearLog() = _state.update { it.copy(log = emptyList()) }
    private fun log(kind: LogEntry.Kind, text: String) {
        val entry = LogEntry(System.currentTimeMillis(), kind, text)
        _state.update { it.copy(log = (it.log + entry).takeLast(200)) }
        Log.i(TAG, "[${kind.name}] $text")
        // Persist to a file too: logcat's ring buffer rotates during slow (~10-min) real-model
        // tasks, losing the trace. The file is the auditable record (plan §22, round-9 finding).
        try { traceFile?.appendText("[${kind.name}] ${text.replace("\n", " ")}\n") } catch (t: Throwable) {}
    }

    /** Fresh trace file per task under filesDir/traces; pull with `adb ... run-as`/`cat` or the
     *  debug receiver's --es dumptrace. Keeps the newest 40. */
    private fun openTrace(goal: String) {
        val ctx = appContext ?: return
        try {
            val dir = java.io.File(ctx.filesDir, "traces").apply { mkdirs() }
            dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(39)?.forEach { it.delete() }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            val f = java.io.File(dir, "trace-$stamp.log")
            f.writeText("GOAL: $goal\n")
            traceFile = f
        } catch (t: Throwable) { traceFile = null }
    }
}
