package io.github.strongsand.lshell.beacon

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BeaconPowerTelemetryTest {
    private val capabilities = setOf(POWER_SIMULATION_CAPABILITY)
    private fun wire(time: Long = 0, boot: Long = 7) = JSONObject("""{
        "schema_version":1,"source":"simulator","simulated":true,"circuit":"battery_bus","circuit_index":0,
        "validity":"valid","energy_basis":"net_since_sensor_start","timestamp_ms":null,
        "capture_uptime_ms":$time,"boot_id":$boot,"voltage_v":24,"current_a":2,"power_w":48,"energy_wh":0,
        "battery_percent":null,"remaining_wh":null,"charging":null,"autonomy_seconds":null} """)
    private fun parse(reading: JSONObject = wire(), uptime: Long = reading.optLong("capture_uptime_ms"), boot: Long = 7) =
        BeaconPowerParser.parse(JSONObject().put("boot_id", boot).put("beacon_uptime_ms", uptime).put("electrical", reading), capabilities)
    private fun packet(time: Long, boot: Long = 7) = parse(wire(time, boot), time, boot)

    @Test fun oldBeaconWithoutCapabilityRemainsSupportedWithoutElectricalData() {
        assertEquals(BeaconPowerCondition.UNSUPPORTED, BeaconPowerParser.parse(JSONObject(), emptySet()).condition)
        assertEquals(BeaconPowerCondition.UNSUPPORTED, BeaconPowerParser.parse(JSONObject().put("electrical", wire()), emptySet()).condition)
        assertEquals(BeaconPowerCondition.WAITING, BeaconPowerParser.parse(JSONObject(), capabilities).condition)
    }
    @Test fun measuredZeroAndZeroUptimeArePreserved() {
        val result = parse(wire().put("voltage_v", 0).put("current_a", 0).put("power_w", 0))
        assertEquals(BeaconPowerCondition.VALID, result.condition)
        assertEquals(0.0, result.reading!!.voltageV!!, 0.0)
        assertEquals(0.0, result.reading!!.currentA!!, 0.0)
        assertEquals(0.0, result.reading!!.powerW!!, 0.0)
        assertEquals(0L, result.reading!!.captureUptimeMs)
        assertNull(result.reading!!.batteryPercent)
    }
    @Test fun missingAndInvalidNumbersNeverBecomeZero() {
        val missing = parse(wire().put("current_a", JSONObject.NULL).put("power_w", JSONObject.NULL))
        assertEquals(BeaconPowerCondition.PARTIAL, missing.condition)
        assertNull(missing.reading!!.currentA)
        for (bad in listOf<Any>("NaN", "Infinity", "0", true, -1.0, 1e13)) {
            val result = parse(wire().put("voltage_v", bad))
            assertEquals(BeaconPowerCondition.INVALID, result.condition)
            assertNull(result.reading!!.voltageV)
        }
        assertNull(BeaconPowerSession().accept("a", parse(wire().put("voltage_v", "NaN")), 0).visible(0))
    }
    @Test fun actualNonfiniteNumbersAreRejectedWithoutJsonCoercion() {
        val nan = object : Number() {
            override fun toByte() = 0.toByte(); override fun toShort() = 0.toShort()
            override fun toInt() = 0; override fun toLong() = 0L
            override fun toFloat() = Float.NaN; override fun toDouble() = Double.NaN
        }
        // JSONObject rejects nonfinite primitive values; a Number wrapper exercises the parser boundary.
        assertEquals(BeaconPowerCondition.INVALID, parse(wire().put("current_a", nan)).condition)
    }
    @Test fun signedChargingCurrentPowerAndEnergyAreRetained() {
        val r = parse(wire().put("current_a", -2).put("power_w", -48).put("energy_wh", -1).put("charging", true)).reading!!
        assertEquals(-2.0, r.currentA!!, 0.0); assertEquals(-48.0, r.powerW!!, 0.0)
        assertEquals(-1.0, r.energyWh!!, 0.0); assertEquals(true, r.charging)
    }
    @Test fun inconsistentPowerIsInvalidRatherThanRecalculated() {
        val p = parse(wire().put("power_w", 999))
        assertEquals(BeaconPowerCondition.INVALID, p.condition); assertNull(p.reading!!.powerW)
        assertEquals(24.0, p.reading!!.voltageV!!, 0.0)
    }
    @Test fun metadataMustProveTheSupportedSimulatedSchemaAndCircuit() {
        for ((key, value) in listOf("simulated" to false, "simulated" to "true", "source" to "ina228",
            "circuit" to "unknown", "circuit_index" to -1, "capture_uptime_ms" to "0", "validity" to "ok",
            "timestamp_ms" to 1_800_000_000_000L)) {
            assertEquals("$key=$value", BeaconPowerCondition.INVALID, parse(wire().put(key, value)).condition)
        }
        assertEquals(BeaconPowerCondition.UNSUPPORTED, parse(wire().put("schema_version", 2)).condition)
    }
    @Test fun unknownFieldsAreIgnoredAndNotImportedIntoHistoricalModels() {
        val p = parse(wire().put("future_field", 123).put("power_watts", 999))
        assertEquals(48.0, p.reading!!.powerW!!, 0.0)
        assertEquals(BeaconPowerCondition.VALID, p.condition)
        assertNull(BeaconRuntimeStatus(BeaconDevice("a", "b", "localhost", 80)).electrical)
    }
    @Test fun invalidBootAndFutureCaptureDoNotInventUtc() {
        assertEquals(BeaconPowerCondition.INVALID, parse(wire(100), 99).condition)
        assertEquals(BeaconPowerCondition.INVALID, parse(wire(0, 8), 0).condition)
        assertEquals(BeaconPowerCondition.INVALID, parse(wire(0, 0), 0, 0).condition)
        assertEquals(BeaconPowerCondition.INVALID, parse(wire().put("boot_id", 7.5)).condition)
    }
    @Test fun disconnectedSensorCannotRetainReportedOldValues() {
        val r = parse(wire().put("validity", "disconnected"))
        assertEquals(BeaconPowerCondition.SENSOR_DISCONNECTED, r.condition)
        assertNull(r.reading!!.voltageV); assertNull(r.reading!!.powerW)
        assertNull(BeaconPowerSession().accept("a", r, 0).visible(0))
    }
    @Test fun staleSourceAndReceiptAgeExpireIndependently() {
        val old = BeaconPowerSession().accept("a", parse(wire(), 20_000), 1000)
        assertEquals(BeaconPowerCondition.STALE, old.condition(1000)); assertNull(old.points.single().power)
        val fresh = BeaconPowerSession().accept("a", parse(wire(), 10_000), 1000)
        assertNotNull(fresh.visible(6000)); assertNull(fresh.visible(6001))
        assertNull(fresh.visible(999)) // monotonic clock rollback
    }
    @Test fun cachedSampleDoesNotRenewFreshnessOrAddPoints() {
        val first = BeaconPowerSession().accept("a", packet(1000), 100)
        val repeated = first.accept("a", parse(wire(1000), 14_000), 13_100)
        assertEquals(1, repeated.points.size)
        assertNull(repeated.visible(15_101))
        val aged = first.accept("a", parse(wire(1000), 99_000), 200)
        assertNull(aged.visible(200))
    }
    @Test fun sameCaptureCannotChangeIntoFreshDifferentMeasurements() {
        val first = BeaconPowerSession().accept("a", packet(1000), 0)
        val changed = first.accept("a", parse(wire(1000).put("current_a", 3).put("power_w", 72)), 1000)
        assertNull(changed.visible(1000)); assertEquals(1, changed.points.size)
    }
    @Test fun newCaptureUpdatesMetricsAndPreservesRealTemporalSpacing() {
        val s = BeaconPowerSession().accept("a", packet(1000), 0)
            .accept("a", parse(wire(6000).put("current_a", 3).put("power_w", 72)), 5000)
        assertEquals(listOf(1000L, 6000L), s.points.map { it.uptimeMs })
        assertEquals(72.0, s.visible(5000)!!.powerW!!, 0.0)
    }
    @Test fun networkLossHidesCurrentAndBreaksTheNextSegmentWithoutInventingASample() {
        val first = BeaconPowerSession().accept("a", packet(1000), 0)
        val lost = first.disconnected()
        assertNull(lost.visible(100)); assertEquals(1, lost.points.size)
        assertEquals(BeaconPowerCondition.OFFLINE, lost.condition(100))
        val back = lost.accept("a", packet(6000), 5000)
        assertTrue(back.points.last().breakBefore); assertNotNull(back.visible(5000))
    }
    @Test fun bootDeviceAndCircuitChangesNeverJoinDifferentSessions() {
        val first = BeaconPowerSession().accept("a", packet(1000), 0)
        assertEquals(1, first.accept("a", packet(0, 8), 5000).points.size)
        assertEquals(1, first.accept("b", packet(6000), 5000).points.size)
        assertEquals(1, first.accept("a", parse(wire(6000).put("circuit", "solar_input")), 5000).points.size)
        val rollback = first.accept("a", packet(999), 5000)
        assertNull(rollback.visible(5000)); assertNull(rollback.accept("a", packet(998), 6000).visible(6000))
    }
    @Test fun boundedBufferAndPerMetricGapsDoNotFillMissingSamples() {
        var s = BeaconPowerSession()
        repeat(150) { s = s.accept("a", packet(it * 1000L), it * 1000L) }
        assertEquals(120, s.points.size)
        s = s.accept("a", parse(wire(150_000).put("power_w", JSONObject.NULL)), 150_000)
        assertNull(s.points.last().power); assertEquals(24.0, s.points.last().voltage!!, 0.0)
        assertTrue(s.accept("a", packet(180_000), 180_000).points.last().breakBefore)
    }
    @Test fun allSevenActualCppFixturesDecodeAndRemainExplicitlySimulated() {
        val file = File("src/test/resources/beacon_power_samples_v1.json").takeIf { it.exists() }
            ?: File("app/src/test/resources/beacon_power_samples_v1.json")
        for (scenario in BeaconPowerScenario.entries) {
            val frames = BeaconPowerFixtures.read(file.readText(), scenario)
            assertTrue(frames.size in 31..33)
            frames.forEach { p ->
                val r = p.reading!!
                if (scenario == BeaconPowerScenario.DISCONNECTED) assertEquals(BeaconPowerCondition.SENSOR_DISCONNECTED, p.condition)
                else { assertEquals(BeaconPowerCondition.VALID, p.condition); assertEquals(r.voltageV!! * r.currentA!!, r.powerW!!, .00001) }
            }
        }
        val discharge = BeaconPowerFixtures.read(file.readText(), BeaconPowerScenario.DISCHARGE)
        assertEquals(100.0, discharge.first().reading!!.batteryPercent!!, 0.0)
        assertEquals(0.0, discharge.last().reading!!.batteryPercent!!, 0.0)
        val charge = BeaconPowerFixtures.read(file.readText(), BeaconPowerScenario.CHARGING)
        assertTrue(charge.first().reading!!.currentA!! < 0)
        assertEquals(80.0, charge.last().reading!!.batteryPercent!!, 0.0)
        val solar = BeaconPowerFixtures.read(file.readText(), BeaconPowerScenario.SOLAR)
        assertTrue(solar.any { it.reading!!.powerW!! == 80.0 })
    }
    @Test fun mockTransportReadsOnlyVerifiedInfoAndStatusWithoutCommands() = runBlocking {
        val device = BeaconDevice("a", "b", "localhost", 80, protocolVersion = 1)
        val calls = mutableListOf<String>()
        val result = readBeaconPowerDiagnostic(device,
            info = { calls += "info"; Result.success(it.copy(capabilities = capabilities)) },
            status = { calls += "status"; Result.success(BeaconRuntimeStatus(it, electrical = packet(0))) })
        assertTrue(result.isSuccess); assertEquals(listOf("info", "status"), calls)
        assertNotNull(result.getOrThrow().electrical)
        var requested = false
        assertTrue(readBeaconPowerDiagnostic(device, { Result.success(it.copy(id = "other")) },
            { requested = true; Result.failure(Exception()) }).isFailure)
        assertFalse(requested)
    }
    @Test fun mockCancellationRemainsCancellationRatherThanAnOfflineMeasurement() = runBlocking {
        val device = BeaconDevice("a", "b", "localhost", 80, protocolVersion = 1)
        try {
            readBeaconPowerDiagnostic(device, { throw kotlinx.coroutines.CancellationException() }, { error("must not call") })
            fail("Cancellation swallowed")
        } catch (_: kotlinx.coroutines.CancellationException) { }
    }
    @Test fun mockOldBeaconStillReturnsItsNormalStatusAndNeverRequestsANewEndpoint() = runBlocking {
        val device = BeaconDevice("old", "Beacon", "localhost", 80, protocolVersion = 1)
        val result = readBeaconPowerDiagnostic(device, { Result.success(it) }, {
            Result.success(BeaconRuntimeStatus(it, pendingRecords = 42, electrical = BeaconPowerParser.parse(JSONObject(), it.capabilities)))
        }).getOrThrow()
        assertEquals(42L, result.pendingRecords)
        assertEquals(BeaconPowerCondition.UNSUPPORTED, result.electrical!!.condition)
    }
    @Test fun mockProtocolMismatchAndNetworkFailureDoNotSupplyMeasurements() = runBlocking {
        val device = BeaconDevice("a", "b", "localhost", 80, protocolVersion = 1)
        var called = false
        assertTrue(readBeaconPowerDiagnostic(device, { Result.success(it.copy(protocolVersion = 2)) },
            { called = true; error("must not read") }).isFailure)
        assertFalse(called)
        assertTrue(readBeaconPowerDiagnostic(device, { Result.failure(Exception("offline")) },
            { called = true; error("must not read") }).isFailure)
        assertFalse(called)
    }
    @Test fun slowMockResponseIsAlreadyStaleAtArrival() = runBlocking {
        val device = BeaconDevice("a", "b", "localhost", 80, protocolVersion = 1)
        var time = 0L
        val result = readBeaconPowerDiagnostic(device, { Result.success(it) }, {
            time = 16_000
            Result.success(BeaconRuntimeStatus(it, electrical = packet(0)))
        }, elapsed = { time }).getOrThrow()
        val s = BeaconPowerSession().accept(device.id, result.electrical!!, 50_000)
        assertEquals(BeaconPowerCondition.STALE, s.condition(50_000))
        assertNull(s.visible(50_000))
    }
    @Test fun cancellationReturnedByStatusAlsoRemainsCancellation() = runBlocking {
        val device = BeaconDevice("a", "b", "localhost", 80, protocolVersion = 1)
        try {
            readBeaconPowerDiagnostic(device, { Result.success(it) }, { Result.failure(kotlinx.coroutines.CancellationException()) })
            fail("Status cancellation was swallowed")
        } catch (_: kotlinx.coroutines.CancellationException) { }
    }

    @Test fun simulatedAndFutureRealOriginsUseTheExistingV1WireFields() {
        assertEquals(BeaconPowerOrigin.SIMULATED, packet(0).reading!!.origin)
        for (source in listOf("ina228", "solar_controller", "bms")) {
            val reading = wire().put("source", source).put("simulated", false)
            val status = JSONObject().put("boot_id", 7).put("beacon_uptime_ms", 0).put("electrical", reading)
            val result = BeaconPowerParser.parse(status, emptySet()) // no invented future capability
            assertEquals(BeaconPowerCondition.VALID, result.condition)
            assertEquals(BeaconPowerOrigin.REAL, result.reading!!.origin)
            assertEquals(source, result.reading!!.source)
        }
        assertEquals(BeaconPowerCondition.INVALID, parse(wire().put("simulated", false)).condition)
        assertEquals(BeaconPowerCondition.INVALID, parse(wire().put("source", "bms")).condition)
        assertEquals(BeaconPowerOrigin.UNAVAILABLE, BeaconPowerSession().origin)
    }

    @Test fun provenancePersistsWithRetainedOfflineOrExpiredChartsAndSeparatesSources() {
        val simulated = BeaconPowerSession().accept("a", packet(0), 0)
        assertEquals(BeaconPowerOrigin.SIMULATED, simulated.disconnected().origin)
        assertNull(simulated.disconnected().visible(1000))
        assertEquals(BeaconPowerOrigin.SIMULATED, simulated.origin)
        assertNull(simulated.visible(20_000))
        val real = parse(wire(1000).put("source", "ina228").put("simulated", false))
        val changed = simulated.accept("a", real, 1000)
        assertEquals(BeaconPowerOrigin.REAL, changed.origin)
        assertEquals(1, changed.points.size)
        assertTrue(changed.points.single().breakBefore)
    }

    @Test fun malformedHttpJsonIsInvalidRatherThanAnUnreachableDevice() {
        val first = BeaconPowerSession().accept("a", packet(0), 0)
        val invalid = first.failed(org.json.JSONException("synthetic malformed body"))
        assertEquals(BeaconPowerCondition.INVALID, invalid.condition(1000))
        assertEquals(BeaconPowerOrigin.SIMULATED, invalid.origin) // retained chart must keep its warning
        assertNull(invalid.visible(1000))
        assertEquals(1, invalid.points.size)
        assertEquals(BeaconPowerCondition.OFFLINE, first.failed(java.io.IOException("offline")).condition(1000))
    }

    @Test fun onlyTransportResponsesProducePointsAndClosedScreensStopPolling() = runBlocking {
        val device = BeaconDevice("a", "b", "localhost", 80, protocolVersion = 1)
        val gate = Channel<Unit>(Channel.UNLIMITED)
        var calls = 0
        var observed = BeaconPowerSession()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            beaconPowerUpdates(read = {
                calls++
                Result.success(BeaconRuntimeStatus(device, electrical = packet(calls * 1000L)))
            }, waitForNext = { gate.receive() }).collect { response ->
                observed = observed.accept(device.id, response.getOrThrow().electrical!!, calls * 1000L)
            }
        }
        assertEquals(1, calls); assertEquals(listOf(1000L), observed.points.map { it.uptimeMs })
        collector.cancelAndJoin()
        gate.trySend(Unit)
        assertEquals(1, calls); assertEquals(1, observed.points.size)
        gate.close()
        Unit
    }

    @Test fun rotationStyleStopStartCancelsTheOldCollectorBeforeTheNewOneRuns() = runBlocking {
        var active = 0; var maximum = 0; var calls = 0
        val read: suspend () -> Result<BeaconRuntimeStatus>? = {
            active++; calls++; maximum = maxOf(maximum, active)
            try { awaitCancellation() } finally { active-- }
        }
        val first = launch(start = CoroutineStart.UNDISPATCHED) { beaconPowerUpdates(read).collect() }
        assertEquals(1, active)
        first.cancelAndJoin(); assertEquals(0, active)
        val second = launch(start = CoroutineStart.UNDISPATCHED) { beaconPowerUpdates(read).collect() }
        assertEquals(1, active); assertEquals(2, calls); assertEquals(1, maximum)
        second.cancelAndJoin(); assertEquals(0, active)
    }
}
