package com.tekyerema

import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.agent.FastIntent
import com.tekyerema.agent.FastIntentClassifier
import com.tekyerema.android.AppResolver
import com.tekyerema.android.AppResolver.AppInfo
import com.tekyerema.core.config.AppConfig
import com.tekyerema.policy.PolicyEngine
import com.tekyerema.policy.PolicyOutcome
import com.tekyerema.policy.RiskTier
import com.tekyerema.tools.AssistantChat
import com.tekyerema.tools.CameraUi
import com.tekyerema.tools.ComposerSend
import com.tekyerema.tools.GoogleSignIn
import com.tekyerema.tools.MediaSessions
import com.tekyerema.tools.PlayMusicTool
import com.tekyerema.tools.PlayStore
import com.tekyerema.tools.SendSmsTool
import com.tekyerema.tools.ToolManifest
import com.tekyerema.tools.defaultTools
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [D] real-world app tasks (A20e owner test list, 2026-09-28): install from the Play Store, send SMS / WhatsApp through
 * the app's own Send, ask ChatGPT, take a selfie, YouTube Music, "Continue with Google". Everything here is the PURE
 * reading of those apps' screens and the routing to them — the device half is exercised on the phone.
 */
class AppTasksTest {
    @After fun resetConfig() { AppConfig.loadFrom(emptyList()) }

    private var nextId = 0
    private fun el(label: String?, top: Int = 100, left: Int = 0, right: Int = 200, clickable: Boolean = true,
                   editable: Boolean = false, viewId: String? = null, hint: String? = null) =
        UiElement(nextId++, if (editable) "EditText" else "Button", label, Box(left, top, right, top + 60),
            editable = editable, clickable = clickable, viewId = viewId, hint = hint)
    private fun snap(pkg: String, vararg els: UiElement) = UiSnapshot(pkg, null, els.toList(), 0L)
    private fun tools() = defaultTools().associateBy { it.name }

    // ---------------------------------------------------------------- app names (E-01 / C-03)
    private val apps = listOf(
        AppInfo("YouTube", "com.google.android.youtube"), AppInfo("YouTube Music", "com.google.android.apps.youtube.music"),
        AppInfo("ChatGPT", "com.openai.chatgpt"), AppInfo("Claude", "com.anthropic.claude"), AppInfo("Messages", "com.samsung.android.messaging"),
        AppInfo("Play Store", "com.android.vending"), AppInfo("Telegram", "org.telegram.messenger"), AppInfo("Camera", "com.sec.android.app.camera"))
    private fun one(q: String, list: List<AppInfo> = apps) = (AppResolver.match(list, q) as? AppResolver.Match.One)?.app?.label

    @Test fun spoken_app_aliases_resolve_to_the_one_app_they_name() {
        assertEquals("YouTube Music", one("YT music"))
        assertEquals("YouTube Music", one("the yt music app"))
        assertEquals("Claude", one("cloud AI"))                 // A20e: "install cloud AI" meant Claude
        assertEquals("ChatGPT", one("chat GBT"))
        assertEquals("Messages", one("SMS"))
        assertEquals("Play Store", one("google play"))
        assertEquals("Telegram", one("telegram"))
        // An alias whose app isn't installed resolves to nothing — no look-alike.
        assertTrue(AppResolver.match(apps.filter { it.label != "Claude" }, "cloud ai", tolerant = false) is AppResolver.Match.None)
    }

    @Test fun config_aliases_add_names_and_a_cycle_cannot_recurse() {
        AppConfig.loadFrom(listOf("config/os.json" to """{"os":{"apps":{"aliases":{"telly":"Telegram","ytmusic":"telly","telegram":"ytmusic"}}}}"""))
        assertEquals("Telegram", one("telly"))
        assertNotNull(AppResolver.match(apps, "telegram"))   // terminates despite the telegram → ytmusic → telly loop
    }

    // ---------------------------------------------------------------- Play Store (E-01 install_app)
    @Test fun store_catalog_maps_spoken_names_to_verified_packages() {
        assertEquals("com.anthropic.claude", PlayStore.lookup("the Claude app")?.pkg)
        assertEquals("com.anthropic.claude", PlayStore.lookup("cloud AI")?.pkg)
        assertEquals("com.olx.ssa.gh", PlayStore.lookup("Jiji")?.pkg)
        assertEquals("com.openai.chatgpt", PlayStore.lookup("chat gpt")?.pkg)
        assertEquals("com.google.android.apps.youtube.music", PlayStore.lookup("YT Music")?.pkg)
        assertNull(PlayStore.lookup("zorblax"))
        AppConfig.loadFrom(listOf("config/os.json" to """{"os":{"store":{"packages":{"MTN MoMo":"com.mtn.momo"}}}}"""))
        assertEquals("com.mtn.momo", PlayStore.lookup("mtn momo")?.pkg)
    }

    @Test fun store_links_are_the_official_play_urls() {
        assertEquals("https://play.google.com/store/apps/details?id=com.anthropic.claude", PlayStore.detailsUrl("com.anthropic.claude"))
        assertEquals("https://play.google.com/store/search?q=jiji+ghana&c=apps", PlayStore.searchUrl("jiji ghana"))
        assertFalse("a query can't inject parameters", PlayStore.searchUrl("x&c=movies").contains("&c=movies"))
    }

    @Test fun details_page_states_are_read_from_exact_captions() {
        val v = "com.android.vending"
        val installable = PlayStore.page(snap(v, el("Claude by Anthropic", 50, clickable = false), el("Install", 300)))
        assertEquals(PlayStore.State.INSTALLABLE, installable.state)
        assertEquals("Install", installable.button?.label)
        assertEquals(PlayStore.State.INSTALLED, PlayStore.page(snap(v, el("Uninstall", 300), el("Open", 300, 300, 500))).state)
        assertEquals(PlayStore.State.INSTALLED, PlayStore.page(snap(v, el("Update", 300), el("Uninstall", 300, 300, 500))).state)
        // While downloading, a greyed "Open" sits next to "Cancel" — that's progress, not installed.
        assertEquals(PlayStore.State.IN_PROGRESS, PlayStore.page(snap(v, el("Cancel", 300), el("Open", 300, 300, 500))).state)
        assertEquals(PlayStore.State.IN_PROGRESS, PlayStore.page(snap(v, el("Pending…", 250, clickable = false))).state)
        // A20e 2026-09-29: the install sheet keeps its "Install" node while showing the download's percentage
        assertEquals(PlayStore.State.IN_PROGRESS, PlayStore.page(snap(v, el("Jiji Ghana: Buy & Sell Online", 50, clickable = false),
            el("8%", 250, clickable = false), el("Install", 300))).state)
        val paid = PlayStore.page(snap(v, el("GH₵ 12.00", 300)))
        assertEquals(PlayStore.State.PAID, paid.state); assertEquals("GH₵ 12.00", paid.price)
        assertEquals(PlayStore.State.PAID, PlayStore.page(snap(v, el("$1.99", 300))).state)
        assertEquals(PlayStore.State.SIGN_IN, PlayStore.page(snap(v, el("Sign in", 300))).state)
        assertEquals(PlayStore.State.UNAVAILABLE, PlayStore.page(snap(v, el("Item not found.", 300, clickable = false))).state)
        assertEquals(PlayStore.State.UNKNOWN, PlayStore.page(snap(v, el("Loading", 300, clickable = false))).state)
        // A results list (many inline Install buttons) is never treated as one app's page.
        val list = snap(v, el("Install", 100), el("Install", 200), el("Install", 300), el("Install", 400))
        assertEquals(PlayStore.State.UNKNOWN, PlayStore.page(list).state)
    }

    @Test fun search_result_skips_ads_and_prefers_the_title_that_starts_with_the_name() {
        val v = "com.android.vending"
        val s = snap(v,
            el("Search for 'jiji'", 40),
            el("Ad · Jumia Online Shopping Star rating: 4.3", 100),
            el("Buy and sell near you — Jiji alternative Star rating: 3.1", 200),
            el("Jiji Ghana: Buy & Sell Online Jiji.com.gh Star rating: 4.4 30 MB", 300),
            el("Install", 300, 300, 500))
        assertEquals("Jiji Ghana: Buy & Sell Online Jiji.com.gh Star rating: 4.4 30 MB", PlayStore.pickResult(s, "Jiji")?.label)
        assertNull("nothing names it → ask, never install a look-alike", PlayStore.pickResult(s, "zorblax"))
        // The search bar echoes the query ("jiji") above the results; a title that only MENTIONS the name is a look-alike.
        val echo = snap(v, el("jiji", 20), el("Buy and sell near you — Jiji alternative Star rating: 3.1", 200),
            el("Jiji Ghana: Buy & Sell Online", 300, clickable = false))
        assertEquals("Jiji Ghana: Buy & Sell Online", PlayStore.pickResult(echo, "jiji")?.label)
        assertNull(PlayStore.pickResult(snap(v, el("jiji", 20), el("Buy and sell near you — Jiji alternative Star rating: 3.1", 200)), "jiji"))
        assertEquals(listOf("Jiji Ghana: Buy & Sell Online Jiji.com.gh"), PlayStore.resultNames(s).filter { it.startsWith("Jiji") })
    }

    @Test fun details_page_must_name_the_requested_app() {
        val page = snap("com.android.vending", el("Jiji Ghana: Buy & Sell Online", 50, clickable = false), el("Install", 300))
        assertTrue(PlayStore.pageNames(page, "Jiji Ghana", page.elements[1]))
        assertFalse(PlayStore.pageNames(page, "Claude", page.elements[1]))
    }

    @Test fun app_driving_tools_honour_the_deny_and_guide_only_lists() {
        AppConfig.loadFrom(listOf("config/policy.json" to
            """{"policy":{"denyApps":["com.whatsapp"],"guideOnlyApps":["com.android.vending"]}}"""))
        assertNotNull(com.tekyerema.tools.driveRefusal("com.whatsapp"))
        assertNotNull(com.tekyerema.tools.driveRefusal("com.android.vending"))
        assertNull(com.tekyerema.tools.driveRefusal("com.openai.chatgpt"))
        assertNull(com.tekyerema.tools.driveRefusal(null))
    }

    @Test fun a_plain_tap_on_install_is_confirmed_too() {
        // A20e trace 2026-09-28: the planner tapped the Play Store's "Install" with no confirmation.
        val page = snap("com.android.vending", el("Claude by Anthropic", 50, clickable = false), el("Install", 300))
        val ts = com.tekyerema.policy.TargetSemantics
        assertEquals(RiskTier.TIER2, ts.classify("tap", page.elements[1], null, false, page).tier)
        assertEquals(RiskTier.TIER2, ts.classify("tap", el("Uninstall", 300), null, false, page).tier)
        assertNull(ts.classify("tap", el("Installed apps", 300), null, false, page).tier)
    }

    @Test fun install_is_confirmed_and_names_the_listing() {
        val t = tools().getValue("install_app")
        assertEquals(RiskTier.TIER2, t.riskTier)
        val args = JSONObject().put("app", "cloud AI").put("_title", "Claude")
        val d = PolicyEngine().evaluate(t, args)
        assertEquals(PolicyOutcome.CONFIRM, d.outcome)
        assertEquals("Install Claude from the Play Store? I'll only install it if it's free.", d.confirmationPrompt)
    }

    // ---------------------------------------------------------------- composer Send (E-03 / E-04)
    @Test fun the_box_must_hold_exactly_the_approved_words() {
        val box = el("On my way", 1500, editable = true)
        val s = snap("com.samsung.android.messaging", el("Ama", 50, clickable = false), box, el("Send", 1500, 600, 700))
        assertEquals(box, ComposerSend.composerWith(s, "On my  way "))
        // An old draft plus our text is NOT what the user approved.
        val old = snap("com.samsung.android.messaging", el("Call me. On my way", 1500, editable = true))
        assertNull(ComposerSend.composerWith(old, "On my way"))
    }

    @Test fun send_button_is_found_by_caption_or_id_on_the_composer_row() {
        val box = el("hi", 1500, editable = true)
        val s = snap("com.whatsapp", box, el("Send money", 800, 0, 300), el("Send", 1500, 600, 700), el("Send", 200, 600, 700))
        assertEquals(1500, ComposerSend.sendButton(s, box)?.bounds?.top)
        val iconOnly = snap("com.whatsapp", box, el(null, 1500, 600, 700, viewId = "com.whatsapp:id/send"))
        assertEquals("com.whatsapp:id/send", ComposerSend.sendButton(iconOnly, box)?.viewId)
        val sim = snap("com.samsung.android.messaging", box, el("Send (SIM 1)", 1500, 600, 700))
        assertNotNull(ComposerSend.sendButton(sim, box))
        assertNull(ComposerSend.sendButton(snap("x", box, el("Send money", 1500, 600, 700), el("Sender", 1500)), box))
    }

    @Test fun sent_means_the_box_emptied_and_the_words_show_as_a_message() {
        val after = snap("com.whatsapp", el("On my way 10:42", 900, clickable = false), el("", 1500, editable = true))
        assertTrue(ComposerSend.looksSent(after, "On my way"))
        val stuck = snap("com.whatsapp", el("On my way", 1500, editable = true))
        assertFalse(ComposerSend.looksSent(stuck, "On my way"))
        assertTrue(ComposerSend.notOnWhatsApp(snap("com.whatsapp", el("The phone number +233 24 123 4567 isn't on WhatsApp.", 500, clickable = false))))
    }

    @Test fun text_confirmation_names_app_person_number_and_words() {
        val t = SendSmsTool
        // Money safety pass (2026-09-29): numbers are read in 3-3-4 groups, digits apart (NumberSpeech).
        assertEquals("Ama Mensah on 0 2 4, 4 1 2, 3 4 5 6", t.recipientWords("Ama Mensah", "0244123456"))
        assertEquals("0 2 4, 4 1 2, 3 4 5 6", t.recipientWords(null, "0244123456"))
        assertEquals("0 2 4, 4 1 2, 3 4 5 6", t.recipientWords("0244123456", "0244123456"))
        val a = JSONObject().put("contact", "Ama").put("message", "On my way").put("_to", "Ama Mensah on 024 412 3456")
        // Three beats with pauses (2026-09-30): who + number, the exact words, the question.
        val plain = { s: String -> com.tekyerema.voice.SpokenText.plain(s) }
        assertEquals("Text Ama Mensah on 024 412 3456. The message: \"On my way.\" Send it?", plain(t.confirmationPrompt(a)))
        assertEquals("WhatsApp Ama Mensah on 024 412 3456. The message: \"On my way.\" Send it?",
            plain(t.confirmationPrompt(a.put("via", "whatsapp"))))
        assertEquals(2, t.confirmationPrompt(a).count { it == com.tekyerema.voice.SpokenText.PAUSE })
        assertEquals(PolicyOutcome.CONFIRM, PolicyEngine().evaluate(t, a).outcome)
    }

    // ---------------------------------------------------------------- camera (E-21)
    @Test fun camera_shutter_and_side_are_read_from_captions() {
        val cam = "com.sec.android.app.camera"
        val rear = snap(cam, el("Switch to front camera", 1700, 500, 600), el("Take picture", 1700, 300, 400), el("Gallery", 1700, 0, 100))
        assertEquals("Take picture", CameraUi.shutter(rear)?.label)
        assertEquals(CameraUi.Facing.BACK, CameraUi.facing(rear))
        assertEquals("Switch to front camera", CameraUi.switchTo(rear, CameraUi.Facing.FRONT)?.label)
        val front = snap(cam, el("Switch to rear camera", 1700, 500, 600), el(null, 1700, 300, 400, viewId = "$cam:id/shutter_button"))
        assertEquals(CameraUi.Facing.FRONT, CameraUi.facing(front))
        assertNotNull(CameraUi.shutter(front))
        // An uncaptioned "Switch camera" is never a coin flip when the current side is unknown.
        val unknown = snap(cam, el("Switch camera", 1700, 500, 600), el("Capture", 1700, 300, 400))
        assertEquals(CameraUi.Facing.UNKNOWN, CameraUi.facing(unknown))
        assertNull(CameraUi.switchTo(unknown, CameraUi.Facing.FRONT))
        assertEquals(3, CameraUi.countdown(null, front = true)); assertEquals(2, CameraUi.countdown(null, front = false))
        assertEquals(10, CameraUi.countdown(99, front = true)); assertEquals(0, CameraUi.countdown(-4, front = false))
    }

    // ---------------------------------------------------------------- assistants (E-20)
    @Test fun assistant_answer_is_the_new_text_minus_question_buttons_and_footer() {
        val gpt = "com.openai.chatgpt"
        val q = "What is the capital of Ghana?"
        val before = snap(gpt, el("Earlier answer about jollof rice", 300, clickable = false), el(q, 1500, editable = true), el("Send prompt", 1500, 600, 700))
        assertNotNull(AssistantChat.composer(before))
        val streaming = snap(gpt, el("Earlier answer about jollof rice", 300, clickable = false), el(q, 600, clickable = false),
            el("The capital of Ghana is", 700, clickable = false), el("Stop generating", 1500, 600, 700))
        assertTrue(AssistantChat.generating(streaming))
        val after = snap(gpt, el("Earlier answer about jollof rice", 300, clickable = false), el(q, 600, clickable = false),
            el("The capital of Ghana is Accra.", 700, clickable = false), el("Copy", 800), el("Read aloud", 800, 300, 400),
            el("ChatGPT can make mistakes. Check important info.", 1400, clickable = false), el("", 1500, editable = true))
        assertFalse(AssistantChat.generating(after))
        assertEquals("The capital of Ghana is Accra.", AssistantChat.answer(before, after, q))
        assertNull(AssistantChat.answer(before, before, q))
        // A20e 2026-09-29: a new toolbar control ("Edit Menu", above the reply) was read out as the answer's start
        val withToolbar = snap(gpt, el("Edit Menu", 60), el(q, 600, clickable = false),
            el("The capital of Ghana is Accra. 🇬🇭", 700, clickable = false), el("Reply to ChatGPT", 1400))
        assertEquals("The capital of Ghana is Accra. 🇬🇭", AssistantChat.answer(before, withToolbar, q))
        assertTrue(AssistantChat.needsLogin(snap(gpt, el("Log in", 900), el("Sign up", 1000))))
    }

    @Test fun asking_an_assistant_is_like_a_web_search_not_a_send() {
        val t = tools().getValue("ask_app")
        assertEquals(PolicyOutcome.ALLOW, PolicyEngine().evaluate(t, JSONObject().put("question", "hi")).outcome)
        // A question copied off the screen is escalated to a confirmation, as for web_search.
        assertEquals(PolicyOutcome.CONFIRM, PolicyEngine().evaluate(t, JSONObject().put("question", "hi"), hasUntrustedParams = true).outcome)
    }

    // ---------------------------------------------------------------- Continue with Google (G-13)
    @Test fun google_sign_in_screens() {
        val app = snap("com.canva.editor", el("Continue with email", 800), el("Continue with Google", 900), el("Continue", 1000))
        assertEquals("Continue with Google", GoogleSignIn.button(app)?.label)
        assertNotNull(GoogleSignIn.button(snap("x", el("Google", 900))))
        assertNotNull(GoogleSignIn.button(snap("x", el(null, 900, viewId = "x:id/btn_google_login"))))
        assertNull(GoogleSignIn.button(snap("x", el("Continue with email", 800))))

        val chooser = snap("com.google.android.gms", el("Choose an account", 200, clickable = false),
            el("Prince Nasamu pnalhassan@gmail.com", 400), el("Add another account", 500))
        val rows = GoogleSignIn.accounts(chooser)
        assertEquals(1, rows.size)
        assertEquals("pnalhassan@gmail.com", GoogleSignIn.email(GoogleSignIn.pick(rows, null)!!))
        val two = GoogleSignIn.accounts(snap("com.google.android.gms", el("Ama a@gmail.com", 400), el("Kofi k@gmail.com", 500)))
        assertNull("several accounts → ask the user", GoogleSignIn.pick(two, null))
        assertEquals("Kofi k@gmail.com", GoogleSignIn.pick(two, "k@gmail.com")?.label)

        val sheet = snap("com.google.android.gms", el("Sign in to Canva with google.com", 300, clickable = false), el("Continue as Prince", 900))
        assertEquals("Continue as Prince", GoogleSignIn.carryOn(sheet)?.label)
        assertNull("Allow is left to the policy", GoogleSignIn.carryOn(snap("com.google.android.gms", el("Allow", 900))))
        val scopes = snap("com.google.android.gms", el("See, edit, create and delete all your Google Drive files", 400, clickable = false))
        assertEquals(1, GoogleSignIn.extraAccess(scopes).size)
        assertTrue(GoogleSignIn.extraAccess(sheet).isEmpty())
    }

    @Test fun google_sign_in_is_confirmed_and_never_unattended() {
        val t = tools().getValue("google_sign_in")
        assertEquals(RiskTier.TIER2, t.riskTier)
        val d = PolicyEngine().evaluate(t, JSONObject().put("_app", "Canva"))
        assertEquals(PolicyOutcome.CONFIRM, d.outcome)
        assertTrue(d.confirmationPrompt!!.startsWith("Sign in to Canva with your Google account?"))
    }

    // ---------------------------------------------------------------- music (E-14)
    @Test fun transport_replies_say_what_plays_now() {
        val t = MediaSessions.Track("com.google.android.apps.youtube.music", "Kupe", "Black Sherif", playing = true)
        assertEquals("Kupe by Black Sherif", MediaSessions.words(t))
        assertEquals(t, MediaSessions.playing(listOf(t.copy(playing = false, title = "Old"), t)))
        assertNull(MediaSessions.playing(listOf(t), "com.spotify.music"))
        assertEquals("Skipped. Now playing Kupe by Black Sherif.", PlayMusicTool.transportWords("next", t, true))
        assertEquals("Skipped to the next song.", PlayMusicTool.transportWords("next", null, false))
        assertEquals("Paused.", PlayMusicTool.transportWords("pause", t, true))
    }

    // ---------------------------------------------------------------- fast path + tool loading
    private fun c(s: String) = FastIntentClassifier.classify(s)

    @Test fun fast_path_takes_photos_and_selfies_and_only_opens_the_camera_when_asked_to_open_it() {
        assertEquals(FastIntent.TakeSelfie, c("Take a selfie."))
        assertEquals(FastIntent.TakeSelfie, c("take a picture of me"))
        assertEquals(FastIntent.TakePhoto, c("Take a photo"))
        assertEquals(FastIntent.OpenApp("camera"), c("open the camera"))
    }

    @Test fun fast_path_plays_music_in_a_named_app() {
        assertEquals(FastIntent.PlayMusic("sarkodie", null, "youtube music"), c("Play Sarkodie on YouTube Music."))
        assertEquals(FastIntent.PlayMusic(null, "stonebwoy", null), c("play songs by Stonebwoy"))
        assertEquals(FastIntent.PlayMusic(null, "black sherif", "yt music"), c("play Black Sherif's songs on YT Music"))
        assertEquals(FastIntent.PlayMusic(null, null, "spotify"), c("play some music on spotify"))
        assertEquals(FastIntent.PlayMusic(null), c("play music"))
        assertEquals(FastIntent.MediaNext, c("skip to the next song"))
        assertEquals(FastIntent.OpenApp("play store"), c("open play store"))
        assertNull(c("play despacito on youtube"))   // a video → the agent, not a music intent
    }

    /** Owner, A20e 2026-09-29: "play my downloads on YouTube Music" searched the words "my downloads" and played a
     *  stranger's song. The user's own music is a collection to open, never a search. */
    @Test fun the_users_own_music_is_a_collection_not_a_search() {
        val yt = "youtube music"
        assertEquals(FastIntent.PlayMusic(null, null, yt, "downloads"), c("Play my downloads on YouTube Music."))
        assertEquals(FastIntent.PlayMusic(null, null, null, "downloads"), c("play my downloads"))
        assertEquals(FastIntent.PlayMusic(null, null, null, "downloads"), c("play my downloaded songs"))
        assertEquals(FastIntent.PlayMusic(null, null, null, "downloads"), c("play the songs I downloaded"))
        assertEquals(FastIntent.PlayMusic(null, null, "yt music", "downloads"), c("play songs from my downloads on YT Music"))
        assertEquals(FastIntent.PlayMusic(null, null, null, "downloads"), c("shuffle my offline music"))
        assertEquals(FastIntent.PlayMusic(null, null, yt, "liked"), c("play my liked songs on youtube music"))
        assertEquals(FastIntent.PlayMusic(null, null, null, "liked"), c("play my favourites"))
        assertEquals(FastIntent.PlayMusic(null, null, null, "library"), c("play my library"))
        assertEquals(FastIntent.PlayMusic(null, null, yt, "downloads"), c("play my download on youtube music"))   // a dropped "s"
        // Misspelt or misheard (owner typed "downladed", 2026-09-29, and got a stranger's song).
        assertEquals(FastIntent.PlayMusic(null, null, "yt music", "downloads"), c("Play my downladed songs on yt music"))
        for (w in listOf("downloded", "donwloaded", "dowloaded", "down loaded", "dwnloaded", "downloadded"))
            assertEquals(w, FastIntent.PlayMusic(null, null, null, "downloads"), c("play my $w songs"))
        assertEquals(FastIntent.PlayMusic(null, null, null, "downloads"), c("play the songs i downladed"))
        assertEquals(FastIntent.PlayMusic(null, null, null, "liked"), c("play the songs i liked"))
        // Still a search / an artist, as before.
        assertEquals(FastIntent.PlayMusic("sarkodie", null, yt), c("play sarkodie on youtube music"))
        assertEquals(FastIntent.PlayMusic(null, "stonebwoy", null), c("play songs by Stonebwoy"))
    }

    /** A20e 2026-09-30: "Play all" restarted the song already playing (the list's first); that is playing, not a failure. */
    @Test fun the_same_song_from_the_top_counts_as_started() {
        assertTrue(MediaSessions.restarted(61_000L, 900L))
        assertFalse("still going on from where it was", MediaSessions.restarted(61_000L, 64_000L))
        assertFalse("had only just started — can't tell", MediaSessions.restarted(2_000L, 500L))
        assertFalse(MediaSessions.restarted(null, 500L)); assertFalse(MediaSessions.restarted(61_000L, null))
    }

    @Test fun youtube_musics_library_tab_is_the_lowest_button_named_library() {
        val s = snap("com.google.android.apps.youtube.music",
            el("Library", top = 300), el("Home", top = 1368, left = 0, right = 180), el("Library", top = 1368, left = 540, right = 720),
            el("Library", top = 1368, left = 540, right = 720, clickable = false))
        val tab = PlayMusicTool.libraryTab(s)
        assertEquals(1368, tab?.bounds?.top); assertEquals(540, tab?.bounds?.left); assertTrue(tab!!.clickable)
        assertNull(PlayMusicTool.libraryTab(snap("com.google.android.apps.youtube.music", el("Home", top = 1368))))
        // A20e 2026-09-30, the Downloaded songs page: the tab words are plain text, the buttons around them unnamed.
        val plain = PlayMusicTool.libraryTab(snap("com.google.android.apps.youtube.music",
            el("Library", top = 150, clickable = false),
            el("Home", top = 1384, left = 24, right = 156, clickable = false), el("Samples", top = 1384, left = 204, right = 336, clickable = false),
            el("Library", top = 1384, left = 564, right = 696, clickable = false)))
        assertEquals(564, plain?.bounds?.left); assertEquals(1384, plain?.bounds?.top)
        assertNull("a page title is not the tab", PlayMusicTool.libraryTab(snap("com.google.android.apps.youtube.music",
            el("Library", top = 150, clickable = false), el("Home", top = 1384, clickable = false))))
    }

    @Test fun fast_path_asks_assistant_apps_with_the_users_own_words() {
        assertEquals(FastIntent.AskApp("ChatGPT", "what is the capital of Ghana?"), c("Ask ChatGPT, what is the capital of Ghana?"))
        assertEquals(FastIntent.AskApp("ChatGPT", "who won the 2010 World Cup and where"), c("open chatgpt and ask it who won the 2010 World Cup and where"))
        assertEquals(FastIntent.AskApp("Gemini", "write a short poem"), c("ask gemini to write a short poem"))
        assertNull(c("tell her I'm coming"))
        assertNull("sending a WhatsApp is not a fast action", c("Send Kwame a WhatsApp message saying I'll call him later."))
    }

    @Test fun app_task_tools_load_for_their_requests_and_never_for_triggers() {
        val dir = listOf(java.io.File("src/main/assets/config"), java.io.File("app/src/main/assets/config")).first { it.isDirectory }
        AppConfig.loadFrom(dir.listFiles { f -> f.name.endsWith(".json") }!!.map { "config/${it.name}" to it.readText() })
        val all = defaultTools().map { it.name }
        fun sel(goal: String) = com.tekyerema.agent.ToolSelector.select(goal, emptySet(), emptySet(), all)
        assertTrue("install_app" in sel("install the Claude app"))
        assertTrue("install_app" in sel("download Jiji from the Play Store"))
        assertTrue("take_photo" in sel("take a selfie with the front camera"))
        assertTrue("ask_app" in sel("open ChatGPT and ask it something"))
        assertTrue("google_sign_in" in sel("sign in to Canva with Continue with Google"))
        assertTrue("play_music" in sel("skip this track"))
        assertTrue("send_sms" in sel("send Ama a WhatsApp message"))
        assertFalse("install_app" in sel("open chrome"))
        val t = tools()
        for (n in listOf("install_app", "google_sign_in", "take_photo", "ask_app"))
            assertFalse("$n must not run unattended", ToolManifest.mayCall(t.getValue(n), ToolManifest.CALLER_TRIGGER))
        assertFalse("sign-in only after the planner decided it", ToolManifest.mayCall(t.getValue("google_sign_in"), ToolManifest.CALLER_LOCAL))
    }
}
