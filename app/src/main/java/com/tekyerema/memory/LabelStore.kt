package com.tekyerema.memory

import android.content.Context
import android.util.Log
import com.tekyerema.accessibility.UiElement
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.core.SecureStore
import com.tekyerema.core.config.AppConfig
import java.io.File

/**
 * Label memory on the phone (MASTER_STATUS #38): the names of unlabelled buttons, one encrypted file
 * (files/memory/button_names.enc, [SecureStore] — AES-GCM, Keystore key), loaded once, rewritten on change. Offline, never
 * uploaded. Logs say how many names there are, never what they are. Switch: `memory.labels.enabled` (default true).
 *
 * Names come from (1) the user ([LabelCommands]: "that button is Search"), (2) a verified outcome ([afterStep] +
 * [LabelInference]: a tap that provably opened a search box). They are put on a snapshot by [apply], called where the
 * accessibility service builds each snapshot, so the planner, the policy and every spoken reading see the same name; the
 * element is marked `learned`. See [LabelSignature] for how a control is recognised and [LabelCore] for what is never kept.
 */
object LabelStore {
    private const val TAG = "Tekyerema"

    @Volatile private var entries: List<LabelEntry> = emptyList()
    @Volatile private var file: File? = null
    @Volatile private var loaded = false
    @Volatile private var loading = false
    private var failedLoads = 0

    /** The unnamed control the agent pressed last ("that button"), and when. Memory only, never saved. */
    class Tapped(val sig: LabelSig, val appName: String?, val at: Long)
    @Volatile private var lastTap: Tapped? = null
    @Volatile private var lastTaught: LabelSig? = null

    private const val TAP_WINDOW_MS = 3 * 60_000L

    fun enabled(): Boolean = AppConfig.bool("memory.labels.enabled", true)

    /** Load once (a Keystore decrypt, so not on the main thread). An unreadable file leaves the store unloaded and memory-only. */
    fun ensureLoaded(ctx: Context?) {
        if (loaded || ctx == null) return
        synchronized(this) {
            if (loaded) return
            val f = File(ctx.applicationContext.filesDir, "memory/button_names.enc")
            val parsed = try { LabelCore.decode(SecureStore.read(f)) } catch (_: Throwable) { null }
            if (f.exists() && parsed == null && ++failedLoads < 3) {
                Log.w(TAG, "[LABELS] couldn't read the saved button names (try $failedLoads) — not overwriting them")
                return
            }
            file = f
            loaded = true
            parsed?.let { entries = LabelCore.prune(it, System.currentTimeMillis()) }
        }
    }

    fun snapshot(): List<LabelEntry> = entries

    @Synchronized private fun update(ctx: Context?, change: (List<LabelEntry>) -> List<LabelEntry>) {
        ensureLoaded(ctx)
        entries = change(entries)
        save()
    }

    private fun save() {
        val f = file ?: return   // unit tests, or an unreadable file: memory only
        try {
            if (entries.isEmpty()) f.delete() else SecureStore.write(f, LabelCore.encode(entries))
            Log.i(TAG, "[LABELS] ${entries.size} button names")
        } catch (_: Throwable) {}
    }

    // ---------------------------------------------------------------- applying (called for every snapshot)

    /**
     * [snap] with the remembered names on their controls. Cheap when nothing is remembered. The first call after start only
     * kicks off the (background) load and returns [snap] unchanged — never a Keystore read on the caller's thread.
     */
    fun apply(ctx: Context?, snap: UiSnapshot): UiSnapshot {
        if (!enabled()) return snap
        if (!loaded) {
            if (ctx != null && !loading) {
                loading = true
                kotlin.concurrent.thread(isDaemon = true, name = "label-load") { try { ensureLoaded(ctx) } finally { loading = false } }
            }
            return snap
        }
        val list = entries
        if (list.isEmpty() || com.tekyerema.core.Privacy.secureStep) return snap
        return try { LabelSignature.apply(snap, list) } catch (_: Throwable) { snap }
    }

    // ---------------------------------------------------------------- teaching

    sealed class Taught {
        data class Saved(val name: String) : Taught()
        object BadName : Taught()
        object NotSure : Taught()      // the control cannot be told apart reliably: nothing is kept
        object Off : Taught()
    }

    /** Keep [name] for the control [sig] in the app [appName]. [told] = the user said it. */
    fun teach(ctx: Context?, sig: LabelSig, appName: String?, rawName: String, told: Boolean): Taught {
        if (!enabled()) return Taught.Off
        if (com.tekyerema.core.Privacy.secureStep) return Taught.NotSure
        val name = LabelCore.cleanName(rawName) ?: return Taught.BadName
        val now = System.currentTimeMillis()
        update(ctx) { LabelCore.upsert(it, LabelEntry(sig, name, appName?.takeIf { a -> a.isNotBlank() }, told, now, now), now) }
        lastTaught = sig
        return Taught.Saved(name)
    }

    /** The signature of [element] on [snap], or null when it may not be named or cannot be told from its neighbours. */
    fun sigFor(snap: UiSnapshot, element: UiElement): LabelSig? = LabelSignature.signatures(snap)[element.id]

    /** The unnamed control the agent pressed in the last few minutes in the app now in front ("that button"), or null. */
    fun recentTap(currentPkg: String?): Tapped? {
        val t = lastTap ?: return null
        if (System.currentTimeMillis() - t.at > TAP_WINDOW_MS) return null
        return if (currentPkg == null || t.sig.pkg == currentPkg) t else null
    }

    /** "forget that button name": the name taught last. True when something was removed. */
    fun forgetLast(ctx: Context?): Boolean {
        val s = lastTaught ?: return false
        val before = entries.size
        update(ctx) { LabelCore.remove(it, s) }
        lastTaught = null
        return entries.size < before
    }

    /** Forget the names of one app (by what the user calls it), or all when [app] is null. Returns how many were removed. */
    fun forget(ctx: Context?, app: String?): Int {
        ensureLoaded(ctx)
        val before = entries.size
        if (app == null) update(ctx) { emptyList() } else update(ctx) { LabelCore.forgetApp(it, app).first }
        lastTaught = null
        return before - entries.size
    }

    // ---------------------------------------------------------------- learning from use

    /**
     * Called once per executed step (AgentEngine, after the verifier ran). Notes which unnamed control a tap pressed (for "that
     * button is …") and, when the verified outcome proves what it is ([LabelInference]), keeps the name. Never throws.
     */
    fun afterStep(ctx: Context?, tool: String, target: UiElement?, before: UiSnapshot, after: UiSnapshot?, verified: Boolean) {
        try {
            if (!enabled() || tool != "tap" || com.tekyerema.core.Privacy.secureStep) return
            if (target == null) return
            if (!target.label.isNullOrBlank() && !target.learned) { lastTap = null; return }   // a named control: "that button" moves on
            val sig = sigFor(before, target) ?: run { lastTap = null; return }
            lastTap = Tapped(sig, before.appName, System.currentTimeMillis())
            if (!verified || after == null || target.learned) return
            val name = LabelInference.infer(before, target, after) ?: return
            if (teach(ctx, sig, before.appName, name, told = false) is Taught.Saved)
                Log.i(TAG, "[LABELS] learned a button name from a verified outcome (${entries.size} names)")
        } catch (_: Throwable) {}
    }
}
