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

        FastIntent.BrightnessUp -> stepBrightness(up = true) ?: brightnessViaSlider(null, +0.2f)
        FastIntent.BrightnessDown -> stepBrightness(up = false) ?: brightnessViaSlider(null, -0.2f)
        is FastIntent.BrightnessSet -> setBrightness(intent.fraction)

        FastIntent.FlashlightOn -> torch(on = true)
        FastIntent.FlashlightOff -> torch(on = false)
        FastIntent.FlashlightQuery -> torchState()

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
        is FastIntent.QuickToggle -> quickToggle(intent)
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

    /** An absolute level: straight through Settings.System when WRITE_SETTINGS is granted, else the Quick Settings
     *  slider (no permission needed — it's the same control a sighted user would drag). */
    private suspend fun setBrightness(fraction: Float): FastOutcome? {
        if (Settings.System.canWrite(appContext)) try {
            val cr = appContext.contentResolver
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, Math.round(fraction * 254 + 1))
            return action("BRIGHTNESS_SET", brightnessSaid(fraction))
        } catch (_: Throwable) { /* fall back to the slider */ }
        return brightnessViaSlider(fraction, null)
    }

    private fun brightnessSaid(fraction: Float) = when {
        fraction >= 0.995f -> "Brightness is at maximum."
        fraction <= 0.005f -> "Brightness is at minimum."
        else -> "Brightness set to ${Math.round(fraction * 100)} percent."
    }

    /** Quick Settings' brightness slider: set [fraction] of its range, or move it by [delta] of its range; VERIFY the
     *  slider's reported value, close the shade. Anything unexpected → null (the agent continues from here). */
    private suspend fun brightnessViaSlider(fraction: Float?, delta: Float?): FastOutcome? {
        val c = controller() ?: return null
        openShade(c) ?: return null
        var slider: com.tekyerema.accessibility.UiElement? = null
        repeat(5) { i ->
            if (slider == null) {
                if (i > 0) { kotlinx.coroutines.delay(150); c.waitForSettle(1000) }
                val snap = c.buildSnapshot()
                slider = snap.elements.firstOrNull { it.range != null && it.label?.lowercase()?.contains("bright") == true }
                    ?: snap.elements.firstOrNull { it.range != null }
            }
        }
        val s = slider ?: run { c.globalAction("back"); return null }
        val r = s.range!!
        val span = r.max - r.min
        val target = when {
            fraction != null -> r.min + span * fraction
            else -> (r.current + span * (delta ?: 0f)).coerceIn(r.min, r.max)
        }
        if (!c.setRangeValue(s.id, target)) return null
        var now: Float? = null
        repeat(6) {
            if (now == null || kotlin.math.abs(now!! - target) > span * 0.03f) {
                kotlinx.coroutines.delay(150); c.waitForSettle(800)
                now = c.buildSnapshot().elements.firstOrNull { it.range != null && it.label == s.label }?.range?.current
            }
        }
        val reached = now?.let { kotlin.math.abs(it - target) <= span * 0.03f } == true
        if (!reached) return null
        c.globalAction("back")
        val frac = (target - r.min) / span
        return action("BRIGHTNESS", if (fraction != null) brightnessSaid(fraction)
            else if ((delta ?: 0f) > 0) "Brightness up, now ${Math.round(frac * 100)} percent." else "Brightness down, now ${Math.round(frac * 100)} percent.")
    }

    /** Pull down Quick Settings and wait until it is the active window and has stopped animating. Returns the package
     *  that was in front before (to tell a drawn shade from the old app), or null if it couldn't be opened. */
    private suspend fun openShade(c: AccessibilityController): String? {
        val before = c.activeWindowPackage() ?: ""
        if (!c.globalAction("quick_settings")) return null
        var waited = 0
        while (c.activeWindowPackage() == before && waited < 12) { kotlinx.coroutines.delay(200); waited++ }
        c.waitForSettle(1500)
        return before
    }

    // ---------------------------------------------------------------- flashlight / torch
    /** Torch state per camera id, kept current by a TorchCallback (it fires once with the state on registration). */
    private val torchOn = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    @Volatile private var torchWatching = false

    private fun watchTorch(cm: CameraManager) {
        if (torchWatching) return
        torchWatching = true
        try {
            cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { torchOn[cameraId] = enabled }
                override fun onTorchModeUnavailable(cameraId: String) { torchOn.remove(cameraId) }
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        } catch (t: Throwable) { torchWatching = false }
    }

    private suspend fun torchState(): FastOutcome? {
        val cm = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        watchTorch(cm)
        if (torchOn.isEmpty()) kotlinx.coroutines.delay(150)   // first registration: let the initial callback land
        if (torchOn.isEmpty()) return handled(FastIntent.FlashlightQuery, "I can't tell whether the flashlight is on right now.")
        return handled(FastIntent.FlashlightQuery, if (torchOn.values.any { it }) "The flashlight is on." else "The flashlight is off.")
    }

    private fun torch(on: Boolean): FastOutcome? {
        val cm = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        val id = try {
            cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (t: Throwable) { null } ?: return action("FLASHLIGHT", "This phone doesn't have a flashlight I can reach.")
        watchTorch(cm)
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
    private suspend fun toggleBluetooth(on: Boolean): FastOutcome? {
        val a = BluetoothAdapter.getDefaultAdapter() ?: return null
        return try {
            val ok = if (on) a.enable() else a.disable()
            if (ok) action(if (on) "BLUETOOTH_ON" else "BLUETOOTH_OFF", "Turning Bluetooth ${if (on) "on" else "off"}.")
            else quickToggle(FastIntent.QuickToggle("bluetooth", on))   // Android 13+ refuses apps → its Quick Settings tile
        } catch (t: Throwable) { quickToggle(FastIntent.QuickToggle("bluetooth", on)) }
    }

    // ---------------------------------------------------------------- Quick Settings tiles (no LLM)
    /**
     * Pull down Quick Settings → find the tile by name ([QuickTiles]) → tap → VERIFY its checked state → close the
     * shade. "Already on" is only said when the tile really was. Anything unexpected (tile not on this phone's
     * panel, a tap that opens a dialog instead of flipping) returns null and the agent continues FROM THE CURRENT
     * SCREEN — never a claimed success that didn't happen.
     */
    private suspend fun quickToggle(t: FastIntent.QuickToggle): FastOutcome? {
        val tile = QuickTiles.byKey(t.key) ?: return null
        val c = controller() ?: return null
        val t0 = SystemClock.uptimeMillis()
        // openShade waits (cheaply) until the shade IS the active window, then until it stops animating: a snapshot
        // mid-animation blocks on System UI's busy UI thread (4–5 s on the emulator).
        val before = openShade(c) ?: return null
        suspend fun look(tries: Int): com.tekyerema.accessibility.UiElement? {
            repeat(tries) { i ->
                if (i > 0) { kotlinx.coroutines.delay(150); c.waitForSettle(1000) }
                val s0 = SystemClock.uptimeMillis()
                val snap = c.buildSnapshot()
                val hit = QuickTiles.find(snap, tile)
                Log.d(TAG, "[QS] look#$i snapshot=${SystemClock.uptimeMillis() - s0}ms n=${snap.elements.size} " +
                    "app=${snap.appPackage} hit=${hit?.label} at=${SystemClock.uptimeMillis() - t0}ms")
                if (hit != null) return hit
                // the panel is fully drawn (its tiles are there) and this one isn't among them → stop re-reading
                if (snap.appPackage != before && snap.elements.count { it.checkable } >= 3) return null
            }
            return null
        }
        var el = look(6)
        if (el == null) {
            // not on the first page: swipe the tile grid left once (QS pages scroll horizontally)
            val tiles = c.buildSnapshot().elements.filter { it.checkable }
            if (tiles.isNotEmpty()) {
                val y = (tiles.minOf { it.bounds.top } + tiles.maxOf { it.bounds.bottom }) / 2
                val (w, _) = c.screenSize()
                c.swipe((w * 0.85).toInt(), y, (w * 0.15).toInt(), y, 250)
                kotlinx.coroutines.delay(200); c.waitForSettle(1500)
                el = look(4)
            }
        }
        val found = el ?: run { c.globalAction("back"); return null }   // this phone's panel has no such tile → agent
        val state = if (t.on) "on" else "off"
        if (found.checked == t.on) {
            c.globalAction("back")
            return action(t.name, "${tile.spoken} is already $state.")
        }
        if (!c.clickElement(found.id)) return null
        var ok = false
        repeat(8) { if (!ok) { kotlinx.coroutines.delay(150); c.waitForSettle(1000); ok = QuickTiles.find(c.buildSnapshot(), tile)?.checked == t.on } }
        if (!ok) {
            Log.i(TAG, "[LOCAL_FAST_PATH] ${tile.key} tile didn't flip (dialog?) → agent continues from this screen")
            return null
        }
        c.globalAction("back")
        return action(t.name, "${tile.spoken} is $state.")
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
