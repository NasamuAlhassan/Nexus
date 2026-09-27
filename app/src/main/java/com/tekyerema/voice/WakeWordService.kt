package com.tekyerema.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tekyerema.settings.Settings
import com.tekyerema.ui.VoiceActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Hands-free WAKE WORD ("Tɛkyerɛma") — free, no account, fully on-device. It reuses the pieces the app
 * already ships: the Silero VAD + the bundled Gh-English tiny model. A foreground (microphone) service
 * listens continuously; each speech segment the VAD isolates is transcribed on-device (no cloud, no quota),
 * and if it matches the wake word we activate Tɛkyerɛma. The following instruction is then captured by the
 * normal voice pipeline (with the good online ASR), so the wake word itself is never sent to the model.
 *
 * Trade-off (owner's choice over Picovoice, which now needs a paid/company account): a dedicated hotword
 * engine would be lower-power and lower-latency; this is ~1-2 s slower and can occasionally mis-trigger, but
 * it needs no account, no extra model, and works with the custom word "Tɛkyerɛma" today.
 *
 * Coexistence: while a turn/confirmation/secure-input is active we pause listening (single mic owner), and
 * resume only when the assistant is idle again — so the wake word never restarts a task in flight.
 */
class WakeWordService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private var asr: SherpaAsr? = null
    private val mic by lazy { MicCapture(appContext = applicationContext) }
    @Volatile private var handling = false

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); instance = this }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        startForegroundSafe()
        if (!Settings(this).wakeWordEnabled) { Log.i(TAG, "[WAKE] disabled by setting"); stopSelf(); return START_NOT_STICKY }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "[WAKE] microphone permission not granted — not arming"); stopSelf(); return START_NOT_STICKY
        }
        val a = SherpaAsr(applicationContext)
        if (!a.ready) { Log.i(TAG, "[WAKE] on-device model not ready — can't run the VAD wake word"); stopSelf(); return START_NOT_STICKY }
        asr = a
        armLoop()
        return START_STICKY
    }

    private fun armLoop() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            Log.i(TAG, "[WAKE] listening for 'Tɛkyerɛma' (on-device VAD + Gh-English tiny model)")
            while (isActive) {
                if (handling || VoiceEngine.isRunning) { delay(500); continue }   // pause while a turn owns the mic
                val cap = try { mic.capture({}, {}) }
                catch (c: CancellationException) { throw c }
                catch (t: Throwable) { Log.w(TAG, "[WAKE] capture error: ${t.message}"); delay(800); continue }
                if (cap is CaptureResult.Success) {
                    val text = try { asr?.rawTranscript(cap.wav, Lang.GH_EN) } catch (_: Throwable) { null }
                    if (text != null && matchesWake(text)) {
                        Log.i(TAG, "[WAKE] wake word matched → activating (the wake word is NOT sent to the model)")
                        onWake()
                    }
                }
            }
        }
    }

    /** A wake match: hand the mic to a voice turn, then resume wake listening only once it's fully done. */
    private fun onWake() {
        if (handling || VoiceEngine.isRunning) return
        handling = true
        scope.launch {
            try {
                launchVoice()
                delay(600)                                          // let the turn take the mic
                withTimeoutOrNull(180_000) {
                    VoiceEngine.state.first { !VoiceEngine.isRunning && it.state == VoiceState.IDLE }
                }
                delay(400)
            } finally { handling = false }
        }
    }

    private fun launchVoice() {
        try {
            startActivity(Intent(this, VoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) { Log.w(TAG, "[WAKE] activation failed: ${t.message}") }
    }

    private fun startForegroundSafe() {
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Tɛkyerɛma")
            .setContentText("Listening for the wake word")
            .setSmallIcon(applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_btn_speak_now)
            .setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        try { startForeground(NOTIF_ID, n) } catch (t: Throwable) { Log.w(TAG, "[WAKE] startForeground failed: ${t.message}") }
    }

    override fun onDestroy() {
        loopJob?.cancel()
        if (instance === this) instance = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Tekyerema"
        private const val CHANNEL = "tekyerema_wake"
        private const val NOTIF_ID = 4202
        @Volatile private var running = false
        @Volatile private var instance: WakeWordService? = null

        /** Forms the Gh-English tiny model actually produces for "Tɛkyerɛma" — observed on-device:
         *  "Tɛkyerɛma" → "hit the drama"; "Hey Tɛkyerɛma" → "hey dear kwama". Keep adding real transcripts
         *  from the `[ASR] sherpa-tiny(eng) … -> '…'` log. The tiny model is approximate, so this matches the
         *  SOUND-ALIKE core, not the spelling. */
        private val FORMS = listOf(
            "tekyerema", "tɛkyerɛma", "kyerema", "kyerɛma", "cherema", "cherama", "chirema",
            "kwama", "drama", "krama", "grama", "kwarma", "kraama", "chama", "tama", "kyerma", "tekyer"
        )
        private val FILLER = setOf("hey", "the", "a", "ok", "okay", "hi", "hello", "dear", "hit", "yo", "its", "it", "hey,", "so", "and", "um", "uh")

        /** True when a SHORT on-device transcript sounds like the wake word. Strips leading filler ("hey",
         *  "the", "dear", "hit"…) that the model tacks on, then matches the remaining core against [FORMS]
         *  (exact/contains/edit-distance ≤ 2). Rejects anything longer than a few words, so ordinary commands
         *  ("call Kwame", "what's the weather") don't trip it. Generous on purpose — tune [FORMS] from logs. */
        fun matchesWake(raw: String): Boolean {
            val words = raw.lowercase().replace(Regex("[^a-zɛɔ ]"), " ").trim()
                .split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.isEmpty() || words.size > 4) return false          // the wake word is short; a sentence isn't it
            val core = words.filterNot { it in FILLER }
            if (core.isEmpty()) return false
            val joined = core.joinToString("")
            if (joined.length < 4) return false
            if (FORMS.any { joined == it || joined.contains(it) }) return true
            return FORMS.any { editDistance(joined, it) <= 2 }
        }

        private fun editDistance(a: String, b: String): Int {
            val dp = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = dp[0]; dp[0] = i
                for (j in 1..b.length) {
                    val tmp = dp[j]
                    dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = tmp
                }
            }
            return dp[b.length]
        }

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL) == null)
                mgr.createNotificationChannel(NotificationChannel(CHANNEL, "Wake word", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Lets you activate Tɛkyerɛma hands-free."; setShowBadge(false)
                })
        }

        fun start(ctx: Context) {
            if (running || !Settings(ctx).wakeWordEnabled) return
            running = true
            val i = Intent(ctx, WakeWordService::class.java)
            try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i) }
            catch (t: Throwable) { running = false; Log.w(TAG, "[WAKE] start failed: ${t.message}") }
        }

        fun stop(ctx: Context) {
            if (!running) return
            running = false
            try { ctx.stopService(Intent(ctx, WakeWordService::class.java)) } catch (_: Throwable) {}
        }

        /** DEBUG/testing: simulate a detection (no audio) → activation path. */
        fun simulate(ctx: Context) {
            instance?.let { it.onWake(); return }
            try { ctx.startActivity(Intent(ctx, VoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
        }
    }
}
