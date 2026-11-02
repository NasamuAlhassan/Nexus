package com.tekyerema

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.tekyerema.ime.DictationText
import com.tekyerema.ime.EnterBehaviour
import com.tekyerema.ime.EnterKeys
import com.tekyerema.ime.FieldPolicy
import com.tekyerema.ime.KeyKind
import com.tekyerema.ime.KeyNames
import com.tekyerema.ime.KeyboardLayout
import com.tekyerema.ime.KeyboardLayouts
import com.tekyerema.ime.KeyboardSetupLogic
import com.tekyerema.ime.KeyboardSetupText
import com.tekyerema.ime.KeyboardStatus
import com.tekyerema.ime.LearnedWords
import com.tekyerema.ime.Page
import com.tekyerema.ime.PadKind
import com.tekyerema.ime.ShiftMachine
import com.tekyerema.ime.ShiftState
import com.tekyerema.ime.TwiLexicon
import com.tekyerema.ime.VoicePlan
import com.tekyerema.ime.VoiceRoute
import com.tekyerema.ime.WordAtCursor
import com.tekyerema.ime.WordSuggester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** G-05 — the Nexus keyboard's pure logic (English only since 31 Oct 2026): layouts and spoken key names, shift, the
 *  word at the cursor, the enter key, the per-field safety policy (passwords/PINs), suggestions and learned words, the
 *  voice-key route, setup status — and a guard that nothing in ime/ logs or touches the network. */
class TwiKeyboardTest {

    private val enter = EnterKeys.of(EditorInfo.IME_ACTION_SEND, InputType.TYPE_CLASS_TEXT)
    private fun chars(l: KeyboardLayout) = l.rows.flatten().filter { it.kind == KeyKind.CHAR }.map { it.text }
    private fun allLayouts(): List<KeyboardLayout> =
        ShiftState.values().map { KeyboardLayouts.letters(it, enter) } +
            KeyboardLayouts.symbols(enter) + KeyboardLayouts.moreSymbols(enter) +
            PadKind.values().map { KeyboardLayouts.pad(it, enter) }

    // ------------------------------------------------------------------------------------------ layouts

    @Test fun the_keyboard_is_plain_qwerty_with_the_akan_letters_on_long_press_and_no_language_key() {
        val l = KeyboardLayouts.letters(ShiftState.OFF, enter)
        assertEquals(listOf("Shift", "z", "x", "c", "v"), l.rows[2].take(5).map { if (it.kind == KeyKind.SHIFT) "Shift" else it.text })
        assertTrue("ɛ" !in chars(l) && "ɔ" !in chars(l))
        assertEquals("qwertyuiop".map { it.toString() }, l.rows[0].map { it.text })
        val e = l.rows[0].first { it.text == "e" }
        val o = l.rows[0].first { it.text == "o" }
        assertEquals("ɛ", e.variants.first())
        assertEquals("ɔ", o.variants.first())
        assertTrue("ŋ" in l.rows[2].first { it.text == "n" }.variants)
    }

    @Test fun shift_gives_capitals_and_capital_options() {
        val l = KeyboardLayouts.letters(ShiftState.ONCE, enter)
        assertEquals("Q", l.rows[0][0].text)
        assertEquals("Ɛ", l.rows[0].first { it.text == "E" }.variants.first())
        assertEquals("Ɔ", l.rows[0].first { it.text == "O" }.variants.first())
        assertEquals("⇪", KeyboardLayouts.letters(ShiftState.LOCKED, enter).rows[2][0].label)
    }

    @Test fun every_row_is_ten_units_wide_and_the_pad_is_four() {
        for (l in allLayouts()) for (row in l.rows) {
            val w = row.sumOf { it.width.toDouble() }
            assertEquals("${l.page} row ${row.map { it.label }}", if (l.page == Page.PAD) 4.0 else 10.0, w, 0.001)
        }
    }

    @Test fun every_key_a_blind_user_can_touch_has_a_spoken_name() {
        for (l in allLayouts()) for (k in l.rows.flatten().filter { it.kind != KeyKind.SPACER }) {
            assertTrue("${k.label} has no spoken name", k.spoken.isNotBlank())
            for (v in k.variants) assertTrue("variant $v", KeyNames.spoken(v).isNotBlank())
        }
    }

    @Test fun shift_changes_keep_the_same_shape_so_talkback_focus_stays() {
        val a = KeyboardLayouts.letters(ShiftState.OFF, enter)
        assertEquals(a.shape(), KeyboardLayouts.letters(ShiftState.LOCKED, enter).shape())
        assertEquals(a.shape(), KeyboardLayouts.letters(ShiftState.ONCE, enter).shape())
    }

    @Test fun the_symbols_page_has_the_cedi_sign_first_among_currencies() {
        val s = KeyboardLayouts.symbols(enter)
        val cedi = s.rows.flatten().first { it.text == "₵" }
        assertEquals("cedi sign", cedi.spoken)
        assertEquals("GH₵", cedi.variants.first())
        assertEquals((0..9).map { ((it + 1) % 10).toString() }, s.rows[0].map { it.text })
    }

    @Test fun number_fields_get_a_pad_and_a_pin_pad_has_digits_only() {
        assertEquals((0..9).map { it.toString() }.toSet(), chars(KeyboardLayouts.pad(PadKind.PIN, enter)).toSet())
        val phone = chars(KeyboardLayouts.pad(PadKind.PHONE, enter))
        assertTrue(phone.containsAll(listOf("+", "*", "#")))
        assertTrue(KeyboardLayouts.pad(PadKind.NUMBER, enter).rows.flatten().any { it.kind == KeyKind.LETTERS })
    }

    @Test fun keys_are_named_for_talkback() {
        assertEquals("ɛ, open e", KeyNames.spoken("ɛ"))
        assertEquals("Ɔ, capital open O", KeyNames.spoken("Ɔ"))
        assertEquals("e acute", KeyNames.spoken("é"))
        assertEquals("capital o grave", KeyNames.spoken("Ò"))
        assertEquals("capital a", KeyNames.spoken("A"))
        assertEquals("k", KeyNames.spoken("k"))
        assertEquals("full stop", KeyNames.spoken("."))
        assertEquals("Shift, caps lock on", KeyNames.shift(ShiftState.LOCKED))
    }

    // ------------------------------------------------------------------------------------------ typing

    @Test fun shift_once_caps_lock_and_auto_capitals() {
        val s = ShiftMachine(doubleTapMs = 400)
        assertEquals(ShiftState.ONCE, s.tap(1_000))
        s.afterCharacter(); assertEquals(ShiftState.OFF, s.state)
        s.tap(2_000); assertEquals(ShiftState.LOCKED, s.tap(2_200))           // quick double tap
        s.afterCharacter(); assertEquals(ShiftState.LOCKED, s.state)          // caps lock survives letters
        s.autoCapitalize(false); assertEquals(ShiftState.LOCKED, s.state)     // and the editor can't undo it
        assertEquals(ShiftState.OFF, s.tap(3_000))
        s.tap(4_000); assertEquals(ShiftState.OFF, s.tap(5_000))              // slow second tap = off
        assertEquals(ShiftState.LOCKED, s.lock())                             // long-press (TalkBack double-tap-hold)
        s.reset(); s.autoCapitalize(true); assertEquals(ShiftState.ONCE, s.state)
    }

    @Test fun the_word_at_the_cursor() {
        assertEquals(WordAtCursor.Word("wor", ' '), WordAtCursor.of("hello wor"))
        assertEquals("don't", WordAtCursor.of("I don't").text)
        assertEquals(WordAtCursor.Word("quote", '\''), WordAtCursor.of("'quote"))
        assertEquals(WordAtCursor.Word("", null), WordAtCursor.of(null))
        assertEquals(7, WordAtCursor.deleteWordLength("hello world  "))
        assertEquals(2, WordAtCursor.deleteWordLength("hi, "))
        assertEquals(1, WordAtCursor.deleteWordLength(""))
    }

    @Test fun the_enter_key_follows_the_field() {
        val send = EnterKeys.of(EditorInfo.IME_ACTION_SEND, InputType.TYPE_CLASS_TEXT)
        assertEquals(EnterBehaviour.EDITOR_ACTION, send.behaviour); assertEquals("Send", send.label)
        assertEquals(EditorInfo.IME_ACTION_SEND, send.actionId)
        assertEquals("Next field", EnterKeys.of(EditorInfo.IME_ACTION_NEXT, InputType.TYPE_CLASS_TEXT).spoken)
        val chat = EnterKeys.of(EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        assertEquals(EnterBehaviour.NEW_LINE, chat.behaviour)
        assertEquals(EnterBehaviour.KEY_EVENT, EnterKeys.of(EditorInfo.IME_ACTION_UNSPECIFIED, InputType.TYPE_CLASS_TEXT).behaviour)
    }

    // ------------------------------------------------------------------------------------------ field safety

    @Test fun password_and_pin_fields_get_no_suggestions_learning_voice_or_conversion() {
        val types = listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        for (t in types) {
            assertTrue(FieldPolicy.isPasswordType(t))
            val p = FieldPolicy.of(t, 0, null, secureStep = false)
            assertTrue(p.sensitive); assertFalse(p.suggestions); assertFalse(p.learning); assertFalse(p.voice)
            assertFalse(p.autoCapitalize)
        }
        assertEquals(PadKind.PIN, FieldPolicy.of(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, 0, null, false).pad)
    }

    @Test fun a_pin_box_that_is_not_a_password_field_is_caught_by_its_hint_or_the_secure_step() {
        // USSD / MoMo PIN dialogs are often plain number or text fields.
        val byHint = FieldPolicy.of(InputType.TYPE_CLASS_NUMBER, 0, "Enter PIN", secureStep = false)
        assertTrue(byHint.sensitive); assertEquals(PadKind.PIN, byHint.pad)
        assertTrue(FieldPolicy.of(InputType.TYPE_CLASS_TEXT, 0, "Hyɛ wo PIN", false).sensitive)
        assertTrue(FieldPolicy.of(InputType.TYPE_CLASS_TEXT, 0, "One-time code (OTP)", false).sensitive)
        val bySecureStep = FieldPolicy.of(InputType.TYPE_CLASS_TEXT, 0, "Reply", secureStep = true)
        assertTrue(bySecureStep.sensitive); assertFalse(bySecureStep.voice); assertFalse(bySecureStep.learning)
        assertFalse(FieldPolicy.of(InputType.TYPE_CLASS_TEXT, 0, "Spinach recipe", false).sensitive)
    }

    @Test fun ordinary_and_special_text_fields() {
        val msg = FieldPolicy.of(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 0, "Message", false)
        assertFalse(msg.sensitive); assertTrue(msg.suggestions); assertTrue(msg.learning); assertTrue(msg.voice)
        assertEquals(Page.LETTERS, msg.startPage)
        val incognito = FieldPolicy.of(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, null, false)
        assertTrue(incognito.suggestions); assertFalse(incognito.learning)
        val email = FieldPolicy.of(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 0, null, false)
        assertFalse(email.suggestions); assertFalse(email.learning); assertFalse(email.autoCapitalize)
        val noSuggest = FieldPolicy.of(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 0, null, false)
        assertFalse(noSuggest.suggestions); assertFalse(noSuggest.learning)
        val phone = FieldPolicy.of(InputType.TYPE_CLASS_PHONE, 0, null, false)
        assertEquals(Page.PAD, phone.startPage); assertEquals(PadKind.PHONE, phone.pad); assertFalse(phone.learning)
    }

    // ------------------------------------------------------------------------------------------ suggestions

    private val english = TwiLexicon.parse(sequenceOf("# comment", "", "the\t22761659", "they\t5000000", "then\t3000000",
        "me\t8500000", "mean\t500000", "cat\t200000", "come\t2000000", "Kwabɛna\t300", "Frε\t3"))

    @Test fun the_lexicon_parses_and_normalises() {
        assertEquals(9, english.size)
        assertTrue("kwabɛna" in english)        // lower-cased
        assertTrue("The" in english)
    }

    @Test fun english_words_are_suggested_with_case_following_the_user() {
        val s = WordSuggester({ english })
        assertEquals("the", s.suggest("th").first())
        assertTrue("mean" in s.suggest("me"))
        assertEquals("Come", s.suggest("Co").first())
        assertEquals("THE", s.suggest("TH").first())
        assertTrue(s.suggest("me", max = 2).size <= 2)
        assertFalse("the finished word isn't offered back", "the" in s.suggest("the"))
        assertTrue(s.suggest("").isEmpty())
        assertTrue(WordSuggester().suggest("th").isEmpty())
    }

    @Test fun a_name_with_akan_letters_is_found_by_its_plain_spelling() {
        val s = WordSuggester({ english })
        assertEquals("kwabɛna", s.suggest("kwabe").first())
        assertEquals("kwabɛna", s.suggest("kwabena").first())   // a whole-word correction
    }

    @Test fun learned_words_are_suggested_and_never_hold_digits_or_known_words() {
        val lw = LearnedWords(max = 3)
        assertTrue(lw.learn("Abrokyire", english))
        assertFalse(lw.learn("0244123456", english))      // a phone number
        assertFalse(lw.learn("1234", english))            // a PIN
        assertFalse(lw.learn("pass3word", english))
        assertFalse(lw.learn("then", english))            // the bundled list already has it
        assertFalse(lw.learn("a", english))
        val s = WordSuggester({ english }) { lw.view() }
        assertEquals("abrokyire", s.suggest("abro").first())
        // Over the cap the least-used word goes.
        lw.learn("abrokyire", english); lw.learn("kwaku", english); lw.learn("ananse", english); lw.learn("zzz", english)
        assertEquals(3, lw.size)
        assertTrue("abrokyire" in lw.view())
        val round = LearnedWords.parse(lw.serialize())
        assertEquals(lw.view(), round.view())
        assertFalse(lw.dirty)
        lw.clear(); assertTrue(lw.dirty); assertEquals(0, lw.size)
        assertEquals(0, LearnedWords.parse("1234\t9\nok\tx\n").size)   // junk in the file is ignored
    }

    @Test fun the_twi_word_list_is_gone() {
        assertFalse(File("src/main/assets/ime/twi_words.tsv").exists())
    }

    @Test fun the_bundled_english_list_is_large_credited_and_clean() {
        val text = File("src/main/assets/ime/en_words.tsv").readText()
        assertTrue(text.contains("MIT License"))
        val lex = TwiLexicon.parse(text.lineSequence())
        assertTrue("${lex.size}", lex.size in 15_000..30_000)
        for (w in listOf("the", "hello", "please", "money", "tomorrow")) assertTrue(w, w in lex)
        for ((w, c) in lex.words) assertTrue(w, c > 0 && w.all { it.isLetter() || it == '\'' } && w.first().isLetter())
        assertEquals("the most common word first", lex.words.maxOf { it.second }, lex.words.first().second)
        assertEquals("folded keys line up with the words", lex.words.size, lex.folded.size)
    }

    // ------------------------------------------------------------------------------------------ voice key

    @Test fun the_voice_key_route() {
        assertEquals(VoiceRoute.OFF_IN_THIS_FIELD, VoicePlan.route(false, true, true, true))
        assertEquals(VoiceRoute.IN_KEYBOARD, VoicePlan.route(true, true, true, true))
        assertEquals(VoiceRoute.VOICE_KEYBOARD, VoicePlan.route(true, false, true, true))
        assertEquals(VoiceRoute.UNAVAILABLE, VoicePlan.route(true, false, false, false))
        assertTrue(VoicePlan.explain(VoiceRoute.OFF_IN_THIS_FIELD).contains("PIN"))
        val own = "com.tekyerema/.ime.TwiKeyboardService"
        val list = listOf(Triple(own, 0, "voice"), Triple("com.samsung/.Kb", 0, "keyboard"), Triple("com.google/.Voice", 1, "voice"))
        assertEquals("com.google/.Voice" to 1, VoicePlan.pickVoiceKeyboard(list, own))
        assertNull(VoicePlan.pickVoiceKeyboard(list.take(2), own))
        assertEquals("en-GH", VoicePlan.LANGUAGE_TAG)
        for (code in 1..15) assertTrue(VoicePlan.errorMessage(code).isNotBlank())
        assertTrue(VoicePlan.isLanguageError(12) && !VoicePlan.isLanguageError(7))
    }

    @Test fun dictated_text_is_spaced_capitalised_and_spelled_properly() {
        assertEquals(" call me later", DictationText.forInsertion("  call  me later ", capitalize = false, textBefore = "Hi"))
        assertEquals("Call me", DictationText.forInsertion("call me", true, ""))
        assertEquals("Call me", DictationText.forInsertion("call me", capitalize = true, textBefore = "("))
        assertEquals("", DictationText.forInsertion("   ", false, "x"))
    }

    // ------------------------------------------------------------------------------------------ setup

    @Test fun setup_status_reads_both_id_forms() {
        val own = "com.tekyerema/.ime.TwiKeyboardService"
        val longForm = "com.tekyerema/com.tekyerema.ime.TwiKeyboardService"
        assertEquals(KeyboardStatus.NOT_ENABLED, KeyboardSetupLogic.status(listOf("com.google/.Latin"), "com.google/.Latin", own))
        assertEquals(KeyboardStatus.ENABLED_NOT_SELECTED, KeyboardSetupLogic.status(listOf(longForm), "com.google/.Latin", own))
        assertEquals(KeyboardStatus.IN_USE, KeyboardSetupLogic.status(listOf(own), longForm, own))
        assertNull(KeyboardSetupLogic.normalize(""))
    }

    @Test fun setup_steps_read_well_aloud() {
        for (s in KeyboardStatus.values()) {
            val t = KeyboardSetupText.steps(s)
            assertTrue(t.isNotBlank())
            assertFalse("no arrows or symbols a voice would read out: $t", t.any { it in "›>•→/" })
        }
        assertTrue(KeyboardSetupText.steps(KeyboardStatus.NOT_ENABLED).contains(KeyboardSetupText.NAME))
    }

    // ------------------------------------------------------------------------------------------ privacy guards

    @Test fun nothing_in_the_keyboard_logs_or_uses_the_network() {
        val files = File("src/main/java/com/tekyerema/ime").walk().filter { it.extension == "kt" }.toList()
        assertTrue(files.size >= 5)
        val banned = listOf(
            Regex("import android\\.util\\.Log"), Regex("\\bLog\\.[vdiwe]\\("), Regex("\\bprintln\\("),
            Regex("import okhttp3"), Regex("import java\\.net"), Regex("HttpURLConnection"), Regex("\\bURL\\("),
            Regex("Trajectories|FieldLog|AuditLog|HistoryStore|Telemetry"))
        for (f in files) {
            val src = f.readText()
            for (b in banned) assertFalse("${f.name} must not match ${b.pattern}", b.containsMatchIn(src))
        }
    }

    @Test fun the_manifest_declares_the_keyboard_to_the_system_only() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val service = manifest.substringAfter("android:name=\".ime.TwiKeyboardService\"").substringBefore("</service>")
        assertTrue(service.contains("android.permission.BIND_INPUT_METHOD"))
        assertTrue(service.contains("android.view.InputMethod"))
        assertTrue(service.contains("@xml/method"))
        assertTrue(File("src/main/res/xml/method.xml").readText().contains("imeSubtypeMode=\"keyboard\""))
    }
}
