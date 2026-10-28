package io.github.strongsand.lshell.ar

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.strongsand.lshell.LocalOrbitMotion
import io.github.strongsand.lshell.R
import kotlin.math.hypot

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ArVisualControls(names: Boolean, trails: Boolean, reduced: Boolean,
    onNames: () -> Unit, onTrails: () -> Unit, onReduced: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(names, onNames, label = { Text(stringResource(R.string.ar_link_names)) })
        if (io.github.strongsand.lshell.CandidatePublicationPolicy.ESTIMATED_AR_LINK)
            FilterChip(trails, onTrails, label = { Text(stringResource(R.string.ar_link_trails)) })
        FilterChip(reduced, onReduced, label = { Text(stringResource(R.string.ar_link_reduce_effects)) })
    }
}

@Composable
internal fun ArSatelliteDetails(satellite: SateliteRA, candidate: ArLinkCandidate?, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(satellite.nome) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ar_link_identity, satellite.catalogNumber ?: stringResource(R.string.ar_link_unknown)))
            Text(generationLabel(satellite.classification.generation))
            Text(stringResource(R.string.ar_link_angles, satellite.azimute.toInt(), satellite.elevacao.toInt()))
            if (candidate != null) Text(stringResource(R.string.ar_link_primary))
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } })
}

/** Transparent tap observer. A missed marker does not consume gestures used for calibration. */
@Composable
internal fun ArSatelliteTapLayer(satellites: List<SateliteRA>, azimuth: Float, elevation: Float, roll: Float,
    onSelect: (String) -> Unit) {
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val radius = with(LocalDensity.current) { 24.dp.toPx() }
    val latestSatellites by rememberUpdatedState(satellites)
    val latestPose by rememberUpdatedState(Triple(azimuth, elevation, roll))
    val select by rememberUpdatedState(onSelect)
    Box(Modifier.fillMaxSize().onSizeChanged { viewport = it }.pointerInput(radius) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val pose = latestPose
            val hit = latestSatellites.mapNotNull { sat ->
                projectArSatellite(viewport.width.toFloat(), viewport.height.toFloat(), pose.first, pose.second,
                    pose.third, sat.azimute, sat.elevacao)?.let { point ->
                    sat.orbitKey to hypot(point.x - down.position.x, point.y - down.position.y)
                }
            }.minByOrNull { it.second }?.takeIf { it.second <= radius }?.first
            val up = waitForUpOrCancellation()
            if (hit != null && up != null && (up.position - down.position).getDistance() < viewConfiguration.touchSlop &&
                latestSatellites.any { it.orbitKey == hit }) {
                up.consume()
                select(hit)
            }
        }
    })
}

private data class ArCandidateDrawing(val center: Offset, val strength: Float, val trail: Path, val rank: Int)

/** One small drawing layer owns the pulse; no orbital calculation or parent recomposition per pulse frame. */
@Composable
internal fun ArCandidateOverlay(
    estimate: ArLinkEstimate, displayedKeys: Set<String>, azimuth: Float, elevation: Float, roll: Float,
    names: Boolean, trails: Boolean, reducedEffects: Boolean, active: Boolean
) {
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val palette = MaterialTheme.colorScheme
    // Bright thematic ink over dark camera imagery, dark backing over bright imagery.
    val color = if (palette.primary.luminance() >= palette.inversePrimary.luminance())
        palette.primary else palette.inversePrimary
    val backing = MaterialTheme.colorScheme.surfaceContainer
    val ink = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val label = stringResource(R.string.ar_link_primary)
    val labelLayout = remember(label, ink, backing, density, viewport.width) {
        textMeasurer.measure(label, style = TextStyle(color = ink, background = backing.copy(alpha = .96f),
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold), maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            constraints = androidx.compose.ui.unit.Constraints(maxWidth = (viewport.width - with(density) { 24.dp.roundToPx() }).coerceAtLeast(1)))
    }
    val drawings = remember(estimate, displayedKeys, viewport, azimuth, elevation, roll, trails) {
        estimate.candidates.mapIndexedNotNull { index, candidate ->
            val sat = candidate.satellite
            if (sat.orbitKey !in displayedKeys) return@mapIndexedNotNull null
            val point = projectArSatellite(viewport.width.toFloat(), viewport.height.toFloat(), azimuth, elevation, roll,
                sat.azimute, sat.elevacao) ?: return@mapIndexedNotNull null
            // Off-screen halos do not keep a pulse clock alive.
            if (point.x !in 0f..viewport.width.toFloat() || point.y !in 0f..viewport.height.toFloat()) return@mapIndexedNotNull null
            val strength = candidateVisualStrength(estimate.confidence, index)
            val path = Path()
            if (trails) {
                var drawing = false
                candidate.forecast?.points?.takeWhile { it.secondsAhead <= 60 }?.forEach { future ->
                    val p = projectArSatellite(viewport.width.toFloat(), viewport.height.toFloat(), azimuth, elevation, roll,
                        future.azimuth, future.elevation)
                    if (p == null) drawing = false else {
                        if (drawing) path.lineTo(p.x, p.y) else path.moveTo(p.x, p.y)
                        drawing = true
                    }
                }
            }
            ArCandidateDrawing(Offset(point.x, point.y), strength, path, index)
        }
    }
    val animate = candidatePulseEnabled(LocalOrbitMotion.current, reducedEffects, active, drawings.isNotEmpty())
    val pulse: State<Float> = if (animate) rememberInfiniteTransition(label = "estimated candidate aura").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "candidate pulse")
        else rememberUpdatedState(.35f)
    Canvas(Modifier.fillMaxSize().onSizeChanged { viewport = it }.clearAndSetSemantics {}) {
        drawings.forEach { marker ->
            val phase = pulse.value
            val radius = (24f + phase * 7f).dp.toPx()
            if (trails) drawPath(marker.trail, color.copy(alpha = .48f * marker.strength), style = Stroke(1.5.dp.toPx()))
            // Layered translucent circles avoid per-frame blur/shader allocation.
            drawCircle(color.copy(alpha = (.04f + .04f * phase) * marker.strength), radius * 1.9f, marker.center)
            drawCircle(color.copy(alpha = (.07f + .07f * phase) * marker.strength), radius * 1.5f, marker.center)
            drawCircle(color.copy(alpha = (.10f + .10f * phase) * marker.strength), radius * 1.18f, marker.center)
            drawCircle(Color.Black.copy(alpha = .65f), radius, marker.center, style = Stroke(5.dp.toPx()))
            drawCircle(color.copy(alpha = (.70f + .25f * phase) * marker.strength), radius, marker.center,
                style = Stroke(2.5.dp.toPx()))
            if (names && marker.rank == 0) drawText(labelLayout, topLeft = Offset(
                (marker.center.x - labelLayout.size.width / 2f).coerceIn(0f, (size.width - labelLayout.size.width).coerceAtLeast(0f)),
                (marker.center.y + 62.dp.toPx()).coerceIn(0f, (size.height - labelLayout.size.height).coerceAtLeast(0f))))
        }
    }
}
