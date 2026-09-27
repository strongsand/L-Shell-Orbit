// =========================================================================
// PROJETO: L-Shell Orbit (L-Shell Orbit Android)
// ARQUIVO: DishyGrpcClient.kt
// PACOTE: com.hurricane.lshell.core.network.grpc
// VERSÃO: v1.2 (2026-09-20)
//
// CHANGELOG:
// - [v1.2 | 2026-09-20]: Padronização de imports para 'com.hurricane.lshell.*'
//   e 'com.hurricane.lshell.proto.*'.
// - [v1.0 | 2026-09-20]: Implementação gRPC OkHttp com endpoint 192.168.100.1:9200.
// =========================================================================

package com.hurricane.lshell.core.network.grpc

import com.hurricane.lshell.core.model.DishAlerts
import com.hurricane.lshell.core.model.DishDeviceInfo
import com.hurricane.lshell.core.model.DishObstruction
import com.hurricane.lshell.core.model.DishState
import com.hurricane.lshell.core.model.DishySnapshot
import com.hurricane.lshell.core.model.LatencyStats
import com.hurricane.lshell.core.model.ThroughputStats
import com.hurricane.lshell.proto.DeviceGrpcKt
import com.hurricane.lshell.proto.DishGetStatusRequest
import com.hurricane.lshell.proto.DishGetStatusResponse
import com.hurricane.lshell.proto.DishGetHistoryRequest
import com.hurricane.lshell.proto.DishGetObstructionMapRequest
import com.hurricane.lshell.proto.GetDiagnosticsRequest
import com.hurricane.lshell.proto.Request
import com.hurricane.lshell.proto.RebootRequest
import com.hurricane.lshell.proto.DishStowRequest
import com.hurricane.lshell.proto.DishInhibitGpsRequest
import io.grpc.ManagedChannel
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import io.grpc.okhttp.OkHttpChannelBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DishyGrpcClient(
    private val host: String = DEFAULT_DISHY_HOST,
    private val port: Int = DEFAULT_DISHY_PORT,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) : Closeable {

    enum class Control { REBOOT, STOW, UNSTOW, INHIBIT_GPS }

    suspend fun control(action: Control): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.newBuilder().apply {
                when (action) {
                    Control.REBOOT -> setReboot(RebootRequest.getDefaultInstance())
                    Control.STOW -> setDishStow(DishStowRequest.getDefaultInstance())
                    Control.UNSTOW -> setDishStow(DishStowRequest.newBuilder().setUnstow(true).build())
                    Control.INHIBIT_GPS -> setDishInhibitGps(
                        DishInhibitGpsRequest.newBuilder().setInhibitGps(true).build())
                }
            }.build()
            DeviceGrpcKt.DeviceCoroutineStub(getOrCreateChannel())
                .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS).handle(request)
            Result.success(Unit)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }

    companion object {
        const val DEFAULT_DISHY_HOST = "192.168.100.1"
        const val DEFAULT_DISHY_PORT = 9200
        const val DEFAULT_TIMEOUT_MS = 3500L
    }

    private val channelRef = AtomicReference<ManagedChannel?>(null)

    data class AlignmentReading(
        val azimuth: Float,
        val desiredAzimuth: Float,
        val elevation: Float,
        val desiredElevation: Float
    )

    data class HistoryReading(
        val current: Long,
        val downloadMbps: List<Float>,
        val uploadMbps: List<Float>,
        val latencyMs: List<Float>,
        val dropPercent: List<Float>,
        /** One-second input-power samples reported by DishGetHistory.power_in, in watts. */
        val powerWatts: List<Float>
    )

    data class ObstructionMapReading(
        val rows: Int,
        val columns: Int,
        /** Fraction of usable sky per cell. Negative values have not been surveyed yet. */
        val usableFractions: List<Float>,
        val maxThetaDegrees: Float?,
        val referenceFrame: ReferenceFrame
    ) {
        enum class ReferenceFrame { UNKNOWN, EARTH, USER_TERMINAL }
    }

    private fun getOrCreateChannel(): ManagedChannel {
        channelRef.get()?.let { existing ->
            if (!existing.isShutdown && !existing.isTerminated) {
                return existing
            }
        }
        val newChannel = OkHttpChannelBuilder.forAddress(host, port)
            .usePlaintext()
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(5, TimeUnit.SECONDS)
            .build()
        channelRef.set(newChannel)
        return newChannel
    }

    suspend fun fetchStatus(): Result<DishySnapshot> = withContext(Dispatchers.IO) {
        try {
            val channel = getOrCreateChannel()
            val stub = DeviceGrpcKt.DeviceCoroutineStub(channel)
                .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)

            val request = Request.newBuilder()
                .setGetStatus(DishGetStatusRequest.getDefaultInstance())
                .build()

            val response = stub.handle(request)

            if (!response.hasDishGetStatus()) {
                return@withContext Result.failure(
                    IllegalStateException("A resposta da Dishy não contém o payload 'dish_get_status'.")
                )
            }

            val statusProto = response.dishGetStatus
            val snapshot = DishyTelemetryMapper.map(statusProto)
            Result.success(snapshot)
        } catch (e: StatusRuntimeException) {
            Result.failure(Exception("Erro gRPC Dishy [${e.status.code}]: ${e.status.description ?: e.message}", e))
        } catch (e: StatusException) {
            Result.failure(Exception("Erro gRPC Dishy [${e.status.code}]: ${e.status.description ?: e.message}", e))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception("Falha de conexão com a Starlink em $host:$port: ${e.message}", e))
        }
    }

    suspend fun fetchAlignment(): Result<AlignmentReading> = withContext(Dispatchers.IO) {
        try {
            val response = DeviceGrpcKt.DeviceCoroutineStub(getOrCreateChannel())
                .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                .handle(Request.newBuilder().setGetDiagnostics(GetDiagnosticsRequest.getDefaultInstance()).build())
            check(response.hasDishGetDiagnostics() && response.dishGetDiagnostics.hasAlignmentStats()) {
                "Esta antena não forneceu os dados de alinhamento."
            }
            val stats = response.dishGetDiagnostics.alignmentStats
            check(listOf(stats.boresightAzimuthDeg, stats.desiredBoresightAzimuthDeg,
                stats.boresightElevationDeg, stats.desiredBoresightElevationDeg).all { it.isFinite() }) {
                "A antena retornou ângulos inválidos."
            }
            Result.success(AlignmentReading(stats.boresightAzimuthDeg, stats.desiredBoresightAzimuthDeg,
                stats.boresightElevationDeg, stats.desiredBoresightElevationDeg))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchHistory(): Result<HistoryReading> = withContext(Dispatchers.IO) {
        try {
            val response = DeviceGrpcKt.DeviceCoroutineStub(getOrCreateChannel())
                .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                .handle(Request.newBuilder().setGetHistory(DishGetHistoryRequest.getDefaultInstance()).build())
            check(response.hasDishGetHistory()) { "A antena não retornou o histórico." }
            val history = response.dishGetHistory
            Result.success(HistoryReading(
                current = history.current,
                downloadMbps = history.downlinkThroughputBpsList.map { it / 1_000_000f },
                uploadMbps = history.uplinkThroughputBpsList.map { it / 1_000_000f },
                latencyMs = history.popPingLatencyMsList,
                dropPercent = history.popPingDropRateList.map { it * 100f },
                powerWatts = history.powerInList
            ))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }

    suspend fun fetchObstructionMap(): Result<ObstructionMapReading> = withContext(Dispatchers.IO) {
        try {
            val response = DeviceGrpcKt.DeviceCoroutineStub(getOrCreateChannel())
                .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                .handle(Request.newBuilder()
                    .setDishGetObstructionMap(DishGetObstructionMapRequest.getDefaultInstance())
                    .build())
            check(response.hasDishGetObstructionMap()) { "A antena não retornou o mapa de obstrução." }
            val map = response.dishGetObstructionMap
            val rows = map.numRows.toInt()
            val columns = map.numCols.toInt()
            val expectedCells = rows.toLong() * columns.toLong()
            check(rows > 0 && columns > 0 && expectedCells <= 262_144L) {
                "A antena retornou dimensões inválidas para o mapa."
            }
            check(map.snrCount.toLong() >= expectedCells) {
                "O mapa de obstrução chegou incompleto."
            }
            val frame = when (map.mapReferenceFrameValue) {
                1 -> ObstructionMapReading.ReferenceFrame.EARTH
                2 -> ObstructionMapReading.ReferenceFrame.USER_TERMINAL
                else -> ObstructionMapReading.ReferenceFrame.UNKNOWN
            }
            Result.success(ObstructionMapReading(
                rows = rows,
                columns = columns,
                usableFractions = map.snrList.take(expectedCells.toInt()).map {
                    if (it.isFinite()) it else -1f
                },
                maxThetaDegrees = map.maxThetaDeg.takeIf { it.isFinite() && it > 0f },
                referenceFrame = frame
            ))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }

    override fun close() {
        // close() is also called from Compose disposal and Activity.onDestroy on the main
        // thread. Cancel in-flight calls immediately instead of blocking that thread.
        channelRef.getAndSet(null)?.shutdownNow()
    }
}

