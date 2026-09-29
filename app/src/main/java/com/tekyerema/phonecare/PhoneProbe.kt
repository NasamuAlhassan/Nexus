package com.tekyerema.phonecare

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import com.tekyerema.core.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The read-only half of "check my phone": what the phone says right now, as [PhoneCheck.Facts]. Needs no new permission
 * (a permission state, a Settings value, the app's own in-memory state). The only thing that can touch the network is
 * the existing link probe (one `generate_204` on a fresh connection, [com.tekyerema.core.LinkProbe]) — never a model.
 * Every read is guarded: a check that can't be made is reported as fine or unknown, never as a false alarm.
 */
object PhoneProbe {
    suspend fun probe(ctx: Context): PhoneCheck.Facts = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        val s = com.tekyerema.settings.Settings(app)
        fun granted(p: String) = try { app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED } catch (_: Throwable) { false }
        fun <T> safe(def: T, block: () -> T): T = try { block() } catch (_: Throwable) { def }

        val a11yOn = safe(false) { com.tekyerema.core.config.SystemChecks.accessibilityEnabled(app) }
        val a11yBound = safe(false) { com.tekyerema.agent.AgentEngine.currentController()?.ready == true } ||
            com.tekyerema.accessibility.TekyeremaAccessibilityService.live != null
        val notifOn = safe(false) { com.tekyerema.core.config.SystemChecks.notificationAccess(app) }
        val battery = batteryState(app)
        val (dnsMode, dnsHost) = privateDns(app)
        val cooling = safe(0L) { com.tekyerema.agent.OpenAiCompatBrain.coolingLeftMs(s.groqModel, s.groqApiKeys) }
        val op = com.tekyerema.operator.OperatorPolicy
        val groq = safe(false) { s.groqApiKeys.isNotEmpty() && "groq" in op.providers }
        val gemini = safe(false) { s.allowGeminiBackup && s.geminiApiKey.isNotBlank() }
        val onDeviceSpeech = com.tekyerema.voice.VoiceEngine.voiceLayer == com.tekyerema.voice.io.VoiceLayer.GHANA

        PhoneCheck.Facts(
            micGranted = granted(Manifest.permission.RECORD_AUDIO),
            screenControlOn = a11yOn,
            screenControlBound = a11yBound,
            a11yShortcut = shortcutTargetsUs(app),
            wakeWordOn = safe(false) { s.wakeWordEnabled },
            floatingButtonOn = safe(false) { s.floatingTrigger },
            notificationAccess = notifOn,
            // Only this process's own binding can be seen; a granted-but-unbound listener is the 2026-09-29 failure class.
            notificationListenerBound = com.tekyerema.notifications.TekyeremaNotificationListener.instance != null,
            callsGranted = granted(Manifest.permission.CALL_PHONE),
            contactsGranted = granted(Manifest.permission.READ_CONTACTS),
            smsGranted = granted(Manifest.permission.SEND_SMS),
            batteryUnrestricted = safe(true) { com.tekyerema.ui.Perms.batteryUnrestricted(app) },
            backgroundRestricted = safe(false) {
                Build.VERSION.SDK_INT >= 28 && app.getSystemService(android.app.ActivityManager::class.java)?.isBackgroundRestricted == true
            },
            network = network(),
            weakNetwork = safe(false) { com.tekyerema.core.NetworkMonitor.isWeak() },
            privateDnsMode = dnsMode,
            privateDnsHost = dnsHost,
            samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true),
            cloudAllowed = op.cloudAllowed,
            budgetSpent = safe(false) { op.budgetExhausted() },
            onlineAi = groq || gemini,
            mainModel = safe("") { s.groqModel },
            // The main model only counts as "out" when Groq is what the user relies on.
            mainModelWaitMs = if (groq) cooling else 0L,
            lastModel = op.lastModel,
            weakPolicy = op.weakModelPolicy,
            aiCallsToday = safe(0) { op.usageToday().first },
            speechIn = safe(null as Boolean?) {
                if (onDeviceSpeech) onDeviceModelPresent(app)
                else android.speech.SpeechRecognizer.isRecognitionAvailable(app)
            },
            onDeviceSpeech = onDeviceSpeech,
            // The manifest's <queries> lists the TTS service intent, so this sees the engines on API 30+.
            speechOut = safe(null as Boolean?) {
                app.packageManager.queryIntentServices(Intent(android.speech.tts.TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0).isNotEmpty()
            },
            speakAloud = safe(true) { s.speakAloud },
            batteryPct = battery.first,
            charging = battery.second
        )
    }

    /** Offline / stalled from what the app already knows; otherwise one bounded probe (recent traffic answers for free). */
    private suspend fun network(): PhoneCheck.Net = try {
        when {
            com.tekyerema.core.NetworkMonitor.state() == com.tekyerema.core.Connectivity.OFFLINE -> PhoneCheck.Net.OFFLINE
            com.tekyerema.core.LinkHealth.stalled() -> PhoneCheck.Net.NOT_ANSWERING
            else -> {
                val budget = AppConfig.long("phonecare.check.internetMs", 8_000L)
                // A probe still silent at the budget is, for the user, "not answering" (the A20e's DNS took 5–11 s).
                when (withTimeoutOrNull(budget) { com.tekyerema.core.LinkHealth.answering() ?: NO_PROBE }) {
                    true -> PhoneCheck.Net.ONLINE
                    false -> PhoneCheck.Net.NOT_ANSWERING
                    NO_PROBE -> PhoneCheck.Net.UNKNOWN
                    else -> PhoneCheck.Net.NOT_ANSWERING
                }
            }
        }
    } catch (ce: kotlinx.coroutines.CancellationException) { throw ce } catch (_: Throwable) { PhoneCheck.Net.UNKNOWN }

    private val NO_PROBE = Any()

    /**
     * Private DNS: the active network's own report first (API 28 LinkProperties — the strict-mode host when one is in
     * use), then the Settings value (readable without a permission on Android 11; guarded for later versions).
     */
    private fun privateDns(app: Context): Pair<String?, String?> {
        val lpHost = try {
            if (Build.VERSION.SDK_INT >= 28) {
                val cm = app.getSystemService(ConnectivityManager::class.java)
                cm?.activeNetwork?.let { cm.getLinkProperties(it) }?.privateDnsServerName
            } else null
        } catch (_: Throwable) { null }
        if (!lpHost.isNullOrBlank()) return "hostname" to lpHost
        val mode = try { android.provider.Settings.Global.getString(app.contentResolver, "private_dns_mode") } catch (_: Throwable) { null }
        val host = try { android.provider.Settings.Global.getString(app.contentResolver, "private_dns_specifier") } catch (_: Throwable) { null }
        return mode to host
    }

    /**
     * Is an on-device speech model on the phone (the places SherpaAsr looks)? Checked by file, NOT through
     * SherpaAsr.ready — that would extract a bundled model (hundreds of MB) as a side effect of a read-only check.
     */
    private fun onDeviceModelPresent(app: Context): Boolean {
        for (lang in listOf("eng", "twi")) {
            val id = if (lang == "twi") "twi-asr" else "en-asr"
            if (try { com.tekyerema.models.ModelManager.isReady(id) } catch (_: Throwable) { false }) return true
            if (java.io.File(app.filesDir, "ondevice/$lang/asr/tiny-encoder.onnx").exists()) return true
            if (app.getExternalFilesDir(null)?.let { java.io.File(it, "ondevice/$lang/asr/tiny-encoder.onnx").exists() } == true) return true
            if (try { app.assets.list("models/ondevice/$lang/asr")?.isNotEmpty() == true } catch (_: Throwable) { false }) return true
        }
        return false
    }

    /** Is the accessibility button or shortcut set to start Tɛkyerɛma? null when the phone won't say. */
    private fun shortcutTargetsUs(app: Context): Boolean? = try {
        val keys = listOf("accessibility_button_targets", "accessibility_shortcut_target_service")
        val values = keys.map { android.provider.Settings.Secure.getString(app.contentResolver, it) }
        if (values.all { it == null }) null else values.any { it?.contains(app.packageName) == true }
    } catch (_: Throwable) { null }

    /** (percent, charging) from the sticky battery broadcast — no receiver kept, no permission. */
    private fun batteryState(app: Context): Pair<Int?, Boolean> = try {
        val i = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else
            app.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        pct.takeIf { it in 0..100 } to charging
    } catch (_: Throwable) { null to false }
}
