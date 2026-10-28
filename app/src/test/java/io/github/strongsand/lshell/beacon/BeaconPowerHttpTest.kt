package io.github.strongsand.lshell.beacon

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Loopback HTTP server and fixtures live only in tests, never in the Android panel. */
class BeaconPowerHttpTest {
    private fun fixture(): JSONObject = javaClass.getResourceAsStream("/beacon_power_http_contract.json")!!
        .bufferedReader().use { JSONObject(it.readText()) }

    private class Server : AutoCloseable {
        val requests = CopyOnWriteArrayList<String>()
        val authorization = CopyOnWriteArrayList<String>()
        @Volatile var info = "{}"
        @Volatile var status = "{}"
        @Volatile var statusCode = 200
        @Volatile var infoCode = 200
        @Volatile var blockStatus: CountDownLatch? = null
        val statusStarted = CountDownLatch(1)
        private val executor = Executors.newCachedThreadPool()
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        init {
            executor.execute {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    sockets += socket
                    executor.execute {
                        try { socket.use { serve(it) } } finally { sockets.remove(socket) }
                    }
                }
            }
        }
        private fun serve(socket: Socket) = runCatching {
            socket.soTimeout = 5000
            val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
            val request = reader.readLine().split(' ').take(2).joinToString(" ")
            requests += request
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isEmpty()) break
                if (header.startsWith("Authorization:", ignoreCase = true)) authorization += header
            }
            val isInfo = request == "GET /v1/info"
            val body = (if (isInfo) info else status).toByteArray(Charsets.UTF_8)
            val code = if (isInfo) infoCode else statusCode
            val redirect = if (code == 302) "Location: /forbidden\r\n" else ""
            val output = socket.getOutputStream()
            output.write(("HTTP/1.1 $code Test\r\nContent-Length: ${body.size}\r\n" +
                "Content-Type: application/json\r\nConnection: close\r\n$redirect\r\n").toByteArray(Charsets.US_ASCII))
            output.flush()
            if (!isInfo) { statusStarted.countDown(); blockStatus?.await(8, TimeUnit.SECONDS) }
            output.write(body); output.flush()
        }
        val endpoint: BeaconPowerEndpoint get() = BeaconPowerEndpoint.parse("127.0.0.1:${server.localPort}")
        override fun close() {
            blockStatus?.countDown(); server.close()
            sockets.forEach { runCatching { it.close() } }
            executor.shutdownNow()
        }
    }

    private fun server() = Server().also {
        val f = fixture(); it.info = f.getJSONObject("info").toString(); it.status = f.getJSONObject("status").toString()
    }

    @Test fun developmentAddressSupportsIpHostnamePortAndIpv6WithoutCommandsOrCredentials() {
        assertEquals("http://192.168.1.10:8765", BeaconPowerEndpoint.parse(" 192.168.1.10:8765 ").address)
        assertEquals("http://beacon.local:80", BeaconPowerEndpoint.parse("http://beacon.local/").address)
        assertEquals("http://[::1]:8765", BeaconPowerEndpoint.parse("[::1]:8765").address)
        for (bad in listOf("", "https://host", "http://u:p@host", "http://host/v1/status", "host?x=1",
            "host#fragment", "host:0", "host:65536", "host:", "host name:80", "http://host\\evil", "http://host/%2e")) {
            assertTrue(bad, runCatching { BeaconPowerEndpoint.parse(bad) }.isFailure)
        }
    }

    @Test fun realHttpReadsOnlyInfoAndStatusWithoutAuthorizationAndUsesSharedParser() = runBlocking {
        server().use { s ->
            val result = BeaconPowerHttpClient(s.endpoint).read().getOrThrow()
            assertEquals(listOf("GET /v1/info", "GET /v1/status"), s.requests.toList())
            assertTrue(s.authorization.isEmpty())
            val p = result.electrical!!
            assertEquals(BeaconPowerOrigin.SIMULATED, p.reading!!.origin)
            assertEquals(24.0, p.reading!!.voltageV!!, 0.0)
            assertEquals(12.5, p.reading!!.energyWh!!, 0.0)
            assertEquals(900L, p.reading!!.captureUptimeMs)
            assertTrue(p.sourceAgeMs >= 100)
            assertNull(result.recordCount) // this adapter cannot import or ACK history
        }
    }

    @Test fun httpZeroNegativeAndMissingEnergyRemainDifferentAndNoQuantityIsDerived() = runBlocking {
        server().use { s ->
            val client = BeaconPowerHttpClient(s.endpoint)
            val json = JSONObject(s.status); val e = json.getJSONObject("electrical")
            for (amps in listOf(2.0, -2.0, 0.0)) {
                e.put("current_a", amps).put("power_w", 24 * amps).put("energy_wh", amps)
                s.status = json.toString()
                val r = client.read().getOrThrow().electrical!!.reading!!
                assertEquals(amps, r.currentA!!, 0.0); assertEquals(24 * amps, r.powerW!!, 0.0)
                assertEquals(amps, r.energyWh!!, 0.0)
            }
            e.put("energy_wh", JSONObject.NULL).put("power_w", JSONObject.NULL)
            s.status = json.toString()
            val missing = client.read().getOrThrow().electrical!!
            assertEquals(BeaconPowerCondition.PARTIAL, missing.condition)
            assertNull(missing.reading!!.energyWh); assertNull(missing.reading!!.powerW)
            assertEquals(0.0, missing.reading!!.currentA!!, 0.0)
        }
    }

    @Test fun malformedHttpJsonAndInvalidContractCannotBecomeZeroOrValidMeasurements() = runBlocking {
        server().use { s ->
            val client = BeaconPowerHttpClient(s.endpoint)
            s.status = "{broken"
            val malformed = client.read(); assertTrue(malformed.isFailure)
            val state = BeaconPowerSession().failed(malformed.exceptionOrNull()!!)
            assertEquals(BeaconPowerCondition.INVALID, state.condition(0)); assertNull(state.visible(0))
            s.status = fixture().getJSONObject("status").toString()
            val json = JSONObject(s.status)
            json.getJSONObject("electrical").put("voltage_v", "NaN")
            s.status = json.toString()
            val invalid = client.read().getOrThrow().electrical!!
            assertEquals(BeaconPowerCondition.INVALID, invalid.condition); assertNull(invalid.reading!!.voltageV)
        }
    }

    @Test fun absentModuleIsCompatibleAndSensorDisconnectionHidesAllCurrentValues() = runBlocking {
        server().use { s ->
            val client = BeaconPowerHttpClient(s.endpoint)
            s.info = JSONObject(s.info).put("capabilities", org.json.JSONArray()).toString()
            s.status = "{}"
            assertEquals(BeaconPowerCondition.UNSUPPORTED, client.read().getOrThrow().electrical!!.condition)
            s.info = fixture().getJSONObject("info").toString()
            val json = fixture().getJSONObject("status")
            json.getJSONObject("electrical").put("validity", "disconnected")
            s.status = json.toString()
            val p = client.read().getOrThrow().electrical!!
            assertEquals(BeaconPowerCondition.SENSOR_DISCONNECTED, p.condition)
            assertNull(p.reading!!.currentA); assertNull(p.reading!!.energyWh)
        }
    }

    @Test fun offlineAndReconnectKeepProvenanceAndBreakTheChartRatherThanInventingPoints() = runBlocking {
        server().use { s ->
            val client = BeaconPowerHttpClient(s.endpoint)
            var state = BeaconPowerSession().accept("a", client.read().getOrThrow().electrical!!, 0)
            s.statusCode = 503
            state = state.failed(client.read().exceptionOrNull()!!)
            assertEquals(BeaconPowerCondition.OFFLINE, state.condition(10)); assertNull(state.visible(10))
            assertEquals(BeaconPowerOrigin.SIMULATED, state.origin); assertEquals(1, state.points.size)
            s.statusCode = 200
            val json = JSONObject(s.status).put("beacon_uptime_ms", 6000)
            json.getJSONObject("electrical").put("capture_uptime_ms", 5900).put("energy_wh", 12.6)
            s.status = json.toString()
            state = state.accept("a", client.read().getOrThrow().electrical!!, 5000)
            assertEquals(2, state.points.size); assertTrue(state.points.last().breakBefore)
            assertEquals(12.6, state.visible(5000)!!.energyWh!!, 0.0)
        }
    }

    @Test fun httpCaptureExpiryUsesRemoteBootTimeWithoutManufacturingUtc() = runBlocking {
        server().use { s ->
            val client = BeaconPowerHttpClient(s.endpoint)
            val p = client.read().getOrThrow().electrical!!
            val state = BeaconPowerSession().accept("a", p, 500)
            assertEquals(p.sourceAgeMs + 1000, state.ageMs(1500))
            assertNull(state.ageMs(499))
            assertNull(state.visible(500 + POWER_MAX_AGE_MS + 1))
            val json = JSONObject(s.status)
            json.getJSONObject("electrical").put("timestamp_ms", 1_800_000_000_000L)
            s.status = json.toString() // current v1 does not define an absolute electrical clock
            assertEquals(BeaconPowerCondition.INVALID, client.read().getOrThrow().electrical!!.condition)
            json.getJSONObject("electrical").put("timestamp_ms", JSONObject.NULL).put("boot_id", 8)
            s.status = json.toString()
            assertEquals(BeaconPowerCondition.INVALID, client.read().getOrThrow().electrical!!.condition)
        }
    }

    @Test fun realSourceUsesSamePanelContractAndAddressDoesNotDetermineSimulation() = runBlocking {
        server().use { s ->
            s.info = JSONObject(s.info).put("capabilities", org.json.JSONArray()).toString()
            val json = JSONObject(s.status)
            json.getJSONObject("electrical").put("source", "ina228").put("simulated", false).put("energy_basis", "device_counter")
            s.status = json.toString()
            val r = BeaconPowerHttpClient(s.endpoint).read().getOrThrow().electrical!!.reading!!
            assertEquals(BeaconPowerOrigin.REAL, r.origin); assertEquals("device_counter", r.energyBasis)
            assertEquals(12.5, r.energyWh!!, 0.0)
        }
    }

    @Test fun identityOrProtocolChangeIsRejectedWithoutFollowingRedirects() = runBlocking {
        server().use { s ->
            val client = BeaconPowerHttpClient(s.endpoint); client.read().getOrThrow()
            s.info = JSONObject(s.info).put("beacon_id", "different").toString()
            val failure = client.read().exceptionOrNull()!!
            assertTrue(failure is BeaconPowerContractException)
            assertEquals(BeaconPowerCondition.INVALID, BeaconPowerSession().failed(failure).condition(0))
            assertEquals(1, s.requests.count { it == "GET /v1/status" })
            s.infoCode = 302
            assertTrue(client.read().isFailure)
            assertFalse(s.requests.any { "forbidden" in it })
            s.infoCode = 200; s.info = JSONObject(s.info).put("protocol_version", 2).toString()
            assertTrue(BeaconPowerHttpClient(s.endpoint).read().exceptionOrNull() is BeaconPowerContractException)
        }
    }

    @Test fun oversizedOrTrailingHttpBodiesAreRejectedBeforeBecomingReadings() = runBlocking {
        server().use { s ->
            s.status = " ".repeat(64 * 1024 + 1)
            assertTrue(BeaconPowerHttpClient(s.endpoint).read().exceptionOrNull() is BeaconPowerContractException)
            s.status = fixture().getJSONObject("status").toString() + " garbage"
            assertTrue(BeaconPowerHttpClient(s.endpoint).read().exceptionOrNull() is BeaconPowerContractException)
        }
    }

    @Test fun leavingDuringHttpReadCancelsTheStreamWithoutPublishingOrPollingAgain() = runBlocking {
        server().use { s ->
            s.blockStatus = CountDownLatch(1)
            var emitted = 0
            val client = BeaconPowerHttpClient(s.endpoint)
            val collector = launch(Dispatchers.Default) { beaconPowerUpdates(client::read).collect { emitted++ } }
            assertTrue(s.statusStarted.await(3, TimeUnit.SECONDS))
            collector.cancel()
            s.blockStatus!!.countDown() // release the test server; the cancelled result must never publish
            withTimeout(7000) { collector.join() }
            assertEquals(0, emitted); assertEquals(1, s.requests.count { it == "GET /v1/status" })
        }
    }

    @Test fun changingTestTargetsCancelsAndJoinsThePreviousCollector() = runBlocking {
        val target = MutableStateFlow("configured")
        val observed = Channel<String>(Channel.UNLIMITED)
        var active = 0; var maximum = 0
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            target.collectLatest { address ->
                active++; maximum = maxOf(maximum, active); observed.send(address)
                try { awaitCancellation() } finally { active-- }
            }
        }
        assertEquals("configured", observed.receive())
        target.value = "http://external:8765"
        assertEquals("http://external:8765", observed.receive())
        assertEquals(1, active); assertEquals(1, maximum)
        collector.cancelAndJoin(); assertEquals(0, active)
    }

    @Test fun smallNonzeroReadingsDoNotRenderAsMeasuredZero() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.US)
            assertEquals("0.00", formatBeaconPowerNumber(0.0))
            assertNotEquals("0.00", formatBeaconPowerNumber(.001))
            assertNotEquals("-0.00", formatBeaconPowerNumber(-.001))
            assertEquals("12.50", formatBeaconPowerNumber(12.5))
        } finally { java.util.Locale.setDefault(original) }
    }
}
