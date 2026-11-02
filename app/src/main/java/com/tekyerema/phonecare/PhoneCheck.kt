package com.tekyerema.phonecare

import android.content.Context
import com.tekyerema.operator.WeakModelPolicy

/**
 * "Check my phone" — a spoken self-test (openclaw's `doctor`, docs/agent/research/openclaw.md §5.1). Zero LLM, read-only.
 *
 * WHY (A20e, 2026-09-29): a hackathon judge used the phone while (1) its Private DNS (AdGuard, DNS-over-TLS) stalled every
 * network call, (2) the accessibility service was unbound after a reinstall, and (3) the main AI's daily quota (Groq
 * gpt-oss-120b, all four keys) was spent, so the weaker backup model drove tasks. Nothing told the user any of it. Now one
 * short sentence does, each problem with its one-line fix.
 *
 * Three layers: [PhoneProbe.probe] reads the phone (thin, Android); [findings] turns the facts into problems (pure);
 * [summarise] makes the one spoken line (pure). Problems are ordered by how much they stop Tɛkyerɛma working; at most
 * [MAX_SPOKEN] are spoken, and never a count or a step number (owner rule, 2026-09-29). English only: no Twi table has
 * these lines yet (Twi versions PENDING NATIVE SPEAKER).
 */
object PhoneCheck {
    const val MAX_SPOKEN = 4
    const val ALL_GOOD = "Everything I need is working."
    const val REST_GOOD = "Everything else is working."

    /** Is the internet answering? [UNKNOWN] = couldn't tell (no probe possible) — never reported as a problem. */
    enum class Net { ONLINE, OFFLINE, NOT_ANSWERING, UNKNOWN }

    /** What the probe saw. The defaults are a healthy phone, so a test states only what it changes. */
    data class Facts(
        val micGranted: Boolean = true,
        /** Tɛkyerɛma is switched on in Settings › Accessibility. */
        val screenControlOn: Boolean = true,
        /** …and the service is actually connected (an AccessibilityController is attached). */
        val screenControlBound: Boolean = true,
        /** The accessibility button / shortcut starts Tɛkyerɛma (null = couldn't read it). */
        val a11yShortcut: Boolean? = true,
        val wakeWordOn: Boolean = true,
        val floatingButtonOn: Boolean = true,
        val notificationAccess: Boolean = true,
        /** The notification listener is connected (access can be granted while the binding is lost). */
        val notificationListenerBound: Boolean = true,
        val callsGranted: Boolean = true,
        val contactsGranted: Boolean = true,
        val smsGranted: Boolean = true,
        /** Exempt from battery optimisation (PowerManager.isIgnoringBatteryOptimizations). */
        val batteryUnrestricted: Boolean = true,
        /** Android 9+: the user or Samsung restricted the app in the background. */
        val backgroundRestricted: Boolean = false,
        val network: Net = Net.ONLINE,
        val weakNetwork: Boolean = false,
        /** Settings.Global private_dns_mode: "off" | "opportunistic" | "hostname" (null = unknown). */
        val privateDnsMode: String? = null,
        val privateDnsHost: String? = null,
        /** Samsung wording for Settings paths ("Connections, More connection settings") — else stock Android's. */
        val samsung: Boolean = true,
        val cloudAllowed: Boolean = true,
        val budgetSpent: Boolean = false,
        /** Some online AI can be used (a key exists for an allowed provider). */
        val onlineAi: Boolean = true,
        val mainModel: String = "openai/gpt-oss-120b",
        /** ms until any key may call the main model again (0 = free now). Over [DAILY_WAIT_MS] = its daily allowance. */
        val mainModelWaitMs: Long = 0L,
        /** The model that answered last ("groq/openai/gpt-oss-20b"), for the on-screen details. */
        val lastModel: String? = null,
        val weakPolicy: WeakModelPolicy = WeakModelPolicy.ASK,
        val aiCallsToday: Int = 0,
        /** Speech in: the phone's recognizer (builtin profile) or the on-device model (ghana profile); null = unknown. */
        val speechIn: Boolean? = true,
        /** The Ghana profile listens with the on-device model (else the phone's own speech recognition). */
        val onDeviceSpeech: Boolean = false,
        /** A text-to-speech engine is installed; null = unknown. */
        val speechOut: Boolean? = true,
        val speakAloud: Boolean = true,
        /** The Twi voice was tried and isn't available — the sentence to say (voice/TwiVoiceStatus.problem); null = fine or unused. */
        val twiVoiceProblem: String? = null,
        val batteryPct: Int? = null,
        val charging: Boolean = false
    )

    /** One problem: what's wrong (first) and the one-line fix. [rank] orders them — higher is said first. */
    data class Finding(val key: String, val rank: Int, val problem: String, val fix: String? = null) {
        val spoken: String get() = if (fix.isNullOrBlank()) problem else "$problem $fix"
    }

    /** A per-minute limit refills within a minute; a wait this long is the daily allowance (OpenAiCompatBrain's rule). */
    const val DAILY_WAIT_MS = 90_000L
    private const val OPEN_SETTINGS = "To fix it: in Nexus, open Settings, Permissions,"
    private const val A11Y_PATH = "Settings, Accessibility, Installed apps, Nexus screen control"

    /** The problems in [f], most important first. Pure. */
    fun findings(f: Facts): List<Finding> {
        val out = ArrayList<Finding>()
        fun add(key: String, rank: Int, problem: String, fix: String?) { out.add(Finding(key, rank, problem, fix)) }

        // ---- hearing and speaking: without these nothing else matters
        if (!f.micGranted) add("mic", 100, "I can't use the microphone, so I can't hear you.", "$OPEN_SETTINGS Microphone.")
        if (f.speechIn == false) {
            if (f.onDeviceSpeech) add("speech_in", 88, "My on-device speech model isn't on this phone, so I can only hear you while you're online.", null)
            else add("speech_in", 88, "Your phone's speech recognition isn't available, so I can't hear you.",
                "To fix it: update Speech Services by Google, or the Google app, from the Play Store.")
        }
        if (f.speechOut == false) add("speech_out", 86, "Your phone has no text-to-speech voice, so I can't talk to you.",
            "To fix it: install Speech Services by Google from the Play Store.")
        // Audit 2026-10-01: the lab's Twi voice rejected its key and Twi replies went silent / English with no word to the user.
        f.twiVoiceProblem?.takeIf { it.isNotBlank() }?.let { add("twi_voice", 60, it, null) }

        // ---- screen control (the 2026-09-29 reinstall left it unbound)
        if (!f.screenControlOn) add("screen_control", 95, "Screen control is off, so I can't open apps or tap for you.",
            "To fix it: $A11Y_PATH, and switch it on.")
        else if (!f.screenControlBound) add("screen_control", 95, "Screen control is switched on but isn't running, so I can't open apps or tap for you.",
            "To fix it: $A11Y_PATH, switch it off, then on again.")

        // ---- the internet (the 2026-09-29 Private DNS stall)
        val dnsHost = f.privateDnsHost?.trim()?.takeIf { f.privateDnsMode.equals("hostname", true) && it.isNotEmpty() }
        when (f.network) {
            Net.OFFLINE -> add("internet", 90, "You're offline, so I can only do simple phone commands.", "To fix it: turn on Wi-Fi or mobile data.")
            Net.NOT_ANSWERING -> add("internet", 90, "You're connected, but the internet isn't answering, so online answers will fail.",
                if (dnsHost != null) null else "To fix it: switch Wi-Fi off and on again, or try mobile data.")
            else -> {}
        }
        if (dnsHost != null) {
            // When the internet is also failing, the DNS setting is the likely cause: said right after it, as its fix.
            val rank = if (f.network == Net.NOT_ANSWERING || f.weakNetwork) 89 else 72
            add("private_dns", rank, "Your Private DNS is set to ${spokenDnsHost(dnsHost)}, which can slow me down.", "To fix it: ${privateDnsPath(f.samsung)}.")
        }
        if (f.weakNetwork && f.network == Net.ONLINE && dnsHost == null)
            add("slow_network", 25, "Your connection is slow, so online answers will take longer.", null)

        // ---- the online AI (the 2026-09-29 daily quota)
        when {
            !f.cloudAllowed -> add("cloud_off", 35, "The cloud is off, so bigger tasks and web answers won't work.",
                "Say “use the cloud again” to switch it back on.")
            f.budgetSpent -> add("budget", 80, "You've reached today's online AI limit, so bigger tasks wait until tomorrow.",
                "To change the limit: in Nexus, open Settings, Control, Limits and usage.")
            !f.onlineAi -> add("no_ai", 80, "No online AI is set up, so bigger tasks won't work.",
                "To fix it: in Nexus, open Settings, Control, Your API keys.")
            f.mainModelWaitMs > DAILY_WAIT_MS -> add("ai_quota", 78, "My main AI has used up its allowance for now, " + when (f.weakPolicy) {
                WeakModelPolicy.ALLOW -> "so a smaller backup AI is doing bigger tasks, and it can make mistakes."
                WeakModelPolicy.NEVER -> "so I'll pause bigger tasks until it's back."
                WeakModelPolicy.ASK -> "so I'll ask before a smaller backup AI takes over a task."
            }, "Nothing to fix: it comes back by itself ${spokenWait(f.mainModelWaitMs)}. Phone commands still work.")
        }

        // ---- reading notifications
        if (!f.notificationAccess) add("notifications", 70, "I can't see your notifications, so I can't read your messages or alert you.",
            "$OPEN_SETTINGS Read messages.")
        else if (!f.notificationListenerBound) add("notifications", 70, "Notification access is on, but it isn't connected.",
            "To fix it: restart the phone.")

        // ---- phone actions
        if (!f.callsGranted || !f.contactsGranted) add("calls", 60, when {
            !f.callsGranted && !f.contactsGranted -> "I can't make calls or look up your contacts."
            !f.callsGranted -> "I can't make phone calls for you."
            else -> "I can't look up your contacts."
        }, "$OPEN_SETTINGS Calls and contacts.")
        if (!f.smsGranted) add("sms", 55, "I can't send text messages for you.", "$OPEN_SETTINGS Text messages.")

        // ---- staying awake (Samsung puts unused apps to sleep: the wake word and alerts stop)
        if (f.backgroundRestricted) add("sleep", 50, "Android is restricting me in the background, so “Hey Tɛkyerɛma” and alerts can stop.",
            "To fix it: Settings, Apps, Nexus, Battery, and allow background activity.")
        else if (!f.batteryUnrestricted) add("sleep", 50, "Android may put me to sleep, so “Hey Tɛkyerɛma” and alerts can stop working.",
            "To fix it: in Nexus, open Settings, Accessibility, Keep Nexus running.")

        // ---- small things
        val pct = f.batteryPct
        if (pct != null && pct in 0..LOW_BATTERY && !f.charging) add("battery", 45, "Your battery is at $pct percent.", "Plug in your charger soon.")
        if (!f.speakAloud) add("speak_aloud", 40, "Reading replies aloud is switched off.",
            "To switch it on: in Nexus, open Settings, Voice, Read replies aloud.")
        if (f.a11yShortcut == false && !f.wakeWordOn && !f.floatingButtonOn)
            add("start", 20, "There's no quick way to start me from other apps.",
                "To add one: in Nexus, open Settings, Accessibility, and switch on “Hey Tɛkyerɛma” or the floating Talk button.")
        return out.sortedByDescending { it.rank }
    }

    const val LOW_BATTERY = 15

    /**
     * The Private DNS host as said aloud: a plain public name ("dns.adguard.com") is named; a personal one
     * ("abc123.d.adguard-dns.com" — AdGuard / NextDNS put an account or device id in it) is only "a custom server", so
     * the id isn't read out on the loudspeaker. The full host stays on screen ([details]). Pure.
     */
    fun spokenDnsHost(host: String): String {
        val h = host.trim().lowercase().trimEnd('.')
        val labels = h.split('.')
        val plain = labels.size in 2..3 && h.length <= 30 && labels.all { l -> l.isNotEmpty() && l.all { it.isLetter() || it == '-' } }
        return if (plain) h else "a custom server"
    }

    /** Where Private DNS lives: Samsung One UI (the A20e) or stock Android. */
    fun privateDnsPath(samsung: Boolean): String =
        if (samsung) "Settings, Connections, More connection settings, Private DNS, Automatic"
        else "Settings, Network and internet, Private DNS, Automatic"

    /** "in about 3 hours" / "in about 40 minutes" / "in a minute or two". Pure. */
    fun spokenWait(ms: Long): String {
        val min = ((ms + 59_999L) / 60_000L).coerceAtLeast(1L)
        return when {
            min <= 2 -> "in a minute or two"
            min < 60 -> "in about $min minutes"
            else -> {
                val h = ((min + 30) / 60).coerceAtLeast(1)
                if (h == 1L) "in about an hour" else "in about $h hours"
            }
        }
    }

    /**
     * The one spoken line: the problems first (most important first, at most [max]), each with its fix; then "Everything
     * else is working." — or, with nothing wrong, "Everything I need is working." and the battery level. No counts, no
     * step numbers. Pure.
     */
    fun summarise(findings: List<Finding>, batteryPct: Int? = null, charging: Boolean = false, max: Int = MAX_SPOKEN): String {
        val top = findings.sortedByDescending { it.rank }.take(max.coerceAtLeast(1))
        if (top.isEmpty()) {
            val battery = batteryPct?.takeIf { it in 0..100 }?.let { " Your battery is at $it percent" + (if (charging) " and charging." else ".") }.orEmpty()
            return ALL_GOOD + battery
        }
        val body = top.joinToString(" ") { it.spoken }
        return if (findings.size <= max) "$body $REST_GOOD" else body
    }

    /** The checks as short on-screen lines ("Screen control: on") — for Settings › Phone care, never spoken. Pure. */
    fun details(f: Facts): List<String> {
        fun onOff(b: Boolean) = if (b) "on" else "off"
        val ai = when {
            !f.cloudAllowed -> "cloud off"
            f.budgetSpent -> "today's limit reached"
            !f.onlineAi -> "not set up"
            f.mainModelWaitMs > DAILY_WAIT_MS -> "${shortModel(f.mainModel)} out of allowance, back ${spokenWait(f.mainModelWaitMs)}"
            else -> shortModel(f.mainModel)
        }
        return listOfNotNull(
            "Microphone: ${onOff(f.micGranted)}",
            "Screen control: " + if (!f.screenControlOn) "off" else if (!f.screenControlBound) "on, not running" else "on",
            "Read messages: " + if (!f.notificationAccess) "off" else if (!f.notificationListenerBound) "on, not connected" else "on",
            "Calls and contacts: ${onOff(f.callsGranted && f.contactsGranted)}",
            "Text messages: ${onOff(f.smsGranted)}",
            "Keep running: ${onOff(f.batteryUnrestricted && !f.backgroundRestricted)}",
            "Internet: " + when (f.network) { Net.ONLINE -> if (f.weakNetwork) "slow" else "working"; Net.OFFLINE -> "offline"
                Net.NOT_ANSWERING -> "not answering"; Net.UNKNOWN -> "not checked" },
            "Private DNS: " + when {
                f.privateDnsMode.equals("hostname", true) && !f.privateDnsHost.isNullOrBlank() -> f.privateDnsHost!!.trim()
                f.privateDnsMode.equals("off", true) -> "off"
                f.privateDnsMode.equals("opportunistic", true) -> "automatic"
                else -> "unknown"
            },
            "Online AI: $ai",
            f.lastModel?.let { "Last answer from: ${shortModel(it)}" },
            "Online AI calls today: ${f.aiCallsToday}",
            "Speech in: " + when (f.speechIn) { true -> if (f.onDeviceSpeech) "on-device model" else "phone's recognizer"; false -> "not available"; null -> "unknown" },
            "Speech out: " + when (f.speechOut) { true -> "phone's voice"; false -> "not available"; null -> "unknown" },
            "“Hey Tɛkyerɛma”: ${onOff(f.wakeWordOn)}",
            f.batteryPct?.let { "Battery: $it%" + if (f.charging) ", charging" else "" }
        )
    }

    /** "groq/openai/gpt-oss-120b" → "gpt-oss-120b"; "gemini" stays. Pure. */
    fun shortModel(m: String): String = m.substringAfterLast('/').ifBlank { m }

    /** Probe the phone, then the spoken line. The probe may make one tiny internet check (no model). */
    suspend fun run(ctx: Context): String {
        val facts = PhoneProbe.probe(ctx)
        val found = findings(facts)
        android.util.Log.i("Tekyerema", "[PHONE_CHECK] problems=${found.joinToString(",") { it.key }.ifEmpty { "none" }}")
        return summarise(found, facts.batteryPct, facts.charging)
    }
}
