package io.github.strongsand.lshell.ar

import android.os.Build

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.strongsand.lshell.DashDesign
import io.github.strongsand.lshell.DashFeedback
import io.github.strongsand.lshell.hapticClick
import io.github.strongsand.lshell.pressMotion

@Composable
internal fun arColorScheme(): ColorScheme =
    if (Build.VERSION.SDK_INT >= 31) dynamicDarkColorScheme(LocalContext.current) else darkColorScheme()

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
    hasLocation: Boolean, hasCompass: Boolean, catalogReady: Boolean,
    onlyDishView: Boolean, dishOrientationAvailable: Boolean, onToggleDishView: () -> Unit,
    maxDistanceKm: Int, onDistanceSelected: (Int) -> Unit,
    phase: FaseCalibracao, bottomInset: Dp, onExit: () -> Unit, onHide: () -> Unit,
    onRefresh: () -> Unit, onCalibrate: () -> Unit, onCancelCalibration: () -> Unit
) {
    var details by rememberSaveable { mutableStateOf(false) }
    var distanceMenuExpanded by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().padding(bottom = bottomInset)
        .padding(horizontal = 12.dp, vertical = 8.dp)) {
        val compact = maxHeight / LocalDensity.current.fontScale < 450.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = DashDesign.pill, color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f)) {
                    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ArIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Sair da AR", onExit)
                        Column(Modifier.weight(1f)) {
                            Text("Céu em AR", style = MaterialTheme.typography.titleMedium)
                            Text("Posições estimadas", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        ArIconButton(Icons.Rounded.Fullscreen, "Ocultar interface", onHide, feedback = DashFeedback.TOGGLE_OFF)
                    }
                }
                Surface(shape = DashDesign.section, color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f)) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(if (catalogReady) satelliteStatus else "Catálogo de satélites",
                                    style = MaterialTheme.typography.labelLarge)
                                Text("Az $azimuth° · El $elevation°", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            ArIconButton(Icons.Rounded.Refresh, "Atualizar catálogo", onRefresh)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val gpsInteraction = remember { MutableInteractionSource() }
                            val detailsInteraction = remember { MutableInteractionSource() }
                            val filterInteraction = remember { MutableInteractionSource() }
                            AssistChip(onClick = hapticClick { details = !details },
                                interactionSource = gpsInteraction, modifier = Modifier.pressMotion(gpsInteraction, 0.96f),
                                label = { Text(if (hasLocation) "GPS disponível" else "Buscando GPS") },
                                leadingIcon = { Icon(Icons.Rounded.MyLocation, null, Modifier.size(16.dp)) })
                            AssistChip(onClick = hapticClick { details = !details },
                                interactionSource = detailsInteraction, modifier = Modifier.pressMotion(detailsInteraction, 0.96f),
                                label = { Text(if (details) "Menos detalhes" else "Detalhes") },
                                trailingIcon = { Icon(if (details) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null) })
                            FilterChip(selected = onlyDishView,
                                onClick = hapticClick(if (onlyDishView) DashFeedback.TOGGLE_OFF else DashFeedback.TOGGLE_ON, onToggleDishView),
                                enabled = dishOrientationAvailable,
                                interactionSource = filterInteraction,
                                modifier = Modifier.pressMotion(filterInteraction, 0.96f),
                                label = { Text("Visíveis pela antena") },
                                leadingIcon = { Icon(Icons.Rounded.Visibility, null, Modifier.size(18.dp)) })
                            Box {
                                val distanceInteraction = remember { MutableInteractionSource() }
                                AssistChip(
                                    onClick = hapticClick { distanceMenuExpanded = true },
                                    interactionSource = distanceInteraction,
                                    modifier = Modifier.pressMotion(distanceInteraction, 0.96f),
                                    label = { Text(if (maxDistanceKm == 0) "Sem limite" else "Até $maxDistanceKm km") },
                                    leadingIcon = { Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp)) },
                                    trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(18.dp)) }
                                )
                                DropdownMenu(
                                    expanded = distanceMenuExpanded,
                                    onDismissRequest = { distanceMenuExpanded = false }
                                ) {
                                    Text(
                                        "Distância dos satélites",
                                        style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                    listOf(500, 750, 1_000, 1_500, 2_000, 0).forEach { distance ->
                                        val selected = distance == maxDistanceKm
                                        DropdownMenuItem(
                                            text = { Text(if (distance == 0) "Sem limite" else "Até $distance km") },
                                            leadingIcon = if (selected) {
                                                { Icon(Icons.Rounded.Check, contentDescription = null) }
                                            } else null,
                                            onClick = hapticClick(DashFeedback.TOGGLE_ON) {
                                                onDistanceSelected(distance)
                                                distanceMenuExpanded = false
                                            }
                                        )
                                    }
                                    Text(
                                        "Oculta satélites muito distantes para deixar o AR mais limpo.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.widthIn(max = 260.dp).padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                        AnimatedVisibility(onlyDishView, enter = fadeIn(), exit = fadeOut()) {
                            Text("Campo de visão estimado pela direção da antena; não indica conexão ativa.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!dishOrientationAvailable && catalogReady) {
                            Text("Conecte à antena para filtrar pela direção dela.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (details) {
                            Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(gpsStatus, style = MaterialTheme.typography.bodySmall)
                                Text(sensor, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(satelliteStatus, style = MaterialTheme.typography.bodySmall)
                            }
                        } else if (!catalogReady) {
                            Text(if (satelliteStatus.startsWith("Catálogo indisponível")) "Catálogo indisponível. Veja detalhes ou tente atualizar."
                                else satelliteStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (compact && !hasCompass) CalibrationPanel(phase, onCalibrate, onCancelCalibration)
            }
            if (!compact && !hasCompass) CalibrationPanel(phase, onCalibrate, onCancelCalibration)
        }
    }
}

@Composable
private fun CalibrationPanel(phase: FaseCalibracao, onStart: () -> Unit, onCancel: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Surface(shape = DashDesign.hero, color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f)) {
        Column(Modifier.padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Explore, null, tint = MaterialTheme.colorScheme.primary)
                Text("Orientação por GPS", style = MaterialTheme.typography.titleMedium)
            }
            when (phase) {
                FaseCalibracao.Desativada -> {
                    Text("Sem bússola? Caminhe 20 m com o celular em pé, apontado para a caminhada. Ou deslize a câmera para ajustar o rumo.",
                        style = MaterialTheme.typography.bodySmall)
                    Button(onClick = hapticClick(DashFeedback.SPECIAL, onStart), interactionSource = interaction,
                        modifier = Modifier.pressMotion(interaction, 0.97f)) { Text("Calibrar rumo") }
                }
                FaseCalibracao.AguardandoGps -> {
                    Text("Aguardando GPS com boa precisão…", style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = hapticClick(action = onCancel)) { Text("Cancelar") }
                }
                is FaseCalibracao.Caminhando -> {
                    Text("Siga em frente com a câmera voltada para a caminhada.", style = MaterialTheme.typography.bodySmall)
                    val progress by animateFloatAsState((phase.distanciaMetros / 20f).coerceIn(0f, 1f),
                        spring(dampingRatio = 1f), label = "calibration distance")
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp))
                    Text("${"%.1f".format(phase.distanciaMetros)} / 20 m", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = hapticClick(action = onCancel)) { Text("Cancelar") }
                }
                is FaseCalibracao.Concluida -> {
                    Text("Rumo calibrado · ${phase.azimute.toInt()}°", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        }
    }
}
