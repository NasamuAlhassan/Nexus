package com.tekyerema.live

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper

/**
 * Whatever app is playing — YouTube Music, Spotify, Boomplay — shows in the island with back / pause / next, the way
 * the iPhone's island shows Now Playing. Read from the phone's media sessions (needs the notification access Tɛkyerɛma
 * already has); our own player (LocalMusic) shows itself. Only a session that is playing or paused with a title counts.
 */
object MediaLive {
    private const val ID = "music-session"
    private val main = Handler(Looper.getMainLooper())
    private var manager: MediaSessionManager? = null
    private var component: ComponentName? = null
    private val watched = mutableMapOf<MediaController, MediaController.Callback>()
    private var lead: MediaController? = null
    @Volatile private var started = false

    fun start(ctx: Context) {
        if (started) return
        val app = ctx.applicationContext
        val m = app.getSystemService(MediaSessionManager::class.java) ?: return
        val comp = ComponentName(app, com.tekyerema.notifications.TekyeremaNotificationListener::class.java)
        try {
            m.addOnActiveSessionsChangedListener({ list -> rewatch(list.orEmpty()) }, comp, main)
            manager = m; component = comp; started = true
            rewatch(m.getActiveSessions(comp))
        } catch (_: SecurityException) {
            // No notification access yet: nothing to show; tried again on the next start.
        } catch (_: Throwable) {}
    }

    private fun rewatch(list: List<MediaController>) {
        watched.forEach { (c, cb) -> try { c.unregisterCallback(cb) } catch (_: Throwable) {} }
        watched.clear()
        list.filter { it.packageName != "com.tekyerema" }.forEach { c ->
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
                override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
                override fun onSessionDestroyed() = refresh()
            }
            try { c.registerCallback(cb, main); watched[c] = cb } catch (_: Throwable) {}
        }
        refresh()
    }

    private fun refresh() {
        if (LocalMusic.active) { LiveActivities.end(ID); return }
        val candidates = watched.keys.mapNotNull { c ->
            val st = c.playbackState?.state ?: return@mapNotNull null
            val title = c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (st != PlaybackState.STATE_PLAYING && st != PlaybackState.STATE_PAUSED && st != PlaybackState.STATE_BUFFERING) return@mapNotNull null
            Triple(c, title, st)
        }
        val pick = candidates.firstOrNull { it.third == PlaybackState.STATE_PLAYING } ?: candidates.firstOrNull()
        if (pick == null) { lead = null; LiveActivities.end(ID); return }
        val (c, title, st) = pick
        lead = c
        val artist = c.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
        val playing = st != PlaybackState.STATE_PAUSED
        val app = MusicApps.nameOf(c.packageName).takeIf { it != c.packageName }
        LiveActivities.upsert(LiveActivity(id = ID, kind = LiveKind.MUSIC, title = title, compact = artist ?: title,
            detail = listOfNotNull(artist, app).joinToString(" · ").ifEmpty { null },
            glyph = if (playing) LiveGlyph.MUSIC else LiveGlyph.PAUSED, priority = 1,
            actions = listOf(LiveAction("prev", "Back"), LiveAction("toggle", if (playing) "Pause" else "Play", primary = true), LiveAction("next", "Next")),
            spoken = (if (playing) "Playing " else "Paused, ") + title + (artist?.let { " by $it" } ?: ""))) { a ->
            val t = lead?.transportControls ?: return@upsert
            when (a) {
                "prev" -> t.skipToPrevious()
                "next" -> t.skipToNext()
                "toggle" -> if (lead?.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
            }
        }
    }
}
