package com.tekyerema.money.fraud

import com.tekyerema.core.config.AppConfig

/**
 * How risky are the links in a message? (MASTER_STATUS #13, fraud step 2: "warnings on LINKS", 2 Nov 2026.) Pure, warn-only:
 * it reads the text, never opens a link, never blocks anything. [ScamScore.cues] turns the findings into spoken reasons.
 *
 * Signals (each one a thing a careful person checks before tapping):
 *  - LOOKALIKE: the address names a brand people trust here (MTN, MoMo, Telecel, AT, a bank, GRA, SSNIT, NHIS, the
 *    government…) but is not that brand's own website ("mtn-gh-reward.xyz", "rnomo-verify.com", "ecobank.secure-login.top");
 *  - UNSAFE: punycode ("xn--…": letters that only look Latin), a bare IP address instead of a name, or an "@" that hides the
 *    real host ("https://mtn.com.gh@evil.site");
 *  - HIDDEN: a link shortener (bit.ly, tinyurl…) — you can't see where it goes;
 *  - WEAK: plain http (not https), or an ending scammers favour (.xyz, .top, .click…), or a long chain of sub-domains.
 * LOOKALIKE and UNSAFE warn on their own. HIDDEN and WEAK warn only with an action cue ("verify", "claim", "blocked"…), so
 * a friend's shortened link to a song stays quiet. Official domains: [OFFICIAL] plus `fraud.links.trustedDomains`.
 */
object LinkRisk {
    enum class Signal { LOOKALIKE, UNSAFE, HIDDEN, WEAK }

    data class Link(val raw: String, val host: String, val signals: Set<Signal>, val brand: String?, val official: Boolean = false)

    // The brand names a lookalike borrows, with the word to say ("MTN", "your bank"…).
    private val BRANDS = linkedMapOf(
        "mtn" to "MTN", "momo" to "MTN MoMo", "mobilemoney" to "Mobile Money", "telecel" to "Telecel", "vodafone" to "Telecel",
        "airteltigo" to "AT", "atmoney" to "AT Money", "gcb" to "your bank", "ecobank" to "your bank", "absa" to "your bank",
        "stanbic" to "your bank", "fidelity" to "your bank", "calbank" to "your bank", "zenith" to "your bank",
        "ghipss" to "GhIPSS", "gra" to "the GRA", "ssnit" to "SSNIT", "nhis" to "NHIS", "bog" to "the Bank of Ghana",
        "ghanagov" to "the government", "gov-gh" to "the government", "whatsapp" to "WhatsApp", "facebook" to "Facebook",
        "paypal" to "PayPal")

    /** The brands' own sites (the host itself or any sub-domain of it is genuine). */
    private val OFFICIAL = listOf(
        "mtn.com.gh", "mtn.com", "mtnonline.com", "momo.mtn.com", "telecel.com.gh", "vodafone.com.gh", "at.com.gh",
        "airteltigo.com.gh", "gcbbank.com.gh", "ecobank.com", "absa.com.gh", "stanbicbank.com.gh", "fidelitybank.com.gh",
        "calbank.net", "zenithbank.com.gh", "ghipss.net", "gov.gh", "ssnit.org.gh", "nhis.gov.gh", "bog.gov.gh",
        "whatsapp.com", "wa.me", "facebook.com", "fb.com", "paypal.com")

    private val SHORTENERS = setOf("bit.ly", "tinyurl.com", "t.co", "cutt.ly", "is.gd", "rb.gy", "shorturl.at", "tiny.cc",
        "ow.ly", "s.id", "rebrand.ly", "goo.gl", "t.ly", "bitly.com", "short.io", "v.gd", "lnkd.in", "buff.ly", "qrco.de")

    private val RISKY_ENDINGS = setOf("xyz", "top", "click", "link", "live", "shop", "online", "site", "icu", "buzz", "rest",
        "cfd", "sbs", "monster", "work", "info", "biz", "loan", "win", "bid", "vip", "app", "support", "tk", "ml", "ga", "cf", "gq")

    private val URL = Regex("(?i)\\b(?:https?://[^\\s<>\"]+|www\\.[^\\s<>\"]+|(?:[a-z0-9-]+\\.)+[a-z]{2,24}(?:/[^\\s<>\"]*)?)")
    private val IP = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")
    private val ACTION = Regex("(?i)\\b(?:verify|verification|claim|reward|bonus|unlock|activate|update your|confirm|blocked|" +
        "suspended|locked|deactivat\\w*|reactivat\\w*|login|log in|sign in|reverse|reversal|refund|won|winner|prize|promo|" +
        "expire[sd]?|urgent|immediately|within 24)\\b")

    fun trustedDomains(): List<String> = OFFICIAL + AppConfig.stringList("fraud.links.trustedDomains", emptyList())

    /** Every link in [text] with its signals (empty = no links). */
    fun links(text: String?): List<Link> {
        if (text.isNullOrBlank()) return emptyList()
        return URL.findAll(text).mapNotNull { m -> parse(m.value.trimEnd('.', ',', ')', '!', '?', ';', ':', '\'')) }.toList()
    }

    /** True when the words around the links ask the reader to act ("verify", "claim", "your account is blocked"…). */
    fun asksToAct(text: String?): Boolean = !text.isNullOrBlank() && ACTION.containsMatchIn(text)

    fun parse(raw: String): Link? {
        val noScheme = raw.replace(Regex("(?i)^https?://"), "")
        // "user@host" before the first "/" hides the real host: the part after the @ is where it goes.
        val authority = noScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val atTrick = '@' in authority
        val host = authority.substringAfterLast('@').substringBefore(':').lowercase().trimEnd('.').removePrefix("www.")
        if (host.isBlank() || '.' !in host) return null
        val ending = host.substringAfterLast('.')
        if (ending.length < 2 || ending.all { it.isDigit() } && !IP.matches(host)) return null
        val signals = mutableSetOf<Signal>()
        val official = trustedDomains().any { host == it || host.endsWith(".$it") }
        val brand = if (official) null else brandIn(host)
        if (brand != null) signals += Signal.LOOKALIKE
        if (atTrick || IP.matches(host) || host.split('.').any { it.startsWith("xn--") }) signals += Signal.UNSAFE
        if (host in SHORTENERS) signals += Signal.HIDDEN
        val http = raw.startsWith("http://", ignoreCase = true)
        if (!official && (http || ending in RISKY_ENDINGS || host.count { it == '.' } >= 4)) signals += Signal.WEAK
        return Link(raw, host, signals, brand, official)
    }

    /** The trusted brand a host borrows, read the way an eye skims it: "rn" as "m", 0 as o, 1 as l, hyphens dropped. */
    fun brandIn(host: String): String? {
        val skim = host.split('.').dropLast(1)                       // the ending (.com, .gh) never names a brand
            .map { it.replace("rn", "m").replace('0', 'o').replace('1', 'l') }
        val words = skim.flatMap { it.split('-') }.filter { it.isNotEmpty() }
        val joined = skim.map { it.replace("-", "") }
        for ((key, say) in BRANDS) {
            val k = key.replace("-", "")
            // Short names (mtn, gra, bog, gcb, absa, momo, nhis) only as a whole word or with a telling ending ("mtnghana",
            // "gcbbank-login", "momoapp"), so "grace-church" and "bogus" stay quiet. Longer names anywhere in a label.
            val hit = if (k.length <= 5) words.any { w -> w == k || w.startsWith(k) && (w.drop(k.length) in SHORT_TAILS || w.drop(k.length).all(Char::isDigit)) }
                else joined.any { k in it }
            if (hit) return say
        }
        return null
    }

    private val SHORT_TAILS = setOf("gh", "ghana", "online", "money", "momo", "pay", "app", "web", "secure", "bank", "login",
        "verify", "promo", "reward", "bonus", "support", "help", "care", "net", "portal", "update")
}
