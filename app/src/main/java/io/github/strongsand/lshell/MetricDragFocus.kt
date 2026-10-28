package io.github.strongsand.lshell

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** Gesture calibration in density-independent units; no data or session state is changed. */
internal data class MetricFocusParameters(
    val enterSpeedDpPerSecond: Double = 80.0,
    val exitSpeedDpPerSecond: Double = 420.0,
    val exitCancelSpeedDpPerSecond: Double = 180.0,
    val exitDwellMs: Long = 160,
    val exitDistanceDp: Double = 48.0,
    val exitCommitMs: Long = 120,
    val smoothingMs: Double = 90.0,
    val enterDwellMs: Long = 120,
    val maximumZoom: Double = 4096.0,
    val minimumWindowMs: Long = 5_000,
    val targetSampleSpacingDp: Double = 12.0,
    val contextReferencePeriodMs: Long = 300_000,
    val contextReferenceWindowMs: Long = 30_000,
    val approachZoom: Double = 2.0,
    val approachDurationMs: Long = 220,
    val precisionBaseDurationMs: Long = 300,
    val precisionDurationPerLogZoomMs: Double = 180.0,
    val sampleSwitchHysteresisDp: Double = 2.0,
    val sampleSwitchMaximumFraction: Double = .2,
    val minimumVisibleSamples: Int = 8,
    val edgeMargin: Double = .18,
    val edgePanWindowsPerSecond: Double = .6,
    val edgePanPeriodPerSecond: Double = .08,
    val edgePanMaximumWindowsPerSecond: Double = 3.0,
    val edgePanRampMs: Long = 650,
    val fingerClearanceDp: Float = 56f,
    val springStiffness: Float = 300f,
) {
    init {
        require(enterSpeedDpPerSecond > 0)
        require(exitSpeedDpPerSecond > enterSpeedDpPerSecond && smoothingMs > 0 && enterDwellMs > 0)
        require(exitCancelSpeedDpPerSecond >= enterSpeedDpPerSecond && exitCancelSpeedDpPerSecond < exitSpeedDpPerSecond)
        require(exitDwellMs > 0 && exitDistanceDp > 0 && exitCommitMs > 0)
        require(sampleSwitchHysteresisDp >= 0 && sampleSwitchMaximumFraction in 0.0..<.5)
        require(maximumZoom >= 1 && minimumWindowMs > 0 && targetSampleSpacingDp > 0 && minimumVisibleSamples >= 2)
        require(contextReferencePeriodMs > 0 && contextReferenceWindowMs > 0)
        require(approachZoom > 1 && approachDurationMs > 0 && precisionBaseDurationMs > 0 && precisionDurationPerLogZoomMs > 0)
        require(edgeMargin > 0 && edgeMargin < .5 && edgePanRampMs > 0)
        require(edgePanWindowsPerSecond > 0 && edgePanPeriodPerSecond >= 0 && edgePanMaximumWindowsPerSecond > 0)
    }
}

internal enum class MetricFocusPhase { EXPLORE, FOCUSED, EXIT_PENDING, EXITING }
internal data class MetricFocusReading(val speedDpPerSecond: Double, val focused: Boolean, val intensity: Double,
    val phase: MetricFocusPhase = if (focused) MetricFocusPhase.FOCUSED else MetricFocusPhase.EXPLORE,
    val slowFocusMs: Long = 0)

/** Filter absolute horizontal speed (not signed velocity, which would cancel on reversal).
 * Slow entry acquires a stable target. Exit needs sustained fast movement and distance;
 * brief corrections/reversals never continuously shrink an acquired focus.
 * Stationary presses never trigger focus because only accepted horizontal drag events call move. */
internal class MetricDragFocus(val parameters: MetricFocusParameters = MetricFocusParameters()) {
    private var previousX = 0.0
    private var previousTime = 0L
    private var initialized = false
    private var speed: Double? = null
    private var slowMs = 0L
    private var phase = MetricFocusPhase.EXPLORE
    private var fastMs = 0L
    private var fastDistance = 0.0
    private var fastDirection = 0
    private var exitingMs = 0L
    private var slowFocusMs = 0L

    fun begin(xDp: Double, timeMs: Long) {
        previousX = xDp; previousTime = timeMs; initialized = true
        speed = null; slowMs = 0; slowFocusMs = 0; phase = MetricFocusPhase.EXPLORE; clearExit()
    }

    fun move(xDp: Double, timeMs: Long): MetricFocusReading {
        if (!initialized) { begin(xDp, timeMs); return reading() }
        if (!xDp.isFinite() || timeMs <= previousTime) return reading()
        val elapsed = timeMs - previousTime
        val movement = xDp - previousX
        val instantaneous = abs(movement) * 1_000 / elapsed
        previousX = xDp; previousTime = timeMs
        val alpha = 1 - exp(-elapsed / parameters.smoothingMs)
        speed = speed?.let { it + alpha * (instantaneous - it) } ?: instantaneous
        val observedMs = elapsed.coerceAtMost(50)
        val wasFocused = phase == MetricFocusPhase.FOCUSED
        if (phase == MetricFocusPhase.EXPLORE) {
            // A delayed event after a stationary hold cannot count as an entire dwell.
            slowMs = if (speed!! <= parameters.enterSpeedDpPerSecond) slowMs + elapsed.coerceAtMost(50) else 0
            if (slowMs >= parameters.enterDwellMs) phase = MetricFocusPhase.FOCUSED
        } else if (phase == MetricFocusPhase.EXITING) {
            if (instantaneous <= parameters.exitCancelSpeedDpPerSecond || speed!! <= parameters.exitCancelSpeedDpPerSecond) {
                phase = MetricFocusPhase.FOCUSED; clearExit()
            } else {
                exitingMs += observedMs
                if (exitingMs >= parameters.exitCommitMs) { phase = MetricFocusPhase.EXPLORE; slowMs = 0; slowFocusMs = 0; clearExit() }
            }
        } else {
            val direction = if (movement < 0) -1 else 1
            if (instantaneous >= parameters.exitSpeedDpPerSecond && speed!! >= parameters.exitSpeedDpPerSecond) {
                if (direction != fastDirection) clearExit()
                fastDirection = direction
                fastMs += observedMs
                fastDistance += abs(movement) * observedMs / elapsed
                phase = MetricFocusPhase.EXIT_PENDING
                if (fastMs >= parameters.exitDwellMs && fastDistance >= parameters.exitDistanceDp) {
                    phase = MetricFocusPhase.EXITING; exitingMs = 0
                }
            } else {
                phase = MetricFocusPhase.FOCUSED; clearExit()
            }
        }
        // Deepen only during continued slow drag, not edge panning, a held press or
        // movement still travelling towards a peak. Corrections pause, never undo it.
        if (wasFocused && phase == MetricFocusPhase.FOCUSED &&
            instantaneous <= parameters.enterSpeedDpPerSecond && speed!! <= parameters.enterSpeedDpPerSecond) {
            slowFocusMs += observedMs
        }
        return reading()
    }

    fun end(): MetricFocusReading {
        initialized = false; speed = null; slowMs = 0; slowFocusMs = 0; phase = MetricFocusPhase.EXPLORE; clearExit()
        return reading()
    }

    private fun clearExit() { fastMs = 0; fastDistance = 0.0; fastDirection = 0; exitingMs = 0 }
    private fun reading() = MetricFocusReading(speed ?: 0.0, phase != MetricFocusPhase.EXPLORE,
        if (phase == MetricFocusPhase.FOCUSED || phase == MetricFocusPhase.EXIT_PENDING) 1.0 else 0.0, phase, slowFocusMs)
}

/** Stateful capture is supplied by the caller; all returned values are original readings.
 * Never retain across missing support or skip over intervening records to create a snap. */
internal object MetricFocusSnap {
    fun inspect(series: MetricHistorySeries, time: Long, previousTime: Long?, msPerDp: Double,
        parameters: MetricFocusParameters = MetricFocusParameters()): MetricHistoryPoint? {
        val candidate = MetricChartPresentation.inspect(series, time) ?: return null
        if (previousTime == null || !msPerDp.isFinite() || msPerDp <= 0) return candidate
        val previousIndex = series.points.binarySearchBy(previousTime) { it.time }
        val candidateIndex = series.points.binarySearchBy(candidate.time) { it.time }
        if (previousIndex < 0 || abs(candidateIndex - previousIndex) != 1) return candidate
        val previous = series.points[previousIndex]
        if (previous.measurement == null || previous.time < series.start || previous.time >= series.end) return candidate
        val a = series.points[minOf(previousIndex, candidateIndex)]
        val b = series.points[maxOf(previousIndex, candidateIndex)]
        if (b.time > a.time + a.measurement!!.supportMs) return candidate
        val interval = (b.time - a.time).toDouble()
        val midpoint = a.time + interval / 2
        val slack = minOf(parameters.sampleSwitchHysteresisDp * msPerDp, interval * parameters.sampleSwitchMaximumFraction)
        val keep = if (candidate.time > previous.time) time < midpoint + slack else time > midpoint - slack
        return if (keep) previous else candidate
    }
}

/** Cadence and temporal context constrain precision together; 12 dp is a preference. */
internal data class MetricFocusDensity(val maximumZoom: Double, val typicalIntervalMs: Double?, val minimumUsefulWindowMs: Double,
    val firstTime: Long, val lastSupportedTime: Long) {
    fun zoom(intensity: Double) = 1 + (maximumZoom - 1) * intensity.coerceIn(0.0, 1.0)
    companion object {
        fun from(series: MetricHistorySeries, widthDp: Double, parameters: MetricFocusParameters = MetricFocusParameters()): MetricFocusDensity {
            val valid = series.points.filter { it.time >= series.start && it.time < series.end && it.measurement != null }
            val allIntervals = mutableListOf<Long>()
            val supportedIntervals = mutableListOf<Long>()
            valid.zipWithNext().forEach { (a, b) ->
                val gap = b.time - a.time
                if (gap > 0) {
                    allIntervals += gap
                    if (gap <= a.measurement!!.supportMs) supportedIntervals += gap
                }
            }
            val intervals = (supportedIntervals.takeIf { it.isNotEmpty() } ?: allIntervals).sorted()
            val cadence = intervals.takeIf { it.isNotEmpty() }?.let { (it[(it.size - 1) / 2].toDouble() + it[it.size / 2]) / 2 }
            val duration = (series.end - series.start).coerceAtLeast(0)
            val context = parameters.contextReferenceWindowMs * sqrt(duration.toDouble() / parameters.contextReferencePeriodMs)
            val minimum = maxOf(parameters.minimumWindowMs.toDouble(), (cadence ?: 0.0) * parameters.minimumVisibleSamples, context)
            val desired = maxOf(minimum, (widthDp.coerceAtLeast(1.0) / parameters.targetSampleSpacingDp) * (cadence ?: 0.0))
            val maximum = if (cadence == null || !widthDp.isFinite() || widthDp <= 0) 1.0 else
                (duration / desired).coerceIn(1.0, parameters.maximumZoom)
            val first = valid.firstOrNull()?.time ?: series.start
            val last = valid.lastOrNull()
            val next = last?.let { p -> series.points.firstOrNull { it.time > p.time }?.time }
            val lastSupported = if (last == null) first else minOf(series.end - 1,
                last.time + last.measurement!!.supportMs - 1, next?.minus(1) ?: Long.MAX_VALUE)
            return MetricFocusDensity(maximum, cadence, minimum, first, maxOf(first, lastSupported))
        }
    }
}

internal data class MetricFocusAnchor(val period: MetricTimeViewport, val time: Double, val fraction: Double,
    val firstTime: Long = period.start, val lastSupportedTime: Long = maxOf(period.start, period.end - 1))

/** Latch final density once. Slow drag first approaches at <=2x, then deepens in
 * logarithmic space. Functional progression also applies with animations disabled. */
internal class MetricFocusTarget(private val parameters: MetricFocusParameters = MetricFocusParameters()) {
    private var acquired: Double? = null
    fun zoom(reading: MetricFocusReading, density: MetricFocusDensity): Double {
        if (reading.phase == MetricFocusPhase.EXPLORE) acquired = null
        else if (acquired == null) acquired = density.maximumZoom
        if (reading.intensity <= 0) return 1.0
        val finalZoom = acquired ?: return 1.0
        val approach = minOf(finalZoom, parameters.approachZoom)
        fun ease(fraction: Double): Double = fraction.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
        val time = reading.slowFocusMs.toDouble()
        if (time <= parameters.approachDurationMs) {
            return exp(ln(approach) * ease(time / parameters.approachDurationMs))
        }
        val precisionDuration = parameters.precisionBaseDurationMs + parameters.precisionDurationPerLogZoomMs * ln(finalZoom / approach)
        if (time >= parameters.approachDurationMs + precisionDuration) return finalZoom
        return exp(ln(approach) + ln(finalZoom / approach) * ease((time - parameters.approachDurationMs) / precisionDuration))
    }
    fun end() { acquired = null }
}

internal object MetricPrecisionLens {
    fun viewport(anchor: MetricFocusAnchor, zoom: Double, minimumWindowMs: Long = 5_000): MetricTimeViewport {
        val period = anchor.period
        if (period.end <= period.start) return period
        val width = (period.duration / zoom.coerceAtLeast(1.0)).toLong()
            .coerceAtLeast(minimumWindowMs.coerceAtMost(period.duration)).coerceAtMost(period.duration)
        // The final valid instant is end-1; at that boundary include it in the window.
        val left = (if (anchor.time >= period.end - 1) period.end - width
            else (anchor.time - width * anchor.fraction.coerceIn(0.0, 1.0)).toLong())
            .coerceIn(period.start, period.end - width)
        return MetricTimeViewport(left, left + width)
    }

    fun edgeStrength(fraction: Double, margin: Double = .18): Double = when {
        fraction < margin -> -((margin - fraction) / margin).coerceIn(0.0, 1.0)
        fraction > 1 - margin -> ((fraction - (1 - margin)) / margin).coerceIn(0.0, 1.0)
        else -> 0.0
    }

    /** Only the live drag's edge frame pump calls this. No timer survives release/cancel.
     * Local movement begins gently; the outer edge permits traversing a long period in one drag.
     * Focal bounds stop on real supported observations, including sparse/trailing-gap series. */
    fun pan(anchor: MetricFocusAnchor, zoom: Double, elapsedMs: Double, edgeAgeMs: Double,
        parameters: MetricFocusParameters = MetricFocusParameters()): MetricFocusAnchor {
        if (zoom <= 1.001 || elapsedMs <= 0 || anchor.period.end <= anchor.period.start) return anchor
        val edge = edgeStrength(anchor.fraction, parameters.edgeMargin)
        if (edge == 0.0) return anchor
        val progress = (edgeAgeMs / parameters.edgePanRampMs).coerceIn(0.0, 1.0)
        val ramp = progress * progress * (3 - 2 * progress)
        val width = viewport(anchor, zoom, parameters.minimumWindowMs).duration
        val rate = (width * parameters.edgePanWindowsPerSecond * abs(edge) +
            anchor.period.duration * parameters.edgePanPeriodPerSecond * abs(edge).pow(4))
            .coerceAtMost(width * parameters.edgePanMaximumWindowsPerSecond)
        val delta = rate * ramp * elapsedMs.coerceAtMost(50.0) / 1_000 * if (edge < 0) -1 else 1
        // A live series may refresh during this gesture. Never pan beyond its captured period.
        val first = anchor.firstTime.coerceIn(anchor.period.start, anchor.period.end - 1)
        val last = anchor.lastSupportedTime.coerceIn(first, anchor.period.end - 1)
        return anchor.copy(time = (anchor.time + delta).coerceIn(first.toDouble(), last.toDouble()))
    }

    // Animate this coordinate, rather than a factor that may span thousands of times.
    fun logZoom(zoom: Double) = ln(zoom.coerceAtLeast(1.0)).toFloat()
    fun zoomFromLog(value: Float) = exp(value.toDouble())

    /** Include the adjacent original representatives so clipping retains supported edge segments. */
    fun visible(points: List<TrafficPlotPoint>, viewport: MetricTimeViewport): List<TrafficPlotPoint> {
        fun lowerBound(time: Long): Int {
            var low = 0; var high = points.size
            while (low < high) {
                val mid = (low + high) / 2
                if (points[mid].time < time) low = mid + 1 else high = mid
            }
            return low
        }
        val first = (lowerBound(viewport.start) - 1).coerceAtLeast(0)
        val end = (lowerBound(viewport.end) + 1).coerceAtMost(points.size)
        return points.subList(first, end)
    }
}

/** Index continuity once. Precision draws only a binary-searched slice of the original series.
 * It never constructs huge full-day buckets for a 2,000x lens, nor scans the full day per frame. */
internal class MetricPrecisionPlot(private val series: MetricHistorySeries) {
    private val points = series.points
    private val runs = IntArray(points.size)
    init {
        var run = 0
        points.forEachIndexed { index, point ->
            val before = points.getOrNull(index - 1)
            if (point.measurement == null || before?.measurement == null || point.time > before.time + before.measurement.supportMs) run++
            runs[index] = run
        }
    }
    fun reduce(viewport: MetricTimeViewport, buckets: Int = 360): List<TrafficPlotPoint> {
        require(buckets > 0)
        fun lowerBound(time: Long): Int {
            var low = 0; var high = points.size
            while (low < high) { val mid = (low + high) / 2; if (points[mid].time < time) low = mid + 1 else high = mid }
            return low
        }
        val begin = (lowerBound(viewport.start) - 1).coerceAtLeast(0)
        val end = (lowerBound(viewport.end) + 1).coerceAtMost(points.size)
        val width = ((viewport.duration + buckets - 1) / buckets).coerceAtLeast(1)
        val indices = mutableListOf<Int>()
        var bucket: Long? = null
        var first = -1; var last = -1; var minimum = -1; var maximum = -1
        fun flush() { if (first >= 0) indices.addAll(listOf(first, minimum, maximum, last).distinct().sorted()) }
        for (index in begin until end) {
            val p = points[index]
            val value = p.measurement?.value ?: continue
            val key = Math.floorDiv(p.time - viewport.start, width)
            if (key != bucket) { flush(); bucket = key; first = index; minimum = index; maximum = index }
            last = index
            if (value < points[minimum].measurement!!.value) minimum = index
            if (value > points[maximum].measurement!!.value) maximum = index
        }
        flush()
        var previousRun: Int? = null
        return indices.map { index ->
            val p = points[index]; val value = p.measurement!!
            TrafficPlotPoint(p.time, value.value, value.value, value.value, value.origin, connect = previousRun == runs[index])
                .also { previousRun = runs[index] }
        }
    }
}

internal data class MetricPointerLabelBounds(val left: Float, val top: Float, val width: Float, val height: Float)
internal fun metricPointerLabelBounds(x: Float, y: Float, width: Float, height: Float,
    left: Float, right: Float, bottom: Float, gap: Float): MetricPointerLabelBounds {
    val safeWidth = width.coerceAtMost((right - left).coerceAtLeast(1f))
    val safeHeight = height.coerceAtMost(bottom.coerceAtLeast(1f))
    return MetricPointerLabelBounds((x - safeWidth / 2).coerceIn(left, maxOf(left, right - safeWidth)),
        (y - safeHeight - gap).coerceIn(0f, (bottom - safeHeight).coerceAtLeast(0f)), safeWidth, safeHeight)
}
