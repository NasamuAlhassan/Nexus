package com.tekyerema.ui.assistant

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.tekyerema.agent.AgentStatus
import com.tekyerema.agent.LogEntry
import com.tekyerema.ui.theme.LocalCalmMotion
import com.tekyerema.ui.theme.Spectrum
import com.tekyerema.voice.ConfirmPhase
import com.tekyerema.voice.ConfirmRetry
import com.tekyerema.voice.PromptSurface
import com.tekyerema.voice.VoiceState
import com.tekyerema.voice.VoiceUiState

/**
 * How the surface reflects the voice pipeline while a prompt waits (docs/assistant-ux/PLAN_V2.md, stage 5). Pure, and only
 * ever a reading of real state: the pipeline's confirmation phase and retry reason, the voice state while the agent asks, the
 * engine's next status and its own log. It never answers anything — voice answers go through the voice pipeline's existing
 * path and checks, taps through AgentEngine.confirm / ChoiceGate; this only shows what they did.
 */
object VoiceReflect {

    sealed interface Cue {
        data object None : Cue
        /** The microphone is really listening for the answer. */
        data class Listening(val hint: String) : Cue
        /** An answer was captured and is being read. */
        data object Heard : Cue
        /** The pipeline is asking again, and why. */
        data class AskingAgain(val note: String) : Cue
    }

    enum class Decision { APPROVED, DECLINED, REVISED }

    /** The cue for a waiting surface. Hints appear only while the pipeline is truly listening. */
    fun cue(kind: PromptSurface.Kind, voice: VoiceUiState, hasChoices: Boolean, twi: Boolean): Cue {
        if (kind == PromptSurface.Kind.SECURE) return Cue.None
        if (kind == PromptSurface.Kind.CONFIRM) {
            val again = when (voice.confirmRetry) {
                ConfirmRetry.UNCLEAR -> if (twi) "Mante no." else "Didn't catch that."
                ConfirmRetry.HOLD -> if (twi) "Yoo, twɛn." else "Take your time."
                ConfirmRetry.NONE -> null
            }
            return when (voice.confirmPhase) {
                ConfirmPhase.LISTENING -> Cue.Listening(listOfNotNull(again,
                    if (twi) "Ka aane anaa daabi, anaa mia." else "Say yes or no, or tap.").joinToString(" "))
                ConfirmPhase.HEARD -> Cue.Heard
                ConfirmPhase.ASKING -> if (again != null) Cue.AskingAgain(again + if (twi) " Mebisa bio." else " Asking again.") else Cue.None
                ConfirmPhase.NONE -> Cue.None
            }
        }
        return when (voice.state) {
            VoiceState.LISTENING -> Cue.Listening(when {
                twi -> if (hasChoices) "Ka wo mmuae, anaa mia baako." else "Ka wo mmuae."
                hasChoices -> "Say your answer, or tap one."
                else -> "Say your answer."
            })
            VoiceState.CAPTURED, VoiceState.TRANSCRIBING -> Cue.Heard
            else -> Cue.None
        }
    }

    /**
     * What the engine decided for the confirmation it had been waiting on, read from its next state: a revision is logged
     * ("User revised…"), a decline ends the run (STOPPED), an approval moves on (acting, thinking, or already finished).
     * Null while it is still deciding — then nothing is highlighted.
     */
    fun decision(status: AgentStatus, log: List<LogEntry>, since: Long): Decision? {
        val after = log.filter { it.timeMs >= since && it.kind == LogEntry.Kind.POLICY }
        return when {
            after.any { it.text.startsWith("User revised") } -> Decision.REVISED
            status == AgentStatus.STOPPED || after.any { it.text.startsWith("Declined") } -> Decision.DECLINED
            status == AgentStatus.ACTING || status == AgentStatus.THINKING || status == AgentStatus.COMPLETED ||
                status == AgentStatus.COMPLETED_UNVERIFIED || status == AgentStatus.FAILED -> Decision.APPROVED
            else -> null
        }
    }

    /** The option the engine took as the answer to its question: its newest user line since [since], matched to the choices
     *  (the longest choice it names wins). Null when the answer named none of them — then nothing is highlighted. */
    fun chosen(choices: List<String>, log: List<LogEntry>, since: Long): String? {
        val said = log.lastOrNull { it.kind == LogEntry.Kind.USER && it.timeMs >= since }?.text?.trim()?.lowercase() ?: return null
        if (said.isEmpty()) return null
        return choices.filter { c -> val l = c.lowercase(); said == l || said.contains(l) || l.contains(said) && said.length >= 3 }
            .maxByOrNull { it.length }
    }
}

/**
 * The listening cue: a small waveform whose bars follow the real microphone level ([level], read per frame only while the
 * pipeline listens), and the hint. Heard: the bars lie still. Asking again: the reason, quietly. Calm motion: no moving bars —
 * the words carry the state (it is never conveyed by animation alone). Not a live region: TalkBack speaking while the user
 * answers would be heard by the recogniser.
 */
@Composable
fun VoiceCueRow(cue: VoiceReflect.Cue, level: () -> Float, twi: Boolean, modifier: Modifier = Modifier) {
    val calm = LocalCalmMotion.current
    Crossfade(targetState = cue, animationSpec = tween(SurfaceMotion.FADE_MS), label = "voice cue", modifier = modifier) { c ->
        when (c) {
            VoiceReflect.Cue.None -> Spacer(Modifier.size(0.dp))
            is VoiceReflect.Cue.Listening -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                Waveform(live = !calm, level = level, modifier = Modifier.size(width = 44.dp, height = 24.dp))
                Spacer(Modifier.width(10.dp))
                Text(c.hint, style = MaterialTheme.typography.bodyMedium, color = GlassColors.TextSecondary)
            }
            VoiceReflect.Cue.Heard -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                Waveform(live = false, level = { 0f }, modifier = Modifier.size(width = 44.dp, height = 24.dp))
                Spacer(Modifier.width(10.dp))
                Text(if (twi) "Mete wo." else "Heard you.", style = MaterialTheme.typography.bodyMedium, color = GlassColors.TextSecondary)
            }
            is VoiceReflect.Cue.AskingAgain -> Text(c.note, style = MaterialTheme.typography.bodyMedium, color = GlassColors.Warm,
                modifier = Modifier.padding(top = 10.dp))
        }
    }
}

/** Five rounded bars. Live: each frame eases toward the real level (no invented motion when the room is quiet). */
@Composable
private fun Waveform(live: Boolean, level: () -> Float, modifier: Modifier = Modifier) {
    var smooth by remember { mutableFloatStateOf(0f) }
    val idleBar = LocalGlass.current.idleBar
    val currentLevel by rememberUpdatedState(level)
    LaunchedEffect(live) {
        if (!live) { smooth = 0f; return@LaunchedEffect }
        val lite = com.tekyerema.ui.glass.glassLite()
        var last = 0L
        while (true) withFrameNanos { now ->
            if (!lite || now - last >= 66_000_000L) {
                last = now
                val v = smooth + (currentLevel().coerceIn(0f, 1f) - smooth) * (if (lite) 0.6f else 0.35f)
                if (!lite || kotlin.math.abs(v - smooth) > 0.02f) smooth = v
            }
        }
    }
    val weights = remember { floatArrayOf(0.45f, 0.75f, 1f, 0.75f, 0.45f) }
    Canvas(modifier.clearAndSetSemantics { }) {
        val n = weights.size
        val gap = size.width * 0.08f
        val bw = (size.width - gap * (n - 1)) / n
        val minH = bw
        for (i in 0 until n) {
            val h = (minH + (size.height - minH) * (smooth * weights[i])).coerceAtMost(size.height)
            val x = i * (bw + gap)
            val color = if (live) Spectrum.Aqua.copy(alpha = 0.55f + 0.45f * smooth) else idleBar
            drawRoundRect(color, topLeft = Offset(x, (size.height - h) / 2f), size = Size(bw, h), cornerRadius = CornerRadius(bw / 2f))
        }
    }
}
