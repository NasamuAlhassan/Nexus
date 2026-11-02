package com.tekyerema.teach

import com.tekyerema.voice.Confirmation

/**
 * Teach mode — the conversation, as a small state machine. Pure given its collaborators → unit-tested.
 *
 *   IDLE ──"watch me"──▶ RECORDING ──"stop, call it X"──▶ (ASK_TEXT…) ──▶ CONFIRM_SAVE ──yes──▶ saved
 *                              │                              ▲
 *                              └──"stop"──▶ NEED_NAME ──name──┘        (anything private ends RECORDING by itself)
 *
 * What it promises:
 *  - nothing typed is kept unless the user says, for that box, that it is not private ([askText]); a number-only entry
 *    (a PIN-like thing) is not even offered;
 *  - the whole skill is read back in plain words and saved only on a yes;
 *  - a skill's name can't be a command the phone already understands ([validName]) or a routine's name.
 */
class TeachSession(
    private val store: TeachStore,
    private val recorder: TeachRecorder = TeachRecorder(),
    /** Not something the phone already does ("what time is it", "lock the phone"). */
    private val validName: (String) -> Boolean = { com.tekyerema.agent.Routines.validName(it) },
    /** The name of a routine (H-02) — taught skills and routines share the spoken names. */
    private val routineExists: (String) -> Boolean = { com.tekyerema.agent.Routines.get(it) != null },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val answerOf: (String) -> Confirmation.Answer = { Confirmation.interpret(it) }
) {
    enum class Phase { IDLE, RECORDING, NEED_NAME, ASK_TEXT, CONFIRM_SAVE }

    /** [run] = the skill to replay (the caller does it and speaks the result). */
    data class Reply(val spoken: String, val listenAfter: Boolean = false, val ok: Boolean = true, val run: String? = null)

    @Volatile var phase = Phase.IDLE; private set
    val recording: Boolean get() = recorder.recording

    private var drafts: List<TeachRecorder.Draft> = emptyList()
    private var name: String? = null
    private var offered: String? = null
    private var privateEnd = false
    private var textQueue = ArrayDeque<Int>()
    private var tries = 0
    private var touched = 0L

    // ---------------------------------------------------------------- events from the accessibility service

    /** An event while recording. Returns a line to announce when the recorder stopped by itself (private screen…). */
    @Synchronized fun onEvent(e: RawEvent, secureNow: Boolean): String? {
        if (phase != Phase.RECORDING) return null
        return when (val r = recorder.onEvent(e, secureNow)) {
            is TeachRecorder.Result.Stopped -> stoppedBySystem(r.reason)
            else -> null
        }
    }

    /** The adapter looked at the whole screen a moment after [sinceMs] and it is a credential screen. */
    @Synchronized fun onPrivateScreen(sinceMs: Long): String? {
        if (phase != Phase.RECORDING || !recorder.recording) return null
        recorder.stopForPrivacy(StopReason.PRIVATE, sinceMs)
        return stoppedBySystem(StopReason.PRIVATE)
    }

    private fun stoppedBySystem(reason: StopReason): String {
        drafts = recorder.stop()
        privateEnd = reason == StopReason.PRIVATE || reason == StopReason.PHONE_DIALOG
        touched = now()
        if (drafts.isEmpty()) { phase = Phase.IDLE; return reason.spoken + " There was nothing to keep." }
        phase = Phase.NEED_NAME; name = null; offered = null
        val n = drafts.size
        return reason.spoken + " I kept $n step${if (n == 1) "" else "s"}. Say “call it” and a name to save them, or say cancel."
    }

    // ---------------------------------------------------------------- what the user says

    /** @return the reply when [text] is teach mode's, or null to let the phone handle it as usual. */
    @Synchronized fun handle(text: String): Reply? {
        expire()
        return when (phase) {
            Phase.IDLE -> idle(text)
            Phase.RECORDING -> duringRecording(text)
            Phase.NEED_NAME -> needName(text)
            Phase.ASK_TEXT -> askText(text)
            Phase.CONFIRM_SAVE -> confirmSave(text)
        }
    }

    /** A half-finished conversation is dropped after a while; a recording that stopped by itself moves on. */
    private fun expire() {
        if (phase == Phase.RECORDING && !recorder.recording) { stoppedBySystem(recorder.stopReason ?: StopReason.TIMEOUT) }
        if (phase in listOf(Phase.NEED_NAME, Phase.ASK_TEXT, Phase.CONFIRM_SAVE) && now() - touched > 5 * 60_000L) reset()
    }

    private fun reset() {
        recorder.discard(); drafts = emptyList(); name = null; offered = null; privateEnd = false
        textQueue = ArrayDeque(); tries = 0; phase = Phase.IDLE
    }

    private fun idle(text: String): Reply? {
        TeachParse.parseStart(text)?.let { task ->
            recorder.start(now()); touched = now(); phase = Phase.RECORDING; privateEnd = false
            val suggested = TeachParse.cleanName(task).takeIf { it.isNotEmpty() && validName(it) && !routineExists(it) }
            offered = suggested
            return Reply("Okay, I'm watching. Do the steps now, the way you normally do. I won't keep anything private, like a PIN or a password. " +
                "When you're finished, press volume down twice and say “stop, call it” and a name.")
        }
        val names = store.names()
        TeachParse.matchRun(text, names)?.let { return Reply("", run = it) }
        if (TeachParse.isList(text)) return Reply(
            if (names.isEmpty()) "You haven't taught me anything yet. Say “watch me”, then show me the steps."
            else "You have taught me ${names.size} thing${if (names.size == 1) "" else "s"}: ${names.take(8).joinToString(", ") { "“$it”" }}.")
        TeachParse.parseDelete(text, names)?.let { n ->
            return Reply(if (store.remove(n)) "Okay, I've forgotten “$n”." else "I don't have “$n”.")
        }
        TeachParse.parseSteps(text, names)?.let { n ->
            val s = store.get(n) ?: return null
            return Reply("“$n”: ${StepWords.spoken(s.steps)}" + if (s.endsAtPrivate) " It stops before the private part, which you do yourself." else "")
        }
        return null
    }

    private fun duringRecording(text: String): Reply? {
        if (TeachParse.isCancel(text)) { reset(); return Reply("Okay, I've thrown that away.") }
        when (val st = TeachParse.parseStop(text)) {
            TeachParse.Stop.Plain -> return finish(null)
            is TeachParse.Stop.Named -> return finish(st.name)
            null -> {}
        }
        TeachParse.parseName(text, bareOk = false)?.let { return finish(it) }
        return null   // anything else is an ordinary request; the phone handles it (what the agent itself taps is not recorded)
    }

    private fun finish(named: String?): Reply {
        drafts = recorder.stop()
        privateEnd = recorder.stopReason == StopReason.PRIVATE || recorder.stopReason == StopReason.PHONE_DIALOG
        touched = now()
        if (drafts.isEmpty()) { reset(); return Reply("I didn't see any steps, so there is nothing to save. Say “watch me” to try again.", ok = false) }
        phase = Phase.NEED_NAME
        if (!named.isNullOrBlank()) return setName(named)
        val n = drafts.size
        val ask = offered?.let { "I saw $n step${if (n == 1) "" else "s"}. Shall I call it “$it”? Say yes, or tell me another name." }
            ?: "I saw $n step${if (n == 1) "" else "s"}. What shall I call it?"
        return Reply(ask, listenAfter = true)
    }

    private fun needName(text: String): Reply? {
        touched = now()
        if (TeachParse.isCancel(text)) { reset(); return Reply("Okay, I haven't saved anything.") }
        // "stop" again (the recording had already ended by itself) is not a name.
        if (TeachParse.parseStop(text) == TeachParse.Stop.Plain) return Reply("What shall I call it?", listenAfter = true)
        val o = offered
        if (o != null && answerOf(text) == Confirmation.Answer.YES) return setName(o)
        val n = TeachParse.parseName(text, bareOk = true) ?: return Reply("What shall I call it?", listenAfter = true)
        return setName(n)
    }

    private fun setName(raw: String): Reply {
        val n = TeachParse.cleanName(raw)
        if (n.isEmpty()) return Reply("What shall I call it?", listenAfter = true)
        if (!validName(n)) return Reply("I can't use “$n” as a name, because I already know it as something else. Say another name.", listenAfter = true)
        if (routineExists(n)) return Reply("You already have a routine called “$n”. Say another name.", listenAfter = true)
        name = n
        textQueue = ArrayDeque(drafts.indices.filter { i -> drafts[i].step.kind == StepKind.TYPE && !drafts[i].typed.isNullOrEmpty() })
        // A number-only entry looks like a PIN or a code: it is never offered for keeping.
        textQueue.toList().forEach { i -> if (looksPrivate(drafts[i].typed.orEmpty())) { drafts[i].typed = null; textQueue.remove(i) } }
        return nextQuestion()
    }

    private fun nextQuestion(): Reply {
        tries = 0
        val i = textQueue.firstOrNull() ?: return readBack()
        phase = Phase.ASK_TEXT
        val s = drafts[i].step
        return Reply("You typed some words into the ${StepWords.boxName(s)} box. Should I remember them? Say yes only if they are not private. " +
            "If you say no, I'll ask you what to type each time.", listenAfter = true)
    }

    private fun askText(text: String): Reply? {
        touched = now()
        if (TeachParse.isCancel(text)) { reset(); return Reply("Okay, I haven't saved anything.") }
        val i = textQueue.firstOrNull() ?: return readBack()
        val answer = answerOf(text)
        val d = drafts[i]
        if (answer == Confirmation.Answer.YES) {
            d.step = d.step.copy(text = d.typed, textLength = d.typed?.length ?: d.step.textLength); d.typed = null; textQueue.removeFirst()
        } else if (answer == Confirmation.Answer.NO) {
            d.typed = null; textQueue.removeFirst()
        } else if (answer == Confirmation.Answer.HOLD) {
            return Reply("Take your time. Should I remember the words you typed? Yes or no.", listenAfter = true)
        } else if (++tries >= 3) {
            d.typed = null; textQueue.removeFirst()   // no clear yes: it is not kept
        } else {
            return Reply("Please say yes or no. Should I remember the words you typed?", listenAfter = true)
        }
        return nextQuestion()
    }

    private fun readBack(): Reply {
        phase = Phase.CONFIRM_SAVE; tries = 0; touched = now()
        val steps = drafts.map { it.step }
        val extras = buildString {
            if (steps.any { it.sensitive }) append(" The steps that send, pay or save will always ask you first.")
            if (steps.any { it.unnamed }) append(" One button had no name, so I'll hand over to my usual way there.")
            if (privateEnd) append(" It stops before the private part, which you do yourself.")
        }
        return Reply("Here is what I learned for “$name”: ${StepWords.spoken(steps)}$extras Shall I save it?", listenAfter = true)
    }

    private fun confirmSave(text: String): Reply? {
        touched = now()
        if (TeachParse.isCancel(text)) { reset(); return Reply("Okay, I haven't saved it.") }
        if (Confirmation.asksToRepeat(text)) return readBack()
        val answer = answerOf(text)
        if (answer == Confirmation.Answer.YES) {
            val n = name
            if (n == null) { reset(); return Reply("Sorry, I lost the name. Please start again.", ok = false) }
            val replaced = store.get(n) != null
            store.put(TeachSkill(n, drafts.map { it.step }, now(), privateEnd))
            reset()
            return Reply("Saved. Say “$n” any time and I'll do it.${if (replaced) " That replaces the old one." else ""}")
        }
        if (answer == Confirmation.Answer.NO) { reset(); return Reply("Okay, I haven't saved it.") }
        if (answer == Confirmation.Answer.HOLD) return Reply("Take your time. Shall I save it? Yes or no.", listenAfter = true)
        if (++tries >= 3) { reset(); return Reply("I didn't hear a yes, so I haven't saved it.") }
        return Reply("Please say yes or no. Shall I save “$name”?", listenAfter = true)
    }

    companion object {
        /** An entry that looks like a PIN / code / phone number (digits only, 4+): never offered for keeping. */
        fun looksPrivate(text: String): Boolean = text.trim().let { it.length >= 4 && it.all { c -> c.isDigit() || c == ' ' || c == '-' } && it.any(Char::isDigit) }
    }
}
