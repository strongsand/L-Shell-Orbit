package io.github.strongsand.lshell

import android.content.Context
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.color.ColorProvider as dayNightColorProvider
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import java.text.DateFormat
import java.util.Date
import java.util.Locale

// Glance 1.1.1 provides its own LocalContext, but not Compose UI LocalConfiguration.
// Observe real configuration changes and dispose the callback with this widget session.
@Composable
private fun WidgetConfiguration(context: Context, content: @Composable () -> Unit) {
    val state = remember(context) { mutableStateOf(Configuration(context.resources.configuration)) }
    val appearance = remember(context) { mutableStateOf(ExperiencePreferences.read(context)) }
    DisposableEffect(context) {
        val owner = context.applicationContext ?: context
        val callbacks = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) { state.value = Configuration(newConfig) }
            override fun onLowMemory() = Unit
        }
        owner.registerComponentCallbacks(callbacks)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            appearance.value = ExperiencePreferences.read(owner)
        }
        ExperiencePreferences.observe(owner, listener)
        onDispose {
            owner.unregisterComponentCallbacks(callbacks)
            ExperiencePreferences.stopObserving(owner, listener)
        }
    }
    val configuration = state.value
    val resourceContext = remember(context, configuration) { context.createConfigurationContext(configuration) }
    CompositionLocalProvider(LocalConfiguration provides configuration, LocalContext provides resourceContext,
        LocalExperience provides appearance.value) { content() }
}

@Composable
private fun widgetColor(resource: Int): ColorProvider {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val appearance = LocalExperience.current
    return remember(context, configuration, resource, appearance.monochrome) {
        fun color(mode: Int): ComposeColor {
            val themed = Configuration(configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
            }
            return ComposeColor(widgetAppearanceColor(resource, appearance,
                context.createConfigurationContext(themed).getColor(resource)))
        }
        // Keep both variants in RemoteViews, including after the composition session ends.
        dayNightColorProvider(day = color(Configuration.UI_MODE_NIGHT_NO), night = color(Configuration.UI_MODE_NIGHT_YES))
    }
}

@Composable
private fun GlanceModifier.widgetSurface(drawable: Int, color: Int, radius: Int = 28): GlanceModifier =
    if (LocalExperience.current.monochrome) background(widgetColor(color)).cornerRadius(radius.dp)
    else background(ImageProvider(drawable))

private enum class WidgetTone(val resource: Int) {
    TEXT(R.color.widget_text), MUTED(R.color.widget_muted), ACCENT(R.color.widget_accent),
    ON_TONAL(R.color.widget_on_primary), GOOD(R.color.widget_online), WARNING(R.color.widget_warning)
}

@Composable
private fun widgetLayout(): OrbitWidgetLayout {
    val size = LocalSize.current
    return orbitWidgetLayout(size.width.value, size.height.value, LocalConfiguration.current.fontScale)
}

@Composable
internal fun ObstructionWidgetContent(context: Context, data: ObstructionWidgetData) {
    WidgetConfiguration(context) { ObstructionWidgetBody(context, data) }
}

@Composable
private fun ObstructionWidgetBody(context: Context, data: ObstructionWidgetData) {
    val layout = widgetLayout()
    val title = widgetString(R.string.editorial_obstruction)
    val refresh = actionRunCallback<RefreshObstructionWidgetAction>()
    val state = data.presentation
    val label = if (state.cachedReading && state.state != ObstructionWidgetState.OFFLINE)
        widgetString(R.string.widget_last_reading) else obstructionLabel(state.state)
    val tone = when (state.state) {
        ObstructionWidgetState.FREE -> WidgetTone.GOOD
        ObstructionWidgetState.RELEVANT, ObstructionWidgetState.OFFLINE -> WidgetTone.WARNING
        else -> WidgetTone.ACCENT
    }
    val value = state.percent?.takeUnless { layout == OrbitWidgetLayout.GLANCE && state.cachedReading }
        ?.let { percent(it) } ?: "—"
    WidgetContainer(widgetOpen(context, OrbitWidgetDestination.OBSTRUCTION)) {
        when (layout) {
            OrbitWidgetLayout.GLANCE -> GlanceReading(title, value, label, tone, refresh)
            OrbitWidgetLayout.HORIZONTAL -> Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                SkyMap(data, 92)
                Spacer(GlanceModifier.width(8.dp))
                Column(GlanceModifier.defaultWeight()) {
                    WidgetText(title, 12, WidgetTone.MUTED)
                    WidgetText(value, 28, tone, true)
                    WidgetText(label, 12, tone, maxLines = 2)
                    if (!state.cachedReading) data.updatedAt?.let { WidgetText(shortTime(it), 11, WidgetTone.MUTED) }
                }
                RefreshButton(refresh)
            }
            OrbitWidgetLayout.COMPACT -> {
                WidgetHeader(title, refresh)
                Column(GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    SkyMap(data, 72)
                    WidgetText(value, 28, tone, true)
                    WidgetText(label, 12, tone, maxLines = 1)
                }
            }
            OrbitWidgetLayout.HERO -> {
                WidgetHeader(title, refresh)
                Column(GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    SkyMap(data, 120)
                    WidgetText(value, 36, tone, true)
                    WidgetText(label, 14, tone)
                    state.coverage?.let { WidgetText(widgetString(R.string.widget_surveyed, percent(it)), 12, WidgetTone.MUTED) }
                    ObstructionTime(data)
                }
            }
            OrbitWidgetLayout.PRIMARY, OrbitWidgetLayout.EXPANDED -> {
                PrimaryObstruction(data, title, value, label, tone, refresh)
            }
        }
    }
}

@Composable
private fun SkyMap(data: ObstructionWidgetData, side: Int) {
    if (data.bitmap != null) Image(ImageProvider(data.bitmap),
        contentDescription = data.presentation.coverage?.let { widgetString(R.string.editorial_map_description, percent(it)) }
            ?: widgetString(R.string.editorial_obstruction),
        modifier = GlanceModifier.size(side.dp), contentScale = ContentScale.Fit)
    else Box(GlanceModifier.size(side.dp).background(widgetColor(R.color.widget_inner)).cornerRadius((side / 2).dp),
        contentAlignment = Alignment.Center) {
        Image(ImageProvider(R.drawable.ic_dish_antenna), contentDescription = widgetString(R.string.widget_map_unavailable),
            modifier = GlanceModifier.size((side / 2).coerceAtMost(40).dp),
            colorFilter = ColorFilter.tint(widgetColor(R.color.widget_muted)))
    }
}

@Composable
private fun ObstructionTime(data: ObstructionWidgetData) {
    val state = data.presentation
    if (state.cachedReading) WidgetText(widgetString(R.string.widget_cached_reading,
        data.updatedAt?.let(::shortDateTime) ?: "—"), 11, WidgetTone.MUTED, maxLines = 2)
    else data.updatedAt?.let { WidgetText(widgetString(R.string.widget_updated_time, shortTime(it)), 11, WidgetTone.MUTED) }
}

@Composable
internal fun ReportWidgetContent(context: Context, report: ReportWidgetData?) {
    WidgetConfiguration(context) { ReportWidgetBody(context, report) }
}

@Composable
private fun ReportWidgetBody(context: Context, report: ReportWidgetData?) {
    val layout = widgetLayout()
    val refresh = actionRunCallback<RefreshReportWidgetAction>()
    val title = widgetString(R.string.editorial_widget_report)
    val value = report?.availability?.let { percent(it, if (layout == OrbitWidgetLayout.GLANCE && it >= 10f) 0 else 1) } ?: "—"
    val label = widgetString(when {
        report == null -> R.string.editorial_widget_no_report
        report.availability == null -> R.string.widget_no_data
        else -> R.string.editorial_widget_availability
    })
    val open = widgetOpen(context, OrbitWidgetDestination.REPORT).apply { report?.let { putExtra("report_end", it.end) } }
    WidgetContainer(open) {
        if (layout == OrbitWidgetLayout.GLANCE) GlanceReading(title, value, label, WidgetTone.ACCENT, refresh)
        else if (layout == OrbitWidgetLayout.PRIMARY || layout == OrbitWidgetLayout.EXPANDED) {
            PrimaryReport(report, title, value, label, refresh)
        } else if (layout == OrbitWidgetLayout.HORIZONTAL) Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.defaultWeight()) {
                WidgetText(title, 12, WidgetTone.MUTED)
                WidgetText(value, 28, WidgetTone.ACCENT, true)
                WidgetText(label, 12, WidgetTone.MUTED)
            }
            Column(GlanceModifier.defaultWeight().padding(horizontal = 8.dp)) {
                if (report == null) WidgetText(widgetString(R.string.widget_open_reports), 12, WidgetTone.MUTED, maxLines = 2)
                else {
                    WidgetText(reportDay(report.end), 12, WidgetTone.TEXT, true)
                    ReportCoverage(report)
                }
            }
            RefreshButton(refresh)
        } else {
            WidgetHeader(title, refresh, R.drawable.ic_widget_report)
            if (report == null) EmptyWidgetState(widgetString(R.string.editorial_widget_no_report), widgetString(R.string.widget_open_reports))
            else {
                val compact = layout == OrbitWidgetLayout.COMPACT
                Spacer(GlanceModifier.height(if (compact) 2.dp else 10.dp))
                Column(GlanceModifier.fillMaxWidth().widgetSurface(R.drawable.widget_hero_surface, R.color.widget_primary).padding(if (compact) 10.dp else 16.dp)) {
                    WidgetText(value, if (compact) 30 else 42, WidgetTone.ON_TONAL, true)
                    WidgetText(label, 12, WidgetTone.ON_TONAL)
                    WidgetText(reportDay(report.end), 12, WidgetTone.ON_TONAL)
                }
                Spacer(GlanceModifier.height(8.dp))
                ReportCoverage(report)
                if (!compact) {
                    Spacer(GlanceModifier.height(8.dp))
                    WidgetText(context.telemetryText(report.summary), 14, WidgetTone.TEXT, maxLines = 2)
                }

            }
        }
    }
}

@Composable
private fun ReportCoverage(report: ReportWidgetData) {
    WidgetText(report.coverage?.let { widgetString(R.string.widget_observed, percent(it)) }
        ?: widgetString(R.string.widget_coverage_unknown), 12, WidgetTone.MUTED, maxLines = 2)
}

@Composable
internal fun BeaconWidgetContent(context: Context, data: BeaconWidgetData) {
    WidgetConfiguration(context) { BeaconWidgetBody(context, data) }
}

@Composable
private fun BeaconWidgetBody(context: Context, data: BeaconWidgetData) {
    val layout = widgetLayout()
    val refresh = actionRunCallback<RefreshBeaconWidgetAction>()
    val title = widgetString(if (layout == OrbitWidgetLayout.PRIMARY || layout == OrbitWidgetLayout.EXPANDED)
        R.string.widget_beacon_full_title else R.string.widget_beacon_title)
    val status = data.status
    val state = beaconWidgetState(data.configured, status != null, status?.recording == true,
        status?.storageHealthy != false, status?.dishyAvailable)
    val label = widgetString(when (state) {
        BeaconWidgetState.SETUP -> R.string.editorial_widget_unconfigured
        BeaconWidgetState.OFFLINE -> R.string.beacon_offline
        BeaconWidgetState.STORAGE_WARNING -> R.string.widget_storage_attention
        BeaconWidgetState.DISH_UNAVAILABLE -> R.string.widget_dish_wait
        BeaconWidgetState.RECORDING -> R.string.widget_recording
        BeaconWidgetState.ONLINE -> R.string.widget_online
    })
    val tone = if (state in setOf(BeaconWidgetState.OFFLINE, BeaconWidgetState.STORAGE_WARNING,
        BeaconWidgetState.DISH_UNAVAILABLE)) WidgetTone.WARNING else WidgetTone.ACCENT
    val pending = status?.pendingRecords?.takeIf { it >= 0L }
    val value = pending?.let { widgetPendingCount(it, Locale.getDefault()) } ?: "—"
    WidgetContainer(widgetOpen(context, OrbitWidgetDestination.BEACON)) {
        if (layout == OrbitWidgetLayout.GLANCE) GlanceReading(title, value, label, tone, refresh)
        else if (layout == OrbitWidgetLayout.PRIMARY || layout == OrbitWidgetLayout.EXPANDED) {
            PrimaryBeacon(data, title, value, label, state, pending, refresh)
        } else if (layout == OrbitWidgetLayout.HORIZONTAL) Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.defaultWeight()) {
                WidgetText(title, 12, WidgetTone.MUTED)
                WidgetText(label, 16, tone, true, maxLines = 2)
            }
            Column(GlanceModifier.defaultWeight().padding(horizontal = 8.dp)) {
                WidgetText(value, 26, WidgetTone.ACCENT, true)
                WidgetText(widgetString(R.string.widget_pending), 12, WidgetTone.MUTED)
            }
            RefreshButton(refresh)
        } else {
            WidgetHeader(title, refresh, R.drawable.ic_beacon_sync_notification)
            if (!data.configured) EmptyWidgetState(label, widgetString(R.string.editorial_widget_setup))
            else {
                val compact = layout == OrbitWidgetLayout.COMPACT
                WidgetText(label, if (compact) 16 else 22, tone, true, maxLines = 2)
                Spacer(GlanceModifier.height(8.dp))
                if (status != null) {
                    Column(GlanceModifier.fillMaxWidth().widgetSurface(R.drawable.widget_hero_surface, R.color.widget_primary).padding(if (compact) 10.dp else 16.dp)) {
                        WidgetText(value, if (compact) 28 else 42, WidgetTone.ON_TONAL, true)
                        WidgetText(widgetString(R.string.widget_pending), 12, WidgetTone.ON_TONAL)
                    }
                }
                Spacer(GlanceModifier.height(8.dp))
                WidgetText(widgetString(R.string.widget_last_sync,
                    data.lastSyncAt?.let(::shortDateTime) ?: widgetString(R.string.beacon_never)), 12, WidgetTone.MUTED, maxLines = 2)
                if (!compact && status == null) WidgetText(widgetString(R.string.editorial_widget_retry), 12, WidgetTone.MUTED, maxLines = 2)

            }
        }
    }
}

// The 3x2 composition also serves wider/taller widgets; smaller contracts retain their fallback.
@Composable
private fun ColumnScope.PrimaryObstruction(data: ObstructionWidgetData, title: String, value: String,
    label: String, tone: WidgetTone, refresh: Action) {
    WidgetHeader(title, refresh, showIcon = false)
    val size = LocalSize.current
    val side = widgetPrimaryMapSide(size.width.value, size.height.value)
    val readingWidth = size.width.value - 32f - side
    val font = widgetHeadlineSize(value, readingWidth,
        LocalConfiguration.current.fontScale, 40)
    Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
        SkyMap(data, side)
        Spacer(GlanceModifier.width(8.dp))
        Column(GlanceModifier.defaultWeight()) {
            WidgetText(value, font, WidgetTone.TEXT, true)
            Spacer(GlanceModifier.height(4.dp))
            WidgetText(label, 13, tone, true, maxLines = 2)
            data.presentation.coverage?.let {
                Spacer(GlanceModifier.height(4.dp))
                WidgetText(widgetString(R.string.widget_surveyed_compact, percent(it)), 11, WidgetTone.MUTED, maxLines = 2)
            }
            ObstructionTime(data)
        }
    }
}

@Composable
private fun ColumnScope.PrimaryReport(report: ReportWidgetData?, title: String, value: String,
    label: String, refresh: Action) {
    WidgetHeader(title, refresh, showIcon = false)
    if (report == null) EmptyWidgetState(label, widgetString(R.string.widget_open_reports))
    else Column(GlanceModifier.fillMaxWidth().defaultWeight()) {
        val font = widgetHeadlineSize(value, LocalSize.current.width.value - 24f,
            LocalConfiguration.current.fontScale, 48)
        WidgetText(value, font, WidgetTone.TEXT, true)
        WidgetText(label, 12, WidgetTone.TEXT, true)
        Spacer(GlanceModifier.defaultWeight())
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            WidgetText(reportDay(report.end), 11, WidgetTone.MUTED, maxLines = 2,
                modifier = GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.width(8.dp))
            if (report.coverage != null) Column(GlanceModifier.width(84.dp)
                .widgetSurface(R.drawable.widget_coverage_surface, R.color.widget_inner, 24).padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                WidgetText(percent(report.coverage), 22, WidgetTone.TEXT, true)
                WidgetText(widgetString(R.string.widget_observed_short), 11, WidgetTone.MUTED)
            } else WidgetText(widgetString(R.string.widget_coverage_unknown), 11, WidgetTone.MUTED,
                maxLines = 2, modifier = GlanceModifier.width(84.dp))
        }
    }
}

@Composable
private fun ColumnScope.PrimaryBeacon(data: BeaconWidgetData, title: String, value: String, label: String,
    state: BeaconWidgetState, pending: Long?, refresh: Action) {
    WidgetHeader(title, refresh, showIcon = false)
    if (!data.configured) EmptyWidgetState(label, widgetString(R.string.editorial_widget_setup))
    else {
        Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.defaultWeight().widgetSurface(R.drawable.widget_state_surface, R.color.widget_primary).padding(12.dp)) {
                WidgetText(widgetString(if (state == BeaconWidgetState.RECORDING)
                    R.string.widget_collection_active else R.string.widget_operational_state), 10, WidgetTone.ON_TONAL, true)
                Spacer(GlanceModifier.height(4.dp))
                WidgetText(label, 20, WidgetTone.ON_TONAL, true, maxLines = 2)
            }
            Spacer(GlanceModifier.width(8.dp))
            Column(GlanceModifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val font = widgetHeadlineSize(value, 76f,
                    LocalConfiguration.current.fontScale, 44)
                WidgetText(value, font, WidgetTone.TEXT, true)
                WidgetText(widgetString(R.string.widget_pending), 11, WidgetTone.MUTED)
            }
        }
        Spacer(GlanceModifier.height(6.dp))
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val synced = pending == 0L && data.lastSyncAt != null
            if (synced) {
                Image(ImageProvider(R.drawable.ic_widget_synced), null, GlanceModifier.size(16.dp),
                    colorFilter = ColorFilter.tint(widgetColor(R.color.widget_accent)))
                Spacer(GlanceModifier.width(4.dp))
            }
            WidgetText(widgetString(if (synced) R.string.widget_synced_short else R.string.widget_sync_time_label),
                11, WidgetTone.MUTED, modifier = GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.width(8.dp))
            WidgetText(data.lastSyncAt?.let(::shortDateTime) ?: widgetString(R.string.beacon_never),
                11, WidgetTone.MUTED, maxLines = 2, modifier = GlanceModifier.width(104.dp), align = TextAlign.End)
        }
    }
}

internal fun widgetOpen(context: Context, destination: OrbitWidgetDestination): Intent =
    Intent(context, MainActivity::class.java).putExtra("open_tab", destination.tab)

@Composable
private fun WidgetContainer(open: Intent, content: @Composable ColumnScope.() -> Unit) {
    Column(GlanceModifier.fillMaxSize().widgetSurface(R.drawable.widget_background, R.color.widget_background).appWidgetBackground()
        .cornerRadius(R.dimen.widget_outer_radius).clickable(actionStartActivity(open)).padding(12.dp)) { content() }
}

@Composable
private fun WidgetHeader(title: String, refresh: Action, icon: Int = R.drawable.ic_dish_antenna, showIcon: Boolean = true) {
    Row(GlanceModifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showIcon) {
            Image(ImageProvider(icon), null, modifier = GlanceModifier.size(24.dp),
                colorFilter = ColorFilter.tint(widgetColor(R.color.widget_accent)))
            Spacer(GlanceModifier.width(8.dp))
        }
        WidgetText(title, 14, WidgetTone.TEXT, true, modifier = GlanceModifier.defaultWeight())
        RefreshButton(refresh)
    }
}

@Composable
private fun RefreshButton(action: Action) {
    Box(GlanceModifier.size(48.dp).clickable(action), contentAlignment = Alignment.Center) {
        Image(ImageProvider(R.drawable.ic_widget_refresh), widgetString(R.string.editorial_refresh),
            modifier = GlanceModifier.size(20.dp), colorFilter = ColorFilter.tint(widgetColor(R.color.widget_muted)))
    }
}

@Composable
private fun GlanceReading(title: String, value: String, label: String, tone: WidgetTone, refresh: Action) {
    val fontScale = LocalConfiguration.current.fontScale
    val width = (LocalSize.current.width.value - 72f).coerceAtLeast(48f)
    val headline = widgetHeadlineSize(value, width, fontScale, 22)
    Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Column(GlanceModifier.defaultWeight()) {
            if (fontScale < 1.4f) WidgetText(title, 12, WidgetTone.MUTED)
            WidgetText(value, headline, tone, true)
            WidgetText(label, 12, tone)
        }
        RefreshButton(refresh)
    }
}

@Composable
private fun EmptyWidgetState(title: String, supporting: String) {
    Column(GlanceModifier.fillMaxWidth().padding(vertical = 8.dp)) {
        WidgetText(title, 18, WidgetTone.TEXT, true, maxLines = 2)
        Spacer(GlanceModifier.height(6.dp))
        WidgetText(supporting, 12, WidgetTone.MUTED, maxLines = 2)
    }
}

@Composable
private fun WidgetText(text: String, size: Int, tone: WidgetTone, bold: Boolean = false,
    maxLines: Int = 1, modifier: GlanceModifier = GlanceModifier, align: TextAlign = TextAlign.Start) {
    Text(text, modifier = modifier, maxLines = maxLines, style = TextStyle(
        color = widgetColor(tone.resource), fontSize = size.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, textAlign = align))
}

@Composable
private fun obstructionLabel(state: ObstructionWidgetState): String = widgetString(when (state) {
    ObstructionWidgetState.FREE -> R.string.editorial_free
    ObstructionWidgetState.LIGHT -> R.string.widget_light_obstruction
    ObstructionWidgetState.RELEVANT -> R.string.widget_relevant_obstruction
    ObstructionWidgetState.PARTIAL -> R.string.widget_partial_map
    ObstructionWidgetState.NO_DATA -> R.string.widget_no_data
    ObstructionWidgetState.OFFLINE -> R.string.widget_dish_offline
    ObstructionWidgetState.LOADING -> R.string.widget_loading
})

@Composable
private fun widgetString(id: Int, vararg args: Any): String = LocalContext.current.getString(id, *args)
private fun percent(value: Float, decimals: Int = if (value < 10f) 1 else 0): String =
    String.format(Locale.getDefault(), "%.${decimals}f%%", value)
private fun shortTime(timestamp: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestamp))
private fun shortDateTime(timestamp: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))
private fun reportDay(timestamp: Long) = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(timestamp))
