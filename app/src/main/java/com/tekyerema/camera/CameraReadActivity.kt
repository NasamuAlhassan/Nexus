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
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
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
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlin.coroutines.resume
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
        const val MODE_CURRENCY = "currency"
        /** Offline "what is this" (WhatIsThis): the thing in front of the camera named on the phone. */
        const val MODE_IDENTIFY = "identify"

        private const val TAG = "Tekyerema"
        /** Finish even if the TTS engine never reports the last line done (engine died, no voice). */
        private const val FINISH_FALLBACK_MS = 10_000L
        /** A11Y: TalkBack announces the new window first; start speaking after it so the two voices don't overlap. */
        private const val SCREEN_READER_DELAY_MS = 1_200L

        /** Launch from anywhere (a service, the accessibility layer): its own task, see the manifest. */
        fun intent(ctx: Context, mode: String, question: String? = null): Intent =
            Intent(ctx, CameraReadActivity::class.java)
                .putExtra(EXTRA_MODE, mode)
                .apply { if (!question.isNullOrBlank()) putExtra(EXTRA_QUESTION, question) }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        /** The user's own question (VisionPrompts): the photo is answered for it instead of a general description. */
        const val EXTRA_QUESTION = "question"

        /** P5-02: a picture the user already has, read (or described) instead of the camera. */
        const val EXTRA_IMAGE = "image"
        /** P5-02: open the phone's photo picker first, then read the chosen picture. */
        const val EXTRA_PICK = "pick"
        fun imageIntent(ctx: Context, image: android.net.Uri, mode: String): Intent =
            intent(ctx, mode).putExtra(EXTRA_IMAGE, image.toString())
        fun pickIntent(ctx: Context, mode: String): Intent = intent(ctx, mode).putExtra(EXTRA_PICK, true)
    }

    private class Speech(val line: String, val flush: Boolean, val then: (() -> Unit)?)

    private lateinit var settings: Settings
    private lateinit var previewView: PreviewView
    /** The floating glass over the picture (CameraSurface): what it's doing, the question, the answer. */
    private val ui = CameraUi()

    /** The user's question for this photo (VisionPrompts), or null for the general description. */
    private var question: String? = null
    /** Counting money with the vision model (several notes, a total) instead of the one-note classifier. */
    private var moneyAsk = false

    /** What the analyser does now. Describe can fall back to text mid-session, so this is not final. */
    @Volatile private var mode = MODE_TEXT
    /** Set once we are leaving: the analyser drops frames and no new result is spoken. */
    @Volatile private var closing = false

    private val main = Handler(Looper.getMainLooper())

    // --- speech (main thread only) ---
    private var tts: TextToSpeech? = null
    /** The app's voice in the user's language (Kafui in English, the Twi voice in Twi); the phone's engine only behind it. */
    private val appVoice by lazy { ReaderVoice(lifecycleScope, main) { line, then -> systemUtter(line, true, then) } }
    private var ttsReady = false
    private var ttsFailed = false
    private val pending = ArrayList<Speech>()
    private val afterSpeech = HashMap<String, () -> Unit>()
    private var utteranceSeq = 0

    // --- camera ---
    private var analysis: ImageAnalysis? = null
    private var analysisExecutor: ExecutorService? = null
    /** The bound camera, kept for its torch control (auto-on when describe frames stay dark). */
    private var camera: androidx.camera.core.Camera? = null
    private var torchOn = false
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
    private val currencyAnnouncer by lazy {
        CurrencyAnnouncer(
            minConfidence = AppConfig.double("camera.currency.minConfidence", 0.85),
            agreeFrames = AppConfig.int("camera.currency.agreeFrames", 2)
        )
    }
    private val itemsAnnouncer by lazy { ItemsAnnouncer(AppConfig.int("camera.currency.agreeFrames", 2)) }
    /** Loaded on the first currency frame (~0.2 s); null when the model isn't bundled (said honestly, once). */
    private var cedi: CediClassifier? = null
    private var cediTried = false
    private var rgbBuf: FloatArray? = null
    private var smallBuf: FloatArray? = null     // the 96 px grid the object is found on
    private var tightBuf: FloatArray? = null     // the object crop the model reads
    private var detector: CediDetector? = null   // finds every note and coin in the frame (cedi_det.onnx), when in the build
    private var detTried = false
    private var detBuf: FloatArray? = null       // the whole frame squeezed to 320 px for the detector
    /** When money was last SEEN but not readable — drives the honest "I can see money but…" coaching. */
    private var moneySeenAt = 0L
    private var whatIs: WhatIsThis? = null
    private var whatIsTried = false
    private var idBuf: FloatArray? = null
    private var idLast: String? = null           // the label the previous frame gave
    private var idSaid: String? = null           // the label last spoken, and when
    private var idSaidAt = 0L
    private var idHintAt = 0L
    private var lastCurrencyHintAt = 0L

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
        // The picture runs under the status and navigation bars; the glass keeps clear of them (CameraSurface).
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        settings = Settings(this)
        mode = when (intent?.getStringExtra(EXTRA_MODE)) {
            MODE_COLOR -> MODE_COLOR
            MODE_DESCRIBE -> MODE_DESCRIBE
            MODE_CURRENCY -> MODE_CURRENCY
            else -> MODE_TEXT
        }
        question = intent?.getStringExtra(EXTRA_QUESTION)?.trim()?.takeIf { it.isNotEmpty() }
        // "How much am I holding": online, the vision model counts every note and the phone adds them up; offline (or with
        // `camera.currency.vision` off) the on-phone classifier names one note at a time.
        if ((mode == MODE_CURRENCY || (mode == MODE_DESCRIBE && VisionPrompts.isMoneyQuestion(question))) &&
            AppConfig.bool("camera.currency.vision", true) && NetworkMonitor.isOnline() &&
            VisionLadder.canDescribe(settings) &&
            // 28 Oct 2026: money is read on the phone whenever the reader is in the build (`camera.currency.offlineFirst`); the cloud
            // counted a held 50 as 200 and is not there when the network is not.
            !(AppConfig.bool("camera.currency.offlineFirst", true) && CediClassifier.installed(this))) {
            mode = MODE_DESCRIBE; moneyAsk = true
        } else if (CediClassifier.useOffline(mode == MODE_DESCRIBE, VisionPrompts.isMoneyQuestion(question),
                NetworkMonitor.isOnline() && VisionLadder.canDescribe(settings) &&
                    !(AppConfig.bool("camera.currency.offlineFirst", true) && CediClassifier.installed(this)), CediClassifier.installed(this))) {
            // A money question with no online vision (25 Oct 2026, A20e: "How much am I holding?" offline said "I can't describe
            // it offline" and read text): the note classifier is on the phone for exactly this.
            mode = MODE_CURRENCY
        }
        intervalMs = AppConfig.long("camera.analysis.intervalMs", 500L).coerceAtLeast(100L)
        ui.question = question
        ui.label = modeLabel()
        setContentView(buildLayout())
        tts = TextToSpeech(applicationContext) { status -> runOnUiThread { onTtsInit(status) } }

        // P5-02: a picture the user already has needs no camera (and no camera permission).
        val imported = intent?.getStringExtra(EXTRA_IMAGE)?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
        if (imported != null) { importing = true; readImage(imported); return }
        if (intent?.getBooleanExtra(EXTRA_PICK, false) == true) {
            importing = true
            announce(phrase("pickPhoto", "Choose the picture you want me to read."))
            resetIdleTimer()
            try {
                pickLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            } catch (t: Throwable) {
                Log.w(TAG, "[IMPORT] photo picker unavailable: ${t.javaClass.simpleName}")
                speakThenFinish(phrase("pickUnavailable", "I couldn't open the photo picker on this phone."))
            }
            return
        }

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) begin() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // ------------------------------------------------------------------------------------------------ P5-02 import

    /** Reading a picture the user already has: the camera is never opened; a tap still closes. */
    @Volatile private var importing = false
    private var imageView: android.widget.ImageView? = null

    private val pickLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) speakThenFinish(phrase("pickCancelled", "Okay, nothing was picked."))
        else readImage(uri)
    }

    /** Decode off the main thread, show it, then read it (or describe it when that mode is possible). */
    private fun readImage(uri: android.net.Uri) {
        resetIdleTimer()
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { ImportedImage.decode(contentResolver, uri, AppConfig.int("camera.import.maxSide", 1600)) }
            if (closing) return@launch
            if (bmp == null) { speakThenFinish(phrase("importUnreadable", "I couldn't open that picture.")); return@launch }
            showImage(bmp)
            if (mode == MODE_DESCRIBE) {
                val why = when {
                    VisionLadder.allowedRungs(settings).isEmpty() -> phrase("describeDisabled", "Describing photos needs an online AI, and none is allowed in Settings.")
                    !VisionLadder.canDescribe(settings) -> phrase("describeNoKey", "Describing needs a key that isn't set up on this phone. I can read any text I see instead.")
                    settings.geminiApiKey.isBlank() && settings.groqApiKeys.isEmpty() -> phrase("describeNoKey", "Describing needs a key that isn't set up on this phone. I can read any text I see instead.")
                    !NetworkMonitor.isOnline() -> phrase("describeOffline", "I can't describe it offline, but I can read any text I see.")
                    else -> null
                }
                if (why == null) {
                    showCaption(phrase("describing", "Got it, one moment."), CameraPhase.WORKING)
                    speak(phrase("describing", "Got it, one moment."), flush = false)
                    val jpeg = withContext(Dispatchers.Default) { ImportedImage.jpeg(bmp, AppConfig.int("camera.describe.maxSide", 1024)) }
                    LastCapture.hold(jpeg)   // a follow-up question goes back to the cloud with THIS picture (VisionQa)
                    // P5-07: the model answers in a small JSON contract (what it sees, the text it read, or why it won't answer);
                    // ML Kit reads the same picture meanwhile, the authority for exact strings (StructuredDescribe).
                    val ocr = async(Dispatchers.Default) { ocrOf(InputImage.fromBitmap(bmp, 0)) }
                    when (val r = VisionLadder.describe(jpeg, StructuredDescribe.PROMPT, settings, structured = true)) {
                        is VisionResult.Described -> { deliver(StructuredDescribe.spokenFor(r.text, ocr.await(), TAG)); return@launch }
                        is VisionResult.Failed -> {
                            Log.w(TAG, "[IMPORT] describe failed: ${r.reason}")
                            speak(phrase("describeFailed", "I couldn't get a description just now. I'll read any text I see instead."), flush = false)
                        }
                    }
                } else { mode = MODE_TEXT; speak(why, flush = false) }
            }
            readBitmapText(bmp)
        }
    }

    /** P5-07: the words ML Kit reads in the picture being described — null when OCR fails or takes too long
     *  (`camera.describe.ocrTimeoutMs`); the description is never held back for it beyond that. */
    private suspend fun ocrOf(input: InputImage): String? = withContext(Dispatchers.Default) {
        try {
            // ML Kit's first call on a cold process can take several seconds; the cloud round trip usually takes longer, and
            // the result is only awaited after it, so a generous cap costs nothing on the common path (first run: 4 s was
            // too short on the emulator — the OCR came back null and the divergence rule had nothing to compare).
            kotlinx.coroutines.withTimeoutOrNull(AppConfig.long("camera.describe.ocrTimeoutMs", 12_000L)) {
                kotlinx.coroutines.suspendCancellableCoroutine<String?> { cont ->
                    recognizer.process(input)
                        .addOnSuccessListener { r -> if (cont.isActive) cont.resume(ImportedImage.readingOrder(r.textBlocks)) }
                        .addOnFailureListener { if (cont.isActive) cont.resume(null) }
                }
            }
        } catch (_: Throwable) { null }
    }

    /** On-device OCR of the whole picture, once; the words as they are, in reading order. */
    private fun readBitmapText(bmp: android.graphics.Bitmap) {
        // Off the main thread: the recogniser's first call initialises ML Kit on the calling thread, which on the emulator
        // under load held the UI thread past the 5-s input window ("Input dispatching timed out … CameraReadActivity",
        // 13 October 2026 04:55 — the ANR dialog then took the whole process down). The listeners still run on main.
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                recognizer.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { result ->
                        if (closing) return@addOnSuccessListener
                        val text = ImportedImage.readingOrder(result.textBlocks)
                        if (text.isBlank()) deliver(phrase("importNoText", "I couldn't find any text in that picture."), reading = false) else deliver(text)
                    }
                    .addOnFailureListener { t ->
                        Log.w(TAG, "[IMPORT] OCR failed: ${t.javaClass.simpleName}")
                        deliver(phrase("importOcrFailed", "I couldn't read that picture."), reading = false)
                    }
            } catch (t: Throwable) {
                runOnUiThread { deliver(phrase("importOcrFailed", "I couldn't read that picture."), reading = false) }
            }
        }
    }

    /** The picked picture, for low-vision users, where the camera preview would be. */
    private fun showImage(bmp: android.graphics.Bitmap) {
        try {
            previewView.visibility = View.GONE
            val iv = imageView ?: android.widget.ImageView(this).apply {
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                (previewView.parent as? ViewGroup)?.addView(this, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                imageView = this
            }
            iv.setImageBitmap(bmp)
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        closing = true
        main.removeCallbacksAndMessages(null)
        pending.clear()
        afterSpeech.clear()
        // The held photo must not outlive the camera screen (VisionQa: a follow-up only makes sense in the moment).
        LastCapture.clear()
        // Unbinding puts the torch out too; explicit, so a light we lit can never be left burning.
        if (torchOn) try { camera?.cameraControl?.enableTorch(false) } catch (_: Throwable) {}
        // Use cases are unbound by the lifecycle (ON_DESTROY runs before this); make sure no frame is still routed here.
        try { analysis?.clearAnalyzer() } catch (_: Throwable) {}
        analysisExecutor?.shutdown()
        if (recognizerLazy.isInitialized()) try { recognizer.close() } catch (_: Throwable) {}
        appVoice.stop()
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
                // 28 Oct 2026 (owner: offline is the main feature): no network, no key or no cloud allowed → the phone names what it
                // sees by itself (WhatIsThis) instead of "I can't describe it offline". `camera.describe.offlineFirst` = always.
                (question == null || WhatIsThis.isNamingQuestion(question!!)) && !moneyAsk && WhatIsThis.installed(this) && (AppConfig.bool("camera.describe.offlineFirst", false) ||
                    VisionLadder.allowedRungs(settings).isEmpty() || !VisionLadder.canDescribe(settings) || !NetworkMonitor.isOnline()) -> {
                    mode = MODE_IDENTIFY
                    describeStartedAt = SystemClock.elapsedRealtime()
                    announce(phrase("modeIdentify", "I'll name what's in front of you, on this phone. Point the camera at it and hold still."))
                }
                // J-18: the user switched the Gemini backup off — a photo must not leave the phone behind their back.
                VisionLadder.allowedRungs(settings).isEmpty() -> {
                    speakThenFinish(phrase("describeDisabled",
                        "Describing photos needs an online AI, and none is allowed in Settings."))
                    return
                }
                // The Groq vision rung (VisionLadder) means describe works on keyless-Gemini days too.
                !VisionLadder.canDescribe(settings) -> {
                    mode = MODE_TEXT
                    announce(phrase("describeNoKey", "Describing needs a key that isn't set up on this phone. I can read any text I see instead."))
                }
                !NetworkMonitor.isOnline() -> {
                    mode = MODE_TEXT
                    announce(phrase("describeOffline", "I can't describe it offline, but I can read any text I see."))
                }
                moneyAsk -> announce(phrase("modeMoneyAsk", "Hold the money flat in front of the camera. I'll count it."))
                question != null -> announce(phrase("modeAsk", if (instantCapture) "Looking." else "Point the camera at it and hold still. I'll look."))
                else -> announce(phrase("modeDescribe", if (instantCapture) "Looking." else "Hold still, I'll describe what's in front of you."))
            }
        } else if (mode == MODE_CURRENCY) {
            // No model in this build: say so at once, before the camera opens — not after a frame, and never by just
            // closing (audit 2026-10-01, CAM04).
            if (!CediClassifier.installed(this)) {
                Log.w(TAG, "[CEDI] model not in this build (assets/cedi/cedi.onnx) — telling the user")
                speakThenFinish(phrase("currencyNoModel", CediClassifier.NOT_INSTALLED))
                return
            }
            announce(phrase("modeCurrency", "Checking money. Hold the note flat, about a hand away."))
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
                // Money too (28 Oct 2026): the detector's coin boxes are cropped from these pixels, and a coin is a small part of the frame.
                val target = if (mode == MODE_DESCRIBE || (mode == MODE_CURRENCY && AppConfig.bool("camera.currency.hiRes", true)))
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

                val cam = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, useCase)
                camera = cam
                // Opening can fail later (another app holds the camera, policy) — say so instead of a silent black screen.
                cam.cameraInfo.cameraState.observe(this) { state ->
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
        val every = if (mode == MODE_DESCRIBE && instantCapture) minOf(intervalMs, AppConfig.long("camera.capture.frameEveryMs", 120L)) else intervalMs
        if (closing || now - lastAnalysedAt < every) { image.close(); return }
        lastAnalysedAt = now
        when (mode) {
            MODE_TEXT -> readText(image)   // closes the image when OCR completes
            MODE_COLOR -> try { checkColour(image) } catch (t: Throwable) { logFrameError(t) } finally { image.close() }
            MODE_IDENTIFY -> try { checkIdentify(image) } catch (t: Throwable) { logFrameError(t) } finally { image.close() }
            MODE_CURRENCY -> try { checkCurrency(image) } catch (t: Throwable) { logFrameError(t) } finally { image.close() }
            else -> try { gateForDescribe(image) } catch (t: Throwable) { logFrameError(t) } finally { image.close() }
        }
    }

    private fun logFrameError(t: Throwable) {
        if (BuildConfig.DEV_HOOKS) Log.d(TAG, "[CAMERA] frame skipped: ${t.javaClass.simpleName}: ${t.message}")
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

    // ------------------------------------------------------------------------------------------------ cedi money

    /** Analyser thread: the centre square → the on-phone cedi model. Never speaks a denomination itself. */
    private fun checkCurrency(image: ImageProxy) {
        if (closing || mode != MODE_CURRENCY) return
        if (!cediTried) { cediTried = true; cedi = CediClassifier.create(this) }
        val model = cedi ?: run {
            // The file is there but wouldn't load (corrupt, out of memory): the same honest line, then leave.
            main.post { if (!closing) speakThenFinish(phrase("currencyNoModel", CediClassifier.NOT_INSTALLED)) }
            return
        }
        // It's dark: money is counted indoors and at night — put the light on before guessing at shadows.
        val yP = image.planes[0]
        val luma = FrameStats.mean(FrameConvert.planeBytes(yP, yBuf).also { yBuf = it },
            yP.rowStride, yP.pixelStride, 0, 0, image.width, image.height, step = 16)
        if (luma < AppConfig.double("camera.quality.darkBelow", QualityGate.DARK_BELOW)) main.post { turnTorchOn() }

        // The detector (28 Oct 2026): every note and coin in the whole frame, each then named from the camera's own pixels and
        // checked in its other quarter-turns. A coin box named as a note (or the reverse) is not trusted. Nothing found → the
        // older path below (a note filling the frame is still read from the centre square).
        if (AppConfig.bool("camera.currency.detector", true)) {
            if (!detTried) { detTried = true; detector = if (CediDetector.installed(this)) CediDetector.create(this) else null }
            detector?.let { det ->
                val boxes = det.detect(RgbSquare.stretched(image, CediDetector.SIDE, detBuf).also { detBuf = it },
                    AppConfig.double("camera.currency.detScore", CediDetector.SCORE_MIN.toDouble()).toFloat())
                    .take(AppConfig.int("camera.currency.maxItems", 6))
                if (boxes.isNotEmpty()) {
                    val readings = boxes.map { b ->
                        val w = CediDetector.window(b, image.width, image.height, AppConfig.double("camera.currency.detMargin", 1.25).toFloat())
                        val px = RgbSquare.window(image, CediClassifier.SIDE, tightBuf, w.x, w.y, w.size).also { tightBuf = it }
                        val (c0, p0) = model.classify(px)
                        val (c, p) = model.checked(c0, p0, px)
                        val sameKind = MoneyItems.itemOf(c)?.let { it.note == !b.coin } ?: true
                        MoneyItems.Reading(c, if (sameKind) p else 0.0)
                    }
                    if (com.tekyerema.BuildConfig.DEV_HOOKS) Log.i(TAG, "[CEDI] detector ${boxes.size} box(es): " +
                        boxes.zip(readings).joinToString(" ") { (b, r) -> "${if (b.coin) "coin" else "note"}@${"%.2f".format(b.score)}=${r.cls}:${"%.2f".format(r.conf)}" })
                    if (boxes.size >= 2) {
                        val count = MoneyItems.count(readings, AppConfig.double("camera.currency.gate", 0.85))
                        main.post { onItems(count) }
                    } else main.post { onCurrency(readings[0].cls, readings[0].conf) }
                    return
                }
            }
        }

        val rgb = RgbSquare.from(image, CediClassifier.SIDE, rgbBuf).also { rgbBuf = it }
        // The object, cropped (ObjectCrop): a coin is ~15 % of the centre square and was misread at that size. Off: camera.currency.tightCrop.
        val tight = if (AppConfig.bool("camera.currency.tightCrop", true)) {
            val small = RgbSquare.from(image, ObjectCrop.GRID, smallBuf).also { smallBuf = it }
            // Two or more separate things in view (a note and a coin): each is read by itself, on the phone, and counted only when
            // it is sure (MoneyItems). One thing, or none that is money, goes on to the single-item reading below.
            if (AppConfig.bool("camera.currency.multi", true)) {
                val boxes = ObjectCrop.findAll(small, ObjectCrop.GRID, AppConfig.int("camera.currency.maxItems", 6))
                if (boxes.size >= 2) {
                    val readings = boxes.map { box ->
                        val w = ObjectCrop.window(box, image.width, image.height)
                        val px = RgbSquare.window(image, CediClassifier.SIDE, tightBuf, w.x, w.y, w.size).also { tightBuf = it }
                        model.classify(px).let { (c, p) -> MoneyItems.Reading(c, p) }
                    }
                    val count = MoneyItems.count(readings, AppConfig.double("camera.currency.gate", 0.85))
                    if (count.seen >= 2) { main.post { onItems(count) }; return }
                }
            }
            ObjectCrop.find(small, ObjectCrop.GRID)?.takeIf { ObjectCrop.plausible(it) }?.let { box ->
                val w = ObjectCrop.window(box, image.width, image.height)
                RgbSquare.window(image, CediClassifier.SIDE, tightBuf, w.x, w.y, w.size).also { tightBuf = it }
            }
        } else null
        val (cls, confidence) = model.classifyBoth(rgb, tight)
        main.post { onCurrency(cls, confidence) }
    }

    private fun onCurrency(cls: String, confidence: Double) {
        if (closing || mode != MODE_CURRENCY) return
        val now = SystemClock.elapsedRealtime()
        if (CurrencyAnnouncer.isMoney(cls)) moneySeenAt = now
        currencyAnnouncer.offer(cls, confidence, now)?.let { deliver(if (isTwi()) MoneyItems.spokenTwiOf(cls) ?: it else it); return }
        hintCurrency(now)
    }

    /** Main thread. Two or more things in view, each read by itself ([MoneyItems]): the ones read and their total, said once the
     *  same set holds on two frames; what was not read is said as not read. */
    private fun onItems(count: MoneyItems.Count) {
        if (closing || mode != MODE_CURRENCY) return
        val now = SystemClock.elapsedRealtime()
        moneySeenAt = now
        val say = itemsAnnouncer.offer(count, now)
        if (say == null) { hintCurrency(now); return }
        Log.i(TAG, "[CAMERA] money on the phone: ${say.sure.size} read, ${say.unsure} not read, ${say.ignored} not money")
        (if (isTwi()) MoneyItems.spokenTwi(say) else MoneyItems.spoken(say))?.let { deliver(it) }
    }

    /** Silence is ambiguous to someone who can't see the screen — say WHY, on the guidance cadence, and never a number below the
     *  gate (a wrong amount is worse than no answer). The unsure line names money, not "note": a coin may be what is held. */
    private fun hintCurrency(now: Long) {
        if (isSpeaking() || now - lastCurrencyHintAt < AppConfig.long("camera.currency.hintEveryMs", 6_000L)) return
        val sawMoney = moneySeenAt > 0 && now - moneySeenAt < 2_000L
        val waited = now - describeStartedAt
        val line = when {
            sawMoney && waited > AppConfig.long("camera.currency.unsureHintMs", 8_000L) ->
                if (isTwi()) MoneyItems.UNSURE_TW else phrase("currencyUnsure", MoneyItems.UNSURE_EN)
            !sawMoney && waited > AppConfig.long("camera.currency.noMoneyHintMs", 10_000L) ->
                phrase("currencyNone", "I don't see any money yet. Hold the note flat, about a hand away.")
            else -> return
        }
        lastCurrencyHintAt = now
        deliver(line, reading = false)
    }

    /** Analyser thread: the centre square → the offline namer. Never speaks itself. */
    private fun checkIdentify(image: ImageProxy) {
        if (closing || mode != MODE_IDENTIFY) return
        if (!whatIsTried) { whatIsTried = true; whatIs = WhatIsThis.create(this) }
        val model = whatIs ?: run {
            main.post { if (!closing) { mode = MODE_TEXT; announce(phrase("describeOffline", "I can't describe it offline, but I can read any text I see.")) } }
            return
        }
        val t0 = SystemClock.elapsedRealtime()
        val a = model.identify(RgbSquare.from(image, WhatIsThis.SIDE, idBuf).also { idBuf = it })
        if (com.tekyerema.BuildConfig.DEV_HOOKS) Log.i(TAG, "[WHATIS] ${a.kind} ${a.label} p=${"%.3f".format(a.p)} top=${a.top} in ${SystemClock.elapsedRealtime() - t0} ms")
        main.post { onIdentify(a) }
    }

    /** Main thread. Said once the same answer holds on two frames in a row; the same thing is not said again for 10 s. */
    private fun onIdentify(a: WhatIsThis.Answer) {
        if (closing || mode != MODE_IDENTIFY) return
        val now = SystemClock.elapsedRealtime()
        val key = a.label?.let { "${a.kind}:$it" }
        // Two frames in a row, or one very sure item: on the Infinix a frame takes ~1.8 s, so waiting for two cost ~4 s.
        val steady = key != null && (key == idLast || (a.kind == WhatIsThis.Kind.ITEM && a.p >= AppConfig.double("camera.identify.sureAt", 0.85)))
        idLast = key
        if (steady && !isSpeaking() && (key != idSaid || now - idSaidAt > AppConfig.long("camera.identify.repeatMs", 10_000L))) {
            WhatIsThis.spoken(a)?.let { line ->
                idSaid = key; idSaidAt = now
                Log.i(TAG, "[CAMERA] named offline: ${a.kind}")
                deliver(line)
                return
            }
        }
        if (key == null && !isSpeaking() && now - describeStartedAt > 6_000L && now - idHintAt > 8_000L && now - idSaidAt > 8_000L) {
            idHintAt = now
            deliver(phrase("identifyUnsure", "I'm not sure what this is. Move it a little closer, or into better light."), reading = false)
        }
    }

    /** The conversation is in Twi: money and reading lines that have a Twi form are said in it, with no translator. */
    private fun isTwi(): Boolean = try { com.tekyerema.voice.VoiceEngine.language() == com.tekyerema.voice.Lang.TWI } catch (_: Throwable) { false }

    // ------------------------------------------------------------------------------------------------ P-01 / P-09 describe

    /** Analyser thread: grade the frame; the first good one (claimed once) is copied and sent. */
    /** Owner, 21 Oct 2026: "it should capture the moment and analyse it… not I'll be pointing, waiting". The first moment is
     *  taken as a short burst (`camera.capture.windowMs`, ~0.9 s once frames arrive); its sharpest well-lit frame is sent at
     *  once. The vision answer itself says how to move the phone if the thing is cut off. `camera.capture.instant` false = the
     *  old coached wait for a sharp frame. */
    private val instantCapture: Boolean by lazy { AppConfig.bool("camera.capture.instant", true) }
    private var burstStartAt = 0L
    private var burstBest: ByteArray? = null
    private var burstBestScore = -1.0
    private var burstW = 0; private var burstH = 0; private var burstRot = 0

    // P5-a: the second sharp frame of a money count, taken ~0.5 s after the first, used only when one model answers.
    @Volatile private var firstSentAt = 0L
    @Volatile private var second: Triple<ByteArray, IntArray, Boolean>? = null   // nv21, [w, h, rotation]

    /** Analyser thread, after the first frame went out: keep ONE sharp, well-lit frame at least 0.5 s later (money only). */
    private fun grabSecond(image: ImageProxy) {
        if (second != null || firstSentAt == 0L) return
        val now = SystemClock.elapsedRealtime()
        if (now - firstSentAt < AppConfig.long("camera.money.secondFrameGapMs", 500L)) return
        val yP = image.planes[0]
        val luma = FrameConvert.planeBytes(yP, yBuf).also { yBuf = it }
        val q = QualityGate.assess(luma, image.width, image.height, yP.rowStride,
            darkBelow = AppConfig.double("camera.quality.darkBelow", QualityGate.DARK_BELOW),
            brightAbove = AppConfig.double("camera.quality.brightAbove", QualityGate.BRIGHT_ABOVE),
            blurMin = AppConfig.double("camera.quality.blurMin", QualityGate.BLUR_MIN))
        if (q.verdict == Verdict.TOO_DARK || q.verdict == Verdict.TOO_BRIGHT || q.verdict == Verdict.BLURRY) return
        second = Triple(FrameConvert.toNv21(image), intArrayOf(image.width, image.height, image.imageInfo.rotationDegrees), true)
    }

    private fun gateForDescribe(image: ImageProxy) {
        if (describeClaimed.get()) { if (moneyAsk) grabSecond(image); return }
        if (instantCapture) { captureMoment(image); return }
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
            if (BuildConfig.DEV_HOOKS) Log.d(TAG, "[CAMERA] frame accepted blur=${q.blurScore.toInt()} luma=${q.meanLuma.toInt()} bestEffort=$bestEffort")
            main.post { sendForDescription(nv21, w, h, rotation) }
        } else {
            val verdict = q.verdict
            main.post { onGuidance(verdict) }
        }
    }

    /** Analyser thread: one frame of the burst. Too dark → the light goes on and the moment starts again (once). */
    private fun captureMoment(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (burstStartAt == 0L) burstStartAt = now
        val yP = image.planes[0]
        val luma = FrameConvert.planeBytes(yP, yBuf).also { yBuf = it }
        val q = QualityGate.assess(luma, image.width, image.height, yP.rowStride,
            darkBelow = AppConfig.double("camera.quality.darkBelow", QualityGate.DARK_BELOW),
            brightAbove = AppConfig.double("camera.quality.brightAbove", QualityGate.BRIGHT_ABOVE),
            blurMin = AppConfig.double("camera.quality.blurMin", QualityGate.BLUR_MIN))
        if (q.verdict == Verdict.TOO_DARK && !torchOn) {
            main.post { if (turnTorchOn()) burstStartAt = SystemClock.elapsedRealtime() }
        }
        val usable = q.verdict != Verdict.TOO_DARK && q.verdict != Verdict.TOO_BRIGHT
        if (usable && q.blurScore > burstBestScore) {
            burstBestScore = q.blurScore
            burstBest = FrameConvert.toNv21(image); burstW = image.width; burstH = image.height; burstRot = image.imageInfo.rotationDegrees
        }
        val window = AppConfig.long("camera.capture.windowMs", 900L)
        val waited = now - burstStartAt
        // The moment is over: send the best frame; with none usable after three windows, send this one anyway (the answer
        // will say it is dark or blurred and how to fix it).
        val pick = when {
            waited >= window && burstBest != null -> burstBest
            waited >= window * 3 -> FrameConvert.toNv21(image).also { burstW = image.width; burstH = image.height; burstRot = image.imageInfo.rotationDegrees }
            else -> null
        } ?: return
        if (!describeClaimed.compareAndSet(false, true)) return
        val w = burstW; val h = burstH; val rot = burstRot
        firstSentAt = SystemClock.elapsedRealtime()
        if (BuildConfig.DEV_HOOKS) Log.d(TAG, "[CAMERA] moment captured after ${waited} ms (best blur=${burstBestScore.toInt()})")
        main.post { sendForDescription(pick, w, h, rot) }
    }

    /** Main thread. Coach only on a verdict seen twice in a row, never over other speech, at most every few seconds. */
    private fun onGuidance(verdict: Verdict) {
        if (closing || mode != MODE_DESCRIBE || describeClaimed.get()) return
        val stable = verdict == lastVerdict
        lastVerdict = verdict
        if (!stable) return
        // A frame that STAYED dark (not just the first auto-exposure frame): the user can't see the darkness and
        // may have no light to turn on — put the phone's own light on for them instead of coaching.
        if (verdict == Verdict.TOO_DARK && turnTorchOn()) return
        val now = SystemClock.elapsedRealtime()
        if (isSpeaking() || now - lastGuidanceAt < AppConfig.long("camera.describe.guidanceIntervalMs", 3_000L)) return
        lastGuidanceAt = now
        val line = QualityGate.guidance(verdict)
        if (line.isNotEmpty()) speak(line)
    }

    /** Auto-torch (`vision.torch.auto`), once per session, said aloud — surprise light in a blind user's hand is
     *  rude, and a sighted helper nearby would wonder. False when there is no torch to turn on (front camera has
     *  none; describe uses the back one), so the normal "too dark" coaching still happens. */
    private fun turnTorchOn(): Boolean {
        if (torchOn || !AppConfig.bool("vision.torch.auto", true)) return false
        val cam = camera ?: return false
        if (!cam.cameraInfo.hasFlashUnit()) return false
        return try {
            cam.cameraControl.enableTorch(true)
            torchOn = true
            // Give the light a moment to change the frames before any "too dark" line could contradict it.
            lastGuidanceAt = SystemClock.elapsedRealtime()
            speak(phrase("torchOn", "It's dark — I've put the light on."), flush = false)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "[CAMERA] torch failed: ${t.javaClass.simpleName}")
            false
        }
    }

    private fun sendForDescription(nv21: ByteArray, width: Int, height: Int, rotation: Int) {
        if (closing) return
        // The request takes a few seconds: say we have the picture so the user can stop aiming.
        ui.shutter++
        showCaption(phrase("describing", "Got it, one moment."), CameraPhase.WORKING)
        speak(phrase("describing", "Got it, one moment."), flush = false)
        lifecycleScope.launch {
            val jpeg = withContext(Dispatchers.Default) {
                FrameConvert.nv21ToJpeg(nv21, width, height, rotation,
                    if (question != null || moneyAsk) VisionPrompts.maxSide(question, moneyAsk) else AppConfig.int("camera.describe.maxSide", 1024),
                    if (question != null || moneyAsk) 80 else 80)
            }
            // P5-07: ML Kit reads the same frame while the cloud describes it — the authority for exact strings.
            val ocr = async(Dispatchers.Default) {
                try { ocrOf(InputImage.fromByteArray(nv21, width, height, rotation, android.graphics.ImageFormat.NV21)) } catch (_: Throwable) { null }
            }
            // Money: the phone's own cedi model re-reads each note or coin the cloud model boxed, and its sure reading replaces the
            // cloud's value (27 Oct 2026: a coin came back as 2, 1 and 0.5 cedis in three tries). Nothing leaves the phone for this.
            val photo = if (moneyAsk && jpeg != null) withContext(Dispatchers.Default) {
                if (loadCedi() == null) null else android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            } else null
            val moneyDebug = java.util.Collections.synchronizedList(ArrayList<String>())
            val onPhone: (String) -> MoneyTally.Checked? = { text ->
                val model = cedi
                if (photo == null || model == null) null
                else MoneyTally.withOnDevice(text) { box ->
                    // A note is held any way up and the cloud's box may be loose or tight: the piece is read in a tight and a wide
                    // crop, each in all four quarter-turns, and the phone's word is taken only when the readings agree.
                    val readings = ArrayList<MoneyItems.Reading>()
                    for (margin in floatArrayOf(1.25f, 1.7f)) CediCrops.rgb(photo, box, margin)?.let { px ->
                        for (k in 0..3) model.classify(CediCrops.turned(px, CediClassifier.SIDE, k)).let { (c, p) -> readings.add(MoneyItems.Reading(c, p)) }
                    }
                    val item = if (readings.isEmpty()) null else MoneyItems.consensus(readings, AppConfig.double("camera.currency.gate", 0.85))
                    if (com.tekyerema.BuildConfig.DEV_HOOKS) moneyDebug.add("box=${box.joinToString(",") { it.toInt().toString() }} " +
                        "reads=${readings.joinToString(" ") { "${it.cls}:${"%.2f".format(it.conf)}" }} → ${item?.let { "${it.value}${if (it.note) "n" else "c"}" } ?: "unsure"}")
                    item
                }
            }
            val answer = if (jpeg == null) VisionResult.Failed("encode")
            else {
                // Held (memory only, dropped when this screen closes) so a follow-up question — "what colour is
                // the shirt?" — can go back to the cloud with THIS photo (VisionQa, the Be My AI pattern).
                LastCapture.hold(jpeg)
                val q = question
                when {
                    moneyAsk -> VisionLadder.describe(jpeg, VisionPrompts.MONEY, settings, structured = false, smart = true,
                        agree = { a, b -> VisionPrompts.moneyDisagreement(onPhone(a)?.json ?: a, onPhone(b)?.json ?: b,
                            phrase("moneyDisagree", "I can't be sure of the total — I counted it two ways. Hold the money flat, " +
                                "one note at a time, and ask me again.")) })
                    q != null -> VisionLadder.describe(jpeg, VisionPrompts.forQuestion(q), settings, structured = false, smart = true)
                    else -> VisionLadder.describe(jpeg, StructuredDescribe.PROMPT, settings, structured = true)   // N-06; Groq and Gemini hedged (VisionLadder.race)
                }
            }
            if (closing) return@launch
            val checked = if (moneyAsk && answer is VisionResult.Described) onPhone(answer.text) else null
            // Debug builds keep the frame and what each model said about it (files/money_debug), so a wrong count can be looked at afterwards.
            if (com.tekyerema.BuildConfig.DEV_HOOKS && moneyAsk && jpeg != null && answer is VisionResult.Described) try {
                val dir = java.io.File(filesDir, "money_debug").apply { mkdirs() }
                val stamp = System.currentTimeMillis()
                java.io.File(dir, "$stamp.jpg").writeBytes(jpeg)
                java.io.File(dir, "$stamp.txt").writeText("cloud: ${answer.text}\n" + moneyDebug.joinToString("\n"))
            } catch (_: Throwable) {}
            val result = if (checked != null && answer is VisionResult.Described) answer.copy(text = checked.json) else answer
            when (result) {
                is VisionResult.Described -> deliver(when {
                    moneyAsk -> {
                        val listed = MoneyTally.parse(result.text)
                        // Every piece read for sure by the phone's own model: nothing left to cross-check with a second cloud frame.
                        val allOnPhone = checked != null && listed != null && listed.isNotEmpty() && checked.read == listed.size
                        // P5-a: one model alone is not trusted with a count — a second frame must give the same tally.
                        val twoFramesAgree = allOnPhone || secondFrameAgrees(result)
                        if (checked != null) Log.i(TAG, "[CAMERA] money on the phone: ${checked.read} of ${checked.boxed} boxed pieces read, " +
                            "${checked.changed} differed from the cloud model's value")
                        // How the phone counted (no amounts in a release log): pieces the model listed, merged duplicates, overlap.
                        listed?.let { p -> val t = MoneyTally.reconcile(p)
                            Log.i(TAG, "[CAMERA] money: ${p.size} listed, ${p.count { it.box != null }} with a box, ${t.merged} merged as one note, " +
                                "unstable=${t.unstable}" + if (com.tekyerema.BuildConfig.DEV_HOOKS) " model='${result.text.take(600)}'" else "") }
                        if (!twoFramesAgree) VisionPrompts.SPREAD_OUT
                        else VisionPrompts.spokenMoney(result.text) ?: VisionPrompts.spoken(result.text)
                    }
                    question != null -> VisionPrompts.spoken(result.text)
                    else -> StructuredDescribe.spokenFor(result.text, ocr.await(), TAG)
                })
                is VisionResult.Failed -> {
                    Log.w(TAG, "[CAMERA] describe failed: ${result.reason}")
                    // Counting money must not end in text reading: the phone's own cedi model reads each note and coin without the net.
                    if (moneyAsk && CediClassifier.installed(this@CameraReadActivity)) {
                        moneyAsk = false
                        mode = MODE_CURRENCY
                        describeStartedAt = SystemClock.elapsedRealtime()
                        itemsAnnouncer.offer(MoneyItems.Count(emptyList(), 0, 0), 0L)   // a fresh run
                        announce(phrase("moneyOnPhone", "I couldn't count it online, so I'll read it on this phone. Hold each note or coin flat, a hand away."))
                        resetIdleTimer()
                    } else fallBackToText(phrase("describeFailed", "I couldn't get a description just now. I'll read any text I see instead."))
                }
            }
        }
    }

    /** The cedi model, loaded once (~0.2 s); null when it isn't in the build or cannot load. */
    private fun loadCedi(): CediClassifier? {
        if (!cediTried) { cediTried = true; cedi = CediClassifier.create(this) }
        return cedi
    }

    /**
     * P5-a. False only when a money count came from ONE model (not cross-checked by the other) and the same notes in a second
     * frame, ~0.5 s later, were counted differently. True = nothing to doubt, or nothing to check against (no second sharp
     * frame, or the second question failed — logged, the first answer stands).
     */
    private suspend fun secondFrameAgrees(first: VisionResult.Described): Boolean {
        if (first.crossChecked || !AppConfig.bool("camera.money.twoFrames", true)) return true
        val firstTally = MoneyTally.of(first.text) ?: return true
        if (firstTally.isEmpty || firstTally.unstable) return true          // already the "no money" / "spread out" line
        var f = second
        var waited = 0
        while (f == null && waited < 1_500 && !closing) { delay(100); waited += 100; f = second }
        f ?: run { Log.i(TAG, "[CAMERA] money: single model, no second sharp frame — first count kept"); return true }
        val jpeg2 = withContext(Dispatchers.Default) {
            FrameConvert.nv21ToJpeg(f.first, f.second[0], f.second[1], f.second[2], VisionPrompts.maxSide(question, true), 80)
        } ?: return true
        val r2 = VisionLadder.describe(jpeg2, VisionPrompts.MONEY, settings, structured = false, smart = true)
        if (r2 !is VisionResult.Described) { Log.i(TAG, "[CAMERA] money: second frame unanswered — first count kept"); return true }
        val same = MoneyTally.sameCounts(firstTally, MoneyTally.of(r2.text))
        Log.i(TAG, "[CAMERA] money: single model, second frame agrees=$same")
        return same
    }

    /** Describe isn't possible this session: keep the camera useful by reading text, and say so. */
    private fun fallBackToText(line: String) {
        mode = MODE_TEXT
        stabilizer.reset()
        ui.label = modeLabel()
        showCaption(line, CameraPhase.FAILED)
        speak(line)
        resetIdleTimer()
        scheduleNoTextHint()
    }

    // ------------------------------------------------------------------------------------------------ output

    /** A new result: show it, say it, and restart the idle clock. [reading] = false for a hint or a failure line. */
    private fun deliver(text: String, reading: Boolean = true) {
        if (closing) return
        spokeResult = true
        main.removeCallbacks(noTextHint)
        showCaption(text, CameraPhase.ANSWER)
        // The agent remembers what was read, so "send that to my wife" or "remind me of the date" can act on it next.
        if (reading && mode != MODE_COLOR) com.tekyerema.agent.AgentEngine.noteReading("camera", text)
        // Printed English in a Twi conversation with no internet: the page's own words in the English voice (ReaderVoice.englishOffline).
        if (reading && mode == MODE_TEXT && ReaderVoice.englishOffline(text)) systemUtter(Verbalize.apply(text), true, null) else speak(text)
        resetIdleTimer()
    }

    /** The mode line. Queued (never cuts anything off), and after TalkBack's own window announcement. */
    private fun announce(line: String) {
        ui.label = modeLabel()   // begin() may have settled on text (offline, no key)
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
        showCaption(line, CameraPhase.FAILED)
        speak(line) { finish() }
        main.postDelayed({ finish() }, FINISH_FALLBACK_MS)
    }

    private fun showCaption(text: String, phase: CameraPhase = CameraPhase.LOOKING) {
        ui.text = text
        ui.phase = phase
    }

    /** The capsule's small line: what the camera is doing (the question, when there is one, goes under it). */
    private fun modeLabel(): String = when {
        moneyAsk -> phrase("labelMoneyAsk", "Counting money")
        mode == MODE_DESCRIBE && question != null -> phrase("labelAsk", "Looking for you")
        mode == MODE_DESCRIBE -> phrase("labelDescribe", "Describing")
        mode == MODE_CURRENCY -> phrase("labelCurrency", "Checking money")
        mode == MODE_COLOR -> phrase("labelColor", "Colour and light")
        mode == MODE_IDENTIFY -> phrase("labelIdentify", "Naming it, offline")
        else -> phrase("labelText", "Reading text")
    }

    private fun phrase(key: String, def: String): String = AppConfig.string("camera.phrases.$key", def)

    // ------------------------------------------------------------------------------------------------ TTS

    /** New content flushes what is being said (a new sign supersedes the old); status lines queue. */
    private fun speak(text: String, flush: Boolean = true, then: (() -> Unit)? = null) {
        utter(Verbalize.apply(text), flush, then)
    }

    private fun utter(line: String, flush: Boolean, then: (() -> Unit)?) {
        if (line.isBlank()) { then?.invoke(); return }
        // The app's own voice in the language the user set — Kafui's Ghanaian English, or Twi (translated) — never the phone's
        // generic English engine while that voice is up (owner, 20 + 21 Oct 2026).
        if (ReaderVoice.use()) { logSpoke(line, true); appVoice.say(line, flush, then); return }
        systemUtter(line, flush, then)
    }

    /** The phone's own engine: only when the app's voice is not up, or could not say a line ([ReaderVoice] fallback). */
    private fun systemUtter(line: String, flush: Boolean, then: (() -> Unit)?) {
        val engine = tts
        if (ttsFailed || engine == null) { sayWithAppVoice(line, then); return }
        if (!ttsReady) {
            if (flush) pending.clear()
            pending.add(Speech(line, flush, then))
            return
        }
        val id = "cam-" + (++utteranceSeq)
        if (then != null) afterSpeech[id] = then
        spokenLines[id] = line
        val rc = try {
            engine.speak(line, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        } catch (_: Throwable) {
            TextToSpeech.ERROR
        }
        if (rc != TextToSpeech.SUCCESS) {
            // The engine took the line and refused it: the app's own voice says it instead — never nothing.
            spokenLines.remove(id)
            sayWithAppVoice(line, afterSpeech.remove(id))
        }
    }

    /** id → the line being spoken, so its outcome can be logged ([logSpoke]). Main thread only. */
    private val spokenLines = HashMap<String, String>()

    /**
     * One log line per camera utterance, in the agent's own format (`[ANNOUNCE] … spoke=`), so what the camera said
     * can be checked from the log like every other reply (audit 2026-10-01: colour and money results were on screen
     * but nothing in the log showed whether they were spoken). The words only where content logging is allowed.
     */
    private fun logSpoke(line: String, ok: Boolean) {
        Log.i(TAG, "[ANNOUNCE] camera($mode) spoke=$ok ${com.tekyerema.core.Privacy.content(line)}")
    }

    /**
     * The activity's own engine can't speak (init failed, or it refused the line): hand the line to the agent's voice
     * rather than say nothing, and give it time to be heard before [then] (usually finish()) runs — a camera screen
     * that closes in silence tells a blind user nothing.
     */
    private fun sayWithAppVoice(line: String, then: (() -> Unit)?) {
        val handed = try { com.tekyerema.voice.VoiceEngine.announce(line); true } catch (_: Throwable) { false }
        // "handed", not "spoke": the agent's voice logs its own `[ANNOUNCE] spoke=` when it has said the line.
        Log.w(TAG, "[ANNOUNCE] camera($mode) own voice unavailable → handed to the app voice=$handed ${com.tekyerema.core.Privacy.content(line)}")
        if (then != null) main.postDelayed({ then() }, CameraSpeech.hearingMs(line))
    }

    private fun isSpeaking(): Boolean = appVoice.speaking || try { tts?.isSpeaking == true } catch (_: Throwable) { false }

    private fun onTtsInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "[CAMERA] TTS engine init failed ($status) — camera lines go to the app's voice")
            ttsFailed = true
            val waiting = pending.toList()
            pending.clear()
            // Flushed lines were superseded; what is left is said (by the app's voice), in order, then their follow-ups run.
            waiting.forEach { utter(it.line, it.flush, it.then) }
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
            override fun onDone(utteranceId: String?) = finished(utteranceId, true)
            @Deprecated("deprecated in API 21")
            override fun onError(utteranceId: String?) = finished(utteranceId, false)
            override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId, false)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId, false)
        })
        ttsReady = true
        val waiting = pending.toList()
        pending.clear()
        waiting.forEach { utter(it.line, it.flush, it.then) }
    }

    /** TTS binder thread → main: log the line's outcome, then run whatever was waiting on this utterance.
     *  [spokenFully] is false when the line was cut off by a newer one, stopped, or failed. */
    private fun finished(utteranceId: String?, spokenFully: Boolean) {
        if (utteranceId == null) return
        main.post {
            spokenLines.remove(utteranceId)?.let { logSpoke(it, spokenFully) }
            afterSpeech.remove(utteranceId)?.invoke()
        }
    }

    // ------------------------------------------------------------------------------------------------ layout

    private fun buildLayout(): View {
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
        // The liquid-glass layer (CameraSurface). Its words are already spoken by us — TalkBack reading them again would double
        // every result — so only its Close pill is exposed.
        val surface = androidx.compose.ui.platform.ComposeView(this).apply {
            setContent { CameraSurface(ui, closeLabel = phrase("closeAction", "Close").replaceFirstChar { it.uppercase() }, onClose = { close() }) }
        }
        root.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return root
    }
}
