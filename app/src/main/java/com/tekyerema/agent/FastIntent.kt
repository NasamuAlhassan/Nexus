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
    /** E-21: take a photo now with the back camera (the camera app saves it). */
    object TakePhoto : FastIntent("TAKE_PHOTO")
    /** E-21: take a selfie now with the front camera. */
    object TakeSelfie : FastIntent("TAKE_SELFIE")
    /** E-14: play [query] (or an [artist]'s songs), in [app] when named; all null = "play some music". */
    data class PlayMusic(val query: String?, val artist: String? = null, val app: String? = null) : FastIntent("PLAY_MUSIC")
    /** E-20: ask an AI assistant app ([app], e.g. ChatGPT) [question] and read back its answer. */
    data class AskApp(val app: String, val question: String) : FastIntent("ASK_APP")
    object CalendarToday : FastIntent("CALENDAR_TODAY")
    object LockQuery : FastIntent("LOCK_QUERY")
    /** H-08: a preference set by voice — key "rate" (slower|faster|normal), "verbosity" (brief|detailed|normal),
     *  "patience" (patient|quick|normal). */
    data class Preference(val key: String, val value: String) : FastIntent("PREFERENCE")
    // ---- W6 ----
    /** P-03 / P-06 / P-01: "text" | "color" | "describe". */
    data class Camera(val mode: String) : FastIntent("CAMERA")
    /** K-15: what changed on the screen since it was last described. */
    object WhatChanged : FastIntent("WHAT_CHANGED")
    /** K-09: "in" | "out" | "reset". */
    data class Magnify(val action: String) : FastIntent("MAGNIFY")
    /** L-04: remember [name] (a contact) as the emergency contact. */
    data class SetEmergencyContact(val contact: String) : FastIntent("SET_EMERGENCY_CONTACT")
    /** L-04: put a Ghana emergency line on the dial pad — "ambulance" | "police" | "fire" | "emergency". */
    data class EmergencyLine(val service: String) : FastIntent("EMERGENCY_LINE")
    // ---- W7 ----
    /** A-13: "every day at 7 read my calendar", "at 9 pm remind me to take my medicine" (the user's words). */
    data class CreateSchedule(val text: String) : FastIntent("CREATE_SCHEDULE")
    object ListSchedules : FastIntent("LIST_SCHEDULES")
    object ClearSchedules : FastIntent("CLEAR_SCHEDULES")
    /** A-12: "tell me when Ama messages me" / "stop telling me when Ama messages". */
    data class WatchSender(val who: String, val on: Boolean) : FastIntent("WATCH_SENDER")
    /** I-07: where the last web answer came from; [links] = read the web addresses, not just the site names. */
    data class Sources(val links: Boolean) : FastIntent("SOURCES")
    // ---- W8 ----
    /** J-13: reverse the last request's reversible changes. */
    object Undo : FastIntent("UNDO")
    /** J-12: "what did you just do?" ([today] = false) / "what have you done today?". */
    data class WhatDidYouDo(val today: Boolean) : FastIntent("WHAT_DID_YOU_DO")
    /** H-12: "what do you remember about me?" */
    object MemoryQuery : FastIntent("MEMORY_QUERY")
    /** H-12: "forget that" ([scope] "last"), "forget everything" ("all"), "forget Ama" ("person", [who]). */
    data class Forget(val scope: String, val who: String? = null) : FastIntent("FORGET")
    /** J-15: "clear my history" — the History list, the action audit, traces and telemetry. */
    object ClearHistory : FastIntent("CLEAR_HISTORY")
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
        // X-03: phrasings from the per-locale rule files come first (config, not code).
        if (com.tekyerema.core.config.FeatureFlags.isEnabled("X-03", true)) RouterRules.match(g)?.let { return it }
        // W7, before the compound check: a schedule's goal may itself be compound (the trigger re-checks what may run).
        scheduleIntent(g, raw)?.let { return it }
        if (RE_LIST_SCHEDULES.containsMatchIn(g)) return FastIntent.ListSchedules
        if (RE_CLEAR_SCHEDULES.matches(g)) return FastIntent.ClearSchedules
        if (RE_SOURCES.matches(g)) return FastIntent.Sources(links = RE_LINK_WORD.containsMatchIn(g))
        RE_UNWATCH.matchEntire(g)?.let { m -> return FastIntent.WatchSender(watchName(raw, m.groupValues[1]), on = false) }
        RE_WATCH.matchEntire(g)?.let { m -> return FastIntent.WatchSender(watchName(raw, m.groupValues[1]), on = true) }
        // W8: undo, the action audit, memory controls.
        if (RE_UNDO.matches(g)) return FastIntent.Undo
        if (RE_WHAT_DID.matches(g)) return FastIntent.WhatDidYouDo(today = g.contains("today") || g.contains("have you done"))
        if (RE_MEMORY_QUERY.matches(g)) return FastIntent.MemoryQuery
        if (RE_FORGET_LAST.matches(g)) return FastIntent.Forget("last")
        if (RE_FORGET_ALL.matches(g)) return FastIntent.Forget("all")
        if (RE_CLEAR_HISTORY.matches(g)) return FastIntent.ClearHistory
        RE_FORGET_PERSON.matchEntire(g)?.let { m -> return FastIntent.Forget("person", watchName(raw, m.groupValues[1])) }
        // E-20, before the compound check: "open ChatGPT and ask it …" is ONE deterministic action (the question may
        // itself contain "and").
        askApp(g, raw)?.let { return it }
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

            // E-14: "play Sarkodie on YouTube Music", "play songs by Stonebwoy" — before the bare transport words.
            playMusic(g) != null -> playMusic(g)
            RE_MEDIA_NEXT.containsMatchIn(g) -> FastIntent.MediaNext
            RE_MEDIA_PREV.containsMatchIn(g) -> FastIntent.MediaPrevious
            RE_MEDIA_PAUSE.containsMatchIn(g) -> FastIntent.MediaPause
            RE_MEDIA_STOP.containsMatchIn(g) -> FastIntent.MediaStop
            RE_MEDIA_PLAY.containsMatchIn(g) -> FastIntent.MediaPlay

            // H-08 preferences by voice (before the phone actions: "talk slower" is not a call).
            preference(g) != null -> preference(g)
            // W6: camera, screen changes, magnification, emergency set-up.
            RE_CAMERA_COLOR.containsMatchIn(g) -> FastIntent.Camera("color")
            RE_CAMERA_TEXT.containsMatchIn(g) -> FastIntent.Camera("text")
            RE_CAMERA_DESCRIBE.containsMatchIn(g) -> FastIntent.Camera("describe")
            RE_WHAT_CHANGED.containsMatchIn(g) -> FastIntent.WhatChanged
            RE_MAGNIFY_IN.containsMatchIn(g) -> FastIntent.Magnify("in")
            RE_MAGNIFY_OUT.containsMatchIn(g) -> FastIntent.Magnify("out")
            RE_MAGNIFY_RESET.containsMatchIn(g) -> FastIntent.Magnify("reset")
            RE_SET_EMERGENCY.find(g) != null -> FastIntent.SetEmergencyContact(RE_SET_EMERGENCY.find(g)!!.groupValues[1].trim())
            RE_EMERGENCY_LINE.find(g) != null -> FastIntent.EmergencyLine(emergencyService(RE_EMERGENCY_LINE.find(g)!!.groupValues[1]))
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
            RE_SELFIE.containsMatchIn(g) -> FastIntent.TakeSelfie
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

    // ---- W7: schedules (A-13), notification rules (A-12), source readback (I-07)
    private val RE_SCHEDULE_CUE = Regex(
        "^(?:at \\d|at (?:noon|midday|midnight)\\b|every\\b|each\\b|daily\\b|on (?:weekdays|weekends|(?:mon|tues|wednes|thurs|fri|satur|sun)days?)\\b)" +
            "|\\bremind me\\b|\\b(?:every|each) (?:day|morning|afternoon|evening|night|weekday|(?:mon|tues|wednes|thurs|fri|satur|sun)day)\\b" +
            "|\\bdaily\\b|^(?:set|create|add|make) (?:a|an|me a) reminder\\b")
    private val RE_NOT_SCHEDULE = Regex("\\b(?:alarm|timer|wake me)\\b")
    private val RE_LIST_SCHEDULES = Regex("^(?:what|which) (?:are )?(?:my )?(?:schedules|reminders|scheduled tasks)\\b|" +
        "^(?:list|read|tell me) (?:all )?(?:of )?my (?:schedules|reminders|scheduled tasks)$|^what reminders do i have|^do i have any (?:reminders|schedules)")
    private val RE_CLEAR_SCHEDULES = Regex("^(?:cancel|delete|remove|clear|stop) (?:all )?(?:of )?my (?:schedules|reminders|scheduled tasks)$")
    private val RE_SOURCES = Regex(
        "^(?:what(?:'s| is| are| was| were)? (?:the |your |its )?sources?(?: for (?:that|this|it))?" +
            "|(?:read|give|tell) (?:me )?(?:the |your )?sources?(?: for (?:that|this|it))?" +
            "|where did (?:you|that|this|it) (?:get|find|come from|read)(?: (?:that|this|it))?(?: from)?" +
            "|where(?:'s| is| was) (?:that|this|it) from" +
            "|(?:what|which) (?:site|website|sites|websites) (?:was|is|were|are|did) (?:that|this|it)(?: come)?(?: from)?" +
            "|(?:read|give|tell) (?:me )?(?:the )?(?:links?|web addresses?|websites?) for (?:that|this|it)(?: answer)?" +
            "|sources? please)$")
    private val RE_LINK_WORD = Regex("\\b(?:links?|addresses?|websites?|sites?)\\b")
    private val RE_WATCH = Regex("^(?:tell|let) me (?:know )?when ([\\p{L}' .-]{2,40}?) (?:messages|texts|writes|sends|calls|emails|whatsapps)(?: me)?(?: something| anything)?$")
    private val RE_UNWATCH = Regex("^stop (?:telling me|announcing|letting me know) (?:when |about )?(?:messages from )?([\\p{L}' .-]{2,40}?)(?:'s)?(?: (?:messages|texts|writes|sends|calls|emails))?(?: me)?$")

    private val RE_UNDO = Regex("^(?:undo(?: that| it| the last (?:thing|one|change))?|take (?:that|it) back|reverse (?:that|it)|" +
        "put it back(?: the way it was)?|change it back)$")
    private val RE_WHAT_DID = Regex("^what (?:did|have) you (?:just )?(?:do|done)(?: for me)?(?: just now| today| so far)?$")
    private val RE_MEMORY_QUERY = Regex("^what do you (?:remember|know)(?: about me)?$|^what have you (?:remembered|saved|kept)(?: about me)?$")
    private val RE_FORGET_LAST = Regex("^(?:forget (?:that|it|what i (?:just )?said)|scratch that)$")
    private val RE_FORGET_ALL = Regex("^(?:forget everything(?: about me)?|(?:clear|delete|erase|wipe) (?:your|my) memory)$")
    private val RE_FORGET_PERSON = Regex("^forget (?:everything about |all about |about )?(?!that$|it$|everything)([\\p{L}' .-]{2,40}?)(?:'s messages| messages)?$")
    private val RE_CLEAR_HISTORY = Regex("^(?:clear|delete|erase|wipe) (?:my |the |your )?(?:history|activity(?: log)?|action log)$")

    private fun scheduleIntent(g: String, raw: String): FastIntent? {
        if (!RE_SCHEDULE_CUE.containsMatchIn(g) || RE_NOT_SCHEDULE.containsMatchIn(g)) return null
        // "remind me to …" without a usable time still comes here, so the router can ask for the time.
        val text = raw.trim()
        return if (com.tekyerema.agent.triggers.ScheduleParser.parse(text) != null || g.contains("remind me")) FastIntent.CreateSchedule(text) else null
    }

    /** The name as the user said it (original case), found in the raw text. */
    private fun watchName(raw: String, lower: String): String {
        val i = raw.lowercase().indexOf(lower)
        return (if (i >= 0) raw.substring(i, i + lower.length) else lower).trim()
    }

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
    // ---- W6 camera / screen / magnification / emergency ----
    // Camera phrases name a physical object or the camera, so "read my screen" never opens the camera.
    private val RE_CAMERA_TEXT = Regex("\\bwhat does (?:this|the|that) (?:label|sign|paper|letter|document|note|package|box|bottle|card|receipt|medicine|book) say\\b|" +
        "\\bread (?:this|the|that) (?:label|sign|paper|letter|document|note|package|box|bottle|card|receipt|medicine|book)\\b|" +
        "\\bread (?:it |this |the text )?(?:with|using) the camera\\b|^scan (?:this|it)$")
    private val RE_CAMERA_COLOR = Regex("\\bwhat colou?r is (?:this|that|it|my \\w+)\\b|\\bwhat colou?r are (?:these|those|they)\\b|\\bis the light on\\b")
    private val RE_CAMERA_DESCRIBE = Regex("\\bwhat(?:'s| is) in front of me\\b|\\bwhat am i holding\\b|\\bdescribe what (?:you see|is in front of me)\\b|" +
        "\\blook at this for me\\b|\\bwhat is this (?:thing|object)\\b")
    private val RE_WHAT_CHANGED = Regex("^(?:what(?:'s| has)? changed|what(?:'s| is) new on (?:the |my )?screen|anything new on (?:the |my )?screen)$")
    private val RE_MAGNIFY_IN = Regex("\\bmagnify\\b|\\bzoom (?:in )?(?:on )?the screen\\b|\\bmake the screen bigger\\b")
    private val RE_MAGNIFY_OUT = Regex("\\bzoom out (?:of )?the screen\\b|\\bmake the screen smaller\\b")
    private val RE_MAGNIFY_RESET = Regex("\\b(?:stop|turn off|reset) (?:magnif(?:ying|ication)|zooming|the zoom)\\b")
    private val RE_SET_EMERGENCY = Regex("^(?:set|make|save) (?:my )?emergency contact (?:to |as )?(.{2,40})$")
    private val RE_EMERGENCY_LINE = Regex("^(?:please )?(?:call|get|dial|phone) (?:an? |the )?(ambulance|police|fire service|fire brigade|fire|emergency services?|emergency)(?: please)?$")

    private fun emergencyService(s: String): String = when {
        s.startsWith("ambulance") -> "ambulance"
        s.startsWith("police") -> "police"
        s.startsWith("fire") -> "fire"
        else -> "emergency"
    }

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
    // "who did I call last" / "who I called last" (A20e trace: missed, and the model wandered into Settings).
    private val RE_CALL_HISTORY = Regex("\\b(?:who (?:just )?called(?: me)?|missed calls?|any (?:missed )?calls|recent calls|call history|call log|did anyone call|" +
        "who (?:did )?i (?:just |last )?call(?:ed)?|my last call|(?:last|latest) (?:person|number) i (?:called|dialled|dialed))\\b")
    // SMS only — "texts", "sms", "text message(s)" or "my (last) text"; "read the text on the screen" is not an SMS.
    private val RE_LAST_SMS = Regex("\\b(?:read|check|what(?:'s| is| was)|any)\\b.*\\b(?:text messages?|texts|sms|my (?:last |latest |new )?text)\\b|^(?:my )?(?:last|latest|new) (?:text|sms|text message)s?$")
    private val RE_WHERE_AM_I = Regex("\\b(?:where am i|where i am|what(?:'s| is) my (?:current )?location|my current location|what street am i on)\\b")
    private val RE_ONLINE = Regex("\\b(?:am i (?:online|connected)|(?:is|do i have) (?:the )?internet|internet connection|am i on (?:wi-?fi|data|mobile data)|what network am i on)\\b")
    private val RE_NOW_PLAYING = Regex("\\b(?:what(?:'s| is) (?:playing|this song)|what song is (?:this|playing)|who (?:sings|is singing) this)\\b")
    private val RE_SHOW_ALARMS = Regex("\\b(?:show|open|list|check)\\b.*\\balarms\\b|^(?:my )?alarms$|\\bwhat alarms\\b")
    private val RE_SCREENSHOT = Regex("\\b(?:take|capture|grab|save)\\b.*\\bscreenshot\\b|^screenshot$")
    private val RE_POWER_MENU = Regex("\\b(?:power (?:menu|options)|power button menu|restart menu|shut ?down menu)\\b")
    // "open the camera" is no longer a photo: it only opens the Camera app (OpenApp), while "take a photo" now TAKES one.
    private val RE_TAKE_PHOTO = Regex("\\b(?:take|snap) (?:a |me a |my )?(?:photo|picture|pic)\\b")
    private val RE_SELFIE = Regex("\\b(?:take|snap|capture) (?:a |me a |my )?selfie\\b|^selfie(?: please)?$|\\b(?:photo|picture|pic) of (?:me|myself)\\b")

    // ---- E-14 music: "play X on <music app>", "play songs by X", "play X's songs", "play some music" ----
    private const val MUSIC_APPS = "youtube music|yt music|you tube music|spotify|boomplay|boom play|audiomack|samsung music|apple music|deezer"
    private val RE_PLAY_BY = Regex("^(?:please )?play (?:me )?(?:some )?(?:songs|music|tracks|hits) (?:by|from) (.+?)(?: (?:on|in|with|using) ($MUSIC_APPS))?(?: please)?$")
    private val RE_PLAY_POSSESSIVE = Regex("^(?:please )?play (?:me )?(?:some )?(?:of )?(.+?)'s (?:songs|music|tracks|hits)(?: (?:on|in|with|using) ($MUSIC_APPS))?(?: please)?$")
    private val RE_PLAY_ON = Regex("^(?:please )?play (?:me )?(.+?) (?:on|in|with|using|from) ($MUSIC_APPS)(?: please)?$")
    private val RE_PLAY_ANY = Regex("^(?:please )?play (?:some |my )?music(?: please)?$")

    private fun playMusic(g: String): FastIntent.PlayMusic? {
        RE_PLAY_BY.matchEntire(g)?.let { m -> return FastIntent.PlayMusic(null, m.groupValues[1].trim(), m.groupValues[2].ifBlank { null }) }
        RE_PLAY_POSSESSIVE.matchEntire(g)?.let { m -> return FastIntent.PlayMusic(null, m.groupValues[1].trim(), m.groupValues[2].ifBlank { null }) }
        RE_PLAY_ON.matchEntire(g)?.let { m ->
            val what = m.groupValues[1].trim()
            // "play some music on Spotify" = just play there; "play Sarkodie on YouTube Music" = that search.
            val q = what.takeUnless { Regex("^(?:some |my )?(?:music|songs)$").matches(it) }
            return FastIntent.PlayMusic(q, null, m.groupValues[2])
        }
        if (RE_PLAY_ANY.matches(g)) return FastIntent.PlayMusic(null)
        return null
    }

    // ---- E-20 assistants: "ask ChatGPT what …", "open ChatGPT and ask it …" ----
    private const val ASSISTANTS = "chatgpt|chat gpt|gemini|claude|copilot|perplexity"
    private val RE_ASK_APP = Regex("^(?:please |can you |could you )?(?:open ($ASSISTANTS) and )?(?:ask|tell) ($ASSISTANTS|it|him|her)(?: to)?[,:]? (.{3,})$")

    private fun askApp(g: String, raw: String): FastIntent.AskApp? {
        val m = RE_ASK_APP.matchEntire(g) ?: return null
        val named = m.groupValues[1].ifBlank { null } ?: m.groupValues[2].takeUnless { it in setOf("it", "him", "her") } ?: return null
        val app = if (named.replace(" ", "") == "chatgpt") "ChatGPT" else named.replaceFirstChar { it.uppercaseChar() }
        return FastIntent.AskApp(app, watchName(raw, m.groupValues[3]).trimEnd('.', '!', '?').trim().ifEmpty { return null } +
            if (raw.trim().endsWith("?")) "?" else "")
    }
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

    // "open/launch/start X" — X is the last group. Existing AppResolver does the actual name matching. "opening the
    // calculator." missed this on the A20e (2026-09-28) and went to the cloud model, which paged the home screen
    // left and right hunting for the icon — so -ing forms, stacked polite lead-ins, "up", "my" and a trailing
    // "app"/"please" are accepted. "opening hours …" is not an app.
    private val RE_OPEN = Regex("^(?:(?:please|kindly|can you|could you|would you|will you|i want to|i'd like to|i would like to|help me)\\s+)*" +
        "(?:open(?:ing)?|launch(?:ing)?|start(?:ing)?|go\\s+to|goto|bring\\s+up|pull\\s+up|put\\s+on|bue\\S*|bua\\S*)\\s+" +
        "(?!hours?\\b|times?\\b)(?:up\\s+)?(?:the\\s+|my\\s+)?(.+?)(?:\\s+app)?(?:\\s+(?:please|for me))?$")
    private val RE_APP_HAS_VERB = Regex("\\b(send|message|reply|forward|dial|text|call|find|search|tell|saying|then)\\b")
}
