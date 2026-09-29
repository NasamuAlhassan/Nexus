package com.tekyerema.agent.research

import com.tekyerema.agent.search.SearchText
import com.tekyerema.agent.search.Source
import com.tekyerema.core.config.AppConfig

/**
 * Where a spoken referent ("University of Ghana Sakai") lives on the web, taken DETERMINISTICALLY from a search's
 * sources — host + title only. Search text never becomes an instruction or a URL of its own; guide/aggregator sites
 * are dropped; the organisation's own domain is preferred. Pure → unit-tested (the Sakai example: sakai.ug.edu.gh
 * over yen.com.gh / mabumbe.com, research brief §B.1).
 */
data class Candidate(
    val host: String,
    /** https only (an http source is upgraded; the browser says so if the site has no https). */
    val url: String,
    val title: String,
    val score: Double,
    /** Under an institutional domain (edu / gov / ac / mil). */
    val institutional: Boolean
)

object Candidates {
    /** Search engines, social networks, encyclopedias, and Ghanaian news/"how to" guide sites that rank for portal
     *  names without being the portal. Extend with `answers.locate.guideHosts`. */
    private val GUIDE_HOSTS = listOf(
        "google.com", "bing.com", "duckduckgo.com", "yahoo.com", "exa.ai", "vertexaisearch.cloud.google.com",
        "facebook.com", "twitter.com", "x.com", "instagram.com", "linkedin.com", "tiktok.com", "youtube.com", "pinterest.com",
        "wikipedia.org", "quora.com", "reddit.com", "medium.com", "blogspot.com", "wordpress.com", "scribd.com",
        "yen.com.gh", "mabumbe.com", "ghanaweb.com", "myjoyonline.com", "pulse.com.gh", "modernghana.com", "graphic.com.gh",
        "citinewsroom.com", "3news.com", "ghanaeducation.org", "educationghana.org", "myschoolgist.com", "ghanaportal.org",
        "ghanapolitics.net", "gh.opera.news", "opera.news", "africa-updates.com", "zionhill.com", "ghanadmission.com"
    )
    private val GUIDE_TITLE = Regex("(?i)\\b(how to|guide|step[- ]by[- ]step|steps? to|tutorial|everything you need|explained|full list|" +
        "requirements|faqs?|what should i do|i can'?t|problems?|troubleshoot|news|update[sd]?|latest)\\b")
    private val INSTITUTIONAL_SLD = setOf("edu", "gov", "ac", "mil")
    private val SECOND_LEVEL = setOf("com", "co", "org", "gov", "edu", "net", "ac", "or", "ne", "go", "mil", "sch")
    private val STOP = setOf("of", "the", "and", "for", "my", "a", "an", "in", "at", "on", "to", "website", "web", "site", "page",
        "homepage", "portal", "login", "log", "sign", "official", "online", "link")
    /** Organisation-kind words: they say WHAT it is, not WHICH one — never the distinctive "brand" token. */
    private val GENERIC = setOf("university", "college", "school", "polytechnic", "academy", "institute", "bank", "ministry",
        "authority", "commission", "hospital", "clinic", "council", "assembly", "agency", "embassy", "service", "services",
        "board", "government", "company", "limited", "ltd", "student", "students", "staff", "ghana", "national")
    private val DOMAINISH = Regex("^[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+$")
    private val REDIRECT_HOSTS = listOf("vertexaisearch.cloud.google.com")

    fun tokens(referent: String): List<String> = referent.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        .split(Regex("\\s+")).filter { it.isNotEmpty() && it !in STOP }

    /** Initials of every run of 2+ consecutive tokens, with the tokens each one stands for ("university ghana" → "ug"). */
    internal fun acronyms(tokens: List<String>): Map<String, Set<String>> {
        val out = HashMap<String, Set<String>>()
        for (i in tokens.indices) for (j in i + 1 until tokens.size) {
            val run = tokens.subList(i, j + 1)
            out.putIfAbsent(run.joinToString("") { it.take(1) }, run.toSet())
        }
        return out
    }

    /** The organisation's registrable domain: "sakai.ug.edu.gh" → "ug.edu.gh", "www.mtn.com.gh" → "mtn.com.gh". */
    fun registrable(host: String): String {
        val l = host.lowercase().removePrefix("www.").split('.').filter { it.isNotEmpty() }
        if (l.size <= 2) return l.joinToString(".")
        return if (l.last().length == 2 && l[l.size - 2] in SECOND_LEVEL) l.takeLast(3).joinToString(".") else l.takeLast(2).joinToString(".")
    }

    private fun institutional(host: String): Boolean {
        val l = host.lowercase().split('.')
        return l.last() in setOf("edu", "gov", "mil") || (l.size >= 2 && l.last().length == 2 && l[l.size - 2] in INSTITUTIONAL_SLD)
    }

    private fun guideHosts(): List<String> = GUIDE_HOSTS + AppConfig.stringList("answers.locate.guideHosts", emptyList()).map { it.lowercase() }

    /** A source → (host, https url, title), or null when it can't be a destination. Gemini's redirect links carry the
     *  real domain only in the title. */
    internal fun normalize(s: Source): Triple<String, String, String>? {
        val rawHost = hostOf(s.url)
        val redirect = rawHost == null || REDIRECT_HOSTS.any { rawHost == it || rawHost.endsWith(".$it") }
        val title = s.title.trim()
        if (redirect && !DOMAINISH.matches(title)) return null
        val host = if (redirect) title.lowercase().removePrefix("www.") else rawHost!!
        val url = if (redirect) "https://$host/" else s.url.trim().let { if (it.startsWith("http://", ignoreCase = true)) "https://" + it.substring(7) else it }
        val safe = SafeUrl.httpsOrNull(url) ?: return null
        return Triple(host, safe, if (redirect) "" else title)
    }

    private fun hostOf(url: String): String? = try {
        java.net.URI(url.trim()).host?.lowercase()?.removePrefix("www.")?.trimEnd('.')?.takeIf { it.contains('.') }
    } catch (_: Throwable) { null }

    /**
     * Candidates for [referent], best first, one per host. Score (0..1) = 0.5 × how much of the referent the host/title
     * covers (host labels and acronyms count fully, title words 0.6) + 0.3 × official (institutional domain 1, the
     * organisation's own brand domain 0.6) + 0.2 × the distinctive word in the host (0.5 in the title only), minus
     * 0.2 for a help/news-style title and 0.15 for a deep article path.
     */
    fun from(sources: List<Source>, referent: String): List<Candidate> {
        val toks = tokens(referent)
        if (toks.isEmpty()) return emptyList()
        val acr = acronyms(toks)
        val brand = toks.lastOrNull { it !in GENERIC } ?: toks.last()
        val guides = guideHosts()
        val wantsLogin = Regex("(?i)\\b(log ?in|sign ?in)\\b").containsMatchIn(referent)
        val best = LinkedHashMap<String, Candidate>()
        for (s in sources) {
            val (host, url, title) = normalize(s) ?: continue
            val labels = host.split('.')
            val reg = registrable(host)
            val regLabel = reg.substringBefore('.')
            // A guide/aggregator host is dropped — unless the user named that very site.
            val named = toks.any { it == regLabel } || acr.containsKey(regLabel)
            if (!named && guides.any { host == it || host.endsWith(".$it") }) continue
            val inst = institutional(host)
            val guideTitle = GUIDE_TITLE.containsMatchIn(title)
            if (guideTitle && !inst && !named) continue
            val titleToks = title.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()
            val covered = HashMap<String, Double>()
            for (t in toks) {
                if (labels.any { it == t || (t.length >= 4 && it.contains(t)) }) covered[t] = 1.0
                else if (t in titleToks) covered[t] = maxOf(covered[t] ?: 0.0, 0.6)
            }
            for (label in labels.dropLast(1)) acr[label]?.forEach { covered[it] = 1.0 }
            for (tt in titleToks) acr[tt]?.forEach { covered[it] = maxOf(covered[it] ?: 0.0, 0.6) }
            val coverage = toks.sumOf { covered[it] ?: 0.0 } / toks.size
            val official = when {
                inst -> 1.0
                toks.any { it == regLabel } || acr.containsKey(regLabel) -> 0.6
                else -> 0.0
            }
            val brandScore = when {
                labels.any { it == brand } || acr[labels.first()]?.contains(brand) == true -> 1.0
                brand in titleToks -> 0.5
                else -> 0.0
            }
            val path = try { java.net.URI(url).path.orEmpty().trim('/') } catch (_: Throwable) { "" }
            val deep = path.count { it == '/' } >= 1 || (path.length > 30)
            var score = 0.5 * coverage + 0.3 * official + 0.2 * brandScore
            if (guideTitle) score -= 0.2
            if (deep) score -= 0.15
            val c = Candidate(host, url, title, score.coerceIn(0.0, 1.0), inst)
            val prev = best[host]
            val better = prev == null || c.score > prev.score + 1e-9 ||
                (Math.abs(c.score - prev.score) < 1e-9 && preferUrl(c.url, prev.url, wantsLogin))
            if (better) best[host] = c
        }
        return best.values.sortedByDescending { it.score }
    }

    /** Same host, same score: a login page when the user said login, else the shorter address. */
    private fun preferUrl(a: String, b: String, wantsLogin: Boolean): Boolean {
        if (wantsLogin) {
            val al = a.contains("login", true); val bl = b.contains("login", true)
            if (al != bl) return al
        }
        return a.length < b.length
    }

    /** A short spoken label for [c]: the title part that names it ("UG Sakai : Gateway : Welcome" → "UG Sakai"),
     *  else the site's name from its host. */
    fun label(c: Candidate, referent: String): String {
        val toks = tokens(referent).toSet()
        val parts = c.title.split(Regex("\\s+[:|–—·-]\\s+|\\s*\\|\\s*")).map { it.trim() }.filter { it.isNotEmpty() }
            .filter { it.split(Regex("\\s+")).size <= 6 }   // a label, not a sentence ("I can't login Sakai. What should I do?")
        return parts.firstOrNull { p -> p.lowercase().split(Regex("\\W+")).any { it in toks } } ?: parts.firstOrNull()
            ?: SearchText.nameForHost(c.host)
    }
}

/** research brief §B.4 — act, ask, or say nothing fits, scaled by what a wrong pick would cost. */
object Decide {
    enum class Tier {
        /** T0: read-only / instantly undone. */
        READ_ONLY,
        /** T1: opens or navigates somewhere (open a URL). */
        OPEN,
        /** T2: send, call, pay — never acted on from a guess. */
        CONSEQUENTIAL
    }

    sealed class Outcome {
        data class Act(val pick: Candidate) : Outcome()
        /** Ask with [options] (most likely first). [narrowing]: more fitted than can be read out — "or something else?". */
        data class Ask(val options: List<Candidate>, val narrowing: Boolean) : Outcome()
        object NoneFound : Outcome()
    }

    /** (top ≥ x, gap to the 2nd ≥ y) per tier; `answers.locate.thresholds.<tier>` overrides. */
    fun thresholds(tier: Tier): Pair<Double, Double> = when (tier) {
        Tier.READ_ONLY -> AppConfig.double("answers.locate.thresholds.readOnly.top", 0.62) to AppConfig.double("answers.locate.thresholds.readOnly.gap", 0.10)
        Tier.OPEN -> AppConfig.double("answers.locate.thresholds.open.top", 0.75) to AppConfig.double("answers.locate.thresholds.open.gap", 0.15)
        Tier.CONSEQUENTIAL -> 2.0 to 2.0   // unreachable: never on a guess
    }

    fun decide(candidates: List<Candidate>, tier: Tier, floor: Double = AppConfig.double("answers.locate.floor", 0.4)): Outcome {
        val viable = candidates.filter { it.score >= floor }.sortedByDescending { it.score }
        if (viable.isEmpty()) return Outcome.NoneFound
        val top = viable[0]
        val gap = top.score - (viable.getOrNull(1)?.score ?: 0.0)
        val (minTop, minGap) = thresholds(tier)
        if (tier != Tier.CONSEQUENTIAL && top.score >= minTop && gap >= minGap) return Outcome.Act(top)
        return Outcome.Ask(viable.take(3), narrowing = viable.size > 3)
    }
}

/** Opening a research-derived address: https only — never intent:, file:, javascript:, content:, data: or http:. */
object SafeUrl {
    private val HOST = Regex("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$")

    /** The URL, normalized, when it is a plain https address to a real host; else null. */
    fun httpsOrNull(raw: String?): String? {
        val s = raw?.trim() ?: return null
        if (s.isEmpty() || s.length > 2_000 || s.any { it.isWhitespace() || it.isISOControl() }) return null
        val u = try { java.net.URI(s) } catch (_: Throwable) { return null }
        if (!u.scheme.equals("https", ignoreCase = true)) return null
        if (u.rawUserInfo != null) return null                     // https://user@evil — never
        val host = u.host?.lowercase()?.trimEnd('.') ?: return null
        if (!HOST.matches(host) || host.split('.').last().all { it.isDigit() }) return null   // a name, not a bare IP
        if (u.port != -1 && u.port != 443) return null
        val path = u.rawPath?.ifEmpty { "/" } ?: "/"
        return "https://$host$path" + (u.rawQuery?.let { "?$it" } ?: "")
    }

    fun host(url: String): String? = try { java.net.URI(url).host?.lowercase()?.removePrefix("www.") } catch (_: Throwable) { null }

    /** "sakai.ug.edu.gh" → "sakai dot u g dot edu dot g h": short labels are spelled, so the voice reads them letter by letter. */
    fun spokenHost(host: String): String = host.lowercase().removePrefix("www.").split('.').filter { it.isNotEmpty() }
        .joinToString(" dot ") { l -> if (l.length <= 2) l.toCharArray().joinToString(" ") else l.replace("-", " dash ") }
}
