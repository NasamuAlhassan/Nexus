package com.tekyerema.agent

import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot

/**
 * Device toggles that live in the Quick Settings panel, by the names people SAY and the names OEMs PRINT on the
 * tile. The Local Fast Path flips these deterministically (pull down Quick Settings → find the checkable tile →
 * tap → verify its checked state) — no LLM. On the on-device eval the same requests went through the cloud agent
 * at 25–35 s and ~15k tokens each (Groq's free tier allows ~40 cloud steps a day).
 *
 * Pure (no Android) → unit-tested. Matching is exact on the spoken phrase, so "turn on location sharing in
 * WhatsApp" is NOT the Location tile.
 */
object QuickTiles {
    data class Tile(val key: String, val spoken: String, val phrases: Set<String>, val labels: List<String>)

    val ALL = listOf(
        Tile("dnd", "Do Not Disturb", setOf("do not disturb", "dnd", "don't disturb"), listOf("do not disturb")),
        Tile("rotate", "Auto-rotate", setOf("auto-rotate", "auto rotate", "autorotate", "screen rotation", "rotation",
            "rotate", "auto-rotation", "auto rotation"), listOf("auto-rotate", "auto rotate", "autorotate", "rotation")),
        Tile("airplane", "Airplane mode", setOf("airplane mode", "aeroplane mode", "flight mode", "airplane", "aeroplane"),
            listOf("airplane", "aeroplane", "flight mode")),
        Tile("battery_saver", "Battery Saver", setOf("battery saver", "battery saving", "battery saving mode", "power saving",
            "power saver", "power saving mode", "battery saver mode"), listOf("battery saver", "power saving", "battery saving")),
        Tile("dark", "Dark theme", setOf("dark mode", "dark theme", "night mode"), listOf("dark theme", "dark mode")),
        Tile("location", "Location", setOf("location", "gps", "location services"), listOf("location")),
        Tile("hotspot", "Hotspot", setOf("hotspot", "hot spot", "mobile hotspot", "tethering", "wifi hotspot", "wi-fi hotspot"),
            listOf("hotspot")),
        Tile("mobile_data", "Mobile data", setOf("mobile data", "cellular data"), listOf("mobile data")),
        Tile("night_light", "Night Light", setOf("night light", "eye comfort", "eye comfort shield", "blue light filter",
            "eye protection"), listOf("night light", "eye comfort", "blue light")),
        Tile("nfc", "NFC", setOf("nfc"), listOf("nfc")),
        // Data is expensive in Ghana: Data saver stops apps using mobile data in the background. Not on every phone's
        // panel — when it isn't there the fast path hands over to the agent (which goes through Settings).
        Tile("data_saver", "Data saver", setOf("data saver", "data saving", "data saving mode", "data saver mode"),
            listOf("data saver", "data saving")),
        // No spoken phrase: reached only as the fallback when Android refuses a direct Bluetooth toggle (API 33+).
        Tile("bluetooth", "Bluetooth", emptySet(), listOf("bluetooth")),
    )

    fun byKey(key: String): Tile? = ALL.firstOrNull { it.key == key }

    /** The tile the user named, if [phrase] (already stripped of the verb/on/off) is one of its spoken names. */
    fun forPhrase(phrase: String): Tile? {
        val p = phrase.trim().lowercase().removePrefix("the ").removePrefix("my ").trim()
        return ALL.firstOrNull { p in it.phrases } ?: ALL.firstOrNull { p.removeSuffix(" setting").removeSuffix(" feature") in it.phrases }
    }

    /** The CHECKABLE element that is this tile on the current screen (the label may carry the state: "…, Off"). */
    fun find(snap: UiSnapshot?, tile: Tile): UiElement? = snap?.elements?.firstOrNull { e ->
        e.checkable && e.label?.lowercase()?.let { l -> tile.labels.any { l.contains(it) } } == true
    }
}
