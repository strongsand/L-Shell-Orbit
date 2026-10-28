package io.github.strongsand.lshell

import org.junit.Assert.*
import org.junit.Test

class MetricDragFocusTest {
    private class Drag(parameters: MetricFocusParameters = MetricFocusParameters()) {
        val detector = MetricDragFocus(parameters)
        var x = 0.0
        var time = 0L
        var reading = MetricFocusReading(0.0, false, 0.0)
        init { detector.begin(x, time) }
        fun frames(speed: Double, count: Int, interval: Long = 16): List<MetricFocusReading> = (0 until count).map {
            x += speed * interval / 1_000; time += interval
            detector.move(x, time).also { reading = it }
        }
        fun reach(phase: MetricFocusPhase, speed: Double = 600.0) {
            repeat(200) { if (reading.phase == phase) return; frames(speed, 1) }
            fail("Did not reach $phase")
        }
    }

    @Test fun fastDragNeverMagnifies() {
        val readings = Drag().frames(600.0, 60)
        assertTrue(readings.all { !it.focused && it.intensity == 0.0 })
        assertEquals(600.0, readings.last().speedDpPerSecond, .0001)
    }
    @Test fun moderateDragStillUsesNormalInspection() {
        assertTrue(Drag().frames(120.0, 60).all { !it.focused && it.intensity == 0.0 })
    }
    @Test fun slowingDownProgressivelyIncreasesFocusInTheSameDrag() {
        val drag = Drag()
        drag.frames(600.0, 12)
        val readings = listOf(200.0, 80.0, 50.0, 30.0, 10.0).flatMap { drag.frames(it, 16) }
        val focused = readings.filter { it.focused }
        assertTrue(focused.isNotEmpty())
        assertTrue(focused.zipWithNext().all { (a, b) -> b.intensity >= a.intensity })
        assertTrue(drag.reading.intensity > .99)
        assertTrue(readings.all { it.intensity in 0.0..1.0 })
    }
    @Test fun accelerationExitsWithoutLiftingAndCanFocusAgain() {
        val drag = Drag()
        drag.frames(20.0, 20)
        assertTrue(drag.reading.focused)
        drag.frames(600.0, 40)
        assertFalse(drag.reading.focused)
        assertEquals(0.0, drag.reading.intensity, 0.0)
        drag.frames(20.0, 30)
        assertTrue(drag.reading.focused)
    }
    @Test fun smoothedSpeedDoesNotJumpToOneNoisyEvent() {
        val drag = Drag()
        drag.frames(20.0, 20)
        val reading = drag.frames(600.0, 1).single()
        assertTrue(reading.speedDpPerSecond in 20.0..180.0)
        assertTrue(reading.focused)
    }
    @Test fun hysteresisKeepsFocusThroughEntryThresholdOscillation() {
        val drag = Drag()
        drag.frames(20.0, 20)
        val readings = (0 until 60).flatMap { drag.frames(if (it % 2 == 0) 75.0 else 110.0, 1) }
        assertTrue(readings.all { it.focused })
        val normal = Drag().frames(110.0, 60)
        assertTrue(normal.all { !it.focused })
    }
    @Test fun slowMovementMustPersistBeforeFocus() {
        val drag = Drag()
        assertFalse(drag.frames(20.0, 6).last().focused)
        assertTrue(drag.frames(20.0, 3).last().focused)
    }
    @Test fun heldTouchAndDelayedFirstMovementDoNotTriggerFocus() {
        val detector = MetricDragFocus()
        detector.begin(0.0, 0)
        assertFalse(detector.move(2.0, 2_000).focused)
    }
    @Test fun directionReversalDoesNotCancelAbsoluteSpeed() {
        val drag = Drag()
        drag.frames(500.0, 10)
        val readings = drag.frames(-500.0, 10)
        assertTrue(readings.all { !it.focused })
        assertEquals(500.0, readings.last().speedDpPerSecond, .0001)
    }
    @Test fun duplicateOrStaleEventTimesAreIgnored() {
        val detector = MetricDragFocus()
        detector.begin(0.0, 0)
        val first = detector.move(10.0, 16)
        assertEquals(first, detector.move(100.0, 16))
        assertEquals(first, detector.move(200.0, 10))
        assertEquals(first, detector.move(Double.NaN, 32))
    }
    @Test fun filterResponseIsConsistentAcrossEventRates() {
        fun reading(interval: Long): Double {
            val drag = Drag()
            drag.frames(600.0, (320 / interval).toInt(), interval)
            drag.frames(20.0, (320 / interval).toInt(), interval)
            return drag.reading.speedDpPerSecond
        }
        assertEquals(reading(16), reading(32), .0001)
    }
    @Test fun releaseResetsFocusAndNextDragStartsNormally() {
        val drag = Drag()
        drag.frames(20.0, 20)
        assertEquals(MetricFocusReading(0.0, false, 0.0), drag.detector.end())
        drag.detector.begin(100.0, 1_000)
        assertFalse(drag.detector.move(110.0, 1_016).focused)
    }
    @Test fun lensPreservesUnsnappedAnchorAcrossIntermediateZooms() {
        val period = MetricTimeViewport(0, 900_000)
        val anchor = MetricFocusAnchor(period, 420_000.0, .45)
        listOf(1.5, 2.0, 3.0, 4.5, 6.0).forEach { zoom ->
            val viewport = MetricPrecisionLens.viewport(anchor, zoom)
            assertEquals(anchor.time, viewport.start + anchor.fraction * viewport.duration, 1.0)
        }
        assertEquals(period, MetricPrecisionLens.viewport(anchor, 1.0))
    }
    @Test fun lensStaysInsidePeriodAtBothEdgesAndShortWindows() {
        val period = MetricTimeViewport(0, 900_000)
        assertEquals(0L, MetricPrecisionLens.viewport(MetricFocusAnchor(period, 0.0, .5), 6.0).start)
        assertEquals(period.end, MetricPrecisionLens.viewport(MetricFocusAnchor(period, period.end.toDouble(), .5), 6.0).end)
        val short = MetricTimeViewport(0, 20_000)
        assertEquals(5_000L, MetricPrecisionLens.viewport(MetricFocusAnchor(short, 5_000.0, .5), 6.0).duration)
    }
    @Test fun visibleRepresentativesRetainEdgeSegmentsAndGapFlags() {
        val points = (0..10).map { TrafficPlotPoint(it * 1_000L, it.toDouble(), connect = it != 5) }
        val visible = MetricPrecisionLens.visible(points, MetricTimeViewport(3_500, 6_500))
        assertEquals(listOf(3_000L, 4_000L, 5_000L, 6_000L, 7_000L), visible.map { it.time })
        assertFalse(visible.first { it.time == 5_000L }.connect)
        assertTrue(MetricPrecisionLens.visible(emptyList(), MetricTimeViewport(0, 1)).isEmpty())
    }
    @Test fun timeLabelRemainsAbovePointAndInsideEitherEdge() {
        listOf(25f, 160f, 290f).forEach { x ->
            val label = metricPointerLabelBounds(x, 160f, 90f, 25f, 30f, 280f, 230f, 10f)
            assertTrue(label.left >= 30f)
            assertTrue(label.left + label.width <= 280f)
            assertTrue(label.top + label.height < 160f)
        }
        assertEquals(0f, metricPointerLabelBounds(50f, 10f, 90f, 25f, 30f, 280f, 230f, 10f).top, 0f)
    }
    @Test fun clockLabelUsesSecondsFromActualSelectedTimestamp() {
        val timestamp = 1_800_000_032_000L
        val expected = java.time.Instant.ofEpochMilli(timestamp).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
        assertEquals(expected, metricInspectionTime(timestamp))
        assertNotEquals(metricInspectionTime(timestamp), metricInspectionTime(timestamp + 1_000))
    }

    private fun sampledSeries(durationMs: Long, intervalMs: Long = 1_000): MetricHistorySeries {
        val points = (0 until (durationMs / intervalMs).toInt()).map {
            MetricHistoryPoint(it * intervalMs, TrafficValue((it % 100).toDouble(), TrafficOrigin.LOCAL_PHONE, intervalMs))
        }
        return MetricHistorySeries(0, durationMs, points, durationMs, emptyList())
    }
    @Test fun densePeriodsPreserveProgressivelyMoreTemporalContext() {
        val width = 240.0
        var previousWindow = 0.0
        listOf(300_000L, 1_800_000L, 3_600_000L, 7_200_000L, 21_600_000L, 86_400_000L).forEach { duration ->
            val density = MetricFocusDensity.from(sampledSeries(duration), width)
            val window = duration / density.maximumZoom
            assertTrue(window > previousWindow)
            previousWindow = window
            assertEquals(30_000 * kotlin.math.sqrt(duration / 300_000.0), window, .01)
            assertTrue(width / (window / 1_000) < 12.0)
        }
        assertEquals(30_000.0, 300_000 / MetricFocusDensity.from(sampledSeries(300_000), width).maximumZoom, .01)
    }
    @Test fun cadenceAndWidthDetermineZoomRatherThanPeriodLabelsOrBuckets() {
        val dense = sampledSeries(1_800_000)
        val sparse = sampledSeries(1_800_000, 30_000)
        assertTrue(MetricFocusDensity.from(dense, 240.0).maximumZoom > MetricFocusDensity.from(sparse, 240.0).maximumZoom)
        // Density/width still matter when they require more context than the temporal floor.
        val moderate = sampledSeries(1_800_000, 5_000)
        assertTrue(MetricFocusDensity.from(moderate, 120.0).maximumZoom > MetricFocusDensity.from(moderate, 240.0).maximumZoom)
        assertEquals(MetricFocusDensity.from(dense, 240.0), MetricFocusDensity.from(dense.copy(plot = listOf(TrafficPlotPoint(0, 0.0))), 240.0))
    }
    @Test fun gapsDoNotDiluteTheObservedCadence() {
        val s = sampledSeries(10_000).let { it.copy(end = 86_400_000,
            points = it.points + MetricHistoryPoint(80_000_000, TrafficValue(10.0, TrafficOrigin.BEACON, 1_000))) }
        assertEquals(1_000.0, MetricFocusDensity.from(s, 240.0).typicalIntervalMs!!, 0.0)
        assertNull(MetricChartPresentation.inspect(s, 40_000_000))
    }
    @Test fun emptySinglePointAndUnmeasuredWidthHaveSafeZoom() {
        val s = sampledSeries(1_000)
        assertEquals(1.0, MetricFocusDensity.from(s, 240.0).maximumZoom, 0.0)
        assertEquals(1.0, MetricFocusDensity.from(s.copy(points = emptyList()), 240.0).maximumZoom, 0.0)
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach {
            assertEquals(1.0, MetricFocusDensity.from(sampledSeries(10_000), it).maximumZoom, 0.0)
        }
        val emptyPeriod = MetricTimeViewport(0, 0)
        assertEquals(emptyPeriod, MetricPrecisionLens.viewport(MetricFocusAnchor(emptyPeriod, 0.0, .5), 6.0))
    }
    @Test fun adaptiveFactorCombinesSpeedIntensityAndDensityWithinSafetyLimits() {
        val drag = Drag()
        drag.frames(20.0, 20)
        val sparse = MetricFocusDensity.from(sampledSeries(300_000, 30_000), 240.0)
        val dense = MetricFocusDensity.from(sampledSeries(86_400_000), 240.0)
        assertTrue(dense.zoom(drag.reading.intensity) > sparse.zoom(drag.reading.intensity))
        assertTrue(dense.zoom(.5) < dense.zoom(1.0))
        drag.frames(600.0, 40)
        assertEquals(1.0, dense.zoom(drag.reading.intensity), 0.0)
        assertEquals(1.0, dense.zoom(drag.detector.end().intensity), 0.0)
        val capped = MetricFocusDensity.from(sampledSeries(10_000, 1).copy(end = 86_400_000), 10.0)
        assertTrue(capped.maximumZoom < 200) // Context wins even for a one-millisecond cadence.
        assertTrue(capped.minimumUsefulWindowMs >= 5_000)
        assertEquals(4.0, MetricFocusDensity.from(sampledSeries(300_000), 240.0,
            MetricFocusParameters(maximumZoom = 4.0)).maximumZoom, 0.0)
    }
    @Test fun logZoomAllowsContinuousRetargetingWhenDensityChanges() {
        val from = MetricFocusDensity.from(sampledSeries(1_800_000, 5_000), 240.0).zoom(1.0)
        val to = MetricFocusDensity.from(sampledSeries(1_800_000, 1_000), 240.0).zoom(1.0)
        val start = MetricPrecisionLens.logZoom(from)
        val target = MetricPrecisionLens.logZoom(to)
        assertEquals(from, MetricPrecisionLens.zoomFromLog(start), .0001)
        val intermediate = MetricPrecisionLens.zoomFromLog((start + target) / 2)
        assertTrue(intermediate > from && intermediate < to)
        assertEquals(to, MetricPrecisionLens.zoomFromLog(target), .0001)
    }
    @Test fun movingFocusedWindowReachesLastOriginalSampleWithoutRelease() {
        val s = sampledSeries(86_400_000)
        val density = MetricFocusDensity.from(s, 240.0)
        var anchor = MetricFocusAnchor(MetricTimeViewport(s.start, s.end), s.end / 2.0, .99,
            density.firstTime, density.lastSupportedTime)
        repeat(60_000) { frame -> anchor = MetricPrecisionLens.pan(anchor, density.maximumZoom, 16.0, frame * 16.0) }
        assertEquals(s.points.last().time, MetricChartPresentation.inspect(s, anchor.time.toLong())!!.time)
        val viewport = MetricPrecisionLens.viewport(anchor, density.maximumZoom)
        assertEquals(s.end, viewport.end)
        assertTrue(viewport.start > s.end / 2)
    }
    @Test fun movingFocusedWindowReachesFirstOriginalSampleWithoutRelease() {
        val s = sampledSeries(86_400_000)
        val density = MetricFocusDensity.from(s, 240.0)
        var anchor = MetricFocusAnchor(MetricTimeViewport(s.start, s.end), s.end / 2.0, .01,
            density.firstTime, density.lastSupportedTime)
        repeat(60_000) { frame -> anchor = MetricPrecisionLens.pan(anchor, density.maximumZoom, 16.0, frame * 16.0) }
        assertEquals(s.points.first().time, MetricChartPresentation.inspect(s, anchor.time.toLong())!!.time)
        assertEquals(s.start, MetricPrecisionLens.viewport(anchor, density.maximumZoom).start)
    }
    @Test fun comfortZoneAndRampAvoidAggressiveRecentering() {
        val period = MetricTimeViewport(0, 900_000)
        val center = MetricFocusAnchor(period, 450_000.0, .5)
        assertEquals(center, MetricPrecisionLens.pan(center, 20.0, 16.0, 2_000.0))
        val edge = center.copy(fraction = .85)
        val beginning = MetricPrecisionLens.pan(edge, 20.0, 16.0, 16.0)
        val settled = MetricPrecisionLens.pan(edge, 20.0, 16.0, 2_000.0)
        assertTrue(beginning.time >= edge.time && beginning.time - edge.time < settled.time - edge.time)
        assertEquals(edge, MetricPrecisionLens.pan(edge, 1.0, 16.0, 2_000.0))
        assertEquals(MetricPrecisionLens.pan(edge, 20.0, 50.0, 2_000.0),
            MetricPrecisionLens.pan(edge, 20.0, 10_000.0, 2_000.0))
    }
    @Test fun panClampsToRealObservationsWithTrailingEmptyIntervals() {
        val s = sampledSeries(300_000).copy(end = 1_800_000)
        val density = MetricFocusDensity.from(s, 240.0)
        var anchor = MetricFocusAnchor(MetricTimeViewport(s.start, s.end), 150_000.0, 1.0,
            density.firstTime, density.lastSupportedTime)
        repeat(2_000) { anchor = MetricPrecisionLens.pan(anchor, density.maximumZoom, 16.0, 1_000.0) }
        assertTrue(anchor.time < 300_000)
        assertEquals(s.points.last().time, MetricChartPresentation.inspect(s, anchor.time.toLong())!!.time)
        assertTrue(MetricPrecisionLens.viewport(anchor, density.maximumZoom).end <= s.end)
    }
    @Test fun movingWindowKeepsRawFocalTimeUnderTheSameScreenFraction() {
        val period = MetricTimeViewport(0, 900_000)
        val anchor = MetricFocusAnchor(period, 450_000.0, .85)
        val next = MetricPrecisionLens.pan(anchor, 20.0, 16.0, 1_000.0)
        val viewport = MetricPrecisionLens.viewport(next, 20.0)
        assertEquals(next.time, viewport.start + next.fraction * viewport.duration, 1.0)
    }
    @Test fun refreshedObservationBoundsCannotOverscrollTheCapturedDragPeriod() {
        val period = MetricTimeViewport(10_000, 310_000)
        var anchor = MetricFocusAnchor(period, 300_000.0, 1.0, 0, 400_000)
        repeat(200) { anchor = MetricPrecisionLens.pan(anchor, 20.0, 16.0, 1_000.0) }
        assertEquals(309_999.0, anchor.time, 0.0)
        assertEquals(period.end, MetricPrecisionLens.viewport(anchor, 20.0).end)
        anchor = anchor.copy(time = 20_000.0, fraction = 0.0)
        repeat(200) { anchor = MetricPrecisionLens.pan(anchor, 20.0, 16.0, 1_000.0) }
        assertEquals(period.start.toDouble(), anchor.time, 0.0)
    }
    @Test fun denseDayPanNeverJumpsAcrossAnEntireVisibleWindowInOneFrame() {
        val s = sampledSeries(86_400_000)
        val density = MetricFocusDensity.from(s, 240.0)
        val anchor = MetricFocusAnchor(MetricTimeViewport(s.start, s.end), s.end / 2.0, 1.0)
        val viewport = MetricPrecisionLens.viewport(anchor, density.maximumZoom)
        val next = MetricPrecisionLens.pan(anchor, density.maximumZoom, 50.0, 2_000.0)
        assertTrue(next.time > anchor.time)
        assertTrue(next.time - anchor.time <= viewport.duration * .15 + .01)
    }
    @Test fun precisionRenderingUsesOriginalLocalPointsAndDoesNotBridgeGaps() {
        val s = sampledSeries(86_400_000)
        val points = s.points.toMutableList().apply { this[43_210] = MetricHistoryPoint(43_210_000, null) }
        val plot = MetricPrecisionPlot(s.copy(points = points))
            .reduce(MetricTimeViewport(43_200_000, 43_240_000))
        assertTrue(plot.size > 35)
        assertFalse(plot.first { it.time > 43_210_000 }.connect)
        assertEquals(s.points[43_215].measurement!!.value, plot.first { it.time == 43_215_000L }.value!!, 0.0)
        assertTrue(plot.zipWithNext().all { (a, b) -> a.time < b.time })
    }
    @Test fun indexedRenderingRemainsBoundedAndPreservesExtremaOnDenseDay() {
        val s = sampledSeries(86_400_000)
        val plot = MetricPrecisionPlot(s).reduce(MetricTimeViewport(s.start, s.end))
        assertTrue(plot.size <= 1440)
        assertEquals(0.0, plot.minOf { it.value!! }, 0.0)
        assertEquals(99.0, plot.maxOf { it.value!! }, 0.0)
    }
    @Test fun clockRailKeepsLabelAboveEveryTouchableYAtLargeFontsAndEdges() {
        listOf(25f, 50f).forEach { labelHeight ->
            val clearance = 56f
            val rail = labelHeight + clearance + 6
            listOf(30f, 160f, 280f).forEach { x ->
                val label = metricPointerLabelBounds(x, rail, 90f, labelHeight, 30f, 280f, rail, clearance)
                assertTrue(label.top + label.height <= rail - clearance + .01f)
                assertTrue(label.left >= 30f && label.left + label.width <= 280f)
            }
        }
    }
    @Test fun acquiredFocusStaysStableThroughNaturalCorrectionsAndReversal() {
        val drag = Drag()
        drag.frames(600.0, 12); drag.frames(20.0, 30)
        val moves = listOf(30.0, 170.0, 60.0, 280.0, 20.0, -90.0, 150.0, -240.0, 30.0)
            .flatMap { drag.frames(it, 12) }
        assertTrue(moves.all { it.phase == MetricFocusPhase.FOCUSED && it.intensity == 1.0 })
    }
    @Test fun briefFastSpikeCannotShrinkFocus() {
        val drag = Drag(); drag.frames(20.0, 20)
        val readings = drag.frames(1_400.0, 3) + drag.frames(20.0, 15)
        assertTrue(readings.all { it.focused && it.intensity == 1.0 })
    }
    @Test fun exitNeedsBothSustainedSpeedAndDistance() {
        val drag = Drag(MetricFocusParameters(exitDistanceDp = 500.0))
        drag.frames(20.0, 20); drag.frames(600.0, 30)
        assertEquals(MetricFocusPhase.EXIT_PENDING, drag.reading.phase)
        assertEquals(1.0, drag.reading.intensity, 0.0)
        drag.reach(MetricFocusPhase.EXITING)
        assertEquals(0.0, drag.reading.intensity, 0.0)
        drag.reach(MetricFocusPhase.EXPLORE)
        assertFalse(drag.reading.focused)
    }
    @Test fun exitCannotBeTriggeredByDistanceInOneShortFlick() {
        val drag = Drag(); drag.frames(20.0, 20)
        val moves = drag.frames(3_000.0, 4) + drag.frames(20.0, 5)
        assertTrue(moves.all { it.intensity == 1.0 })
    }
    @Test fun reversalResetsPendingEscapeRatherThanAccumulatingCorrections() {
        val drag = Drag(); drag.frames(20.0, 20)
        val moves = (0..10).flatMap { drag.frames(if (it % 2 == 0) 900.0 else -900.0, 6) }
        assertTrue(moves.all { it.focused && it.intensity == 1.0 })
    }
    @Test fun pendingExitCanBeAbandonedWithoutChangingScale() {
        val drag = Drag(); drag.frames(20.0, 20)
        drag.reach(MetricFocusPhase.EXIT_PENDING)
        assertEquals(1.0, drag.reading.intensity, 0.0)
        drag.frames(30.0, 1)
        assertEquals(MetricFocusPhase.FOCUSED, drag.reading.phase)
        assertEquals(1.0, drag.reading.intensity, 0.0)
    }
    @Test fun slowingDuringUncommittedExitRestoresTheSameAcquiredZoom() {
        val drag = Drag(); drag.frames(20.0, 20)
        val target = MetricFocusTarget()
        val original = MetricFocusDensity.from(sampledSeries(300_000), 240.0)
        val changed = MetricFocusDensity.from(sampledSeries(300_000, 5_000), 240.0)
        val acquired = target.zoom(drag.reading, original)
        drag.reach(MetricFocusPhase.EXITING)
        assertEquals(1.0, target.zoom(drag.reading, changed), 0.0)
        drag.frames(30.0, 1)
        assertEquals(MetricFocusPhase.FOCUSED, drag.reading.phase)
        assertEquals(acquired, target.zoom(drag.reading, changed), 0.0)
    }
    @Test fun refreshDoesNotRetargetAcquiredZoomButNextAcquisitionUsesNewDensity() {
        val target = MetricFocusTarget()
        val original = MetricFocusDensity.from(sampledSeries(300_000), 240.0)
        val changed = MetricFocusDensity.from(sampledSeries(300_000, 5_000), 240.0)
        val focused = MetricFocusReading(20.0, true, 1.0, slowFocusMs = 5_000)
        assertEquals(original.maximumZoom, target.zoom(focused, original), 0.0)
        assertEquals(original.maximumZoom, target.zoom(focused, changed), 0.0)
        assertEquals(1.0, target.zoom(MetricFocusReading(600.0, false, 0.0), changed), 0.0)
        assertEquals(changed.maximumZoom, target.zoom(focused, changed), 0.0)
        target.end()
        assertEquals(original.maximumZoom, target.zoom(focused, original), 0.0)
    }
    @Test fun focusedPanDoesNotFeedVelocityOrReduceAcquiredZoom() {
        val drag = Drag(); drag.frames(20.0, 20)
        val series = sampledSeries(300_000)
        val density = MetricFocusDensity.from(series, 240.0)
        val target = MetricFocusTarget()
        val zoom = target.zoom(drag.reading, density)
        var anchor = MetricFocusAnchor(MetricTimeViewport(series.start, series.end), 150_000.0, .9)
        repeat(120) { anchor = MetricPrecisionLens.pan(anchor, zoom, 16.0, it * 16.0) }
        assertTrue(anchor.time > 150_000)
        assertEquals(zoom, target.zoom(drag.reading, density), 0.0)
    }
    @Test fun snapRetainsAdjacentPointUntilItsCaptureBoundaryIsCrossed() {
        val series = sampledSeries(10_000)
        val msPerDp = 1_000.0 / 12
        var held = 0L
        listOf(490L, 510L, 495L, 550L, 630L).forEach {
            held = MetricFocusSnap.inspect(series, it, held, msPerDp)!!.time
            assertEquals(0L, held)
        }
        held = MetricFocusSnap.inspect(series, 680, held, msPerDp)!!.time
        assertEquals(1_000L, held)
        listOf(510L, 490L, 420L).forEach {
            held = MetricFocusSnap.inspect(series, it, held, msPerDp)!!.time
            assertEquals(1_000L, held)
        }
        assertEquals(0L, MetricFocusSnap.inspect(series, 320, held, msPerDp)!!.time)
    }
    @Test fun snapNeverRetainsThroughNullOrUnsupportedGapOrDistantSamples() {
        val series = sampledSeries(10_000)
        val nullSeries = series.copy(points = series.points.map { if (it.time == 1_000L) it.copy(measurement = null) else it })
        assertNull(MetricFocusSnap.inspect(nullSeries, 1_400, 0, 100.0))
        assertEquals(2_000L, MetricFocusSnap.inspect(nullSeries, 2_100, 0, 100.0)!!.time)
        val sparse = series.copy(points = listOf(series.points[0].copy(measurement = TrafficValue(0.0, TrafficOrigin.BEACON, 100)), series.points[2]))
        assertNull(MetricFocusSnap.inspect(sparse, 1_900, 0, 1_000.0))
        assertEquals(2_000L, MetricFocusSnap.inspect(sparse, 2_010, 0, 1_000.0)!!.time)
        assertEquals(7_000L, MetricFocusSnap.inspect(series, 7_010, 0, 100.0)!!.time)
        assertNull(MetricFocusSnap.inspect(series, -1, 0, 100.0))
    }
    @Test fun snapIsPrecisionOnlyAndPreservesActualZeroAndOrigin() {
        val series = sampledSeries(10_000)
        assertEquals(1_000L, MetricFocusSnap.inspect(series, 550, 0, 0.0)!!.time)
        val selected = MetricFocusSnap.inspect(series, 550, 0, 1_000.0 / 12)!!
        assertEquals(0.0, selected.measurement!!.value, 0.0)
        assertEquals(TrafficOrigin.LOCAL_PHONE, selected.measurement.origin)
    }

    @Test fun shortAndLongPeriodsEnterWithModerateApproachBeforePrecision() {
        listOf(300_000L, 3_600_000L, 21_600_000L, 86_400_000L).forEach { duration ->
            val density = MetricFocusDensity.from(sampledSeries(duration), 240.0)
            val target = MetricFocusTarget()
            fun at(ms: Long) = target.zoom(MetricFocusReading(20.0, true, 1.0, slowFocusMs = ms), density)
            assertEquals(1.0, at(0), 0.0)
            assertTrue(at(110) in 1.1..1.8)
            assertEquals(2.0, at(220), .0001)
            assertTrue(at(400) > 2 && at(400) < density.maximumZoom)
            assertEquals(density.maximumZoom, at(5_000), 0.0)
            val finalWindow = duration / at(5_000)
            assertTrue(finalWindow >= density.minimumUsefulWindowMs - .01)
        }
    }
    @Test fun greaterZoomTakesMoreContinuedSlowMovementToReachPrecision() {
        fun completion(density: MetricFocusDensity): Long {
            val target = MetricFocusTarget()
            return (0L..3_000L step 16).first { ms ->
                target.zoom(MetricFocusReading(20.0, true, 1.0, slowFocusMs = ms), density) == density.maximumZoom
            }
        }
        val modest = MetricFocusDensity.from(sampledSeries(300_000, 10_000), 240.0)
        val short = MetricFocusDensity.from(sampledSeries(300_000), 240.0)
        val hour = MetricFocusDensity.from(sampledSeries(3_600_000), 240.0)
        val day = MetricFocusDensity.from(sampledSeries(86_400_000), 240.0)
        assertTrue(completion(modest) < completion(short))
        assertTrue(completion(short) < completion(hour))
        assertTrue(completion(hour) < completion(day))
        assertTrue(completion(day) < 1_600)
    }
    @Test fun continuedSlowDragDeepensButModerateCorrectionsPauseWithoutShrinking() {
        val drag = Drag()
        val target = MetricFocusTarget()
        val density = MetricFocusDensity.from(sampledSeries(3_600_000), 240.0)
        drag.reach(MetricFocusPhase.FOCUSED, 20.0)
        assertEquals(0L, drag.reading.slowFocusMs)
        assertEquals(1.0, target.zoom(drag.reading, density), 0.0)
        drag.frames(20.0, 14)
        val approach = target.zoom(drag.reading, density)
        assertTrue(approach in 2.0..2.1)
        // Raw speed is checked too: a single acceleration does not deepen while EMA catches up.
        drag.frames(200.0, 30).forEach { assertEquals(approach, target.zoom(it, density), 0.0) }
        drag.frames(20.0, 110)
        assertEquals(density.maximumZoom, target.zoom(drag.reading, density), 0.0)
        drag.frames(-240.0, 20).forEach { assertEquals(density.maximumZoom, target.zoom(it, density), 0.0) }
    }
    @Test fun entryProgressDoesNotCountStallsDuplicateEventsOrAutomaticPan() {
        val drag = Drag()
        drag.reach(MetricFocusPhase.FOCUSED, 20.0)
        val before = drag.reading
        assertEquals(before, drag.detector.move(drag.x, drag.time))
        val stalled = drag.detector.move(drag.x + 1, drag.time + 10_000)
        assertEquals(50L, stalled.slowFocusMs)
        val anchor = MetricFocusAnchor(MetricTimeViewport(0, 3_600_000), 1_800_000.0, .95)
        repeat(300) { MetricPrecisionLens.pan(anchor, 20.0, 16.0, 1_000.0) }
        assertEquals(stalled, drag.detector.move(drag.x + 1, drag.time + 10_000))
        assertEquals(0L, drag.detector.end().slowFocusMs)
    }
    @Test fun entryProgressIsConsistentAcrossPointerEventRates() {
        fun progress(interval: Long): Long {
            val drag = Drag()
            drag.reach(MetricFocusPhase.FOCUSED, 20.0)
            drag.frames(20.0, (640 / interval).toInt(), interval)
            return drag.reading.slowFocusMs
        }
        assertEquals(640L, progress(16))
        assertEquals(progress(16), progress(32))
    }
    @Test fun acquisitionAndPrecisionBothExpandAroundCurrentRawAnchor() {
        listOf(300_000L, 3_600_000L, 21_600_000L, 86_400_000L).forEach { duration ->
            val series = sampledSeries(duration)
            val density = MetricFocusDensity.from(series, 240.0)
            val target = MetricFocusTarget()
            val period = MetricTimeViewport(0, duration)
            var anchor = MetricFocusAnchor(period, duration * .5, .5)
            var viewport = period
            (0L..1_600L step 16).forEach { ms ->
                // The finger keeps moving during entry, using the currently visible raw time.
                val fraction = .5 + ms / 16 * .001
                val time = viewport.time(fraction)
                anchor = anchor.copy(time = time.toDouble(), fraction = fraction)
                val zoom = target.zoom(MetricFocusReading(20.0, true, 1.0, slowFocusMs = ms), density)
                viewport = MetricPrecisionLens.viewport(anchor, zoom)
                if (zoom > 1.001) assertEquals(time.toDouble(), viewport.start + fraction * viewport.duration, 1.0)
            }
            assertTrue(anchor.time > duration * .5) // Not frozen at the first acquisition point.
            assertEquals(density.maximumZoom, target.zoom(MetricFocusReading(20.0, true, 1.0, slowFocusMs = 1_600), density), 0.0)
        }
    }
    @Test fun stationaryAnchorDoesNotDriftThroughoutDeepEntry() {
        val series = sampledSeries(86_400_000)
        val density = MetricFocusDensity.from(series, 240.0)
        val target = MetricFocusTarget()
        val anchor = MetricFocusAnchor(MetricTimeViewport(0, series.end), 43_210_000.0, .47)
        (16L..1_600L step 16).forEach { ms ->
            val zoom = target.zoom(MetricFocusReading(20.0, true, 1.0, slowFocusMs = ms), density)
            val viewport = MetricPrecisionLens.viewport(anchor, zoom)
            // At the period's hard boundary alignment is necessarily constrained.
            if (viewport.start > 0 && viewport.end < series.end)
                assertEquals(anchor.time, viewport.start + anchor.fraction * viewport.duration, 1.0)
        }
    }
    @Test fun reducedMotionTargetsProgressInSmallStepsWithoutJumpToFullZoom() {
        val density = MetricFocusDensity.from(sampledSeries(86_400_000), 240.0)
        val target = MetricFocusTarget()
        var previous = 1.0
        (0L..1_600L step 50).forEach { ms ->
            // The UI can snap to each target; smooth functional entry does not depend on a spring.
            val zoom = target.zoom(MetricFocusReading(20.0, true, 1.0, slowFocusMs = ms), density)
            assertTrue(zoom >= previous)
            assertTrue(zoom / previous < 1.5)
            previous = zoom
        }
        assertEquals(density.maximumZoom, previous, 0.0)
    }
    @Test fun liveDensityRefreshCannotChangeTheEntryTrajectory() {
        val original = MetricFocusDensity.from(sampledSeries(3_600_000), 240.0)
        val refreshed = MetricFocusDensity.from(sampledSeries(3_600_000, 30_000), 240.0)
        val stable = MetricFocusTarget()
        val live = MetricFocusTarget()
        val reading = MetricFocusReading(20.0, true, 1.0)
        stable.zoom(reading, original); live.zoom(reading, original)
        listOf(110L, 220L, 400L, 700L, 5_000L).forEach { ms ->
            assertEquals(stable.zoom(reading.copy(slowFocusMs = ms), original),
                live.zoom(reading.copy(slowFocusMs = ms), refreshed), 0.0)
        }
    }
    @Test fun lowerVisualZoomStillSelectsEveryOriginalDenseSampleAndHonestGap() {
        val series = sampledSeries(86_400_000)
        val density = MetricFocusDensity.from(series, 240.0)
        val msPerDp = series.end / density.maximumZoom / 240
        assertTrue(1_000 / msPerDp < 1) // Several original seconds fit into one dp.
        var previous = 43_200_000L
        (43_200L..43_240L).forEach { second ->
            val selected = MetricFocusSnap.inspect(series, second * 1_000, previous, msPerDp)!!
            assertEquals(second * 1_000, selected.time)
            assertEquals(series.points[second.toInt()], selected)
            previous = selected.time
        }
        val missing = series.copy(points = series.points.map { if (it.time == previous) it.copy(measurement = null) else it })
        assertNull(MetricFocusSnap.inspect(missing, previous, previous - 1_000, msPerDp))
    }
}
