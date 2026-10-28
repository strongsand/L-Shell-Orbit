package io.github.strongsand.lshell

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun ReportArchiveControls(report: DailyReport? = null, showHeading: Boolean = true) {
    val context = LocalContext.current
    val preferences = remember(context) { ReportArchivePreferences(context) }
    val scope = rememberCoroutineScope()
    var revision by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<Int?>(null) }
    var formatOpen by remember { mutableStateOf(false) }
    var helpOpen by remember { mutableStateOf(false) }
    DisposableEffect(preferences) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        preferences.observe(listener)
        onDispose { preferences.stopObserving(listener) }
    }
    // Reading this revision updates non-observable preferences after operations complete.
    val folder = remember(revision) { preferences.tree }
    val automatic = remember(revision) { preferences.automatic }
    val format = remember(revision) { preferences.format }
    val last = remember(revision) { preferences.lastExport }
    val failed = remember(revision) { preferences.failed }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            feedback = runCatching { ReportArchive.selectFolder(context, uri, result.data!!.flags) }
                .fold({ R.string.archive_folder_ready }, { R.string.archive_access_error })
            revision++
        }
    }
    fun operation(export: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                if (export && report != null) ReportArchive.export(context, report)
                else ReportArchive.testAccess(context)
                feedback = if (export) R.string.archive_exported else R.string.archive_access_ok
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                preferences.failed()
                feedback = R.string.archive_access_error
            } finally {
                busy = false; revision++
            }
        }
    }

    fun chooseFolder() {
        picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
    }
    val grouped = LocalGroupedSettings.current
    val noteModifier = if (grouped) Modifier.padding(horizontal = 16.dp, vertical = 4.dp) else Modifier
    val actionModifier = if (grouped) Modifier.padding(horizontal = 4.dp) else Modifier
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (grouped) 2.dp else 0.dp)) {
        if (showHeading) SectionHeading(stringResource(R.string.archive_title))
        val folderLabel = folder?.let { uri ->
            runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri)
                .substringAfterLast('/').substringAfterLast(':').takeIf { it.isNotBlank() } }.getOrNull()
        } ?: stringResource(if (folder == null) R.string.archive_no_folder else R.string.archive_folder_ready)
        SettingsActionRow(stringResource(R.string.settings_folder),folderLabel,enabled = !busy) { chooseFolder() }
        SettingsSwitchRow(stringResource(R.string.archive_automatic),automatic,enabled = folder != null && !busy) {
            preferences.setAutomatic(it); revision++
        }
        SettingsActionRow(stringResource(R.string.archive_format),
            reportFormatLabel(format),enabled = !busy) { formatOpen = true }
        if (folder != null) {
            SettingsSecondaryAction(stringResource(R.string.archive_test_access), enabled = !busy) { operation(false) }
            if (report != null) FilledTonalButton(enabled = !busy,onClick = hapticClick { operation(true) }) {
                Text(stringResource(R.string.archive_export_now))
            }
        }
        if (last > 0) androidx.compose.material3.ListItem(
            headlineContent = { Text(stringResource(R.string.settings_last_export)) },
            supportingContent = { Text(DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(last))) },
            colors = androidx.compose.material3.ListItemDefaults.colors(containerColor =
                if (grouped) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface))
        if (busy) Text(stringResource(R.string.archive_working),modifier = noteModifier,style = MaterialTheme.typography.bodySmall)
        val message = feedback ?: R.string.archive_access_error.takeIf { failed }
        if (message == R.string.archive_access_error) {
            Text(stringResource(R.string.settings_folder_error),modifier = noteModifier,style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            TextButton(modifier = actionModifier,enabled = !busy,onClick = hapticClick { chooseFolder() }) {
                Text(stringResource(R.string.settings_folder_reselect))
            }
        } else message?.let { Text(stringResource(it),modifier = noteModifier,style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
        SettingsActionRow(stringResource(R.string.settings_archive_help)) { helpOpen = true }
    }
    if (formatOpen) SettingsChoiceDialog(stringResource(R.string.archive_format),
        ReportExportFormat.entries.map { reportFormatLabel(it) },
        format.ordinal,dismiss = { formatOpen = false }) { preferences.setFormat(ReportExportFormat.entries[it]); revision++ }
    if (helpOpen) androidx.compose.material3.AlertDialog(onDismissRequest = { helpOpen = false },
        title = { Text(stringResource(R.string.archive_title)) },
        text = { Text(stringResource(R.string.archive_explanation)) },
        confirmButton = { TextButton(onClick = hapticClick { helpOpen = false }) { Text(stringResource(R.string.traffic_back)) } })
}

/** Manual export only. Folder, format and automatic-export settings stay in Settings. */
@Composable internal fun ReportExportAction(report: DailyReport) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    androidx.compose.material3.IconButton(enabled = !busy, onClick = {
        if (ReportArchivePreferences(context).tree == null) {
            android.widget.Toast.makeText(context, context.getString(R.string.reports_select_folder), android.widget.Toast.LENGTH_LONG).show()
        } else {
            busy = true
            scope.launch {
                try {
                    ReportArchive.export(context, report)
                    android.widget.Toast.makeText(context, context.getString(R.string.archive_exported), android.widget.Toast.LENGTH_SHORT).show()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    ReportArchivePreferences(context).failed()
                    android.widget.Toast.makeText(context, context.getString(R.string.settings_folder_error), android.widget.Toast.LENGTH_LONG).show()
                } finally { busy = false }
            }
        }
    }) {
        androidx.compose.material3.Icon(OrbitSymbols.FileUpload,
            stringResource(R.string.archive_export_now))
    }
}

@Composable private fun reportFormatLabel(format: ReportExportFormat) = stringResource(when (format) {
    ReportExportFormat.JSON -> R.string.reports_export_json
    ReportExportFormat.CSV -> R.string.reports_export_csv
    ReportExportFormat.BOTH -> R.string.reports_export_both
})
