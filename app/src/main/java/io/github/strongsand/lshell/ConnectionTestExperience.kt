package io.github.strongsand.lshell

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/** Derived presentation, never a second execution state or an analytics calculation. */
internal enum class TestOutcome { IDLE, ACTIVE, SUCCESS, NO_MEASUREMENTS, FAILED, INTERRUPTED }
internal object ConnectionTestExperience {
    fun outcome(state: ConnectionTestState): TestOutcome = when {
        state.active -> TestOutcome.ACTIVE
        state.phase == ConnectionTestPhase.FAILED -> TestOutcome.FAILED
        state.phase == ConnectionTestPhase.INTERRUPTED -> TestOutcome.INTERRUPTED
        state.phase == ConnectionTestPhase.COMPLETED -> {
            val measured = if (state.kind == ConnectionTestKind.NORMAL)
                ConnectionTestPresentation.metrics(state) != TestMetrics()
            else state.runs.any { run -> run.failure == null && (
                run.downloadMeasured != false && ConnectionTestPresentation.valid(run.downloadMbps) != null ||
                run.uploadMeasured != false && ConnectionTestPresentation.valid(run.uploadMbps) != null ||
                ConnectionTestPresentation.valid(run.latencyMs) != null) }
            if (measured) TestOutcome.SUCCESS else TestOutcome.NO_MEASUREMENTS
        }
        else -> TestOutcome.IDLE
    }

    fun phaseLabel(state: ConnectionTestState): Int = when (state.phase) {
        ConnectionTestPhase.STARTING -> R.string.test_preparing
        ConnectionTestPhase.BETWEEN_ROUNDS -> R.string.test_between_rounds
        ConnectionTestPhase.RUNNING -> when (state.liveActivity) {
            SpeedTestActivity.DOWNLOAD -> R.string.test_flow_download
            SpeedTestActivity.UPLOAD -> R.string.test_flow_upload
            SpeedTestActivity.UNKNOWN -> R.string.test_running
        }
        ConnectionTestPhase.FAILED -> R.string.test_failed
        ConnectionTestPhase.INTERRUPTED -> R.string.test_interrupted
        ConnectionTestPhase.COMPLETED -> if (outcome(state) == TestOutcome.SUCCESS)
            R.string.test_done else R.string.test_no_result
        else -> R.string.editorial_start_test
    }

    fun progress(state: ConnectionTestState): Float? = ExtremeTestNotificationModel.progress(state)
        ?.let { (total, completed) -> completed.toFloat() / total }

    fun feedback(previous: ConnectionTestState, next: ConnectionTestState): DashFeedback? {
        if (previous.id != next.id || !previous.active || previous.kind != next.kind) return null
        return when {
            next.phase == ConnectionTestPhase.COMPLETED -> if (outcome(next) == TestOutcome.SUCCESS)
                DashFeedback.CONFIRM else DashFeedback.WARNING
            next.phase == ConnectionTestPhase.FAILED -> DashFeedback.WARNING
            next.completed > previous.completed -> if (next.runs.lastOrNull()?.failure != null)
                DashFeedback.WARNING else DashFeedback.TAP
            else -> null
        }
    }
}

/** PendingIntent identity includes kind and execution ID; updates cannot retarget an older tap. */
internal data class ConnectionTestNotificationTarget(val tab: String, val id: Long, val uri: String) {
    companion object {
        fun from(state: ConnectionTestState) = ConnectionTestNotificationTarget(
            if (state.kind == ConnectionTestKind.NORMAL) "SPEED" else "EXTREME", state.id,
            "lshell://connection-test/${state.kind.name.lowercase(java.util.Locale.ROOT)}/${state.id}")
    }
}

@Composable
internal fun Modifier.testContentMotion(): Modifier = animateContentSize(
    OrbitMotion.spec(OrbitMotionRole.STATE, LocalOrbitMotion.current))

/** Crossfade/scale the exact text, never interpolate a measurement or invent intermediate numbers. */
@Composable
internal fun TestValueText(value: String, style: androidx.compose.ui.text.TextStyle,
    color: Color = LocalContentColor.current, modifier: Modifier = Modifier) {
    val motion = LocalOrbitMotion.current
    if (!motion) { Text(value, modifier = modifier, style = style, color = color); return }
    AnimatedContent(value, modifier.clearAndSetSemantics { text = androidx.compose.ui.text.AnnotatedString(value) }, transitionSpec = {
        (fadeIn(tween(160)) + scaleIn(
            OrbitMotion.spec(OrbitMotionRole.SETTLE, true), initialScale = .96f)) togetherWith fadeOut(tween(80))
    }, label = "test exact value") { text -> Text(text, style = style, color = color) }
}

@Composable
internal fun TestMeasuredValue(value: String, unit: String, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        TestValueText(value, MaterialTheme.typography.headlineLarge.copy(fontFeatureSettings = "tnum"), color)
        Text(unit, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** The Start button and its parent share press feedback; no second click/semantics target. */
@Composable
internal fun TestChoiceSurface(icon: ImageVector, intense: Boolean = false,
    content: @Composable ColumnScope.(MutableInteractionSource) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val motion = LocalOrbitMotion.current
    val palette = MaterialTheme.colorScheme
    val rest = amoledCardColor(if (intense) palette.tertiaryContainer else palette.primaryContainer)
    val container by animateColorAsState(if (pressed) androidx.compose.ui.graphics.lerp(rest, palette.surface, .12f) else rest,
        if (motion) spring(stiffness = 500f) else snap(), label = "test press tone")
    val radius by animateDpAsState(if (pressed) 22.dp else 32.dp,
        if (motion) spring(dampingRatio = .9f, stiffness = 500f) else snap(), label = "test press shape")
    val tilt by animateFloatAsState(if (pressed && motion) -8f else 0f,
        if (motion) spring(dampingRatio = .85f, stiffness = 500f) else snap(), label = "test icon tilt")
    Surface(Modifier.fillMaxWidth().pressMotion(source, .985f),
        shape = RoundedCornerShape(radius, radius, if (intense) 28.dp else 12.dp, radius),
        color = container, contentColor = amoledCardContent(if (intense) palette.onTertiaryContainer else palette.onPrimaryContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(32.dp).graphicsLayer { rotationZ = tilt })
            content(source)
        }
    }
}

/** Visible UI timer only. Notification time is rendered by the system chronometer. */
@Composable
internal fun TestActivityCard(state: ConnectionTestState, onStop: (() -> Unit)? = null,
    onOpen: (() -> Unit)? = null) {
    val motion = LocalOrbitMotion.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var now by remember(state.id) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.id, state.active, lifecycle) {
        if (state.active) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { now = System.currentTimeMillis(); delay(1000L) }
        }
    }
    val phase = stringResource(ConnectionTestExperience.phaseLabel(state))
    val round = if (state.kind == ConnectionTestKind.EXTREME)
        stringResource(R.string.test_round, state.currentRound, state.total) else null
    val description = listOfNotNull(phase, round).joinToString(". ")
    val progress = ConnectionTestExperience.progress(state)
    val animatedProgress by animateFloatAsState(progress ?: 0f,
        if (motion) spring(dampingRatio = 1f, stiffness = 400f) else snap(), label = "completed rounds")
    val context = androidx.compose.ui.platform.LocalContext.current
    Surface(Modifier.fillMaxWidth().testContentMotion(), shape = DashDesign.hero,
        color = amoledCardColor(MaterialTheme.colorScheme.secondaryContainer),
        contentColor = amoledCardContent(MaterialTheme.colorScheme.onSecondaryContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                stateDescription = description; liveRegion = LiveRegionMode.Polite
            }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(OrbitSymbols.Speed, null, Modifier.size(32.dp))
                Column(Modifier.weight(1f)) {
                    TestValueText(round ?: stringResource(R.string.product_normal_test), MaterialTheme.typography.titleLarge)
                    TestValueText(phase, MaterialTheme.typography.bodyMedium)
                }
            }
            TestTransferLine(state)
            if (state.startedAt > 0) Text(testNotificationDuration(context,
                ((now - state.startedAt).coerceAtLeast(0L)) / 1000), style = MaterialTheme.typography.labelLarge)
            if (progress != null) LinearProgressIndicator(progress = { animatedProgress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f) })
            val metrics = if (state.kind == ConnectionTestKind.EXTREME)
                ExtremeTestNotificationModel.compactMetrics(ExtremeTestNotificationModel.activeMetrics(state),
                    context.resources.configuration.locales[0]) else ""
            if (metrics.isNotBlank()) TestValueText(stringResource(R.string.test_recent_round_metrics, metrics),
                MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                onStop?.let { TextButton(onClick = hapticClick(DashFeedback.CONFIRM, it)) { Text(stringResource(R.string.test_stop)) } }
                onOpen?.let { TextButton(onClick = hapticClick(action = it)) { Text(stringResource(R.string.product_continue_test)) } }
            }
        }
    }
}
