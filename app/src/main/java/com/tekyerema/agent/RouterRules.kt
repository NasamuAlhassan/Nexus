package com.tekyerema.agent

import com.tekyerema.core.config.AppConfig
import org.json.JSONArray

/**
 * X-03 — router rule files: extra phrasings for the Local Fast Path, per locale, in config instead of code.
 * `router.rules[]` (assets/config/rules.json, overridable like any config):
 *
 *   {"locale": "en", "pattern": "^how much charge do i have$", "intent": "BATTERY"}
 *   {"locale": "tw", "pattern": "^bue (.+)$", "intent": "OPEN_APP", "arg": "$1"}
 *
 * Patterns match the normalised request (lower-case, single spaces, no trailing punctuation) and are checked before
 * the built-in patterns. `intent` is a fast-path intent name; OPEN_APP, CAMERA, MAGNIFY and RINGER take `arg`
 * ("$1" = the first group, or a literal). `router.ruleLocales` limits which locales are active (default all).
 * A rule with a bad pattern or an unknown intent is skipped and logged, never guessed — and it can only reach
 * the same safe, reversible fast-path actions the built-in patterns reach (J-01: nothing consequential lives here).
 */
object RouterRules {
    data class Rule(val locale: String, val re: Regex, val intent: String, val arg: String?)

    private val OBJECTS: Map<String, FastIntent> by lazy {
        listOf(
            FastIntent.Time, FastIntent.Date, FastIntent.Day, FastIntent.Battery, FastIntent.Storage,
            FastIntent.VolumeUp, FastIntent.VolumeDown, FastIntent.VolumeMute, FastIntent.VolumeUnmute, FastIntent.VolumeQuery,
            FastIntent.BrightnessUp, FastIntent.BrightnessDown,
            FastIntent.FlashlightOn, FastIntent.FlashlightOff, FastIntent.FlashlightQuery,
            FastIntent.WifiQuery, FastIntent.WifiOn, FastIntent.WifiOff,
            FastIntent.BluetoothQuery, FastIntent.BluetoothOn, FastIntent.BluetoothOff,
            FastIntent.MediaPlay, FastIntent.MediaPause, FastIntent.MediaStop, FastIntent.MediaNext, FastIntent.MediaPrevious,
            FastIntent.Back, FastIntent.Home, FastIntent.Recents, FastIntent.ScrollUp, FastIntent.ScrollDown,
            FastIntent.LockScreen, FastIntent.Notifications, FastIntent.AnswerCall, FastIntent.EndCall,
            FastIntent.LastSms, FastIntent.WhereAmI, FastIntent.Connectivity, FastIntent.NowPlaying, FastIntent.ShowAlarms,
            FastIntent.Screenshot, FastIntent.PowerMenu, FastIntent.TakePhoto, FastIntent.TakeSelfie, FastIntent.CalendarToday, FastIntent.LockQuery,
            FastIntent.WhatChanged, FastIntent.ListSchedules, FastIntent.Undo, FastIntent.MemoryQuery
        ).associateBy { it.name }
    }

    @Volatile private var cacheKey: Any? = null
    @Volatile private var cache: List<Rule> = emptyList()

    /** The active rules, parsed once per config load. */
    fun rules(): List<Rule> {
        val node = AppConfig.node("router.rules")
        if (node === cacheKey) return cache
        val locales = AppConfig.stringList("router.ruleLocales", emptyList()).map { it.lowercase() }.toSet()
        val parsed = parse(node as? JSONArray).filter { locales.isEmpty() || it.locale in locales }
        cache = parsed; cacheKey = node
        return parsed
    }

    /** Pure: rules from JSON, skipping anything malformed. */
    fun parse(arr: JSONArray?): List<Rule> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val pattern = o.optString("pattern").trim()
            val intent = o.optString("intent").trim().uppercase()
            if (pattern.isEmpty() || intent.isEmpty()) return@mapNotNull null
            val re = try { Regex(pattern) } catch (_: Throwable) { warn("bad pattern '$pattern'"); return@mapNotNull null }
            Rule(o.optString("locale", "any").lowercase(), re, intent, o.optString("arg").takeIf { it.isNotBlank() })
        }
    }

    /** The first rule matching the normalised request [g], as a fast-path intent; null when none applies. */
    fun match(g: String, rules: List<Rule> = rules()): FastIntent? {
        for (r in rules) {
            val m = r.re.matchEntire(g) ?: continue
            val arg = r.arg?.let { a -> if (a.startsWith("$")) m.groupValues.getOrNull(a.drop(1).toIntOrNull() ?: 1)?.trim() else a }
            val intent = intentFor(r.intent, arg)
            if (intent != null) return intent
            warn("rule for unknown intent '${r.intent}' skipped")
        }
        return null
    }

    fun intentFor(name: String, arg: String?): FastIntent? = OBJECTS[name] ?: when (name) {
        "OPEN_APP" -> arg?.takeIf { it.isNotBlank() }?.let { FastIntent.OpenApp(it) }
        "CAMERA" -> arg?.takeIf { it in setOf("text", "color", "describe") }?.let { FastIntent.Camera(it) }
        "MAGNIFY" -> arg?.takeIf { it in setOf("in", "out", "reset") }?.let { FastIntent.Magnify(it) }
        "RINGER" -> arg?.takeIf { it in setOf("silent", "vibrate", "normal") }?.let { FastIntent.Ringer(it) }
        // E-14: a locale rule for "play …" — the arg is what to play (blank = the user's music).
        "PLAY_MUSIC" -> FastIntent.PlayMusic(arg?.trim()?.takeIf { it.isNotEmpty() })
        else -> null
    }

    private fun warn(msg: String) { try { android.util.Log.w("Tekyerema", "[RULES] $msg") } catch (_: Throwable) {} }
}
