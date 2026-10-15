package com.tekyerema.live

import android.content.ContentUris
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log

/**
 * "Play music" with the songs already on the phone (owner, 15 Oct 2026: "maybe it's there locally. If it's not there
 * locally, then it will have to open apps"). Plays them itself — no app opened — and shows the song in the island with
 * back / pause / next. A music app is only used when nothing here matches (tools/PlayMusicTool).
 */
object LocalMusic {
    private const val TAG = "Tekyerema"
    data class Song(val id: Long, val title: String, val artist: String?, val album: String?, val durationMs: Long) {
        val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
        fun words(): String = title + (artist?.takeIf { it.isNotBlank() && !it.contains("unknown", true) }?.let { " by $it" } ?: "")
    }

    @Volatile private var cache: Pair<Long, List<Song>>? = null
    private var player: MediaPlayer? = null
    private var queue: List<Song> = emptyList()
    private var index = 0
    @Volatile var playing: Boolean = false; private set
    private var focus: AudioFocusRequest? = null
    @Volatile private var app: Context? = null
    const val LIVE_ID = "music-local"

    val active: Boolean get() = player != null

    /** The songs on the phone (music only), newest scan at most a minute old. Empty without the permission. */
    fun songs(ctx: Context): List<Song> {
        cache?.let { (at, list) -> if (System.currentTimeMillis() - at < 60_000L) return list }
        val list = try {
            ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION),
                "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 30000", null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC")?.use { c ->
                buildList {
                    while (c.moveToNext()) add(Song(c.getLong(0), c.getString(1) ?: "Untitled", c.getString(2), c.getString(3), c.getLong(4)))
                }
            } ?: emptyList()
        } catch (t: Throwable) { Log.i(TAG, "[MUSIC] local library not readable (${t.javaClass.simpleName})"); emptyList() }
        cache = System.currentTimeMillis() to list
        return list
    }

    /** Songs matching what was asked — everything (shuffled) when nothing specific was. Pure given [all]. */
    fun match(all: List<Song>, query: String?, artist: String?): List<Song> {
        val q = norm(query); val a = norm(artist)
        if (q.isEmpty() && a.isEmpty()) return all.shuffled()
        fun has(field: String?, words: String) = words.isNotEmpty() && norm(field).let { f -> f.contains(words) || words.split(' ').all { w -> w.length > 1 && f.contains(w) } }
        val hits = all.filter { s ->
            val byArtist = a.isEmpty() || has(s.artist, a)
            val byQuery = q.isEmpty() || has(s.title, q) || has(s.artist, q) || has(s.album, q)
            byArtist && byQuery
        }
        return hits.sortedByDescending { s -> if (q.isNotEmpty() && norm(s.title) == q) 2 else if (q.isNotEmpty() && norm(s.title).startsWith(q)) 1 else 0 }
    }

    private fun norm(s: String?): String = s.orEmpty().lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Play [songs] from the first; returns what to say. */
    fun play(ctx: Context, songs: List<Song>): String {
        app = ctx.applicationContext
        queue = songs; index = 0
        start()
        return "Playing ${songs.first().words()} from your phone."
    }

    private fun start() {
        val ctx = app ?: return
        val song = queue.getOrNull(index) ?: return stop()
        release()
        if (!takeFocus(ctx)) { Log.i(TAG, "[MUSIC] no audio focus"); return }
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            try { setDataSource(ctx, song.uri); prepare() } catch (t: Throwable) {
                Log.i(TAG, "[MUSIC] can't play a local song (${t.javaClass.simpleName}) — next"); release(); player = null
                index++; if (index < queue.size) start() else stop(); return
            }
            setOnCompletionListener { next() }
            start()
        }
        playing = true
        Log.i(TAG, "[MUSIC] local: playing ${index + 1}/${queue.size}")
        show()
    }

    fun pause() { player?.let { if (it.isPlaying) it.pause() }; playing = false; show() }
    fun resume() { val p = player ?: return; app?.let { takeFocus(it) }; p.start(); playing = true; show() }
    fun toggle() { if (playing) pause() else resume() }
    fun next() { if (queue.isEmpty()) return; index = (index + 1) % queue.size; start() }
    fun previous() {
        val p = player
        if (p != null && p.currentPosition > 4_000) { p.seekTo(0); return }
        if (queue.isEmpty()) return; index = (index - 1 + queue.size) % queue.size; start()
    }
    fun stop() {
        release(); playing = false; queue = emptyList()
        LiveActivities.end(LIVE_ID)
        focus?.let { f -> app?.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(f) }; focus = null
    }
    fun now(): Song? = queue.getOrNull(index)?.takeIf { player != null }

    private fun release() { try { player?.release() } catch (_: Throwable) {}; player = null }

    private fun takeFocus(ctx: Context): Boolean {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return true
        if (Build.VERSION.SDK_INT < 26) return true
        val req = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> pause()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { player?.let { if (it.isPlaying) it.pause() } }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player?.setVolume(0.25f, 0.25f)
                    AudioManager.AUDIOFOCUS_GAIN -> { player?.setVolume(1f, 1f); if (playing) player?.start() }
                }
            }.build().also { focus = it }
        return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun show() {
        val s = now() ?: return
        LiveActivities.upsert(LiveActivity(id = LIVE_ID, kind = LiveKind.MUSIC, title = s.title,
            compact = s.artist?.takeIf { !it.contains("unknown", true) } ?: s.title,
            detail = listOfNotNull(s.artist?.takeIf { !it.contains("unknown", true) }, s.album?.takeIf { !it.contains("unknown", true) }).joinToString(" · ").ifEmpty { null },
            glyph = if (playing) LiveGlyph.MUSIC else LiveGlyph.PAUSED, priority = 1,
            actions = listOf(LiveAction("prev", "Back"), LiveAction("toggle", if (playing) "Pause" else "Play", primary = true), LiveAction("next", "Next")),
            spoken = (if (playing) "Playing " else "Paused, ") + s.words())) { a ->
            when (a) { "prev" -> previous(); "toggle" -> toggle(); "next" -> next(); "stop" -> stop() }
        }
    }
}

/**
 * Which music app plays a request when nothing on the phone matches (owner: "if there are multiple apps, it can ask").
 * Known apps in a sensible order; the user's choice is remembered.
 */
object MusicApps {
    val KNOWN = listOf(
        "com.google.android.apps.youtube.music" to "YouTube Music",
        "com.spotify.music" to "Spotify",
        "com.afmobi.boomplayer" to "Boomplay",
        "com.audiomack" to "Audiomack",
        "deezer.android.app" to "Deezer",
        "com.apple.android.music" to "Apple Music",
        "com.soundcloud.android" to "SoundCloud",
    )
    private const val PREF = "music.preferredApp"

    fun installed(ctx: Context): List<Pair<String, String>> = KNOWN.filter { (pkg, _) ->
        try { ctx.packageManager.getLaunchIntentForPackage(pkg) != null } catch (_: Throwable) { false }
    }

    fun preferred(ctx: Context): String? = ctx.getSharedPreferences("tekyerema_music", Context.MODE_PRIVATE).getString(PREF, null)
    fun remember(ctx: Context, pkg: String) { ctx.getSharedPreferences("tekyerema_music", Context.MODE_PRIVATE).edit().putString(PREF, pkg).apply() }
    fun nameOf(pkg: String): String = KNOWN.firstOrNull { it.first == pkg }?.second ?: pkg

    /** "Which app — YouTube Music or Spotify?" Pure. */
    fun question(options: List<Pair<String, String>>): String {
        val names = options.map { it.second }
        val list = if (names.size <= 2) names.joinToString(" or ") else names.dropLast(1).joinToString(", ") + " or " + names.last()
        return "Which app should I play it on — $list?"
    }

    /** The option the user's answer names ("Spotify", "the first one", "YouTube"), or null. Pure. */
    fun pick(answer: String, options: List<Pair<String, String>>): Pair<String, String>? {
        val a = answer.lowercase()
        options.firstOrNull { (_, name) -> a.contains(name.lowercase()) }?.let { return it }
        options.firstOrNull { (_, name) -> name.lowercase().split(' ').any { w -> w.length > 3 && a.contains(w) } }?.let { return it }
        // ("the second one" holds "one": the ordinals are checked from the last, and a bare "one" isn't one)
        return when {
            Regex("\\b(third|3rd)\\b").containsMatchIn(a) -> options.getOrNull(2)
            Regex("\\b(second|2nd)\\b").containsMatchIn(a) -> options.getOrNull(1)
            Regex("\\b(first|1st)\\b").containsMatchIn(a) -> options.getOrNull(0)
            else -> null
        }
    }

    /** A pending "which app?" question: the request, the choices, when asked. */
    data class Pending(val query: String, val artist: String, val options: List<Pair<String, String>>, val at: Long = System.currentTimeMillis())
    @Volatile var pending: Pending? = null
    fun takePending(maxAgeMs: Long = 60_000L): Pending? = pending?.takeIf { System.currentTimeMillis() - it.at <= maxAgeMs }.also { pending = null }
}
