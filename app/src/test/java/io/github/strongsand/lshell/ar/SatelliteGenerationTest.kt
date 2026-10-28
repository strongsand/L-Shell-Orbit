package io.github.strongsand.lshell.ar

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import uk.me.g4dpz.satellite.GroundStationPosition
import uk.me.g4dpz.satellite.SatelliteFactory
import uk.me.g4dpz.satellite.TLE as PredictTle
import java.util.Date
import java.io.File
import org.json.JSONArray

class SatelliteGenerationTest {
    private val source = listOf("https://example.org/verified-catalog")
    private fun metadata(designator: String?, number: String = "999999999") =
        SatelliteCatalogMetadata(number, designator, "STARLINK-SYNTHETIC", "celestrak_starlink")

    private fun catalog() = SatelliteGenerationResolver.fromJson(
        File("src/main/assets/starlink_generation_catalog.json").takeIf { it.exists() }
            .let { it ?: File("app/src/main/assets/starlink_generation_catalog.json") }.readText())
    private fun publicSamples(): String = javaClass.getResourceAsStream("/ar/celestrak-starlink-samples.json")!!
        .bufferedReader().use { it.readText() }

    @Test fun numericAndTextIdentitiesHaveOneCanonicalForm() {
        listOf(58705, 58705L, "58705", " 00058705 ").forEach {
            assertEquals("58705", SatelliteIdentity.normalizeNoradId(it))
        }
        listOf(null, 0, -1, "0", "1000000000", "58 705", "58705.0", 58705.0).forEach {
            assertNull(SatelliteIdentity.normalizeNoradId(it))
        }
        listOf("2024-002a", " 2024 – 2 A ", "2024002A", "2024-002A").forEach {
            assertEquals("2024-002A", SatelliteIdentity.normalizeInternationalDesignator(it))
        }
        listOf(null, "2024-000A", "x2024-002A", "2024-002A extra", "2024-002A B", "24-002A").forEach {
            assertNull(SatelliteIdentity.normalizeInternationalDesignator(it))
        }
        assertEquals("STARLINK-11072 [DTC]", SatelliteIdentity.normalizeObjectName("  starlink-11072   [dtc] "))
    }

    @Test fun exactIdentitiesTakePriorityWithoutResolvingConflictsByGuessing() {
        val resolver = SatelliteGenerationResolver(listOf(
            GenerationRule(SatelliteGeneration.V1, "2024-002", sources = source),
            GenerationRule(SatelliteGeneration.V2_MINI_D2C, objects = setOf("2024-002A"), sources = source),
            GenerationRule(SatelliteGeneration.V1_5, sources = source, noradIds = setOf("58705"))))
        assertEquals(SatelliteGeneration.V1_5, resolver.resolve(metadata("2024-002A", "00058705")).generation)
        assertEquals(SatelliteGeneration.V2_MINI_D2C, resolver.resolve(metadata("2024 – 2 a")).generation)
        val conflicting = SatelliteGenerationResolver(listOf(
            GenerationRule(SatelliteGeneration.V1, sources = source, noradIds = setOf("58705")),
            GenerationRule(SatelliteGeneration.V3, sources = source, noradIds = setOf("58705")),
            GenerationRule(SatelliteGeneration.V1, "2024-002", sources = source)))
        assertEquals(SatelliteGeneration.UNKNOWN, conflicting.resolve(metadata("2024-002A", "58705")).generation)
    }

    @Test fun actualAssetAndFivePublicOmmFixturesClassifyCorrectly() {
        val resolver = catalog()
        val array = JSONArray(publicSamples())
        val orbits = OmmOrbitAdapter.parse(array.toString(), "celestrak_starlink_supgp")
        assertEquals(5, orbits.size)
        orbits.forEachIndexed { i, orbit ->
            val expected = SatelliteGeneration.valueOf(array.getJSONObject(i).getString("EXPECTED_GENERATION"))
            val result = resolver.resolve(orbit.metadata!!)
            assertEquals(orbit.name, expected, result.generation)
            assertEquals(expected != SatelliteGeneration.UNKNOWN, result.verified)
            if (result.verified) assertTrue(result.sources.isNotEmpty())
        }
        assertEquals(SatelliteGeneration.V2_MINI_D2C, resolver.resolve(metadata(null, "58705")).generation)
        assertEquals(SatelliteGeneration.V1, resolver.resolve(metadata("2020-001P", "44927")).generation)
        assertEquals(SatelliteGeneration.V1_5, resolver.resolve(metadata("2021-104B", "49409")).generation)
        assertEquals(SatelliteGeneration.V2_MINI, resolver.resolve(metadata("2023-026A")).generation)
        assertEquals(SatelliteGeneration.V2_MINI_D2C, resolver.resolve(metadata("2024-002A")).generation)
        assertEquals(SatelliteGeneration.V2_MINI, resolver.resolve(metadata("2024-002G")).generation)
        assertEquals(SatelliteGeneration.V3, resolver.resolve(metadata("2026-225M", "100866")).generation)
        assertEquals(SatelliteGeneration.UNKNOWN, resolver.resolve(metadata("2026-225AC")).generation)
        assertEquals(SatelliteGeneration.UNKNOWN, resolver.resolve(metadata("2026-999A")).generation)
    }

    @Test fun everyShippedCatalogIdentityResolvesItsDeclaredFamily() {
        val path = File("src/main/assets/starlink_generation_catalog.json").takeIf { it.exists() }
            ?: File("app/src/main/assets/starlink_generation_catalog.json")
        val rules = org.json.JSONObject(path.readText()).getJSONArray("rules")
        val resolver = catalog()
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            val expected = SatelliteGeneration.valueOf(rule.getString("generation"))
            val objects = rule.getJSONArray("objects")
            for (j in 0 until objects.length()) {
                assertEquals(expected, resolver.resolve(metadata(objects.getString(j))).generation)
            }
            val norad = rule.getJSONArray("noradIds")
            for (j in 0 until norad.length()) {
                assertEquals(expected, resolver.resolve(metadata(null, norad.getString(j))).generation)
            }
        }
    }

    @Test fun knownNoradResolvesTheObservedGpSupgpDesignatorMismatch() {
        // Public STARLINK-1771: SupGP lists 2020-062BN, GP/index list 2020-062BM.
        // Both feeds retain NORAD 46384; the independently reviewed exact NORAD wins.
        val resolver = catalog()
        assertEquals(SatelliteGeneration.V1, resolver.resolve(metadata("2020-062BN", "46384")).generation)
        assertEquals(SatelliteGeneration.UNKNOWN, resolver.resolve(metadata("2020-062BN")).generation)
    }

    @Test fun ommNumericStringVariantsAndOptionalIdentityDoNotDropAnOrbit() {
        val array = JSONArray(publicSamples())
        val first = array.getJSONObject(0)
        first.put("NORAD_CAT_ID", "00044927")
        first.put("OBJECT_ID", "2020 – 1 p")
        first.put("OBJECT_NAME", "  starlink-1114 ")
        val parsed = StarlinkOrbitTracker.parseCatalog(array.toString(), "celestrak_starlink")
        assertEquals(5, parsed.size)
        assertEquals("44927", parsed[0].metadata!!.catalogNumber)
        assertEquals("2020-001P", parsed[0].metadata!!.internationalDesignator)
        assertEquals("STARLINK-1114", parsed[0].name)
        assertEquals("celestrak_starlink", parsed[0].metadata!!.feed)
        first.remove("OBJECT_ID")
        val noDesignator = OmmOrbitAdapter.parse(array.toString(), "celestrak_starlink")[0]
        assertNull(noDesignator.metadata!!.internationalDesignator)
        assertEquals(SatelliteGeneration.V1, catalog().resolve(noDesignator.metadata!!).generation)
        first.remove("MEAN_MOTION")
        assertEquals(4, OmmOrbitAdapter.parse(array.toString(), "celestrak_starlink").size)
    }

    @Test fun filtersRetainUnknownInAllAndSeparateEachFamily() {
        SatelliteGeneration.entries.forEach { generation ->
            val classification = SatelliteClassification(generation)
            assertTrue(classification.matchesGenerationFilter(null))
            SatelliteGeneration.entries.forEach { filter ->
                assertEquals(generation == filter, classification.matchesGenerationFilter(filter))
            }
        }
    }

    @Test fun malformedOrUnsourcedAssetRulesAreNotSilentlyAccepted() {
        assertTrue(runCatching { SatelliteGenerationResolver.fromJson("""{"version":1,"rules":[{"generation":"V3","objects":["2026-999A"],"sources":[]}]}""") }.isFailure)
        assertTrue(runCatching { SatelliteGenerationResolver.fromJson("""{"version":1,"rules":[{"generation":"V3","objects":["garbage"],"sources":["https://example.org"]}]}""") }.isFailure)
    }

    @Test fun verifiedLaunchRulesClassifyOnlyMatchingIdentities() {
        val resolver = SatelliteGenerationResolver(listOf(GenerationRule(SatelliteGeneration.V1_5, "2021-082", sources = source)))
        val result = resolver.resolve(metadata("2021-082AB"))
        assertEquals(SatelliteGeneration.V1_5, result.generation)
        assertTrue(result.verified)
        assertEquals(source, result.sources)
        assertEquals(SatelliteGeneration.UNKNOWN, resolver.resolve(metadata("2021-083A")).generation)
        assertEquals(SatelliteGeneration.UNKNOWN, resolver.resolve(metadata(null)).generation)
    }

    @Test fun mixedLaunchDoesNotClassifyAllPayloadsAsDirectToCell() {
        val resolver = SatelliteGenerationResolver(listOf(GenerationRule(SatelliteGeneration.V2_MINI_D2C,
            objects = setOf("2024-002A", "2024-002B"), sources = source)))
        assertEquals(SatelliteGeneration.V2_MINI_D2C, resolver.resolve(metadata("2024-002A")).generation)
        assertEquals(SatelliteGeneration.UNKNOWN, resolver.resolve(metadata("2024-002G")).generation)
    }

    @Test fun unsupportedOrConflictingEvidenceRemainsUnknown() {
        val resolver = SatelliteGenerationResolver(listOf(
            GenerationRule(SatelliteGeneration.V1, "2021-082", sources = source),
            GenerationRule(SatelliteGeneration.V3, "2021-082", sources = source)))
        assertFalse(resolver.resolve(metadata("2021-082A")).verified)
        assertEquals(SatelliteGeneration.UNKNOWN, resolver.resolve(metadata("2021-082A")).generation)
        val sourced = SatelliteGenerationResolver(listOf(GenerationRule(SatelliteGeneration.V3, "2026-999", sources = source)))
        assertEquals(SatelliteGeneration.V3, sourced.resolve(metadata("2026-999A")).generation)
        assertEquals(SatelliteGeneration.UNKNOWN, sourced.resolve(metadata("2026-998A")).generation)
        assertEquals(SatelliteGeneration.UNKNOWN, sourced.resolve(metadata("2026-999A").copy(feed = "other")).generation)
        assertEquals(SatelliteGeneration.UNKNOWN, sourced.resolve(metadata("2026-999A").copy(objectName = "V3", internationalDesignator = null)).generation)
    }

    private fun elements() = OmmElements(metadata("2026-999A", "100123"), Instant.parse("2026-09-30T12:30:00Z"),
        15.0, 0.0001, 53.0, 120.0, 40.0, 80.0, 0.00012, 0.0001, 0.0)

    @Test fun modernIdentitySurvivesLegacyPropagatorAdapter() {
        val orbit = OmmOrbitAdapter.toOrbit(elements())
        assertEquals("100123", orbit.metadata?.catalogNumber)
        assertEquals("2026-999A", orbit.metadata?.internationalDesignator)
        assertEquals(69, orbit.line1.length)
        assertEquals(69, orbit.line2.length)
        val tle = PredictTle(arrayOf(orbit.name, orbit.line1, orbit.line2))
        assertEquals(15.0, tle.meanmo, 1e-8)
        assertEquals(0.00012, tle.bstar, 1e-10)
        assertEquals(0.0001, tle.eccn, 1e-8)
        val position = SatelliteFactory.createSatellite(tle).getPosition(
            GroundStationPosition(0.0, 0.0, 0.0), Date(orbit.epochTimeMs))
        assertTrue(position.azimuth.isFinite())
        assertTrue(position.elevation.isFinite())
        assertTrue(position.range.isFinite() && position.range > 0)
        val other = OmmOrbitAdapter.toOrbit(elements().copy(metadata = metadata("2026-999B", "999999999")))
        assertEquals("999999999", other.metadata?.catalogNumber)
        assertEquals(orbit.line1, other.line1) // Private token cannot change propagation or real identity.
        assertEquals(orbit.line2, other.line2)
    }

    @Test fun invalidOrMissingOrbitalValuesAreRejectedInsteadOfInvented() {
        listOf(elements().copy(meanMotion = Double.NaN), elements().copy(meanMotion = 0.0),
            elements().copy(eccentricity = 1.0), elements().copy(bstar = Double.POSITIVE_INFINITY),
            elements().copy(epoch = Instant.parse("2057-01-01T00:00:00Z")),
            elements().copy(metadata = metadata(null, "not-a-number"))).forEach { value ->
            assertTrue(runCatching { OmmOrbitAdapter.toOrbit(value) }.isFailure)
        }
    }
}
