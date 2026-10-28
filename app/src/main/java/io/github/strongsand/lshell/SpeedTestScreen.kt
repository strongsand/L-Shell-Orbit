package io.github.strongsand.lshell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SpeedTestScreen(modifier: Modifier = Modifier, requestedId: Long? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val controller = remember(context) { ConnectionTestController.get(context) }
    ConnectionTestFeedback(controller, ConnectionTestKind.NORMAL)
    val state by controller.state.collectAsState()
    var selectedId by rememberSaveable(requestedId) { mutableStateOf(requestedId) }
    LaunchedEffect(state.id, state.phase, requestedId) {
        selectedId = SpeedTestNavigation.followedId(state, requestedId, selectedId)
    }
    val own = state.kind == ConnectionTestKind.NORMAL && (state.id == selectedId || state.active && selectedId == null)
    val running = state.active
    val routerResult = state.result.takeIf { own && state.phase == ConnectionTestPhase.COMPLETED }
    val routerError = when {
        selectedId != null && state.id != selectedId -> androidx.compose.ui.res.stringResource(R.string.test_result_unavailable)
        !own && running -> androidx.compose.ui.res.stringResource(R.string.test_other_active)
        own && state.phase == ConnectionTestPhase.INTERRUPTED -> androidx.compose.ui.res.stringResource(R.string.test_interrupted_detail)
        own || state.kind == ConnectionTestKind.NORMAL && state.id == 0L -> state.error
        else -> null
    }
    val startTest = rememberConnectionTestStart(ConnectionTestKind.NORMAL)

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(androidx.compose.ui.res.stringResource(R.string.function_speed), style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(androidx.compose.ui.res.stringResource(R.string.editorial_speed_help))
        Spacer(Modifier.height(20.dp))
        val onRun: () -> Unit = {
            selectedId = null
            val before = controller.state.value
            startTest()
            val after = controller.state.value
            if (after.kind == ConnectionTestKind.NORMAL && (after.active || after.id != before.id)) selectedId = after.id
        }
        if (routerResult != null) NormalTestResult(state, !running, onRun)
        else TestCard(androidx.compose.ui.res.stringResource(R.string.function_speed),
            androidx.compose.ui.res.stringResource(R.string.editorial_speed_summary), routerError,
            running && own, running, onRun)
        if (running && own) {
            Spacer(Modifier.height(12.dp))
            TestActivityCard(state, onStop = { controller.stop(state.id) })
        }
        Spacer(Modifier.height(12.dp))
        Text(androidx.compose.ui.res.stringResource(R.string.editorial_speed_supported),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TestCard(
    title: String,
    description: String,
    error: String?,
    running: Boolean,
    disabled: Boolean,
    onRun: () -> Unit
) {
    Card(Modifier.fillMaxWidth().testContentMotion(), shape = DashDesign.hero,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExpressiveIcon(OrbitSymbols.Speed, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
            Text(description, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            val interaction = remember { MutableInteractionSource() }
            Button(onClick = hapticClick(DashFeedback.SPECIAL, onRun), enabled = !disabled,
                interactionSource = interaction, modifier = Modifier.pressMotion(interaction, 0.96f)) {
                if (running) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).size(18.dp), strokeWidth = 2.dp)
                Text(if (running) androidx.compose.ui.res.stringResource(R.string.editorial_measuring) else androidx.compose.ui.res.stringResource(R.string.editorial_start_test))
            }
        }
    }
}

/** Exact saved normal result, including an explicit unavailable reading rather than a fabricated zero. */
@Composable
private fun NormalTestResult(state: ConnectionTestState, canStart: Boolean, onRun: () -> Unit) {
    val model = remember(state) { ConnectionTestResultModel.from(state) }
    val success = ConnectionTestExperience.outcome(state) == TestOutcome.SUCCESS
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxWidth().expressiveReveal(state.id), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TestResultBanner(androidx.compose.ui.res.stringResource(R.string.product_normal_test),
            androidx.compose.ui.res.stringResource(model.title), null, success)
        TestResultRates(model.metrics)
        TestResultFacts(model.metrics, model.durationSeconds?.let { testNotificationDuration(context, it) })
        val interaction = remember { MutableInteractionSource() }
        Button(onClick = hapticClick(DashFeedback.SPECIAL, onRun), enabled = canStart,
            interactionSource = interaction, modifier = Modifier.fillMaxWidth().pressMotion(interaction, .96f)) {
            Text(androidx.compose.ui.res.stringResource(R.string.editorial_start_test))
        }
    }
}
