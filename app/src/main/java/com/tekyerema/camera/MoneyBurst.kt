package com.tekyerema.camera

/**
 * Money read from a BRIEF capture, then one decision (owner, 29 Oct 2026: "it should take a brief capture and analyse it accurately
 * instead of always watching"). The camera used to speak whenever two frames agreed — a held 50 that flickered to 200 for two frames
 * was said as 200. Now, once money is in view, the frames of a short window (~2 s) are collected and the money is decided ONCE:
 *
 *  - each frame is either a sure list of pieces (a single reading over its class's gate, or a pile where every piece was read) or
 *    "unsure";
 *  - the list said is the one that holds at least [share] of all the window's frames and at least [minAgree] of them; otherwise the
 *    answer is "not sure".
 *
 * Online, the activity also asks the cloud about one sharp frame of the same window and says the answer only when both agree
 * ([agrees]). Pure → unit-tested.
 */
class MoneyBurst(
    private val windowMs: Long = 2_000L,
    private val minFrames: Int = 4,
    private val minAgree: Int = 3,
    private val share: Double = 0.6,
    private val base: Double = 0.85
) {
    sealed class Verdict {
        data class Sure(val items: List<MoneyItems.Item>) : Verdict()
        object Unsure : Verdict()
    }

    private val frames = ArrayList<List<MoneyItems.Item>?>()
    private var startedAt = 0L
    @Volatile private var open = false
    @Volatile var done = false
        private set

    /** Money came into view and the window is open (read from the analyser thread to keep a frame for the cloud). */
    val started: Boolean get() = open

    fun reset() { frames.clear(); startedAt = 0L; open = false; done = false }

    /** One frame's single reading. Before any money, "not money" does not open the window. */
    fun offerSingle(cls: String, conf: Double, now: Long): Verdict? {
        if (!started && !CurrencyAnnouncer.isMoney(cls)) return null
        val item = MoneyItems.sureItem(MoneyItems.Reading(cls, conf), base)
        return add(item?.let { listOf(it) }, now)
    }

    /** One frame with several pieces: sure only when every piece seen was read. */
    fun offerItems(c: MoneyItems.Count, now: Long): Verdict? =
        add(if (c.unsure == 0 && c.sure.isNotEmpty()) c.sure else null, now)

    private fun add(f: List<MoneyItems.Item>?, now: Long): Verdict? {
        if (done) return null
        if (!open) { open = true; startedAt = now }
        frames.add(f?.sortedWith(compareBy({ it.value }, { it.note })))
        if (now - startedAt < windowMs || frames.size < minFrames) return null
        done = true
        return decide()
    }

    internal fun decide(): Verdict {
        val winner = frames.filterNotNull().groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return Verdict.Unsure
        return if (winner.value >= minAgree && winner.value >= share * frames.size) Verdict.Sure(winner.key) else Verdict.Unsure
    }

    companion object {
        /** The pieces as (value, note) → how many. */
        fun counts(items: List<MoneyItems.Item>): Map<Pair<Double, Boolean>, Int> =
            items.groupingBy { it.value to it.note }.eachCount()

        /** The phone's sure reading and the cloud's count of the same frame say the same money. */
        fun agrees(phone: List<MoneyItems.Item>, cloud: Map<Pair<Double, Boolean>, Int>?): Boolean = cloud != null && counts(phone) == cloud

        /** Every piece the phone read is among the cloud's, and the cloud saw more (notes held together: the phone reads the top one). */
        fun within(phone: List<MoneyItems.Item>, cloud: Map<Pair<Double, Boolean>, Int>?): Boolean =
            cloud != null && cloud.values.sum() > phone.size && counts(phone).all { (k, n) -> (cloud[k] ?: 0) >= n }
    }
}
