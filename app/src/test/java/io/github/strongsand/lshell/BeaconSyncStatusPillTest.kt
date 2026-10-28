package io.github.strongsand.lshell

import io.github.strongsand.lshell.beacon.*
import org.junit.Assert.*
import org.junit.Test

class BeaconSyncStatusPillTest {
    private val device = BeaconDevice("synthetic", "Beacon", "example.local", 80)
    private val runtime = BeaconRuntimeStatus(device, lastSyncAtMillis = 1234L, pendingRecords = 0)
    private val complete = BeaconUiEvent(1234L, BeaconUiEventKind.SYNC_COMPLETE, 0)

    @Test fun activeSyncTakesPrecedenceOverPreviousSuccess() {
        assertEquals(BeaconSyncPillState.SYNCING,
            beaconSyncPillState(BeaconState.Syncing(runtime), listOf(complete)))
    }

    @Test fun onlyExplicitSyncFailureGetsSyncErrorStatus() {
        assertEquals(BeaconSyncPillState.ERROR,
            beaconSyncPillState(BeaconState.Error(BeaconErrorCode.SYNC_FAILED), listOf(complete)))
        assertNull(beaconSyncPillState(BeaconState.Error(BeaconErrorCode.PAIRING_FAILED), listOf(complete)))
    }

    @Test fun pageAcknowledgementAndTimestampAreNotWholeSyncSuccess() {
        assertNull(beaconSyncPillState(BeaconState.Connected(runtime), emptyList()))
        assertNull(beaconSyncPillState(BeaconState.Connected(runtime),
            listOf(BeaconUiEvent(1234L, BeaconUiEventKind.ACK, 1))))
    }

    @Test fun explicitCompletedSyncIncludingZeroImportedRecordsCanShowSuccess() {
        assertEquals(BeaconSyncPillState.SYNCED,
            beaconSyncPillState(BeaconState.Recording(runtime), listOf(complete)))
    }

    @Test fun NewPendingDataOfflineAndNewErrorDoNotKeepOldSuccess() {
        assertNull(beaconSyncPillState(BeaconState.Recording(runtime.copy(pendingRecords = 4)), listOf(complete)))
        assertNull(beaconSyncPillState(BeaconState.BeaconOffline(device), listOf(complete)))
        assertNull(beaconSyncPillState(BeaconState.NotConfigured, listOf(complete)))
        assertNull(beaconSyncPillState(BeaconState.Connected(runtime),
            listOf(complete, BeaconUiEvent(1235L, BeaconUiEventKind.ERROR))))
    }
}
