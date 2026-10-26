package com.tekyerema.agent

/**
 * A request that OPENS with a messaging verb aimed at a person — "tell my wife to send me mashup", "ask Kofi to buy me a
 * bundle", "text Ama my battery is dead", "tell Kofi to go back to the shop". Its words are a MESSAGE for someone else, not
 * commands for the phone: the keywords inside it ("mashup", "battery", "back", "mute", "wrong number") must never fire a phone
 * rule. One guard, used by every phone-side keyword router that runs BEFORE the brain (FastIntent, the telco, bank and Mobile
 * Money parsers); a request it holds goes to the brain, which understands it.
 *
 * "tell me …", "ask me …", "let me …", "remind me …" are aimed at the phone and stay with it, as do "text size" (a setting),
 * "message notifications" and "ask chatgpt / gemini / google / siri …" (an app is asked, nobody is messaged). Pure.
 */
object MessageToSomeone {
    private val OPENS = Regex("^\\s*(?:(?:please|kindly|can you|could you|would you|will you|hey|ok|okay) )*" +
        "(?:tell|ask|message|text|whatsapp|sms|let|remind|inform|notify) " +
        "(?!me\\b|us\\b|myself\\b|it\\b|size\\b|notifications?\\b|history\\b|app\\b|chat ?gpt\\b|gemini\\b|google\\b|siri\\b|alexa\\b)")

    fun matches(utterance: String): Boolean =
        OPENS.containsMatchIn(utterance.lowercase().replace('’', '\'').replace(Regex("[^\\p{L}\\p{N}' ]"), " ").replace(Regex("\\s+"), " "))
}
