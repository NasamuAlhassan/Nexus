package com.tekyerema.live

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * Live activities (owner, 15 Oct 2026: "like the Dynamic Island of iPhones") — things that keep going after the request
 * that started them: a timer counting down, walking directions, music, a call. Each is shown by the island at the top of
 * the screen (ui/island): a small capsule while it runs, opening into a card when it needs the user (a timer that is done,
 * the next turn close by). Pure state plus action handlers; the features own their activity and update it.
 */
enum class LiveKind { TIMER, NAVIGATION, MUSIC, CALL, TASK }

/** What the island draws in front of the words (ui/island maps it to a shape). */
enum class LiveGlyph { TIMER, ARROW_LEFT, ARROW_RIGHT, ARROW_STRAIGHT, ARROW_SLIGHT_LEFT, ARROW_SLIGHT_RIGHT, ARROW_UTURN, ARRIVE, WALK, MUSIC, PAUSED, CALL, BELL }

data class LiveAction(val id: String, val label: String, val primary: Boolean = false)

data class LiveActivity(
    val id: String,
    val kind: LiveKind,
    /** The big line of the open card: "Timer", "Kofi Demo", "Accra Mall". */
    val title: String,
    /** The capsule's words: "4:32", "120 m", "Sarkodie". Short. */
    val compact: String,
    /** One more line in the open card: "Turn left onto Oxford Street", "Rap · Sarkodie". */
    val detail: String? = null,
    /** 0..1 where it makes sense (a timer's elapsed share, how far along the walk). */
    val progress: Float? = null,
    val glyph: LiveGlyph = LiveGlyph.BELL,
    /** It needs the user now — a timer that is done, an arrival, a turn a few metres ahead: the island opens and glows. */
    val urgent: Boolean = false,
    val actions: List<LiveAction> = emptyList(),
    /** What TalkBack reads for the capsule. */
    val spoken: String = "$title, $compact",
    val priority: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)

object LiveActivities {
    private val _state = MutableStateFlow<List<LiveActivity>>(emptyList())
    val state: StateFlow<List<LiveActivity>> = _state.asStateFlow()
    private val handlers = ConcurrentHashMap<String, (String) -> Unit>()

    /** Start or update [a]; [onAction] answers its buttons (the action ids it declares). */
    fun upsert(a: LiveActivity, onAction: ((String) -> Unit)? = null) {
        onAction?.let { handlers[a.id] = it }
        _state.update { list -> (list.filterNot { it.id == a.id } + a).sortedWith(ORDER) }
    }

    fun end(id: String) {
        handlers.remove(id)
        _state.update { list -> list.filterNot { it.id == id } }
    }

    fun get(id: String): LiveActivity? = _state.value.firstOrNull { it.id == id }

    /** The one the island leads with: urgent first, then priority, then the newest. */
    fun lead(): LiveActivity? = _state.value.firstOrNull()

    fun act(id: String, action: String) { handlers[id]?.invoke(action) }

    /** Tests. */
    internal fun clear() { handlers.clear(); _state.value = emptyList() }

    internal val ORDER = compareByDescending<LiveActivity> { it.urgent }.thenByDescending { it.priority }.thenByDescending { it.updatedAt }
}
