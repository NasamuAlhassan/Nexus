package com.tekyerema.tools

import android.content.Intent
import android.provider.MediaStore
import android.provider.Settings
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.core.config.AppConfig
import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.RiskTier
import com.tekyerema.verifier.VerificationResult
import org.json.JSONObject

/**
 * E-14 — what the phone is playing, from the active media sessions (visible to us through the notification listener;
 * null when that access is off). The wording is pure and unit-tested.
 */
object MediaSessions {
    data class Track(val pkg: String, val title: String?, val artist: String?, val playing: Boolean)

    fun current(context: android.content.Context): List<Track>? = try {
        val msm = context.getSystemService(android.media.session.MediaSessionManager::class.java)
        val listener = android.content.ComponentName(context, com.tekyerema.notifications.TekyeremaNotificationListener::class.java)
        msm?.getActiveSessions(listener)?.map { c ->
            val md = c.metadata
            Track(c.packageName, md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE),
                md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST),
                c.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING)
        }
    } catch (_: SecurityException) { null } catch (_: Throwable) { null }

    /** The playing track, from [pkg] when given. */
    fun playing(tracks: List<Track>?, pkg: String? = null): Track? =
        tracks?.firstOrNull { it.playing && (pkg == null || it.pkg == pkg) }

    /** "Blinding Lights by The Weeknd" / "Blinding Lights" / null. */
    fun words(t: Track?): String? {
        val title = t?.title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return t.artist?.trim()?.takeIf { it.isNotEmpty() }?.let { "$title by $it" } ?: title
    }
}

/**
 * §A.2 / E-14 play_music — start music, or control what's playing. Play = `ACTION_MEDIA_PLAY_FROM_SEARCH` (aimed at a
 * named app such as YouTube Music when given; an `artist` sets the artist focus so the player picks that artist's
 * songs). pause/resume/next/previous/stop = a media key through AudioManager (no permission, reaches the active
 * player). Reversible → TIER1.
 *
 * Honest about playback: YouTube Music is known to open its search from this intent WITHOUT starting it (2025
 * support thread), so after a play request the tool checks the media session; if nothing plays it presses the
 * player's own "Play"/"Shuffle" once, and otherwise says plainly that the search is open but nothing is playing yet.
 */
object PlayMusicTool : Tool {
    override val name = "play_music"
    override val description = "Play a song, an artist or any music, optionally in an app like YouTube Music; or " +
        "pause/resume/next/previous."
    override val scope = CapabilityScope.DEVICE_CONTROL
    override val riskTier = RiskTier.TIER1
    override val isAccelerator = true
    override val verifiesOutcome = true
    override val params = listOf(
        ToolParam("query", "string", "Song/album/playlist.", required = false),
        ToolParam("artist", "string", "Artist.", required = false),
        ToolParam("app", "string", "Music app.", required = false),
        ToolParam("action", "string", "Default play.", required = false,
            enumValues = listOf("play", "pause", "resume", "next", "previous", "stop"))
    )

    /** Pure (unit-tested): the spoken reply after a transport key, given what plays afterwards. */
    internal fun transportWords(action: String, now: MediaSessions.Track?, sessionsVisible: Boolean): String {
        val what = MediaSessions.words(now)
        return when (action) {
            "next" -> if (what != null) "Skipped. Now playing $what." else "Skipped to the next song."
            "previous" -> if (what != null) "Back one. Now playing $what." else "Went back to the previous song."
            "pause" -> "Paused."
            "stop" -> "Stopped."
            else -> if (what != null) "Playing $what." else if (sessionsVisible) "I pressed play, but nothing seems to be playing." else "Pressed play."
        }
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val action = args.str("action")?.trim()?.lowercase()?.ifEmpty { null } ?: "play"
        val query = args.str("query")?.trim().orEmpty()
        val artist = args.str("artist")?.trim().orEmpty()
        if (action != "play") return transport(action, ctx)
        val appName = args.str("app")?.trim()?.takeIf { it.isNotEmpty() }
        val pkg = appName?.let { n ->
            when (val m = com.tekyerema.android.AppResolver.resolveDetailed(ctx.appContext, n)) {
                is com.tekyerema.android.AppResolver.Match.One -> m.app.packageName
                else -> return ToolResult(false, "$n isn't installed on this phone — I can install it from the Play Store, or use another music app.",
                    data = JSONObject().put("notInstalled", n), recoverable = false)
            }
        }
        val search = if (artist.isNotEmpty() && query.isEmpty()) artist else listOf(query, artist).filter { it.isNotEmpty() }.joinToString(" ")
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (search.isNotEmpty()) {
                // Artist focus when only an artist was asked for ("play Sarkodie's songs"); otherwise "anything".
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS,
                    if (artist.isNotEmpty() && query.isEmpty()) MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE else "vnd.android.cursor.item/*")
                putExtra(android.app.SearchManager.QUERY, search)
                if (artist.isNotEmpty()) putExtra(MediaStore.EXTRA_MEDIA_ARTIST, artist)
                if (query.isNotEmpty()) putExtra(MediaStore.EXTRA_MEDIA_TITLE, query)
            }
            pkg?.let { setPackage(it) }
        }
        val pm = ctx.appContext.packageManager
        val launch: Intent = when {
            intent.resolveActivity(pm) != null -> intent
            // YouTube Music answers its own web search links (VIEW music.youtube.com/*) even when it ignores the play intent.
            pkg == YT_MUSIC && search.isNotEmpty() -> Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("https://music.youtube.com/search?q=" + android.net.Uri.encode(search))).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            pkg != null -> pm.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?: return ToolResult(false, "$appName can't be opened.", recoverable = true)
            else -> return ToolResult(false, "No music app on this phone can handle a play request.", recoverable = true)
        }
        try { ctx.appContext.startActivity(launch) } catch (t: Throwable) {
            return ToolResult(false, "Couldn't start music: ${t.javaClass.simpleName}", recoverable = true)
        }
        val label = appName?.let { spokenAppName(it) } ?: "your music app"
        val asked = if (search.isEmpty()) "Asked $label to play." else "Asked $label to play \"$search\"."
        if (MediaSessions.current(ctx.appContext) == null)   // no notification access → can't check; say only what we did
            return ToolResult(true, asked, JSONObject().put("checked", false), channel = "intent")
        val playing = awaitPlaying(ctx, pkg, AppConfig.long("os.media.playWaitMs", 7_000L))
            ?: pressPlayOnScreen(ctx, pkg)
        return if (playing != null) ToolResult(true, "Playing ${MediaSessions.words(playing) ?: search.ifEmpty { "music" }}" +
            (appName?.let { " on ${spokenAppName(it)}" } ?: "") + ".", JSONObject().put("playing", true).put("checked", true), channel = "intent")
        else ToolResult(false, "$label opened${if (search.isNotEmpty()) " the search for \"$search\"" else ""}, but nothing is playing yet — " +
            "tap the top result's Play button (read_state to find it).", JSONObject().put("playing", false).put("checked", true), channel = "intent")
    }

    private const val YT_MUSIC = "com.google.android.apps.youtube.music"

    private suspend fun awaitPlaying(ctx: ToolContext, pkg: String?, timeoutMs: Long): MediaSessions.Track? {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            MediaSessions.playing(MediaSessions.current(ctx.appContext), pkg)?.let { return it }
            if (System.currentTimeMillis() >= end) return null
            kotlinx.coroutines.delay(500L)
        }
    }

    /** One press of the player's own Play/Shuffle control when the search opened but didn't start (reversible). */
    private suspend fun pressPlayOnScreen(ctx: ToolContext, pkg: String?): MediaSessions.Track? {
        val ctrl = ctx.controller ?: return null
        val snap = try { ctrl.buildSnapshot() } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Throwable) { return null }
        if (pkg != null && snap.appPackage != pkg) return null
        val play = AppUi.preferClickable(AppUi.exact(snap, "play", "shuffle", "play all", "shuffle play")) ?: return null
        ctrl.clickElement(play.id)
        return awaitPlaying(ctx, pkg, 4_000L)
    }

    /** A media key to whatever is playing, then (when the sessions are visible) say what plays now. */
    private suspend fun transport(action: String, ctx: ToolContext): ToolResult {
        val code = when (action) {
            "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
            "resume", "play" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
            "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> android.view.KeyEvent.KEYCODE_MEDIA_STOP
            else -> return ToolResult(false, "I can play, pause, resume, skip to the next or go back to the previous song.")
        }
        val am = ctx.appContext.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
            ?: return ToolResult(false, "The phone's audio service isn't available.", recoverable = false)
        val before = MediaSessions.current(ctx.appContext)
        // With no player session there is nothing to skip or pause. "Resume" still goes out: Android hands a play key
        // to the last player the user had, even after its session ended.
        if (before != null && before.isEmpty() && action !in setOf("resume", "play"))
            return ToolResult(false, "Nothing is playing right now — say what you'd like to hear.", recoverable = true)
        try {
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
        } catch (t: Throwable) {
            return ToolResult(false, "Couldn't send the $action key: ${t.javaClass.simpleName}", recoverable = true)
        }
        val wasTitle = MediaSessions.words(MediaSessions.playing(before))
        var now: MediaSessions.Track? = null
        if (before != null && action in setOf("next", "previous", "resume", "play")) {
            // Wait for the player to report the new song (or to start), bounded.
            val end = System.currentTimeMillis() + 3_000L
            while (System.currentTimeMillis() < end) {
                now = MediaSessions.playing(MediaSessions.current(ctx.appContext))
                if (now != null && (action == "resume" || action == "play" || MediaSessions.words(now) != wasTitle)) break
                kotlinx.coroutines.delay(300L)
            }
        }
        return ToolResult(true, transportWords(action, now, before != null), JSONObject().put("action", action), channel = "native")
    }

    override suspend fun verify(args: JSONObject, ctx: ToolContext, before: UiSnapshot?, after: UiSnapshot?, result: ToolResult): VerificationResult {
        val d = result.data
        // Verified only when a media session actually reported playback (or a key was delivered to a player).
        val ok = result.success && d != null && (d.optBoolean("playing") || d.has("action"))
        return VerificationResult(ok, result.message)
    }
}

/**
 * §A.2 open_setting — Android 11 does NOT let an ordinary app flip Wi-Fi / mobile data / Bluetooth /
 * airplane mode directly (system permission), so we do the honest thing: OPEN the relevant settings screen
 * (a Settings Panel where the platform offers one) and tell the user to toggle it there. We never claim to
 * have flipped a switch we cannot flip.
 */
object OpenSettingTool : Tool {
    override val name = "open_setting"
    override val description = "Open a system setting screen (wifi, mobile data, bluetooth, airplane, location, sound, display). " +
        "Pass state=on or state=off to have its switch set to that position; without state the screen is only opened."
    override val scope = CapabilityScope.DEVICE_CONTROL
    override val riskTier = RiskTier.TIER1
    override val isAccelerator = true
    override val params = listOf(
        ToolParam("target", "string", "Which setting: wifi | mobile_data | bluetooth | airplane | location | nfc | volume | sound | " +
            "display | battery | accessibility | apps | storage | date | language | security | dnd | settings."),
        ToolParam("state", "string", "The position to leave the switch in. Omit to only open the screen.", required = false,
            enumValues = listOf("on", "off"))
    )

    private fun norm(target: String) = target.trim().lowercase().replace(" ", "_")

    /** Map a target to (intent action, spoken label). Panel actions land on a togglable surface where they exist.
     *  E-11: `os.settings.map` in config can add targets ({"target": {"action": "...", "label": "..."}}). */
    private fun actionFor(target: String): Pair<String, String>? {
        com.tekyerema.core.config.AppConfig.obj("os.settings.map.${norm(target)}")?.let { o ->
            val a = o.optString("action"); if (a.isNotBlank()) return a to o.optString("label", target)
        }
        return when (norm(target)) {
            "wifi", "wi-fi", "wi_fi" -> Settings.Panel.ACTION_WIFI to "Wi-Fi"
            "mobile_data", "data", "internet", "cellular" -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY to "mobile data"
            "airplane", "flight", "aeroplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS to "airplane mode"
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS to "Bluetooth"
            "location", "gps" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS to "location"
            "nfc" -> Settings.Panel.ACTION_NFC to "NFC"                                   // E-10 panel
            "volume", "volume_panel" -> Settings.Panel.ACTION_VOLUME to "volume"          // E-10 panel
            "sound", "silent", "ringtone" -> Settings.ACTION_SOUND_SETTINGS to "sound"
            "display", "brightness", "screen" -> Settings.ACTION_DISPLAY_SETTINGS to "display"
            "battery", "battery_saver", "power_saving" -> Settings.ACTION_BATTERY_SAVER_SETTINGS to "battery saver"
            "accessibility", "talkback" -> Settings.ACTION_ACCESSIBILITY_SETTINGS to "accessibility"
            "apps", "applications" -> Settings.ACTION_APPLICATION_SETTINGS to "apps"
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS to "storage"
            "date", "time", "date_time" -> Settings.ACTION_DATE_SETTINGS to "date and time"
            "language", "languages" -> Settings.ACTION_LOCALE_SETTINGS to "language"
            "security", "lock", "screen_lock" -> Settings.ACTION_SECURITY_SETTINGS to "security"
            "dnd", "do_not_disturb" -> Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS to "Do Not Disturb"
            "settings" -> Settings.ACTION_SETTINGS to "Settings"
            else -> null
        }
    }

    /** Whole-word label patterns for each target's switch ("wi" alone matched "Switch", "Window"…). */
    internal fun labelPattern(target: String): Regex? = when (norm(target)) {
        "wifi", "wi-fi", "wi_fi" -> Regex("(?i)\\b(wi-?fi|wlan)\\b")
        "mobile_data", "data", "internet", "cellular" -> Regex("(?i)\\b(mobile data|data|cellular data)\\b")
        "airplane", "flight", "aeroplane" -> Regex("(?i)\\b(airplane|aeroplane|flight) mode\\b|\\bairplane\\b")
        "bluetooth" -> Regex("(?i)\\bbluetooth\\b")
        "location", "gps" -> Regex("(?i)\\b(location|use location|gps)\\b")
        else -> null
    }

    /** E-10: the switch whose label names the target — never "the first checkable thing on the screen". */
    internal fun findToggle(snap: UiSnapshot, target: String): com.tekyerema.accessibility.UiElement? {
        val re = labelPattern(target) ?: return null
        return snap.elements.firstOrNull { it.checkable && it.label?.let(re::containsMatchIn) == true }
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val target = args.str("target")?.trim().orEmpty()
        val want: Boolean? = when (args.str("state")?.trim()?.lowercase()) { "on" -> true; "off" -> false; else -> null }
        val (action, label) = actionFor(target)
            ?: return ToolResult(false, "I don't know which setting '$target' is.", recoverable = true)
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(ctx.appContext.packageManager) == null)
            return ToolResult(false, "This phone doesn't expose a $label setting screen I can open.", recoverable = true)
        try { ctx.appContext.startActivity(intent) } catch (t: Throwable) {
            return ToolResult(false, "Couldn't open $label settings: ${t.message}", recoverable = true)
        }
        // §P2.2 — the API forbids an app from flipping these directly, but our ACCESSIBILITY service can find and
        // tap the switch like a person would. It only taps when a direction was asked for AND the switch isn't
        // already there, then VERIFIES the checked state — reporting honestly if it didn't change.
        val ctrl = ctx.controller
            ?: return ToolResult(true, "Opened $label settings — screen control is off, so please use the switch there.",
                data = JSONObject().put("confirmed", false), channel = "intent")
        ctrl.waitForSettle(1500)
        val before = ctrl.buildSnapshot()
        val toggle = findToggle(before, target)
        if (want == null) {
            val now = toggle?.checked?.let { if (it) " It's on." else " It's off." } ?: ""
            return ToolResult(true, "Opened $label settings.$now", data = JSONObject().put("confirmed", true), channel = "intent")
        }
        if (toggle == null)
            return ToolResult(true, "Opened $label settings — I couldn't find its switch, so please set it there.",
                data = JSONObject().put("confirmed", false), channel = "intent", recoverable = true)
        val word = if (want) "on" else "off"
        if (toggle.checked == want)
            return ToolResult(true, "$label is already $word.", data = JSONObject().put("confirmed", true), channel = "a11y")
        val tapped = ctrl.clickElement(toggle.id)
        ctrl.waitForSettle(900)
        val after = ctrl.buildSnapshot()
        val nowChecked = after.elements.firstOrNull { it.checkable && it.label == toggle.label }?.checked
            ?: findToggle(after, target)?.checked
        return if (tapped && nowChecked == want)
            ToolResult(true, "Turned $label $word.", data = JSONObject().put("confirmed", true), channel = "a11y")
        else
            ToolResult(true, "Opened $label settings — I tried to turn it $word but couldn't confirm it, so please check it.",
                data = JSONObject().put("confirmed", false), channel = "a11y", recoverable = true)
    }

    override suspend fun verify(
        args: JSONObject, ctx: ToolContext, before: UiSnapshot?, after: UiSnapshot?, result: ToolResult
    ): VerificationResult = VerificationResult(result.success && result.data?.optBoolean("confirmed", false) == true, result.message)
}
