package com.tekyerema.phone

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Connect to Home Wi-Fi, the password is …", "show me the available Wi-Fi networks", "is Wi-Fi / Bluetooth / mobile data on?" (owner,
 * 28 Oct 2026). Android 10+ lets no app join a network by itself; Android 11+ lets an app hand a network to the system's own "Save this
 * network?" sheet (Settings.ACTION_WIFI_ADD_NETWORKS), which our accessibility service then confirms — the user asked for it in words.
 * The connection is then CHECKED and the result said; nothing is assumed.
 *
 * The password: every request has PINs and passwords masked before it is logged or sent anywhere (core/Privacy.maskCredentials), so
 * the spoken password is taken out here first ([capture], at that same door), held in memory only, used once, and wiped. It is
 * never logged, said back, stored or sent to a model.
 */
object WifiJoin {
    private const val TAG = "Tekyerema"
    data class Req(val ssid: String?, val password: String?)

    private val PW = "(?:wi-?fi\\s+)?(?:password|pass ?word|pass ?code|key)"
    private val JOIN_PW = Regex("(?i)\\b(?:connect|join)(?: me)? to (?:the )?(.+?)(?:\\s+(?:wi-?fi|network|wireless))?\\s*(?:[.,;]\\s*|\\s+and\\s+|\\s+)(?:the |its |my )?$PW\\s*(?:is|:|=)\\s*(.+)$")
    private val JOIN = Regex("(?i)^(?:please |can you |could you )?(?:connect|join)(?: me)? to (?:the )?(.+?)\\s+(?:wi-?fi|network|wireless)(?: network)?$")
    private val PW_ONLY = Regex("(?i)^(?:the |its |my )?$PW\\s*(?:is|:|=)\\s*(.+)$")
    private val NO_PW = Regex("(?i)\\b(?:no password|open network|it(?:'s| is| has) (?:open|no password))\\b")
    private val LIST = Regex("(?i)\\b(?:show|list|tell|read|what are|which)\\b.*\\b(?:wi-?fi|wireless) networks?\\b|\\bavailable (?:wi-?fi|networks)\\b")
    private val STATE = Regex("(?i)^(?:is|are)\\s+(?:my |the )?(wi-?fi|bluetooth|mobile data|data)\\s+(?:on|off|enabled|working|connected)\\b")

    /** "Home Wi-Fi" + "hunter 2" → the request; null when [text] is not one. Pure. */
    fun parse(text: String): Req? {
        val t = text.trim().trimEnd('.', '!', '?')
        JOIN_PW.find(t)?.let { m -> return Req(cleanSsid(m.groupValues[1]), cleanPassword(m.groupValues[2])) }
        JOIN.find(t)?.let { m -> return Req(cleanSsid(m.groupValues[1]), if (NO_PW.containsMatchIn(t)) "" else null) }
        PW_ONLY.find(t)?.let { m -> return Req(null, cleanPassword(m.groupValues[1])) }
        return null
    }

    internal fun cleanSsid(s: String): String = s.trim().trimEnd('.', ',').removeSuffix(" wifi").removeSuffix(" wi-fi").trim()

    /** A spoken password has no spaces in it ("one two three four five six seven eight" → "12345678"; "hunter two" → "huntertwo" is
     *  the recogniser's limit — said honestly when the join fails). */
    internal fun cleanPassword(s: String): String {
        val words = mapOf("zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6",
            "seven" to "7", "eight" to "8", "nine" to "9")
        val toks = s.trim().trimEnd('.').split(Regex("\\s+"))
        val allDigits = toks.all { it.all(Char::isDigit) || it.lowercase() in words }
        return if (allDigits) toks.joinToString("") { words[it.lowercase()] ?: it } else toks.joinToString("")
    }

    @Volatile private var pendingSsid: String? = null
    @Volatile private var pendingPassword: String? = null
    @Volatile private var pendingAt = 0L

    /** At the request door, BEFORE masking: keeps the network name and password said in [raw] (memory only, 2 minutes). */
    fun capture(raw: String) {
        val r = parse(raw) ?: return
        val now = System.currentTimeMillis()
        if (now - pendingAt > 120_000L) { pendingSsid = null; pendingPassword = null }
        r.ssid?.let { pendingSsid = it }
        r.password?.let { pendingPassword = it }
        pendingAt = now
    }

    private fun wipe() { pendingPassword = null; pendingSsid = null; pendingAt = 0L }

    /** The spoken line for a (masked) request about Wi-Fi joining, networks or device states; null when it is not one. */
    fun handle(ctx: Context, maskedText: String): String? {
        val t = maskedText.trim().trimEnd('.', '!', '?')
        STATE.find(t)?.let { m -> return state(ctx, m.groupValues[1].lowercase()) }
        if (LIST.containsMatchIn(t)) return networks(ctx)
        val r = parse(t) ?: return null
        val fresh = System.currentTimeMillis() - pendingAt < 120_000L
        val ssid = (if (fresh) pendingSsid else null) ?: r.ssid?.takeIf { !it.contains("[hidden]") } ?: return null
        val pw = if (fresh) pendingPassword else null
        if (pw == null) {
            pendingSsid = ssid; pendingAt = System.currentTimeMillis()
            return "What's the password for $ssid? Say: the password is, and then the password. If it has no password, say: it has no password."
        }
        return join(ctx, ssid, pw)
    }

    private fun join(ctx: Context, ssid: String, password: String): String {
        if (Build.VERSION.SDK_INT < 30) {
            wipe()
            try { ctx.startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
            return "On this Android version I can't add a network myself. I've opened Wi-Fi settings: choose $ssid there and type the password."
        }
        return try {
            val b = android.net.wifi.WifiNetworkSuggestion.Builder().setSsid(ssid)
            if (password.isNotEmpty()) b.setWpa2Passphrase(password)
            val list = arrayListOf(b.build())
            wipe()
            ctx.startActivity(Intent(android.provider.Settings.ACTION_WIFI_ADD_NETWORKS)
                .putParcelableArrayListExtra(android.provider.Settings.EXTRA_WIFI_NETWORK_LIST, list).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            confirmAndVerify(ctx, ssid)
            "Adding $ssid. I'll tell you when it's connected."
        } catch (t: Throwable) {
            wipe(); Log.w(TAG, "[WIFI] add network failed: ${t.javaClass.simpleName}")
            "I couldn't add $ssid. Try again, or open Wi-Fi settings."
        }
    }

    /** Press Save on Android's sheet (the user asked for this network), then watch for the connection for up to 25 s and say what happened. */
    private fun confirmAndVerify(ctx: Context, ssid: String) {
        CoroutineScope(Dispatchers.Main).launch {
            delay(1_500)
            pressSave()
            val end = System.currentTimeMillis() + 25_000L
            var ok = false
            while (System.currentTimeMillis() < end) {
                delay(1_500)
                if (connectedTo(ctx) == ssid) { ok = true; break }
            }
            val line = if (ok) "Connected to $ssid." else "I couldn't confirm a connection to $ssid. The password may be wrong, or the network is out of range."
            Log.i(TAG, "[WIFI] join result ok=$ok")
            try { com.tekyerema.voice.VoiceEngine.speakIn(line, com.tekyerema.voice.VoiceEngine.language()) } catch (_: Throwable) {}
        }
    }

    private fun pressSave() {
        val svc = com.tekyerema.accessibility.TekyeremaAccessibilityService.live ?: return
        val root = svc.rootInActiveWindow ?: return
        for (label in listOf("Save", "SAVE", "Connect", "Allow")) {
            val node = root.findAccessibilityNodeInfosByText(label).firstOrNull { it.text?.toString().equals(label, true) } ?: continue
            var n: AccessibilityNodeInfo? = node
            while (n != null && !n.isClickable) n = n.parent
            if (n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { Log.i(TAG, "[WIFI] pressed '$label' on the system sheet"); return }
        }
    }

    @Suppress("DEPRECATION")
    private fun connectedTo(ctx: Context): String? = try {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wm.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    } catch (_: Throwable) { null }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun networks(ctx: Context): String = try {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wm.isWifiEnabled) "Wi-Fi is off. Say: turn on Wi-Fi, then ask again."
        else {
            val names = wm.scanResults.filter { it.SSID.isNotBlank() }.sortedByDescending { it.level }.map { it.SSID }.distinct().take(6)
            if (names.isEmpty()) "I don't see any Wi-Fi networks right now. Location may need to be on for me to see them."
            else "I can see ${names.size} Wi-Fi network${if (names.size == 1) "" else "s"}, strongest first: ${names.joinToString(", ")}."
        }
    } catch (_: SecurityException) { "I need location permission to see the Wi-Fi networks around you." } catch (_: Throwable) { "I couldn't list the Wi-Fi networks." }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun state(ctx: Context, what: String): String = try {
        when {
            what.startsWith("wi") -> {
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                if (!wm.isWifiEnabled) "Wi-Fi is off." else connectedTo(ctx)?.let { "Wi-Fi is on, connected to $it." } ?: "Wi-Fi is on, but not connected to a network."
            }
            what == "bluetooth" -> {
                val bt = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
                if (bt == null) "This phone has no Bluetooth." else if (bt.isEnabled) "Bluetooth is on." else "Bluetooth is off."
            }
            else -> {
                val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
                if (tm.isDataEnabled) "Mobile data is on." else "Mobile data is off."
            }
        }
    } catch (_: SecurityException) { "I don't have permission to check that." } catch (_: Throwable) { "I couldn't check that." }
}
