package com.tekyerema.memory

import android.content.Context
import android.os.SystemClock
import com.tekyerema.agent.FastOutcome
import com.tekyerema.android.Relations
import com.tekyerema.settings.Settings

/**
 * The user's voice control of their memory, offline, before the classifier (LocalFastRouter.tryHandle, next to OperatorCommands):
 * "what do you remember about me / about Ama", "what do I usually ask", "forget everything" (read back, then a yes),
 * "forget my contacts history", "forget Ama", "remember that I prefer slow speech / my church is …", "that's wrong, it is …",
 * "stop remembering my contacts". Lists are grouped and capped; "more" goes on. Nothing here sends, pays or calls.
 */
object MemoryCommands {
    private const val WINDOW_MS = 60_000L
    private const val HINT = "Say “forget” and a name, or “forget everything”, to remove any of it."

    @Volatile private var wipeAskedAt = 0L
    @Volatile private var wipeConfirmed = false
    private var more: List<String> = emptyList()
    private var moreFrom = 0
    private var moreAt = 0L
    private var touched: Pair<MemKind, String>? = null
    private var touchedAt = 0L

    /** True once, after the user said yes to "erase it all?": the router then runs its full forget-everything. */
    fun takeWipeConfirmed(): Boolean { val c = wipeConfirmed; wipeConfirmed = false; return c }

    private fun say(name: String, line: String, listen: Boolean = false) =
        FastOutcome(name, line, systemVoice = true, listenAfter = listen)

    private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

    /** The outcome for [raw] when it is a memory command (or the answer to one), else null — carry on as before. */
    fun handle(ctx: Context, raw: String, mono: Long = SystemClock.elapsedRealtime()): FastOutcome? {
        if (com.tekyerema.core.Privacy.secureStep) return null
        UserMemory.ensureLoaded(ctx)   // once; every later call this session is free (this runs off the main thread)
        if (wipeAskedAt != 0L) {
            val within = mono - wipeAskedAt in 0..WINDOW_MS
            wipeAskedAt = 0L
            if (within) when (MemoryParse.answerToWipe(raw)) {
                MemoryParse.Wipe.YES -> { wipeConfirmed = true; return say("MEMORY_FORGET_ALL", "") }
                MemoryParse.Wipe.NO -> return say("MEMORY_FORGET_ALL", "Okay, I kept everything.")
                MemoryParse.Wipe.OTHER -> {}
            }
        }
        val cmd = MemoryParse.classify(raw)
        if (cmd !is MemCmd.More) more = emptyList()
        if (cmd == null) return null
        val now = System.currentTimeMillis()
        return when (cmd) {
            MemCmd.Summary -> summary(ctx, mono, now)
            is MemCmd.About -> about(ctx, cmd.name, mono)
            MemCmd.Usual -> usual(now)
            MemCmd.ForgetAll -> forgetAll(ctx, mono)
            is MemCmd.ForgetKind -> forgetKind(ctx, cmd.kind)
            is MemCmd.ForgetAbout -> forgetAbout(ctx, cmd.name)
            is MemCmd.SetPref -> setPref(ctx, cmd.key, cmd.value, mono, corrected = false)
            is MemCmd.SetFact -> setFact(ctx, cmd.subject, cmd.value, mono, corrected = false)
            is MemCmd.Correct -> correct(ctx, cmd, mono)
            is MemCmd.Switch -> switch(ctx, cmd)
            MemCmd.More -> moreLines(mono)
            is MemCmd.Note -> note(ctx, cmd.text, mono)
        }
    }

    /** A fact kept in the user's own words, under a key made of its words (so saying it again updates, not duplicates). */
    private fun note(ctx: Context, text: String, mono: Long): FastOutcome {
        val fact = text.trim().trimEnd('.', '!')
        val key = MemoryCore.noteKey(fact)
        if (!MemoryCore.check(MemKind.FACT, key, fact))
            return say("MEMORY_SET", "I can't keep that — I don't store numbers, codes, amounts or passwords.")
        UserMemory.update(ctx) { MemoryCore.upsert(it, MemKind.FACT, key, fact, true, System.currentTimeMillis()) }
        touched = MemKind.FACT to key; touchedAt = mono
        return say("MEMORY_SET", "Noted: $fact. Say “that's wrong” if I have it wrong.")
    }

    /** Speak the first page of [lines]; the rest wait behind "more" for a minute. */
    private fun speakLines(name: String, head: String, lines: List<String>, mono: Long): FastOutcome {
        val (text, next) = MemoryCore.page(lines, 0)
        if (next >= lines.size) return say(name, "$head$text $HINT")
        more = lines; moreFrom = next; moreAt = mono
        return say(name, "$head$text And ${lines.size - next} more. Say “more” to hear them.", listen = true)
    }

    private fun moreLines(mono: Long): FastOutcome? {
        if (more.isEmpty() || mono - moreAt !in 0..WINDOW_MS) { more = emptyList(); return null }
        val (text, next) = MemoryCore.page(more, moreFrom)
        val left = more.size - next
        if (left <= 0) { more = emptyList(); return say("MEMORY_MORE", "$text That's all. $HINT") }
        moreFrom = next; moreAt = mono
        return say("MEMORY_MORE", "$text And $left more. Say “more” to hear them.", listen = true)
    }

    private fun summary(ctx: Context, mono: Long, now: Long): FastOutcome {
        Relations.ensureLoaded(ctx)
        val lines = MemoryCore.summaryLines(UserMemory.snapshot(), Relations.all(), Settings(ctx).preferredName, now)
        if (lines.isEmpty()) return say("MEMORY_SUMMARY",
            "I'm not remembering anything about you yet. You can tell me things, like: remember that I prefer slow speech.")
        return speakLines("MEMORY_SUMMARY", "Here's what I remember. ", lines, mono)
    }

    private fun about(ctx: Context, name: String, mono: Long): FastOutcome? {
        Relations.ensureLoaded(ctx)
        val s = UserMemory.snapshot()
        val lines = MemoryCore.aboutLines(s, Relations.all(), name)
        if (lines.isEmpty()) return null   // "what do you know about Ghana" is a question for the agent, not the memory
        val own = MemoryCore.entriesAbout(s, name)
        if (own.size == 1 && lines.size == 1) { touched = own[0].kind to own[0].key; touchedAt = mono }
        return speakLines("MEMORY_ABOUT", "About $name: ", lines, mono)
    }

    private fun usual(now: Long): FastOutcome {
        val tasks = MemoryCore.topTasks(UserMemory.snapshot(), now, 3)
        if (tasks.isEmpty()) return say("MEMORY_USUAL", "I haven't noticed anything you ask for again and again yet.")
        val list = tasks.joinToString(", ") { "“${it.value}”" }
        val first = tasks[0]
        // A shortcut is only ever OFFERED: a yes hands the user's own words to the agent, which runs its usual checks.
        if (first.count >= 3) {
            com.tekyerema.agent.NextStep.offer(com.tekyerema.agent.NextStep.Offer(first.value, "Do “${first.value}”?", null,
                System.currentTimeMillis(), "memory_task"))
            return say("MEMORY_USUAL", "You often ask: $list. Want me to do “${first.value}” now? Say yes or no.", listen = true)
        }
        return say("MEMORY_USUAL", "You often ask: $list.")
    }

    private fun forgetAll(ctx: Context, mono: Long): FastOutcome {
        Relations.ensureLoaded(ctx)
        val n = UserMemory.snapshot().entries.size + Relations.all().size
        wipeAskedAt = mono
        return say("MEMORY_FORGET_ALL", "This erases ${plural(n, "thing")} I learned about you, our conversation and the messages I was keeping. " +
            "Your settings, routines and emergency contact stay. Say “yes” to erase it, or “no” to keep it.", listen = true)
    }

    private fun forgetKind(ctx: Context, kind: MemKind): FastOutcome {
        var removed = 0
        UserMemory.update(ctx) { s -> MemoryCore.removeKind(s, kind).let { removed = it.second; it.first } }
        touched = null
        if (removed == 0) return say("MEMORY_FORGET", "I wasn't keeping any ${kind.spoken}.")
        val off = if (kind == MemKind.CONTACT || kind == MemKind.TASK) " I'll start learning them again unless you say “stop remembering my ${if (kind == MemKind.CONTACT) "contacts" else "tasks"}”." else ""
        return say("MEMORY_FORGET", "Okay, I've forgotten the ${kind.spoken} I was keeping.$off")
    }

    private fun forgetAbout(ctx: Context, name: String): FastOutcome? {
        Relations.ensureLoaded(ctx)
        var removed = 0
        UserMemory.update(ctx) { s -> MemoryCore.removeAbout(s, name).let { removed = it.second; it.first } }
        val rels = Relations.all().filter { (_, v) -> MemoryCore.mentions(v, name) }.keys
        rels.forEach { Relations.remove(it) }
        val total = removed + rels.size
        if (total == 0) return null   // nothing of ours: the router's "forget <person>" (their messages) goes on as before
        val msgs = try { com.tekyerema.notifications.MessageIndex.forget(name) } catch (_: Throwable) { 0 }
        try { com.tekyerema.agent.References.forget(name) } catch (_: Throwable) {}
        touched = null
        return say("MEMORY_FORGET", "Okay, I've forgotten ${plural(total, "thing")} about $name" +
            (if (msgs > 0) " and ${plural(msgs, "message")} from them" else "") + ". Your contacts haven't changed.")
    }

    private fun setPref(ctx: Context, key: String, value: String, mono: Long, corrected: Boolean): FastOutcome {
        if (!MemoryCore.check(MemKind.PREF, key, value)) return say("MEMORY_SET", "I can't keep that.")
        UserMemory.update(ctx) { MemoryCore.upsert(it, MemKind.PREF, key, value, true, System.currentTimeMillis()) }
        touched = MemKind.PREF to key; touchedAt = mono
        // A preference for everything is applied now, once, and never asked again (the Settings it changes persist).
        val applied = try {
            val st = Settings(ctx)
            when (key) {
                "rate" -> { st.speechRate = when (value) { "slow" -> 0; "fast" -> 2; else -> 1 }; true }
                "language" -> { st.languageMode = if (value == "twi") Settings.LANG_MODE_TWI else Settings.LANG_MODE_ENGLISH; true }
                "verbosity" -> { st.verbosity = value; true }
                else -> false
            }
        } catch (_: Throwable) { false }
        val said = MemoryCore.spokenPref(key, value)
        return say("MEMORY_SET", (if (corrected) "Okay, I've corrected it: $said." else "Okay. I'll remember that $said.") +
            if (applied) " I've set that up now." else "")
    }

    private fun setFact(ctx: Context, subject: String, value: String, mono: Long, corrected: Boolean): FastOutcome {
        if (!MemoryCore.check(MemKind.FACT, subject, value))
            return say("MEMORY_SET", "I can't keep that — I don't store numbers, codes, amounts or passwords.")
        UserMemory.update(ctx) { MemoryCore.upsert(it, MemKind.FACT, subject, value, true, System.currentTimeMillis()) }
        touched = MemKind.FACT to subject.lowercase(); touchedAt = mono
        val said = MemoryCore.spokenFact(subject.lowercase(), value)
        return say("MEMORY_SET", if (corrected) "Okay, I've corrected it: $said." else "Okay. I'll remember that $said. Say “that's wrong” if I have it wrong.")
    }

    private val FORGET_IT = Regex("""(?i)^(?:forget|delete|remove|erase) (?:it|that)$""")

    private fun correct(ctx: Context, cmd: MemCmd.Correct, mono: Long): FastOutcome? {
        val t = touched
        if (t == null || mono - touchedAt !in 0..WINDOW_MS) return null   // "that's wrong" about something else: the agent's
        val (kind, key) = t
        if (FORGET_IT.matches(cmd.text.trim())) {
            UserMemory.update(ctx) { MemoryCore.removeEntry(it, kind, key) }
            touched = null
            return say("MEMORY_FORGET", "Okay, I've forgotten it.")
        }
        MemoryParse.parsePref(cmd.text)?.let { p ->
            if (p.key != key) UserMemory.update(ctx) { MemoryCore.removeEntry(it, kind, key) }
            return setPref(ctx, p.key, p.value, mono, corrected = true)
        }
        MemoryParse.parseFact(cmd.text)?.let { f ->
            if (f.subject != key) UserMemory.update(ctx) { MemoryCore.removeEntry(it, kind, key) }
            return setFact(ctx, f.subject, f.value, mono, corrected = true)
        }
        if (kind == MemKind.FACT) return setFact(ctx, key, cmd.text, mono, corrected = true)
        return say("MEMORY_SET", "Tell me what it should be, like: remember that I prefer slow speech.")
    }

    private fun switch(ctx: Context, cmd: MemCmd.Switch): FastOutcome {
        UserMemory.update(ctx) { MemoryCore.withSwitch(it, cmd.kind, cmd.on) }
        val what = cmd.kind?.spoken ?: "anything new about you"
        return say("MEMORY_SWITCH", if (cmd.on) "Okay, I'll keep track of $what again."
            else "Okay, I've stopped keeping track of $what. What I already kept stays until you say “forget”.")
    }
}
