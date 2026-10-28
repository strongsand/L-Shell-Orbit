package io.github.strongsand.lshell

import io.github.strongsand.lshell.beacon.*
import io.github.strongsand.lshell.core.network.grpc.RouterClientInfo
import io.github.strongsand.lshell.core.network.grpc.RouterClientRole
import io.github.strongsand.lshell.core.network.grpc.RouterClientInterface
import org.junit.Assert.*
import org.junit.Test

class RichHomePresentationTest {
    @Test fun insufficientReportShowsCoverageEvenWithPerfectObservedAvailability() {
        assertFalse(HomePresentation.showAvailability(HealthGrade.INSUFFICIENT, HealthConfidence.HIGH, 100.0))
    }
    @Test fun lowConfidenceDoesNotHeadlineObservedAvailability() {
        assertFalse(HomePresentation.showAvailability(HealthGrade.GOOD, HealthConfidence.LOW, 100.0))
    }
    @Test fun reliableReportCanShowAvailabilityWithoutInventingStability() {
        for (grade in listOf(HealthGrade.GOOD, HealthGrade.FAIR, HealthGrade.POOR))
            assertTrue(HomePresentation.showAvailability(grade, HealthConfidence.MEDIUM, 99.9))
    }
    @Test fun nullAndNonfiniteAvailabilityFallBackToCoverage() {
        for (value in listOf(null, Double.NaN, Double.POSITIVE_INFINITY))
            assertFalse(HomePresentation.showAvailability(HealthGrade.GOOD, HealthConfidence.HIGH, value))
    }
    @Test fun realZeroAvailabilityRemainsAValidMetric() {
        assertTrue(HomePresentation.showAvailability(HealthGrade.POOR, HealthConfidence.HIGH, 0.0))
    }
    @Test fun homeMetricsAdaptToNothingPhoneWidthAndFourFontScales() {
        assertFalse(HomePresentation.stacked(361f, 1f))
        for (font in listOf(1.15f, 1.3f, 2f)) assertTrue(HomePresentation.stacked(361f, font))
        assertFalse(HomePresentation.stacked(800f, 2f))
    }
    @Test fun compactPowerHasASeparateBreakpoint() {
        assertFalse(HomePresentation.stacked(329f, 1f, 280f))
        assertFalse(HomePresentation.stacked(329f, 1.15f, 280f))
        assertTrue(HomePresentation.stacked(329f, 1.3f, 280f))
        assertTrue(HomePresentation.stacked(260f, 1f, 280f))
    }
    @Test fun shortcutGridRespectsActualWidthAndFontScale() {
        assertTrue(HealthReportUiPolicy.twoColumns(361f, 1f, 340f))
        for (font in listOf(1.15f, 1.3f, 2f)) assertFalse(HealthReportUiPolicy.twoColumns(361f, font, 340f))
    }
    @Test fun missingTelemetryDoesNotLeakNanOrInventZero() {
        assertEquals("— Mbps", HomePresentation.reading(true, Float.NaN, "NaN Mbps", "Mbps"))
        assertEquals("— ms", HomePresentation.reading(true, Float.POSITIVE_INFINITY, "Infinity ms", "ms"))
        assertEquals("— Mbps", HomePresentation.reading(false, 285f, "285 Mbps", "Mbps"))
    }
    @Test fun validZeroAndModelFormattingStayIntact() {
        assertEquals("0.0 Mbps", HomePresentation.reading(true, 0f, "0.0 Mbps", "Mbps"))
        assertEquals("-- ms", HomePresentation.reading(true, 0f, "-- ms", "ms"))
        assertEquals("285,3 Mbps", HomePresentation.reading(true, 285.3f, "285,3 Mbps", "Mbps"))
    }
    private val device = BeaconDevice("synthetic", "Preview Beacon", "beacon.local", 80)
    private val status = BeaconRuntimeStatus(device, pendingRecords = 200, recording = true)
    @Test fun pendingRecordsDoNotMaskDishUnavailability() {
        assertEquals(R.string.beacon_online_dishy_unavailable, beaconHomeLabel(BeaconState.DishyUnavailable(status)))
    }
    @Test fun pendingRecordsDoNotMaskStorageWarningOrError() {
        assertEquals(R.string.beacon_storage_warning, beaconHomeLabel(BeaconState.StorageWarning(status)))
        assertEquals(R.string.beacon_storage_error, beaconHomeLabel(BeaconState.StorageWarning(status.copy(storageHealthy = false))))
    }
    @Test fun recordingAndSyncKeepTheirRealStates() {
        assertEquals(R.string.rich_beacon_recording, beaconHomeLabel(BeaconState.Recording(status)))
        assertEquals(R.string.beacon_online_syncing, beaconHomeLabel(BeaconState.Syncing(status)))
        assertEquals(R.string.beacon_online_ready, beaconHomeLabel(BeaconState.Connected(status)))
    }
    @Test fun offlineDiscoveryAndErrorsStayExplicit() {
        assertEquals(R.string.beacon_offline, beaconHomeLabel(BeaconState.BeaconOffline(device)))
        assertEquals(R.string.beacon_searching, beaconHomeLabel(BeaconState.DiscoveringLan(device)))
        assertEquals(R.string.editorial_beacon_issue, beaconHomeLabel(BeaconState.Error(BeaconErrorCode.SYNC_FAILED)))
    }
    private fun client(rx: Boolean = true, tx: Boolean = true) = RouterClientInfo(
        "Synthetic node", RouterClientRole.REPEATER, RouterClientInterface.WIFI_5_GHZ, "", emptyList(), "", null, null, 0, 0, 10, true, rx, tx, 2)
    @Test fun absentCountersAreNotInventedAndZeroIsPreserved() {
        assertEquals(listOf(R.string.router_received to 0L, R.string.router_sent to 10L), RouterClientPresentation.counters(client()))
        assertEquals(listOf(R.string.router_sent to 10L), RouterClientPresentation.counters(client(rx = false)))
        assertTrue(RouterClientPresentation.counters(client(rx = false, tx = false)).isEmpty())
    }
    @Test fun unknownRoleIsNotInventedAsMesh() {
        assertEquals(R.string.router_role_unknown, RouterClientPresentation.roleLabel(RouterClientRole.fromWire(99)))
        assertEquals(R.string.rich_mesh_node, RouterClientPresentation.roleLabel(RouterClientRole.REPEATER))
        assertEquals(R.string.router_role_client, RouterClientPresentation.roleLabel(RouterClientRole.CLIENT))
    }
    @Test fun unknownInterfaceIsNotInventedAsFiveGhz() {
        assertEquals(R.string.rich_interface_unknown, RouterClientPresentation.interfaceLabel(RouterClientInterface.UNKNOWN))
        assertEquals(R.string.rich_interface_unknown, RouterClientPresentation.interfaceLabel(RouterClientInterface.fromWire(99)))
        assertEquals(R.string.rich_wifi_24, RouterClientPresentation.interfaceLabel(RouterClientInterface.WIFI_24_GHZ))
    }
}
