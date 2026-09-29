package com.tekyerema.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext

/** Result of one capture attempt — an explicit outcome so failures name their stage (§29). */
sealed class CaptureResult {
    data class Success(val wav: ByteArray, val speechMs: Long) : CaptureResult()
    object NoSpeech : CaptureResult()          // listened, heard nothing
    object TooShort : CaptureResult()          // a blip, rejected by VAD min-duration
    object Cancelled : CaptureResult()
    data class Error(val message: String) : CaptureResult()
}

/**
 * Real microphone path: 16 kHz mono PCM16 via [AudioRecord], segmented by [EnergyVad] into one
 * utterance. Keeps a pre-roll so the leading phoneme isn't clipped (§6), caps total duration, and
 * gives up after sustained initial silence (§5 empty/short handling). Cancellable at any point (§25).
 */
class MicCapture(
    private val appContext: android.content.Context? = null,
    private val sampleRate: Int = 16000,
    private val preRollMs: Int = 600,
    private val noSpeechTimeoutMs: Int = 8000,
    /** The caller's listening tone plays right after [Recorder.capture]'s onListening; frames in
     *  this window are kept (pre-roll) but not shown to the VAD, so the tone can't trigger it. */
    private val vadGuardMs: Int = 400,
    /** The listening tone itself: frames in this window are dropped entirely (never pre-roll), so the beep is
     *  never handed to the recognizer (B-14). */
    private val toneGuardMs: Int = 250,
    /** B-03: this mic's silence strategy when a capture doesn't name one (null = constructor timings). */
    private val defaultStrategy: (() -> VadStrategy)? = null,
    /** B-07: the barge-in mic listens WHILE our own voice plays — prefer the echo-cancelled voice-call source and
     *  attach AcousticEchoCanceler/NoiseSuppressor, require a longer onset, and leave audio focus alone. */
    private val echoCancelled: Boolean = false,
    private val onsetMs: Int = 40
) : Recorder {
    private val vad = EnergyVad(sampleRate = sampleRate)
    // §P0 — prefer Silero (neural) VAD; it handles the A20's low-SNR raw mic where an energy threshold can't.
    // Falls back to the energy VAD if the model can't load (or no context).
    private val silero: SileroVad? = appContext?.let { runCatching { SileroVad(it, sampleRate, startFrames = (onsetMs / 20).coerceAtLeast(2)) }.getOrNull()?.takeIf { s -> s.ready } }
    private val vadName = if (silero != null) "silero" else "energy"
    private fun segReset() { if (silero != null) silero.reset() else vad.reset() }
    private fun segProcess(f: ShortArray): EnergyVad.Event = silero?.process(f) ?: vad.process(f)
    private val frame = vad.frameSamples
    private val bytesPerSample = 2

    /** Live input level 0..1 (smoothed RMS) while the mic is open; 0 otherwise. UI-only: the VAD
     *  and the captured audio never depend on it. */
    private val _level = kotlinx.coroutines.flow.MutableStateFlow(0f)
    val level: kotlinx.coroutines.flow.StateFlow<Float> = _level
    private fun publishLevel(f: ShortArray) {
        val rms = EnergyVad.rms(f)
        // ~300 = quiet room, ~6000 = normal speech at arm's length; a log scale reads naturally.
        val norm = ((Math.log10(rms.coerceAtLeast(1.0)) - 2.3) / 1.6).coerceIn(0.0, 1.0).toFloat()
        val prev = _level.value
        _level.value = if (norm > prev) prev + (norm - prev) * 0.6f else prev + (norm - prev) * 0.25f
    }

    override suspend fun capture(onListening: () -> Unit, onSpeechStart: () -> Unit): CaptureResult =
        captureInternal(null, onListening, onSpeechStart)

    override suspend fun captureWith(strategy: VadStrategy, onListening: () -> Unit, onSpeechStart: () -> Unit): CaptureResult =
        captureInternal(strategy, onListening, onSpeechStart)

    private val focus = if (echoCancelled) null else appContext?.let { AudioFocus(it) }

    @SuppressLint("MissingPermission")   // caller MUST ensure RECORD_AUDIO is granted first
    private suspend fun captureInternal(
        strategy: VadStrategy?,
        onListening: () -> Unit,
        onSpeechStart: () -> Unit
    ): CaptureResult = withContext(Dispatchers.IO) {
        // B-03: the silence strategy for this capture — explicit, else this mic's default, else the constructor values.
        val strat = strategy ?: defaultStrategy?.invoke()
        silero?.configure(strat?.hangoverMs ?: VadStrategy.NORMAL.hangoverMs, strat?.maxUtteranceMs ?: VadStrategy.NORMAL.maxUtteranceMs)
        val noSpeechMs = strat?.noSpeechMs ?: noSpeechTimeoutMs
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return@withContext CaptureResult.Error("AudioRecord unsupported config ($minBuf)")
        val bufSize = maxOf(minBuf, frame * bytesPerSample * 4)

        // Audio source: prefer the RAWEST stream so the on-device tiny model sees audio like its
        // training data. On Samsung, VOICE_RECOGNITION applies AGC/noise-suppression + "Samsung VAD"
        // that reshapes phonemes and wrecks the tiny Twi model (clean GOLD files transcribe fine; the
        // Samsung-processed live mic does not). UNPROCESSED (API 24+, when the device advertises it)
        // bypasses that; else plain MIC; VOICE_RECOGNITION only as a last resort.
        val sources = buildList {
            if (echoCancelled) add(MediaRecorder.AudioSource.VOICE_COMMUNICATION)   // B-07: platform echo cancellation
            if (android.os.Build.VERSION.SDK_INT >= 24) add(MediaRecorder.AudioSource.UNPROCESSED)
            add(MediaRecorder.AudioSource.MIC)
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        }
        var recorder: AudioRecord? = null; var usedSource = -1
        for (src in sources) {
            val r = try {
                AudioRecord(src, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
            } catch (t: Throwable) { null }
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) { recorder = r; usedSource = src; break }
            try { r?.release() } catch (_: Throwable) {}
        }
        if (recorder == null) return@withContext CaptureResult.Error("AudioRecord create failed for all sources")
        // Raw sources (UNPROCESSED/MIC) have NO auto-gain — on the A20 their speech RMS is ~300-500 vs the
        // ~6000 the model trained on, so the VAD won't fire and the model sees near-silence. Apply our own
        // gain to bring raw speech to GOLD-like level (clean + normal), the one condition that matches
        // training. VOICE_RECOGNITION is already AGC'd → no extra gain.
        val micGain = if (usedSource == MediaRecorder.AudioSource.VOICE_RECOGNITION || usedSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION) 1f else 12f
        // B-07: on the barge-in mic, also attach the device's echo canceller / noise suppressor when it has them.
        val effects = if (echoCancelled) listOfNotNull(
            runCatching { if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) android.media.audiofx.AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true } else null }.getOrNull(),
            runCatching { if (android.media.audiofx.NoiseSuppressor.isAvailable()) android.media.audiofx.NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true } else null }.getOrNull()
        ) else emptyList()
        Log.i("Tekyerema", "[VOICE] mic source=${sourceName(usedSource)} ($usedSource) uiGain=${micGain}x vad=$vadName (VAD sees RAW)")   // §3.8/§P0

        segReset()
        val firstRms = ArrayList<Int>(30)                         // §P0 first ~500 ms of raw RMS, logged once
        val preRollFrames = (preRollMs / (frame * 1000.0 / sampleRate)).toInt().coerceAtLeast(0)
        val preRoll = ArrayDeque<ShortArray>(preRollFrames + 1)
        val speech = ByteArrayOutputStream()
        var started = false
        var idleFrames = 0
        val idleLimit = (noSpeechMs / (frame * 1000.0 / sampleRate)).toInt()
        // Capture diagnostics so a "didn't hear anything" is explainable from the log alone.
        var frames = 0; var maxRms = 0.0; var sumRms = 0.0
        var endReason = "?"                                       // §3.3
        val captureStart = android.os.SystemClock.uptimeMillis()  // §3.11
        val guardFrames = (vadGuardMs / (frame * 1000.0 / sampleRate)).toInt()
        val toneFrames = (toneGuardMs / (frame * 1000.0 / sampleRate)).toInt()
        fun stats() = "vad=$vadName frames=$frames maxRms=${"%.0f".format(maxRms)} avgRms=${"%.0f".format(if (frames == 0) 0.0 else sumRms / frames)} " +
            (if (silero == null) "noiseFloor=${"%.0f".format(vad.currentNoiseFloor)} startThresh=${"%.0f".format(vad.currentStartThreshold)}" else "neural")

        try {
            focus?.acquire(AudioFocus.Kind.LISTEN)   // B-15: pause media so the recognizer hears the user
            recorder.startRecording()
            onListening()
            val buf = ShortArray(frame)
            while (true) {
                coroutineContext.ensureActive()   // cooperative cancellation (§25)
                val n = recorder.read(buf, 0, frame)
                if (n <= 0) {
                    if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE)
                        return@withContext CaptureResult.Error("mic read error $n")
                    continue
                }
                // Keep the RAW frame for the saved audio (never gained ⇒ never clipped). The VAD/level
                // see an AMPLIFIED copy so they trigger reliably on the A20's quiet raw mic, but the audio
                // handed to ASR is the clean raw signal — later peak-normalized once (below) to a consistent
                // level. This fixes the fixed-gain clipping that was distorting loud/close speech for Khaya.
                val f = if (n == frame) buf.copyOf() else buf.copyOf(n)
                val vf = if (micGain != 1f) ShortArray(f.size) { (f[it] * micGain).toInt().coerceIn(-32768, 32767).toShort() } else f
                publishLevel(vf)                                  // UI level: the gained copy reads more responsively
                val rms = EnergyVad.rms(f); frames++; sumRms += rms; if (rms > maxRms) maxRms = rms   // §3.1 diagnostics on RAW
                if (firstRms.size < 25) { firstRms.add(rms.toInt()); if (firstRms.size == 25) Log.i("Tekyerema", "[VOICE] first-frames rawRMS=$firstRms") }
                if (frames <= toneFrames) continue    // B-14: the tone itself never reaches the pre-roll / recognizer
                if (frames <= guardFrames) {          // just after the tone: keep the audio, skip the VAD
                    if (preRoll.size >= preRollFrames && preRoll.isNotEmpty()) preRoll.removeFirst()
                    if (preRollFrames > 0) preRoll.addLast(f)
                    continue
                }
                when (segProcess(f)) {                // §P0 Silero (or energy) on the RAW signal
                    EnergyVad.Event.SILENCE -> {
                        if (!started) {
                            if (preRoll.size >= preRollFrames && preRoll.isNotEmpty()) preRoll.removeFirst()
                            if (preRollFrames > 0) preRoll.addLast(f)
                            if (++idleFrames >= idleLimit) {
                                Log.i("Tekyerema", "[VOICE] end=NO_SPEECH in ${noSpeechMs}ms (${strat?.name ?: "default"}): ${stats()}")   // §3.3
                                return@withContext CaptureResult.NoSpeech
                            }
                        }
                    }
                    EnergyVad.Event.SPEECH_START -> {
                        started = true; onSpeechStart()
                        preRoll.forEach { appendPcm(speech, it) }   // recover the leading phoneme
                        preRoll.clear()
                        appendPcm(speech, f)
                    }
                    EnergyVad.Event.SPEECH -> appendPcm(speech, f)
                    EnergyVad.Event.SPEECH_END -> { appendPcm(speech, f); endReason = "SPEECH_END"; break }
                    EnergyVad.Event.TIMEOUT -> { appendPcm(speech, f); endReason = "TIMEOUT"; break }
                    EnergyVad.Event.TOO_SHORT -> {   // §3.4 a real blip, not silence — tell the caller so it can re-prompt
                        Log.i("Tekyerema", "[VOICE] end=TOO_SHORT: ${stats()}")
                        return@withContext CaptureResult.TooShort
                    }
                }
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) return@withContext CaptureResult.Cancelled
            return@withContext CaptureResult.Error("capture failed: ${t.message}")
        } finally {
            try { recorder.stop() } catch (_: Throwable) {}
            effects.forEach { try { it.release() } catch (_: Throwable) {} }
            recorder.release()
            focus?.release()
            _level.value = 0f
        }

        val rawPcm = speech.toByteArray()
        val preRms = pcmRms(rawPcm)
        val pcm = normalizeRms(rawPcm)
        val speechMs = (pcm.size / bytesPerSample.toLong()) * 1000 / sampleRate
        // §3.3/§3.8/§3.11 — end reason, pre/post RMS, and a per-utterance timing line for regression tracking.
        // B-19: the turn's capture numbers (the first capture of a turn wins; a later yes/no listen doesn't overwrite them)
        val tookMs = android.os.SystemClock.uptimeMillis() - captureStart
        com.tekyerema.core.telemetry.Telemetry.update {
            voice.putIfAbsent("captureMs", tookMs); voice.putIfAbsent("speechMs", speechMs)
            voice.putIfAbsent("endReason", endReason); voice.putIfAbsent("vad", vadName)
            voice.putIfAbsent("hangoverMs", strat?.hangoverMs ?: VadStrategy.NORMAL.hangoverMs)
        }
        Log.i("Tekyerema", "[VOICE] end=$endReason captured ${pcm.size}B PCM (~${speechMs}ms speech) " +
            "preRms=${"%.0f".format(preRms)} postRms=${"%.0f".format(pcmRms(pcm))} tookMs=${android.os.SystemClock.uptimeMillis() - captureStart} ${stats()}")
        if (pcm.isEmpty()) return@withContext CaptureResult.NoSpeech
        CaptureResult.Success(Wav.wrap(pcm, sampleRate), speechMs)
    }

    private fun appendPcm(out: ByteArrayOutputStream, samples: ShortArray) {
        for (s in samples) { out.write(s.toInt() and 0xFF); out.write((s.toInt() shr 8) and 0xFF) }
    }

    /**
     * §P0 offline validation: run the SAME segmenter (Silero if loaded, else energy) over a WAV's frames —
     * no microphone — and report what it decides. Lets us prove on the connected phone that real-speech audio
     * produces SPEECH_END rather than NO_SPEECH, without a human speaking. `[measured]` on device.
     */
    fun probe(samples: ShortArray): String {
        segReset()
        var frames = 0; var maxRms = 0.0; var sumRms = 0.0; var started = false; var speechFrames = 0; var end = "NONE"
        val guardFrames = (vadGuardMs / (frame * 1000.0 / sampleRate)).toInt()
        var i = 0
        loop@ while (i < samples.size) {
            val len = minOf(frame, samples.size - i)
            val f = samples.copyOfRange(i, i + len); i += len
            val rms = EnergyVad.rms(f); frames++; sumRms += rms; if (rms > maxRms) maxRms = rms
            if (frames <= guardFrames) continue
            when (segProcess(f)) {
                EnergyVad.Event.SPEECH_START -> { started = true; speechFrames++ }
                EnergyVad.Event.SPEECH -> speechFrames++
                EnergyVad.Event.SPEECH_END -> { end = "SPEECH_END"; break@loop }
                EnergyVad.Event.TIMEOUT -> { end = "TIMEOUT"; break@loop }
                EnergyVad.Event.TOO_SHORT -> { end = "TOO_SHORT"; break@loop }
                EnergyVad.Event.SILENCE -> {}
            }
        }
        if (end == "NONE") end = if (started) "EOF_WHILE_SPEAKING" else "NO_SPEECH"
        return "vad=$vadName end=$end onset=$started speechFrames=$speechFrames frames=$frames " +
            "maxRms=${"%.0f".format(maxRms)} avgRms=${"%.0f".format(if (frames == 0) 0.0 else sumRms / frames)}"
    }

    /** Readable name for an AudioSource int (§3.8). */
    private fun sourceName(src: Int): String = when (src) {
        MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
        MediaRecorder.AudioSource.MIC -> "MIC"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        else -> "src$src"
    }

    /** RMS of a PCM16 little-endian byte buffer (0..32767 scale). */
    private fun pcmRms(pcm: ByteArray): Double {
        if (pcm.size < 2) return 0.0
        var sum = 0.0; var n = 0; var i = 0
        while (i + 1 < pcm.size) {
            var s = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            if (s > 32767) s -= 65536
            sum += s.toDouble() * s; n++; i += 2
        }
        return if (n == 0) 0.0 else kotlin.math.sqrt(sum / n)
    }

    /**
     * §3.7 — RMS-based normalization with a peak CEILING. Pure peak-normalization let a single click/plosive
     * (which sets the peak) leave the actual speech quiet. Instead scale to a target RMS (loudness the model
     * trained near), but clamp the gain so the loudest sample never exceeds [peakCeiling] — so we get
     * consistent loudness without clipping transients.
     */
    private fun normalizeRms(pcm: ByteArray, targetRms: Int = 3000, peakCeiling: Int = 30000): ByteArray {
        if (pcm.size < 4) return pcm
        val rms = pcmRms(pcm)
        if (rms < 40.0) return pcm                 // essentially silence — don't amplify noise
        var peak = 0; var i = 0
        while (i + 1 < pcm.size) {
            var s = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            if (s > 32767) s -= 65536
            val a = if (s < 0) -s else s; if (a > peak) peak = a
            i += 2
        }
        if (peak == 0) return pcm
        val gain = minOf(targetRms.toDouble() / rms, peakCeiling.toDouble() / peak)
        if (gain <= 1.05) return pcm               // already loud enough — leave it
        i = 0
        while (i + 1 < pcm.size) {
            var s = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            if (s > 32767) s -= 65536
            val g = (s * gain).toInt().coerceIn(-32768, 32767)
            pcm[i] = (g and 0xFF).toByte(); pcm[i + 1] = ((g shr 8) and 0xFF).toByte()
            i += 2
        }
        return pcm
    }
}

/** Minimal WAV (RIFF/PCM16) writer — the format Groq's audio endpoint accepts. */
object Wav {
    fun wrap(pcm: ByteArray, sampleRate: Int, channels: Int = 1, bits: Int = 16): ByteArray {
        val byteRate = sampleRate * channels * bits / 8
        val blockAlign = channels * bits / 8
        val out = ByteArrayOutputStream(44 + pcm.size)
        fun s(x: String) = out.write(x.toByteArray(Charsets.US_ASCII))
        fun i32(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        fun i16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        s("RIFF"); i32(36 + pcm.size); s("WAVE")
        s("fmt "); i32(16); i16(1); i16(channels); i32(sampleRate); i32(byteRate); i16(blockAlign); i16(bits)
        s("data"); i32(pcm.size); out.write(pcm)
        return out.toByteArray()
    }
}
