package io.github.strongsand.lshell

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.widget.RemoteViews
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.provideContent
import io.github.strongsand.lshell.beacon.BeaconDevice
import io.github.strongsand.lshell.beacon.BeaconRuntimeStatus
import io.github.strongsand.lshell.core.network.grpc.DishyGrpcClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Synthetic, auditable picker/development samples. Never used as live acquisition fallbacks. */
internal object OrbitWidgetSamples {
    const val TIME = 1_791_621_660_000L
    enum class Obstruction { ZERO, TRACE, LIGHT, RELEVANT, PARTIAL, MISSING, OFFLINE, LOADING }
    enum class Beacon { HEALTHY, BACKLOG, OFFLINE, NEVER_SYNCED }
    enum class Report { GOOD, BAD, PARTIAL, EMPTY }

    fun obstruction(sample: Obstruction, context: Context? = null): ObstructionWidgetData {
        val percent = when (sample) {
            Obstruction.ZERO -> 0f
            Obstruction.TRACE -> 0.1f
            Obstruction.LIGHT -> 5f
            Obstruction.RELEVANT -> 18f
            Obstruction.PARTIAL -> 1.5f
            else -> null
        }
        val map = if (sample in setOf(Obstruction.MISSING, Obstruction.OFFLINE, Obstruction.LOADING)) null
        else DishyGrpcClient.ObstructionMapReading(21, 21, List(441) { index ->
            val x = index % 21
            val y = index / 21
            when {
                sample == Obstruction.PARTIAL && x < 10 -> -1f
                sample == Obstruction.ZERO -> 1f
                x > 15 && y in 8..12 -> 0f
                else -> 1f
            }
        }, null, DishyGrpcClient.ObstructionMapReading.ReferenceFrame.UNKNOWN)
        return ObstructionWidgetData(map, if (map != null && context != null) obstructionBitmap(context, map) else null,
            obstructionPresentation(percent, map?.let { skyCoverage(it.rows, it.columns, it.usableFractions) },
                offline = sample == Obstruction.OFFLINE, loading = sample == Obstruction.LOADING),
            TIME.takeUnless { sample == Obstruction.MISSING || sample == Obstruction.LOADING })
    }

    fun beacon(sample: Beacon): BeaconWidgetData {
        val status = if (sample == Beacon.OFFLINE) null else BeaconRuntimeStatus(
            device = BeaconDevice("preview-only", "L-Shell Beacon", "", 80),
            recording = true, dishyAvailable = true,
            pendingRecords = if (sample == Beacon.BACKLOG) 213L else 0L)
        return BeaconWidgetData(true, status, TIME.takeUnless { sample == Beacon.NEVER_SYNCED })
    }

    fun report(sample: Report): ReportWidgetData? = if (sample == Report.EMPTY) null else ReportWidgetData(
        end = TIME, createdAt = TIME, summary = when (sample) {
            Report.BAD -> "Foram detectadas interrupções relevantes"
            Report.PARTIAL -> "O período teve monitoramento parcial"
            else -> "Conexão estável durante o período monitorado"
        }, availability = if (sample == Report.BAD) 91.2f else 99.9f,
        coverage = if (sample == Report.PARTIAL) 45.5f else 98.5f,
        interruptions = if (sample == Report.BAD) 4 else 0,
        alerts = if (sample == Report.BAD) 3 else 0, latency = 24f)
}

/** No extra receiver/provider: this only composes the existing content for the picker. */
internal class OrbitWidgetPreview(private val destination: OrbitWidgetDestination,
    private val obstruction: OrbitWidgetSamples.Obstruction = OrbitWidgetSamples.Obstruction.PARTIAL,
    private val report: OrbitWidgetSamples.Report = OrbitWidgetSamples.Report.GOOD,
    private val beacon: OrbitWidgetSamples.Beacon = OrbitWidgetSamples.Beacon.HEALTHY
) : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val obstructionData = if (destination == OrbitWidgetDestination.OBSTRUCTION)
            withContext(Dispatchers.Default) { OrbitWidgetSamples.obstruction(obstruction, context) } else null
        provideContent {
            when (destination) {
                OrbitWidgetDestination.OBSTRUCTION -> ObstructionWidgetContent(context, requireNotNull(obstructionData))
                OrbitWidgetDestination.REPORT -> ReportWidgetContent(context, OrbitWidgetSamples.report(report))
                OrbitWidgetDestination.BEACON -> BeaconWidgetContent(context, OrbitWidgetSamples.beacon(beacon))
            }
        }
    }
}

private val previewMutex = Mutex()
private const val PREVIEW_REVISION = 2

/** Foreground event only. Platform rate limits may postpone the third preview until a later opening. */
internal suspend fun publishOrbitWidgetPreviews(context: Context) {
    if (Build.VERSION.SDK_INT < 35) return
    previewMutex.withLock {
        val prefs = context.getSharedPreferences("orbit_widget_previews", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now < prefs.getLong("retry_after", 0L)) return@withLock
        val key = "$PREVIEW_REVISION:${context.resources.configuration.locales.toLanguageTags()}:" +
            "${context.resources.configuration.uiMode}:${context.getColor(R.color.widget_primary)}"
        val manager = AppWidgetManager.getInstance(context)
        for (destination in OrbitWidgetDestination.entries) {
            if (prefs.getString(destination.name, null) == key) continue
            try {
                // One canonical size per provider. Compose the real UI, without RPC/DB/identity data.
                val remoteViews: RemoteViews = OrbitWidgetPreview(destination).compose(context,
                    size = DpSize(300.dp, 208.dp))
                val receiver = when (destination) {
                    OrbitWidgetDestination.OBSTRUCTION -> ObstructionWidgetReceiver::class.java
                    OrbitWidgetDestination.REPORT -> ReportWidgetReceiver::class.java
                    OrbitWidgetDestination.BEACON -> BeaconWidgetReceiver::class.java
                }
                if (!manager.setWidgetPreview(ComponentName(context, receiver),
                        AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN, remoteViews)) {
                    prefs.edit().putLong("retry_after", now + 3_600_000L).apply()
                    break
                }
                prefs.edit().putString(destination.name, key).apply()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Picker improvements must not break real updates. XML/image previews remain available.
                prefs.edit().putLong("retry_after", now + 3_600_000L).apply()
                break
            }
        }
    }
}
