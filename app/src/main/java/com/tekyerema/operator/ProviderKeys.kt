package com.tekyerema.operator

/**
 * WS-C — bring-your-own keys and your own server, the pure parts (unit-tested). Keys are stored encrypted by
 * [com.tekyerema.settings.Settings] ("enc1:" + Keystore AES-GCM), shown only masked, and never logged.
 */
object ProviderKeys {
    /** "••••1a2b" — the last four characters only, and only for a key long enough that they don't give it away. */
    fun mask(key: String): String {
        val k = key.trim()
        return when {
            k.isEmpty() -> ""
            k.length < 12 -> "••••"
            else -> "••••" + k.takeLast(4)
        }
    }

    /** A plausible API key: no spaces or line breaks, 8..400 printable characters. The message says what's wrong. */
    fun keyProblem(key: String): String? {
        val k = key.trim()
        return when {
            k.isEmpty() -> "Type or paste the key first."
            k.any { it.isWhitespace() } -> "A key has no spaces in it — check what was pasted."
            k.length < 8 -> "That's too short to be a key."
            k.length > 400 -> "That's too long to be a key."
            k.any { it.code < 0x21 || it.code > 0x7e } -> "A key only has letters, numbers and symbols."
            else -> null
        }
    }

    private val PRIVATE_HOST = Regex("^(?:localhost|127(?:\\.\\d{1,3}){3}|10(?:\\.\\d{1,3}){3}|192\\.168(?:\\.\\d{1,3}){2}|" +
        "172\\.(?:1[6-9]|2\\d|3[01])(?:\\.\\d{1,3}){2}|[a-z0-9-]+\\.local)$")

    /**
     * An OpenAI-compatible server address the user may point Tɛkyerɛma at: https anywhere, or plain http only on the
     * user's own network (a laptop running Ollama). Null = fine; otherwise why not.
     */
    fun serverProblem(url: String): String? {
        val u = url.trim()
        if (u.isEmpty()) return "Type the server address first."
        val uri = try { java.net.URI(u) } catch (_: Throwable) { return "That isn't a web address." }
        val host = uri.host?.lowercase() ?: return "That address has no server name."
        return when (uri.scheme?.lowercase()) {
            "https" -> null
            "http" -> if (PRIVATE_HOST.matches(host)) null else "Use https:// — plain http is only allowed on your own network."
            else -> "The address must start with https://"
        }
    }
}
