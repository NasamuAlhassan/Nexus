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
    /** "read my last WhatsApp message", "any new WhatsApp messages", "read my WhatsApp messages from Ama" — from the
     *  messages the phone already captured, instantly. The agent opened WhatsApp for these and took 25–42 s (A20e device
     *  suite 2026-09-30). [app] = "WhatsApp" | "Telegram" | "Messenger"; [latestOnly] = one message ("message", singular). */
    data class ChatMessages(val app: String, val latestOnly: Boolean, val from: String? = null) : FastIntent("CHAT_MESSAGES")
    /** "continue" while messages are being read a few at a time (notifications/MessageReading) — never classified, only
     *  the router's name for that turn. */
    object MoreMessages : FastIntent("MORE_MESSAGES")
    object WhereAmI : FastIntent("WHERE_AM_I")
    object Connectivity : FastIntent("CONNECTIVITY")
    object NowPlaying : FastIntent("NOW_PLAYING")
    /** [mode] = "vibrate" | "silent" | "normal". */
    data class Ringer(val mode: String) : FastIntent("RINGER")
    object ShowAlarms : FastIntent("SHOW_ALARMS")
    /** "stop the timer" — the Clock's own dismiss request, no model (A20e 2026-09-29: it went to a spent cloud AI). */
    object StopTimer : FastIntent("STOP_TIMER")
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
    /** P5-02: a picture the user already has — the phone's photo picker, then read ("text") or describe. */
    data class ReadPhoto(val mode: String) : FastIntent("READ_PHOTO")
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
    /** [messagesOnly]: the user said "messages" (chats and texts), not "notifications" / "what did I miss" (everything). */
    data class Digest(val scope: String, val messagesOnly: Boolean = false) : FastIntent("DIGEST")
    /** Demo step 2: "read my message" — the newest message (from the person just talked to, when there is one), in full. */
    data class ReadMessage(val from: String? = null) : FastIntent("READ_MESSAGE")
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
    /** "show me locations to X", "navigate to X", "directions to X" — opens Maps via open_intent(maps_search).
     *  [appFirst]: "take me to X" can also name an app ("take me to WhatsApp") — the router opens that app when X is
     *  exactly an installed one, and searches the map otherwise. */
    data class MapSearch(val place: String, val appFirst: Boolean = false) : FastIntent("MAP_SEARCH")
    /** "read my last four text messages" — [LastSms] with a count (2–10). The audit (2026-10-01) heard one message. */
    data class LastSmsN(val count: Int) : FastIntent("LAST_SMS")
    /** "close notifications" / "close the notification panel": the shade is CLOSED. Nothing is ever cleared — the
     *  agent took "close notifications" for "clear" and wiped them all (audit 2026-10-01, S23). */
    object CloseShade : FastIntent("CLOSE_SHADE")
    /** "close this app" ([query] null) / "close WhatsApp" ([query] = the name as said; the router checks it is an
     *  installed app). Leaves the app with Home — never the Recents "Close all" button (audit S20). */
    data class CloseApp(val query: String?) : FastIntent("CLOSE_APP")
    /** "continue" after a screen was read to its last part: read further down the same screen. Never classified —
     *  only the router's name for that turn (audit AX03: it was taken for media Play). */
    object ReadMore : FastIntent("READ_MORE")
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
            // Close the notification panel — before every rule that mentions notifications (RE_NOTIFS would OPEN it for
            // "close the notification panel"), and never a "clear".
            RE_CLOSE_SHADE.matches(g) -> FastIntent.CloseShade
            // Ghanaian-English switches without a verb ("on the torch", "off the light") and Twi "sɔ kanea no".
            ghSwitch(g) != null -> ghSwitch(g)
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
            // P5-02: a picture the user already has (gallery, files, a screenshot) — before the camera forms, which "read
            // this photo" would otherwise take to the live camera.
            RE_READ_PHOTO.containsMatchIn(g) -> FastIntent.ReadPhoto(if (RE_DESCRIBE_WORD.containsMatchIn(g)) "describe" else "text")
            // W6: camera, screen changes, magnification, emergency set-up.
            RE_CAMERA_MONEY.containsMatchIn(g) -> FastIntent.Camera("currency")
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
            RE_CHAT_MESSAGES.matchEntire(g) != null -> RE_CHAT_MESSAGES.matchEntire(g)!!.let { m ->
                val app = when (m.groupValues[1]) { "telegram" -> "Telegram"; "messenger" -> "Messenger"; else -> "WhatsApp" }
                FastIntent.ChatMessages(app, latestOnly = !m.groupValues[2].endsWith("s"), from = m.groupValues[3].trim().ifEmpty { null })
            }
            RE_LAST_SMS.containsMatchIn(g) -> smsCount(g)?.let { FastIntent.LastSmsN(it) } ?: FastIntent.LastSms
            RE_WHERE_AM_I.containsMatchIn(g) -> FastIntent.WhereAmI
            RE_ONLINE.containsMatchIn(g) -> FastIntent.Connectivity
            RE_NOW_PLAYING.containsMatchIn(g) -> FastIntent.NowPlaying
            RE_SHOW_ALARMS.containsMatchIn(g) -> FastIntent.ShowAlarms
            RE_STOP_TIMER.matches(g) -> FastIntent.StopTimer
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
            closeApp(g, raw) != null -> closeApp(g, raw)
            mapSearch(g, raw) != null -> mapSearch(g, raw)

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
        // Demo step 2 (owner, 14 Oct 2026): ONE message, read in full — before the catch-up, which takes "messages".
        RE_READ_ONE.find(g)?.let { m -> return FastIntent.ReadMessage(m.groupValues[1].trim().ifEmpty { null }) }
        // "read the message my wife sent" / "read my wife's message" (19 Oct 2026: the Twi "kenkan nkra a me yere somaa me no"
        // went to the planner, which read an unrelated notification).
        RE_READ_SENT.find(g)?.let { m -> return FastIntent.ReadMessage(m.groupValues[1].trim().removePrefix("my ").trim().ifEmpty { null }) }
        RE_READ_POSS.find(g)?.let { m -> return FastIntent.ReadMessage(m.groupValues[1].trim().removePrefix("my ").trim().ifEmpty { null }) }
        // E-15 / H-09 the catch-up.
        digestScope(g)?.let { return FastIntent.Digest(it, RE_ASKED_MESSAGES.containsMatchIn(g) && "notification" !in g) }
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
    private val RE_READ_ONE = Regex("^(?:(?:please|now|okay|ok|so|can you|could you)\\s+)*(?:read|open|play|check)\\s+(?:me\\s+)?" +
        "(?:my|the|his|her|their|that|this)\\s+(?:new\\s+|latest\\s+|last\\s+|recent\\s+)?message(?:\\s+from\\s+(.+?))?(?:\\s+please)?$|" +
        "^what\\s+(?:does|did)\\s+(?:the|his|her|my)\\s+(?:new\\s+|last\\s+)?message\\s+say()$")
    private val RE_READ_SENT = Regex("^(?:(?:please|now|okay|ok|so|can you|could you)\\s+)*(?:read|open|play|check)\\s+(?:me\\s+)?" +
        "(?:the|that|this)\\s+(?:new\\s+|latest\\s+|last\\s+|recent\\s+)?(?:message|text)\\s+(?:that\\s+|which\\s+)?(?:from\\s+)?" +
        "(.+?)\\s+(?:sent|texted|wrote)(?:\\s+(?:me|to me))?(?:\\s+please)?$")
    private val RE_READ_POSS = Regex("^(?:(?:please|now|okay|ok|so|can you|could you)\\s+)*(?:read|open|play|check)\\s+(?:me\\s+)?" +
        "((?:my\\s+)?[a-z][a-z ]{1,30}?)(?:'s|s'|’s)\\s+(?:new\\s+|latest\\s+|last\\s+|recent\\s+)?(?:message|text)(?:\\s+please)?$")
    private val RE_DIGEST_TODAY = Regex("^(?:what did i miss|what(?:'s| has) come in|who messaged me|what messages did i get) today$")

    // "read my messages" means chats and texts — A20e 2026-09-30 it read "33 notifications", YouTube first, for ~50 s.
    private val RE_ASKED_MESSAGES = Regex("\\b(?:messages?|messaged|texts?|sms|nkra)\\b")

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

    private val RE_TIME = Regex("\\b(what(?:'?s| is)?\\s+(?:the\\s+)?time|what\\s+time\\s+is\\s+it|tell\\s+me\\s+(?:the\\s+)?time|current\\s+time|check\\s+the\\s+time|m?bere\\s+bɛn|m?bɛre\\s+bɛn|mmere\\s+bɛn|dɔn\\s+s[ɛe]n|don\\s+sen|" +
        // Every way "time" is written in Twi: "ɛberɛ bɛn" was typed on 2 October 2026, missed, and answered from a stale web page.
        "ɛ?b[ɛe]r[ɛe]\\s+b[ɛe]n|m?m[ɛe]r[ɛe]\\s+b[ɛe]n)\\b")   // Twi: "(m)mere bɛn", "ɛyɛ dɔn sɛn" [owner: verify]
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
    private val RE_VOL_UP = Regex("\\b(?:volume|sound)\\b[^.]*\\btoo\\s+(?:low|soft|quiet)\\b|\\bvolume\\s+up\\b|\\blouder\\b|\\bturn\\s+it\\s+up\\b|\\bturn\\s+up\\s+(?:the\\s+|my\\s+)?(?:volume|sound)\\b|\\bturn\\s+(?:the\\s+)?(?:volume|sound)\\s+up\\b|\\b(?:increase|raise|boost|crank|pump|bump)\\s+(?:the\\s+|my\\s+)?(?:volume|sound)\\b|\\bmake\\s+it\\s+louder\\b|\\bma\\s+volume\\s+no\\s+so\\b")
    private val RE_VOL_DOWN = Regex("\\b(?:volume|sound)\\b[^.]*\\btoo\\s+(?:loud|high)\\b|\\btoo\\s+loud\\b|\\bvolume\\s+down\\b|\\b(?:quieter|softer)\\b|\\bturn\\s+it\\s+down\\b|\\bturn\\s+down\\s+(?:the\\s+|my\\s+)?(?:volume|sound)\\b|\\bturn\\s+(?:the\\s+)?(?:volume|sound)\\s+down\\b|\\b(?:decrease|lower|reduce|drop)\\s+(?:the\\s+|my\\s+)?(?:volume|sound)\\b|\\bmake\\s+it\\s+(?:quieter|softer)\\b|\\bma\\s+volume\\s+no\\s+ase\\b")

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
    private val RE_BRIGHT_UP = Regex("\\b(?:screen|display|brightness)\\b[^.]*\\btoo\\s+(?:dim|dark|low)\\b|\\b(?:increase|raise|boost|turn\\s+up|up)\\b[^.]*\\bbright(?:ness|er)?\\b|\\bbright(?:ness|er)?\\b[^.]*\\b(?:up|higher|brighter)\\b|\\bbrighter\\b|\\bbrighten\\b|\\bmake\\s+(?:the\\s+)?screen\\s+brighter\\b")
    private val RE_BRIGHT_DOWN = Regex("\\b(?:screen|display|brightness)\\b[^.]*\\btoo\\s+(?:bright|high)\\b|\\b(?:decrease|lower|reduce|turn\\s+down|dim)\\b[^.]*\\bbright(?:ness|er)?\\b|\\bbrightness\\b[^.]*\\b(?:down|lower)\\b|\\bmake\\s+(?:the\\s+)?screen\\s+(?:darker|dimmer)\\b|\\b(?:darker|dimmer)\\b|\\bdim\\s+the\\s+screen\\b")

    private val RE_TORCH_OFF = Regex("\\b(?:flashlight|torch|flash\\s*light)\\b[^.]*\\boff\\b|\\boff\\b[^.]*\\b(?:flashlight|torch|flash\\s*light)\\b")
    // ---- W6 camera / screen / magnification / emergency ----
    // Camera phrases name a physical object or the camera, so "read my screen" never opens the camera.
    private val RE_CAMERA_TEXT = Regex("\\bwhat does (?:this|the|that) (?:label|sign|paper|letter|document|note|package|box|bottle|card|receipt|medicine|book) say\\b|" +
        "\\bread (?:this|the|that|my) (?:label|sign|paper|page|letter|document|note|package|box|bottle|card|receipt|medicine|book|menu|form)\\b|" +
        "\\bread (?:it |this |the text )?(?:with|using) (?:the )?camera\\b|^scan (?:this|it)$|" +
        // What a blind user actually says when holding something (A20e 2026-10-01: "read this" read the SCREEN
        // and listed home-screen icons — useless with a letter in hand).
        "\\bread what(?:'s| is)? (?:in )?(?:my hand|in front of me|here)\\b|\\bread what i(?:'m| am) holding\\b|" +
        "\\bread (?:this|it) (?:out )?(?:for me |aloud )?with (?:the )?camera\\b|" +
        // Audit CAM03: "read the text in front of the camera" went to the planner, which opened Samsung Camera instead.
        "\\bread (?:the |this |that |any )?(?:text|writing|words) (?:that(?:'s| is) )?in front of (?:me|the camera|my camera)\\b|" +
        "\\bread (?:what(?:'s| is) )?in front of (?:the|my) camera\\b|\\bwhat does (?:this|it|that) say (?:with|using) (?:the |my )?camera\\b|" +
        "\\b(?:use|open) the camera (?:to|and) read\\b")
    /** The cedi reader: a note or coin held to the camera. "How much is this" alone is money too — nobody asks
     *  that of a chair (camera/CurrencyAnnouncer; first-of-its-kind for the cedi). */
    private val RE_CAMERA_MONEY = Regex("(?i)\\bwhat (?:note|bill|cedi|denomination) is (?:this|that|it)\\b|" +
            "\\bhow much (?:money |cash )?is (?:this|that|it)\\b|\\bwhat(?:'s| is) this (?:note|money|cash|bill)\\b|" +
            "\\b(?:check|read|count|identify|tell me) (?:this |the |my )?(?:money|cash|note|notes|cedis?|bill)\\b|" +
            "\\bhow many cedis? is (?:this|that|it)\\b|\\bwhich note is (?:this|that|it)\\b|" +
            "\\bwhat kind of (?:note|money) is (?:this|that|it)\\b")

    // P5-02: a picture that already exists — named with where it is (gallery, photos, files, downloads, "I took"), or "the
    // last / latest photo / screenshot", or an explicit "pick a picture and read it". A bare "read this photo" stays with
    // the camera (the thing in front of the user).
    private const val PICK_PIC = "(?:picture|photo|image|screenshot|pic|photograph)"
    private val RE_READ_PHOTO = Regex("\\b(?:read|describe|open)\\b[^.]{0,24}?\\b$PICK_PIC\\b[^.]{0,30}?\\b(?:gallery|photos app|my photos|my pictures|my files|" +
        "album|camera roll|downloads|i took|i saved|i downloaded|i have|on my phone)\\b|" +
        "^(?:pick|choose|select) (?:a |the )?$PICK_PIC(?: (?:from|in) (?:my )?(?:gallery|photos|files))?(?: and (?:read|describe) it)?$|" +
        "\\b(?:read|describe) (?:the |my )?(?:last|latest|most recent|newest) $PICK_PIC\\b|" +
        "\\bwhat(?:'s| is| does) (?:in|on|written in|written on|it say in) (?:the |my )?(?:last|latest|most recent|newest) $PICK_PIC\\b")
    private val RE_DESCRIBE_WORD = Regex("\\b(?:describe|what(?:'s| is) (?:in|on)|what does it show)\\b")
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

    /** Switching the voice language (gh_mixed: English → the phone's recognizer, Twi → the UG HCI Lab). Twi forms marked
     *  for a native speaker: "kasa Twi" (speak Twi), "kasa Borɔfo" (speak English). [owner: verify] */
    private val KUS_WORD = "(?:kusaal|kusal|kusasi|kusa al|kusaa?l language)"
    private val RE_PREF_TWI = Regex("^(?:please\\s+)?(?:(?:speak|talk|reply|answer|listen)(?: to me)?(?: in)? twi|" +
        "(?:switch|change) (?:to|into) twi|use twi|twi (?:please|mode)|(?:kasa|ka) twi|twi kasa)(?: please| now| from now on)?$")
    private val RE_PREF_ENGLISH = Regex("^(?:please\\s+)?(?:(?:speak|talk|reply|answer|listen)(?: to me)?(?: in)? english|" +
        "(?:switch|change) (?:to|into) english|use english|english (?:please|mode)|(?:kasa|ka) (?:borɔfo|borofo|brofo|bro fo)|" +
        "(?:borɔfo|borofo|brofo) kasa)(?: please| now| from now on)?$")

    /** Kusaal (19 October 2026) as the phone's English recogniser writes it ("kusal", "kusasi", "kusa al"). */
    private val RE_PREF_KUSAAL = Regex("^(?:please\\s+)?(?:(?:speak|talk|reply|answer|listen)(?: to me)?(?: in)? $KUS_WORD|" +
        "(?:switch|change) (?:to|into) $KUS_WORD|use $KUS_WORD|$KUS_WORD (?:please|mode))(?: please| now| from now on)?$")

    private fun preference(g: String): FastIntent.Preference? = when {
        RE_PREF_SLOWER.matches(g) -> FastIntent.Preference("rate", "slower")
        RE_PREF_FASTER.matches(g) -> FastIntent.Preference("rate", "faster")
        RE_PREF_NORMAL_SPEED.matches(g) -> FastIntent.Preference("rate", "normal")
        RE_PREF_BRIEF.matches(g) -> FastIntent.Preference("verbosity", "brief")
        RE_PREF_DETAILED.matches(g) -> FastIntent.Preference("verbosity", "detailed")
        RE_PREF_PATIENT.matches(g) -> FastIntent.Preference("patience", "patient")
        RE_PREF_TWI.matches(g) -> FastIntent.Preference("language", "twi")
        RE_PREF_ENGLISH.matches(g) -> FastIntent.Preference("language", "english")
        // Kusaal removed from the build (owner, 20 Oct 2026): "speak Kusaal" is no longer a language switch.
        false && RE_PREF_KUSAAL.matches(g) -> FastIntent.Preference("language", "kus")
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
    // Group 1 = app, 2 = "message(s)"/"chat(s)", 3 = a person. A send ("send a WhatsApp message to Ama") never matches.
    private val RE_CHAT_MESSAGES = Regex("^(?:please )?(?:(?:can|could) you )?(?:read|check|tell me|any|do i have(?: any)?|" +
        "have i got(?: any)?|are there(?: any)?|did i get(?: any)?)(?: me)?(?: (?:my|the))?(?: (?:last|latest|newest|new|recent|unread))?" +
        " (whatsapp|whats app|telegram|messenger) (messages?|chats?)(?: from (.+?))?(?: please)?$")
    private val RE_LAST_SMS = Regex("\\b(?:read|check|what(?:'s| is| was)|any)\\b.*\\b(?:text messages?|texts|sms|my (?:last |latest |new )?text)\\b|^(?:my )?(?:last|latest|new) (?:text|sms|text message)s?$")
    private val RE_WHERE_AM_I = Regex("\\b(?:where am i|where i am|what(?:'s| is) my (?:current )?location|my current location|what street am i on)\\b")
    // A place on the map. Whole utterances, and only wordings that ASK FOR THE MAP: a bare "where is X" is a question
    // (a blind user wants the answer spoken, not Maps opened) and "go to X" is an app or a screen ("go to settings") —
    // both were swallowed by the first version of this rule (2026-10-01). Group 7 ("take me to X") may name an app.
    private const val MAP_FIND = "(?:show me|find|search for|look up|locate|look for)"
    private val RE_MAP_SEARCH = Regex(
        "^(?:(?:please|can you|could you)\\s+)?(?:" +
            "$MAP_FIND\\s+(?:the\\s+)?(?:location|locations|place|places|way|address|directions?|route)\\s+(?:to|of|for|near)\\s+(.+)" +
            "|$MAP_FIND\\s+(.+?)\\s+on\\s+(?:the\\s+map|(?:google\\s+)?maps)" +
            "|(?:navigate|directions?|route|journey|drive|walk)\\s+to\\s+(.+)" +
            "|(?:how do i|how can i|how to)\\s+(?:get|go|drive|walk|travel)\\s+to\\s+(.+)" +
            "|show\\s+(?:me\\s+)?where\\s+(.+?)\\s+(?:is|are)(?:\\s+located)?" +
            "|start\\s+(?:the\\s+|a\\s+|my\\s+)?(?:journey|navigation|navigating|trip)\\s+to\\s+(.+)" +
            "|(?:take|get|bring)\\s+me\\s+to\\s+(.+)" +
            ")(?:\\s+please)?$"
    )
    /** The user's own things are not places ("show me where my messages are") — except where they live or work. */
    private val RE_NOT_A_PLACE_LEAD = Regex("^(?:(?:my|your|his|her|our|their)\\b(?!\\s+(?:home|house|office|work|workplace|school|church|" +
        "mosque|hostel|hall|place|hotel|village|hometown|shop|farm)\\b)|(?:this|that|these|those|it|him|them|me)\\b)")
    /** Parts of the phone, never a place on the map ("how do I get to the settings", "navigate to my messages"). */
    private val RE_PHONE_THING = Regex("\\b(?:phone|screen|home ?screen|settings?|menu|page|tab|button|keyboard|apps?|application|messages?|texts?|" +
        "sms|notifications?|contacts?|chats?|inbox|emails?|photos?|pictures?|gallery|camera|files?|folder|downloads?|wi-?fi|bluetooth|" +
        "battery|volume|alarms?|timer|password|pin|account|balance|airtime|bundle|internet|songs?|music|videos?|whatsapp|telegram|youtube)\\b")
    /** A piece of a screen: not a place, and not an app either. */
    private val RE_SCREEN_PART = Regex("\\b(?:screen|home ?screen|menu|page|tab|button|keyboard|top|bottom)\\b")

    /** True when [place] names a part of the phone rather than somewhere on the map (the router re-checks an
     *  "take me to X" that turned out not to be an installed app). Pure. */
    internal fun isPhoneThing(place: String): Boolean = RE_PHONE_THING.containsMatchIn(place.lowercase())
    private val RE_ONLINE = Regex("\\b(?:am i (?:online|connected)|(?:is|do i have) (?:the )?internet|internet connection|am i on (?:wi-?fi|data|mobile data)|what network am i on)\\b")
    private val RE_NOW_PLAYING = Regex("\\b(?:what(?:'s| is) (?:playing|this song)|what song is (?:this|playing)|who (?:sings|is singing) this)\\b")
    private val RE_STOP_TIMER = Regex("^(?:please |can you )?(?:stop|cancel|dismiss|end|turn off|switch off) (?:the |my |that )?timer(?: please| now)?$")
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
    /** "download(s/ed)" as typed or heard: owner typed "Play my downladed songs on yt music" (A20e 2026-09-29) and it
     *  searched those words — a stranger's song played. Also downloded / donwloaded / dowloaded / "down loaded". */
    private const val DL = "d[ao]?w?n? ?w?l[oa]{0,2}d(?:s|ed|ded)?"

    private val RE_PLAY_COLLECTION = Regex("^(?:please )?(?:play|shuffle|put on|start playing|start) (?:me )?(?:all )?(?:of )?" +
        "(?:(?:some |the |all the )?(?:songs|music|tracks) (?:from|in|on) )?(?:my |the )?" +
        "($DL(?: (?:songs|music|tracks))?|(?:songs|music|tracks) (?:that )?i(?:'ve| have)? (?:$DL|saved|liked)|" +
        "offline (?:songs|music|tracks)|(?:music )?library|liked (?:songs|music|tracks)|likes|favou?rites|favou?rite (?:songs|music|tracks))" +
        "(?: (?:on|in|from|using) ($MUSIC_APPS))?(?: please)?$")

    /** "downloads" | "liked" | "library" for the words of [RE_PLAY_COLLECTION]'s first group. */
    private fun collectionOf(words: String): String = when {
        Regex("^$DL|$DL$|offline").containsMatchIn(words) -> "downloads"
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
    // The WHOLE request must be the Home command. Unanchored, "fie" (Twi: home) and "go home" matched inside any sentence —
    // "Bue WhatsApp ne ka kyerɛ Prince sɛ ɔmbra fie" (tell Prince to come home) or "tell him to go home" pressed Home
    // instead of sending the message (found in review, 2026-09-29).
    private val RE_HOME = Regex("^(?:please |can you |ok(?:ay)? )?(?:go (?:back )?(?:to )?(?:the )?home(?: ?screen| page)?|" +
        "(?:take me|back) (?:to )?(?:the )?home(?: ?screen)?|(?:press|tap|hit) (?:the )?home(?: button| key)?|" +
        "home(?: ?screen| button| key)?|k[ɔo] fie|fie)(?: please| now)?$")
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

    private fun mapSearch(g: String, raw: String): FastIntent.MapSearch? {
        val m = RE_MAP_SEARCH.matchEntire(g) ?: return null
        val gi = (1..7).firstOrNull { m.groupValues[it].isNotBlank() } ?: return null
        val placeLower = m.groupValues[gi].trim().removeSuffix(" please").removeSuffix(" for me").trim()
        if (placeLower.length < 2) return null
        val appFirst = gi == 7
        if (RE_NOT_A_PLACE_LEAD.containsMatchIn(placeLower)) return null
        // "take me to WhatsApp" may be an app (the router decides); every other wording asked for the map outright.
        if (if (appFirst) RE_SCREEN_PART.containsMatchIn(placeLower) else RE_PHONE_THING.containsMatchIn(placeLower)) return null
        val idx = raw.lowercase().lastIndexOf(placeLower)
        val place = if (idx >= 0 && idx + placeLower.length <= raw.length) raw.substring(idx, idx + placeLower.length).trim() else placeLower
        return FastIntent.MapSearch(place, appFirst)
    }

    // ---- Audit 2026-10-01: close the shade (S23), leave the app (S20) — whole utterances only.
    private const val POLITE = "(?:(?:please|kindly|abeg|i beg|can you|could you|would you)\\s+)*"
    private const val POLITE_TAIL = "(?:\\s+(?:please|for me|now))*"
    /** Close / hide — never "clear", "dismiss" or "remove" (those delete notifications: the agent's, behind a confirmation),
     *  and never "all notifications". */
    private val RE_CLOSE_SHADE = Regex("^$POLITE(?:close|hide|collapse|shut|put away)\\s+(?:the\\s+|my\\s+|this\\s+)?" +
        "(?:notifications?(?:\\s+(?:shade|panel|drawer|bar|screen))?|(?:quick\\s+settings\\s+|status\\s+)?(?:panel|shade|bar)|quick\\s+settings)" +
        "$POLITE_TAIL$")
    private val RE_CLOSE_THIS_APP = Regex("^$POLITE(?:close|exit|quit|leave|shut|get out of|come out of)\\s+" +
        "(?:(?:this|the|that|my|the current|this current)\\s+)?(?:app|application|program)$POLITE_TAIL$")
    private val RE_CLOSE_NAMED_APP = Regex("^$POLITE(?:close|exit|quit)\\s+(?:the\\s+|my\\s+)?(.{2,30}?)(?:\\s+app)?$POLITE_TAIL$")
    /** Things people close that are not apps — left to the agent, which sees the screen. */
    private val RE_NOT_AN_APP = Regex("\\b(?:notifications?|panel|shade|door|gate|window|windows|tab|tabs|keyboard|menu|dialog|dialogue|pop-?up|" +
        "all|everything|apps|applications|it|this|that|them|call|chat|group|message|page|screen|ad|advert|account|file|folder|eyes?)\\b")

    private fun closeApp(g: String, raw: String): FastIntent.CloseApp? {
        if (RE_CLOSE_THIS_APP.matches(g)) return FastIntent.CloseApp(null)
        val name = RE_CLOSE_NAMED_APP.matchEntire(g)?.groupValues?.get(1)?.trim() ?: return null
        if (name.length < 2 || name.split(' ').size > 3) return null
        if (RE_NOT_AN_APP.containsMatchIn(name) || RE_APP_HAS_VERB.containsMatchIn(name)) return null
        return FastIntent.CloseApp(watchName(raw, name))
    }

    // ---- Ghanaian English drops the verb: "on the torch", "off the light", "on the bluetooth" (audit 2026-10-01, L10/L11:
    // 7–13 s through the cloud model against 0.2 s here). "light" is the torch ONLY in these on/off commands — "is the
    // light on" stays the camera's question. Twi: "sɔ kanea (no)" = put the light on, "dum kanea (no)" = put it out (L04).
    private const val GH_LEAD = "(?:(?:please|kindly|abeg|i beg|chale|charley|charlie|can you|could you)\\s+)*"
    private const val GH_TAIL = "(?:\\s+(?:for me|please|now|wai|oo|o))*"
    private val RE_GH_SWITCH = Regex("^$GH_LEAD(?:(?:put|turn|switch)\\s+)?(on|off)\\s+(?:the\\s+|my\\s+)?" +
        "(torch\\s*light|torch|flash\\s*light|light|bluetooth|wi-?fi)$GH_TAIL$")
    private val RE_GH_LIGHT_AFTER = Regex("^$GH_LEAD(?:(?:put|turn|switch)\\s+)?(?:the\\s+|my\\s+)?(?:torch\\s*light|light)\\s+(on|off)$GH_TAIL$")
    private val RE_TWI_LIGHT = Regex("^(?:mesr[ɛe] wo\\s+|mepa wo ky[ɛe]w\\s+)?(s[ɔo]|dum)\\s+kanea(?:\\s+no)?(?:\\s+ma me)?$")
    /** "close the light / the torch" = put it off, "open the light" = put it on (a light is opened and closed in Ghanaian
     *  English). Named here so "close the torch" is never read as closing an app called Torch. */
    private val RE_GH_OPEN_CLOSE_LIGHT = Regex("^$GH_LEAD(open|close|kill|quench)\\s+(?:the\\s+|my\\s+)?" +
        "(?:torch\\s*light|torch|flash\\s*light|light)$GH_TAIL$")

    private fun ghSwitch(g: String): FastIntent? {
        RE_TWI_LIGHT.matchEntire(g)?.let { return if (it.groupValues[1] == "dum") FastIntent.FlashlightOff else FastIntent.FlashlightOn }
        RE_GH_OPEN_CLOSE_LIGHT.matchEntire(g)?.let { return if (it.groupValues[1] == "open") FastIntent.FlashlightOn else FastIntent.FlashlightOff }
        val m = RE_GH_SWITCH.matchEntire(g)
        val on: Boolean
        val what: String
        if (m != null) { on = m.groupValues[1] == "on"; what = m.groupValues[2] }
        else { val a = RE_GH_LIGHT_AFTER.matchEntire(g) ?: return null; on = a.groupValues[1] == "on"; what = "light" }
        return when {
            what.startsWith("bluetooth") -> if (on) FastIntent.BluetoothOn else FastIntent.BluetoothOff
            what.startsWith("wi") -> if (on) FastIntent.WifiOn else FastIntent.WifiOff
            else -> if (on) FastIntent.FlashlightOn else FastIntent.FlashlightOff
        }
    }

    // ---- "read my last four text messages" (audit 2026-10-01: one was read). A number next to last/latest or next to
    // the word for the messages; "the last 2 days" is a time window, not a count.
    private const val COUNT = "(\\d{1,2}|two|three|four|five|six|seven|eight|nine|ten|few|couple)"
    private val RE_SMS_COUNT = Regex(
        "\\b(?:last|latest|recent|newest|past|previous)\\s+$COUNT\\b(?!\\s+(?:days?|hours?|weeks?|minutes?|months?|years?)\\b)" +
            "|\\b$COUNT\\s+(?:of\\s+)?(?:my\\s+)?(?:last|latest|most recent|recent|newest|new|unread)\\b" +
            "|\\b$COUNT\\s+(?:text messages?|texts|sms|messages)\\b")
    private val COUNT_WORDS = mapOf("two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "few" to 3, "couple" to 2)

    /** How many texts were asked for (2–10), or null for one / no number. Pure. */
    internal fun smsCount(g: String): Int? {
        val m = RE_SMS_COUNT.find(g) ?: return null
        val w = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return null
        val n = w.toIntOrNull() ?: COUNT_WORDS[w] ?: return null
        return n.coerceAtMost(10).takeIf { it >= 2 }
    }
}
