package com.tekyerema

import com.tekyerema.accessibility.Box
import com.tekyerema.accessibility.PerceptionRoute
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiRange
import com.tekyerema.accessibility.UiSnapshot

/**
 * N-03 — representative screens for measuring the per-step prompt. Shaped like the A20e's real accessibility trees
 * (720×1560, depth-first traversal order, the way TekyeremaAccessibilityService collects them): a tappable row is an
 * unlabeled container followed by its icon and texts, which are tappable through it; lists carry collection hints.
 */
object PromptFixtures {
    private class B {
        val out = ArrayList<UiElement>()
        fun add(role: String, label: String?, box: Box, clickable: Boolean = false, longClickable: Boolean = false,
                editable: Boolean = false, scrollable: Boolean = false, checkable: Boolean = false, checked: Boolean = false,
                selected: Boolean = false, focused: Boolean = false, heading: Boolean = false, link: Boolean = false,
                range: UiRange? = null, collection: String? = null, custom: List<String> = emptyList()) {
            out += UiElement(out.size, role, label, box, clickable = clickable, longClickable = longClickable, editable = editable,
                scrollable = scrollable, checkable = checkable, checked = checked, selected = selected, focused = focused,
                heading = heading, link = link, range = range, collection = collection, customActions = custom)
        }

        /** A list row: the clickable container, its icon, then its texts (tappable through the container). */
        fun row(top: Int, height: Int, texts: List<String>, icon: String? = null, iconLabel: String? = null, longClickable: Boolean = false,
                index: Int? = null) {
            add("LinearLayout", null, Box(0, top, 720, top + height), clickable = true, longClickable = longClickable,
                collection = index?.let { "row $it" })
            if (icon != null) add(icon, iconLabel, Box(24, top + 20, 104, top + 100), clickable = true)
            texts.forEachIndexed { i, t -> add("TextView", t, Box(128, top + 20 + i * 44, 690, top + 60 + i * 44), clickable = true) }
        }

        /** What Perception.observe records for an ordinary tree read. */
        fun route() = PerceptionRoute("tree", "tree exposed ${out.count { it.interactive }} actionable elements")
    }

    /** Samsung Settings home, first page (9 rows visible). */
    val settingsHome: UiSnapshot = B().run {
        add("TextView", "Settings", Box(40, 120, 400, 200), heading = true)
        add("ImageButton", "Search", Box(560, 120, 640, 200), clickable = true)
        add("ImageButton", "More options", Box(640, 120, 720, 200), clickable = true)
        add("RecyclerView", null, Box(0, 220, 720, 1560), scrollable = true, collection = "list 22 rows x 1 cols")
        val rows = listOf(
            "Samsung account" to "Profile • Apps • Samsung Cloud",
            "Connections" to "Wi-Fi • Bluetooth • Airplane mode • Data usage",
            "Sounds and vibration" to "Sound mode • Ringtone",
            "Notifications" to "Status bar • Do not disturb",
            "Display" to "Brightness • Eye comfort shield • Navigation bar",
            "Wallpaper" to "Home and lock screen wallpaper",
            "Themes" to "Themes • Wallpapers • Icons",
            "Home screen" to "Layout • App icon badges",
            "Lock screen" to "Screen lock type • Always On Display")
        rows.forEachIndexed { i, (t, s) -> row(240 + i * 146, 140, listOf(t, s), icon = "ImageView", index = i) }
        UiSnapshot("com.android.settings", "Settings", out, 1L, route(), appName = "Settings")
    }

    /** Settings → About phone (the answer screen of the "which Android version" task). */
    val aboutPhone: UiSnapshot = B().run {
        add("ImageButton", "Navigate up", Box(0, 120, 100, 200), clickable = true)
        add("TextView", "About phone", Box(110, 120, 500, 200), heading = true)
        add("RecyclerView", null, Box(0, 220, 720, 1560), scrollable = true, collection = "list 9 rows x 1 cols")
        row(240, 110, listOf("Phone name", "Galaxy A20e"), index = 0)
        row(356, 110, listOf("Phone number", "Unknown"), index = 1)
        row(472, 110, listOf("Model number", "SM-A202F/DS"), index = 2)
        row(588, 110, listOf("Serial number", "R58M7XXXXXX"), index = 3)
        row(704, 110, listOf("IMEI", "3574XXXXXXXXXXX"), index = 4)
        row(820, 110, listOf("Status information", "View the SIM card status, IMEI, and other information."), index = 5)
        row(936, 110, listOf("Legal information"), index = 6)
        row(1052, 110, listOf("Software information", "View the currently installed Android version, baseband version, kernel version, build number, and more."), index = 7)
        row(1168, 110, listOf("Battery information", "View your phone's battery status, remaining power, and other information."), index = 8)
        UiSnapshot("com.android.settings", "About phone", out, 2L, route(), appName = "Settings")
    }

    /** WhatsApp chat list: 8 chats visible (long-pressable rows), tabs, a FAB — about 50 elements, so the listing cap bites. */
    val whatsappChats: UiSnapshot = B().run {
        add("TextView", "WhatsApp", Box(30, 60, 400, 140))
        add("ImageView", "Camera", Box(470, 60, 550, 140), clickable = true)
        add("ImageView", "Search", Box(550, 60, 630, 140), clickable = true)
        add("ImageView", "More options", Box(630, 60, 720, 140), clickable = true)
        add("LinearLayout", null, Box(0, 150, 240, 230), clickable = true, selected = true)
        add("TextView", "Chats", Box(40, 160, 160, 220), clickable = true, selected = true)
        add("TextView", "3 unread chats", Box(170, 170, 220, 210), clickable = true)
        add("LinearLayout", null, Box(240, 150, 480, 230), clickable = true)
        add("TextView", "Status", Box(300, 160, 420, 220), clickable = true)
        add("LinearLayout", null, Box(480, 150, 720, 230), clickable = true)
        add("TextView", "Calls", Box(560, 160, 660, 220), clickable = true)
        add("ListView", null, Box(0, 230, 720, 1560), scrollable = true)
        val chats = listOf(
            Triple("Kofi Mensah", "10:42 AM", "Are we still meeting at Accra Mall today?"),
            Triple("Mama", "9:15 AM", "Call me when you wake up"),
            Triple("Ama Serwaa", "Yesterday", "Photo"),
            Triple("Family Group", "Yesterday", "Yaw: Happy birthday Efua! 🎉"),
            Triple("Kwame Boateng", "Yesterday", "Ok, I'll send it tomorrow morning"),
            Triple("Church Choir", "Tuesday", "Rehearsal moved to 6pm"),
            Triple("Abena", "Monday", "Thanks!"),
            Triple("MTN MoMo Agent Yaw", "Sunday", "Your float is ready"))
        chats.forEachIndexed { i, (name, time, last) ->
            val top = 240 + i * 150
            add("RelativeLayout", null, Box(0, top, 720, top + 146), clickable = true, longClickable = true)
            add("ImageView", "Profile photo, $name", Box(20, top + 18, 130, top + 128), clickable = true)
            add("TextView", name, Box(150, top + 20, 520, top + 70), clickable = true)
            add("TextView", time, Box(540, top + 20, 700, top + 70), clickable = true)
            add("TextView", last, Box(150, top + 76, 640, top + 126), clickable = true)
            if (i < 2) add("TextView", "${i + 1} unread message${if (i == 0) "" else "s"}", Box(650, top + 80, 700, top + 120), clickable = true)
        }
        add("ImageButton", "New chat", Box(580, 1400, 700, 1520), clickable = true)
        UiSnapshot("com.whatsapp", "WhatsApp", out, 3L, route(), appName = "WhatsApp")
    }

    /** WhatsApp conversation with Kofi, the compose box focused (the step before typing). */
    val whatsappConversation: UiSnapshot = B().run {
        add("ImageButton", "Navigate up", Box(0, 60, 80, 140), clickable = true)
        add("LinearLayout", null, Box(80, 60, 460, 140), clickable = true)
        add("ImageView", "Profile photo, Kofi Mensah", Box(90, 70, 150, 130), clickable = true)
        add("TextView", "Kofi Mensah", Box(160, 66, 450, 104), clickable = true)
        add("TextView", "online", Box(160, 104, 450, 134), clickable = true)
        add("ImageButton", "Video call", Box(470, 60, 550, 140), clickable = true)
        add("ImageButton", "Voice call", Box(550, 60, 630, 140), clickable = true)
        add("ImageButton", "More options", Box(630, 60, 720, 140), clickable = true)
        add("ListView", null, Box(0, 150, 720, 1380), scrollable = true)
        val msgs = listOf("Hi Kofi", "Hello, how are you?", "I'm fine. Are we still meeting today?",
            "Are we still meeting at Accra Mall today?", "10:42 AM")
        msgs.forEachIndexed { i, m ->
            val top = 900 + i * 90
            add("TextView", m, Box(40, top, 680, top + 80), longClickable = i < 4)
        }
        add("ImageButton", "Emoji", Box(10, 1400, 90, 1480), clickable = true)
        add("EditText", "Message", Box(90, 1400, 520, 1480), clickable = true, editable = true, focused = true)
        add("ImageButton", "Attach", Box(520, 1400, 600, 1480), clickable = true)
        add("ImageButton", "Camera", Box(600, 1400, 660, 1480), clickable = true)
        add("ImageButton", "Voice message", Box(660, 1400, 720, 1480), clickable = true, longClickable = true)
        UiSnapshot("com.whatsapp", "Kofi Mensah", out, 4L, route(), appName = "WhatsApp")
    }

    /** One UI quick settings, expanded: tiles are checkable switches, plus the brightness slider. */
    val quickSettings: UiSnapshot = B().run {
        add("TextView", "10:42", Box(20, 40, 200, 110))
        add("TextView", "Tue, 29 September", Box(20, 110, 360, 150))
        add("ImageButton", "Search", Box(420, 40, 500, 120), clickable = true)
        add("ImageButton", "Power off", Box(500, 40, 580, 120), clickable = true)
        add("ImageButton", "Open settings", Box(580, 40, 660, 120), clickable = true)
        add("ImageButton", "More options", Box(660, 40, 720, 120), clickable = true)
        val tiles = listOf("Wi-Fi" to true, "Sound" to true, "Bluetooth" to false, "Auto rotate" to false, "Flashlight" to false,
            "Airplane mode" to false, "Power saving" to false, "Mobile data" to true, "Location" to true, "Blue light filter" to false,
            "Do not disturb" to false, "Mobile Hotspot" to false)
        tiles.forEachIndexed { i, (t, on) ->
            val x = (i % 4) * 180; val y = 180 + (i / 4) * 190
            add("Switch", t, Box(x, y, x + 180, y + 180), clickable = true, longClickable = true, checkable = true, checked = on)
        }
        add("SeekBar", "Brightness", Box(40, 780, 600, 860), range = UiRange(0f, 255f, 128f))
        add("ImageButton", "Adaptive brightness", Box(600, 780, 700, 860), clickable = true, checkable = true, checked = true)
        add("TextView", "Device control", Box(40, 900, 340, 980), clickable = true)
        add("TextView", "Media output", Box(380, 900, 680, 980), clickable = true)
        UiSnapshot("com.android.systemui", null, out, 5L, route(), appName = "System UI",
            windowType = "system")
    }

    /** A news article in Chrome: headings, links and long paragraphs — far more than the listing cap. */
    val chromeArticle: UiSnapshot = B().run {
        add("ImageButton", "Home", Box(0, 60, 80, 140), clickable = true)
        add("EditText", "bbc.com/news/world-africa-cedi-rally", Box(80, 60, 520, 140), clickable = true, editable = true)
        add("ImageButton", "Switch or close tabs, 3 open tabs", Box(520, 60, 620, 140), clickable = true)
        add("ImageButton", "Customize and control Google Chrome", Box(620, 60, 720, 140), clickable = true)
        add("WebView", "Ghana's cedi posts biggest weekly gain in a year - BBC News", Box(0, 150, 720, 1560), scrollable = true)
        add("TextView", "BBC", Box(20, 170, 200, 230), clickable = true, link = true)
        listOf("Home", "News", "Sport", "Business", "Innovation", "Culture").forEachIndexed { i, t ->
            add("TextView", t, Box(20 + i * 110, 240, 120 + i * 110, 290), clickable = true, link = true)
        }
        add("TextView", "Ghana's cedi posts biggest weekly gain in a year", Box(20, 310, 700, 420), heading = true)
        add("TextView", "29 September 2026", Box(20, 430, 400, 470))
        add("TextView", "Thomas Naadi, BBC News, Accra", Box(20, 470, 500, 510))
        val para = listOf(
            "Ghana's currency, the cedi, has recorded its strongest weekly performance against the US dollar in more than a year, traders in Accra say.",
            "The Bank of Ghana said the rally reflected improved inflows from cocoa and gold exports, as well as tighter liquidity in the banking system.",
            "The cedi closed on Friday at 11.55 to the dollar, compared with 12.40 a week earlier, according to central bank data.",
            "Importers welcomed the gains but warned that prices in the markets would take time to fall.",
            "\"We are still paying for goods we ordered three months ago,\" said Akosua Frimpong, a trader at Makola market.",
            "Analysts said the rally could be short-lived if demand for dollars picks up ahead of the festive season.",
            "The government has repeatedly said it expects the currency to stabilise as the economy recovers.",
            "Inflation eased to 18.5% in August, the lowest level in three years, the statistics service said last week.",
            "The central bank will announce its next interest rate decision on 14 October.")
        para.forEachIndexed { i, p -> add("TextView", p, Box(20, 530 + i * 110, 700, 630 + i * 110)) }
        add("TextView", "Related", Box(20, 1540, 300, 1560), heading = true)
        for (i in 1..30) add("TextView", "Related story $i: markets, cocoa prices and the cedi outlook for the quarter",
            Box(20, 1560, 700, 1560 + i), clickable = true, link = true)
        UiSnapshot("com.android.chrome", "Ghana's cedi posts biggest weekly gain in a year - BBC News", out, 6L,
            route(), appName = "Chrome", truncated = true)
    }

    /** A canvas screen the tree can't read, after a vision look: OCR elements carry their own bounds. */
    val visionGame: UiSnapshot = UiSnapshot("com.example.ludo", null, listOf(
        UiElement(0, "View", null, Box(0, 0, 720, 1560), clickable = true),
        UiElement(1, "ocr_text", "Play with friends", Box(160, 700, 560, 780), source = com.tekyerema.accessibility.ElementSource.VISION),
        UiElement(2, "ocr_text", "Play online", Box(160, 820, 560, 900), source = com.tekyerema.accessibility.ElementSource.VISION),
        UiElement(3, "ocr_text", "Settings", Box(160, 940, 560, 1020), source = com.tekyerema.accessibility.ElementSource.VISION)),
        7L, PerceptionRoute("tree+vision", "vision requested by the model"), appName = "Ludo King")

    // ---------------------------------------------------------------- step records (as AgentEngine writes them)
    val openSettings = "open_app({\"app\":\"Settings\"}) → success [intent]; verified=true (foreground is com.android.settings); Opened Settings."
    val scrollSettings = "scroll({\"direction\":\"down\",\"expects\":\"element_present: About phone\"}) → success [node]; verified=true " +
        "(found 'About phone') (kept scrolling 2 more times until it appeared); Scrolled down."
    val tapAbout = "tap({\"label\":\"About phone\"}) → success [node]; verified=true (found 'Software information'); Tapped 'About phone'."
    val openWhatsapp = "open_app({\"app\":\"WhatsApp\"}) → success [intent]; verified=true (foreground is com.whatsapp); Opened WhatsApp."
    val tapKofi = "tap({\"element_id\":14}) → success [node]; verified=true (found 'Message'); Tapped 'Kofi Mensah'."
    val quickSettingsOpened = "key_event({\"key\":\"quick_settings\"}) → success [global]; verified=true (found 'Bluetooth'); Opened quick settings."
    val searchCedi = "web_search({\"query\":\"cedi dollar rate this week\"}) → success [web]; verified=true (observation); [untrusted] " +
        "The cedi closed at 11.55 per US dollar on Friday, its best week in a year (bbc.com, myjoyonline.com)."
    val openChrome = "open_app({\"app\":\"Chrome\"}) → success [intent]; verified=true (foreground is com.android.chrome); Opened Chrome."
    val tapArticle = "tap({\"label\":\"Ghana's cedi posts biggest weekly gain\"}) → success [node]; verified=partial (found no " +
        "'element_present: Related'; but it moved on to new content, so keep going); Tapped 'Ghana's cedi posts biggest weekly gain in a year'."

    val conversation = listOf("User: what time is it", "Tɛkyerɛma: It's 10:40 in the morning.",
        "User: did Kofi message me", "Tɛkyerɛma: Yes — Kofi Mensah asked if you're still meeting at Accra Mall today.")
}
