package io.github.strongsand.lshell

import io.github.strongsand.lshell.core.data.LivePowerReading
import io.github.strongsand.lshell.core.data.livePower
import io.github.strongsand.lshell.core.model.DishState
import io.github.strongsand.lshell.core.model.DishySnapshot
import io.github.strongsand.lshell.core.network.grpc.DishyGrpcClient
import io.github.strongsand.lshell.proto.DishGetHistoryResponse
import org.junit.Assert.*
import org.junit.Test

class LivePowerProjectionTest {
    private val now = 20_000L
    private val snapshot = DishySnapshot(timestamp = now, state = DishState.CONNECTED, isOnline = true)

    private fun history(power: List<Float> = listOf(0f)) = DishyGrpcClient.HistoryReading(
        current = 1L, downloadMbps = listOf(1f), uploadMbps = listOf(2f),
        latencyMs = listOf(20f), dropPercent = listOf(0f), powerWatts = power)

    private fun assertDisplayedPower(expected: Float, data: DishyGrpcClient.HistoryReading) {
        val reading = data.livePower(now)
        assertEquals(LivePowerReading(now, expected), reading)
        assertEquals(expected, recentAntennaPower(reading, now))
        assertEquals(CurrentMetricReading(CurrentMetricState.AVAILABLE, expected),
            currentMetricReading(MetricDetailKind.POWER, snapshot, now, reading))
    }

    private fun assertMissingPower(data: DishyGrpcClient.HistoryReading) {
        val reading = data.livePower(now)
        assertNull(reading) // Missing/invalid must never pass as a measured 0 W.
        assertNull(recentAntennaPower(reading, now))
        assertEquals(CurrentMetricReading(CurrentMetricState.UNAVAILABLE),
            currentMetricReading(MetricDetailKind.POWER, snapshot, now, reading))
    }

    @Test fun zeroWithCompleteMetricsReachesBothCurrentDisplays() {
        assertDisplayedPower(0f, history())
    }

    @Test fun zeroSurvivesEachIndependentMetricBeingAbsent() {
        val data = history()
        listOf(data.copy(downloadMbps = emptyList()), data.copy(uploadMbps = emptyList()),
            data.copy(latencyMs = emptyList()), data.copy(dropPercent = emptyList()),
            data.copy(signal = emptyList())).forEach { assertDisplayedPower(0f, it) }
    }

    @Test fun positivePowerSurvivesMissingOrZeroLatency() {
        val data = history(listOf(70f))
        assertDisplayedPower(70f, data.copy(latencyMs = emptyList()))
        assertDisplayedPower(70f, data.copy(latencyMs = listOf(0f)))
    }

    @Test fun positivePowerSurvivesMissingDownloadOrUpload() {
        val data = history(listOf(70f))
        assertDisplayedPower(70f, data.copy(downloadMbps = emptyList()))
        assertDisplayedPower(70f, data.copy(uploadMbps = emptyList()))
    }

    @Test fun powerDoesNotRequireAnyOtherMetric() {
        for (power in listOf(0f, 71.25f)) assertDisplayedPower(power,
            history(listOf(power)).copy(downloadMbps = emptyList(), uploadMbps = emptyList(),
                latencyMs = emptyList(), dropPercent = emptyList(), signal = emptyList()))
    }

    @Test fun invalidIndependentMetricsDoNotInvalidatePower() {
        assertDisplayedPower(42f, history(listOf(42f)).copy(downloadMbps = listOf(Float.NaN),
            uploadMbps = listOf(Float.POSITIVE_INFINITY), latencyMs = listOf(-1f),
            dropPercent = listOf(101f), signal = listOf(Float.NaN)))
    }

    @Test fun absentPowerIsMissingEvenWhenOtherMetricsAreComplete() {
        assertMissingPower(history(emptyList()))
    }

    @Test fun nanPowerIsMissingRatherThanZero() {
        assertMissingPower(history(listOf(Float.NaN)))
    }

    @Test fun bothInfinitePowersAreMissingRatherThanZero() {
        assertMissingPower(history(listOf(Float.POSITIVE_INFINITY)))
        assertMissingPower(history(listOf(Float.NEGATIVE_INFINITY)))
    }

    @Test fun negativePowerRemainsInvalid() {
        assertMissingPower(history(listOf(-1f)))
    }

    @Test fun latestCounterUsesThePowerRingsOwnLengthAndUnchangedProjectionTime() {
        assertDisplayedPower(0f, history(listOf(0f, 55f)).copy(current = 3L,
            downloadMbps = listOf(1f, 2f, 3f, 4f), latencyMs = emptyList()))
    }

    @Test fun invalidNewestPowerDoesNotReviveAnOlderValidReading() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f))
            assertMissingPower(history(listOf(55f, value)).copy(current = 2L))
    }

    @Test fun missingAndZeroResponsesReplaceThePreviousCurrentReading() {
        assertDisplayedPower(55f, history(listOf(55f)))
        assertMissingPower(history(emptyList()))
        assertDisplayedPower(0f, history(listOf(0f)))
    }

    @Test fun noCounterCannotFabricatePower() {
        assertMissingPower(history(listOf(55f)).copy(current = 0L))
    }

    @Test fun repeatedProtobufZeroHasPresenceUnlikeAnAbsentArray() {
        val zero = DishGetHistoryResponse.parseFrom(DishGetHistoryResponse.newBuilder()
            .setCurrent(1L).addPowerIn(0f).build().toByteArray())
        val absent = DishGetHistoryResponse.parseFrom(DishGetHistoryResponse.newBuilder()
            .setCurrent(1L).build().toByteArray())
        assertEquals(1, zero.powerInCount)
        assertEquals(0, absent.powerInCount)
        assertDisplayedPower(0f, history(zero.powerInList))
        assertMissingPower(history(absent.powerInList))
    }
}
