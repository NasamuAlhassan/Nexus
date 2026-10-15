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

    /** How far [pkg]'s player is into its track, in ms (carried forward to now while playing), or null. */
    fun positionMs(context: android.content.Context, pkg: String): Long? = try {
        val msm = context.getSystemService(android.media.session.MediaSessionManager::class.java)
        val listener = android.content.ComponentName(context, com.tekyerema.notifications.TekyeremaNotificationListener::class.java)
        msm?.getActiveSessions(listener)?.firstOrNull { it.packageName == pkg }?.playbackState?.takeIf { it.position >= 0 }?.let { st ->
            if (st.state != android.media.session.PlaybackState.STATE_PLAYING) st.position
            else st.position + ((android.os.SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
    } catch (_: SecurityException) { null } catch (_: Throwable) { null }

    /** The same track started again from the top: [before] well into it, [now] back near the start. Pure. */
    fun restarted(before: Long?, now: Long?): Boolean =
        before != null && now != null && before >= 5_000L && now < before - 3_000L && now < 20_000L

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
            enumValues = listOf("play", "pause", "resume", "next", "previous", "stop")),
        ToolParam("collection", "string", "The user's own music.", required = false, enumValues = listOf("downloads", "liked", "library"))
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
        args.str("collection")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { c ->
            val named = args.str("app")?.trim()?.lowercase().orEmpty()
            if (named.isNotEmpty() && !Regex("youtube|you tube|yt").containsMatchIn(named))
                return ToolResult(false, "I can only play your own downloads, likes or library in YouTube Music for now, not $named.", recoverable = false)
            return playCollection(c, ctx)
        }
        val appName = args.str("app")?.trim()?.takeIf { it.isNotEmpty() }
        // Owner, 15 Oct 2026: the songs on the phone first — played here, no app opened (live/LocalMusic); a music app only
        // when nothing here matches; with several music apps and no favourite yet, ask which one (and remember it).
        var chosenPkg: String? = args.str("package")?.trim()?.takeIf { it.isNotEmpty() }
        if (appName == null && chosenPkg == null && com.tekyerema.core.config.AppConfig.bool("music.localFirst", true)) {
            val local = com.tekyerema.live.LocalMusic.match(com.tekyerema.live.LocalMusic.songs(ctx.appContext), query, artist)
            if (local.isNotEmpty()) {
                val said = com.tekyerema.live.LocalMusic.play(ctx.appContext, local)
                return ToolResult(true, said, channel = "native", data = JSONObject().put("local", true))
            }
            val installed = com.tekyerema.live.MusicApps.installed(ctx.appContext)
            val fav = com.tekyerema.live.MusicApps.preferred(ctx.appContext)?.takeIf { p -> installed.any { it.first == p } }
            chosenPkg = fav ?: installed.singleOrNull()?.first
            if (chosenPkg == null && installed.size > 1) {
                com.tekyerema.live.MusicApps.pending = com.tekyerema.live.MusicApps.Pending(query, artist, installed)
                return ToolResult(false, com.tekyerema.live.MusicApps.question(installed), needsDisambiguation = true,
                    data = JSONObject().put("ask", com.tekyerema.live.MusicApps.question(installed)), recoverable = false)
            }
        }
        val pkg = chosenPkg ?: appName?.let { n ->
            when (val m = com.tekyerema.android.AppResolver.resolveDetailed(ctx.appContext, n)) {
                is com.tekyerema.android.AppResolver.Match.One -> m.app.packageName
                else -> return ToolResult(false, "$n isn't installed on this phone — I can install it from the Play Store, or use another music app.",
                    data = JSONObject().put("notInstalled", n), recoverable = false)
            }
        }
        launchRefusal(pkg, appName?.let { spokenAppName(it) } ?: "that app")?.let { return ToolResult(false, it, recoverable = false) }   // D8
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
        val playing = awaitPlaying(ctx, pkg, AppConfig.long("os.media.playWaitMs", 12_000L))
            ?: pressPlayOnScreen(ctx, pkg)
        return if (playing != null) ToolResult(true, "Playing ${MediaSessions.words(playing) ?: search.ifEmpty { "music" }}" +
            (appName?.let { " on ${spokenAppName(it)}" } ?: "") + ".", JSONObject().put("playing", true).put("checked", true), channel = "intent")
        else ToolResult(false, "$label opened${if (search.isNotEmpty()) " the search for \"$search\"" else ""}, but nothing is playing yet — " +
            "tap the top result's Play button (read_state to find it).", JSONObject().put("playing", false).put("checked", true), channel = "intent")
    }

    private const val YT_MUSIC = "com.google.android.apps.youtube.music"

    /**
     * "Play my downloads" / "my liked songs" / "my library": the user's OWN music, not a search. The owner's "play my
     * downloads on YouTube Music" (A20e, 2026-09-29) was searched as the words "my downloads" and played a stranger's
     * song. YouTube Music only: liked songs open from their own link (list=LM) and Play is pressed; downloads have no
     * link, so the app is walked — full-screen player minimised, Library tab, the Downloads filter, Shuffle all. It never
     * touches anything else on those pages (the "Smart downloads — Turn on" card included). Success = the player's own
     * session reports playing.
     */
    private suspend fun playCollection(collection: String, ctx: ToolContext): ToolResult {
        val label = when (collection) { "liked" -> "your liked songs"; "library" -> "your library"; else -> "your downloads" }
        if (!isInstalled(ctx.appContext, YT_MUSIC))
            return ToolResult(false, "I can play $label only in YouTube Music, and it isn't on this phone.", recoverable = false)
        // D8 / J-11: this walks YouTube Music's own screens, so a "never touch" or "guide only" rule there stops it.
        launchRefusal(YT_MUSIC, "YouTube Music")?.let { return ToolResult(false, it, recoverable = false) }
        driveRefusal(YT_MUSIC)?.let { return ToolResult(false, "I can't play $label for you: YouTube Music — $it.", recoverable = false) }
        val ctrl = ctx.controller ?: return ToolResult(false, NO_SERVICE, recoverable = false)
        val wait = AppConfig.long("os.media.collectionWaitMs", 12_000L)
        fun inApp(s: UiSnapshot) = s.appPackage == YT_MUSIC
        val t0 = System.currentTimeMillis()
        fun stage(what: String) = android.util.Log.i("Tekyerema", "[MUSIC] $collection: $what at ${System.currentTimeMillis() - t0} ms")
        // What was already playing (maybe a search from a minute ago): only a DIFFERENT song, or a start from silence,
        // proves the press worked.
        val before = MediaSessions.playing(MediaSessions.current(ctx.appContext), YT_MUSIC)
        // …or the SAME song from the top: the list's first song was the one already playing, and "Play all" restarted it
        // (A20e 2026-09-30: music played, but the tool said nothing was playing).
        val beforeAt = before?.let { MediaSessions.positionMs(ctx.appContext, YT_MUSIC) }
        if (collection == "liked") {
            try {
                ctx.appContext.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://music.youtube.com/playlist?list=LM"))
                    .setPackage(YT_MUSIC).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (t: Throwable) { return ToolResult(false, "Couldn't open YouTube Music: ${t.javaClass.simpleName}", recoverable = true) }
            val page = awaitScreen(ctrl, wait) { s -> inApp(s) && AppUi.has(s, "play") && AppUi.anyLabel(s, Regex("(?i)^liked music$")) }
                ?: return ToolResult(false, "YouTube Music didn't open your liked songs.", recoverable = true, channel = "intent")
            AppUi.preferClickable(AppUi.exact(page, "play"))?.let { ctrl.clickElement(it.id) }
        } else {
            val launch = ctx.appContext.packageManager.getLaunchIntentForPackage(YT_MUSIC)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?: return ToolResult(false, "YouTube Music can't be opened.", recoverable = false)
            try { ctx.appContext.startActivity(launch) } catch (t: Throwable) {
                return ToolResult(false, "Couldn't open YouTube Music: ${t.javaClass.simpleName}", recoverable = true)
            }
            val onLibrary: (UiSnapshot) -> Boolean = { x -> inApp(x) && x.elements.any { e -> e.label?.startsWith("Show downloads", true) == true } }
            val settled: (UiSnapshot) -> Boolean = { x -> inApp(x) && (onLibrary(x) || libraryTab(x) != null || AppUi.has(x, "minimise", "minimize")) }
            var s = awaitScreen(ctrl, wait, accept = settled) ?: awaitScreen(ctrl, 500L) { inApp(it) }
                ?: return ToolResult(false, "YouTube Music didn't open.", recoverable = true)
            stage("opened (library=${onLibrary(s)}, tab=${libraryTab(s) != null})")
            // The full-screen player covers the tabs: put it away first.
            AppUi.preferClickable(AppUi.exact(s, "minimise", "minimize"))?.let { m ->
                ctrl.clickElement(m.id)
                s = awaitScreen(ctrl, 4_000L) { x -> inApp(x) && (onLibrary(x) || libraryTab(x) != null) } ?: s
            }
            if (!onLibrary(s)) {
                val tab = libraryTab(s) ?: return ToolResult(false, "I couldn't find the Library tab in YouTube Music.", recoverable = true, channel = "a11y")
                pressElement(ctrl, tab, s) { awaitScreen(ctrl, 4_000L) { onLibrary(it) } != null }
                s = awaitScreen(ctrl, wait) { onLibrary(it) }
                    ?: return ToolResult(false, "YouTube Music's Library didn't open.", recoverable = true, channel = "a11y")
                stage("library tab")
            }
            if (collection == "downloads") {
                val chip = s.elements.firstOrNull { it.clickable && it.label?.startsWith("Show downloads", true) == true }
                if (chip != null && chip.label?.contains("selected", true) != true) {
                    ctrl.clickElement(chip.id)
                    stage("downloads filter")
                }
                // The list loads a moment after the page (A20e: the page was read before it appeared and the tool said
                // there were no downloads while "Downloaded songs · 3 songs" sat on the screen).
                s = awaitScreen(ctrl, wait) { x -> AppUi.has(x, "downloaded songs") } ?: s
                // "Downloaded songs" is the app's own playlist of exactly the downloads: open it and play THAT, so
                // nothing that merely sits in the library (or was recently played) can start instead.
                val list = AppUi.exact(s, "downloaded songs").firstOrNull()
                    ?: return ToolResult(false, "I opened your downloads in YouTube Music, but there's no Downloaded songs list — there may be nothing downloaded yet.",
                        recoverable = false, channel = "a11y")
                pressElement(ctrl, list, s) { awaitScreen(ctrl, 4_000L) { x -> inApp(x) && !onLibrary(x) } != null }
                s = awaitScreen(ctrl, wait) { x -> inApp(x) && !onLibrary(x) && AppUi.has(x, "shuffle", "play", "shuffle all", "play all") }
                    ?: return ToolResult(false, "YouTube Music didn't open your Downloaded songs list.", recoverable = true, channel = "a11y")
                stage("downloaded songs list")
            }
            val go = AppUi.preferClickable(AppUi.exact(s, "shuffle", "shuffle all")) ?: AppUi.preferClickable(AppUi.exact(s, "play", "play all"))
                ?: return ToolResult(false, "I opened $label in YouTube Music, but there was no Play or Shuffle button.", recoverable = false, channel = "a11y")
            ctrl.clickElement(go.id)
            stage("pressed '${go.label}'")
        }
        val playing = awaitPlaying(ctx, YT_MUSIC, wait) { t ->
            before == null || t != before || MediaSessions.restarted(beforeAt, MediaSessions.positionMs(ctx.appContext, YT_MUSIC))
        }
        stage(if (playing != null) "playing" else "not playing")
        return if (playing != null) ToolResult(true, "Playing $label in YouTube Music. Now playing ${MediaSessions.words(playing) ?: "your music"}.",
            JSONObject().put("playing", true).put("collection", collection), channel = "a11y")
        else ToolResult(false, "I opened $label in YouTube Music and pressed play, but nothing is playing yet.",
            JSONObject().put("playing", false), recoverable = false, channel = "a11y")
    }

    /** YouTube Music's Library tab: its label is hidden (zero-size) in the tree, so it is the tab button whose label
     *  names it (the snapshot gives such a button its child's label), lowest on screen. Other times the tab's word is
     *  plain text and the button around it has no name (A20e 2026-09-30, on the Downloaded songs page: "Library" at
     *  y 1384 beside "Home", neither tappable, so the second "play my downloads" said there was no Library tab): then
     *  the tab is the "Library" text on the same row as "Home" (pressElement taps it). Pure. */
    internal fun libraryTab(s: UiSnapshot): com.tekyerema.accessibility.UiElement? {
        fun named(n: String) = s.elements.filter { it.label?.trim().equals(n, true) && !it.bounds.isEmpty() }
        named("library").filter { it.clickable }.maxByOrNull { it.bounds.top }?.let { return it }
        val homes = named("home")
        return named("library").filter { l -> homes.any { h -> kotlin.math.abs(h.bounds.centerY - l.bounds.centerY) < 40 } }
            .maxByOrNull { it.bounds.top }
    }

    private suspend fun awaitPlaying(ctx: ToolContext, pkg: String?, timeoutMs: Long,
                                     accept: (MediaSessions.Track) -> Boolean = { true }): MediaSessions.Track? {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            MediaSessions.playing(MediaSessions.current(ctx.appContext), pkg)?.takeIf(accept)?.let { return it }
            if (System.currentTimeMillis() >= end) return null
            kotlinx.coroutines.delay(500L)
        }
    }

    /** One press of the player's own Play/Shuffle control when the search opened but didn't start (reversible). */
    private suspend fun pressPlayOnScreen(ctx: ToolContext, pkg: String?): MediaSessions.Track? {
        val ctrl = ctx.controller ?: return null
        val snap = try { ctrl.buildSnapshot() } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Throwable) { return null }
        if (pkg != null && snap.appPackage != pkg) return null
        if (driveRefusal(snap.appPackage) != null) return null   // J-11 / D8: no press inside a denied or guide-only app
        val play = AppUi.preferClickable(AppUi.exact(snap, "play", "shuffle", "play all", "shuffle play")) ?: return null
        ctrl.clickElement(play.id)
        return awaitPlaying(ctx, pkg, 4_000L)
    }

    /** A media key to whatever is playing, then (when the sessions are visible) say what plays now. */
    private suspend fun transport(action: String, ctx: ToolContext): ToolResult {
        // Our own player (the phone's songs, live/LocalMusic) answers directly.
        val local = com.tekyerema.live.LocalMusic
        if (local.active) {
            when (action) {
                "pause" -> local.pause(); "resume", "play" -> local.resume(); "next" -> local.next()
                "previous" -> local.previous(); "stop" -> local.stop()
            }
            val words = when (action) {
                "pause" -> "Paused."; "stop" -> "Stopped."
                else -> local.now()?.let { "Playing ${it.words()}." } ?: "Done."
            }
            return ToolResult(true, words, channel = "native", data = JSONObject().put("action", action).put("local", true))
        }
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
        val ok = result.success && d != null && (d.optBoolean("playing") || d.has("action") ||
            (d.optBoolean("local") && com.tekyerema.live.LocalMusic.active))
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
            "display | battery (saver) | battery_usage (the Battery page) | about_phone | accessibility | apps | storage | date | " +
            "language | security | privacy | notifications | dnd (priority) | dnd_page | settings."),
        ToolParam("state", "string", "The position to leave the switch in. Omit to only open the screen.", required = false,
            enumValues = listOf("on", "off"))
    )

    private fun norm(target: String) = target.trim().lowercase().replace(" ", "_")

    /** True when [target] names a settings screen this tool can open — lets the fast path send "open Bluetooth
     *  settings" here instead of opening the Settings app's front page. */
    fun knows(target: String): Boolean = actionFor(target) != null

    /** The tool reads the switch back after setting it ("confirmed" = its checked state is what was asked): an OUTCOME, so
     *  the engine trusts it instead of a screen-change guess — without this, a confirmed "Turned mobile data off." was
     *  judged "no effect" and a 15-second screen read followed (Pixel emulator, 14 Oct 2026). */
    override val verifiesOutcome = true

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
            // N-09 (3): the pages a local plan reaches in one intent instead of "open Settings, then tap the row".
            "battery_usage", "battery_page", "battery_use" -> Intent.ACTION_POWER_USAGE_SUMMARY to "battery"
            "about", "about_phone", "about_device", "device_info", "phone_info" -> Settings.ACTION_DEVICE_INFO_SETTINGS to "about phone"
            "dnd_page", "do_not_disturb_page", "zen" -> "android.settings.ZEN_MODE_SETTINGS" to "Do Not Disturb"
            "privacy" -> Settings.ACTION_PRIVACY_SETTINGS to "privacy"
            "notifications", "notification" -> "android.settings.NOTIFICATION_SETTINGS" to "notifications"
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
        // Any hyphen: Android 15's panels write "Wi‑Fi" with U+2011, the non-breaking hyphen (Pixel emulator, 14 Oct 2026).
        "wifi", "wi-fi", "wi_fi" -> Regex("(?i)\\b(wi[-‐‑‒–— ]?fi|wlan)\\b")
        "mobile_data", "data", "internet", "cellular" -> Regex("(?i)\\b(mobile data|data|cellular data)\\b")
        "airplane", "flight", "aeroplane" -> Regex("(?i)\\b(airplane|aeroplane|flight) mode\\b|\\bairplane\\b")
        "bluetooth" -> Regex("(?i)\\bbluetooth\\b")
        "location", "gps" -> Regex("(?i)\\b(location|use location|gps)\\b")
        else -> null
    }

    /**
     * E-10: the switch whose label names the target — never "the first checkable thing on the screen". The label may be the
     * switch's own, or the TextView just before it in reading order: on the A20e's Internet and Wi-Fi panels (One UI 3,
     * 14 Oct 2026) the Switch has no text and no description at all — "Wi-Fi" / "Mobile data" are sibling TextViews, with
     * the carrier name ("MTN") between "Mobile data" and its switch. Both toggles were reported "couldn't find its switch"
     * and the task went to the cloud planner's quick-settings fumbling.
     */
    internal fun findToggle(snap: UiSnapshot, target: String): com.tekyerema.accessibility.UiElement? {
        val re = labelPattern(target) ?: return null
        val els = snap.elements
        els.firstOrNull { it.checkable && it.label?.let(re::containsMatchIn) == true }?.let { return it }
        // A label that names the target, then the first checkable element within the next few nodes — stopping at the next
        // label that names a DIFFERENT setting (on the Internet panel "Wi-Fi" must not take "Mobile data"'s switch).
        val others = listOf("wifi", "mobile_data", "airplane", "bluetooth", "location").mapNotNull { labelPattern(it) }.filter { it.pattern != re.pattern }
        for ((i, e) in els.withIndex()) {
            val label = e.label ?: continue
            if (e.checkable || !re.containsMatchIn(label) || label.length > 40) continue
            for (j in i + 1..minOf(i + 4, els.lastIndex)) {
                val c = els[j]
                if (c.checkable) return c
                val l = c.label
                if (l != null && others.any { it.containsMatchIn(l) }) break
            }
        }
        return null
    }

    /** The switch's state now: the same labelled switch, else the target's switch found afresh (a label of null matches
     *  nothing — on the Internet panel every switch is unlabelled, and "the first unlabelled switch" is Wi-Fi's). */
    internal fun stateOf(snap: UiSnapshot, toggle: com.tekyerema.accessibility.UiElement, target: String): Boolean? =
        toggle.label?.let { l -> snap.elements.firstOrNull { it.checkable && it.label == l }?.checked } ?: findToggle(snap, target)?.checked

    /** The phone's own record of a radio's state (Settings.Global, readable by any app), or null when it has none. */
    internal fun systemState(ctx: android.content.Context, target: String): Boolean? = try {
        val cr = ctx.contentResolver
        fun global(key: String): Int? = Settings.Global.getInt(cr, key, -1).takeIf { it >= 0 }
        when (norm(target)) {
            "wifi", "wi-fi", "wi_fi" -> global(Settings.Global.WIFI_ON)?.let { it == 1 || it == 2 }   // 2 = on while in airplane mode
            "mobile_data", "data", "internet", "cellular" -> global("mobile_data")?.let { it != 0 }
            "airplane", "flight", "aeroplane" -> global(Settings.Global.AIRPLANE_MODE_ON)?.let { it != 0 }
            "bluetooth" -> global(Settings.Global.BLUETOOTH_ON)?.let { it != 0 }
            else -> null
        }
    } catch (_: Throwable) { null }

    /** One UI may ask first ("Turn off mobile data?"): the button that does what the user asked, or null. */
    internal fun confirmButton(snap: UiSnapshot, want: Boolean): com.tekyerema.accessibility.UiElement? {
        val re = if (want) Regex("(?i)^(?:turn on|switch on|enable|ok|okay|allow)$") else Regex("(?i)^(?:turn off|switch off|disable|ok|okay)$")
        return snap.elements.firstOrNull { it.clickable && it.label?.trim()?.let(re::matches) == true }
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val target = args.str("target")?.trim().orEmpty()
        val want: Boolean? = when (args.str("state")?.trim()?.lowercase()) { "on" -> true; "off" -> false; else -> null }
        val (action, label) = actionFor(target)
            ?: return ToolResult(false, "I don't know which setting '$target' is.", recoverable = true)
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val settingsPkg = intent.resolveActivity(ctx.appContext.packageManager)?.packageName
            ?: return ToolResult(false, "This phone doesn't expose a $label setting screen I can open.", recoverable = true)
        // D8: "never touch Settings" keeps the Settings app closed; "guide only" opens it but leaves the switch to the user.
        launchRefusal(settingsPkg, "Settings")?.let { return ToolResult(false, it, recoverable = false) }
        try { ctx.appContext.startActivity(intent) } catch (t: Throwable) {
            return ToolResult(false, "Couldn't open $label settings: ${t.message}", recoverable = true)
        }
        // §P2.2 — the API forbids an app from flipping these directly, but our ACCESSIBILITY service can find and
        // tap the switch like a person would. It only taps when a direction was asked for AND the switch isn't
        // already there, then VERIFIES the checked state — reporting honestly if it didn't change.
        val ctrl = ctx.controller
            ?: return ToolResult(true, "Opened $label settings — screen control is off, so please use the switch there.",
                data = JSONObject().put("confirmed", false), channel = "intent")
        // Wait for the SETTINGS screen itself, not merely a quiet moment: right after startActivity the event stream is
        // often silent while the OLD app is still in front, and a "Wi-Fi"/"location" switch there could be tapped
        // (audit 2026-09-30, half 2 #11).
        var before = ctrl.buildSnapshot()
        val deadline = android.os.SystemClock.uptimeMillis() + 5_000L
        // Android 12+ shows the Internet panel as a System UI dialog, not a Settings screen (Pixel emulator, 14 Oct 2026): for
        // a panel, either package is "the settings screen came up".
        val shownBy = setOf(settingsPkg) + (if (action.startsWith("android.settings.panel.")) setOf("com.android.systemui") else emptySet())
        while (before.appPackage !in shownBy && android.os.SystemClock.uptimeMillis() < deadline) {
            kotlinx.coroutines.delay(300)
            before = ctrl.buildSnapshot()
        }
        if (before.appPackage !in shownBy)
            return ToolResult(true, if (want == null) "Opened $label settings — it was still coming up." else
                "Opened $label settings — the screen didn't come up in time, so please use the switch there.",
                data = JSONObject().put("confirmed", false), channel = "intent", recoverable = true)
        ctrl.waitForSettle(800)
        before = ctrl.buildSnapshot()
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
        if (driveRefusal(before.appPackage ?: settingsPkg) != null)
            return ToolResult(true, "Opened $label settings. I only guide you in Settings, so please turn it $word there yourself: " +
                "find the $label switch and double-tap it.", data = JSONObject().put("confirmed", false), channel = "intent", recoverable = false)
        val tapped = ctrl.clickElement(toggle.id)
        ctrl.waitForSettle(700)
        var after = ctrl.buildSnapshot()
        var nowChecked = stateOf(after, toggle, target)
        // The switch can take a moment to show its new position (the radio behind it changes first), and One UI / Android 15
        // may ask first ("Turn off mobile data?"): for up to 3 s the confirm button is pressed once if it appears, and the
        // phone's own setting (Wi-Fi on, mobile data) counts as the answer. The 14 Oct emulator run 3 turned both off/on and
        // still said "couldn't confirm it" — the switch was read before it moved.
        var pressedConfirm = false
        var waited = 0L
        while (tapped && nowChecked != want && waited <= 3_000L) {
            if (!pressedConfirm) confirmButton(after, want)?.let { b ->
                android.util.Log.i("Tekyerema", "[SETTING] the phone asked to confirm — pressing “${b.label}” (what the user asked for)")
                ctrl.clickElement(b.id)
                pressedConfirm = true
                ctrl.waitForSettle(700)
            }
            if (systemState(ctx.appContext, target) == want) { nowChecked = want; break }
            kotlinx.coroutines.delay(300); waited += 300
            after = ctrl.buildSnapshot()
            nowChecked = stateOf(after, toggle, target)
        }
        // A quick-settings PANEL is closed once its switch is set: the user is back where they were, and the next toggle
        // in the same request opens its own (owner, A20e, 14 Oct 2026: "Wi-Fi and data is supposed to be very, very quick").
        if (tapped && nowChecked == want && action.startsWith("android.settings.panel.")) {
            try { ctrl.globalAction("back") } catch (_: Throwable) {}
        }
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
