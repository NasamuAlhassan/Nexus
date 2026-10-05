package com.tekyerema.voice.understand

/**
 * Where a MESSAGE command's own words begin (safety audit, 5 October 2026). The repairs around the recogniser — network
 * names, money words, money amounts, contact names — are for requests the app acts on; inside a message they would put
 * words in the user's mouth ("saying em ti en office is closed" was sent as "MTN office", "I don't have a cedi" as "1
 * cedi", "call coffee now" as "call Kofi now").
 *
 *  - [isMessaging]: the request is a message to someone — it starts with tell / message / text / SMS / WhatsApp / email /
 *    reply / write / ask / "let X know", or says "send … a message";
 *  - [split]: head (who it goes to) and BODY (from "saying", "that", "the message is", ":" or a quote on) — the body is
 *    passed on exactly as heard. A message with no such word has no body split off; the caller then skips its
 *    contextual repairs for the whole request.
 *
 * Pure → SpeechRobustnessTest.
 */
object MessageBody {
    private val LEAD = "^\\s*(?:(?:please|kindly|can you|could you|would you|i want to|i'd like to|help me|go ahead and)\\s+)*"
    private val MESSAGING = Regex("(?i)$LEAD(?:tell(?!\\s+(?:me|us)\\b)|message|text|sms|whatsapp|e-?mail|write(?:\\s+to)?|reply(?:\\s+to)?|" +
        "ask(?!\\s+(?:me|us|for)\\b)|inform|let\\s+\\S+(?:\\s+\\S+)?\\s+know)\\b|" +
        "\\bsend\\s+(?:\\S+\\s+){0,3}?(?:a|an)\\s+(?:message|text|sms|whatsapp|note|e-?mail)\\b")
    /** The first word of the message itself. "that" — but not "that number/person/one" (a recipient). */
    private val MARKER = Regex("(?i)\\b(?:saying|that says|which says|and say|and tell (?:him|her|them)|telling (?:him|her|them)|" +
        "with the message|the message is|message is|that(?!\\s+(?:number|person|contact|one|guy|man|woman|lady)\\b))\\b|[:\"“]")

    fun isMessaging(text: String): Boolean = MESSAGING.containsMatchIn(text)

    /** (head, body): the body from its marker on, exactly as given; ("text", "") when [text] isn't a message or has no marker. */
    fun split(text: String): Pair<String, String> {
        val verb = MESSAGING.find(text) ?: return text to ""
        val m = MARKER.find(text, verb.range.last + 1) ?: return text to ""
        return text.substring(0, m.range.first) to text.substring(m.range.first)
    }
}
