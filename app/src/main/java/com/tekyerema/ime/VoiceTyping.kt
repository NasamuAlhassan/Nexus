package com.tekyerema.ime

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import com.tekyerema.language.TwiText

/**
 * G-05 — the keyboard's voice key.
 *
 * Why the phone's own voice input and not Tɛkyerɛma's on-device recogniser (the brief's first choice), for now:
 *  - the on-device Twi model is disqualified (2026-09-19 audit: its test clips were in its training data) and both
 *    whisper-tiny models are tuned to short commands, not free dictation;
 *  - SherpaAsr is private to VoiceEngine; a second copy in the keyboard would double ~hundreds of MB on a 3 GB phone
 *    and fight the wake word for the microphone;
 *  - SherpaAsr writes the transcript to logcat in debug builds, and the keyboard must never log what is typed.
 * So the key uses the phone's speech service in place (SpeechRecognizer, offline preferred), or hands over to an
 * installed voice keyboard (Google/Samsung voice input), which returns to this keyboard by itself. The keyboard
 * itself never touches the network: whatever the phone's own voice service does is the same as tapping its mic.
 * [VoicePlan] is where an on-device route slots in once voice/ exposes a shared, non-logging dictation call.
 */
enum class VoiceRoute { OFF_IN_THIS_FIELD, IN_KEYBOARD, VOICE_KEYBOARD, UNAVAILABLE }

object VoicePlan {
    fun route(fieldAllowsVoice: Boolean, micGranted: Boolean, recognizerAvailable: Boolean, voiceKeyboardAvailable: Boolean): VoiceRoute = when {
        !fieldAllowsVoice -> VoiceRoute.OFF_IN_THIS_FIELD      // passwords/PINs: never say a secret out loud
        micGranted && recognizerAvailable -> VoiceRoute.IN_KEYBOARD
        voiceKeyboardAvailable -> VoiceRoute.VOICE_KEYBOARD     // it has its own microphone permission
        else -> VoiceRoute.UNAVAILABLE
    }

    fun explain(route: VoiceRoute): String = when (route) {
        VoiceRoute.OFF_IN_THIS_FIELD -> "Voice typing is off in password and PIN boxes. Type it with the keys."
        VoiceRoute.UNAVAILABLE -> "Voice typing isn't available on this phone. In Tɛkyerɛma, allow the microphone, then try again."
        VoiceRoute.IN_KEYBOARD, VoiceRoute.VOICE_KEYBOARD -> ""
    }

    /** First enabled voice-mode subtype of another input method, as (imeId, subtypeIndex); null if none. */
    fun pickVoiceKeyboard(candidates: List<Triple<String, Int, String>>, ownId: String): Pair<String, Int>? =
        candidates.firstOrNull { (id, _, mode) -> id != ownId && mode == "voice" }?.let { it.first to it.second }

    /** The recogniser's language for the keyboard's language. Twi is asked for first; see [VoiceTyping] fallback. */
    fun languageTag(language: KbLanguage) = if (language == KbLanguage.TWI) "ak-GH" else "en-GH"

    /** What to tell the user for a SpeechRecognizer error code (the codes are stable platform constants). */
    fun errorMessage(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that. Tap the microphone and try again."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Voice typing needs the microphone. Allow it in Tɛkyerɛma's settings."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER,
        11 /* ERROR_SERVER_DISCONNECTED */ -> "Voice typing needs your phone's speech service, and it couldn't be reached. Try again, or type."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, 10 /* ERROR_TOO_MANY_REQUESTS */ -> "Voice typing is busy. Try again in a moment."
        SpeechRecognizer.ERROR_AUDIO -> "The microphone isn't free right now. Try again in a moment."
        12, 13 /* LANGUAGE_NOT_SUPPORTED / UNAVAILABLE */ -> "Your phone's voice typing doesn't have this language."
        else -> "Voice typing stopped. Try again, or type."
    }

    fun isLanguageError(code: Int) = code == 12 || code == 13
}

/** How recognised speech is inserted: tidy spacing, Twi letters in their standard form, a capital where due. */
object DictationText {
    fun forInsertion(recognized: String, twi: Boolean, capitalize: Boolean, textBefore: CharSequence?): String {
        var t = recognized.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return ""
        if (twi) t = TwiText.canonical(t)
        if (capitalize) t = t.replaceFirstChar { it.uppercaseChar() }
        val prev = textBefore?.lastOrNull()
        val needsSpace = prev != null && !prev.isWhitespace() && prev !in "([{“‘\"'/@#"
        return if (needsSpace) " $t" else t
    }
}

/** Finds an installed voice keyboard (an input method with a "voice" subtype) to hand over to. */
object VoiceKeyboards {
    fun find(imm: InputMethodManager, ownId: String): Pair<InputMethodInfo, InputMethodSubtype>? {
        val imes = try { imm.enabledInputMethodList } catch (_: Throwable) { return null }
        val candidates = ArrayList<Triple<String, Int, String>>()
        val subtypes = HashMap<String, List<InputMethodSubtype>>()
        for (imi in imes) {
            val list = try { imm.getEnabledInputMethodSubtypeList(imi, true) } catch (_: Throwable) { emptyList() }
            subtypes[imi.id] = list
            list.forEachIndexed { i, s -> candidates += Triple(imi.id, i, s.mode ?: "") }
        }
        val (id, index) = VoicePlan.pickVoiceKeyboard(candidates, ownId) ?: return null
        val imi = imes.first { it.id == id }
        return imi to subtypes.getValue(id)[index]
    }
}

/**
 * In-keyboard dictation through the phone's speech service. Main thread only (a SpeechRecognizer rule). Nothing it
 * hears is logged or kept: the text goes to [Listener.onFinal] and from there straight into the field.
 */
class VoiceTyping(private val ctx: Context, private val listener: Listener) {
    interface Listener {
        fun onListening()
        fun onPartial(text: String)
        fun onFinal(text: String, language: KbLanguage)
        fun onVoiceError(message: String)
        fun onIdle()
    }

    private var recognizer: SpeechRecognizer? = null
    var listening = false
        private set
    private var language = KbLanguage.ENGLISH
    private var triedEnglishInstead = false

    fun available(): Boolean = try { SpeechRecognizer.isRecognitionAvailable(ctx) } catch (_: Throwable) { false }

    fun start(lang: KbLanguage) {
        language = lang
        triedEnglishInstead = false
        begin(VoicePlan.languageTag(lang))
    }

    private fun begin(tag: String) {
        val r = recognizer ?: try { SpeechRecognizer.createSpeechRecognizer(ctx) } catch (_: Throwable) { null }
        if (r == null) { listener.onVoiceError(VoicePlan.explain(VoiceRoute.UNAVAILABLE)); return }
        recognizer = r
        r.setRecognitionListener(callbacks)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)   // stay on the phone where its engine can
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        listening = true
        try { r.startListening(intent) } catch (_: Throwable) {
            listening = false
            listener.onVoiceError(VoicePlan.errorMessage(SpeechRecognizer.ERROR_CLIENT))
        }
    }

    /** Stop listening and keep what was heard so far (the result still arrives through onResults). */
    fun stop() { try { recognizer?.stopListening() } catch (_: Throwable) {} }

    /** Drop everything (the keyboard is closing or the field changed). */
    fun cancel() {
        try { recognizer?.cancel() } catch (_: Throwable) {}
        if (listening) { listening = false; listener.onIdle() }
    }

    fun release() {
        cancel()
        try { recognizer?.destroy() } catch (_: Throwable) {}
        recognizer = null
    }

    private val callbacks = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = listener.onListening()
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            best(partialResults)?.let { listener.onPartial(it) }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val text = best(results)
            if (text.isNullOrBlank()) listener.onVoiceError(VoicePlan.errorMessage(SpeechRecognizer.ERROR_NO_MATCH))
            else listener.onFinal(text, if (triedEnglishInstead) KbLanguage.ENGLISH else language)
            listener.onIdle()
        }

        override fun onError(error: Int) {
            listening = false
            // The phone's recogniser rarely has Twi: say so once and listen again in (Ghanaian) English.
            if (language == KbLanguage.TWI && !triedEnglishInstead && VoicePlan.isLanguageError(error)) {
                triedEnglishInstead = true
                listener.onVoiceError("Your phone's voice typing doesn't have Twi. Listening in English.")
                begin(VoicePlan.languageTag(KbLanguage.ENGLISH))
                return
            }
            listener.onVoiceError(VoicePlan.errorMessage(error))
            listener.onIdle()
        }
    }

    private fun best(b: Bundle?): String? = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
}
