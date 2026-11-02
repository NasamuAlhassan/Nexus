package com.tekyerema.camera.obstacle

import org.json.JSONArray
import org.json.JSONObject

/**
 * Every number the obstacle warner uses (assets/config/obstacles.json, node `obstacles`). Pure: [fromJson] reads a JSONObject, and
 * a missing file or key gives the defaults below, so a broken config can never remove a safety rule (the disclaimer, the cooldowns).
 * Every threshold is an uncalibrated first guess to tune on recorded walks.
 */
data class ObstacleConfig(
    val enabled: Boolean = false,
    val model: Model = Model(),
    val bands: Bands = Bands(),
    val sides: Sides = Sides(),
    val approach: Approach = Approach(),
    val debounce: Debounce = Debounce(),
    val cooldown: Cooldown = Cooldown(),
    val fps: Fps = Fps(),
    val watch: Watch = Watch(),
    val haptics: Haptics = Haptics(),
    val phrases: Phrases = Phrases(),
    val classes: Map<String, ClassInfo> = DEFAULT_CLASSES,
) {
    data class Model(
        val file: String = "yolox_nano.onnx", val side: Int = 416, val decoded: Boolean = false, val bgr: Boolean = true,
        val scoreMin: Float = 0.35f, val nmsIou: Float = 0.45f, val maxDetections: Int = 20, val threads: Int = 2,
    )

    /** One band's edge: a box this tall (after its class's scale), or one that reaches [minBottom] and is at least [bottomMinHeight] tall. */
    data class Edge(val minHeight: Float, val minBottom: Float = 2f, val bottomMinHeight: Float = 1f)
    data class Bands(
        val near: Edge = Edge(0.50f, 0.97f, 0.30f),
        val medium: Edge = Edge(0.25f, 0.85f, 0.12f),
        val far: Edge = Edge(0.10f),
    )

    /** Where "left", "ahead" and "right" begin on the frame's width (0..1), and how wide a box must be to count as ahead when it spans the middle. */
    data class Sides(val leftBelow: Float = 0.38f, val rightAbove: Float = 0.62f, val spanWidth: Float = 0.45f)
    data class Approach(val windowMs: Long = 1200, val minSamples: Int = 3, val growth: Float = 1.25f, val minDelta: Float = 0.04f)
    data class Debounce(val confirmFrames: Int = 2, val dropAfterMs: Long = 1000, val matchIou: Float = 0.15f)
    data class Cooldown(
        val speakPerClassMs: Long = 3000, val speakAnyMs: Long = 1200, val repeatSameMs: Long = 10_000,
        val hapticNearMs: Long = 900, val hapticMediumMs: Long = 2500,
    )
    data class Fps(val activeIntervalMs: Long = 150, val idleIntervalMs: Long = 400, val idleAfterMs: Long = 4000)
    data class Watch(
        val sessionMs: Long = 600_000, val blockedMeanLumaBelow: Float = 22f, val blockedLumaStdBelow: Float = 6f,
        val blockedMs: Long = 3000, val heartbeatMs: Long = 0,
    )

    /** Waveform timings (pause, on, pause, on ...) per direction and band. */
    data class Haptics(
        val ahead: Map<Band, LongArray> = mapOf(Band.NEAR to longArrayOf(0, 250, 80, 250, 80, 250), Band.MEDIUM to longArrayOf(0, 120, 100, 120), Band.FAR to longArrayOf(0, 40)),
        val left: Map<Band, LongArray> = mapOf(Band.NEAR to longArrayOf(0, 400, 120, 120), Band.MEDIUM to longArrayOf(0, 250, 120, 80), Band.FAR to longArrayOf(0, 40)),
        val right: Map<Band, LongArray> = mapOf(Band.NEAR to longArrayOf(0, 120, 120, 400), Band.MEDIUM to longArrayOf(0, 80, 120, 250), Band.FAR to longArrayOf(0, 40)),
        val approachingSuffix: LongArray = longArrayOf(100, 80),
        val farBuzz: Boolean = false,
    )

    data class Phrases(
        val disclaimer: String = "Walking mode. I may miss things, so keep using your cane.",
        val stopped: String = "Stopped watching. I may have missed things.",
        val blocked: String = "I can't see ahead, so I've stopped watching.",
        val timeout: String = "I've been watching a while and will stop now. I may have missed things.",
        val notInstalled: String = "The obstacle model isn't installed on this phone, so I can't warn you about obstacles.",
        val loadFailed: String = "I couldn't load the obstacle model, so I can't warn you about obstacles.",
        val disabled: String = "Obstacle warnings aren't switched on yet.",
        val near: String = "close", val far: String = "far", val coming: String = "coming",
        val ahead: String = "ahead", val left: String = "left", val right: String = "right",
        val label: String = "Watching ahead",
    )

    data class ClassInfo(val say: String, val group: Group, val scale: Float = 1f)

    companion object {
        /** The built-in class map (same as the JSON), used when the config has no `classes`. */
        val DEFAULT_CLASSES: Map<String, ClassInfo> = mapOf(
            "car" to ClassInfo("Car", Group.VEHICLE, 1.1f), "bus" to ClassInfo("Bus", Group.VEHICLE, 0.8f),
            "truck" to ClassInfo("Truck", Group.VEHICLE, 0.8f), "train" to ClassInfo("Train", Group.VEHICLE, 0.6f),
            "motorcycle" to ClassInfo("Motorbike", Group.MOTORBIKE, 1.2f), "bicycle" to ClassInfo("Bicycle", Group.BICYCLE, 1.2f),
            "person" to ClassInfo("Person", Group.PERSON, 1f),
            "fire hydrant" to ClassInfo("Post", Group.POLE, 1.5f), "stop sign" to ClassInfo("Sign post", Group.POLE, 1.5f),
            "parking meter" to ClassInfo("Post", Group.POLE, 1.3f),
            "dog" to ClassInfo("Animal", Group.ANIMAL, 1.6f), "cat" to ClassInfo("Animal", Group.ANIMAL, 1.8f),
            "sheep" to ClassInfo("Animal", Group.ANIMAL, 1.6f), "cow" to ClassInfo("Animal", Group.ANIMAL, 1.2f),
            "horse" to ClassInfo("Animal", Group.ANIMAL, 1f),
            "bench" to ClassInfo("Bench", Group.OTHER, 1.6f), "chair" to ClassInfo("Chair", Group.OTHER, 1.6f),
            "couch" to ClassInfo("Sofa", Group.OTHER, 1.4f), "dining table" to ClassInfo("Table", Group.OTHER, 1.4f),
            "potted plant" to ClassInfo("Plant", Group.OTHER, 1.6f), "suitcase" to ClassInfo("Bag", Group.OTHER, 1.8f),
        )

        /** Parse the `obstacles` node; null or malformed parts fall back to the defaults. */
        fun fromJson(o: JSONObject?): ObstacleConfig {
            if (o == null) return ObstacleConfig()
            val d = ObstacleConfig()
            val m = o.optJSONObject("model")
            val b = o.optJSONObject("bands")
            val dir = o.optJSONObject("direction")
            val ap = o.optJSONObject("approach")
            val db = o.optJSONObject("debounce")
            val cd = o.optJSONObject("cooldown")
            val fp = o.optJSONObject("fps")
            val w = o.optJSONObject("watch")
            val h = o.optJSONObject("haptics")
            val p = o.optJSONObject("phrases")
            return ObstacleConfig(
                enabled = o.optBoolean("enabled", false),
                model = Model(
                    m.str("file", d.model.file), m.int("side", d.model.side), m.bool("decoded", d.model.decoded), m.bool("bgr", d.model.bgr),
                    m.flt("scoreMin", d.model.scoreMin), m.flt("nmsIou", d.model.nmsIou), m.int("maxDetections", d.model.maxDetections), m.int("threads", d.model.threads),
                ),
                bands = Bands(edge(b?.optJSONObject("near"), d.bands.near), edge(b?.optJSONObject("medium"), d.bands.medium), edge(b?.optJSONObject("far"), d.bands.far)),
                sides = Sides(dir.flt("leftBelow", d.sides.leftBelow), dir.flt("rightAbove", d.sides.rightAbove), dir.flt("spanWidth", d.sides.spanWidth)),
                approach = Approach(ap.lng("windowMs", d.approach.windowMs), ap.int("minSamples", d.approach.minSamples), ap.flt("growth", d.approach.growth), ap.flt("minDelta", d.approach.minDelta)),
                debounce = Debounce(db.int("confirmFrames", d.debounce.confirmFrames).coerceAtLeast(1), db.lng("dropAfterMs", d.debounce.dropAfterMs), db.flt("matchIou", d.debounce.matchIou)),
                cooldown = Cooldown(
                    // The spoken-warning spacing is a safety rule: a config cannot push it under one second.
                    cd.lng("speakPerClassMs", d.cooldown.speakPerClassMs).coerceAtLeast(1000), cd.lng("speakAnyMs", d.cooldown.speakAnyMs),
                    cd.lng("repeatSameMs", d.cooldown.repeatSameMs), cd.lng("hapticNearMs", d.cooldown.hapticNearMs), cd.lng("hapticMediumMs", d.cooldown.hapticMediumMs),
                ),
                fps = Fps(fp.lng("activeIntervalMs", d.fps.activeIntervalMs).coerceAtLeast(50), fp.lng("idleIntervalMs", d.fps.idleIntervalMs), fp.lng("idleAfterMs", d.fps.idleAfterMs)),
                watch = Watch(w.lng("sessionMs", d.watch.sessionMs), w.flt("blockedMeanLumaBelow", d.watch.blockedMeanLumaBelow),
                    w.flt("blockedLumaStdBelow", d.watch.blockedLumaStdBelow), w.lng("blockedMs", d.watch.blockedMs), w.lng("heartbeatMs", d.watch.heartbeatMs)),
                haptics = Haptics(
                    ahead = pattern(h?.optJSONObject("ahead"), d.haptics.ahead), left = pattern(h?.optJSONObject("left"), d.haptics.left),
                    right = pattern(h?.optJSONObject("right"), d.haptics.right),
                    approachingSuffix = longs(h?.optJSONArray("approachingSuffix")) ?: d.haptics.approachingSuffix,
                    farBuzz = h.bool("farBuzz", d.haptics.farBuzz),
                ),
                phrases = Phrases(
                    p.str("disclaimer", d.phrases.disclaimer).ifBlank { d.phrases.disclaimer },   // never empty: the warning that it may miss things
                    p.str("stopped", d.phrases.stopped), p.str("blocked", d.phrases.blocked), p.str("timeout", d.phrases.timeout),
                    p.str("notInstalled", d.phrases.notInstalled), p.str("loadFailed", d.phrases.loadFailed), p.str("disabled", d.phrases.disabled),
                    p.str("near", d.phrases.near), p.str("far", d.phrases.far), p.str("coming", d.phrases.coming),
                    p.str("ahead", d.phrases.ahead), p.str("left", d.phrases.left), p.str("right", d.phrases.right), p.str("label", d.phrases.label),
                ),
                classes = classes(o.optJSONObject("classes")) ?: DEFAULT_CLASSES,
            )
        }

        private fun edge(o: JSONObject?, d: Edge): Edge =
            if (o == null) d else Edge(o.flt("minHeight", d.minHeight), o.flt("minBottom", d.minBottom), o.flt("bottomMinHeight", d.bottomMinHeight))

        private fun pattern(o: JSONObject?, d: Map<Band, LongArray>): Map<Band, LongArray> {
            if (o == null) return d
            return mapOf(
                Band.NEAR to (longs(o.optJSONArray("near")) ?: d.getValue(Band.NEAR)),
                Band.MEDIUM to (longs(o.optJSONArray("medium")) ?: d.getValue(Band.MEDIUM)),
                Band.FAR to (longs(o.optJSONArray("far")) ?: d.getValue(Band.FAR)),
            )
        }

        private fun longs(a: JSONArray?): LongArray? = if (a == null || a.length() == 0) null else LongArray(a.length()) { a.optLong(it) }

        private fun classes(o: JSONObject?): Map<String, ClassInfo>? {
            if (o == null) return null
            val out = LinkedHashMap<String, ClassInfo>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k.startsWith("_")) continue
                val c = o.optJSONObject(k) ?: continue
                val say = c.optString("say", "").trim().ifEmpty { continue }
                val group = Group.of(c.optString("group", "other"))
                out[k.lowercase()] = ClassInfo(say, group, c.optDouble("scale", 1.0).toFloat().coerceIn(0.2f, 4f))
            }
            return out.takeIf { it.isNotEmpty() }
        }

        private fun JSONObject?.str(k: String, d: String) = this?.optString(k, d) ?: d
        private fun JSONObject?.int(k: String, d: Int) = this?.optInt(k, d) ?: d
        private fun JSONObject?.lng(k: String, d: Long) = this?.optLong(k, d) ?: d
        private fun JSONObject?.flt(k: String, d: Float) = if (this != null && has(k)) optDouble(k, d.toDouble()).toFloat() else d
        private fun JSONObject?.bool(k: String, d: Boolean) = this?.optBoolean(k, d) ?: d
    }
}

/** How close, as a band — never metres. */
enum class Band { FAR, MEDIUM, NEAR }

enum class Direction { LEFT, AHEAD, RIGHT }

/** Urgency order: lower [priority] is more urgent (vehicles first, then motorbikes, bicycles, people, poles, animals, the rest). */
enum class Group(val priority: Int) {
    VEHICLE(0), MOTORBIKE(1), BICYCLE(2), PERSON(3), POLE(4), ANIMAL(5), OTHER(6);

    companion object {
        fun of(s: String): Group = entries.firstOrNull { it.name.equals(s.trim(), ignoreCase = true) } ?: OTHER
    }
}
