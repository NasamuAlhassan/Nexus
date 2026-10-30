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
    private val base: Double = 0.85,
    /** The second rule's floor (0 = off): see [decide]. */
    private val likelyFloor: Double = 0.6
) {
    sealed class Verdict {
        /** [likely]: by the second rule, not the gate — said as "I think this is…". */
        data class Sure(val items: List<MoneyItems.Item>, val likely: Boolean = false) : Verdict()
        object Unsure : Verdict()
    }

    private val frames = ArrayList<List<MoneyItems.Item>?>()
    /** Every single reading of the window, sure or not (the second rule). */
    private val readings = ArrayList<MoneyItems.Reading>()
    private var startedAt = 0L
    @Volatile private var open = false
    @Volatile var done = false
        private set

    /** Money came into view and the window is open (read from the analyser thread to keep a frame for the cloud). */
    val started: Boolean get() = open

    fun reset() { frames.clear(); readings.clear(); startedAt = 0L; open = false; done = false }

    /** One frame's single reading. Before any money, "not money" does not open the window. */
    fun offerSingle(cls: String, conf: Double, now: Long): Verdict? {
        if (!started && !CurrencyAnnouncer.isMoney(cls)) return null
        val item = MoneyItems.sureItem(MoneyItems.Reading(cls, conf), base)
        if (!done) readings.add(MoneyItems.Reading(cls, conf))
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
        val winner = frames.filterNotNull().groupingBy { it }.eachCount().maxByOrNull { it.value }
        if (winner != null && winner.value >= minAgree && winner.value >= share * frames.size) return Verdict.Sure(winner.key)
        return likely() ?: Verdict.Unsure
    }

    /**
     * The second rule (owner's offline rehearsal, Infinix, 29 Oct 2026, 22:42: a held 10 was read as 10 at 0.66, 0.79, 0.81, 0.97 and
     * never three times over the 0.85 gate, so the camera said "not sure" for two minutes). ONE note, read as the same value at least
     * twice over [likelyFloor] (once over 0.8), and NO other value over the floor anywhere in the window — a held 50 that flickered to 200
     * (28 Oct) has both, and stays "not sure". Said as likely, never as sure.
     */
    private fun likely(): Verdict? {
        if (likelyFloor <= 0.0) return null
        val over = readings.filter { CurrencyAnnouncer.isMoney(it.cls) && it.conf >= likelyFloor }
        val items = over.mapNotNull { MoneyItems.itemOf(it.cls) }
        val first = items.firstOrNull() ?: return null
        if (items.any { it != first } || items.size < 2 || over.none { it.conf >= 0.8 }) return null
        return Verdict.Sure(listOf(first), likely = true)
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
