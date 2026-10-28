package io.github.strongsand.lshell

import android.app.Notification
import android.os.Build
import androidx.annotation.RequiresApi
import java.util.Locale

internal fun monitorMetricText(context: android.content.Context, reading: MonitorReading): String {
    fun metric(label: Int, value: Float?, unit: String) =
        "${context.getString(label)} ${value?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "—"} $unit"
    return listOf(metric(R.string.traffic_download, reading.download, "Mb/s"),
        metric(R.string.traffic_upload, reading.upload, "Mb/s"), metric(R.string.test_latency, reading.latency, "ms")).joinToString(" · ")
}

internal fun applyMonitorMetricStyle(context: android.content.Context, normal: Notification, reading: MonitorReading): Notification {
    if (Build.VERSION.SDK_INT < 37 || reading.connection != MonitorConnection.CONNECTED) return normal
    return try { MonitorMetricStyle.apply(context, normal, reading) }
    catch (_: LinkageError) { normal } catch (_: RuntimeException) { normal }
}

@RequiresApi(37)
private object MonitorMetricStyle {
    fun apply(context: android.content.Context, normal: Notification, reading: MonitorReading): Notification {
        val style = Notification.MetricStyle()
        fun add(label: Int, value: Float?, unit: String) { value?.let {
            style.addMetric(Notification.Metric(Notification.Metric.FixedFloat(it, unit), context.getString(label)))
        } }
        add(R.string.traffic_download, reading.download, "Mb/s")
        add(R.string.traffic_upload, reading.upload, "Mb/s")
        add(R.string.test_latency, reading.latency, "ms")
        // Passive telemetry has no inherently critical metric or decorative semantic color.
        style.setCriticalMetric(Notification.MetricStyle.METRIC_INDEX_NONE)
        return Notification.Builder.recoverBuilder(context, normal).setStyle(style)
            .setRequestPromotedOngoing(false).build()
    }
}
