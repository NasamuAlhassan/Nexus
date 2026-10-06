package com.tekyerema.language

/**
 * The core Twi requests understood ON THE PHONE (owner, 2 October 2026: "let ours get things right"). A Twi request in one
 * of these shapes becomes the plain English command the rest of the app already handles — the quick device answers, the
 * Mobile Money flow and its read-back, the USSD walk — online or offline, with no translator in the way. Every
 * translation model scored that day broke exactly these: names ("Frɛ Kofi" → "Get some coffee") and money ("Soma sidi du
 * ma Ama" → "Send a dime to Ama"). Anything else stays Twi and goes to the translator and the planner.
 *
 * The input has been through [com.tekyerema.voice.Normalizer.forTranslation] (numerals → digits, "sidi" → cedis, glued
 * words split). Names keep the user's own spelling. Pure → unit-tested.
 * [owner: verify every Twi pattern with a native speaker]
 */
object TwiCommands {
    private const val AMOUNT = "(\\d+(?:\\.\\d+)?)\\s+(cedis?|pesewas?)"
    private const val WANT = "(?:me\\s?pɛ\\s+sɛ\\s+me\\s?|mepɛ\\s+sɛ\\s+|mepɛ\\s+)?"   // "Mepɛ sɛ me…" / "Mepɛ …" — "I want (to)…"
    private val END = Regex("[\\s.!?]+$")
    /** "Mesrɛ wo," (please), "Mepa wo kyɛw," (please), "Yɛ ma me," (do for me) — said before the request itself. */
    private val POLITE = Regex("^(?i:mesr[ɛe]\\s+wo|me\\s*pa\\s+wo\\s+ky[ɛe]w|y[ɛe]\\s+ma\\s+me|wo\\s*b[ɛe]tumi(?:\\s+a)?)[,\\s]+")
    /** ", 1 cedi" / " 10 pesewas" at the end of the person's part (the amount said after the name). */
    private val TRAILING_AMOUNT = Regex("(?i)[,\\s]+(\\d+(?:\\.\\d+)?)\\s+(cedis?|pesewas?)\\s*$")

    /** "Kɔ MoMo na soma …" (go to MoMo and send …) — the same send. */
    private const val VIA = "(?:kɔ\\s+(?:momo|mobile money)\\s+na\\s+)?"
    private val SEND_AMOUNT = Regex("^$VIA${WANT}soma\\s+$AMOUNT\\s+(?:kɔ\\s?ma|ma)\\s+(.+)$")
    /** "soma sidi anum ama": the recogniser swallowed the "ma" of "ma Ama" — only taken with a known person after it. */
    private val SEND_AMOUNT_NO_MA = Regex("^$VIA${WANT}soma\\s+$AMOUNT\\s+(\\S+(?:\\s+\\S+)?)$")
    /** "soma sidi du" — an amount and no one (yet): the money flow asks who. */
    private val SEND_AMOUNT_ONLY = Regex("^$VIA${WANT}soma\\s+$AMOUNT(?:\\s+.*)?$")
    /** "soma sika (cedi) kɔma Prince" — money, with a currency word but no figure ("cedi" alone used to break the match and
     *  send the request to the translator, which dropped it: "Send money to Prince" — A20e, 5 October 2026). */
    private val SEND_MONEY = Regex("^$VIA${WANT}soma\\s+sika(?:\\s+(?:cedis?|pesewas?))?(?:\\s+$AMOUNT)?\\s+(?:kɔ\\s?ma|ma)\\s+(.+)$")
    /** "soma pesee kɔma prinsmalane" — a unit and no figure (the translator made it "Send a bus to Princesman"): money to a
     *  person, amount still to be asked. */
    private val SEND_UNIT = Regex("^$VIA${WANT}soma\\s+(?:sika\\s+)?(?:cedis?|pesewas?|pes[eɛ]e|pɛs[eɛ]e|pes[eɛ]wa|pɛs[eɛ]wa)\\s+(?:kɔ\\s?ma|ma)\\s+(.+)$")
    /** "10 pesewas kɔma 055…" — the amount and the person with the verb lost: a send ("tempɛsos kɔma …", 5 October 2026). */
    private val SEND_NO_VERB = Regex("^$AMOUNT\\s+(?:kɔ\\s?ma|ma)\\s+(.+)$")
    /** "katweɛrichmond", "ka tweɛ Richmond", "kakyerɛ Kofi" — "ka kyerɛ" (tell) as the lab recogniser writes it, often glued
     *  to the name after it (A20e, 5 October 2026). Split back into "ka kyerɛ <name>". */
    private val KA_KYERE = Regex("(?i)(?<![\\p{L}])ka\\s?(?:ky|ty|tw|kw)(?:[ɛe]r[ɛe]|[ɛe][ɛe]|[ɛe]|ere)(?=\\p{L}{3,}|\\s)")
    /** "Richmond wɔ Telegram" — the person, and the app the message goes by. */
    private val ON_APP = Regex("(?i)^(.+?)\\s+wɔ\\s+(telegram|whatsapp|sms)$")
    /** "Tɔ airtime a ne bo yɛ sidi anum" (airtime whose price is 5 cedis) as well as "tɔ airtime sidi anum". */
    private val AIRTIME = Regex("^${WANT}tɔ\\s+(?:kredit|krɛdit|airtime|ɛtaem)(?:\\s+(?:a+\\s+ne\\s?bo\\s+yɛ\\s+)?$AMOUNT)?(?:\\s+ma\\s+(.+))?$")
    /** "Twerɛ nkra kɔ ma Prince sɛ meba" / "Ka kyerɛ Kwame sɛ meba anɔpa" — a message WITH its words. The words after "sɛ"
     *  are sent as the user said them, in Twi: the translator turned "sɛ meba" (that I'm coming) into "when I arrive"
     *  (3 October 2026), and a message dictated in Twi should reach the other person in Twi anyway. */
    private val MESSAGE_SAY = Regex("^(?:(?:twerɛ|twɛrɛ|soma)\\s+(?:nkra|message|mɛsege)\\s+(?:kɔ\\s?ma|ma)|ka\\s+kyerɛ)\\s+(.+?)\\s+sɛ\\s+(.+)$")
    /** "Soma message ma Kwame" — a message with no words yet: the flow asks what to say. */
    private val MESSAGE_TO = Regex("^soma\\s+(?:message|mɛsege|nkra)\\s+(?:kɔ\\s?ma|ma)\\s+(.+)$")
    private val VOLUME_UP = Regex("^ma\\s+(?:volume|nne)(?:\\s+no)?\\s+so$")
    private val VOLUME_DOWN = Regex("^brɛ\\s+(?:volume|nne)(?:\\s+no)?\\s+ase$")
    private val MUSIC = Regex("^bɔ\\s+(?:nnwom|ndwom)(?:\\s+bi)?$")
    private val STOP = Regex("^(?:gyae|gyae\\s+ho|gyina)$")
    private val READ_IT = Regex("^kenkan(?:\\s+no)?$")
    private val READ_ALL = Regex("^kenkan\\s+no\\s+nyinaa$")
    /** "Hena na ɔsomaa no" — either recogniser drops "hena" or "na" ("na ɔsomaa no", "hena ɔsɔmaa no"). */
    private val WHO_SENT = Regex("^(?:hena\\s+)?(?:na\\s+)?ɔs[oɔ]maa?\\s+no$")
    private val DATA = Regex("^${WANT}tɔ\\s+data(?:\\s+bundle)?(?:\\s+(?:a+\\s+ne\\s?bo\\s+yɛ\\s+)?$AMOUNT)?(?:\\s+ma\\s+(.+))?$")
    private val MASHUP = Regex("^${WANT}(?:tɔ\\s+)?mashup(?:\\s+bundle)?(?:\\s+ma\\s+(.+))?$")
    private val CALL = Regex("^frɛ\\s+(.+)$")
    private val FOR_ME = Regex("(?i)\\s+(?:ma\\s+me|mame|maai|mma\\s+me)$")
    private val OPEN = Regex("^bue\\s+(.+)$")
    /** "Kyerɛ me nsɛm a ɛwɔ me screen so" / "Kenkan me screen" / "Hwɛ me screen" / "Ɛdeɛn na ɛwɔ me screen so" — what is on
     *  the screen: the phone reads it itself (QA TR03, 3 October 2026: it went to a cold translator for 40 s, then the cloud). */
    private val SCREEN = Regex("^(?:kyerɛ\\s+me\\s+(?:nsɛm|deɛ)\\s+a\\s+ɛwɔ\\s+me\\s+screen\\s+(?:so|mu)|(?:kenkan|hwɛ|kan)\\s+me\\s+screen(?:\\s+(?:no|so|mu))?|" +
        "(?:ɛdeɛn|deɛn|dɛn)\\s+na\\s+ɛwɔ\\s+me\\s+screen\\s+(?:so|mu))$")
    /** "Me nkra a Prince de somaa me" / "nkra a Kofi somaa me" / "kenkan Kofi nkra" / "Kofi kaa sɛn" — the messages from ONE
     *  person, answered from the phone's notifications (QA TR04: the translator was cold and the planner then lost the name). */
    private val FROM_WHO = Regex("^(?:(?:${WANT}(?:hu|hwɛ|kyerɛ\\s+me|kenkan)\\s+)?(?:me\\s+)?nkra\\s+a\\s+(.+?)\\s+(?:de\\s+)?somaa\\s+me|" +
        "kenkan\\s+(.+?)\\s+nkra|(.+?)\\s+kaa\\s+sɛn)$")
    /** English verb, Twi object — code-mixed money: "Send pesewa du ma Prince" / "send sika ma Ama" (QA TR11: neither parser
     *  took it and the cloud asked for a payment reference). */
    private val SEND_MIXED = Regex("^send\\s+(?:$AMOUNT|sika(?:\\s+$AMOUNT)?)\\s+(?:kɔ\\s?ma|ma|to)\\s+(.+)$")
    /** "nkra" (messages) — the HCI Lab wrote "koraa" for it on GOLD K04 (3 October 2026). */
    private val READ_MESSAGES = Regex("^kenkan\\s+me\\s+(?:nkra|nkraa|nkara|koraa)$")
    private val MOMO_BALANCE = Regex("^(?:me\\s+(?:momo|mobile money)(?:\\s+mu)?\\s+sika(?:\\s+a\\s+aka)?\\s+(?:yɛ|aka|ka)\\s+sɛn" +
        "|hwɛ\\s+me\\s+(?:momo\\s+)?sika(?:\\s+a\\s+aka|\\s+no)?|mewɔ\\s+sika\\s+sɛn)$")
    private val AIRTIME_BALANCE = Regex("^me\\s+(?:kredit|krɛdit|credit|airtime)(?:\\s+no)?\\s+(?:aka|ka|yɛ)\\s+sɛn$")
    /** Everyday code-mixed forms, as Ghana's Abena app lists them in its help (3 October 2026): "Aka me credit sɛn?" (how much
     *  credit have I left), "Checki me MTN credit no ma me" (check my MTN credit for me) — and the same for MoMo and data. */
    private const val CHECK = "(?:check|checki|cheki|chɛki|chɛk)"
    private const val NET = "(?:(?:mtn|telecel|vodafone|at|airteltigo)\\s+)?"
    private val LEFT_OF = Regex("^aka\\s+me\\s+(kredit|krɛdit|credit|airtime|data|momo)(?:\\s+no)?\\s+sɛn$")
    private val CHECK_MY = Regex("^$CHECK\\s+me\\s+$NET(kredit|krɛdit|credit|airtime|data|momo|mobile money)(?:\\s+balance)?(?:\\s+no)?(?:\\s+ma\\s+me)?$")
    private val DATA_LEFT = Regex("^me\\s+data(?:\\s+no)?\\s+(?:aka|ka)\\s+sɛn$")

    private fun balanceOf(what: String) = when (what) {
        "data" -> "check my data balance"
        "momo", "mobile money" -> "check my mobile money balance"
        else -> "check my airtime balance"
    }
    private val STATUS = Regex("^hwɛ\\s+sɛ\\s+me\\s+sika(?:\\s+no)?\\s+(?:kɔeɛ|kɔɔ|akɔ|kɔ)$")
    private val LIGHT_ON = Regex("^sɔ\\s+kanea(?:\\s+no)?$")
    private val LIGHT_OFF = Regex("^dum\\s+kanea(?:\\s+no)?$")
    private val TIME = Regex("^(?:ɛyɛ\\s+dɔn\\s+sɛn|ɛ?b[ɛe]r[ɛe]\\s+b[ɛe]n(?:\\s+na\\s+ɛyɛ|\\s+ni)?|mm?[ɛe]r[ɛe]\\s+b[ɛe]n)$")
    /** "Ɛnnɛ yɛ da bɛn" / "kyerɛ me sɛ ɛnnɛ yɛ da bɛn" (what day is today) and "ɛnnɛ yɛ da a ɛtɔ so sɛn" / "ɛnnɛ da bɛn ni"
     *  (today's date): a phone fact — the translator sent it to the cloud, which answered "Monday, October 3" on a Saturday
     *  (QA R4_TWMT, A20e, 3 October 2026). */
    private val DAY = Regex("^(?:ky[ɛe]r[ɛe]\\s+me\\s+s[ɛe]\\s+)?(?:[ɛe]nn[ɛe]\\s+(?:y[ɛe]\\s+)?da\\s+b[ɛe]n(?:\\s+ni)?|da\\s+b[ɛe]n\\s+ni(?:\\s+[ɛe]nn[ɛe])?|[ɛe]nn[ɛe]\\s+y[ɛe]\\s+da\\s+b[ɛe]n\\s+ni)$")
    private val DATE = Regex("^(?:ky[ɛe]r[ɛe]\\s+me\\s+s[ɛe]\\s+)?(?:[ɛe]nn[ɛe]\\s+(?:y[ɛe]\\s+)?da\\s+a\\s+[ɛe]t[ɔo]\\s+so\\s+s[ɛe]n|[ɛe]nn[ɛe]\\s+(?:y[ɛe]\\s+)?(?:date|deet)\\s+b[ɛe]n(?:\\s+ni)?)$")
    /** "Kae me anɔpa nnɔn 6" (remind me at six in the morning), "ɔkyena" = tomorrow; the part of the day before or after
     *  the hour. Every translator scored on 2 October 2026 got this one wrong ("Remember me at six o'clock"). A reminder
     *  WITH a task ("kae me sɛ …") isn't taken: the task is free Twi, for the translator. */
    private const val PART = "(anɔpa|awia|anwummerɛ|anwumerɛ|anadwo)"
    private val REMIND = Regex("^kae\\s+me\\s+(?:(ɔkyena|nnɛ)\\s+)?(?:$PART\\s+)?(?:nnɔn|dɔn)\\s+(\\d{1,2})(?:\\s+$PART)?(?:\\s+(ɔkyena|nnɛ))?$")

    /**
     * Whole requests with nothing to fill in and nothing that spends, sends or calls — here CLOSE is good enough. The
     * on-device Twi model gets these nearly right ("thinkan me nkra", "kenkan no henaa"; GOLD, 3 October 2026), and a
     * near miss costs nothing worse than reading the wrong thing aloud. Money, calls and messages stay exact-match only.
     */
    private val NEAR = listOf(
        "kenkan me nkra" to "read my messages", "kenkan no nyinaa" to "read all of it", "hena na ɔsomaa no" to "who sent it",
        "hwɛ me sika a aka" to "check my mobile money balance", "mewɔ sika sɛn" to "check my mobile money balance",
        "me momo mu sika aka sɛn" to "check my mobile money balance", "me airtime aka sɛn" to "check my airtime balance",
        "ma volume no so" to "turn the volume up", "brɛ volume no ase" to "turn the volume down",
        "bɔ nnwom bi" to "play some music", "bere bɛn ni" to "what's the time", "ɛyɛ dɔn sɛn" to "what's the time",
        "sɔ kanea no" to "turn on the flashlight", "dum kanea no" to "turn off the flashlight")

    /** Letters only, ɛ→e / ɔ→o, doubled letters as one — how close two spellings SOUND, roughly. */
    private fun key(s: String) = TwiText.fold(s).filter { it.isLetter() }.replace(Regex("(.)\\1+"), "$1")

    private fun lev(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) { val t = d[j]; d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1); prev = t }
        }
        return d[b.length]
    }

    /** The [NEAR] request [low] is closest to — within a quarter of its letters, and clearly closer than any OTHER
     *  request (by 0.15) — or null. Pure. */
    internal fun near(low: String): String? {
        val k = key(low)
        if (k.length < 7) return null                                   // "kya" for "gyae": too short to judge
        val scored = NEAR.map { (twi, en) -> val t = key(twi); en to lev(k, t).toDouble() / maxOf(k.length, t.length) }
        val best = scored.minByOrNull { it.second } ?: return null
        val other = scored.filter { it.first != best.first }.minOfOrNull { it.second } ?: 1.0
        return best.first.takeIf { best.second <= 0.25 && other - best.second >= 0.15 }
    }

    /** "6" + "anɔpa" → "6 am". Afternoon and evening are pm; the night is pm until midnight, then am. */
    private fun clock(hour: Int, part: String?): String? {
        if (hour !in 1..12) return null
        val pm = when (part) {
            "anɔpa" -> false
            "awia", "anwummerɛ", "anwumerɛ" -> hour != 12 || part == "awia"
            "anadwo" -> hour in 7..11
            else -> return "$hour o'clock"
        }
        return "$hour ${if (pm) "pm" else "am"}"
    }

    /** Who, said the Twi way, in English: "me maame" → "my mother"; a name stays as said; "me" alone → me. */
    private val KIN = mapOf("maame" to "mother", "ɛna" to "mother", "papa" to "father", "agya" to "father",
        "nua" to "sibling", "nuabarima" to "brother", "nuabaa" to "sister", "yere" to "wife", "kunu" to "husband",
        "ba" to "child", "nana" to "grandparent")

    private fun who(raw: String): String {
        // "frɛ Prince ma me" — call Prince FOR ME: "ma me" is not part of the name. The lab recogniser writes it "maai" /
        // "mame" (A20e, 5 October 2026: "frɛ prince maai" became a call to "Prince maai", who doesn't exist).
        val w = raw.trim().trimEnd('.', '!', '?').replace(FOR_ME, "").trim().ifEmpty { raw.trim() }
        if (w.equals("me", true)) return "me"
        // "me nua barima" (my brother) is said in two words as often as one
        Regex("^[Mm]e\\s+(\\S+(?:\\s+(?:barima|baa))?)$").find(w)?.let { m ->
            KIN[m.groupValues[1].lowercase().replace(" ", "")]?.let { return "my $it" }
        }
        // The recogniser writes names in lower case: a common name gets its capital back ("ama" → "Ama").
        return w.split(" ").joinToString(" ") { if (it.lowercase() in EntityTags.COMMON_NAMES) it.replaceFirstChar { c -> c.uppercaseChar() } else it }
    }

    /**
     * Whether [raw] can be the person a request is for: "me", a family word ("me maame"), a phone number, a saved contact
     * ([isContact]), a common Ghanaian name, or a name the user TYPED with a capital. A garbled transcript is none of
     * those — "send 10 cedis to ziroffvtziro ne tsi sir" was the result before this check (2 October 2026); now the
     * recipient is left out and the money flow asks who.
     */
    private fun plausible(raw: String, isContact: (String) -> Boolean): Boolean {
        val w = raw.trim().trimEnd('.', '!', '?')
        if (w.isEmpty()) return false
        if (w.equals("me", true) || who(w).startsWith("my ")) return true
        if (w.count { it.isDigit() } >= 9) return true
        if (isContact(w)) return true
        val words = w.split(Regex("\\s+"))
        if (words.size <= 3 && words.all { it.lowercase() in EntityTags.COMMON_NAMES || isContact(it) }) return true
        return words.size <= 3 && words.all { it.first().isUpperCase() && it.drop(1).all { c -> c.isLetter() || c == '-' } }
    }

    private fun amount(n: String?, unit: String?): String? =
        if (n == null || unit.isNullOrBlank()) null else "$n ${unit.lowercase().let { if (it.endsWith("s") || n == "1") it else it + "s" }}"

    /** The English command for a core Twi request, or null when [text] isn't one of them (it then goes to translation).
     *  [isContact]: the word or phrase is one of the phone's saved contacts. */
    fun gloss(text: String, isContact: (String) -> Boolean = { false }): String? {
        // A polite lead-in ("Mesrɛ wo, …" please; "Mepa wo kyɛw, …") is not part of the request — with it in front no
        // pattern matched, and the English bundle parser then took the raw Twi and lost the amount (QA TW05, 3 October 2026).
        val t = POLITE.replace(END.replace(com.tekyerema.voice.Normalizer.forTranslation(text).trim(), "").replace(Regex("\\s+"), " "), "")
            .let { KA_KYERE.replace(it, "ka kyerɛ ").replace(Regex("\\s+"), " ").trim() }
        if (t.isEmpty()) return null
        // Matched in lower case (Ɛ/ɛ and Ɔ/ɔ fold — a regex case flag alone doesn't, and the Unicode-case flag isn't
        // safe on Android's regex engine); a name is taken from the user's own spelling at the same place.
        val low = t.lowercase()
        if (low.length != t.length) return null
        fun MatchResult.g(i: Int): String = groups[i]?.range?.let { t.substring(it.first, it.last + 1) } ?: ""
        // "ma Prince" heard as "ama prince" (the owner's test on the phone, 2 October 2026 — it looked for a contact "Ama
        // Prince"): a leading "ama" before another name is the swallowed "ma", unless the whole phrase is a saved contact.
        fun person(to: String?): String? {
            val t2 = to?.trim() ?: return null
            // a saved contact, in the user's spelling, with its capitals back ("richmond" → "Richmond")
            if (isContact(t2)) return t2.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
            val rest = Regex("^(?i:ama)\\s+(.+)$").find(t2)?.groupValues?.get(1)
            if (rest != null && plausible(rest, isContact)) return rest
            return t2.takeIf { plausible(it, isContact) }
        }
        // A send: the person only when they are plausibly a person — otherwise the amount alone, and the flow asks who.
        fun send(a: String?, to: String?): String =
            "send ${a ?: "money"}" + (person(to)?.let { " to ${who(it)}" }
                // a number the recogniser destroyed stays in, as heard: the money flow asks for it again (MoneyAsk.lostNumber)
                ?: to?.trim()?.takeIf { com.tekyerema.voice.understand.SpokenNumbers.looksLikeLostDigits(it) }?.let { " to $it" } ?: "")
        // "Bue WhatsApp na kenkan me nkra" — two requests joined by "na" (and): each understood on its own, said with "and"
        // (the OPEN pattern used to take "WhatsApp na kenkan me nkra" as the app's name — QA TR06, 3 October 2026).
        Regex("\\s+na\\s+").let { na ->
            if (na.containsMatchIn(t) && !low.contains("sika") && !low.contains("momo")) {
                val parts = t.split(na, 2)
                val a = gloss(parts[0], isContact); val b = gloss(parts[1], isContact)
                if (a != null && b != null) return "$a and $b"
            }
        }
        SEND_AMOUNT.find(low)?.let { m -> return send(amount(m.g(1), m.g(2)), m.g(3)) }
        SEND_MIXED.find(low)?.let { m -> return send(amount(m.g(1).ifEmpty { m.g(3).ifEmpty { null } }, m.g(2).ifEmpty { m.g(4) }), m.g(5)) }
        SEND_MONEY.find(low)?.let { m ->
            var a = amount(m.g(1).ifEmpty { null }, m.g(2))
            var to = m.g(3)
            // "soma sika kɔma Prince Melanin, sidi baako" — the amount said AFTER the person (QA TW08, 3 October 2026:
            // glossed to a bare "send money", name and amount both lost).
            if (a == null) TRAILING_AMOUNT.find(to)?.let { ta -> a = amount(ta.groupValues[1], ta.groupValues[2]); to = to.substring(0, ta.range.first).trim().trimEnd(',') }
            return send(a, to)
        }
        SEND_UNIT.find(low)?.let { m -> return send(null, m.g(1)) }
        SEND_NO_VERB.find(low)?.let { m -> return send(amount(m.g(1), m.g(2)), m.g(3)) }
        SEND_AMOUNT_NO_MA.find(low)?.let { m ->
            val to = m.g(3)
            if (person(to) != null) return send(amount(m.g(1), m.g(2)), to)
        }
        SEND_AMOUNT_ONLY.find(low)?.let { m -> return send(amount(m.g(1), m.g(2)), null) }
        AIRTIME.find(low)?.let { m ->
            val a = amount(m.g(1).ifEmpty { null }, m.g(2))
            val forWho = m.g(3).ifEmpty { null }?.let { who(it) }
            return "buy ${a?.let { "$it of " } ?: ""}airtime for ${if (forWho == null || forWho == "me") "my number" else forWho}"
        }
        DATA.find(low)?.let { m ->
            val a = amount(m.g(1).ifEmpty { null }, m.g(2))
            val forWho = m.g(3).ifEmpty { null }?.let { who(it) }
            return "buy a ${a?.let { "$it " } ?: ""}data bundle" + (forWho?.takeIf { it != "me" }?.let { " for $it" } ?: "")
        }
        MASHUP.find(low)?.let { m ->
            val forWho = m.g(1).ifEmpty { null }?.let { who(it) }
            return "buy a mashup bundle for " + (if (forWho == null || forWho == "me") "my number" else forWho)
        }
        MESSAGE_SAY.find(low)?.let { m ->
            // "… Richmond wɔ Telegram sɛ …": the person, and the app it goes by
            val onApp = ON_APP.find(m.g(1))
            val app = onApp?.groupValues?.get(2)?.let { a -> if (a.equals("sms", true)) "SMS" else a.replaceFirstChar { it.uppercaseChar() } }
            val to = person(onApp?.groupValues?.get(1) ?: m.g(1))
            val words = m.g(2).trim().replaceFirstChar { it.uppercaseChar() }
            // "Ka kyerɛ me sɛ…" is "tell ME…" — a question to the phone, never a message to oneself
            if (to != null && who(to) != "me" && words.isNotEmpty())
                return "send a message to ${who(to)}" + (app?.let { " on $it" } ?: "") + " saying “$words”"
        }
        MESSAGE_TO.find(low)?.let { m -> return "send a message to ${who(person(m.g(1)) ?: m.g(1))}" }
        if (SCREEN.matches(low)) return "read my screen"
        FROM_WHO.find(low)?.let { m ->
            val who = listOf(m.g(1), m.g(2), m.g(3)).firstOrNull { it.isNotBlank() }?.let { person(it) }
            if (who != null && who(who) != "me") return "what did ${who(who)} say"
        }
        if (READ_MESSAGES.matches(low)) return "read my messages"
        if (READ_ALL.matches(low)) return "read all of it"
        if (READ_IT.matches(low)) return "read it"
        if (WHO_SENT.matches(low)) return "who sent it"
        if (VOLUME_UP.matches(low)) return "turn the volume up"
        if (VOLUME_DOWN.matches(low)) return "turn the volume down"
        if (MUSIC.matches(low)) return "play some music"
        if (STOP.matches(low)) return "stop"
        if (MOMO_BALANCE.matches(low)) return "check my mobile money balance"
        if (AIRTIME_BALANCE.matches(low)) return "check my airtime balance"
        LEFT_OF.find(low)?.let { return balanceOf(it.groupValues[1]) }
        CHECK_MY.find(low)?.let { return balanceOf(it.groupValues[1]) }
        if (DATA_LEFT.matches(low)) return "check my data balance"
        if (STATUS.matches(low)) return "did my money go through"
        if (LIGHT_ON.matches(low)) return "turn on the flashlight"
        if (LIGHT_OFF.matches(low)) return "turn off the flashlight"
        if (TIME.matches(low)) return "what's the time"
        if (DAY.matches(low)) return "what day is it today"
        if (DATE.matches(low)) return "what is the date today"
        REMIND.find(low)?.let { m ->
            val at = clock(m.g(3).toInt(), m.g(2).ifEmpty { m.g(4) }.ifEmpty { null }) ?: return null
            val day = m.g(1).ifEmpty { m.g(5) }
            return "remind me at $at" + if (day == "ɔkyena") " tomorrow" else ""
        }
        CALL.find(low)?.let { m -> return "call ${who(m.g(1))}" }
        OPEN.find(low)?.let { m -> return "open ${m.g(1).trim()}" }
        // Nothing exact: a near miss of a harmless whole request (never money, a call or a message — see NEAR).
        if (!Regex("\\d|soma|frɛ|cedi|pesewa|sika\\s+ma").containsMatchIn(low)) near(low)?.let { return it }
        return null
    }
}
