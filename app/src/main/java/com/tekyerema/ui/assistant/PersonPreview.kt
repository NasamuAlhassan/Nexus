package com.tekyerema.ui.assistant

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tekyerema.ui.glass.GlassDepth
import com.tekyerema.ui.glass.GlassShape
import com.tekyerema.ui.glass.LiquidStyles
import com.tekyerema.ui.glass.liquidGlass
import com.tekyerema.ui.theme.Spectrum

/**
 * Who a confirmation is about, read from its own words (pure, unit-tested): a call, a text or a payment, the person, the
 * digits as read, the amount and network for money, the message for a text. Null when the prompt is about something else.
 */
object ConfirmFacts {
    enum class Kind { CALL, TEXT, MONEY }
    data class Facts(val kind: Kind, val name: String, val digits: String?, val amount: String? = null, val network: String? = null,
                     val message: String? = null, val ending: Boolean = false,
                     /** An announced call ("Calling …. Say stop to cancel."): it goes ahead when its short window passes. */
                     val announced: Boolean = false)

    private const val NAME = "([A-Z][\\p{L}'.-]*(?: [A-Z][\\p{L}'.-]*){0,3})"
    private val PREFIX = Regex("^Taking '\\w+' as [^.]+\\.\\s*")
    private val CALL = Regex("^Call $NAME(?:, ending ([0-9 ]+))?\\?")
    private val CALLING = Regex("^Calling $NAME(?:, ending ([0-9 ]+))?\\.")
    private val TEXT = Regex("^(?:Text|Send a text to|Message) $NAME(?:, ending ([0-9 ]+))?\\.")
    private val MESSAGE = Regex("The message: [“\"](.+?)[”\"]")
    private val MONEY = Regex("^Send (\\d+(?:\\.\\d+)?) cedis? to $NAME, on ([0-9][0-9 ,]*[0-9])(?:, an? (MTN|Telecel|AT|AirtelTigo|Vodafone) number)?,")

    fun parse(prompt: String): Facts? {
        val t = prompt.replace('⁣', ' ').replace(PREFIX, "").trim()
        MONEY.find(t)?.let { m ->
            return Facts(Kind.MONEY, m.groupValues[2], m.groupValues[3].replace(Regex("[^0-9]"), ""), amount = m.groupValues[1],
                network = m.groupValues[4].ifBlank { null })
        }
        CALL.find(t)?.let { m -> return Facts(Kind.CALL, m.groupValues[1], m.groupValues[2].replace(" ", "").ifBlank { null }, ending = m.groupValues[2].isNotBlank()) }
        CALLING.find(t)?.let { m -> return Facts(Kind.CALL, m.groupValues[1], m.groupValues[2].replace(" ", "").ifBlank { null },
            ending = m.groupValues[2].isNotBlank(), announced = true) }
        TEXT.find(t)?.let { m ->
            return Facts(Kind.TEXT, m.groupValues[1], m.groupValues[2].replace(" ", "").ifBlank { null }, message = MESSAGE.find(t)?.groupValues?.get(1),
                ending = m.groupValues[2].isNotBlank())
        }
        return null
    }

    /** "050 000 2222" for ten digits; "•••• 1111" for an ending. */
    fun shownNumber(f: Facts): String? {
        val d = f.digits ?: return null
        if (f.ending) return "•••• $d"
        return if (d.length == 10) "${d.substring(0, 3)} ${d.substring(3, 6)} ${d.substring(6)}" else d
    }

    fun initials(name: String): String = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
}

/**
 * The person a call, text or payment is for, at the top of the confirmation (owner, 15 Oct 2026: "it shouldn't be opening
 * the contacts to find persons … it should bring the preview to the top"): their initials in a lit disc, the name large,
 * the number as it will be used (the network too for money) and the amount; a text's words in a bubble. Drawn only — the
 * prompt's own words, read in full below it, are what TalkBack and the voice say.
 */
@Composable
fun PersonPreview(f: ConfirmFacts.Facts, modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    val accent = if (g.dark) Color(0xFF2FD8C2) else Color(0xFF0B7F71)
    Column(modifier.fillMaxWidth().clearAndSetSemantics { }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(54.dp)) {
                    drawCircle(Brush.linearGradient(listOf(Spectrum.Aqua, Spectrum.Teal, Color(0xFF0B5E6B)), Offset.Zero, Offset(size.width, size.height)))
                    drawCircle(Brush.radialGradient(0f to Color.White.copy(alpha = 0.45f), 1f to Color.Transparent,
                        center = Offset(size.width * 0.32f, size.height * 0.26f), radius = size.width * 0.42f))
                }
                Text(ConfirmFacts.initials(f.name), style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold), color = Color(0xFF032420))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(f.name, style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
                    color = g.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(ConfirmFacts.shownNumber(f), f.network).joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, style = TextStyle(fontSize = 16.sp, fontFeatureSettings = "tnum"), color = g.textSecondary)
            }
            Spacer(Modifier.width(10.dp))
            when (f.kind) {
                ConfirmFacts.Kind.MONEY -> Column(horizontalAlignment = Alignment.End) {
                    Text("GHS ${f.amount}", style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"), color = g.text)
                    Icon(Icons.Rounded.Payments, null, tint = accent, modifier = Modifier.size(20.dp))
                }
                ConfirmFacts.Kind.CALL -> Icon(Icons.Rounded.Call, null, tint = accent, modifier = Modifier.size(28.dp))
                ConfirmFacts.Kind.TEXT -> Icon(Icons.Rounded.ChatBubble, null, tint = accent, modifier = Modifier.size(26.dp))
            }
        }
        f.message?.let { msg ->
            Spacer(Modifier.padding(top = 12.dp))
            val inner = LiquidStyles.of(g.dark, com.tekyerema.ui.theme.glassMode())
            Box(Modifier.fillMaxWidth()
                .liquidGlass(inner.copy(specular = inner.specular * 0.5f, glint = 0f, grain = 0f), GlassShape.Rounded(20.dp), GlassDepth.FLAT,
                    tint = if (inner.border != null) null else accent.copy(alpha = if (g.dark) 0.16f else 0.12f))
                .padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("“$msg”", style = TextStyle(fontSize = 17.sp, lineHeight = 24.sp), color = g.text)
            }
        }
    }
}

