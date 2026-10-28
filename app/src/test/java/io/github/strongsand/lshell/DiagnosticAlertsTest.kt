package io.github.strongsand.lshell

import io.github.strongsand.lshell.core.model.DishAlerts
import org.junit.Assert.*
import org.junit.Test

class DiagnosticAlertsTest {
    @Test fun thermalShutdownIsCriticalAndRecognizedWithoutTheWordThermal() {
        val alerts = DishAlerts(thermalShutdown = true)
        assertTrue(alerts.hasCriticalAlerts)
        assertEquals(HistoryAlert.THERMAL_SHUTDOWN, HistoryEventCodec.parse(alerts.getActiveAlertsList().single()).alert)
    }
    @Test fun thermalThrottleUsesItsBooleanFlag() {
        val alerts = DishAlerts(thermalThrottle = true)
        assertTrue(alerts.thermalThrottle); assertFalse(alerts.thermalShutdown)
        assertTrue(alerts.hasCriticalAlerts)
    }
    @Test fun powerSupplyThermalUsesItsOwnFlag() {
        val alerts = DishAlerts(powerSupplyThermalThrottle = true)
        assertTrue(alerts.powerSupplyThermalThrottle); assertTrue(alerts.hasCriticalAlerts)
        assertEquals(HistoryAlert.POWER_SUPPLY_THERMAL, HistoryEventCodec.parse(alerts.getActiveAlertsList().single()).alert)
    }
    @Test fun mastUsesItsBooleanFlag() {
        val alerts = DishAlerts(mastNotNearVertical = true)
        assertTrue(alerts.mastNotNearVertical); assertTrue(alerts.hasCriticalAlerts)
        assertEquals(HistoryAlert.MAST_NOT_VERTICAL, HistoryEventCodec.parse(alerts.getActiveAlertsList().single()).alert)
    }
    @Test fun noAlertsIsNotAWarning() {
        val alerts = DishAlerts()
        assertFalse(alerts.hasCriticalAlerts); assertTrue(alerts.getActiveAlertsList().isEmpty())
    }
}
