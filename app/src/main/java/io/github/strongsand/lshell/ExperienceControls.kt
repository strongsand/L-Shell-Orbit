package io.github.strongsand.lshell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ExperienceControls() {
    val settings = LocalExperience.current
    val context = LocalContext.current
    var themeOpen by rememberSaveable { mutableStateOf(false) }
    var hapticsOpen by rememberSaveable { mutableStateOf(false) }
    val selectTheme: (OrbitTheme) -> Unit = { ExperiencePreferences.write(context,settings.copy(theme = it)) }
    val selectHaptics: (HapticLevel) -> Unit = { ExperiencePreferences.write(context,settings.copy(haptics = it)) }
    if (LocalGroupedSettings.current) {
        SettingsThemeSelector(settings.theme, selectTheme)
        SettingsHapticSelector(settings.haptics, selectHaptics)
    } else {
        SettingsActionRow(stringResource(R.string.experience_theme),themeLabel(settings.theme)) { themeOpen = true }
        SettingsActionRow(stringResource(R.string.experience_haptics),hapticLabel(settings.haptics)) { hapticsOpen = true }
    }
    SettingsSwitchRow(stringResource(R.string.settings_technical),settings.technical,
        stringResource(R.string.settings_technical_summary), emphasized = true, icon = OrbitSymbols.Tune) {
        ExperiencePreferences.write(context,settings.copy(technical = it))
    }
    if (themeOpen) SettingsChoiceDialog(stringResource(R.string.experience_theme),
        OrbitTheme.entries.map { themeLabel(it) },settings.theme.ordinal,dismiss = { themeOpen = false }) {
        selectTheme(OrbitTheme.entries[it])
    }
    if (hapticsOpen) SettingsChoiceDialog(stringResource(R.string.experience_haptics),
        HapticLevel.entries.map { hapticLabel(it) },settings.haptics.ordinal,dismiss = { hapticsOpen = false }) {
        selectHaptics(HapticLevel.entries[it])
    }
}

/** Direct choices replace the two dialogs only inside Settings. */
@Composable
private fun SettingsThemeSelector(selected: OrbitTheme, onSelect: (OrbitTheme) -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.experience_theme), Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // No fixed text heights: narrow windows and very large fonts use a single column.
            val minimumChoiceWidth = 104.dp * fontScale
            val columns = if (maxWidth >= minimumChoiceWidth * 2 + 8.dp) 2 else 1
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OrbitTheme.entries.chunked(columns).forEach { themes ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        themes.forEach { theme ->
                            SettingsThemeChoice(theme, theme == selected, Modifier.weight(1f)) { onSelect(theme) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsThemeChoice(theme: OrbitTheme, selected: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    val motion = LocalOrbitMotion.current
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val corner by animateDpAsState(if (selected) 28.dp else 12.dp,
        OrbitMotion.spec(OrbitMotionRole.STATE, motion), label = "theme choice shape")
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        OrbitMotion.spec(OrbitMotionRole.STATE, motion), label = "theme choice surface")
    val foreground = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val icon = when (theme) {
        OrbitTheme.SYSTEM -> OrbitSymbols.BrightnessAuto
        OrbitTheme.LIGHT -> OrbitSymbols.LightMode
        OrbitTheme.DARK -> OrbitSymbols.DarkMode
        OrbitTheme.AMOLED -> OrbitSymbols.Contrast
    }
    Column(modifier.pressMotion(interaction).clip(RoundedCornerShape(corner)).background(container)
        .selectable(selected, interactionSource = interaction, indication = androidx.compose.material3.ripple(), role = Role.RadioButton, onClick = hapticClick(action = onSelect))
        .heightIn(min = 84.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(24.dp), tint = foreground)
            // Shape and a check mark communicate selection in addition to color.
            if (selected) Icon(OrbitSymbols.Check, null, Modifier.size(20.dp), tint = foreground)
            else Spacer(Modifier.size(20.dp))
        }
        Text(themeLabel(theme), style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium, color = foreground)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsHapticSelector(selected: HapticLevel, onSelect: (HapticLevel) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.experience_haptics), Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HapticLevel.entries.forEach { level ->
                val checked = level == selected
                val interaction = remember(level) { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val corner by animateDpAsState(if (checked) 16.dp else 28.dp,
                    OrbitMotion.spec(OrbitMotionRole.STATE, LocalOrbitMotion.current),
                    label = "haptic choice shape")
                val container by animateColorAsState(
                    if (checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    OrbitMotion.spec(OrbitMotionRole.STATE, LocalOrbitMotion.current),
                    label = "haptic choice surface")
                val foreground = if (checked) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
                Row(Modifier.pressMotion(interaction).clip(RoundedCornerShape(corner)).background(container)
                    .selectable(checked, interactionSource = interaction, indication = androidx.compose.material3.ripple(), role = Role.RadioButton, onClick = hapticClick { onSelect(level) })
                    .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (checked) Icon(OrbitSymbols.Check, null, Modifier.size(18.dp), tint = foreground)
                    else Spacer(Modifier.size(18.dp))
                    Text(hapticLabel(level), Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.labelLarge, color = foreground)
                }
            }
        }
    }
}

@Composable
private fun themeLabel(theme: OrbitTheme) = stringResource(when (theme) {
    OrbitTheme.SYSTEM -> R.string.theme_system
    OrbitTheme.LIGHT -> R.string.theme_light
    OrbitTheme.DARK -> R.string.theme_dark
    OrbitTheme.AMOLED -> R.string.theme_amoled
})
@Composable
private fun hapticLabel(level: HapticLevel) = stringResource(when (level) {
    HapticLevel.OFF -> R.string.haptics_off
    HapticLevel.SUBTLE -> R.string.haptics_subtle
    HapticLevel.STANDARD -> R.string.haptics_standard
    HapticLevel.EXPRESSIVE -> R.string.haptics_expressive
})
