package com.tekyerema.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.tekyerema.core.config.AppConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * The parts of the on-device voice that need no phone: what a bundled voice directory holds (its `voice.json`), how
 * this voice is told to say our own words, and how a reply is cut into pieces small enough to start speaking fast.
 * Pure → unit-tested.
 *
 * Why (owner, 2 October 2026): the phone's nearest English voice is Nigerian (Google en-NG). It "does not pronounce our
 * app name properly" and "struggles to say MTN". No platform ships a Ghanaian English voice, so the app carries its
 * own: a Piper (VITS) voice trained on a Ghanaian news anchor, run by the sherpa-onnx runtime already in the APK.
 *
 * ```
 * {"type": "vits-piper", "label": "en_GH-anchorf1", "model": "en_GH-anchorf1-medium.onnx", "tokens": "tokens.txt",
 *  "dataDir": "espeak-ng-data", "threads": 2, "lengthScale": 1.0, "noiseScale": 0.667, "noiseScaleW": 0.8,
 *  "say": {"tekyerema": "tetchy rehma", "mtn": "em tee en"}}
 * ```
 * `say` is this voice's own pronunciation list — whole words, any case — kept beside the model because a respelling
 * that is right for one voice's text frontend is wrong for another's (the system voice has its own, AndroidTts.RESPELL).
 */
object OnDeviceVoice {
    const val FILE = "voice.json"
    const val VITS_PIPER = "vits-piper"
    /** A character VITS (Meta MMS architecture) with no espeak: the Kusaal phone voice (owner, 18 October 2026) —
     *  campaign/kaggle/kus-ondevice. Its tokens are letters; sherpa-onnx lower-cases ASCII only, so [forModel] does. */
    const val VITS_MMS = "vits-mms"

    data class Spec(
        val type: String,
        val label: String,
        val model: String,
        val tokens: String,
        /** espeak-ng-data, copied once to the phone's storage (espeak reads real files, not APK assets). */
        val dataDir: String?,
        val threads: Int = 2,
        val lengthScale: Float = 1f,
        val noiseScale: Float = 0.667f,
        val noiseScaleW: Float = 0.8f,
        val say: Map<String, String> = emptyMap(),
        /** The one language a [VITS_MMS] voice speaks (voice.json "language", e.g. "kus"); null = English (Piper). */
        val language: String? = null,
        /** This voice's own pace (1.0 = as trained); sherpa-onnx "speed", multiplied by the user's rate setting. */
        val speed: Float = 1f
    )

    /** Where a bundled voice lives: `models/ondevice/<language>/tts/` (English = "eng", Kusaal = "kus"). */
    fun dirFor(language: String): String = "models/ondevice/${language.trim().lowercase()}/tts"

    /** Whether this voice may say a line in [language]: a Piper voice is English (never Twi); an MMS voice only its own. */
    fun speaks(s: Spec, language: String): Boolean =
        if (s.type == VITS_MMS) s.language != null && language.equals(s.language, ignoreCase = true) else language != Lang.TWI

    /** The text as the model's front-end wants it: an MMS voice's letters are lower-case tokens (ɛ, ɔ included). The
     *  Kusaal phone voice has no ʋ token (the model would drop the letter), so it reads ʋ as u for now (owner, 19 Oct). */
    fun forModel(s: Spec, text: String): String = if (s.type == VITS_MMS) text.lowercase().replace('ʋ', 'u') else text

    /** A `voice.json`, or null when it names a type this build can't run or lacks a file it needs. */
    fun parse(json: String): Spec? {
        val o = try { JSONObject(json) } catch (_: Throwable) { return null }
        val type = o.optString("type").trim()
        if (type != VITS_PIPER && type != VITS_MMS) return null
        val language = o.optString("language").trim().ifEmpty { null }
        if (type == VITS_MMS && language == null) return null
        val model = o.optString("model").trim().ifEmpty { return null }
        val tokens = o.optString("tokens", "tokens.txt").trim().ifEmpty { return null }
        val say = LinkedHashMap<String, String>()
        o.optJSONObject("say")?.let { s -> for (k in s.keys()) s.optString(k).trim().takeIf { it.isNotEmpty() }?.let { say[k.trim().lowercase()] = it } }
        return Spec(type, o.optString("label").trim().ifEmpty { model.substringBeforeLast('.') }, model, tokens,
            o.optString("dataDir").trim().ifEmpty { null },
            threads = o.optInt("threads", 2).coerceIn(1, 4),
            lengthScale = o.optDouble("lengthScale", 1.0).toFloat().coerceIn(0.5f, 2f),
            noiseScale = o.optDouble("noiseScale", 0.667).toFloat().coerceIn(0f, 1.5f),
            noiseScaleW = o.optDouble("noiseScaleW", 0.8).toFloat().coerceIn(0f, 1.5f),
            say = say, language = language, speed = o.optDouble("speed", 1.0).toFloat().coerceIn(0.5f, 1.6f))
    }

    /** [text] with this voice's own pronunciations put in: whole words only, any case, longest entry first (so
     *  "mobile money" wins over "money"). The screen keeps the real spellings; only the voice gets these. */
    fun say(text: String, entries: Map<String, String>): String {
        if (entries.isEmpty() || text.isBlank()) return text
        var t = text
        for ((word, spoken) in entries.entries.sortedByDescending { it.key.length })
            t = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(word) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
                .replace(t, Regex.escapeReplacement(spoken))
        return t
    }

    /** One thing to play: words to synthesise, or (text == null) a silence. */
    data class Piece(val text: String?, val silenceMs: Long = 0)

    /**
     * A reply as the pieces this voice makes one at a time. The model synthesises a whole piece before any of it can
     * be heard, so the FIRST piece is kept short ([firstChars]: the opening clause) — the reply starts sooner — and the
     * rest are sentence-sized ([maxChars]) and made while the earlier ones play. The reading's pause marks
     * ([SpokenText]) become silences.
     */
    fun plan(text: String, pauseMs: Long = 450, breakMs: Long = 900, maxChars: Int = 140, firstChars: Int = 60, breaths: Boolean = false): List<Piece> {
        val out = ArrayList<Piece>()
        for (seg in SpokenText.segments(text, pauseMs, breakMs)) {
            for (chunk in SpeechChunks.split(seg.text, maxChars)) {
                if (breaths) out += breathe(chunk)
                else if (out.none { it.text != null }) out += opening(chunk, firstChars).map { Piece(it) }
                else out += Piece(chunk)
            }
            if (seg.pauseAfterMs > 0) out += Piece(null, seg.pauseAfterMs)
        }
        return out
    }

    private val CLAUSE_END = Regex("(?<=[,.!?;:])\\s+")
    private val WORDISH = Regex("[\\p{L}\\p{N}']+")

    /** The silence a listener expects after a piece ending in [mark] (ms): the voice's own duration model barely pauses at
     *  punctuation (a line of three sentences ran together), so the breaths are made here (owner, 20 Oct 2026: "like taking
     *  breath, noticing punctuation"). */
    fun breathMs(mark: Char): Long = when (mark) { '.', '!' -> 420L; '?' -> 480L; ';', ':' -> 300L; ',' -> 190L; else -> 0L }

    /**
     * [chunk] as clause-sized pieces with a breath between them: split after . ! ? ; : and ,; a piece of fewer than
     * [minWords] words ("Cancelled.", "Say yes or no.") is joined to its neighbour, because this voice garbles very short
     * lines (scored with Whisper on the app's own lines, 20 Oct 2026: whole 0.21 WER, split 0.22, a bare one-word line 1.0).
     */
    fun breathe(chunk: String, minWords: Int = 4): List<Piece> {
        val parts = chunk.trim().split(CLAUSE_END).filter { it.isNotBlank() }
        val merged = ArrayList<String>()
        var carry = ""
        for (part in parts) {
            val cur = if (carry.isEmpty()) part else "$carry $part"
            if (WORDISH.findAll(cur).count() < minWords) carry = cur
            else { merged += cur; carry = "" }
        }
        if (carry.isNotEmpty()) { if (merged.isEmpty()) merged += carry else merged[merged.lastIndex] = merged.last() + " " + carry }
        val out = ArrayList<Piece>()
        for ((i, m) in merged.withIndex()) {
            out += Piece(m)
            if (i < merged.lastIndex) breathMs(m.trimEnd().last()).takeIf { it > 0 }?.let { out += Piece(null, it) }
        }
        return out
    }

    /** [chunk] as (opening clause, the rest) when it is long and has a clause break early enough; else itself. */
    internal fun opening(chunk: String, firstChars: Int): List<String> {
        if (chunk.length <= firstChars) return listOf(chunk)
        val cut = CLAUSE_END.findAll(chunk).map { it.range }.firstOrNull { it.first in 12..firstChars } ?: return listOf(chunk)
        return listOf(chunk.substring(0, cut.first).trim(), chunk.substring(cut.last + 1).trim()).filter { it.isNotEmpty() }
    }

    /** Model output (-1..1 floats) as 16-bit PCM. */
    fun pcm16(samples: FloatArray): ShortArray =
        ShortArray(samples.size) { i -> (samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
}

/**
 * The app's own voice, on the phone: a sherpa-onnx VITS/Piper model bundled under `assets/models/ondevice/eng/tts/`
 * (see [OnDeviceVoice] for `voice.json`). Works with no network. When the build carries no voice, or it cannot load,
 * [speak] returns false and the next voice in the chain (the phone's engine) says the line — never silent.
 *
 * One line at a time: a newer line stops the one being spoken, as the system engine's QUEUE_FLUSH does. Same audio
 * rules as the system voice — the accessibility stream when a screen reader drives the phone, ducking focus while it
 * speaks, softer at night ([QuietHours]).
 *
 * Threads: every call into the native model (load, generate, release) happens on ONE worker thread — the model is not
 * safe to free while it synthesises. A second thread only feeds the speaker.
 */
class SherpaTts(context: Context, private val dir: String = OnDeviceVoice.dirFor("eng")) : TtsProvider, VoiceAvailability {
    private val app = context.applicationContext

    /** The bundled voice, or null when this build has none. Read once. */
    val spec: OnDeviceVoice.Spec? by lazy {
        try { app.assets.open("$dir/${OnDeviceVoice.FILE}").use { OnDeviceVoice.parse(it.readBytes().decodeToString()) } }
        catch (_: Throwable) { null }
    }
    val present: Boolean get() = spec != null
    override val name: String get() = "on-device(${spec?.label ?: "none"})"

    /** Worker thread only. */
    private var engine: OfflineTts? = null
    @Volatile private var loadError: String? = null
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "gh-voice") }
    private val focus = AudioFocus(app, onLost = { stop() })   // a call rings → the reply stops

    private class Line { @Volatile var cancelled = false; @Volatile var track: AudioTrack? = null }
    private val current = AtomicReference<Line?>(null)

    /** The user's speech-rate setting (1.0 = this voice's normal pace). */
    @Volatile var rate: Float = 1f

    /** Time from speak() to the first audible piece of the last line (ms), or -1. */
    @Volatile var lastFirstAudioMs: Long = -1; private set

    override fun whyUnavailable(): String? = when {
        spec == null -> "this build has no on-device voice"
        else -> loadError
    }

    private fun enabled() = AppConfig.bool("speech.tts.onDevice.enabled", true)

    /** Load the model now (off the main thread), so the first reply doesn't wait for it. */
    fun warm() { if (present && enabled()) worker.execute { spec?.let { load(it) } } }

    /** Give the model's memory back when nothing is being said (the system is short of RAM); it reloads on the next
     *  line. Queued on the worker, so it can never free the model under a line that is being synthesised. */
    fun release() {
        worker.execute {
            if (current.get() != null || engine == null) return@execute
            try { engine?.release() } catch (_: Throwable) {}
            engine = null
            Log.i("Tekyerema", "[TTS] on-device voice released (low memory)")
        }
    }

    override suspend fun speak(text: String, language: String): Boolean {
        if (text.isBlank()) return false
        val s = spec ?: return false
        if (!OnDeviceVoice.speaks(s, language)) return false
        if (!enabled() || loadError != null) return false
        NowSpeaking.begin()
        val line = Line()
        try {
            return suspendCancellableCoroutine { cont ->
                current.getAndSet(line)?.let { cancel(it) }
                cont.invokeOnCancellation { cancel(line) }
                worker.execute {
                    val ok = try { play(line, s, text) } catch (t: Throwable) {
                        Log.w("Tekyerema", "[TTS] on-device voice failed: ${t.javaClass.simpleName}: ${t.message}"); false
                    }
                    current.compareAndSet(line, null)      // never clears a NEWER line that has taken its place
                    if (cont.isActive) cont.resume(ok)
                }
            }
        } finally { NowSpeaking.end() }
    }

    override fun stop() { current.get()?.let { cancel(it) } }

    /** Debug evidence (RUN_GOAL `tts_test … tts_save 1`): the whole line as a 16-bit mono WAV in [out], made on the
     *  worker thread like a spoken line. False when this voice cannot say [language] or the model will not load. */
    suspend fun renderWav(text: String, language: String, out: File): Boolean {
        val s = spec ?: return false
        if (text.isBlank() || !OnDeviceVoice.speaks(s, language)) return false
        return suspendCancellableCoroutine { cont ->
            worker.execute {
                val ok = try {
                    val tts = load(s)
                    if (tts == null) false else {
                        val audio = tts.generate(OnDeviceVoice.forModel(s, OnDeviceVoice.say(text, s.say)), 0, (rate * s.speed).coerceIn(0.5f, 1.6f))
                        val pcm = OnDeviceVoice.pcm16(audio.samples)
                        out.parentFile?.mkdirs()
                        out.writeBytes(wav16(pcm, audio.sampleRate))
                        Log.i("Tekyerema", "[TTS] on-device voice rendered ${pcm.size} samples at ${audio.sampleRate} Hz → ${out.name}")
                        pcm.isNotEmpty()
                    }
                } catch (t: Throwable) {
                    Log.w("Tekyerema", "[TTS] on-device render failed: ${t.javaClass.simpleName}: ${t.message}"); false
                }
                if (cont.isActive) cont.resume(ok)
            }
        }
    }

    private fun wav16(pcm: ShortArray, hz: Int): ByteArray {
        val data = pcm.size * 2
        val b = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(hz).putInt(hz * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(data)
        for (v in pcm) b.putShort(v)
        return b.array()
    }

    override fun shutdown() {
        stop()
        worker.execute { try { engine?.release() } catch (_: Throwable) {}; engine = null }
    }

    private fun cancel(line: Line) {
        line.cancelled = true
        try { line.track?.let { it.pause(); it.flush() } } catch (_: Throwable) {}
    }

    /**
     * True once any of the line was heard — or it was stopped (the next voice must not say it again). False only when
     * this voice could not make a sound, so the next voice in the chain says the line. Worker thread.
     */
    private fun play(line: Line, s: OnDeviceVoice.Spec, raw: String): Boolean {
        if (line.cancelled) return true
        val tts = load(s) ?: return false
        // The pronunciations go in AFTER the text is cut into pieces: a respelling may carry commas ("teh, cheh, reh, mah") that
        // must stay inside one piece, not become breaths.
        val pieces = OnDeviceVoice.plan(raw,
            AppConfig.long("speech.tts.pauseMs", 450L), AppConfig.long("speech.tts.breakMs", 900L),
            AppConfig.int("speech.tts.onDevice.chunkChars", 140), AppConfig.int("speech.tts.onDevice.firstChars", 60),
            breaths = s.type == OnDeviceVoice.VITS_PIPER && AppConfig.bool("speech.tts.onDevice.breaths", true))
            .map { pc -> pc.text?.let { t -> pc.copy(text = OnDeviceVoice.forModel(s, OnDeviceVoice.say(t, s.say))) } ?: pc }
        if (pieces.none { it.text != null }) return false
        val hz = tts.sampleRate()
        val speed = (AppConfig.double("speech.tts.rate", 1.0).toFloat() * rate * s.speed).coerceIn(0.5f, 1.6f)
        val heard = AtomicLong(0)                               // speech frames the speaker actually took
        val token = focus.acquire(AudioFocus.Kind.SPEAK)
        var track: AudioTrack? = null
        try {
            val minBuf = AudioTrack.getMinBufferSize(hz, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val out = AudioTrack.Builder()
                .setAudioAttributes(focus.attributes(AudioFocus.Kind.SPEAK))
                .setAudioFormat(AudioFormat.Builder().setSampleRate(hz).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuf, hz))          // half a second of 16-bit mono
                .build()
            track = out
            line.track = out
            QuietHours.factor().let { if (it < 1f) try { out.setVolume(it) } catch (_: Throwable) {} }

            // The model makes the next piece while the last one plays: this thread synthesises, another feeds the speaker.
            val queue = LinkedBlockingQueue<Chunk>()
            val written = AtomicLong(0)                         // every frame handed to the track, silences included
            val player = Thread({
                try {
                    out.play()
                    while (true) {
                        val c = queue.take()
                        if (c === END || line.cancelled) break
                        var off = 0
                        while (off < c.pcm.size && !line.cancelled) {
                            val n = out.write(c.pcm, off, c.pcm.size - off)
                            if (n <= 0) break                   // a dead track: nothing more can be heard
                            off += n
                        }
                        written.addAndGet(off.toLong())
                        if (c.speech) heard.addAndGet(off.toLong())
                        if (off < c.pcm.size) break
                    }
                } catch (t: Throwable) {
                    Log.w("Tekyerema", "[TTS] on-device playback failed: ${t.javaClass.simpleName}: ${t.message}")
                }
            }, "gh-voice-out")
            player.start()
            val t0 = android.os.SystemClock.uptimeMillis()
            var first = true
            try {
                for (p in pieces) {
                    if (line.cancelled || !player.isAlive) break
                    val words = p.text
                    if (words == null) { queue.put(Chunk(ShortArray((hz * p.silenceMs / 1000).toInt()), speech = false)); continue }
                    // An object, not a lambda: sherpa-onnx's JNI looks up the TYPED method invoke([F)Ljava/lang/Integer;, and
                    // Kotlin 2 compiles a lambda to a class with only the erased invoke(Object) — the native side then aborts
                    // the whole app (SIGABRT, "JNI NewFloatArray called with pending NoSuchMethodError"; emulator, 19 Oct 2026).
                    tts.generateWithCallback(words, 0, speed, object : (FloatArray) -> Int {
                        override fun invoke(samples: FloatArray): Int {
                            if (line.cancelled || !player.isAlive) return 0
                            if (first) { first = false; lastFirstAudioMs = android.os.SystemClock.uptimeMillis() - t0 }
                            queue.put(Chunk(OnDeviceVoice.pcm16(samples), speech = true))
                            return 1
                        }
                    })
                }
            } catch (t: Throwable) {
                // out of memory, a native failure: what was already said stays said — see the return value
                Log.w("Tekyerema", "[TTS] on-device synthesis failed part-way: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                queue.put(END)
                try { player.join() } catch (_: InterruptedException) {}
                if (!line.cancelled) drain(line, out, written.get(), hz)
            }
            if (heard.get() > 0) Log.i("Tekyerema", "[TTS] on-device voice: ${pieces.count { it.text != null }} pieces, first audio " +
                "${lastFirstAudioMs} ms" + if (line.cancelled) " (stopped)" else "")
        } catch (t: Throwable) {
            Log.w("Tekyerema", "[TTS] on-device voice could not play: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            line.track = null
            try { track?.stop() } catch (_: Throwable) {}
            try { track?.release() } catch (_: Throwable) {}
            focus.release(token)
        }
        return heard.get() > 0 || line.cancelled
    }

    /**
     * Wait until what was written has been played. The position is read BEFORE stop(): on Android 11 a stopped track
     * reports position 0, and waiting for it to reach the end held every line — and the microphone that opens after
     * it — for the line's whole length again. stop() is still needed: a line shorter than the buffer only starts
     * playing when the track is stopped.
     */
    private fun drain(line: Line, track: AudioTrack, frames: Long, hz: Int) {
        val played = try { track.playbackHeadPosition.toLong() and 0xFFFFFFFFL } catch (_: Throwable) { frames }
        try { track.stop() } catch (_: Throwable) { return }
        val waitMs = ((frames - played).coerceAtLeast(0) * 1000 / hz + DRAIN_MARGIN_MS).coerceAtMost(MAX_DRAIN_MS)
        val deadline = android.os.SystemClock.uptimeMillis() + waitMs
        while (!line.cancelled && android.os.SystemClock.uptimeMillis() < deadline) {
            try { Thread.sleep(20) } catch (_: InterruptedException) { return }
        }
    }

    /** Worker thread only. */
    private fun load(s: OnDeviceVoice.Spec): OfflineTts? {
        engine?.let { return it }
        if (loadError != null) return null
        val t0 = android.os.SystemClock.uptimeMillis()
        return try {
            val data = s.dataDir?.let { extract("$dir/$it", File(app.filesDir, "ondevice/tts/$it")) } ?: ""
            val vits = OfflineTtsVitsModelConfig(model = "$dir/${s.model}", tokens = "$dir/${s.tokens}", dataDir = data,
                noiseScale = s.noiseScale, noiseScaleW = s.noiseScaleW, lengthScale = s.lengthScale)
            val cfg = OfflineTtsConfig(model = OfflineTtsModelConfig(vits = vits, numThreads = s.threads, debug = false, provider = "cpu"))
            OfflineTts(app.assets, cfg).also {
                engine = it
                Log.i("Tekyerema", "[TTS] on-device voice loaded: ${s.label} ${it.sampleRate()} Hz, ${s.threads} threads (${android.os.SystemClock.uptimeMillis() - t0} ms)")
            }
        } catch (t: Throwable) {
            loadError = "the on-device voice could not be loaded (${t.javaClass.simpleName})"
            Log.w("Tekyerema", "[TTS] on-device voice load failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /** Copy an asset directory to the phone's storage once per installed build; returns its path. */
    private fun extract(assetDir: String, dest: File): String {
        val stamp = try { app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime.toString() } catch (_: Throwable) { "0" }
        val marker = File(dest, ".copied")
        if (marker.exists() && marker.readText() == stamp) return dest.absolutePath
        dest.deleteRecursively()
        copy(assetDir, dest)
        marker.writeText(stamp)
        Log.i("Tekyerema", "[TTS] on-device voice data copied to ${dest.absolutePath}")
        return dest.absolutePath
    }

    private fun copy(assetPath: String, dest: File) {
        val children = app.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            app.assets.open(assetPath).use { ins -> dest.outputStream().use { ins.copyTo(it) } }
        } else {
            dest.mkdirs()
            for (c in children) copy("$assetPath/$c", File(dest, c))
        }
    }

    /** What the speaker is fed: speech, or a silence between two pieces. */
    private class Chunk(val pcm: ShortArray, val speech: Boolean)

    private companion object {
        val END = Chunk(ShortArray(0), speech = false)
        const val DRAIN_MARGIN_MS = 250L
        const val MAX_DRAIN_MS = 3000L          // the track holds half a second; anything longer is a stuck track
    }
}
