package com.tekyerema.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tekyerema.core.config.AppConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * The hand-off between send_photo and [PhotoCaptureActivity]: the tool waits on [begin]'s answer, the screen gives it
 * once ([done]). One photo at a time — a new request answers any older one with nothing.
 */
object PhotoCapture {
    /** The photo, or null with [why] ("permission", "camera", "closed"). */
    data class Shot(val file: File?, val why: String? = null)

    @Volatile private var waiting: CompletableDeferred<Shot>? = null

    fun begin(): CompletableDeferred<Shot> {
        waiting?.complete(Shot(null, "replaced"))
        return CompletableDeferred<Shot>().also { waiting = it }
    }

    fun done(shot: Shot) { waiting?.complete(shot); waiting = null }
}

/**
 * Tɛkyerɛma's own camera taking ONE photo for send_photo ("take a selfie and send it to Ama on Telegram"): the chosen
 * camera, a beep countdown the user can hear, the shutter, then the screen closes and hands the file back. Taking the
 * picture ourselves (CameraX) instead of driving the phone's camera app means no gallery picker to drive afterwards and
 * no per-phone shutter hunt — the file goes straight to the chat app's share target. The JPEG lives in cache/exports
 * (the only folder the FileProvider hands out); the newest few are kept.
 */
class PhotoCaptureActivity : ComponentActivity() {
    companion object {
        private const val TAG = "Tekyerema"
        private const val EXTRA_FRONT = "front"
        private const val EXTRA_COUNTDOWN = "countdown"
        private const val KEEP = 3

        fun intent(ctx: Context, front: Boolean, countdown: Int): Intent =
            Intent(ctx, PhotoCaptureActivity::class.java).putExtra(EXTRA_FRONT, front).putExtra(EXTRA_COUNTDOWN, countdown)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private lateinit var previewView: PreviewView
    private lateinit var label: TextView
    private var answered = false

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else finishWith(PhotoCapture.Shot(null, "permission"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        previewView = PreviewView(this)
        label = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 64f; gravity = Gravity.CENTER
            setShadowLayer(12f, 0f, 0f, Color.BLACK)
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(previewView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        })
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val front = intent.getBooleanExtra(EXTRA_FRONT, false)
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isFinishing || isDestroyed) return@addListener
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                // A chat photo, not a print: ~2 MP keeps the save quick on a 2.8 GB phone and the upload small.
                val size = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(AppConfig.int("camera.sendPhoto.width", 1600),
                        AppConfig.int("camera.sendPhoto.height", 1200)), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()
                val capture = ImageCapture.Builder().setResolutionSelector(size)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                provider.bindToLifecycle(this, selector, preview, capture)
                lifecycleScope.launch { countThenShoot(capture, front) }
            } catch (t: Throwable) {
                Log.w(TAG, "[PHOTO] camera did not start: ${t.javaClass.simpleName}: ${t.message}")
                finishWith(PhotoCapture.Shot(null, "camera"))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private suspend fun countThenShoot(capture: ImageCapture, front: Boolean) {
        delay(AppConfig.long("camera.sendPhoto.settleMs", 700L))   // exposure and focus settle before the count starts
        val seconds = com.tekyerema.tools.CameraUi.countdown(intent.getIntExtra(EXTRA_COUNTDOWN, -1).takeIf { it >= 0 }, front)
        launchCountdownLabel(seconds)
        com.tekyerema.tools.TakePhotoTool.beepCountdown(seconds)
        if (isFinishing || isDestroyed) return
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        com.tekyerema.tools.ShareScreenshotTool.prune(dir, KEEP - 1, prefix = "photo-")
        val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
        previewView.display?.rotation?.let { capture.targetRotation = it }
        capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.i(TAG, "[PHOTO] taken front=$front bytes=${file.length()}")
                    finishWith(PhotoCapture.Shot(file))
                }
                override fun onError(e: ImageCaptureException) {
                    Log.w(TAG, "[PHOTO] capture failed: ${e.imageCaptureError} ${e.message}")
                    finishWith(PhotoCapture.Shot(null, "camera"))
                }
            })
    }

    /** The count on screen too, large, for a low-vision user or whoever is in the picture. */
    private fun launchCountdownLabel(seconds: Int) = lifecycleScope.launch {
        for (s in seconds downTo 1) { label.text = s.toString(); delay(1_000L) }
        label.text = ""
    }

    private fun finishWith(shot: PhotoCapture.Shot) {
        if (answered) return
        answered = true
        PhotoCapture.done(shot)
        finish()
    }

    override fun onDestroy() {
        if (!answered) { answered = true; PhotoCapture.done(PhotoCapture.Shot(null, "closed")) }
        super.onDestroy()
    }
}
