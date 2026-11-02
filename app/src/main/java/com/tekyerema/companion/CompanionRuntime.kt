package com.tekyerema.companion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.tekyerema.accessibility.AccessibilityController
import com.tekyerema.agent.AgentEngine
import com.tekyerema.agent.FastOutcome
import com.tekyerema.agent.helper.HelperPlan
import com.tekyerema.core.Privacy
import com.tekyerema.core.config.AppConfig
import com.tekyerema.memory.MemKind
import com.tekyerema.memory.UserMemory
import com.tekyerema.money.MoneyLimits
import com.tekyerema.money.TxLedger
import com.tekyerema.notifications.IndexedMessage
import com.tekyerema.notifications.MessageIndex
import com.tekyerema.notifications.NotificationDigest
import com.tekyerema.notifications.NotificationStore
import com.tekyerema.notifications.PrivateReading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * Companion mode on the phone (MASTER_STATUS #21): the Android side of [CompanionIntents] — the router hands a recognised
 * request here and gets back what to say ([handle]), or null to let the brain answer it (a chat turn about a topic).
 *
 * Everything personal stays on the phone: the brief reads the calendar, the notification index, the payment ledger and the
 * user's own memory, and builds one paragraph with no model and no network ([CompanionBrief]). The conversation memory (and so
 * the cloud brain, when it is the brain in use) is told only that a brief was read, never what was in it.
 *
 * Switches: `companion.enabled` (everything), `companion.moneyNotes` (the spending note in the brief; the user can change it
 * by voice — kept in the app's own preferences — "stop the spending notes"). Asking outright "how am I doing with money" is
 * always answered: the switch is about unasked notes.
 */
object CompanionRuntime {
    private const val TAG = "Tekyerema"
    private const val PREFS = "tekyerema_companion"
    private const val K_MONEY_NOTES = "money_notes"
    private const val DAY_MS = 86_400_000L

    fun moneyNotes(app: Context): Boolean =
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_MONEY_NOTES, AppConfig.bool("companion.moneyNotes", true))

    fun setMoneyNotes(app: Context, on: Boolean) {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_MONEY_NOTES, on).apply()
    }

    /** @return what to say, or null to let the brain handle [said] (the session is already on, so it answers as a friend). */
    suspend fun handle(app: Context, controller: AccessibilityController?, kind: String, said: String): FastOutcome? {
        val k = CompanionIntents.Kind.of(kind) ?: return null
        if (!CompanionSession.enabled()) return null
        Log.i(TAG, "[COMPANION] kind=${k.id} text='${Privacy.content(said.take(40))}'")
        return when (k) {
            CompanionIntents.Kind.CHAT -> chat(app, said)
            CompanionIntents.Kind.CHAT_TOPIC -> { CompanionSession.start(k); null }
            CompanionIntents.Kind.BRIEF -> brief(app, controller, said)
            CompanionIntents.Kind.TEACH -> teach(said)
            CompanionIntents.Kind.MONEY -> money(app, said)
            CompanionIntents.Kind.HONEST -> remembered(said, "COMPANION_HONEST", CompanionGuard.HONEST_AI, listenAfter = true)
            CompanionIntents.Kind.DISTRESS, CompanionIntents.Kind.CRISIS -> distress(k, said)
            CompanionIntents.Kind.END -> { CompanionSession.end(); remembered(said, "COMPANION_END", CompanionGuard.closing(said)) }
            CompanionIntents.Kind.NOTES_OFF -> { setMoneyNotes(app, false); FastOutcome("COMPANION_NOTES", CompanionLines.NOTES_OFF) }
            CompanionIntents.Kind.NOTES_ON -> { setMoneyNotes(app, true); FastOutcome("COMPANION_NOTES", CompanionLines.NOTES_ON) }
        }
    }

    /** An answer the brain should know was given (its next turn follows it). [kept] replaces the words in the memory when they are private. */
    private fun remembered(said: String, intent: String, line: String, listenAfter: Boolean = false, kept: String = line, keptSaid: String = said): FastOutcome {
        try { AgentEngine.noteCompanionTurn(keptSaid, kept) } catch (_: Throwable) {}
        return FastOutcome(intent, line, ok = true, listenAfter = listenAfter, acted = false)
    }

    // ---------------------------------------------------------------- chat, lessons, distress
    private fun chat(app: Context, said: String): FastOutcome {
        CompanionSession.start(CompanionIntents.Kind.CHAT)
        val bored = Regex("(?i)\\bbor").containsMatchIn(said) || Regex("(?i)nothing to do").containsMatchIn(said)
        // The opener is local, so it works with no internet. What follows needs a brain: the cloud, or the model on this phone.
        val canGoOn = com.tekyerema.core.NetworkMonitor.isOnline() || AppConfig.bool("router.localModel.enabled", false)
        val line = CompanionLines.opener(bored, (System.currentTimeMillis() / 1000L).toInt()) + if (canGoOn) "" else CompanionLines.OFFLINE_NOTE
        return remembered(said, "COMPANION_CHAT", line, listenAfter = canGoOn)
    }

    private fun teach(said: String): FastOutcome {
        CompanionSession.start(CompanionIntents.Kind.TEACH)
        val start = ((System.currentTimeMillis() / DAY_MS) % CompanionLines.LESSONS.size).toInt()
        val line = CompanionLines.lesson(CompanionSession.nextLesson(), start).text + CompanionLines.LESSON_ASK
        return remembered(said, "COMPANION_TEACH", line, listenAfter = true)
    }

    private fun distress(k: CompanionIntents.Kind, said: String): FastOutcome {
        CompanionSession.start(k)
        val crisis = k == CompanionIntents.Kind.CRISIS
        val helper = HelperPlan.currentHelper()
        val emergency = AppConfig.string("os.emergency.numbers.emergency", "112")
        // The verified Ghana Mental Health Authority helpline lives in config companion.helpline.number (+ .name). None is invented.
        val helpline = AppConfig.string("companion.helpline.number", "").ifBlank { null }
        val helplineName = AppConfig.string("companion.helpline.name", "").ifBlank { null }
        val line = CompanionGuard.reply(if (crisis) CompanionGuard.Level.CRISIS else CompanionGuard.Level.LOW, helper, emergency,
            helpline, helplineName, pick = (System.currentTimeMillis() / 1000L).toInt())
        // The words of a self-harm remark are not kept in the conversation; a marker is, so the next turn stays gentle.
        return remembered(said, "COMPANION_DISTRESS", line, listenAfter = true,
            keptSaid = if (crisis) "(said they may hurt themselves)" else said)
    }

    // ---------------------------------------------------------------- money
    private suspend fun money(app: Context, said: String): FastOutcome {
        val now = System.currentTimeMillis()
        val slice = withContext(Dispatchers.IO) { moneySlice(now) }
        val line = CompanionMoney.answer(slice, CompanionIntents.wantsWeek(said)) { MoneyLimits.cedis(it) }
        return remembered(said, "COMPANION_MONEY", line, kept = "(told the user how their spending is going; details not kept)")
    }

    /** The confirmed payments that went through Tɛkyerɛma (the ledger is the only source: nothing is read from the inbox here). */
    internal fun moneySlice(now: Long): CompanionBrief.Money {
        val paid = TxLedger.all(now).filter { it.state == TxLedger.State.DONE }
            .mapNotNull { t -> t.amount?.replace(",", "")?.trim()?.toBigDecimalOrNull()?.let { t.at to it } }
        return CompanionMoney.slice(paid, startOfDay(now), now)
    }

    private fun startOfDay(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // ---------------------------------------------------------------- the brief
    private suspend fun brief(app: Context, controller: AccessibilityController?, said: String): FastOutcome {
        val now = System.currentTimeMillis()
        val zone = TimeZone.getDefault()
        val hidePrivate = try { PrivateReading.enabled(app) && !PrivateReading.privateOutputConnected(app) } catch (_: Throwable) { false }
        val (events, total) = calendarToday(app, controller)
        val (msgs, msgsKnown) = withContext(Dispatchers.IO) { messagesSince(app, now - AppConfig.long("companion.brief.messageHours", 12L).coerceIn(1L, 72L) * 3_600_000L) }
        val chat = msgs.filter { NotificationDigest.isMessage(it, extraChats()) }
        val top = chat.mapNotNull { (it.sender ?: it.title)?.trim()?.takeIf { s -> s.isNotEmpty() } }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }
        val calls = NotificationDigest.callsFromNotifications(msgs).size
        val (liked, name) = withContext(Dispatchers.IO) {
            val facts = try {
                UserMemory.ensureLoaded(app)
                if (UserMemory.isOn(MemKind.FACT)) UserMemory.snapshot().entries.filter { it.kind == MemKind.FACT } else emptyList()
            } catch (_: Throwable) { emptyList() }
            CompanionBrief.pickLiked(facts, Calendar.getInstance().get(Calendar.DAY_OF_YEAR)) to
                (try { com.tekyerema.settings.Settings(app).preferredName.ifBlank { null } } catch (_: Throwable) { null })
        }
        val money = if (moneyNotes(app) && !hidePrivate) withContext(Dispatchers.IO) { try { moneySlice(now) } catch (_: Throwable) { null } } else null
        val line = CompanionBrief.build(CompanionBrief.Inputs(
            nowMs = now, zone = zone, name = name, events = events, eventTotal = total,
            messagesKnown = msgsKnown, messageCount = chat.size, topSender = top?.key, topSenderCount = top?.value ?: 0,
            missedCalls = calls, liked = liked, money = money, hidePrivate = hidePrivate)) { MoneyLimits.cedis(it) }
        return remembered(said, "COMPANION_BRIEF", line, kept = "(read out the user's day; details not kept)")
    }

    private fun extraChats(): Set<String> = AppConfig.stringList("notifications.digest.messagingPackages", emptyList()).toSet()

    /** Today's events, and how many there are in all; (null, 0) when the calendar can't be read. Read-only. */
    private suspend fun calendarToday(app: Context, controller: AccessibilityController?): Pair<List<CompanionBrief.Event>?, Int> {
        if (app.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null to 0
        return try {
            val tool = com.tekyerema.tools.CalendarTool
            val args = JSONObject().put("action", "today")
            // The same gate as the fast path's own calendar reading (a capability the user switched off stays off).
            if (!com.tekyerema.tools.ToolManifest.isAvailable(tool)) return null to 0
            if (com.tekyerema.policy.PolicyEngine().evaluate(tool, args).outcome != com.tekyerema.policy.PolicyOutcome.ALLOW) return null to 0
            val r = tool.execute(args, com.tekyerema.tools.ToolContext(app, controller, NotificationStore))
            if (!r.success) return null to 0
            val arr = r.data?.optJSONArray("events")
            val list = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optJSONObject(it) }
                .map { CompanionBrief.Event(it.optString("title").ifBlank { null }, it.optLong("begin"), it.optBoolean("allDay")) }
            list to (r.data?.optInt("count", list.size) ?: list.size)
        } catch (_: Throwable) { null to 0 }
    }

    /** Messages and notifications since [since], and whether notification access is on at all. Read-only (as the "what did I miss" digest). */
    private fun messagesSince(app: Context, since: Long): Pair<List<IndexedMessage>, Boolean> {
        if (!com.tekyerema.ui.Perms.notifications(app)) return emptyList<IndexedMessage>() to false
        val live = NotificationStore.recent(50).filterNot { it.ongoing }.map {
            IndexedMessage(it.postTime, it.packageName, it.appLabel, it.sender, it.title, it.text)
        }
        val indexed = if (com.tekyerema.core.config.FeatureFlags.isEnabled("H-09", true)) MessageIndex.all() else emptyList()
        val ignore = NotificationDigest.DEFAULT_IGNORE + AppConfig.stringList("notifications.digest.ignorePackages", emptyList())
        val all = (indexed + live)
            .filterNot { com.tekyerema.core.Redact.deniedPackage(it.pkg) }
            .distinctBy { Triple(it.pkg, it.title, it.text) }
            .filter { it.t > since && !NotificationDigest.isNoise(it, app.packageName, ignore) }
        return all to true
    }
}
