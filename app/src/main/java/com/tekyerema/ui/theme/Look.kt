package com.tekyerema.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * How the app looks — the user's choice in Settings › Accessibility (redesign, 2 Nov 2026, docs/assistant-ux/REDESIGN_2026-11-02.md).
 *
 * Two owner rules meet here. DESIGN.md (15 Oct): one liquid-glass material, no outline lines, edges drawn by light. The 27 Oct
 * "simple mode": nothing see-through or moving for decoration, for blind and low-vision users. So the default surface is the
 * glass material's SOLID variant (opaque — nothing behind ever shows through text, NN/g "text on top of text" — but still
 * edged by light, never by a grey line), and motion is calm. The see-through glass and a bordered high-contrast look are choices.
 *
 * Held here, not in the shared Settings, so the draw code (no Context) can read it: [init] is called by every Compose root
 * through [TekyeremaTheme]; config `ui.glassMode` / `ui.calmMotion` still win when set (tests, the owner's overrides).
 */
object Look {
    /** Default: glass on the floating control layer (tab bar, ask field, island), solid on everything you read (agreed with
     *  the brain session, 2 Nov: if a surface can't hold 4.5:1 on glass, it is a reading surface and it is solid). */
    const val AUTO = "auto"
    const val SOLID = "solid"
    const val GLASS = "glass"
    const val CONTRAST = "contrast"

    private const val PREFS = "nexus_look"
    private const val KEY_SURFACE = "surface"
    private const val KEY_MOTION = "motion"
    private const val KEY_COLOURS = "colours"
    private const val KEY_ANSWER = "answer_size"

    @Volatile private var prefs: android.content.SharedPreferences? = null
    private val _version = MutableStateFlow(0L)
    /** Bumped on every change, so Compose roots re-read the look. */
    val version: StateFlow<Long> get() = _version

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** auto (default) | solid | glass | contrast. */
    var surface: String
        get() = prefs?.getString(KEY_SURFACE, AUTO) ?: AUTO
        set(v) { prefs?.edit()?.putString(KEY_SURFACE, v)?.apply(); _version.value++ }

    /** Full motion: cards flow, buttons give under the finger. Off (default) = calm; the voice circle moves either way. */
    var fullMotion: Boolean
        get() = prefs?.getBoolean(KEY_MOTION, false) ?: false
        set(v) { prefs?.edit()?.putBoolean(KEY_MOTION, v)?.apply(); _version.value++ }

    /** Reading colours (ColourPairs): "standard" follows Light/Dark; a pair sets the page, the words and the edges itself. */
    var colours: String
        get() = prefs?.getString(KEY_COLOURS, ColourPairs.STANDARD) ?: ColourPairs.STANDARD
        set(v) { prefs?.edit()?.putString(KEY_COLOURS, v)?.apply(); _version.value++ }

    /** The chosen colour pair, or null for the standard look. */
    fun pair(): ColourPair? = ColourPairs.of(colours)

    /** Answer size (AnswerSizes): a multiplier on the words of answers and questions, on top of the phone's font size. */
    var answerSize: Float
        get() = prefs?.getFloat(KEY_ANSWER, AnswerSizes.STANDARD) ?: AnswerSizes.STANDARD
        set(v) { prefs?.edit()?.putFloat(KEY_ANSWER, v)?.apply(); _version.value++ }

    /** The material of a floating control (tab bar, ask field, island). Config `ui.glassMode` wins when it is set. A colour
     *  pair draws everything with the high-contrast material (opaque, a real edge in the pair's colour). */
    fun controlMode(): com.tekyerema.ui.glass.GlassMode = configMode() ?: if (pair() != null) com.tekyerema.ui.glass.GlassMode.HIGH_CONTRAST else when (surface) {
        SOLID -> com.tekyerema.ui.glass.GlassMode.SOLID
        CONTRAST -> com.tekyerema.ui.glass.GlassMode.HIGH_CONTRAST
        else -> com.tekyerema.ui.glass.GlassMode.TINTED
    }

    /** The material of something you read (cards, lists, the read-back, replies). */
    fun readingMode(): com.tekyerema.ui.glass.GlassMode = configMode() ?: if (pair() != null) com.tekyerema.ui.glass.GlassMode.HIGH_CONTRAST else when (surface) {
        GLASS -> com.tekyerema.ui.glass.GlassMode.TINTED
        CONTRAST -> com.tekyerema.ui.glass.GlassMode.HIGH_CONTRAST
        else -> com.tekyerema.ui.glass.GlassMode.SOLID
    }

    /** Reading surfaces are opaque unless the user chose see-through glass. */
    val opaqueReading: Boolean get() = readingMode() != com.tekyerema.ui.glass.GlassMode.TINTED

    /** Calm motion (default): the platform/user reduce-motion settings are checked separately by the theme. */
    fun calm(): Boolean {
        val cfg = try { com.tekyerema.core.config.AppConfig.node("ui.calmMotion") } catch (_: Throwable) { null }
        if (cfg != null) return com.tekyerema.core.config.AppConfig.bool("ui.calmMotion", true)
        return !fullMotion
    }

    private fun configMode(): com.tekyerema.ui.glass.GlassMode? {
        val raw = try { com.tekyerema.core.config.AppConfig.string("ui.glassMode", "") } catch (_: Throwable) { "" }
        return when (raw.lowercase()) {
            "" -> null
            "solid" -> com.tekyerema.ui.glass.GlassMode.SOLID
            "contrast", "high_contrast", "high-contrast" -> com.tekyerema.ui.glass.GlassMode.HIGH_CONTRAST
            else -> com.tekyerema.ui.glass.GlassMode.TINTED
        }
    }

    /** What Settings shows for each choice: a name and one sentence. */
    fun label(surface: String): String = when (surface) {
        SOLID -> "Solid"
        GLASS -> "Glass"
        CONTRAST -> "High contrast"
        else -> "Standard"
    }
}
