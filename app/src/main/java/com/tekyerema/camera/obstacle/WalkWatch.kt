package com.tekyerema.camera.obstacle

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.tekyerema.core.Haptics
import com.tekyerema.core.config.AppConfig
import kotlin.math.sqrt

/**
 * "Walk with me": the camera reader's obstacle mode (CameraReadActivity.MODE_WALK). Takes the analyser's frames, runs the detector on
 * them at a throttled rate, feeds [ObstacleEngine], and vibrates (first) and speaks (second, rate-limited by the engine).
 *
 * A SUPPLEMENT to the cane. It never says the way is clear: the activity says "I may miss things" at the start and "stopped watching" at
 * the end, and this class reports a blind camera (dark or flat view) or a model that stops working instead of staying quiet.
 *
 * Analyser thread only (except [Sink] callbacks, which the activity posts to its main thread). Caller closes the ImageProxy.
 */
class WalkWatch(private val ctx: Context, val cfg: ObstacleConfig, private val sink: Sink) {

    interface Sink {
        /** A short warning line, ready to say (flush whatever is being said: a hazard is newer than a status line). */
        fun speak(line: String)
        /** The watch has stopped and the user must be told (blind camera, model failing): the activity says the line and leaves. */
        fun stopped(line: String)
    }

    private val lock = Any()
    private val engine = ObstacleEngine(cfg)
    private val blind = BlockedWatch(cfg.watch)
    private var detector: ObstacleDetector? = null
    private var tried = false
    @Volatile private var done = false
    private var failures = 0
    private var lastBeatAt = SystemClock.elapsedRealtime()
    private var frames = 0
    private var inferMs = 0L

    /** How long the activity should wait before handing over the next frame. */
    fun intervalMs(now: Long): Long = WalkPacing.intervalMs(now, engine.lastHazardAt, cfg.fps)

    /** Stops the watch and frees the model. Waits for a frame in flight (the session must not close mid-run), so at most one frame's time. */
    fun close() { done = true; synchronized(lock) { detector?.close(); detector = null } }

    fun analyse(image: ImageProxy) = synchronized(lock) { analyseLocked(image) }

    private fun analyseLocked(image: ImageProxy) {
        if (done) return
        val now = SystemClock.elapsedRealtime()
        if (!tried) {
            tried = true
            detector = ObstacleDetector.create(ctx, cfg.model)?.also { it.keepClasses(cfg.classes.keys) }
        }
        val det = detector ?: run { done = true; sink.stopped(cfg.phrases.loadFailed); return }

        val rot = image.imageInfo.rotationDegrees
        val (uw, uh) = FrameRotation.uprightSize(image.width, image.height, rot)

        val (mean, std) = lumaStats(image)
        if (blind.update(now, blind.isBlindFrame(mean, std))) { done = true; sink.stopped(cfg.phrases.blocked); return }
        if (blind.isBlindFrame(mean, std)) return     // a dark or flat frame says nothing; wait for the window above

        val dets = try {
            val t0 = SystemClock.elapsedRealtime()
            det.detect(YuvPixels(image, rot, uw, uh), uw, uh).also { inferMs = SystemClock.elapsedRealtime() - t0; failures = 0 }
        } catch (t: Throwable) {
            Log.w(TAG, "[OBSTACLE] frame failed: ${t.javaClass.simpleName}: ${t.message}")
            // A watch that quietly fails is worse than none: after a few bad frames in a row, say so and stop.
            if (++failures >= MAX_FAILURES) { done = true; sink.stopped(cfg.phrases.loadFailed) }
            return
        }
        val result = engine.update(now, dets)
        frames++
        if (com.tekyerema.BuildConfig.DEV_HOOKS && frames % 20 == 0) Log.d(TAG, "[OBSTACLE] frames=$frames dets=${dets.size} hazards=${result.hazards.size} infer=${inferMs} ms")
        result.warning?.let { w ->
            w.haptic?.let { Haptics.waveform(ctx, it) }
            w.phrase?.let { line -> sink.speak(line) }
            lastBeatAt = now
        }
        val beat = cfg.watch.heartbeatMs
        if (beat > 0 && now - lastBeatAt >= beat) { lastBeatAt = now; Haptics.waveform(ctx, HEARTBEAT) }
    }

    /** Mean and spread of the frame's brightness from a sparse grid of the Y plane (a few hundred reads). */
    private fun lumaStats(image: ImageProxy): Pair<Float, Float> {
        val p = image.planes[0]
        val buf = p.buffer
        val row = p.rowStride
        val pix = p.pixelStride
        var n = 0; var sum = 0.0; var sum2 = 0.0
        var y = 4
        while (y < image.height) {
            var x = 4
            while (x < image.width) {
                val v = (buf.get(y * row + x * pix).toInt() and 0xFF).toDouble()
                sum += v; sum2 += v * v; n++
                x += 16
            }
            y += 16
        }
        if (n == 0) return 0f to 0f
        val mean = sum / n
        return mean.toFloat() to sqrt((sum2 / n - mean * mean).coerceAtLeast(0.0)).toFloat()
    }

    /** The YUV_420_888 frame as upright RGB (BT.601, integer maths). */
    private class YuvPixels(image: ImageProxy, private val rotation: Int, private val uw: Int, private val uh: Int) : PixelSource {
        private val yB = image.planes[0].buffer
        private val uB = image.planes[1].buffer
        private val vB = image.planes[2].buffer
        private val yRow = image.planes[0].rowStride
        private val yPix = image.planes[0].pixelStride
        private val uRow = image.planes[1].rowStride
        private val uPix = image.planes[1].pixelStride
        private val vRow = image.planes[2].rowStride
        private val vPix = image.planes[2].pixelStride
        private val xy = IntArray(2)

        override fun rgb(x: Int, y: Int): Int {
            FrameRotation.toSensor(x, y, uw, uh, rotation, xy)
            val sx = xy[0]; val sy = xy[1]
            val yy = yB.get(sy * yRow + sx * yPix).toInt() and 0xFF
            val u = (uB.get((sy shr 1) * uRow + (sx shr 1) * uPix).toInt() and 0xFF) - 128
            val v = (vB.get((sy shr 1) * vRow + (sx shr 1) * vPix).toInt() and 0xFF) - 128
            val r = (yy + ((1436 * v) shr 10)).coerceIn(0, 255)
            val g = (yy - ((352 * u + 731 * v) shr 10)).coerceIn(0, 255)
            val b = (yy + ((1814 * u) shr 10)).coerceIn(0, 255)
            return (r shl 16) or (g shl 8) or b
        }
    }

    companion object {
        private const val TAG = "Tekyerema"
        private const val MAX_FAILURES = 5
        private val HEARTBEAT = longArrayOf(0, 15)

        /** `obstacles.*` from the merged app config (the defaults when the file is absent). */
        fun config(): ObstacleConfig = ObstacleConfig.fromJson(AppConfig.obj("obstacles"))

        fun enabled(): Boolean = AppConfig.bool("obstacles.enabled", false)
    }
}
