package com.tekyerema.live.nav

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.floor

/** A street/area name the phone's geocoder gave for a spot (lat, lon), and when. */
data class AreaNote(val street: String?, val area: String?, val lat: Double, val lon: Double, val atMs: Long)

/** A place found by a map lookup (name as heard / as the map calls it) and its coordinates, and when. */
data class PlaceNote(val name: String, val lat: Double, val lon: Double, val atMs: Long)

/**
 * What the phone remembers so location answers still work with no internet (owner, 27 Oct 2026: "make location work without
 * an internet connection wherever possible, using the phone's GPS and cached location data"). Every successful reverse-geocode
 * is kept against a coarse grid cell; every successful place lookup is kept by name. Offline, GPS alone gives coordinates, and
 * these notes turn them into words — always said to be saved, with their age, never guessed. Pure logic (unit-tested).
 */
object PlaceBook {
    const val MAX_AREAS = 60
    const val MAX_PLACES = 100
    /** An area note is only used for a position this close to where it was taken. */
    const val AREA_RADIUS_M = 500.0
    private const val CELL_DEG = 0.002   // about 220 m

    /** The coarse grid cell of a position (same cell ⇒ same note). */
    fun cell(lat: Double, lon: Double): String = "${floor(lat / CELL_DEG).toLong()}:${floor(lon / CELL_DEG).toLong()}"

    /** "The Atlantic Mall!" → "atlantic mall": lower-case words and digits only, no leading "the". */
    fun normalize(s: String): String =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().removePrefix("the ").trim()

    fun addArea(list: List<AreaNote>, note: AreaNote): List<AreaNote> {
        if (note.street.isNullOrBlank() && note.area.isNullOrBlank()) return list
        val c = cell(note.lat, note.lon)
        return (listOf(note) + list.filter { cell(it.lat, it.lon) != c }).take(MAX_AREAS)
    }

    /** The saved area note nearest to (lat, lon) and how far (metres) the position is from it, or null when none is close. */
    fun nearestArea(list: List<AreaNote>, lat: Double, lon: Double): Pair<AreaNote, Double>? =
        list.map { it to NavMath.distance(it.lat, it.lon, lat, lon) }
            .filter { it.second <= AREA_RADIUS_M }
            .minByOrNull { it.second }

    fun addPlace(list: List<PlaceNote>, note: PlaceNote): List<PlaceNote> {
        val key = normalize(note.name)
        if (key.length < 3 || note.lat.isNaN() || note.lon.isNaN()) return list
        return (listOf(note) + list.filter { normalize(it.name) != key }).take(MAX_PLACES)
    }

    /** A place looked up before: the exact name, else one that contains the other (4+ letters), newest first. */
    fun findPlace(list: List<PlaceNote>, said: String): PlaceNote? {
        val key = normalize(said)
        if (key.length < 3) return null
        list.firstOrNull { normalize(it.name) == key }?.let { return it }
        if (key.length < 4) return null
        return list.firstOrNull { val n = normalize(it.name); n.length >= 4 && (n.contains(key) || key.contains(n)) }
    }

    /** "moments ago", "5 minutes ago", "3 hours ago", "2 days ago". */
    fun ageWords(ageMs: Long): String {
        val mins = ageMs / 60_000L
        return when {
            ageMs < 90_000L -> "moments ago"
            mins < 60 -> "$mins minutes ago"
            mins < 120 -> "an hour ago"
            mins < 48 * 60 -> "${mins / 60} hours ago"
            else -> "${mins / (24 * 60)} days ago"
        }
    }

    /**
     * "Where am I?" with no street lookup: the saved name nearest to the GPS position, with its age. [heading] is the
     * direction of travel in degrees when the phone is moving, else null.
     */
    fun whereOffline(note: AreaNote, distM: Double, bearingFromNote: Double, nowMs: Long, accuracy: String?, heading: Double?): String {
        val name = listOfNotNull(note.street?.takeIf { it.isNotBlank() }, note.area?.takeIf { it.isNotBlank() })
            .distinctBy { it.lowercase() }.joinToString(", ")
        val at = if (distM < 50.0) "You're right by $name"
        else "You're about ${NavWords.distance(distM)} ${NavWords.cardinal(bearingFromNote)} of $name"
        val acc = accuracy?.let { " GPS is accurate to about $it." } ?: ""
        val head = heading?.let { " You seem to be heading ${NavWords.cardinal(it)}." } ?: ""
        return "I'm offline, so this comes from a street name I saved ${ageWords(nowMs - note.atMs)}. $at.$acc$head"
    }

    /** "How far is X" with no map service: straight line from the GPS position to a place looked up before. */
    fun distanceOffline(place: PlaceNote, straightM: Double, bearingDeg: Double, nowMs: Long, hereAgeMs: Long): String {
        val here = if (hereAgeMs > 120_000L) " Your position is from ${ageWords(hereAgeMs)}." else ""
        return "I'm offline, so this uses your GPS and where I saved ${place.name} ${ageWords(nowMs - place.atMs)}. " +
            "${place.name} is about ${NavWords.distance(straightM)} from you, to the ${NavWords.cardinal(bearingDeg)}, in a straight line.$here " +
            "I can't plan the walk without internet."
    }

    fun areasToJson(list: List<AreaNote>): String = JSONArray().also { a ->
        list.forEach { a.put(JSONObject().put("s", it.street ?: "").put("a", it.area ?: "").put("la", it.lat).put("lo", it.lon).put("t", it.atMs)) }
    }.toString()

    fun areasFromJson(s: String?): List<AreaNote> = try {
        val a = JSONArray(s ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            AreaNote(it.optString("s").ifBlank { null }, it.optString("a").ifBlank { null }, it.getDouble("la"), it.getDouble("lo"), it.optLong("t"))
        }
    } catch (_: Throwable) { emptyList() }

    fun placesToJson(list: List<PlaceNote>): String = JSONArray().also { a ->
        list.forEach { a.put(JSONObject().put("n", it.name).put("la", it.lat).put("lo", it.lon).put("t", it.atMs)) }
    }.toString()

    fun placesFromJson(s: String?): List<PlaceNote> = try {
        val a = JSONArray(s ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.map { PlaceNote(it.getString("n"), it.getDouble("la"), it.getDouble("lo"), it.optLong("t")) }
    } catch (_: Throwable) { emptyList() }
}

/** The on-phone store behind [PlaceBook] (private SharedPreferences) plus the "is the internet really there" check. */
object PlaceMemory {
    private const val PREFS = "place_memory"
    private val lock = Any()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True only when the active network is validated (can really reach the internet); a dead wifi link is false. */
    fun isOnline(ctx: Context): Boolean = try {
        val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (_: Throwable) { false }

    fun rememberArea(ctx: Context, lat: Double, lon: Double, street: String?, area: String?) = synchronized(lock) {
        try {
            val p = prefs(ctx)
            val next = PlaceBook.addArea(PlaceBook.areasFromJson(p.getString("areas", null)),
                AreaNote(street?.trim(), area?.trim(), lat, lon, System.currentTimeMillis()))
            p.edit().putString("areas", PlaceBook.areasToJson(next)).apply()
        } catch (_: Throwable) {}
    }

    fun nearestArea(ctx: Context, lat: Double, lon: Double): Pair<AreaNote, Double>? = synchronized(lock) {
        try { PlaceBook.nearestArea(PlaceBook.areasFromJson(prefs(ctx).getString("areas", null)), lat, lon) } catch (_: Throwable) { null }
    }

    /** Keeps the place under the name the map gave and, when different, under the words the user said. */
    fun rememberPlace(ctx: Context, said: String, place: OsmClient.Place) = synchronized(lock) {
        try {
            val p = prefs(ctx)
            val now = System.currentTimeMillis()
            var list = PlaceBook.placesFromJson(p.getString("places", null))
            list = PlaceBook.addPlace(list, PlaceNote(place.name, place.lat, place.lon, now))
            list = PlaceBook.addPlace(list, PlaceNote(said, place.lat, place.lon, now))
            p.edit().putString("places", PlaceBook.placesToJson(list)).apply()
        } catch (_: Throwable) {}
    }

    fun findPlace(ctx: Context, said: String): PlaceNote? = synchronized(lock) {
        try { PlaceBook.findPlace(PlaceBook.placesFromJson(prefs(ctx).getString("places", null)), said) } catch (_: Throwable) { null }
    }
}
