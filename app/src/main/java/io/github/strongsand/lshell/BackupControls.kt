package io.github.strongsand.lshell

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date

internal data class BackupUiState(val working: Boolean = false, val step: Int = R.string.backup_preparing,
    val preview: BackupRestorePlan? = null, val manifest: BackupManifest? = null,
    val result: Map<BackupCategory, BackupMergeCount>? = null, val created: Boolean = false,
    val name: String? = null, val size: Long? = null, val destination: String? = null,
    val ignoredSettings: Int = 0, val recovered: Boolean = false,
    val error: BackupException.Reason? = null, val technicalError: String? = null)

internal class BackupViewModel(context: Context) : ViewModel() {
    private val app = context.applicationContext
    private val coordinator = BackupCoordinator(app)
    val state = MutableStateFlow(BackupUiState())
    private var validated: ValidatedBackup? = null
    fun dismiss() { if (!state.value.working) { validated?.close(); validated = null; state.value = BackupUiState() } }
    fun create(uri: Uri) = operation {
        val created = coordinator.create(uri) { category -> state.value = state.value.copy(step = when (category) {
            BackupCategory.EXTREME, BackupCategory.TESTS -> R.string.backup_tests
            BackupCategory.TRAFFIC -> R.string.backup_sessions
            BackupCategory.DAILY, BackupCategory.WEEKLY, BackupCategory.MONTHLY -> R.string.backup_reports
            BackupCategory.SETTINGS -> R.string.backup_preferences
            null -> R.string.backup_verifying_saved
            else -> R.string.backup_history
        }) }
        val (name, size) = documentInfo(uri)
        val provider = runCatching { uri.authority?.let { app.packageManager.resolveContentProvider(it, 0)
            ?.loadLabel(app.packageManager)?.toString() } }.getOrNull()
        state.value = BackupUiState(created = true, manifest = created.manifest, name = name, size = size ?: created.bytes, destination = provider)
    }
    fun select(uri: Uri) = operation {
        validated?.close(); validated = null
        state.value = state.value.copy(step = R.string.backup_validating)
        val backup = coordinator.select(uri)
        try {
            val plan = coordinator.plan(backup); validated = backup
            state.value = BackupUiState(preview = plan, manifest = backup.manifest, name = documentInfo(uri).first)
        } catch (e: Exception) { backup.close(); throw e }
    }
    fun restore(automation: Boolean) {
        val backup = validated ?: return
        operation {
            state.value = state.value.copy(step = R.string.backup_restoring, preview = null)
            try {
                val result = coordinator.restore(backup, automation)
                val ignored = BackupSettingsCodec.plan(backupJson(backup.file(BackupCategory.SETTINGS).readBytes()), automation).ignored
                state.value = BackupUiState(manifest = backup.manifest, result = result,
                    preview = null, ignoredSettings = ignored)
            } finally { backup.close(); validated = null }
        }
    }
    fun retryRecovery() = operation { coordinator.recover(); state.value = BackupUiState(recovered = true) }
    private fun operation(block: () -> Unit) {
        if (state.value.working) return
        state.value = state.value.copy(working = true, error = null)
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.value = state.value.copy(preview = null,
                error = (e as? BackupException)?.reason ?: BackupException.Reason.STORAGE,
                technicalError = e.javaClass.simpleName) }
            finally { state.value = state.value.copy(working = false) }
        }
    }
    private fun documentInfo(uri: Uri): Pair<String?, Long?> = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0)?.take(200) to if (it.isNull(1)) null else it.getLong(1).takeIf { n -> n >= 0 } else null
        } ?: (null to null)
    }.getOrDefault(null to null)
    override fun onCleared() { if (!state.value.working) validated?.close() }
}

@Composable
internal fun BackupControls() {
    val context = LocalContext.current
    val model: BackupViewModel = viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = BackupViewModel(context) as T
    })
    val state by model.state.collectAsState()
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let(model::create) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::select) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.backup_title), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
        SettingsActionRow(stringResource(R.string.backup_create), supporting = stringResource(R.string.backup_create_summary), enabled = !state.working) {
            model.dismiss(); create.launch("L-Shell-Orbit-backup-${LocalDate.now()}.zip")
        }
        SettingsActionRow(stringResource(R.string.backup_restore), supporting = stringResource(R.string.backup_restore_summary), enabled = !state.working) {
            model.dismiss(); restore.launch(arrayOf("application/zip", "application/octet-stream"))
        }
        Text(stringResource(R.string.backup_privacy), Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.working) Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp)); Text(stringResource(state.step))
        }
    }
    var automation by remember(state.preview) { mutableStateOf(false) }
    if (state.working) AlertDialog(onDismissRequest = {}, title = { Text(stringResource(state.step)) },
        text = { CircularProgressIndicator() }, confirmButton = {})
    if (state.preview != null && !state.working) AlertDialog(onDismissRequest = model::dismiss,
        title = { Text(stringResource(R.string.backup_preview)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.manifest?.let { Text(DateFormat.getDateTimeInstance().format(Date(it.createdAt))) }
                BackupCounts(state.preview!!.counts)
                Text(stringResource(R.string.backup_settings_count, state.preview!!.settings.compatible.size, state.preview!!.settings.ignored))
                Text(stringResource(R.string.backup_merge_note), style = MaterialTheme.typography.bodySmall)
                if (state.preview!!.settings.automationRequested) SettingsSwitchRow(stringResource(R.string.backup_enable_automation), automation,
                    supporting = stringResource(R.string.backup_automation_note)) { automation = it }
            }
        }, confirmButton = { TextButton(onClick = hapticClick(DashFeedback.CONFIRM) { model.restore(automation) }) { Text(stringResource(R.string.backup_confirm)) } },
        dismissButton = { TextButton(onClick = model::dismiss) { Text(stringResource(R.string.traffic_cancel)) } })
    if (!state.working && (state.created || state.result != null || state.error != null || state.recovered)) AlertDialog(
        onDismissRequest = model::dismiss, title = { Text(stringResource(when {
            state.error != null -> R.string.backup_error_title
            state.created -> R.string.backup_created
            else -> R.string.backup_restored
        })) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.error?.let { Text(stringResource(backupErrorResource(it))) }
                if (LocalExperience.current.technical && state.error != null) Text("${state.error}: ${state.technicalError}", style = MaterialTheme.typography.bodySmall)
                if (state.error == null) {
                    if (state.created) {
                        Text(state.name ?: stringResource(R.string.backup_title))
                        state.destination?.let { Text(stringResource(R.string.backup_destination, it)) }
                        state.size?.let { Text(android.text.format.Formatter.formatFileSize(context, it)) }
                        state.manifest?.let { m -> Text(DateFormat.getDateTimeInstance().format(Date(m.createdAt)))
                            BackupCounts(m.counts.mapValues { BackupMergeCount(it.value) }, saved = true) }
                    } else if (state.recovered) {
                        Text(stringResource(R.string.backup_recovery_done))
                    } else {
                        BackupCounts(state.result.orEmpty())
                        Text(stringResource(R.string.backup_preferences_done))
                        state.ignoredSettings.takeIf { it > 0 }?.let { Text(stringResource(R.string.backup_ignored, it)) }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = model::dismiss) { Text(stringResource(R.string.backup_close)) } },
        dismissButton = { if (state.error == BackupException.Reason.RECOVERY) TextButton(onClick = model::retryRecovery) { Text(stringResource(R.string.backup_retry)) } })
}

@Composable
private fun BackupCounts(counts: Map<BackupCategory, BackupMergeCount>, saved: Boolean = false) {
    val categories = listOf(R.string.backup_history to listOf(BackupCategory.READINGS, BackupCategory.EVENTS),
        R.string.backup_tests to listOf(BackupCategory.TESTS), R.string.backup_round_archives to listOf(BackupCategory.EXTREME),
        R.string.backup_sessions to listOf(BackupCategory.TRAFFIC), R.string.backup_daily to listOf(BackupCategory.DAILY),
        R.string.backup_weekly to listOf(BackupCategory.WEEKLY), R.string.backup_monthly to listOf(BackupCategory.MONTHLY),
        R.string.backup_observation_days to listOf(BackupCategory.DAYS))
    categories.forEach { (label, ids) ->
        val added = ids.sumOf { counts[it]?.added ?: 0 }; val existing = ids.sumOf { counts[it]?.existing ?: 0 }
        val text = if (saved) stringResource(R.string.backup_saved_count_line, stringResource(label), added)
            else stringResource(R.string.backup_count_line, stringResource(label), added, existing)
        Text(text, style = MaterialTheme.typography.bodyMedium)
        val ignored = ids.sumOf { counts[it]?.ignored ?: 0 }
        if (ignored > 0) Text(stringResource(R.string.backup_empty_days_ignored, ignored),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun backupErrorResource(reason: BackupException.Reason) = when (reason) {
    BackupException.Reason.NOT_BACKUP -> R.string.backup_not_backup
    BackupException.Reason.FUTURE_VERSION -> R.string.backup_future
    BackupException.Reason.CORRUPT, BackupException.Reason.INVALID_RECORD -> R.string.backup_corrupt
    BackupException.Reason.LIMIT -> R.string.backup_limit
    BackupException.Reason.BUSY -> R.string.backup_busy
    BackupException.Reason.RECOVERY -> R.string.backup_recovery_error
    BackupException.Reason.STORAGE -> R.string.backup_storage_error
}

@Composable
internal fun BackupRecoveryScreen(failed: Boolean, retry: () -> Unit) {
    MaterialTheme { Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.backup_recovering), style = MaterialTheme.typography.headlineSmall)
            if (failed) { Text(stringResource(R.string.backup_recovery_error))
                Button(onClick = retry) { Text(stringResource(R.string.backup_retry)) } }
            else CircularProgressIndicator()
        }
    } }
}
