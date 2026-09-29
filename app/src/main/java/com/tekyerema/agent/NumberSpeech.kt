package com.tekyerema.agent

/**
 * How a phone number is SAID back to someone who can't see it, and which Ghana network it belongs to. Pure.
 *
 * Why (research: docs/agent/research/african_voice_and_protocols.md §Recommendations 1, assistive_tech_blind.md §4):
 * strong recognisers still get 15–25% of spoken Ghanaian-English digits wrong, and a run of ten digits read one by one
 * is hard to check. People say and check numbers in groups — 055 · 323 · 8011 — so every read-back of a number is
 * grouped 3-3-4, with a pause between groups, and a money read-back also names the network ("an MTN number"). A group
 * is what the user can correct ("the middle is 4 5 6", [NumberCorrection]).
 *
 * The spoken form keeps the digits apart ("0 5 5, 3 2 3, 8 0 1 1") rather than "055 323 8011": the confirmation is
 * spoken as written, and a voice reads "8011" as "eight thousand and eleven".
 */
object NumberSpeech {

    /** Digits only; a Ghana number written internationally (+233 / 233 + 9 digits) folds to its 0… form. */
    fun normalize(raw: String?): String {
        val d = raw?.filter { it.isDigit() }.orEmpty()
        return if (d.length == 12 && d.startsWith("233")) "0" + d.substring(3) else d
    }

    /** A 10-digit number starting with 0 — the shape of every Ghana mobile number. */
    fun isGhanaMobileShape(raw: String?): Boolean = normalize(raw).let { it.length == 10 && it.startsWith("0") }

    /**
     * The number in the groups people say it in: 10-digit Ghana numbers 3-3-4 ("055", "323", "8011"); a short code
     * (≤ 4 digits) as one group; anything else in threes with a lone last digit joined to the group before it.
     */
    fun groups(raw: String?): List<String> {
        val d = normalize(raw)
        if (d.isEmpty()) return emptyList()
        if (d.length == 10 && d.startsWith("0")) return listOf(d.substring(0, 3), d.substring(3, 6), d.substring(6))
        if (d.length <= 4) return listOf(d)
        val g = d.chunked(3).toMutableList()
        if (g.size > 1 && g.last().length == 1) { val tail = g.removeAt(g.lastIndex); g[g.lastIndex] = g.last() + tail }
        return g
    }

    /** Spoken grouping: "0 5 5, 3 2 3, 8 0 1 1" (digits apart, a comma pause between groups). A non-Ghana
     *  international number keeps its "plus". Empty input gives "". */
    fun spoken(raw: String?): String {
        val g = groups(raw)
        if (g.isEmpty()) return ""
        val plus = raw?.trim()?.startsWith("+") == true && !isGhanaMobileShape(raw)
        return (if (plus) "plus " else "") + g.joinToString(", ") { it.toList().joinToString(" ") }
    }

    // ---------------------------------------------------------------- networks

    /**
     * Code default for `connectors.mobileMoney.prefixes` (connectors.json is the source of truth when present).
     * NCA numbering as of 2026: MTN 024/025/053/054/055/059, Telecel (ex-Vodafone) 020/050, AirtelTigo 026/027/056/057.
     * The research note lists the same prefixes (african_voice_and_protocols.md, recommendation 1).
     */
    private val DEFAULT_PREFIXES: Map<String, List<String>> = linkedMapOf(
        "mtn" to listOf("024", "025", "053", "054", "055", "059"),
        "telecel" to listOf("020", "050"),
        "airteltigo" to listOf("026", "027", "056", "057")
    )

    /** What a person calls each network (config keys → spoken names). */
    private val NAMES = mapOf("mtn" to "MTN", "telecel" to "Telecel", "airteltigo" to "AirtelTigo", "vodafone" to "Telecel")

    private fun prefixes(): Map<String, List<String>> {
        val cfg = com.tekyerema.core.config.AppConfig.stringListMap("connectors.mobileMoney.prefixes")
            .filterValues { it.isNotEmpty() }
        return cfg.ifEmpty { DEFAULT_PREFIXES }
    }

    /** The network key ("mtn" | "telecel" | "airteltigo", the connectors.json keys) for a Ghana mobile number, or null
     *  when it isn't one or its prefix is unknown. */
    fun networkKey(raw: String?): String? {
        val d = normalize(raw)
        if (d.length != 10 || !d.startsWith("0")) return null
        val p = d.substring(0, 3)
        return prefixes().entries.firstOrNull { (_, list) -> p in list }?.key
    }

    /** "MTN" | "Telecel" | "AirtelTigo" for a Ghana mobile number, else null. */
    fun ghanaNetwork(raw: String?): String? = networkKey(raw)?.let { networkName(it) }

    /** The spoken name of a network key ("mtn" → "MTN"). */
    fun networkName(key: String): String = NAMES[key.lowercase()] ?: key.replaceFirstChar { it.uppercase() }

    /** "an MTN number" / "a Telecel number" — the article follows the SOUND: an acronym is said letter by letter
     *  ("an MTN"), a word by its first letter ("an AirtelTigo", "a Telecel"). */
    fun networkPhrase(name: String): String {
        val acronym = name.length <= 4 && name.all { it.isUpperCase() }
        val vowelSound = if (acronym) name.first() in "AEFHILMNORSX" else name.first().lowercaseChar() in "aeiou"
        return (if (vowelSound) "an " else "a ") + "$name number"
    }

    /**
     * A money read-back of [raw]: the grouped number with its network ("0 5 5, 3 2 3, 8 0 1 1, an MTN number") and, when
     * the user should double-check, why — its prefix isn't a Ghana network we know, or it is on a different network from
     * the SIM ([simKey], a connectors.json key; null = unknown, no comparison). Never blocks: the user decides.
     */
    fun moneyReadBack(raw: String?, simKey: String? = null): Pair<String, String?> {
        val said = spoken(raw)
        val key = networkKey(raw)
        if (key == null) return said to "That number doesn't start like an MTN, Telecel or AirtelTigo number, so check it."
        val phrase = "$said, ${networkPhrase(networkName(key))}"
        val sim = simKey?.lowercase()?.takeIf { it.isNotBlank() }
        val warn = if (sim != null && sim != key)
            "Your SIM is on ${networkName(sim)}, so check the number before you send." else null
        return phrase to warn
    }
}
