package io.github.strongsand.lshell

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.provideContent
import io.github.strongsand.lshell.core.network.grpc.DishyGrpcClient
import io.github.strongsand.lshell.beacon.AndroidBeaconLanClient
import io.github.strongsand.lshell.beacon.BeaconPreferences
import io.github.strongsand.lshell.beacon.BeaconRuntimeStatus
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

internal val orbitWidgetSizes = OrbitWidgetLayout.entries.map {
    DpSize(it.width.dp, it.height.dp)
}.toSet()

internal data class ObstructionWidgetData(
    val map: DishyGrpcClient.ObstructionMapReading?,
    val bitmap: Bitmap?,
    val presentation: ObstructionPresentation,
    val updatedAt: Long?
)

internal data class BeaconWidgetData(
    val configured: Boolean,
    val status: BeaconRuntimeStatus?,
    val lastSyncAt: Long?
)

internal data class ReportWidgetData(
    val end: Long, val createdAt: Long, val summary: String,
    val availability: Float?, val coverage: Float?,
    val interruptions: Int, val alerts: Int, val latency: Float?
)

internal fun DailyReport.widgetData() = ReportWidgetData(
    end, createdAt, summary,
    availabilityPercent?.takeIf { it.isFinite() && it in 0f..100f },
    widgetReportCoverage(start, end, monitoredMs), interruptions.size, alerts.size,
    latency?.average?.takeIf { it.isFinite() && it >= 0f }
)

class ObstructionWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(orbitWidgetSizes)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val fallback = withContext(Dispatchers.IO) {
            HistoryStore(context).use { store ->
                store.recent(40).firstOrNull { it.kind == "reading" &&
                    it.obstructionPercent.isFinite() && it.obstructionPercent in 0f..100f }
            }
        }
        val client = DishyGrpcClient(timeoutMs = 5_000L)
        val mapResult: Result<DishyGrpcClient.ObstructionMapReading>
        val statusResult: Result<io.github.strongsand.lshell.core.model.DishySnapshot>
        try {
            mapResult = client.fetchObstructionMap()
            statusResult = client.fetchStatus()
        } finally {
            client.close()
        }
        val map = mapResult.getOrNull()?.takeIf { validSkyGrid(it.rows, it.columns, it.usableFractions) }
        val statusPercent = statusResult.getOrNull()?.obstruction?.obstructionPercentage
            ?.takeIf { it.isFinite() && it in 0f..100f }
        val cached = statusPercent == null && fallback != null
        val now = System.currentTimeMillis()
        val data = withContext(Dispatchers.Default) {
            ObstructionWidgetData(
                map, map?.let { obstructionBitmap(context, it) },
                obstructionPresentation(statusPercent ?: fallback?.obstructionPercent,
                    map?.let { skyCoverage(it.rows, it.columns, it.usableFractions) },
                    mapResult.isFailure && statusResult.isFailure, cached),
                if (cached) fallback?.timestamp else now.takeIf { map != null || statusPercent != null }
            )
        }
        provideContent { ObstructionWidgetContent(context, data) }
    }
}

class ReportWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(orbitWidgetSizes)
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val report = withContext(Dispatchers.IO) { DailyReportStore(context).all().firstOrNull()?.widgetData() }
        provideContent { ReportWidgetContent(context, report) }
    }
}

class BeaconWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(orbitWidgetSizes)
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = withContext(Dispatchers.IO) {
            val preferences = BeaconPreferences(context)
            val device = preferences.configuredBeacon()
            val status = device?.let { AndroidBeaconLanClient().status(it).getOrNull() }
            BeaconWidgetData(device != null, status, preferences.lastSyncAtMillis() ?: status?.lastSyncAtMillis)
        }
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
    publishOrbitWidgetPreviews(context)
}


/** One bounded bitmap per update, shared by all responsive layouts. No renderer in composition. */
internal fun obstructionBitmap(context: Context, map: DishyGrpcClient.ObstructionMapReading): Bitmap {
    require(validSkyGrid(map.rows, map.columns, map.usableFractions))
    val side = widgetBitmapSide(176f, context.resources.displayMetrics.density)
    val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val appearance = ExperiencePreferences.read(context)
    fun color(resource: Int) = widgetAppearanceColor(resource, appearance, context.getColor(resource))
    val center = side / 2f
    val radiusPx = side * 0.46f
    val columnCenter = (map.columns - 1) / 2f
    val rowCenter = (map.rows - 1) / 2f
    val gridRadius = min(columnCenter, rowCenter)
    val pointRadius = (radiusPx / gridRadius * 0.58f).coerceAtLeast(0.6f)
    paint.color = color(R.color.widget_inner)
    canvas.drawCircle(center, center, radiusPx, paint)
    map.usableFractions.forEachIndexed { index, usable ->
        val x = (index % map.columns - columnCenter) / gridRadius
        val y = (rowCenter - index / map.columns) / gridRadius
        if (x * x + y * y > 1f) return@forEachIndexed
        val cell = skyCell(usable)
        paint.color = color(when (cell) {
            SkyCell.UNKNOWN -> R.color.widget_muted
            SkyCell.FREE -> R.color.widget_online
            SkyCell.PARTIAL -> R.color.widget_accent
            SkyCell.BLOCKED -> R.color.widget_warning
        })
        paint.alpha = if (cell == SkyCell.UNKNOWN) 65 else 255
        paint.style = if (appearance.monochrome && cell == SkyCell.PARTIAL) Paint.Style.STROKE else Paint.Style.FILL
        paint.strokeWidth = pointRadius.coerceAtLeast(1f)
        val px = center + x * radiusPx
        val py = center - y * radiusPx
        // Shape supplements color: blocked squares, usable circles, muted unsurveyed dots.
        if (cell == SkyCell.BLOCKED) canvas.drawRect(px-pointRadius, py-pointRadius,
            px+pointRadius, py+pointRadius, paint)
        else canvas.drawCircle(px, py, pointRadius, paint)
    }
    paint.alpha = 255
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = side * 0.006f
    paint.color = color(R.color.widget_muted)
    canvas.drawCircle(center, center, radiusPx, paint)
    return bitmap
}
