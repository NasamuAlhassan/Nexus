package com.tekyerema.language

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.tekyerema.core.config.AppConfig
import com.tekyerema.voice.Lang
import com.tekyerema.voice.TrimPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The offline English -> Twi translator as a [TranslationProvider]: the student model ([StudentTranslator]) used ONLY when the
 * phone has no internet (the online route is the Space; see LanguageLayer.translator). The files are int8 graphs plus the
 * embedding table (~215 MB): put in assets/models/ondevice/twi/mt/ (copied once to the files dir) or pushed to
 * <external files>/ondevice/twi/mt/. Loaded on the first offline line, let go after [idleMs] unused and under memory pressure
 * (a 3 GB phone cannot keep it beside the voices). Switch: `providers.speech.mt.offlineStudent` (default true).
 * A null answer means "speak the English / use the fixed Twi lines" — ReplyLocalizer checks the tags and falls back.
 */
object StudentMt : TranslationProvider {
    override val name = "student_mt"
    private const val SUB = "ondevice/twi/mt"
    private val FILES = listOf("encoder.onnx", "decoder.onnx", "table_q8.bin", "scales_f32.bin")

    private lateinit var app: Context
    private val lock = Mutex()
    @Volatile private var mt: StudentTranslator? = null
    @Volatile private var tok: StudentTokenizer? = null
    @Volatile private var lastUsedAt = 0L
    private val scope = CoroutineScope(Dispatchers.Default)
    private var idleJob: Job? = null

    fun init(context: Context) { app = context.applicationContext }

    /** This provider when the switch is on and the model files are present, else null. */
    fun providerOrNull(): TranslationProvider? =
        if (::app.isInitialized && AppConfig.bool("providers.speech.mt.offlineStudent", true) && modelDir() != null) this else null

    /** The folder holding all four files: files dir, external files dir, else extracted from the APK's assets. */
    private fun modelDir(): File? {
        val roots = listOfNotNull(File(app.filesDir, SUB), app.getExternalFilesDir(null)?.let { File(it, SUB) })
        roots.firstOrNull { d -> FILES.all { File(d, it).length() > 0 } }?.let { return it }
        val assetDir = "models/$SUB"
        if (try { app.assets.list(assetDir)?.toSet()?.containsAll(FILES) != true } catch (_: Throwable) { true }) return null
        val out = roots[0].also { it.mkdirs() }
        return try {
            for (f in FILES) app.assets.open("$assetDir/$f").use { i -> File(out, f + ".part").outputStream().use { o -> i.copyTo(o) } }
            for (f in FILES) File(out, "$f.part").renameTo(File(out, f))
            out
        } catch (e: Throwable) { Log.w("Tekyerema", "[MT] student model not extracted: ${e.message}"); null }
    }

    override suspend fun translate(text: String, from: String, to: String): String? {
        if (from != Lang.GH_EN || to != Lang.TWI || text.isBlank()) return null
        return withContext(Dispatchers.Default) {
            lock.withLock {
                try {
                    val t = ensure() ?: return@withLock null
                    val t0 = SystemClock.uptimeMillis()
                    val out = t.translate(text)
                    lastUsedAt = SystemClock.uptimeMillis()
                    scheduleIdleRelease()
                    Log.i("Tekyerema", "[MT] student ${lastUsedAt - t0} ms: \"${text.take(60)}\" -> ${if (out == null) "nothing" else "\"${out.take(60)}\""}")
                    out
                } catch (e: Throwable) { Log.w("Tekyerema", "[MT] student failed: $e"); null }
            }
        }
    }

    /** A minute after the last offline line the model goes (the phone is usually online again by then). */
    private fun scheduleIdleRelease() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(AppConfig.long("providers.speech.mt.offlineStudentIdleMs", 60_000L))
            release(); Log.i("Tekyerema", "[MT] student model released (idle)")
        }
    }

    private fun ensure(): StudentTranslator? {
        mt?.let { return it }
        val dir = modelDir() ?: return null
        val tokenizer = tok ?: StudentTokenizer(StudentTokenizer.parseTsv(
            app.assets.open("mt/student/bpe_merges.tsv").bufferedReader().lineSequence())).also { tok = it }
        return StudentTranslator(dir, tokenizer, threads = 2, prepacking = true).also { mt = it; lastUsedAt = SystemClock.uptimeMillis() }
    }

    /** Let the model and tokenizer table go (about 200 MB and 30 MB); the next offline line loads them again (~5 s). */
    suspend fun release() = lock.withLock { mt?.close(); mt = null; tok = null }

    fun onTrimMemory(level: Int) {
        if (mt == null || lock.isLocked) return
        val idle = SystemClock.uptimeMillis() - lastUsedAt
        if (TrimPolicy.shouldRelease(level, idle, AppConfig.long("speech.trim.graceMs", 90_000L)))
            scope.launch { release(); Log.i("Tekyerema", "[MT] student model released (low memory)") }
    }
}
