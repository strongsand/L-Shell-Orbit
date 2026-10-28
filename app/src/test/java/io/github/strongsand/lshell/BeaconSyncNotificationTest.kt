package io.github.strongsand.lshell

import io.github.strongsand.lshell.beacon.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class BeaconSyncNotificationTest {
    private val runtime = BeaconRuntimeStatus(BeaconDevice("synthetic", "Beacon", "example.local", 80))

    @Test fun progressUsesAcknowledgedRecordsRatherThanPagesOrAnEstimatedPercentage() {
        val state = BeaconState.Syncing(runtime, progress = .99f, processedRecords = 500,
            totalRecords = 2500, acknowledgedPages = 1)
        assertEquals(10_000 to 2000, BeaconSyncNotificationPolicy.progress(state))
        assertEquals(10_000 to 0, BeaconSyncNotificationPolicy.progress(state.copy(processedRecords = 0)))
        assertEquals(10_000 to 10_000, BeaconSyncNotificationPolicy.progress(state.copy(processedRecords = 2500)))
    }

    @Test fun unknownOrEmptyTotalsAndNonSyncStatesNeverInventProgress() {
        assertNull(BeaconSyncNotificationPolicy.progress(BeaconState.Syncing(runtime)))
        assertNull(BeaconSyncNotificationPolicy.progress(BeaconState.Syncing(runtime, totalRecords = -1)))
        assertNull(BeaconSyncNotificationPolicy.progress(BeaconState.Syncing(runtime, processedRecords = -1, totalRecords = 4)))
        assertNull(BeaconSyncNotificationPolicy.progress(BeaconState.Connected(runtime)))
        assertNull(BeaconSyncNotificationPolicy.progress(BeaconState.Error(BeaconErrorCode.SYNC_FAILED)))
        assertNull(BeaconSyncNotificationPolicy.progress(null))
    }

    @Test fun longCountersDoNotOverflowAndRetriesCanResetRealProgress() {
        val sync = BeaconState.Syncing(runtime, processedRecords = Long.MAX_VALUE / 2, totalRecords = Long.MAX_VALUE)
        assertEquals(10_000 to 5000, BeaconSyncNotificationPolicy.progress(sync))
        assertEquals(10_000 to 0, BeaconSyncNotificationPolicy.progress(sync.copy(processedRecords = 0)))
    }

    @Test fun promotionRequiresApiCapabilityAndActiveUndismissedActivity() {
        assertTrue(BeaconSyncNotificationPolicy.requestsPromotion(36, true, true, false))
        assertTrue(BeaconSyncNotificationPolicy.requestsPromotion(37, true, true, false))
        assertFalse(BeaconSyncNotificationPolicy.requestsPromotion(35, true, true, false))
        assertFalse(BeaconSyncNotificationPolicy.requestsPromotion(36, false, true, false))
        assertFalse(BeaconSyncNotificationPolicy.requestsPromotion(36, true, false, false))
        assertFalse(BeaconSyncNotificationPolicy.requestsPromotion(36, true, true, true))
    }

    private val main = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")
    private fun elements(path: String): List<Element> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val nodes = factory.newDocumentBuilder().parse(File(main, path)).getElementsByTagName("*")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test fun allThreeLocalesHaveShortChipAndCompleteAccessibleStatusText() {
        val locales = listOf("values", "values-en", "values-es").map { locale ->
            elements("res/$locale/beacon_sync_notification_strings.xml").filter { it.tagName == "string" }
                .associate { it.getAttribute("name") to it.textContent }
        }
        for (strings in locales) {
            assertEquals(locales.first().keys, strings.keys)
            assertTrue(strings.getValue("beacon_sync_notification_chip").length in 1..6)
            for (name in listOf("active", "success", "failure")) {
                assertTrue(strings.getValue("beacon_sync_notification_$name").contains("Beacon"))
            }
            assertTrue(strings.getValue("beacon_sync_notification_progress").contains("%1\$s"))
            assertTrue(strings.getValue("beacon_sync_notification_progress").contains("%2\$s"))
        }
    }

    @Test fun smallIconUsesOnlyWhiteVectorPathsForSystemTinting() {
        val icon = elements("res/drawable/ic_beacon_sync_notification.xml")
        assertEquals("vector", icon.first().tagName)
        val paths = icon.filter { it.tagName == "path" }
        assertTrue(paths.isNotEmpty())
        assertTrue(paths.all { it.getAttributeNS("http://schemas.android.com/apk/res/android", "fillColor") == "#FFFFFFFF" })
    }

    @Test fun manualServiceUsesExistingConnectedDeviceAndPromotionPermissions() {
        val manifest = elements("AndroidManifest.xml")
        val android = "http://schemas.android.com/apk/res/android"
        val services = manifest.filter { it.tagName == "service" &&
            it.getAttributeNS(android, "name") == "io.github.strongsand.lshell.BeaconSyncService" }
        assertEquals(1, services.size)
        assertEquals("false", services.single().getAttributeNS(android, "exported"))
        assertEquals("false", services.single().getAttributeNS(android, "stopWithTask"))
        assertEquals("connectedDevice", services.single().getAttributeNS(android, "foregroundServiceType"))
        val permissions = manifest.filter { it.tagName == "uses-permission" }.map { it.getAttributeNS(android, "name") }
        for (permission in listOf("FOREGROUND_SERVICE", "FOREGROUND_SERVICE_CONNECTED_DEVICE",
            "POST_PROMOTED_NOTIFICATIONS", "CHANGE_NETWORK_STATE")) {
            assertEquals(1, permissions.count { it == "android.permission.$permission" })
        }
    }
}
