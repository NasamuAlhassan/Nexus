package com.tekyerema.voice

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
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
 * The loaded recognisers, ONE set for the whole process (audit 2026-10-01, V00): every [SherpaAsr] instance used to keep
 * its own cache, and the debug hooks, the wake monitor and the voice engine each build an instance — two instances
 * meant two copies of a 660 MB model. Now they all share one model per language, one lock, and one memory verdict.
 */
private object ResidentModels {
    // §2.5 — keep BOTH language recognizers resident where the phone can hold them (maxResidentAsrModels: 1 on a
    // 2.8 GB phone, N-08); LRU-eviction beyond that. LinkedHashMap(accessOrder) gives the LRU ordering for free.
    val cache = object : LinkedHashMap<String, OfflineRecognizer>(2, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, OfflineRecognizer>): Boolean {
            val evict = size > com.tekyerema.core.DeviceProfile.maxResidentAsrModels()   // N-08: 1 on a 2.8 GB phone
            if (evict) {
                try { eldest.value.release() } catch (_: Throwable) {}
                loaded.remove(eldest.key)
                Log.i("Tekyerema", "[ASR] LRU-evicted recognizer (${eldest.key})")
            }
            return evict
        }
    }
    /** lang → the loaded model's label. */
    val loaded = java.util.concurrent.ConcurrentHashMap<String, String>()
    val beams = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    /** lang → the loaded model's [ModelSpec.type] (padding and text clean-up differ per type). */
    val types = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** lang → the model fits only just: let it go again after each use ([AsrMemory.Decision.unloadAfterUse]). */
    val unloadAfterUse = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    /** model location → its files' total size (never changes while the app runs). */
    val sizes = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** Guards [cache] (not thread-safe) for the short, non-suspending sections. */
    val monitor = Any()
    /** N-08: model load and decode are seconds of CPU work, serialised across every instance. */
    val lock = Mutex()
}

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
 *
 * Memory (audit 2026-10-01): a model is only loaded when the phone has room for it ([admit] / [AsrMemory]); otherwise
 * [rawTranscript] throws [AsrLowMemoryException] — a sentence for the user instead of a low-memory kill.
 */
class SherpaAsr(private val appContext: Context) : SpeechToRaw {
    /** Names the models loaded so far ("sherpa-parakeet-110m-ghen(eng)"); never touches the disk (read on the main thread). */
    override val name: String
        get() = "sherpa-" + (loaded.entries.joinToString("+") { "${it.value}(${it.key})" }.ifEmpty { "on-device" })
    private val loaded get() = ResidentModels.loaded
    private val cache get() = ResidentModels.cache

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
        if (hasModel(internal)) return internal
        val external = externalRoot()?.let { File(it, "$lang/asr") }
        return if (external != null && hasModel(external)) external else internal
    }

    /** The model a directory holds: its `model.json`, else the legacy Whisper-tiny layout; null if neither. */
    private fun specOf(dir: File): ModelSpec? =
        File(dir, ModelSpec.FILE).takeIf { it.exists() }?.let { f -> try { ModelSpec.parse(f.readText()) } catch (_: Throwable) { null } }
            ?: ModelSpec.LEGACY_WHISPER.takeIf { File(dir, "tiny-encoder.onnx").exists() }

    /** Every file the directory's model needs is there. */
    private fun hasModel(dir: File): Boolean = specOf(dir)?.let { s -> s.required().all { File(dir, it).exists() } } == true

    /** The verified-ready downloaded model dir for this language, or null. */
    private fun storeDir(lang: String): File? {
        val id = if (lang == "twi") "twi-asr" else "en-asr"
        return try {
            if (com.tekyerema.models.ModelManager.isReady(id))
                com.tekyerema.models.ModelManager.filePath(id, "tiny-encoder.onnx")?.parentFile
            else null
        } catch (t: Throwable) { null }
    }
    /**
     * §2.3 — if the APK bundles this language's model under assets/models/ondevice/<lang>/asr/, extract it
     * once to the files dir (sherpa needs a path, not an asset stream) and mark it ready. No-op when nothing
     * is bundled. The marker records WHICH files were extracted (names + sizes): a new bundled model replaces the old
     * one (the old marker was "1", so a model swap would never have been extracted), and files the new model doesn't
     * use are deleted — the A20e has ~2 GB free. Idempotent.
     */
    /** The bundled model when its model.json says `"fromAssets": true` (read straight from the APK), else null.
     *  Cached as Optional: `getOrPut(null)` on a ConcurrentHashMap THROWS, so a language with no bundled model
     *  (Twi; any build without the gitignored eng assets) crashed at startup (audit 2026-09-30, half 1 #3). */
    private fun assetSpec(lang: String): ModelSpec? = assetSpecs.getOrPut(lang) {
        java.util.Optional.ofNullable(
            try {
                appContext.assets.open("models/ondevice/$lang/asr/${ModelSpec.FILE}").use { ModelSpec.parse(it.readBytes().decodeToString()) }
                    ?.takeIf { it.fromAssets && it.type != ModelSpec.WHISPER }
            } catch (_: Throwable) { null }
        )
    }.orElse(null)
    private val assetSpecs = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<ModelSpec>>()

    private fun extractBundledIfPresent(lang: String) {
        if (assetSpec(lang) != null) {
            // Read from the APK: nothing to extract, and an old extracted model (Whisper-tiny, 128 MB) is only in the way.
            File(internalRoot(), "$lang/asr").takeIf { it.exists() }?.let { d ->
                if (d.deleteRecursively()) Log.i("Tekyerema", "[ASR] removed the old extracted $lang model (the new one is read from the APK)")
            }
            return
        }
        val assetDir = "models/ondevice/$lang/asr"
        val names = try { appContext.assets.list(assetDir)?.toList().orEmpty().sorted() } catch (_: Throwable) { emptyList() }
        if (names.isEmpty()) return
        val dest = File(internalRoot(), "$lang/asr")
        val marker = File(dest, ".bundled")
        val signature = names.joinToString("\n") { n ->
            val size = try { appContext.assets.openFd("$assetDir/$n").use { it.length } } catch (_: Throwable) { -1L }
            "$n:$size"
        }
        if (marker.exists() && marker.readText() == signature) return
        try {
            dest.mkdirs()
            dest.listFiles()?.filter { it.isFile && it.name !in names }?.forEach { it.delete() }   // the previous model's files
            for (n in names) appContext.assets.open("$assetDir/$n").use { ins ->
                File(dest, n).outputStream().use { ins.copyTo(it) }
            }
            marker.writeText(signature)
            Log.i("Tekyerema", "[ASR] extracted bundled $lang model (${names.size} files) to ${dest.absolutePath}")
        } catch (t: Throwable) { Log.w("Tekyerema", "[ASR] bundled extract failed ($lang): ${t.message}") }
    }

    /** Encoder tail padding in 10 ms feature frames, or null for sherpa's default (1000). Twi keeps its 300 (§3.9). */
    private fun tailPaddingsFor(lang: String): Int? {
        val v = com.tekyerema.core.config.AppConfig.int("speech.asr.tailPaddings.$lang", if (lang == "twi") 300 else -1)
        return if (v > 0) v.coerceIn(50, 2000) else null
    }

    /**
     * Offline Twi hearing is OFF for now (owner, 3 October 2026: "let's focus on making the online Twi work first — only
     * offline English for now"). The on-device Twi model (omni CTC, docs/agent/OFFLINE_TWI.md) stays on the phone and in the
     * code; `speech.asr.offlineTwi` = true brings it back. Off: Twi is heard by the HCI Lab only.
     */
    private fun offlineTwi(): Boolean = com.tekyerema.core.config.AppConfig.bool("speech.asr.offlineTwi", false)

    /** whisper has no Twi token — the fine-tune decoded through a Latin proxy (en for English, sw else). */
    private fun tokenLang(lang: String) = if (lang == "eng") "en" else "sw"
    private fun langKey(force: String?) = if (force == Lang.TWI) "twi" else "eng"

    override val ready: Boolean
        get() = Build.SUPPORTED_ABIS.any { it.equals("arm64-v8a", true) || it.startsWith("armeabi") } &&
                (assetSpec("eng") != null || modelDir("eng")?.let(::hasModel) == true || modelDir("twi")?.let(::hasModel) == true)

    /** The on-device Twi model is trusted only when it carries its own model.json (the clean whisper-base export of
     *  2 October 2026); the legacy tiny-twi layout (trained on the GOLD clips) never is. Other languages: ready. */
    override fun trustedFor(lang: String): Boolean {
        if (lang != Lang.TWI && lang != "twi") return ready
        if (!offlineTwi()) return false
        val d = modelDir("twi") ?: return false
        return File(d, ModelSpec.FILE).exists() && hasModel(d)
    }

    /** Hotwords (contact and app names) for the next stream, when the loaded model takes them; see [ModelSpec.hotwords]. */
    @Volatile var hotwords: () -> List<String> = { emptyList() }

    /**
     * The loaded recogniser for [lang], loading it if the phone has room.
     * @throws AsrLowMemoryException when loading it now would risk the app being killed ([admit]).
     */
    private fun rec(lang: String): OfflineRecognizer? = synchronized(ResidentModels.monitor) { recLocked(lang) }

    private fun recLocked(lang: String): OfflineRecognizer? {
        cache[lang]?.let { return it }
        if (lang == "twi" && !offlineTwi()) return null   // English-only offline for now (see [offlineTwi])
        assetSpec(lang)?.let { spec -> extractBundledIfPresent(lang); admit(lang, null, spec); return recFor(lang, null, spec) }
        val d = modelDir(lang) ?: return null
        val spec = specOf(d)?.takeIf { hasModel(d) } ?: return null
        admit(lang, d, spec)
        if (spec.type != ModelSpec.WHISPER) return recFor(lang, d, spec)
        // §3.9 per-model tail padding: Twi-type models need a small trailing pad (300) so a short word like
        // "kofi" isn't truncated to "kof"; Gh-EN uses the sherpa default. The big speedup comes from NOT
        // zero-padding the waveform to 30 s (see rawTranscript) — sherpa 1.13.8 encodes the real length.
        // B-19 (2026-09-28 tail bench, phase2/voice_overnight): the Gh-EN default (1000 frames = 10 s of zero features)
        // looks wasteful but is NOT — with 600/300 this fine-tune stops finding the end of the sentence and repeats
        // itself up to sherpa's token cap, so the encoder time saved is eaten by the decoder (300: only 5-14% faster on
        // the desktop) while WER nearly doubles (owner 0.29 → 0.55, unseen Gh-EN 0.48 → 0.73; asr_tail_summary_clean.md).
        // Both stay configurable (`speech.asr.tailPaddings.<eng|twi>`) for an on-phone A/B.
        // The files and the language token come from the model's own model.json (the legacy tiny layout when there is
        // none): the clean whisper-base Twi model (2 October 2026) is twi-encoder.onnx / twi-decoder.int8.onnx, decoded
        // with auto-detect ("") — not the old tiny-twi's Swahili proxy token.
        val whisper = OfflineWhisperModelConfig(
            encoder = File(d, spec.files.getValue("encoder")).absolutePath,      // fp32 on purpose (the hybrid)
            decoder = File(d, spec.files.getValue("decoder")).absolutePath,
            language = spec.language ?: tokenLang(lang), task = "transcribe"
        ).let { w -> tailPaddingsFor(lang)?.let { w.copy(tailPaddings = it) } ?: w }
        val cfg = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                whisper = whisper,
                // 2 = the A20e's two fast (A73) cores; `speech.asr.numThreads` lets the phone try 4 (the extra threads
                // land on the slow A53s, which can make ORT's even split slower — measure before changing the default).
                tokens = File(d, spec.tokens).absolutePath, numThreads = com.tekyerema.core.config.AppConfig.int("speech.asr.numThreads", 2).coerceIn(1, 8),
                modelType = "whisper"),
            decodingMethod = "greedy_search")
        return try {
            OfflineRecognizer(config = cfg).also { cache[lang] = it; loaded[lang] = spec.label; ResidentModels.types[lang] = spec.type; Log.i("Tekyerema", "[ASR] sherpa recognizer ready ($lang)") }
        } catch (t: Throwable) {
            Log.w("Tekyerema", "[ASR] sherpa load failed ($lang): ${t.message}"); null
        }
    }

    /**
     * A non-Whisper model from its `model.json` — NVIDIA NeMo transducer (Parakeet TDT) or CTC. Chosen for offline
     * Ghanaian English by the 2026-09-30 bake-off (campaign/kaggle/offline-en-*, build/asr_bench): scored on the owner's
     * recordings and AfriSpeech's Ghanaian speakers, timed on the A20e itself.
     */
    private fun recFor(lang: String, d: File?, spec: ModelSpec): OfflineRecognizer? {
        // d == null: the model is read straight from the APK's assets (ModelSpec.fromAssets) — paths are asset paths.
        fun file(name: String) = if (d == null) "models/ondevice/$lang/asr/$name" else File(d, name).absolutePath
        fun path(role: String) = file(spec.files.getValue(role))
        val threads = com.tekyerema.core.config.AppConfig.int("speech.asr.numThreads.${spec.type}", spec.threads ?: 4).coerceIn(1, 8)
        val beam = spec.hotwords && spec.bpeVocab != null
        val model = when (spec.type) {
            ModelSpec.NEMO_TRANSDUCER -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(encoder = path("encoder"), decoder = path("decoder"), joiner = path("joiner")),
                tokens = file(spec.tokens), numThreads = threads, modelType = "nemo_transducer",
                modelingUnit = if (beam) "bpe" else "", bpeVocab = if (beam) file(spec.bpeVocab!!) else "")
            ModelSpec.NEMO_CTC -> OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = path("model")),
                tokens = file(spec.tokens), numThreads = threads)
            ModelSpec.OMNILINGUAL_CTC -> OfflineModelConfig(
                omnilingual = OfflineOmnilingualAsrCtcModelConfig(model = path("model")),
                tokens = file(spec.tokens), numThreads = threads)
            else -> { Log.w("Tekyerema", "[ASR] unknown model type '${spec.type}' in ${d?.name ?: "assets"}/model.json"); return null }
        }
        val cfg = OfflineRecognizerConfig(modelConfig = model,
            decodingMethod = if (beam) "modified_beam_search" else "greedy_search", maxActivePaths = 4,
            hotwordsScore = com.tekyerema.core.config.AppConfig.float("speech.asr.hotwordsScore", 1.5f))
        return try {
            val t0 = SystemClock.uptimeMillis()
            OfflineRecognizer(assetManager = if (d == null) appContext.assets else null, config = cfg).also {
                cache[lang] = it; loaded[lang] = spec.label; beams[lang] = beam; ResidentModels.types[lang] = spec.type
                Log.i("Tekyerema", "[ASR] sherpa ${spec.label} ready ($lang, ${spec.type}, $threads threads${if (beam) ", hotwords" else ""}) in ${SystemClock.uptimeMillis() - t0} ms")
            }
        } catch (t: Throwable) {
            Log.w("Tekyerema", "[ASR] sherpa load failed ($lang, ${spec.label}): ${t.message}"); null
        }
    }
    private val beams get() = ResidentModels.beams

    // ------------------------------------------------------------------ memory guard (audit 2026-10-01, V00/V01)

    /** The model's files, summed (0 = couldn't tell). [d] == null: read from the APK's assets. */
    private fun modelBytes(lang: String, d: File?, spec: ModelSpec): Long =
        ResidentModels.sizes.getOrPut("$lang|${d?.absolutePath ?: "assets"}|${spec.label}") {
            spec.required().sumOf { n -> if (d != null) File(d, n).length() else assetLength("models/ondevice/$lang/asr/$n") }
        }

    private fun assetLength(path: String): Long =
        try { appContext.assets.openFd(path).use { it.length } }                       // stored uncompressed (the big models)
        catch (_: Throwable) { try { appContext.assets.open(path).use { it.available().toLong() } } catch (_: Throwable) { 0L } }

    /** availMem (bytes) and the system's own "low memory" flag; a failed read never blocks a load. */
    private fun memoryNow(): Pair<Long, Boolean> = try {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        mi.availMem to mi.lowMemory
    } catch (_: Throwable) { Long.MAX_VALUE to false }

    private fun limits(fromAssets: Boolean): AsrMemory.Limits {
        val cfg = com.tekyerema.core.config.AppConfig
        val peak = if (fromAssets) cfg.double("speech.asr.loadPeakFactor.assets", AsrMemory.PEAK_FROM_ASSETS)
            else cfg.double("speech.asr.loadPeakFactor.files", AsrMemory.PEAK_FROM_FILES)
        return AsrMemory.Limits(
            peakFactor = peak.coerceIn(1.0, 4.0),
            reserveBytes = cfg.long("speech.asr.loadReserveMb", 100L).coerceAtLeast(0L) * AsrMemory.MB,
            keepFreeBytes = cfg.long("speech.asr.keepFreeMb", 400L).coerceAtLeast(0L) * AsrMemory.MB)
    }

    /**
     * May [spec] be loaded now? Loading a model the phone has no room for does not fail — Android kills the app
     * (twelve times in a row on the A20e, each taking the accessibility service down for ~30 s). So: measure first;
     * when short, release the other recogniser and every registered cache ([AsrMemory.release]) and measure again;
     * still short → [AsrLowMemoryException], whose message the user hears. `speech.asr.memoryGuard` = false is the
     * off switch. Called with [ResidentModels.monitor] held.
     */
    private fun admit(lang: String, d: File?, spec: ModelSpec) {
        if (!com.tekyerema.core.config.AppConfig.bool("speech.asr.memoryGuard", true)) return
        val bytes = modelBytes(lang, d, spec)
        val limits = limits(fromAssets = d == null)
        var (avail, low) = memoryNow()
        var decision = AsrMemory.decide(avail, low, bytes, alreadyReleased = false, limits = limits)
        if (decision.verdict == AsrMemory.Verdict.RELEASE_THEN_LOAD) {
            val others = releaseLocked { it != lang }
            val hooks = AsrMemory.release()
            try { System.gc() } catch (_: Throwable) {}
            memoryNow().let { avail = it.first; low = it.second }
            decision = AsrMemory.decide(avail, low, bytes, alreadyReleased = true, limits = limits)
            Log.i("Tekyerema", "[ASR] memory short for ${spec.label}: released $others other recogniser(s) and $hooks cache(s) → " +
                "${avail / AsrMemory.MB} MB available, ${decision.neededBytes / AsrMemory.MB} MB needed")
        }
        if (decision.verdict == AsrMemory.Verdict.REFUSE) {
            Log.w("Tekyerema", "[ASR] NOT loading ${spec.label} ($lang, ${bytes / AsrMemory.MB} MB): ${avail / AsrMemory.MB} MB available" +
                "${if (low) " (system low on memory)" else ""} < ${decision.neededBytes / AsrMemory.MB} MB needed — refused, the app would be killed")
            try { com.tekyerema.core.telemetry.Telemetry.update { voice.putIfAbsent("asrRefused", "low_memory") } } catch (_: Throwable) {}
            throw AsrLowMemoryException(avail / AsrMemory.MB, decision.neededBytes / AsrMemory.MB)
        }
        ResidentModels.unloadAfterUse[lang] = decision.unloadAfterUse
        if (decision.unloadAfterUse) Log.i("Tekyerema", "[ASR] ${spec.label} fits only just (${avail / AsrMemory.MB} MB available) — it will be unloaded after this use")
    }

    /** Release every loaded recogniser whose language passes [which]; returns how many. Monitor held by the caller. */
    private fun releaseLocked(which: (String) -> Boolean): Int {
        val go = cache.keys.filter(which)
        for (k in go) {
            cache.remove(k)?.let { try { it.release() } catch (_: Throwable) {} }
            loaded.remove(k); beams.remove(k); ResidentModels.types.remove(k); ResidentModels.unloadAfterUse.remove(k)
        }
        return go.size
    }

    /** After a decode: let a model that only just fitted go again, so the rest of the phone gets its memory back. */
    private fun unloadIfTight(lang: String) {
        if (ResidentModels.unloadAfterUse[lang] != true) return
        synchronized(ResidentModels.monitor) {
            if (releaseLocked { it == lang } > 0) Log.i("Tekyerema", "[ASR] unloaded the $lang recogniser after use (memory is tight)")
        }
    }

    /** §2.4 — preload the SESSION language off the critical path so the first request isn't slow. On the
     *  4 GB+ target the other language also stays resident (MAX_RESIDENT=2, §2.5), so a switch is instant.
     *
     *  Only with RAM to spare (A20e, 2026-09-30): the eager load on the airplane-mode flip ballooned past what the
     *  phone had free and lmkd KILLED the app ("Reclaim 'com.tekyerema' … to free 1515036kB") — a dead app is worse
     *  than a slow first offline request. Below `speech.asr.warmMinAvailMb` (default 1500) the warm is skipped and
     *  the model loads on first use instead (the turn shows Transcribing… for the load, ~15 s once). */
    suspend fun warm(sessionLanguage: String? = null) {
        if (!ready) return
        val minMb = com.tekyerema.core.config.AppConfig.long("speech.asr.warmMinAvailMb", 1500L)
        val availMb = try {
            val am = appContext.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem / (1024 * 1024)
        } catch (_: Throwable) { Long.MAX_VALUE }
        if (availMb < minMb) {
            Log.i("Tekyerema", "[ASR] warm skipped: ${availMb} MB free < $minMb MB — the model will load on first use")
            return
        }
        withContext(Dispatchers.Default) {
            lock.withLock { try { rec(langKey(sessionLanguage)) } catch (_: AsrLowMemoryException) { null } }   // a warm-up never insists
        }
    }

    /** N-08: model load and decode are seconds of CPU work — never on the caller's (often the main) thread. The
     *  recognizer cache isn't thread-safe, so warm-up and decodes are serialised — across every instance. */
    private val lock get() = ResidentModels.lock

    /** @throws AsrLowMemoryException when the model isn't loaded and the phone has no room for it right now. */
    override suspend fun rawTranscript(wav: ByteArray, forceLanguage: String?): String? =
        withContext(Dispatchers.Default) {
            lock.withLock { try { decodeLocked(wav, forceLanguage) } finally { unloadIfTight(langKey(forceLanguage)) } }
        }

    /** Drop every loaded recognizer (N-08 low-memory: onTrimMemory). They reload on the next request. */
    suspend fun releaseAll() = withContext(Dispatchers.Default) {
        lock.withLock {
            val n = synchronized(ResidentModels.monitor) { releaseLocked { true } }
            // Only when something was held: a 3 GB phone trims memory every few seconds, and this line said "released"
            // seven times in twenty seconds with nothing loaded (A20e, 13 October 2026).
            if (n > 0) Log.i("Tekyerema", "[ASR] released all recognizers (low memory)")
        }
    }

    private fun decodeLocked(wav: ByteArray, forceLanguage: String?): String? {
        val lang = langKey(forceLanguage)
        val (raw, sr) = readWav(wav) ?: return null
        // B-19: the speculative decode of this very utterance (started during the end-of-turn silence) — reuse it when
        // this audio is that snapshot plus only the silence after it.
        val sp = spec; spec = null
        if (sp != null) {
            val hit = sp.gen == specGeneration && sp.lang == lang && sp.text != null && isContinuation(raw, sp.samples, sr)
            com.tekyerema.core.telemetry.Telemetry.update { voice.putIfAbsent("asrSpeculative", if (hit) "hit" else "miss") }
            Log.i("Tekyerema", "[ASR] speculative ${if (hit) "HIT" else "miss"} (snapshot ${sp.samples.size * 1000L / sr}ms of ${raw.size * 1000L / sr}ms)")
            if (hit) return sp.text!!.ifEmpty { null }
        }
        val t0 = SystemClock.uptimeMillis()
        val out = decodeSamples(lang, raw, sr) ?: return null
        val decodeMs = SystemClock.uptimeMillis() - t0
        com.tekyerema.core.telemetry.Telemetry.update {   // B-19: numbers only
            voice.putIfAbsent("asrAudioMs", raw.size * 1000L / sr); voice.putIfAbsent("asrDecodeMs", decodeMs)
            voice.putIfAbsent("asrModel", lang)
        }
        Log.i("Tekyerema", "[ASR] sherpa-${loaded[lang] ?: "?"}($lang) ${raw.size * 1000L / sr}ms audio → ${decodeMs}ms -> '${com.tekyerema.core.Privacy.content(out.take(60))}'")   // J-15: never a PIN in logcat
        return out.ifEmpty { null }
    }

    /** One decode of [raw] (the post-processed text, "" when nothing was recognised), or null if the model can't run. */
    private fun decodeSamples(lang: String, raw: FloatArray, sr: Int): String? {
        val r = rec(lang) ?: return null
        val samples = padFor(lang, raw, sr)                       // §3.9 per-model padding (not a fixed 30 s)
        val words = if (beams[lang] == true) ModelSpec.hotwordLine(try { hotwords() } catch (_: Throwable) { emptyList() }) else ""
        val s = if (words.isNotEmpty()) r.createStream(words) else r.createStream()
        return try {
            s.acceptWaveform(samples, sr)
            r.decode(s)
            guardRepetition(trimRestart(ctcWords(lang, depua(r.getResult(s).text)).trim()))   // §3.10 / B-19 stop runaway loops and restarts
        } catch (t: Throwable) {
            Log.w("Tekyerema", "[ASR] sherpa decode failed: ${t.message}"); null
        } finally {
            try { s.release() } catch (_: Throwable) {}
        }
    }

    /** B-19: the last speculative decode (one at a time; consumed by the next real decode, hit or miss). */
    private class Spec(val lang: String, val samples: FloatArray, val text: String?, val gen: Int)
    @Volatile private var spec: Spec? = null

    /**
     * B-19 — bumped whenever the capture says its snapshot went stale ([dropSpeculation]); a speculative result is only
     * reused if nothing was dropped since its snapshot was taken. Read [specGeneration] when the snapshot is taken (on
     * the mic thread, before the decode is launched) so a drop that lands before the decode even starts still counts.
     */
    @Volatile var specGeneration = 0
        private set

    /** B-19: the user spoke again after the snapshot (or the turn ended otherwise) — never reuse that decode. */
    fun dropSpeculation() { synchronized(this) { specGeneration++ }; spec = null }

    /**
     * B-19 — decode a snapshot of the utterance while the end-of-turn silence is still being waited out (see
     * [MicCapture.onPause]). Runs under the same lock as real decodes, so the real one simply waits for it and then
     * finds the answer ready. Local only: nothing here ever touches the network.
     */
    suspend fun speculate(wav: ByteArray, forceLanguage: String?, gen: Int = specGeneration) {
        if (!ready) return
        withContext(Dispatchers.Default) {
            lock.withLock {
                val lang = langKey(forceLanguage)
                val (raw, sr) = readWav(wav) ?: return@withLock
                val t0 = SystemClock.uptimeMillis()
                if (gen != specGeneration) return@withLock                        // already stale: don't hold the recogniser
                // Speculation is a head start, never a reason to load a model the phone has no room for.
                val text = try { decodeSamples(lang, raw, sr) } catch (_: AsrLowMemoryException) { return@withLock }
                spec = Spec(lang, raw, text, gen)
                Log.i("Tekyerema", "[ASR] speculative decode ${raw.size * 1000L / sr}ms audio → ${SystemClock.uptimeMillis() - t0}ms")
            }
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

    suspend fun diagnose(wav: ByteArray, forceLanguage: String?): AsrDiag? = withContext(Dispatchers.Default) {
        lock.withLock {
            val lang = langKey(forceLanguage)
            try { diagnoseLocked(wav, lang) }
            catch (e: AsrLowMemoryException) {
                Log.w("Tekyerema", "[ASR-DIAG] not run: ${e.availMb} MB available, ${e.neededMb} MB needed to load the model"); null
            } finally { unloadIfTight(lang) }
        }
    }

    private fun diagnoseLocked(wav: ByteArray, lang: String): AsrDiag? {
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
        // The omni CTC Twi model loses a word's first sound when speech starts right at the clip's edge ("en kan" for
        // Kenkan): 0.3 s of near-silence each side brought it back on 8 more of the 72 GOLD clips, CER 0.317 → 0.305
        // (campaign/kaggle/twi-asr-diag2, 3 October 2026). A faint fixed dither, not digital zero — the measured setting.
        if (ResidentModels.types[lang] == ModelSpec.OMNILINGUAL_CTC) {
            val edge = (sr * 3) / 10
            val rnd = java.util.Random(0)
            val out = FloatArray(raw.size + 2 * edge) { (rnd.nextGaussian() * 3e-4).toFloat() }
            System.arraycopy(raw, 0, out, edge, raw.size)
            return out
        }
        return when (lang) {
            "twi" -> raw + FloatArray(sr)                         // + 1 s trailing silence
            else -> if (raw.size >= minLen) raw else raw.copyOf(minLen)
        }
    }

    /** The omnilingual CTC model writes `|` between words (its tokens.txt can't hold a space). */
    private fun ctcWords(lang: String, text: String): String =
        if (ResidentModels.types[lang] == ModelSpec.OMNILINGUAL_CTC) text.replace('|', ' ').replace(Regex("\\s+"), " ") else text

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
        /**
         * B-19 — Whisper-tiny's other decode artefact: having finished the sentence it starts it AGAIN into the padding
         * ("What time is it? What", "No, cancel. No, cancel", "That cost 12 cedis. That cost 12" — the Gh-EN model on
         * the owner's clips with a short tail pad, audit/padding_experiment). Cut at a sentence end (. ? !) when every
         * word after it repeats the transcript's own opening words; the last repeated word may be cut short ("100
         * cedis. 100 ced"). Only a repeat of the OPENING is trimmed, so "Stop. Stop the music." is left alone. Pure.
         */
        /**
         * B-19 — is [final] the speculative [snapshot] plus only what came after it (the end-of-turn silence, ≤ 2 s)?
         * MicCapture builds both with one gain, so the shared part is sample-for-sample identical. What came after must
         * also be QUIET (well under the snapshot's own level): a prefix match alone would pass "call Ama… no, Kofi" off as
         * "call Ama" whenever the recomputed gain equals the snapshot's (always, for a loud close voice). Pure.
         */
        internal fun isContinuation(final: FloatArray, snapshot: FloatArray, sr: Int): Boolean {
            if (snapshot.isEmpty() || final.size < snapshot.size || final.size - snapshot.size > 2 * sr) return false
            for (i in snapshot.indices) if (final[i] != snapshot[i]) return false
            if (final.size == snapshot.size) return true
            return rms(final, snapshot.size, final.size) <= 0.5 * rms(snapshot, 0, snapshot.size)
        }

        private fun rms(a: FloatArray, from: Int, to: Int): Double {
            if (to <= from) return 0.0
            var sum = 0.0
            for (i in from until to) sum += a[i].toDouble() * a[i]
            return Math.sqrt(sum / (to - from))
        }

        internal fun trimRestart(text: String): String {
            val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.size < 2) return text
            val cores = words.map { w -> w.lowercase().filter { it.isLetterOrDigit() || it == '\'' } }
            for (i in 1 until words.size) {
                val prev = words[i - 1]
                if (prev.last() != '.' && prev.last() != '?' && prev.last() != '!') continue
                val tail = cores.subList(i, cores.size)
                if (tail.size > i) continue                          // the "repeat" is longer than what it repeats
                val repeats = tail.withIndex().all { (j, t) ->
                    t.isNotEmpty() && (t == cores[j] || (j == tail.size - 1 && cores[j].startsWith(t)))
                }
                if (repeats) return words.subList(0, i).joinToString(" ")
            }
            return text
        }

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
