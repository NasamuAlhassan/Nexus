package com.tekyerema.agent

/**
 * The deterministic device intents the Local Fast Path can execute WITHOUT the LLM/agent. Pure data —
 * classification ([FastIntentClassifier]) is Android-free and unit-tested.
 */
sealed class FastIntent(val name: String) {
    object Time : FastIntent("TIME")
    object Date : FastIntent("DATE")
    object Day : FastIntent("DAY")
    object Battery : FastIntent("BATTERY")
    object Storage : FastIntent("STORAGE")
    object VolumeUp : FastIntent("VOLUME_UP")
    object VolumeDown : FastIntent("VOLUME_DOWN")
    object VolumeMute : FastIntent("VOLUME_MUTE")
    object VolumeUnmute : FastIntent("VOLUME_UNMUTE")
    object VolumeQuery : FastIntent("VOLUME_QUERY")
    object BrightnessUp : FastIntent("BRIGHTNESS_UP")
    object BrightnessDown : FastIntent("BRIGHTNESS_DOWN")
    /** An absolute level: [fraction] 0.0 = minimum … 1.0 = maximum. */
    data class BrightnessSet(val fraction: Float) : FastIntent("BRIGHTNESS_SET")
    object FlashlightOn : FastIntent("FLASHLIGHT_ON")
    object FlashlightOff : FastIntent("FLASHLIGHT_OFF")
    object FlashlightQuery : FastIntent("FLASHLIGHT_QUERY")
    object WifiQuery : FastIntent("WIFI_QUERY")
    object WifiOn : FastIntent("WIFI_ON")
    object WifiOff : FastIntent("WIFI_OFF")
    object BluetoothQuery : FastIntent("BLUETOOTH_QUERY")
    object BluetoothOn : FastIntent("BLUETOOTH_ON")
    object BluetoothOff : FastIntent("BLUETOOTH_OFF")
    object MediaPlay : FastIntent("MEDIA_PLAY")
    object MediaPause : FastIntent("MEDIA_PAUSE")
    object MediaStop : FastIntent("MEDIA_STOP")
    object MediaNext : FastIntent("MEDIA_NEXT")
    object MediaPrevious : FastIntent("MEDIA_PREVIOUS")
    object Back : FastIntent("BACK")
    object Home : FastIntent("HOME")
    object Recents : FastIntent("RECENTS")
    object ScrollUp : FastIntent("SCROLL_UP")
    object ScrollDown : FastIntent("SCROLL_DOWN")
    object LockScreen : FastIntent("LOCK_SCREEN")
    object Notifications : FastIntent("NOTIFICATIONS")
    data class OpenApp(val query: String) : FastIntent("OPEN_APP")
    // ---- W4 deterministic phone actions (E-02, E-05, E-06, E-09, E-12, E-13, E-14, E-18, E-21, E-22, E-28, E-29) ----
    object AnswerCall : FastIntent("ANSWER_CALL")
    object EndCall : FastIntent("END_CALL")
    data class CallHistory(val missedOnly: Boolean) : FastIntent("CALL_HISTORY")
    object LastSms : FastIntent("LAST_SMS")
    object WhereAmI : FastIntent("WHERE_AM_I")
    object Connectivity : FastIntent("CONNECTIVITY")
    object NowPlaying : FastIntent("NOW_PLAYING")
    /** [mode] = "vibrate" | "silent" | "normal". */
    data class Ringer(val mode: String) : FastIntent("RINGER")
    object ShowAlarms : FastIntent("SHOW_ALARMS")
    object Screenshot : FastIntent("SCREENSHOT")
    object PowerMenu : FastIntent("POWER_MENU")
    object TakePhoto : FastIntent("TAKE_PHOTO")
    object CalendarToday : FastIntent("CALENDAR_TODAY")
    object LockQuery : FastIntent("LOCK_QUERY")
    /** H-08: a preference set by voice — key "rate" (slower|faster|normal), "verbosity" (brief|detailed|normal),
     *  "patience" (patient|quick|normal). */
    data class Preference(val key: String, val value: String) : FastIntent("PREFERENCE")
    /** Flip a Quick Settings tile ([QuickTiles]) to [on]. */
    data class QuickToggle(val key: String, val on: Boolean) : FastIntent("TOGGLE_${key.uppercase()}")
}

/**
 * Confidence-gated intent classification for the Local Fast Path. Deterministic, normalized-intent based
 * (not an ad-hoc pile of substrings): each category has a focused pattern, checked in a priority order that
 * resolves overlaps (query before toggle, brightness kept distinct from volume, media transport before
 * navigation). Returns null when it is NOT confident the request is a simple deterministic device op — the
 * caller then falls through to the existing agent, UNCHANGED. Never intercepts a compound/multi-step request.
 *
 * Twi: a few high-confidence normalized Twi forms are covered for the top intents (open=bue, home=fie,
 * back=san kɔ, volume so/ase). Anything not confidently matched falls through — never fake recognition.
 */
object FastIntentClassifier {

    fun classify(raw: String): FastIntent? {
        val g = raw.trim().lowercase().replace(Regex("\\s+"), " ").trim().trimEnd('.', '!', '?').trim()
        if (g.isEmpty()) return null
        // Compound / multi-step → the agent (the fast path is single deterministic action only).
        if (RE_MULTISTEP.containsMatchIn(g)) return null

        return when {
            // FIRST: an explicit "turn on/off / enable X" naming a Quick Settings tile — before the topic regexes,
            // or "enable battery saver" is read as a battery-LEVEL question.
            quickToggle(g) != null -> quickToggle(g)
            // E-09 ringer mode before the volume rules ("silent mode" is the ringer, not muting the media volume).
            ringerMode(g) != null -> FastIntent.Ringer(ringerMode(g)!!)
            RE_TIME.containsMatchIn(g) -> FastIntent.Time
            RE_DAY.containsMatchIn(g) -> FastIntent.Day
            RE_DATE.containsMatchIn(g) -> FastIntent.Date
            RE_BATTERY.containsMatchIn(g) -> FastIntent.Battery
            RE_STORAGE.containsMatchIn(g) && !RE_STORAGE_ACTION.containsMatchIn(g) -> FastIntent.Storage

            RE_VOL_QUERY.containsMatchIn(g) -> FastIntent.VolumeQuery
            RE_UNMUTE.containsMatchIn(g) -> FastIntent.VolumeUnmute
            RE_MUTE.containsMatchIn(g) -> FastIntent.VolumeMute
            RE_VOL_UP.containsMatchIn(g) -> FastIntent.VolumeUp
            RE_VOL_DOWN.containsMatchIn(g) -> FastIntent.VolumeDown

            brightnessLevel(g) != null -> FastIntent.BrightnessSet(brightnessLevel(g)!!)
            RE_BRIGHT_UP.containsMatchIn(g) -> FastIntent.BrightnessUp
            RE_BRIGHT_DOWN.containsMatchIn(g) -> FastIntent.BrightnessDown

            // C-02: a question about the torch is a query, never a switch; ON needs an on-command or the bare
            // word; any other mention ("flashlight settings", "why is my torch app slow") goes to the agent.
            RE_TORCH_QUERY.containsMatchIn(g) && !RE_REQUEST_VERB.containsMatchIn(g) -> FastIntent.FlashlightQuery
            RE_TORCH_OFF.containsMatchIn(g) -> FastIntent.FlashlightOff
            RE_TORCH_ON.containsMatchIn(g) -> FastIntent.FlashlightOn

            RE_WIFI_QUERY.containsMatchIn(g) -> FastIntent.WifiQuery
            RE_WIFI_ON.containsMatchIn(g) -> FastIntent.WifiOn
            RE_WIFI_OFF.containsMatchIn(g) -> FastIntent.WifiOff
            RE_BT_QUERY.containsMatchIn(g) -> FastIntent.BluetoothQuery
            RE_BT_ON.containsMatchIn(g) -> FastIntent.BluetoothOn
            RE_BT_OFF.containsMatchIn(g) -> FastIntent.BluetoothOff

            RE_MEDIA_NEXT.containsMatchIn(g) -> FastIntent.MediaNext
            RE_MEDIA_PREV.containsMatchIn(g) -> FastIntent.MediaPrevious
            RE_MEDIA_PAUSE.containsMatchIn(g) -> FastIntent.MediaPause
            RE_MEDIA_STOP.containsMatchIn(g) -> FastIntent.MediaStop
            RE_MEDIA_PLAY.containsMatchIn(g) -> FastIntent.MediaPlay

            // H-08 preferences by voice (before the phone actions: "talk slower" is not a call).
            preference(g) != null -> preference(g)
            // W4 phone actions — before lock/navigation so "lock" questions and call words aren't read as navigation.
            RE_ANSWER.matches(g) -> FastIntent.AnswerCall
            RE_END_CALL.matches(g) -> FastIntent.EndCall
            RE_CALL_HISTORY.containsMatchIn(g) -> FastIntent.CallHistory(missedOnly = g.contains("missed"))
            RE_LAST_SMS.containsMatchIn(g) -> FastIntent.LastSms
            RE_WHERE_AM_I.containsMatchIn(g) -> FastIntent.WhereAmI
            RE_ONLINE.containsMatchIn(g) -> FastIntent.Connectivity
            RE_NOW_PLAYING.containsMatchIn(g) -> FastIntent.NowPlaying
            RE_SHOW_ALARMS.containsMatchIn(g) -> FastIntent.ShowAlarms
            RE_SCREENSHOT.containsMatchIn(g) -> FastIntent.Screenshot
            RE_POWER_MENU.containsMatchIn(g) -> FastIntent.PowerMenu
            RE_TAKE_PHOTO.containsMatchIn(g) -> FastIntent.TakePhoto
            RE_CALENDAR_TODAY.containsMatchIn(g) -> FastIntent.CalendarToday
            RE_LOCK_QUERY.containsMatchIn(g) -> FastIntent.LockQuery
            RE_LOCK.containsMatchIn(g) -> FastIntent.LockScreen
            RE_NOTIFS.containsMatchIn(g) -> FastIntent.Notifications
            RE_RECENTS.containsMatchIn(g) -> FastIntent.Recents
            RE_SCROLL_UP.containsMatchIn(g) -> FastIntent.ScrollUp
            RE_SCROLL_DOWN.containsMatchIn(g) -> FastIntent.ScrollDown
            RE_HOME.containsMatchIn(g) -> FastIntent.Home
            RE_BACK.containsMatchIn(g) -> FastIntent.Back

            else -> openApp(g)
        }
    }

    /** "turn on do not disturb", "switch auto-rotate off", "enable battery saver" → a Quick Settings tile. */
    private fun quickToggle(g: String): FastIntent.QuickToggle? {
        for (re in RE_TOGGLE) {
            val m = re.find(g) ?: continue
            val (verb, phrase) = if (m.groupValues[1] in ONOFF_WORDS) m.groupValues[1] to m.groupValues[2] else m.groupValues[2] to m.groupValues[1]
            val tile = QuickTiles.forPhrase(phrase) ?: continue
            return FastIntent.QuickToggle(tile.key, on = verb in setOf("on", "enable", "activate"))
        }
        return null
    }
    private val ONOFF_WORDS = setOf("on", "off", "enable", "disable", "activate", "deactivate")
    private val RE_TOGGLE = listOf(
        Regex("^(?:please\\s+)?(?:turn|switch|put)\\s+(on|off)\\s+(.+)$"),              // turn on X
        Regex("^(?:please\\s+)?(?:turn|switch|put)\\s+(.+?)\\s+(on|off)$"),             // turn X off
        Regex("^(?:please\\s+)?(enable|disable|activate|deactivate)\\s+(.+)$")           // enable X
    )

    private fun openApp(g: String): FastIntent? {
        val app = RE_OPEN.find(g)?.groupValues?.last()?.trim() ?: return null
        if (app.isEmpty()) return null
        if (app.contains(",")) return null                       // "open whatsapp, find kwame" = compound
        if (RE_APP_HAS_VERB.containsMatchIn(app)) return null     // a second instruction, not an app label
        if (app.split(" ").size > 3) return null                 // an app label is a short noun phrase
        return FastIntent.OpenApp(app)
    }

    // Compound/multi-step markers → hand the whole thing to the agent.
    private val RE_MULTISTEP = Regex("\\b(and then|and|then|after that|na)\\b|;")

    private val RE_TIME = Regex("\\b(what(?:'?s| is)?\\s+(?:the\\s+)?time|what\\s+time\\s+is\\s+it|tell\\s+me\\s+(?:the\\s+)?time|current\\s+time|check\\s+the\\s+time|bere\\s+bɛn|bɛre\\s+bɛn)\\b")
    private val RE_DAY = Regex("\\bwhat\\s+day\\b|\\bwhich\\s+day\\b|\\bis\\s+today\\s+\\w+day\\b|\\btell\\s+me\\s+the\\s+day\\b|\\bday\\s+(?:of\\s+the\\s+week|is\\s+it|is\\s+today)\\b")
    private val RE_DATE = Regex("\\bdate\\b")
    // A storage QUESTION ("how much storage is left", "do I have enough space") — not an action on storage
    // ("free up space", "clear storage", "open storage settings"), which goes to the agent.
    private val RE_STORAGE = Regex("\\b(how\\s+much|what'?s|what\\s+is|check|remaining|left|enough|available)\\b.*\\b(storage|space)\\b|" +
        "\\b(storage|space)\\b.*\\b(left|remaining|available|do\\s+i\\s+have|is\\s+free)\\b")
    private val RE_STORAGE_ACTION = Regex("\\b(free\\s+up|clear|clean|delete|remove|open|settings|manage|make)\\b")
    private val RE_BATTERY = Regex("\\bbattery\\b|\\bhow\\s+much\\s+(?:charge|power)\\b")

    private val RE_VOL_QUERY = Regex("\\bwhat(?:'?s| is)?\\s+(?:my\\s+|the\\s+)?volume(?:\\s+level)?\\b|\\bcheck\\s+(?:the\\s+|my\\s+)?volume\\b|\\bvolume\\s+level\\b|\\bhow\\s+loud\\b")
    private val RE_UNMUTE = Regex("\\bunmute\\b|\\bun-mute\\b|\\bturn\\s+(?:the\\s+)?(?:sound|volume|audio)\\s+back\\s+on\\b|\\bunsilence\\b|\\bsound\\s+back\\s+on\\b")
    private val RE_MUTE = Regex("\\bmute\\b|\\bsilence\\b|\\bgo\\s+silent\\b|\\bsilent\\s+mode\\b")
    private val RE_VOL_UP = Regex("\\bvolume\\s+up\\b|\\blouder\\b|\\bturn\\s+it\\s+up\\b|\\bturn\\s+up\\s+(?:the\\s+|my\\s+)?(?:volume|sound)\\b|\\bturn\\s+(?:the\\s+)?(?:volume|sound)\\s+up\\b|\\b(?:increase|raise|boost|crank|pump|bump)\\s+(?:the\\s+|my\\s+)?(?:volume|sound)\\b|\\bmake\\s+it\\s+louder\\b|\\bma\\s+volume\\s+no\\s+so\\b")
    private val RE_VOL_DOWN = Regex("\\bvolume\\s+down\\b|\\b(?:quieter|softer)\\b|\\bturn\\s+it\\s+down\\b|\\bturn\\s+down\\s+(?:the\\s+|my\\s+)?(?:volume|sound)\\b|\\bturn\\s+(?:the\\s+)?(?:volume|sound)\\s+down\\b|\\b(?:decrease|lower|reduce|drop)\\s+(?:the\\s+|my\\s+)?(?:volume|sound)\\b|\\bmake\\s+it\\s+(?:quieter|softer)\\b")

    /** "set the brightness to maximum / 40 percent", "full brightness", "lowest brightness" → 0.0…1.0, else null. */
    private fun brightnessLevel(g: String): Float? {
        if (!g.contains("brightness")) return null   // "what's the brightest star?" must not touch the screen
        RE_BRIGHT_PCT.find(g)?.let { m -> return (m.groupValues[1].toInt().coerceIn(0, 100) / 100f) }
        if (RE_BRIGHT_MAX.containsMatchIn(g)) return 1f
        if (RE_BRIGHT_MIN.containsMatchIn(g)) return 0f
        return null
    }
    private val RE_BRIGHT_PCT = Regex("\\bbrightness\\b[^0-9]{0,20}?(\\d{1,3})\\s*(?:%|percent)")
    private val RE_BRIGHT_MAX = Regex("\\b(?:max|maximum|full|highest|brightest)\\b")
    private val RE_BRIGHT_MIN = Regex("\\b(?:min|minimum|lowest|dimmest)\\b")
    private val RE_BRIGHT_UP = Regex("\\b(?:increase|raise|boost|turn\\s+up|up)\\b[^.]*\\bbright(?:ness|er)?\\b|\\bbright(?:ness|er)?\\b[^.]*\\b(?:up|higher|brighter)\\b|\\bbrighter\\b|\\bbrighten\\b|\\bmake\\s+(?:the\\s+)?screen\\s+brighter\\b")
    private val RE_BRIGHT_DOWN = Regex("\\b(?:decrease|lower|reduce|turn\\s+down|dim)\\b[^.]*\\bbright(?:ness|er)?\\b|\\bmake\\s+(?:the\\s+)?screen\\s+(?:darker|dimmer)\\b|\\b(?:darker|dimmer)\\b|\\bdim\\s+the\\s+screen\\b")

    private val RE_TORCH_OFF = Regex("\\b(?:flashlight|torch|flash\\s*light)\\b[^.]*\\boff\\b|\\boff\\b[^.]*\\b(?:flashlight|torch|flash\\s*light)\\b")
    // ---- H-08 preferences by voice ----
    private val RE_PREF_SLOWER = Regex("^(?:please\\s+)?(?:speak|talk|read)\\s+(?:a (?:bit|little) )?(?:more )?slow(?:er|ly)(?: please)?$|^slow down(?: please)?$")
    private val RE_PREF_FASTER = Regex("^(?:please\\s+)?(?:speak|talk|read)\\s+(?:a (?:bit|little) )?faster(?: please)?$|^speed up(?: please)?$")
    private val RE_PREF_NORMAL_SPEED = Regex("^(?:speak|talk) (?:at )?(?:normal|regular) speed$")
    private val RE_PREF_BRIEF = Regex("^(?:please\\s+)?(?:be brief|keep it short|shorter answers|less detail|say less|don't talk so much)(?: please)?$")
    private val RE_PREF_DETAILED = Regex("^(?:please\\s+)?(?:be more detailed|more detailed answers|give me more detail(?:s)? from now on|explain more from now on)(?: please)?$")
    private val RE_PREF_PATIENT = Regex("^(?:please\\s+)?(?:be patient(?: with me)?|wait longer(?: for me)?|give me more time(?: to (?:speak|talk|finish))?|don't cut me off)(?: please)?$")

    private fun preference(g: String): FastIntent.Preference? = when {
        RE_PREF_SLOWER.matches(g) -> FastIntent.Preference("rate", "slower")
        RE_PREF_FASTER.matches(g) -> FastIntent.Preference("rate", "faster")
        RE_PREF_NORMAL_SPEED.matches(g) -> FastIntent.Preference("rate", "normal")
        RE_PREF_BRIEF.matches(g) -> FastIntent.Preference("verbosity", "brief")
        RE_PREF_DETAILED.matches(g) -> FastIntent.Preference("verbosity", "detailed")
        RE_PREF_PATIENT.matches(g) -> FastIntent.Preference("patience", "patient")
        else -> null
    }

    // ---- W4 phone actions ----
    // "accept"/"take" only with "call" (a bare "accept" is more likely a dialog button than a ringing phone).
    private val RE_ANSWER = Regex("^(?:please\\s+)?(?:(?:answer|pick up|pickup)(?:\\s+(?:the|this|my))?(?:\\s+(?:phone|call))?|(?:accept|take)\\s+(?:the |this |my )?call)(?:\\s+please)?$|^gye (?:frɛ|fre) no$")
    private val RE_END_CALL = Regex("^(?:please\\s+)?(?:hang up|end (?:the |this )?call|end it|cut (?:the |this )?call|reject (?:the |this )?call|decline (?:the |this )?call|drop (?:the |this )?call)(?:\\s+please)?$")
    private val RE_CALL_HISTORY = Regex("\\b(?:who (?:just )?called(?: me)?|missed calls?|any (?:missed )?calls|recent calls|call history|call log|did anyone call)\\b")
    // SMS only — "texts", "sms", "text message(s)" or "my (last) text"; "read the text on the screen" is not an SMS.
    private val RE_LAST_SMS = Regex("\\b(?:read|check|what(?:'s| is| was)|any)\\b.*\\b(?:text messages?|texts|sms|my (?:last |latest |new )?text)\\b|^(?:my )?(?:last|latest|new) (?:text|sms|text message)s?$")
    private val RE_WHERE_AM_I = Regex("\\b(?:where am i|where i am|what(?:'s| is) my (?:current )?location|my current location|what street am i on)\\b")
    private val RE_ONLINE = Regex("\\b(?:am i (?:online|connected)|(?:is|do i have) (?:the )?internet|internet connection|am i on (?:wi-?fi|data|mobile data)|what network am i on)\\b")
    private val RE_NOW_PLAYING = Regex("\\b(?:what(?:'s| is) (?:playing|this song)|what song is (?:this|playing)|who (?:sings|is singing) this)\\b")
    private val RE_SHOW_ALARMS = Regex("\\b(?:show|open|list|check)\\b.*\\balarms\\b|^(?:my )?alarms$|\\bwhat alarms\\b")
    private val RE_SCREENSHOT = Regex("\\b(?:take|capture|grab|save)\\b.*\\bscreenshot\\b|^screenshot$")
    private val RE_POWER_MENU = Regex("\\b(?:power (?:menu|options)|power button menu|restart menu|shut ?down menu)\\b")
    private val RE_TAKE_PHOTO = Regex("\\btake (?:a |me a )?(?:photo|picture|pic|selfie)\\b|^open (?:the )?camera$")
    private val RE_CALENDAR_TODAY = Regex("\\b(?:what(?:'s| is) on my (?:calendar|schedule|agenda)|my (?:schedule|agenda|calendar) (?:for )?today|do i have (?:any )?(?:meetings|appointments|events)|what do i have (?:on )?today)\\b")
    private val RE_LOCK_QUERY = Regex("^is (?:my )?(?:phone|screen) locked$")
    private val RE_RINGER = Regex("\\b(?:put|set|switch|turn|change)\\b(?:.*\\b(?:phone|ringer|sound)\\b)?.*\\b(?:on|to|into)\\s+(vibrate|vibration|silent|ring|ringing|sound|normal)(?:\\s+mode)?$|^(vibrate|silent|ring)\\s+mode(?:\\s+on)?$")

    private fun ringerMode(g: String): String? {
        val m = RE_RINGER.find(g) ?: return null
        return when ((m.groups[1] ?: m.groups[2])?.value) {
            "vibrate", "vibration" -> "vibrate"
            "silent" -> "silent"
            "ring", "ringing", "sound", "normal" -> "normal"
            else -> null
        }
    }

    private const val TORCH = "(?:flashlight|torch|flash\\s*light)"
    private val RE_TORCH_ON = Regex(
        "\\b(?:turn|switch|put)\\s+on\\b[^.]*\\b$TORCH\\b|\\b$TORCH\\b[^.]*\\bon\\b" +
            "|^(?:please\\s+)?(?:open|use|start)\\s+(?:the\\s+|my\\s+)?$TORCH(?:\\s+please)?$" +
            "|^(?:the\\s+|my\\s+)?$TORCH(?:\\s+please)?$")
    private val RE_TORCH_QUERY = Regex("^(?:is|are|was|what(?:'s| is)?|check)\\b[^.]*\\b$TORCH\\b")
    private val RE_REQUEST_VERB = Regex("\\b(?:turn|switch|put|can you|could you|would you|please)\\b")

    private val RE_WIFI_QUERY = Regex("\\bis\\s+(?:my\\s+)?wi.?fi\\b|\\bcheck\\s+(?:my\\s+)?wi.?fi\\b|\\bwi.?fi\\s+status\\b")
    private val RE_WIFI_ON = Regex("\\b(?:turn|switch|put)\\s+on\\b[^.]*\\bwi.?fi\\b|\\benable\\s+wi.?fi\\b|\\bwi.?fi\\s+on\\b")
    private val RE_WIFI_OFF = Regex("\\b(?:turn|switch|put)\\s+off\\b[^.]*\\bwi.?fi\\b|\\bdisable\\s+wi.?fi\\b|\\bwi.?fi\\s+off\\b")
    private val RE_BT_QUERY = Regex("\\bis\\s+(?:my\\s+)?bluetooth\\b|\\bcheck\\s+(?:my\\s+)?bluetooth\\b|\\bbluetooth\\s+status\\b")
    private val RE_BT_ON = Regex("\\b(?:turn|switch|put)\\b[^.]*\\bbluetooth\\b[^.]*\\bon\\b|\\bturn\\s+on\\s+bluetooth\\b|\\benable\\s+bluetooth\\b|\\bbluetooth\\s+on\\b")
    private val RE_BT_OFF = Regex("\\b(?:turn|switch|put)\\b[^.]*\\bbluetooth\\b[^.]*\\boff\\b|\\bturn\\s+off\\s+bluetooth\\b|\\bdisable\\s+bluetooth\\b|\\bbluetooth\\s+off\\b")

    private val RE_MEDIA_NEXT = Regex("^(?:next|skip)$|\\bnext\\s+(?:song|track)\\b|\\bskip\\b|\\bnext\\s+one\\b")
    private val RE_MEDIA_PREV = Regex("^previous$|\\bprevious\\s+(?:song|track)\\b|\\bgo\\s+back\\s+a\\s+(?:song|track)\\b|\\blast\\s+song\\b")
    private val RE_MEDIA_PAUSE = Regex("^pause$|\\bpause\\b")
    private val RE_MEDIA_STOP = Regex("^stop$|\\bstop\\s+(?:the\\s+)?(?:music|song|playback|playing)\\b")
    private val RE_MEDIA_PLAY = Regex("^(?:play|resume|continue|unpause)$")

    private val RE_LOCK = Regex("^lock$|\\block\\s+(?:the\\s+|my\\s+)?(?:phone|screen|device)\\b|\\block\\s+it\\b")
    private val RE_NOTIFS = Regex("\\b(?:open|show|pull\\s+down|expand)\\s+(?:my\\s+|the\\s+)?notifications?\\b|\\bnotification\\s+(?:shade|panel|drawer)\\b")
    private val RE_RECENTS = Regex("\\b(?:recent\\s+apps?|recents|app\\s+switcher|switch\\s+apps?|multitask)\\b")
    private val RE_SCROLL_UP = Regex("\\bscroll\\s+up\\b|\\bscroll\\s+to\\s+the\\s+top\\b|\\b(?:go|jump)\\s+to\\s+the\\s+top\\b|\\bto\\s+the\\s+top\\b")
    private val RE_SCROLL_DOWN = Regex("\\bscroll\\s+down\\b|\\bscroll\\s+to\\s+the\\s+bottom\\b|\\b(?:go|jump)\\s+to\\s+the\\s+bottom\\b|\\bto\\s+the\\s+bottom\\b|\\bscroll\\b")
    private val RE_HOME = Regex("^home$|\\bgo\\s+home\\b|\\bhome\\s+screen\\b|\\bhome\\s+button\\b|\\bk[ɔo]\\s+fie\\b|\\bfie\\b")
    private val RE_BACK = Regex("^back$|\\bgo\\s+back\\b|\\bback\\s+button\\b|\\bprevious\\s+screen\\b|\\bsan\\s+k[ɔo]\\b")

    // "open/launch/start X" — X is the last group. Existing AppResolver does the actual name matching.
    private val RE_OPEN = Regex("^(?:please\\s+|can\\s+you\\s+|could\\s+you\\s+|kindly\\s+|i\\s+want\\s+to\\s+)?(?:open|launch|start|go\\s+to|goto|bring\\s+up|pull\\s+up|put\\s+on|bue\\S*|bua\\S*)\\s+(?:the\\s+)?(.+)$")
    private val RE_APP_HAS_VERB = Regex("\\b(send|message|reply|forward|dial|text|call|find|search|tell|saying|then)\\b")
}
