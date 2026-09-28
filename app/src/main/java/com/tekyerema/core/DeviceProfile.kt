package com.tekyerema.core

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.tekyerema.core.config.AppConfig

/**
 * N-08 — what this phone can afford. The A20e target has ~2.8 GB RAM and a small per-app heap, so heavy on-device
 * models are kept to one resident at a time and released under memory pressure. Read once at startup; the numbers
 * are also reported by `dump_capabilities` and the run report.
 */
object DeviceProfile {
    @Volatile var totalRamMb: Long = 0; private set
    @Volatile var memoryClassMb: Int = 0; private set
    @Volatile var lowRamDevice: Boolean = false; private set

    fun init(ctx: Context) {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val mi = ActivityManager.MemoryInfo()
        try { am.getMemoryInfo(mi) } catch (_: Throwable) {}
        totalRamMb = mi.totalMem / (1024 * 1024)
        memoryClassMb = am.memoryClass
        lowRamDevice = am.isLowRamDevice
        Log.i("Tekyerema", "[DEVICE] ram=${totalRamMb}MB memoryClass=${memoryClassMb}MB lowRam=$lowRamDevice constrained=$constrained")
    }

    /** True on low-RAM phones (the 2.8 GB A20e and below). */
    val constrained: Boolean
        get() = lowRamDevice || (totalRamMb in 1..AppConfig.long("device.memoryProfile.constrainedBelowMb", 3500L))

    /** How many on-device ASR models may stay loaded at once. */
    fun maxResidentAsrModels(): Int =
        AppConfig.int("device.memoryProfile.maxResidentAsr", if (constrained) 1 else 2).coerceAtLeast(1)
}
