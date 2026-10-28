package io.github.strongsand.lshell.core.network.grpc

import io.github.strongsand.lshell.proto.DeviceGrpcKt
import io.github.strongsand.lshell.proto.DishGetStatusRequest
import io.github.strongsand.lshell.proto.GetDiagnosticsRequest
import io.github.strongsand.lshell.proto.Request
import io.github.strongsand.lshell.proto.RebootRequest
import io.github.strongsand.lshell.proto.WifiClient
import io.github.strongsand.lshell.proto.WifiGetClientsRequest
import io.grpc.okhttp.OkHttpChannelBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

data class RouterClientInfo(
    val name: String,
    val role: RouterClientRole,
    val interfaceKind: RouterClientInterface,
    val ipv4: String,
    val ipv6: List<String>,
    val mac: String,
    val signalDbm: Float?,
    val snrDb: Float?,
    val connectedSeconds: Long,
    val receivedBytes: Long,
    val sentBytes: Long,
    val active: Boolean,
    val receivedBytesAvailable: Boolean = true,
    val sentBytesAvailable: Boolean = true,
    /** Wire value, without inferring which access point serves this client. */
    val reportedRole: Int = 0,
    val reportedInterface: Int = 0
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
    val bootReason: RouterBootCause,
    val bootCounts: List<Pair<RouterBootCause, Int>>,
    val wanIpv4: String,
    val wanIpv6: List<String>,
    val pingMs: Float?,
    val dishPingMs: Float?,
    val popPingMs: Float?,
    val alerts: List<RouterAlert>,
    val networks: List<RouterNetworkInfo>,
    val clients: List<RouterClientInfo>,
    val poeInput: RouterPoeReading? = null
) { val hasPendingUpdate: Boolean get() = RouterAlert.INSTALL_PENDING in alerts }

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

    suspend fun fetch(includeDetails: Boolean = true): Result<RouterInfo> = withContext(Dispatchers.IO) {
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
            // Timestamp the status receipt, not the later clients/diagnostics requests.
            val poeInput = if (io.github.strongsand.lshell.CandidatePublicationPolicy.POE_INPUT)
                status.poeInputReading(android.os.SystemClock.elapsedRealtime()) else null
            // `wifi_get_status.clients` can be only a transient subset on some router builds.
            // Always ask the dedicated client endpoint too and merge both snapshots.
            val detailedClients = if (includeDetails) runCatching {
                stub.handle(Request.newBuilder().setWifiGetClients(WifiGetClientsRequest.getDefaultInstance()).build())
                    .wifiGetClients.clientsList
            }.getOrElse { if (it is CancellationException) throw it; emptyList() } else emptyList()
            val clients = if (includeDetails) status.clientsList + detailedClients else emptyList()
            val networks = if (includeDetails) runCatching {
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
            }.getOrElse { if (it is CancellationException) throw it; emptyList() } else emptyList()
            val info = status.deviceInfo
            val alerts = status.alerts
            Result.success(RouterInfo(
                id = info.id,
                hardware = info.hardwareVersion,
                software = info.softwareVersion,
                manufacturedSoftware = info.manufacturedVersion,
                uptimeSeconds = status.deviceState.uptimeS,
                bootCount = info.bootcount,
                bootReason = RouterBootCause(info.boot.lastReason),
                bootCounts = info.boot.countByReasonList.map { RouterBootCause(it.key) to it.value },
                wanIpv4 = status.ipv4WanAddress,
                wanIpv6 = status.ipv6WanAddressesList,
                pingMs = status.pingLatencyMs.takeIf { it.isFinite() && it > 0f },
                dishPingMs = status.dishPingLatencyMs.takeIf { it.isFinite() && it > 0f },
                popPingMs = status.popPingLatencyMs.takeIf { it.isFinite() && it > 0f },
                alerts = alerts.toRouterAlerts(),
                networks = networks,
                poeInput = poeInput,
                clients = clients.map(WifiClient::toRouterClientInfo)
                    .groupBy { it.mac.ifBlank { it.ipv4.ifBlank { it.name } } }
                    .map { (_, versions) ->
                        val best = versions.maxWithOrNull(compareBy<RouterClientInfo> { if (it.active) 1 else 0 }
                            .thenBy { it.receivedBytes + it.sentBytes })!!
                        best.copy(
                            receivedBytes = versions.maxOf { it.receivedBytes },
                            sentBytes = versions.maxOf { it.sentBytes },
                            active = versions.any { it.active },
                            receivedBytesAvailable = versions.any { it.receivedBytesAvailable },
                            sentBytesAvailable = versions.any { it.sentBytesAvailable }
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

internal fun WifiClient.toRouterClientInfo() = RouterClientInfo(
    name = givenName.ifBlank { name },
    role = RouterClientRole.fromWire(role),
    interfaceKind = RouterClientInterface.fromWire(iface),
    ipv4 = ipAddress,
    ipv6 = ipv6AddressesList,
    mac = macAddress,
    signalDbm = signalStrength.takeIf { it.isFinite() && it != 0f },
    snrDb = snr.takeIf { it.isFinite() && it != 0f },
    connectedSeconds = associatedTimeS.toLong().coerceAtLeast(0L),
    receivedBytes = rxStats.bytes.coerceAtLeast(0L),
    sentBytes = txStats.bytes.coerceAtLeast(0L),
    active = active,
    receivedBytesAvailable = hasRxStats() && rxStats.bytes >= 0,
    sentBytesAvailable = hasTxStats() && txStats.bytes >= 0,
    reportedRole = role,
    reportedInterface = iface
)
