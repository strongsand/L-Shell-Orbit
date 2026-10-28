package io.github.strongsand.lshell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** Only presentation state is saveable; report data stays in the existing stores. */
@Composable internal fun ReportDisclosure(title: String, identity: String, icon: ImageVector? = null,
    summary: @Composable ColumnScope.() -> Unit = {}, expanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null, content: (@Composable ColumnScope.() -> Unit)? = null) {
    var savedExpanded by rememberSaveable(identity) { mutableStateOf(false) }
    val open = expanded ?: savedExpanded
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val rotation = androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f,
        OrbitMotion.spec(OrbitMotionRole.SETTLE, LocalOrbitMotion.current), label = "report disclosure")
    val motion = LocalOrbitMotion.current
    val state = stringResource(if (open) R.string.reports_expanded else R.string.reports_collapsed)
    Card(Modifier.fillMaxWidth(), shape = DashDesign.section,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().pressMotion(interaction, .99f).clickable(interactionSource = interaction, indication = ripple(), role = Role.Button, onClick = hapticClick { savedExpanded = !open; onExpandedChange?.invoke(!open) })
            .semantics(mergeDescendants = true) { stateDescription = state }.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (icon != null) Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                Icon(OrbitSymbols.ExpandMore, null, Modifier.graphicsLayer { rotationZ = rotation.value })
            }
            summary()
        }
        if (content != null) AnimatedVisibility(open,
            enter = if (motion) fadeIn() + expandVertically(animationSpec = OrbitMotion.spec(OrbitMotionRole.STATE, true)) else EnterTransition.None,
            exit = if (motion) fadeOut() + shrinkVertically(animationSpec = OrbitMotion.spec(OrbitMotionRole.STATE, true)) else ExitTransition.None) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable internal fun ReportSummaryGrid(tiles: List<@Composable () -> Unit>, minimumWidthDp: Float = 360f) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (HealthReportUiPolicy.twoColumns(maxWidth.value, fontScale, minimumWidthDp)) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            tiles.chunked(columns).forEach { cells ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    cells.forEach { cell -> Box(Modifier.weight(1f)) { cell() } }
                }
            }
        }
    }
}

@Composable internal fun ReportSummaryMetric(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable internal fun reportSpokenDuration(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return when {
        seconds >= 3600 -> stringResource(R.string.reports_spoken_hours, seconds / 3600, seconds / 60 % 60, seconds % 60)
        seconds >= 60 -> stringResource(R.string.reports_spoken_minutes, seconds / 60, seconds % 60)
        else -> stringResource(R.string.reports_spoken_seconds, seconds)
    }
}
