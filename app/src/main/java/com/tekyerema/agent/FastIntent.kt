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
    object DeviceInfo : FastIntent("DEVICE_INFO")
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
    /** A simple sum answered on the phone ([LocalMath]); [answer] is the spoken result. */
    data class Calculate(val answer: String) : FastIntent("CALCULATE")
    /** "How do you say thank you in Twi?" / "what does akwaaba mean?" — from [TwiPhrasebook], no model. */
    data class TwiPhrase(val answer: String) : FastIntent("TWI_PHRASE")
    /** "How many days until Christmas?", "what day is 6 March 2027?", "how old is someone born in 1990?" ([LocalDates]). */
    data class DateAnswer(val answer: String) : FastIntent("DATE_ANSWER")
    /** "Convert 10 km to miles", "30 degrees Celsius in Fahrenheit" ([UnitConvert]). */
    data class Convert(val answer: String) : FastIntent("CONVERT")
    /** "Spell accommodation" ([Spelling]). */
    data class Spell(val answer: String) : FastIntent("SPELL")
    /** "What time is it in London?" ([WorldClock]). */
    data class WorldTime(val answer: String) : FastIntent("WORLD_TIME")
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
    /** E-14: play [query] (or an [artist]'s songs), in [app] when named; all null = "play some music". [collection] =
     *  the user's own music — "downloads" | "liked" | "library" — never searched as words. */
    data class PlayMusic(val query: String?, val artist: String? = null, val app: String? = null, val collection: String? = null) : FastIntent("PLAY_MUSIC")
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
    /** H-12: "forget that" ([scope] "last"), "forget everything" ("all"), "forget Ama" ("person", [who]); a remembered
     *  site pick: "forget that choice" ("choice"), "forget my choices" ("choices"). */
    data class Forget(val scope: String, val who: String? = null) : FastIntent("FORGET")
    /** J-15: "clear my history" — the History list, the action audit, traces and telemetry. */
    object ClearHistory : FastIntent("CLEAR_HISTORY")
    /** Flip a Quick Settings tile ([QuickTiles]) to [on]. */
    data class QuickToggle(val key: String, val on: Boolean) : FastIntent("TOGGLE_${key.uppercase()}")
    // ---- Overnight [S]: what the big assistants don't do for a blind user on an A20e (docs/agent/research/overnight_features.md)
    /** E-15 / H-09: "what did I miss?" — [scope] "last" (since the last catch-up), "recent" (the last hours) or "today". */
    data class Digest(val scope: String) : FastIntent("DIGEST")
    /** C-03 / H-08: "remember that Ama Mensah is my mother" ([said] = the word used, [explicit] = asked in so many words). */
    data class SetRelation(val relation: String, val said: String, val contact: String, val explicit: Boolean) : FastIntent("SET_RELATION")
    /** "who is my mother" ([relation]); "" = every relation. */
    data class RelationQuery(val relation: String) : FastIntent("RELATION_QUERY")
    data class ForgetRelation(val relation: String) : FastIntent("FORGET_RELATION")
    /** K-01 / K-10: "what can I say here?" ([here]) or "what can you do?". */
    data class Help(val here: Boolean) : FastIntent("HELP")
    /** H-02: a routine taught by voice, run by its name, listed, deleted. */
    data class CreateRoutine(val routine: String, val steps: List<String>) : FastIntent("CREATE_ROUTINE")
    data class RunRoutine(val routine: String) : FastIntent("RUN_ROUTINE")
    object ListRoutines : FastIntent("LIST_ROUTINES")
    data class DeleteRoutine(val routine: String) : FastIntent("DELETE_ROUTINE")
    /** H-10: "where did I leave off?" */
    object Resume : FastIntent("RESUME")
    /** F-09: describe the picture / the screen in front, or answer [question] about it (online vision). */
    data class DescribeScreen(val question: String?, val picture: Boolean) : FastIntent("DESCRIBE_SCREEN")
    /** E-11: "open Bluetooth settings" → that settings screen, not the Settings app's front page. */
    data class OpenSetting(val target: String) : FastIntent("OPEN_SETTING")
    /** "is WhatsApp installed?", "do I have Telegram?" */
    data class AppInstalled(val app: String) : FastIntent("APP_INSTALLED")
    /** "is battery saver on?" / "is data saver on?" — [kind] "battery" | "data". */
    data class SaverQuery(val kind: String) : FastIntent("SAVER_QUERY")
    /** "help me save data", "my battery is dying" — the state plus what to say next. [kind] "battery" | "data". */
    data class SaveHelp(val kind: String) : FastIntent("SAVE_HELP")
    // ---- Phone care (com.tekyerema.phonecare; A20e 2026-09-29: a Private DNS stall, an unbound accessibility service and a
    // spent AI quota were never explained to the user)
    /** "check my phone" — the spoken self-test ([com.tekyerema.phonecare.PhoneCheck]). */
    object CheckPhone : FastIntent("CHECK_PHONE")
    /** "tell me when I receive money" / "stop telling me about missed calls" — [kind] "money" | "missed_call" | "battery" |
     *  "balance" | "private". */
    data class AlertSwitch(val kind: String, val on: Boolean) : FastIntent("ALERT_SWITCH")
    /** "read it" (just after a private alert) / "read my alerts". */
    object ReadAlert : FastIntent("READ_ALERT")
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

    /** [routines] = false when resolving a routine's own steps / validating its name (no routine inside a routine). */
    fun classify(raw: String, routines: Boolean = true): FastIntent? {
        val g = raw.trim().lowercase().replace('’', '\'').replace(Regex("\\s+"), " ").trim().trimEnd('.', '!', '?').trim()
        if (g.isEmpty()) return null
        // X-03: phrasings from the per-locale rule files come first (config, not code).
        if (com.tekyerema.core.config.FeatureFlags.isEnabled("X-03", true)) RouterRules.match(g)?.let { return it }
        // H-02: a routine the user taught, by its name — then a new routine being taught. Both are whole utterances and
        // come before the compound check: "when I say good morning, tell me the time and the weather" is ONE request.
        if (routines && com.tekyerema.core.config.FeatureFlags.isEnabled(Routines.FEATURE, true)) {
            Routines.match(g, Routines.names())?.let { return FastIntent.RunRoutine(it) }
            Routines.parseDefinition(raw)?.let { return FastIntent.CreateRoutine(it.name, it.steps) }
            if (Routines.isList(g)) return FastIntent.ListRoutines
            Routines.parseDelete(g)?.let { return FastIntent.DeleteRoutine(it) }
        }
        // Phone care: before the older rules — "tell me about missed calls" is a switch, not the call history; "stop telling
        // me about money" is not a person called Money; a bare "read it" only right after a private alert (else: the screen).
        com.tekyerema.phonecare.PhoneCareIntents.classify(g, com.tekyerema.phonecare.PhoneAlerts.readItPending())?.let { return it }
        overnight(g, raw)?.let { return it }
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
        if (RE_FORGET_CHOICE.matches(g)) return FastIntent.Forget("choice")     // E: before "forget <person>"
        if (RE_FORGET_CHOICES.matches(g)) return FastIntent.Forget("choices")
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
            LocalMath.answer(g) != null -> FastIntent.Calculate(LocalMath.answer(g)!!)
            TwiPhrasebook.answer(g) != null -> FastIntent.TwiPhrase(TwiPhrasebook.answer(g)!!)
            // Questions with one right answer the phone can work out — before the time/day/date rules, which would
            // otherwise read out the time and date HERE and TODAY for "what time is it in London" / "what day is Christmas".
            WorldClock.answer(g) != null -> FastIntent.WorldTime(WorldClock.answer(g)!!)
            WorldClock.asksElsewhere(g) -> null        // a place not in the table: the model, not the time here
            LocalDates.answer(g) != null -> FastIntent.DateAnswer(LocalDates.answer(g)!!)
            LocalDates.asksOtherDay(g) -> null         // "what day is Eid": the model, not "today is Tuesday"
            UnitConvert.answer(g) != null -> FastIntent.Convert(UnitConvert.answer(g)!!)
            Spelling.answer(g) != null -> FastIntent.Spell(Spelling.answer(g)!!)
            // E-09 ringer mode before the volume rules ("silent mode" is the ringer, not muting the media volume).
            ringerMode(g) != null -> FastIntent.Ringer(ringerMode(g)!!)
            RE_TIME.containsMatchIn(g) -> FastIntent.Time
            RE_DAY.containsMatchIn(g) -> FastIntent.Day
            RE_DATE.containsMatchIn(g) -> FastIntent.Date
            RE_BATTERY.containsMatchIn(g) -> FastIntent.Battery
            RE_STORAGE.containsMatchIn(g) && !RE_STORAGE_ACTION.containsMatchIn(g) -> FastIntent.Storage
            RE_DEVICE_INFO.containsMatchIn(g) -> FastIntent.DeviceInfo

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

    /**
     * Overnight [S] intents — every pattern is a WHOLE utterance (anchored), so a compound request ("what did I miss and
     * call Ama back") never matches and still reaches the agent. Checked before the W7/W8 rules on purpose: "forget who
     * my mother is" is a relation, not "forget a person's messages"; "is battery saver on" is not the battery level.
     */
    private fun overnight(g: String, raw: String): FastIntent? {
        // C-03 / H-08 relationship contacts.
        com.tekyerema.android.Relations.parseForget(g)?.let { return FastIntent.ForgetRelation(it) }
        com.tekyerema.android.Relations.parseQuery(g)?.let { return FastIntent.RelationQuery(it) }
        com.tekyerema.android.Relations.parseSet(raw)?.let { r -> return FastIntent.SetRelation(r.relation, r.said, r.name, r.explicit) }
        // E-15 / H-09 the catch-up.
        digestScope(g)?.let { return FastIntent.Digest(it) }
        // K-01 / K-10 help, H-10 resume.
        ContextHelp.classify(g)?.let { return FastIntent.Help(it) }
        if (LeftOff.matches(g)) return FastIntent.Resume
        // F-09 describe the picture / screen.
        describe(g)?.let { return it }
        // Battery and data helpers — before the battery-level and toggle rules.
        RE_SAVER_QUERY.matchEntire(g)?.let { m -> return FastIntent.SaverQuery(if (m.groupValues[1].startsWith("data")) "data" else "battery") }
        if (RE_SAVE_DATA.matches(g)) return FastIntent.SaveHelp("data")
        if (RE_SAVE_BATTERY.matches(g)) return FastIntent.SaveHelp("battery")
        // E-11 a settings screen by name, and "is X installed?".
        RE_OPEN_SETTING.matchEntire(g)?.let { m ->
            val target = m.groupValues[1].trim()
            if (com.tekyerema.tools.OpenSettingTool.knows(target)) return FastIntent.OpenSetting(target)
        }
        for (re in RE_INSTALLED) re.matchEntire(g)?.let { m ->
            val app = m.groupValues[1].trim()
            if (app.isNotEmpty() && app.split(' ').size <= 3 && !RE_APP_HAS_VERB.containsMatchIn(app)) return FastIntent.AppInstalled(app)
        }
        return null
    }

    // ---- E-15 / H-09 digest. "read my messages" is the catch-up; "read my messages from Ama" (a person) is the agent's.
    private val RE_DIGEST_LAST = Regex("^(?:(?:so |okay |ok )?(?:tekyerema|tɛkyerɛma)[, ]+)?(?:what did i miss|what have i missed|" +
        "did i miss anything|have i missed anything|anything i missed|catch me up|fill me in|" +
        "(?:do i have|have i got|did i get|are there|is there) (?:any )?(?:new )?(?:messages|notifications|anything new)|" +
        "any (?:new )?(?:messages|notifications)|anything new|did anyone message me|has anyone messaged me|" +
        // Twi "nkra bɛn na aba?" (which messages have come?) — PENDING NATIVE REVIEW.
        "nkra b[ɛe]n na aba)(?: (?:while i was away|for me))?(?: please)?$")
    private val RE_DIGEST_RECENT = Regex("^(?:please )?(?:(?:read|check|go through|tell me|give me) (?:all )?(?:of )?my (?:new |latest |recent |unread )?" +
        "(?:messages|notifications)|summari[sz]e my (?:messages|notifications)|what are my notifications|(?:my )?notifications? summary|" +
        "who (?:has )?(?:messaged|written to|contacted) me)(?: please)?$")
    private val RE_DIGEST_TODAY = Regex("^(?:what did i miss|what(?:'s| has) come in|who messaged me|what messages did i get) today$")

    private fun digestScope(g: String): String? = when {
        RE_DIGEST_TODAY.matches(g) -> "today"
        RE_DIGEST_LAST.matches(g) -> "last"
        RE_DIGEST_RECENT.matches(g) -> "recent"
        else -> null
    }

    // ---- F-09 describe. A named picture/photo/image or "the screen"; a question that ends "in this picture".
    private const val PIC = "(?:picture|photo|image|pic|photograph)"
    private val RE_DESCRIBE_PIC = Regex("^(?:please |can you |could you )?(?:describe (?:this|the|that) $PIC(?: for me| to me)?|" +
        "what(?:'s| is) (?:in|on) (?:this|the|that) $PIC|what does (?:this|the|that) $PIC show|tell me what(?:'s| is) in (?:this|the|that) $PIC|" +
        "what is this $PIC(?: of)?|who(?:'s| is) in (?:this|the|that) $PIC)(?: please)?$")
    private val RE_DESCRIBE_SCREEN = Regex("^(?:please |can you |could you )?(?:describe (?:the|my|this) screen(?: for me| to me)?|" +
        "describe what(?:'s| is) on (?:the|my|this) screen|what does (?:the|my|this) screen look like)(?: please)?$")
    private val RE_PIC_QUESTION = Regex("^((?:what|who|how many|how much|is there|are there|which|where|does|do|can you see|what colou?r)\\b.{2,80}?) " +
        "(?:in|on) (?:this|the|that) $PIC$")

    private fun describe(g: String): FastIntent? = when {
        RE_DESCRIBE_PIC.matches(g) -> FastIntent.DescribeScreen(null, picture = true)
        RE_DESCRIBE_SCREEN.matches(g) -> FastIntent.DescribeScreen(null, picture = false)
        else -> RE_PIC_QUESTION.matchEntire(g)?.let { FastIntent.DescribeScreen(g, picture = true) }
    }

    // ---- battery / data helpers
    private val RE_SAVER_QUERY = Regex("^(?:is|check if|check whether) (?:my |the )?(battery saver|battery saving|power saving|power saver|data saver|data saving)" +
        "(?: mode)? (?:on|enabled|turned on|active)$")
    private val RE_SAVE_DATA = Regex("^(?:please )?(?:help me |how (?:can|do) i |i (?:want|need) to )?(?:save|reduce|use less) (?:my |mobile )?data(?: usage)?$|" +
        "^(?:i'm|i am) running out of data$|^my data is (?:finishing|running out|almost finished|low)$|^my (?:data )?bundle is (?:finishing|running out|low)$")
    private val RE_SAVE_BATTERY = Regex("^(?:please )?(?:help me |how (?:can|do) i |i (?:want|need) to )?(?:save|conserve) (?:my )?(?:battery|power)$|" +
        "^make (?:my |the )?battery last(?: longer)?$|^(?:my )?(?:battery|phone) is (?:low|dying|running out|finishing|almost dead)$|^(?:i'm|i am) running out of battery$")

    // ---- E-11 "open Bluetooth settings"; "is WhatsApp installed?"
    private val RE_OPEN_SETTING = Regex("^(?:(?:please|can you|could you)\\s+)*(?:open|go to|take me to|show me|bring up|launch)\\s+(?:the\\s+|my\\s+)?" +
        "(.{2,30}?)\\s+settings?(?:\\s+(?:page|screen|menu))?(?:\\s+please)?$")
    private val RE_INSTALLED = listOf(
        Regex("^(?:is|are) (?:the )?(.{2,30}?)(?: app)? installed(?: on (?:my|this) phone)?$"),
        Regex("^do i have (?:the )?(.{2,30}?)(?: app)? (?:installed|on (?:my|this) phone)$"),
        Regex("^do i have (?:the )?(.{2,30}?) app$"),
        Regex("^have i got (?:the )?(.{2,30}?)(?: app)? (?:installed|on (?:my|this) phone)$"),
        Regex("^is there (?:an? )?(.{2,30}?) app (?:on|in) (?:my|this) phone$")
    )

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
        if (RE_NO_APP_NAME.matches(app)) return null             // "open the app" / "open it": which one? → the planner asks
        return FastIntent.OpenApp(app)
    }

    // Compound/multi-step markers → hand the whole thing to the agent.
    // Twi "na" joins two commands, but after a question word it is the focus marker of ONE question ("bere bɛn na ɛyɛ?"
    // = what time is it) — that isn't compound (the device suite found the time question missing the fast path).
    private val RE_MULTISTEP = Regex("\\b(and then|and|then|after that|(?<!(?:bɛn|bɛ|dɛn|hena|ɛhe|ehe|ben|den) )na)\\b|;")

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
    // Workstream E (agent/research/ResolvedChoices): a remembered site pick — the last one, or all of them.
    private val RE_FORGET_CHOICE = Regex("^(?:forget|clear|delete) (?:that|this|the|my|your) (?:choice|pick|site|website|link)$")
    private val RE_FORGET_CHOICES = Regex("^(?:forget|clear|delete|erase) (?:my|your|all|all my|all your|all the) (?:saved )?(?:choices|picks|sites|websites)$")
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
    // "which Android version is this phone?" — the phone knows; on One UI the answer is three screens deep in Settings
    // (About phone › Software information), where the agent got stuck on the A20e.
    private val RE_DEVICE_INFO = Regex("\\b(?:android|software|os|one ui) version\\b|\\bwhich android\\b|\\bwhat android\\b|" +
        "\\bwhat (?:phone|model) (?:is this|am i using|do i have)\\b|\\b(?:my|this) phone(?:'s)? model\\b|\\bwhat model is (?:this|my) phone\\b")
    private val RE_STORAGE_ACTION = Regex("\\b(free\\s+up|clear|clean|delete|remove|open|settings|manage|make)\\b")
    private val RE_BATTERY = Regex("\\bbattery\\b|\\bhow\\s+much\\s+(?:charge|power)\\b")

    private val RE_VOL_QUERY = Regex("\\bwhat(?:'?s| is)?\\s+(?:my\\s+|the\\s+)?volume(?:\\s+level)?\\b|\\bcheck\\s+(?:the\\s+|my\\s+)?volume\\b|\\bvolume\\s+level\\b|\\bhow\\s+loud\\b")
    private val RE_UNMUTE = Regex("\\bunmute\\b|\\bun-mute\\b|\\bturn\\s+(?:the\\s+)?(?:sound|volume|audio)\\s+back\\s+on\\b|\\bunsilence\\b|\\bsound\\s+back\\s+on\\b")
    private val RE_MUTE = Regex("\\bmute\\b|\\bsilence\\b|\\bgo\\s+silent\\b|\\bsilent\\s+mode\\b")
    private val RE_VOL_UP = Regex("\\bvolume\\s+up\\b|\\blouder\\b|\\bturn\\s+it\\s+up\\b|\\bturn\\s+up\\s+(?:the\\s+|my\\s+)?(?:volume|sound)\\b|\\bturn\\s+(?:the\\s+)?(?:volume|sound)\\s+up\\b|\\b(?:increase|raise|boost|crank|pump|bump)\\s+(?:the\\s+|my\\s+)?(?:volume|sound)\\b|\\bmake\\s+it\\s+louder\\b|\\bma\\s+volume\\s+no\\s+so\\b")
    private val RE_VOL_DOWN = Regex("\\bvolume\\s+down\\b|\\b(?:quieter|softer)\\b|\\bturn\\s+it\\s+down\\b|\\bturn\\s+down\\s+(?:the\\s+|my\\s+)?(?:volume|sound)\\b|\\bturn\\s+(?:the\\s+)?(?:volume|sound)\\s+down\\b|\\b(?:decrease|lower|reduce|drop)\\s+(?:the\\s+|my\\s+)?(?:volume|sound)\\b|\\bmake\\s+it\\s+(?:quieter|softer)\\b|\\bma\\s+volume\\s+no\\s+ase\\b")

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
    /** The user's OWN music: "play my downloads on YouTube Music" was searched as the words "my downloads" and played a
     *  stranger's song (A20e 2026-09-29). Group 1 = which collection, group 2 = the app. */
    private val RE_PLAY_COLLECTION = Regex("^(?:please )?(?:play|shuffle|put on|start playing|start) (?:me )?(?:all )?(?:of )?" +
        "(?:(?:some |the |all the )?(?:songs|music|tracks) (?:from|in|on) )?(?:my |the )?" +
        "(downloads?|downloaded (?:songs|music|tracks)|(?:songs|music|tracks) (?:that )?i(?:'ve| have)? (?:downloaded|saved|liked)|" +
        "offline (?:songs|music|tracks)|(?:music )?library|liked (?:songs|music|tracks)|likes|favou?rites|favou?rite (?:songs|music|tracks))" +
        "(?: (?:on|in|from|using) ($MUSIC_APPS))?(?: please)?$")

    /** "downloads" | "liked" | "library" for the words of [RE_PLAY_COLLECTION]'s first group. */
    private fun collectionOf(words: String): String = when {
        Regex("download|offline").containsMatchIn(words) -> "downloads"
        Regex("lik|favou?rite").containsMatchIn(words) -> "liked"
        else -> "library"
    }

    private fun playMusic(g: String): FastIntent.PlayMusic? {
        RE_PLAY_COLLECTION.matchEntire(g)?.let { m ->
            return FastIntent.PlayMusic(null, null, m.groupValues[2].ifBlank { null }, collectionOf(m.groupValues[1]))
        }
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
    private val RE_NO_APP_NAME = Regex("^(?:the |an? |this |that |my |some )?(?:app|apps|application|it|this|that|one|something)$")
    private val RE_OPEN = Regex("^(?:(?:please|kindly|can you|could you|would you|will you|i want to|i'd like to|i would like to|help me)\\s+)*" +
        "(?:open(?:ing)?|launch(?:ing)?|start(?:ing)?|go\\s+to|goto|bring\\s+up|pull\\s+up|put\\s+on|bue\\S*|bua\\S*)\\s+" +
        "(?!hours?\\b|times?\\b)(?:up\\s+)?(?:the\\s+|my\\s+)?(.+?)(?:\\s+app)?(?:\\s+(?:please|for me))?$")
    private val RE_APP_HAS_VERB = Regex("\\b(send|message|reply|forward|dial|text|call|find|search|tell|saying|then)\\b")
}
