package com.tekyerema.camera.obstacle

/**
 * Detector boxes over a sequence of frames -> at most ONE warning per frame: a haptic pattern first, and a short phrase only when the
 * spoken-warning rules allow it. Pure (time is passed in), so every rule is unit-tested without a phone.
 *
 * What it will never do: say the way is clear or safe, give metres, or name more than the detector's class map allows. Silence from
 * this class means "nothing to report", never "nothing there"; the Android glue says so once at the start and again at the end.
 *
 *  - Proximity is a BAND (near / medium / far) from the box's height on the frame (times the class's size scale) or from a box that
 *    reaches the bottom edge — no depth model. Direction is left / ahead / right from the box centre (a wide box spanning the middle is ahead).
 *  - A box becomes a hazard only after [ObstacleConfig.Debounce.confirmFrames] matched frames; a miss costs one hit, so flicker never confirms.
 *  - Approach = the box's height grew by [ObstacleConfig.Approach.growth] within the window.
 *  - Haptic fires on the first report, on a nearer band, on a new approach, and repeats while a near/medium hazard stays (cooldown).
 *  - Speech: at most one line per object class per [ObstacleConfig.Cooldown.speakPerClassMs] (>= 1 s whatever the config says), at most
 *    one line of any class per [ObstacleConfig.Cooldown.speakAnyMs], and the same unchanged hazard is not re-announced for repeatSameMs.
 *  - Urgency order: nearer band first, then class priority (vehicles, motorbikes, bicycles, people, poles, animals, the rest), then size.
 */
class ObstacleEngine(private val cfg: ObstacleConfig) {

    data class Hazard(
        val trackId: Int, val cls: String, val say: String, val group: Group,
        val band: Band, val direction: Direction, val approaching: Boolean, val height: Float,
    )

    /** [haptic] is null when only a held-back spoken line is now due; [phrase] is null when speech is held back by a cooldown. */
    class Warning(val hazard: Hazard, val haptic: LongArray?, val phrase: String?)

    class Result(val warning: Warning?, val hazards: List<Hazard>)

    private class Track(val id: Int, val cls: String, val info: ObstacleConfig.ClassInfo, var det: Detection, now: Long) {
        var lastSeen = now
        var hits = 1
        val history = ArrayDeque<Pair<Long, Float>>()
        var approaching = false
        var reportedBand: Band? = null
        var reportedApproach = false
        var reportedDir: Direction? = null
        var lastHapticAt = NEVER
        var lastSpokeAt = NEVER
        var lastSpokeKey: String? = null
        var speechOwed = false
    }

    private val tracks = ArrayList<Track>()
    private var nextId = 1
    private val classSpokeAt = HashMap<String, Long>()
    private var anySpokeAt = NEVER

    /** The last time something worth a warning (medium or nearer, or approaching) was in view; drives the frame rate. */
    var lastHazardAt: Long = NEVER
        private set

    fun reset() { tracks.clear(); classSpokeAt.clear(); anySpokeAt = NEVER; lastHazardAt = NEVER }

    fun update(now: Long, detections: List<Detection>): Result {
        val dets = detections.filter { cfg.classes.containsKey(it.cls) }
        match(now, dets)

        val hazards = ArrayList<Pair<Track, Hazard>>()
        for (t in tracks) {
            if (t.lastSeen != now || t.hits < cfg.debounce.confirmFrames) continue
            val h = hazardOf(t) ?: continue
            hazards.add(t to h)
        }
        hazards.sortWith(URGENCY)
        if (hazards.any { it.second.band != Band.FAR || it.second.approaching }) lastHazardAt = now

        var chosen: Warning? = null
        for ((t, h) in hazards) {
            val reportable = h.band != Band.FAR || h.approaching
            val first = t.reportedBand == null
            val escalated = t.reportedBand != null && h.band.ordinal > t.reportedBand!!.ordinal
            val newApproach = h.approaching && !t.reportedApproach
            val repeat = when (h.band) {
                Band.NEAR -> now - t.lastHapticAt >= cfg.cooldown.hapticNearMs
                Band.MEDIUM -> now - t.lastHapticAt >= cfg.cooldown.hapticMediumMs
                Band.FAR -> false
            }
            val turned = h.band == Band.NEAR && t.reportedDir != null && t.reportedDir != h.direction
            val farTick = !reportable && first && cfg.haptics.farBuzz
            val dueHaptic = (reportable && (first || escalated || newApproach || repeat || turned)) || farTick
            if (!reportable && !farTick) {
                // A far, steady thing: remember it so a later step nearer counts as nearer, and say nothing.
                if (first) t.reportedBand = h.band
                continue
            }

            val key = "${h.band}/${h.direction}/${h.approaching}"
            val classOk = now - (classSpokeAt[h.cls] ?: NEVER) >= cfg.cooldown.speakPerClassMs
            val anyOk = now - anySpokeAt >= cfg.cooldown.speakAnyMs
            // A line held back by a cooldown is owed, not lost: it is said (without another buzz) as soon as the cooldowns allow.
            val owedNow = reportable && t.speechOwed && classOk && anyOk
            if (!dueHaptic && !owedNow) {
                if (t.reportedBand != null && h.band.ordinal < t.reportedBand!!.ordinal) t.reportedBand = h.band   // moved away: forget the higher band
                continue
            }
            if (chosen != null) continue   // one warning per frame; the next frame takes the next hazard

            val wantsSpeech = reportable && (owedNow || key != t.lastSpokeKey || now - t.lastSpokeAt >= cfg.cooldown.repeatSameMs)
            val phrase = if (wantsSpeech && classOk && anyOk) phraseFor(h) else null
            t.speechOwed = wantsSpeech && phrase == null

            if (dueHaptic) {
                t.reportedBand = h.band
                t.reportedApproach = h.approaching
                t.reportedDir = h.direction
                t.lastHapticAt = now
            }
            if (phrase != null) {
                t.lastSpokeAt = now; t.lastSpokeKey = key
                classSpokeAt[h.cls] = now; anySpokeAt = now
            }
            chosen = Warning(h, if (dueHaptic) hapticFor(h) else null, phrase)
        }
        return Result(chosen, hazards.map { it.second })
    }

    // ---------------------------------------------------------------- tracking

    private fun match(now: Long, dets: List<Detection>) {
        val pairs = ArrayList<Triple<Float, Track, Int>>()
        for (t in tracks) for ((i, d) in dets.withIndex()) {
            if (d.cls != t.cls) continue
            val iou = YoloxCodec.iou(t.det, d)
            if (iou >= cfg.debounce.matchIou) pairs.add(Triple(iou, t, i))
        }
        pairs.sortByDescending { it.first }
        val usedTracks = HashSet<Track>()
        val usedDets = HashSet<Int>()
        for ((_, t, i) in pairs) {
            if (t in usedTracks || i in usedDets) continue
            usedTracks.add(t); usedDets.add(i)
            val d = dets[i]
            t.det = d
            t.lastSeen = now
            t.hits++
            val eff = effectiveHeight(d, t.info)
            t.history.addLast(now to eff)
            while (t.history.size > 1 && now - t.history.first().first > cfg.approach.windowMs) t.history.removeFirst()
            t.approaching = approaching(t.history)
        }
        for (t in tracks) if (t !in usedTracks) { t.hits = (t.hits - 1).coerceAtLeast(0); t.approaching = false }
        for ((i, d) in dets.withIndex()) if (i !in usedDets) {
            val info = cfg.classes.getValue(d.cls)
            val t = Track(nextId++, d.cls, info, d, now)
            t.history.addLast(now to effectiveHeight(d, info))
            tracks.add(t)
        }
        tracks.removeAll { now - it.lastSeen > cfg.debounce.dropAfterMs || (it.hits == 0 && it.lastSeen != now) }
    }

    private fun approaching(history: List<Pair<Long, Float>>): Boolean {
        if (history.size < cfg.approach.minSamples) return false
        val first = history.first().second
        val last = history.last().second
        return first > 0f && last / first >= cfg.approach.growth && last - first >= cfg.approach.minDelta
    }

    private fun effectiveHeight(d: Detection, info: ObstacleConfig.ClassInfo): Float = (d.h * info.scale).coerceAtMost(1f)

    private fun hazardOf(t: Track): Hazard? {
        val d = t.det
        val band = bandOf(effectiveHeight(d, t.info), d.y1, cfg.bands) ?: return null
        return Hazard(t.id, t.cls, t.info.say, t.info.group, band, directionOf(d.x0, d.x1, cfg.sides), t.approaching, d.h)
    }

    // ---------------------------------------------------------------- output

    fun hapticFor(h: Hazard): LongArray {
        val table = when (h.direction) { Direction.LEFT -> cfg.haptics.left; Direction.RIGHT -> cfg.haptics.right; Direction.AHEAD -> cfg.haptics.ahead }
        val base = table.getValue(h.band)
        return if (h.approaching) base + cfg.haptics.approachingSuffix else base
    }

    /** "Car, close, ahead" - "Person, left" - "Car coming, ahead" - "Bicycle coming, far, left". Never a claim that anything is safe. */
    fun phraseFor(h: Hazard): String {
        val p = cfg.phrases
        val parts = ArrayList<String>(3)
        parts.add(if (h.approaching) "${h.say} ${p.coming}" else h.say)
        when (h.band) { Band.NEAR -> parts.add(p.near); Band.FAR -> parts.add(p.far); Band.MEDIUM -> {} }
        parts.add(when (h.direction) { Direction.LEFT -> p.left; Direction.RIGHT -> p.right; Direction.AHEAD -> p.ahead })
        return parts.joinToString(", ")
    }

    companion object {
        const val NEVER = Long.MIN_VALUE / 4

        /** Nearer band first, then class priority, then the bigger box. */
        private val URGENCY = Comparator<Pair<Track, Hazard>> { a, b ->
            val x = a.second; val y = b.second
            when {
                x.band != y.band -> y.band.ordinal - x.band.ordinal
                x.group.priority != y.group.priority -> x.group.priority - y.group.priority
                else -> y.height.compareTo(x.height)
            }
        }

        /** The band for a box of (size-scaled) [effHeight] whose bottom edge is at [bottom] (0 top .. 1 bottom); null = too small to report. */
        fun bandOf(effHeight: Float, bottom: Float, b: ObstacleConfig.Bands): Band? = when {
            effHeight >= b.near.minHeight || (bottom >= b.near.minBottom && effHeight >= b.near.bottomMinHeight) -> Band.NEAR
            effHeight >= b.medium.minHeight || (bottom >= b.medium.minBottom && effHeight >= b.medium.bottomMinHeight) -> Band.MEDIUM
            effHeight >= b.far.minHeight -> Band.FAR
            else -> null
        }

        /** A wide box that spans the middle is ahead whatever its centre says; otherwise the centre decides. */
        fun directionOf(x0: Float, x1: Float, s: ObstacleConfig.Sides): Direction {
            if (x0 < 0.5f && x1 > 0.5f && x1 - x0 >= s.spanWidth) return Direction.AHEAD
            val c = (x0 + x1) / 2f
            return when { c < s.leftBelow -> Direction.LEFT; c > s.rightAbove -> Direction.RIGHT; else -> Direction.AHEAD }
        }
    }
}

/** How often to look: fast while something is near, slow when nothing is (battery and heat on a mid phone). */
object WalkPacing {
    fun intervalMs(now: Long, lastHazardAt: Long, f: ObstacleConfig.Fps): Long =
        if (lastHazardAt != ObstacleEngine.NEVER && now - lastHazardAt <= f.idleAfterMs) f.activeIntervalMs else f.idleIntervalMs
}

/**
 * "I can't see": the picture is dark or flat (a pocket, a hand over the lens, a wall at the nose) for [blockedMs]. A blind user must hear
 * that the watch has stopped; silence would read as "all clear".
 */
class BlockedWatch(private val w: ObstacleConfig.Watch) {
    private var since = ObstacleEngine.NEVER

    fun isBlindFrame(meanLuma: Float, lumaStd: Float): Boolean = meanLuma < w.blockedMeanLumaBelow || lumaStd < w.blockedLumaStdBelow

    /** True once the view has been blind for the whole window. */
    fun update(now: Long, blind: Boolean): Boolean {
        if (!blind) { since = ObstacleEngine.NEVER; return false }
        if (since == ObstacleEngine.NEVER) since = now
        return now - since >= w.blockedMs
    }
}
