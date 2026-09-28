package com.tekyerema.android

import android.content.Context
import android.content.Intent

/** Resolves a natural-language app name to an installed launchable package. */
object AppResolver {

    data class AppInfo(val label: String, val packageName: String)

    /** H-11: loading every launcher label is ~100-300 ms on the A20e and happened on every "open …"; the list is
     *  cached briefly (installs are rare, and a miss re-queries once before giving up). */
    @Volatile private var cached: Pair<Long, List<AppInfo>>? = null

    fun launchable(context: Context, fresh: Boolean = false): List<AppInfo> {
        val ttl = com.tekyerema.core.config.AppConfig.long("cache.apps.ttlMs", 120_000L)
        cached?.let { (at, list) -> if (!fresh && System.currentTimeMillis() - at < ttl) return list }
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0).mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            val label = ri.loadLabel(pm)?.toString() ?: pkg
            AppInfo(label, pkg)
        }.distinctBy { it.packageName }.also { cached = System.currentTimeMillis() to it }
    }

    /** Result of resolving a name: exactly one app, a genuine ambiguity to ask about, or nothing. */
    sealed class Match {
        data class One(val app: AppInfo) : Match()
        data class Ambiguous(val options: List<AppInfo>) : Match()
        object None : Match()
    }

    /** Back-compat: a single confident match, else null. An [Match.Ambiguous] result is treated as
     *  "not resolved" here (null) so a caller that can't ask never silently launches a coin-flip guess. */
    fun resolve(context: Context, query: String): AppInfo? = (resolveDetailed(context, query) as? Match.One)?.app

    fun resolveDetailed(context: Context, query: String): Match =
        match(launchable(context), query).let { m ->
            // H-11: a miss against the cached list re-queries once (the app may have just been installed).
            if (m is Match.None) match(launchable(context, fresh = true), query) else m
        }

    /** Words that carry no app identity ("open THE zorblax APP") — stripped before fuzzy matching so they
     *  can't produce a match on their own (the token "app" used to match "MBWhatsApp"). Exact label/package
     *  equality still runs on the raw query first, so an app literally named e.g. "My Files" still resolves. */
    private val FILLER = setOf(
        "the", "a", "an", "my", "me", "for", "please", "app", "apps", "application", "applications",
        "open", "launch", "start", "up", "go", "to"
    )

    /** Vendor prefixes people say but launcher labels usually drop ("google maps" → "Maps", "samsung notes"
     *  → "Notes"). Dropped only when another word remains, so "open google" still means the Google app. */
    private val VENDOR = setOf("google", "samsung", "microsoft", "android")

    private fun compact(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    /**
     * E-01 / C-03 — names people (and the ASR) use for an app that its launcher label doesn't contain. Keys are
     * compacted (lower-case letters and digits only), values are the launcher label to look for. Seen on the A20e:
     * "install cloud AI" (Claude), "YT music". `os.apps.aliases` in config adds or overrides entries.
     * Deliberately NO bare generic words ("cloud", "music", "chat"): an alias must name one app, not a category.
     * A value is "package|label|other label", tried in order and EXACTLY: labels differ by phone and version — on the
     * A20e YouTube Music is labelled "YT Music", so the alias to the label "YouTube Music" missed, the words fell to
     * token overlap, and "open YouTube Music" opened YouTube.
     */
    private const val YT_MUSIC = "com.google.android.apps.youtube.music|YouTube Music|YT Music"
    private const val CHATGPT = "com.openai.chatgpt|ChatGPT"
    private const val CLAUDE = "com.anthropic.claude|Claude"
    private const val PLAY = "com.android.vending|Play Store|Google Play Store"
    private const val MESSAGES = "com.samsung.android.messaging|com.google.android.apps.messaging|Messages"
    private const val WHATSAPP = "com.whatsapp|WhatsApp"
    private const val GEMINI = "com.google.android.apps.bard|Gemini"
    private val DEFAULT_ALIASES = mapOf(
        "ytmusic" to YT_MUSIC, "youtubemusic" to YT_MUSIC, "ytm" to YT_MUSIC, "youtubemusicapp" to YT_MUSIC,
        "yt" to "com.google.android.youtube|YouTube", "chatgbt" to CHATGPT, "chatgpd" to CHATGPT, "chatgp" to CHATGPT,
        "chatgpt" to CHATGPT, "openai" to CHATGPT,
        "claudeai" to CLAUDE, "cloudai" to CLAUDE, "clawedai" to CLAUDE, "anthropic" to CLAUDE,
        "googleplay" to PLAY, "playstore" to PLAY, "googleplaystore" to PLAY,
        "sms" to MESSAGES, "textmessages" to MESSAGES, "samsungmessages" to MESSAGES,
        "whatsup" to WHATSAPP, "watsapp" to WHATSAPP, "wassap" to WHATSAPP,
        "googlegemini" to GEMINI, "bard" to GEMINI, "jiji" to "com.olx.ssa.gh|Jiji", "jijighana" to "com.olx.ssa.gh|Jiji",
        "gmail" to "com.google.android.gm|Gmail"
    )

    /** The launcher label an alias stands for, or null. Pure → unit-tested. */
    internal fun aliasFor(query: String): String? {
        val words = query.trim().lowercase().split(Regex("[\\s.,]+")).filter { it.isNotEmpty() && it !in FILLER }
        val key = compact(words.joinToString(""))
        if (key.isEmpty()) return null
        val cfg = com.tekyerema.core.config.AppConfig.obj("os.apps.aliases")
        cfg?.optString(key)?.takeIf { it.isNotBlank() }?.let { return it }
        return DEFAULT_ALIASES[key]
    }

    /** Pure resolution core (unit-tested): [apps] = the launchable apps, [query] = the spoken name.
     *  [tolerant]=false skips the fuzzy subsequence/edit-distance rescue — for GUESSING an app out of free text,
     *  where only a confident name match may launch anything. */
    internal fun match(apps: List<AppInfo>, query: String, tolerant: Boolean = true): Match = match(apps, query, tolerant, aliased = false)

    private fun match(apps: List<AppInfo>, query: String, tolerant: Boolean, aliased: Boolean): Match {
        val raw = query.trim().lowercase()
        if (raw.isEmpty()) return Match.None
        // An alias names ONE app; resolve it strictly (no fuzzy rescue on the alias), and only if that app is here —
        // otherwise the original words still get the normal matching below. One hop only: a config alias cycle
        // (a → b, b → a) must not recurse forever.
        if (!aliased) aliasFor(raw)?.takeIf { !it.equals(raw, true) }?.let { alias ->
            // Exact only: a package id, or a label equal (ignoring case/spacing) to one of the alias's names.
            for (name in alias.split('|').map { it.trim() }.filter { it.isNotEmpty() }) {
                apps.firstOrNull { it.packageName.equals(name, true) || it.label.equals(name, true) ||
                    compact(it.label) == compact(name) }?.let { return Match.One(it) }
            }
        }
        apps.firstOrNull { it.packageName.equals(raw, true) }?.let { return Match.One(it) }
        apps.firstOrNull { it.label.equals(raw, true) }?.let { return Match.One(it) }
        // ASR splits/joins names ("whats app", "you tube"): equal once spacing/punctuation is ignored.
        val rawC = compact(raw)
        if (rawC.isNotEmpty()) apps.filter { compact(it.label) == rawC }.minByOrNull { it.label.length }
            ?.let { return Match.One(it) }

        val words = raw.split(Regex("[\\s.,]+")).filter { it.isNotEmpty() && it !in FILLER }
        if (words.isEmpty()) return Match.None
        val core = words.filter { it !in VENDOR }.ifEmpty { words }
        val q = core.joinToString(" ")
        val w = words.joinToString(" ")
        apps.firstOrNull { it.label.equals(w, true) || it.label.equals(q, true) }?.let { return Match.One(it) }
        apps.filter { it.label.lowercase().contains(q) }.minByOrNull { it.label.length }?.let { return Match.One(it) }
        // Token overlap: pick the app that matches the MOST query tokens (ties → shortest label), NOT the
        // first app that happens to contain ANY single token (§1.9 — that mis-picked "Messages" for the
        // token "message" out of "open whatsapp and send prince a message").
        // Honesty rule: if a distinctive word of the name matches NO installed app at all, the user is naming
        // an app we don't have — a partial hit on the other words is a wrong app, not a fuzzy match
        // ("zorblax messenger" must not open Messenger). Such queries skip overlap and only get the tolerant
        // whole-name fallbacks below, which can still rescue an ASR-mangled single name.
        val tokens = core.filter { it.length > 2 }
        val labels = apps.map { it.label.lowercase() }
        val unknownWord = tokens.any { t -> t.length >= 4 && labels.none { it.contains(t) } }
        if (tokens.isNotEmpty() && !unknownWord) {
            val scored = apps.map { app -> app to tokens.count { app.label.lowercase().contains(it) } }
                .filter { it.second > 0 }
            scored.maxWithOrNull(compareBy({ it.second }, { -it.first.label.length }))?.let { return Match.One(it.first) }
        }
        // Tolerant fallback for ASR-mangled app names (e.g. Twi ASR renders "YouTube" as "yutu"):
        // (1) the query is a SUBSEQUENCE of the label (yutu ⊂ y-o-u-t-u-b-e); prefer the shortest label.
        // (2) else the closest label(s) by normalized edit distance. If the top two are within 0.1 of each
        //     other they are a real tie → report Ambiguous so the agent asks instead of guessing (§1.10).
        val qc = compact(q)
        if (tolerant && qc.length >= 3) {
            apps.filter { isSubsequence(qc, compact(it.label)) }
                .minByOrNull { it.label.length }?.let { return Match.One(it) }
            val ranked = apps.map { it to similarity(qc, compact(it.label)) }
                .filter { it.second >= 0.62 }.sortedByDescending { it.second }
            if (ranked.isNotEmpty()) {
                val top = ranked.first()
                val ties = ranked.drop(1).takeWhile { top.second - it.second <= 0.1 }.map { it.first }
                return if (ties.isEmpty()) Match.One(top.first) else Match.Ambiguous(listOf(top.first) + ties)
            }
        }
        return Match.None
    }

    /** True if every char of [needle] appears in [hay] in order (not necessarily contiguously). */
    private fun isSubsequence(needle: String, hay: String): Boolean {
        if (needle.isEmpty()) return false
        var i = 0
        for (c in hay) if (i < needle.length && needle[i] == c) i++
        return i == needle.length
    }

    /** 1 - normalizedLevenshtein, in [0,1]; 1.0 == identical. */
    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return 1.0 - dp[b.length].toDouble() / maxOf(a.length, b.length)
    }

    fun launchIntent(context: Context, pkg: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(pkg)
}
