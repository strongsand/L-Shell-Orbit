// =========================================================================
// PROJETO: L-Shell Orbit (L-Shell Orbit Android)
// ARQUIVO: DishyStatusModels.kt
// PACOTE: com.hurricane.lshell.core.model
// VERSÃO: v1.2 (2026-09-20)
//
// CHANGELOG:
// - [v1.2 | 2026-09-20]: Padronização completa sob 'com.hurricane.lshell.core.model'.
// - [v1.0 | 2026-09-20]: Modelos imutáveis para estado, alertas, latência e tráfego.
// =========================================================================

package com.hurricane.lshell.core.model

import java.util.Locale

enum class DishState(val displayName: String, val isOperational: Boolean) {
    UNKNOWN("Desconhecido", false),
    CONNECTED("Conectado", true),
    SEARCHING("Buscando Satélites", false),
    BOOTING("Inicializando", false),
    STOWED("Recolhida (Stowed)", false),
    THERMAL_SHUTDOWN("Proteção Térmica", false),
    NO_SATS("Sem Satélites no FOV", false),
    OBSTRUCTED("Visada Obstruída", false),
    NO_DOWNLINK("Sem enlace de descida", false),
    NO_PINGS("Sem resposta de rede", false),
    ACTUATOR_ACTIVITY("Ajustando antena", false),
    CABLE_TEST("Testando cabo", false),
    SLEEPING("Em repouso", false),
    INHIBIT_RF("Rádio desativado", false),
    UNREACHABLE("Inalcançável (Fora da Rede)", false)
}

data class DishAlerts(
    val motorsStuck: Boolean = false,
    val thermalThrottle: Boolean = false,
    val thermalShutdown: Boolean = false,
    val mastNotNearVertical: Boolean = false,
    val unexpectedLocation: Boolean = false,
    val slowEthernetSpeeds: Boolean = false,
    val installPending: Boolean = false,
    val isHeating: Boolean = false,
    val powerSaveIdle: Boolean = false,
    val powerSupplyThermalThrottle: Boolean = false,
    val noEthernetLink: Boolean = false
) {
    val hasCriticalAlerts: Boolean
        get() = motorsStuck || thermalThrottle || thermalShutdown || mastNotNearVertical || powerSupplyThermalThrottle || noEthernetLink

    fun getActiveAlertsList(): List<String> {
        val list = mutableListOf<String>()
        if (thermalShutdown) list.add("Desligamento de Emergência por Alta Temperatura")
        if (thermalThrottle) list.add("Estrangulamento Térmico Ativo (Thermal Throttle)")
        if (motorsStuck) list.add("Motores Travados ou Bloqueados")
        if (mastNotNearVertical) list.add("Mastro Inclinado (Não Vertical)")
        if (slowEthernetSpeeds) list.add("Cabo de Rede Limitado a 10/100 Mbps")
        if (powerSupplyThermalThrottle) list.add("Fonte com limitação térmica")
        if (noEthernetLink) list.add("Sem conexão Ethernet")
        if (powerSaveIdle) list.add("Economia de energia ativa")
        if (installPending) list.add("Instalação pendente")
        if (unexpectedLocation) list.add("Localização Inesperada da Célula")
        if (isHeating) list.add("Aquecimento para Derretimento de Gelo Ativo")
        return list
    }
}

data class DishObstruction(
    val currentlyObstructed: Boolean = false,
    val fractionObstructed: Float = 0.0f,
    val timeObstructedSeconds: Float = 0.0f,
    val validSeconds: Float = 0.0f,
    val avgProlongedIntervalSeconds: Float = 0.0f,
    val wedgeFractions: List<Float> = emptyList()
) {
    val obstructionPercentage: Float
        get() = (fractionObstructed * 100f).coerceIn(0f, 100f)

    val formattedPercentage: String
        get() = String.format(Locale.getDefault(), "%.1f%%", obstructionPercentage)
}

data class ThroughputStats(
    val downlinkBps: Float = 0.0f,
    val uplinkBps: Float = 0.0f
) {
    val downlinkMbps: Float
        get() = (downlinkBps / 1_000_000f).coerceAtLeast(0f)

    val uplinkMbps: Float
        get() = (uplinkBps / 1_000_000f).coerceAtLeast(0f)

    val formattedDownlink: String
        get() = String.format(Locale.getDefault(), "%.1f Mbps", downlinkMbps)

    val formattedUplink: String
        get() = String.format(Locale.getDefault(), "%.1f Mbps", uplinkMbps)
}

data class LatencyStats(
    val popPingLatencyMs: Float = 0.0f,
    val popPingDropRate: Float = 0.0f,
    val isSnrAboveNoiseFloor: Boolean? = null
) {
    val formattedLatency: String
        get() = if (popPingLatencyMs <= 0f) "-- ms" else String.format(Locale.getDefault(), "%.0f ms", popPingLatencyMs)

    val formattedDropRate: String
        get() = String.format(Locale.getDefault(), "%.1f%%", (popPingDropRate * 100f).coerceIn(0f, 100f))

    val formattedSignal: String
        get() = when (isSnrAboveNoiseFloor) {
            true -> "Adequado"
            false -> "Baixo"
            null -> "Não informado"
        }

    val signalDescription: String
        get() = when (isSnrAboveNoiseFloor) {
            true -> "Acima do nível de ruído"
            false -> "Não está acima do ruído"
            null -> "Aguardando leitura"
        }
}

data class DishDeviceInfo(
    val id: String = "",
    val hardwareVersion: String = "",
    val softwareVersion: String = "",
    val countryCode: String = "",
    val uptimeSeconds: Long = 0L,
    val boresightAzimuthDeg: Float = 0.0f,
    val boresightElevationDeg: Float = 0.0f,
    val ethSpeedMbps: Int? = null
) {
    val formattedEthernetSpeed: String
        get() = ethSpeedMbps?.let { "$it Mbps" } ?: "Não informado"

    val formattedUptime: String
        get() {
            if (uptimeSeconds <= 0) return "--"
            val days = uptimeSeconds / 86400
            val hours = (uptimeSeconds % 86400) / 3600
            val minutes = (uptimeSeconds % 3600) / 60
            return if (days > 0) "${days}d ${hours}h ${minutes}m" else "${hours}h ${minutes}m"
        }
}

data class DishySnapshot(
    val timestamp: Long = System.currentTimeMillis(),
    val state: DishState = DishState.UNKNOWN,
    val alerts: DishAlerts = DishAlerts(),
    val obstruction: DishObstruction = DishObstruction(),
    val throughput: ThroughputStats = ThroughputStats(),
    val latency: LatencyStats = LatencyStats(),
    val deviceInfo: DishDeviceInfo = DishDeviceInfo(),
    val isOnline: Boolean = false,
    val isFromCache: Boolean = false,
    val errorMessage: String? = null
) {
    companion object {
        fun offlineFallback(cached: DishySnapshot? = null, reason: String): DishySnapshot {
            return cached?.copy(
                isOnline = false,
                isFromCache = true,
                errorMessage = reason
            ) ?: DishySnapshot(
                state = DishState.UNREACHABLE,
                isOnline = false,
                isFromCache = false,
                errorMessage = reason
            )
        }
    }
}

