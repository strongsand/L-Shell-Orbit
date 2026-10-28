package io.github.strongsand.lshell.ar

import org.junit.Assert.*
import org.junit.Test
import uk.me.g4dpz.satellite.GroundStationPosition
import uk.me.g4dpz.satellite.SatelliteFactory
import uk.me.g4dpz.satellite.TLE as PredictTle
import java.util.Date

class ArOrbitForecastTest {
    private fun orbit(): TLE {
        val raw = javaClass.getResource("/ar/celestrak-starlink-samples.json")!!.readText()
        return StarlinkOrbitTracker.parseCatalog(raw).first()
    }

    @Test fun realCatalogPositionAndForecastKeepEpochIdentityUnitsAndSameCameraCoordinates() {
        val orbit = orbit()
        val satellite = SatelliteFactory.createSatellite(PredictTle(arrayOf(orbit.name, orbit.line1, orbit.line2)))
        val atDate = Date(orbit.epochTimeMs)
        val subpoint = satellite.getPosition(GroundStationPosition(0.0, 0.0, 0.0), atDate)
        val latitude = Math.toDegrees(subpoint.latitude)
        val longitude = Math.toDegrees(subpoint.longitude)
        val ground = GroundStationPosition(latitude, longitude, 0.0)
        val reference = satellite.getPosition(ground, atDate)
        val source = listOf(orbit)
        val visible = StarlinkOrbitTracker.calcularSatellitesVisiveis(source, latitude, longitude, 0.0,
            minElevacao = 2f, apenasFovAntena = false, nowMs = orbit.epochTimeMs)
        val actual = visible.single()
        assertEquals(orbit.epochTimeMs, actual.orbitEpochMs)
        assertEquals(orbit.metadata!!.catalogNumber, actual.catalogNumber)
        assertEquals(Math.toDegrees(reference.azimuth).toFloat(), actual.azimute, .001f)
        assertEquals(Math.toDegrees(reference.elevation).toFloat(), actual.elevacao, .001f)
        assertEquals(reference.range.toFloat(), actual.rangeKm!!, .001f)
        val forecast = StarlinkOrbitTracker.candidateForecasts(source, visible, latitude, longitude, 0.0,
            orbit.epochTimeMs).getValue(actual.orbitKey)
        assertEquals(orbit.epochTimeMs, forecast.atTimeMs)
        assertEquals(13, forecast.points.size)
        assertEquals(actual.azimute, forecast.points.first().azimuth, .001f)
        assertEquals(actual.elevacao, forecast.points.first().elevation, .001f)
        assertEquals(180, forecast.points.last().secondsAhead)
        val futureReference = satellite.getPosition(ground, Date(orbit.epochTimeMs + 15_000L))
        assertEquals(Math.toDegrees(futureReference.azimuth).toFloat(), forecast.points[1].azimuth, .001f)
        assertEquals(Math.toDegrees(futureReference.elevation).toFloat(), forecast.points[1].elevation, .001f)
        assertEquals(ArScreenPoint(300f, 500f), projectArSatellite(600f, 1000f,
            actual.azimute, actual.elevacao, 0f, actual.azimute, actual.elevacao))
        // Future look-ahead must not poison the reused SGP4 object for the next live sample.
        val again = StarlinkOrbitTracker.calcularSatellitesVisiveis(source, latitude, longitude, 0.0,
            minElevacao = 2f, apenasFovAntena = false, nowMs = orbit.epochTimeMs).single()
        assertEquals(actual, again)
    }

    @Test fun noForecastIsFabricatedForAnUnknownOrEmptyShortlist() {
        val orbit = orbit()
        assertTrue(StarlinkOrbitTracker.candidateForecasts(listOf(orbit), emptyList(), 0.0, 0.0, 0.0, orbit.epochTimeMs).isEmpty())
        assertTrue(StarlinkOrbitTracker.candidateForecasts(listOf(orbit), listOf(SateliteRA("unknown", 0f, 60f)),
            0.0, 0.0, 0.0, orbit.epochTimeMs).isEmpty())
    }
}
