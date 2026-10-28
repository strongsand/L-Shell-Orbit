package io.github.strongsand.lshell

import androidx.compose.ui.res.stringResource

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.State
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.strongsand.lshell.core.network.grpc.DishyGrpcClient
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin


internal class ObstructionMapState {
    var map by mutableStateOf<DishyGrpcClient.ObstructionMapReading?>(null)
    var error by mutableStateOf<Throwable?>(null)
    var loading by mutableStateOf(true)
    var refreshRequest by mutableIntStateOf(0)
}

@Composable
internal fun rememberObstructionMapState(): ObstructionMapState {
    val state = remember { ObstructionMapState() }
    val client = remember { DishyGrpcClient() }
    DisposableEffect(client) { onDispose { client.close() } }
    LaunchedEffect(state.refreshRequest) {
        state.loading = true
        state.error = null
        client.fetchObstructionMap().fold(
            onSuccess = { state.map = it; state.error = null },
            onFailure = { state.error = it }
        )
        state.loading = false
    }
    return state
}

@Composable
fun ObstructionMapCard(boresightAzimuthDegrees: Float) {
    ObstructionMapCard(boresightAzimuthDegrees, rememberObstructionMapState())
}

@Composable
internal fun ObstructionMapCard(boresightAzimuthDegrees: Float, state: ObstructionMapState) {
    val motion = LocalOrbitMotion.current
    val map = state.map
    val error = state.error
    val loading = state.loading
    val view = LocalView.current
    var visible by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    val reveal = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(visible, map != null, motion) {
        if (!motion) { reveal.snapTo(1f); revealed = true }
        else if (visible && map != null && !revealed) {
            reveal.animateTo(1f, tween(950, easing = FastOutSlowInEasing)); revealed = true
        }
    }

    Card(
        Modifier.fillMaxWidth().onGloballyPositioned {
            val bounds = it.boundsInWindow()
            visible = bounds.bottom > 0f && bounds.top < view.height && bounds.right > 0f && bounds.left < view.width
        }.animateContentSize(if (motion) spring(dampingRatio = .82f, stiffness = 380f) else snap()),
        shape = DashDesign.section,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            Modifier.padding(DashDesign.inset),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    SectionHeading(stringResource(R.string.ui_obstruction_map), stringResource(R.string.ui_actual_sky_reading_from_the_terminal))
                }
                IconButton(
                    onClick = hapticClick { state.refreshRequest++ },
                    enabled = !loading
                ) {
                    if (loading && map != null) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(OrbitSymbols.Refresh, contentDescription = stringResource(R.string.ui_refresh_map))
                    }
                }
            }

            AnimatedContent(
                targetState = Triple(map, error, loading),
                transitionSpec = {
                    if (motion) fadeIn(tween(160)) togetherWith fadeOut(tween(80))
                    else (EnterTransition.None togetherWith ExitTransition.None).using(SizeTransform { _, _ -> snap() })
                },
                label = "estado do mapa de obstrução"
            ) { (currentMap, currentError, isLoading) ->
                when {
                    currentMap != null -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ObstructionMapContent(currentMap, boresightAzimuthDegrees, reveal.asState())
                        currentError?.let {
                            Text(
                                stringResource(R.string.ui_could_not_refresh_now_the_last_map_remains_visible),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    isLoading -> Box(
                        Modifier.fillMaxWidth().padding(vertical = 56.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
                    else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.ui_map_unavailable), style = MaterialTheme.typography.titleMedium)
                        Text(
                            currentError?.message ?: stringResource(if (currentError != null) R.string.ui_map_read_error else R.string.ui_map_waiting),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ObstructionMapContent(
    map: DishyGrpcClient.ObstructionMapReading,
    boresightAzimuthDegrees: Float, reveal: State<Float>
) {
    val coverage = remember(map) {
        val columnCenter = (map.columns - 1) / 2f
        val rowCenter = (map.rows - 1) / 2f
        val gridRadius = min(columnCenter, rowCenter).coerceAtLeast(1f)
        var skyCells = 0
        var surveyedCells = 0
        map.usableFractions.forEachIndexed { index, usable ->
            val row = index / map.columns
            val column = index % map.columns
            val east = (column - columnCenter) / gridRadius
            val north = (rowCenter - row) / gridRadius
            if (east * east + north * north <= 1f) {
                skyCells++
                if (usable >= 0f) surveyedCells++
            }
        }
        surveyedCells * 100f / skyCells.coerceAtLeast(1)
    }
    val surveyedPercent = coverage
    val description = androidx.compose.ui.res.stringResource(R.string.editorial_map_description, formatPercent(surveyedPercent))
    val good = healthColors(HealthTone.GOOD)
    val caution = healthColors(HealthTone.CAUTION)
    val clearColor = good.foreground
    val partialColor = caution.foreground
    val obstructedColor = MaterialTheme.colorScheme.error
    val unmappedColor = MaterialTheme.colorScheme.surfaceVariant
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val compassColor = MaterialTheme.colorScheme.onSurfaceVariant
    val canOrient = map.referenceFrame == DishyGrpcClient.ObstructionMapReading.ReferenceFrame.EARTH ||
        (map.referenceFrame == DishyGrpcClient.ObstructionMapReading.ReferenceFrame.USER_TERMINAL &&
            boresightAzimuthDegrees.isFinite())
    val safeAzimuth = boresightAzimuthDegrees.takeIf { it.isFinite() } ?: 0f
    val cells = remember(map, safeAzimuth) {
        ObstructionRevealPolicy.cells(map.columns, map.rows, map.usableFractions,
            map.referenceFrame == DishyGrpcClient.ObstructionMapReading.ReferenceFrame.USER_TERMINAL, safeAzimuth)
    }


    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .fillMaxWidth()
                .widthIn(max = 420.dp)
                .aspectRatio(1f)
                .semantics { contentDescription = description }
        ) {
            Box(Modifier.fillMaxSize().padding(22.dp).drawWithCache {
                val radius = min(size.width, size.height) / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                val clip = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, radius)) }
                val gridRadius = min((map.columns - 1) / 2f, (map.rows - 1) / 2f).coerceAtLeast(1f)
                val pointRadius = (radius / gridRadius * .58f).coerceAtLeast(1f)
                val colors = mapOf(SkyCellClass.UNREAD to unmappedColor, SkyCellClass.CLEAR to clearColor,
                    SkyCellClass.PARTIAL to partialColor, SkyCellClass.BLOCKED to obstructedColor)
                // At most 64 radial bands per class, batched once when geometry changes.
                val batches = cells.groupBy { it.kind to (it.radius * 63f).toInt().coerceIn(0, 63) }.map { (key, cells) ->
                    Triple(colors.getValue(key.first), cells.maxOf { it.radius },
                        cells.map { Offset(center.x + it.east * radius, center.y - it.north * radius) })
                }
                onDrawBehind {
                    val progress = reveal.value
                    drawCircle(unmappedColor, radius, center)
                    clipPath(clip) {
                        batches.forEach { (color, radial, points) ->
                            val alpha = if (progress >= .9999f) 1f else ObstructionRevealPolicy.alpha(progress, radial)
                            if (alpha > 0f) drawPoints(points, PointMode.Points, color.copy(alpha = color.alpha * alpha),
                                strokeWidth = pointRadius * 2f, cap = StrokeCap.Round)
                        }
                        if (progress > 0f && progress < 1f) drawCircle(outlineColor.copy(alpha = .25f * (1f - progress)),
                            radius * progress, center, style = Stroke(1.5.dp.toPx()))
                    }
                    drawCircle(outlineColor, radius, center, style = Stroke(2.dp.toPx()))
                }
            })
            if (canOrient) {
                CompassLabel(androidx.compose.ui.res.stringResource(R.string.editorial_compass_0), Modifier.align(Alignment.TopCenter), compassColor)
                CompassLabel(androidx.compose.ui.res.stringResource(R.string.editorial_compass_2), Modifier.align(Alignment.CenterEnd), compassColor)
                CompassLabel(androidx.compose.ui.res.stringResource(R.string.editorial_compass_4), Modifier.align(Alignment.BottomCenter), compassColor)
                CompassLabel(androidx.compose.ui.res.stringResource(R.string.editorial_compass_6), Modifier.align(Alignment.CenterStart), compassColor)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MapLegend(stringResource(R.string.editorial_free), clearColor, Modifier.weight(1f))
            MapLegend(stringResource(R.string.editorial_partial), partialColor, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MapLegend(stringResource(R.string.editorial_blocked), obstructedColor, Modifier.weight(1f))
            MapLegend(stringResource(R.string.ui_not_surveyed), unmappedColor, Modifier.weight(1f))
        }

        Text(
            stringResource(R.string.ui_map_surveyed, formatPercent(surveyedPercent)) +
                (map.maxThetaDegrees?.let {
                    stringResource(R.string.ui_map_horizon, String.format(Locale.getDefault(), "%.0f°", 90f - it))
                } ?: ""),
            style = MaterialTheme.typography.labelLarge
        )
        Text(
            if (canOrient) {
                stringResource(R.string.ui_the_map_becomes_more_complete_as_the_dish_observes_the_sky_north_)
            } else {
                androidx.compose.ui.res.stringResource(R.string.editorial_orientation_missing)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CompassLabel(label: String, modifier: Modifier, color: Color) {
    Text(label, modifier.padding(3.dp), color = color, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun MapLegend(label: String, color: Color, modifier: Modifier = Modifier) {
    Surface(modifier, shape = DashDesign.pill, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Surface(Modifier.size(10.dp), shape = DashDesign.cookie, color = color) {}
            Text(label, style = MaterialTheme.typography.labelMedium, minLines = 1)
        }
    }
}

private fun formatPercent(value: Float): String =
    String.format(Locale.getDefault(), if (value < 10f) "%.1f%%" else "%.0f%%", value)
