package com.tekyerema.voice

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.tekyerema.agent.AgentEngine
import com.tekyerema.agent.AgentStatus

/**
 * A-08 — a Quick Settings tile: pull down the shade, tap "Tɛkyerɛma", speak. While Tɛkyerɛma is working the tile
 * shows as active and tapping it STOPS everything (J-14 kill switch from the shade, in any app). The voice screen is
 * opened in front (startActivityAndCollapse) so the microphone is allowed to record.
 */
class TalkTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (!com.tekyerema.core.config.FeatureFlags.isEnabled("A-08", true)) return
        if (busy()) {
            Log.i("Tekyerema", "[TRIGGER] quick settings tile → STOP (kill switch)")
            VoiceEngine.cancel()
        } else {
            Log.i("Tekyerema", "[TRIGGER] quick settings tile → talk")
            val i = Intent(this, com.tekyerema.ui.VoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { startActivityAndCollapse(i) } catch (t: Throwable) { Log.w("Tekyerema", "[TRIGGER] tile launch failed: ${t.message}") }
        }
        refresh()
    }

    private fun busy(): Boolean {
        val st = AgentEngine.state.value.status
        return VoiceEngine.isRunning || st == AgentStatus.THINKING || st == AgentStatus.ACTING ||
            st == AgentStatus.WAITING_CONFIRMATION || st == AgentStatus.SECURE_INPUT
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val b = try { busy() } catch (_: Throwable) { false }
        tile.state = if (b) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Tɛkyerɛma"
        tile.contentDescription = if (b) "Tɛkyerɛma is working. Tap to stop." else "Talk to Tɛkyerɛma"
        tile.updateTile()
    }
}
