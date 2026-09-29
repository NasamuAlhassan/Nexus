package com.tekyerema.agent

import com.tekyerema.core.config.AppConfig

/**
 * C-01 — the request tiers, cheapest first, from config `router.tiers` (default: all five, in this order):
 *
 *  - `fast_path`      T0: deterministic device ops and phone reads ([LocalFastRouter]) — no model, no network.
 *  - `direct_answer`  T1: a knowledge question answered without observing the screen ([DirectAnswer]).
 *  - `retrieval`      T2: current facts via `web_search` ([RetrievalNeed] decides whether a question needs it).
 *  - `local_brain`    T3: the on-device rule brain ([LocalBrain]) for routine operations, offline.
 *  - `cloud_operator` T4: the cloud model operating the phone step by step.
 *
 * Removing a tier from the list skips it (the request falls through to the next one); the cloud operator can be
 * removed to keep a build fully on-device. The router's other inputs — network strength (N-10), provider quota
 * (N-15), a local plan going wrong (C-17), the action's risk (policy) — are applied inside each tier.
 */
object RouterTiers {
    const val FAST_PATH = "fast_path"
    const val DIRECT = "direct_answer"
    const val RETRIEVAL = "retrieval"
    const val LOCAL = "local_brain"
    const val CLOUD = "cloud_operator"
    val DEFAULT = listOf(FAST_PATH, DIRECT, RETRIEVAL, LOCAL, CLOUD)

    /** Tiers that send the request to an online service (the cloud model or the web). */
    val ONLINE = setOf(DIRECT, RETRIEVAL, CLOUD)

    /** WS-C: "cloud off" (or today's budget spent — OperatorPolicy) removes the online tiers; the router then works on the
     *  phone only. The user can only remove tiers here, never add one the config left out. */
    fun enabled(tier: String): Boolean =
        tier in AppConfig.stringList("router.tiers", DEFAULT).map { it.trim().lowercase() } &&
            (tier !in ONLINE || com.tekyerema.operator.OperatorPolicy.cloudUsable())
}
