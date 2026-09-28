package com.tekyerema.agent

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import com.tekyerema.accessibility.AccessibilityController
import com.tekyerema.android.AppResolver
import com.tekyerema.voice.Lang
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LOCAL FAST PATH (demo-critical). Sits IN FRONT of the AgentEngine/LLM: deterministic device operations
 * that Android itself can answer or execute are handled here directly — no LLM, no network to classify, no
 * screen observation, no intermediate app launches (never open Clock to tell the time, never open Settings
 * to read the battery). Anything it can't classify with confidence returns null and falls through UNCHANGED
 * to the existing agent pipeline. It never bypasses the PolicyEngine for sensitive/irreversible actions —
 * every intent here is a reversible TIER0/1 device control.
 *
 * Classification ([FastIntentClassifier]) is pure + unit-tested. Execution uses stock Android APIs and the
 * existing [AppResolver] / [AccessibilityController] primitives. Honest failure over fake success: if the OS
 * won't permit an operation (e.g. brightness without WRITE_SETTINGS), it falls through rather than pretend.
 */
class LocalFastRouter(
    private val appContext: Context,
    private val controller: () -> AccessibilityController?
) {
    /** @return a spoken outcome when handled locally, or null to fall through to the agent (unchanged). */
    suspend fun tryHandle(text: String, language: String): FastOutcome? {
        val intent = FastIntentClassifier.classify(text) ?: run {
            Log.i(TAG, "[LOCAL_FAST_PATH] no_match → agent  text='${text.take(60)}'")
            return null
        }
        val t0 = SystemClock.uptimeMillis()
        val outcome = try { execute(intent) } catch (t: Throwable) {
            Log.w(TAG, "[LOCAL_FAST_PATH] intent=${intent.name} threw ${t.message} → agent"); null
        }
        val dt = SystemClock.uptimeMillis() - t0
        if (outcome == null) {
            Log.i(TAG, "[LOCAL_FAST_PATH] intent=${intent.name} could_not_execute llm_called=false latency_ms=$dt → agent")
            return null
        }
        Log.i(TAG, "[LOCAL_FAST_PATH] intent=${intent.name} llm_called=false latency_ms=$dt result='${outcome.spoken.take(60)}'")
        return outcome
    }

    private suspend fun execute(intent: FastIntent): FastOutcome? = when (intent) {
        FastIntent.Time -> handled(intent, timeText())
        FastIntent.Date -> handled(intent, dateText())
        FastIntent.Day -> handled(intent, dayText())
        FastIntent.Battery -> handled(intent, batteryText())
        FastIntent.Storage -> handled(intent, storageText())

        FastIntent.VolumeUp -> stepVolume(raise = true)
        FastIntent.VolumeDown -> stepVolume(raise = false)
        FastIntent.VolumeMute -> muteVolume(mute = true)
        FastIntent.VolumeUnmute -> muteVolume(mute = false)
        FastIntent.VolumeQuery -> handled(intent, volumeText())

        FastIntent.BrightnessUp -> stepBrightness(up = true)
        FastIntent.BrightnessDown -> stepBrightness(up = false)

        FastIntent.FlashlightOn -> torch(on = true)
        FastIntent.FlashlightOff -> torch(on = false)

        FastIntent.WifiQuery -> wifiState()
        FastIntent.WifiOn -> openWifiPanel(turnOn = true)
        FastIntent.WifiOff -> openWifiPanel(turnOn = false)
        FastIntent.BluetoothQuery -> bluetoothState()
        FastIntent.BluetoothOn -> toggleBluetooth(on = true)
        FastIntent.BluetoothOff -> toggleBluetooth(on = false)

        FastIntent.MediaPlay -> media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, intent, "Playing.")
        FastIntent.MediaPause -> media(KeyEvent.KEYCODE_MEDIA_PAUSE, intent, "Paused.")
        FastIntent.MediaStop -> media(KeyEvent.KEYCODE_MEDIA_STOP, intent, "Stopped.")
        FastIntent.MediaNext -> media(KeyEvent.KEYCODE_MEDIA_NEXT, intent, "Next.")
        FastIntent.MediaPrevious -> media(KeyEvent.KEYCODE_MEDIA_PREVIOUS, intent, "Previous.")

        FastIntent.Back -> global("back", intent, "Went back.")
        FastIntent.Home -> global("home", intent, "Home.")
        FastIntent.Recents -> global("recents", intent, "Recent apps.")
        FastIntent.Notifications -> global("notifications", intent, "Notifications.")
        FastIntent.LockScreen -> global("lock", intent, "Locking.")
        FastIntent.ScrollUp -> scroll(down = false)
        FastIntent.ScrollDown -> scroll(down = true)

        is FastIntent.OpenApp -> openApp(intent.query)
    }

    // ---------------------------------------------------------------- info queries
    private fun timeText(): String =
        "The time is " + android.text.format.DateFormat.getTimeFormat(appContext).format(Date()) + "."

    private fun dateText(): String =
        "Today is " + SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date()) + "."

    private fun dayText(): String =
        "Today is " + SimpleDateFormat("EEEE", Locale.getDefault()).format(Date()) + "."

    /** Free / total internal storage, straight from the filesystem (what Settings › Storage shows). */
    private fun storageText(): String {
        val stat = try { android.os.StatFs(android.os.Environment.getDataDirectory().path) } catch (_: Throwable) { null }
            ?: return "I couldn't read your storage right now."
        return "You have ${gb(stat.availableBytes)} free out of ${gb(stat.totalBytes)}."
    }

    private fun gb(bytes: Long): String {
        val g = bytes / 1_000_000_000.0
        return when {
            g >= 10 -> "${Math.round(g)} gigabytes"
            g >= 1 -> "${"%.1f".format(Locale.US, g)} gigabytes"
            else -> "${Math.round(bytes / 1_000_000.0)} megabytes"
        }
    }

    private fun batteryText(): String {
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        if (pct < 0 || pct > 100) return "I couldn't read the battery level right now."
        val status = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return "Your battery is at $pct percent" + (if (charging) " and charging." else ".")
    }

    // ---------------------------------------------------------------- audio
    private fun audio(): AudioManager? = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private fun stepVolume(raise: Boolean): FastOutcome? {
        val am = audio() ?: return null
        val dir = if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        return action("VOLUME_${if (raise) "UP" else "DOWN"}", if (raise) "Volume up." else "Volume down.")
    }

    private fun muteVolume(mute: Boolean): FastOutcome? {
        val am = audio() ?: return null
        val dir = if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        return action(if (mute) "VOLUME_MUTE" else "VOLUME_UNMUTE", if (mute) "Muted." else "Unmuted.")
    }

    private fun volumeText(): String {
        val am = audio() ?: return "I couldn't read the volume."
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val pct = if (max > 0) Math.round(cur * 100.0 / max).toInt() else 0
        return "The volume is at $pct percent."
    }

    // ---------------------------------------------------------------- brightness (best-effort; honest if not permitted)
    private fun stepBrightness(up: Boolean): FastOutcome? {
        if (!Settings.System.canWrite(appContext)) return null   // no WRITE_SETTINGS → fall through to the agent
        return try {
            val cr = appContext.contentResolver
            // switch off auto-brightness so a manual value sticks
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
            val next = (cur + if (up) 45 else -45).coerceIn(10, 255)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, next)
            action("BRIGHTNESS_${if (up) "UP" else "DOWN"}", if (up) "Brightness up." else "Brightness down.")
        } catch (t: Throwable) { null }
    }

    // ---------------------------------------------------------------- flashlight / torch
    private fun torch(on: Boolean): FastOutcome? {
        val cm = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        val id = try {
            cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (t: Throwable) { null } ?: return action("FLASHLIGHT", "This phone doesn't have a flashlight I can reach.")
        return try {
            cm.setTorchMode(id, on)
            action(if (on) "FLASHLIGHT_ON" else "FLASHLIGHT_OFF", if (on) "Flashlight on." else "Flashlight off.")
        } catch (t: Throwable) {
            action("FLASHLIGHT", "Sorry, I couldn't switch the flashlight ${if (on) "on" else "off"}.")
        }
    }

    // ---------------------------------------------------------------- wifi / bluetooth
    private fun wifiState(): FastOutcome? = try {
        val wm = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        FastOutcome("WIFI_QUERY", if (wm.isWifiEnabled) "Wi-Fi is on." else "Wi-Fi is off.")
    } catch (t: Throwable) { null }

    /** Android 10+ forbids apps toggling Wi-Fi directly — open the system Wi-Fi panel (honest, deterministic). */
    private fun openWifiPanel(turnOn: Boolean): FastOutcome? {
        if (Build.VERSION.SDK_INT < 29) return null
        return try {
            appContext.startActivity(Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            action(if (turnOn) "WIFI_ON" else "WIFI_OFF",
                "Android won't let me change Wi-Fi directly, so I've opened the Wi-Fi controls for you.")
        } catch (t: Throwable) { null }
    }

    private fun bluetoothState(): FastOutcome? = try {
        val a = BluetoothAdapter.getDefaultAdapter() ?: return FastOutcome("BLUETOOTH_QUERY", "This phone has no Bluetooth.")
        FastOutcome("BLUETOOTH_QUERY", if (a.isEnabled) "Bluetooth is on." else "Bluetooth is off.")
    } catch (t: Throwable) { null }

    @Suppress("DEPRECATION")
    private fun toggleBluetooth(on: Boolean): FastOutcome? {
        val a = BluetoothAdapter.getDefaultAdapter() ?: return null
        return try {
            val ok = if (on) a.enable() else a.disable()
            if (ok) action(if (on) "BLUETOOTH_ON" else "BLUETOOTH_OFF", "Turning Bluetooth ${if (on) "on" else "off"}.")
            else null   // OS refused → fall through to the agent (never fake success)
        } catch (t: Throwable) { null }
    }

    // ---------------------------------------------------------------- media transport keys
    private fun media(keyCode: Int, intent: FastIntent, say: String): FastOutcome? {
        val am = audio() ?: return null
        return try {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            action(intent.name, say)
        } catch (t: Throwable) { null }
    }

    // ---------------------------------------------------------------- navigation (accessibility global actions)
    private fun global(globalName: String, intent: FastIntent, say: String): FastOutcome? {
        val c = controller() ?: return null
        return if (c.globalAction(globalName)) action(intent.name, say) else null
    }

    private suspend fun scroll(down: Boolean): FastOutcome? {
        val c = controller() ?: return null
        val (w, h) = c.screenSize()
        if (w <= 0 || h <= 0) return null
        val x = w / 2
        // scroll DOWN = content moves up = swipe from lower to upper; UP = the reverse.
        val y1 = if (down) (h * 0.72).toInt() else (h * 0.28).toInt()
        val y2 = if (down) (h * 0.28).toInt() else (h * 0.72).toInt()
        val ok = try { c.swipe(x, y1, x, y2) } catch (_: Throwable) { false }
        return if (ok) action(if (down) "SCROLL_DOWN" else "SCROLL_UP", if (down) "Scrolled down." else "Scrolled up.") else null
    }

    // ---------------------------------------------------------------- open app (existing resolver)
    private suspend fun openApp(query: String): FastOutcome? {
        val match = AppResolver.resolveDetailed(appContext, query)
        val app = (match as? AppResolver.Match.One)?.app ?: return null   // ambiguous/none → let the agent ask
        val launch = AppResolver.launchIntent(appContext, app.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return null
        val prevFg = controller()?.foregroundPackage()
        try { appContext.startActivity(launch) } catch (t: Throwable) { return null }
        // Say "is open" only once the app is actually in front. Without the accessibility service we can't
        // see the foreground, so announce the attempt instead of claiming the result. If it never comes up,
        // fall through: the agent observes the real screen and either finishes the job or says why not.
        val c = controller() ?: return action("OPEN_APP", "Opening ${app.label}.")
        // Up to ~6 s: a cold start of a heavy app (Chrome, a bank app) on a low-end phone routinely takes 3–5 s,
        // and a slow launch is not a failure. Polling is cheap — foregroundPackage() reads event-tracked state.
        val (ok, fg) = com.tekyerema.verifier.Verify.pollLaunched(c, app.packageName, prevFg)
        if (!ok) {
            Log.i(TAG, "[LOCAL_FAST_PATH] OPEN_APP ${app.packageName} not in front (fg=$fg) → agent")
            return null
        }
        return action("OPEN_APP", "${app.label} is open.")
    }

    /** An information QUERY answer — spoken on the normal (Twi-capable) voice. */
    private fun handled(intent: FastIntent, text: String) = FastOutcome(intent.name, text, systemVoice = false)

    /** A deterministic ACTION confirmation — spoken on the OFFLINE system engine so it's instant, never
     *  blocked by the online Twi voice on a slow network. */
    private fun action(name: String, text: String) = FastOutcome(name, text, systemVoice = true)

    companion object { private const val TAG = "Tekyerema" }
}

/**
 * The result of a locally-handled command: the intent name (for logs) + the sentence to speak.
 * [systemVoice] = speak the confirmation through the OFFLINE system TTS (instant, never network-blocked) —
 * true for deterministic ACTIONS (volume, back, flashlight…) so their confirm can't be delayed by the online
 * Twi voice; false for information QUERIES (time, battery…) whose answer stays on the normal Twi voice.
 */
data class FastOutcome(val intent: String, val spoken: String, val systemVoice: Boolean = false)
