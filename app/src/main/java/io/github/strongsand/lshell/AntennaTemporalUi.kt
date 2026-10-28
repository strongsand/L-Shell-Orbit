package io.github.strongsand.lshell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import io.github.strongsand.lshell.core.model.DishySnapshot
import androidx.compose.ui.graphics.vector.ImageVector

internal object AntennaUiTokens {
    val seriesStroke = 2.dp
}

/** Missing readings remain absent; a measured zero is not an empty state. */
internal fun antennaObstructionPercent(fraction: Float?, available: Boolean): Float? =
    fraction?.takeIf { available && it.isFinite() }?.coerceIn(0f, 1f)?.times(100f)

@Composable
internal fun TemporalEventRow(timestamp: Long, title: String, detail: String,
    origin: (@Composable () -> Unit)? = null, initiallyExpanded: Boolean = false,
    icon: ImageVector = OrbitSymbols.History) {
    var expanded by rememberSaveable(timestamp, title, detail) { mutableStateOf(initiallyExpanded) }
    val expandable = detail.isNotBlank() || origin != null
    val state = stringResource(if (expanded) R.string.antenna_expanded else R.string.antenna_collapsed)
    val action = stringResource(if (expanded) R.string.antenna_hide_details else R.string.antenna_show_details)
    val interaction = if (expandable) Modifier.semantics { stateDescription = state }
        .clickable(role = Role.Button, onClickLabel = action, onClick = hapticClick { expanded = !expanded }) else Modifier
    val axis = MaterialTheme.colorScheme.outlineVariant
    Surface(Modifier.fillMaxWidth().then(interaction), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.height(IntrinsicSize.Min).padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(20.dp).fillMaxHeight().drawBehind {
                drawLine(axis, Offset(size.width / 2, 24.dp.toPx()),
                    Offset(size.width / 2, size.height), 1.dp.toPx())
            }) {
                Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestamp)),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                ChartInspectionPanel(expandable && expanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        origin?.invoke()
                    }
                }
            }
            if (expandable) Icon(OrbitSymbols.ExpandMore, null,
                Modifier.size(20.dp).rotate(if (expanded) 180f else 0f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun AntennaFieldOfViewCard(snapshot: DishySnapshot) {
    val percent = antennaObstructionPercent(snapshot.obstruction.fractionObstructed,
        snapshot.isOnline || snapshot.isFromCache)
    val state = stringResource(when {
        percent == null -> R.string.rich_view_wait
        snapshot.obstruction.currentlyObstructed -> R.string.rich_view_active
        percent >= 10 -> R.string.rich_view_critical
        percent >= 5 -> R.string.rich_view_blocked
        percent >= 1 -> R.string.rich_view_caution
        else -> R.string.rich_view_clear
    })
    val detail = stringResource(when {
        percent == null -> R.string.rich_view_no_data
        snapshot.isFromCache -> R.string.rich_view_cache
        snapshot.obstruction.currentlyObstructed -> R.string.rich_view_block
        percent >= 5 -> R.string.rich_view_move
        percent >= 1 -> R.string.rich_view_small
        else -> R.string.rich_view_good
    })
    AntennaFieldOfViewContent(percent, state, detail, activeObstruction = snapshot.obstruction.currentlyObstructed)
}

@Composable
internal fun AntennaFieldOfViewContent(percent: Float?, state: String, detail: String, activeObstruction: Boolean = false) {
    val value = percent?.let { String.format(Locale.getDefault(), "%.1f%%", it) } ?: "—"
    val spoken = if (percent == null) state else stringResource(R.string.rich_obstruction_spoken, state, value)
    Surface(Modifier.fillMaxWidth(), shape = DashDesign.section,
        color = MaterialTheme.colorScheme.surfaceContainerLow, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeading(stringResource(R.string.rich_field), stringResource(R.string.rich_obstruction))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stacked = HomePresentation.stacked(maxWidth.value, LocalDensity.current.fontScale, 300f)
                val number: @Composable () -> Unit = {
                    Column {
                        Text(value, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface)
                        Text(state, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val ring: @Composable () -> Unit = {
                    Box(Modifier.size(80.dp).clearAndSetSemantics { contentDescription = spoken },
                        contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(progress = { percent?.let { 1f - it / 100f } ?: 0f },
                            modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.outline, strokeWidth = 6.dp)
                        Icon(when {
                            percent == null -> OrbitSymbols.Visibility
                            activeObstruction || percent >= 1 -> OrbitSymbols.Warning
                            else -> OrbitSymbols.CheckCircle
                        }, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
                if (stacked) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { number(); ring() }
                else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.weight(1f)) { number() }; ring()
                }
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

