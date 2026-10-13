package com.tekyerema.camera

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.tekyerema.core.config.AppConfig
import com.tekyerema.voice.SpeechChunks
import com.tekyerema.voice.Verbalize
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P5-03 v0: a PDF read aloud page by page. The page is shown large for low-vision users; the words are spoken in sentence
 * chunks; "tap for the next page" (the whole screen and a Next button), back leaves. The next page's words are prepared
 * while the current one is being read. Own TextToSpeech, as the camera reader has: the reader works even when the
 * assistant's voice stack is busy or not yet up. Log lines carry page numbers and timings only — never the words.
 */
class DocumentReadActivity : Activity() {
    companion object {
        const val EXTRA_FILE = "file"
        private const val TAG = "Tekyerema"
        fun intent(ctx: Context, file: File): Intent =
            Intent(ctx, DocumentReadActivity::class.java).putExtra(EXTRA_FILE, file.absolutePath)
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var reader: DocumentReader? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pending = ArrayList<Pair<String, (() -> Unit)?>>()
    private val afterSpeech = HashMap<String, () -> Unit>()
    private var utteranceSeq = 0
    private var page = 0
    private var pages = 0
    private var busy = false
    private var closing = false
    private var current: Bitmap? = null
    private val prepared = HashMap<Int, String>()
    private var prefetch: Job? = null
    private lateinit var image: ImageView
    private lateinit var caption: TextView
    private lateinit var next: Button
    private val idleTimeout = Runnable { finishQuietly() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        tts = TextToSpeech(applicationContext) { status -> main.post { onTtsInit(status == TextToSpeech.SUCCESS) } }
        val path = intent?.getStringExtra(EXTRA_FILE)
        val file = path?.let { File(it) }
        if (file == null || !file.isFile || !DocumentReader.looksLikePdf(file)) {
            Log.i(TAG, "[DOC] not a PDF or missing file")
            speak("I couldn't open that document.") { finishQuietly() }
            return
        }
        scope.launch {
            val r = withContext(Dispatchers.IO) { try { DocumentReader(file) } catch (t: Throwable) { Log.w(TAG, "[DOC] open failed: ${t.javaClass.simpleName}"); null } }
            if (r == null) { speak("I couldn't open that document.") { finishQuietly() }; return@launch }
            reader = r; pages = r.pageCount
            Log.i(TAG, "[DOC] opened pages=$pages")
            if (pages == 0) { speak("That document has no pages.") { finishQuietly() }; return@launch }
            speak(DocumentLines.intro(pages), flush = true)
            readPage(0)
        }
    }

    private fun readPage(index: Int) {
        val r = reader ?: return
        if (busy || closing || index !in 0 until pages) return
        busy = true; page = index
        resetIdleTimer()
        scope.launch {
            val t0 = SystemClock.uptimeMillis()
            val bmp = withContext(Dispatchers.Default) { try { r.render(index, AppConfig.int("document.render.maxSide", 2000)) } catch (t: Throwable) { null } }
            if (closing) return@launch
            if (bmp == null) { busy = false; speak("I couldn't draw page ${index + 1}."); return@launch }
            show(bmp)
            val tRender = SystemClock.uptimeMillis() - t0
            val text = prepared.remove(index) ?: withContext(Dispatchers.Default) { r.text(bmp) }
            val tOcr = SystemClock.uptimeMillis() - t0 - tRender
            Log.i(TAG, "[DOC] page ${index + 1}/$pages render=${tRender}ms ocr=${tOcr}ms chars=${text.length}")
            if (closing) return@launch
            caption.text = if (text.isBlank()) DocumentLines.empty(index, pages) else text.take(400)
            prefetchNext(index + 1)
            val lines = if (text.isBlank()) listOf(DocumentLines.empty(index, pages))
                else listOf(DocumentLines.pageStart(index)) + SpeechChunks.split(text, AppConfig.int("document.chunkChars", 180))
            lines.forEachIndexed { i, line -> speak(line, flush = i == 0 && index > 0, then = null) }
            speak(DocumentLines.pageEnd(index, pages), flush = false) { busy = false; resetIdleTimer() }
            busy = false
        }
    }

    /** The next page's words, prepared while this page is being read (one bitmap at a time: rendered, read, recycled). */
    private fun prefetchNext(index: Int) {
        val r = reader ?: return
        if (index !in 0 until pages || prepared.containsKey(index)) return
        prefetch?.cancel()
        prefetch = scope.launch(Dispatchers.Default) {
            try {
                val bmp = r.render(index, AppConfig.int("document.render.maxSide", 2000))
                val text = r.text(bmp)
                bmp.recycle()
                withContext(Dispatchers.Main) { prepared[index] = text }
            } catch (_: Throwable) {}
        }
    }

    private fun show(bmp: Bitmap) {
        val old = current
        current = bmp
        image.setImageBitmap(bmp)
        old?.let { if (it !== bmp) it.recycle() }
    }

    private fun onNext() {
        if (busy) return
        if (page + 1 < pages) readPage(page + 1)
        else if (pages == 1) readPage(0)
        else speak(DocumentLines.pageEnd(page, pages), flush = true)
    }

    // ---------------------------------------------------------------- speech (the camera reader's pattern, reduced)
    private fun onTtsInit(ok: Boolean) {
        ttsReady = ok
        if (!ok) { Log.w(TAG, "[DOC] system voice unavailable"); return }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { id?.let { afterSpeech.remove(it) }?.let { main.post(it) } }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { id?.let { afterSpeech.remove(it) }?.let { main.post(it) } }
        })
        val queued = ArrayList(pending); pending.clear()
        queued.forEach { (line, then) -> utter(line, flush = false, then = then) }
    }

    private fun speak(text: String, flush: Boolean = false, then: (() -> Unit)? = null) = utter(Verbalize.apply(text), flush, then)

    private fun utter(line: String, flush: Boolean, then: (() -> Unit)?) {
        val engine = tts
        if (line.isBlank()) { then?.invoke(); return }
        if (!ttsReady || engine == null) { if (flush) pending.clear(); pending.add(line to then); return }
        val id = "doc-" + (++utteranceSeq)
        if (then != null) afterSpeech[id] = then
        val rc = try { engine.speak(line, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id) } catch (_: Throwable) { TextToSpeech.ERROR }
        if (rc != TextToSpeech.SUCCESS) afterSpeech.remove(id)?.invoke()
    }

    // ---------------------------------------------------------------- layout / lifecycle
    private fun buildLayout(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF000000.toInt()) }
        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnClickListener { onNext() }
        }
        root.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        caption = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt()); textSize = 18f; setPadding(24, 16, 24, 16); maxLines = 4
            contentDescription = "What the page says"
        }
        root.addView(caption, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        next = Button(this).apply {
            text = "Next page"
            textSize = 22f
            setOnClickListener { onNext() }
        }
        val bar = FrameLayout(this).apply { setPadding(24, 8, 24, 24) }
        bar.addView(next, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 160, Gravity.CENTER))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return root
    }

    private fun resetIdleTimer() {
        main.removeCallbacks(idleTimeout)
        main.postDelayed(idleTimeout, AppConfig.long("document.sessionTimeoutMs", 180_000L))
    }

    private fun finishQuietly() { closing = true; finish() }

    override fun onDestroy() {
        closing = true
        main.removeCallbacks(idleTimeout)
        prefetch?.cancel()
        scope.cancel()
        try { tts?.stop(); tts?.shutdown() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        current?.recycle(); current = null
        super.onDestroy()
    }
}
