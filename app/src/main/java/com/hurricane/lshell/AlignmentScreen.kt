package com.hurricane.lshell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hurricane.lshell.core.network.grpc.DishyGrpcClient
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private enum class Axis { ROTATION, TILT, NEAR, DONE, SKIPPED }
private const val ROTATION_ALIGNED_ENTER = 5f
private const val ROTATION_ALIGNED_EXIT = 7f
private const val TILT_ALIGNED_ENTER = 3f
private const val TILT_ALIGNED_EXIT = 4.5f
private const val ROTATION_NEAR = 12f
private const val TILT_NEAR = 7.5f
private fun azDelta(target: Float, current: Float) = ((target - current + 540f) % 360f) - 180f
private fun deg(value: Float) = String.format(Locale.getDefault(), "%.1f°", value)

@Composable
fun AlignmentScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val client = remember { DishyGrpcClient() }
    var reading by remember { mutableStateOf<DishyGrpcClient.AlignmentReading?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(client) { onDispose { client.close() } }
    LaunchedEffect(client) {
        while (true) {
            client.fetchAlignment().fold(
                onSuccess = { reading = it; error = null },
                onFailure = { error = it.message ?: "Sem leitura da antena" }
            )
            delay(2_000)
        }
    }
    val data = reading
    val rotationError = data?.let { azDelta(it.desiredAzimuth, it.azimuth) } ?: 0f
    val tiltError = data?.let { it.desiredElevation - it.elevation } ?: 0f
    var tiltSkipped by remember { mutableStateOf(false) }
    var aligned by remember { mutableStateOf(false) }
    LaunchedEffect(data, rotationError, tiltError) {
        if (data == null) {
            aligned = false
            return@LaunchedEffect
        }
        val enters = abs(rotationError) <= ROTATION_ALIGNED_ENTER &&
            abs(tiltError) <= TILT_ALIGNED_ENTER
        val exits = abs(rotationError) > ROTATION_ALIGNED_EXIT ||
            abs(tiltError) > TILT_ALIGNED_EXIT
        when {
            !aligned && enters -> {
                // A stable reading avoids completing the step on a single noisy sample.
                delay(700)
                aligned = true
            }
            aligned && exits -> {
                // Wider exit limits prevent flicker around the accepted zone.
                delay(900)
                aligned = false
            }
        }
    }
    val nearTarget = data != null && abs(rotationError) <= ROTATION_NEAR &&
        abs(tiltError) <= TILT_NEAR
    // Visual guidance only; the terminal determines actual calibration.
    val axis = when {
        data == null -> null
        aligned -> Axis.DONE
        tiltSkipped && abs(rotationError) <= ROTATION_ALIGNED_EXIT -> Axis.SKIPPED
        nearTarget -> Axis.NEAR
        abs(rotationError) / ROTATION_ALIGNED_ENTER >= abs(tiltError) / TILT_ALIGNED_ENTER &&
            abs(rotationError) > ROTATION_ALIGNED_ENTER -> Axis.ROTATION
        else -> Axis.TILT
    }
    var visibleAxis by remember { mutableStateOf<Axis?>(null) }
    LaunchedEffect(axis) {
        if (visibleAxis != null && axis != visibleAxis) delay(650)
        visibleAxis = axis
    }
    val haptics = rememberDashHaptics()
    var previousVisibleAxis by remember { mutableStateOf<Axis?>(null) }
    LaunchedEffect(visibleAxis) {
        if (visibleAxis == Axis.DONE && previousVisibleAxis != null && previousVisibleAxis != Axis.DONE) {
            haptics.perform(DashFeedback.CONFIRM)
        }
        previousVisibleAxis = visibleAxis
    }
    var showDetails by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxSize()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FilledTonalButton(onClick = hapticClick(action = onBack), modifier = Modifier.align(Alignment.Start)) { Text("Voltar") }
        Spacer(Modifier.height(16.dp))
        Text("Alinhar antena", style = MaterialTheme.typography.headlineMedium)
        Text("Mova devagar e acompanhe o ponteiro.", style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(22.dp))
        if (error != null) {
            Card(Modifier.fillMaxWidth()) {
                Text("Sem leitura em tempo real: $error", Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(12.dp))
        }
        if (data == null) Text("Aguardando a antena…") else {
            AnimatedContent(visibleAxis, label = "Etapa de alinhamento") { step ->
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(when (step) {
                        Axis.ROTATION -> "Rotação"
                        Axis.TILT -> "Inclinação"
                        Axis.NEAR -> "Próximo do alvo"
                        Axis.DONE -> "Alinhado"
                        Axis.SKIPPED -> "Inclinação pulada"
                        null -> "Lendo antena"
                    }, style = MaterialTheme.typography.titleLarge)
                    when (step) {
                        Axis.ROTATION -> CompassDiagram(data.azimuth, data.desiredAzimuth)
                        Axis.TILT -> TiltDiagram(data.elevation, data.desiredElevation)
                        Axis.NEAR -> {
                            NearTargetIndicator()
                            Text("Rotação e inclinação estão próximas dos valores desejados.",
                                textAlign = TextAlign.Center)
                        }
                        Axis.DONE -> {
                            CompletedAlignmentIndicator()
                            Text("A antena está dentro da faixa de alinhamento.",
                                textAlign = TextAlign.Center)
                        }
                        Axis.SKIPPED -> {
                            Text("A inclinação foi pulada. A antena pode precisar de ajuste para melhorar o sinal.",
                                textAlign = TextAlign.Center)
                            TextButton(onClick = hapticClick { tiltSkipped = false }) { Text("Retomar inclinação") }
                        }
                        null -> Unit
                    }
                    Text(when (step) {
                        Axis.ROTATION -> if (rotationError > 0) "Gire devagar no sentido horário" else "Gire devagar no sentido anti-horário"
                        Axis.TILT -> if (tiltError > 0) "Eleve a antena um pouco" else "Abaixe a antena um pouco"
                        Axis.NEAR -> "Faça movimentos pequenos para entrar na faixa ideal."
                        Axis.DONE -> "Confira a qualidade do sinal antes de terminar."
                        Axis.SKIPPED -> "Confira a qualidade do sinal antes de terminar."
                        null -> ""
                    }, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    if (step == Axis.ROTATION || step == Axis.TILT) {
                        val remaining = abs(if (step == Axis.ROTATION) rotationError else tiltError)
                        Text("Faltam " + deg(remaining), style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            FilledTonalButton(onClick = hapticClick(if (showDetails) DashFeedback.TOGGLE_OFF else DashFeedback.TOGGLE_ON) { showDetails = !showDetails }) {
                Text(if (showDetails) "Ocultar leituras" else "Ver leituras")
            }
            if (visibleAxis == Axis.TILT) {
                Spacer(Modifier.height(12.dp))
                val skipInteraction = remember { MutableInteractionSource() }
                TextButton(onClick = hapticClick { tiltSkipped = true }, interactionSource = skipInteraction,
                    modifier = Modifier.pressMotion(skipInteraction, 0.95f)) { Text("Pular") }
            }
            if (showDetails) {
                Spacer(Modifier.height(8.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Rotação: " + deg(data.azimuth) + " → " + deg(data.desiredAzimuth))
                        Text("Inclinação: " + deg(data.elevation) + " → " + deg(data.desiredElevation))
                        Text("Faixa aceita: ±5° na rotação e ±3° na inclinação, com folga contra oscilações. A antena decide o alinhamento real.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun NearTargetIndicator() {
    val motion = rememberInfiniteTransition(label = "Perto do alvo")
    val lift by motion.animateFloat(0f, 1f,
        infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse),
        label = "Seta se aproximando")
    val liftPx = with(LocalDensity.current) { 4.dp.toPx() }
    Surface(modifier = Modifier.size(108.dp).graphicsLayer { translationY = -lift * liftPx },
        shape = DashDesign.cookie,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.NearMe, contentDescription = "Perto do alvo", modifier = Modifier.size(48.dp))
        }
    }
}

@Composable
private fun CompletedAlignmentIndicator() {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val scale by animateFloatAsState(if (appeared) 1f else 0.72f,
        spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow), label = "Alinhamento concluído")
    Surface(modifier = Modifier.size(108.dp).graphicsLayer { scaleX = scale; scaleY = scale },
        shape = DashDesign.cookie, color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = "Alinhamento concluído",
                modifier = Modifier.size(54.dp))
        }
    }
}

@Composable
private fun CompassDiagram(current: Float, target: Float) {
    val background = MaterialTheme.colorScheme.primaryContainer
    val targetColor = MaterialTheme.colorScheme.onPrimaryContainer
    val dishBorder = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    val phase = movingWavePhase()
    val settled by animateFloatAsState(
        if (abs(azDelta(target, current)) <= ROTATION_ALIGNED_ENTER) 1f else 0f,
        spring(stiffness = Spring.StiffnessLow), label = "Forma alinhada"
    )
    var continuousTurn by remember { mutableFloatStateOf(current) }
    LaunchedEffect(current) { continuousTurn += azDelta(current, continuousTurn) }
    val turn by animateFloatAsState(continuousTurn, spring(stiffness = Spring.StiffnessLow), label = "Antena girando")
    Box(Modifier.size(288.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val baseRadius = min(size.width, size.height) * 0.40f
            val cookie = Path()
            for (i in 0..180) {
                val angle = i * 2.0 * PI / 180.0
                val lobes = (1f - settled) * cos(9 * angle).toFloat() + settled * cos(4 * angle).toFloat()
                val radius = baseRadius * (1f + 0.085f * lobes)
                val point = center + Offset((cos(angle) * radius).toFloat(), (sin(angle) * radius).toFloat())
                if (i == 0) cookie.moveTo(point.x, point.y) else cookie.lineTo(point.x, point.y)
            }
            cookie.close()
            drawPath(cookie, background)
            drawCircle(Color.White.copy(alpha = 0.17f), radius = baseRadius * 0.72f)

            val wave = Path()
            val targetAngle = target - 90f
            for (i in 0..48) {
                val t = i / 48f
                val angle = Math.toRadians((targetAngle + (t - 0.5f) * 34f).toDouble())
                val radius = baseRadius * 0.82f + 5.dp.toPx() * sin(phase + t * 5f * PI).toFloat()
                val point = center + Offset((cos(angle) * radius).toFloat(), (sin(angle) * radius).toFloat())
                if (i == 0) wave.moveTo(point.x, point.y) else wave.lineTo(point.x, point.y)
            }
            drawPath(wave, targetColor, style = Stroke(width = 5.dp.toPx(),
                cap = StrokeCap.Round, join = StrokeJoin.Round))

            rotate(turn, pivot = center) {
                val dishWidth = 64.dp.toPx()
                val dishHeight = 104.dp.toPx()
                val topLeft = center - Offset(dishWidth / 2f, dishHeight / 2f)
                drawRoundRect(Color(0xFFF9F8FD), topLeft = topLeft,
                    size = Size(dishWidth, dishHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(11.dp.toPx()))
                drawRoundRect(dishBorder, topLeft = topLeft,
                    size = Size(dishWidth, dishHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(11.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx()))
                drawLine(targetColor.copy(alpha = 0.65f),
                    center - Offset(16.dp.toPx(), dishHeight * 0.36f),
                    center + Offset(16.dp.toPx(), -dishHeight * 0.36f),
                    strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        Text("N", Modifier.align(Alignment.TopCenter), style = MaterialTheme.typography.labelLarge)
        Text("NE", Modifier.align(Alignment.TopEnd).padding(top = 35.dp, end = 24.dp), style = MaterialTheme.typography.labelMedium)
        Text("L", Modifier.align(Alignment.CenterEnd), style = MaterialTheme.typography.labelLarge)
        Text("SE", Modifier.align(Alignment.BottomEnd).padding(bottom = 35.dp, end = 24.dp), style = MaterialTheme.typography.labelMedium)
        Text("S", Modifier.align(Alignment.BottomCenter), style = MaterialTheme.typography.labelLarge)
        Text("SO", Modifier.align(Alignment.BottomStart).padding(bottom = 35.dp, start = 24.dp), style = MaterialTheme.typography.labelMedium)
        Text("O", Modifier.align(Alignment.CenterStart), style = MaterialTheme.typography.labelLarge)
        Text("NO", Modifier.align(Alignment.TopStart).padding(top = 35.dp, start = 24.dp), style = MaterialTheme.typography.labelMedium)
    }
    Text("Retângulo: antena  ·  Traço em movimento: alvo",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center)
}

@Composable
private fun TiltDiagram(current: Float, target: Float) {
    val background = MaterialTheme.colorScheme.primaryContainer
    val targetColor = MaterialTheme.colorScheme.onPrimaryContainer
    val phase = movingWavePhase()
    val tilt by animateFloatAsState(current, spring(stiffness = Spring.StiffnessLow), label = "Antena inclinando")
    val settled by animateFloatAsState(
        if (abs(target - current) <= TILT_ALIGNED_ENTER) 1f else 0f,
        spring(stiffness = Spring.StiffnessLow), label = "Fan para pill"
    )
    Canvas(Modifier.size(width = 288.dp, height = 268.dp)) {
        val left = size.width * 0.12f
        val top = size.height * 0.11f
        val side = min(size.width * 0.78f, size.height * 0.78f)
        val corner = side * 0.19f
        // The fan follows the Material shape: two straight sides, a soft
        // quarter-circle edge and rounded joins. The hinge stays inside it.
        val fan = Path().apply {
            moveTo(left, top + corner)
            quadraticTo(left, top, left + corner, top)
            cubicTo(left + side * 0.56f, top, left + side, top + side * 0.42f,
                left + side, top + side - corner)
            quadraticTo(left + side, top + side, left + side - corner, top + side)
            lineTo(left + corner, top + side)
            quadraticTo(left, top + side, left, top + side - corner)
            close()
        }
        drawPath(fan, background.copy(alpha = 1f - settled))
        drawRoundRect(background.copy(alpha = settled),
            topLeft = Offset(left + side * 0.14f, top + side * 0.34f),
            size = Size(side * 0.72f, side * 0.30f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(side * 0.15f))

        // The pointer and the target arc share the same hinge and radius.
        val origin = Offset(left + side * 0.18f, top + side * 0.82f)
        val radius = side * 0.64f

        val wave = Path()
        for (i in 0..48) {
            val t = i / 48f
            val angle = Math.toRadians((-target + (t - 0.5f) * 24f).toDouble())
            val waveRadius = radius + 3.dp.toPx() * sin(phase + t * 5f * PI).toFloat()
            val point = origin + Offset((cos(angle) * waveRadius).toFloat(),
                (sin(angle) * waveRadius).toFloat())
            if (i == 0) wave.moveTo(point.x, point.y) else wave.lineTo(point.x, point.y)
        }
        drawPath(wave, targetColor.copy(alpha = 1f - settled), style = Stroke(width = 5.dp.toPx(),
            cap = StrokeCap.Round, join = StrokeJoin.Round))

        val angle = Math.toRadians((-tilt).toDouble())
        val start = origin + Offset((cos(angle) * 9.dp.toPx()).toFloat(),
            (sin(angle) * 9.dp.toPx()).toFloat())
        val tip = origin + Offset((cos(angle) * radius).toFloat(),
            (sin(angle) * radius).toFloat())
        drawLine(targetColor.copy(alpha = 0.20f * (1f - settled)), start, tip,
            strokeWidth = 21.dp.toPx(), cap = StrokeCap.Round)
        drawLine(Color(0xFFF9F8FD).copy(alpha = 1f - settled), start, tip,
            strokeWidth = 16.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(targetColor.copy(alpha = 0.75f * (1f - settled)), 4.dp.toPx(), origin)

        // Show an explicit completed symbol while the fan settles into its pill.
        val checkCenter = Offset(left + side * 0.50f, top + side * 0.49f)
        val check = Path().apply {
            moveTo(checkCenter.x - side * 0.09f, checkCenter.y)
            lineTo(checkCenter.x - side * 0.02f, checkCenter.y + side * 0.07f)
            lineTo(checkCenter.x + side * 0.11f, checkCenter.y - side * 0.08f)
        }
        drawPath(check, targetColor.copy(alpha = settled),
            style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    Text("Barra branca: antena  ·  Traço em movimento: alvo",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center)
}

@Composable
private fun movingWavePhase(): Float {
    val motion = rememberInfiniteTransition(label = "Alvo em movimento")
    val phase by motion.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
        label = "Onda do alvo"
    )
    return phase
}
