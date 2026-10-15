package com.tekyerema.live.nav

import com.tekyerema.live.LiveGlyph
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Walking guidance, pure (unit-tested): a route's steps, where the walker is, what to say and show. The words are for
 * someone who can't see the map — the next turn and how far, said ahead of time and again at the turn; the arrival; a
 * walk off the route noticed and re-planned.
 */
data class Step(
    /** "Turn left onto Oxford Street". */
    val instruction: String,
    val glyph: LiveGlyph,
    /** Where this manoeuvre happens. */
    val lat: Double, val lon: Double,
    /** How long this step is, metres (from the manoeuvre to the next one). */
    val lengthM: Double,
    val arrive: Boolean = false,
)

data class Route(val steps: List<Step>, val totalM: Double, val durationS: Double, val line: List<Pair<Double, Double>>)

class Guidance(val route: Route, val destination: String) {
    /** The manoeuvre being walked towards (0 = the departure). */
    var next = 1; private set
    private val said = mutableSetOf<String>()
    private var offRoute = 0
    private var closest = Double.MAX_VALUE

    data class Update(
        val step: Step?,
        val distanceM: Double,
        /** A line to say now, or null. */
        val say: String?,
        val arrived: Boolean = false,
        /** Off the route for a while: plan again from here. */
        val reroute: Boolean = false,
        /** Metres left to the destination (along the route, roughly). */
        val remainingM: Double = 0.0,
        /** Close to the turn — the island opens. */
        val urgent: Boolean = false,
    )

    /** The opening line: where to, how far, how long, and the first move. */
    fun opening(): String {
        val first = route.steps.firstOrNull()?.instruction ?: "Start walking"
        val mins = (route.durationS / 60.0).roundToInt().coerceAtLeast(1)
        return "Walking to $destination: ${NavWords.distance(route.totalM)}, about $mins minute${if (mins == 1) "" else "s"}. $first."
    }

    fun update(lat: Double, lon: Double): Update {
        val step = route.steps.getOrNull(next) ?: return Update(null, 0.0, null, arrived = true)
        val d = NavMath.distance(lat, lon, step.lat, step.lon)
        val remaining = d + route.steps.drop(next).sumOf { it.lengthM }
        // Off the route: more than 45 m from its line three fixes in a row.
        val fromLine = NavMath.distanceToLine(lat, lon, route.line)
        offRoute = if (fromLine > 45.0) offRoute + 1 else 0
        if (offRoute >= 3) { offRoute = 0; return Update(step, d, "You're off the route. Finding the way again.", reroute = true, remainingM = remaining) }

        if (step.arrive) {
            if (d <= 20.0) { next = route.steps.size; return Update(step, d, step.instruction + ".", arrived = true, urgent = true) }
            return Update(step, d, sayOnce("$next-far", d <= 120.0 && d > 60.0) { "Your destination is ${NavWords.distance(d)} ahead." },
                remainingM = remaining, urgent = d <= 40.0)
        }
        // At the turn (or just past it): say it as "now", then move on to the next manoeuvre.
        closest = minOf(closest, d)
        val passed = closest < 25.0 && d > closest + 8.0
        if (d <= 12.0 || passed) {
            val line = sayOnce("$next-now", true) { "Now ${step.instruction.replaceFirstChar { it.lowercase() }}." }
            next++; closest = Double.MAX_VALUE
            return Update(step, d, line, remainingM = remaining, urgent = true)
        }
        val ahead = when {
            d <= 40.0 -> sayOnce("$next-40", true) { "In ${NavWords.distance(d)}, ${step.instruction.replaceFirstChar { it.lowercase() }}." }
            d <= 120.0 -> sayOnce("$next-120", true) { "In ${NavWords.distance(d)}, ${step.instruction.replaceFirstChar { it.lowercase() }}." }
            else -> null
        }
        return Update(step, d, ahead, remainingM = remaining, urgent = d <= 40.0)
    }

    /** "Where do I turn?" — the next move and how far, any time. */
    fun repeatLine(lat: Double?, lon: Double?): String {
        val step = route.steps.getOrNull(next) ?: return "You've arrived."
        val d = if (lat != null && lon != null) NavMath.distance(lat, lon, step.lat, step.lon) else null
        return if (d == null) step.instruction + "." else "In ${NavWords.distance(d)}, ${step.instruction.replaceFirstChar { it.lowercase() }}."
    }

    private fun sayOnce(key: String, cond: Boolean, line: () -> String): String? = if (cond && said.add(key)) line() else null
}

object NavMath {
    private const val R = 6_371_000.0
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * R * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Degrees clockwise from north. */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2); val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Shortest distance from a point to a polyline (flat-earth within each segment — fine at walking scale). */
    fun distanceToLine(lat: Double, lon: Double, line: List<Pair<Double, Double>>): Double {
        if (line.isEmpty()) return 0.0
        if (line.size == 1) return distance(lat, lon, line[0].first, line[0].second)
        val k = cos(Math.toRadians(lat)) * 111_320.0
        fun xy(p: Pair<Double, Double>) = Pair((p.second - lon) * k, (p.first - lat) * 110_540.0)
        var best = Double.MAX_VALUE
        for (i in 0 until line.size - 1) {
            val (ax, ay) = xy(line[i]); val (bx, by) = xy(line[i + 1])
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else ((-ax) * dx + (-ay) * dy) / len2
            val tt = t.coerceIn(0.0, 1.0)
            val px = ax + tt * dx; val py = ay + tt * dy
            best = minOf(best, sqrt(px * px + py * py))
        }
        return best
    }
}

object NavWords {
    /** 7 → "10 metres", 85 → "90 metres", 260 → "250 metres", 1240 → "1.2 kilometres". */
    fun distance(m: Double): String = when {
        m < 15 -> "10 metres"
        m < 100 -> "${((m / 10).roundToInt() * 10)} metres"
        m < 950 -> "${((m / 50).roundToInt() * 50)} metres"
        else -> "%.1f kilometres".format(m / 1000.0).replace(".0 kilometres", " kilometres")
    }

    /** The island's short form: "90 m", "1.2 km". */
    fun short(m: Double): String = when {
        m < 100 -> "${((m / 10).roundToInt() * 10).coerceAtLeast(10)} m"
        m < 950 -> "${((m / 50).roundToInt() * 50)} m"
        else -> "%.1f km".format(m / 1000.0)
    }

    private val CARDINAL = listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")
    fun cardinal(bearing: Double): String = CARDINAL[(((bearing % 360) + 360) % 360 / 45.0).roundToInt() % 8]

    /**
     * One OSRM step as words and a glyph. [type]/[modifier] are OSRM's manoeuvre fields; [name] the street ("" when none);
     * [exit] a roundabout's exit; [bearingAfter] the heading after the manoeuvre (departure only).
     */
    fun step(type: String, modifier: String?, name: String, exit: Int?, bearingAfter: Double?, destination: String): Pair<String, LiveGlyph> {
        val onto = if (name.isNotBlank()) " onto $name" else ""
        val along = if (name.isNotBlank()) " on $name" else ""
        val glyph = when (modifier) {
            "left", "sharp left" -> LiveGlyph.ARROW_LEFT
            "right", "sharp right" -> LiveGlyph.ARROW_RIGHT
            "slight left" -> LiveGlyph.ARROW_SLIGHT_LEFT
            "slight right" -> LiveGlyph.ARROW_SLIGHT_RIGHT
            "uturn" -> LiveGlyph.ARROW_UTURN
            else -> LiveGlyph.ARROW_STRAIGHT
        }
        fun turn(): String = when (modifier) {
            "left" -> "Turn left"; "right" -> "Turn right"
            "sharp left" -> "Turn sharply left"; "sharp right" -> "Turn sharply right"
            "slight left" -> "Bear left"; "slight right" -> "Bear right"
            "uturn" -> "Turn around"
            else -> "Continue straight"
        }
        return when (type) {
            "depart" -> ("Head ${bearingAfter?.let { cardinal(it) } ?: "off"}$along") to LiveGlyph.WALK
            "arrive" -> (when (modifier) {
                "left", "slight left", "sharp left" -> "You've arrived at $destination, on your left"
                "right", "slight right", "sharp right" -> "You've arrived at $destination, on your right"
                else -> "You've arrived at $destination"
            }) to LiveGlyph.ARRIVE
            "roundabout", "rotary" -> ("At the roundabout, take the ${ordinal(exit ?: 1)} exit$onto") to glyph
            "continue", "new name" -> (if (modifier == null || modifier == "straight") "Continue straight$along" else turn() + onto) to glyph
            else -> (turn() + onto) to glyph
        }
    }

    private fun ordinal(n: Int) = when (n) { 1 -> "first"; 2 -> "second"; 3 -> "third"; 4 -> "fourth"; 5 -> "fifth"; else -> "${n}th" }
}
