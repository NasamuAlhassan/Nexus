package com.tekyerema.mcp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tekyerema.agent.AgentEngine
import com.tekyerema.audit.AuditLog
import com.tekyerema.operator.OperatorPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The computer connection's lifecycle (research note §3.4 item 3, "Remote-connection control"):
 *  - OFF by default. It runs only while [OperatorPolicy.remoteControlEnabled] is on (Workstream C's settings toggle; the
 *    debug receiver's `--es mcp on|off` for testing) — checked every second.
 *  - While on: a persistent notification says so, with a "Turn off" button; the pairing code is spoken and shown when a
 *    computer connects; "Your computer is connected" is spoken; every remote goal is spoken before it runs.
 *  - It turns itself OFF after [idleOffMs] without a request, and when the phone's user stops a task the computer
 *    started (the kill switch cuts the computer off too).
 *  - Every request goes to the call log, and the ones that start, stop or reveal something to the user's audit
 *    ([RemoteAudit]).
 *  - Remote tasks are read-only unless [actionsAllowed].
 */
object RemoteControl {
    /**
     * "Let my computer ask for actions". Off by default: a remote task may only read. Even when on, every step that
     * changes anything is confirmed on the phone ([RemoteGate.floor]). Not persisted (it resets with the process, like
     * the connection switch); Workstream C surfaces it beside [OperatorPolicy.remoteControlEnabled].
     */
    @Volatile var actionsAllowed: Boolean = false

    fun idleOffMs(): Long = com.tekyerema.core.config.AppConfig.long("mcp.idleOffMs", 10 * 60_000L).coerceIn(60_000L, 3_600_000L)

    private const val TAG = "Tekyerema"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pairing = Pairing()
    private val callLog = RemoteAudit.CallLog()
    private val lastStatus = HashMap<String, String>()
    private val tracker = RemoteTaskTracker(redact = { kind, text ->
        if (com.tekyerema.core.Privacy.redactTraces()) com.tekyerema.core.TraceRedact.line(kind, text, AgentEngine.UNTRUSTED_MARK) else text
    })
    @Volatile private var app: Context? = null
    @Volatile private var server: McpServer? = null
    @Volatile private var offReason: String? = null
    @Volatile private var auditTask = 0L
    @Volatile private var lastRefusedAppAudit = 0L
    private var started = false

    val isOn: Boolean get() = server?.isRunning == true

    @Synchronized fun init(ctx: Context) {
        if (started) return
        started = true
        app = ctx.applicationContext
        // Every app start begins with the computer connection OFF. (merge C+D: C persists the switch, D assumed it reset with
        // the process — without this, a connection left on came back by itself after a restart, until the idle timer.)
        OperatorPolicy.remoteControlEnabled = false
        try {
            ContextCompat.registerReceiver(ctx.applicationContext, object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) { if (i.action == RemoteNotice.ACTION_OFF) turnOff("I've turned off the computer connection.") }
            }, IntentFilter(RemoteNotice.ACTION_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (_: Throwable) {}
        scope.launch {
            while (isActive) {
                try { tick() } catch (t: Throwable) { Log.w(TAG, "[MCP] ${t.javaClass.simpleName}") }
                delay(1_000L)
            }
        }
        // Remote tasks are followed through the engine's own state; a task the phone's user killed disconnects the computer.
        scope.launch {
            AgentEngine.state.collect { st ->
                val f = try { synchronized(tracker) { tracker.onState(st) } } catch (_: Throwable) { null }
                if (f != null && f.killedOnPhone && isOn) turnOff("You stopped your computer's task, so I've disconnected the computer too.")
            }
        }
    }

    /** Turn the connection off (the notification's button, the kill switch, the idle timer). */
    fun turnOff(reason: String) {
        offReason = reason
        OperatorPolicy.remoteControlEnabled = false
    }

    /** For the debug receiver: one line about the connection (no code, no content). */
    fun status(): String = "on=$isOn wanted=${OperatorPolicy.remoteControlEnabled} actions=$actionsAllowed codeActive=${pairing.active()} " +
        "calls=${callLog.size()} socket=localabstract:${McpServer.SOCKET_NAME}"

    private fun tick() {
        val want = OperatorPolicy.remoteControlEnabled
        val s = server
        when {
            want && s == null -> startServer()
            !want && s != null -> stopServer(offReason ?: "The computer connection is off.")
            s != null && System.currentTimeMillis() - s.lastActivityMs > idleOffMs() -> {
                audit(RemoteGate.origin(null), AuditLog.DONE, RemoteAudit.DISCONNECTED_IDLE)
                turnOff("I turned off the computer connection because it wasn't used for ${idleOffMs() / 60_000} minutes.")
            }
        }
    }

    private fun startServer() {
        val ctx = app ?: return
        val srv = McpServer(pairing, EngineBackend(tracker, callLog, ::say), events, com.tekyerema.BuildConfig.VERSION_NAME)
        if (!srv.start()) {
            OperatorPolicy.remoteControlEnabled = false
            say("I couldn't turn on the computer connection.")
            return
        }
        server = srv
        offReason = null
        auditTask = System.currentTimeMillis()
        RemoteNotice.show(ctx, null)
        say("Computer connection is on. A computer you've allowed for USB debugging can ask me for tasks — I'll say a code when it connects.")
    }

    private fun stopServer(reason: String) {
        server?.stop()
        server = null
        // The computer can no longer follow or stop its task: a task it started doesn't outlive the connection.
        try { if (AgentEngine.isBusy && RemoteGate.isRemote(AgentEngine.state.value.origin)) AgentEngine.stop() } catch (_: Throwable) {}
        pairing.reset()
        synchronized(lastStatus) { lastStatus.clear() }
        offReason = null
        app?.let { RemoteNotice.hide(it) }
        say(reason)
    }

    private val events = object : McpServer.Events {
        override fun announceCode(code: String) {
            app?.let { RemoteNotice.show(it, code) }
            val c = Pairing.spoken(code)
            say("A computer wants to connect to Nexus. The code is $c. Again: $c.")
        }

        override fun connected(client: String) {
            audit(RemoteGate.origin(client), AuditLog.DONE, RemoteAudit.CONNECTED)
            app?.let { RemoteNotice.show(it, null, connected = true) }
            say("Your computer is connected.")
        }

        override fun refused(why: String) {
            val now = System.currentTimeMillis()
            // An app on the phone knocking on the socket in a loop must not flood the audit.
            if (why == RemoteAudit.REFUSED_APP && now - lastRefusedAppAudit < 60_000L) return
            if (why == RemoteAudit.REFUSED_APP) lastRefusedAppAudit = now
            audit("remote", AuditLog.BLOCKED, why)
            if (why == RemoteAudit.REFUSED_CODE) say("I refused a computer that gave the wrong code.")
        }

        override fun call(c: RemoteCall) {
            callLog.add(c)
            val e = synchronized(lastStatus) { RemoteAudit.entry(c, lastStatus) } ?: return
            audit(RemoteGate.origin(c.client), e.first, e.second)
        }
    }

    private fun audit(source: String, kind: String, text: String) {
        try { AuditLog.record(source, kind, text, auditTask.takeIf { it != 0L } ?: System.currentTimeMillis()) } catch (_: Throwable) {}
    }

    private fun say(text: String) {
        Log.i(TAG, "[MCP] announce (${text.length} chars)")
        try { com.tekyerema.voice.VoiceEngine.announce(text) } catch (_: Throwable) {}
    }
}

/** The ongoing "computer connection is on" notification, with the pairing code while one is being offered. */
internal object RemoteNotice {
    private const val CHANNEL = "tekyerema_remote"
    private const val ID = 4210
    const val ACTION_OFF = "com.tekyerema.action.REMOTE_OFF"

    fun show(ctx: Context, code: String?, connected: Boolean = false) {
        try {
            val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26 && mgr.getNotificationChannel(CHANNEL) == null)
                mgr.createNotificationChannel(NotificationChannel(CHANNEL, "Computer connection", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Shows when a computer can ask Nexus for tasks, and the pairing code."
                    setShowBadge(false)
                })
            val off = PendingIntent.getBroadcast(ctx, 7, Intent(ACTION_OFF).setPackage(ctx.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val text = when {
                code != null -> "Pairing code: ${Pairing.spoken(code)}. Only type it on your own computer."
                connected -> "A computer is connected. It can ask for tasks; anything that changes something needs your OK here."
                else -> "A computer you allowed for USB debugging can ask me for tasks."
            }
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setContentTitle("Computer connection is on")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(ctx.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .setOnlyAlertOnce(code == null)
                // On the lock screen: that the connection is on, never the code.
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(NotificationCompat.Builder(ctx, CHANNEL).setContentTitle("Computer connection is on")
                    .setSmallIcon(ctx.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info).build())
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Turn off", off)
                .build()
            mgr.notify(ID, n)
        } catch (_: Throwable) {}
    }

    fun hide(ctx: Context) {
        try { ctx.getSystemService(NotificationManager::class.java)?.cancel(ID) } catch (_: Throwable) {}
    }
}
