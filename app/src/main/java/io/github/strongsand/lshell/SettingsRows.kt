package io.github.strongsand.lshell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Scoped to Settings: the same rows in report details and Home keep their style.
internal val LocalGroupedSettings = compositionLocalOf { false }
private val settingsRowShape = RoundedCornerShape(20.dp)
private val settingsGroupShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp, bottomEnd = 16.dp, bottomStart = 32.dp)

@Composable
internal fun SettingsSection(title: String, icon: ImageVector, prominent: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val motion = if (LocalOrbitMotion.current) Modifier.animateContentSize(
        spring(dampingRatio = 1f, stiffness = 400f)) else Modifier
    val headerContainer = amoledCardColor(if (prominent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
    val headerForeground = amoledCardContent(if (prominent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
    Surface(Modifier.fillMaxWidth().then(motion), shape = if (prominent) DashDesign.hero else settingsGroupShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Row(Modifier.fillMaxWidth().background(headerContainer).padding(20.dp)
                .semantics(mergeDescendants = true) { heading() },
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(if (prominent) 52.dp else 40.dp)
                    .background(if (prominent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer, DashDesign.cookie),
                    contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(if (prominent) 28.dp else 22.dp),
                        tint = if (prominent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Text(title, Modifier.weight(1f),
                    style = if (prominent) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                    color = headerForeground)
            }
            CompositionLocalProvider(LocalGroupedSettings provides true) {
                Column(Modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
            }
        }
    }
}

/** Entire rows own the action and label; trailing controls do not add duplicate targets. */
@Composable
internal fun SettingsActionRow(title: String, value: String? = null, supporting: String? = null,
    enabled: Boolean = true, onClick: () -> Unit) {
    val grouped = LocalGroupedSettings.current
    val container = if (grouped) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface
    Surface(onClick = hapticClick(action = onClick),enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = if (grouped) settingsRowShape else RectangleShape, color = container) {
        if (grouped) {
            val alpha = if (enabled) 1f else .38f
            Row(Modifier.heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
                    value?.let {
                        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Text(it, style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = alpha))
                        }
                    }
                    supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)) }
                }
                Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, DashDesign.pill),
                    contentAlignment = Alignment.Center) {
                    Icon(OrbitSymbols.ChevronRight, null, Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
                }
            }
        } else ListItem(headlineContent = { Text(title) },supportingContent = if (value != null || supporting != null) {{
            Column(verticalArrangement = Arrangement.spacedBy(if (grouped) 3.dp else 0.dp)) {
                value?.let { Text(it,color = MaterialTheme.colorScheme.onSurfaceVariant) }
                supporting?.let { Text(it,style = MaterialTheme.typography.bodySmall) }
            }
        }} else null,trailingContent = { Icon(OrbitSymbols.ChevronRight,null) },
            colors = ListItemDefaults.colors(containerColor = container))
    }
}

@Composable
internal fun SettingsSwitchRow(title: String, checked: Boolean, supporting: String? = null,
    enabled: Boolean = true, emphasized: Boolean = false, icon: ImageVector? = null, onChange: (Boolean) -> Unit) {
    val grouped = LocalGroupedSettings.current
    val highlighted = grouped && emphasized
    val container by animateColorAsState(
        if (highlighted && checked) amoledCardColor(MaterialTheme.colorScheme.primaryContainer)
        else if (highlighted) MaterialTheme.colorScheme.surfaceContainerHigh
        else if (grouped && checked && enabled) MaterialTheme.colorScheme.surfaceContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
        if (LocalOrbitMotion.current) spring(dampingRatio = 1f, stiffness = 500f) else snap(),
        label = "settings switch container")
    val foreground = if (!grouped) LocalContentColor.current
        else if (highlighted && checked) amoledCardContent(MaterialTheme.colorScheme.onPrimaryContainer) else MaterialTheme.colorScheme.onSurface
    val supportingColor = if (highlighted && checked) foreground else MaterialTheme.colorScheme.onSurfaceVariant
    if (highlighted) {
        val corner by animateDpAsState(if (checked) 32.dp else 12.dp,
            if (LocalOrbitMotion.current) spring(dampingRatio = 1f, stiffness = 400f) else snap(),
            label = "technical switch shape")
        Column(Modifier.fillMaxWidth().padding(6.dp)
            .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp, bottomStart = 32.dp, bottomEnd = corner))
            .background(container)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = hapticToggle(action = onChange))
            .padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (icon != null && LocalDensity.current.fontScale < 1.5f) {
                    Box(Modifier.size(36.dp).background(
                        if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                        DashDesign.cookie), contentAlignment = Alignment.Center) {
                        Icon(icon, null, Modifier.size(22.dp), tint = if (checked) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                    color = if (enabled) foreground else foreground.copy(alpha = .38f))
                Switch(checked, onCheckedChange = null, enabled = enabled,
                    thumbContent = if (checked) {{ Icon(OrbitSymbols.Check, null, Modifier.size(SwitchDefaults.IconSize)) }} else null)
            }
            supporting?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) supportingColor else supportingColor.copy(alpha = .38f)) }
        }
        return
    }
    val rowModifier = if (grouped) Modifier.clip(settingsRowShape).background(container) else Modifier
    Row(rowModifier.fillMaxWidth().heightIn(min = if (grouped) 72.dp else 64.dp)
        .toggleable(checked,enabled = enabled,role = Role.Switch,onValueChange = hapticToggle(action = onChange))
        .padding(horizontal = 16.dp,vertical = if (grouped) 14.dp else 10.dp),verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Preserve text width with large accessibility fonts; the icon is decorative.
        if (grouped && icon != null && LocalDensity.current.fontScale < 1.5f) {
            Icon(icon, null, Modifier.size(24.dp),
                tint = if (enabled) foreground else foreground.copy(alpha = .38f))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (grouped) 4.dp else 0.dp)) {
            Text(title,style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (grouped) FontWeight.Medium else null,
                color = if (grouped && !enabled) foreground.copy(alpha = .38f) else foreground)
            supporting?.let { Text(it,style = MaterialTheme.typography.bodySmall,
                color = if (grouped && !enabled) supportingColor.copy(alpha = .38f) else supportingColor) }
        }
        Switch(checked,onCheckedChange = null,enabled = enabled,
            thumbContent = if (grouped && checked) {{ Icon(OrbitSymbols.Check, null, Modifier.size(SwitchDefaults.IconSize)) }} else null)
    }
}

/** Auxiliary actions stay quieter than selectable settings and only gain tone here. */
@Composable
internal fun SettingsSecondaryAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    if (LocalGroupedSettings.current) {
        FilledTonalButton(onClick = hapticClick(action = onClick), enabled = enabled,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).heightIn(min = 48.dp),
            shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface)) {
            Text(label)
        }
    } else TextButton(onClick = hapticClick(action = onClick), enabled = enabled) { Text(label) }
}

@Composable
internal fun SettingsChoiceDialog(title: String, choices: List<String>, selected: Int,
    note: String? = null, dismiss: () -> Unit, onSelect: (Int) -> Unit) {
    AlertDialog(onDismissRequest = dismiss,title = { Text(title) },text = {
        LazyColumn(Modifier.heightIn(max = 420.dp).selectableGroup()) {
            note?.let { item { Text(it,Modifier.padding(bottom = 12.dp),style = MaterialTheme.typography.bodySmall) } }
            itemsIndexed(choices) { index,label ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .selectable(index == selected,role = Role.RadioButton,onClick = hapticClick { onSelect(index); dismiss() })
                    .padding(vertical = 8.dp),verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RadioButton(index == selected,onClick = null)
                    Text(label,Modifier.weight(1f))
                }
            }
        }
    },confirmButton = { TextButton(onClick = hapticClick(action = dismiss)) { Text(stringResource(R.string.traffic_cancel)) } })
}
