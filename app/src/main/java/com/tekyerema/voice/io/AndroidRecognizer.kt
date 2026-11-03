package com.tekyerema.voice.io

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * [RecognizerEngine] over the platform SpeechRecognizer (API 30 surface only: no on-device-only recognizer, no
 * biasing strings, no audio-source extra — see the brief §A.2). Main thread only, like SpeechRecognizer itself.
 * The keyboard's voice key (ime/VoiceTyping) drives the same service for dictation; this one serves the assistant.
 */
class AndroidRecognizerEngine(
    private val context: Context,
    /** Flattened ComponentName from `systemSpeech.component`; blank = the system default recognizer. */
    private val component: () -> String = { "" }
) : RecognizerEngine {
    override val name: String by lazy { defaultServicePackage(context) ?: "default" }

    override fun available(): Boolean = try { SpeechRecognizer.isRecognitionAvailable(context) } catch (_: Throwable) { false }

    override fun create(): RecognizerHandle? {
        val cn = component().trim().takeIf { it.isNotEmpty() }?.let { ComponentName.unflattenFromString(it) } ?: preferredGoogle()
        val r = try {
            // The ComponentName overload asks for the component to be checked with queryIntentServices first.
            if (cn != null && serviceComponents(context).contains(cn)) SpeechRecognizer.createSpeechRecognizer(context, cn)
            else SpeechRecognizer.createSpeechRecognizer(context)
        } catch (t: Throwable) { Log.w(TAG, "[VOICE] createSpeechRecognizer failed: ${t.javaClass.simpleName}"); null }
        return r?.let { Handle(it) }
    }

    /**
     * Google's recognizer when the phone's default is another maker's (Samsung's on the A20e): it lists Ghanaian English
     * (en-GH) and the free-form model the assistant relies on, and it is what the Pixel uses anyway, so the emulator and
     * the phone hear alike (owner, 13 October 2026: "changing the TTS and ASR… will be better"). `systemSpeech.preferGoogle`
     * false keeps the phone's default; an explicit `component` always wins. Logged once, without content.
     */
    private var loggedPick = false
    private fun preferredGoogle(): ComponentName? {
        val want = try { com.tekyerema.core.config.AppConfig.bool("speech.voiceLayer.systemSpeech.preferGoogle", true) } catch (_: Throwable) { true }
        if (!want) return null
        val default = defaultServicePackage(context)
        if (default != null && default.startsWith("com.google.")) return null
        val services = serviceComponents(context)
        val google = services.firstOrNull { it.packageName == GOOGLE_RECOGNIZER_PKG }
            ?: services.firstOrNull { it.packageName.startsWith("com.google.") } ?: return null
        if (!loggedPick) { loggedPick = true; Log.i(TAG, "[VOICE] recognizer: ${google.packageName} preferred over the phone's default ${default ?: "?"}") }
        return google
    }

    private inner class Handle(private val r: SpeechRecognizer) : RecognizerHandle {
        override fun start(request: RecognitionRequest, callbacks: RecognizerCallbacks) {
            r.setRecognitionListener(Listener(callbacks))   // before any command (SpeechRecognizer contract)
            r.startListening(intentFor(request))
        }
        override fun cancel() { r.cancel() }
        override fun destroy() { r.destroy() }
    }

    private fun intentFor(q: RecognitionRequest): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, q.languageTag)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, q.partials)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, q.maxResults)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, q.preferOffline)
        .apply {
            q.silenceMs?.let { (complete, possibly, minimum) ->
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, complete)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, possibly)
                if (minimum > 0) putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, minimum)
            }
            // Speech repair #6 (research note 3 Nov 2026 §2a): bias the recogniser towards this phone's contact and app
            // names ("Richmond", not "reach money"). EXTRA_BIASING_STRINGS is API 33; whether Google's recogniser reads it on
            // Android 11 is unknown — this is for the A/B on the A20e. OFF by default: it sends contact NAMES to the
            // recogniser's service (the audio goes there already; the list would not). `speech.system.biasing`.
            if (com.tekyerema.core.config.AppConfig.bool("speech.system.biasing", false)) biasingNames().takeIf { it.isNotEmpty() }?.let {
                putStringArrayListExtra(EXTRA_BIASING_STRINGS, ArrayList(it))
                Log.i("Tekyerema", "[VOICE] recogniser biasing: ${it.size} names")
            }
        }

    private fun biasingNames(): List<String> {
        val max = com.tekyerema.core.config.AppConfig.int("speech.system.biasingMax", 100)
        return com.tekyerema.voice.SpeechVocabulary.current().entries
            .sortedBy { if (it.kind == com.tekyerema.voice.EntityCorrector.Kind.CONTACT) 0 else 1 }
            .map { it.text }.filter { it.length >= 3 }.distinct().take(max)
    }

    private class Listener(private val cb: RecognizerCallbacks) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = cb.onReady()
        override fun onBeginningOfSpeech() = cb.onBeginningOfSpeech()
        override fun onRmsChanged(rmsdB: Float) = cb.onRms(rmsdB)
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() = cb.onEndOfSpeech()
        override fun onError(error: Int) = cb.onError(error)
        override fun onResults(results: Bundle?) =
            cb.onResults(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty(),
                results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES))
        override fun onPartialResults(partialResults: Bundle?) =
            cb.onPartial(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    companion object {
        /** RecognizerIntent.EXTRA_BIASING_STRINGS (API 33), by name so it compiles and is simply ignored below. */
        private const val EXTRA_BIASING_STRINGS = "android.speech.extra.BIASING_STRINGS"
        private const val TAG = "Tekyerema"
        /** The Google app's recognition service (Android 11 phones); Pixels default to it. */
        const val GOOGLE_RECOGNIZER_PKG = "com.google.android.googlequicksearchbox"

        /** Installed RecognitionService components (needs the `<queries>` entry on API 30+ without QUERY_ALL_PACKAGES). */
        fun serviceComponents(ctx: Context): List<ComponentName> = try {
            ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .mapNotNull { it.serviceInfo?.let { s -> ComponentName(s.packageName, s.name) } }
        } catch (_: Throwable) { emptyList() }

        /** The package of the recognizer createSpeechRecognizer(ctx) binds (Settings.Secure voice_recognition_service). */
        fun defaultServicePackage(ctx: Context): String? = try {
            android.provider.Settings.Secure.getString(ctx.contentResolver, "voice_recognition_service")
                ?.let { ComponentName.unflattenFromString(it)?.packageName }
        } catch (_: Throwable) { null }

        /**
         * Startup diagnostics (no content): which recognition services exist, which one is the default, and whether it
         * lists [tag] among its languages (RecognizerIntent.getVoiceDetailsIntent, an ordered broadcast). [onLanguages]
         * gets the supported tags when the service answers — an empty answer means "unknown", never "unsupported".
         */
        fun probe(ctx: Context, tag: String, fallback: String, onLanguages: (Set<String>) -> Unit) {
            val services = serviceComponents(ctx).map { it.packageName }.distinct()
            Log.i(TAG, "[STARTUP] speech recognizers=${services.ifEmpty { listOf("none") }} default=${defaultServicePackage(ctx) ?: "?"} " +
                "available=${try { SpeechRecognizer.isRecognitionAvailable(ctx) } catch (_: Throwable) { false }}")
            val details = try { RecognizerIntent.getVoiceDetailsIntent(ctx) } catch (_: Throwable) { null } ?: return
            try {
                ctx.sendOrderedBroadcast(details, null, object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, i: Intent?) {
                        val extras = getResultExtras(true)
                        val langs = extras?.getStringArrayList(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES).orEmpty()
                            .map { it.replace('_', '-').lowercase() }.toSet()
                        Log.i(TAG, "[STARTUP] speech languages=${langs.size} $tag=${tag.lowercase() in langs} " +
                            "$fallback=${fallback.lowercase() in langs} preferred=${extras?.getString(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE)}")
                        if (langs.isNotEmpty()) onLanguages(langs)
                    }
                }, null, Activity.RESULT_OK, null, null)
            } catch (t: Throwable) { Log.w(TAG, "[STARTUP] speech language probe failed: ${t.javaClass.simpleName}") }
        }
    }
}
