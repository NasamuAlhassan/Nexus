package com.tekyerema.ime

import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import com.tekyerema.core.Privacy
import com.tekyerema.core.config.AppConfig
import com.tekyerema.voice.Dictation
import kotlin.concurrent.thread

/**
 * G-05 — the Tɛkyerɛma Twi keyboard (an InputMethodService). Owner request: "the local language has keyboards — when
 * setting up you can add your Twi keyboard". Design and sources: docs/agent/research/overnight_twi_keyboard.md.
 *
 * PRIVACY — a keyboard sees everything typed, so this class:
 *  - never logs (no android.util.Log anywhere in ime/ — ImePrivacyTest fails the build otherwise), never uses the
 *    network, and keeps no history; the only thing it can store is the OPT-IN learned word list (word + count);
 *  - reads text only to find the word at the cursor (suggestions, the 3/c habit, delete-word) and forgets it;
 *  - in a sensitive field (see [FieldPolicy]: password types, a PIN/password/OTP hint, or the agent's Secure Input
 *    step) shows no suggestions, learns nothing, converts nothing, and turns the voice key off;
 *  - has no entry point for the agent: nothing but a touched key (or the user's own speech) types. The agent keeps
 *    typing through the accessibility service, and the Secure Input boundary — the user types PINs themselves — stays.
 */
class TwiKeyboardService : InputMethodService(), TwiKeyboardView.Listener, VoiceTyping.Listener {

    private lateinit var prefs: KeyboardPrefs
    private var view: TwiKeyboardView? = null
    private var policy = FieldPolicy.of(InputType.TYPE_CLASS_TEXT, 0, null, false)
    private var enter = EnterKeys.of(0, InputType.TYPE_CLASS_TEXT)
    private var inputType = InputType.TYPE_CLASS_TEXT
    private var page = Page.LETTERS
    private var language = KbLanguage.TWI
    private val shift = ShiftMachine()
    private val habit = HabitSession()
    private var lexicon = TwiLexicon.EMPTY
    private var english = TwiLexicon.EMPTY
    private lateinit var suggester: WordSuggester
    private var voice: VoiceTyping? = null
    private var variantsOpen = false
    private var lastSpaceMs = 0L
    private var listeningShown = false

    // G-05 (2): the last swipe's other words, on offer in the strip until the next key, and the text they would replace.
    private var choices: List<String> = emptyList()
    private var committed: String? = null
    /** The space a swipe or a chosen word put after itself: the next punctuation key takes it back ("hello ." → "hello."). */
    private var autoSpaced = false

    // The user's options, read when each field opens (a change on the Settings page applies to the next field).
    private var optSuggest = true
    private var optSwipe = true
    private var optHabit = true
    private var optLearn = false
    private var optVibrate = true
    private var optSound = false

    override fun onCreate() {
        super.onCreate()
        prefs = KeyboardPrefs(this)
        lexicon = try {
            assets.open("ime/twi_words.tsv").bufferedReader(Charsets.UTF_8).useLines { TwiLexicon.parse(it) }
        } catch (_: Throwable) { TwiLexicon.EMPTY }
        suggester = WordSuggester(lexicon, { english }) { if (optLearn) LearnedWordStore.get(this).view() else emptyMap() }
        // The English list (20,000 words) parses off the main thread; until it lands the keyboard suggests Twi only.
        val main = Handler(Looper.getMainLooper())
        thread(name = "ime-words", isDaemon = true) {
            val lex = try {
                assets.open("ime/en_words.tsv").bufferedReader(Charsets.UTF_8).useLines { TwiLexicon.parse(it) }
            } catch (_: Throwable) { TwiLexicon.EMPTY }
            lex.folded   // computed here, not on the first key press
            main.post { english = lex }
        }
    }

    override fun onCreateInputView(): View {
        val v = TwiKeyboardView(this, this)
        view = v
        v.bigKeys = prefs.bigKeys
        render()
        return v
    }

    /** Never the full-screen "extract" editor in landscape: it moves the text away from where a TalkBack user left it. */
    override fun onEvaluateFullscreenMode() = false

    /** A voice start queued behind the listen cue (250 ms), dropped when the field changes or the keyboard closes. */
    private var pendingVoiceStart: Runnable? = null
    private fun cancelPendingVoiceStart() {
        pendingVoiceStart?.let { view?.removeCallbacks(it) }
        pendingVoiceStart = null
        listeningShown = false
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        cancelPendingVoiceStart()
        voice?.cancel()
        Dictation.cancel()   // the field changed: never carry a dictation into it
        optSuggest = prefs.suggestions; optHabit = prefs.textingHabit; optLearn = prefs.learnWords
        optVibrate = prefs.vibrate; optSound = prefs.sound; optSwipe = prefs.swipeTyping
        inputType = info.inputType
        val hint = listOfNotNull(info.hintText, info.label, info.fieldName).joinToString(" ")
        policy = FieldPolicy.of(info.inputType, info.imeOptions, hint, Privacy.secureStep)
        enter = EnterKeys.of(info.imeOptions, info.inputType)
        page = policy.startPage
        language = prefs.language
        habit.reset()
        shift.reset()
        variantsOpen = false
        lastSpaceMs = 0L
        listeningShown = false
        forgetSwipe()
        autoSpaced = false
        view?.let { v ->
            v.bigKeys = prefs.bigKeys
            v.setVoiceState(if (policy.voice) TwiKeyboardView.VoiceKeyState.IDLE else TwiKeyboardView.VoiceKeyState.OFF)
        }
        autoCapitalize()
        render()
        refreshSuggestions()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        cancelPendingVoiceStart()
        voice?.cancel()
        Dictation.cancel()
        habit.reset()
        variantsOpen = false
        forgetSwipe()
        view?.showSuggestions(emptyList())
        if (optLearn) LearnedWordStore.saveIfDirty(this)
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        voice?.release()
        voice = null
        Dictation.cancel()
        if (optLearn) LearnedWordStore.saveIfDirty(this)
        super.onDestroy()
    }

    /** The editor moved the cursor (our own typing, a tap, or the app): re-check capitals and suggestions. */
    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (!isInputViewShown) return
        if (newSelStart != newSelEnd) habit.onOtherInput()
        if (autoCapitalize()) render()
        refreshSuggestions()
    }

    // ------------------------------------------------------------------------------------ rendering

    private fun render() {
        val v = view ?: return
        v.setLayout(when (page) {
            Page.LETTERS -> KeyboardLayouts.letters(language, shift.state, enter)
            Page.SYMBOLS -> KeyboardLayouts.symbols(language, enter)
            Page.MORE_SYMBOLS -> KeyboardLayouts.moreSymbols(language, enter)
            Page.PAD -> KeyboardLayouts.pad(policy.pad ?: PadKind.NUMBER, enter)
        })
        // Swipe typing only where suggestions are allowed: never in a password or PIN box, never in the Secure Input step.
        v.gesturesEnabled = optSwipe && page == Page.LETTERS && policy.suggestions && !Privacy.secureStep
    }

    /** Sentence capitals where the editor asks for them. True when the shift state changed. */
    private fun autoCapitalize(): Boolean {
        if (page != Page.LETTERS || !policy.autoCapitalize || shift.state == ShiftState.LOCKED) return false
        val ic = currentInputConnection ?: return false
        val before = shift.state
        shift.autoCapitalize(try { ic.getCursorCapsMode(inputType) != 0 } catch (_: Throwable) { false })
        return shift.state != before
    }

    private fun refreshSuggestions() {
        val v = view ?: return
        if (variantsOpen || voice?.listening == true || listeningShown) return
        if (!optSuggest || !policy.suggestions || page != Page.LETTERS) { v.showSuggestions(emptyList()); return }
        val word = WordAtCursor.of(currentInputConnection?.getTextBeforeCursor(WINDOW, 0)).text
        if (word.isEmpty() && choices.isNotEmpty()) { v.showSuggestions(choices); return }   // the last swipe's other words stay on offer
        if (word.isNotEmpty()) forgetSwipe()
        v.showSuggestions(suggester.suggest(word, language == KbLanguage.TWI))
    }

    private fun forgetSwipe() { choices = emptyList(); committed = null }

    // ------------------------------------------------------------------------------------ keys

    override fun onKeyDown(key: Key) {
        if (optVibrate) tick()
        if (optSound) {
            val fx = when (key.kind) {
                KeyKind.BACKSPACE -> AudioManager.FX_KEYPRESS_DELETE
                KeyKind.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                KeyKind.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            // The volume overload plays even when system touch sounds are off — this is the keyboard's own switch.
            try { getSystemService(AudioManager::class.java)?.playSoundEffect(fx, -1f) } catch (_: Throwable) {}
        }
    }

    override fun onKey(key: Key) {
        if (variantsOpen) closeVariants()
        val auto = autoSpaced
        autoSpaced = false
        if (key.kind != KeyKind.BACKSPACE && key.kind != KeyKind.SHIFT) forgetSwipe()   // delete has its own use for the last swipe
        when (key.kind) {
            KeyKind.CHAR -> type(key.text, afterAutoSpace = auto)
            KeyKind.SPACE -> {
                val now = SystemClock.uptimeMillis()
                val before = currentInputConnection?.getTextBeforeCursor(1, 0)
                if (auto && before?.length == 1 && before[0] == ' ') {
                    // The swipe (or the chosen word) already left this space: the press confirms it, nothing doubles.
                    lastSpaceMs = now
                } else if (lastSpaceMs > 0 && now - lastSpaceMs < 400 && policy.autoCapitalize &&
                    before?.length == 1 && before[0] == ' ') {
                    currentInputConnection?.let { ic ->
                        ic.deleteSurroundingText(1, 0)
                        ic.commitText(". ", 1)
                        habit.onOtherInput()
                    }
                    lastSpaceMs = 0
                    if (autoCapitalize()) render()
                } else {
                    finishWord(" ")
                    lastSpaceMs = now
                }
            }
            KeyKind.ENTER -> pressEnter()
            KeyKind.BACKSPACE -> backspace()
            KeyKind.SHIFT -> {
                val s = shift.tap(SystemClock.uptimeMillis())
                render()
                view?.say(when (s) { ShiftState.OFF -> "Capitals off"; ShiftState.ONCE -> "Capital next"; ShiftState.LOCKED -> "Caps lock on" })
            }
            KeyKind.SYMBOLS -> switchPage(Page.SYMBOLS, "Numbers and symbols")
            KeyKind.MORE_SYMBOLS -> switchPage(Page.MORE_SYMBOLS, "More symbols")
            KeyKind.LETTERS -> { switchPage(Page.LETTERS, "Letters"); if (autoCapitalize()) render() }
            KeyKind.LANGUAGE -> {
                language = if (language == KbLanguage.TWI) KbLanguage.ENGLISH else KbLanguage.TWI
                prefs.language = language
                render()
                view?.say(if (language == KbLanguage.TWI) "Twi keyboard" else "English keyboard")
                refreshSuggestions()
            }
            KeyKind.SPACER -> {}
        }
    }

    override fun onKeyLongPress(key: Key): Boolean = when (key.kind) {
        KeyKind.CHAR -> when (key.variants.size) {
            0 -> false
            // One option only (a top-row number, ¿, a backslash): typed straight away, as Gboard does, and said.
            1 -> { forgetSwipe(); autoSpaced = false; type(key.variants[0]); view?.say(KeyNames.spoken(key.variants[0])); true }
            else -> { openVariants(key.variants); true }
        }
        KeyKind.SHIFT -> { shift.lock(); render(); view?.say("Caps lock on"); true }
        KeyKind.BACKSPACE -> { deleteWord(); true }
        KeyKind.SPACE, KeyKind.LANGUAGE -> { showKeyboardPicker(); true }
        else -> false
    }

    override fun onBackspaceRepeat() = backspace()

    override fun onSuggestion(word: String) {
        val ic = currentInputConnection ?: return
        val last = committed
        if (last != null && word in choices && ic.getTextBeforeCursor(last.length, 0)?.toString() == last) {
            // One of the last swipe's other words: it takes the place of the word the swipe chose, which joins the offers.
            val others = listOf(last.trim()) + choices.filter { it != word }
            commitWord(word, others, leadingSpace = last.startsWith(" "), replacing = last.length)
            return
        }
        val typed = WordAtCursor.of(ic.getTextBeforeCursor(WINDOW, 0)).text
        commitWord(word, emptyList(), replacing = typed.length)
    }

    override fun onVariant(text: String) {
        if (variantsOpen) closeVariants()
        val auto = autoSpaced
        autoSpaced = false
        forgetSwipe()
        type(text, afterAutoSpace = auto)
    }

    override fun onCloseVariants() { closeVariants(); refreshSuggestions() }

    // ------------------------------------------------------------------------------------ swipe typing (G-05 (2))

    /** The finger crossed [path] and lifted: the best word goes in, the runners-up wait in the strip. Nothing is guessed silently. */
    override fun onGesture(path: List<GestureDecoder.Step>) {
        if (variantsOpen) closeVariants()
        autoSpaced = false
        val twi = language == KbLanguage.TWI
        val sources = ArrayList<GestureDecoder.Source>(4)
        if (twi) sources += GestureDecoder.Source(lexicon.words, bonus = 1.0, scale = WordScales.TWI)
        sources += GestureDecoder.Source(english.words)
        if (!twi) sources += GestureDecoder.Source(lexicon.words, bonus = -0.5, scale = WordScales.TWI)   // a Twi name on the English keyboard
        if (optLearn && policy.learning) sources += GestureDecoder.Source(LearnedWordStore.get(this).view().toList(), bonus = 0.5, scale = WordScales.LEARNED)
        val words = GestureDecoder.decode(path, sources, 4)
        if (words.isEmpty()) {
            forgetSwipe()
            val msg = "No word for that swipe"
            view?.showMessage(msg)
            view?.say(msg)
            return
        }
        val cased = words.map(::cased)
        commitWord(cased[0], cased.drop(1))
    }

    /** A swiped word follows the shift key like a typed one: sentence capital, or caps lock. */
    private fun cased(w: String): String = when (shift.state) {
        ShiftState.OFF -> w
        ShiftState.ONCE -> w.replaceFirstChar { it.uppercaseChar() }
        ShiftState.LOCKED -> w.uppercase()
    }

    /**
     * [word] goes in with a space after it — and one before when [leadingSpace], or when it would otherwise glue to a
     * word — after [replacing] characters before the cursor are removed. [others] are offered in the strip (a tap swaps
     * the word) until the next key.
     */
    private fun commitWord(word: String, others: List<String>, leadingSpace: Boolean = false, replacing: Int = 0) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        if (replacing > 0) ic.deleteSurroundingText(replacing, 0)
        val before = ic.getTextBeforeCursor(1, 0)
        val space = leadingSpace || (replacing == 0 && !before.isNullOrEmpty() &&
            (before[0].isLetterOrDigit() || before[0] == '\'' || before[0] == '’'))
        val text = (if (space) " " else "") + word + " "
        ic.commitText(text, 1)
        ic.endBatchEdit()
        habit.onOtherInput()
        committed = text
        choices = others
        autoSpaced = true
        learn(word)
        if (shift.state == ShiftState.ONCE && word.firstOrNull()?.isLetter() == true) { shift.afterCharacter(); render() }
        if (others.isNotEmpty() && optSuggest && policy.suggestions) view?.showSuggestions(others)
        view?.say(word)
    }

    private fun switchPage(p: Page, spoken: String) {
        page = p
        render()
        view?.say(spoken)
        refreshSuggestions()
    }

    private fun type(text: String, afterAutoSpace: Boolean = false) {
        val ic = currentInputConnection ?: return
        if (text.length == 1 && text[0] in WORD_END_PUNCTUATION) {
            // "hello ." → "hello.": the space a swipe or a chosen word left behind gives way to punctuation.
            if (afterAutoSpace && ic.getTextBeforeCursor(1, 0)?.toString() == " ") ic.deleteSurroundingText(1, 0)
            finishWord(text)
            return
        }
        ic.commitText(text, 1)
        habit.onOtherInput()
        if (page == Page.LETTERS && text.firstOrNull()?.isLetter() == true && shift.state == ShiftState.ONCE) {
            shift.afterCharacter()
            render()
        }
    }

    /** A word ends with [separator] (space, punctuation, or "" before enter): learn it and apply the 3/c habit. */
    private fun finishWord(separator: String) {
        val ic = currentInputConnection ?: return
        val hasSelection = !ic.getSelectedText(0).isNullOrEmpty()
        val word = if (hasSelection) WordAtCursor.Word("", null) else WordAtCursor.of(ic.getTextBeforeCursor(WINDOW, 0))
        val habitOn = language == KbLanguage.TWI && optHabit && policy.habitConversion && !Privacy.secureStep
        val r = if (habitOn) habit.onWordEnd(word.text, word.before, separator) else { habit.onOtherInput(); null }
        if (r != null) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(r.deleteBefore, 0)
            ic.commitText(r.insert, 1)
            ic.endBatchEdit()
            learn(r.insert.removeSuffix(separator))
        } else {
            if (separator.isNotEmpty()) ic.commitText(separator, 1)
            learn(word.text)
        }
    }

    private fun learn(word: String) {
        if (!optLearn || !policy.learning || Privacy.secureStep || word.isEmpty() || word in english) return
        LearnedWordStore.get(this).learn(word, lexicon)
    }

    private fun backspace() {
        val ic = currentInputConnection ?: return
        val last = committed
        if (last != null && choices.isNotEmpty() && ic.getTextBeforeCursor(last.length, 0)?.toString() == last) {
            // Straight after a swipe, delete takes the whole word back, offers and all (Gboard's habit).
            forgetSwipe()
            autoSpaced = false
            ic.deleteSurroundingText(last.length, 0)
            habit.onOtherInput()
            view?.say("Deleted ${last.trim()}")
            return
        }
        forgetSwipe()
        habit.onBackspace(ic.getTextBeforeCursor(WINDOW, 0))?.let { r ->
            ic.beginBatchEdit()
            ic.deleteSurroundingText(r.deleteBefore, 0)
            ic.commitText(r.insert, 1)
            ic.endBatchEdit()
            view?.say("Changed back to ${r.insert}")
            return
        }
        if (!ic.getSelectedText(0).isNullOrEmpty()) { ic.commitText("", 1); return }
        // Nothing before the cursor: send a real DEL key so apps that act on it (e.g. removing a chip) still can.
        if (ic.getTextBeforeCursor(1, 0).isNullOrEmpty()) sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        else ic.deleteSurroundingTextInCodePoints(1, 0)   // an emoji or a surrogate pair goes in one press
    }

    private fun deleteWord() {
        val ic = currentInputConnection ?: return
        habit.onOtherInput()
        if (!ic.getSelectedText(0).isNullOrEmpty()) { ic.commitText("", 1); return }
        val n = WordAtCursor.deleteWordLength(ic.getTextBeforeCursor(WINDOW, 0))
        ic.deleteSurroundingText(n, 0)
        view?.say("Word deleted")
    }

    private fun pressEnter() {
        val ic: InputConnection = currentInputConnection ?: return
        when (enter.behaviour) {
            EnterBehaviour.NEW_LINE -> finishWord("\n")
            EnterBehaviour.EDITOR_ACTION -> { finishWord(""); ic.performEditorAction(enter.actionId) }
            EnterBehaviour.KEY_EVENT -> { finishWord(""); sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER) }
        }
    }

    private fun openVariants(variants: List<String>) {
        variantsOpen = true
        view?.showVariants(variants)
        view?.say("Options in the top row: " + variants.joinToString(", ") { KeyNames.spoken(it) })
    }

    private fun closeVariants() { variantsOpen = false; view?.showSuggestions(emptyList()) }

    /** The system keyboard chooser — the way back to the user's other keyboard (Gboard, Samsung). */
    private fun showKeyboardPicker() {
        try { getSystemService(InputMethodManager::class.java)?.showInputMethodPicker() } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------------------------ feedback

    private fun tick() {
        val vib = getSystemService(Vibrator::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= 29) vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            else vib.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Throwable) {}
    }

    /** A short tone before the microphone opens, so a blind user knows when to speak (a spoken "speak now" would be
     *  heard by the recogniser itself). */
    private fun listenCue() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 60)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            view?.postDelayed({ tg.release() }, 400)
        } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------------------------ voice key

    override fun onVoiceKey() {
        if (Dictation.active) { Dictation.cancel(); view?.say("Stopped"); return }   // our mic: a second tap cancels
        val v = voice ?: VoiceTyping(this, this).also { voice = it }
        if (v.listening) { v.stop(); return }
        if (listeningShown) { onIdle(); return }   // tapped again before the microphone opened: don't open it
        val micGranted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val voiceKeyboard = voiceKeyboard()
        when (val route = VoicePlan.route(policy.voice && !Privacy.secureStep, micGranted, v.available(), voiceKeyboard != null, ownAsrReady())) {
            VoiceRoute.OWN_ASR -> beginListening("Starting voice typing…") {
                // §3(a): our own mic + recogniser. A refusal before the mic opens falls back to the system route.
                if (!Dictation.start(it == KbLanguage.TWI, dictationEars(it, fallbackAvailable = v.available()))) onIdle()
            }
            VoiceRoute.IN_KEYBOARD -> beginListening("Starting voice typing…") { v.start(it) }
            VoiceRoute.VOICE_KEYBOARD -> switchToVoiceKeyboard()
            else -> VoicePlan.explain(route).let { view?.showMessage(it); view?.say(it) }
        }
    }

    /** §3(a): our own dictation is the first choice when the config flag is on and the voice stack can serve it. */
    private fun ownAsrReady(): Boolean =
        AppConfig.bool("ime.dictation.ownAsr", true) &&
            try { Dictation.available(language == KbLanguage.TWI) } catch (_: Throwable) { false }

    /** The shared listen choreography: cue, then start [go] after 250 ms. The field may change (or the keyboard
     *  close) during the cue: re-check it's still this field's voice turn — a password field or a Secure Input
     *  step never gets the mic. */
    private fun beginListening(message: String, go: (KbLanguage) -> Unit) {
        if (variantsOpen) closeVariants()
        listeningShown = true
        view?.setVoiceState(TwiKeyboardView.VoiceKeyState.LISTENING)
        view?.showMessage(message)
        listenCue()
        val lang = language
        val start = Runnable { if (listeningShown && isInputViewShown && policy.voice && !Privacy.secureStep) go(lang) }
        pendingVoiceStart = start
        view?.postDelayed(start, 250)
    }

    /** Dictation callbacks → the same show/say/commit paths the system route uses. [fallbackAvailable] = the
     *  phone's SpeechRecognizer existed at tap time, so a stack-not-ready refusal can hand the attempt over. */
    private fun dictationEars(lang: KbLanguage, fallbackAvailable: Boolean) = object : Dictation.Listener {
        private var fellBack = false
        override fun onListening() {
            view?.setVoiceState(TwiKeyboardView.VoiceKeyState.LISTENING)
            view?.showMessage("Listening… I stop when you pause.")
            view?.say("Listening")
        }
        override fun onText(text: String) { commitDictation(text, lang) }
        override fun onNothing(message: String, fallbackToSystem: Boolean) {
            if (fallbackToSystem) {
                if (fallbackAvailable) { fellBack = true; return }   // onDone starts the system route instead
                VoicePlan.explain(VoiceRoute.UNAVAILABLE).let { listeningShown = false; view?.showMessage(it); view?.say(it) }
                return
            }
            listeningShown = false   // so onIdle below leaves this honest line in the strip
            view?.showMessage(message)
            view?.say(message)
        }
        override fun onDone() {
            if (!fellBack) { onIdle(); return }
            val v = voice ?: VoiceTyping(this@TwiKeyboardService, this@TwiKeyboardService).also { voice = it }
            // The cue already played at the tap; keep the listening state and hand straight over.
            if (listeningShown && isInputViewShown && policy.voice && !Privacy.secureStep) v.start(lang) else onIdle()
        }
    }

    /** Long-press the microphone: go straight to the phone's voice keyboard (it returns here when done). */
    override fun onVoiceLongPress(): Boolean {
        if (!policy.voice || Privacy.secureStep) { VoicePlan.explain(VoiceRoute.OFF_IN_THIS_FIELD).let { view?.showMessage(it); view?.say(it) }; return true }
        if (voiceKeyboard() == null) { VoicePlan.explain(VoiceRoute.UNAVAILABLE).let { view?.showMessage(it); view?.say(it) }; return true }
        switchToVoiceKeyboard()
        return true
    }

    private fun voiceKeyboard() =
        getSystemService(InputMethodManager::class.java)?.let { VoiceKeyboards.find(it, KeyboardSetup.ownId(this)) }

    private fun switchToVoiceKeyboard() {
        val (imi, subtype) = voiceKeyboard() ?: return
        try {
            if (Build.VERSION.SDK_INT >= 28) switchInputMethod(imi.id, subtype)
            else {
                val token = window?.window?.attributes?.token ?: return
                @Suppress("DEPRECATION")
                getSystemService(InputMethodManager::class.java)?.setInputMethodAndSubtype(token, imi.id, subtype)
            }
        } catch (_: Throwable) {
            VoicePlan.explain(VoiceRoute.UNAVAILABLE).let { view?.showMessage(it); view?.say(it) }
        }
    }

    override fun onListening() {
        view?.setVoiceState(TwiKeyboardView.VoiceKeyState.LISTENING)
        view?.showMessage("Listening… tap the microphone when you finish.")
        view?.say("Listening")   // §3(b): a blind user hears when the microphone really opens
    }

    override fun onPartial(text: String) { view?.showMessage(text) }

    override fun onFinal(text: String, spokenIn: KbLanguage) { commitDictation(text, spokenIn) }

    /** Recognised speech → the field, one path for both voice routes. Announces what went in (§3(b)). */
    private fun commitDictation(text: String, spokenIn: KbLanguage) {
        // The field may have changed, or the agent's Secure Input step begun, while the user spoke.
        if (!policy.voice || Privacy.secureStep) return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(WINDOW, 0)
        val capital = policy.autoCapitalize && try { ic.getCursorCapsMode(inputType) != 0 } catch (_: Throwable) { false }
        val insert = DictationText.forInsertion(text, spokenIn == KbLanguage.TWI, capital, before)
        if (insert.isNotEmpty()) { ic.commitText(insert, 1); view?.say(DictationText.announceTyped(insert)) }
        habit.onOtherInput()
        forgetSwipe()
        autoSpaced = false
    }

    override fun onVoiceError(message: String) {
        listeningShown = false
        view?.showMessage(message)
        view?.say(message)
    }

    override fun onIdle() {
        val wasListening = listeningShown
        listeningShown = false
        view?.setVoiceState(if (policy.voice) TwiKeyboardView.VoiceKeyState.IDLE else TwiKeyboardView.VoiceKeyState.OFF)
        if (wasListening) refreshSuggestions()
    }

    companion object {
        /** How much text before the cursor is read to find the current word — a word, not the message. */
        private const val WINDOW = 48
        private const val WORD_END_PUNCTUATION = ".,?!;:"
    }
}
