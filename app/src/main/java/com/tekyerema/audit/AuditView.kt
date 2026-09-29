package com.tekyerema.audit

/**
 * WS-C — the activity log arranged for reading (pure, unit-tested; the Settings › Control › Activity log page draws it):
 * one card per request, newest first, and inside it one line per step that joins the step's linked entries — what was
 * proposed, what the policy said, who decided, the user's answer to confirmation #N, the outcome and the check.
 */
object AuditView {
    data class StepLine(
        val step: Int,
        val words: String,
        val tool: String,
        val tier: String,
        /** "allowed", "asked you first", "blocked". */
        val policy: String,
        val reason: String,
        val decidedBy: String,
        val provenance: String,
        /** "You said yes to question #3", "You said no to question #3", "Covered by an earlier yes". */
        val answer: String?,
        /** "done", "failed", "blocked", "not run" or "" (still pending when the log was read). */
        val outcome: String,
        val verified: Boolean?
    ) {
        /** One sentence for TalkBack and the card. */
        fun sentence(): String = buildList {
            add(words.replaceFirstChar { it.uppercase() })
            if (policy.isNotEmpty()) add(policy)
            answer?.let { add(it) }
            if (outcome.isNotEmpty()) add(outcome)
            when (verified) { true -> add("checked on screen"); false -> add("not confirmed on screen"); null -> {} }
        }.joinToString(" · ")
    }

    data class TaskCard(
        val task: Long,
        val time: Long,
        val source: String,
        /** The request in the user's words (agent tasks), or the one line of a fast command / setting change. */
        val title: String,
        val brain: String,
        /** "Finished and checked", "Stopped"… or null when there is no end entry (a fast command, or still running). */
        val status: String?,
        val steps: List<StepLine>,
        /** Entries that aren't steps (fast-path actions, setting changes, the "cleared" marker). */
        val notes: List<String>
    )

    fun sourceLabel(source: String): String = when {
        source == "agent" -> "You asked"
        source == "fast" -> "Quick command"
        source == "trigger" -> "Ran by itself"
        source == "user" -> "Your change"
        source == "test" -> "Test"
        source.startsWith("remote") -> "Your computer asked"
        else -> source.replaceFirstChar { it.uppercase() }
    }

    fun statusLabel(status: String): String = when (status) {
        "COMPLETED" -> "Finished and checked"
        "COMPLETED_UNVERIFIED" -> "Finished, not confirmed"
        "CANNOT_DO" -> "Couldn't do it"
        "FAILED" -> "Failed"
        "STOPPED" -> "Stopped"
        else -> status.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /** Newest request first. */
    fun group(entries: List<AuditEntry>): List<TaskCard> {
        val byTask = LinkedHashMap<Long, MutableList<AuditEntry>>()
        for (e in entries) byTask.getOrPut(e.task) { ArrayList() }.add(e)
        return byTask.map { (task, list) -> card(task, list) }.sortedByDescending { it.time }
    }

    private fun card(task: Long, list: List<AuditEntry>): TaskCard {
        val start = list.firstOrNull { it.kind == AuditLog.TASK_START }
        val end = list.lastOrNull { it.kind == AuditLog.TASK_END }
        val stepEntries = list.filter { it.step != 0 }
        val steps = stepEntries.groupBy { it.step }.toSortedMap().map { (n, es) -> stepLine(n, es) }
        val notes = list.filter { it.step == 0 && it.kind != AuditLog.TASK_START && it.kind != AuditLog.TASK_END }
            .map { it.text }.filter { it.isNotBlank() }
        val title = start?.text?.takeIf { it.isNotBlank() } ?: notes.firstOrNull() ?: steps.firstOrNull()?.words ?: "Activity"
        return TaskCard(task, list.first().t, list.first().source, title, start?.decidedBy.orEmpty(),
            end?.outcome?.let { statusLabel(it) }, steps, if (start == null) notes.drop(1) else notes)
    }

    private fun stepLine(n: Int, es: List<AuditEntry>): StepLine {
        val proposed = es.firstOrNull { it.kind == AuditLog.PROPOSED } ?: es.first()
        val approved = es.lastOrNull { it.kind == AuditLog.APPROVED }
        val declined = es.lastOrNull { it.kind == AuditLog.DECLINED }
        val answer = when {
            declined != null -> (if (declined.outcome == "revised") "You changed it" else "You said no") +
                (if (declined.confirmSeq > 0) " to question #${declined.confirmSeq}" else "")
            approved != null -> if (approved.confirmSeq > 0) "You said yes to question #${approved.confirmSeq}" else "Covered by an earlier yes"
            else -> null
        }
        val blocked = es.lastOrNull { it.kind == AuditLog.BLOCKED }
        val outcome = when {
            es.any { it.kind == AuditLog.DONE } -> "done"
            es.any { it.kind == AuditLog.FAILED } -> "failed"
            blocked != null -> "blocked"
            es.any { it.kind == AuditLog.NOT_RUN } -> "not run"
            declined != null -> "not done"
            else -> ""
        }
        val verified = es.lastOrNull { it.kind == AuditLog.VERIFIED || it.kind == AuditLog.UNVERIFIED }?.verified
        val policy = AuditHooks.outcomeWords(blocked?.policy ?: proposed.policy)
        val reason = (blocked ?: es.lastOrNull { it.kind == AuditLog.NOT_RUN } ?: proposed).reason
        return StepLine(n, proposed.text, proposed.tool, proposed.tier, policy, reason, proposed.decidedBy, proposed.provenance,
            answer, outcome, verified)
    }

    /** The chain check in one line for the top of the page. */
    fun chainLine(c: AuditChain.Check): String = when {
        c.intact && c.checked == 0 -> "The log is empty."
        c.intact -> "Log intact — all ${c.checked} entries check out; none was changed, moved or taken out from between others."
        !c.ok -> "Warning: ${c.problem ?: "the log doesn't check out"}. Something changed this log outside Tɛkyerɛma."
        else -> "Warning: ${c.unreadable} stored ${if (c.unreadable == 1) "entry" else "entries"} couldn't be read back; the rest check out."
    }
}
