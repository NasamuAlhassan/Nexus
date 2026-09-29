package com.tekyerema.agent

import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * "What time is it in London / New York / Lagos?" — the time in another city, from the phone's own clock and time-zone
 * rules (summer time included), with how far ahead or behind Ghana it is. Family abroad is part of daily life for many
 * Ghanaians: calling someone in London or Toronto starts with this question. Before this, "what time is it in London"
 * matched the plain time question and the phone read out GHANA's time — a wrong answer said confidently.
 *
 * Only places in the table: a city, or a country with ONE time zone ("Nigeria", "the UK"). The United States, Canada
 * and Australia get their two main cities. A place the table doesn't know is not the time here either — [asksElsewhere]
 * lets the caller send it to the model instead. Pure: [now] is injected so the tests are exact.
 */
object WorldClock {

    /** The spoken answer, or null when [g] (lower-case) isn't "the time in <a place in the table>". */
    fun answer(g: String, now: ZonedDateTime = ZonedDateTime.now()): String? {
        val s = clean(g)
        RE_DIFF.matchEntire(s)?.let { m ->
            val p = place(m.groupValues[1])?.singleOrNull() ?: return null
            val there = now.withZoneSameInstant(zone(p.zone) ?: return null)
            val diff = there.offset.totalSeconds - now.offset.totalSeconds
            return if (diff == 0) "${cap(p.name)} has the same time as ${here(now)}."
                else "${cap(p.name)} is ${gap(diff)} ${if (diff > 0) "ahead of" else "behind"} ${here(now)}."
        }
        val m = RE_TIME_IN.matchEntire(s) ?: RE_PLACE_TIME.matchEntire(s) ?: return null
        val places = place(m.groupValues[1]) ?: return null
        if (places.size > 1) {
            val parts = places.map { p -> "in ${p.name} it's ${clock(now.withZoneSameInstant(zone(p.zone) ?: return null), now)}" }
            return "${SEVERAL_NAMES[m.groupValues[1].trim()] ?: "It"} has several time zones: ${parts.joinToString(", and ")}."
        }
        val p = places.single()
        val z = zone(p.zone) ?: return null
        val there = now.withZoneSameInstant(z)
        if (z.id == now.zone.id) return "In ${p.name} it's ${clock(there, now)}."
        val diff = there.offset.totalSeconds - now.offset.totalSeconds
        val rel = if (diff == 0) "the same time as ${here(now)}" else "${gap(diff)} ${if (diff > 0) "ahead of" else "behind"} ${here(now)}"
        return "In ${p.name} it's ${clock(there, now)}, $rel."
    }

    /** "What time is it in <somewhere>?" for a place the table doesn't know: not the time HERE — the model's. */
    fun asksElsewhere(g: String): Boolean {
        val s = clean(g)
        return RE_TIME_IN.matches(s) || RE_DIFF.matches(s)
    }

    private val RE_TIME_IN = Regex("^(?:what(?:'s| is)? (?:the )?(?:current |local |exact )?time(?: is it| it is)?(?: now| right now)?|" +
        "what time (?:is it|it is)(?: now| right now)?|(?:the )?(?:current |local )?time|what time)(?: over)? in (.+?)(?: now| right now| at the moment| currently)?$")
    /** "What's London time?" — answered for a known place only (never flags: "what's my screen time" isn't a place). */
    private val RE_PLACE_TIME = Regex("^what(?:'s| is) (?:the )?(?:current )?(.+?) time(?: now| right now)?$")
    private val RE_DIFF = Regex("^(?:what(?:'s| is) )?(?:the )?time difference (?:with|to|from|for) (.+)$")

    private fun clock(t: ZonedDateTime, now: ZonedDateTime): String {
        val time = t.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
        return if (t.toLocalDate() == now.toLocalDate()) time
            else "$time on ${t.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}"
    }

    /** "1 hour", "5 and a half hours", "5 hours and 45 minutes". */
    private fun gap(seconds: Int): String {
        val a = kotlin.math.abs(seconds)
        val h = a / 3600; val m = (a % 3600) / 60
        return when {
            m == 0 -> if (h == 1) "1 hour" else "$h hours"
            m == 30 && h == 0 -> "half an hour"
            m == 30 -> "$h and a half hours"
            h == 0 -> "$m minutes"
            else -> "${if (h == 1) "1 hour" else "$h hours"} and $m minutes"
        }
    }

    /** "Ghana" on a phone set to Ghana time (the users this is built for); "you" anywhere else. */
    private fun here(now: ZonedDateTime) = if (now.zone.id == "Africa/Accra") "Ghana" else "you"

    private fun zone(id: String): ZoneId? = try { ZoneId.of(id) } catch (_: Exception) { null }

    private class Place(val name: String, val zone: String)

    /**
     * Spoken name → time zone; a country only when it has a single time zone. Each entry is how the answer names the
     * place, and "alias=Name" adds another way of saying it.
     */
    private val PLACES: Map<String, Place> = buildMap {
        fun p(zone: String, vararg names: String) = names.forEach { n ->
            val (key, shown) = if ('=' in n) n.substringBefore('=') to n.substringAfter('=') else n.lowercase() to n
            put(key, Place(shown, zone))
        }
        // Ghana and West Africa
        p("Africa/Accra", "Ghana", "Accra", "Kumasi", "Tamale", "Takoradi", "Cape Coast", "Tema", "Ho", "Koforidua", "Sunyani", "Bolgatanga", "Wa")
        p("Africa/Lagos", "Nigeria", "Lagos", "Abuja", "Kano", "Ibadan", "Port Harcourt")
        p("Africa/Lome", "Togo", "Lomé", "lome=Lomé")
        p("Africa/Abidjan", "Ivory Coast", "Côte d'Ivoire", "cote d'ivoire=Côte d'Ivoire", "Abidjan")
        p("Africa/Porto-Novo", "Benin", "Cotonou", "Porto-Novo", "porto novo=Porto-Novo")
        p("Africa/Ouagadougou", "Burkina Faso", "Ouagadougou")
        p("Africa/Dakar", "Senegal", "Dakar")
        p("Africa/Bamako", "Mali", "Bamako")
        p("Africa/Niamey", "Niger", "Niamey")
        p("Africa/Freetown", "Sierra Leone", "Freetown")
        p("Africa/Monrovia", "Liberia", "Monrovia")
        p("Africa/Conakry", "Conakry")
        p("Africa/Banjul", "The Gambia", "gambia=The Gambia", "Banjul")
        p("Africa/Douala", "Cameroon", "Douala", "Yaoundé", "yaounde=Yaoundé")
        // Rest of Africa
        p("Africa/Nairobi", "Kenya", "Nairobi", "Mombasa")
        p("Africa/Kampala", "Uganda", "Kampala")
        p("Africa/Dar_es_Salaam", "Tanzania", "Dar es Salaam")
        p("Africa/Kigali", "Rwanda", "Kigali")
        p("Africa/Addis_Ababa", "Ethiopia", "Addis Ababa")
        p("Africa/Cairo", "Egypt", "Cairo")
        p("Africa/Johannesburg", "South Africa", "Johannesburg", "Cape Town", "Pretoria", "Durban")
        p("Africa/Harare", "Zimbabwe", "Harare")
        p("Africa/Lusaka", "Zambia", "Lusaka")
        p("Africa/Casablanca", "Morocco", "Casablanca", "Rabat")
        p("Africa/Luanda", "Luanda")
        p("Africa/Kinshasa", "Kinshasa")
        // Europe
        p("Europe/London", "London", "Manchester", "Birmingham", "Liverpool", "Leeds", "Glasgow", "Edinburgh", "Cardiff", "Milton Keynes",
            "England", "Scotland", "Wales", "Britain", "Great Britain", "uk=the UK", "the uk=the UK", "united kingdom=the UK", "the united kingdom=the UK")
        p("Europe/Dublin", "Ireland", "Dublin")
        p("Europe/Paris", "France", "Paris")
        p("Europe/Berlin", "Germany", "Berlin", "Hamburg", "Munich", "Frankfurt", "Cologne", "Düsseldorf", "dusseldorf=Düsseldorf")
        p("Europe/Amsterdam", "Amsterdam", "Rotterdam", "The Hague", "netherlands=the Netherlands", "the netherlands=the Netherlands", "holland=Holland")
        p("Europe/Brussels", "Belgium", "Brussels", "Antwerp")
        p("Europe/Rome", "Italy", "Rome", "Milan", "Naples", "Turin")
        p("Europe/Madrid", "Madrid", "Barcelona")
        p("Europe/Lisbon", "Lisbon")
        p("Europe/Zurich", "Switzerland", "Zurich", "Geneva")
        p("Europe/Vienna", "Austria", "Vienna")
        p("Europe/Stockholm", "Sweden", "Stockholm")
        p("Europe/Oslo", "Norway", "Oslo")
        p("Europe/Copenhagen", "Copenhagen")
        p("Europe/Helsinki", "Finland", "Helsinki")
        p("Europe/Warsaw", "Poland", "Warsaw")
        p("Europe/Athens", "Greece", "Athens")
        p("Europe/Istanbul", "Turkey", "Istanbul", "Ankara")
        p("Europe/Moscow", "Moscow")
        // Middle East and Asia
        p("Asia/Dubai", "Dubai", "Abu Dhabi", "Sharjah", "uae=the UAE", "the uae=the UAE", "united arab emirates=the UAE",
            "the united arab emirates=the UAE")
        p("Asia/Qatar", "Qatar", "Doha")
        p("Asia/Riyadh", "Saudi Arabia", "saudi=Saudi Arabia", "Riyadh", "Jeddah", "Mecca", "Makkah", "Medina", "Madinah")
        p("Asia/Kuwait", "Kuwait", "kuwait city=Kuwait")
        p("Asia/Beirut", "Lebanon", "Beirut")
        p("Asia/Jerusalem", "Israel", "Jerusalem", "Tel Aviv")
        p("Asia/Tehran", "Iran", "Tehran")
        p("Asia/Kolkata", "India", "Delhi", "New Delhi", "Mumbai", "bombay=Mumbai", "Bangalore", "Bengaluru", "Chennai", "Kolkata", "Hyderabad")
        p("Asia/Karachi", "Pakistan", "Karachi", "Lahore", "Islamabad")
        p("Asia/Dhaka", "Bangladesh", "Dhaka")
        p("Asia/Kathmandu", "Nepal", "Kathmandu")
        p("Asia/Shanghai", "China", "Beijing", "Shanghai", "Guangzhou", "Shenzhen", "Yiwu")
        p("Asia/Hong_Kong", "Hong Kong")
        p("Asia/Tokyo", "Japan", "Tokyo", "Osaka")
        p("Asia/Seoul", "South Korea", "Seoul")
        p("Asia/Singapore", "Singapore")
        p("Asia/Kuala_Lumpur", "Malaysia", "Kuala Lumpur")
        p("Asia/Bangkok", "Thailand", "Bangkok")
        p("Asia/Manila", "Manila", "philippines=the Philippines", "the philippines=the Philippines")
        // The Americas
        p("America/New_York", "New York", "new york city=New York", "nyc=New York", "Washington", "washington dc=Washington DC",
            "washington d c=Washington DC", "Boston", "Philadelphia", "Atlanta", "Miami", "Orlando", "Baltimore", "New Jersey", "Newark",
            "The Bronx", "bronx=The Bronx", "Detroit", "Charlotte")
        p("America/Chicago", "Chicago", "Houston", "Dallas", "Austin", "San Antonio", "Minneapolis", "New Orleans")
        p("America/Denver", "Denver")
        p("America/Phoenix", "Phoenix")
        p("America/Los_Angeles", "Los Angeles", "San Francisco", "Seattle", "Las Vegas", "San Diego", "California")
        p("America/Toronto", "Toronto", "Montreal", "Ottawa")
        p("America/Vancouver", "Vancouver")
        p("America/Edmonton", "Calgary", "Edmonton")
        p("America/Winnipeg", "Winnipeg")
        p("America/Mexico_City", "Mexico City")
        p("America/Sao_Paulo", "São Paulo", "sao paulo=São Paulo", "Rio de Janeiro", "rio=Rio de Janeiro")
        p("America/Argentina/Buenos_Aires", "Buenos Aires")
        p("America/Jamaica", "Jamaica", "Kingston")
        // Oceania
        p("Australia/Sydney", "Sydney", "Canberra")
        p("Australia/Melbourne", "Melbourne")
        p("Australia/Brisbane", "Brisbane")
        p("Australia/Perth", "Perth")
        p("Pacific/Auckland", "New Zealand", "Auckland", "Wellington")
    }

    /** Countries with more than one time zone: their two main cities. */
    private val USA = listOf("new york", "los angeles")
    private val SEVERAL: Map<String, List<String>> = mapOf(
        "america" to USA, "the united states" to USA, "united states" to USA, "the us" to USA, "usa" to USA, "the usa" to USA,
        "the states" to USA, "the united states of america" to USA, "united states of america" to USA,
        "canada" to listOf("toronto", "vancouver"), "australia" to listOf("sydney", "perth")
    )
    private val SEVERAL_NAMES = mapOf("canada" to "Canada", "australia" to "Australia") + SEVERAL.keys.filter { SEVERAL[it] === USA }.associateWith { "The United States" }

    private fun place(said: String): List<Place>? {
        val k = said.trim()
        SEVERAL[k]?.let { cities -> return cities.map { PLACES.getValue(it) } }
        return (PLACES[k] ?: PLACES[k.removePrefix("the ")])?.let { listOf(it) }
    }

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

    private val SPACES = Regex("\\s+")
    private val LEAD = Regex("^(?:(?:please|can you tell me|could you tell me|tell me|do you know|hey)\\s+)+")
    private val PLEASE = Regex(",? please$")

    private fun clean(g: String): String = g.lowercase().replace('’', '\'').trim().trimEnd('?', '.', '!').trim().replace(SPACES, " ")
        .replace(LEAD, "").replace(PLEASE, "").replace(",", "").trim()
}
