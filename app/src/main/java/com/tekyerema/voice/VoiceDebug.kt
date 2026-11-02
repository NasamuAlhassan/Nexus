package com.tekyerema.voice

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.tekyerema.agent.AgentEngine
import com.tekyerema.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * DEBUG-only voice test hooks, driven over ADB via RunGoalReceiver — so the ASR→agent wiring and the
 * mic/VAD layers can be validated on the real device WITHOUT a human speaking (which the fully-live
 * loop still needs). Logs to `Tekyerema` and (for transcription) feeds the canonical goal to the
 * SAME AgentEngine, i.e. the real pipeline minus the live microphone.
 */
object VoiceDebug {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun hasMic(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * `--es transcribe_wav <name.wav> [--es lang gh_en|twi|auto]` — reads the WAV from the app's
     * external files dir (adb push to /sdcard/Android/data/com.tekyerema/files/<name.wav>), runs the
     * on-device ASR, logs raw/canonical/entities, then submits the canonical goal to the agent.
     */
    fun transcribeFileAndRun(ctx: Context, name: String, lang: String?) {
        val settings = Settings(ctx)
        val file = File(ctx.getExternalFilesDir(null), name)
        if (!file.exists()) { Log.w("Tekyerema", "[VOICE-DEBUG] no such file: ${file.absolutePath}"); return }
        // Mirror the production ASR: on-device sherpa-onnx first (offline), online proxy fallback.
        val asr = LocalAsrProvider(SherpaAsr(ctx), sessionLanguage = { settings.sessionLanguage })
        scope.launch {
            try {
                val wav = file.readBytes()
                val force = lang?.takeIf { it == Lang.GH_EN || it == Lang.TWI }
                val tr = asr.transcribe(wav, settings.sessionLanguage, force)
                Log.i("Tekyerema", "[VOICE-DEBUG] file=$name raw='${tr.raw}' canonical='${tr.canonical}' " +
                    "lang=${tr.language} reason='${tr.reason}' entities=${tr.entities} backend='${tr.backend}' ${tr.asrMs}ms")
                if (tr.canonical.isNotBlank()) {
                    settings.sessionLanguage = tr.language
                    AgentEngine.submitGoal(tr.canonical)
                    Log.i("Tekyerema", "[VOICE-DEBUG] submitted canonical goal to agent: '${tr.canonical}'")
                } else Log.w("Tekyerema", "[VOICE-DEBUG] empty canonical; not submitting")
            } catch (e: Exception) {
                Log.w("Tekyerema", "[VOICE-DEBUG] transcribe failed: ${e.message}")
            }
        }
    }

    /**
     * `--es asr_diag <name.wav> [--es lang gh_en|twi]` — Phase 5 per-stage ASR diagnostic. Runs the WAV
     * through the SAME on-device path and logs model/language/durations + the sw-PROXY output (pre-depua),
     * RAW (post-depua), NORMALIZED and FINAL transcripts, so a device run can be diffed against the clean
     * reference. Does NOT submit a goal to the agent (pure ASR — no side effects). DEBUG-only.
     */
    /**
     * `--es online_asr <name.wav> [--es lang twi]` — the WAV through the ONLINE recogniser the app would use for that language
     * (the Space for Twi: the upload codec, sizes and timings are logged by HttpAsrProvider). No goal is submitted. DEBUG-only.
     */
    fun onlineAsr(ctx: Context, name: String, lang: String?) {
        val file = File(ctx.getExternalFilesDir(null), name)
        if (!file.exists()) { Log.w("Tekyerema", "[ONLINE-ASR] no such file: ${file.absolutePath}"); return }
        scope.launch {
            val l = lang ?: Lang.TWI
            // Our Space only: a test must never spend the HCI Lab's ~10 requests a day (the fallback chain would).
            val asr = com.tekyerema.language.LanguageLayer.build(ctx.applicationContext).asr[if (l == Lang.TWI) "space_asr" else "space_asr_$l"]
            if (asr == null) { Log.w("Tekyerema", "[ONLINE-ASR] no Space recogniser for $l"); return@launch }
            // The bytes the upload would carry, kept beside the WAV so they can be checked off the phone.
            com.tekyerema.language.SpaceOpus.encode(file.readBytes())?.let { File(file.parentFile, "$name.ogg").writeBytes(it) }
            try {
                val tr = asr.transcribe(file.readBytes(), l, l)
                Log.i("Tekyerema", "[ONLINE-ASR] file=$name via=${asr.name} raw='${tr.raw}' canonical='${tr.canonical}' ${tr.asrMs}ms")
            } catch (e: Exception) {
                Log.w("Tekyerema", "[ONLINE-ASR] failed: ${e.message}")
            }
        }
    }

    fun asrDiag(ctx: Context, name: String, lang: String?) {
        val settings = Settings(ctx)
        val file = File(ctx.getExternalFilesDir(null), name)
        if (!file.exists()) { Log.w("Tekyerema", "[ASR-DIAG] no such file: ${file.absolutePath}"); return }
        val sherpa = SherpaAsr(ctx)
        val force = lang?.takeIf { it == Lang.GH_EN || it == Lang.TWI }
        scope.launch {
            val d = sherpa.diagnose(file.readBytes(), force)
            if (d == null) { Log.w("Tekyerema", "[ASR-DIAG] no result — the model can't run right now or wasn't loaded for lack of memory (model ready=${sherpa.ready})"); return@launch }
            val tr = LanguagePipeline.process(d.raw, settings.sessionLanguage, force, backend = sherpa.name)
            Log.i("Tekyerema", "[ASR-DIAG] ===== $name =====")
            Log.i("Tekyerema", "[ASR-DIAG] MODEL=${d.model} LANG(token)=${d.lang} RESOLVED=${tr.language}")
            Log.i("Tekyerema", "[ASR-DIAG] AUDIO=${d.audioMs}ms SR=${d.sampleRate} PREPROCESS=${d.preprocessMs}ms " +
                "INFERENCE=${d.inferenceMs}ms POST=${d.postMs}ms")
            Log.i("Tekyerema", "[ASR-DIAG] PROXY(pre-depua)='${d.proxyRaw}'")
            Log.i("Tekyerema", "[ASR-DIAG] RAW(post-depua)='${d.raw}'")
            Log.i("Tekyerema", "[ASR-DIAG] NORMALIZED='${tr.canonical}'")
            Log.i("Tekyerema", "[ASR-DIAG] FINAL='${tr.canonical}' ENTITIES=${tr.entities}")
        }
    }

    /**
     * `--es asr_batch <dir> [--es lang gh_en|twi]` — transcribe every WAV in the app's files/<dir>/ with ONE on-device
     * recogniser on the production path (the bundled model, contact/app hotwords, the language pipeline and its name
     * corrector), logging `[ASR-BATCH] <file> ms=… raw='…' final='…'` per clip. Scores a model on the phone itself with
     * the phone's own contacts (2026-09-30 offline English bake-off). Never submits anything to the agent. DEBUG-only.
     */
    fun asrBatch(ctx: Context, dir: String, lang: String?) {
        val settings = Settings(ctx)
        val files = File(ctx.getExternalFilesDir(null), dir).listFiles { f -> f.name.endsWith(".wav") }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) { Log.w("Tekyerema", "[ASR-BATCH] no WAVs in $dir"); return }
        val sherpa = SherpaAsr(ctx).also { it.hotwords = { SpeechVocabulary.current().entries.map { e -> e.text } } }
        val force = lang?.takeIf { it == Lang.GH_EN || it == Lang.TWI } ?: Lang.GH_EN
        scope.launch {
            try { SpeechVocabulary.refresh(ctx) } catch (_: Throwable) {}
            var total = 0L
            for (f in files) {
                val t0 = android.os.SystemClock.uptimeMillis()
                val raw = try { sherpa.rawTranscript(f.readBytes(), force).orEmpty() }
                    catch (e: AsrLowMemoryException) {
                        // The model wasn't loaded (it would have got the app killed): say so and stop, never crash.
                        Log.w("Tekyerema", "[ASR-BATCH] stopped at ${f.name}: ${e.availMb} MB available, ${e.neededMb} MB needed to load the model")
                        return@launch
                    }
                val ms = android.os.SystemClock.uptimeMillis() - t0
                total += ms
                val tr = LanguagePipeline.process(raw, settings.sessionLanguage, force, backend = sherpa.name)
                Log.i("Tekyerema", "[ASR-BATCH] ${f.name} ms=$ms raw='$raw' final='${tr.canonical}'")
            }
            Log.i("Tekyerema", "[ASR-BATCH] done ${files.size} files in ${total} ms, model=${sherpa.name}")
        }
    }

    /**
     * §P0 `--es vad_probe <wav> [--es scale <f>]` — run the REAL segmenter (Silero if loaded, else energy)
     * over a WAV's frames with no microphone, and log the decision (end reason, onset, RMS). `scale` lets us
     * simulate the quiet raw mic from a louder recording (e.g. scale=0.06). Proves the fix on real speech.
     */
    fun vadProbe(ctx: Context, name: String, scaleStr: String?) {
        val file = File(ctx.getExternalFilesDir(null), name)
        if (!file.exists()) { Log.w("Tekyerema", "[VAD-PROBE] no such file: ${file.absolutePath}"); return }
        val scale = scaleStr?.toFloatOrNull() ?: 1f
        val b = file.readBytes()
        // locate the PCM16 'data' chunk (standard RIFF/WAVE)
        var pos = 12; var dataOff = -1; var dataLen = 0
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII)
            val len = (b[pos+4].toInt() and 0xFF) or ((b[pos+5].toInt() and 0xFF) shl 8) or
                ((b[pos+6].toInt() and 0xFF) shl 16) or ((b[pos+7].toInt() and 0xFF) shl 24)
            if (id == "data") { dataOff = pos + 8; dataLen = len; break }
            pos += 8 + len + (len and 1)
        }
        if (dataOff < 0) { Log.w("Tekyerema", "[VAD-PROBE] no data chunk in $name"); return }
        val n = minOf(dataLen, b.size - dataOff) / 2
        val shorts = ShortArray(n) {
            var s = (b[dataOff + 2*it].toInt() and 0xFF) or (b[dataOff + 2*it + 1].toInt() shl 8)
            if (s > 32767) s -= 65536
            (s * scale).toInt().coerceIn(-32768, 32767).toShort()
        }
        val res = MicCapture(appContext = ctx).probe(shorts)
        Log.i("Tekyerema", "[VAD-PROBE] $name scale=$scale samples=$n -> $res")
    }

    /**
     * `--es asr_route [gh_en|twi]` — Phase 8: log the hybrid router's decision WITHOUT recognizing, so the
     * routing policy is observable (§13). Shows effective language, network state, whether an online
     * endpoint is configured, and the resulting ONLINE vs ON-DEVICE choice.
     */
    fun asrRoute(ctx: Context, lang: String?) {
        val s = Settings(ctx)
        val networkOnline = com.tekyerema.core.NetworkMonitor.isOnline()
        val eff = lang?.takeIf { it == Lang.GH_EN || it == Lang.TWI } ?: s.forcedLanguage
        // B-18: the online provider ids configured for this language (providers.speech.asr.online.<lang>).
        val ids = SpeechProviders.onlineAsrIds(eff)
        val useOnline = networkOnline && ids.isNotEmpty() && VoiceEngine.onlineAsrFor(eff) != null
        Log.i("Tekyerema", "[ASR-ROUTE] lang=${eff ?: "auto"} networkOnline=$networkOnline configured=$ids " +
            "-> ${if (useOnline) "ONLINE($ids)" else "ON-DEVICE"}")
    }

    /**
     * `--es set_asr_online <url>|off` — DEBUG: point the hybrid router's online leg at a (placeholder)
     * endpoint so routing/fail-over can be validated on-device; `off` clears it (back to fully offline).
     * Sets a placeholder key so hasOnlineAsr flips true even without a Groq key.
     */
    fun setAsrOnline(ctx: Context, url: String?) {
        val s = Settings(ctx)
        if (url == null || url.equals("off", true) || url.isBlank()) {
            s.asrOnlineBaseUrl = ""; s.asrOnlineKey = ""
            Log.i("Tekyerema", "[ASR-ROUTE] online endpoint CLEARED — router is now fully offline")
        } else {
            s.asrOnlineBaseUrl = url
            if (s.asrOnlineKey.isBlank()) s.asrOnlineKey = "debug-placeholder-key"
            Log.i("Tekyerema", "[ASR-ROUTE] online endpoint set url='$url' hasOnlineAsr=${s.hasOnlineAsr}")
        }
    }

    /**
     * `--es set_brain <url>|off [--es model <m>]` — repoint the cloud agent brain at any OpenAI-compatible
     * `/chat/completions` server WITHOUT a rebuild — e.g. the brain-relay so Claude answers each step
     * (`--es set_brain http://<host>:877/v1`). `off` restores the build default (real Groq). The existing
     * Groq key is still sent as the Bearer token (the relay ignores it); `hasGroqKey` stays true so the cloud
     * brain is selected. LocalBrain still handles simple commands on-device; the relay gets escalated steps.
     */
    fun setBrain(ctx: Context, url: String?, model: String?) {
        val s = Settings(ctx)
        if (url == null || url.equals("off", true) || url.isBlank()) {
            s.groqBaseUrl = ""; s.brainCloudFirst = false
            Log.i("Tekyerema", "[BRAIN] cloud base URL reset → ${s.groqBaseUrl}; cloud-first OFF")
        } else {
            s.groqBaseUrl = url
            if (!model.isNullOrBlank()) s.groqModel = model
            s.brainCloudFirst = true   // point at the relay → route EVERY request to it (Claude as the brain)
            Log.i("Tekyerema", "[BRAIN] cloud brain → base=${s.groqBaseUrl} model=${s.groqModel} cloudFirst=true hasGroqKey=${s.hasGroqKey}")
        }
    }

    /** `--es cloud_first 1|0` — route every request to the cloud brain (skip on-device LocalBrain), without
     *  changing the URL. Lets us test cloud-first against the real Groq brain before the relay is up. */
    fun setCloudFirst(ctx: Context, on: Boolean) {
        Settings(ctx).brainCloudFirst = on
        Log.i("Tekyerema", "[BRAIN] cloud-first = $on (every request → cloud brain)")
    }

    /** `--es list_tts_voices 1` — enumerate the device's TTS voices; definitive Ghanaian/African-English verdict (§B.4). */
    fun listTtsVoices(ctx: Context) {
        val tts = AndroidTts(ctx)
        scope.launch { try { tts.logVoices() } finally { tts.shutdown() } }
    }

    /** `--es tts_test "<text>" [--es lang gh_en|twi]` — speaks via the phone's system TTS engine (the Abena/
     *  Khaya cloud voices were removed 2026-09-27; the UG HCI Lab TTS API drops into RoutingTts later). */
    fun ttsTest(ctx: Context, text: String, lang: String?, save: Boolean = false) {
        // A language with its own on-device voice (`--es lang kus`): spoken the way a reply is (VoiceEngine → RoutingTts →
        // the bundled voice), and with `--es tts_save 1` also written to files/tts_test_<lang>.wav for evidence.
        val own = lang?.lowercase()?.takeIf { it != Lang.TWI && it != Lang.GH_EN }
        if (own != null) {
            scope.launch {
                val t0 = android.os.SystemClock.uptimeMillis()
                val spoke = try { VoiceEngine.speakIn(text, own) }
                    catch (e: Exception) { Log.w("Tekyerema", "[VOICE-DEBUG] tts error: ${e.message}"); false }
                Log.i("Tekyerema", "[VOICE-DEBUG] tts_test spoke=$spoke lang=$own via=${VoiceEngine.onDeviceVoice(own)?.name ?: "no on-device voice"} " +
                    "in ${android.os.SystemClock.uptimeMillis() - t0} ms (${text.length} chars)")
                if (save) {
                    val out = File(ctx.getExternalFilesDir(null), "tts_test_$own.wav")
                    val ok = VoiceEngine.onDeviceVoice(own)?.renderWav(text, own, out) ?: false
                    Log.i("Tekyerema", "[VOICE-DEBUG] tts_test saved=$ok ${if (ok) "${out.length()} B → ${out.absolutePath}" else ""}")
                }
            }
            return
        }
        val tts = AndroidTts(ctx)
        val language = lang?.takeIf { it == Lang.TWI } ?: Lang.GH_EN
        scope.launch {
            val spoke = try { tts.speak(text, language) }
            catch (e: Exception) { Log.w("Tekyerema", "[VOICE-DEBUG] tts error: ${e.message}"); false }
            Log.i("Tekyerema", "[VOICE-DEBUG] tts_test spoke=$spoke text='$text' lang=$language")
            tts.shutdown()
        }
    }

    /** `--es mic_test 1` — opens the real mic, runs VAD, logs the CaptureResult. Proves MIC+VAD on hardware. */
    fun micTest(ctx: Context) {
        if (!hasMic(ctx)) { Log.w("Tekyerema", "[VOICE-DEBUG] RECORD_AUDIO not granted"); return }
        scope.launch {
            val r = MicCapture(appContext = ctx).capture(   // §P0 use app context so Silero VAD loads (as production)
                onListening = { Log.i("Tekyerema", "[VOICE-DEBUG] mic: listening…") },
                onSpeechStart = { Log.i("Tekyerema", "[VOICE-DEBUG] mic: speech detected") }
            )
            val desc = when (r) {
                is CaptureResult.Success -> {
                    // Save the captured mic WAV so it can be re-run through `asr_diag` (Phase 5 live-mic
                    // vs clean-clip comparison) — the ONLY variable the WAV-replay path can't reach.
                    val out = File(ctx.getExternalFilesDir(null), "mic_last.wav")
                    try { out.writeBytes(r.wav); "SUCCESS ${r.wav.size}B WAV, ~${r.speechMs}ms speech → saved ${out.name} (asr_diag mic_last.wav)" }
                    catch (e: Exception) { "SUCCESS ${r.wav.size}B WAV, ~${r.speechMs}ms speech (save failed: ${e.message})" }
                }
                CaptureResult.NoSpeech -> "NO_SPEECH (mic opened, VAD heard only silence)"
                CaptureResult.TooShort -> "TOO_SHORT (blip rejected)"
                CaptureResult.Cancelled -> "CANCELLED"
                is CaptureResult.Error -> "ERROR ${r.message}"
            }
            Log.i("Tekyerema", "[VOICE-DEBUG] mic_test result: $desc")
        }
    }
}
