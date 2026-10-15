package com.tekyerema.live.nav

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * OpenStreetMap, free and keyless (owner's rule D-020: free tiers only): Overpass for "the nearest mall / pharmacy / bank",
 * Nominatim for a named place, and the public OSRM walking router (routing.openstreetmap.de). Light use only, a clear
 * User-Agent (their usage policies), short timeouts — a failure falls back to Google Maps (WalkGuide).
 */
object OsmClient {
    private const val TAG = "Tekyerema"
    private const val UA = "Tekyerema/1.0 (Android accessibility assistant for blind users)"

    data class Place(val name: String, val lat: Double, val lon: Double)

    /** Words for a kind of place → its OpenStreetMap tags. Pure. */
    fun categoryOf(phrase: String): List<Pair<String, String>>? {
        val p = phrase.lowercase()
        fun has(vararg w: String) = w.any { Regex("\\b$it\\b").containsMatchIn(p) }
        return when {
            has("mall", "malls", "shopping centre", "shopping center", "shopping mall") -> listOf("shop" to "mall")
            has("pharmacy", "chemist", "drug store", "drugstore") -> listOf("amenity" to "pharmacy")
            has("hospital") -> listOf("amenity" to "hospital")
            has("clinic") -> listOf("amenity" to "clinic", "amenity" to "hospital")
            has("atm", "cash machine") -> listOf("amenity" to "atm")
            has("bank") -> listOf("amenity" to "bank")
            has("supermarket", "grocery", "groceries") -> listOf("shop" to "supermarket")
            has("restaurant", "chop bar", "food joint") -> listOf("amenity" to "restaurant")
            has("fast food", "kfc", "pizza") -> listOf("amenity" to "fast_food")
            has("filling station", "fuel station", "petrol station", "gas station", "fuel") -> listOf("amenity" to "fuel")
            has("bus stop", "trotro stop", "trotro station", "bus station", "station") -> listOf("highway" to "bus_stop", "amenity" to "bus_station")
            has("market") -> listOf("amenity" to "marketplace")
            has("church") -> listOf("amenity" to "place_of_worship|religion=christian")
            has("mosque") -> listOf("amenity" to "place_of_worship|religion=muslim")
            has("police", "police station") -> listOf("amenity" to "police")
            has("school") -> listOf("amenity" to "school")
            has("toilet", "toilets", "washroom", "restroom") -> listOf("amenity" to "toilets")
            has("cafe", "coffee") -> listOf("amenity" to "cafe")
            has("hotel") -> listOf("tourism" to "hotel")
            has("park") -> listOf("leisure" to "park")
            else -> null
        }
    }

    private val NEAREST = Regex("\\b(nearest|closest|near me|nearby|around here|close by)\\b", RegexOption.IGNORE_CASE)
    private val LEAD = Regex("^(?:(?:the|a|an|any|some|nearest|closest)\\s+)+", RegexOption.IGNORE_CASE)
    private val GENERIC = setOf("mall", "malls", "shopping mall", "shopping centre", "shopping center", "pharmacy", "chemist", "drug store",
        "hospital", "clinic", "atm", "cash machine", "bank", "supermarket", "restaurant", "chop bar", "fast food", "filling station",
        "fuel station", "petrol station", "gas station", "bus stop", "trotro stop", "trotro station", "bus station", "station", "market",
        "church", "mosque", "police", "police station", "school", "toilet", "toilets", "washroom", "restroom", "cafe", "coffee shop",
        "hotel", "park")

    /**
     * (is it just a KIND of place, the words without "the / nearest / near me") — "the nearest mall" → (true, "mall");
     * "a pharmacy near me" → (true, "pharmacy"); "Accra Mall" → (false, "Accra Mall"). Pure.
     */
    fun kindOnly(destination: String): Pair<Boolean, String> {
        val near = NEAREST.containsMatchIn(destination)
        val rest = destination.replace(NEAREST, " ").replace(LEAD, "").replace(Regex("\\s+"), " ").trim().trimEnd('.', '?', '!')
        return Pair(near && categoryOf(rest) != null || rest.lowercase() in GENERIC, rest)
    }

    /** The nearest place of a kind within [radiusM] of (lat, lon), or null. */
    /** What a search found: the place (or none), and whether any map service answered at all (so "none near you" is only
     *  said when it is true, never when the servers were down). */
    data class Lookup(val place: Place?, val reached: Boolean)

    /** Public Overpass servers, tried in turn (the main one answered 502 under load on 15 Oct 2026). */
    private val OVERPASS = listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter")

    /**
     * The nearest place of a kind within [radiusM]: Nominatim's search for the word, bounded round the user, first — one quick
     * request that answered reliably on 15 Oct 2026 while every public Overpass server returned 5xx — then the Overpass
     * mirrors for kinds Nominatim has no word for.
     */
    suspend fun nearestOf(tags: List<Pair<String, String>>, lat: Double, lon: Double, radiusM: Int, label: String): Lookup {
        val near = searchBounded(label, lat, lon, radiusM)
        near.getOrNull()?.let { return Lookup(it, reached = true) }
        for (url in OVERPASS) {
            val r = nearestAt(url, tags, lat, lon, radiusM, label)
            if (r != null) return Lookup(r.getOrNull(), reached = true)
        }
        return Lookup(null, reached = near.isSuccess)
    }

    /** null = this server failed; success(null) = it answered with nothing. */
    private suspend fun nearestAt(url: String, tags: List<Pair<String, String>>, lat: Double, lon: Double, radiusM: Int, label: String): Result<Place?>? =
        withContext(Dispatchers.IO) {
            val parts = tags.joinToString("") { (k, v) ->
                val (value, extra) = v.split('|').let { it[0] to it.getOrNull(1) }
                val more = extra?.split('=')?.let { (a, b) -> "[\"$a\"=\"$b\"]" }.orEmpty()
                "nwr[\"$k\"=\"$value\"]$more(around:$radiusM,$lat,$lon);"
            }
            val q = "[out:json][timeout:12];($parts);out center 40;"
            val body = post(url, "data=" + URLEncoder.encode(q, "UTF-8")) ?: return@withContext null
            val els = try { JSONObject(body).optJSONArray("elements") } catch (_: Throwable) { null } ?: return@withContext null
            Result.success((0 until els.length()).mapNotNull { i ->
                val e = els.getJSONObject(i)
                val la = if (e.has("lat")) e.getDouble("lat") else e.optJSONObject("center")?.optDouble("lat") ?: return@mapNotNull null
                val lo = if (e.has("lon")) e.getDouble("lon") else e.optJSONObject("center")?.optDouble("lon") ?: return@mapNotNull null
                val name = e.optJSONObject("tags")?.optString("name")?.takeIf { it.isNotBlank() } ?: "the nearest $label"
                Place(name, la, lo)
            }.minByOrNull { NavMath.distance(lat, lon, it.lat, it.lon) })
        }

    private suspend fun searchBounded(word: String, lat: Double, lon: Double, radiusM: Int): Result<Place?> = withContext(Dispatchers.IO) {
        val d = radiusM / 111_000.0
        val vb = "${lon - d},${lat + d},${lon + d},${lat - d}"
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=10&bounded=1&q=" + URLEncoder.encode(word, "UTF-8") +
            "&viewbox=" + URLEncoder.encode(vb, "UTF-8")
        val body = get(url) ?: return@withContext Result.failure(IllegalStateException("unreachable"))
        val arr = try { JSONArray(body) } catch (t: Throwable) { return@withContext Result.failure(t) }
        Result.success((0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            Place(o.optString("name").ifBlank { o.optString("display_name").substringBefore(",") }.ifBlank { "the nearest $word" },
                o.getString("lat").toDouble(), o.getString("lon").toDouble())
        }.minByOrNull { NavMath.distance(lat, lon, it.lat, it.lon) })
    }

    /** A named place, nearest to (lat, lon) among the matches, in Ghana first. */
    suspend fun search(name: String, lat: Double, lon: Double): Place? = withContext(Dispatchers.IO) {
        val vb = "${lon - 0.4},${lat + 0.4},${lon + 0.4},${lat - 0.4}"
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=6&q=" + URLEncoder.encode(name, "UTF-8") +
            "&viewbox=" + URLEncoder.encode(vb, "UTF-8") + "&countrycodes=gh"
        val body = get(url) ?: return@withContext null
        val arr = JSONArray(body)
        (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            Place(o.optString("name").ifBlank { o.optString("display_name").substringBefore(",") }.ifBlank { name },
                o.getString("lat").toDouble(), o.getString("lon").toDouble())
        }.minByOrNull { NavMath.distance(lat, lon, it.lat, it.lon) }
    }

    /** A walking route with its steps as words. */
    suspend fun walkRoute(fromLat: Double, fromLon: Double, to: Place): Route? = withContext(Dispatchers.IO) {
        val url = "https://routing.openstreetmap.de/routed-foot/route/v1/driving/$fromLon,$fromLat;${to.lon},${to.lat}" +
            "?overview=full&geometries=geojson&steps=true"
        val body = get(url) ?: return@withContext null
        parseRoute(body, to.name)
    }

    /** OSRM's JSON → a Route. Pure (unit-tested with a recorded reply). */
    fun parseRoute(body: String, destination: String): Route? {
        val root = JSONObject(body)
        if (root.optString("code") != "Ok") return null
        val r = root.optJSONArray("routes")?.optJSONObject(0) ?: return null
        val coords = r.optJSONObject("geometry")?.optJSONArray("coordinates")
        val line = if (coords == null) emptyList() else (0 until coords.length()).map { i ->
            val c = coords.getJSONArray(i); c.getDouble(1) to c.getDouble(0)
        }
        val steps = mutableListOf<Step>()
        val legs = r.optJSONArray("legs") ?: return null
        for (li in 0 until legs.length()) {
            val ss = legs.getJSONObject(li).optJSONArray("steps") ?: continue
            for (si in 0 until ss.length()) {
                val s = ss.getJSONObject(si)
                val m = s.getJSONObject("maneuver")
                val loc = m.getJSONArray("location")
                val type = m.optString("type")
                val (words, glyph) = NavWords.step(type, m.optString("modifier").ifBlank { null }, s.optString("name"),
                    if (m.has("exit")) m.optInt("exit") else null, if (m.has("bearing_after")) m.optDouble("bearing_after") else null, destination)
                // OSRM repeats "continue on the same street" steps; a step that changes nothing is folded into the last.
                val prev = steps.lastOrNull()
                if (prev != null && type == "new name" && s.optString("name").isBlank()) {
                    steps[steps.size - 1] = prev.copy(lengthM = prev.lengthM + s.optDouble("distance")); continue
                }
                steps += Step(words, glyph, loc.getDouble(1), loc.getDouble(0), s.optDouble("distance"), arrive = type == "arrive")
            }
        }
        if (steps.isEmpty()) return null
        return Route(steps, r.optDouble("distance"), r.optDouble("duration") * WALK_SLOWDOWN, line)
    }

    /** OSRM's foot profile assumes ~5 km/h; someone walking with a cane or a guide is slower. */
    private const val WALK_SLOWDOWN = 1.25

    private fun get(url: String): String? = request(url, null)
    private fun post(url: String, form: String): String? = request(url, form)

    private fun request(url: String, form: String?): String? = try {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 6_000; readTimeout = 12_000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json")
            if (form != null) {
                requestMethod = "POST"; doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                outputStream.use { it.write(form.toByteArray()) }
            }
            val code = responseCode
            if (code !in 200..299) { Log.i(TAG, "[WALK] ${URL(url).host} answered $code"); disconnect(); null }
            else inputStream.bufferedReader().use { it.readText() }.also { disconnect() }
        }
    } catch (t: Throwable) { Log.i(TAG, "[WALK] ${try { URL(url).host } catch (_: Throwable) { "?" }} failed: ${t.javaClass.simpleName}"); null }
}
