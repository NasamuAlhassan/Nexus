package com.tekyerema.camera.obstacle

import android.content.Context
import android.util.Log
import java.io.File

/**
 * The on-phone object detector for obstacle warnings (research note 2 Nov 2026, section B): YOLOX-Nano, ONNX Runtime, nothing leaves the phone.
 * NOT Ultralytics YOLO (AGPL). The model is OPTIONAL and not in the repository or the APK by default — [installed] says whether a copy is
 * on the phone, and the walk mode tells the user honestly when it is not.
 *
 * THE FILE THE MAIN SESSION MUST ADD (one file; nothing was downloaded here):
 *  - `yolox_nano.onnx` from the official YOLOX release assets: https://github.com/Megvii-BaseDetection/YOLOX/releases/download/0.1.1rc0/yolox_nano.onnx
 *    (Apache-2.0, repo Megvii-BaseDetection/YOLOX; ~3.7 MB; COCO, 80 classes; 0.91 M parameters, COCO mAP about 25.8 at 416 px).
 *    If that URL has moved: download `yolox_nano.pth` from the same release and run YOLOX `tools/export_onnx.py` (opset 11, NO --decode_in_inference).
 *  - Input `images` float32 [1,3,416,416], BGR, 0..255, no normalisation (see [YoloxCodec]); output float32 [1,3549,85]:
 *    cx, cy, w, h (raw grid offsets), objectness, 80 class scores (sigmoided). `obstacles.model.decoded=true` if an export already decodes.
 *  - Where: `<external files>/ondevice/obstacles/yolox_nano.onnx` (adb push to /sdcard/Android/data/com.tekyerema/files/ondevice/obstacles/),
 *    or `<files>/obstacles/yolox_nano.onnx`, or `assets/obstacles/yolox_nano.onnx` (bundled). Checked in that order.
 *  - YOLOX-Tiny (`yolox_tiny.onnx`, ~20 MB, COCO mAP 32.8, same 416 input and output format) drops in by setting `obstacles.model.file`.
 *
 * Runs on the camera analyser thread only; one session, buffers reused.
 */
class ObstacleDetector private constructor(private val session: ai.onnxruntime.OrtSession, private val cfg: ObstacleConfig.Model, val side: Int) {

    private val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
    private val input = FloatArray(3 * side * side)
    private var raw = FloatArray(YoloxCodec.anchorCount(side) * YoloxCodec.ROW)
    private val inputName: String = session.inputNames.first()
    private var letterbox: Letterbox? = null
    private var keep: ((String) -> Boolean)? = null

    /** Only these COCO classes are reported (the class map of obstacles.json). */
    fun keepClasses(names: Set<String>) { keep = { it in names } }

    /** One upright frame -> detections (0..1 of the frame). [w] x [h] is the upright size. */
    fun detect(src: PixelSource, w: Int, h: Int): List<Detection> {
        val lb = letterbox?.takeIf { it.srcW == w && it.srcH == h } ?: Letterbox(w, h, side).also { letterbox = it }
        YoloxCodec.fillInput(src, lb, cfg.bgr, input)
        val tensor = ai.onnxruntime.OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(input), longArrayOf(1, 3, side.toLong(), side.toLong()))
        try {
            session.run(mapOf(inputName to tensor)).use { out ->
                val t = out[0] as ai.onnxruntime.OnnxTensor
                val rows = YoloxCodec.anchorCount(side)
                val buf = t.floatBuffer
                if (buf.remaining() < rows * YoloxCodec.ROW) throw IllegalStateException("obstacle model output has ${buf.remaining()} floats, expected ${rows * YoloxCodec.ROW}")
                if (raw.size < rows * YoloxCodec.ROW) raw = FloatArray(rows * YoloxCodec.ROW)
                buf.get(raw, 0, rows * YoloxCodec.ROW)
                return YoloxCodec.postprocess(raw, rows, lb, cfg.decoded, cfg.scoreMin, cfg.nmsIou, cfg.maxDetections, keep)
            }
        } finally { tensor.close() }
    }

    fun close() = try { session.close() } catch (_: Throwable) {}

    companion object {
        private const val TAG = "Tekyerema"
        private const val DIR = "obstacles"

        /** Where the model may be, most likely first (same places as the other optional models: external files `ondevice/`, then app files, then assets). */
        fun files(ctx: Context, name: String): List<File> = listOfNotNull(
            ctx.getExternalFilesDir(null)?.let { File(it, "ondevice/$DIR/$name") },
            File(ctx.filesDir, "$DIR/$name"),
        )

        fun installed(ctx: Context, name: String): Boolean =
            files(ctx, name).any { it.isFile && it.length() > 0 } ||
                try { ctx.assets.list(DIR)?.contains(name) == true } catch (_: Throwable) { false }

        /** Null (and a log line saying why) when the file is missing, is not a YOLOX of the expected shape, or ONNX Runtime refuses it. */
        fun create(ctx: Context, cfg: ObstacleConfig.Model): ObstacleDetector? = try {
            val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
            val opts = ai.onnxruntime.OrtSession.SessionOptions().apply { setIntraOpNumThreads(cfg.threads.coerceIn(1, 4)) }
            val onDisk = files(ctx, cfg.file).firstOrNull { it.isFile && it.length() > 0 }
            val session = if (onDisk != null) env.createSession(onDisk.absolutePath, opts)
                else env.createSession(ctx.applicationContext.assets.open("$DIR/${cfg.file}").use { it.readBytes() }, opts)
            val shape = (session.inputInfo.values.first().info as ai.onnxruntime.TensorInfo).shape
            val side = if (shape.size == 4 && shape[2] > 0) shape[2].toInt() else cfg.side
            if (shape.size == 4 && shape[2] != shape[3] && shape[3] > 0) throw IllegalStateException("not square: ${shape.toList()}")
            Log.i(TAG, "[OBSTACLE] model ${cfg.file} loaded, input side $side")
            ObstacleDetector(session, cfg, side)
        } catch (t: Throwable) {
            Log.w(TAG, "[OBSTACLE] detector unavailable: ${t.javaClass.simpleName}: ${t.message}"); null
        }
    }
}
