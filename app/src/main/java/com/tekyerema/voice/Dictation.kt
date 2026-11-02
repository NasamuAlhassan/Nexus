package com.tekyerema.voice

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * §3(a) docs/agent/research/interaction_details.md — the Twi keyboard's OWN dictation. The mic key dictates
 * through Tɛkyerɛma's ASR stack instead of the phone's SpeechRecognizer (broken offline on this Android 11
 * device, and it has no Twi). The IME runs in the app's own process (manifest, G-05), so this is a thin
 * facade over the voice singletons — no second model copy, no second microphone fight:
 *
 *   refuse while a voice turn runs or a secure step is active → [WakeWordService.handOver] (the same
 *   hand-over a voice turn's recognizer uses, so the wake word and dictation never contend for the mic) →
 *   a dedicated [MicCapture] (the VAD ends the turn when the user pauses, Gboard-style) → the SAME
 *   [HybridAsr] route a voice turn uses (online recogniser when configured and connected; the on-device
 *   models otherwise) → the raw transcript back to the keyboard's existing commit path.
 *
 * PRIVACY (J-15 + the ime/ no-content rule): nothing dictated is ever logged from here — stages and lengths
 * only, guarded by a test. The stack below keeps its app-wide content-log gate (core/Privacy; release: length only).
 */
object Dictation {
    private const val TAG = "Tekyerema"

    /** The short honest line when nothing usable was heard. */
    const val DIDNT_CATCH = "I didn't catch that. Tap the microphone and try again."

    /** Callbacks for the keyboard, mirroring the [com.tekyerema.ime.VoiceTyping.Listener] seam. Main thread. */
    interface Listener {
        /** The microphone is really open (wake word handed over, recorder started). */
        fun onListening()
        /** The final transcript — insert it and forget it; it is never logged. */
        fun onText(text: String)
        /** Nothing usable: an honest [message]. [fallbackToSystem] = our stack refused before the microphone
         *  ever opened, so the keyboard may hand this attempt to the phone's SpeechRecognizer instead. */
        fun onNothing(message: String, fallbackToSystem: Boolean)
        /** Always last, even after [cancel]: return the mic key to idle. */
        fun onDone()
    }

    /** What one dictation produced. */
    sealed class Outcome {
        data class Text(val text: String) : Outcome()
        object Cancelled : Outcome()
        /** Nothing usable; see [Listener.onNothing] for the meaning of [fallbackToSystem]. */
        data class None(val message: String, val fallbackToSystem: Boolean = false) : Outcome()
    }

    /** Why dictation can't start right now, or null when it can. Pure — unit-tested (DictationTest). */
    internal fun refusal(engineRunning: Boolean, secure: Boolean, ready: Boolean): Outcome.None? = when {
        // Never speak a secret into a secure step — and never route that audio to any other recognizer either.
        secure -> Outcome.None("Voice typing is off during a secure step. Type it with the keys.")
        // A voice turn holds a microphone already; a second recorder would be silenced or silence it.
        engineRunning -> Outcome.None("Nexus is already listening. Finish that conversation first.")
        // The captured stack isn't built or can't serve this language: the keyboard's old route may still work.
        !ready -> Outcome.None("own dictation isn't ready", fallbackToSystem = true)
        else -> null
    }

    /** Our own stack can dictate in this language right now (models present, or an online recogniser reachable). */
    fun available(twi: Boolean): Boolean = try { VoiceEngine.dictationReady(twi) } catch (_: Throwable) { false }

    /**
     * One dictation with every dependency injected — the testable core. [handOver]/[reclaim] bracket the
     * capture exactly (reclaim runs even on failure or cancellation) so the wake word always gets the mic back.
     */
    internal suspend fun run(
        twi: Boolean,
        engineRunning: Boolean,
        secure: Boolean,
        mic: Recorder?,
        asr: AsrProvider?,
        handOver: suspend () -> Unit,
        reclaim: () -> Unit,
        onListening: () -> Unit
    ): Outcome {
        refusal(engineRunning, secure, mic != null && asr != null)?.let { return it }
        handOver()                                    // the wake word lets go of the microphone first
        try {
            return when (val captured = mic!!.capture(onListening = onListening)) {
                is CaptureResult.Success -> {
                    val lang = if (twi) Lang.TWI else Lang.GH_EN   // the keyboard's language, not LID
                    val t = try { asr!!.transcribe(captured.wav, sessionLanguage = lang, forceLanguage = lang) }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) {
                            Log.i(TAG, "[DICTATE] asr failed after ~${captured.speechMs}ms speech: ${e.javaClass.simpleName}")
                            // The user already spoke: surface the stage's honest line, never a silent re-listen.
                            return Outcome.None(e.message?.takeIf { it.isNotBlank() } ?: DIDNT_CATCH)
                        }
                    val out = t.raw.trim()
                    Log.i(TAG, "[DICTATE] ok (${out.length} chars from ~${captured.speechMs}ms speech)")   // length only, never the words
                    if (out.isBlank()) Outcome.None(DIDNT_CATCH) else Outcome.Text(out)
                }
                CaptureResult.NoSpeech, CaptureResult.TooShort -> Outcome.None(DIDNT_CATCH)
                CaptureResult.Cancelled -> Outcome.Cancelled
                is CaptureResult.Error -> Outcome.None("The microphone isn't free right now. Try again in a moment.")
            }
        } finally { reclaim() }
    }

    // --------------------------------------------------------------------- the keyboard-facing wrapper

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var job: Job? = null
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** A dictation is in flight (listening or transcribing). */
    val active: Boolean get() = job?.isActive == true

    /** Start one dictation; callbacks arrive on the main thread. False when one is already running. */
    fun start(twi: Boolean, listener: Listener): Boolean {
        if (active) return false
        job = scope.launch {
            var outcome: Outcome = Outcome.Cancelled
            try {
                outcome = run(
                    twi = twi,
                    engineRunning = try { VoiceEngine.isRunning } catch (_: Throwable) { false },   // lateinit-safe before init
                    secure = SecureStep.active() || com.tekyerema.core.Privacy.secureStep,
                    mic = VoiceEngine.dictationMic,
                    asr = VoiceEngine.dictationAsr,
                    handOver = { WakeWordService.handOver() },
                    reclaim = { WakeWordService.reclaim() },
                    onListening = { main.post { listener.onListening() } }
                )
            } finally {
                val o = outcome
                main.post {
                    when (o) {
                        is Outcome.Text -> listener.onText(o.text)
                        is Outcome.None -> listener.onNothing(o.message, o.fallbackToSystem)
                        Outcome.Cancelled -> {}
                    }
                    listener.onDone()
                }
            }
        }
        return true
    }

    /** The keyboard is closing, the field changed, or the user tapped the mic again: drop everything. */
    fun cancel() { job?.cancel() }
}
