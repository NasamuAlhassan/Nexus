package com.tekyerema.android

import android.content.Context
import android.content.Intent

/** Resolves a natural-language app name to an installed launchable package. */
object AppResolver {

    data class AppInfo(val label: String, val packageName: String)

    fun launchable(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0).mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            val label = ri.loadLabel(pm)?.toString() ?: pkg
            AppInfo(label, pkg)
        }.distinctBy { it.packageName }
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

    fun resolveDetailed(context: Context, query: String): Match {
        val apps = launchable(context)
        val q = query.trim().lowercase()
        apps.firstOrNull { it.packageName.equals(query, true) }?.let { return Match.One(it) }
        apps.firstOrNull { it.label.equals(query, true) }?.let { return Match.One(it) }
        apps.filter { it.label.lowercase().contains(q) }.minByOrNull { it.label.length }?.let { return Match.One(it) }
        // Token overlap: pick the app that matches the MOST query tokens (ties → shortest label), NOT the
        // first app that happens to contain ANY single token (§1.9 — that mis-picked "Messages" for the
        // token "message" out of "open whatsapp and send prince a message").
        val tokens = q.split(Regex("[\\s.]+")).filter { it.length > 2 }
        if (tokens.isNotEmpty()) {
            val scored = apps.map { app -> app to tokens.count { app.label.lowercase().contains(it) } }
                .filter { it.second > 0 }
            scored.maxWithOrNull(compareBy({ it.second }, { -it.first.label.length }))?.let { return Match.One(it.first) }
        }
        // Tolerant fallback for ASR-mangled app names (e.g. Twi ASR renders "YouTube" as "yutu"):
        // (1) the query is a SUBSEQUENCE of the label (yutu ⊂ y-o-u-t-u-b-e); prefer the shortest label.
        // (2) else the closest label(s) by normalized edit distance. If the top two are within 0.1 of each
        //     other they are a real tie → report Ambiguous so the agent asks instead of guessing (§1.10).
        val qc = q.replace(Regex("[^a-z0-9]"), "")
        if (qc.length >= 3) {
            apps.filter { isSubsequence(qc, it.label.lowercase().replace(Regex("[^a-z0-9]"), "")) }
                .minByOrNull { it.label.length }?.let { return Match.One(it) }
            val ranked = apps.map { it to similarity(qc, it.label.lowercase().replace(Regex("[^a-z0-9]"), "")) }
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
