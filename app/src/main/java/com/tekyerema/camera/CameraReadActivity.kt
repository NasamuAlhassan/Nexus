package com.tekyerema.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tekyerema.BuildConfig
import com.tekyerema.core.NetworkMonitor
import com.tekyerema.core.config.AppConfig
import com.tekyerema.settings.Settings
import com.tekyerema.voice.AudioFocus
import com.tekyerema.voice.ScreenReader
import com.tekyerema.voice.Verbalize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The phone's eyes, spoken (catalog P-03 read text, P-06 colour and light, P-01 describe with the P-09 quality gate).
 *
 * A full-screen camera the voice agent opens for "read this", "what colour is this", "what's in front of me". Every
 * result is SPOKEN — the user may not see the screen at all — through the activity's own TextToSpeech, so it works
 * whether or not the agent's voice session is alive. The preview and a large caption are there for low-vision users.
 *
 *  - Text: on-device ML Kit OCR on ~2 frames a second; [TextStabilizer] speaks only new text that held still for two
 *    frames. Nothing leaves the phone.
 *  - Colour: the centre of the frame averaged to one colour name plus a light level; spoken only when it changes.
 *  - Describe: waits for a frame the [QualityGate] passes (coaching the user until then), then sends that ONE frame
 *    to Gemini. Only when the user allows the Gemini backup; offline or keyless it says so and reads text instead.
 *
 * Leaving is one gesture: a tap anywhere (double-tap with TalkBack) or Back closes it, and it closes itself after
 * `camera.sessionTimeoutMs` with nothing new to say — a forgotten camera must not keep running in a pocket.
 */
class CameraReadActivity : ComponentActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_TEXT = "text"
        const val MODE_COLOR = "color"
        const val MODE_DESCRIBE = "describe"

        private const val TAG = "Tekyerema"
        /** Finish even if the TTS engine never reports the last line done (engine died, no voice). */
        private const val FINISH_FALLBACK_MS = 10_000L
        /** A11Y: TalkBack announces the new window first; start speaking after it so the two voices don't overlap. */
        private const val SCREEN_READER_DELAY_MS = 1_200L

        /** Launch from anywhere (a service, the accessibility layer): its own task, see the manifest. */
        fun intent(ctx: Context, mode: String): Intent =
            Intent(ctx, CameraReadActivity::class.java)
                .putExtra(EXTRA_MODE, mode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private class Speech(val line: String, val flush: Boolean, val then: (() -> Unit)?)

    private lateinit var settings: Settings
    private lateinit var previewView: PreviewView
    private lateinit var caption: TextView

    /** What the analyser does now. Describe can fall back to text mid-session, so this is not final. */
    @Volatile private var mode = MODE_TEXT
    /** Set once we are leaving: the analyser drops frames and no new result is spoken. */
    @Volatile private var closing = false

    private val main = Handler(Looper.getMainLooper())

    // --- speech (main thread only) ---
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsFailed = false
    private val pending = ArrayList<Speech>()
    private val afterSpeech = HashMap<String, () -> Unit>()
    private var utteranceSeq = 0

    // --- camera ---
    private var analysis: ImageAnalysis? = null
    private var analysisExecutor: ExecutorService? = null
    private var cameraErrorSpoken = false
    private var intervalMs = 500L
    @Volatile private var lastAnalysedAt = 0L
    // Reusable plane copies — touched only on the analyser thread.
    private var yBuf: ByteArray? = null
    private var uBuf: ByteArray? = null
    private var vBuf: ByteArray? = null

    // --- text (P-03), main thread ---
    private val recognizerLazy = lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val recognizer: TextRecognizer by recognizerLazy
    private val stabilizer = TextStabilizer()
    private var spokeResult = false

    // --- colour (P-06), main thread ---
    private val colourAnnouncer by lazy { ChangeAnnouncer(AppConfig.long("camera.color.minIntervalMs", 2_000L)) }

    // --- describe (P-01 / P-09) ---
    private val describeClaimed = AtomicBoolean(false)
    @Volatile private var describeStartedAt = 0L
    private var lastVerdict: Verdict? = null
    private var lastGuidanceAt = 0L

    private val idleTimeout = Runnable { close() }
    private val noTextHint = Runnable {
        if (!closing && mode == MODE_TEXT && !spokeResult && !isSpeaking()) {
            speak(phrase("noTextYet", "No text yet. Move the phone slowly."))
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin() else speakThenFinish(phrase("permissionDenied", "I need camera permission to see for you."))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        settings = Settings(this)
        mode = when (intent?.getStringExtra(EXTRA_MODE)) {
            MODE_COLOR -> MODE_COLOR
            MODE_DESCRIBE -> MODE_DESCRIBE
            else -> MODE_TEXT
        }
        intervalMs = AppConfig.long("camera.analysis.intervalMs", 500L).coerceAtLeast(100L)
        setContentView(buildLayout())
        tts = TextToSpeech(applicationContext) { status -> runOnUiThread { onTtsInit(status) } }

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) begin() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        closing = true
        main.removeCallbacksAndMessages(null)
        pending.clear()
        afterSpeech.clear()
        // Use cases are unbound by the lifecycle (ON_DESTROY runs before this); make sure no frame is still routed here.
        try { analysis?.clearAnalyzer() } catch (_: Throwable) {}
        analysisExecutor?.shutdown()
        if (recognizerLazy.isInitialized()) try { recognizer.close() } catch (_: Throwable) {}
        tts?.let { try { it.stop(); it.shutdown() } catch (_: Throwable) {} }
        tts = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------------------------------------ session

    /** Camera permission is granted: settle the mode, say what we're doing, open the camera. */
    private fun begin() {
        if (closing) return
        if (mode == MODE_DESCRIBE) {
            when {
                // J-18: the user switched the Gemini backup off — a photo must not leave the phone behind their back.
                !settings.allowGeminiBackup -> {
                    speakThenFinish(phrase("describeDisabled",
                        "Describing photos needs the Gemini backup, which is switched off in Settings."))
                    return
                }
                settings.geminiApiKey.isBlank() -> {
                    mode = MODE_TEXT
                    announce(phrase("describeNoKey", "Describing needs a Gemini key, which isn't set up. I can read any text I see instead."))
                }
                !NetworkMonitor.isOnline() -> {
                    mode = MODE_TEXT
                    announce(phrase("describeOffline", "I can't describe it offline, but I can read any text I see."))
                }
                else -> announce(phrase("modeDescribe", "Hold still, I'll describe what's in front of you."))
            }
        } else if (mode == MODE_COLOR) {
            announce(phrase("modeColor", "Checking colour and light."))
        } else {
            announce(phrase("modeText", "Reading text. Point the camera at it."))
        }
        describeStartedAt = SystemClock.elapsedRealtime()
        if (mode == MODE_TEXT) scheduleNoTextHint()
        resetIdleTimer()
        startCamera()
    }

    /** A tap, the idle timeout, or a finished final line. Says nothing extra on the way out. */
    private fun close() {
        closing = true
        finish()
    }

    private fun resetIdleTimer() {
        main.removeCallbacks(idleTimeout)
        main.postDelayed(idleTimeout, AppConfig.long("camera.sessionTimeoutMs", 90_000L))
    }

    /** One honest cue if nothing readable shows up — silence is ambiguous to someone who can't see the screen. */
    private fun scheduleNoTextHint() {
        main.removeCallbacks(noTextHint)
        val ms = AppConfig.long("camera.text.noTextHintMs", 10_000L)
        if (ms > 0) main.postDelayed(noTextHint, ms)
    }

    // ------------------------------------------------------------------------------------------------ camera

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (closing || isFinishing || isDestroyed) return@addListener
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                // Sizes are in the sensor's (landscape) orientation. Text/colour stay small for speed on a 2.8 GB phone;
                // describe asks for more pixels because that one frame is all the model gets to read labels from.
                val target = if (mode == MODE_DESCRIBE)
                    Size(AppConfig.int("camera.describe.width", 1280), AppConfig.int("camera.describe.height", 960))
                else
                    Size(AppConfig.int("camera.analysis.width", 640), AppConfig.int("camera.analysis.height", 480))
                val selector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(target, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()
                val useCase = ImageAnalysis.Builder()
                    .setResolutionSelector(selector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                val executor = Executors.newSingleThreadExecutor()
                analysisExecutor = executor
                useCase.setAnalyzer(executor) { image -> analyze(image) }
                analysis = useCase

                val camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, useCase)
                // Opening can fail later (another app holds the camera, policy) — say so instead of a silent black screen.
                camera.cameraInfo.cameraState.observe(this) { state ->
                    val error = state.error ?: return@observe
                    if (cameraErrorSpoken || closing) return@observe
                    cameraErrorSpoken = true
                    Log.w(TAG, "[CAMERA] camera state error code=${error.code} type=${error.type}")
                    if (error.type == CameraState.ErrorType.CRITICAL) {
                        speakThenFinish(phrase("cameraFailed", "I couldn't open the camera."))
                    } else {
                        speak(phrase("cameraBusy", "The camera is busy. Close any other camera app and try again."))
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "[CAMERA] could not start: ${t.javaClass.simpleName}: ${t.message}")
                speakThenFinish(phrase("cameraFailed", "I couldn't open the camera."))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Analyser thread. At most one frame per [intervalMs]; every path closes the ImageProxy exactly once. */
    private fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (closing || now - lastAnalysedAt < intervalMs) { image.close(); return }
        lastAnalysedAt = now
        when (mode) {
            MODE_TEXT -> readText(image)   // closes the image when OCR completes
            MODE_COLOR -> try { checkColour(image) } catch (t: Throwable) { logFrameError(t) } finally { image.close() }
            else -> try { gateForDescribe(image) } catch (t: Throwable) { logFrameError(t) } finally { image.close() }
        }
    }

    private fun logFrameError(t: Throwable) {
        if (BuildConfig.DEBUG) Log.d(TAG, "[CAMERA] frame skipped: ${t.javaClass.simpleName}: ${t.message}")
    }

    // ------------------------------------------------------------------------------------------------ P-03 text

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun readText(image: ImageProxy) {
        val media = image.image
        if (media == null) { image.close(); return }
        try {
            val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
            // ML Kit reads the frame in place, so it stays open until OCR finishes; listeners run on the main thread.
            recognizer.process(input)
                .addOnSuccessListener { text -> onText(text) }
                .addOnCompleteListener { image.close() }
        } catch (t: Throwable) {
            logFrameError(t)
            image.close()
        }
    }

    /** Main thread. Blocks in reading order (top to bottom), lines of a block joined into one sentence. */
    private fun onText(result: Text) {
        if (closing || mode != MODE_TEXT) return
        val raw = result.textBlocks
            .sortedWith(compareBy<Text.TextBlock>({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
            .joinToString("\n") { block -> block.lines.joinToString(" ") { it.text } }
        val say = stabilizer.offer(raw) ?: return
        deliver(say)
    }

    // ------------------------------------------------------------------------------------------------ P-06 colour

    /** Analyser thread: centre colour + whole-frame light, posted to the main thread for the change gate. */
    private fun checkColour(image: ImageProxy) {
        val w = image.width
        val h = image.height
        val yP = image.planes[0]
        val uP = image.planes[1]
        val vP = image.planes[2]
        val y = FrameConvert.planeBytes(yP, yBuf).also { yBuf = it }
        val u = FrameConvert.planeBytes(uP, uBuf).also { uBuf = it }
        val v = FrameConvert.planeBytes(vP, vBuf).also { vBuf = it }

        val box = FrameStats.centreBox(w, h, AppConfig.double("camera.color.centreFraction", 0.2))
        val yMean = FrameStats.mean(y, yP.rowStride, yP.pixelStride, box[0], box[1], box[2], box[3])
        // Chroma is subsampled 2×2, so the same box in chroma coordinates is half size.
        val uMean = FrameStats.mean(u, uP.rowStride, uP.pixelStride, box[0] / 2, box[1] / 2, box[2] / 2, box[3] / 2)
        val vMean = FrameStats.mean(v, vP.rowStride, vP.pixelStride, box[0] / 2, box[1] / 2, box[2] / 2, box[3] / 2)
        val rgb = YuvColor.toRgb(yMean, uMean, vMean)
        // Light is about the room, not the object: use the whole frame, sparsely sampled.
        val frameLuma = FrameStats.mean(y, yP.rowStride, yP.pixelStride, 0, 0, w, h, step = 8)
        val label = ColorNames.name(rgb[0], rgb[1], rgb[2]) + ", " + LightLevel.describe(frameLuma)
        main.post { onColour(label) }
    }

    private fun onColour(label: String) {
        if (closing || mode != MODE_COLOR) return
        colourAnnouncer.offer(label, SystemClock.elapsedRealtime())?.let { deliver(it) }
    }

    // ------------------------------------------------------------------------------------------------ P-01 / P-09 describe

    /** Analyser thread: grade the frame; the first good one (claimed once) is copied and sent. */
    private fun gateForDescribe(image: ImageProxy) {
        if (describeClaimed.get()) return
        val yP = image.planes[0]
        val luma = FrameConvert.planeBytes(yP, yBuf).also { yBuf = it }
        val q = QualityGate.assess(
            luma, image.width, image.height, yP.rowStride,
            darkBelow = AppConfig.double("camera.quality.darkBelow", QualityGate.DARK_BELOW),
            brightAbove = AppConfig.double("camera.quality.brightAbove", QualityGate.BRIGHT_ABOVE),
            blurMin = AppConfig.double("camera.quality.blurMin", QualityGate.BLUR_MIN)
        )
        // A plain surface (a wall, a blank page) never scores "sharp". After a while, a well-exposed frame is sent
        // anyway rather than coaching "hold still" for the rest of the session. Bad light is never sent.
        val waited = SystemClock.elapsedRealtime() - describeStartedAt
        val bestEffort = q.verdict == Verdict.BLURRY && waited >= AppConfig.long("camera.describe.bestEffortAfterMs", 10_000L)
        if (q.verdict == Verdict.OK || bestEffort) {
            if (!describeClaimed.compareAndSet(false, true)) return
            val nv21 = FrameConvert.toNv21(image)
            val w = image.width
            val h = image.height
            val rotation = image.imageInfo.rotationDegrees
            if (BuildConfig.DEBUG) Log.d(TAG, "[CAMERA] frame accepted blur=${q.blurScore.toInt()} luma=${q.meanLuma.toInt()} bestEffort=$bestEffort")
            main.post { sendForDescription(nv21, w, h, rotation) }
        } else {
            val verdict = q.verdict
            main.post { onGuidance(verdict) }
        }
    }

    /** Main thread. Coach only on a verdict seen twice in a row, never over other speech, at most every few seconds. */
    private fun onGuidance(verdict: Verdict) {
        if (closing || mode != MODE_DESCRIBE || describeClaimed.get()) return
        val stable = verdict == lastVerdict
        lastVerdict = verdict
        val now = SystemClock.elapsedRealtime()
        if (!stable || isSpeaking() || now - lastGuidanceAt < AppConfig.long("camera.describe.guidanceIntervalMs", 3_000L)) return
        lastGuidanceAt = now
        val line = QualityGate.guidance(verdict)
        if (line.isNotEmpty()) speak(line)
    }

    private fun sendForDescription(nv21: ByteArray, width: Int, height: Int, rotation: Int) {
        if (closing) return
        // The request takes a few seconds: say we have the picture so the user can stop aiming.
        speak(phrase("describing", "Got it, one moment."), flush = false)
        lifecycleScope.launch {
            val jpeg = withContext(Dispatchers.Default) {
                FrameConvert.nv21ToJpeg(nv21, width, height, rotation, AppConfig.int("camera.describe.maxSide", 1024), 80)
            }
            val result = if (jpeg == null) VisionResult.Failed("encode")
            else GeminiVision.describe(jpeg, settings.geminiApiKey, com.tekyerema.agent.ModelRoles.model("vision", settings.model))   // N-06
            if (closing) return@launch
            when (result) {
                is VisionResult.Described -> deliver(result.text)
                is VisionResult.Failed -> {
                    Log.w(TAG, "[CAMERA] describe failed: ${result.reason}")
                    fallBackToText(phrase("describeFailed", "I couldn't get a description just now. I'll read any text I see instead."))
                }
            }
        }
    }

    /** Describe isn't possible this session: keep the camera useful by reading text, and say so. */
    private fun fallBackToText(line: String) {
        mode = MODE_TEXT
        stabilizer.reset()
        showCaption(line)
        speak(line)
        resetIdleTimer()
        scheduleNoTextHint()
    }

    // ------------------------------------------------------------------------------------------------ output

    /** A new result: show it, say it, and restart the idle clock. */
    private fun deliver(text: String) {
        if (closing) return
        spokeResult = true
        main.removeCallbacks(noTextHint)
        showCaption(text)
        speak(text)
        resetIdleTimer()
    }

    /** The mode line. Queued (never cuts anything off), and after TalkBack's own window announcement. */
    private fun announce(line: String) {
        showCaption(line)
        if (ScreenReader.active(this)) {
            main.postDelayed({ if (!closing) speak(line, flush = false) }, SCREEN_READER_DELAY_MS)
        } else {
            speak(line, flush = false)
        }
    }

    /** Say a last line, then leave (permission refused, describe switched off, camera failure). */
    private fun speakThenFinish(line: String) {
        closing = true
        main.removeCallbacks(idleTimeout)
        main.removeCallbacks(noTextHint)
        showCaption(line)
        speak(line) { finish() }
        main.postDelayed({ finish() }, FINISH_FALLBACK_MS)
    }

    private fun showCaption(text: String) {
        if (::caption.isInitialized) caption.text = text
    }

    private fun phrase(key: String, def: String): String = AppConfig.string("camera.phrases.$key", def)

    // ------------------------------------------------------------------------------------------------ TTS

    /** New content flushes what is being said (a new sign supersedes the old); status lines queue. */
    private fun speak(text: String, flush: Boolean = true, then: (() -> Unit)? = null) {
        utter(Verbalize.apply(text), flush, then)
    }

    private fun utter(line: String, flush: Boolean, then: (() -> Unit)?) {
        val engine = tts
        if (line.isBlank() || ttsFailed || engine == null) { then?.invoke(); return }
        if (!ttsReady) {
            if (flush) pending.clear()
            pending.add(Speech(line, flush, then))
            return
        }
        val id = "cam-" + (++utteranceSeq)
        if (then != null) afterSpeech[id] = then
        val rc = try {
            engine.speak(line, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        } catch (_: Throwable) {
            TextToSpeech.ERROR
        }
        if (rc != TextToSpeech.SUCCESS) afterSpeech.remove(id)?.invoke()
    }

    private fun isSpeaking(): Boolean = try { tts?.isSpeaking == true } catch (_: Throwable) { false }

    private fun onTtsInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "[CAMERA] TTS engine init failed ($status)")
            ttsFailed = true
            val waiting = pending.toList()
            pending.clear()
            waiting.forEach { it.then?.invoke() }
            return
        }
        try {
            // Same voice choices as the agent: en-GH when the engine has it, the user's speaking rate, and the
            // accessibility stream while TalkBack drives the phone.
            val gh = Locale("en", "GH")
            engine.setLanguage(if (engine.isLanguageAvailable(gh) >= TextToSpeech.LANG_AVAILABLE) gh else Locale.ENGLISH)
            engine.setSpeechRate(when (settings.speechRate) { 0 -> 0.8f; 2 -> 1.25f; else -> 1.0f })
            engine.setAudioAttributes(AudioFocus(this).attributes(AudioFocus.Kind.SPEAK))
        } catch (_: Throwable) {}
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finished(utteranceId)
            @Deprecated("deprecated in API 21")
            override fun onError(utteranceId: String?) = finished(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
        })
        ttsReady = true
        val waiting = pending.toList()
        pending.clear()
        waiting.forEach { utter(it.line, it.flush, it.then) }
    }

    /** TTS binder thread → main: run whatever was waiting on this utterance. */
    private fun finished(utteranceId: String?) {
        if (utteranceId == null) return
        main.post { afterSpeech.remove(utteranceId)?.invoke() }
    }

    // ------------------------------------------------------------------------------------------------ layout

    private fun buildLayout(): View {
        val dp = resources.displayMetrics.density
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)   // the theme is translucent; the camera screen should not show the app below
            contentDescription = phrase("contentDescription", "Camera — point it at what you want me to read")
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            isClickable = true
            isFocusable = true
            setOnClickListener { close() }
        }
        // TalkBack says "double-tap to close" rather than the generic "activate".
        ViewCompat.replaceAccessibilityAction(
            root, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK, phrase("closeAction", "close"), null
        )
        previewView = PreviewView(this).apply {
            // TextureView-backed: reliable inside a translucent window, and the preview is secondary here anyway.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        root.addView(previewView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        caption = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setBackgroundColor(0xCC000000.toInt())
            gravity = Gravity.CENTER
            val pad = (16 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            // Already spoken by us; TalkBack reading it again would double every result.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(
            caption,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        )
        return root
    }
}
