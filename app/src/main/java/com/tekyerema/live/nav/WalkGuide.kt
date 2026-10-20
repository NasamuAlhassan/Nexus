package com.tekyerema.live.nav

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tekyerema.live.LiveAction
import com.tekyerema.live.LiveActivities
import com.tekyerema.live.LiveActivity
import com.tekyerema.live.LiveKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * "Show me directions to the nearest mall" (owner, 15 Oct 2026: "a live mapping … When I'm walking, it will direct me …
 * like the Dynamic Island"). Tɛkyerɛma finds the place and a walking route itself (OpenStreetMap, OsmClient), then guides
 * from the phone's location: the island shows the next turn, how far, and the street; the voice says each turn ahead of
 * time and again at the turn; walking off the route plans again. No map app is opened — unless OpenStreetMap can't be
 * reached, when Google Maps' own walking navigation takes over.
 */
object WalkGuide {
    private const val TAG = "Tekyerema"
    const val LIVE_ID = "walk"
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var guidance: Guidance? = null
    @Volatile private var target: OsmClient.Place? = null
    @Volatile private var last: Location? = null
    private var listener: LocationListener? = null
    private var rerouting = false
    @Volatile private var app: Context? = null

    val active: Boolean get() = guidance != null

    /**
     * Find [destination] ("the nearest mall", "Accra Mall") and start guiding. Returns the opening line — or why not, or
     * that Google Maps took over.
     */
    suspend fun start(ctx: Context, destination: String): String {
        val context = ctx.applicationContext.also { app = it }
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return "I need permission to use your location for walking directions. You can allow it in Tɛkyerɛma's settings."
        val here = currentLocation(context) ?: return openMaps(context, destination, "I can't tell where you are right now")
        val (generic, rest) = OsmClient.kindOnly(destination)
        val category = OsmClient.categoryOf(rest)
        val label = rest
        val dest = rest
        val place: OsmClient.Place?
        if (generic && category != null) {
            var look = OsmClient.nearestOf(category, here.latitude, here.longitude, 3_000, label)
            if (look.reached && look.place == null) look = OsmClient.nearestOf(category, here.latitude, here.longitude, 10_000, label)
            if (!look.reached) return openMaps(context, "$label near me", "I couldn't reach the map service")
            place = look.place ?: return "I couldn't find a $label within 10 kilometres of you."
        } else {
            place = OsmClient.search(rest, here.latitude, here.longitude) ?: return openMaps(context, dest, "I couldn't find $dest on the map")
        }
        val route = OsmClient.walkRoute(here.latitude, here.longitude, place) ?: return openMaps(context, place.name, "I couldn't plan the walk")
        stopUpdates()
        target = place
        val g = Guidance(route, place.name)
        guidance = g
        last = here
        Log.i(TAG, "[WALK] guiding to a place ${route.totalM.toInt()} m away, ${route.steps.size} steps")
        show(g, g.update(here.latitude, here.longitude), here)
        startUpdates(context)
        return g.opening()
    }

    /** "Stop the directions." */
    fun stop(): String {
        if (guidance == null) return "There are no directions running."
        end()
        return "Directions stopped."
    }

    /** "Where do I turn?" / "repeat". */
    fun repeat(): String = guidance?.repeatLine(last?.latitude, last?.longitude) ?: "There are no directions running."

    /** "How far is it?" */
    fun howFar(): String {
        val g = guidance ?: return "There are no directions running."
        val l = last ?: return "Walking to ${g.destination}."
        val u = g.update(l.latitude, l.longitude)
        val mins = (u.remainingM / 1.1 / 60.0).toInt().coerceAtLeast(1)
        return "${NavWords.distance(u.remainingM)} to ${g.destination}, about $mins minute${if (mins == 1) "" else "s"}."
    }

    /**
     * "How far is Atlantic Mall (from here)?" — from the phone's own location, never asking where "here" is (owner, 20 Oct 2026:
     * the assistant asked "From which location?"). Straight-line distance, plus the walk when the route service answers. Starts
     * no guidance.
     */
    suspend fun distanceTo(ctx: Context, destination: String): String {
        val context = ctx.applicationContext
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return "I need permission to use your location to tell you how far it is. You can allow it in Tɛkyerɛma's settings."
        // a distance needs no exact fix: the last one the phone knows if it is under 2 hours old, else a new one (10 s)
        val here = currentLocation(context, 2 * 3_600_000L, waitMs = 20_000L)
            ?: return "I can't tell where you are right now, so I can't say how far it is."
        val place = OsmClient.search(destination, here.latitude, here.longitude)
            ?: return "I couldn't find $destination on the map."
        val straight = NavMath.distance(here.latitude, here.longitude, place.lat, place.lon)
        val route = OsmClient.walkRoute(here.latitude, here.longitude, place)
        val far = "${place.name} is about ${NavWords.distance(straight)} from you"
        if (route == null) return "$far, in a straight line."
        val mins = (route.durationS / 60.0).toInt().coerceAtLeast(1)
        return "$far. Walking it is ${NavWords.distance(route.totalM)}, about ${if (mins >= 90) "${mins / 60} hours" else "$mins minutes"}."
    }

    private fun end() {
        stopUpdates()
        guidance = null; target = null
        LiveActivities.end(LIVE_ID)
    }

    private fun onLocation(loc: Location) {
        val g = guidance ?: return
        last = loc
        val u = g.update(loc.latitude, loc.longitude)
        show(g, u, loc)
        u.say?.let { say(it) }
        if (u.arrived) { main.postDelayed({ if (guidance === g) end() }, 10_000); return }
        if (u.reroute && !rerouting) reroute(loc)
    }

    private fun reroute(from: Location) {
        val place = target ?: return
        rerouting = true
        scope.launch {
            val r = OsmClient.walkRoute(from.latitude, from.longitude, place)
            rerouting = false
            if (r != null && target === place) {
                val g = Guidance(r, place.name); guidance = g
                show(g, g.update(from.latitude, from.longitude), from)
                say(r.steps.firstOrNull()?.instruction?.let { "$it." } ?: "Carry on.")
            }
        }
    }

    private fun show(g: Guidance, u: Guidance.Update, at: Location) {
        val step = u.step
        val total = g.route.totalM.coerceAtLeast(1.0)
        LiveActivities.upsert(LiveActivity(
            id = LIVE_ID, kind = LiveKind.NAVIGATION, title = g.destination,
            compact = if (u.arrived) "Arrived" else NavWords.short(u.distanceM),
            detail = step?.instruction,
            progress = (1.0 - u.remainingM / total).toFloat().coerceIn(0f, 1f),
            glyph = step?.glyph ?: com.tekyerema.live.LiveGlyph.WALK,
            urgent = u.urgent || u.arrived, priority = 5,
            actions = listOf(LiveAction("repeat", "Repeat"), LiveAction("end", "End")),
            spoken = if (u.arrived) "Arrived at ${g.destination}" else "${step?.instruction ?: "Walking"}, in ${NavWords.distance(u.distanceM)}"
        )) { a -> when (a) { "repeat" -> say(repeat()); "end" -> say(stop()) } }
    }

    private fun say(line: String) {
        try { com.tekyerema.voice.VoiceEngine.announceAnswer(line, null) } catch (_: Throwable) {}
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates(ctx: Context) {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) = onLocation(location)
            @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        listener = l
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { p ->
            try { if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, 1_000L, 1f, l, Looper.getMainLooper()) } catch (_: Throwable) {}
        }
    }

    private fun stopUpdates() {
        val l = listener ?: return
        try { app?.getSystemService(LocationManager::class.java)?.removeUpdates(l) } catch (_: Throwable) {}
        listener = null
    }

    /** A fresh fix: a recent last-known one, else one new fix within 10 s. */
    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(ctx: Context, maxAgeMs: Long = 120_000L, waitMs: Long = 10_000L): Location? {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val recent = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, "fused")
            .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Throwable) { null } }
            .filter { System.currentTimeMillis() - it.time < maxAgeMs }
            .minByOrNull { it.accuracy }
        if (recent != null) return recent
        return withTimeoutOrNull(waitMs) {
            suspendCancellableCoroutine { cont ->
                val l = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        try { lm.removeUpdates(this) } catch (_: Throwable) {}
                        if (cont.isActive) cont.resume(location)
                    }
                    @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused").forEach { p ->
                    try { if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, 0L, 0f, l, Looper.getMainLooper()) } catch (_: Throwable) {}
                }
                cont.invokeOnCancellation { try { lm.removeUpdates(l) } catch (_: Throwable) {} }
            }
        }
    }

    /** Google Maps' own walking navigation, when OpenStreetMap can't do it. */
    private fun openMaps(ctx: Context, destination: String, why: String): String {
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(destination) + "&mode=w"))
            .setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val any = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(destination))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pm = ctx.packageManager
        val intent = if (nav.resolveActivity(pm) != null) nav else if (any.resolveActivity(pm) != null) any else null
            ?: return "$why, and there's no maps app on this phone."
        return try { ctx.startActivity(intent); Log.i(TAG, "[WALK] handed to the maps app"); "$why myself, so Google Maps will guide you to $destination." }
        catch (_: Throwable) { "$why." }
    }
}
