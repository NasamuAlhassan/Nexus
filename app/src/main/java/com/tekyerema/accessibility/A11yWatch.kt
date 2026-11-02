package com.tekyerema.accessibility

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.tekyerema.core.config.AppConfig
import kotlinx.coroutines.launch

/**
 * Is Tɛkyerɛma's accessibility service on? (owner, 28 Oct 2026). Without it the app cannot read or drive the screen, and it cannot
 * notice that itself — so the check runs from the wake-word service ([tick], every ~10 min while Android lets background work run;
 * Doze may delay it) and when the screen comes on or the app opens ([check]).
 *
 * Off → one spoken question ("Would you like me to help you turn it on?"), and the next thing the user says is the answer
 * ([answer], called before routing). Yes → Android's Accessibility settings open and the service is watched for ~5 min, and said
 * when it is on. No → not asked again for a day (`a11y.watch.declineMs`). Tɛkyerɛma's service and TalkBack are separate and are
 * reported separately ("is TalkBack on?").
 */
object A11yWatch {
    private const val TAG = "Tekyerema"
    const val ASK = "Nexus screen control is turned off. Would you like me to help you turn it on?"
    const val OPENING = "Opening Accessibility settings. Find Nexus screen control in the list, open it, and switch it on. I'll tell you when it's on."
    const val DECLINED = "Okay. I won't ask again today. You can say: turn on Nexus screen control, any time."
    const val NOW_ON = "Nexus screen control is on now."

    private val TALKBACK = listOf("com.google.android.marvin.talkback", "com.samsung.android.accessibility.talkback", "com.android.talkback")

    @Volatile private var lastCheckAt = 0L
    @Volatile private var askedAt = 0L
    @Volatile private var declinedUntil = 0L
    @Volatile private var watchUntil = 0L

    private fun enabledList(ctx: Context): String =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()

    /** Our own service is switched on in Settings. */
    fun ourEnabled(ctx: Context): Boolean = isOurs(enabledList(ctx), ctx.packageName)

    /** TalkBack (Google's or Samsung's) is switched on. */
    fun talkBackEnabled(ctx: Context): Boolean = isTalkBack(enabledList(ctx))

    internal fun isOurs(list: String, pkg: String): Boolean =
        list.split(':').any { it.trim().startsWith("$pkg/") }

    internal fun isTalkBack(list: String): Boolean =
        list.split(':').any { s -> TALKBACK.any { s.trim().startsWith("$it/") } }

    /** From the wake service's watchdog (every few seconds): does the real check at most every `a11y.watch.everyMs`. */
    fun tick(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now < watchUntil) { followUp(ctx); return }
        if (now - lastCheckAt < AppConfig.long("a11y.watch.everyMs", 10 * 60_000L)) return
        check(ctx, "timer")
    }

    /** Check now (screen on, app opened, the timer). Asks only when off, not declined, and not asked in the last 30 min. */
    fun check(ctx: Context, why: String) {
        if (!AppConfig.bool("a11y.watch.enabled", true)) return
        val now = System.currentTimeMillis()
        lastCheckAt = now
        if (ourEnabled(ctx)) return
        Log.i(TAG, "[A11Y] Tɛkyerɛma's accessibility service is off ($why); talkback=${talkBackEnabled(ctx)}")
        if (now < declinedUntil || now - askedAt < AppConfig.long("a11y.watch.reaskMs", 30 * 60_000L)) return
        askedAt = now
        say(ASK, listenAfter = true)
    }

    /**
     * The words the user just said, when they are about this: the answer to [ASK] (within a minute of asking), or "turn on
     * Tɛkyerɛma accessibility", "is TalkBack on", "is Tɛkyerɛma accessibility on". The line to say, or null when not about this.
     */
    fun answer(ctx: Context, text: String): String? {
        val t = text.lowercase().trim().trimEnd('.', '!', '?')
        val now = System.currentTimeMillis()
        if (askedAt > 0 && now - askedAt < 60_000L) {
            when (com.tekyerema.voice.Confirmation.interpret(text)) {
                com.tekyerema.voice.Confirmation.Answer.YES -> { askedAt = 0; return openSettings(ctx) }
                com.tekyerema.voice.Confirmation.Answer.NO -> {
                    askedAt = 0; declinedUntil = now + AppConfig.long("a11y.watch.declineMs", 24 * 3_600_000L); return DECLINED
                }
                else -> {}
            }
        }
        if (Regex("\\b(?:turn on|switch on|enable|activate)\\b.*\\b(?:t[eɛ]ky[eɛ]r[eɛ]ma|tekyerema|nexus|my) (?:accessibility|screen control)\\b").containsMatchIn(t))
            return if (ourEnabled(ctx)) NOW_ON.replace("now", "already") else openSettings(ctx)
        if (Regex("\\b(?:is|are)\\b.*\\btalk ?back\\b.*\\b(?:on|off|enabled|running)\\b").containsMatchIn(t))
            return if (talkBackEnabled(ctx)) "TalkBack is on." else "TalkBack is off."
        if (Regex("\\b(?:is|are)\\b.*\\b(?:t[eɛ]ky[eɛ]r[eɛ]ma|tekyerema|nexus|your) (?:accessibility|screen control)\\b.*\\b(?:on|off|enabled|running)\\b").containsMatchIn(t))
            return (if (ourEnabled(ctx)) "Nexus screen control is on." else "Nexus screen control is off.") +
                " TalkBack is ${if (talkBackEnabled(ctx)) "on" else "off"}."
        return null
    }

    private fun openSettings(ctx: Context): String {
        try { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
        watchUntil = System.currentTimeMillis() + AppConfig.long("a11y.watch.followMs", 5 * 60_000L)
        return OPENING
    }

    /** After opening Settings: said once the service is on. */
    private fun followUp(ctx: Context) {
        if (!ourEnabled(ctx)) return
        watchUntil = 0
        Log.i(TAG, "[A11Y] Tɛkyerɛma's accessibility service is on again")
        say(NOW_ON, listenAfter = false)
    }

    private fun say(line: String, listenAfter: Boolean) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                try { com.tekyerema.voice.VoiceEngine.speakIn(line, com.tekyerema.voice.VoiceEngine.language()) } catch (_: Throwable) {}
                if (listenAfter) try { com.tekyerema.voice.VoiceEngine.start() } catch (_: Throwable) {}
            }
        }
    }
}
