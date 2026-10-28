package com.tekyerema.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Debug builds only: collect labelled money photos for training and for an honest real-world test (28 Oct 2026). The frames are
 * the camera-analysis frames the money reader itself sees (YUV preview, 1280×960), not the camera app's photos, so the training
 * data matches what the phone reads. One sharp frame every [EXTRA_EVERY] ms is saved upright to
 * `files/money_collect/<label>/<time>.jpg`, with a short beep, until [EXTRA_COUNT] are saved. The label is free text: a single
 * class ("coin_1_cedi_2022") or a pile's contents ("mix_50n_20n_20n_1c"). Started by the RUN_GOAL hook `money_collect`.
 */
class MoneyCollectActivity : ComponentActivity() {
    companion object {
        private const val TAG = "Tekyerema"
        const val EXTRA_LABEL = "label"
        const val EXTRA_COUNT = "count"
        const val EXTRA_EVERY = "every"

        fun intent(ctx: Context, label: String, count: Int, everyMs: Long): Intent =
            Intent(ctx, MoneyCollectActivity::class.java).putExtra(EXTRA_LABEL, label).putExtra(EXTRA_COUNT, count)
                .putExtra(EXTRA_EVERY, everyMs).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Folder-safe label: letters, digits and _ - only. */
        fun safeLabel(s: String): String = s.trim().lowercase().replace(Regex("[^a-z0-9_-]+"), "_").trim('_').take(60).ifEmpty { "unlabelled" }
    }

    private lateinit var previewView: PreviewView
    private lateinit var info: TextView
    private var executor: ExecutorService? = null
    private var tone: ToneGenerator? = null
    private lateinit var dir: File
    private var label = ""
    private var want = 30
    private var everyMs = 600L
    @Volatile private var saved = 0
    @Volatile private var lastAt = 0L
    @Volatile private var done = false
    private var yBuf: ByteArray? = null

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!com.tekyerema.BuildConfig.DEV_HOOKS) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        label = safeLabel(intent.getStringExtra(EXTRA_LABEL).orEmpty())
        want = intent.getIntExtra(EXTRA_COUNT, 30).coerceIn(1, 500)
        everyMs = intent.getLongExtra(EXTRA_EVERY, 600L).coerceIn(200L, 10_000L)
        dir = File(filesDir, "money_collect/$label").apply { mkdirs() }
        tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, 60) } catch (_: Throwable) { null }
        previewView = PreviewView(this)
        info = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 28f; gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            setPadding(24, 24, 24, 96); setShadowLayer(10f, 0f, 0f, Color.BLACK)
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(previewView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(info, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        })
        show()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    private fun show() { info.text = "$label\n$saved / $want" }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isFinishing || isDestroyed) return@addListener
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val selector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()
                val analysis = ImageAnalysis.Builder().setResolutionSelector(selector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888).build()
                val ex = Executors.newSingleThreadExecutor().also { executor = it }
                analysis.setAnalyzer(ex) { image -> try { onFrame(image) } finally { image.close() } }
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                Log.i(TAG, "[COLLECT] started label=$label want=$want every=${everyMs}ms")
            } catch (t: Throwable) {
                Log.w(TAG, "[COLLECT] camera did not start: ${t.javaClass.simpleName}: ${t.message}")
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onFrame(image: ImageProxy) {
        if (done) return
        val now = SystemClock.elapsedRealtime()
        if (lastAt == 0L) lastAt = now + 1_000L - everyMs          // a second for focus and exposure to settle
        if (now - lastAt < everyMs) return
        val yP = image.planes[0]
        val y = FrameConvert.planeBytes(yP, yBuf).also { yBuf = it }
        val q = try { QualityGate.assess(y, image.width, image.height, yP.rowStride) } catch (_: Throwable) { null }
        if (q != null && q.verdict == Verdict.BLURRY) return      // dark and bright frames are kept: they are the hard cases
        val jpeg = FrameConvert.nv21ToJpeg(FrameConvert.toNv21(image), image.width, image.height, image.imageInfo.rotationDegrees,
            maxSide = 1280, quality = 92) ?: return
        File(dir, "${System.currentTimeMillis()}.jpg").writeBytes(jpeg)
        lastAt = now
        saved++
        try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 80) } catch (_: Throwable) {}
        val finished = saved >= want
        if (finished) done = true
        runOnUiThread {
            show()
            if (finished) {
                Log.i(TAG, "[COLLECT] done label=$label saved=$saved dir=${dir.absolutePath}")
                try { tone?.startTone(ToneGenerator.TONE_PROP_ACK, 400) } catch (_: Throwable) {}
                info.postDelayed({ finish() }, 600)
            }
        }
    }

    override fun onDestroy() {
        executor?.shutdown()
        try { tone?.release() } catch (_: Throwable) {}
        super.onDestroy()
    }
}
