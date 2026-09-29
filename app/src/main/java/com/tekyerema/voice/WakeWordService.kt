package com.tekyerema.voice

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/**
 * Hands-free WAKE WORD ("Hey Tɛkyerɛma") — free, no account, fully on-device. A foreground (microphone)
 * service streams the mic in 80 ms frames through [WakeScorer] (openWakeWord features + our heads trained on
 * the owner's recordings) and [WakeGate] (3 consecutive hits, then a ~2 s refractory). On a fire we open the
 * normal voice turn; the wake phrase itself is never transcribed or sent to the model — nothing is recorded,
 * stored or logged except scores.
 *
 * Coexistence: while a turn/confirmation/secure-input is active the mic is RELEASED (single mic owner) and
 * reopened only when the assistant is idle again — so the wake word never restarts a task in flight.
 */
class WakeWordService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    @Volatile private var handling = false
    /** C-12: an interjection (a wake heard while a task works) is opening the answer mic. Separate from [handling],
     *  which belongs to the turn the first wake started and must not be cleared by the interjection. */
    @Volatile private var interjecting = false

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); instance = this }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        startForegroundSafe()
        if (!Settings(this).wakeWordEnabled) { Log.i(TAG, "[WAKE] disabled by setting"); stopSelf(); return START_NOT_STICKY }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "[WAKE] microphone permission not granted — not arming"); stopSelf(); return START_NOT_STICKY
        }
        armLoop()
        return START_STICKY
    }

    private fun armLoop() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch(Dispatchers.IO) {
            val scorer = WakeScorer(applicationContext)
            if (!scorer.load()) { Log.w(TAG, "[WAKE] wake models failed to load (assets/wakeword/) — not arming"); stopSelf(); return@launch }
            Log.i(TAG, "[WAKE] armed — say 'Hey Tɛkyerɛma'")
            try { listen(scorer) } finally { scorer.close() }
        }
    }

    @SuppressLint("MissingPermission")   // RECORD_AUDIO checked in onStartCommand
    private suspend fun listen(scorer: WakeScorer) {
        val gate = WakeGate()
        val frame = FloatArray(WakeScorer.FRAME)
        val minBuf = AudioRecord.getMinBufferSize(WakeScorer.SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        var rec: AudioRecord? = null
        fun release() { rec?.let { try { it.stop() } catch (_: Throwable) {}; try { it.release() } catch (_: Throwable) {} }; rec = null; micOpen = false }
        var hb = 0L; var top = 0f
        try {
            while (currentCoroutineContext().isActive) {
                val now = System.currentTimeMillis()
                if (now - hb >= 10_000) {                          // heartbeat: the tuning window into live scores
                    // frames = 80 ms frames scored in these ~10 s (125 = keeping up with real time); ms = scorer time
                    // per frame (must stay under 80); full = frames that needed the full recompute (the peak changed).
                    val st = scorer.takeStats()
                    if (hb != 0L) Log.i(TAG, "[WAKE] hb mic=${rec != null} top=${"%.2f".format(top)} bar=${WakeGate.bar()} " +
                        "frames=${st[0]} ms(avg/max)=${st[1]}/${st[2]} full=${st[3]} incremental=${scorer.incremental} ${scorer.takeReasons()}")
                    hb = now; top = 0f
                }
                // A turn owns the mic — except while a task is working with the mic free, when the wake word keeps
                // listening so the user can interject ("Hey Tɛkyerɛma… pause / what are you doing / send it to Kofi").
                // (`handling` stays true for the whole wake-started turn, so it must not block this — seen on the A20e:
                // the mic was released for an entire task and "Hey Tɛkyerɛma, stop" could not be heard.)
                // Listen while a task works with the mic free (interjection) or waits on a reply nobody is listening for.
                // voice/io: the phone's recognizer asked for the mic (handOver) — closed until it is done, plus a grace period.
                if (handedOver()) { release(); delay(50); continue }
                if (interjecting || ((handling || VoiceEngine.isRunning) && !VoiceEngine.canInterject && !VoiceEngine.canRespond)) { release(); delay(250); continue }
                if (rec == null) {
                    // ~2.5 s of buffering (32 frames): a slow scoring frame is absorbed instead of dropping audio.
                    rec = openMic(maxOf(minBuf, WakeScorer.FRAME * 4 * 32))
                    if (rec == null) { delay(1000); continue }
                    micOpen = true
                    gate.reset(); scorer.reset()                     // fresh listen: no audio from before the pause
                }
                val got = rec!!.read(frame, 0, WakeScorer.FRAME, AudioRecord.READ_BLOCKING)
                if (got < 0) {                                       // DEAD_OBJECT etc. are RETURNED, not thrown
                    Log.w(TAG, "[WAKE] mic read error $got — reopening"); release(); delay(200); continue
                }
                if (got == 0) continue
                var e = 0f
                for (j in 0 until got) e += frame[j] * frame[j]
                val rms = sqrt(e / got)
                val scores = scorer.feed(if (got == frame.size) frame else frame.copyOf(got))
                scores[WakeScorer.FIRE_HEAD]?.let { if (it > top) top = it }
                val fired = gate.feed(rms, scores)
                if (monitor) mon.frame(rms, scores, fired, scorer)
                if (fired && SecureStep.active()) {
                    // The user is typing a PIN and TalkBack is speaking the keys — never open the mic by ourselves now.
                    Log.i(TAG, "[WAKE] detection ignored: a PIN is being entered")
                } else if (fired) {
                    Log.i(TAG, "[WAKE] 'Hey Tɛkyerɛma' detected (hey=${"%.3f".format(scores[WakeScorer.FIRE_HEAD])}) → activating")
                    release()                                        // hand the mic over before the turn opens it
                    onWake()
                }
            }
        } catch (c: CancellationException) { throw c }
        catch (t: Throwable) { Log.w(TAG, "[WAKE] listen loop error: ${t.message}") }
        finally { release() }
    }

    /** Plain MIC first — the source the V2 bars (RMS floor, raw-peak floor) were measured on; the scorer
     *  peak-normalises, so no AGC is needed. VOICE_RECOGNITION only as a fallback. */
    @SuppressLint("MissingPermission")
    private fun openMic(bufSize: Int): AudioRecord? {
        for (src in listOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION)) {
            val r = try {
                AudioRecord(src, WakeScorer.SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, bufSize)
            } catch (_: Throwable) { null } ?: continue
            if (r.state == AudioRecord.STATE_INITIALIZED) {
                try { r.startRecording(); watchSilencing(r); return r } catch (_: Throwable) {}
            }
            try { r.release() } catch (_: Throwable) {}
        }
        Log.w(TAG, "[WAKE] could not open the microphone")
        return null
    }

    /** voice/io device check (brief §A.3): Android 10+ silences one of two concurrent captures. Log when the wake mic is
     *  the silenced one (e.g. the phone's recognizer or a call took the mic) — scores only, never audio. */
    private fun watchSilencing(r: AudioRecord) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            var last: Boolean? = null
            r.registerAudioRecordingCallback({ it.run() }, object : android.media.AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>?) {
                    val mine = configs?.firstOrNull { it.clientAudioSessionId == r.audioSessionId } ?: return
                    if (mine.isClientSilenced != last) { last = mine.isClientSilenced; Log.i(TAG, "[WAKE] wake mic silenced=${mine.isClientSilenced}") }
                }
            })
        } catch (_: Throwable) {}
    }

    // ---- Monitor mode ("what does it hear?") — debug only, OFF by default: --es wake_monitor 1|0 ----
    private val mon = Monitor()

    /**
     * The wake model hears no WORDS — only "how much does this sound like Hey Tɛkyerɛma" (0–1). Monitor mode
     * makes that visible: ~4×/s a `[WAKE-MON]` line (mic level, hey score, bare score), and for every
     * CANDIDATE (hey ≥ [CANDIDATE]) or wake, the exact 2.7 s the heads scored is saved as a WAV and
     * transcribed by the on-device Gh-English model → `[WAKE] heard: '…'`. Clips live in
     * externalCacheDir/wake (adb-pullable, last [KEEP_CLIPS] kept). Never on unless the owner turns it on.
     */
    private inner class Monitor {
        private var n = 0
        private var inCandidate = false
        private var best = 0f
        private var bestBare = 0f
        private var asr: SherpaAsr? = null
        private val io = Dispatchers.IO.limitedParallelism(1)     // one clip at a time: shared ASR, ordered logs

        fun frame(rms: Float, scores: Map<String, Float>, fired: Boolean, scorer: WakeScorer) {
            val hey = scores[WakeScorer.FIRE_HEAD] ?: 0f
            val bare = scores["tekyerema"] ?: 0f
            if (++n % 3 == 0) {
                val bar = "#".repeat((minOf(rms * 400f, 20f)).toInt())
                Log.i(TAG, if (scores.isEmpty()) "[WAKE-MON] level ${"%.3f".format(rms)} $bar (quiet — not scored)"
                    else "[WAKE-MON] level ${"%.3f".format(rms)} $bar hey=${"%.2f".format(hey)} bare=${"%.2f".format(bare)}")
            }
            if (hey >= CANDIDATE) { inCandidate = true; best = maxOf(best, hey); bestBare = maxOf(bestBare, bare) }
            // Report when the candidate ends (score falls back) or fires — the buffer then holds the whole phrase.
            if (fired || (inCandidate && hey < CANDIDATE)) {
                report(scorer.snapshot(), best, bestBare, fired)
                inCandidate = false; best = 0f; bestBare = 0f
            }
        }

        private fun report(audio: FloatArray, hey: Float, bare: Float, fired: Boolean) {
            scope.launch(io) {
                val pcm = ByteArray(audio.size * 2)
                for (i in audio.indices) {
                    val v = (audio[i].coerceIn(-1f, 1f) * 32767f).toInt()
                    pcm[2 * i] = (v and 0xFF).toByte(); pcm[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
                }
                val wav = Wav.wrap(pcm, WakeScorer.SR)
                val file = try {
                    val dir = File(externalCacheDir ?: cacheDir, "wake").apply { mkdirs() }
                    dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(KEEP_CLIPS - 1)?.forEach { it.delete() }
                    File(dir, "wake_${SimpleDateFormat("HHmmss", Locale.US).format(Date())}_hey${(hey * 100).toInt()}_${if (fired) "FIRED" else "miss"}.wav")
                        .apply { writeBytes(wav) }
                } catch (t: Throwable) { null }
                val words = try {
                    val a = asr ?: SherpaAsr(applicationContext).also { asr = it }
                    if (a.ready) a.rawTranscript(wav, Lang.GH_EN)?.trim()?.ifBlank { "(nothing)" } else "(on-device model not ready)"
                } catch (t: Throwable) { "(transcribe failed: ${t.message})" }
                Log.i(TAG, "[WAKE] heard: '$words'  hey=${"%.2f".format(hey)} bare=${"%.2f".format(bare)} " +
                    "${if (fired) "→ FIRED" else "→ below bar ${WakeGate.bar()}"}  clip=${file?.name}")
            }
        }
    }

    /** A wake match: hand the mic to a voice turn, then resume wake listening only once it's fully done. */
    private fun onWake() {
        if (VoiceEngine.isRunning || VoiceEngine.canRespond) {
            // C-12: a task is working — this wake is an interjection, heard on the answer mic while the task runs on;
            // or the task waits on the user — this wake is the reply (VoiceEngine.start routes it to respond()).
            // Checked BEFORE [handling]: a task started by an earlier wake keeps `handling` set until it ends.
            if (!(VoiceEngine.canInterject || VoiceEngine.canRespond) || interjecting) return
            interjecting = true
            scope.launch {
                try { withContext(Dispatchers.Main) { VoiceEngine.start() }; delay(1_500) } finally { interjecting = false }
            }
            return
        }
        if (handling) return
        handling = true
        scope.launch {
            try {
                launchVoice()
                // Wait for the turn to ACTUALLY start (not a fixed timer: a slow activity launch would let the
                // wake mic reopen first and re-fire — V2 audit BUG 1), then for it to finish.
                withTimeoutOrNull(8_000) {
                    VoiceEngine.state.first { VoiceEngine.isRunning || it.state != VoiceState.IDLE }
                }
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
            .setContentText("Listening for \"Hey Tɛkyerɛma\"")
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
        /** Monitor mode (debug): stream scores + save/transcribe candidate clips. Runtime-only, never persisted. */
        @Volatile var monitor = false
        private const val CANDIDATE = 0.5f
        private const val KEEP_CLIPS = 20

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

        // ---- voice/io mic hand-over (the brief §A.4.3) ----
        // SpeechRecognizer opens its own microphone in another process; two ordinary captures never both get audio on
        // Android 10+ (one is silenced), and the loop above only polled VoiceEngine.isRunning every ≤ 250 ms. So the
        // system recognizer asks for the mic explicitly and starts only once the wake loop has really let go.
        @Volatile private var micOpen = false
        private val holders = java.util.concurrent.atomic.AtomicInteger(0)
        @Volatile private var reopenAfter = 0L
        private fun handedOver() = holders.get() > 0 || android.os.SystemClock.uptimeMillis() < reopenAfter

        /** Close the wake word's microphone and wait (≤ [timeoutMs]) until it is really released. Pair with [reclaim]. */
        suspend fun handOver(timeoutMs: Long = 800): Boolean {
            holders.incrementAndGet()
            val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
            while (micOpen && android.os.SystemClock.uptimeMillis() < deadline) delay(20)
            if (micOpen) Log.w(TAG, "[WAKE] hand-over: the wake mic is still open after ${timeoutMs} ms")
            return !micOpen
        }

        /** The recognizer is done with the mic: the wake word may re-arm after [graceMs]. */
        fun reclaim(graceMs: Long = 300) {
            reopenAfter = android.os.SystemClock.uptimeMillis() + graceMs
            holders.updateAndGet { if (it > 0) it - 1 else 0 }
        }

        /** DEBUG/testing: simulate a detection (no audio) → activation path. */
        fun simulate(ctx: Context) {
            instance?.let { it.onWake(); return }
            try { ctx.startActivity(Intent(ctx, VoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
        }
    }
}
