package com.tekyerema.voice

/**
 * Which English the phone's own voice should speak (Settings › Voice › English voice). No platform ships a Ghanaian
 * English voice; the nearest the phone had, Nigerian English, was turned down by the owner ("it's still the Nigerian
 * accent", 13 October 2026). "auto" = Tɛkyerɛma's own Ghanaian voice when it is installed, else British English; "GB",
 * "NG", "US" = that country's voice when the engine has it offline. Pure: the order the voice picker tries.
 */
object TtsAccent {
    const val AUTO = "auto"
    /** Tɛkyerɛma's own Ghanaian-English voice, on the phone (owner, 18 October 2026: "add it as the fourth ... so fast
     *  and good" — a small Piper voice taught by our fine-tuned F5 voice, see campaign/kaggle/ghen-piper-f5). */
    const val GHANA = "GH"
    val CHOICES = listOf(AUTO, GHANA, "GB", "NG", "US")

    @Volatile var value: String = AUTO

    /** Our own voice speaks first for Automatic and Ghanaian; a country the user picked is the phone's engine alone. */
    fun ownVoiceFirst(choice: String = value): Boolean = choice == AUTO || choice.equals(GHANA, ignoreCase = true)

    /** The country codes to try, best first — the chosen one, then Ghana, then British, Nigerian, American English. */
    fun order(choice: String = value): List<String> =
        (listOfNotNull(choice.takeIf { it != AUTO && it.isNotBlank() }?.uppercase()) + listOf("GH", "GB", "NG", "US")).distinct()

    fun label(choice: String): String = when (choice.uppercase()) {
        GHANA -> "Ghanaian English"; "GB" -> "British English"; "NG" -> "Nigerian English"; "US" -> "American English"
        else -> "Automatic"
    }
}
