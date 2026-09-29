package com.tekyerema.agent

import android.content.Context
import com.tekyerema.core.SecureStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * H-02 — routines taught by voice: "when I say good morning, tell me the time, the weather and my messages". Saying
 * "good morning" then runs each step in order and speaks one combined answer.
 *
 * Bixby Quick Commands and Google/Alexa routines are built in a phone app's forms (sighted set-up), Gemini's routines
 * lost time starters and skip media, and none of them exists in Twi or runs offline. Here the routine is made AND run
 * by voice, on the phone, and every step that doesn't need the web works without data.
 *
 * Safety: a step is only ever a Local Fast Path intent from [ALLOWED] (reading, and reversible device controls such as
 * Do Not Disturb, volume, brightness, the torch, opening an app, playing music) or a web QUESTION (weather, news) that
 * web_search answers. Nothing that sends, calls, pays, installs, deletes or signs in can be in a routine — those words
 * don't resolve to a step, so the user hears which parts were left out when the routine is saved. Each step still runs
 * through the same code (and, for tools, the same policy check) as when said on its own.
 *
 * Parsing and step resolution are pure (unit-tested); the store is in memory with an encrypted file behind it.
 */
object Routines {
    const val FEATURE = "H-02"
    const val MAX_STEPS = 6

    data class Routine(val name: String, val steps: List<String>)
    data class Definition(val name: String, val steps: List<String>)

    sealed class Step {
        abstract val said: String
        data class Fast(val intent: FastIntent, override val said: String) : Step()
        data class Web(val query: String, override val said: String) : Step()
    }

    // ---------------------------------------------------------------- definition (pure)

    private val WHEN_I_SAY = Regex("^(?:please )?(?:when(?:ever)?|if) i say[,:]? (.+)$")
    private val CREATE = Regex("^(?:please )?(?:create|make|add|set up|save|start|teach you) (?:a |an |my |me a )?(?:new )?routine(?: called| named)?[,:]? (.+)$")
    /** Words that open a step — used to find where the routine's name ends when nobody paused ("when I say good night
     *  turn on do not disturb"). */
    private val STEP_VERB = Regex("^(?:(?:read|tell|say|give|turn|switch|put|set|open|play|check|lower|raise|increase|decrease|" +
        "mute|unmute|enable|disable|start|show|lock|dim|make|remind|call|send|text|catch|brighten|launch)s?|what|what's|you|please|then|i want)\\b")
    /** "reads the time" (after "a routine that …") → "read the time". */
    private val THIRD_PERSON = Regex("^(read|tell|give|turn|switch|play|check|open|lock|set|say|lower|raise|mute|unmute|show|catch)s\\b")
    private val BODY_LEAD = Regex("^(?:(?:you should|i want you to|please|then|to|and|can you|you|should)\\s+)+")
    private val SPLIT = Regex("\\s*(?:,\\s*(?:and then|and|then|also)?|;|\\band then\\b|\\bthen\\b|\\band\\b|\\balso\\b|\\bafter that\\b)\\s*")
    /** A routine may not be named like an answer to a confirmation or a core command. */
    private val RESERVED = setOf("yes", "no", "stop", "cancel", "okay", "ok", "continue", "help", "help me", "undo", "back", "home",
        "next", "done", "wait", "emergency", "call", "repeat", "sorry", "thank you", "thanks")

    private fun norm(raw: String) = raw.trim().lowercase().replace('’', '\'').replace(Regex("\\s+"), " ").trimEnd('.', '!', '?').trim()

    /** The routine being taught, or null when this isn't a routine definition. */
    fun parseDefinition(raw: String): Definition? {
        val g = norm(raw)
        val body = WHEN_I_SAY.matchEntire(g)?.groupValues?.get(1) ?: CREATE.matchEntire(g)?.groupValues?.get(1) ?: return null
        val (name, rest) = splitNameBody(body) ?: return null
        if (!validName(name)) return null
        val steps = splitSteps(rest)
        if (steps.isEmpty()) return null
        return Definition(name, steps)
    }

    private fun splitNameBody(body: String): Pair<String, String>? {
        val b = body.trim()
        // Quoted name: when I say "good morning" read …
        Regex("^[\"“'‘](.{1,40}?)[\"”'’][,:]?\\s+(.+)$").matchEntire(b)?.let { return cleanName(it.groupValues[1]) to it.groupValues[2] }
        // A pause the recogniser wrote down: when I say good morning, read …
        Regex("^(.{1,40}?)\\s*(?:[,:]| - | – )\\s*(.+)$").matchEntire(b)?.let { m ->
            val n = cleanName(m.groupValues[1])
            if (n.split(' ').size <= 5) return n to m.groupValues[2]
        }
        // "routine called good morning that reads / to read …"
        Regex("^(.{1,40}?) (?:that|which|to|where you|so that you) (.+)$").matchEntire(b)?.let { m ->
            if (STEP_VERB.containsMatchIn(m.groupValues[2])) return cleanName(m.groupValues[1]) to m.groupValues[2]
        }
        // No pause: the name is the shortest run of 1–5 words followed by a step verb.
        val words = b.split(' ')
        for (n in 1..minOf(5, words.size - 1)) {
            val rest = words.drop(n).joinToString(" ")
            if (STEP_VERB.containsMatchIn(rest)) return cleanName(words.take(n).joinToString(" ")) to rest
        }
        return null
    }

    private fun cleanName(s: String) = s.trim().trim('"', '“', '”', '\'', '‘', '’', ',', ':').replace(Regex("\\s+"), " ")
        .replace(Regex("[, ]+(?:tekyerema|tɛkyerɛma)$"), "").replace(Regex("^(?:tekyerema|tɛkyerɛma)[, ]+"), "").trim()

    fun validName(name: String): Boolean {
        val n = name.trim()
        if (n.length < 2 || n.split(' ').size > 5 || n.none { it.isLetter() }) return false
        if (n in RESERVED) return false
        // It must not shadow something the phone already understands ("what time is it", "lock the phone").
        return FastIntentClassifier.classify(n, routines = false) == null && ContextHelp.classify(n) == null
    }

    /** "the time, the weather and my messages" → ["the time", "the weather", "my messages"]. */
    fun splitSteps(rest: String): List<String> = rest.split(SPLIT)
        .map { it.trim().replace(BODY_LEAD, "").trim().trim(',', '.', ' ') }
        .filter { it.isNotEmpty() && it.any(Char::isLetter) }

    // ---------------------------------------------------------------- steps (pure)

    /** Reading, and reversible device controls — nothing that leaves the phone or can't be put back. */
    fun allowed(i: FastIntent): Boolean = when (i) {
        FastIntent.Time, FastIntent.Date, FastIntent.Day, FastIntent.Battery, FastIntent.Storage, FastIntent.DeviceInfo,
        FastIntent.VolumeUp, FastIntent.VolumeDown, FastIntent.VolumeMute, FastIntent.VolumeUnmute, FastIntent.VolumeQuery,
        FastIntent.BrightnessUp, FastIntent.BrightnessDown, FastIntent.FlashlightOn, FastIntent.FlashlightOff, FastIntent.FlashlightQuery,
        FastIntent.WifiQuery, FastIntent.BluetoothQuery, FastIntent.BluetoothOn, FastIntent.BluetoothOff,
        FastIntent.MediaPlay, FastIntent.MediaPause, FastIntent.MediaStop, FastIntent.MediaNext, FastIntent.MediaPrevious,
        FastIntent.LockScreen, FastIntent.LastSms, FastIntent.WhereAmI,
        FastIntent.Connectivity, FastIntent.NowPlaying, FastIntent.ShowAlarms, FastIntent.CalendarToday, FastIntent.ListSchedules -> true
        is FastIntent.BrightnessSet, is FastIntent.QuickToggle, is FastIntent.Ringer, is FastIntent.OpenApp, is FastIntent.PlayMusic,
        is FastIntent.CallHistory, is FastIntent.Digest, is FastIntent.SaverQuery -> true
        else -> false
    }

    /** Topics a routine may look up on the web (read-only, answered by web_search). */
    private val WEB_TOPIC = Regex("\\b(?:weather|forecast|temperature|rain|news|headlines|exchange rate|dollar rate|cedi rate|cedi|" +
        "football|scores?|black stars|premier league|fuel prices?|petrol prices?)\\b")
    private val WEB_LEAD = Regex("^(?:tell me|read(?: me)?|check|give me|what(?:'s| is| are)|get)\\s+")
    private val WHEN_WORD = Regex("\\b(?:today|tonight|tomorrow|now|this (?:week|morning|evening)|latest)\\b")

    /** A step as said, resolved to something runnable; null when it can't be part of a routine. */
    fun resolve(step: String): Step? {
        val s = norm(step).replace(Regex("^(?:please|and|then)\\s+"), "").replace(THIRD_PERSON, "$1")
        if (s.isEmpty()) return null
        // "the time" / "read the time" / "my calendar" are how steps are listed; try the forms the fast path knows.
        val bare = s.replace(Regex("^(?:read(?: me)?|tell me|check|give me|say)\\s+"), "")
        val forms = listOf(s, "tell me $s", "what's $s", "read $s", "check $s", "what's on $s", "turn $s") +
            if (bare != s) listOf("tell me $bare", "what's $bare", "what's on $bare") else emptyList()
        for (c in forms) {
            val i = FastIntentClassifier.classify(c, routines = false) ?: continue
            return if (allowed(i)) Step.Fast(i, s) else null   // an understood-but-not-allowed step is refused, not reinterpreted
        }
        if (WEB_TOPIC.containsMatchIn(s)) {
            var q = s.replace(WEB_LEAD, "")
            if (!WHEN_WORD.containsMatchIn(q)) q += " today"
            // Without a place the web answer is for nowhere in particular; Ghana is the default (config: routines.web.place).
            if (Regex("\\b(?:weather|forecast|temperature|rain)\\b").containsMatchIn(q) && !Regex("\\bin [a-z]").containsMatchIn(q))
                q += " in " + com.tekyerema.core.config.AppConfig.string("routines.web.place", "Ghana")
            return Step.Web(q, s)
        }
        return null
    }

    // ---------------------------------------------------------------- running by name (pure)

    private val RUN_LEAD = Regex("^(?:(?:hey |okay |ok )?(?:tekyerema|tɛkyerɛma)[, ]+)?(?:please )?(?:(?:run|start|do|begin|launch|play) (?:my |the )?)?")
    private val RUN_TAIL = Regex("(?: routine)?(?:[, ]+(?:tekyerema|tɛkyerɛma))?(?: please)?$")

    /** The routine named by the whole utterance [g] ("good morning", "run my good morning routine"), or null. */
    fun match(g: String, names: Collection<String>): String? {
        if (names.isEmpty()) return null
        val s = norm(g).replace(",", "")
        val core = s.replace(RUN_LEAD, "").replace(RUN_TAIL, "").trim()
        return names.firstOrNull { it == s || it == core }
    }

    private val LIST = Regex("^(?:what|which) routines (?:do i have|have i (?:got|made|taught you))|^(?:list|read|tell me) (?:all )?(?:of )?my routines$|^what are my routines$")
    private val DELETE = Regex("^(?:please )?(?:delete|remove|forget|cancel|clear) (?:the |my )?(?:routine )?(.{2,40}?)(?: routine)$|" +
        "^(?:please )?(?:delete|remove|forget|cancel|clear) (?:the |my )?routine (?:called |named )?(.{2,40}?)$")

    fun isList(g: String) = LIST.containsMatchIn(norm(g))

    /** "delete the good morning routine" → "good morning". */
    fun parseDelete(g: String): String? = DELETE.matchEntire(norm(g))?.let { m -> (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim() }

    // ---------------------------------------------------------------- store

    private val routines = LinkedHashMap<String, Routine>()
    @Volatile private var file: File? = null
    @Volatile private var loaded = false

    fun ensureLoaded(ctx: Context?) {
        if (loaded || ctx == null) return
        synchronized(this) {
            if (loaded) return
            val f = File(ctx.applicationContext.filesDir, "memory/routines.enc")
            file = f; loaded = true
            val text = try { SecureStore.read(f) } catch (_: Throwable) { null } ?: return
            try {
                val arr = JSONArray(text)
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { o ->
                    val steps = o.optJSONArray("steps")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                    val name = o.optString("name")
                    if (name.isNotBlank() && steps.isNotEmpty()) routines[name] = Routine(name, steps)
                }
            } catch (_: Throwable) {}
        }
    }

    @Synchronized fun names(): List<String> = routines.keys.toList()
    @Synchronized fun get(name: String): Routine? = routines[name]
    @Synchronized fun all(): List<Routine> = routines.values.toList()
    @Synchronized fun put(r: Routine) { routines[r.name] = r; save() }
    @Synchronized fun remove(name: String): Boolean { val had = routines.remove(name) != null; if (had) save(); return had }
    @Synchronized fun clearForTest() { routines.clear() }

    private fun save() {
        val f = file ?: return
        val arr = JSONArray()
        routines.values.forEach { r -> arr.put(JSONObject().put("name", r.name).put("steps", JSONArray(r.steps))) }
        try { SecureStore.write(f, arr.toString()) } catch (_: Throwable) {}
    }

    /** "the time, the weather and my messages". */
    fun spokenSteps(steps: List<String>): String = when (steps.size) {
        0 -> ""; 1 -> steps[0]; else -> steps.dropLast(1).joinToString(", ") + " and " + steps.last()
    }
}
