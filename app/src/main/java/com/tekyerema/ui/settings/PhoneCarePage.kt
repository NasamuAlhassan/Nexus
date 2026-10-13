package com.tekyerema.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PhoneMissed
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tekyerema.phonecare.PhoneCheck
import com.tekyerema.phonecare.PhoneProbe
import com.tekyerema.settings.Settings
import com.tekyerema.ui.Perms
import com.tekyerema.ui.components.GlassGroup
import com.tekyerema.ui.components.Hairline
import com.tekyerema.ui.components.Note
import com.tekyerema.ui.components.PageTitle
import com.tekyerema.ui.components.SettingRow
import com.tekyerema.ui.components.SwitchRow
import com.tekyerema.ui.theme.Spectrum
import com.tekyerema.ui.theme.Tk
import kotlinx.coroutines.launch

/**
 * Settings › Phone care: "check my phone" on a button, and the event alerts (money received, missed calls, low battery),
 * each OFF until switched on here or by voice. WHY: A20e 2026-09-29 — a judge's session failed on a Private DNS stall,
 * an unbound accessibility service and a spent AI quota, and nothing said so (phonecare/PhoneCheck, PhoneAlerts).
 */
@Composable
internal fun PhoneCarePage(settings: Settings, onBack: () -> Unit) {
    val version by settings.changes.collectAsState()
    val opTick by com.tekyerema.operator.OperatorPolicy.changes.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val money = remember(version) { settings.alertMoney }
    val balance = remember(version) { settings.alertMoneyBalance }
    val missed = remember(version) { settings.alertMissedCalls }
    val battery = remember(version) { settings.alertBattery }
    val privateAlerts = remember(version) { settings.alertsPrivate }
    val notifications = remember(version) { Perms.notifications(context) }
    val messageAlerts = remember(opTick) { com.tekyerema.operator.OperatorPolicy.messageAlertsEnabled }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var details by remember { mutableStateOf<List<String>>(emptyList()) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        PageTitle("Phone care", onBack)
        Note("Tɛkyerɛma can check that everything it needs is working, and tell you about things that matter as they happen.")
        GlassGroup("Check my phone") {
            SettingRow("Check my phone", value = if (checking) "Checking…" else "", icon = Icons.Outlined.HealthAndSafety,
                iconTint = Spectrum.Lime, divider = false,
                description = "Permissions, the internet, the AI and speech — and how to fix what isn't working.") {
                if (!checking) {
                    checking = true
                    scope.launch {
                        // Leaving the page cancels the check: that must not say "Sorry, I couldn't check".
                        val facts = try { PhoneProbe.probe(context) }
                            catch (ce: kotlinx.coroutines.CancellationException) { throw ce } catch (_: Throwable) { null }
                        val line = facts?.let { PhoneCheck.summarise(PhoneCheck.findings(it), it.batteryPct, it.charging) }
                            ?: "Sorry, I couldn't check the phone just now."
                        result = line
                        details = facts?.let { PhoneCheck.details(it) }.orEmpty()
                        checking = false
                        // TalkBack reads the result from the live region; otherwise Tɛkyerɛma says it.
                        if (!Perms.talkBack(context)) com.tekyerema.voice.VoiceEngine.announce(line)
                    }
                }
            }
        }
        result?.let { Note(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (details.isNotEmpty()) GlassGroup("What I checked") {
            details.forEachIndexed { i, d -> DetailLine(d, divider = i < details.size - 1) }
        }
        GlassGroup("Alerts") {
            SwitchRow("Money received", "Says who sent you Mobile Money and how much, and warns you about “sent by mistake” tricks.",
                money, icon = Icons.Outlined.Payments, iconTint = Spectrum.Gold) { settings.alertMoney = it }
            SwitchRow("Include my balance", "Money alerts also say your balance. Off: your balance is never said.",
                balance, icon = Icons.Outlined.AccountBalanceWallet, iconTint = Spectrum.Gold) { settings.alertMoneyBalance = it }
            SwitchRow("Missed calls", "Tells you when someone in your contacts calls and you miss it.",
                missed, icon = Icons.AutoMirrored.Outlined.PhoneMissed, iconTint = Spectrum.Lime) { settings.alertMissedCalls = it }
            SwitchRow("Low battery", "Warns you when the battery is low, and again when it's nearly empty.",
                battery, icon = Icons.Outlined.BatteryAlert, iconTint = Spectrum.Coral) { settings.alertBattery = it }
            SwitchRow("Private alerts", "On the loudspeaker, only says that you have an alert — say “read it” to hear it. " +
                "With earphones or Bluetooth, reads it straight away.", privateAlerts, divider = false,
                icon = Icons.Outlined.VisibilityOff, iconTint = Spectrum.Green) { settings.alertsPrivate = it }
        }
        if ((money || missed) && !notifications)
            Note("Money and missed-call alerts need “Read messages”. Turn it on in Settings, Permissions.")
        if ((money || missed) && !messageAlerts)
            Note("“Message alerts” is off in Settings, Control, so money and missed-call alerts stay quiet.")
        Note("Alerts stay quiet at night${quietHoursLabel()}, during calls and while Tɛkyerɛma is busy, and each is said once. " +
            "Codes are never read out. You can also say “check my phone”, “tell me when I receive money”, “tell me about " +
            "missed calls”, “warn me when my battery is low” or “read my alerts”.")
    }
}

/** " (10 PM to 6 AM)" from config triggers.quietHours; "" when quiet hours are off. */
private fun quietHoursLabel(): String {
    val w = com.tekyerema.agent.triggers.QuietHours.parse(com.tekyerema.agent.triggers.QuietHours.configuredSpec()) ?: return ""
    fun t(m: Int): String {
        val h = m / 60; val mm = m % 60
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$h12" + (if (mm > 0) ":" + mm.toString().padStart(2, '0') else "") + if (h < 12) " AM" else " PM"
    }
    return " (${t(w.startMin)} to ${t(w.endMin)})"
}

/** One read-only line of the check's details (TalkBack reads it as one item). */
@Composable
private fun DetailLine(text: String, divider: Boolean) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Tk.TextSecondary,
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).padding(horizontal = 18.dp, vertical = 12.dp))
        if (divider) Hairline(inset = 20.dp)
    }
}
