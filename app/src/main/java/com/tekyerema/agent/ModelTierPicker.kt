package com.tekyerema.agent

import com.tekyerema.core.config.AppConfig
import org.json.JSONArray

/**
 * Device tiers for the on-phone planner (MASTER_STATUS #30, 2 Nov 2026): which `.litertlm` file this phone should load.
 * Bigger phones get the bigger model, smaller ones step down, and a phone too small for any of them gets NO model (the
 * rules and the cloud brain carry it, exactly as before). Pure: (total RAM, free RAM, files present, tier table) → a
 * [Pick] or none with a reason; [LocalModelBrain.ifEnabled] reads the device and logs `[TIER] …`.
 *
 * Tiers come from `router.localModel.tiers` (best first). A tier is used only when its file is installed, the phone's
 * total RAM reaches `minTotalMb` AND (when free RAM is known) at least `minFreeMb` is free right now — otherwise the next,
 * smaller installed tier is tried. An unknown total (0, the profile not read) never blocks, but then the SMALLEST installed
 * tier is chosen (safest). The old single `model.litertlm` stays as the last resort, guarded by `router.localModel.minRamMb`.
 */
object ModelTierPicker {

    data class Tier(val id: String, val file: String, val minTotalMb: Long, val minFreeMb: Long)

    /** The tier table plus the legacy single file. */
    data class Config(val tiers: List<Tier>, val legacyFile: String, val legacyMinRamMb: Long)

    data class Pick(val file: String?, val tierId: String?, val reason: String) {
        val loads: Boolean get() = file != null
    }

    /** Qwen3.5-4B v2 / Qwen3.5-2B v3 / Granite 4.0-1B. Android reports a bit less than the marketing size (8 GB → ~7.4 GB). */
    val DEFAULT_TIERS: List<Tier> = listOf(
        Tier("qwen3.5-4b-v2", "qwen3.5-4b.litertlm", 7_000L, 3_500L),
        Tier("qwen3.5-2b-v3", "qwen3.5-2b.litertlm", 3_600L, 2_000L),
        Tier("granite-4.0-1b", "granite-4.0-1b.litertlm", 2_800L, 1_200L)
    )
    const val LEGACY_FILE = "model.litertlm"

    val DEFAULT_CONFIG = Config(DEFAULT_TIERS, LEGACY_FILE, 2_500L)

    /**
     * [totalMb] / [freeMb]: 0 or less = unknown. [installed]: the file NAMES present in the model folder.
     * Never throws; a pick of null means "no on-phone model on this phone".
     */
    fun pick(totalMb: Long, freeMb: Long, installed: Set<String>, config: Config = DEFAULT_CONFIG): Pick {
        val present = config.tiers.filter { it.file in installed }
        val legacy = config.legacyFile.isNotBlank() && config.legacyFile in installed
        if (present.isEmpty() && !legacy) return Pick(null, null, "no model file installed")
        val totalKnown = totalMb > 0
        val freeKnown = freeMb > 0
        val why = ArrayList<String>()
        if (!totalKnown && present.isNotEmpty()) {
            val t = present.last()   // tiers are listed best first: the last installed one is the smallest
            return Pick(t.file, t.id, "total RAM unknown, smallest installed tier ${t.id}")
        }
        for (t in present) {
            if (totalKnown && totalMb < t.minTotalMb) { why += "${t.id}: ${totalMb} MB total < ${t.minTotalMb}"; continue }
            if (freeKnown && freeMb < t.minFreeMb) { why += "${t.id}: ${freeMb} MB free < ${t.minFreeMb}"; continue }
            return Pick(t.file, t.id, "${t.id} fits (${if (totalKnown) "$totalMb MB total" else "total unknown"}, " +
                (if (freeKnown) "$freeMb MB free" else "free unknown") + ")")
        }
        if (legacy) {
            if (totalKnown && totalMb < config.legacyMinRamMb) why += "legacy ${config.legacyFile}: ${totalMb} MB total < ${config.legacyMinRamMb}"
            else return Pick(config.legacyFile, "legacy", "legacy ${config.legacyFile} (${if (totalKnown) "$totalMb MB total" else "total unknown"})")
        }
        return Pick(null, null, "no installed model fits: " + why.joinToString("; "))
    }

    /** The table from `router.localModel.tiers` (falls back to [DEFAULT_CONFIG]); `router.localModel.minRamMb` guards the legacy file. */
    fun configFromApp(): Config {
        val legacyMin = AppConfig.long("router.localModel.minRamMb", 2_500L)
        val arr = AppConfig.node("router.localModel.tiers") as? JSONArray ?: return DEFAULT_CONFIG.copy(legacyMinRamMb = legacyMin)
        val tiers = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val file = o.optString("file").trim()
            if (file.isEmpty()) return@mapNotNull null
            Tier(o.optString("id").ifBlank { file.removeSuffix(".litertlm") }, file, o.optLong("minTotalMb", 0L), o.optLong("minFreeMb", 0L))
        }
        return Config(tiers.ifEmpty { DEFAULT_TIERS }, AppConfig.string("router.localModel.legacyFile", LEGACY_FILE), legacyMin)
    }

    /** The log line (`[TIER] …`, debug-greppable). */
    fun logLine(totalMb: Long, freeMb: Long, installed: Set<String>, p: Pick): String =
        "[TIER] total=${totalMb}MB free=${freeMb}MB files=${installed.sorted()} → ${p.file ?: "none"} (${p.reason})"
}
