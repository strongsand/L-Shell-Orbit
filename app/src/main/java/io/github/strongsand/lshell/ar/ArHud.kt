package io.github.strongsand.lshell.ar

import io.github.strongsand.lshell.OrbitSymbols

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.strongsand.lshell.DashDesign
import io.github.strongsand.lshell.DashFeedback
import io.github.strongsand.lshell.hapticClick
import io.github.strongsand.lshell.pressMotion
import io.github.strongsand.lshell.R
import androidx.compose.ui.res.stringResource

@Composable
internal fun arColorScheme(): ColorScheme = MaterialTheme.colorScheme

@Composable
internal fun ArIconButton(icon: ImageVector, label: String, onClick: () -> Unit,
                          modifier: Modifier = Modifier, feedback: DashFeedback = DashFeedback.TAP) {
    val interaction = remember { MutableInteractionSource() }
    FilledTonalIconButton(onClick = hapticClick(feedback, onClick), interactionSource = interaction,
        modifier = modifier.size(48.dp).pressMotion(interaction),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
            contentColor = MaterialTheme.colorScheme.onSurface)) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(23.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ArHud(
    azimuth: Int, elevation: Int, satelliteStatus: String, gpsStatus: String, sensor: String,
    gpsQuality: ArGpsQuality, hasCompass: Boolean, catalogReady: Boolean,
    overlayEnabled: Boolean, overlayStatus: String, onToggleOverlay: () -> Unit,
    onlyDishView: Boolean, dishOrientationAvailable: Boolean, onToggleDishView: () -> Unit,
    generationFilter: SatelliteGeneration?, onGenerationSelected: (SatelliteGeneration?) -> Unit,
    maxDistanceKm: Int, onDistanceSelected: (Int) -> Unit,
    phase: FaseCalibracao, bottomInset: Dp, onExit: () -> Unit, onHide: () -> Unit,
    onRefresh: () -> Unit, onCalibrate: () -> Unit, onCancelCalibration: () -> Unit,
    visualControls: @Composable () -> Unit = {},
    onInspectFocused: (() -> Unit)? = null, inspectFocusedLabel: String? = null
) {
    var details by rememberSaveable { mutableStateOf(false) }
    var distanceMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var generationMenuExpanded by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().padding(bottom = bottomInset)
        .padding(horizontal = 12.dp, vertical = 8.dp)) {
        val availableHeight = maxHeight
        val panelHeight = (availableHeight * 0.42f).coerceAtMost(360.dp)
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().align(Alignment.TopCenter),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = DashDesign.pill, color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f)) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArIconButton(OrbitSymbols.ArrowBack, androidx.compose.ui.res.stringResource(R.string.editorial_exit_ar), onExit)
                    Column(Modifier.weight(1f)) {
                        Text(androidx.compose.ui.res.stringResource(R.string.editorial_sky), style = MaterialTheme.typography.titleMedium)
                    }
                    ArIconButton(OrbitSymbols.Fullscreen, androidx.compose.ui.res.stringResource(R.string.editorial_hide_ar), onHide, feedback = DashFeedback.TOGGLE_OFF)
                }
            }
            Column(Modifier.fillMaxWidth().heightIn(max = panelHeight).clip(DashDesign.section)
                .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = DashDesign.section, color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f)) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(if (catalogReady) satelliteStatus else androidx.compose.ui.res.stringResource(R.string.editorial_catalog),
                                    style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val detailsInteraction = remember { MutableInteractionSource() }
                            ArGpsQualityChip(gpsQuality, onClick = { details = !details })
                            val detailsState = stringResource(if (details) R.string.editorial_details_less else R.string.editorial_details)
                            AssistChip(onClick = hapticClick { details = !details },
                                interactionSource = detailsInteraction, modifier = Modifier.pressMotion(detailsInteraction, 0.96f)
                                    .semantics { stateDescription = detailsState },
                                label = { Text(if (details) androidx.compose.ui.res.stringResource(R.string.editorial_details_less) else androidx.compose.ui.res.stringResource(R.string.editorial_details)) },
                                trailingIcon = { Icon(if (details) OrbitSymbols.ExpandLess else OrbitSymbols.ExpandMore, null) })
                        }
                        if (onInspectFocused != null && inspectFocusedLabel != null) {
                            TextButton(onClick = onInspectFocused, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(inspectFocusedLabel)
                            }
                        }
                        if (details) {
                            HorizontalDivider(Modifier.padding(vertical = 8.dp),
                                color = MaterialTheme.colorScheme.outlineVariant)
                            visualControls()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.editorial_estimated_positions),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Az $azimuth° · El $elevation°", style = MaterialTheme.typography.bodySmall)
                                }
                                ArIconButton(OrbitSymbols.Refresh, stringResource(R.string.editorial_refresh_catalog), onRefresh)
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                val filterInteraction = remember { MutableInteractionSource() }
                                FilterChip(selected = overlayEnabled, onClick = hapticClick(action = onToggleOverlay),
                                    label = { Text(stringResource(R.string.overlay_title)) },
                                    leadingIcon = { Icon(OrbitSymbols.Layers, null, Modifier.size(18.dp)) })
                                if (overlayEnabled) Text(overlayStatus, style = MaterialTheme.typography.labelSmall)
                                FilterChip(selected = onlyDishView,
                                    onClick = hapticClick(if (onlyDishView) DashFeedback.TOGGLE_OFF else DashFeedback.TOGGLE_ON, onToggleDishView),
                                    enabled = dishOrientationAvailable,
                                    interactionSource = filterInteraction,
                                    modifier = Modifier.pressMotion(filterInteraction, 0.96f),
                                    label = { Text(androidx.compose.ui.res.stringResource(R.string.editorial_estimated_fov)) },
                                    leadingIcon = { Icon(OrbitSymbols.Visibility, null, Modifier.size(18.dp)) })
                                Box {
                                    AssistChip(onClick = hapticClick { generationMenuExpanded = true },
                                        label = { Text(generationFilter?.let { generationLabel(it) }
                                            ?: stringResource(R.string.satellite_generation_all)) },
                                        leadingIcon = { Icon(OrbitSymbols.SatelliteAlt, null, Modifier.size(18.dp)) },
                                        trailingIcon = { Icon(OrbitSymbols.ArrowDropDown, null, Modifier.size(18.dp)) })
                                    DropdownMenu(expanded = generationMenuExpanded,
                                        onDismissRequest = { generationMenuExpanded = false }) {
                                        (listOf<SatelliteGeneration?>(null) + SatelliteGeneration.entries).forEach { generation ->
                                            DropdownMenuItem(text = { Text(generation?.let { generationLabel(it) }
                                                ?: stringResource(R.string.satellite_generation_all)) },
                                                leadingIcon = { Icon(OrbitSymbols.SatelliteAlt, null,
                                                    Modifier.size(20.dp), tint = generation?.visualColor(MaterialTheme.colorScheme)
                                                        ?: MaterialTheme.colorScheme.onSurfaceVariant) },
                                                trailingIcon = if (generation == generationFilter)
                                                    { { Icon(OrbitSymbols.Check, null) } } else null,
                                                onClick = hapticClick(DashFeedback.TOGGLE_ON) {
                                                    onGenerationSelected(generation); generationMenuExpanded = false
                                                })
                                        }
                                        Text(stringResource(R.string.satellite_generation_note),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.widthIn(max = 260.dp).padding(12.dp))
                                    }
                                }
                                Box {
                                    val distanceInteraction = remember { MutableInteractionSource() }
                                    AssistChip(
                                        onClick = hapticClick { distanceMenuExpanded = true },
                                        interactionSource = distanceInteraction,
                                        modifier = Modifier.pressMotion(distanceInteraction, 0.96f),
                                        label = { Text(if (maxDistanceKm == 0) androidx.compose.ui.res.stringResource(R.string.editorial_no_distance_limit) else androidx.compose.ui.res.stringResource(R.string.editorial_max_distance, maxDistanceKm)) },
                                        leadingIcon = { Icon(OrbitSymbols.Tune, null, Modifier.size(18.dp)) },
                                        trailingIcon = { Icon(OrbitSymbols.ArrowDropDown, null, Modifier.size(18.dp)) }
                                    )
                                    DropdownMenu(
                                        expanded = distanceMenuExpanded,
                                        onDismissRequest = { distanceMenuExpanded = false }
                                    ) {
                                        Text(
                                            androidx.compose.ui.res.stringResource(R.string.editorial_satellite_distance),
                                            style = MaterialTheme.typography.labelLarge,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                        )
                                        listOf(500, 750, 1_000, 1_500, 2_000, 0).forEach { distance ->
                                            val selected = distance == maxDistanceKm
                                            DropdownMenuItem(
                                                text = { Text(if (distance == 0) androidx.compose.ui.res.stringResource(R.string.editorial_no_distance_limit) else androidx.compose.ui.res.stringResource(R.string.editorial_max_distance, distance)) },
                                                leadingIcon = if (selected) {
                                                    { Icon(OrbitSymbols.Check, contentDescription = null) }
                                                } else null,
                                                onClick = hapticClick(DashFeedback.TOGGLE_ON) {
                                                    onDistanceSelected(distance)
                                                    distanceMenuExpanded = false
                                                }
                                            )
                                        }
                                        Text(
                                            androidx.compose.ui.res.stringResource(R.string.editorial_distance_filter_note),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.widthIn(max = 260.dp).padding(horizontal = 12.dp, vertical = 8.dp)
                                        )
                                    }
                                }
                            }
                            AnimatedVisibility(onlyDishView, enter = fadeIn(), exit = fadeOut()) {
                                Text(androidx.compose.ui.res.stringResource(R.string.editorial_estimated_fov_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (!dishOrientationAvailable && catalogReady) {
                                Text(androidx.compose.ui.res.stringResource(R.string.editorial_orientation_filter_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (details) {
                            Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(gpsStatus, style = MaterialTheme.typography.bodySmall)
                                Text(sensor, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(satelliteStatus, style = MaterialTheme.typography.bodySmall)
                            }
                        } else if (!catalogReady) {
                            Text(satelliteStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (!hasCompass && (details || phase !is FaseCalibracao.Desativada))
                    CalibrationPanel(phase, onCalibrate, onCancelCalibration)
            }
        }
    }
}

@Composable
private fun CalibrationPanel(phase: FaseCalibracao, onStart: () -> Unit, onCancel: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Surface(shape = DashDesign.hero, color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f)) {
        Column(Modifier.padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(OrbitSymbols.Explore, null, tint = MaterialTheme.colorScheme.primary)
                Text(androidx.compose.ui.res.stringResource(R.string.editorial_gps_heading), style = MaterialTheme.typography.titleMedium)
            }
            when (phase) {
                FaseCalibracao.Desativada -> {
                    Text(androidx.compose.ui.res.stringResource(R.string.editorial_gps_heading_help),
                        style = MaterialTheme.typography.bodySmall)
                    Button(onClick = hapticClick(DashFeedback.SPECIAL, onStart), interactionSource = interaction,
                        modifier = Modifier.pressMotion(interaction, 0.97f)) { Text(androidx.compose.ui.res.stringResource(R.string.editorial_calibrate_heading)) }
                }
                FaseCalibracao.AguardandoGps -> {
                    Text(androidx.compose.ui.res.stringResource(R.string.editorial_gps_heading_wait), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = hapticClick(action = onCancel)) { Text(androidx.compose.ui.res.stringResource(R.string.editorial_cancel)) }
                }
                is FaseCalibracao.Caminhando -> {
                    Text(androidx.compose.ui.res.stringResource(R.string.editorial_gps_walk), style = MaterialTheme.typography.bodySmall)
                    val progress by animateFloatAsState((phase.distanciaMetros / 20f).coerceIn(0f, 1f),
                        spring(dampingRatio = 1f), label = "calibration distance")
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp))
                    Text("${"%.1f".format(phase.distanciaMetros)} / 20 m", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = hapticClick(action = onCancel)) { Text(androidx.compose.ui.res.stringResource(R.string.editorial_cancel)) }
                }
                is FaseCalibracao.Concluida -> {
                    Text(androidx.compose.ui.res.stringResource(R.string.editorial_heading_ready, phase.azimute.toInt()), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        }
    }
}
