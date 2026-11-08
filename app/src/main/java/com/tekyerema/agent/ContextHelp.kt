package com.tekyerema.agent

/**
 * K-01 / K-10 — "what can you do?" and "what can I say here?". Blind users can't see a menu of features, and the
 * research on voice assistants keeps finding the same thing: people don't know what they can say, try a few things,
 * fail, and give up (Abdolrahmani et al., ASSETS 2018; Pradhan et al., CHI 2018). Siri's on-screen help needs an
 * iPhone 15 Pro; TalkBack's "describe screen" says what is there, not what you can ask for. So this answers from the
 * app in front with a few things that REALLY work there (each one a phrase the fast path or the agent handles), and
 * with a short tour otherwise. No model, works offline.
 *
 * This is not an app manual for the model (BrainPrompts keeps those out on purpose): it is spoken to the user only.
 * Pure, unit-tested.
 */
object ContextHelp {
    private data class AppHelp(val name: String, val examples: List<String>)

    /** Package → what to say there. Samsung (A20e) and stock/Google variants of the same app share an entry. */
    private val BY_PACKAGE: Map<String, AppHelp> = buildMap {
        fun add(pkgs: List<String>, help: AppHelp) = pkgs.forEach { put(it, help) }
        add(listOf("com.whatsapp", "com.whatsapp.w4b"), AppHelp("WhatsApp", listOf(
            "what did I miss", "read my messages from Ama", "reply to Ama saying I'm on my way", "call Kofi on WhatsApp")))
        add(listOf("org.telegram.messenger", "org.telegram.messenger.web"), AppHelp("Telegram", listOf(
            "what did I miss", "read my messages from Kofi", "reply to Kofi saying okay")))
        add(listOf("com.google.android.apps.youtube.music"), AppHelp("YouTube Music", listOf(
            "play Sarkodie on YouTube Music", "next song", "what's playing", "pause")))
        add(listOf("com.google.android.youtube"), AppHelp("YouTube", listOf(
            "search YouTube for Ghana news", "pause", "turn it up", "read the screen")))
        add(listOf("com.android.chrome", "com.sec.android.app.sbrowser"), AppHelp("the browser", listOf(
            "search the web for the cedi rate", "read the whole page", "scroll down", "go back")))
        add(listOf("com.samsung.android.messaging", "com.google.android.apps.messaging"), AppHelp("Messages", listOf(
            "read my last text", "text Ama saying I'm coming", "who texted me")))
        add(listOf("com.samsung.android.dialer", "com.google.android.dialer", "com.android.dialer"), AppHelp("the phone app", listOf(
            "who called me", "call my mother", "answer", "hang up")))
        add(listOf("com.samsung.android.app.contacts", "com.google.android.contacts", "com.android.contacts"), AppHelp("Contacts", listOf(
            "find Ama's number", "remember that Ama Mensah is my mother", "save 0244 123 456 as Kofi")))
        add(listOf("com.sec.android.app.camera", "com.google.android.GoogleCamera", "com.android.camera2"), AppHelp("the camera", listOf(
            "take a photo", "take a selfie", "what's in front of me", "read this label")))
        add(listOf("com.sec.android.gallery3d", "com.google.android.apps.photos"), AppHelp("the gallery", listOf(
            "describe this picture", "what's in this photo", "go back")))
        add(listOf("com.android.vending"), AppHelp("the Play Store", listOf(
            "install Telegram", "is WhatsApp installed", "go back")))
        add(listOf("com.sec.android.app.popupcalculator", "com.google.android.calculator"), AppHelp("the calculator", listOf(
            "what is 25 times 4", "what is 20 percent of 150")))
        add(listOf("com.android.settings"), AppHelp("Settings", listOf(
            "turn on Bluetooth", "open Wi-Fi settings", "turn on battery saver", "is data saver on")))
        add(listOf("com.openai.chatgpt"), AppHelp("ChatGPT", listOf(
            "ask ChatGPT how to cook jollof", "read the screen")))
        add(listOf("com.sec.android.app.clockpackage", "com.google.android.deskclock"), AppHelp("the clock", listOf(
            "set an alarm for 6 AM", "remind me in 20 minutes to check the food", "what alarms do I have")))
        add(listOf("com.google.android.apps.maps"), AppHelp("Maps", listOf(
            "where am I", "read the screen", "go back")))
    }

    /** The home screen: the tour is more useful there than "open an app". */
    private val LAUNCHERS = setOf("com.sec.android.app.launcher", "com.google.android.apps.nexuslauncher", "com.android.launcher3")

    /**
     * The spoken tour of what Tɛkyerɛma can do, with the emergency line last so it is heard even in a hurry. Voice-UX
     * (2026-09-29): rewritten for today's features — Mobile Money up to the PIN, alerts, "check my phone", the helper,
     * "speak Twi" — and cut to about 20 seconds (the old tour ran over 30 and missed most of them). Each quoted phrase
     * works as said; the app-by-app detail stays in [forApp] ("what can I say here?"). No numbers or counts (spoken-output
     * rule). Measured in HelpAndLeftOffTest.
     */
    const val GENERAL = "I call, text and WhatsApp, reading it back first, and do Mobile Money up to the PIN, which you enter. " +
        "I read messages and notifications, open apps and settings, and set reminders, routines and alerts. " +
        "Try “play my downloads”, “take a selfie”, “check my phone”, “call my helper” or “speak Twi”. " +
        "Anywhere, ask “what can I say here?”. In an emergency, say “call the ambulance”."

    /** What to say for the app in front ([pkg], shown as [label]); the general tour when there's no app or it's ours. */
    fun forApp(pkg: String?, label: String?, ownPkg: String = "com.tekyerema"): String {
        if (pkg.isNullOrBlank() || pkg == ownPkg || pkg in LAUNCHERS) return GENERAL
        val known = BY_PACKAGE[pkg]
        return if (known != null) "In ${known.name} you can say: " + quoteList(known.examples) + ". Or say “read the screen” to hear what's here."
        else {
            val name = label?.takeIf { it.isNotBlank() && it != pkg } ?: "this app"
            "In $name you can say “read the screen”, “what changed”, “scroll down” or “go back” — " +
                "or just tell me what you want done here, and I'll do it."
        }
    }

    private fun quoteList(xs: List<String>): String {
        val q = xs.map { "“$it”" }
        return if (q.size <= 1) q.joinToString("") else q.dropLast(1).joinToString(", ") + " or " + q.last()
    }

    // ---------------------------------------------------------------- phrasing (pure)

    /** "what can I say here", "what can you do in this app", "help me with this app" → true (this app). */
    private val HERE = Regex("^(?:(?:hey |okay |ok )?(?:tekyerema|tɛkyerɛma|nexus)[, ]+)?(?:" +
        "what can (?:i|you) (?:say|do|ask(?: for)?) (?:here|in (?:this|the) app|on this screen|with this app|in here)" +
        "|what (?:can|do) i say here|help(?: me)? (?:with|in) this app" +
        "|how do i use this app|what are my options here|what(?:'s| is) possible here" +
        ")(?: please)?$")
    /** "what can you do", "what can I say", "what are your features" → the general tour. Twi "wobɛtumi ayɛ dɛn"
     *  (what can you do) is PENDING NATIVE REVIEW. A bare "help" / "help me" is deliberately NOT here: said in distress
     *  it is the emergency path (LocalBrain.RE_EMERGENCY → the confirmed emergency tool), never a feature tour. */
    private val GENERAL_RE = Regex("^(?:(?:hey |okay |ok )?(?:tekyerema|tɛkyerɛma|nexus)[, ]+)?(?:" +
        "what can you do|what can you do for me|what else can you do|what can i say|what can i ask(?: you)?|" +
        "what do you do|what are your (?:features|commands|skills)|what commands (?:do you know|can i use)|how do i use you|" +
        "how can you help(?: me)?|wobɛtumi ayɛ dɛn|wobetumi aye den" +
        ")(?: please)?$")

    /** true = help for the app in front, false = the general tour, null = not a help request. */
    fun classify(g: String): Boolean? = when {
        HERE.matches(g) -> true
        GENERAL_RE.matches(g) -> false
        else -> null
    }
}
