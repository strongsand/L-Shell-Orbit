package io.github.strongsand.lshell.core.network.grpc

import io.github.strongsand.lshell.core.model.*
import io.github.strongsand.lshell.proto.DishGetStatusResponse

internal object DishyTelemetryMapper {
    fun map(proto: DishGetStatusResponse): DishySnapshot {
        // An absent outage denotes CONNECTED in the current dish protocol.
        // Reject empty payloads rather than showing a fabricated connection.
        require(proto.hasDeviceInfo() || proto.hasDeviceState()) { "Resposta da antena sem identificação ou estado." }
        val mappedState = if (!proto.hasOutage()) DishState.CONNECTED else when (proto.outage.cause) {
            io.github.strongsand.lshell.proto.DishOutage.Cause.BOOTING -> DishState.BOOTING
            io.github.strongsand.lshell.proto.DishOutage.Cause.STOWED -> DishState.STOWED
            io.github.strongsand.lshell.proto.DishOutage.Cause.THERMAL_SHUTDOWN -> DishState.THERMAL_SHUTDOWN
            io.github.strongsand.lshell.proto.DishOutage.Cause.NO_SCHEDULE,
            io.github.strongsand.lshell.proto.DishOutage.Cause.SKY_SEARCH -> DishState.SEARCHING
            io.github.strongsand.lshell.proto.DishOutage.Cause.NO_SATS -> DishState.NO_SATS
            io.github.strongsand.lshell.proto.DishOutage.Cause.OBSTRUCTED -> DishState.OBSTRUCTED
            io.github.strongsand.lshell.proto.DishOutage.Cause.NO_DOWNLINK -> DishState.NO_DOWNLINK
            io.github.strongsand.lshell.proto.DishOutage.Cause.NO_PINGS -> DishState.NO_PINGS
            io.github.strongsand.lshell.proto.DishOutage.Cause.ACTUATOR_ACTIVITY -> DishState.ACTUATOR_ACTIVITY
            io.github.strongsand.lshell.proto.DishOutage.Cause.CABLE_TEST -> DishState.CABLE_TEST
            io.github.strongsand.lshell.proto.DishOutage.Cause.SLEEPING -> DishState.SLEEPING
            io.github.strongsand.lshell.proto.DishOutage.Cause.INHIBIT_RF -> DishState.INHIBIT_RF
            else -> DishState.UNKNOWN
        }

        val protoAlerts = proto.alerts
        val alerts = DishAlerts(
            motorsStuck = protoAlerts.motorsStuck,
            thermalThrottle = protoAlerts.thermalThrottle,
            thermalShutdown = protoAlerts.thermalShutdown,
            mastNotNearVertical = protoAlerts.mastNotNearVertical,
            unexpectedLocation = protoAlerts.unexpectedLocation,
            slowEthernetSpeeds = protoAlerts.slowEthernetSpeeds || protoAlerts.slowEthernetSpeeds100,
            installPending = protoAlerts.installPending,
            isHeating = protoAlerts.isHeating,
            powerSaveIdle = protoAlerts.isPowerSaveIdle,
            powerSupplyThermalThrottle = protoAlerts.powerSupplyThermalThrottle,
            noEthernetLink = protoAlerts.noEthernetLink
        )

        val protoObs = proto.obstructionStats
        val obstruction = DishObstruction(
            currentlyObstructed = protoObs.currentlyObstructed,
            fractionObstructed = protoObs.fractionObstructed.finiteNonNegative(),
            timeObstructedSeconds = protoObs.timeObstructed.finiteNonNegative(),
            validSeconds = protoObs.validS.finiteNonNegative(),
            avgProlongedIntervalSeconds = protoObs.avgProlongedObstructionIntervalS.finiteNonNegative(),
            wedgeFractions = emptyList()
        )

        val throughput = ThroughputStats(
            downlinkBps = proto.downlinkThroughputBps.finiteNonNegative(),
            uplinkBps = proto.uplinkThroughputBps.finiteNonNegative()
        )

        val latency = LatencyStats(
            popPingLatencyMs = proto.popPingLatencyMs.finiteNonNegative(),
            popPingDropRate = proto.popPingDropRate.finiteNonNegative().coerceAtMost(1f),
            isSnrAboveNoiseFloor = proto.isSnrAboveNoiseFloor
        )

        val protoDev = proto.deviceInfo
        val deviceInfo = DishDeviceInfo(
            id = protoDev.id ?: "",
            hardwareVersion = protoDev.hardwareVersion ?: "",
            softwareVersion = protoDev.softwareVersion ?: "",
            countryCode = protoDev.countryCode ?: "",
            uptimeSeconds = (proto.deviceState?.uptimeS ?: 0L).coerceAtLeast(0L),
            boresightAzimuthDeg = proto.boresightAzimuthDeg.finiteOrZero(),
            boresightElevationDeg = proto.boresightElevationDeg.finiteOrZero(),
            ethSpeedMbps = proto.ethSpeedMbps.takeIf { it > 0 }
        )

        return DishySnapshot(
            timestamp = System.currentTimeMillis(),
            state = mappedState,
            alerts = alerts,
            obstruction = obstruction,
            throughput = throughput,
            latency = latency,
            deviceInfo = deviceInfo,
            isOnline = true,
            isFromCache = false,
            errorMessage = null
        )
    }

}

private fun Float.finiteOrZero(): Float = takeIf { it.isFinite() } ?: 0f
private fun Float.finiteNonNegative(): Float = takeIf { it.isFinite() && it >= 0f } ?: 0f
