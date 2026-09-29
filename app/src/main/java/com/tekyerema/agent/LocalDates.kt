package com.tekyerema.agent

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Period
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Questions about days and dates answered on the phone — "how many days until Christmas", "what day is 6 March 2027",
 * "what's the date next Friday / in 10 days", "how old is someone born in 1990", "when is Farmers' Day", "is 2028 a
 * leap year", "what is the Akan day name for a boy born on a Friday". Each has ONE right answer the phone can work out
 * from its own calendar: no model, no network, never a guess. The free cloud quota runs out every night and a campus
 * Wi-Fi answer can take 5–40 s; these are instant and offline.
 *
 * Holidays are the Ghana public holidays whose date is fixed or follows a rule, as in the Public Holidays and
 * Commemorative Days Act (Act 601) as amended in 2025 — Republic Day (1 July) is back and Founders' Day is 21
 * September (Kwame Nkrumah's birthday; 4 August is no longer a holiday). Easter-based days (Good Friday, Easter Monday)
 * follow the Gregorian Easter rule. Eid-ul-Fitr, Shaqq Day and Eid-ul-Adha depend on the moon being sighted, so they
 * are never answered here (nor is "the next holiday", which may be one of them) — the model or the web gets those.
 * The date said is the holiday's own date; a holiday falling mid-week or on a weekend may be observed on another day
 * by executive instrument.
 *
 * Only whole, clear utterances match; anything else returns null and goes on to the model unchanged. Pure: [today] is
 * injected so the tests are exact.
 */
object LocalDates {

    /** The spoken answer, or null when [g] (lower-case) isn't a date question this can answer exactly. */
    fun answer(g: String, today: LocalDate = LocalDate.now()): String? {
        val s = clean(g)
        if (s.isEmpty()) return null
        return dayName(s, today) ?: yearFacts(s, today) ?: until(s, today) ?: since(s, today) ?: age(s, today)
            ?: whatDay(s, today) ?: whatDate(s, today) ?: isOn(s, today) ?: thisYearOrMonth(s, today)
    }

    /**
     * "What day is Eid?", "what's the date of my exam?" — a question about ANOTHER day that [answer] couldn't work out.
     * It must not fall to the "today is …" answers (the phone said "Today is Tuesday" to "what day is Christmas"
     * before this); the caller sends it to the model instead. "What day is it (today)?" is not one of these.
     */
    fun asksOtherDay(g: String): Boolean {
        val s = clean(g)
        val x = RE_WHAT_DAY.matchEntire(s)?.groupValues?.get(2) ?: RE_WHAT_DATE.matchEntire(s)?.groupValues?.get(1) ?: return false
        return x.trim() !in TODAY_WORDS
    }

    private val TODAY_WORDS = setOf("it", "today", "it today", "now", "it now", "right now", "it right now", "this", "is it",
        "today's date", "it today in ghana", "today in ghana", "it in ghana", "it here", "it now in ghana")

    // ---------------------------------------------------------------- the day being asked about

    private enum class Kind { HOLIDAY, DATE, WEEKDAY, RELATIVE }

    /** [label]: the holiday's name, "Next Friday", "In 10 days" — how the answer names the day ("" for a plain date). */
    private class Target(val date: LocalDate, val kind: Kind, val label: String)

    /** [past]: a day without a year is the latest one on or before today (else the next one on or after today). */
    private fun target(raw: String, today: LocalDate, past: Boolean): Target? {
        val x = raw.trim().replace(RE_LEADING_IT, "").trim()
        if (x.isEmpty()) return null
        return relative(x, today) ?: weekday(x, today, past) ?: holiday(x, today, past) ?: absolute(x, today, past) ?: endOf(x, today)
    }

    private val RE_IN_N = Regex("^in (\\d{1,4}|a|an|one) (day|week|month|year)s?(?:'s?)?(?: time)?$")
    private val RE_N_FROM = Regex("^(\\d{1,4}|a|an|one) (day|week|month|year)s?(?:'s?)? (?:from (now|today)|time|today)$")
    private val RE_N_AGO = Regex("^(\\d{1,4}|a|an|one) (day|week|month|year)s? ago$")

    private fun relative(x: String, today: LocalDate): Target? {
        when (x.removePrefix("the ")) {
            "today" -> return Target(today, Kind.RELATIVE, "Today")
            "tomorrow" -> return Target(today.plusDays(1), Kind.RELATIVE, "Tomorrow")
            "yesterday" -> return Target(today.minusDays(1), Kind.RELATIVE, "Yesterday")
            "day after tomorrow" -> return Target(today.plusDays(2), Kind.RELATIVE, "The day after tomorrow")
            "day before yesterday" -> return Target(today.minusDays(2), Kind.RELATIVE, "The day before yesterday")
        }
        RE_IN_N.matchEntire(x)?.let { m ->
            val n = count(m.groupValues[1]); val u = m.groupValues[2]
            return shift(today, n, u)?.let { Target(it, Kind.RELATIVE, "In ${amount(n, u)}") }
        }
        RE_N_FROM.matchEntire(x)?.let { m ->
            val n = count(m.groupValues[1]); val u = m.groupValues[2]
            val from = m.groupValues[3].ifEmpty { if (x.endsWith(" today")) "today" else "now" }
            return shift(today, n, u)?.let { Target(it, Kind.RELATIVE, cap("${amount(n, u)} from $from")) }
        }
        RE_N_AGO.matchEntire(x)?.let { m ->
            val n = count(m.groupValues[1]); val u = m.groupValues[2]
            return shift(today, -n, u)?.let { Target(it, Kind.RELATIVE, cap("${amount(n, u)} ago")) }
        }
        return null
    }

    private fun count(s: String): Int = s.toIntOrNull() ?: 1
    private fun amount(n: Int, unit: String) = if (n == 1) (if (unit == "day") "a day" else "a $unit") else "$n ${unit}s"

    private fun shift(today: LocalDate, n: Int, unit: String): LocalDate? {
        val limit = when (unit) { "day" -> 3660; "week" -> 520; "month" -> 120; else -> 100 }
        if (n == 0 || kotlin.math.abs(n) > limit) return null
        return when (unit) {
            "day" -> today.plusDays(n.toLong())
            "week" -> today.plusWeeks(n.toLong())
            "month" -> today.plusMonths(n.toLong())
            else -> today.plusYears(n.toLong())
        }
    }

    private const val WEEKDAYS = "(monday|tuesday|wednesday|thursday|friday|saturday|sunday)"
    private val RE_WEEKDAY = Regex("^(?:(next|this|this coming|coming|last|previous|on) )?$WEEKDAYS$")
    private val RE_WEEKDAY_ONLY = Regex("^$WEEKDAYS$")
    private val RE_LEADING_IT = Regex("^it (?:be |was |is )?")

    /** "Friday" / "this Friday" = the coming one (today if it is Friday); "next Friday" = the coming one after today;
     *  "last Friday" = the one before today. The answer always says the date, so the user hears which Friday it is. */
    private fun weekday(x: String, today: LocalDate, past: Boolean): Target? {
        val m = RE_WEEKDAY.matchEntire(x) ?: return null
        val which = m.groupValues[1]
        val dow = DayOfWeek.valueOf(m.groupValues[2].uppercase())
        val ahead = ((dow.value - today.dayOfWeek.value) + 7) % 7
        val back = ((today.dayOfWeek.value - dow.value) + 7) % 7
        val date = when {
            which == "last" || which == "previous" -> today.minusDays(if (back == 0) 7L else back.toLong())
            which == "next" || which.endsWith("coming") -> today.plusDays(if (ahead == 0) 7L else ahead.toLong())
            past && which != "this" -> today.minusDays(if (back == 0) 7L else back.toLong())
            else -> today.plusDays(ahead.toLong())
        }
        val label = when (which) { "", "on" -> day(dow); "previous" -> "Last ${day(dow)}"; "this coming", "coming" -> "This ${day(dow)}"; else -> cap(which) + " " + day(dow) }
        return Target(date, Kind.WEEKDAY, label)
    }

    private class Holiday(val name: String, val re: Regex, val on: (Int) -> LocalDate)

    private val HOLIDAYS = listOf(
        Holiday("New Year's Eve", Regex("new ?years?(?:'s?)? eve")) { LocalDate.of(it, 12, 31) },
        Holiday("New Year's Day", Regex("new ?years?(?:'s?)?(?: day)?")) { LocalDate.of(it, 1, 1) },
        Holiday("Constitution Day", Regex("constitution day")) { LocalDate.of(it, 1, 7) },
        Holiday("Valentine's Day", Regex("(?:saint |st )?valentine(?:'s?|s)?(?: day)?")) { LocalDate.of(it, 2, 14) },
        Holiday("Independence Day", Regex("independence day|independence anniversary")) { LocalDate.of(it, 3, 6) },
        Holiday("Good Friday", Regex("good friday")) { easter(it).minusDays(2) },
        Holiday("Easter Monday", Regex("easter monday")) { easter(it).plusDays(1) },
        Holiday("Easter Sunday", Regex("easter(?: sunday| day)?")) { easter(it) },
        Holiday("May Day", Regex("may day|(?:international )?workers?(?:'s?)? day|labou?r day")) { LocalDate.of(it, 5, 1) },
        Holiday("Republic Day", Regex("republic day")) { LocalDate.of(it, 7, 1) },
        Holiday("Founders' Day", Regex("founders?(?:'s?)? day|(?:kwame )?nkrumah(?:'s)?(?: memorial)? day|(?:kwame )?nkrumah(?:'s)? birthday")) {
            LocalDate.of(it, 9, 21)
        },
        Holiday("Farmers' Day", Regex("farmers?(?:'s?)? day")) { LocalDate.of(it, 12, 1).with(TemporalAdjusters.firstInMonth(DayOfWeek.FRIDAY)) },
        Holiday("Christmas Eve", Regex("christmas eve|xmas eve")) { LocalDate.of(it, 12, 24) },
        Holiday("Christmas", Regex("christmas(?: day)?|xmas(?: day)?")) { LocalDate.of(it, 12, 25) },
        Holiday("Boxing Day", Regex("boxing day")) { LocalDate.of(it, 12, 26) }
    )
    private val RE_HOLIDAY_X = Regex("^(?:the )?(?:(next|this|last) )?(?:ghana(?:'s)? |national |our )?(.+?)(?: (?:in |of )?(\\d{4}|this year|next year|last year))?$")

    private fun holiday(x: String, today: LocalDate, past: Boolean): Target? {
        val m = RE_HOLIDAY_X.matchEntire(x) ?: return null
        val h = HOLIDAYS.firstOrNull { it.re.matches(m.groupValues[2]) } ?: return null
        val y = today.year
        val date = when (val w = m.groupValues[3]) {
            "this year" -> h.on(y)
            "next year" -> h.on(y + 1)
            "last year" -> h.on(y - 1)
            "" -> when (m.groupValues[1]) {
                "next" -> h.on(y).let { if (it.isAfter(today)) it else h.on(y + 1) }
                "last" -> h.on(y).let { if (it.isBefore(today)) it else h.on(y - 1) }
                "this" -> h.on(y)
                else -> if (past) h.on(y).let { if (it.isAfter(today)) h.on(y - 1) else it }
                        else h.on(y).let { if (it.isBefore(today)) h.on(y + 1) else it }
            }
            else -> w.toInt().takeIf { it in 1900..2200 }?.let(h.on) ?: return null
        }
        return Target(date, Kind.HOLIDAY, h.name)
    }

    /** Easter Sunday by the Gregorian rule (the "anonymous" algorithm) — what every church calendar in Ghana prints. */
    internal fun easter(y: Int): LocalDate {
        val a = y % 19; val b = y / 100; val c = y % 100; val d = b / 4; val e = b % 4
        val f = (b + 8) / 25; val g = (b - f + 1) / 3; val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4; val k = c % 4; val l = (32 + 2 * e + 2 * i - h - k) % 7; val mm = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * mm + 114) / 31; val dayOfMonth = (h + l - 7 * mm + 114) % 31 + 1
        return LocalDate.of(y, month, dayOfMonth)
    }

    private const val MONTH = "(january|february|march|april|may|june|july|august|september|october|november|december|" +
        "jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec)"
    private const val DAYNUM = "(\\d{1,2}(?:st|nd|rd|th)?|(?:(?:twenty|thirty|20|30)[- ]?)?(?:first|second|third|fourth|fifth|sixth|seventh|eighth|ninth)|" +
        "tenth|eleventh|twelfth|thirteenth|fourteenth|fifteenth|sixteenth|seventeenth|eighteenth|nineteenth|twentieth|thirtieth)"
    // "1990", or the recogniser's "19 90" for "nineteen ninety".
    private const val YEAR = "(\\d{4}|(?:19|20) \\d{2})"
    private val RE_DMY = Regex("^(?:the )?$DAYNUM(?: day)?(?: of)? $MONTH(?: (?:of )?$YEAR)?$")
    private val RE_MDY = Regex("^$MONTH (?:the )?$DAYNUM(?: $YEAR)?$")

    private fun absolute(x: String, today: LocalDate, past: Boolean): Target? {
        val (dTok, mTok, yTok) = RE_DMY.matchEntire(x)?.let { m -> Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3]) }
            ?: RE_MDY.matchEntire(x)?.let { m -> Triple(m.groupValues[2], m.groupValues[1], m.groupValues[3]) }
            ?: return null
        val dom = dayNum(dTok) ?: return null
        val month = monthNum(mTok) ?: return null
        if (yTok.isNotEmpty()) {
            val y = year(yTok) ?: return null
            return date(y, month, dom)?.let { Target(it, Kind.DATE, "") }
        }
        // No year: the next (or, asked in the past tense, the last) time that day comes round — 29 February included.
        for (k in 0..8) {
            val y = if (past) today.year - k else today.year + k
            val d = date(y, month, dom) ?: continue
            if (past && d.isAfter(today) || !past && d.isBefore(today)) continue
            return Target(d, Kind.DATE, "")
        }
        return null
    }

    private fun date(y: Int, m: Int, d: Int): LocalDate? = try { LocalDate.of(y, m, d) } catch (_: Exception) { null }
    private fun year(s: String): Int? = s.replace(" ", "").toIntOrNull()?.takeIf { it in 1900..2200 }
    private val RE_YEAR_ONLY = Regex("^$YEAR$")
    private val RE_ENDS_IN_YEAR = Regex("(?:\\d{4}|(?:19|20) \\d{2})$")
    /** "1990" / "19 90" said on its own → 1990. */
    private fun yearOnly(s: String): Int? = if (RE_YEAR_ONLY.matches(s)) year(s) else null

    private val ORDINALS = mapOf("first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6, "seventh" to 7,
        "eighth" to 8, "ninth" to 9, "tenth" to 10, "eleventh" to 11, "twelfth" to 12, "thirteenth" to 13, "fourteenth" to 14,
        "fifteenth" to 15, "sixteenth" to 16, "seventeenth" to 17, "eighteenth" to 18, "nineteenth" to 19, "twentieth" to 20, "thirtieth" to 30)

    private val RE_DAY_DIGITS = Regex("^(\\d{1,2})(?:st|nd|rd|th)?$")
    private val RE_DAY_TENS = Regex("^(twenty|thirty|20|30)[- ]?(\\p{L}+)$")

    private fun dayNum(t: String): Int? {
        RE_DAY_DIGITS.matchEntire(t)?.let { return it.groupValues[1].toInt().takeIf { d -> d in 1..31 } }
        ORDINALS[t]?.let { return it }
        val m = RE_DAY_TENS.matchEntire(t) ?: return null
        val unit = ORDINALS[m.groupValues[2]]?.takeIf { it in 1..9 } ?: return null
        return (if (m.groupValues[1] == "thirty" || m.groupValues[1] == "30") 30 else 20) + unit
    }

    private fun monthNum(t: String): Int? = when (t.take(3)) {
        "jan" -> 1; "feb" -> 2; "mar" -> 3; "apr" -> 4; "may" -> 5; "jun" -> 6
        "jul" -> 7; "aug" -> 8; "sep" -> 9; "oct" -> 10; "nov" -> 11; "dec" -> 12
        else -> null
    }

    private fun endOf(x: String, today: LocalDate): Target? = when (x.removePrefix("the ")) {
        "end of the month", "end of this month", "end of month" ->
            Target(today.with(TemporalAdjusters.lastDayOfMonth()), Kind.HOLIDAY, "The end of the month")
        "end of the year", "end of this year", "end of year" -> Target(LocalDate.of(today.year, 12, 31), Kind.HOLIDAY, "The end of the year")
        else -> null
    }

    // ---------------------------------------------------------------- "how many days until …"

    private val RE_UNTIL = Regex("^(?:how many (?:more )?(days|weeks|months)|how long|how much (?:more )?time)(?: (?:is it|is there|are there|do (?:i|we) have|" +
        "have (?:i|we) got|are left|is left|left|remain|remaining|more|to go))? (?:until|till|til|to|before) (.+?)(?: (?:left|to go|remaining|from (?:now|today)))?$")
    private val RE_AWAY = listOf(
        Regex("^how far (?:away )?is (.+?)(?: away| from (?:now|today))?$"),
        Regex("^how many (days|weeks|months) (?:away )?is (.+?)(?: away| from (?:now|today))?$"),
        Regex("^how long is (.+?) (?:away|from (?:now|today))$")
    )
    private val RE_COUNTDOWN = Regex("^(?:countdown to|(days|weeks) (?:left )?(?:until|till|to)) (.+)$")

    private fun until(s: String, today: LocalDate): String? {
        val (unit, x) = RE_UNTIL.matchEntire(s)?.let { it.groupValues[1] to it.groupValues[2] }
            ?: RE_AWAY.firstNotNullOfOrNull { re -> re.matchEntire(s)?.let { m -> if (m.groupValues.size == 3) m.groupValues[1] to m.groupValues[2] else "" to m.groupValues[1] } }
            ?: RE_COUNTDOWN.matchEntire(s)?.let { it.groupValues[1] to it.groupValues[2] }
            ?: return null
        val t = target(x, today, past = false)?.takeIf { it.kind != Kind.RELATIVE } ?: return null
        val n = ChronoUnit.DAYS.between(today, t.date)
        val subject = subject(t, today)
        val on = onPart(t, today)
        return when {
            n == 0L -> "$subject is today."
            n == 1L -> "$subject is tomorrow$on."
            n < 0L -> "$subject was ${span(t.date, today, unit)} ago$on."
            unit == "weeks" || unit == "months" -> "$subject is ${span(today, t.date, unit)} away$on."
            else -> "$subject is in ${plural(n, "day")}$on."
        }
    }

    // ---------------------------------------------------------------- "how long ago was …" / "how many years since …"

    private val RE_SINCE = Regex("^how (?:many (days|weeks|months|years)|long)(?: (?:has it been|is it|have passed|has passed|is it now))? since (.+?)(?: (?:till|until|to) (?:now|today))?$")
    private val RE_AGO = Regex("^how (?:many (days|weeks|months|years)|long) ago (?:was|is|did) (.+?)(?: happen)?$")

    private fun since(s: String, today: LocalDate): String? {
        val m = RE_SINCE.matchEntire(s) ?: RE_AGO.matchEntire(s) ?: return null
        val unit = m.groupValues[1]; val x = m.groupValues[2]
        // A bare year: "how long ago was 1957" — whole years only (the day in that year isn't known).
        yearOnly(x)?.let { y ->
            if (unit != "" && unit != "years") return null
            val n = today.year - y
            return if (n <= 0) null else "$y was ${plural(n.toLong(), "year")} ago."
        }
        val t = target(x, today, past = true)?.takeIf { it.kind != Kind.RELATIVE } ?: return null
        if (t.kind == Kind.HOLIDAY && unit == "years") return null   // "years since Independence Day" means 1957: not this
        val n = ChronoUnit.DAYS.between(t.date, today)
        if (n < 0) return null
        val subject = subject(t, today)
        val on = onPart(t, today)
        return when {
            n == 0L -> "$subject is today."
            n == 1L -> "$subject was yesterday$on."
            unit == "" && n >= 365 -> "$subject was ${span(t.date, today, "years")} ago$on."
            unit == "" -> "$subject was ${plural(n, "day")} ago$on."
            else -> "$subject was ${span(t.date, today, unit)} ago$on."
        }
    }

    // ---------------------------------------------------------------- "how old is someone born in 1990"

    private const val WHO = "(someone|somebody|a person|a man|a woman|a child|a baby|a boy|a girl|he|she|they)"
    private const val ASKER = "(am i|would i be|will i be|is he|is she|are they|are you|would he be|would she be|would they be|is that person|would that person be)"
    private val RE_AGE = listOf(
        Regex("^how old (?:is|would be|will be) $WHO(?: who (?:was|is|were))? born (?:in|on) (.+?)(?: now| today| this year)?$"),
        Regex("^how old would $WHO(?: who (?:was|were))? born (?:in|on) (.+?) be(?: now| today| this year)?$"),
        Regex("^how old $ASKER(?: now| today| this year)? if (?:i|he|she|they|you) (?:was|were) born (?:in|on) (.+)$"),
        Regex("^if (i|he|she|they|you|someone|somebody) (?:was|were|is) born (?:in|on) (.+?) how old (?:am i|would i be|will i be|is he|is she|are they|are you|would they be|would he be|would she be|is that person|would that person be)(?: now| today| this year)?$"),
        Regex("^(i|he|she)(?: was|'m| am) born (?:in|on) (.+?) (?:so )?how old (?:am i|is he|is she)(?: now| today| this year)?$"),
        Regex("^(?:what(?:'s| is) )?(?:the )?age of $WHO born (?:in|on) (.+)$")
    )

    private fun age(s: String, today: LocalDate): String? {
        val m = RE_AGE.firstNotNullOfOrNull { it.matchEntire(s) } ?: return null
        val who = when (val w = m.groupValues[1]) {
            "am i", "would i be", "will i be", "i", "are you", "you" -> "you"
            "is he", "would he be", "he" -> "he"
            "is she", "would she be", "she" -> "she"
            "are they", "would they be", "they" -> "they"
            else -> "someone"
        }
        val x = m.groupValues[2].trim()
        // A full date must carry its year ("born on 6 March" alone can't give an age).
        val born = target(x, today, past = true)?.takeIf { it.kind == Kind.DATE && RE_ENDS_IN_YEAR.containsMatchIn(x) }
        if (born == null) {
            val y = yearOnly(x) ?: return null
            val n = today.year - y
            if (n < 0) return null
            val verb = if (who == "someone" || who == "he" || who == "she") "turns" else "turn"
            return when {
                n == 0 -> "${lead(who, "in $y")} ${be(who)} under a year old."
                else -> "${lead(who, "in $y")} $verb $n this year."
            }
        }
        val d = born.date
        if (d.isAfter(today)) return null
        val p = Period.between(d, today)
        val when_ = "on ${d.dayOfMonth} ${month(d)} ${d.year}"
        return when {
            p.years > 0 && p.months == 0 && p.days == 0 -> "${lead(who, when_)} ${if (who == "someone" || who == "he" || who == "she") "turns" else "turn"} ${p.years} today."
            p.years > 0 -> "${lead(who, when_)} ${be(who)} ${plural(p.years.toLong(), "year")} old."
            p.months > 0 -> "${lead(who, when_)} ${be(who)} ${plural(p.months.toLong(), "month")} old."
            else -> "${lead(who, when_)} ${be(who)} ${plural(ChronoUnit.DAYS.between(d, today), "day")} old."
        }
    }

    private fun lead(who: String, born: String) = when (who) {
        "you" -> "If you were born $born, you"
        "he" -> "If he was born $born, he"
        "she" -> "If she was born $born, she"
        "they" -> "If they were born $born, they"
        else -> "Someone born $born"
    }
    private fun be(who: String) = when (who) { "you", "they" -> "are"; else -> "is" }

    // ---------------------------------------------------------------- "what day is Christmas" / "what's the date next Friday"

    private val RE_WHAT_DAY = Regex("^(?:what|which) day(?: of the week)? (is|was|will|would|does|did|is it|was it)(?: it)?(?: be)? (.+?)(?: (?:fall on|falls on|fall|be on|land on|on))?$")
    private val RE_WHAT_DAY_POST = Regex("^(?:what|which) day(?: of the week)? (.+?) (is|was|will be|falls on|fell on|is on|was on|will fall on)$")
    private val RE_DAY_OF_POST = Regex("^(.+?) (is|was|will be|falls|fell|will fall|is falling)(?: on)? (?:what|which) day(?: of the week)?$")

    private fun whatDay(s: String, today: LocalDate): String? {
        val (verb, x) = RE_WHAT_DAY.matchEntire(s)?.let { it.groupValues[1] to it.groupValues[2] }
            ?: RE_WHAT_DAY_POST.matchEntire(s)?.let { it.groupValues[2] to it.groupValues[1] }
            ?: RE_DAY_OF_POST.matchEntire(s)?.let { it.groupValues[2] to it.groupValues[1] }
            ?: return null
        return describe(x, today, past = verb.startsWith("was") || verb == "did" || verb == "fell")
    }

    private val RE_WHAT_DATE = Regex("^what(?:'s| is| was| will be| would be)? (?:the )?date (?:of |on |for |is |was |will be |will it be |would it be |" +
        "is it |was it |it will be )?(.+)$")
    private val RE_WHAT_DATE_WILL = Regex("^what will (?:the date be|be the date) (.+)$")
    private val RE_WHEN = Regex("^when(?:'s| is| was| will be| will| does| did)? (.+?)(?: be| fall| come)?$")

    private fun whatDate(s: String, today: LocalDate): String? {
        RE_WHAT_DATE.matchEntire(s)?.let { m ->
            val head = s.removeSuffix(m.groupValues[1])
            return describe(m.groupValues[1], today, past = head.split(' ').contains("was"))
        }
        RE_WHAT_DATE_WILL.matchEntire(s)?.let { m -> return describe(m.groupValues[1], today, past = false) }
        // "When is Farmers' Day?" — only a named holiday or day ("when is my exam" is the model's).
        RE_WHEN.matchEntire(s)?.let { m ->
            val t = target(m.groupValues[1], today, past = s.startsWith("when was") || s.startsWith("when did"))
            if (t != null && (t.kind == Kind.HOLIDAY || t.kind == Kind.WEEKDAY)) return describe(t, today)
        }
        return null
    }

    private fun describe(x: String, today: LocalDate, past: Boolean): String? {
        val t = target(x, today, past) ?: return null
        if (t.kind == Kind.RELATIVE && t.date == today) return null   // "what day is it today" is FastIntent.Day's
        return describe(t, today)
    }

    private fun describe(t: Target, today: LocalDate): String {
        val n = ChronoUnit.DAYS.between(today, t.date)
        val d = spoken(t.date, today)
        return when (t.kind) {
            Kind.HOLIDAY -> when {
                n == 0L -> "${t.label} is today, $d."
                n == 1L -> "${t.label} is tomorrow, $d."
                n > 0 -> "${t.label} is on $d, in ${plural(n, "day")}."
                else -> "${t.label} was on $d."
            }
            Kind.WEEKDAY -> "${t.label} ${if (n < 0) "was" else "is"} ${spoken(t.date, today, weekday = false)}."
            Kind.RELATIVE -> when {
                t.label.startsWith("In ") -> "${t.label}, it will be $d."
                n < 0 -> "${t.label} was $d."
                else -> "${t.label} is $d."
            }
            Kind.DATE -> {
                val plain = spoken(t.date, today, weekday = false)
                when {
                    n == 0L -> "$plain is today, a ${day(t.date.dayOfWeek)}."
                    n < 0 -> "$plain was a ${day(t.date.dayOfWeek)}."
                    else -> "$plain is a ${day(t.date.dayOfWeek)}."
                }
            }
        }
    }

    /** How the countdown answers name the day: the holiday, "Next Friday", or the date itself. */
    private fun subject(t: Target, today: LocalDate) = when (t.kind) {
        Kind.DATE -> spoken(t.date, today)
        else -> t.label
    }

    private fun onPart(t: Target, today: LocalDate) = when (t.kind) {
        Kind.HOLIDAY -> ", on ${spoken(t.date, today)}"
        Kind.WEEKDAY -> ", on ${spoken(t.date, today, weekday = false)}"
        else -> ""
    }

    // ---------------------------------------------------------------- "is Christmas on a Friday?" / "what year is it?"

    private val RE_IS_ON = Regex("^(?:is|was|will|does|did) (.+?)(?: be| fall| falls)?(?: on)? (?:a |an )?$WEEKDAYS(?: this year)?$")

    private fun isOn(s: String, today: LocalDate): String? {
        val m = RE_IS_ON.matchEntire(s) ?: return null
        val x = m.groupValues[1]
        if (x in TODAY_WORDS) return null   // "is today Monday" is FastIntent.Day's
        val t = target(x, today, past = s.startsWith("was") || s.startsWith("did"))
            ?.takeIf { !(it.kind == Kind.RELATIVE && it.date == today) } ?: return null
        val yes = t.date.dayOfWeek == DayOfWeek.valueOf(m.groupValues[2].uppercase())
        return (if (yes) "Yes. " else "No. ") + describe(t, today)
    }

    private val RE_YEAR_NOW = Regex("^(?:what|which) year (?:is it|is this|are we in|is it now|is it today)(?: now)?$")
    private val RE_MONTH_NOW = Regex("^(?:what|which) month (?:is it|is this|are we in|is it now|is it today)(?: now)?$")

    private fun thisYearOrMonth(s: String, today: LocalDate): String? = when {
        RE_YEAR_NOW.matches(s) -> "It's ${today.year}."
        RE_MONTH_NOW.matches(s) -> "It's ${month(today)} ${today.year}."
        else -> null
    }

    // ---------------------------------------------------------------- leap years and month lengths

    private val RE_LEAP = Regex("^(?:is|was|will) (\\d{4}|this year|next year|last year)(?: be)? a leap year$")
    private val RE_DAYS_IN_MONTH = Regex("^how many days (?:are )?(?:there )?(?:in|does|has) (?:the month of )?$MONTH(?: $YEAR)?(?: have| got)?$")
    private val RE_DAYS_IN_THIS_MONTH = Regex("^how many days (?:are )?(?:there )?(?:in|does|has) (?:this|the current) month(?: have| got)?$")
    private val RE_DAYS_IN_YEAR = Regex("^how many days (?:are )?(?:there )?(?:in|does|has) (a year|this year|next year|\\d{4})(?: have| got)?$")

    private fun yearFacts(s: String, today: LocalDate): String? {
        RE_LEAP.matchEntire(s)?.let { m ->
            val y = when (val w = m.groupValues[1]) { "this year" -> today.year; "next year" -> today.year + 1; "last year" -> today.year - 1; else -> w.toInt() }
            if (y !in 1900..2200) return null
            val tense = if (y < today.year) "was" else "is"
            if (java.time.Year.isLeap(y.toLong())) return "Yes, $y $tense a leap year: February has 29 days."
            val next = (y + 1..y + 8).first { java.time.Year.isLeap(it.toLong()) }
            return "No, $y $tense not a leap year. The next one after it is $next."
        }
        if (RE_DAYS_IN_THIS_MONTH.matches(s)) return "${month(today)} ${today.year} has ${today.lengthOfMonth()} days."
        RE_DAYS_IN_MONTH.matchEntire(s)?.let { m ->
            val mo = monthNum(m.groupValues[1]) ?: return null
            val name = java.time.Month.of(mo).getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            if (m.groupValues[2].isNotEmpty()) {
                val y = year(m.groupValues[2]) ?: return null
                return "$name $y has ${java.time.YearMonth.of(y, mo).lengthOfMonth()} days."
            }
            if (mo != 2) return "$name has ${java.time.YearMonth.of(2001, mo).lengthOfMonth()} days."
            val next = (today.year..today.year + 8).first { java.time.Year.isLeap(it.toLong()) }
            return "February has 28 days, or 29 in a leap year. The next leap year is $next."
        }
        RE_DAYS_IN_YEAR.matchEntire(s)?.let { m ->
            val y = when (val w = m.groupValues[1]) { "a year" -> return "A year has 365 days, or 366 in a leap year."
                "this year" -> today.year; "next year" -> today.year + 1; else -> w.toInt() }
            if (y !in 1900..2200) return null
            return "$y has ${java.time.Year.of(y).length()} days."
        }
        return null
    }

    // ---------------------------------------------------------------- Akan day names

    /** The day names every Ghanaian knows (Asante forms): the day of the week a child is born on names them. */
    private val AKAN: Map<DayOfWeek, Pair<String, String>> = mapOf(
        DayOfWeek.SUNDAY to ("Kwasi" to "Akosua"), DayOfWeek.MONDAY to ("Kwadwo" to "Adwoa"),
        DayOfWeek.TUESDAY to ("Kwabena" to "Abena"), DayOfWeek.WEDNESDAY to ("Kwaku" to "Akua"),
        DayOfWeek.THURSDAY to ("Yaw" to "Yaa"), DayOfWeek.FRIDAY to ("Kofi" to "Afua"), DayOfWeek.SATURDAY to ("Kwame" to "Ama")
    )
    /** Other common spellings (mostly Fante) — recognised in "what day is Kojo born", never used in an answer.
     *  [owner: have a native speaker check the list] */
    private val AKAN_VARIANTS: Map<String, Pair<DayOfWeek, Boolean>> = buildMap {
        for ((dow, names) in AKAN) { put(names.first.lowercase(), dow to true); put(names.second.lowercase(), dow to false) }
        fun v(dow: DayOfWeek, boy: Boolean, vararg ns: String) = ns.forEach { put(it, dow to boy) }
        v(DayOfWeek.SUNDAY, true, "kwesi", "akwasi"); v(DayOfWeek.SUNDAY, false, "esi")
        v(DayOfWeek.MONDAY, true, "kojo", "kwadjo"); v(DayOfWeek.MONDAY, false, "adjoa", "ajoa")
        v(DayOfWeek.TUESDAY, true, "kobina"); v(DayOfWeek.TUESDAY, false, "araba")
        v(DayOfWeek.WEDNESDAY, true, "kweku"); v(DayOfWeek.WEDNESDAY, false, "ekua")
        v(DayOfWeek.THURSDAY, true, "ekow"); v(DayOfWeek.THURSDAY, false, "aba")
        v(DayOfWeek.FRIDAY, true, "fiifi"); v(DayOfWeek.FRIDAY, false, "efua", "afia")
        v(DayOfWeek.SATURDAY, true, "kwamena"); v(DayOfWeek.SATURDAY, false, "amma")
    }

    private const val AKAN_KIND = "(?:akan|ghanaian|ghana|twi|fante|fanti|asante|ashanti|kra|soul|local)(?: day)?|day"
    private const val GENDER = "(?:boy|girl|male|female|man|woman|child|baby|person|son|daughter|boys|girls|children|babies|people)"
    private val RE_DAYNAME_BORN = Regex("^(?:(?:what(?:'s| is| are| would be| will be)|tell me|give me)\\s+)?(?:the |my |his |her |their |a |an )?" +
        "(?:$AKAN_KIND) names? ((?:[a-z']+ ){0,8}?)born (?:on )?(?:a |an )?(.+)$")
    private val RE_DAYNAME_FOR = Regex("^(?:(?:what(?:'s| is| are)|tell me|give me)\\s+)?(?:the |a |an )?(?:$AKAN_KIND) names? (?:for|of) (?:a |an )?" +
        "(?:($GENDER) )?(?:born )?(?:on )?(?:a |an )?$WEEKDAYS(?:s)?(?: born)?(?: ($GENDER))?$")
    private val FILLER = setOf("for", "of", "given", "to", "a", "an", "the", "boy", "girl", "man", "woman", "male", "female", "child", "baby",
        "person", "someone", "somebody", "if", "i", "he", "she", "they", "was", "were", "is", "are", "who", "my", "son", "daughter",
        "people", "children", "boys", "girls", "babies", "when", "that", "you")
    private val RE_NAME_DAY = listOf(
        Regex("^(?:what|which) day (?:is|was) (?:a |someone |somebody |a person |a child |a boy |a girl )?(?:called |named )?(\\p{L}+) born(?: on)?$"),
        Regex("^(?:what|which) day (?:is|does) (?:the name )?(\\p{L}+) (?:for|mean|stand for|represent)$"),
        Regex("^(?:what|which) day (?:of the week )?is the (?:(?:akan|day|twi) )?name (\\p{L}+)(?: for)?$"),
        Regex("^(\\p{L}+) is (?:the |a |an )?(?:(?:akan|day|twi) )?name (?:for|of) (?:which|what) day$")
    )

    private fun dayName(s: String, today: LocalDate): String? {
        for (re in RE_NAME_DAY) {
            val m = re.matchEntire(s) ?: continue
            val (dow, boy) = AKAN_VARIANTS[m.groupValues[1]] ?: return null
            val name = m.groupValues[1].replaceFirstChar { it.uppercase() }
            return "$name is a day name for a ${if (boy) "boy" else "girl"} born on a ${day(dow)}."
        }
        RE_DAYNAME_FOR.matchEntire(s)?.let { m ->
            val dow = DayOfWeek.valueOf(m.groupValues[2].uppercase())
            return akanFor(dow, gender(m.groupValues[1] + " " + m.groupValues[3]))
        }
        val m = RE_DAYNAME_BORN.matchEntire(s) ?: return null
        val middle = m.groupValues[1].trim()
        if (middle.isNotEmpty() && middle.split(' ').any { it !in FILLER }) return null
        val x = m.groupValues[2].trim().removeSuffix("s")
        if (RE_WEEKDAY_ONLY.matches(x)) return akanFor(DayOfWeek.valueOf(x.uppercase()), gender(middle))
        val t = target(m.groupValues[2].trim(), today, past = true)?.takeIf { it.kind == Kind.DATE || it.kind == Kind.RELATIVE } ?: return null
        val dow = t.date.dayOfWeek
        val plain = spoken(t.date, today, weekday = false)
        val tense = if (t.date.isAfter(today)) "is" else "was"
        val lead = if (t.kind == Kind.RELATIVE) "${t.label} $tense a ${day(dow)}." else "$plain $tense a ${day(dow)}."
        return "$lead ${akanFor(dow, gender(middle))}"
    }

    private fun gender(words: String): Boolean? {
        val w = words.split(' ').toSet()
        val boy = w.any { it in setOf("boy", "male", "man", "son", "boys") }
        val girl = w.any { it in setOf("girl", "female", "woman", "daughter", "girls") }
        return if (boy == girl) null else boy
    }

    private fun akanFor(dow: DayOfWeek, boy: Boolean?): String {
        val (m, f) = AKAN.getValue(dow)
        return when (boy) {
            true -> "A boy born on a ${day(dow)} is named $m."
            false -> "A girl born on a ${day(dow)} is named $f."
            null -> "A boy born on a ${day(dow)} is named $m, and a girl $f."
        }
    }

    // ---------------------------------------------------------------- speaking

    private fun day(d: DayOfWeek) = d.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    private fun month(d: LocalDate) = d.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

    /** "Friday, 25 December" — the year only when it isn't this year ("Friday, 1 January 2027"). */
    private fun spoken(d: LocalDate, today: LocalDate, weekday: Boolean = true): String {
        val base = "${d.dayOfMonth} ${month(d)}" + if (d.year != today.year) " ${d.year}" else ""
        return if (weekday) "${day(d.dayOfWeek)}, $base" else base
    }

    private fun plural(n: Long, unit: String) = if (n == 1L) "1 $unit" else "$n ${unit}s"

    /** The time between two dates in the unit asked for: "12 weeks and 3 days", "2 months and 26 days", "69 years". */
    private fun span(from: LocalDate, to: LocalDate, unit: String): String {
        val (a, b) = if (from.isAfter(to)) to to from else from to to
        val days = ChronoUnit.DAYS.between(a, b)
        return when (unit) {
            "weeks" -> { val w = days / 7; val r = days % 7
                if (w == 0L) plural(r, "day") else plural(w, "week") + if (r > 0) " and ${plural(r, "day")}" else "" }
            "months" -> { val p = Period.between(a, b); val mo = p.toTotalMonths()
                if (mo == 0L) plural(p.days.toLong(), "day") else plural(mo, "month") + if (p.days > 0) " and ${plural(p.days.toLong(), "day")}" else "" }
            "years" -> { val p = Period.between(a, b)
                if (p.years == 0) plural(p.toTotalMonths(), "month")
                else plural(p.years.toLong(), "year") + if (p.months > 0) " and ${plural(p.months.toLong(), "month")}" else "" }
            else -> plural(days, "day")
        }
    }

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

    private val LEAD = Regex("^(?:(?:please|kindly|so|okay|ok|hey|can you tell me|could you tell me|can you|could you|tell me|do you know|" +
        "i want to know|i'd like to know)\\s+)+")

    private val SPACES = Regex("\\s+")
    private val TAIL = Regex(" (?:please|for me)$")

    private fun clean(g: String): String = g.lowercase().replace('’', '\'').replace(",", " ").trim().trimEnd('?', '.', '!').trim()
        .replace(SPACES, " ").replace(LEAD, "").replace(TAIL, "").trim()
}
