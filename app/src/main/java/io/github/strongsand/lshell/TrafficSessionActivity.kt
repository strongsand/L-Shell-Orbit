package io.github.strongsand.lshell

import io.github.strongsand.lshell.core.data.LivePowerReading
import io.github.strongsand.lshell.core.model.DishySnapshot
import java.util.Locale
import kotlin.math.roundToInt

enum class TrafficSessionMode { DOWNLOAD, UPLOAD, ENERGY, BOTH }

/** Lifecycle effects are injected so the same decisions can be checked without Android stubs. */
internal object TrafficSessionLifecycle {
    suspend fun start(sessions: List<TrafficSession>, create: suspend () -> TrafficSession,
        startService: (TrafficSession) -> Unit): TrafficSession {
        require(sessions.count { it.active } <= 1)
        val session = sessions.firstOrNull { it.active } ?: create()
        startService(session)
        return session
    }
    suspend fun finish(session: TrafficSession, now: Long, interrupted: Boolean,
        save: suspend (TrafficSession) -> TrafficSession, stopService: () -> Unit): TrafficSession {
        if (!session.active) return session
        val ended = TrafficSessionMetadata.finish(session, now).copy(interrupted = interrupted)
        val saved = save(ended) // Never remove the service before metadata is durable.
        stopService()
        return saved
    }
}

internal sealed interface TrafficSessionActivity {
    val session: TrafficSession? get() = null
    data object Inactive : TrafficSessionActivity
    data class Starting(override val session: TrafficSession) : TrafficSessionActivity
    data class Active(override val session: TrafficSession) : TrafficSessionActivity
    data class Stopping(override val session: TrafficSession) : TrafficSessionActivity
    data class Completed(override val session: TrafficSession) : TrafficSessionActivity
    data class ErrorRecoverable(override val session: TrafficSession?) : TrafficSessionActivity
}

/** Presentation only: no analyzer math, averaging, or interpolated measurements. */
internal data class TrafficNotificationReading(val download: Float?, val upload: Float?, val power: Float? = null) {
    val hasData get() = download != null || upload != null || power != null
}
internal object TrafficSessionNotificationModel {
    const val UPDATE_MS = 5000L
    const val FRESH_MS = 15000L
    fun reading(snapshot: DishySnapshot, now: Long, mode: TrafficSessionMode,
        livePower: LivePowerReading? = null): TrafficNotificationReading {
        if (!snapshot.isOnline || snapshot.isFromCache || now - snapshot.timestamp !in 0..FRESH_MS)
            return TrafficNotificationReading(null, null, null)
        fun valid(v: Float) = v.takeIf { it.isFinite() && it >= 0 }
        val power = if (mode == TrafficSessionMode.BOTH || mode == TrafficSessionMode.ENERGY)
            recentAntennaPower(livePower, now) else null
        return TrafficNotificationReading(
            if (mode == TrafficSessionMode.DOWNLOAD || mode == TrafficSessionMode.BOTH) valid(snapshot.throughput.downlinkBps)?.div(1000000) else null,
            if (mode == TrafficSessionMode.UPLOAD || mode == TrafficSessionMode.BOTH) valid(snapshot.throughput.uplinkBps)?.div(1000000) else null,
            power
        )
    }
    fun chip(reading: TrafficNotificationReading): String? {
        val down = reading.download ?: 0f
        val up = reading.upload ?: 0f
        val value = maxOf(down, up)
        if (value < 0.1f) return null // Platform chronometer takes over, with no per-second text updates.
        val number = if (value >= 1000) String.format(Locale.ROOT, "%.1fG", value / 1000)
            else if (value < 1) "${(value * 1000).roundToInt()}k" else "${value.roundToInt()}M"
        return ((if (down >= up) "↓" else "↑") + number).takeIf { it.length <= 6 }
    }
    fun requiresInterruption(session: TrafficSession, boot: Int) = session.active &&
        session.bootCount >= 0 && boot >= 0 && session.bootCount != boot
    fun criticalMetricIndex(reading: TrafficNotificationReading): Int {
        // Metrics are emitted download, upload, then duration, omitting absent values.
        val durationIndex = listOfNotNull(reading.download, reading.upload).size
        if (chip(reading) == null) return durationIndex
        return if ((reading.download ?: -1f) >= (reading.upload ?: -1f)) 0
            else if (reading.download != null) 1 else 0
    }
    fun restoreCandidate(sessions: List<TrafficSession>, requestedId: String?): TrafficSession? =
        sessions.singleOrNull { it.active }?.takeIf { requestedId == null || it.id == requestedId }
    fun notificationVariant(api: Int) = when { api >= 37 -> "metric"; api >= 36 -> "promoted_big_text"; else -> "big_text" }
}
