package com.tekyerema.voice

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-device ASR (§5/§8/§19) — sherpa-onnx running whisper-TINY per-language models. Gh-EN = tekyerema-ghen-
 * whisper-tiny-v1. Twi = tekyerema-whisper-tiny-twi — **DISQUALIFIED** (2026-09-19 audit: the owner's 72 GOLD
 * Twi clips were in its training set ×20, so its "Twi WER 2.2 / 1.000 entity" was measured ON TRAINING DATA;
 * NC-tainted lineage; do not ship). Kept only until the clean v2 base int8 export replaces it (Phase 9.1).
 * Fully offline, small + fast on the A20 (tiny, not base). Faithful port of the old project's OnDeviceAsr:
 *   - fp32 encoder + int8 decoder (int8 encoder + 30 s padding loops → WER 117 vs 21),
 *   - 30 s zero-pad (whisper caps at 6 tok/s; an unpadded 4 s clip truncates mid-word),
 *   - Latin-proxy language token: whisper has no Twi token, so the fine-tune trained through en/sw,
 *   - depua(): tokens.txt maps byte-BPE bytes (ɛ/ɔ/ʋ) to U+E000+byte; turn them back.
 * Models live under the app's external files dir (…/files/ondevice/<lang>/asr/), pushed/downloaded once.
 * Licence: CC BY-NC 4.0 (MMS/scripture-derived) — non-commercial.
 */
class SherpaAsr(private val appContext: Context) : SpeechToRaw {
    override val name = "sherpa-whisper-tiny(eng+twi)"

    // §2.5 — target is now a 4 GB+ RAM phone (the A20e ~600 MB ceiling is retired). Such a device holds
    // tiny-en (~273 MB) + a Twi recognizer + TTS concurrently, so keep BOTH language recognizers resident
    // (MAX_RESIDENT=2) to avoid reload latency on a language switch. LRU-eviction is still the fallback if
    // more than MAX_RESIDENT are ever loaded. LinkedHashMap(accessOrder) gives us the LRU ordering for free.
    private val cache = object : LinkedHashMap<String, OfflineRecognizer>(2, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, OfflineRecognizer>): Boolean {
            val evict = size > com.tekyerema.core.DeviceProfile.maxResidentAsrModels()   // N-08: 1 on a 2.8 GB phone
            if (evict) { try { eldest.value.release() } catch (_: Throwable) {}; Log.i("Tekyerema", "[ASR] LRU-evicted recognizer (${eldest.key})") }
            return evict
        }
    }

    // §2.2 — one place decides where a language's model lives. Bundled-in-APK assets (extracted once to the
    // files dir, since sherpa-onnx needs real file paths) are the shipping path; an adb-pushed copy under the
    // legacy external dir is the dev fallback. First existing wins.
    private fun internalRoot() = File(appContext.filesDir, "ondevice")
    private fun externalRoot() = appContext.getExternalFilesDir(null)?.let { File(it, "ondevice") }
    private fun modelDir(lang: String): File? {
        // §Part D — the SHIPPING path is a first-run download into the unified ModelStore; prefer it when the
        // package is verified-ready. (No-op today: the twi-asr/en-asr specs are NOT_HOSTED until Phase 9, so
        // ModelManager.isReady is false and we fall through to the dev paths below.)
        storeDir(lang)?.let { return it }
        extractBundledIfPresent(lang)
        val internal = File(internalRoot(), "$lang/asr")
        if (File(internal, "tiny-encoder.onnx").exists()) return internal
        val external = externalRoot()?.let { File(it, "$lang/asr") }
        return if (external != null && File(external, "tiny-encoder.onnx").exists()) external else internal
    }

    /** The verified-ready downloaded model dir for this language, or null. */
    private fun storeDir(lang: String): File? {
        val id = if (lang == "twi") "twi-asr" else "en-asr"
        return try {
            if (com.tekyerema.models.ModelManager.isReady(id))
                com.tekyerema.models.ModelManager.filePath(id, "tiny-encoder.onnx")?.parentFile
            else null
        } catch (t: Throwable) { null }
    }
    private fun encoder(lang: String) = modelDir(lang)?.let { File(it, "tiny-encoder.onnx") }

    /**
     * §2.3 — if the APK bundles this language's model under assets/models/ondevice/<lang>/asr/, extract it
     * once to the files dir (sherpa needs a path, not an asset stream) and mark it ready. No-op when nothing
     * is bundled (today's state: no shippable model exists yet — see Phase 9). Idempotent.
     */
    private fun extractBundledIfPresent(lang: String) {
        val assetDir = "models/ondevice/$lang/asr"
        val names = try { appContext.assets.list(assetDir)?.toList().orEmpty() } catch (_: Throwable) { emptyList() }
        if (names.isEmpty()) return
        val dest = File(internalRoot(), "$lang/asr")
        val marker = File(dest, ".bundled")
        if (marker.exists()) return
        try {
            dest.mkdirs()
            for (n in names) appContext.assets.open("$assetDir/$n").use { ins ->
                File(dest, n).outputStream().use { ins.copyTo(it) }
            }
            marker.writeText("1")
            Log.i("Tekyerema", "[ASR] extracted bundled $lang model (${names.size} files) to ${dest.absolutePath}")
        } catch (t: Throwable) { Log.w("Tekyerema", "[ASR] bundled extract failed ($lang): ${t.message}") }
    }

    /** whisper has no Twi token — the fine-tune decoded through a Latin proxy (en for English, sw else). */
    private fun tokenLang(lang: String) = if (lang == "eng") "en" else "sw"
    private fun langKey(force: String?) = if (force == Lang.TWI) "twi" else "eng"

    override val ready: Boolean
        get() = Build.SUPPORTED_ABIS.any { it.equals("arm64-v8a", true) || it.startsWith("armeabi") } &&
                (encoder("eng")?.exists() == true || encoder("twi")?.exists() == true)

    @Synchronized
    private fun rec(lang: String): OfflineRecognizer? {
        cache[lang]?.let { return it }
        val d = modelDir(lang) ?: return null
        if (!File(d, "tiny-encoder.onnx").exists()) return null
        // §3.9 per-model tail padding: Twi-type models need a small trailing pad (300) so a short word like
        // "kofi" isn't truncated to "kof"; Gh-EN uses the sherpa default. The big speedup comes from NOT
        // zero-padding the waveform to 30 s (see rawTranscript) — sherpa 1.13.8 encodes the real length.
        val whisper = OfflineWhisperModelConfig(
            encoder = File(d, "tiny-encoder.onnx").absolutePath,      // fp32 on purpose
            decoder = File(d, "tiny-decoder.int8.onnx").absolutePath,
            language = tokenLang(lang), task = "transcribe"
        ).let { if (lang == "twi") it.copy(tailPaddings = 300) else it }
        val cfg = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                whisper = whisper,
                tokens = File(d, "tiny-tokens.txt").absolutePath, numThreads = 2,
                modelType = "whisper"),
            decodingMethod = "greedy_search")
        return try {
            OfflineRecognizer(config = cfg).also { cache[lang] = it; Log.i("Tekyerema", "[ASR] sherpa recognizer ready ($lang)") }
        } catch (t: Throwable) {
            Log.w("Tekyerema", "[ASR] sherpa load failed ($lang): ${t.message}"); null
        }
    }

    /** §2.4 — preload the SESSION language off the critical path so the first request isn't slow. On the
     *  4 GB+ target the other language also stays resident (MAX_RESIDENT=2, §2.5), so a switch is instant. */
    suspend fun warm(sessionLanguage: String? = null) {
        if (ready) withContext(Dispatchers.Default) { lock.withLock { rec(langKey(sessionLanguage)) } }
    }

    /** N-08: model load and decode are seconds of CPU work — never on the caller's (often the main) thread. The
     *  recognizer cache isn't thread-safe, so warm-up and decodes are serialised. */
    private val lock = Mutex()

    override suspend fun rawTranscript(wav: ByteArray, forceLanguage: String?): String? =
        withContext(Dispatchers.Default) { lock.withLock { decodeLocked(wav, forceLanguage) } }

    /** Drop every loaded recognizer (N-08 low-memory: onTrimMemory). They reload on the next request. */
    suspend fun releaseAll() = withContext(Dispatchers.Default) {
        lock.withLock {
            cache.values.forEach { try { it.release() } catch (_: Throwable) {} }
            cache.clear()
            Log.i("Tekyerema", "[ASR] released all recognizers (low memory)")
        }
    }

    private fun decodeLocked(wav: ByteArray, forceLanguage: String?): String? {
        val lang = langKey(forceLanguage)
        val r = rec(lang) ?: return null
        val (raw, sr) = readWav(wav) ?: return null
        val samples = padFor(lang, raw, sr)                       // §3.9 per-model padding (not a fixed 30 s)
        val t0 = SystemClock.uptimeMillis()
        val s = r.createStream()
        return try {
            s.acceptWaveform(samples, sr)
            r.decode(s)
            val out = guardRepetition(depua(r.getResult(s).text).trim())   // §3.10 stop runaway loops
            Log.i("Tekyerema", "[ASR] sherpa-tiny($lang) ${raw.size * 1000L / sr}ms audio → ${SystemClock.uptimeMillis() - t0}ms -> '${com.tekyerema.core.Privacy.content(out.take(60))}'")   // J-15: never a PIN in logcat
            out.ifEmpty { null }
        } catch (t: Throwable) {
            Log.w("Tekyerema", "[ASR] sherpa decode failed: ${t.message}"); null
        } finally {
            try { s.release() } catch (_: Throwable) {}
        }
    }

    /**
     * DEBUG-only per-stage diagnostic (Phase 5): the SAME model path as [rawTranscript], but it also
     * captures the `sw`-proxy sherpa output BEFORE depua, the post-depua raw, and per-stage timings so a
     * device run can be compared to the clean reference. Not on any production path.
     */
    data class AsrDiag(
        val model: String, val lang: String, val audioMs: Long, val sampleRate: Int,
        val preprocessMs: Long, val inferenceMs: Long, val postMs: Long,
        val proxyRaw: String, val raw: String
    )

    fun diagnose(wav: ByteArray, forceLanguage: String?): AsrDiag? {
        val lang = langKey(forceLanguage)
        val r = rec(lang) ?: return null
        val t0 = SystemClock.uptimeMillis()
        val (raw, sr) = readWav(wav) ?: return null
        val samples = padFor(lang, raw, sr)                       // §3.9
        val audioMs = raw.size * 1000L / sr
        val t1 = SystemClock.uptimeMillis()
        val s = r.createStream()
        return try {
            s.acceptWaveform(samples, sr)
            r.decode(s)
            val t2 = SystemClock.uptimeMillis()
            val proxy = r.getResult(s).text                 // pre-depua: raw sw-proxy bytes (may hold PUA)
            val out = depua(proxy).trim()
            val t3 = SystemClock.uptimeMillis()
            AsrDiag(name, tokenLang(lang), audioMs, sr, t1 - t0, t2 - t1, t3 - t2, proxy, out)
        } catch (t: Throwable) {
            Log.w("Tekyerema", "[ASR-DIAG] decode failed: ${t.message}"); null
        } finally { try { s.release() } catch (_: Throwable) {} }
    }

    /**
     * §3.9 — per-model waveform padding. The old fixed 30 s zero-pad forced sherpa (variable-length whisper)
     * to encode a full 30 s for every clip (~6.7 s). Instead: Gh-EN feeds the real audio (min ~1 s so an
     * ultra-short clip isn't degenerate); Twi appends 1 s of real silence so the tail word isn't clipped
     * (paired with tailPaddings=300 on the recognizer). Measured desktop: 2.7×/4× faster at equal accuracy.
     */
    private fun padFor(lang: String, raw: FloatArray, sr: Int): FloatArray {
        val minLen = sr                                           // 1 s floor
        return when (lang) {
            "twi" -> raw + FloatArray(sr)                         // + 1 s trailing silence
            else -> if (raw.size >= minLen) raw else raw.copyOf(minLen)
        }
    }

    /** sherpa strips invalid UTF-8 per token; tokens.txt maps split bytes (ɛ/ɔ/ʋ) to U+E000+byte. Undo it. */
    private fun depua(text: String): String {
        if (text.none { it.code in PUA..(PUA + 0xFF) }) return text
        val out = ByteArrayOutputStream(text.length)
        for (ch in text) {
            if (ch.code in PUA..(PUA + 0xFF)) out.write(ch.code - PUA)
            else out.write(ch.toString().toByteArray(Charsets.UTF_8))
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** RIFF/WAVE PCM-16 (from MicCapture) -> mono floats + sample rate. */
    private fun readWav(b: ByteArray): Pair<FloatArray, Int>? {
        if (b.size < 44 || String(b, 0, 4) != "RIFF" || String(b, 8, 4) != "WAVE") return null
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12; var channels = 1; var sr = 16_000; var bits = 16
        var dataOff = -1; var dataLen = 0
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4); val len = bb.getInt(pos + 4)
            if (id == "fmt ") {
                channels = bb.getShort(pos + 10).toInt(); sr = bb.getInt(pos + 12)
                bits = bb.getShort(pos + 22).toInt()
            } else if (id == "data") { dataOff = pos + 8; dataLen = len; break }
            pos += 8 + len + (len and 1)
        }
        if (dataOff < 0 || bits != 16) return null
        val n = minOf(dataLen, b.size - dataOff) / 2
        val frames = n / channels
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            var acc = 0f
            for (c in 0 until channels) acc += bb.getShort(dataOff + 2 * (i * channels + c)) / 32768f
            out[i] = acc / channels
        }
        return out to sr
    }

    companion object {
        private const val PUA = 0xE000
        /** §2.5 — recognizers resident at once. 2 = keep both languages warm (4 GB+ target); the old value
         *  1 was the A20e ~600 MB budget. Lower to 1 only if a device reports severe memory pressure. */
        private const val MAX_RESIDENT = 2

        /**
         * §3.10 — repetition/length guard. Short waveform tails make the tiny models loop ("Coffee. Coffee.
         * Coffee." / "wo wo wo …"). Collapse an immediately-repeating trailing token run (≥3×) to a single
         * instance and cap absurd length, so a decode artefact can't become a spoken stutter. Pure → tested.
         */
        internal fun guardRepetition(text: String): String {
            if (text.isBlank()) return text
            val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.size < 4) return text
            val norm = words.map { it.lowercase().trim('.', ',', '!', '?') }
            var end = words.size
            val runVal = norm.last(); var run = 1
            for (i in words.size - 2 downTo 0) {
                if (norm[i] == runVal) { run++; if (run >= 3) end = i + 1 } else break
            }
            var kept = if (end < words.size) words.subList(0, end.coerceAtLeast(1)) else words
            if (kept.size > 60) kept = kept.subList(0, 60)
            return kept.joinToString(" ")
        }
    }
}
