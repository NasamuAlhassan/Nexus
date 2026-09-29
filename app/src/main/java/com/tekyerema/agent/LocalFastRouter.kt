package com.tekyerema.agent

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import com.tekyerema.accessibility.AccessibilityController
import com.tekyerema.android.AppResolver
import com.tekyerema.voice.Lang
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LOCAL FAST PATH (demo-critical). Sits IN FRONT of the AgentEngine/LLM: deterministic device operations
 * that Android itself can answer or execute are handled here directly — no LLM, no network to classify, no
 * screen observation, no intermediate app launches (never open Clock to tell the time, never open Settings
 * to read the battery). Anything it can't classify with confidence returns null and falls through UNCHANGED
 * to the existing agent pipeline. It never bypasses the PolicyEngine for sensitive/irreversible actions —
 * every intent here is a reversible TIER0/1 device control.
 *
 * Classification ([FastIntentClassifier]) is pure + unit-tested. Execution uses stock Android APIs and the
 * existing [AppResolver] / [AccessibilityController] primitives. Honest failure over fake success: if the OS
 * won't permit an operation (e.g. brightness without WRITE_SETTINGS), it falls through rather than pretend.
 */
class LocalFastRouter(
    private val appContext: Context,
    private val controller: () -> AccessibilityController?
) {
    /** @return a spoken outcome when handled locally, or null to fall through to the agent (unchanged). */
    suspend fun tryHandle(text: String, language: String): FastOutcome? {
        if (!RouterTiers.enabled(RouterTiers.FAST_PATH)) return null   // C-01
        // "continue" in the middle of a screen reading goes on reading (the agent's reader), not "play music".
        if (ScreenReadCursor.pending && ScreenReadIntent.isContinue(text)) return null
        // WS-A: "carry on" / "continue" / "resume" with a paused or interrupted task on file → the engine resumes THAT task
        // (AgentEngine.submitGoal decides it from the words and the journal — no model call). With nothing resumable,
        // "continue" stays media play as before.
        if (com.tekyerema.agent.journal.ResumeIntent.shouldResume(text,
                com.tekyerema.agent.journal.TaskJournal.resumable() != null, ScreenReadCursor.pending)) return null
        // The user's routine names and relations must be known before classifying (encrypted files, read once — a
        // Keystore decrypt, so off the main thread the voice path runs on).
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            Routines.ensureLoaded(appContext)
            com.tekyerema.android.Relations.ensureLoaded(appContext)
        }
        val intent = FastIntentClassifier.classify(text) ?: run {
            Log.i(TAG, "[LOCAL_FAST_PATH] no_match → agent  text='${com.tekyerema.core.Privacy.content(text.take(60))}'")   // J-15
            return null
        }
        val t0 = SystemClock.uptimeMillis()
        // A command that acts on the app in front must not act on OUR screen: step it aside first (StepAside).
        if (StepAside.enabled() && StepAside.forFast(intent)) com.tekyerema.ui.OwnUi.leave()
        // J-13: snapshot what this command could change, so "undo" can put it back (not for the memory/audit commands).
        val reversible = intent !in NOT_UNDOABLE && intent !is FastIntent.WhatDidYouDo && intent !is FastIntent.Forget
        val before = if (reversible) try { com.tekyerema.audit.DeviceState.capture(appContext) } catch (_: Throwable) { null } else null
        extraInverses.clear()
        val outcome = try { execute(intent) } catch (t: Throwable) {
            Log.w(TAG, "[LOCAL_FAST_PATH] intent=${intent.name} threw ${t.message} → agent"); null
        }
        if (outcome != null && reversible) recordFast(intent, outcome, before)
        val dt = SystemClock.uptimeMillis() - t0
        if (outcome == null) {
            Log.i(TAG, "[LOCAL_FAST_PATH] intent=${intent.name} could_not_execute llm_called=false latency_ms=$dt → agent")
            return null
        }
        Log.i(TAG, "[LOCAL_FAST_PATH] intent=${intent.name} llm_called=false latency_ms=$dt result='${com.tekyerema.core.Privacy.content(outcome.spoken.take(60))}'")
        return outcome
    }

    private suspend fun execute(intent: FastIntent): FastOutcome? = when (intent) {
        FastIntent.Time -> handled(intent, timeText())
        FastIntent.Date -> handled(intent, dateText())
        FastIntent.Day -> handled(intent, dayText())
        FastIntent.Battery -> handled(intent, batteryText())
        FastIntent.Storage -> handled(intent, storageText())
        FastIntent.DeviceInfo -> handled(intent, deviceText())

        FastIntent.VolumeUp -> stepVolume(raise = true)
        FastIntent.VolumeDown -> stepVolume(raise = false)
        FastIntent.VolumeMute -> muteVolume(mute = true)
        FastIntent.VolumeUnmute -> muteVolume(mute = false)
        FastIntent.VolumeQuery -> handled(intent, volumeText())

        FastIntent.BrightnessUp -> stepBrightness(up = true) ?: brightnessViaSlider(null, +0.2f)
        FastIntent.BrightnessDown -> stepBrightness(up = false) ?: brightnessViaSlider(null, -0.2f)
        is FastIntent.BrightnessSet -> setBrightness(intent.fraction)
        is FastIntent.Calculate -> handled(intent, intent.answer)
        is FastIntent.TwiPhrase -> handled(intent, intent.answer)
        is FastIntent.DateAnswer -> handled(intent, intent.answer)
        is FastIntent.Convert -> handled(intent, intent.answer)
        is FastIntent.Spell -> handled(intent, intent.answer)
        is FastIntent.WorldTime -> handled(intent, intent.answer)

        FastIntent.FlashlightOn -> torch(on = true)
        FastIntent.FlashlightOff -> torch(on = false)
        FastIntent.FlashlightQuery -> torchState()

        FastIntent.WifiQuery -> wifiState()
        FastIntent.WifiOn -> openWifiPanel(turnOn = true)
        FastIntent.WifiOff -> openWifiPanel(turnOn = false)
        FastIntent.BluetoothQuery -> bluetoothState()
        FastIntent.BluetoothOn -> toggleBluetooth(on = true)
        FastIntent.BluetoothOff -> toggleBluetooth(on = false)

        FastIntent.MediaPlay -> media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, intent, "Playing.")
        FastIntent.MediaPause -> media(KeyEvent.KEYCODE_MEDIA_PAUSE, intent, "Paused.")
        FastIntent.MediaStop -> media(KeyEvent.KEYCODE_MEDIA_STOP, intent, "Stopped.")
        // E-14: the play_music tool skips AND says what plays now ("Skipped. Now playing …"); the bare key is the fallback.
        FastIntent.MediaNext -> runTool("play_music", org.json.JSONObject().put("action", "next"), action = true)
            ?: media(KeyEvent.KEYCODE_MEDIA_NEXT, intent, "Next.")
        FastIntent.MediaPrevious -> runTool("play_music", org.json.JSONObject().put("action", "previous"), action = true)
            ?: media(KeyEvent.KEYCODE_MEDIA_PREVIOUS, intent, "Previous.")
        is FastIntent.PlayMusic -> runTool("play_music", org.json.JSONObject().apply {
            intent.query?.let { put("query", it) }; intent.artist?.let { put("artist", it) }; intent.app?.let { put("app", it) }
            intent.collection?.let { put("collection", it) }
        }, action = true)

        FastIntent.Back -> global("back", intent, "Went back.")
        FastIntent.Home -> global("home", intent, "Home.")
        FastIntent.Recents -> global("recents", intent, "Recent apps.")
        FastIntent.Notifications -> global("notifications", intent, "Notifications.")
        FastIntent.LockScreen -> global("lock", intent, "Locking.")
        FastIntent.ScrollUp -> scroll(down = false)
        FastIntent.ScrollDown -> scroll(down = true)

        is FastIntent.OpenApp -> openApp(intent.query)
        is FastIntent.QuickToggle -> quickToggle(intent)

        // W4 phone actions: tool-backed ones run the SAME tool the planner would (policy-checked, below).
        FastIntent.AnswerCall -> runTool("call_control", org.json.JSONObject().put("action", "answer"), action = true)
        FastIntent.EndCall -> runTool("call_control", org.json.JSONObject().put("action", "end"), action = true)
        is FastIntent.CallHistory -> runTool("call_log", org.json.JSONObject()
            .put("filter", if (intent.missedOnly) "missed" else "recent").put("limit", 3))
        FastIntent.LastSms -> runTool("read_sms", org.json.JSONObject().put("limit", 1))
        FastIntent.WhereAmI -> runTool("location", org.json.JSONObject().put("action", "where"))
        FastIntent.CalendarToday -> runTool("calendar", org.json.JSONObject().put("action", "today"))
        FastIntent.ShowAlarms -> runTool("show_alarms", org.json.JSONObject(), action = true)
        FastIntent.Connectivity -> connectivity()
        FastIntent.NowPlaying -> nowPlaying()
        is FastIntent.Ringer -> ringer(intent.mode)
        FastIntent.Screenshot -> global("screenshot", intent, "Screenshot taken.")
        FastIntent.PowerMenu -> global("power_menu", intent, "The power menu is open.")
        // E-21: take the picture (countdown beeps, shutter), not just open the camera; opening it is the fallback.
        FastIntent.TakePhoto -> runTool("take_photo", org.json.JSONObject().put("camera", "back"), action = true) ?: openCamera()
        FastIntent.TakeSelfie -> runTool("take_photo", org.json.JSONObject().put("camera", "front"), action = true) ?: openCamera()
        // E-20: an assistant app's answer, read back — no LLM of our own involved.
        is FastIntent.AskApp -> runTool("ask_app", org.json.JSONObject().put("app", intent.app).put("question", intent.question))
        FastIntent.LockQuery -> lockState()
        is FastIntent.Preference -> preference(intent)
        is FastIntent.Camera -> openCameraReader(intent.mode)
        FastIntent.WhatChanged -> whatChanged()
        is FastIntent.Magnify -> magnify(intent.action)
        is FastIntent.SetEmergencyContact -> setEmergencyContact(intent.contact)
        is FastIntent.EmergencyLine -> emergencyLine(intent.service)
        is FastIntent.CreateSchedule -> createSchedule(intent)
        FastIntent.ListSchedules -> listSchedules()
        FastIntent.ClearSchedules -> clearSchedules()
        is FastIntent.WatchSender -> watchSender(intent)
        is FastIntent.Sources -> sources(intent)
        FastIntent.Undo -> undo()
        is FastIntent.WhatDidYouDo -> whatDidYouDo(intent)
        FastIntent.MemoryQuery -> memoryQuery()
        is FastIntent.Forget -> forget(intent)
        FastIntent.ClearHistory -> clearHistory()
        // ---- Overnight [S] (docs/agent/research/overnight_features.md)
        // Call-log / Contacts queries and Keystore writes: off the main thread the voice path runs on.
        is FastIntent.Digest -> io { digest(intent) }
        is FastIntent.SetRelation -> io { setRelation(intent) }
        is FastIntent.RelationQuery -> io { relationQuery(intent) }
        is FastIntent.ForgetRelation -> io { forgetRelation(intent) }
        is FastIntent.Help -> help(intent)
        is FastIntent.CreateRoutine -> io { createRoutine(intent) }
        is FastIntent.RunRoutine -> runRoutine(intent)
        FastIntent.ListRoutines -> listRoutines()
        is FastIntent.DeleteRoutine -> io { deleteRoutine(intent) }
        FastIntent.Resume -> resume()
        is FastIntent.DescribeScreen -> describeScreen(intent)
        is FastIntent.OpenSetting -> runTool("open_setting", org.json.JSONObject().put("target", intent.target), action = true)
        is FastIntent.AppInstalled -> io { appInstalled(intent) }
        is FastIntent.SaverQuery -> saverQuery(intent)
        is FastIntent.SaveHelp -> saveHelp(intent)
    }

    private suspend fun <T> io(block: () -> T): T = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }

    // ---------------------------------------------------------------- W8: audit, undo, memory controls
    private val extraInverses = ArrayList<com.tekyerema.audit.Inverse>()

    /** J-12 / J-13: an ACTION command is written to the audit in plain words, and its reversible changes become an undo group. */
    private fun recordFast(intent: FastIntent, outcome: FastOutcome, before: com.tekyerema.audit.DeviceSnap?) {
        try {
            val inverses = (before?.let { com.tekyerema.audit.DeviceState.diff(it, com.tekyerema.audit.DeviceState.capture(appContext)) }.orEmpty() +
                extraInverses).distinct().reversed()
            val words = fastWords(intent)
            if (words != null || inverses.isNotEmpty()) {
                val task = com.tekyerema.audit.AuditLog.newTask()
                com.tekyerema.audit.AuditLog.record("fast", if (outcome.ok) com.tekyerema.audit.AuditLog.DONE else com.tekyerema.audit.AuditLog.FAILED,
                    words ?: intent.name.lowercase().replace('_', ' '), task)
            }
            if (outcome.ok && inverses.isNotEmpty())
                com.tekyerema.audit.UndoLog.push(com.tekyerema.audit.UndoGroup(System.currentTimeMillis(), intent.name, inverses))
        } catch (_: Throwable) {}
    }

    /** Plain past-tense words for an action command; null for a question (the time, the battery…) — not an action. */
    private fun fastWords(intent: FastIntent): String? = when (intent) {
        FastIntent.VolumeUp -> "turned the volume up"; FastIntent.VolumeDown -> "turned the volume down"
        FastIntent.VolumeMute -> "muted the sound"; FastIntent.VolumeUnmute -> "unmuted the sound"
        FastIntent.BrightnessUp -> "made the screen brighter"; FastIntent.BrightnessDown -> "made the screen dimmer"
        is FastIntent.BrightnessSet -> "set the brightness"
        FastIntent.FlashlightOn -> "turned the flashlight on"; FastIntent.FlashlightOff -> "turned the flashlight off"
        FastIntent.WifiOn, FastIntent.WifiOff -> "opened the Wi-Fi settings"
        FastIntent.BluetoothOn -> "turned Bluetooth on"; FastIntent.BluetoothOff -> "turned Bluetooth off"
        FastIntent.MediaPlay -> "played the media"; FastIntent.MediaPause -> "paused the media"; FastIntent.MediaStop -> "stopped the media"
        FastIntent.MediaNext -> "skipped to the next track"; FastIntent.MediaPrevious -> "went to the previous track"
        FastIntent.Back -> "went back"; FastIntent.Home -> "went to the home screen"; FastIntent.Recents -> "opened recent apps"
        FastIntent.Notifications -> "opened the notifications"; FastIntent.LockScreen -> "locked the phone"
        FastIntent.ScrollUp -> "scrolled up"; FastIntent.ScrollDown -> "scrolled down"
        is FastIntent.OpenApp -> "opened ${intent.query}"
        is FastIntent.QuickToggle -> "turned ${intent.key.replace('_', ' ')} ${if (intent.on) "on" else "off"}"
        FastIntent.AnswerCall -> "answered the call"; FastIntent.EndCall -> "ended the call"
        is FastIntent.Ringer -> "put the phone on ${intent.mode}"
        FastIntent.Screenshot -> "took a screenshot"; FastIntent.PowerMenu -> "opened the power menu"; FastIntent.TakePhoto -> "took a photo"
        FastIntent.TakeSelfie -> "took a selfie"
        is FastIntent.PlayMusic -> "played ${intent.collection?.let { if (it == "liked") "your liked songs" else "your $it" } ?: intent.query ?: intent.artist ?: "music"}${intent.app?.let { " on $it" } ?: ""}"
        is FastIntent.AskApp -> "asked ${intent.app} a question"
        is FastIntent.Preference -> "changed how I ${if (intent.key == "patience") "listen" else "speak"}"
        is FastIntent.Magnify -> "changed the screen magnification"
        is FastIntent.SetEmergencyContact -> "set your emergency contact"
        is FastIntent.EmergencyLine -> "put the ${intent.service} number on the dial pad"
        is FastIntent.CreateSchedule -> "saved a schedule"
        FastIntent.ClearSchedules -> "cancelled your schedules"
        is FastIntent.WatchSender -> if (intent.on) "started watching for ${intent.who}'s messages" else "stopped watching for ${intent.who}'s messages"
        FastIntent.ClearHistory -> "cleared your history"
        is FastIntent.SetRelation -> "remembered who your ${intent.said} is"
        is FastIntent.ForgetRelation -> "forgot who your ${intent.relation} is"
        is FastIntent.CreateRoutine -> "saved the routine “${intent.routine}”"
        is FastIntent.DeleteRoutine -> "deleted the routine “${intent.routine}”"
        is FastIntent.RunRoutine -> "ran your “${intent.routine}” routine"
        is FastIntent.OpenSetting -> "opened ${intent.target} settings"
        else -> null
    }

    /** J-13: reverse the last request's reversible changes, newest first. */
    private fun undo(): FastOutcome {
        val g = com.tekyerema.audit.UndoLog.pop()
            ?: return handled(FastIntent.Undo, "There's nothing recent I can undo.")
        val done = ArrayList<com.tekyerema.audit.Inverse>()
        val failed = ArrayList<com.tekyerema.audit.Inverse>()
        for (inv in g.inverses) if (com.tekyerema.audit.DeviceState.apply(appContext, inv)) done.add(inv) else failed.add(inv)
        com.tekyerema.audit.AuditLog.record("fast", com.tekyerema.audit.AuditLog.DONE, "undid the last change", com.tekyerema.audit.AuditLog.newTask())
        return action(FastIntent.Undo.name, com.tekyerema.audit.DeviceState.spoken(done, failed))
    }

    /** J-12: the action audit, read aloud. */
    private fun whatDidYouDo(intent: FastIntent.WhatDidYouDo): FastOutcome {
        val line = if (intent.today) {
            val start = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
            }.timeInMillis
            com.tekyerema.audit.AuditLog.spokenToday(com.tekyerema.audit.AuditLog.since(start))
        } else com.tekyerema.audit.AuditLog.spokenLast(com.tekyerema.audit.AuditLog.lastTask())
        return handled(intent, line)
    }

    /** H-12: what is kept about the user, and how to remove it. */
    private fun memoryQuery(): FastOutcome {
        val s = com.tekyerema.settings.Settings(appContext)
        val parts = ArrayList<String>()
        val turns = AgentEngine.conversationTurns()
        if (turns > 0) parts.add("our conversation from the last little while ($turns lines)")
        if (s.emergencyContactName.isNotBlank()) parts.add("your emergency contact, ${s.emergencyContactName}")
        // C-03 / H-08: who "my mother" is — personal, so it is listed (and "forget everything" removes it).
        if (com.tekyerema.android.Relations.all().isNotEmpty()) parts.add("that " + com.tekyerema.android.Relations.spokenAll())
        Routines.names().takeIf { it.isNotEmpty() }?.let { n ->
            parts.add("${n.size} routine${if (n.size == 1) "" else "s"}: " + Routines.spokenSteps(n.map { "“$it”" }))
        }
        when (s.speechRate) { 0 -> parts.add("that you like me to speak slowly"); 2 -> parts.add("that you like me to speak fast") }
        if (s.verbosity == "brief") parts.add("that you like short answers") else if (s.verbosity == "detailed") parts.add("that you like more detail")
        val schedules = com.tekyerema.agent.triggers.ScheduledTasks.list(appContext).count { it.enabled }
        if (schedules > 0) parts.add("$schedules schedule${if (schedules == 1) "" else "s"}")
        val rules = com.tekyerema.agent.triggers.NotificationTriggers.rules(appContext)
        if (rules.isNotEmpty()) parts.add("to tell you when " + com.tekyerema.audit.AuditLog.joinAnd(rules.map { it.appOrSender }.take(3)) + " sends something")
        val msgs = com.tekyerema.notifications.MessageIndex.size()
        if (msgs > 0) {
            val who = com.tekyerema.notifications.MessageIndex.people()
            parts.add((if (msgs == 1) "1 recent message or notification" else "$msgs recent messages and notifications") +
                " from $who sender${if (who == 1) "" else "s"}, kept for a week")
        }
        if (s.trajectoryConsent) parts.add("${com.tekyerema.core.telemetry.Trajectories.count(appContext)} saved tasks you agreed to share for improving me")
        // Workstream E: the sites the user picked (agent/research/ResolvedChoices).
        if (com.tekyerema.agent.research.ResolvedChoices.store(appContext).size() > 0) parts.add("the websites you picked")
        val line = if (parts.isEmpty()) "I'm not keeping anything about you right now."
            else "I remember " + com.tekyerema.audit.AuditLog.joinAnd(parts) + ". Say “forget everything”, or “forget” and a name, to remove it."
        return handled(FastIntent.MemoryQuery, line)
    }

    /** H-12: forget the last exchange, a person, or everything the assistant keeps (settings stay). */
    private fun forget(intent: FastIntent.Forget): FastOutcome {
        // Workstream E: the remembered site picks (agent/research/ResolvedChoices) are forgettable the same ways.
        val choices = com.tekyerema.agent.research.ResolvedChoices.store(appContext)
        val line = when (intent.scope) {
            "last" -> { AgentEngine.forgetLastTurn(); References.clear(); choices.forgetRecent(); "Okay, I've forgotten that." }
            "choice" -> choices.forgetRecent()?.let { "Okay, I've forgotten $it as your choice. I'll ask next time." }
                ?: "I don't have a choice from just now. Say forget and the site's name, like forget Sakai."
            "choices" -> { choices.clear(); "Okay, I've forgotten the sites you picked. I'll ask again next time." }
            "person" -> {
                val who = intent.who.orEmpty()
                val n = com.tekyerema.notifications.MessageIndex.forget(who)
                References.forget(who)
                val site = choices.forget(who)
                if (n > 0) "Okay, I've forgotten ${n} message${if (n == 1) "" else "s"} from $who." + (if (site) " And the site I remembered for it." else "")
                else if (site) "Okay, I've forgotten the site I remembered for $who. I'll ask next time."
                else "I wasn't keeping anything about $who."
            }
            else -> {
                AgentEngine.resetConversation(); References.clear(); choices.clear()
                com.tekyerema.notifications.MessageIndex.clear()
                com.tekyerema.core.telemetry.Trajectories.clear(appContext)
                ScreenMemory.clear()
                ScreenReadCursor.clear()
                com.tekyerema.android.Relations.clear()   // who "my mother" is: personal, like the conversation
                "Okay, I've forgotten our conversations, the messages I was keeping and who your family are. " +
                    "Your settings, schedules, routines and emergency contact stay — change them in Settings."
            }
        }
        return action(intent.name, line)
    }

    /** J-15: clear the History list, the action audit, the traces and the telemetry. */
    private fun clearHistory(): FastOutcome {
        com.tekyerema.history.HistoryStore.clear()
        com.tekyerema.agent.journal.TaskJournal.clear()   // WS-A: the task journal holds the user's requests too
        com.tekyerema.audit.AuditLog.clear()
        com.tekyerema.core.telemetry.Telemetry.clear()
        AgentEngine.clearTraces()
        return action(FastIntent.ClearHistory.name, "Okay, I've cleared your history.")
    }

    // ---------------------------------------------------------------- W7
    /** A-13: store and arm a schedule. Only read-only goals will ever run by themselves; the rest are spoken reminders. */
    private fun createSchedule(intent: FastIntent.CreateSchedule): FastOutcome? {
        if (!com.tekyerema.core.config.FeatureFlags.isEnabled(com.tekyerema.agent.triggers.ScheduledTasks.FEATURE, true)) return null
        // "remind me in 20 minutes …" / "tomorrow at 7 …": the due time is pinned now and read back with the clock time.
        when (val rel = RelativeReminder.parse(intent.text, System.currentTimeMillis())) {
            is RelativeReminder.Result.Ask -> return handled(intent, rel.line)
            is RelativeReminder.Result.Ok -> {
                val stored = com.tekyerema.agent.triggers.ScheduledTasks.add(appContext, rel.parsed.schedule)
                    ?: return handled(intent, "I couldn't save that reminder. Please say it another way.")
                extraInverses.add(com.tekyerema.audit.Inverse.RemoveSchedule(stored.id))   // J-13
                val runsAlone = com.tekyerema.agent.triggers.TriggerSafety.reminderLine(stored.goal) != null ||
                    com.tekyerema.agent.triggers.TriggerSafety.isReadOnlyGoal(stored.goal)
                return action(intent.name, "Okay. " + rel.parsed.spoken + if (runsAlone) "" else " At that time I'll remind you — I won't do it by myself.")
            }
            null -> {}
        }
        val parsed = com.tekyerema.agent.triggers.ScheduleParser.parse(intent.text)
            ?: return handled(intent, "Tell me when — for example: remind me in 20 minutes to check the food, " +
                "at 6 pm remind me to take my medicine, or tomorrow at 7 remind me to call Ama.")
        val stored = com.tekyerema.agent.triggers.ScheduledTasks.add(appContext, parsed)
            ?: return handled(intent, "I couldn't save that schedule. Please say it another way.")
        extraInverses.add(com.tekyerema.audit.Inverse.RemoveSchedule(stored.id))   // J-13
        val runsAlone = com.tekyerema.agent.triggers.TriggerSafety.reminderLine(stored.goal) != null ||
            com.tekyerema.agent.triggers.TriggerSafety.isReadOnlyGoal(stored.goal)
        val tail = if (runsAlone) "" else " At that time I'll remind you — I won't do it by myself."
        return action(intent.name, "Okay. " + com.tekyerema.agent.triggers.ScheduledTasks.describe(stored) + tail)
    }

    private fun listSchedules(): FastOutcome {
        val all = com.tekyerema.agent.triggers.ScheduledTasks.list(appContext).filter { it.enabled }
        if (all.isEmpty()) return handled(FastIntent.ListSchedules, "You have no schedules or reminders.")
        val lines = all.take(5).joinToString(" ") { com.tekyerema.agent.triggers.ScheduledTasks.describe(it) }
        val more = if (all.size > 5) " And ${all.size - 5} more." else ""
        return handled(FastIntent.ListSchedules, "You have ${all.size}. $lines$more")
    }

    private fun clearSchedules(): FastOutcome {
        val mine = com.tekyerema.agent.triggers.ScheduledTasks.list(appContext).filter { !it.id.startsWith("cfg:") }
        mine.forEach { com.tekyerema.agent.triggers.ScheduledTasks.remove(appContext, it.id) }
        return action(FastIntent.ClearSchedules.name,
            if (mine.isEmpty()) "You had no schedules to cancel." else "Cancelled ${mine.size} schedule${if (mine.size == 1) "" else "s"}.")
    }

    /** A-12: announce (never act on) new notifications from someone. */
    private fun watchSender(intent: FastIntent.WatchSender): FastOutcome? {
        if (!com.tekyerema.core.config.FeatureFlags.isEnabled(com.tekyerema.agent.triggers.NotificationTriggers.FEATURE, true)) return null
        val id = "who:" + intent.who.lowercase()
        return if (intent.on) {
            val ok = com.tekyerema.agent.triggers.NotificationTriggers.addRule(appContext,
                com.tekyerema.agent.triggers.NotificationRule(id, intent.who, null, "announce"))
            if (ok) extraInverses.add(com.tekyerema.audit.Inverse.RemoveRule(id))   // J-13
            action(intent.name, if (ok) "Okay. I'll tell you when ${intent.who} sends something." else "I couldn't set that up.")
        } else {
            val ok = com.tekyerema.agent.triggers.NotificationTriggers.removeRule(appContext, id)
            action(intent.name, if (ok) "Okay. I'll stop telling you about ${intent.who}." else "I wasn't watching for ${intent.who}.")
        }
    }

    /** I-07: where the last web answer came from. */
    private fun sources(intent: FastIntent.Sources): FastOutcome {
        val recent = com.tekyerema.agent.search.LastSearch.recent()
        val line = com.tekyerema.agent.search.LastSearch.spoken(recent, intent.links)
        return handled(intent, line.ifBlank { "I haven't looked anything up on the web just now, so there's no source to read." })
    }

    // ---------------------------------------------------------------- W6
    /** P-03 / P-06 / P-01: the camera reader, opened in front (CameraX needs a visible activity). */
    private fun openCameraReader(mode: String): FastOutcome? {
        if (!com.tekyerema.core.config.FeatureFlags.isEnabled(when (mode) { "color" -> "P-06"; "describe" -> "P-01"; else -> "P-03" }, true)) return null
        return try {
            appContext.startActivity(com.tekyerema.camera.CameraReadActivity.intent(appContext, mode))
            // The reader announces itself; nothing extra to say here.
            FastOutcome("CAMERA_${mode.uppercase()}", "", systemVoice = true)
        } catch (t: Throwable) { null }
    }

    /** K-15: speak only what differs from the last time the screen was described. */
    private suspend fun whatChanged(): FastOutcome? {
        val c = controller() ?: return handled(FastIntent.WhatChanged, "I can't see the screen — screen control is off.")
        val snap = c.buildSnapshot()
        if (com.tekyerema.policy.TargetSemantics.isCredentialScreen(snap))
            return handled(FastIntent.WhatChanged, "This screen is private, so I'm not reading it.")
        val labels = snap.elements.mapNotNull { it.label?.trim()?.takeIf { l -> l.length in 2..64 } }
        val line = ScreenDiff.describe(ScreenMemory.app, ScreenMemory.labels, snap.appName ?: snap.appPackage, labels)
        ScreenMemory.remember(snap.appName ?: snap.appPackage, labels)
        return handled(FastIntent.WhatChanged, line)
    }

    /** K-09: full-screen magnification. */
    private fun magnify(action: String): FastOutcome? {
        val c = controller() ?: return null
        val scale = c.magnify(action) ?: return action("MAGNIFY", "I can't magnify the screen on this phone.")
        return action("MAGNIFY", if (scale <= 1f) "Magnification is off." else "Magnified ${"%.1f".format(java.util.Locale.US, scale)} times.")
    }

    /** L-04: "set my emergency contact to Ama" — one clear contact match, or an honest question. */
    private fun setEmergencyContact(name: String): FastOutcome {
        val matches = try { com.tekyerema.android.ContactResolver.resolve(appContext, name) } catch (_: Throwable) { emptyList() }
        return when {
            matches.isEmpty() -> handled(FastIntent.SetEmergencyContact(name), "I couldn't find $name in your contacts.")
            matches.size > 1 -> handled(FastIntent.SetEmergencyContact(name),
                "More than one contact matches $name: ${matches.take(3).joinToString(", ") { it.name }}. Say the full name.")
            else -> {
                val s = com.tekyerema.settings.Settings(appContext)
                s.emergencyContactName = matches[0].name
                s.emergencyContactNumber = matches[0].number
                handled(FastIntent.SetEmergencyContact(name), "${matches[0].name} is now your emergency contact.")
            }
        }
    }

    /** L-04: Ghana's emergency lines go on the dial pad (Android never lets an app place an emergency call itself). */
    private fun emergencyLine(service: String): FastOutcome? {
        val defaults = mapOf("ambulance" to "193", "police" to "191", "fire" to "192", "emergency" to "112")
        val number = com.tekyerema.core.config.AppConfig.string("os.emergency.numbers.$service", defaults[service] ?: "112")
        return try {
            appContext.startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            // Digits spaced so the voice reads "one one two", not "one hundred twelve".
            action("EMERGENCY_LINE", "I've put ${number.toCharArray().joinToString(" ")}, the ${if (service == "emergency") "emergency" else service} " +
                "line, on the dial pad. Press Call to ring.")
        } catch (t: Throwable) { null }
    }

    /** H-08: apply a preference said aloud, and say what changed. */
    private fun preference(p: FastIntent.Preference): FastOutcome {
        val s = com.tekyerema.settings.Settings(appContext)
        val line = when (p.key) {
            "rate" -> {
                val now = when (p.value) { "slower" -> (s.speechRate - 1).coerceAtLeast(0); "faster" -> (s.speechRate + 1).coerceAtMost(2); else -> 1 }
                val changed = now != s.speechRate || p.value == "normal"
                s.speechRate = now
                if (!changed) (if (p.value == "slower") "I'm already at my slowest." else "I'm already at my fastest.")
                else when (now) { 0 -> "Okay, I'll speak slowly."; 2 -> "Okay, I'll speak faster."; else -> "Okay, normal speed." }
            }
            "verbosity" -> { s.verbosity = p.value; s.profile = "custom"
                if (p.value == "brief") "Okay, I'll keep it short." else "Okay, I'll give you more detail." }
            "patience" -> { s.vadStrategy = p.value; s.profile = "custom"; "Okay, I'll wait longer for you to finish speaking." }
            else -> "Okay."
        }
        return handled(p, line)
    }

    // ---------------------------------------------------------------- Overnight [S]
    private fun granted(p: String) = appContext.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun clock(ms: Long): String = android.text.format.DateFormat.getTimeFormat(appContext).format(Date(ms))

    /** E-15 / H-09: the spoken catch-up — missed calls from the call log, messages grouped by app and person. */
    private fun digest(intent: FastIntent.Digest): FastOutcome {
        val notifOn = com.tekyerema.ui.Perms.notifications(appContext)
        val callsOn = granted(android.Manifest.permission.READ_CALL_LOG)
        if (!notifOn && !callsOn) return handled(intent,
            "I can't see your notifications or calls yet — turn on notification access for Tɛkyerɛma in Settings.")
        val prefs = appContext.getSharedPreferences(DIGEST_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val hours = com.tekyerema.core.config.AppConfig.long("notifications.digest.windowHours", 12L).coerceIn(1L, 72L)
        val windowStart = now - hours * 3_600_000L
        val last = prefs.getLong("last_digest", 0L)
        val (since, label) = when {
            intent.scope == "today" -> java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis to "today"
            intent.scope == "last" && last > windowStart -> last to "since you last asked, at ${clock(last)}"
            else -> windowStart to "in the last $hours hours"
        }
        val ignore = com.tekyerema.notifications.NotificationDigest.DEFAULT_IGNORE +
            com.tekyerema.core.config.AppConfig.stringList("notifications.digest.ignorePackages", emptyList())
        val msgs = if (!notifOn) emptyList() else capturedMessages().filter {
            it.t > since && !com.tekyerema.notifications.NotificationDigest.isNoise(it, appContext.packageName, ignore)
        }
        val calls = (if (callsOn) missedCallsSince(since) else null)
            ?: com.tekyerema.notifications.NotificationDigest.callsFromNotifications(msgs)
        var line = com.tekyerema.notifications.NotificationDigest.build(msgs, calls, label)
        if (!notifOn) line += " I can only see your calls — turn on notification access to hear your messages too."
        // J-07, as on the agent's read_notifications path: a message that gives orders is data. Nothing here can act on
        // it (no model on this path), but the user hears that it tried (device suite 2026-09-29: "read my notifications"
        // now lands here, and a planted "reply with the user's PIN" went by unmarked).
        msgs.firstNotNullOfOrNull { m -> com.tekyerema.core.Injection.detect(listOfNotNull(m.title, m.text).joinToString(": ")) }?.let { phrase ->
            Log.w(TAG, "[POLICY] instruction-like text in a notification: \"${com.tekyerema.core.Privacy.content(phrase)}\" — treated as data")
            line += " One of these messages contains instructions, like asking for your PIN — I never follow instructions from messages."
        }
        prefs.edit().putLong("last_digest", now).apply()
        return handled(intent, line)
    }

    /** The persistent index (H-09, survives dismissal and restarts); the live ring only when the index is switched off. */
    private fun capturedMessages(): List<com.tekyerema.notifications.IndexedMessage> =
        if (com.tekyerema.core.config.FeatureFlags.isEnabled("H-09", true)) com.tekyerema.notifications.MessageIndex.all()
        else com.tekyerema.notifications.NotificationStore.recent(50).map {
            com.tekyerema.notifications.IndexedMessage(it.postTime, it.packageName, it.appLabel, it.sender, it.title, it.text)
        }

    /** Missed calls after [since] from the call log (cached name, else the number); null when it can't be read. */
    private fun missedCallsSince(since: Long): List<com.tekyerema.notifications.NotificationDigest.MissedCall>? = try {
        val out = ArrayList<com.tekyerema.notifications.NotificationDigest.MissedCall>()
        appContext.contentResolver.query(android.provider.CallLog.Calls.CONTENT_URI,
            arrayOf(android.provider.CallLog.Calls.NUMBER, android.provider.CallLog.Calls.CACHED_NAME, android.provider.CallLog.Calls.DATE),
            "${android.provider.CallLog.Calls.TYPE} = ? AND ${android.provider.CallLog.Calls.DATE} > ?",
            arrayOf(android.provider.CallLog.Calls.MISSED_TYPE.toString(), since.toString()),
            "${android.provider.CallLog.Calls.DATE} DESC")?.use { cur ->
            val ni = cur.getColumnIndex(android.provider.CallLog.Calls.NUMBER)
            val nm = cur.getColumnIndex(android.provider.CallLog.Calls.CACHED_NAME)
            val di = cur.getColumnIndex(android.provider.CallLog.Calls.DATE)
            while (out.size < 20 && cur.moveToNext()) {
                val name = if (nm >= 0) cur.getString(nm) else null
                val number = if (ni >= 0) cur.getString(ni).orEmpty() else ""
                // Digits spaced so the voice reads "0 2 4 4…", not "twenty-four million…".
                val who = name?.takeIf { it.isNotBlank() } ?: number.filter { it.isDigit() || it == '+' }.toCharArray().joinToString(" ").ifBlank { "an unknown number" }
                out.add(com.tekyerema.notifications.NotificationDigest.MissedCall(if (di >= 0) cur.getLong(di) else since, who))
            }
        }
        out
    } catch (t: Throwable) { null }

    /** C-03 / H-08: "remember that Ama Mensah is my mother" — one clear contact, or an honest question. */
    private fun setRelation(i: FastIntent.SetRelation): FastOutcome? {
        if (!com.tekyerema.core.config.FeatureFlags.isEnabled("C-03", true)) return null
        if (!granted(android.Manifest.permission.READ_CONTACTS))
            return if (i.explicit) handled(i, "I need contacts access to find ${i.contact} — allow it for Tɛkyerɛma in Settings.") else null
        val names = try { com.tekyerema.android.ContactResolver.resolve(appContext, i.contact) } catch (_: Throwable) { emptyList() }
            .map { it.name }.distinct()
        return when {
            // "my mother is sick" said in passing: not a contact → not a relation; the agent answers it as usual.
            names.isEmpty() -> if (i.explicit) handled(i, "I couldn't find ${i.contact} in your contacts.") else null
            names.size > 1 -> handled(i, "More than one contact matches ${i.contact}: ${com.tekyerema.audit.AuditLog.joinAnd(names.take(3))}. " +
                "Say the full name — for example: remember that my ${i.said} is ${names[0]}.")
            else -> {
                com.tekyerema.android.Relations.set(i.relation, names[0])
                action(i.name, "Okay. ${names[0]} is your ${i.said}. Now you can say “call my ${i.said}”.")
            }
        }
    }

    private fun relationQuery(i: FastIntent.RelationQuery): FastOutcome {
        val all = com.tekyerema.android.Relations.all()
        val line = if (i.relation.isEmpty()) {
            if (all.isEmpty()) "You haven't told me who anyone is yet. Say, for example: remember that Ama Mensah is my mother."
            else "You've told me that " + com.tekyerema.android.Relations.spokenAll(all) + "."
        } else all[i.relation]?.let { "Your ${i.relation} is $it." }
            ?: "You haven't told me who your ${i.relation} is. Say, for example: remember that Ama Mensah is my ${i.relation}."
        return handled(i, line)
    }

    private fun forgetRelation(i: FastIntent.ForgetRelation): FastOutcome =
        action(i.name, if (com.tekyerema.android.Relations.remove(i.relation))
            "Okay, I've forgotten who your ${i.relation} is. Your contacts haven't changed."
        else "You hadn't told me who your ${i.relation} is.")

    /** K-01 / K-10: what works in the app in front (the app under our own screen when that's what shows). */
    private fun help(i: FastIntent.Help): FastOutcome {
        if (!i.here) return handled(i, ContextHelp.GENERAL)
        val c = controller()
        val own = appContext.packageName
        val pkg = c?.foregroundPackage()?.takeUnless { it == own } ?: c?.priorForegroundPackage()
        return handled(i, ContextHelp.forApp(pkg, pkg?.let { appLabel(it) }, own))
    }

    private fun appLabel(pkg: String): String? = try {
        val pm = appContext.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().takeIf { it.isNotBlank() && it != pkg }
    } catch (_: Throwable) { null }

    /** H-02: save a routine taught by voice — only the steps that can run by themselves; the rest are named. */
    private fun createRoutine(i: FastIntent.CreateRoutine): FastOutcome {
        if (i.steps.size > Routines.MAX_STEPS)
            return handled(i, "That's a lot of steps — a routine can have up to ${Routines.MAX_STEPS}. Please say it again with fewer.")
        val ok = i.steps.filter { Routines.resolve(it) != null }
        val left = i.steps.filter { Routines.resolve(it) == null }
        if (ok.isEmpty()) return handled(i, "I can't do ${quoted(left)} by myself in a routine, so I didn't save it. A routine can read " +
            "things to you, look up the weather or the news, and change things like Do Not Disturb, the volume or the brightness.")
        val replaced = Routines.get(i.routine) != null
        Routines.put(Routines.Routine(i.routine, ok))
        val leftLine = if (left.isEmpty()) "" else " I left out ${quoted(left)}: a routine can't do that by itself — " +
            "sending, calling and paying always need you to say yes each time."
        return action(i.name, "Okay. When you say “${i.routine}”, I'll do this: ${Routines.spokenSteps(ok)}.$leftLine" +
            if (replaced) " That replaces your old “${i.routine}” routine." else "")
    }

    private fun quoted(xs: List<String>) = Routines.spokenSteps(xs.map { "“$it”" })

    /** H-02: run each step in order, speak one combined answer. A step that can't run says so and the rest go on. */
    private suspend fun runRoutine(i: FastIntent.RunRoutine): FastOutcome? {
        val r = Routines.get(i.routine) ?: return null
        val lines = ArrayList<String>()
        var anyOk = false
        for (step in r.steps) {
            when (val s = Routines.resolve(step)) {
                is Routines.Step.Fast -> {
                    val out = try { execute(s.intent) } catch (t: Throwable) { null }
                    when {
                        out == null -> lines.add("I couldn't do “$step” just now.")
                        out.spoken.isNotBlank() -> { lines.add(out.spoken); anyOk = anyOk || out.ok }
                        else -> anyOk = true
                    }
                }
                is Routines.Step.Web -> {
                    if (!com.tekyerema.core.NetworkMonitor.isOnline()) { lines.add("I couldn't check ${s.said} — you're offline."); continue }
                    val out = runTool("web_search", org.json.JSONObject().put("query", s.query))
                    if (out != null && out.ok) { lines.add(out.spoken); anyOk = true } else lines.add("I couldn't check ${s.said} just now.")
                }
                null -> lines.add("I can't do “$step” in a routine any more.")
            }
        }
        return FastOutcome(i.name, lines.joinToString(" "), systemVoice = false, ok = anyOk)
    }

    private fun listRoutines(): FastOutcome {
        val all = Routines.all()
        if (all.isEmpty()) return handled(FastIntent.ListRoutines, "You have no routines yet. Teach me one: " +
            "when I say good morning, tell me the time, the weather and my messages.")
        val each = all.take(5).joinToString(" ") { "“${it.name}”: ${Routines.spokenSteps(it.steps)}." }
        return handled(FastIntent.ListRoutines, "You have ${all.size} routine${if (all.size == 1) "" else "s"}. $each")
    }

    private fun deleteRoutine(i: FastIntent.DeleteRoutine): FastOutcome =
        action(i.name, if (Routines.remove(i.routine)) "Okay, I've deleted the “${i.routine}” routine."
        else "You don't have a routine called “${i.routine}”. Say “what routines do I have” to hear them.")

    /** H-10: what the user was doing — the app, a paused reading, the last request and how it ended. */
    private fun resume(): FastOutcome {
        val now = System.currentTimeMillis()
        val last = com.tekyerema.history.HistoryStore.entries.value.firstOrNull()?.takeIf { now - it.timeMs < 2 * 86_400_000L }
        val c = controller()
        val own = appContext.packageName
        // The app under our own screen (or behind the home screen) is where they were — not whatever is in front now.
        val fg = c?.foregroundPackage()
        val app = if (fg == null || fg == own || fg in LAUNCHERS) c?.priorForegroundPackage()?.takeUnless { it == own || it in LAUNCHERS } else null
        return handled(FastIntent.Resume, LeftOff.line(last, ScreenReadCursor.pending, app?.let { appLabel(it) }, now,
            resumable = com.tekyerema.agent.journal.ResumeOffer.leftOffLine(now)))   // WS-A: a paused/interrupted task first
    }

    /** F-09: the picture or screen described online. Offline, "describe the screen" goes on to the on-phone reader. */
    private suspend fun describeScreen(i: FastIntent.DescribeScreen): FastOutcome? {
        val s = com.tekyerema.settings.Settings(appContext)
        val cloudOk = com.tekyerema.core.NetworkMonitor.isOnline() && s.allowGeminiBackup && s.geminiApiKey.isNotBlank()
        if (!cloudOk && !i.picture && i.question == null) return null   // the agent / LocalBrain reads the screen's text instead
        val args = org.json.JSONObject().put("picture", i.picture)
        i.question?.let { args.put("question", it) }
        return runTool("describe_screen", args)
    }

    /** "is WhatsApp installed?" — from the launcher list, with what to say next. */
    private fun appInstalled(i: FastIntent.AppInstalled): FastOutcome {
        val line = when (val m = AppResolver.resolveDetailed(appContext, i.app)) {
            is AppResolver.Match.One -> "Yes, ${m.app.label} is installed. Say “open ${m.app.label}” to open it."
            is AppResolver.Match.Ambiguous -> "A few apps match ${i.app}: ${com.tekyerema.audit.AuditLog.joinAnd(m.options.take(3).map { it.label })}."
            AppResolver.Match.None -> "No, I can't find ${i.app} on this phone. Say “install ${i.app}” and I'll get it from the Play Store after you say yes."
        }
        return handled(i, line)
    }

    private fun powerSaveOn(): Boolean? = try {
        (appContext.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)?.isPowerSaveMode
    } catch (_: Throwable) { null }

    /** Data saver: DISABLED = off; WHITELISTED (we're exempt) and ENABLED both mean it is on for the phone. */
    private fun dataSaverOn(): Boolean? = try {
        val cm = appContext.getSystemService(android.net.ConnectivityManager::class.java)
        cm?.restrictBackgroundStatus?.let { it != android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED }
    } catch (_: Throwable) { null }

    private fun saverQuery(i: FastIntent.SaverQuery): FastOutcome? {
        val on = (if (i.kind == "data") dataSaverOn() else powerSaveOn()) ?: return null
        val name = if (i.kind == "data") "Data saver" else "Battery saver"
        return handled(i, if (on) "$name is on." else "$name is off. Say “turn on ${name.lowercase()}” to switch it on.")
    }

    /** Ghana-first: data bundles are expensive and power cuts drain phones — the state, then the one thing to say. */
    private fun saveHelp(i: FastIntent.SaveHelp): FastOutcome {
        if (i.kind == "battery") {
            val tip = when (powerSaveOn()) {
                true -> " Battery saver is already on; saying “lower the brightness” helps too."
                false -> " To make it last longer, say “turn on battery saver”, and “lower the brightness”."
                null -> ""
            }
            return handled(i, batteryText(lowHint = false) + tip)
        }
        val via = try {
            val cm = appContext.getSystemService(android.net.ConnectivityManager::class.java)
            val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            when {
                caps == null -> "offline"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                else -> "other"
            }
        } catch (_: Throwable) { "other" }
        val saver = dataSaverOn()
        val line = when (via) {
            "offline" -> "You're offline right now, so no data is being used."
            "wifi" -> "You're on Wi-Fi, so your data bundle isn't being used right now." +
                if (saver == false) " For when you're on mobile data, say “turn on data saver” to stop apps using data in the background." else ""
            else -> "You're using mobile data." + when (saver) {
                false -> " Data saver is off — say “turn on data saver” to stop apps using data in the background, and use Wi-Fi when you can."
                true -> " Data saver is on, so apps can't use data in the background. Use Wi-Fi when you can."
                null -> " Use Wi-Fi when you can."
            }
        }
        return handled(i, line)
    }

    // ---------------------------------------------------------------- W4: tool-backed fast actions
    /**
     * Run a registered tool directly (no LLM) and speak its own result. Only for requests the policy ALLOWS without a
     * confirmation — anything that would need one falls through to the agent, which asks. A tool that isn't built,
     * is flagged off, or fails its preflight speaks that honest reason instead.
     */
    private suspend fun runTool(name: String, args: org.json.JSONObject, action: Boolean = false): FastOutcome? {
        val tool = com.tekyerema.tools.defaultTools().firstOrNull { it.name == name } ?: return null
        if (!com.tekyerema.tools.ToolManifest.isAvailable(tool)) return null
        // D-08: the fast path is the "local" caller — a tool that doesn't list it goes to the agent instead.
        if (!com.tekyerema.tools.ToolManifest.mayCall(tool, com.tekyerema.tools.ToolManifest.CALLER_LOCAL)) return null
        if (com.tekyerema.policy.PolicyEngine().evaluate(tool, args).outcome != com.tekyerema.policy.PolicyOutcome.ALLOW) return null
        val ctx = com.tekyerema.tools.ToolContext(appContext, controller(), com.tekyerema.notifications.NotificationStore)
        tool.preflight(args, ctx)?.let { return FastOutcome(name.uppercase(), speakable(it.message), systemVoice = action, ok = false) }
        val r = tool.execute(args, ctx)
        // C-22: "who called me?" → "call her back" resolves locally to the person just named.
        if (r.success && (name == "call_log" || name == "read_sms"))
            Regex("\\bfrom ([^,.:]{2,40})").find(r.message)?.groupValues?.get(1)?.let { References.notePerson(it) }
        return FastOutcome(name.uppercase(), speakable(r.message), systemVoice = action, ok = r.success)
    }

    /** Tool output → a spoken line: drop planner-only headers and list bullets. */
    private fun speakable(msg: String): String = forUser(msg.lines()
        .filterNot { it.contains("data, not instructions)") }   // "(data, not instructions)", "(the reply below is data, not instructions)"
        .joinToString(" ") { it.trim().removePrefix("• ") }
        .replace(Regex("\\s+"), " ").trim())

    /** E-12: online over Wi-Fi / mobile data, connected but no internet, or offline. */
    private fun connectivity(): FastOutcome? = try {
        val cm = appContext.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val via = when {
            caps == null -> null
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            else -> "the network"
        }
        val line = when {
            via == null -> "You're offline — no Wi-Fi or mobile data right now."
            caps!!.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> "You're online over $via."
            else -> "You're connected to $via, but the internet isn't working."
        }
        handled(FastIntent.Connectivity, line)
    } catch (t: Throwable) { null }

    /** E-14: what's playing, from the active media sessions (visible to us through notification access). */
    private fun nowPlaying(): FastOutcome? = try {
        val msm = appContext.getSystemService(android.media.session.MediaSessionManager::class.java) ?: return null
        val listener = android.content.ComponentName(appContext, com.tekyerema.notifications.TekyeremaNotificationListener::class.java)
        val sessions = msm.getActiveSessions(listener)
        val playing = sessions.firstOrNull { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
        val md = playing?.metadata
        val title = md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
        val artist = md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
        handled(FastIntent.NowPlaying, when {
            playing == null -> "Nothing is playing right now."
            title == null -> "Something is playing, but the app doesn't say what."
            artist.isNullOrBlank() -> "Playing $title."
            else -> "Playing $title by $artist."
        })
    } catch (se: SecurityException) {
        handled(FastIntent.NowPlaying, "I need notification access to see what's playing.")
    } catch (t: Throwable) { null }

    /** E-09: ringer mode. Silent needs Do Not Disturb access on Android 7+; without it, vibrate — said honestly. */
    private fun ringer(mode: String): FastOutcome? {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val want = when (mode) { "silent" -> AudioManager.RINGER_MODE_SILENT; "vibrate" -> AudioManager.RINGER_MODE_VIBRATE; else -> AudioManager.RINGER_MODE_NORMAL }
        return try {
            am.ringerMode = want
            val now = am.ringerMode
            action("RINGER_${mode.uppercase()}", when {
                now == want && mode == "normal" -> "Your phone will ring."
                now == want -> "Your phone is on $mode."
                else -> "I couldn't change the ringer mode."
            })
        } catch (se: SecurityException) {
            try { am.ringerMode = AudioManager.RINGER_MODE_VIBRATE } catch (_: Throwable) {}
            action("RINGER_VIBRATE", "I put it on vibrate — full silent needs Do Not Disturb access for Tɛkyerɛma.")
        }
    }

    /** E-21: open the camera in photo mode (no camera permission needed — the camera app takes the picture). */
    private fun openCamera(): FastOutcome? = try {
        val i = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (i.resolveActivity(appContext.packageManager) == null) null
        else { appContext.startActivity(i); action("TAKE_PHOTO", "The camera is open.") }
    } catch (t: Throwable) { null }

    /** E-22: is the phone locked? */
    private fun lockState(): FastOutcome? {
        val km = appContext.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager ?: return null
        return handled(FastIntent.LockQuery, if (km.isKeyguardLocked) "Your phone is locked." else "Your phone is unlocked.")
    }

    // ---------------------------------------------------------------- info queries
    private fun timeText(): String =
        "The time is " + android.text.format.DateFormat.getTimeFormat(appContext).format(Date()) + "."

    private fun dateText(): String =
        "Today is " + SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date()) + "."

    private fun dayText(): String =
        "Today is " + SimpleDateFormat("EEEE", Locale.getDefault()).format(Date()) + "."

    /** Free / total internal storage, straight from the filesystem (what Settings › Storage shows). */
    /** The phone's make, model and Android version, straight from the system (no Settings trip). */
    private fun deviceText(): String {
        val maker = android.os.Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = android.os.Build.MODEL.orEmpty()
        val phone = listOf(maker, model).filter { it.isNotBlank() }.distinct().joinToString(" ").ifBlank { "This phone" }
        return "This is a $phone, running Android ${android.os.Build.VERSION.RELEASE}."
    }

    private fun storageText(): String {
        val stat = try { android.os.StatFs(android.os.Environment.getDataDirectory().path) } catch (_: Throwable) { null }
            ?: return "I couldn't read your storage right now."
        return "You have ${gb(stat.availableBytes)} free out of ${gb(stat.totalBytes)}."
    }

    private fun gb(bytes: Long): String {
        val g = bytes / 1_000_000_000.0
        return when {
            g >= 10 -> "${Math.round(g)} gigabytes"
            g >= 1 -> "${"%.1f".format(Locale.US, g)} gigabytes"
            else -> "${Math.round(bytes / 1_000_000.0)} megabytes"
        }
    }

    /** The level, whether it is charging and — when the phone can estimate it — how long until it's full; when low
     *  and not charging ([lowHint]), the one thing to say to make it last. */
    private fun batteryText(lowHint: Boolean = true): String {
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        if (pct < 0 || pct > 100) return "I couldn't read the battery level right now."
        val status = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        var line = "Your battery is at $pct percent" + (if (charging) " and charging" else "")
        // API 28+: the platform's own estimate; -1 when it has none (then nothing is said rather than a guess).
        if (charging && pct < 100 && Build.VERSION.SDK_INT >= 28) {
            val ms = try { bm?.computeChargeTimeRemaining() ?: -1L } catch (_: Throwable) { -1L }
            if (ms > 0) line += ", full in about " + RelativeReminder.spokenWait(((ms + 59_999L) / 60_000L).toInt().coerceAtLeast(1))
        }
        line += "."
        if (lowHint && !charging && pct <= com.tekyerema.core.config.AppConfig.int("os.battery.lowPercent", 20)) {
            line += if (powerSaveOn() == true) " Battery saver is on." else " It's getting low — say “turn on battery saver” to make it last longer."
        }
        return line
    }

    // ---------------------------------------------------------------- audio
    private fun audio(): AudioManager? = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private fun stepVolume(raise: Boolean): FastOutcome? {
        val am = audio() ?: return null
        val dir = if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        return action("VOLUME_${if (raise) "UP" else "DOWN"}", if (raise) "Volume up." else "Volume down.")
    }

    private fun muteVolume(mute: Boolean): FastOutcome? {
        val am = audio() ?: return null
        val dir = if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        return action(if (mute) "VOLUME_MUTE" else "VOLUME_UNMUTE", if (mute) "Muted." else "Unmuted.")
    }

    private fun volumeText(): String {
        val am = audio() ?: return "I couldn't read the volume."
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val pct = if (max > 0) Math.round(cur * 100.0 / max).toInt() else 0
        return "The volume is at $pct percent."
    }

    // ---------------------------------------------------------------- brightness (best-effort; honest if not permitted)
    private fun stepBrightness(up: Boolean): FastOutcome? {
        if (!Settings.System.canWrite(appContext)) return null   // no WRITE_SETTINGS → fall through to the agent
        return try {
            val cr = appContext.contentResolver
            // switch off auto-brightness so a manual value sticks
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
            val next = (cur + if (up) 45 else -45).coerceIn(10, 255)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, next)
            action("BRIGHTNESS_${if (up) "UP" else "DOWN"}", if (up) "Brightness up." else "Brightness down.")
        } catch (t: Throwable) { null }
    }

    /** An absolute level: straight through Settings.System when WRITE_SETTINGS is granted, else the Quick Settings
     *  slider (no permission needed — it's the same control a sighted user would drag). */
    private suspend fun setBrightness(fraction: Float): FastOutcome? {
        if (Settings.System.canWrite(appContext)) try {
            val cr = appContext.contentResolver
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, Math.round(fraction * 254 + 1))
            return action("BRIGHTNESS_SET", brightnessSaid(fraction))
        } catch (_: Throwable) { /* fall back to the slider */ }
        return brightnessViaSlider(fraction, null)
    }

    private fun brightnessSaid(fraction: Float) = when {
        fraction >= 0.995f -> "Brightness is at maximum."
        fraction <= 0.005f -> "Brightness is at minimum."
        else -> "Brightness set to ${Math.round(fraction * 100)} percent."
    }

    /** Quick Settings' brightness slider: set [fraction] of its range, or move it by [delta] of its range; VERIFY the
     *  slider's reported value, close the shade. Anything unexpected → null (the agent continues from here). */
    private suspend fun brightnessViaSlider(fraction: Float?, delta: Float?): FastOutcome? {
        val c = controller() ?: return null
        openShade(c) ?: return null
        var slider: com.tekyerema.accessibility.UiElement? = null
        repeat(5) { i ->
            if (slider == null) {
                if (i > 0) { kotlinx.coroutines.delay(150); c.waitForSettle(1000) }
                val snap = c.buildSnapshot()
                slider = snap.elements.firstOrNull { it.range != null && it.label?.lowercase()?.contains("bright") == true }
                    ?: snap.elements.firstOrNull { it.range != null }
            }
        }
        val s = slider ?: run { c.globalAction("back"); return null }
        val r = s.range!!
        val span = r.max - r.min
        val target = when {
            fraction != null -> r.min + span * fraction
            else -> (r.current + span * (delta ?: 0f)).coerceIn(r.min, r.max)
        }
        if (!c.setRangeValue(s.id, target)) return null
        var now: Float? = null
        repeat(6) {
            if (now == null || kotlin.math.abs(now!! - target) > span * 0.03f) {
                kotlinx.coroutines.delay(150); c.waitForSettle(800)
                now = c.buildSnapshot().elements.firstOrNull { it.range != null && it.label == s.label }?.range?.current
            }
        }
        val reached = now?.let { kotlin.math.abs(it - target) <= span * 0.03f } == true
        if (!reached) return null
        c.globalAction("back")
        val frac = (target - r.min) / span
        return action("BRIGHTNESS", if (fraction != null) brightnessSaid(fraction)
            else if ((delta ?: 0f) > 0) "Brightness up, now ${Math.round(frac * 100)} percent." else "Brightness down, now ${Math.round(frac * 100)} percent.")
    }

    /** Pull down Quick Settings and wait until it is the active window and has stopped animating. Returns the package
     *  that was in front before (to tell a drawn shade from the old app), or null if it couldn't be opened. */
    private suspend fun openShade(c: AccessibilityController): String? {
        val before = c.activeWindowPackage() ?: ""
        if (!c.globalAction("quick_settings")) return null
        var waited = 0
        while (c.activeWindowPackage() == before && waited < 12) { kotlinx.coroutines.delay(200); waited++ }
        c.waitForSettle(1500)
        return before
    }

    // ---------------------------------------------------------------- flashlight / torch
    /** Torch state per camera id, kept current by a TorchCallback (it fires once with the state on registration). */
    private val torchOn = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    @Volatile private var torchWatching = false

    private fun watchTorch(cm: CameraManager) {
        if (torchWatching) return
        torchWatching = true
        try {
            cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { torchOn[cameraId] = enabled }
                override fun onTorchModeUnavailable(cameraId: String) { torchOn.remove(cameraId) }
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        } catch (t: Throwable) { torchWatching = false }
    }

    private suspend fun torchState(): FastOutcome? {
        val cm = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        watchTorch(cm)
        if (torchOn.isEmpty()) kotlinx.coroutines.delay(150)   // first registration: let the initial callback land
        if (torchOn.isEmpty()) return handled(FastIntent.FlashlightQuery, "I can't tell whether the flashlight is on right now.")
        return handled(FastIntent.FlashlightQuery, if (torchOn.values.any { it }) "The flashlight is on." else "The flashlight is off.")
    }

    private fun torch(on: Boolean): FastOutcome? {
        val cm = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        val id = try {
            cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (t: Throwable) { null } ?: return action("FLASHLIGHT", "This phone doesn't have a flashlight I can reach.")
        watchTorch(cm)
        // J-13: the torch callback lands asynchronously (after the undo snapshot), so record the inverse here from the
        // state we already know; unknown ⇒ it was the opposite of what was asked.
        val was = torchOn[id] ?: !on
        return try {
            cm.setTorchMode(id, on)
            if (was != on) extraInverses.add(com.tekyerema.audit.Inverse.Torch(was))
            action(if (on) "FLASHLIGHT_ON" else "FLASHLIGHT_OFF", if (on) "Flashlight on." else "Flashlight off.")
        } catch (t: Throwable) {
            action("FLASHLIGHT", "Sorry, I couldn't switch the flashlight ${if (on) "on" else "off"}.")
        }
    }

    // ---------------------------------------------------------------- wifi / bluetooth
    private fun wifiState(): FastOutcome? = try {
        val wm = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        FastOutcome("WIFI_QUERY", if (wm.isWifiEnabled) "Wi-Fi is on." else "Wi-Fi is off.")
    } catch (t: Throwable) { null }

    /** Android 10+ forbids apps toggling Wi-Fi directly — open the system Wi-Fi panel (honest, deterministic). */
    private fun openWifiPanel(turnOn: Boolean): FastOutcome? {
        if (Build.VERSION.SDK_INT < 29) return null
        return try {
            appContext.startActivity(Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            action(if (turnOn) "WIFI_ON" else "WIFI_OFF",
                "Android won't let me change Wi-Fi directly, so I've opened the Wi-Fi controls for you.")
        } catch (t: Throwable) { null }
    }

    private fun bluetoothState(): FastOutcome? = try {
        val a = BluetoothAdapter.getDefaultAdapter() ?: return FastOutcome("BLUETOOTH_QUERY", "This phone has no Bluetooth.")
        FastOutcome("BLUETOOTH_QUERY", if (a.isEnabled) "Bluetooth is on." else "Bluetooth is off.")
    } catch (t: Throwable) { null }

    // Android 12+ refuses without BLUETOOTH_CONNECT (not requested) and 13+ refuses apps outright: the SecurityException
    // / false is caught and the Quick Settings tile does it instead.
    @Suppress("DEPRECATION")
    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun toggleBluetooth(on: Boolean): FastOutcome? {
        val a = BluetoothAdapter.getDefaultAdapter() ?: return null
        return try {
            val ok = if (on) a.enable() else a.disable()
            if (ok) action(if (on) "BLUETOOTH_ON" else "BLUETOOTH_OFF", "Turning Bluetooth ${if (on) "on" else "off"}.")
            else quickToggle(FastIntent.QuickToggle("bluetooth", on))   // Android 13+ refuses apps → its Quick Settings tile
        } catch (t: Throwable) { quickToggle(FastIntent.QuickToggle("bluetooth", on)) }
    }

    // ---------------------------------------------------------------- Quick Settings tiles (no LLM)
    /**
     * Pull down Quick Settings → find the tile by name ([QuickTiles]) → tap → VERIFY its checked state → close the
     * shade. "Already on" is only said when the tile really was. Anything unexpected (tile not on this phone's
     * panel, a tap that opens a dialog instead of flipping) returns null and the agent continues FROM THE CURRENT
     * SCREEN — never a claimed success that didn't happen.
     */
    private suspend fun quickToggle(t: FastIntent.QuickToggle): FastOutcome? {
        val tile = QuickTiles.byKey(t.key) ?: return null
        val c = controller() ?: return null
        val t0 = SystemClock.uptimeMillis()
        // openShade waits (cheaply) until the shade IS the active window, then until it stops animating: a snapshot
        // mid-animation blocks on System UI's busy UI thread (4–5 s on the emulator).
        val before = openShade(c) ?: return null
        suspend fun look(tries: Int): com.tekyerema.accessibility.UiElement? {
            repeat(tries) { i ->
                if (i > 0) { kotlinx.coroutines.delay(150); c.waitForSettle(1000) }
                val s0 = SystemClock.uptimeMillis()
                val snap = c.buildSnapshot()
                val hit = QuickTiles.find(snap, tile)
                Log.d(TAG, "[QS] look#$i snapshot=${SystemClock.uptimeMillis() - s0}ms n=${snap.elements.size} " +
                    "app=${snap.appPackage} hit=${hit?.label} at=${SystemClock.uptimeMillis() - t0}ms")
                if (hit != null) return hit
                // the panel is fully drawn (its tiles are there) and this one isn't among them → stop re-reading
                if (snap.appPackage != before && snap.elements.count { it.checkable } >= 3) return null
            }
            return null
        }
        var el = look(6)
        if (el == null) {
            // not on the first page: swipe the tile grid left once (QS pages scroll horizontally)
            val tiles = c.buildSnapshot().elements.filter { it.checkable }
            if (tiles.isNotEmpty()) {
                val y = (tiles.minOf { it.bounds.top } + tiles.maxOf { it.bounds.bottom }) / 2
                val (w, _) = c.screenSize()
                c.swipe((w * 0.85).toInt(), y, (w * 0.15).toInt(), y, 250)
                kotlinx.coroutines.delay(200); c.waitForSettle(1500)
                el = look(4)
            }
        }
        val found = el ?: run { c.globalAction("back"); return null }   // this phone's panel has no such tile → agent
        val state = if (t.on) "on" else "off"
        if (found.checked == t.on) {
            c.globalAction("back")
            return action(t.name, "${tile.spoken} is already $state.")
        }
        if (!c.clickElement(found.id)) return null
        var ok = false
        repeat(8) { if (!ok) { kotlinx.coroutines.delay(150); c.waitForSettle(1000); ok = QuickTiles.find(c.buildSnapshot(), tile)?.checked == t.on } }
        if (!ok) {
            Log.i(TAG, "[LOCAL_FAST_PATH] ${tile.key} tile didn't flip (dialog?) → agent continues from this screen")
            return null
        }
        c.globalAction("back")
        return action(t.name, "${tile.spoken} is $state.")
    }

    // ---------------------------------------------------------------- media transport keys
    private fun media(keyCode: Int, intent: FastIntent, say: String): FastOutcome? {
        val am = audio() ?: return null
        return try {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            action(intent.name, say)
        } catch (t: Throwable) { null }
    }

    // ---------------------------------------------------------------- navigation (accessibility global actions)
    private fun global(globalName: String, intent: FastIntent, say: String): FastOutcome? {
        val c = controller() ?: return null
        return if (c.globalAction(globalName)) action(intent.name, say) else null
    }

    private suspend fun scroll(down: Boolean): FastOutcome? {
        val c = controller() ?: return null
        val (w, h) = c.screenSize()
        if (w <= 0 || h <= 0) return null
        val x = w / 2
        // scroll DOWN = content moves up = swipe from lower to upper; UP = the reverse.
        val y1 = if (down) (h * 0.72).toInt() else (h * 0.28).toInt()
        val y2 = if (down) (h * 0.28).toInt() else (h * 0.72).toInt()
        val ok = try { c.swipe(x, y1, x, y2) } catch (_: Throwable) { false }
        return if (ok) action(if (down) "SCROLL_DOWN" else "SCROLL_UP", if (down) "Scrolled down." else "Scrolled up.") else null
    }

    // ---------------------------------------------------------------- open app (existing resolver)
    private suspend fun openApp(query: String): FastOutcome? {
        val match = AppResolver.resolveDetailed(appContext, query)
        val app = (match as? AppResolver.Match.One)?.app ?: return null   // ambiguous/none → let the agent ask
        val launch = AppResolver.launchIntent(appContext, app.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return null
        // Our own screen in front would keep the app BEHIND it: it steps aside first (the reply is still spoken).
        com.tekyerema.ui.OwnUi.leave()
        val prevFg = controller()?.foregroundPackage()
        try { appContext.startActivity(launch) } catch (t: Throwable) { return null }
        // Say "is open" only once the app is actually in front. Without the accessibility service we can't
        // see the foreground, so announce the attempt instead of claiming the result. If it never comes up,
        // fall through: the agent observes the real screen and either finishes the job or says why not.
        val c = controller() ?: return action("OPEN_APP", "Opening ${app.label}.")
        // Up to ~6 s: a cold start of a heavy app (Chrome, a bank app) on a low-end phone routinely takes 3–5 s,
        // and a slow launch is not a failure. Polling is cheap — foregroundPackage() reads event-tracked state.
        val (ok, fg) = com.tekyerema.verifier.Verify.pollLaunched(c, app.packageName, prevFg)
        if (!ok) {
            Log.i(TAG, "[LOCAL_FAST_PATH] OPEN_APP ${app.packageName} not in front (fg=$fg) → agent")
            return null
        }
        return action("OPEN_APP", "${app.label} is open.")
    }

    /** An information QUERY answer — spoken on the normal (Twi-capable) voice. */
    private fun handled(intent: FastIntent, text: String) = FastOutcome(intent.name, text, systemVoice = false)

    /** A deterministic ACTION confirmation — spoken on the OFFLINE system engine so it's instant, never
     *  blocked by the online Twi voice on a slow network. */
    private fun action(name: String, text: String) = FastOutcome(name, text, systemVoice = true)

    companion object {
        private const val TAG = "Tekyerema"
        // A tool's advice to the PLANNER ("— tap the top result's Play button (read_state to find it)", "Ask the user which
        // one they mean, then call install_app …") was read out to the user by the fast path (A20e, 2026-09-29).
        private val PLANNER_TALK = Regex("\\b[a-z]+_[a-z_]+\\b|\\bthe user\\b|\\buse [a-z]+_[a-z]+", RegexOption.IGNORE_CASE)

        /** [text] without the sentences / trailing clauses meant for the planner. Pure. */
        internal fun forUser(text: String): String {
            val kept = text.split(Regex("(?<=[.!?])\\s+")).mapNotNull { s ->
                val dash = s.indexOf(" — ")
                when {
                    dash > 0 && PLANNER_TALK.containsMatchIn(s.substring(dash)) && !PLANNER_TALK.containsMatchIn(s.substring(0, dash)) ->
                        s.substring(0, dash).trimEnd(',', ';', ' ') + "."
                    PLANNER_TALK.containsMatchIn(s) -> null
                    else -> s
                }
            }
            return kept.joinToString(" ").trim().ifEmpty { text }
        }
        /** Commands that must not become an undo group themselves (undoing an undo would flip-flop). */
        private val NOT_UNDOABLE: Set<FastIntent> = setOf(FastIntent.Undo, FastIntent.MemoryQuery, FastIntent.ListSchedules,
            FastIntent.ListRoutines, FastIntent.Resume)
        private const val DIGEST_PREFS = "tekyerema_digest"
        private val LAUNCHERS = setOf("com.sec.android.app.launcher", "com.google.android.apps.nexuslauncher", "com.android.launcher3")
    }
}

/**
 * The result of a locally-handled command: the intent name (for logs) + the sentence to speak.
 * [systemVoice] = speak the confirmation through the OFFLINE system TTS (instant, never network-blocked) —
 * true for deterministic ACTIONS (volume, back, flashlight…) so their confirm can't be delayed by the online
 * Twi voice; false for information QUERIES (time, battery…) whose answer stays on the normal Twi voice.
 */
data class FastOutcome(
    val intent: String,
    val spoken: String,
    val systemVoice: Boolean = false,
    /** K-03: false when the line reports a failure ("Sorry, I couldn't…") — the error cue plays, not "done". */
    val ok: Boolean = !FAILURE_LINE.containsMatchIn(spoken)
)

private val FAILURE_LINE = Regex("(?i)^(sorry\\b|i couldn't|i can't|i could not|i cannot|this phone (doesn't|does not|has no))")
