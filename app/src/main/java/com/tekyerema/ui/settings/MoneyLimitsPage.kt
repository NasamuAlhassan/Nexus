package com.tekyerema.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tekyerema.money.MoneyLimits
import com.tekyerema.settings.Settings
import com.tekyerema.ui.components.ChoiceRow
import com.tekyerema.ui.components.GlassGroup
import com.tekyerema.ui.components.Note
import com.tekyerema.ui.components.PageTitle
import java.math.BigDecimal

/**
 * Settings › Control › Mobile Money limits (money safety round 2, 2026-09-29; money/MoneyLimits). The most one send and one
 * day of Mobile Money sends may be — none until the user picks one. A lower limit applies at once; a higher one, or "No
 * limit", only after the cooling-off (30 minutes), and the page says so aloud (live region) — the same rule as by voice, so
 * a caller can't talk the user into lifting it and sending at once. Every change goes to the activity log.
 */
@Composable
internal fun MoneyLimitsPage(settings: Settings, onBack: () -> Unit) {
    val version by settings.changes.collectAsState()
    val per = remember(version) { MoneyLimits.limit(MoneyLimits.Kind.PER_SEND) }
    val day = remember(version) { MoneyLimits.limit(MoneyLimits.Kind.DAILY) }
    val status = remember(version) { MoneyLimits.statusLine() }
    var said by remember { mutableStateOf("") }

    fun choose(kind: MoneyLimits.Kind, value: Int?) {
        said = MoneyLimits.changeLine(MoneyLimits.set(kind, value?.let { BigDecimal(it) }))
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        PageTitle("Mobile Money limits", onBack)
        Note("The most Tɛkyerɛma will help you send. A lower limit starts at once; a higher one, or none, starts after " +
            "${MoneyLimits.spokenWait(MoneyLimits.coolingOffMs()).removePrefix("in ")} — so no one on a call can talk you into " +
            "lifting it and sending straight away.")
        Note(status)
        if (said.isNotEmpty()) Note(said, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        GlassGroup("Most for one send") {
            Column(Modifier.selectableGroup()) {
                val options = listOf<Int?>(null, 50, 100, 200, 500, 1000)
                options.forEachIndexed { i, n ->
                    ChoiceRow(n?.let { "$it cedis" } ?: "No limit", selected = same(per, n), divider = i < options.lastIndex) {
                        choose(MoneyLimits.Kind.PER_SEND, n)
                    }
                }
            }
        }
        GlassGroup("Most in one day") {
            Column(Modifier.selectableGroup()) {
                val options = listOf<Int?>(null, 200, 500, 1000, 2000, 5000)
                options.forEachIndexed { i, n ->
                    ChoiceRow(n?.let { "$it cedis" } ?: "No limit", selected = same(day, n), divider = i < options.lastIndex) {
                        choose(MoneyLimits.Kind.DAILY, n)
                    }
                }
            }
        }
        Note("You can also say “set my MoMo limit to 200 cedis”, “set my daily MoMo limit to 500”, “what is my MoMo limit” " +
            "or “remove my MoMo limit”. A send over your limit is refused before anything is read back.")
    }
}

private fun same(limit: BigDecimal?, n: Int?): Boolean = if (limit == null) n == null else n != null && limit.compareTo(BigDecimal(n)) == 0

/** The Control row's value: "200 cedis a send" / "500 cedis a day" / "None" (on screen only). */
internal fun moneyLimitsSummary(): String {
    val per = MoneyLimits.limit(MoneyLimits.Kind.PER_SEND)
    val day = MoneyLimits.limit(MoneyLimits.Kind.DAILY)
    return when {
        per != null -> "${MoneyLimits.cedis(per)} a send"
        day != null -> "${MoneyLimits.cedis(day)} a day"
        else -> "None"
    }
}
