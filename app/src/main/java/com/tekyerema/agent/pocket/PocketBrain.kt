package com.tekyerema.agent.pocket

import android.content.Context
import android.util.Log
import com.tekyerema.agent.AgentBrain
import com.tekyerema.agent.AgentDecision
import com.tekyerema.agent.BrainContext
import com.tekyerema.agent.LocalBrain
import com.tekyerema.core.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What the router asks: a decision for the FIRST step of a request, or null (disabled / model absent / not a first step / failure). */
interface PocketProposer {
    suspend fun propose(ctx: BrainContext): AgentDecision?
}

/**
 * The pocket brain: a small int8 ONNX encoder (MiniLM-L6 / bge-small + heads) that understands one spoken request OFFLINE and
 * proposes ONE first action - a tool call, a question, or an honest "can't". See docs/agent/research/pocket_brain_deep_2026-11-03.md.
 *
 * Files (names in router.json -> router.pocket.*): model.onnx, spec.json, tokenizer.json (or vocab.txt), looked up first in
 * <filesDir>/<dir>/ (adb push, no rebuild) and then in assets/<dir>/. Any file missing -> the brain is disabled and [propose]
 * returns null, so nothing changes. Loaded on first use, kept for the process; a failed load is retried after a minute.
 *
 * Only the first step of a task (empty history): later steps return null and the existing local flow carries on.
 * The log line carries tool / status / probability / milliseconds, never the request (release builds: no request text anywhere).
 */
class PocketBrain private constructor(private val files: Files, private val cfg: Cfg) : PocketProposer, AgentBrain {

    class Cfg(val tauRisk: Float?, val tauOther: Float?)
    /** Where to read each file from; exactly one of [file] / [asset] is set per entry. */
    class Source(val file: File?, val asset: String?)
    class Files(val ctx: Context, val model: Source, val spec: Source, val vocab: Source, val vocabIsJson: Boolean)

    private val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
    private val session: ai.onnxruntime.OrtSession
    private val spec: PocketSpec
    private val tokenizer: PocketTokenizer
    private val decoder: PocketDecoder
    private val outIndex: Map<String, Int>

    init {
        spec = PocketSpec.parse(read(files.spec).toString(Charsets.UTF_8))
        tokenizer = PocketTokenizer(PocketTokenizer.loadVocab(read(files.vocab).toString(Charsets.UTF_8), files.vocabIsJson), spec)
        decoder = PocketDecoder(spec, cfg.tauRisk, cfg.tauOther)
        val opts = ai.onnxruntime.OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
        session = files.model.file?.let { env.createSession(it.absolutePath, opts) } ?: env.createSession(read(files.model), opts)
        val names = session.outputNames.toList()
        outIndex = spec.outputNames.associateWith { names.indexOf(it) }
        require(outIndex.values.all { it >= 0 }) { "model outputs $names do not match the spec" }
    }

    private fun read(s: Source): ByteArray = s.file?.readBytes() ?: files.ctx.assets.open(s.asset!!).use { it.readBytes() }

    override suspend fun propose(ctx: BrainContext): AgentDecision? {
        if (ctx.history.isNotEmpty()) return null           // first step only
        val request = ctx.goal.trim()
        if (request.isEmpty()) return null
        return withContext(Dispatchers.Default) {
            try {
                val t0 = System.nanoTime()
                val enc = tokenizer.encode(request, tokenizer.contextText(ctx.conversation))
                val out = run(enc)
                val r = decoder.decode(request.take(spec.requestCharCap), enc, out)
                Log.i("Tekyerema", "[POCKET] tool=${r.tool} status=${r.status} p=${"%.2f".format(r.p)} ms=${(System.nanoTime() - t0) / 1_000_000}")
                r.decision
            } catch (t: Throwable) {
                Log.w("Tekyerema", "[POCKET] failed: ${t.javaClass.simpleName}")
                null
            }
        }
    }

    /** AgentBrain face: the same proposal, or a DEFER ("no rule matched") when there is none. */
    override suspend fun decide(ctx: BrainContext): AgentDecision =
        propose(ctx) ?: AgentDecision.CannotDo(LocalBrain.DEFER, "the pocket brain has no decision")

    private fun run(enc: PocketEncoding): PocketOutputs {
        val shape = longArrayOf(1, enc.size.toLong())
        val ids = ai.onnxruntime.OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(LongArray(enc.size) { enc.ids[it].toLong() }), shape)
        val mask = try {
            ai.onnxruntime.OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(LongArray(enc.size) { 1L }), shape)
        } catch (t: Throwable) { ids.close(); throw t }
        try {
            session.run(mapOf("input_ids" to ids, "attention_mask" to mask)).use { res ->
                @Suppress("UNCHECKED_CAST")
                fun vec(name: String): FloatArray = (res[outIndex.getValue(name)].value as Array<FloatArray>)[0]
                @Suppress("UNCHECKED_CAST")
                val tag = (res[outIndex.getValue("tag_logits")].value as Array<Array<Array<FloatArray>>>)[0]   // [t][nArg][3]
                val flat = Array(tag.size) { j -> FloatArray(tag[j].size * 3).also { f -> for (a in tag[j].indices) for (c in 0 until 3) f[a * 3 + c] = tag[j][a][c] } }
                return PocketOutputs(vec("tool_logits"), vec("status_logits"), vec("missing_logits"), vec("slot_logits"), flat)
            }
        } finally { ids.close(); mask.close() }
    }

    companion object {
        @Volatile private var instance: PocketBrain? = null
        @Volatile private var failedAt = 0L

        /** The pocket brain for the router, or null when switched off. Cheap and never throws: the app context, the files and the
         *  model are only looked for (and loaded) on the first [PocketProposer.propose]; absent files -> propose() returns null. */
        fun ifEnabled(): PocketProposer? = if (AppConfig.bool("router.pocket.enabled", true)) Shared else null

        private fun src(ctx: Context, dir: String, name: String): Source? {
            val f = File(File(ctx.filesDir, dir), name)
            if (f.isFile) return Source(f, null)
            val a = "$dir/$name"
            return try { ctx.assets.open(a).close(); Source(null, a) } catch (_: Throwable) { null }
        }

        internal fun locate(ctx: Context): Files? {
            val dir = AppConfig.string("router.pocket.dir", "pocket")
            val model = src(ctx, dir, AppConfig.string("router.pocket.model", "model.onnx")) ?: return null
            val spec = src(ctx, dir, AppConfig.string("router.pocket.spec", "spec.json")) ?: return null
            val vocabName = AppConfig.string("router.pocket.vocab", "tokenizer.json")
            val vocab = src(ctx, dir, vocabName) ?: return null
            return Files(ctx, model, spec, vocab, vocabName.endsWith(".json"))
        }

        private fun cfg(): Cfg = Cfg(
            AppConfig.double("router.pocket.tauRisk", -1.0).takeIf { it >= 0 }?.toFloat(),
            AppConfig.double("router.pocket.tauOther", -1.0).takeIf { it >= 0 }?.toFloat())

        private fun get(files: Files): PocketBrain? {
            instance?.let { return it }
            if (failedAt != 0L && System.currentTimeMillis() - failedAt < 60_000L) return null
            return synchronized(this) {
                instance ?: try { PocketBrain(files, cfg()).also { instance = it; failedAt = 0L } }
                catch (t: Throwable) { failedAt = System.currentTimeMillis(); Log.w("Tekyerema", "[POCKET] unavailable: ${t.message}"); null }
            }
        }

        /** Hands out the shared instance, loading it on first use. */
        private object Shared : PocketProposer {
            override suspend fun propose(ctx: BrainContext): AgentDecision? {
                if (ctx.history.isNotEmpty()) return null
                return withContext(Dispatchers.Default) {
                    val app = try { com.tekyerema.agent.AgentEngine.appContext() } catch (_: Throwable) { null }
                    val files = app?.let { locate(it) }
                    files?.let { get(it) }
                }?.propose(ctx)
            }
        }
    }
}
