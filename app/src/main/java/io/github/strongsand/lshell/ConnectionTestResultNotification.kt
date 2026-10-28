package io.github.strongsand.lshell

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import java.util.Locale

/** Layout policy only; execution, persistence and result navigation remain service-owned. */
internal object ConnectionTestNotificationUi {
    fun usesProgressStyle(state: ConnectionTestState, api: Int) =
        state.active && api >= 36

    fun requestsPromotion(state: ConnectionTestState, api: Int, allowed: Boolean, demoted: Boolean) =
        state.active && api >= 36 && allowed && !demoted

    fun usesCustomResult(state: ConnectionTestState, api: Int, fontScale: Float, widthDp: Int) =
        state.id > 0 && state.phase == ConnectionTestPhase.COMPLETED && api >= 26 &&
            // Enlarged labels can wrap beyond the host's custom-content height limit.
            fontScale in 0.5f..1f && widthDp >= 360 && when (state.kind) {
                ConnectionTestKind.NORMAL -> ConnectionTestPresentation.metrics(state) != TestMetrics()
                ConnectionTestKind.EXTREME -> state.extreme?.let {
                    it.id == state.id && it.runs.isNotEmpty()
                } == true
            }

    // The installed framework merges >10 segments. Preserve the entire denominator
    // for longer sessions instead of dropping rounds or adding decorative points.
    fun segmentLengths(state: ConnectionTestState): List<Int> {
        val total = ExtremeTestNotificationModel.progress(state)?.first ?: return emptyList()
        return if (total <= 10) List(total) { 1 } else listOf(total)
    }
}

internal data class ConnectionTestResultModel(
    val title: Int,
    val metrics: TestMetrics,
    val averages: Boolean,
    val durationSeconds: Long?,
    val statusIcon: Int
) {
    companion object {
        fun from(state: ConnectionTestState): ConnectionTestResultModel {
            val stats = ExtremeTestNotificationModel.resultStats(state)
            val title = when (state.phase) {
                ConnectionTestPhase.COMPLETED -> if (ConnectionTestExperience.outcome(state) == TestOutcome.NO_MEASUREMENTS)
                    R.string.test_no_result else stats?.let(ExtremeTestNotificationModel::qualityLabel) ?: R.string.test_done
                ConnectionTestPhase.FAILED -> R.string.test_failed
                else -> R.string.test_interrupted
            }
            val runs = state.extreme?.runs.orEmpty().filter { it.failure == null }
            val metrics = if (stats != null) TestMetrics(
                ConnectionTestPresentation.valid(stats.downloadAverage).takeIf { runs.any {
                    it.downloadMeasured != false && ConnectionTestPresentation.valid(it.downloadMbps) != null } },
                ConnectionTestPresentation.valid(stats.uploadAverage).takeIf { runs.any {
                    it.uploadMeasured != false && ConnectionTestPresentation.valid(it.uploadMbps) != null } },
                ConnectionTestPresentation.valid(stats.latencyAverage).takeIf { runs.any {
                    ConnectionTestPresentation.valid(it.latencyMs) != null } }
            ) else ConnectionTestPresentation.metrics(state)
            val duration = if (state.startedAt > 0 && state.endedAt >= state.startedAt)
                (state.endedAt - state.startedAt) / 1000 else null
            val icon = when (title) {
                R.string.test_quality_excellent, R.string.test_quality_good -> R.drawable.ic_test_result_check
                R.string.test_quality_variable, R.string.test_quality_unstable,
                R.string.test_failed -> R.drawable.ic_test_result_warning
                R.string.test_interrupted -> R.drawable.ic_test_result_interrupted
                else -> if (state.kind == ConnectionTestKind.NORMAL &&
                    ConnectionTestExperience.outcome(state) == TestOutcome.SUCCESS)
                    R.drawable.ic_test_result_check else R.drawable.ic_test_result_info
            }
            return ConnectionTestResultModel(title, metrics, stats != null, duration, icon)
        }
    }
}

internal fun testNotificationDuration(context: Context, seconds: Long): String = when {
    seconds >= 3600 -> context.getString(R.string.test_elapsed_hours, seconds / 3600, seconds / 60 % 60)
    seconds >= 60 -> context.getString(R.string.test_elapsed_minutes, seconds / 60, seconds % 60)
    else -> context.getString(R.string.test_elapsed_seconds, seconds)
}

/** RemoteViews are restricted to completed, dismissible notifications. */
internal object ConnectionTestResultNotification {
    fun build(context: Context, channel: String, state: ConnectionTestState, open: PendingIntent): Notification {
        val model = ConnectionTestResultModel.from(state)
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val title = context.getString(model.title)
        val subtitle = when {
            state.kind == ConnectionTestKind.EXTREME -> {
                val rounds = context.getString(R.string.test_rounds_done, state.completed, state.total)
                val failed = state.runs.count { it.failure != null }
                if (failed > 0) "$rounds · ${context.getString(R.string.test_rounds_without_result, failed)}" else rounds
            }
            state.phase == ConnectionTestPhase.COMPLETED -> ""
            else -> ""
        }
        val unavailable = context.getString(R.string.test_metric_unavailable)
        fun number(value: Float?) = value?.let { String.format(locale, "%.1f", it) }
        val down = number(model.metrics.download)?.let { context.getString(R.string.test_rate_value, it) }
        val up = number(model.metrics.upload)?.let { context.getString(R.string.test_rate_value, it) }
        val latency = number(model.metrics.latency)?.let { context.getString(R.string.test_latency_value, it) }
        val duration = model.durationSeconds?.let { testNotificationDuration(context, it) }
        val labels = listOf(
            if (model.averages) R.string.test_average_download else R.string.traffic_download,
            if (model.averages) R.string.test_average_upload else R.string.traffic_upload,
            if (model.averages) R.string.test_average_latency else R.string.test_latency,
            R.string.test_total_duration
        ).map(context::getString)
        val values = listOf(down, up, latency, duration)
        val detail = when (state.phase) {
            ConnectionTestPhase.FAILED -> context.getString(R.string.test_failed_detail)
            ConnectionTestPhase.INTERRUPTED -> context.getString(R.string.test_interrupted_detail)
            else -> null
        }
        val metricLines = labels.zip(values).mapNotNull { (label, value) -> value?.let { "$label  $it" } }
        val text = listOfNotNull(subtitle.takeIf { it.isNotEmpty() }, detail,
            context.getString(R.string.test_recent_round_label).takeIf {
                state.kind == ConnectionTestKind.EXTREME && !model.averages &&
                    listOf(model.metrics.download, model.metrics.upload, model.metrics.latency).any { it != null }
            }) + metricLines
        val compactMetrics = ExtremeTestNotificationModel.compactMetrics(model.metrics, locale)
        val compact = listOfNotNull(subtitle.takeIf { it.isNotEmpty() },
            compactMetrics.takeIf { it.isNotEmpty() }, duration).joinToString(" · ")
        fun base() = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_speed_test_notification)
            .setContentTitle(title).setContentText(compact.ifBlank { detail ?: unavailable })
            .setSubText(context.getString(if (state.kind == ConnectionTestKind.EXTREME)
                R.string.function_extreme else R.string.product_normal_test))
            .setTicker(listOf(context.getString(ConnectionTestExperience.phaseLabel(state)), title, subtitle, detail)
                .filterNotNull().filter { it.isNotBlank() }.distinct().joinToString(". "))
            .setContentIntent(open).setOngoing(false).setAutoCancel(true)
            .setOnlyAlertOnce(true).setSilent(true).setLocalOnly(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_speed_test_notification)
                .setContentTitle(context.getString(R.string.test_channel)).build())
            .setShowWhen(false)
            // Keep one system-sized, accessible action and the original result PendingIntent.
            .addAction(R.drawable.ic_result_navigation, context.getString(if (state.phase == ConnectionTestPhase.COMPLETED)
                R.string.test_view_results else R.string.test_open), open)
        // A large icon reserves trailing space for the entire decorated custom
        // content. That content already has its own status icon; keep the large
        // icon only in the native text fallback, where Android lays out the text.
        fun fallback() = base()
            .setLargeIcon(ContextCompat.getDrawable(context, model.statusIcon)?.toBitmap(
                (48 * context.resources.displayMetrics.density).toInt(),
                (48 * context.resources.displayMetrics.density).toInt()))
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(text.joinToString("\n").ifBlank { unavailable })).build()
        val configuration = context.resources.configuration
        if (!ConnectionTestNotificationUi.usesCustomResult(state, Build.VERSION.SDK_INT,
                configuration.fontScale, configuration.screenWidthDp)) return fallback()
        return try {
            val small = RemoteViews(context.packageName, R.layout.notification_test_result_compact).apply {
                setTextViewText(R.id.test_result_title, title)
                // Keep rates first in the tiny slot; round count remains in the expanded view.
                setTextViewText(R.id.test_result_summary, compactMetrics.ifBlank { unavailable })
            }
            val expanded = RemoteViews(context.packageName, R.layout.notification_test_result_expanded).apply {
                setTextViewText(R.id.test_result_title, title)
                setTextViewText(R.id.test_result_summary, subtitle)
                setViewVisibility(R.id.test_result_summary, if (subtitle.isEmpty()) View.GONE else View.VISIBLE)
                setImageViewResource(R.id.test_result_status, model.statusIcon)
                if (model.statusIcon == R.drawable.ic_test_result_check)
                    setInt(R.id.test_result_status, "setBackgroundResource", R.drawable.test_notification_success)
                val containers = listOf(R.id.test_result_down, R.id.test_result_up,
                    R.id.test_result_latency, R.id.test_result_duration)
                val labelIds = listOf(R.id.test_result_down_label, R.id.test_result_up_label,
                    R.id.test_result_latency_label, R.id.test_result_duration_label)
                val valueIds = listOf(R.id.test_result_down_value, R.id.test_result_up_value,
                    R.id.test_result_latency_value, R.id.test_result_duration_value)
                for (index in containers.indices) {
                    setTextViewText(labelIds[index], labels[index])
                    // The primary tiles separate numeric reading from unit, without changing
                    // the compact/native fallback or converting absent readings into zero.
                    val tileValue = when (index) {
                        0 -> number(model.metrics.download)
                        1 -> number(model.metrics.upload)
                        else -> values[index]
                    }
                    setTextViewText(valueIds[index], tileValue ?: context.getString(R.string.test_metric_dash))
                    val spoken = if (index < 2) tileValue?.let {
                        context.getString(R.string.test_rate_accessible, it)
                    } else values[index]
                    setContentDescription(containers[index], "${labels[index]}: ${spoken ?: unavailable}")
                }
                setTextViewText(R.id.test_result_down_unit, context.getString(R.string.test_rate_unit))
                setTextViewText(R.id.test_result_up_unit, context.getString(R.string.test_rate_unit))
                // Saved results have no power measurement. Do not sample live power here
                // or label it as test consumption. Latency appears only when measured.
                if (latency == null) setViewVisibility(R.id.test_result_latency, View.GONE)
            }
            base().setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(small).setCustomBigContentView(expanded).build()
        } catch (_: RuntimeException) { fallback() }
        catch (_: LinkageError) { fallback() }
    }
}
