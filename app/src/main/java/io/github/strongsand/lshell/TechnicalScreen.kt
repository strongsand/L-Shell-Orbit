package io.github.strongsand.lshell

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.strongsand.lshell.core.network.grpc.DishyGrpcClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TechnicalScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val client = remember { DishyGrpcClient() }
    DisposableEffect(client) { onDispose { client.close() } }
    var fields by remember { mutableStateOf<List<TechnicalField>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var exportResult by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            exportResult = withContext(Dispatchers.IO) {
                try {
                    val json = TechnicalTelemetry.json(fields)
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(json) }
                    true
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
            }
        }
    }
    LaunchedEffect(refresh) {
        busy = true; failed = false
        val status = client.fetchTechnicalStatus()
        val history = client.fetchHistory()
        fields = withContext(Dispatchers.Default) {
            status.getOrNull()?.let(TechnicalTelemetry::status).orEmpty() +
                history.getOrNull()?.let(TechnicalTelemetry::history).orEmpty()
        }
        failed = status.isFailure || history.isFailure
        busy = false
    }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionHeading(stringResource(R.string.technical_title), stringResource(R.string.technical_presence))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = hapticClick { refresh++ }, enabled = !busy) { Text(stringResource(R.string.experience_refresh)) }
                OutlinedButton(onClick = hapticClick { export.launch("L-Shell-Orbit-technical.json") }, enabled = fields.isNotEmpty() && !busy) {
                    Text(stringResource(R.string.technical_export))
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (failed) Text(stringResource(R.string.technical_partial), color = MaterialTheme.colorScheme.error)
            exportResult?.let { Text(stringResource(if (it) R.string.technical_export_ok else R.string.technical_export_error)) }
            Text(stringResource(R.string.technical_schema_limits), style = MaterialTheme.typography.bodySmall)
        }
        fields.groupBy { it.group }.forEach { (group, rows) ->
            item(key = "heading:$group") { SectionHeading(stringResource(when (group) {
                "terminal" -> R.string.technical_terminal
                "rf" -> R.string.technical_rf
                "energy" -> R.string.technical_energy
                "obstruction" -> R.string.technical_obstruction
                else -> R.string.technical_history
            })) }
            items(rows, key = { "${it.source}:${it.name}" }) { field ->
                Surface(shape = DashDesign.section, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        InfoRow(field.name.replace('_', ' '), field.value ?: stringResource(R.string.technical_unreported))
                        Text(stringResource(R.string.technical_source, field.source), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}
