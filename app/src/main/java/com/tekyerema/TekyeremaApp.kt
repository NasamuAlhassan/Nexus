package com.tekyerema

import android.app.Application
import com.tekyerema.agent.AgentEngine
import com.tekyerema.core.NetworkMonitor
import com.tekyerema.models.ModelManager
import com.tekyerema.voice.VoiceEngine

class TekyeremaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NetworkMonitor.init(this)   // online/offline detection for hybrid routing (§32)
        ModelManager.init(this)     // §Part D — single model store; first-run download is the delivery path
        AgentEngine.init(this)
        VoiceEngine.init(this)
    }
}
