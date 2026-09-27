package com.hurricane.lshell.core.network.grpc

import com.hurricane.lshell.proto.DeviceGrpcKt
import com.hurricane.lshell.proto.DishGetStatusRequest
import com.hurricane.lshell.proto.GetDiagnosticsRequest
import com.hurricane.lshell.proto.Request
import com.hurricane.lshell.proto.RebootRequest
import com.hurricane.lshell.proto.WifiClient
import com.hurricane.lshell.proto.WifiGetClientsRequest
import io.grpc.okhttp.OkHttpChannelBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

data class RouterClientInfo(
    val name: String,
    val role: String,
    val interfaceName: String,
    val ipv4: String,
    val ipv6: List<String>,
    val mac: String,
    val signalDbm: Float?,
    val snrDb: Float?,
    val connectedSeconds: Long,
    val receivedBytes: Long,
    val sentBytes: Long,
    val active: Boolean
)

data class RouterNetworkInfo(
    val domain: String,
    val ipv4: String,
    val ipv6: List<String>,
    val ethernetClients: Int,
    val wifi24Clients: Int,
    val wifi5Clients: Int
)

data class RouterInfo(
    val id: String,
    val hardware: String,
    val software: String,
    val manufacturedSoftware: String,
    val uptimeSeconds: Long,
    val bootCount: Int,
    val bootReason: String,
    val bootCounts: List<Pair<String, Int>>,
    val wanIpv4: String,
    val wanIpv6: List<String>,
    val pingMs: Float?,
    val dishPingMs: Float?,
    val popPingMs: Float?,
    val alerts: List<String>,
    val networks: List<RouterNetworkInfo>,
    val clients: List<RouterClientInfo>
)

class RouterGrpcClient(
    private val host: String = "192.168.1.1",
    private val port: Int = 9000
) {
    suspend fun reboot(): Result<Unit> = withContext(Dispatchers.IO) {
        val channel = OkHttpChannelBuilder.forAddress(host, port).usePlaintext().build()
        try {
            DeviceGrpcKt.DeviceCoroutineStub(channel).withDeadlineAfter(4, TimeUnit.SECONDS)
                .handle(Request.newBuilder().setReboot(RebootRequest.getDefaultInstance()).build())
            Result.success(Unit)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
        finally { channel.shutdownNow() }
    }

    suspend fun fetch(): Result<RouterInfo> = withContext(Dispatchers.IO) {
        val channel = OkHttpChannelBuilder.forAddress(host, port).usePlaintext().build()
        try {
            val stub = DeviceGrpcKt.DeviceCoroutineStub(channel).withDeadlineAfter(4, TimeUnit.SECONDS)
            val response = stub.handle(
                Request.newBuilder().setGetStatus(DishGetStatusRequest.getDefaultInstance()).build()
            )
            if (!response.hasWifiGetStatus()) {
                return@withContext Result.failure(IllegalStateException("O roteador não retornou dados de estado."))
            }
            val status = response.wifiGetStatus
            // `wifi_get_status.clients` can be only a transient subset on some router builds.
            // Always ask the dedicated client endpoint too and merge both snapshots.
            val detailedClients = runCatching {
                stub.handle(Request.newBuilder().setWifiGetClients(WifiGetClientsRequest.getDefaultInstance()).build())
                    .wifiGetClients.clientsList
            }.getOrDefault(emptyList())
            val clients = status.clientsList + detailedClients
            val networks = runCatching {
                stub.handle(Request.newBuilder().setGetDiagnostics(GetDiagnosticsRequest.getDefaultInstance()).build())
                    .wifiGetDiagnostics.networksList.map { network ->
                        RouterNetworkInfo(
                            domain = network.domain,
                            ipv4 = network.ipv4,
                            ipv6 = network.ipv6List,
                            ethernetClients = network.clientsEthernet,
                            wifi24Clients = network.clients2Ghz,
                            wifi5Clients = network.clients5Ghz
                        )
                    }
            }.getOrDefault(emptyList())
            val info = status.deviceInfo
            val alerts = status.alerts
            Result.success(RouterInfo(
                id = info.id,
                hardware = info.hardwareVersion,
                software = info.softwareVersion,
                manufacturedSoftware = info.manufacturedVersion,
                uptimeSeconds = status.deviceState.uptimeS,
                bootCount = info.bootcount,
                bootReason = when (info.boot.lastReason) {
                    1 -> "Reinício após longo período"
                    2 -> "Energia desligada/ligada"
                    3 -> "Comando"
                    4 -> "Atualização de software"
                    5 -> "Atualização de configuração"
                    else -> "Não informado"
                },
                bootCounts = info.boot.countByReasonList.map { entry ->
                    val label = when (entry.key) {
                        0 -> "Desconhecido"
                        1 -> "Esquecido"
                        2 -> "Energia"
                        3 -> "Comando"
                        4 -> "Software"
                        5 -> "Configuração"
                        else -> "Motivo ${entry.key}"
                    }
                    label to entry.value
                },
                wanIpv4 = status.ipv4WanAddress,
                wanIpv6 = status.ipv6WanAddressesList,
                pingMs = status.pingLatencyMs.takeIf { it.isFinite() && it > 0f },
                dishPingMs = status.dishPingLatencyMs.takeIf { it.isFinite() && it > 0f },
                popPingMs = status.popPingLatencyMs.takeIf { it.isFinite() && it > 0f },
                alerts = buildList {
                    if (alerts.thermalThrottle) add("Temperatura alta")
                    if (alerts.installPending) add("Atualização pendente")
                    if (alerts.lanEthSlowLink10 || alerts.lanEthSlowLink100) add("Ethernet lenta")
                    if (alerts.wanEthPoorConnection) add("Conexão com a antena degradada")
                },
                networks = networks,
                clients = clients.map(WifiClient::toRouterClientInfo)
                    .groupBy { it.mac.ifBlank { it.ipv4.ifBlank { it.name } } }
                    .map { (_, versions) ->
                        val best = versions.maxWithOrNull(compareBy<RouterClientInfo> { if (it.active) 1 else 0 }
                            .thenBy { it.receivedBytes + it.sentBytes })!!
                        best.copy(
                            receivedBytes = versions.maxOf { it.receivedBytes },
                            sentBytes = versions.maxOf { it.sentBytes },
                            active = versions.any { it.active }
                        )
                    }
                    .sortedWith(compareByDescending<RouterClientInfo> { it.active }.thenBy { it.name })
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception("Não foi possível consultar o roteador em $host:$port: ${e.message}", e))
        } finally {
            channel.shutdownNow()
        }
    }
}

private fun WifiClient.toRouterClientInfo() = RouterClientInfo(
    name = givenName.ifBlank { name.ifBlank { "Dispositivo sem nome" } },
    role = when (role) { 1 -> "Cliente"; 2 -> "Repetidor"; 3 -> "Controlador"; else -> "Dispositivo" },
    interfaceName = when (iface) { 1 -> "Ethernet"; 2 -> "Wi-Fi 2,4 GHz"; 3 -> "Wi-Fi 5 GHz"; 4 -> "Wi-Fi 5 GHz alto"; else -> "Interface não informada" },
    ipv4 = ipAddress,
    ipv6 = ipv6AddressesList,
    mac = macAddress,
    signalDbm = signalStrength.takeIf { it.isFinite() && it != 0f },
    snrDb = snr.takeIf { it.isFinite() && it != 0f },
    connectedSeconds = associatedTimeS.toLong().coerceAtLeast(0L),
    receivedBytes = rxStats.bytes.coerceAtLeast(0L),
    sentBytes = txStats.bytes.coerceAtLeast(0L),
    active = active
)
