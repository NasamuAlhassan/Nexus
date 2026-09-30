package com.tekyerema

import android.app.Application
import com.tekyerema.agent.AgentEngine
import com.tekyerema.core.NetworkMonitor
import com.tekyerema.models.ModelManager
import com.tekyerema.voice.VoiceEngine

class TekyeremaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // X-02: versioned JSON config (assets/config) + local overrides — loaded before anything reads a tunable.
        com.tekyerema.core.config.AppConfig.load(this)
        com.tekyerema.core.config.Registrations.registerAll()   // X-01: capability registry (flags, API gates, checks)
        // WS-C: the user's standing choices (execution mode, cloud, providers, budgets, scopes…) — before anything reads them.
        com.tekyerema.operator.OperatorPolicy.init(com.tekyerema.operator.PrefsOperatorStore(this))
        com.tekyerema.core.DeviceProfile.init(this)             // N-08: RAM class → how many models stay resident
        NetworkMonitor.init(this)   // online/offline detection for hybrid routing (§32)
        // A-13 safety net: re-arm the user's schedules (an alarm can be lost to a force-stop or a clock change).
        try { com.tekyerema.agent.triggers.ScheduledTasks.rescheduleAll(this) } catch (_: Throwable) {}
        // W8: local telemetry and resources (Q-01..Q-10), the action audit (J-12), the message index (H-09), and the
        // retention sweep of old traces / trajectories (J-15).
        com.tekyerema.core.telemetry.Telemetry.init(this)
        com.tekyerema.core.telemetry.Resources.init(this)
        com.tekyerema.audit.AuditLog.init(this)
        com.tekyerema.notifications.MessageIndex.init(this)
        com.tekyerema.core.Privacy.sweepAll(filesDir)
        // Coaching tails ("You can say cancel to stop.") shrink after enough hearings — the count survives restarts.
        com.tekyerema.voice.Coaching.store = getSharedPreferences("tekyerema_coaching", MODE_PRIVATE)
        ModelManager.init(this)     // §Part D — single model store; first-run download is the delivery path
        AgentEngine.init(this)
        VoiceEngine.init(this)
        // D: the computer connection (MCP over adb) — idle until the user turns it on (OperatorPolicy.remoteControlEnabled).
        com.tekyerema.mcp.RemoteControl.init(this)
        // Phone care: the low-battery alert listens to the battery (off unless the user switched it on).
        try { com.tekyerema.phonecare.PhoneAlerts.init(this) } catch (_: Throwable) {}
    }

    /** N-08: release on-device models under memory pressure instead of being killed for them. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.tekyerema.core.telemetry.Telemetry.noteTrim(level)   // Q-10
        VoiceEngine.onTrimMemory(level)
    }
}
