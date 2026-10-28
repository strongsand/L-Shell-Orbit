package io.github.strongsand.lshell

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AutomaticTestsScreen(smart: Boolean, modifier: Modifier = Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { AutomaticTestPreferences(context) }
    var settings by remember { mutableStateOf(preferences.settings()) }
    var ledger by remember { mutableStateOf(preferences.ledger()) }
    val trigger = if (smart) AutomaticTestTrigger.SMART else AutomaticTestTrigger.SCHEDULED
    var outcome by remember(smart) { mutableStateOf(preferences.lastOutcome(context, trigger)) }
    var lastTest by remember(smart) { mutableStateOf(preferences.lastTest(trigger)) }
    var evaluatingAt by remember { mutableLongStateOf(preferences.evaluatingAt()) }
    var errorAt by remember(smart) { mutableLongStateOf(preferences.errorAt(trigger)) }
    var permissionMessage by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf(false) }
    var help by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var editing by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Int?>(null) }
    val controller = ConnectionTestController.get(context)
    val execution by controller.state.collectAsStateWithLifecycle()
    DisposableEffect(preferences, trigger) {
        val stop = preferences.observeOutcome {
            settings = preferences.settings(); ledger = preferences.ledger()
            outcome = preferences.lastOutcome(context, trigger); lastTest = preferences.lastTest(trigger)
            evaluatingAt = preferences.evaluatingAt(); errorAt = preferences.errorAt(trigger)
        }
        onDispose(stop)
    }
    fun persist(next: AutomaticTestSettings) {
        val normalized = AutomaticTestPolicy.normalize(next)
        if (normalized == preferences.settings()) return
        if (!preferences.save(normalized)) { saveError = true; return }
        settings = normalized
        AutomaticTestSchedule.configure(context, replace = true)
    }
    fun enable() {
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(ConnectionTestService.CHANNEL)
        if (NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            channel?.importance != NotificationManager.IMPORTANCE_NONE)
            persist(if (smart) settings.copy(smart = true) else settings.copy(scheduled = true))
        else permissionMessage = true
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enable() else permissionMessage = true
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            now = System.currentTimeMillis()
            while (true) { delay(60_000); now = System.currentTimeMillis() }
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) now = System.currentTimeMillis()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val day = AutomaticTestPolicy.localDay(now)
    val used = AutomaticTestPolicy.usedToday(ledger, day)
    val smartUsed = if (ledger.day >= day) ledger.smartAttempts else 0
    val manager = context.getSystemService(NotificationManager::class.java)
    val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        manager.getNotificationChannel(ConnectionTestService.CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    val next = AutomaticTestPolicy.nextEligible(settings, ledger, smart, execution.startedAt, now)
    val status = AutomaticTestPolicy.screenStatus(settings, ledger, smart, now, day, execution.active,
        execution.origin, execution.startedAt, notificationsEnabled, evaluatingAt, errorAt)
    AutomaticTestsContent(smart, settings, used, if (smart) smartUsed else if (ledger.day >= day) ledger.scheduledAttempts else 0,
        status, next,
        AutomaticTestPolicy.nextScheduled(settings, ledger, now).key, day, outcome, lastTest,
        notificationsEnabled, modifier, onBack,
        onEnabled = { checked ->
            if (!checked) persist(if (smart) settings.copy(smart = false) else settings.copy(scheduled = false))
            else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else enable()
        }, onFrequency = { frequency ->
            persist(if (smart) settings.copy(smartDailyLimit = frequency, cooldownHours = AutomaticTestPolicy.intervalFor(frequency))
                else settings.copy(intervalHours = AutomaticTestPolicy.intervalFor(frequency), custom = false))
        }, onHelp = { help = true }, records = ledger.slots,
        now = now,
        plannedAt = AutomaticTestPolicy.nextScheduled(settings, ledger, now).plannedAt,
        onCustom = { persist(settings.copy(custom = true)) }, onEditTime = { editing = it },
        onRemoveTime = { index -> persist(settings.copy(customTimes = AutomaticTestPolicy.editTimes(settings.customTimes, index, null))) })
    editing?.let { index ->
        val minute = settings.customTimes.getOrNull(index) ?: (8 * 60)
        val picker = rememberTimePickerState(minute / 60, minute % 60, android.text.format.DateFormat.is24HourFormat(context))
        val configuration = LocalConfiguration.current
        val compact = compactTimeEntry(configuration.screenWidthDp, configuration.screenHeightDp, LocalDensity.current.fontScale)
        val selectedMinute = picker.hour * 60 + picker.minute
        val valid = settings.customTimes.filterIndexed { i, _ -> i != index }.none { it == selectedMinute } &&
            (index >= 0 || settings.customTimes.size < AutomaticTestPolicy.DAILY_LIMIT)
        AlertDialog(onDismissRequest = { editing = null },
            title = { Text(stringResource(if (index < 0) R.string.auto_add_time else R.string.auto_edit_time)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    if (compact) TimeInput(picker) else TimePicker(picker)
                }
                if (!valid) Text(stringResource(R.string.auto_duplicate_time), color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { TextButton(enabled = valid, onClick = {
                persist(settings.copy(customTimes = AutomaticTestPolicy.editTimes(settings.customTimes,
                    index.takeIf { it >= 0 }, selectedMinute))); editing = null
            }) { Text(stringResource(android.R.string.ok)) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(android.R.string.cancel)) } })
    }
    if (permissionMessage) AlertDialog(onDismissRequest = { permissionMessage = false },
        text = { Text(stringResource(R.string.auto_notifications_needed)) },
        confirmButton = { TextButton(onClick = { permissionMessage = false }) { Text(stringResource(android.R.string.ok)) } })
    if (saveError) AlertDialog(onDismissRequest = { saveError = false },
        text = { Text(stringResource(R.string.auto_save_failed)) },
        confirmButton = { TextButton(onClick = { saveError = false }) { Text(stringResource(android.R.string.ok)) } })
    if (help) ModalBottomSheet(onDismissRequest = { help = false }) {
        Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.product_how_it_works), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(if (smart) R.string.auto_smart_explanation else R.string.auto_schedule_explanation))
            Text(stringResource(R.string.product_shared_help))
            Text(stringResource(R.string.auto_data_usage))
            Text(stringResource(R.string.auto_android_limits))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AutomaticTestsContent(smart: Boolean, settings: AutomaticTestSettings, used: Int, modeUsed: Int,
    status: AutomaticScreenStatus, next: Long, plannedKey: Long, day: Long, outcome: Pair<Long, String>?,
    lastTest: Pair<Long, Float?>?, notificationsEnabled: Boolean, modifier: Modifier = Modifier, onBack: () -> Unit = {},
    onEnabled: (Boolean) -> Unit = {}, onFrequency: (Int) -> Unit = {}, onHelp: () -> Unit = {},
    records: List<AutomaticSlotRecord> = emptyList(), onCustom: () -> Unit = {},
    onEditTime: (Int) -> Unit = {}, onRemoveTime: (Int) -> Unit = {}, plannedAt: Long = next,
    now: Long = System.currentTimeMillis()) {
    val enabled = if (smart) settings.smart else settings.scheduled
    val selected = if (smart) settings.smartDailyLimit else if (settings.custom) settings.customTimes.size else 24 / settings.intervalHours
    val palette = MaterialTheme.colorScheme
    val statusText = when (status) {
        AutomaticScreenStatus.DISABLED -> stringResource(R.string.product_auto_off)
        AutomaticScreenStatus.RUNNING -> stringResource(R.string.product_test_active)
        AutomaticScreenStatus.EVALUATING -> stringResource(R.string.auto_evaluating)
        AutomaticScreenStatus.ERROR -> stringResource(R.string.auto_error_retry)
        AutomaticScreenStatus.DAILY_LIMIT_REACHED -> stringResource(R.string.product_budget_exhausted)
        AutomaticScreenStatus.TEMPORARILY_BLOCKED -> stringResource(if (notificationsEnabled) R.string.product_test_active else R.string.auto_notifications_needed)
        AutomaticScreenStatus.COOLDOWN -> stringResource(R.string.auto_waiting_cooldown,
            automationTimeLabel(next, now))
        AutomaticScreenStatus.WAITING -> stringResource(R.string.product_waiting_idle)
        AutomaticScreenStatus.SCHEDULED -> stringResource(R.string.auto_planned_approx,
            automationTimeLabel(plannedAt, now))
        AutomaticScreenStatus.OVERDUE -> stringResource(R.string.auto_android_delayed_time,
            automationTimeLabel(plannedAt, now))
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = hapticClick(action = onBack)) { Icon(OrbitSymbols.ArrowBack, stringResource(R.string.auto_back)) }
            Text(stringResource(if (smart) R.string.auto_smart_title else R.string.auto_scheduled_title),
                style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        }
        Text(stringResource(if (smart) R.string.auto_smart_description else R.string.auto_scheduled_description),
            style = MaterialTheme.typography.bodyMedium, color = palette.onSurfaceVariant)
        Surface(shape = DashDesign.section, color = palette.surfaceContainerLow, contentColor = palette.onSurface) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(if (smart) OrbitSymbols.AutoAwesome else OrbitSymbols.Schedule, null,
                    tint = palette.primary)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.auto_enable), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(if (enabled) R.string.auto_enabled else R.string.product_auto_off),
                        style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant)
                }
                val switchLabel = stringResource(if (smart) R.string.auto_smart_title else R.string.auto_scheduled_title)
                Switch(enabled, onCheckedChange = hapticToggle(onEnabled),
                    modifier = Modifier.semantics { contentDescription = switchLabel })
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(if (smart) R.string.auto_daily_maximum else R.string.product_frequency),
                Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AutomaticTestPolicy.frequencies.forEach { frequency ->
                    OrbitFilterChip((smart || !settings.custom) && selected == frequency, onClick = hapticClick { onFrequency(frequency) },
                        label = { Text(stringResource(if (smart) R.string.auto_up_to_short else R.string.auto_per_day, frequency)) })
                }
            }
            if (!smart) OrbitFilterChip(settings.custom, onClick = hapticClick(action = onCustom),
                label = { Text(stringResource(R.string.auto_custom)) })
            if ((smart || !settings.custom) && selected !in AutomaticTestPolicy.frequencies) Text(stringResource(if (smart) R.string.product_up_to_day else R.string.auto_hours,
                if (smart) settings.smartDailyLimit else settings.intervalHours),
                style = MaterialTheme.typography.labelMedium, color = palette.onSurfaceVariant)
        }
        Surface(shape = DashDesign.section, color = palette.surfaceContainerLow, contentColor = palette.onSurface) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.auto_today), Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium)
                Text(stringResource(if (smart) R.string.auto_smart_today else R.string.auto_scheduled_today,
                    modeUsed, if (smart) settings.smartDailyLimit else selected), style = MaterialTheme.typography.bodyLarge)
                Text(statusText, style = MaterialTheme.typography.bodyMedium, color = palette.primary)
                if (lastTest != null) {
                    val time = automationTimeLabel(lastTest.first, now)
                    val metric = lastTest.second?.let { String.format(Locale.getDefault(), "%.1f", it) }
                    Text(if (metric != null) stringResource(R.string.auto_last_test_speed, time, metric)
                        else stringResource(R.string.auto_last_test_time, time),
                        style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant)
                }
                Text(stringResource(R.string.auto_shared_usage, used, AutomaticTestPolicy.DAILY_LIMIT),
                    style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant)
                outcome?.let { (time, text) ->
                    if (time >= java.time.LocalDate.ofEpochDay(day).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
                        Text(text, style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant)
                }
            }
        }
        if (!smart && (enabled || settings.custom)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.auto_today_windows), Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AutomaticTestPolicy.plannedSlots(settings, day).forEach { slot ->
                        val label = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(slot.plannedAt))
                        val recorded = records.lastOrNull { it.slot.key == slot.key }?.status
                        val isNext = enabled && slot.key == plannedKey
                        val description = stringResource(when (recorded) {
                            AutomaticSlotStatus.COMPLETED -> R.string.auto_slot_completed
                            AutomaticSlotStatus.RUNNING -> R.string.auto_running
                            AutomaticSlotStatus.FAILED -> R.string.auto_failed
                            AutomaticSlotStatus.INTERRUPTED -> R.string.auto_interrupted
                            AutomaticSlotStatus.NOT_EXECUTED -> R.string.auto_slot_not_executed
                            null -> if (isNext) R.string.auto_slot_next else R.string.auto_slot_planned
                        })
                        Surface(modifier = if (settings.custom) Modifier.fillMaxWidth() else Modifier, shape = DashDesign.section,
                            color = if (isNext) palette.secondaryContainer else palette.surfaceContainerHigh) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(when (recorded) {
                                    AutomaticSlotStatus.COMPLETED -> OrbitSymbols.CheckCircle
                                    AutomaticSlotStatus.RUNNING -> OrbitSymbols.PlayCircle
                                    AutomaticSlotStatus.FAILED, AutomaticSlotStatus.INTERRUPTED,
                                    AutomaticSlotStatus.NOT_EXECUTED -> OrbitSymbols.ErrorOutline
                                    null -> if (isNext) OrbitSymbols.Schedule else OrbitSymbols.RadioButtonUnchecked
                                }, description, Modifier.size(18.dp))
                                Text(label, if (settings.custom) Modifier.weight(1f) else Modifier,
                                    style = MaterialTheme.typography.labelLarge)
                                if (settings.custom) {
                                    val index = settings.customTimes.indexOf((slot.key % 1440).toInt())
                                    IconButton(onClick = hapticClick { onEditTime(index) }) {
                                        Icon(OrbitSymbols.Edit, stringResource(R.string.auto_edit_time) + ": " + label)
                                    }
                                    IconButton(enabled = settings.customTimes.size > 1,
                                        onClick = hapticClick { onRemoveTime(index) }) {
                                        Icon(OrbitSymbols.DeleteOutline, stringResource(R.string.auto_remove_time) + ": " + label)
                                    }
                                }
                            }
                        }
                    }
                }
                if (settings.custom) TextButton(enabled = settings.customTimes.size < AutomaticTestPolicy.DAILY_LIMIT,
                    onClick = hapticClick { onEditTime(-1) }) {
                    Icon(OrbitSymbols.Add, null); Text(stringResource(R.string.auto_add_time))
                }
                Text(stringResource(R.string.auto_windows_note), style = MaterialTheme.typography.bodySmall,
                    color = palette.onSurfaceVariant)
            }
        }
        TextButton(onClick = hapticClick(action = onHelp)) {
            Icon(OrbitSymbols.Info, null, Modifier.padding(end = 8.dp)); Text(stringResource(R.string.product_how_it_works))
        }
    }
}
