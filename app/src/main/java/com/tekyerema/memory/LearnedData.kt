package com.tekyerema.memory

import android.content.Context

/**
 * "What Nexus has learned" (Settings page, 2 Nov 2026): one read-and-delete door onto the things the phone learns, so the
 * UI never reads another package's files or prefs. Each store is an encrypted file (a Keystore decrypt), so call these
 * OFF the main thread (Dispatchers.IO). Names only — no screen words, codes, numbers or message text are kept in any of them.
 */
object LearnedData {

    // ---- teach mode (teach/, #24): the skills the user showed once ("data bundle")

    /** The taught skills' names, A–Z. */
    fun taughtSkills(ctx: Context): List<String> {
        val store = com.tekyerema.teach.TeachStore.shared
        store.ensureLoaded(ctx)
        return store.names().sortedBy { it.lowercase() }
    }

    /** Delete one taught skill by its exact name (as listed). True when it existed. */
    fun deleteSkill(ctx: Context, name: String): Boolean {
        val store = com.tekyerema.teach.TeachStore.shared
        store.ensureLoaded(ctx)
        return store.remove(name)
    }

    // ---- label memory (memory/Label*, #38): names the user gave unlabelled buttons

    /** App label → the button names remembered in it (A–Z); apps without a label are grouped as "Other apps". */
    fun buttonNames(ctx: Context): Map<String, List<String>> {
        LabelStore.ensureLoaded(ctx)
        return LabelStore.snapshot()
            .groupBy({ it.appName?.trim()?.takeIf { n -> n.isNotEmpty() } ?: OTHER_APPS }, { it.name })
            .mapValues { (_, names) -> names.distinct().sortedBy { it.lowercase() } }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
    }

    /** Forget every button name in one app (the label as listed by [buttonNames]). Returns how many were removed. */
    fun forgetButtons(ctx: Context, appLabel: String): Int =
        if (appLabel == OTHER_APPS) forgetUnnamedApps(ctx) else LabelStore.forget(ctx, appLabel)

    /** Forget every remembered button name. Returns how many were removed. */
    fun forgetAllButtons(ctx: Context): Int = LabelStore.forget(ctx, null)

    const val OTHER_APPS = "Other apps"

    /** The "Other apps" group has no name to match by: remove the entries with no app label, one at a time. */
    private fun forgetUnnamedApps(ctx: Context): Int {
        LabelStore.ensureLoaded(ctx)
        val unnamed = LabelStore.snapshot().filter { it.appName.isNullOrBlank() }
        var n = 0
        for (e in unnamed) if (LabelStore.forgetSig(ctx, e.sig)) n++
        return n
    }
}
