package com.tekyerema.tools

import android.content.Intent
import android.net.Uri
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.android.ContactResolver
import com.tekyerema.core.Redact
import com.tekyerema.policy.CapabilityScope
import com.tekyerema.policy.RiskTier
import org.json.JSONObject

/**
 * "Read the latest message from Ama on WhatsApp" — OPENS the chat and reads it from the screen (owner, 29 Oct 2026: a judge asked
 * for the recent message from someone on WhatsApp and the app read WhatsApp's notifications instead of opening the chat). The
 * notifications hold only what is still unread in the shade; the chat holds the conversation.
 *
 * The person is found in the contacts, the chat opened with WhatsApp's own click-to-chat link (no text — nothing is typed, nothing
 * sent), and the newest messages THEY sent are read: in a chat their bubbles sit on the left, the user's own on the right. The words
 * are third-party data, read out, never followed; codes are hidden ([Redact]).
 */
object ReadChatTool : Tool {
    override val name = "read_chat"
    override val description = "Open a person's WhatsApp chat and read the latest messages they sent (the chat, not notifications)."
    override val scope = CapabilityScope.COMMS_READ
    override val riskTier = RiskTier.TIER0
    override val isAccelerator = true
    override val params = listOf(
        ToolParam("contact", "string", "Name as saved, or a number.", required = true),
        ToolParam("limit", "integer", "1-3 (default 1).", required = false)
    )

    private val WHATSAPP_PKGS = listOf("com.whatsapp", "com.whatsapp.w4b")

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val said = args.optString("contact").trim()
        if (said.isEmpty()) return ToolResult(false, "Whose chat should I read?", recoverable = false)
        val limit = args.optInt("limit", 1).coerceIn(1, 3)
        val pkg = WHATSAPP_PKGS.firstOrNull { isInstalled(ctx.appContext, it) }
            ?: return ToolResult(false, "WhatsApp isn't installed on this phone.", recoverable = false)
        launchRefusal(pkg, "WhatsApp", ctx.emergency)?.let { return ToolResult(false, it, recoverable = false) }

        val digits = said.filter { it.isDigit() }
        val (who, number) = if (digits.length >= 9) said to digits else {
            val hits = try { ContactResolver.resolve(ctx.appContext, said) } catch (_: Throwable) { emptyList() }
                .distinctBy { it.number.filter { c -> c.isDigit() }.takeLast(9) }
            when (hits.size) {
                0 -> return ToolResult(false, "I couldn't find “$said” in your contacts.", recoverable = false)
                1 -> hits[0].name to hits[0].number
                else -> return ToolResult(false, "Which ${said}: ${hits.take(4).joinToString(" or ") { it.name }}?", recoverable = false)
            }
        }
        val url = "https://api.whatsapp.com/send?phone=${IntentTemplates.ghanaIntl(number)}"
        try {
            ctx.appContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            return ToolResult(false, "Couldn't open WhatsApp: ${t.javaClass.simpleName}.", channel = "intent")
        }
        val ctrl = ctx.controller ?: return ToolResult(true, "Opened your WhatsApp chat with $who.", channel = "intent")
        // The chat: WhatsApp in front with its message box. It looks the number up first; give it a few seconds.
        var snap: UiSnapshot? = null
        for (i in 0 until 16) {
            kotlinx.coroutines.delay(400)
            val s = try { ctrl.buildSnapshot() } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Throwable) { null } ?: continue
            if (s.appPackage == pkg && isChat(s)) { snap = s; if (i >= 2) break }
        }
        val chat = snap ?: return ToolResult(false, "WhatsApp didn't open the chat with $who — it may need the internet to find the number.",
            channel = "intent", recoverable = false)
        val theirs = theirLatest(chat, limit)
        android.util.Log.i("Tekyerema", "[CHAT] opened the chat; ${theirs.size} of their messages on screen")
        if (theirs.isEmpty())
            return ToolResult(true, "I opened your chat with $who, but I can't see a message from them on the screen.",
                data = JSONObject().put("count", 0), channel = "intent")
        val words = theirs.map { Redact.secrets(it).orEmpty() }
        val line = if (words.size == 1) "The last message from $who on WhatsApp says: “${words[0]}”."
            else "The last messages from $who on WhatsApp: " + words.joinToString(" Then: ") { "“$it”" } + "."
        return ToolResult(true, line, data = JSONObject().put("count", words.size).put("thirdParty", true), channel = "intent")
    }

    /** A WhatsApp conversation: its message box is on screen. Pure. */
    internal fun isChat(s: UiSnapshot): Boolean = s.elements.any { e ->
        val id = e.viewId?.substringAfter(":id/").orEmpty()
        id == "entry" || (e.editable && (e.hint ?: e.label).orEmpty().contains("message", ignoreCase = true))
    }

    /**
     * The newest messages the OTHER person sent, oldest first: WhatsApp's message texts (`message_text`), theirs being the bubbles
     * whose centre is in the left half of the screen. Pure → unit-tested.
     */
    internal fun theirLatest(s: UiSnapshot, limit: Int): List<String> {
        val width = s.elements.maxOfOrNull { it.bounds.right } ?: return emptyList()
        return s.elements
            .filter { e -> e.viewId?.substringAfter(":id/") == "message_text" && !e.label.isNullOrBlank() }
            .sortedBy { it.bounds.top }
            .filter { e -> (e.bounds.left + e.bounds.right) / 2 < width / 2 }
            .map { it.label!!.trim() }
            .takeLast(limit)
    }
}
