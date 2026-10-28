package io.github.strongsand.lshell

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun FunctionsScreen(modifier: Modifier = Modifier,onNavigate: (String) -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(start = 16.dp,end = 16.dp,top = 16.dp,bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.settings_functions),Modifier.padding(vertical = 8.dp).semantics { heading() },
            style = MaterialTheme.typography.headlineLarge)
        FunctionDirectory(onNavigate)
    }
}

@Composable
internal fun OrbitSettingsScreen(modifier: Modifier = Modifier,onBack: () -> Unit,onTechnical: () -> Unit) {
    val context = LocalContext.current
    var monitoring by remember { mutableStateOf(MonitorPreferences.enabled(context)) }
    var silent by remember { mutableStateOf(MonitorPreferences.silent(context)) }
    var interval by remember { mutableStateOf(MonitorPreferences.intervalSeconds(context)) }
    var intervalMenu by rememberSaveable { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var lastSuccess by remember { mutableStateOf(MonitorPreferences.lastSuccess(context)) }
    var monitorError by remember { mutableStateOf(MonitorPreferences.lastError(context)) }
    var reportEnabled by remember { mutableStateOf(DailyReportPreferences.enabled(context)) }
    var reportHour by remember { mutableStateOf(DailyReportPreferences.hour(context)) }
    var reportMinute by remember { mutableStateOf(DailyReportPreferences.minute(context)) }
    var reportNotify by remember { mutableStateOf(DailyReportPreferences.notify(context)) }
    var keepReports by remember { mutableStateOf(DailyReportPreferences.keepHistory(context)) }
    var retention by remember { mutableStateOf(DailyReportPreferences.retention(context)) }
    var retentionMenu by rememberSaveable { mutableStateOf(false) }
    var timeDialog by rememberSaveable { mutableStateOf(false) }
    var creditsOpen by rememberSaveable { mutableStateOf(false) }
    val backupRevision by BackupGate.revision.collectAsState()
    LaunchedEffect(backupRevision) {
        silent = MonitorPreferences.silent(context); interval = MonitorPreferences.intervalSeconds(context)
        reportEnabled = DailyReportPreferences.enabled(context); reportHour = DailyReportPreferences.hour(context)
        reportMinute = DailyReportPreferences.minute(context); reportNotify = DailyReportPreferences.notify(context)
        keepReports = DailyReportPreferences.keepHistory(context); retention = DailyReportPreferences.retention(context)
    }
    LaunchedEffect(monitoring) {
        while (monitoring) {
            lastSuccess = MonitorPreferences.lastSuccess(context)
            monitorError = MonitorPreferences.lastError(context)
            delay(5_000)
        }
    }
    fun startMonitoring() {
        permissionDenied = false
        monitoring = true
        MonitorPreferences.setLastAlerts(context,emptySet())
        MonitorPreferences.setEnabled(context,true)
        DishyMonitorService.start(context)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startMonitoring() else permissionDenied = true
    }
    val reportPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        reportNotify = granted
        DailyReportPreferences.setNotify(context,granted)
    }
    fun needsNotifications() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    LazyColumn(modifier.fillMaxSize(),contentPadding = PaddingValues(start = 16.dp,end = 16.dp,top = 12.dp,bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "header") {
            Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalIconButton(onClick = hapticClick(action = onBack), modifier = Modifier.size(48.dp)) {
                    Icon(OrbitSymbols.ArrowBack,stringResource(R.string.traffic_back))
                }
                Text(stringResource(R.string.settings_title),Modifier.weight(1f)
                    .orbitSharedBounds("destination:PREFERENCES").semantics { heading() },
                    style = MaterialTheme.typography.headlineLarge)
            }
        }
        item(key = "appearance") {
            SettingsSection(stringResource(R.string.experience_title), OrbitSymbols.Tune, prominent = true) {
                ExperienceControls()
                if (LocalExperience.current.technical) SettingsActionRow(stringResource(R.string.technical_title),
                    supporting = stringResource(R.string.technical_opt_in),onClick = onTechnical)
            }
        }
        item(key = "monitoring") {
            SettingsSection(stringResource(R.string.settings_monitoring), OrbitSymbols.Notifications) {
                SettingsSwitchRow(stringResource(R.string.settings_background),monitoring,
                    stringResource(R.string.settings_background_summary)) { enabled ->
                    if (enabled) {
                        if (needsNotifications()) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else startMonitoring()
                    } else {
                        monitoring = false; MonitorPreferences.setEnabled(context,false); DishyMonitorService.stop(context)
                    }
                }
                SettingsSwitchRow(stringResource(R.string.settings_silent),silent) {
                    silent = it; MonitorPreferences.setSilent(context,it)
                }
                SettingsActionRow(stringResource(R.string.settings_interval),intervalLabel(interval)) { intervalMenu = true }
                if (monitoring) {
                    val status = if (lastSuccess > 0) stringResource(R.string.settings_last_reading,
                        DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.MEDIUM).format(Date(lastSuccess)))
                    else stringResource(R.string.settings_waiting)
                    Text(status,Modifier.padding(horizontal = 16.dp),style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (lastSuccess > 0 && System.currentTimeMillis() - lastSuccess > (interval + 10) * 2_000L)
                        SettingsMessage(stringResource(R.string.settings_no_readings))
                    if (!NotificationManagerCompat.from(context).areNotificationsEnabled())
                        SettingsMessage(stringResource(R.string.settings_notifications_off))
                    SettingsSecondaryAction(stringResource(R.string.settings_test_notification)) {
                        DishyMonitorService.testNotification(context)
                    }
                }
                if (permissionDenied) SettingsMessage(stringResource(R.string.settings_permission))
                monitorError?.let { SettingsMessage(stringResource(R.string.settings_last_error,it)) }
            }
        }
        item(key = "reports") {
            SettingsSection(stringResource(R.string.settings_reports), OrbitSymbols.Assessment) {
                SettingsSwitchRow(stringResource(R.string.settings_daily),reportEnabled) { enabled ->
                    reportEnabled = enabled; DailyReportPreferences.setEnabled(context,enabled)
                    if (enabled) {
                        DailyReportScheduler.schedule(context)
                        if (reportNotify && needsNotifications()) reportPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else DailyReportScheduler.cancel(context)
                }
                SettingsActionRow(stringResource(R.string.settings_report_time),
                    String.format(Locale.getDefault(),"%02d:%02d",reportHour,reportMinute)) { timeDialog = true }
                SettingsSwitchRow(stringResource(R.string.settings_report_notify),reportNotify) { enabled ->
                    if (enabled && needsNotifications()) reportPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else { reportNotify = enabled; DailyReportPreferences.setNotify(context,enabled) }
                }
                SettingsSwitchRow(stringResource(R.string.settings_keep_reports),keepReports) {
                    keepReports = it; DailyReportPreferences.setKeepHistory(context,it)
                }
                if (keepReports) SettingsActionRow(stringResource(R.string.settings_retention),retentionLabel(retention)) { retentionMenu = true }
            }
        }
        item(key = "archive") {
            SettingsSection(stringResource(R.string.reports_data_export), OrbitSymbols.FolderOpen) {
                Text(stringResource(R.string.settings_reports), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
                ReportArchiveControls(showHeading = false)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                BackupControls()
            }
        }
        item(key = "about") {
            SettingsSection(stringResource(R.string.settings_about), OrbitSymbols.Info) {
                SettingsActionRow(stringResource(R.string.settings_credits),supporting = stringResource(R.string.settings_credits_summary)) {
                    creditsOpen = true
                }
            }
        }
    }
    if (creditsOpen) CreditsSheet(onDismiss = { creditsOpen = false })
    if (intervalMenu) SettingsChoiceDialog(stringResource(R.string.settings_interval),
        listOf(1,5,10,30,60,120,300).map { intervalLabel(it) },listOf(1,5,10,30,60,120,300).indexOf(interval),
        stringResource(R.string.settings_interval_note),dismiss = { intervalMenu = false }) { index ->
        interval = listOf(1,5,10,30,60,120,300)[index]
        MonitorPreferences.setIntervalSeconds(context,interval)
        if (monitoring) DishyMonitorService.start(context)
    }
    if (retentionMenu) SettingsChoiceDialog(stringResource(R.string.settings_retention),
        ReportRetention.entries.map { retentionLabel(it) },retention.ordinal,dismiss = { retentionMenu = false }) {
        retention = ReportRetention.entries[it]; DailyReportPreferences.setRetention(context,retention)
    }
    if (timeDialog) ReportTimeDialog(reportHour,reportMinute,onDismiss = { timeDialog = false }) { hour,minute ->
        reportHour = hour; reportMinute = minute
        DailyReportPreferences.setTime(context,hour,minute)
        if (reportEnabled) DailyReportScheduler.schedule(context)
        timeDialog = false
    }
}
@Composable
private fun SettingsMessage(text: String) {
    Text(text,Modifier.padding(horizontal = 16.dp,vertical = 4.dp),style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error)
}
@Composable
private fun intervalLabel(seconds: Int) = stringResource(if (seconds < 60) R.string.traffic_seconds else R.string.traffic_minutes,
    if (seconds < 60) seconds else seconds / 60)
@Composable
private fun retentionLabel(retention: ReportRetention) =
    retention.days?.let { stringResource(R.string.settings_days,it) } ?: stringResource(R.string.settings_always)

@Composable
private fun ReportTimeDialog(hour: Int,minute: Int,onDismiss: () -> Unit,onConfirm: (Int,Int) -> Unit) {
    var selectedHour by remember(hour) { mutableStateOf(hour.toFloat()) }
    var selectedMinuteStep by remember(minute) { mutableStateOf((minute / 5f).coerceIn(0f,11f)) }
    val feedback = rememberDashHaptics()
    var lastSelection by remember(hour, minute) { mutableStateOf(hour to minute) }
    fun selectionFinished() {
        val current = selectedHour.toInt() to selectedMinuteStep.toInt() * 5
        if (current != lastSelection) {
            feedback.perform(DashFeedback.DRAG_SNAP)
            lastSelection = current
        }
    }
    AlertDialog(onDismissRequest = onDismiss,title = { Text(stringResource(R.string.settings_report_time)) },text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(String.format(Locale.getDefault(),"%02d:%02d",selectedHour.toInt(),selectedMinuteStep.toInt() * 5),
                style = MaterialTheme.typography.displaySmall)
            Text(stringResource(R.string.settings_report_time_note),style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.settings_hour))
            val hourLabel = stringResource(R.string.settings_hour)
            Slider(selectedHour,{ selectedHour = it },valueRange = 0f..23f,steps = 22,
                onValueChangeFinished = ::selectionFinished,
                modifier = Modifier.semantics { contentDescription = hourLabel })
            Text(stringResource(R.string.settings_minutes))
            val minuteLabel = stringResource(R.string.settings_minutes)
            Slider(selectedMinuteStep,{ selectedMinuteStep = it },valueRange = 0f..11f,steps = 10,
                onValueChangeFinished = ::selectionFinished,
                modifier = Modifier.semantics { contentDescription = minuteLabel })
        }
    },confirmButton = { TextButton(onClick = hapticClick(DashFeedback.CONFIRM) {
        onConfirm(selectedHour.toInt(),selectedMinuteStep.toInt() * 5)
    }) { Text(stringResource(R.string.settings_save)) } },
        dismissButton = { TextButton(onClick = hapticClick(action = onDismiss)) { Text(stringResource(R.string.traffic_cancel)) } })
}
