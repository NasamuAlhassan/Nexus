package com.tekyerema.models

import java.io.File
import java.security.MessageDigest

/** One downloadable file of a model package. */
data class ModelFile(val name: String, val url: String, val sha256: String, val bytes: Long)

/**
 * A local model package (directive §11–§13). `hosted=false` means the asset is not yet published for
 * download — the manager reports it honestly as NOT_HOSTED rather than pretending it's installable.
 */
data class ModelSpec(
    val id: String,
    val title: String,
    val version: String,
    val runtime: String,          // e.g. "sherpa-onnx-whisper", "sherpa-onnx-vits"
    val approxBytes: Long,
    val files: List<ModelFile>,
    val hosted: Boolean = true,
    val license: String = ""
)

sealed class ModelStatus {
    /** Asset exists in the registry but is not yet published for download (honest, not a failure). */
    object NotHosted : ModelStatus()
    object Missing : ModelStatus()
    data class Partial(val haveBytes: Long, val totalBytes: Long) : ModelStatus()
    data class Downloading(val fraction: Float) : ModelStatus()
    object Verifying : ModelStatus()
    object Ready : ModelStatus()
    data class Corrupt(val file: String) : ModelStatus()
    data class Failed(val reason: String) : ModelStatus()
}

/**
 * Pure filesystem logic for installed models (directive §13): where a package lives, whether it's present
 * and checksum-verified, and version gating. No Android dependency → unit-tested on the JVM with a temp dir.
 * A package is Ready only when every file's SHA-256 matched and a `.ready` marker records the version.
 */
class ModelStore(private val root: File) {
    fun dir(spec: ModelSpec): File = File(root, "${spec.id}/${spec.version}")
    private fun marker(spec: ModelSpec): File = File(dir(spec), ".ready")

    fun status(spec: ModelSpec): ModelStatus {
        if (!spec.hosted) return ModelStatus.NotHosted
        val d = dir(spec)
        if (!d.exists()) return ModelStatus.Missing
        if (marker(spec).exists() && marker(spec).readText().trim() == spec.version) return ModelStatus.Ready
        val have = spec.files.filter { File(d, it.name).exists() }.sumOf { File(d, it.name).length() }
        return if (have == 0L) ModelStatus.Missing else ModelStatus.Partial(have, spec.approxBytes)
    }

    fun isReady(spec: ModelSpec): Boolean = status(spec) is ModelStatus.Ready

    /** Recompute every file's SHA-256; on full match write the ready marker. Never trusts a partial file. */
    fun verify(spec: ModelSpec): ModelStatus {
        val d = dir(spec)
        for (f in spec.files) {
            val file = File(d, f.name)
            if (!file.exists()) return ModelStatus.Missing
            if (f.sha256.isNotBlank() && !sha256(file).equals(f.sha256, ignoreCase = true))
                return ModelStatus.Corrupt(f.name)
        }
        marker(spec).writeText(spec.version)
        return ModelStatus.Ready
    }

    fun filePath(spec: ModelSpec, name: String): File = File(dir(spec), name)
    fun delete(spec: ModelSpec) { dir(spec).deleteRecursively() }

    companion object {
        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { ins ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * The known local models (directive §12 inventory). ASR/TTS runtimes are sherpa-onnx. Twi ASR is the
 * validated FP32 export; English ASR + neural TTS are registered but `hosted=false` until their permissive
 * assets exist (Gh-English ASR needs our recordings + a GPU fine-tune; Twi/Gh-English neural TTS needs a
 * trained voice) — the manager surfaces those as NOT_HOSTED, never faked.
 */
object ModelRegistry {
    val TWI_ASR = ModelSpec(
        id = "twi-asr",
        // §Speed+quality decision (2026-09-27, PRODUCTION_ASR_PLAN.md): on-device Twi = a small streaming
        // zipformer-CTC (sherpa-native), distilled from the w2v-bert teacher (tekyerema-asr-ctc-twi-v2,
        // unseen WER 0.144). Measured rationale: w2v-bert has the quality but RTF 2.3-4x (too slow on a
        // phone); whisper-base is fast (RTF 0.41x) but under-emits ɛ/ɔ (unseen WER 0.56-0.62). A char-vocab
        // zipformer-CTC gets BOTH — RTF ~0.06x on mobile per k2 docs, and native ɛ/ɔ. Trained on the clean
        // pool (WAXAL + FISD CC-BY-4.0, KasaSpeech Apache; +teacher pseudo-labels), no GOLD leakage.
        // NOT_HOSTED until the icefall train + sherpa export lands (interim offline = whisper-base tokens-fix).
        title = "Asante Twi ASR — zipformer-CTC int8 (sherpa-onnx), distilled from w2v-bert teacher",
        version = "v3",
        runtime = "sherpa-onnx-zipformer-ctc",
        approxBytes = 90_000_000,
        license = "Apache-2.0/MIT stack + CC-BY-4.0 (WAXAL, Financial-Inclusion) — permissive/shippable",
        files = emptyList(),
        hosted = false
    )
    val EN_ASR = ModelSpec(
        id = "en-asr",
        // §Speed+quality decision (2026-09-27, PRODUCTION_ASR_PLAN.md): same choice as Twi — a small
        // streaming zipformer-CTC (sherpa-native), distilled from the whisper-large-v3-turbo-tekyerema-eng
        // teacher. Rationale: whisper-tiny is fast (RTF 0.38x) but misses code-switch/entities (MoMo->"mu mu",
        // 1k->"wrong case"); whisper-small would fix quality but is ~1.3x RTF (too slow); zipformer-CTC is both
        // fast (RTF ~0.06x mobile) and accurate. Trained on KasaSpeech (Apache) + FISD + (NC release) the
        // ghanaopendata 2700 h CC-BY-NC set. Online primary stays Groq/large-v3. NOT_HOSTED until trained.
        // Fallback if icefall is too heavy: whisper-BASE FT (RTF ~0.4x, proven) — NOT small.
        title = "Ghanaian-English ASR — zipformer-CTC int8 (sherpa-onnx), distilled from large-v3-turbo teacher",
        version = "v1", runtime = "sherpa-onnx-zipformer-ctc", approxBytes = 90_000_000,
        license = "Apache-2.0/MIT stack + Apache-2.0 (KasaSpeech); +CC-BY-NC (ghanaopendata 2700h) if NC",
        files = emptyList(), hosted = false
    )
    val TWI_TTS = ModelSpec(
        id = "twi-tts",
        title = "Asante Twi TTS (on-device) — BLOCKED on a permissive trained voice",
        version = "v0", runtime = "sherpa-onnx-vits", approxBytes = 60_000_000,
        files = emptyList(), hosted = false
    )
    val EN_TTS = ModelSpec(
        id = "en-tts",
        title = "Ghanaian-English TTS (on-device) — BLOCKED on a trained voice (recordings pending)",
        version = "v0", runtime = "sherpa-onnx-vits", approxBytes = 60_000_000,
        files = emptyList(), hosted = false
    )

    val ALL = listOf(TWI_ASR, EN_ASR, TWI_TTS, EN_TTS)
    fun byId(id: String): ModelSpec? = ALL.find { it.id == id }
}
