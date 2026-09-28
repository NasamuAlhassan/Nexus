package com.tekyerema.audit

import android.content.Context
import android.media.AudioManager

/**
 * J-13 — undo for reversible actions. Before a request runs, the device state it could change is snapshotted
 * ([DeviceState.capture]); afterwards the difference becomes a list of inverse operations, grouped per request
 * (v1.1: a compound task is reversed as a whole, last change first). "Undo" / "take that back" reverses the most
 * recent group within `audit.undo.ttlMs` (10 minutes).
 *
 * Reversible: media and ring volume, ringer mode, screen brightness (with WRITE_SETTINGS), the flashlight, voice
 * preferences and the emergency contact, a schedule or a notification rule just created. Messages sent, calls made
 * and payments are NOT reversible — undo says so plainly instead of pretending.
 */
sealed class Inverse {
    data class Volume(val stream: Int, val level: Int) : Inverse()
    data class Ringer(val mode: Int) : Inverse()
    data class Brightness(val mode: Int, val level: Int) : Inverse()
    data class Torch(val on: Boolean) : Inverse()
    data class Prefs(val values: Map<String, String>) : Inverse()
    data class RemoveSchedule(val id: String) : Inverse()
    data class RemoveRule(val id: String) : Inverse()
    /** Something that happened and can't be taken back ("the message to Ama"). */
    data class Irreversible(val what: String) : Inverse()
}

data class UndoGroup(val t: Long, val label: String, val inverses: List<Inverse>)

object UndoLog {
    private val groups = ArrayDeque<UndoGroup>()

    @Synchronized
    fun push(group: UndoGroup) {
        if (group.inverses.isEmpty() || !com.tekyerema.core.config.FeatureFlags.isEnabled("J-13", true)) return
        groups.addLast(group)
        while (groups.size > 10) groups.removeFirst()
    }

    /** The newest group still inside the undo window, removed from the log; null if there's nothing to undo. */
    @Synchronized
    fun pop(nowMs: Long = System.currentTimeMillis(),
            ttlMs: Long = com.tekyerema.core.config.AppConfig.long("audit.undo.ttlMs", 600_000L)): UndoGroup? {
        while (groups.isNotEmpty() && nowMs - groups.last().t > ttlMs) groups.removeLast()
        return groups.removeLastOrNull()
    }

    @Synchronized fun clear() = groups.clear()
}

/** A snapshot of the reversible device state (pure diff; Android capture/apply). */
data class DeviceSnap(
    val music: Int?, val ring: Int?, val ringer: Int?,
    val brightnessMode: Int?, val brightness: Int?, val torch: Boolean?,
    val prefs: Map<String, String>
)

object DeviceState {
    /** Voice preferences and the emergency contact — restored as a whole if any changed. */
    private val PREF_KEYS = listOf("speech_rate", "verbosity", "speech_pitch", "profile", "vad_strategy",
        "emergency_contact_name", "emergency_contact_number")

    @Volatile private var torchOn: Boolean? = null
    @Volatile private var torchWatched = false

    fun capture(ctx: Context): DeviceSnap {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val cr = ctx.contentResolver
        watchTorch(ctx)
        val prefs = ctx.getSharedPreferences(com.tekyerema.settings.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        return DeviceSnap(
            music = am?.getStreamVolume(AudioManager.STREAM_MUSIC),
            ring = am?.getStreamVolume(AudioManager.STREAM_RING),
            ringer = am?.ringerMode,
            brightnessMode = try { android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE) } catch (_: Throwable) { null },
            brightness = try { android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS) } catch (_: Throwable) { null },
            torch = torchOn,
            prefs = PREF_KEYS.associateWith { k -> prefs.all[k]?.toString() ?: "" }
        )
    }

    /** Pure: the inverses that put [after] back to [before], in the order to apply them. */
    fun diff(before: DeviceSnap, after: DeviceSnap): List<Inverse> = buildList {
        if (before.music != null && before.music != after.music) add(Inverse.Volume(AudioManager.STREAM_MUSIC, before.music))
        if (before.ringer != null && before.ringer != after.ringer) add(Inverse.Ringer(before.ringer))
        if (before.ring != null && before.ring != after.ring && before.ringer == after.ringer) add(Inverse.Volume(AudioManager.STREAM_RING, before.ring))
        if (before.brightness != null && (before.brightness != after.brightness || before.brightnessMode != after.brightnessMode))
            add(Inverse.Brightness(before.brightnessMode ?: 0, before.brightness))
        if (before.torch != null && after.torch != null && before.torch != after.torch) add(Inverse.Torch(before.torch))
        if (before.prefs != after.prefs) add(Inverse.Prefs(before.prefs))
    }

    /** Apply one inverse; true when it took effect. */
    fun apply(ctx: Context, inv: Inverse): Boolean = try {
        when (inv) {
            is Inverse.Volume -> { (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).setStreamVolume(inv.stream, inv.level, 0); true }
            is Inverse.Ringer -> { (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode = inv.mode; true }
            is Inverse.Brightness -> if (!android.provider.Settings.System.canWrite(ctx)) false else {
                val cr = ctx.contentResolver
                android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, inv.level)
                android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, inv.mode)
                true
            }
            is Inverse.Torch -> {
                val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                if (id == null) false else { cm.setTorchMode(id, inv.on); true }
            }
            is Inverse.Prefs -> {
                val e = ctx.getSharedPreferences(com.tekyerema.settings.Settings.PREFS_NAME, Context.MODE_PRIVATE).edit()
                val intKeys = setOf("speech_rate", "speech_pitch")
                inv.values.forEach { (k, v) ->
                    when {
                        v.isEmpty() -> e.remove(k)
                        k in intKeys -> v.toIntOrNull()?.let { e.putInt(k, it) } ?: e.remove(k)
                        else -> e.putString(k, v)
                    }
                }
                e.apply()
                com.tekyerema.settings.Settings.notifyChanged()
                true
            }
            is Inverse.RemoveSchedule -> com.tekyerema.agent.triggers.ScheduledTasks.remove(ctx, inv.id)
            is Inverse.RemoveRule -> com.tekyerema.agent.triggers.NotificationTriggers.removeRule(ctx, inv.id)
            is Inverse.Irreversible -> false
        }
    } catch (_: Throwable) { false }

    /** Pure: what undoing a group sounds like, from what was reversed and what couldn't be. */
    fun spoken(done: List<Inverse>, failed: List<Inverse>): String {
        fun word(i: Inverse) = when (i) {
            is Inverse.Volume -> if (i.stream == AudioManager.STREAM_RING) "the ring volume" else "the volume"
            is Inverse.Ringer -> "the ringer"
            is Inverse.Brightness -> "the brightness"
            is Inverse.Torch -> "the flashlight"
            is Inverse.Prefs -> "your settings"
            is Inverse.RemoveSchedule -> "the new schedule"
            is Inverse.RemoveRule -> "the new message alert"
            is Inverse.Irreversible -> i.what
        }
        fun isRemoval(i: Inverse) = i is Inverse.RemoveSchedule || i is Inverse.RemoveRule
        val back = done.filterNot(::isRemoval).map(::word).distinct()
        val removed = done.filter(::isRemoval).map(::word).distinct()
        val cant = failed.map(::word).distinct()
        val did = buildList {
            if (back.isNotEmpty()) add("put back " + AuditLog.joinAnd(back))
            if (removed.isNotEmpty()) add("removed " + AuditLog.joinAnd(removed))
        }
        return buildList {
            if (did.isNotEmpty()) add("Okay, I " + did.joinToString(" and ") + ".")
            if (cant.isNotEmpty()) add("I can't undo " + AuditLog.joinAnd(cant) + ".")
        }.joinToString(" ").ifBlank { "There's nothing I can undo." }
    }

    private fun watchTorch(ctx: Context) {
        if (torchWatched) return
        torchWatched = true
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            cm.registerTorchCallback(object : android.hardware.camera2.CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { torchOn = enabled }
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        } catch (_: Throwable) {}
    }
}
