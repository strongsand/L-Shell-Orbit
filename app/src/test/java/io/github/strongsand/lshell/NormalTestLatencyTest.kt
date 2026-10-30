package io.github.strongsand.lshell

import io.github.strongsand.lshell.core.model.DishySnapshot
import io.github.strongsand.lshell.core.model.DishState
import io.github.strongsand.lshell.core.model.LatencyStats
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class NormalTestLatencyTest {
    private fun reading(value: Float = 24f, time: Long = 1100) = DishySnapshot(
        timestamp = time, state = DishState.CONNECTED, isOnline = true, latency = LatencyStats(value))

    @Test fun normalResultKeepsRatesAndRecordsAnIdentifiedGrpcPopReading() = runBlocking {
        val flow = MutableStateFlow(reading())
        val rates = SpeedTestResult(200f, 30f)
        val result = normalTestWithLatency(flow, 1000, now = { 1200 }) { rates }
        assertEquals(200f, result.downloadMbps, 0f)
        assertEquals(30f, result.uploadMbps, 0f)
        assertEquals(24f, result.latencyMs!!, 0f)
        assertEquals(TestLatencySource.POP_TELEMETRY, result.latencySource)
        assertEquals(1100L, result.latencySampledAt)
        assertEquals(R.string.test_pop_latency, normalTestLatencyLabel(result))
        assertEquals(0, flow.subscriptionCount.value)
    }

    @Test fun invalidDisconnectedCachedOldOrFutureReadingsRemainMissing() = runBlocking {
        val cases = listOf(reading(Float.NaN), reading(Float.POSITIVE_INFINITY), reading(-1f),
            reading(0f), reading().copy(isOnline = false), reading().copy(isFromCache = true),
            reading(time = 999), reading(time = 1201))
        for (snapshot in cases) {
            val result = normalTestWithLatency(MutableStateFlow(snapshot), 1000, now = { 1200 }) { SpeedTestResult(1f, 2f) }
            assertNull(result.latencyMs)
            assertNull(result.latencySource)
            assertNull(result.latencySampledAt)
            assertNull(ConnectionTestPresentation.metrics(ConnectionTestState(result = result)).latency)
        }
    }

    @Test fun expiryDoesNotKeepTheLastKnownValue() = runBlocking {
        var now = 1200L
        val result = normalTestWithLatency(MutableStateFlow(reading()), 1000, now = { now }) {
            now = 20_000
            SpeedTestResult(1f, 2f)
        }
        assertNull(result.latencyMs)
    }

    @Test fun genuineTestLatencyIncludingZeroHasPriorityOverTelemetry() = runBlocking {
        for (latency in listOf(0f, 12.5f)) {
            val measured = SpeedTestResult(200f, 30f, latency)
            val result = normalTestWithLatency(MutableStateFlow(reading()), 1000, now = { 1200 }) { measured }
            assertEquals(measured, result)
            assertEquals(R.string.test_latency, normalTestLatencyLabel(result))
        }
    }

    @Test fun observerUpdatesLivePresentationAndStopsAfterCompletion() = runBlocking {
        val flow = MutableStateFlow(reading(time = 900))
        var state = ConnectionTestState(id = 1, phase = ConnectionTestPhase.RUNNING)
        val observed = mutableListOf<Float?>()
        val result = normalTestWithLatency(flow, 1000, now = { 1400 }, onReading = {
            state = state.copy(liveLatencyMs = it?.milliseconds, liveLatencySampledAt = it?.sampledAt)
            observed += normalLiveLatency(state, 1400)
        }) {
            flow.value = reading(20f, 1200); yield()
            assertEquals(20f, normalLiveLatency(state, 1400)!!, 0f)
            flow.value = reading(31f, 1300); yield()
            SpeedTestResult(1f, 2f)
        }
        assertEquals(listOf(null, 20f, 31f), observed)
        assertEquals(31f, result.latencyMs!!, 0f)
        assertNull(normalLiveLatency(state.copy(phase = ConnectionTestPhase.STARTING), 1400))
        assertNull(normalLiveLatency(state.copy(phase = ConnectionTestPhase.COMPLETED), 1400))
        assertNull(normalLiveLatency(state, 30_000))
        assertEquals(0, flow.subscriptionCount.value)
    }

    @Test fun timeoutAndCancellationDoNotBecomeSuccessfulZeroLatencyResults() = runBlocking {
        val flow = MutableStateFlow(reading())
        var returned = false
        try {
            withTimeout(50) {
                normalTestWithLatency(flow, 1000, now = { 1200 }) { delay(1000); SpeedTestResult(1f, 2f) }
                returned = true
            }
            fail("Expected cancellation")
        } catch (_: TimeoutCancellationException) { }
        assertFalse(returned)
        assertEquals(0, flow.subscriptionCount.value)
    }

    @Test fun historyKeepsLatencySourceAndTheActualSampleTime() = runBlocking {
        val result = normalTestWithLatency(MutableStateFlow(reading()), 1000, now = { 1200 }) { SpeedTestResult(1f, 2f) }
        val state = ConnectionTestState(id = 1000, startedAt = 1000, endedAt = 1200,
            phase = ConnectionTestPhase.COMPLETED, result = result, completed = 1)
        val json = historicalTestDetail(state).getJSONObject("result")
        assertEquals(24.0, json.getDouble("latency"), 0.0)
        assertEquals(TestLatencySource.POP_TELEMETRY, testLatencySourceFromJson(json))
        assertEquals(1100L, json.getLong("latency_sample_time"))
        assertEquals(listOf(24.0), RecordedTest.from(state)!!.latencies)
        assertNull(testLatencySourceFromJson(org.json.JSONObject().put("latency", 12)))
    }
}
