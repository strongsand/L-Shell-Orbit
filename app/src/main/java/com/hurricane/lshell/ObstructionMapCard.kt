package com.hurricane.lshell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.hurricane.lshell.core.network.grpc.DishyGrpcClient
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private const val CLEAR_OBSTRUCTION_FLOOR = 0.005f
private const val PARTIAL_OBSTRUCTION_CEILING = 0.25f

@Composable
fun ObstructionMapCard(boresightAzimuthDegrees: Float) {
    val client = remember { DishyGrpcClient() }
    DisposableEffect(client) { onDispose { client.close() } }
    var map by remember { mutableStateOf<DishyGrpcClient.ObstructionMapReading?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshRequest by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshRequest) {
        loading = true
        error = null
        client.fetchObstructionMap().fold(
            onSuccess = {
                map = it
                error = null
            },
            onFailure = {
                error = it.message ?: "Não foi possível ler o mapa de obstrução."
            }
        )
        loading = false
    }

    Card(
        Modifier.fillMaxWidth().animateContentSize(),
        shape = DashDesign.section,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            Modifier.padding(DashDesign.inset),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    SectionHeading("Mapa de obstrução", "Leitura real do céu feita pelo terminal")
                }
                IconButton(
                    onClick = hapticClick { refreshRequest++ },
                    enabled = !loading
                ) {
                    if (loading && map != null) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Atualizar mapa")
                    }
                }
            }

            AnimatedContent(
                targetState = Triple(map, error, loading),
                label = "estado do mapa de obstrução"
            ) { (currentMap, currentError, isLoading) ->
                when {
                    currentMap != null -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ObstructionMapContent(currentMap, boresightAzimuthDegrees)
                        currentError?.let {
                            Text(
                                "Não foi possível atualizar agora. O último mapa continua visível.",
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
                        Text("Mapa indisponível", style = MaterialTheme.typography.titleMedium)
                        Text(
                            currentError ?: "A antena ainda não forneceu dados do céu.",
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
    boresightAzimuthDegrees: Float
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
    val description = "Mapa de obstrução com ${formatPercent(surveyedPercent)} do céu analisado"
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

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .fillMaxWidth()
                .widthIn(max = 420.dp)
                .aspectRatio(1f)
                .semantics { contentDescription = description }
        ) {
            Canvas(Modifier.fillMaxSize().padding(22.dp)) {
                val radius = min(size.width, size.height) / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                val clip = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, radius)) }
                drawCircle(unmappedColor, radius, center)
                clipPath(clip) {
                    val columnCenter = (map.columns - 1) / 2f
                    val rowCenter = (map.rows - 1) / 2f
                    val gridRadius = min(columnCenter, rowCenter).coerceAtLeast(1f)
                    val pointRadius = (radius / gridRadius * 0.58f).coerceAtLeast(1f)
                    val azimuth = safeAzimuth * PI.toFloat() / 180f
                    val sine = sin(azimuth)
                    val cosine = cos(azimuth)
                    map.usableFractions.forEachIndexed { index, usable ->
                        val row = index / map.columns
                        val column = index % map.columns
                        val eastGrid = (column - columnCenter) / gridRadius
                        val northGrid = (rowCenter - row) / gridRadius
                        if (eastGrid * eastGrid + northGrid * northGrid > 1.02f) return@forEachIndexed
                        val (east, north) = when (map.referenceFrame) {
                            DishyGrpcClient.ObstructionMapReading.ReferenceFrame.USER_TERMINAL -> {
                                val forward = -northGrid
                                val right = eastGrid
                                (right * cosine + forward * sine) to
                                    (-right * sine + forward * cosine)
                            }
                            else -> eastGrid to northGrid
                        }
                        val obstruction = 1f - usable
                        val color = when {
                            usable < 0f -> unmappedColor
                            obstruction <= CLEAR_OBSTRUCTION_FLOOR -> clearColor
                            obstruction <= PARTIAL_OBSTRUCTION_CEILING -> partialColor
                            else -> obstructedColor
                        }
                        drawCircle(
                            color = color,
                            radius = pointRadius,
                            center = Offset(center.x + east * radius, center.y - north * radius)
                        )
                    }
                }
                drawCircle(outlineColor, radius, center, style = Stroke(width = 2.dp.toPx()))
            }
            if (canOrient) {
                CompassLabel("N", Modifier.align(Alignment.TopCenter), compassColor)
                CompassLabel("L", Modifier.align(Alignment.CenterEnd), compassColor)
                CompassLabel("S", Modifier.align(Alignment.BottomCenter), compassColor)
                CompassLabel("O", Modifier.align(Alignment.CenterStart), compassColor)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MapLegend("Livre", clearColor, Modifier.weight(1f))
            MapLegend("Parcial", partialColor, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MapLegend("Obstruído", obstructedColor, Modifier.weight(1f))
            MapLegend("Não lido", unmappedColor, Modifier.weight(1f))
        }

        Text(
            buildString {
                append(formatPercent(surveyedPercent)).append(" do mapa analisado")
                map.maxThetaDegrees?.let {
                    append(" · horizonte a partir de ")
                    append(String.format(Locale.getDefault(), "%.0f°", 90f - it))
                }
            },
            style = MaterialTheme.typography.labelLarge
        )
        Text(
            if (canOrient) {
                "O mapa fica mais completo conforme a antena observa o céu. Norte permanece no topo."
            } else {
                "O firmware não informou a orientação do mapa. As células são exibidas na ordem recebida."
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
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

private fun formatPercent(value: Float): String =
    String.format(Locale.getDefault(), if (value < 10f) "%.1f%%" else "%.0f%%", value)
