package io.github.strongsand.lshell.ar

import org.junit.Assert.*
import org.junit.Test

class ArLinkCandidatesTest {
    private val now = 1_800_000_000_000L
    private val input = ArLinkInputs(true, 1_000L, 0f, 60f, 1_000L, 5f)
    private fun sat(id: String, az: Float = 0f, el: Float = 60f, age: Long = 1_000L) =
        SateliteRA(id, az, el, catalogNumber = id, orbitEpochMs = now - age)
    private fun estimate(satellites: List<SateliteRA>, context: ArLinkInputs = input) =
        ArLinkCandidatePolicy.estimate(satellites, context, now)

    @Test fun alignmentUsesSphericalSeparationIncludingNorthWrap() {
        assertEquals(0f, angularSeparationDegrees(0f, 60f, 360f, 60f), .001f)
        assertTrue(angularSeparationDegrees(359f, 60f, 1f, 60f) < 1.1f)
        assertTrue(angularSeparationDegrees(0f, 90f, 180f, 90f) < .001f)
    }

    @Test fun aGeometricallyClearLeaderCanHaveHighRelativeConfidence() {
        val result = estimate(listOf(sat("near"), sat("far", 80f, 40f)))
        assertEquals(ArCandidateConfidence.HIGH, result.confidence)
        assertEquals(listOf("near"), result.candidates.map { it.satellite.nome })
        assertFalse(result.candidates.single().satellite.conectado)
    }

    @Test fun mediumConfidenceNeedsAModerateButRealScoreMargin() {
        val result = estimate(listOf(sat("first", 20f), sat("second", 40f)))
        assertEquals(ArCandidateConfidence.MEDIUM, result.confidence)
        assertEquals("first", result.candidates.first().satellite.nome)
    }

    @Test fun tiedCandidatesStayLowButOnlyOneStableHighlightIsShown() {
        val result = estimate(listOf(sat("c"), sat("b"), sat("a"), sat("d")))
        assertEquals(ArCandidateConfidence.LOW, result.confidence)
        assertEquals(listOf("a"), result.candidates.map { it.satellite.nome })
    }

    @Test fun aSingleCandidateDoesNotProveUniquenessInAnIncompleteCatalog() {
        assertEquals(ArCandidateConfidence.LOW, estimate(listOf(sat("one"))).confidence)
    }

    @Test fun offlineCachedOrStaleDishDoesNotProduceLinkCandidates() {
        listOf(input.copy(liveDish = false), input.copy(dishAgeMs = 15_001L), input.copy(dishAgeMs = -1L)).forEach {
            val result = estimate(listOf(sat("a")), it)
            assertEquals(ArCandidateStatus.NO_LIVE_DISH, result.status)
            assertTrue(result.candidates.isEmpty())
        }
    }

    @Test fun unknownNanInfiniteOrInvalidOrientationIsNotReplacedWithAHeading() {
        listOf(input.copy(dishAzimuth = null), input.copy(dishAzimuth = Float.NaN),
            input.copy(dishAzimuth = -1f), input.copy(dishElevation = Float.POSITIVE_INFINITY),
            input.copy(dishElevation = 0f), input.copy(dishElevation = 91f)).forEach {
            assertEquals(ArCandidateStatus.NO_ORIENTATION, estimate(listOf(sat("a")), it).status)
        }
    }

    @Test fun northIsAValidMeasuredAzimuthNotMissing() {
        assertEquals(ArCandidateStatus.AVAILABLE, estimate(listOf(sat("a"))).status)
    }

    @Test fun staleAndFutureLocationHaveNoCandidates() {
        listOf(-1L, 30_001L).forEach {
            assertEquals(ArCandidateStatus.NO_LOCATION,
                estimate(listOf(sat("a")), input.copy(locationAgeMs = it)).status)
        }
    }

    @Test fun missingOrWeakLocationAccuracyCapsConfidenceWithoutInventingPrecision() {
        listOf(null, Float.NaN, Float.POSITIVE_INFINITY, -1f, 500f).forEach {
            assertEquals(ArCandidateConfidence.LOW,
                estimate(listOf(sat("near"), sat("far", 80f, 40f)), input.copy(locationAccuracyM = it)).confidence)
        }
    }

    @Test fun olderElementsDowngradeConfidenceAndExpiredElementsDoNotGetHighlighted() {
        val older = listOf(sat("near", age = 4 * 86_400_000L), sat("far", 80f, 40f))
        assertEquals(ArCandidateConfidence.LOW, estimate(older).confidence)
        assertEquals(ArCandidateStatus.NO_CANDIDATES,
            estimate(listOf(sat("ancient", age = 8 * 86_400_000L))).status)
        assertEquals(ArCandidateStatus.NO_CANDIDATES,
            estimate(listOf(sat("future", age = -7 * 3_600_000L))).status)
        assertTrue(estimate(listOf(sat("missing").copy(orbitEpochMs = null))).candidates.isEmpty())
    }

    @Test fun invalidPositionsBelowRegionAndOppositeSkyAreExcluded() {
        assertTrue(estimate(listOf(sat("low", el = 17f), sat("opposite", 180f, 18f),
            sat("nan", Float.NaN), sat("infinite", el = Float.POSITIVE_INFINITY))).candidates.isEmpty())
    }

    @Test fun rankIsDeterministicAndDuplicateIdentitiesDoNotCreateFalseRunners() {
        val first = sat("a")
        val result = estimate(listOf(first, first.copy(nome = "same identity")))
        assertEquals(1, result.candidates.size)
        assertEquals(ArCandidateConfidence.LOW, result.confidence)
        assertEquals(estimate(listOf(sat("a"), sat("b"))), estimate(listOf(sat("b"), sat("a"))))
    }

    @Test fun nearTieHysteresisDoesNotUpgradeConfidenceOrRetainAWorseCandidateForever() {
        val satellites = listOf(sat("a"), sat("b", 1f), sat("c", 60f))
        val tied = ArLinkCandidatePolicy.estimate(satellites, input, now, previousKey = "b")
        assertEquals("b", tied.candidates.first().satellite.nome)
        assertEquals(ArCandidateConfidence.LOW, tied.confidence)
        val changed = ArLinkCandidatePolicy.estimate(satellites, input, now, previousKey = "c")
        assertEquals("a", changed.candidates.first().satellite.nome)
    }

    @Test fun residenceIsBasedOnFiniteForecastAndCannotOverrideAClearAlignmentDifference() {
        val points = listOf(ArOrbitPoint(0, 5f, 60f), ArOrbitPoint(180, 5f, 60f))
        val forecasts = mapOf("far" to ArCandidateForecast(now, points))
        val result = ArLinkCandidatePolicy.rank(listOf(sat("near"), sat("far", 80f, 40f)), input, now, forecasts)
        assertEquals("near", result.first().satellite.nome)
        assertNull(result.first().forecast)
    }

    @Test fun missingForecastDoesNotInventAnExitTimeAndStaleForecastIsIgnored() {
        val forecast = ArCandidateForecast(now - 11_000, listOf(ArOrbitPoint(0, 0f, 60f), ArOrbitPoint(15, 180f, 10f)))
        val result = ArLinkCandidatePolicy.rank(listOf(sat("a")), input, now, mapOf("a" to forecast)).single()
        assertNull(result.forecast)
    }

    @Test fun forecastExitIsASampledRegionBoundaryNotAnActualHandover() {
        val forecast = ArCandidateForecast(now, listOf(ArOrbitPoint(0, 0f, 60f), ArOrbitPoint(15, 5f, 60f),
            ArOrbitPoint(30, 180f, 10f)))
        assertEquals(30, forecast.exitSeconds(0f, 60f))
        assertEquals(25, forecast.remainingSeconds(now + 5_000, 0f, 60f))
        assertEquals(0, forecast.remainingSeconds(now + 90_000, 0f, 60f))
        assertNull(ArCandidateForecast(now, forecast.points.take(2)).exitSeconds(0f, 60f))
    }

    @Test fun invalidOrFutureForecastCannotInventResidenceOrRegionExit() {
        val good = listOf(ArOrbitPoint(0, 0f, 60f), ArOrbitPoint(15, 0f, 60f))
        val malformed = listOf(good + ArOrbitPoint(30, Float.NaN, 60f),
            listOf(good[1], good[0]), listOf(good[0], good[0]))
        malformed.forEach { points ->
            val forecast = ArCandidateForecast(now, points)
            assertFalse(forecast.valid)
            assertNull(forecast.exitSeconds(0f, 60f))
            assertNull(ArLinkCandidatePolicy.rank(listOf(sat("a")), input, now, mapOf("a" to forecast)).single().forecast)
        }
        assertNull(ArLinkCandidatePolicy.rank(listOf(sat("a")), input, now,
            mapOf("a" to ArCandidateForecast(now + 1000L, good))).single().forecast)
    }

    @Test fun projectionMatchesCameraCenterAndRollOfExistingMarkers() {
        assertEquals(ArScreenPoint(300f, 500f), projectArSatellite(600f, 1000f, 0f, 60f, 0f, 0f, 60f))
        val east = projectArSatellite(600f, 1000f, 0f, 60f, 0f, 10f, 60f)!!
        assertEquals(400f, east.x, .001f)
        val rolled = projectArSatellite(600f, 1000f, 0f, 60f, 90f, 10f, 60f)!!
        assertEquals(300f, rolled.x, .001f)
        assertEquals(400f, rolled.y, .001f)
        assertEquals(320f, projectArSatellite(600f, 1000f, 359f, 60f, 0f, 1f, 60f)!!.x, .001f)
    }

    @Test fun invalidAndBehindCameraPointsDoNotCreateTapTargets() {
        assertNull(projectArSatellite(0f, 1000f, 0f, 60f, 0f, 0f, 60f))
        assertNull(projectArSatellite(600f, 1000f, 0f, 60f, 0f, Float.NaN, 60f))
        assertNull(projectArSatellite(600f, 1000f, 0f, 60f, 0f, 180f, 60f))
    }

    @Test fun reducedMotionBackgroundAndNoVisibleCandidatesDisablePulse() {
        assertTrue(candidatePulseEnabled(true, false, true, true))
        assertFalse(candidatePulseEnabled(false, false, true, true))
        assertFalse(candidatePulseEnabled(true, true, true, true))
        assertFalse(candidatePulseEnabled(true, false, false, true))
        assertFalse(candidatePulseEnabled(true, false, true, false))
    }

    @Test fun ambiguousLeaderStillHasAVisiblePulseWithoutClaimingAConfirmedLink() {
        val result = estimate(listOf(sat("a"), sat("b")))
        assertEquals(ArCandidateConfidence.LOW, result.confidence)
        assertEquals(1, result.candidates.size)
        assertFalse(result.candidates.single().satellite.conectado)
        assertTrue(candidatePulseEnabled(true, false, true, result.candidates.isNotEmpty()))
        assertTrue(candidateVisualStrength(result.confidence, 0) >= .7f)
    }

    @Test fun confidenceAndRankChangeVisualEmphasisWithoutChangingTelemetry() {
        assertTrue(candidateVisualStrength(ArCandidateConfidence.HIGH, 0) > candidateVisualStrength(ArCandidateConfidence.MEDIUM, 0))
        assertTrue(candidateVisualStrength(ArCandidateConfidence.MEDIUM, 0) > candidateVisualStrength(ArCandidateConfidence.LOW, 0))
        assertTrue(candidateVisualStrength(ArCandidateConfidence.LOW, 0) > candidateVisualStrength(ArCandidateConfidence.LOW, 2))
        assertTrue(candidateVisualStrength(ArCandidateConfidence.LOW, 2) > 0f)
    }
}
