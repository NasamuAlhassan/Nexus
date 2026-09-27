package com.tekyerema.models

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Central on-device model manager (directive §11/§13/§14). Knows what's installed, downloads missing
 * packages with resume + SHA-256 verification, and exposes readiness so the voice stack can prefer a
 * local model when present and degrade honestly when it isn't. Never crashes on a missing model —
 * callers check [isReady] first.
 *
 * "Offline after model installation" vs "offline from a fresh install": a fresh install ships NO large
 * models; the first-run flow downloads them once, then the stack is fully offline. This distinction is
 * documented for the user (§11) and surfaced as [ModelStatus].
 */
object ModelManager {
    private lateinit var store: ModelStore
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()

    fun init(context: Context) {
        store = ModelStore(File(context.applicationContext.filesDir, "models"))
    }

    fun status(id: String): ModelStatus =
        ModelRegistry.byId(id)?.let { if (::store.isInitialized) store.status(it) else ModelStatus.Missing }
            ?: ModelStatus.Failed("unknown model '$id'")

    fun isReady(id: String): Boolean =
        ::store.isInitialized && ModelRegistry.byId(id)?.let { store.isReady(it) } == true

    /** Absolute path to a ready model file for the runtime (e.g. the sherpa encoder). Null if not ready. */
    fun filePath(id: String, name: String): File? {
        val spec = ModelRegistry.byId(id) ?: return null
        if (!store.isReady(spec)) return null
        return store.filePath(spec, name)
    }

    /** Download + verify a package. Idempotent: an already-verified package returns Ready immediately. */
    suspend fun install(id: String, onProgress: (ModelStatus) -> Unit = {}): ModelStatus = withContext(Dispatchers.IO) {
        val spec = ModelRegistry.byId(id) ?: return@withContext ModelStatus.Failed("unknown model '$id'")
        if (!spec.hosted || spec.files.isEmpty()) return@withContext ModelStatus.NotHosted
        if (store.isReady(spec)) return@withContext ModelStatus.Ready
        val dir = store.dir(spec).apply { mkdirs() }
        try {
            val total = spec.files.sumOf { it.bytes }.coerceAtLeast(1)
            var done = 0L
            for (f in spec.files) {
                val out = File(dir, f.name)
                if (out.exists() && (f.sha256.isBlank() || ModelStore.sha256(out).equals(f.sha256, true))) {
                    done += f.bytes; continue                       // already have this file, verified
                }
                onProgress(ModelStatus.Downloading(done.toFloat() / total))
                // Resume: if a partial download exists, ask the server to continue from where we stopped
                // (HTTP Range). A server that ignores Range answers 200 (full body) → we restart cleanly.
                val part = File(dir, f.name + ".part")
                val have = if (part.exists()) part.length() else 0L
                val reqB = Request.Builder().url(f.url)
                if (have > 0) reqB.header("Range", "bytes=$have-")
                http.newCall(reqB.build()).execute().use { resp ->
                    if (!resp.isSuccessful && resp.code != 206) return@withContext ModelStatus.Failed("HTTP ${resp.code} for ${f.name}")
                    val body = resp.body ?: return@withContext ModelStatus.Failed("empty body ${f.name}")
                    val append = resp.code == 206                    // server honoured Range → append; else overwrite
                    if (!append && part.exists()) part.delete()
                    java.io.FileOutputStream(part, append).use { os -> body.byteStream().copyTo(os, 1 shl 16) }
                }
                // Per-file integrity BEFORE promoting .part → final; a corrupt/short resume must never stick.
                if (f.sha256.isNotBlank() && !ModelStore.sha256(part).equals(f.sha256, ignoreCase = true)) {
                    part.delete()
                    return@withContext ModelStatus.Corrupt(f.name)
                }
                if (!part.renameTo(out)) { part.copyTo(out, overwrite = true); part.delete() }
                done += f.bytes
                onProgress(ModelStatus.Downloading(done.toFloat() / total))
            }
            onProgress(ModelStatus.Verifying)
            val v = store.verify(spec)
            if (v !is ModelStatus.Ready) { Log.w("Tekyerema", "[Model] verify failed: $v") }
            onProgress(v); v
        } catch (t: Throwable) {
            Log.w("Tekyerema", "[Model] install ${spec.id} failed: ${t.message}")
            ModelStatus.Failed(t.message ?: "download error")
        }
    }

    fun delete(id: String) { ModelRegistry.byId(id)?.let { store.delete(it) } }
}
