package com.tekyerema.learning

import android.content.Context
import android.os.SystemClock
import com.tekyerema.agent.FastOutcome
import com.tekyerema.settings.Settings

/**
 * The user's voice control of learning from mistakes, offline, before the classifier (LocalFastRouter.tryHandle, right after the
 * memory commands): "learn from your mistakes" / "stop learning from your mistakes", "what mistakes have you kept", "forget my
 * mistakes" (all of it, at once), and "share my mistakes for training" — which first says what the file holds and waits for a yes.
 * Everything else the user says is shown to [FailureLearning.noteUtterance] (a "no, that's wrong" about the last task) and goes on
 * to the agent unchanged. Nothing here sends, pays or calls, and nothing is uploaded: the share sheet is the user's own choice.
 */
object FailureCommands {
    private const val WINDOW_MS = 60_000L

    @Volatile private var shareAskedAt = 0L

    private fun say(name: String, line: String, listen: Boolean = false) =
        FastOutcome(name, line, systemVoice = true, listenAfter = listen)

    private fun notes(n: Int) = if (n == 1) "1 task" else "$n tasks"

    /** The outcome for [raw] when it is a mistakes command (or the answer to the share question), else null — carry on as before. */
    fun handle(ctx: Context, raw: String, mono: Long = SystemClock.elapsedRealtime()): FastOutcome? {
        if (com.tekyerema.core.Privacy.secureStep) return null
        if (shareAskedAt != 0L) {
            val within = mono - shareAskedAt in 0..WINDOW_MS
            shareAskedAt = 0L
            // Only a SHORT reply is an answer; longer words are a new request, handled as said.
            if (within && raw.trim().split(Regex("\\s+")).size <= 4) when (com.tekyerema.voice.Confirmation.interpret(raw)) {
                com.tekyerema.voice.Confirmation.Answer.YES -> return share(ctx)
                com.tekyerema.voice.Confirmation.Answer.NO -> return say("MISTAKES_SHARE", "Okay, I won't share anything.")
                else -> {}
            }
        }
        val cmd = FailureParse.classify(raw)
        if (cmd == null) { FailureLearning.noteUtterance(ctx, raw); return null }
        FailureStore.ensureLoaded(ctx)   // once (a Keystore decrypt); this runs off the main thread
        return when (cmd) {
            FailureParse.Cmd.ON -> {
                Settings(ctx).learnFromMistakes = true
                say("MISTAKES_ON", "Okay, I'll learn from my mistakes. I keep a private note on this phone of tasks that go wrong, " +
                    "without names, numbers, amounts or messages. Nothing leaves the phone unless you say “share my mistakes for training”.")
            }
            FailureParse.Cmd.OFF -> {
                Settings(ctx).learnFromMistakes = false
                say("MISTAKES_OFF", "Okay, I've stopped. What I already noted stays until you say “forget my mistakes”.")
            }
            FailureParse.Cmd.FORGET -> {
                val n = FailureLearning.forgetAll(ctx)
                say("MISTAKES_FORGET", if (n == 0) "I wasn't keeping any notes about my mistakes." else "Okay, I've deleted my notes on ${notes(n)} that went wrong.")
            }
            FailureParse.Cmd.STATUS -> {
                val n = FailureStore.count()
                val on = Settings(ctx).learnFromMistakes
                say("MISTAKES_STATUS", (if (n == 0) "I haven't noted any mistakes." else "I've noted ${notes(n)} that went wrong.") +
                    if (on) " Learning from mistakes is on." else " Learning from mistakes is off. Say “learn from your mistakes” to turn it on.")
            }
            FailureParse.Cmd.SHARE -> {
                val all = FailureStore.all()
                if (all.isEmpty()) say("MISTAKES_SHARE", "I haven't noted any mistakes yet, so there is nothing to share.")
                else {
                    shareAskedAt = mono
                    say("MISTAKES_SHARE", FailureCore.describeExport(all) + " Shall I open the share sheet? Say yes or no.", listen = true)
                }
            }
        }
    }

    private fun share(ctx: Context): FastOutcome =
        if (FailureExport.share(ctx)) say("MISTAKES_SHARE", "Okay. Choose where to send the file.")
        else say("MISTAKES_SHARE", "Sorry, I couldn't prepare the file.")
}
