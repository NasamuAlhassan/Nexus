package com.tekyerema.agent

import android.content.Context
import android.provider.Settings

/**
 * A task that turns the connection OFF ends without the internet — by design. On the A20e (2026-09-30) "turn on airplane
 * mode" tapped the Flight mode tile and verified it on, then the next model call had no network and the user heard "I've
 * paused this because the internet dropped. Say carry on." The goal was done; the phone now proves it.
 *
 * [asks] reads which connection state the request wanted (pure); [doneLine] confirms the phone is in it now and says so.
 */
object ConnectivityGoal {
    enum class Want { AIRPLANE_ON, WIFI_OFF, DATA_OFF }

    private val AIRPLANE_ON = Regex("(?i)\\b(?:turn|switch|put)\\s+(?:on\\s+)?(?:the\\s+)?(?:airplane|aeroplane|flight)\\s*mode(?:\\s+on)?\\b|" +
        "\\b(?:enable|activate)\\s+(?:airplane|aeroplane|flight)\\s*mode\\b|\\b(?:airplane|aeroplane|flight)\\s*mode\\s+on\\b")
    private val WIFI_OFF = Regex("(?i)\\b(?:turn|switch)\\s+off\\s+(?:the\\s+)?wi-?fi\\b|\\b(?:turn|switch)\\s+(?:the\\s+)?wi-?fi\\s+off\\b|" +
        "\\b(?:disable)\\s+(?:the\\s+)?wi-?fi\\b")
    private val DATA_OFF = Regex("(?i)\\b(?:turn|switch)\\s+off\\s+(?:my\\s+|the\\s+)?(?:mobile\\s+)?data\\b|" +
        "\\b(?:turn|switch)\\s+(?:my\\s+|the\\s+)?(?:mobile\\s+)?data\\s+off\\b|\\bdisable\\s+(?:mobile\\s+)?data\\b")

    /** Which connection-cutting state [goal] asks for, or null. Pure. */
    fun asks(goal: String): Want? = when {
        AIRPLANE_ON.containsMatchIn(goal) && !Regex("(?i)\\b(?:off|disable)\\b").containsMatchIn(goal) -> Want.AIRPLANE_ON
        WIFI_OFF.containsMatchIn(goal) -> Want.WIFI_OFF
        DATA_OFF.containsMatchIn(goal) -> Want.DATA_OFF
        else -> null
    }

    /** The spoken result when [goal] cut the connection and the phone is now in that state; null otherwise. */
    fun doneLine(goal: String, ctx: Context): String? = when (asks(goal)) {
        Want.AIRPLANE_ON -> "Airplane mode is on — the internet and calls are off until you turn it off."
            .takeIf { Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1 }
        Want.WIFI_OFF -> "Wi-Fi is off.".takeIf {
            (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)?.isWifiEnabled == false
        }
        Want.DATA_OFF -> "Mobile data is off.".takeIf {
            Settings.Global.getInt(ctx.contentResolver, "mobile_data", 1) == 0
        }
        null -> null
    }
}
