package com.tekyerema.memory

import android.content.Context
import android.util.Log
import com.tekyerema.accessibility.AccessibilityController
import com.tekyerema.accessibility.UiSnapshot
import com.tekyerema.agent.FastOutcome

/**
 * The user's voice control of button names (label memory, MASTER_STATUS #38), offline, before the memory commands in
 * LocalFastRouter.tryHandle: "that button is Search" (the unnamed button the agent pressed last), "the third button is Call"
 * (counted among the unnamed buttons on screen, top to bottom, left to right), "the button next to the message box is Send",
 * "what button names do you know", "forget the button names for WhatsApp", "forget that button name". Nothing here taps,
 * sends, pays or calls: it only decides what a control is CALLED. Words that are not a plain name, or a control that cannot
 * be told apart reliably, are refused out loud — nothing is guessed.
 */
object LabelCommands {
    private const val TAG = "Tekyerema"
    private const val HOW = "Say, for example: the second button is Search."

    private fun say(name: String, line: String) = FastOutcome(name, line, systemVoice = true)

    /** The outcome for [raw] when it is about button names, else null — carry on as before. */
    suspend fun handle(ctx: Context, raw: String, controller: AccessibilityController?): FastOutcome? {
        if (com.tekyerema.core.Privacy.secureStep) return null
        val cmd = LabelParse.classify(raw) ?: return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            LabelStore.ensureLoaded(ctx)
            when (cmd) {
                is LabelCmd.Name -> name(ctx, cmd, controller)
                is LabelCmd.ListNames -> list(cmd.app)
                is LabelCmd.Forget -> forget(ctx, cmd, controller)
                LabelCmd.ForgetLast ->
                    if (LabelStore.forgetLast(ctx)) say("LABEL_FORGET", "Okay, I've forgotten that name.")
                    else say("LABEL_FORGET", "I haven't named a button just now.")
            }
        }
    }

    private suspend fun look(c: AccessibilityController?): UiSnapshot? =
        try { c?.takeIf { it.ready }?.buildSnapshot() } catch (_: Throwable) { null }

    private suspend fun name(ctx: Context, cmd: LabelCmd.Name, c: AccessibilityController?): FastOutcome? {
        val name = LabelCore.cleanName(cmd.name)
        // "the first button is not working" is a remark for the agent, not a name: only an order gets an explanation.
        if (name == null) return if (cmd.instruction) say("LABEL_NAME", "I can't use that as a button name. Use one to three plain words, like Search or Send.") else null
        if (!LabelStore.enabled()) return say("LABEL_NAME", "Remembering button names is switched off.")
        val snap = look(c)
        val sig: LabelSig
        val app: String?
        when (val t = cmd.target) {
            LabelTarget.Recent -> {
                val tap = LabelStore.recentTap(snap?.appPackage)
                    ?: return if (cmd.instruction) say("LABEL_NAME", "I haven't pressed an unnamed button just now. Tell me which one. $HOW") else null
                sig = tap.sig; app = tap.appName ?: snap?.appName
            }
            else -> {
                if (snap == null) return say("LABEL_NAME", "I can't see the screen right now.")
                if (!LabelSignature.usable(snap)) return say("LABEL_NAME", "I don't keep button names on this screen.")
                val nameless = LabelSignature.nameless(snap)
                val el = when (t) {
                    is LabelTarget.Nth -> {
                        if (nameless.isEmpty()) return say("LABEL_NAME", "I don't see any unnamed buttons on this screen.")
                        val i = if (t.fromEnd) nameless.size - t.n else t.n - 1
                        nameless.getOrNull(i) ?: return say("LABEL_NAME", "There ${if (nameless.size == 1) "is only 1 unnamed button" else "are only ${nameless.size} unnamed buttons"} on this screen.")
                    }
                    LabelTarget.Only -> {
                        if (nameless.size != 1) return say("LABEL_NAME",
                            if (nameless.isEmpty()) "I don't see any unnamed buttons on this screen."
                            else "There are ${nameless.size} unnamed buttons on this screen, so say which one. $HOW")
                        nameless[0]
                    }
                    is LabelTarget.Beside ->
                        LabelSignature.beside(snap, t.anchor, t.side) ?: return say("LABEL_NAME", "I can't find one unnamed button next to that. $HOW")
                    LabelTarget.Recent -> return null
                }
                sig = LabelStore.sigFor(snap, el)
                    ?: return say("LABEL_NAME", "I can't tell that button apart from the others reliably on this screen, so I won't name it.")
                app = snap.appName
            }
        }
        return when (LabelStore.teach(ctx, sig, app, name, told = true)) {
            is LabelStore.Taught.Saved -> {
                Log.i(TAG, "[LABELS] taught by voice")   // never the name or the app: they are the user's content
                say("LABEL_NAME", "Okay. I'll call that button $name${app?.takeIf { it != "the home screen" }?.let { " in $it" }.orEmpty()}. " +
                    "Say “forget that button name” if that's wrong.")
            }
            LabelStore.Taught.BadName -> say("LABEL_NAME", "I can't use that as a button name. Use one to three plain words, like Search or Send.")
            LabelStore.Taught.NotSure -> say("LABEL_NAME", "I can't keep a button name right now.")
            LabelStore.Taught.Off -> say("LABEL_NAME", "Remembering button names is switched off.")
        }
    }

    private fun list(app: String?): FastOutcome {
        val lines = LabelCore.spokenLines(LabelStore.snapshot(), app)
        if (lines.isEmpty()) return say("LABEL_LIST",
            if (app == null) "I haven't learned any button names yet. $HOW" else "I haven't learned any button names for $app.")
        val (text, next) = MemoryCore.page(lines, 0, 4)
        val more = lines.size - next
        return say("LABEL_LIST", "I know these button names: $text" + if (more > 0) " And $more more apps." else "")
    }

    private suspend fun forget(ctx: Context, cmd: LabelCmd.Forget, c: AccessibilityController?): FastOutcome {
        val target: String? = when {
            cmd.all -> null
            cmd.app != null -> cmd.app
            else -> look(c)?.appName ?: return say("LABEL_FORGET", "Which app? Say, forget the button names for WhatsApp.")
        }
        val n = LabelStore.forget(ctx, target)
        return when {
            n == 0 -> say("LABEL_FORGET", if (target == null) "I wasn't keeping any button names." else "I wasn't keeping any button names for $target.")
            target == null -> say("LABEL_FORGET", "Okay, I've forgotten all the button names I was keeping.")
            else -> say("LABEL_FORGET", "Okay, I've forgotten the button names I kept for $target.")
        }
    }
}
