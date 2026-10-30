package com.tekyerema.voice

/**
 * What a recogniser makes of Ghana's money and telecom words, put right before the request is acted on. Pure →
 * unit-tested on the transcripts themselves (GhanaWordsTest).
 *
 * Why: neither recogniser knows these words. Measured on 2 October 2026 —
 *  - our own offline model (Parakeet fine-tuned on Ghanaian English), on the owner's own recordings: "cedis" right in
 *    7 of 20 clips ("20 cities", "1 CD", "2 cds.", "5 ceds"), "pesewas" in 0 of 3 ("50 ps.", "50 pesos"), "MoMo" as
 *    "Mumu", "WhatsApp" as "was up" / "what's up" / "wasa", "Buy 5 cedis of airtime" as "By 5 cities of airtime";
 *  - the phone's recogniser (Google, en-GH — it cannot be given a word list on Android 11), live on the A20e:
 *    "10 pesos" for ten pesewas, three times in one session, and "a telegram number" for a Telecel number.
 *
 * Every rule is narrow on purpose — a wrong repair is worse than none (the read-back and the confirmation still see
 * the result, but a blind user hears only what we say), and a repair must never rewrite the WORDS OF A MESSAGE:
 *  - "pesos" after a figure is pesewas, except in a question about currencies ("what is 100 pesos in cedis");
 *  - "cities / CDs / ceds / ps" after a figure are money only when money is being moved right there — a paying verb
 *    just before ("send two cities", "send Ama 20 cities"), airtime / credit / data / Mobile Money just after — or the
 *    words are nothing but an amount (the answer to "How much?"), or the caller says so ([fix] `aboutMoney`).
 *    "Send Ama a message that I visited 2 cities" keeps its cities;
 *  - "Mumu" is MoMo only where an app or wallet is named ("go to Mumu", "my Mumu balance"), in a money request;
 *  - "telegram number" is "Telecel number" only next to number / line / SIM / bundle words AND with airtime, data or an
 *    amount elsewhere in the request. "send it on Telegram", "clear Telegram data" and "telephone number" are left alone;
 *  - "what's up" / "wasa" are WhatsApp only after an app word (open, close, on, via …) and at the end of the phrase —
 *    "go to Wasa Akropong" is a town.
 * The words as heard are kept for the screen ([Transcript.raw]); only the request that is acted on changes. Typed
 * requests are not repaired (VoiceController.runTurn) — these are recogniser mistakes, not typing mistakes.
 */
object GhanaWords {
    private const val NUM = "(?:(?<![\\d,.])\\d+(?:[.,]\\d+)*|\\b(?:one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|" +
        "fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand))"

    private val FOREX = Regex("(?i)\\b(?:convert|exchange|rate|dollars?|euros?|naira|pounds?|mexic|philippin)|\\b(?:in|to|into)\\s+(?:ghana\\s+)?cedis\\b")

    /** The request is about money — judged on words other than the ones being repaired. */
    private val MONEY_SHAPED = Regex("(?i)\\b(?:send|sent|sending|pay|paid|buy|bought|transfer|give|withdraw|deposit|owe|costs?|price|" +
        "balance|airtime|credit|momo|mobile money|pesewas?|cedis?|wallet|top ?up|bundles?|data|cash ?out|mash ?up)\\b")
    /** Airtime, data or an amount is named — what makes "a telegram number" a network and not the chat app. */
    private val TELCO = Regex("(?i)\\b(?:airtime|credit|data|bundles?|mash ?up|cedis?|pesewas?|momo|mobile money|top ?up)\\b")

    // "10 pesos" — heard live, read back as 10 cedis (a hundred times too much). After a figure.
    private val PESEWAS = Regex("(?i)($NUM\\s+)(?:pesos?|peswas?|pesuas?|pesowas?|peshawas?|pesawas?|pisewas?|pessewas?|pesoas?)\\b")
    private val PESEWAS_PS = Regex("(?i)($NUM\\s+)(?:ps|p's)\\b")
    private val CEDIS = Regex("(?i)($NUM\\s+)(?:cities|city|ceds|ced|cdis|cds|cd's|cd)\\b")
    /** Nothing but an amount: "20 cities", "10 cities 50 ps." — the answer to "How much?". */
    private val ONLY_AMOUNT = Regex("(?i)^\\s*$NUM\\s+\\p{L}[\\p{L}']*(?:\\s+(?:and\\s+)?$NUM\\s+\\p{L}[\\p{L}']*)?\\s*[.!?]?\\s*$")

    /** Directly before the figure: money is being moved, priced or bought. */
    private val PAYING = setOf("send", "sent", "sending", "pay", "paid", "give", "transfer", "buy", "bought", "cost",
        "costs", "withdraw", "deposit", "owe", "recharge")
    /** "send Ama 20 cities", "send my wife 20 cities": the verb, then who, then the figure. */
    private val GIVING = setOf("send", "sent", "give", "pay", "paid", "transfer")
    /** Words that are never the "who" between a giving verb and a figure — they start a thing or a message. */
    private val NOT_WHO = setOf("a", "an", "the", "that", "this", "these", "those", "of", "about", "message", "messages", "text",
        "picture", "pictures", "photo", "photos", "image", "images", "video", "videos", "and", "i", "we", "you",
        // "send me 5 cities in the north", "give us 3 cities to visit": the speaker is not paying themselves
        "me", "us", "it")
    private val BOUGHT = setOf("airtime", "credit", "data", "mashup", "bundle", "bundles", "momo", "pesewas", "pesewa")
    private val WORD = Regex("[\\p{L}\\p{N}']+")

    private const val MOMO_WORD = "(?:mumu|moomoo|mu mu|mo mo|mow mow|more more|mo more|more mo)"   // "more more": Infinix, 29 Oct 2026
    private val MOMO_AFTER = Regex("(?i)\\b(go to|open|my|on|via|with|from|use|using|check|into|through)(\\s+)$MOMO_WORD\\b")
    private val MOMO_BEFORE = Regex("(?i)\\b$MOMO_WORD(?=\\s+(?:balance|wallet|pin|account|agent)\\b)")
    private val MOMO_ANY = Regex("(?i)\\b$MOMO_WORD\\b")
    /** "my normal / mom / mama balance": "MoMo", as the phone's English recogniser hears it before "balance" (Infinix, 28 Oct 2026:
     *  "Where's my normal balance?", "what's my mom balance"). Only after "my" and before balance / wallet / account. */
    private val MOMO_MISHEARD = Regex("(?i)\\b(my\\s+)(?:normal|mom|mama|mummy|mum|mamo|moma|momma|mamma|mommy|mono|moono|mumo|mum who|mom who|mamu|move|moves)(?=\\s+(?:balance|belongs?|wallet|account)\\b)")
    /** Airtime said fast: "back once this airtime" / "buy once it is airtime" = "buy one cedi's airtime" (Infinix, 28 Oct 2026). Only
     *  in a sentence about airtime / credit / a bundle. */
    private val AIRTIME_TALK = Regex("(?i)\\b(?:airtime|credit|bundle|data)\\b")
    private val BUY_HEARD = Regex("(?i)\\b(?:back|bye|bike)\\b(?=\\s+(?:me\\s+)?(?:once|one|\\d|some|airtime|credit|data|a bundle))")
    private val CEDI_AIRTIME_HEARD = Regex("(?i)\\b(?:cd|c\\.d\\.|cedi|sedi|seedy)\\s*(?:8|eight|ate)\\s*time\\b")
    private val SERIES_OF_AIRTIME = Regex("(?i)\\b(\\d+(?:\\.\\d+)?)\\s+series\\b(?=\\s+(?:of\\s+)?(?:airtime|credit))")
    private val ONE_CEDIS_HEARD = Regex("(?i)\\bonce\\s+(?:it\\s+is|it's|this|is|cities|city's|seedies|seedy's|sedis|cedis|cds)\\b")
    private val BUY = Regex("(?i)^(\\s*)by(\\s+$NUM\\s+(?:ghana\\s+)?(?:cedis?|pesewas?)\\b|\\s+(?:me\\s+)?(?:some\\s+)?(?:airtime|credit|data|a bundle|bundles?|mashup)\\b)")
    private val TELECEL = Regex("(?i)\\b(?:telegram|telesales?|tele ?cell|telecel|telly ?(?:cell|sell)|tele ?sel|tell a (?:cell|sell))" +
        "(\\s+(?:number|line|sim|bundles?|data|airtime|credit|cash|network|mashup)\\b)")
    private val WHATSAPP = Regex("(?i)\\b(open|close|launch|start|go to|on|via|through|using|opening|hoping)(\\s+(?:my\\s+)?)" +
        "(?:was up|what'?s up|whats ?app|what'?s app|wasa|wassup|watsup|what sup|was app|what sap)\\b" +
        "(?=\\s*(?:[.,!?:;]|$)|\\s+(?:and|to|for|that|messages?|chats?|groups?|status|call|now|please)\\b)")
    private val HOPING = Regex("(?i)^(\\s*)(?:hoping|opening)(\\s+WhatsApp\\b)")

    /**
     * [text] with the money and telecom words a recogniser gets wrong put right; unchanged when nothing applies.
     * [aboutMoney]: the caller already knows the words are about money — the answer to "How much?", a correction to a
     * payment being confirmed — so "no, 20 cities" needs no money word of its own to be read as cedis.
     */
    /** "Send once it is to my husband" — "send one cedi's" as the offline English recogniser writes it (Infinix, 29 Oct 2026, 22:32: it
     *  became a TEXT saying "once it is"). Only right after "send", so a message's own words are never touched. */
    private val SEND_ONE_HEARD = Regex("(?i)^(\\s*sen[dt])\\s+once\\s+(?:it\\s+is|it's|this|is|cities|city's|seedies|sedis|cedis|cds)\\b")
    /** "Sent 1 cedis to my husband" — the same recogniser's "send" before an amount (22:36: "I can't do that"). */
    private val SENT_AMOUNT = Regex("(?i)^(\\s*)sent(?=\\s+(?:\\d+(?:\\.\\d+)?|one|two|five|ten|twenty|fifty)\\s+(?:ghana\\s+)?(?:cedis?|pesewas?)\\b)")

    fun fix(text: String, aboutMoney: Boolean = false): String {
        if (text.isBlank()) return text
        @Suppress("NAME_SHADOWING") val text = SENT_AMOUNT.replace(SEND_ONE_HEARD.replace(text) { m -> m.groupValues[1] + " 1 cedi" }) { m -> m.groupValues[1] + "Send" }
        // A MESSAGE keeps its words: no money or network repair in it, the app's name only, and only before the message
        // itself begins ("let Kofi know that I sent 10 pesos" is what the user said — safety audit 2026-10-05).
        if (!aboutMoney && com.tekyerema.voice.understand.MessageBody.isMessaging(text)) {
            val (head, body) = com.tekyerema.voice.understand.MessageBody.split(text)
            val h = HOPING.replace(WHATSAPP.replace(head) { m -> m.groupValues[1] + m.groupValues[2] + "WhatsApp" }) { m ->
                m.groupValues[1] + "Open" + m.groupValues[2] }
            return h + body
        }
        var t = text
        if (!FOREX.containsMatchIn(t)) {
            t = PESEWAS.replace(t) { m -> m.groupValues[1] + "pesewas" }
            val all = aboutMoney || ONLY_AMOUNT.matches(t)
            t = repairAmounts(t, PESEWAS_PS, "pesewas", all)
            t = repairAmounts(t, CEDIS, "cedis", all)
        }
        t = MOMO_MISHEARD.replace(t) { m -> m.groupValues[1] + "MoMo" }
        // "What's my mum who belongs?" (the offline English recogniser, Infinix, 29 Oct 2026): after MoMo, "belongs" is balance
        t = Regex("(?i)\\b(my\\s+MoMo)\\s+belongs?\\b").replace(t, "$1 balance")
        t = CEDI_AIRTIME_HEARD.replace(t, "cedi airtime")
        t = SERIES_OF_AIRTIME.replace(t) { m -> m.groupValues[1] + " cedis" }
        if (AIRTIME_TALK.containsMatchIn(t)) t = ONE_CEDIS_HEARD.replace(BUY_HEARD.replace(t, "buy"), "1 cedi's")
        t = BUY.replace(t) { m -> m.groupValues[1] + "Buy" + m.groupValues[2] }
        if (aboutMoney || MONEY_SHAPED.containsMatchIn(MOMO_ANY.replace(t, " "))) {
            t = MOMO_AFTER.replace(t) { m -> m.groupValues[1] + m.groupValues[2] + "MoMo" }
            t = MOMO_BEFORE.replace(t, "MoMo")
        }
        if (aboutMoney || TELCO.containsMatchIn(TELECEL.replace(t, " "))) t = TELECEL.replace(t) { m -> "Telecel" + m.groupValues[1] }
        t = WHATSAPP.replace(t) { m -> m.groupValues[1] + m.groupValues[2] + "WhatsApp" }
        t = HOPING.replace(t) { m -> m.groupValues[1] + "Open" + m.groupValues[2] }
        return t
    }

    /** Each "figure + mis-heard unit" becomes "figure + [unit]" where money is being moved right there ([movesMoney]). */
    private fun repairAmounts(t: String, rx: Regex, unit: String, all: Boolean): String =
        rx.replace(t) { m -> if (all || movesMoney(t, m.range)) m.groupValues[1] + unit else m.value }

    /** A paying verb just before the figure (optionally with who in between), or what is bought just after it. */
    private fun movesMoney(t: String, at: IntRange): Boolean {
        val before = WORD.findAll(t.substring(0, at.first)).map { it.value.lowercase() }.toList().takeLast(3)
        val after = WORD.findAll(t.substring(at.last + 1)).map { it.value.lowercase() }.toList().take(3)
        val n = before.size
        if (n >= 1 && before[n - 1] in PAYING) return true
        if (n == 1 && before[0] == "by") return true           // "By 5 cities of airtime": "Buy", heard at the very start
        if (n >= 2 && before[n - 2] == "top" && before[n - 1] == "up") return true      // "top up 5 cds" — not "the top 5 cities"
        if (n >= 2 && before[n - 2] in GIVING && before[n - 1] !in NOT_WHO) return true
        if (n >= 3 && before[n - 3] in GIVING && before[n - 2] !in NOT_WHO && before[n - 1] !in NOT_WHO) return true
        // "airtime of 5 cities" — but not "mobile data for 3 cities"
        if (n >= 2 && before[n - 1] in setOf("of", "worth") && before[n - 2] in BOUGHT) return true
        if (after.isNotEmpty() && after[0] in BOUGHT) return true
        if (after.size >= 2 && after[0] == "of" && (after[1] in BOUGHT || after[1] == "mobile")) return true
        if (after.size >= 2 && after[0] == "mobile" && after[1] == "money") return true
        return false
    }
}
