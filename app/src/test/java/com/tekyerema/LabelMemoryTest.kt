package com.tekyerema

import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.core.config.AppConfig
import com.tekyerema.memory.LabelCmd
import com.tekyerema.memory.LabelCore
import com.tekyerema.memory.LabelEntry
import com.tekyerema.memory.LabelInference
import com.tekyerema.memory.LabelParse
import com.tekyerema.memory.LabelSig
import com.tekyerema.memory.LabelSignature
import com.tekyerema.memory.LabelStore
import com.tekyerema.memory.LabelTarget
import com.tekyerema.policy.ActionSemantics
import com.tekyerema.policy.RiskTier
import com.tekyerema.policy.TargetSemantics
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** MASTER_STATUS #38 label memory: signature, applying, the never-store rules, voice commands, verified-outcome learning, safety. */
class LabelMemoryTest {
    @After fun reset() = AppConfig.loadFrom(emptyList())

    // ---------------------------------------------------------------- builders

    private fun el(id: Int, role: String, label: String?, l: Int, t: Int, r: Int, b: Int, clickable: Boolean = true,
                   editable: Boolean = false, focused: Boolean = false, hint: String? = null, viewId: String? = null,
                   collection: String? = null, scrollable: Boolean = false, heading: Boolean = false, learned: Boolean = false) =
        UiElement(id, role, label, Box(l, t, r, b), editable = editable, clickable = clickable && !editable, focused = focused,
            hint = hint, viewId = viewId, collection = collection, scrollable = scrollable, heading = heading, learned = learned)

    /** A chat screen: contact name (tappable) and an unlabelled call button on top, the message list, the composer with an unlabelled send button. */
    private fun chat(composerTop: Int = 1850, rows: Int = 3, typed: String? = null, extra: List<UiElement> = emptyList(),
                     title: String? = "Ama", pkg: String = "com.chat.app"): UiSnapshot {
        val els = ArrayList<UiElement>()
        els.add(el(10, "TextView", "Ama", 150, 60, 500, 160))
        els.add(el(1, "ImageButton", null, 900, 60, 1000, 160))
        els.add(el(4, "RecyclerView", null, 0, 200, 1080, composerTop - 50, clickable = false, scrollable = true, collection = "list 40 rows"))
        for (i in 0 until rows) els.add(el(20 + i, "TextView", "message number $i", 20, 220 + i * 100, 800, 300 + i * 100, collection = "row $i"))
        els.add(el(2, "EditText", typed ?: "Message", 150, composerTop, 850, composerTop + 100, editable = true, focused = true, hint = "Message"))
        els.add(el(3, "ImageButton", null, 900, composerTop, 1040, composerTop + 100))
        els.addAll(extra)
        return UiSnapshot(pkg, title, els, 0L, appName = "Chat")
    }

    private val frame = el(40, "FrameLayout", null, 0, 0, 1080, 2000, clickable = false)

    private fun sigOf(id: Int, s: UiSnapshot) = LabelSignature.signatures(s).getValue(id)

    private fun entry(sig: LabelSig, name: String, app: String? = "Chat", told: Boolean = true, at: Long = 1_000L) =
        LabelEntry(sig, name, app, told, at, at)

    private fun idSig(id: String, pkg: String = "com.x") = LabelSig(pkg, id, "ImageButton", 0, 0, null, 0, 0, null)

    // ---------------------------------------------------------------- the signature

    @Test fun a_button_beside_a_named_box_gets_a_signature_that_survives_scrolling_the_list() {
        val a = sigOf(3, chat(rows = 3))
        val b = sigOf(3, chat(rows = 1))
        assertEquals(a, b)
        assertNull(a.viewId)
        assertTrue(a.anchor!!.startsWith("left:"))
        assertNotEquals(a, sigOf(1, chat()))          // the call button up top is another control
    }

    @Test fun the_signature_holds_hashes_not_screen_words() {
        val s = sigOf(3, chat())
        assertEquals("left:" + LabelSignature.hash("message"), s.anchor)
        assertFalse(s.toString().contains("Ama"))
        assertEquals(8, LabelSignature.hash("anything").length)
        assertEquals(LabelSignature.hash("Message "), LabelSignature.hash("message"))
    }

    @Test fun small_layout_changes_are_tolerated_when_a_neighbour_anchors_the_control() {
        val stored = sigOf(3, chat())
        val moved = chat(composerTop = 1200, extra = listOf(el(30, "TextView", "Footer", 0, 1900, 1080, 1950, clickable = false)))
        val live = sigOf(3, moved)
        assertNotEquals(stored.vBand, live.vBand)
        assertTrue(LabelSignature.matches(stored, live))
        val applied = LabelSignature.apply(moved, listOf(entry(stored, "Send")))
        assertEquals("Send", applied.element(3)!!.label)
    }

    @Test fun a_different_anchor_or_a_different_row_size_is_a_different_control() {
        fun toolbar(buttons: Int): UiSnapshot {
            val els = arrayListOf(frame, el(10, "TextView", "Ama", 150, 60, 500, 160), el(1, "ImageButton", null, 900, 60, 1000, 160))
            if (buttons > 1) els.add(el(5, "ImageButton", null, 780, 60, 880, 160))
            return UiSnapshot("com.chat.app", "Ama", els, 0L)
        }
        val two = LabelSignature.signatures(toolbar(2))
        assertEquals(2, two.size)
        assertEquals(1, two.getValue(1).ordinal)       // counted left to right
        assertEquals(0, two.getValue(5).ordinal)
        val stored = two.getValue(1)
        // one button gone: the row is a different size, so the stored name is not guessed onto what is left
        assertNull(LabelSignature.apply(toolbar(1), listOf(entry(stored, "Call"))).element(1)!!.label)
        // the same two buttons: only the right one is named
        val applied = LabelSignature.apply(toolbar(2), listOf(entry(stored, "Call")))
        assertEquals("Call", applied.element(1)!!.label)
        assertNull(applied.element(5)!!.label)
    }

    @Test fun a_resource_id_is_used_when_it_is_unique_and_ignores_where_the_button_is() {
        fun s(top: Int, vararg extra: UiElement) = UiSnapshot("com.x", "Home",
            listOf(frame, el(1, "ImageButton", null, 900, top, 1000, top + 100, viewId = "com.x:id/btn_search")) + extra, 0L)
        val sig = sigOf(1, s(60))
        assertEquals("com.x:id/btn_search", sig.viewId)
        assertNull(sig.anchor)
        assertEquals("Search", LabelSignature.apply(s(1500), listOf(entry(sig, "Search"))).element(1)!!.label)
        // the same id twice on one screen: not an identity any more, falls back to place (and says so)
        val twice = LabelSignature.signatures(s(60, el(2, "ImageButton", null, 700, 60, 800, 160, viewId = "com.x:id/btn_search")))
        assertTrue(twice.values.all { it.viewId == null })
    }

    @Test fun two_look_alike_controls_are_both_left_alone() {
        val s = UiSnapshot("com.x", null, listOf(frame,
            el(10, "TextView", "Item", 150, 60, 500, 160), el(1, "ImageButton", null, 900, 60, 1000, 160),
            el(11, "TextView", "Item", 150, 1000, 500, 1100), el(2, "ImageButton", null, 900, 1000, 1000, 1100)), 0L)
        assertTrue(LabelSignature.signatures(s).isEmpty())
    }

    @Test fun with_no_neighbour_and_no_title_it_is_not_sure_so_no_signature() {
        val bare = UiSnapshot("com.x", null, listOf(frame, el(1, "ImageButton", null, 900, 60, 1000, 160)), 0L)
        assertTrue(LabelSignature.signatures(bare).isEmpty())
        val titled = UiSnapshot("com.x", "Home", listOf(frame, el(1, "ImageButton", null, 900, 60, 1000, 160)), 0L)
        val sig = sigOf(1, titled)
        assertNull(sig.anchor)
        assertEquals(LabelSignature.hash("home"), sig.screen)
        // a different screen of the app does not take the name
        val other = UiSnapshot("com.x", "Settings", titled.elements, 0L)
        assertSame(other, LabelSignature.apply(other, listOf(entry(sig, "Menu"))))
    }

    @Test fun list_rows_labelled_controls_and_big_surfaces_are_never_candidates() {
        val s = UiSnapshot("com.x", "Home", listOf(frame,
            el(1, "ImageButton", "Settings", 900, 60, 1000, 160),                                      // has a name
            el(2, "RecyclerView", null, 0, 300, 1080, 1500, clickable = false, scrollable = true, collection = "list 5 rows"),
            el(3, "ImageButton", null, 900, 400, 1000, 500),                                          // inside a list
            el(4, "LinearLayout", null, 0, 1600, 1080, 1700),                                         // a full-width bar
            el(5, "ImageButton", null, 900, 1800, 1000, 1900)), 0L)                                   // the only real one
        assertEquals(listOf(5), LabelSignature.candidates(s).map { it.id })
    }

    @Test fun private_or_system_screens_never_carry_names() {
        val ok = chat()
        assertTrue(LabelSignature.signatures(ok).isNotEmpty())
        assertTrue(LabelSignature.signatures(ok.copy(secureWindow = true)).isEmpty())
        assertTrue(LabelSignature.signatures(ok.copy(windowType = "keyboard")).isEmpty())
        assertTrue(LabelSignature.signatures(chat(pkg = "com.android.phone")).isEmpty())
        assertTrue(LabelSignature.signatures(chat(pkg = "com.android.systemui")).isEmpty())
        assertTrue(LabelSignature.signatures(chat(extra = listOf(UiElement(60, "EditText", "(password field, redacted)", Box(100, 900, 900, 980),
            editable = true, password = true)))).isEmpty())
        val keys = (0..9).map { el(100 + it, "Button", "$it", 100 + it * 90, 1500, 180 + it * 90, 1600) }
        assertTrue(LabelSignature.signatures(chat(extra = keys)).isEmpty())
    }

    // ---------------------------------------------------------------- applying

    @Test fun a_remembered_name_is_put_on_the_control_and_marked_learned() {
        val sig = sigOf(3, chat())
        val applied = LabelSignature.apply(chat(rows = 1), listOf(entry(sig, "Send")))
        val send = applied.element(3)!!
        assertEquals("Send", send.label)
        assertTrue(send.learned)
        assertNull(applied.element(1)!!.label)
        assertFalse(applied.element(1)!!.learned)
        // applying again changes nothing
        assertEquals(applied, LabelSignature.apply(applied, listOf(entry(sig, "Send"))))
    }

    @Test fun another_app_or_no_entries_leave_the_screen_untouched() {
        val s = chat()
        val sig = sigOf(3, s)
        assertSame(s, LabelSignature.apply(s, emptyList()))
        assertSame(s, LabelSignature.apply(s, listOf(entry(sig.copy(pkg = "com.other"), "Send"))))
    }

    @Test fun two_different_names_for_one_control_means_unsure_and_nothing_is_applied() {
        val s = chat()
        val sig = sigOf(3, s)
        val out = LabelSignature.apply(s, listOf(entry(sig, "Send"), entry(sig.copy(hBand = 1), "Share")))
        assertNull(out.element(3)!!.label)
    }

    @Test fun the_planner_sees_the_name_and_a_learned_flag() {
        val s = chat()
        val applied = LabelSignature.apply(s, listOf(entry(sigOf(3, s), "Send")))
        val arr = applied.toCompactJson().getJSONArray("elements")
        var found = false
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getInt("id") == 3) {
                found = true
                assertEquals("Send", o.getString("label"))
                assertTrue(o.getJSONArray("flags").toString().contains("learned_name"))
            }
        }
        assertTrue(found)
    }

    @Test fun beside_finds_the_one_nameless_button_next_to_a_box() {
        val s = chat()
        assertEquals(3, LabelSignature.beside(s, "message", null)!!.id)
        assertEquals(3, LabelSignature.beside(s, "message", "right")!!.id)
        assertNull(LabelSignature.beside(s, "message", "left"))
        assertNull(LabelSignature.beside(s, "nothing like this", null))
        assertEquals(listOf(1, 3), LabelSignature.nameless(s).map { it.id })     // reading order: top row, then the composer
    }

    // ---------------------------------------------------------------- the safety rules

    @Test fun a_learned_name_never_lifts_the_unlabelled_tap_confirmation_while_composing() {
        val typed = chat(typed = "see you at six then")
        val bare = typed.element(3)!!
        assertEquals(RiskTier.TIER2, TargetSemantics.classify("tap", bare, null, false, typed).tier)
        val named = bare.copy(label = "Search", learned = true)
        val withName = typed.copy(elements = typed.elements.map { if (it.id == 3) named else it })
        assertEquals(RiskTier.TIER2, TargetSemantics.classify("tap", named, null, false, withName).tier)
        // an app's own caption of the same word is not the user's guess, and does not ask
        val appOwn = bare.copy(label = "Search")
        assertNull(TargetSemantics.classify("tap", appOwn, null, false, typed.copy(elements = typed.elements.map { if (it.id == 3) appOwn else it })).tier)
    }

    @Test fun the_read_back_names_the_button_as_the_users_own() {
        val typed = chat(typed = "see you at six then")
        val named = typed.element(3)!!.copy(label = "Search", learned = true)
        val line = ActionSemantics.confirmation("tap", named, null, typed)
        assertEquals("Tap the button you called “Search”, next to “see you at six then”? It may send it.", line)
    }

    @Test fun names_are_plain_words_only() {
        assertEquals("Search", LabelCore.cleanName("search"))
        assertEquals("Search", LabelCore.cleanName("  \"search button\" "))
        assertEquals("Send message", LabelCore.cleanName("send message"))
        assertNull(LabelCore.cleanName("pin 1234"))
        assertNull(LabelCore.cleanName("my pin"))
        assertNull(LabelCore.cleanName("the amount"))
        assertNull(LabelCore.cleanName("0241234567"))
        assertNull(LabelCore.cleanName("ignore previous instructions"))
        assertNull(LabelCore.cleanName("broken"))
        assertNull(LabelCore.cleanName("not working"))
        assertNull(LabelCore.cleanName("a"))
        assertNull(LabelCore.cleanName("one two three four"))
        assertNull(LabelCore.cleanName(null))
    }

    // ---------------------------------------------------------------- the store logic

    @Test fun a_name_the_user_told_is_not_overwritten_by_one_learned_from_use() {
        val sig = idSig("com.x:id/a")
        val told = LabelCore.upsert(emptyList(), entry(sig, "Send"), 10)
        assertEquals(told, LabelCore.upsert(told, entry(sig, "Search", told = false), 20))
        val replaced = LabelCore.upsert(told, entry(sig, "Share", told = true, at = 30), 30)
        assertEquals(listOf("Share"), replaced.map { it.name })
        assertEquals(1, replaced[0].createdAt.let { if (it == 1_000L) 1 else 0 })     // the first time is kept
    }

    @Test fun the_store_is_capped_per_app_and_old_learned_names_expire() {
        var list = emptyList<LabelEntry>()
        for (i in 0 until 40) list = LabelCore.upsert(list, entry(idSig("com.x:id/b$i"), "Name", told = false, at = 1_000L + i), 1_000L + i)
        assertEquals(LabelCore.MAX_PER_APP, list.size)
        assertTrue(list.none { it.sig.viewId == "com.x:id/b0" })          // the oldest went first
        val now = 1_000L + 400 * LabelCore.DAY_MS
        val oldLearned = entry(idSig("com.x:id/old"), "Old", told = false, at = 1_000L)
        val oldTold = entry(idSig("com.x:id/told"), "Told", told = true, at = 1_000L)
        assertEquals(listOf("Told"), LabelCore.prune(listOf(oldLearned, oldTold), now).map { it.name })
    }

    @Test fun forgetting_an_app_removes_only_its_names() {
        val wa = entry(idSig("com.whatsapp:id/s", "com.whatsapp"), "Send", app = "WhatsApp")
        val other = entry(idSig("com.chat:id/s", "com.chat"), "Search", app = "Chat")
        val (kept, n) = LabelCore.forgetApp(listOf(wa, other), "WhatsApp")
        assertEquals(1, n)
        assertEquals(listOf(other), kept)
        assertEquals(1, LabelCore.forgetApp(listOf(wa, other), "whats app").second)
        assertEquals(0, LabelCore.forgetApp(listOf(wa, other), "Telegram").second)
    }

    @Test fun the_spoken_list_groups_names_by_app() {
        val a = entry(idSig("com.whatsapp:id/s", "com.whatsapp"), "Send", app = "WhatsApp")
        val b = entry(idSig("com.whatsapp:id/c", "com.whatsapp"), "Camera", app = "WhatsApp")
        assertEquals(listOf("in WhatsApp you named Send and Camera"), LabelCore.spokenLines(listOf(a, b)))
        assertTrue(LabelCore.spokenLines(listOf(a, b), "Telegram").isEmpty())
    }

    @Test fun the_stored_form_round_trips_and_a_bad_file_is_not_fatal() {
        val s = chat()
        val list = listOf(entry(sigOf(3, s), "Send"), entry(idSig("com.x:id/q"), "Search", told = false))
        assertEquals(list, LabelCore.decode(LabelCore.encode(list)))
        assertNull(LabelCore.decode("not json"))
        assertNull(LabelCore.decode(null))
        assertEquals(emptyList<LabelEntry>(), LabelCore.decode("""{"v":1}"""))
        assertEquals(1, LabelCore.decode("""{"v":1,"e":[{"n":"","pkg":"p","r":"r"},{"n":"Ok","pkg":"p","r":"r"}]}""")!!.size)
    }

    @Test fun the_switch_is_on_by_default_and_the_config_file_says_so() {
        assertTrue(LabelStore.enabled())
        val dir = listOf(File("src/main/assets/config"), File("app/src/main/assets/config")).first { it.isDirectory }
        AppConfig.loadFrom(listOf("memory.json" to File(dir, "memory.json").readText()))
        assertTrue(AppConfig.bool("memory.labels.enabled", false))
        AppConfig.loadFrom(listOf("m.json" to """{"memory":{"labels":{"enabled":false}}}"""))
        assertFalse(LabelStore.enabled())
        val s = chat()
        assertSame(s, LabelStore.apply(null, s))
    }

    // ---------------------------------------------------------------- the voice commands (parsing)

    private fun name(raw: String) = LabelParse.classify(raw) as LabelCmd.Name

    @Test fun that_button_is_the_one_just_pressed() {
        assertEquals(LabelCmd.Name(LabelTarget.Recent, "search", false), LabelParse.classify("That button is search"))
        assertEquals(LabelCmd.Name(LabelTarget.Recent, "Search", true), LabelParse.classify("call that button Search."))
        assertEquals(LabelCmd.Name(LabelTarget.Recent, "search", false), LabelParse.classify("that was the search button"))
        assertEquals(LabelCmd.Name(LabelTarget.Recent, "voice search", false), LabelParse.classify("remember that that button is for voice search"))
    }

    @Test fun counting_the_unnamed_buttons() {
        assertEquals(LabelTarget.Nth(3, false), name("the third button is call").target)
        assertEquals("call", name("the third button is call").name)
        assertEquals(LabelTarget.Nth(2, false), name("remember that the second button is send").target)
        assertEquals(LabelTarget.Nth(1, true), name("the last button is menu").target)
        assertEquals(LabelTarget.Nth(2, false), name("button number two is camera").target)
        assertEquals(LabelTarget.Nth(4, false), name("name the 4th unnamed button as share").target)
        assertTrue(name("name the 4th unnamed button as share").instruction)
        assertEquals(LabelTarget.Only, name("the only unnamed button is share").target)
    }

    @Test fun naming_by_what_is_next_to_it() {
        val n = name("the button next to the message box is send")
        assertEquals(LabelTarget.Beside("message", null), n.target)
        assertEquals("send", n.name)
        assertEquals(LabelTarget.Beside("search", "right"), name("the button to the right of the search box is mic").target)
        assertEquals(LabelTarget.Beside("message", "left"), name("the icon left of the message field is emoji").target)
    }

    @Test fun listing_and_forgetting_by_voice() {
        assertEquals(LabelCmd.ListNames(null), LabelParse.classify("what button names do you know"))
        assertEquals(LabelCmd.ListNames("whatsapp"), LabelParse.classify("what button names do you know for whatsapp"))
        assertEquals(LabelCmd.Forget("WhatsApp", false), LabelParse.classify("forget the button names for WhatsApp"))
        assertEquals(LabelCmd.Forget(null, true), LabelParse.classify("forget all button names"))
        assertEquals(LabelCmd.Forget(null, false), LabelParse.classify("forget the button names"))
        assertEquals(LabelCmd.ForgetLast, LabelParse.classify("forget that button name"))
    }

    @Test fun ordinary_requests_and_remarks_are_not_taken() {
        for (s in listOf("open whatsapp", "call mum", "that is wrong", "forget Ama", "what do you remember about me",
            "the button is red", "tap the third button", "send a message to Kofi", "forget everything", ""))
            assertNull(s, LabelParse.classify(s))
        // a remark parses (it may be about a button) but is not a name, so the handler passes it on
        assertNull(LabelCore.cleanName(name("that button is broken").name))
        assertNull(LabelCore.cleanName(name("the first button is not working").name))
    }

    // ---------------------------------------------------------------- learning from a verified outcome

    private val plain = UiSnapshot("com.x", "Home", listOf(frame, el(1, "ImageButton", null, 900, 60, 1000, 160),
        el(2, "TextView", "Welcome", 100, 300, 700, 360, clickable = false)), 0L)

    @Test fun a_tap_that_opened_a_search_box_proves_the_button_is_search() {
        val after = UiSnapshot("com.x", "Search", plain.elements + el(5, "EditText", "Search", 100, 60, 800, 160, editable = true, focused = true, hint = "Search"), 1L)
        assertEquals("Search", LabelInference.infer(plain, plain.element(1)!!, after))
        // already a search box before the tap, or it has no keyboard: proves nothing
        assertNull(LabelInference.infer(after, plain.element(1)!!, after))
        val unfocused = after.copy(elements = after.elements.map { if (it.id == 5) it.copy(focused = false) else it })
        assertNull(LabelInference.infer(plain, plain.element(1)!!, unfocused))
        // a different app in front afterwards proves nothing
        assertNull(LabelInference.infer(plain, plain.element(1)!!, after.copy(appPackage = "com.y")))
        // a named button is not learned about
        assertNull(LabelInference.infer(plain, plain.element(1)!!.copy(label = "Menu"), after))
    }

    @Test fun a_tap_after_which_the_typed_words_appear_as_sent_proves_the_button_is_send() {
        val msg = "I will be there by six"
        val before = chat(typed = msg)
        val sent = before.copy(elements = before.elements.map { if (it.id == 2) it.copy(label = "Message") else it } +
            el(50, "TextView", "$msg, 10:42, delivered", 20, 700, 800, 780))
        assertEquals("Send", LabelInference.infer(before, before.element(3)!!, sent))
        // the words are still in the box: not sent
        assertNull(LabelInference.infer(before, before.element(3)!!, before))
        // the box was cleared but the words are nowhere: a "clear" button, not Send
        val cleared = before.copy(elements = before.elements.map { if (it.id == 2) it.copy(label = "Message") else it })
        assertNull(LabelInference.infer(before, before.element(3)!!, cleared))
    }

    @Test fun nothing_else_is_learned_from_a_screen_that_merely_changed() {
        val after = UiSnapshot("com.x", "Other", listOf(frame, el(7, "TextView", "Something new", 100, 300, 700, 360, clickable = false)), 1L)
        assertNull(LabelInference.infer(plain, plain.element(1)!!, after))
        assertNotNull(plain.element(1))
    }
}
