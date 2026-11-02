package com.tekyerema.teach

import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.tekyerema.accessibility.AccessibilityController
import com.tekyerema.accessibility.TekyeremaAccessibilityService
import com.tekyerema.agent.AgentEngine
import com.tekyerema.agent.FastOutcome
import com.tekyerema.agent.StepAside
import com.tekyerema.core.Privacy
import com.tekyerema.core.config.AppConfig
import com.tekyerema.notifications.NotificationStore
import com.tekyerema.policy.TargetSemantics
import com.tekyerema.tools.ReadScreenTool
import com.tekyerema.tools.ToolContext
import com.tekyerema.voice.SecureStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Teach mode ("show me once", MASTER_STATUS #24) — the two doors into it.
 *
 *  - [handle]: what the user SAYS ("watch me", "stop, call it data bundle", "data bundle", "what skills do I have").
 *    Called by LocalFastRouter.tryHandle with the user's own controls, ahead of the classifier — a taught skill's name
 *    is theirs. Returns a spoken outcome, or an agent goal when a replay hands over, or null (not ours).
 *  - [onAccessibilityEvent]: what the user DOES, forwarded by TekyeremaAccessibilityService while recording.
 *
 * Config: `teach.enabled` (default true) switches the whole feature; `teach.maxSteps`, `teach.maxRecordMs`,
 * `teach.readScreenAfter` tune it (assets/config/teach.json).
 */
object TeachCommands {
    private const val TAG = "Tekyerema"

    /** [outcome] = say this; [agentGoal] = hand this to the normal agent (a replay stopped and the agent takes the rest). */
    class Result(val outcome: FastOutcome? = null, val agentGoal: String? = null)

    fun enabled(): Boolean = AppConfig.bool("teach.enabled", true)

    val session: TeachSession by lazy {
        TeachSession(TeachStore.shared, TeachRecorder(maxSteps = AppConfig.int("teach.maxSteps", 30).coerceIn(5, 80),
            maxMs = AppConfig.long("teach.maxRecordMs", 10 * 60_000L).coerceIn(60_000L, 30 * 60_000L)))
    }

    // ---------------------------------------------------------------- what the user says

    suspend fun handle(ctx: Context, text: String, controller: AccessibilityController?): Result? {
        if (!enabled()) return null
        if (!TeachStore.shared.isLoaded) withContext(Dispatchers.IO) { TeachStore.shared.ensureLoaded(ctx) }
        val reply = session.handle(text) ?: return null
        reply.run?.let { return replay(ctx, it, controller) }
        Log.i(TAG, "[TEACH] phase=${session.phase} listen=${reply.listenAfter}")
        return Result(FastOutcome("TEACH", reply.spoken, systemVoice = true, ok = reply.ok, listenAfter = reply.listenAfter))
    }

    private suspend fun replay(ctx: Context, name: String, controller: AccessibilityController?): Result {
        val skill = TeachStore.shared.get(name)
            ?: return Result(FastOutcome("TEACH_RUN", "I don't have “$name” any more.", systemVoice = true, ok = false))
        val c = controller
        if (c == null || !c.ready) return Result(FastOutcome("TEACH_RUN", com.tekyerema.tools.NO_SERVICE, systemVoice = true, ok = false))
        if (AgentEngine.isBusy) return Result(FastOutcome("TEACH_RUN", "I'm in the middle of something else. Say stop first, then ask again.", systemVoice = true, ok = false))
        // The steps act on the app in front, so our own screen steps aside first (as for "go back", "open WhatsApp").
        if (StepAside.enabled()) com.tekyerema.ui.OwnUi.leave()
        val t0 = System.currentTimeMillis()
        val result = try { ReplayEngine.run(skill, ReplayHostAndroid(ctx, c)) }
            catch (ce: CancellationException) { throw ce }
            catch (t: Throwable) { Log.w(TAG, "[TEACH] replay threw ${t.javaClass.simpleName}"); null }
        val words = "ran your “$name” skill"
        Log.i(TAG, "[TEACH] replay '${Privacy.content(name)}' steps=${skill.steps.size} → ${result?.javaClass?.simpleName} ${System.currentTimeMillis() - t0} ms")
        return when (result) {
            is ReplayResult.Done -> {
                audit(words, true)
                var said = "Done."
                if (result.endsAtPrivate) said += " That is where the private part starts, so you take it from here."
                else if (AppConfig.bool("teach.readScreenAfter", true)) {
                    // What the skill was for is usually on the last screen (a balance, a status): read it, through the reader's own
                    // privacy rules (a PIN / password / code screen, or a denied app, is never read).
                    val r = try {
                        // the same gate as every other door: a capability switched off, or a policy that is not a plain ALLOW, reads nothing
                        if (com.tekyerema.tools.ToolManifest.isAvailable(ReadScreenTool) &&
                            com.tekyerema.policy.PolicyEngine().evaluate(ReadScreenTool, JSONObject()).outcome == com.tekyerema.policy.PolicyOutcome.ALLOW)
                            ReadScreenTool.execute(JSONObject(), ToolContext(ctx, c, NotificationStore)) else null
                    } catch (_: Throwable) { null }
                    if (r != null && r.success && r.message.isNotBlank()) said += " " + r.message
                }
                Result(FastOutcome("TEACH_RUN", said, systemVoice = false, ok = true, acted = true))
            }
            is ReplayResult.Private -> {
                audit(words + " as far as the private screen", true)
                Result(FastOutcome("TEACH_RUN", "This is a private screen, so I've stopped. You take it from here.", systemVoice = true, ok = true))
            }
            is ReplayResult.HandOver -> {
                audit(words + ", then handed over", true)
                // The user hears why the replay stopped before the agent speaks ("I couldn't find Data, so I'll do the rest my
                // way."): the agent's first words can be seconds away. The router takes the goal (and ignores the outcome), so
                // the line is spoken here, once.
                val said = handOverSpoken(result)
                say(said)
                Result(outcome = FastOutcome("TEACH_RUN", said, systemVoice = true, ok = true), agentGoal = handOverGoal(result))
            }
            ReplayResult.Cancelled -> Result(FastOutcome("TEACH_RUN", "Stopped.", systemVoice = true, ok = true))
            null -> Result(FastOutcome("TEACH_RUN", "Sorry, I couldn't run “$name” just now.", systemVoice = true, ok = false))
        }
    }

    /** The rest of a stopped replay as one request for the normal agent — which finds its own way and, for anything that
     *  sends, pays, saves or consents, asks with its usual read-back. */
    internal fun handOverGoal(h: ReplayResult.HandOver): String {
        val steps = StepWords.asGoal(h.remaining).replaceFirstChar { it.uppercase() }
        val ask = if (h.why == HandOverWhy.NEEDS_TEXT) " Ask me what to type before you type it." else ""
        return "${h.line} Do the rest for me: $steps.$ask"
    }

    /** The one short line the user hears when a replay hands over: why it stopped, and that the rest is done another way. */
    internal fun handOverSpoken(h: ReplayResult.HandOver): String = when (h.why) {
        HandOverWhy.NEEDS_YES -> "This step needs your yes, so I'll do the rest my way and ask you first."
        HandOverWhy.NEEDS_TEXT -> "I'll do the rest my way, and ask you what to type."
        else -> h.line.trim().trimEnd('.', '!').trim().let { if (it.isEmpty()) "I'll do the rest my way." else "$it, so I'll do the rest my way." }
    }

    private fun audit(words: String, ok: Boolean) {
        try { com.tekyerema.audit.AuditLog.record("fast", if (ok) com.tekyerema.audit.AuditLog.DONE else com.tekyerema.audit.AuditLog.FAILED,
            words, com.tekyerema.audit.AuditLog.newTask()) } catch (_: Throwable) {}
    }

    // ---------------------------------------------------------------- what the user does

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null
    private var earliest = Long.MAX_VALUE
    @Volatile private var ctl: AccessibilityController? = null
    private var lastTextCheck = 0L

    /** Called by the accessibility service for every event; a no-op unless a recording is on. Never throws. */
    fun onAccessibilityEvent(service: TekyeremaAccessibilityService, event: AccessibilityEvent) {
        try {
            if (!session.recording) return
            if (!enabled() || AgentEngine.isBusy) return   // what the agent itself taps is not the user's demonstration
            val raw = TeachEventSource.toRaw(service, event) ?: return
            val secure = SecureStep.active() || Privacy.secureStep
            session.onEvent(raw, secure)?.let { notice -> announce(notice); return }
            if (!session.recording || raw.type == RawEvent.Type.SCROLLED) return
            // A moment later the whole screen is looked at: a PIN pad has no flag on its keys, so the screen is what tells.
            if (raw.type == RawEvent.Type.TEXT_CHANGED) { if (raw.timeMs - lastTextCheck < 1_500L) return; lastTextCheck = raw.timeMs }
            requestCheck(service, raw.timeMs)
        } catch (t: Throwable) { Log.w(TAG, "[TEACH] event skipped: ${t.javaClass.simpleName}") }
    }

    private fun announce(line: String) {
        Log.i(TAG, "[TEACH] stopped by itself, phase=${session.phase}")
        say(line)
    }

    private fun say(line: String) {
        try { com.tekyerema.voice.VoiceEngine.announce(line, listenAfter = false) } catch (_: Throwable) {}
    }

    /**
     * The user pressed Back or Home while a recording is on (forwarded by the service's key filter, key-up only; the key is
     * never consumed). Back that only hides the keyboard is not a step. What the agent presses itself is not recorded.
     */
    fun onKey(service: TekyeremaAccessibilityService, keyCode: Int, action: Int) {
        try {
            if (action != android.view.KeyEvent.ACTION_UP) return
            if (keyCode != android.view.KeyEvent.KEYCODE_BACK && keyCode != android.view.KeyEvent.KEYCODE_HOME) return
            if (!session.recording) return
            if (!enabled() || AgentEngine.isBusy) return
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && service.keyboardUp()) return
            val raw = TeachEventSource.keyToRaw(service, keyCode, service.foregroundPackage()) ?: return
            val secure = SecureStep.active() || Privacy.secureStep
            session.onEvent(raw, secure)?.let { notice -> announce(notice); return }
            if (session.recording) requestCheck(service, raw.timeMs)
        } catch (t: Throwable) { Log.w(TAG, "[TEACH] key skipped: ${t.javaClass.simpleName}") }
    }

    private fun requestCheck(c: AccessibilityController, since: Long) {
        ctl = c
        synchronized(this) { if (since < earliest) earliest = since }
        if (worker?.isActive != true) worker = scope.launch { for (u in signal) checkScreen() }
        signal.trySend(Unit)
    }

    /** Is the screen in front a credential screen? Then recording stops and the steps done on it are dropped. */
    private suspend fun checkScreen() {
        val since = synchronized(this) { val v = earliest; earliest = Long.MAX_VALUE; v }
        if (since == Long.MAX_VALUE || !session.recording) return
        delay(150)
        val c = ctl ?: return
        val snap = try { c.buildSnapshot() } catch (_: Throwable) { return }
        if (snap.appPackage != null && TargetSemantics.isCredentialScreen(snap)) session.onPrivateScreen(since)?.let { announce(it) }
    }
}
