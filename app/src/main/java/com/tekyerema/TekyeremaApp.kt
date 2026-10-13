package com.tekyerema

import android.app.Application
import com.tekyerema.agent.AgentEngine
import com.tekyerema.core.NetworkMonitor
import com.tekyerema.models.ModelManager
import com.tekyerema.voice.VoiceEngine

class TekyeremaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // P3-02 (13 October 2026): the first start after an install overran the 10-second start-up window on a busy emulator
        // (`am_anr … failed to complete startup`), the process was killed and the accessibility service was marked crashed.
        // Each stage is timed so the slow one can be found and moved off this path; the line is one number per stage.
        val t0 = android.os.SystemClock.uptimeMillis()
        val marks = StringBuilder()
        var last = t0
        fun stage(name: String) { val now = android.os.SystemClock.uptimeMillis(); marks.append(name).append('=').append(now - last).append(' '); last = now }
        // X-02: versioned JSON config (assets/config) + local overrides — loaded before anything reads a tunable.
        com.tekyerema.core.config.AppConfig.load(this); stage("config")
        com.tekyerema.core.config.Registrations.registerAll(); stage("registry")   // X-01: capability registry (flags, API gates, checks)
        // P3-02 round 3: the heavy singletons (regex tables), the history store and the app list warm on a background thread
        // from here on, so the stages below find them ready instead of initialising them on the main thread (core/StartupWarm).
        com.tekyerema.core.StartupWarm.start(this); stage("warm")
        // WS-C: the user's standing choices (execution mode, cloud, providers, budgets, scopes…) — before anything reads them.
        com.tekyerema.operator.OperatorPolicy.init(com.tekyerema.operator.PrefsOperatorStore(this)); stage("policy")
        com.tekyerema.core.DeviceProfile.init(this); stage("device")             // N-08: RAM class → how many models stay resident
        NetworkMonitor.init(this); stage("network")   // online/offline detection for hybrid routing (§32)
        // A-13 safety net: re-arm the user's schedules (an alarm can be lost to a force-stop or a clock change).
        try { com.tekyerema.agent.triggers.ScheduledTasks.rescheduleAll(this) } catch (_: Throwable) {}
        stage("schedules")
        // W8: local telemetry and resources (Q-01..Q-10), the action audit (J-12), the message index (H-09), and the
        // retention sweep of old traces / trajectories (J-15).
        com.tekyerema.core.telemetry.Telemetry.init(this)
        com.tekyerema.core.telemetry.Resources.init(this); stage("telemetry")
        com.tekyerema.audit.AuditLog.init(this); stage("audit")
        com.tekyerema.notifications.MessageIndex.init(this); stage("messages")
        // The retention sweep walks two directories: off the main thread (P3-02 round 3, 13 October 2026 — 0.3 s cold).
        val dir = filesDir
        Thread({ try { com.tekyerema.core.Privacy.sweepAll(dir) } catch (_: Throwable) {} }, "tekyerema-sweep").start(); stage("sweep")
        // Coaching tails ("You can say cancel to stop.") shrink after enough hearings — the count survives restarts.
        com.tekyerema.voice.Coaching.store = getSharedPreferences("tekyerema_coaching", MODE_PRIVATE)
        ModelManager.init(this); stage("models")     // §Part D — single model store; first-run download is the delivery path
        AgentEngine.init(this); stage("agent")
        VoiceEngine.init(this); stage("voice")
        // D: the computer connection (MCP over adb) — idle until the user turns it on (OperatorPolicy.remoteControlEnabled) —
        // and phone care's low-battery listener: neither is needed in the first seconds, and together they took 0.85 s of a
        // 9.7-s cold start (P3-02 round 3, 13 October 2026). Three seconds later, on the main thread as before.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try { com.tekyerema.mcp.RemoteControl.init(this) } catch (_: Throwable) {}
            try { com.tekyerema.phonecare.PhoneAlerts.init(this) } catch (_: Throwable) {}
        }, 3_000L)
        stage("mcp+alerts(deferred)")
        android.util.Log.i("Tekyerema", "[STARTUP] onCreate ${android.os.SystemClock.uptimeMillis() - t0} ms: $marks")
    }

    /** N-08: release on-device models under memory pressure instead of being killed for them. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.tekyerema.core.telemetry.Telemetry.noteTrim(level)   // Q-10
        VoiceEngine.onTrimMemory(level)
    }
}
