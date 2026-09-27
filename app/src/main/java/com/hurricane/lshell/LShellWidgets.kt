package com.hurricane.lshell

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
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
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.hurricane.lshell.core.network.grpc.DishyGrpcClient
import com.hurricane.lshell.beacon.AndroidBeaconLanClient
import com.hurricane.lshell.beacon.BeaconPreferences
import com.hurricane.lshell.beacon.BeaconRuntimeStatus
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

private enum class WidgetTone(val resource: Int) {
    ACCENT(R.color.widget_accent),
    SECONDARY(R.color.widget_secondary),
    TEXT(R.color.widget_text),
    MUTED(R.color.widget_muted),
    GOOD(R.color.widget_online),
    WARNING(R.color.widget_warning)
}

private data class ObstructionWidgetData(
    val map: DishyGrpcClient.ObstructionMapReading?,
    val obstructionPercent: Float?,
    val updatedAt: Long,
    val error: Boolean
)

private data class BeaconWidgetData(
    val configured: Boolean,
    val status: BeaconRuntimeStatus?,
    val lastSyncAt: Long?
)

class ObstructionWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val fallback = withContext(Dispatchers.IO) {
            HistoryStore(context).use { store ->
                store.recent(40).firstOrNull { it.kind == "reading" }
            }
        }
        val client = DishyGrpcClient(timeoutMs = 5_000L)
        var mapResult: Result<DishyGrpcClient.ObstructionMapReading> =
            Result.failure(IllegalStateException("Consulta não iniciada"))
        var statusPercent: Float? = null
        try {
            mapResult = client.fetchObstructionMap()
            statusPercent = client.fetchStatus().getOrNull()?.obstruction?.obstructionPercentage
                ?.takeIf { it.isFinite() }
        } finally {
            client.close()
        }
        val data = ObstructionWidgetData(
            map = mapResult.getOrNull(),
            obstructionPercent = statusPercent ?: fallback?.obstructionPercent?.takeIf { it.isFinite() },
            updatedAt = System.currentTimeMillis(),
            error = mapResult.isFailure
        )
        provideContent { ObstructionWidgetContent(context, data) }
    }
}

class ReportWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val report = withContext(Dispatchers.IO) { DailyReportStore(context).all().firstOrNull() }
        provideContent { ReportWidgetContent(context, report) }
    }
}

class BeaconWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val preferences = BeaconPreferences(context)
        val device = preferences.configuredBeacon()
        val status = device?.let { AndroidBeaconLanClient().status(it).getOrNull() }
        val data = BeaconWidgetData(
            configured = device != null,
            status = status,
            lastSyncAt = preferences.lastSyncAtMillis() ?: status?.lastSyncAtMillis
        )
        provideContent { BeaconWidgetContent(context, data) }
    }
}

class ObstructionWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ObstructionWidget()
}

class ReportWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ReportWidget()
}

class BeaconWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BeaconWidget()
}

class RefreshObstructionWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        ObstructionWidget().update(context, glanceId)
    }
}

class RefreshReportWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        ReportWidget().update(context, glanceId)
    }
}

class RefreshBeaconWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        BeaconWidget().update(context, glanceId)
    }
}

private suspend fun refreshWidget(
    context: Context,
    receiver: Class<out GlanceAppWidgetReceiver>,
    widget: GlanceAppWidget
) {
    val host = AppWidgetManager.getInstance(context)
    val glance = GlanceAppWidgetManager(context)
    host.getAppWidgetIds(ComponentName(context, receiver)).forEach { appWidgetId ->
        try {
            widget.update(context, glance.getGlanceIdBy(appWidgetId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A falha de um widget não pode impedir o relatório ou a abertura do app.
        }
    }
}

suspend fun refreshReportWidget(context: Context) {
    refreshWidget(context, ReportWidgetReceiver::class.java, ReportWidget())
}

suspend fun refreshBeaconWidget(context: Context) {
    refreshWidget(context, BeaconWidgetReceiver::class.java, BeaconWidget())
}

/** Atualização somente por evento: abertura do app ou botões manuais dos widgets. */
suspend fun refreshLShellWidgets(context: Context) {
    refreshWidget(context, ObstructionWidgetReceiver::class.java, ObstructionWidget())
    refreshReportWidget(context)
    refreshBeaconWidget(context)
}

@Composable
private fun BeaconWidgetContent(context: Context, data: BeaconWidgetData) {
    val size = LocalSize.current
    val compact = size.width < 220.dp || size.height < 170.dp
    val open = Intent(context, MainActivity::class.java).putExtra("open_tab", "BEACON")
    WidgetContainer(open) {
        WidgetHeader("L-Shell Beacon", actionRunCallback<RefreshBeaconWidgetAction>(), R.drawable.ic_beacon_placeholder)
        Spacer(GlanceModifier.height(8.dp))
        val status = data.status
        when {
            !data.configured -> EmptyWidgetState("Beacon não configurado", "Abra o app para iniciar a configuração.")
            status == null -> EmptyWidgetState("Beacon offline", "Não foi possível encontrá-lo na rede local.")
            else -> {
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(GlanceModifier.defaultWeight()) {
                        Text(if (status.recording) "● Online · Registrando" else "● Online",
                            style = widgetStyle(if (compact) 14 else 16, WidgetTone.GOOD, true), maxLines = 1)
                        Spacer(GlanceModifier.height(4.dp))
                        Text(status.device.name, style = widgetStyle(11, WidgetTone.MUTED), maxLines = 1)
                    }
                    WidgetPill(
                        status.pendingRecords?.let { "$it pendentes" } ?: "Pendências —",
                        if ((status.pendingRecords ?: 0L) > 0L) WidgetTone.ACCENT else WidgetTone.GOOD
                    )
                }
                Spacer(GlanceModifier.height(9.dp))
                Row(GlanceModifier.fillMaxWidth()) {
                    ReportMetric("Dishy", when (status.dishyAvailable) {
                        true -> "Acessível"
                        false -> "Sem acesso"
                        null -> "—"
                    }, if (status.dishyAvailable == false) WidgetTone.WARNING else WidgetTone.SECONDARY,
                        GlanceModifier.defaultWeight())
                    Spacer(GlanceModifier.width(7.dp))
                    ReportMetric("Último sync", data.lastSyncAt?.let(::shortTime) ?: "Nunca",
                        WidgetTone.ACCENT, GlanceModifier.defaultWeight())
                }
                if (!compact) {
                    Spacer(GlanceModifier.height(7.dp))
                    Text("Atualização por abertura do app ou toque em ↻",
                        style = widgetStyle(10, WidgetTone.MUTED), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ObstructionWidgetContent(context: Context, data: ObstructionWidgetData) {
    val size = LocalSize.current
    val compact = size.width < 220.dp || size.height < 180.dp
    val open = Intent(context, MainActivity::class.java).putExtra("open_tab", "DISH")
    WidgetContainer(open) {
        WidgetHeader(
            title = "Obstrução",
            refresh = actionRunCallback<RefreshObstructionWidgetAction>()
        )
        Spacer(GlanceModifier.height(8.dp))
        val map = data.map
        if (map == null) {
            EmptyWidgetState(
                if (data.error) "Não foi possível alcançar a antena" else "Mapa ainda indisponível",
                data.obstructionPercent?.let { "Última leitura: ${percent(it)}" }
            )
        } else {
            val coverage = mapCoverage(map)
            val bitmap = obstructionBitmap(context, map)
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Image(
                    provider = ImageProvider(bitmap),
                    contentDescription = "Mapa de obstrução, ${percent(coverage)} analisado",
                    modifier = GlanceModifier.size(if (compact) 76.dp else 108.dp),
                    contentScale = ContentScale.Fit
                )
                Spacer(GlanceModifier.width(12.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Text(
                        data.obstructionPercent?.let { percent(it) } ?: "—",
                        style = widgetStyle(if (compact) 25 else 32, obstructionTone(data.obstructionPercent), true),
                        maxLines = 1
                    )
                    Text("obstruído", style = widgetStyle(11, WidgetTone.MUTED), maxLines = 1)
                    Spacer(GlanceModifier.height(7.dp))
                    WidgetPill(
                        if (coverage >= 99.5f) "Mapa completo" else "${percent(coverage)} analisado",
                        if (coverage >= 80f) WidgetTone.GOOD else WidgetTone.ACCENT
                    )
                }
            }
            if (!compact) {
                Spacer(GlanceModifier.height(8.dp))
                ObstructionLegend()
            }
        }
        Spacer(GlanceModifier.height(7.dp))
        Text(
            "${if (data.error) "Tentativa" else "Atualizado"} ${shortTime(data.updatedAt)} · toque em ↻ para consultar",
            style = widgetStyle(10, WidgetTone.MUTED),
            maxLines = 1
        )
    }
}

@Composable
private fun ReportWidgetContent(context: Context, report: DailyReport?) {
    val size = LocalSize.current
    val compact = size.width < 250.dp || size.height < 175.dp
    val open = Intent(context, MainActivity::class.java).putExtra("open_tab", "REPORTS").apply {
        report?.let { putExtra("report_end", it.end) }
    }
    WidgetContainer(open) {
        WidgetHeader(
            title = "Último relatório",
            refresh = actionRunCallback<RefreshReportWidgetAction>()
        )
        Spacer(GlanceModifier.height(8.dp))
        if (report == null) {
            EmptyWidgetState(
                "Nenhum relatório salvo",
                "O resumo aparecerá após o próximo relatório diário."
            )
        } else {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(
                    GlanceModifier.background(R.color.widget_primary).cornerRadius(24.dp)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        report.availabilityPercent?.let { percent(it, 2) } ?: "—",
                        style = widgetStyle(if (compact) 23 else 29, WidgetTone.ACCENT, true),
                        maxLines = 1
                    )
                    Text("disponível", style = widgetStyle(10, WidgetTone.MUTED), maxLines = 1)
                }
                Spacer(GlanceModifier.width(10.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Text(reportDay(report.end), style = widgetStyle(13, WidgetTone.TEXT, true), maxLines = 1)
                    Text(report.summary, style = widgetStyle(11, WidgetTone.MUTED), maxLines = if (compact) 2 else 3)
                }
            }
            Spacer(GlanceModifier.height(if (compact) 6.dp else 9.dp))
            if (compact) {
                WidgetPill(
                    "${report.interruptions.size} interrupções · ${report.alerts.size} alertas",
                    if (report.interruptions.isEmpty() && report.alerts.isEmpty()) WidgetTone.GOOD else WidgetTone.WARNING
                )
            } else {
                Row(GlanceModifier.fillMaxWidth()) {
                    ReportMetric("Interrupções", report.interruptions.size.toString(), WidgetTone.WARNING, GlanceModifier.defaultWeight())
                    Spacer(GlanceModifier.width(7.dp))
                    ReportMetric("Alertas", report.alerts.size.toString(), WidgetTone.SECONDARY, GlanceModifier.defaultWeight())
                    Spacer(GlanceModifier.width(7.dp))
                    ReportMetric("Latência média",
                        report.latency?.average?.let { String.format(Locale.getDefault(), "%.0f ms", it) } ?: "—",
                        WidgetTone.ACCENT, GlanceModifier.defaultWeight())
                }
            }
            if (!compact) {
                Spacer(GlanceModifier.height(7.dp))
                Text("Gerado ${shortDateTime(report.createdAt)}", style = widgetStyle(10, WidgetTone.MUTED), maxLines = 1)
            }
        }
    }
}

@Composable
private fun WidgetContainer(open: Intent, content: @Composable () -> Unit) {
    Column(
        modifier = GlanceModifier.fillMaxSize().background(R.color.widget_background)
            .appWidgetBackground().cornerRadius(R.dimen.widget_outer_radius)
            .clickable(actionStartActivity(open)).padding(14.dp),
        verticalAlignment = Alignment.Top,
        horizontalAlignment = Alignment.Start
    ) { content() }
}

@Composable
private fun WidgetHeader(title: String, refresh: Action, icon: Int = R.drawable.ic_dish_antenna) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(
            ImageProvider(icon),
            contentDescription = null,
            colorFilter = ColorFilter.tint(ColorProvider(R.color.widget_accent)),
            modifier = GlanceModifier.size(25.dp)
        )
        Spacer(GlanceModifier.width(8.dp))
        Text(title, modifier = GlanceModifier.defaultWeight(), style = widgetStyle(16, WidgetTone.TEXT, true), maxLines = 1)
        Text(
            "↻",
            modifier = GlanceModifier.background(R.color.widget_primary).cornerRadius(50.dp)
                .clickable(refresh).padding(horizontal = 10.dp, vertical = 6.dp),
            style = widgetStyle(18, WidgetTone.ACCENT, true),
            maxLines = 1
        )
    }
}

@Composable
private fun EmptyWidgetState(title: String, supporting: String?) {
    Column(
        GlanceModifier.fillMaxWidth().background(R.color.widget_inner).cornerRadius(R.dimen.widget_inner_radius)
            .padding(13.dp)
    ) {
        Text(title, style = widgetStyle(14, WidgetTone.TEXT, true), maxLines = 2)
        supporting?.let {
            Spacer(GlanceModifier.height(5.dp))
            Text(it, style = widgetStyle(11, WidgetTone.MUTED), maxLines = 3)
        }
    }
}

@Composable
private fun WidgetPill(label: String, tone: WidgetTone) {
    Text(
        label,
        modifier = GlanceModifier.background(R.color.widget_inner).cornerRadius(50.dp)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        style = widgetStyle(10, tone, true),
        maxLines = 1
    )
}

@Composable
private fun ObstructionLegend() {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("● Livre", style = widgetStyle(10, WidgetTone.GOOD, true), maxLines = 1)
        Spacer(GlanceModifier.width(10.dp))
        Text("● Parcial", style = widgetStyle(10, WidgetTone.ACCENT, true), maxLines = 1)
        Spacer(GlanceModifier.width(10.dp))
        Text("● Obstruído", style = widgetStyle(10, WidgetTone.WARNING, true), maxLines = 1)
    }
}

@Composable
private fun ReportMetric(label: String, value: String, tone: WidgetTone, modifier: GlanceModifier) {
    Column(modifier.background(R.color.widget_inner).cornerRadius(R.dimen.widget_inner_radius).padding(9.dp)) {
        Text(label, style = widgetStyle(10, WidgetTone.MUTED), maxLines = 1)
        Spacer(GlanceModifier.height(3.dp))
        Text(value, style = widgetStyle(16, tone, true), maxLines = 1)
    }
}

private fun widgetStyle(size: Int, tone: WidgetTone, bold: Boolean = false) = TextStyle(
    color = ColorProvider(tone.resource),
    fontSize = size.sp,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
)

private fun obstructionTone(value: Float?): WidgetTone = when {
    value == null -> WidgetTone.MUTED
    value >= 5f -> WidgetTone.WARNING
    value >= 1f -> WidgetTone.ACCENT
    else -> WidgetTone.GOOD
}

private fun percent(value: Float, decimals: Int = if (value < 10f) 1 else 0): String =
    String.format(Locale.getDefault(), "%.${decimals}f%%", value)

private fun shortTime(timestamp: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestamp))

private fun shortDateTime(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))

private fun reportDay(timestamp: Long): String =
    SimpleDateFormat("EEE, d 'de' MMM", Locale.getDefault()).format(Date(timestamp))

private fun mapCoverage(map: DishyGrpcClient.ObstructionMapReading): Float {
    val columnCenter = (map.columns - 1) / 2f
    val rowCenter = (map.rows - 1) / 2f
    val radius = min(columnCenter, rowCenter).coerceAtLeast(1f)
    var sky = 0
    var surveyed = 0
    map.usableFractions.forEachIndexed { index, usable ->
        val row = index / map.columns
        val column = index % map.columns
        val x = (column - columnCenter) / radius
        val y = (rowCenter - row) / radius
        if (x * x + y * y <= 1f) {
            sky++
            if (usable >= 0f) surveyed++
        }
    }
    return surveyed * 100f / sky.coerceAtLeast(1)
}

private fun obstructionBitmap(context: Context, map: DishyGrpcClient.ObstructionMapReading): Bitmap {
    val side = 320
    val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val center = side / 2f
    val radiusPx = side * 0.46f
    val columnCenter = (map.columns - 1) / 2f
    val rowCenter = (map.rows - 1) / 2f
    val gridRadius = min(columnCenter, rowCenter).coerceAtLeast(1f)
    val pointRadius = (radiusPx / gridRadius * 0.62f).coerceAtLeast(1.2f)
    val colors = intArrayOf(
        context.getColor(R.color.widget_muted),
        context.getColor(R.color.widget_online),
        context.getColor(R.color.widget_accent),
        context.getColor(R.color.widget_warning)
    )
    paint.color = context.getColor(R.color.widget_inner)
    canvas.drawCircle(center, center, radiusPx, paint)
    map.usableFractions.forEachIndexed { index, usable ->
        val row = index / map.columns
        val column = index % map.columns
        val x = (column - columnCenter) / gridRadius
        val y = (rowCenter - row) / gridRadius
        if (x * x + y * y > 1.02f) return@forEachIndexed
        val blocked = 1f - usable
        val kind = when {
            usable < 0f -> 0
            blocked <= 0.005f -> 1
            blocked <= 0.25f -> 2
            else -> 3
        }
        paint.color = colors[kind]
        paint.alpha = if (kind == 0) 55 else 255
        canvas.drawCircle(center + x * radiusPx, center - y * radiusPx, pointRadius, paint)
    }
    paint.alpha = 255
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 5f
    paint.color = context.getColor(R.color.widget_accent)
    canvas.drawCircle(center, center, radiusPx, paint)
    return bitmap
}
