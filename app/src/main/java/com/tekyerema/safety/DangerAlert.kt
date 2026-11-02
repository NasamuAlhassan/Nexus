package com.tekyerema.safety

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.telecom.TelecomManager
import android.util.Log
import com.tekyerema.audit.AuditLog
import com.tekyerema.core.config.AppConfig
import com.tekyerema.notifications.NotificationStore
import com.tekyerema.settings.Settings
import com.tekyerema.tools.LocationTool
import com.tekyerema.tools.SendSmsTool
import com.tekyerema.tools.ToolContext
import com.tekyerema.voice.VoiceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The danger code word's response (docs/agent/DANGER_CODE_WORD.md). The code word is heard → the phone buzzes and says it, and for
 * `safety.danger.cancelSeconds` (5) the user can cancel by voice, by the cancel PIN, or by the notification's Cancel button.
 * Not cancelled → in parallel: the police toll-free line is dialled with the speaker on (the live audio goes with the call), the
 * position is found, the emergency contact and trusted contacts are texted a map link, and an optional backend is told. No data
 * connection is needed for the call or the texts. Nothing is recorded or uploaded before the trigger.
 *
 * Android never lets an app place a call to an emergency number such as 112 by itself, so the first number tried is 191, a
 * normal toll-free number; if no call can be started the dial pad opens on the number. `safety.danger.testNumber` replaces every
 * number, and `safety.danger.dryRun` does everything except call and text — rehearsals never reach a real emergency line.
 */
object DangerAlert {
    private const val TAG = "Tekyerema"
    private const val CHANNEL = "danger_alert"
    private const val NOTE_ID = 7191
    const val ACTION_CANCEL = "com.tekyerema.DANGER_CANCEL"

    enum class Phase { IDLE, COUNTDOWN, RUNNING }
    @Volatile var phase = Phase.IDLE; private set
    private var job: Job? = null
    @Volatile private var manualRun = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Called with every heard request, before it is routed. True when the words were the alert's (a cancel, or the code word). */
    fun heard(app: Context, text: String): Boolean {
        val s = Settings(app)
        if (phase == Phase.COUNTDOWN) {
            if (DangerCodeWord.isCancel(text, s.dangerCancelPin)) { cancel(app, "voice"); return true }
            return false
        }
        if (phase != Phase.IDLE) return false
        if (DangerSos.matches(text)) return trigger(app, "SOS spoken", manual = true)
        if (!s.dangerEnabled) return false
        return if (DangerCodeWord.matches(text, s.dangerCodeWord)) trigger(app, "code word heard in a request") else false
    }

    /** Start the cancel window. False when the feature is off (a [manual] SOS needs no set-up) or an alert is already under way. */
    @Synchronized
    fun trigger(app: Context, source: String, manual: Boolean = false): Boolean {
        val s = Settings(app)
        if ((!manual && !s.dangerEnabled) || phase != Phase.IDLE) return false
        phase = Phase.COUNTDOWN
        manualRun = manual
        val seconds = AppConfig.int("safety.danger.cancelSeconds", 5).coerceIn(2, 15)
        audit((if (manual) "SOS" else "danger code") + " triggered ($source); cancel window $seconds s")
        buzz(app)
        notify(app, (if (manual) "SOS" else "Danger code heard") + " — calling for help in $seconds s", cancelable = true)
        job = scope.launch {
            launch { try { VoiceEngine.speakIn(DangerPlan.warning(seconds, manual), VoiceEngine.language()) } catch (_: Throwable) {} }
            var left = seconds * 1000
            while (left > 0) { delay(250); left -= 250 }
            run(app)
        }
        return true
    }

    @Synchronized
    fun cancel(app: Context, why: String): Boolean {
        if (phase != Phase.COUNTDOWN) return false
        job?.cancel(); job = null; phase = Phase.IDLE
        audit("danger alert cancelled ($why)")
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(NOTE_ID)
        scope.launch { try { VoiceEngine.speakIn("Cancelled. No one was called.", VoiceEngine.language()) } catch (_: Throwable) {} }
        return true
    }

    private suspend fun run(app: Context) {
        phase = Phase.RUNNING
        val s = Settings(app)
        val dry = AppConfig.bool("safety.danger.dryRun", false)
        val numbers = DangerPlan.numbers(AppConfig.stringList("safety.danger.numbers", listOf("191", "112")), AppConfig.string("safety.danger.testNumber", ""))
        try {
            coroutineScope {
                val calling = async { if (dry) "dry run: would call ${numbers.firstOrNull()}" else placeFirst(app, numbers) }
                val where = async { position(app) }
                val (link, acc) = where.await()
                val time = SimpleDateFormat("HH:mm", Locale.US).format(Date())
                val text = DangerPlan.message(link, acc, time, manualRun)
                val who = DangerPlan.recipients(s.emergencyContactNumber.takeIf { it.isNotBlank() }?.let { s.emergencyContactName.ifBlank { it } to it }, s.trustedContacts)
                val texted = if (dry) 0 else who.count { (_, n) -> textTo(app, n, text) }
                val told = if (dry) false else tellBackend(link, acc, time)
                val done = calling.await()
                audit("danger alert sent: $done; location ${if (link != null) "found" else "not found"}; texted $texted of ${who.size}; backend ${if (told) "told" else "not told"}")
                notify(app, "Alert sent: $done", cancelable = false)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "[DANGER] alert failed: ${t.message}")
        } finally { phase = Phase.IDLE }
    }

    private suspend fun position(app: Context): Pair<String?, Int?> {
        val r = withTimeoutOrNull(AppConfig.long("safety.danger.locationWaitMs", 8000L)) {
            try { LocationTool.execute(JSONObject().put("action", "link"), emergencyCtx(app)) } catch (_: Throwable) { null }
        }
        val d = r?.data ?: return null to null
        return d.optString("link").takeIf { it.isNotBlank() } to d.optDouble("accuracy", Double.NaN).takeIf { !it.isNaN() }?.toInt()
    }

    private suspend fun textTo(app: Context, number: String, text: String): Boolean = try {
        SendSmsTool.execute(JSONObject().put("number", number).put("message", text), emergencyCtx(app)).data?.optBoolean("sent", false) == true
    } catch (_: Throwable) { false }

    private fun emergencyCtx(app: Context) = ToolContext(app, null, NotificationStore, emergency = true)

    private fun placeFirst(app: Context, numbers: List<String>): String {
        val speaker = AppConfig.bool("safety.danger.speaker", true)
        val canCall = app.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        if (canCall) for (n in numbers) {
            try {
                (app.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).placeCall(Uri.fromParts("tel", n, null),
                    Bundle().apply { if (speaker) putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true) })
                return "calling $n"
            } catch (_: Throwable) {}
        }
        val first = numbers.firstOrNull() ?: return "no number to call"
        return try {
            app.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$first")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "dial pad opened on $first"
        } catch (_: Throwable) { "could not start a call" }
    }

    private fun tellBackend(link: String?, acc: Int?, time: String): Boolean {
        val url = AppConfig.string("safety.danger.backendUrl", "").takeIf { it.startsWith("https://") } ?: return false
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 5000; c.readTimeout = 5000; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(JSONObject().put("event", "danger_code").put("time", time).put("location", link ?: "").put("accuracy_m", acc ?: -1).toString().toByteArray()) }
            c.responseCode in 200..299
        } catch (_: Throwable) { false }
    }

    private fun buzz(app: Context) {
        try {
            val v = app.getSystemService(Vibrator::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26) com.tekyerema.core.Haptics.play(app, VibrationEffect.createWaveform(longArrayOf(0, 400, 150, 400, 150, 400), -1),
                com.tekyerema.core.Haptics.Kind.ALERT)
            else @Suppress("DEPRECATION") v.vibrate(1200)
        } catch (_: Throwable) {}
    }

    private fun notify(app: Context, text: String, cancelable: Boolean) {
        try {
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Danger alert", NotificationManager.IMPORTANCE_HIGH))
            val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(app, CHANNEL) else @Suppress("DEPRECATION") android.app.Notification.Builder(app)
            b.setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle("Nexus danger alert").setContentText(text).setOngoing(cancelable)
            if (cancelable) b.addAction(android.app.Notification.Action.Builder(null, "Cancel",
                PendingIntent.getBroadcast(app, 0, Intent(ACTION_CANCEL).setPackage(app.packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
            nm.notify(NOTE_ID, b.build())
        } catch (_: Throwable) {}
    }

    private fun audit(text: String) { try { AuditLog.record("safety", "danger", text) } catch (_: Throwable) {}; Log.i(TAG, "[DANGER] $text") }
}

/** The notification's Cancel button — a silent way to stand the alert down. */
class DangerCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { DangerAlert.cancel(context.applicationContext, "notification") }
}
