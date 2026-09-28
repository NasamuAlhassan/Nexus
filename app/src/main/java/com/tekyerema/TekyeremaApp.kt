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
        com.tekyerema.core.DeviceProfile.init(this)             // N-08: RAM class → how many models stay resident
        NetworkMonitor.init(this)   // online/offline detection for hybrid routing (§32)
        // A-13 safety net: re-arm the user's schedules (an alarm can be lost to a force-stop or a clock change).
        try { com.tekyerema.agent.triggers.ScheduledTasks.rescheduleAll(this) } catch (_: Throwable) {}
        ModelManager.init(this)     // §Part D — single model store; first-run download is the delivery path
        AgentEngine.init(this)
        VoiceEngine.init(this)
    }

    /** N-08: release on-device models under memory pressure instead of being killed for them. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        VoiceEngine.onTrimMemory(level)
    }
}
