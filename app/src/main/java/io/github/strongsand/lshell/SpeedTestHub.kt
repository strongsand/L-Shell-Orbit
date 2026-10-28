package io.github.strongsand.lshell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun SpeedTestHub(modifier: Modifier, onNormal: () -> Unit, onExtreme: () -> Unit,
    onHistory: () -> Unit) {
    val controller = ConnectionTestController.get(LocalContext.current)
    val state by controller.state.collectAsState()
    val startNormal = rememberConnectionTestStart(ConnectionTestKind.NORMAL)
    SpeedTestHubContent(state, modifier, onNormal = {
        if (!state.active) startNormal()
        onNormal()
    }, onExtreme = onExtreme, onHistory = onHistory)
}

/** Stateless so previews use the actual layout without starting any service. */
@Composable
internal fun SpeedTestHubContent(state: ConnectionTestState, modifier: Modifier = Modifier,
    onNormal: () -> Unit = {}, onExtreme: () -> Unit = {}, onHistory: () -> Unit = {}) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.function_speed), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
        if (state.active) TestActivityCard(state, onOpen =
            if (state.kind == ConnectionTestKind.NORMAL) onNormal else onExtreme)
        TestChoiceSurface(OrbitSymbols.Speed) { interaction ->
            Text(stringResource(R.string.product_normal_test), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.product_normal_summary), style = MaterialTheme.typography.bodyMedium)
            Button(enabled = !state.active, interactionSource = interaction,
                modifier = Modifier.pressMotion(interaction, .97f),
                onClick = hapticClick(DashFeedback.CONFIRM, onNormal)) {
                Text(stringResource(R.string.editorial_start_test))
            }
        }
        TestChoiceSurface(OrbitSymbols.ElectricBolt, intense = true) { interaction ->
            Text(stringResource(R.string.function_extreme), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.product_extreme_summary), style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(enabled = !state.active, interactionSource = interaction,
                modifier = Modifier.pressMotion(interaction, .97f), onClick = hapticClick(action = onExtreme)) {
                Text(stringResource(R.string.product_choose_rounds))
            }
        }
        Surface(onClick = hapticClick(DashFeedback.NAVIGATION, onHistory),
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            shape = DashDesign.section, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(OrbitSymbols.History, null, Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.polish_test_history), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium)
                Icon(OrbitSymbols.ChevronRight, null, Modifier.size(22.dp))
            }
        }
    }
}

@Composable
internal fun TestResultsScreen(modifier: Modifier, onBack: () -> Unit, onExtremeArchive: () -> Unit) {
    val context = LocalContext.current
    var tests by remember { mutableStateOf<List<RecordedTest>?>(null) }
    LaunchedEffect(Unit) { tests = withContext(Dispatchers.IO) {
        HistoryStore(context).use { TestHistory.between(it, 0, System.currentTimeMillis()) }.sortedByDescending { it.start }
    } }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("header") { Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = hapticClick(action = onBack)) { Icon(OrbitSymbols.ArrowBack, stringResource(R.string.antenna_back)) }
            Text(stringResource(R.string.polish_test_history), style = MaterialTheme.typography.headlineSmall)
        } }
        item("archive") { TextButton(onClick = hapticClick(action = onExtremeArchive)) { Text(stringResource(R.string.editorial_extreme_history)) } }
        if (tests == null) item("loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        else if (tests!!.isEmpty()) item("empty") {
            Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.product_no_results), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.ux_tests_empty_detail), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(tests.orEmpty(), key = { it.id }, contentType = { "test" }) { test ->
            OccurrenceRow(HistoryOccurrence("test:${test.id}", test.end.takeIf { it > 0 } ?: test.start,
                "", category = OccurrenceCategory.TESTS, test = test))
        }
    }
}
