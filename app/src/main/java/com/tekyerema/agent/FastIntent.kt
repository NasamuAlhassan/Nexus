package com.tekyerema.agent

/**
 * The deterministic device intents the Local Fast Path can execute WITHOUT the LLM/agent. Pure data —
 * classification ([FastIntentClassifier]) is Android-free and unit-tested.
 */
sealed class FastIntent(val name: String) {
    object Time : FastIntent("TIME")
    object Date : FastIntent("DATE")
    object Day : FastIntent("DAY")
    object Battery : FastIntent("BATTERY")
    object Storage : FastIntent("STORAGE")
    object VolumeUp : FastIntent("VOLUME_UP")
    object VolumeDown : FastIntent("VOLUME_DOWN")
    object VolumeMute : FastIntent("VOLUME_MUTE")
    object VolumeUnmute : FastIntent("VOLUME_UNMUTE")
    object VolumeQuery : FastIntent("VOLUME_QUERY")
    object BrightnessUp : FastIntent("BRIGHTNESS_UP")
    object BrightnessDown : FastIntent("BRIGHTNESS_DOWN")
    object FlashlightOn : FastIntent("FLASHLIGHT_ON")
    object FlashlightOff : FastIntent("FLASHLIGHT_OFF")
    object WifiQuery : FastIntent("WIFI_QUERY")
    object WifiOn : FastIntent("WIFI_ON")
    object WifiOff : FastIntent("WIFI_OFF")
    object BluetoothQuery : FastIntent("BLUETOOTH_QUERY")
    object BluetoothOn : FastIntent("BLUETOOTH_ON")
    object BluetoothOff : FastIntent("BLUETOOTH_OFF")
    object MediaPlay : FastIntent("MEDIA_PLAY")
    object MediaPause : FastIntent("MEDIA_PAUSE")
    object MediaStop : FastIntent("MEDIA_STOP")
    object MediaNext : FastIntent("MEDIA_NEXT")
    object MediaPrevious : FastIntent("MEDIA_PREVIOUS")
    object Back : FastIntent("BACK")
    object Home : FastIntent("HOME")
    object Recents : FastIntent("RECENTS")
    object ScrollUp : FastIntent("SCROLL_UP")
    object ScrollDown : FastIntent("SCROLL_DOWN")
    object LockScreen : FastIntent("LOCK_SCREEN")
    object Notifications : FastIntent("NOTIFICATIONS")
    data class OpenApp(val query: String) : FastIntent("OPEN_APP")
}

/**
 * Confidence-gated intent classification for the Local Fast Path. Deterministic, normalized-intent based
 * (not an ad-hoc pile of substrings): each category has a focused pattern, checked in a priority order that
 * resolves overlaps (query before toggle, brightness kept distinct from volume, media transport before
 * navigation). Returns null when it is NOT confident the request is a simple deterministic device op — the
 * caller then falls through to the existing agent, UNCHANGED. Never intercepts a compound/multi-step request.
 *
 * Twi: a few high-confidence normalized Twi forms are covered for the top intents (open=bue, home=fie,
 * back=san kɔ, volume so/ase). Anything not confidently matched falls through — never fake recognition.
 */
object FastIntentClassifier {

    fun classify(raw: String): FastIntent? {
        val g = raw.trim().lowercase().replace(Regex("\\s+"), " ").trim().trimEnd('.', '!', '?').trim()
        if (g.isEmpty()) return null
        // Compound / multi-step → the agent (the fast path is single deterministic action only).
        if (RE_MULTISTEP.containsMatchIn(g)) return null

        return when {
            RE_TIME.containsMatchIn(g) -> FastIntent.Time
            RE_DAY.containsMatchIn(g) -> FastIntent.Day
            RE_DATE.containsMatchIn(g) -> FastIntent.Date
            RE_BATTERY.containsMatchIn(g) -> FastIntent.Battery
            RE_STORAGE.containsMatchIn(g) && !RE_STORAGE_ACTION.containsMatchIn(g) -> FastIntent.Storage

            RE_VOL_QUERY.containsMatchIn(g) -> FastIntent.VolumeQuery
            RE_UNMUTE.containsMatchIn(g) -> FastIntent.VolumeUnmute
            RE_MUTE.containsMatchIn(g) -> FastIntent.VolumeMute
            RE_VOL_UP.containsMatchIn(g) -> FastIntent.VolumeUp
            RE_VOL_DOWN.containsMatchIn(g) -> FastIntent.VolumeDown

            RE_BRIGHT_UP.containsMatchIn(g) -> FastIntent.BrightnessUp
            RE_BRIGHT_DOWN.containsMatchIn(g) -> FastIntent.BrightnessDown

            RE_TORCH_OFF.containsMatchIn(g) -> FastIntent.FlashlightOff
            RE_TORCH_ON.containsMatchIn(g) -> FastIntent.FlashlightOn

            RE_WIFI_QUERY.containsMatchIn(g) -> FastIntent.WifiQuery
            RE_WIFI_ON.containsMatchIn(g) -> FastIntent.WifiOn
            RE_WIFI_OFF.containsMatchIn(g) -> FastIntent.WifiOff
            RE_BT_QUERY.containsMatchIn(g) -> FastIntent.BluetoothQuery
            RE_BT_ON.containsMatchIn(g) -> FastIntent.BluetoothOn
            RE_BT_OFF.containsMatchIn(g) -> FastIntent.BluetoothOff

            RE_MEDIA_NEXT.containsMatchIn(g) -> FastIntent.MediaNext
            RE_MEDIA_PREV.containsMatchIn(g) -> FastIntent.MediaPrevious
            RE_MEDIA_PAUSE.containsMatchIn(g) -> FastIntent.MediaPause
            RE_MEDIA_STOP.containsMatchIn(g) -> FastIntent.MediaStop
            RE_MEDIA_PLAY.containsMatchIn(g) -> FastIntent.MediaPlay

            RE_LOCK.containsMatchIn(g) -> FastIntent.LockScreen
            RE_NOTIFS.containsMatchIn(g) -> FastIntent.Notifications
            RE_RECENTS.containsMatchIn(g) -> FastIntent.Recents
            RE_SCROLL_UP.containsMatchIn(g) -> FastIntent.ScrollUp
            RE_SCROLL_DOWN.containsMatchIn(g) -> FastIntent.ScrollDown
            RE_HOME.containsMatchIn(g) -> FastIntent.Home
            RE_BACK.containsMatchIn(g) -> FastIntent.Back

            else -> openApp(g)
        }
    }

    private fun openApp(g: String): FastIntent? {
        val app = RE_OPEN.find(g)?.groupValues?.last()?.trim() ?: return null
        if (app.isEmpty()) return null
        if (app.contains(",")) return null                       // "open whatsapp, find kwame" = compound
        if (RE_APP_HAS_VERB.containsMatchIn(app)) return null     // a second instruction, not an app label
        if (app.split(" ").size > 3) return null                 // an app label is a short noun phrase
        return FastIntent.OpenApp(app)
    }

    // Compound/multi-step markers → hand the whole thing to the agent.
    private val RE_MULTISTEP = Regex("\\b(and then|and|then|after that|na)\\b|;")

    private val RE_TIME = Regex("\\b(what(?:'?s| is)?\\s+(?:the\\s+)?time|what\\s+time\\s+is\\s+it|tell\\s+me\\s+(?:the\\s+)?time|current\\s+time|check\\s+the\\s+time|bere\\s+bɛn|bɛre\\s+bɛn)\\b")
    private val RE_DAY = Regex("\\bwhat\\s+day\\b|\\bwhich\\s+day\\b|\\bis\\s+today\\s+\\w+day\\b|\\btell\\s+me\\s+the\\s+day\\b|\\bday\\s+(?:of\\s+the\\s+week|is\\s+it|is\\s+today)\\b")
    private val RE_DATE = Regex("\\bdate\\b")
    // A storage QUESTION ("how much storage is left", "do I have enough space") — not an action on storage
    // ("free up space", "clear storage", "open storage settings"), which goes to the agent.
    private val RE_STORAGE = Regex("\\b(how\\s+much|what'?s|what\\s+is|check|remaining|left|enough|available)\\b.*\\b(storage|space)\\b|" +
        "\\b(storage|space)\\b.*\\b(left|remaining|available|do\\s+i\\s+have|is\\s+free)\\b")
    private val RE_STORAGE_ACTION = Regex("\\b(free\\s+up|clear|clean|delete|remove|open|settings|manage|make)\\b")
    private val RE_BATTERY = Regex("\\bbattery\\b|\\bhow\\s+much\\s+(?:charge|power)\\b")

    private val RE_VOL_QUERY = Regex("\\bwhat(?:'?s| is)?\\s+(?:my\\s+|the\\s+)?volume(?:\\s+level)?\\b|\\bcheck\\s+(?:the\\s+|my\\s+)?volume\\b|\\bvolume\\s+level\\b|\\bhow\\s+loud\\b")
    private val RE_UNMUTE = Regex("\\bunmute\\b|\\bun-mute\\b|\\bturn\\s+(?:the\\s+)?(?:sound|volume|audio)\\s+back\\s+on\\b|\\bunsilence\\b|\\bsound\\s+back\\s+on\\b")
    private val RE_MUTE = Regex("\\bmute\\b|\\bsilence\\b|\\bgo\\s+silent\\b|\\bsilent\\s+mode\\b")
    private val RE_VOL_UP = Regex("\\bvolume\\s+up\\b|\\blouder\\b|\\bturn\\s+it\\s+up\\b|\\bturn\\s+up\\s+(?:the\\s+|my\\s+)?(?:volume|sound)\\b|\\bturn\\s+(?:the\\s+)?(?:volume|sound)\\s+up\\b|\\b(?:increase|raise|boost|crank|pump|bump)\\s+(?:the\\s+|my\\s+)?(?:volume|sound)\\b|\\bmake\\s+it\\s+louder\\b|\\bma\\s+volume\\s+no\\s+so\\b")
    private val RE_VOL_DOWN = Regex("\\bvolume\\s+down\\b|\\b(?:quieter|softer)\\b|\\bturn\\s+it\\s+down\\b|\\bturn\\s+down\\s+(?:the\\s+|my\\s+)?(?:volume|sound)\\b|\\bturn\\s+(?:the\\s+)?(?:volume|sound)\\s+down\\b|\\b(?:decrease|lower|reduce|drop)\\s+(?:the\\s+|my\\s+)?(?:volume|sound)\\b|\\bmake\\s+it\\s+(?:quieter|softer)\\b")

    private val RE_BRIGHT_UP = Regex("\\b(?:increase|raise|boost|turn\\s+up|up)\\b[^.]*\\bbright(?:ness|er)?\\b|\\bbright(?:ness|er)?\\b[^.]*\\b(?:up|higher|brighter)\\b|\\bbrighter\\b|\\bbrighten\\b|\\bmake\\s+(?:the\\s+)?screen\\s+brighter\\b")
    private val RE_BRIGHT_DOWN = Regex("\\b(?:decrease|lower|reduce|turn\\s+down|dim)\\b[^.]*\\bbright(?:ness|er)?\\b|\\bmake\\s+(?:the\\s+)?screen\\s+(?:darker|dimmer)\\b|\\b(?:darker|dimmer)\\b|\\bdim\\s+the\\s+screen\\b")

    private val RE_TORCH_OFF = Regex("\\b(?:flashlight|torch|flash\\s*light)\\b[^.]*\\boff\\b|\\boff\\b[^.]*\\b(?:flashlight|torch|flash\\s*light)\\b")
    private val RE_TORCH_ON = Regex("\\b(?:flashlight|torch|flash\\s*light)\\b")

    private val RE_WIFI_QUERY = Regex("\\bis\\s+(?:my\\s+)?wi.?fi\\b|\\bcheck\\s+(?:my\\s+)?wi.?fi\\b|\\bwi.?fi\\s+status\\b")
    private val RE_WIFI_ON = Regex("\\b(?:turn|switch|put)\\s+on\\b[^.]*\\bwi.?fi\\b|\\benable\\s+wi.?fi\\b|\\bwi.?fi\\s+on\\b")
    private val RE_WIFI_OFF = Regex("\\b(?:turn|switch|put)\\s+off\\b[^.]*\\bwi.?fi\\b|\\bdisable\\s+wi.?fi\\b|\\bwi.?fi\\s+off\\b")
    private val RE_BT_QUERY = Regex("\\bis\\s+(?:my\\s+)?bluetooth\\b|\\bcheck\\s+(?:my\\s+)?bluetooth\\b|\\bbluetooth\\s+status\\b")
    private val RE_BT_ON = Regex("\\b(?:turn|switch|put)\\b[^.]*\\bbluetooth\\b[^.]*\\bon\\b|\\bturn\\s+on\\s+bluetooth\\b|\\benable\\s+bluetooth\\b|\\bbluetooth\\s+on\\b")
    private val RE_BT_OFF = Regex("\\b(?:turn|switch|put)\\b[^.]*\\bbluetooth\\b[^.]*\\boff\\b|\\bturn\\s+off\\s+bluetooth\\b|\\bdisable\\s+bluetooth\\b|\\bbluetooth\\s+off\\b")

    private val RE_MEDIA_NEXT = Regex("^(?:next|skip)$|\\bnext\\s+(?:song|track)\\b|\\bskip\\b|\\bnext\\s+one\\b")
    private val RE_MEDIA_PREV = Regex("^previous$|\\bprevious\\s+(?:song|track)\\b|\\bgo\\s+back\\s+a\\s+(?:song|track)\\b|\\blast\\s+song\\b")
    private val RE_MEDIA_PAUSE = Regex("^pause$|\\bpause\\b")
    private val RE_MEDIA_STOP = Regex("^stop$|\\bstop\\s+(?:the\\s+)?(?:music|song|playback|playing)\\b")
    private val RE_MEDIA_PLAY = Regex("^(?:play|resume|continue|unpause)$")

    private val RE_LOCK = Regex("^lock$|\\block\\s+(?:the\\s+|my\\s+)?(?:phone|screen|device)\\b|\\block\\s+it\\b")
    private val RE_NOTIFS = Regex("\\b(?:open|show|pull\\s+down|expand)\\s+(?:my\\s+|the\\s+)?notifications?\\b|\\bnotification\\s+(?:shade|panel|drawer)\\b")
    private val RE_RECENTS = Regex("\\b(?:recent\\s+apps?|recents|app\\s+switcher|switch\\s+apps?|multitask)\\b")
    private val RE_SCROLL_UP = Regex("\\bscroll\\s+up\\b|\\bscroll\\s+to\\s+the\\s+top\\b|\\b(?:go|jump)\\s+to\\s+the\\s+top\\b|\\bto\\s+the\\s+top\\b")
    private val RE_SCROLL_DOWN = Regex("\\bscroll\\s+down\\b|\\bscroll\\s+to\\s+the\\s+bottom\\b|\\b(?:go|jump)\\s+to\\s+the\\s+bottom\\b|\\bto\\s+the\\s+bottom\\b|\\bscroll\\b")
    private val RE_HOME = Regex("^home$|\\bgo\\s+home\\b|\\bhome\\s+screen\\b|\\bhome\\s+button\\b|\\bk[ɔo]\\s+fie\\b|\\bfie\\b")
    private val RE_BACK = Regex("^back$|\\bgo\\s+back\\b|\\bback\\s+button\\b|\\bprevious\\s+screen\\b|\\bsan\\s+k[ɔo]\\b")

    // "open/launch/start X" — X is the last group. Existing AppResolver does the actual name matching.
    private val RE_OPEN = Regex("^(?:please\\s+|can\\s+you\\s+|could\\s+you\\s+|kindly\\s+|i\\s+want\\s+to\\s+)?(?:open|launch|start|go\\s+to|goto|bring\\s+up|pull\\s+up|put\\s+on|bue\\S*|bua\\S*)\\s+(?:the\\s+)?(.+)$")
    private val RE_APP_HAS_VERB = Regex("\\b(send|message|reply|forward|dial|text|call|find|search|tell|saying|then)\\b")
}
