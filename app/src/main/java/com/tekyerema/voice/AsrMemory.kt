package com.tekyerema.voice

/**
 * Whether the phone has room to load an on-device speech model — decided BEFORE the load, because the alternative is
 * the whole app being killed (audit 2026-10-01, V00/V01: one clip through the offline recogniser on the A20e, ~800 MB
 * available, and Android's low-memory killer took the process twelve times in a row; the accessibility service was
 * unbound for ~30 s after each).
 *
 * The numbers: a model read from the APK's assets is first read into memory and then parsed into the inference
 * session, so loading costs about twice its size at the peak (the 652 MB offline English encoder took the process to
 * ~1.5 GB when it was killed on 2026-09-30). A model opened from a file path costs less. On top of that a reserve is
 * left for the rest of the app.
 *
 *  - room to spare            → [Verdict.LOAD]
 *  - not enough, nothing freed yet → [Verdict.RELEASE_THEN_LOAD]: the caller frees what it can ([release]) and asks again
 *  - still not enough         → [Verdict.REFUSE]: the caller throws [AsrLowMemoryException], whose message is what
 *    the user is told. A refused request is a sentence; a killed app is silence and a dead screen reader.
 *
 * [Decision.unloadAfterUse]: the model fits, but with it resident less than [Limits.keepFreeBytes] would remain —
 * load it for this utterance and let it go again.
 *
 * Pure (no Android) → unit-tested.
 */
object AsrMemory {
    const val MB = 1024L * 1024L

    /** What the user hears instead of the app dying. */
    const val SPOKEN = "I can't listen offline on this phone right now — memory is too low. It works when you're online."

    enum class Verdict { LOAD, RELEASE_THEN_LOAD, REFUSE }

    data class Decision(val verdict: Verdict, val unloadAfterUse: Boolean = false, val neededBytes: Long = 0L)

    data class Limits(
        /** Peak memory while loading, as a multiple of the model's size. */
        val peakFactor: Double = PEAK_FROM_ASSETS,
        /** Left for everything else in the app while the model loads. */
        val reserveBytes: Long = 100L * MB,
        /** With the model resident, less than this left free ⇒ unload after each use. */
        val keepFreeBytes: Long = 400L * MB
    )

    const val PEAK_FROM_ASSETS = 2.2
    const val PEAK_FROM_FILES = 1.5

    /** Bytes that must be available before a model of [modelBytes] is loaded. */
    fun needed(modelBytes: Long, limits: Limits = Limits()): Long =
        (modelBytes * limits.peakFactor).toLong() + limits.reserveBytes

    /**
     * @param availBytes ActivityManager.MemoryInfo.availMem (Long.MAX_VALUE when it couldn't be read)
     * @param lowMemory MemoryInfo.lowMemory — the system itself says it is short
     * @param modelBytes the model's files, summed; ≤ 0 = unknown, in which case there is nothing to judge by and the
     *   load goes ahead as it always did
     * @param alreadyReleased the caller has already freed what it could ([release]) and is asking a second time
     */
    fun decide(availBytes: Long, lowMemory: Boolean, modelBytes: Long, alreadyReleased: Boolean, limits: Limits = Limits()): Decision {
        if (modelBytes <= 0L) return Decision(Verdict.LOAD)
        val need = needed(modelBytes, limits)
        if (!lowMemory && availBytes >= need) {
            val leftWithModel = availBytes - modelBytes
            return Decision(Verdict.LOAD, unloadAfterUse = leftWithModel < limits.keepFreeBytes, neededBytes = need)
        }
        return Decision(if (alreadyReleased) Verdict.REFUSE else Verdict.RELEASE_THEN_LOAD, neededBytes = need)
    }

    // ------------------------------------------------------------------ things that can give memory back

    private val hooks = java.util.WeakHashMap<Any, () -> Unit>()

    /**
     * Register something that can free memory when a model needs room (a clip cache, another model that reloads on
     * demand). [owner] is held weakly; [hook] must not capture it. Hooks must be quick and must never throw for long.
     */
    fun onRelease(owner: Any, hook: () -> Unit) { synchronized(hooks) { hooks[owner] = hook } }

    /** Run every registered hook; returns how many ran. Failures are swallowed — freeing memory must never break a turn. */
    fun release(): Int {
        val run = synchronized(hooks) { hooks.values.toList() }
        var n = 0
        for (h in run) try { h(); n++ } catch (_: Throwable) {}
        return n
    }
}

/**
 * The on-device recogniser was not loaded because the phone has too little free memory. [message] is the sentence for
 * the user ([AsrMemory.SPOKEN]); the numbers are for the log.
 */
class AsrLowMemoryException(val availMb: Long, val neededMb: Long) : AsrException(AsrMemory.SPOKEN)
