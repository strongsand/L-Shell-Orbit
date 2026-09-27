package com.hurricane.lshell

import com.hurricane.lshell.proto.DeviceGrpcKt
import com.hurricane.lshell.proto.GetSpeedtestStatusRequest
import com.hurricane.lshell.proto.Request
import com.hurricane.lshell.proto.SpeedTestRequest
import com.hurricane.lshell.proto.StartSpeedtestRequest
import io.grpc.okhttp.OkHttpChannelBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

data class SpeedTestResult(val downloadMbps: Float, val uploadMbps: Float, val latencyMs: Float? = null)

/** Tests run only after a tap. Firmware may not support either local RPC. */
object SpeedTestClient {
    suspend fun dish(): SpeedTestResult = withContext(Dispatchers.IO) {
        val channel = OkHttpChannelBuilder.forAddress("192.168.100.1", 9200).usePlaintext().build()
        try {
            val response = DeviceGrpcKt.DeviceCoroutineStub(channel)
                .withDeadlineAfter(45, TimeUnit.SECONDS)
                .handle(Request.newBuilder().setSpeedTest(SpeedTestRequest.getDefaultInstance()).build())
            check(response.hasSpeedTest()) { "A antena não oferece este teste nesta versão." }
            val test = response.speedTest
            SpeedTestResult(
                downloadMbps = (test.downloadMbps.takeIf { it > 0f }
                    ?: test.downloadBps / 1_000_000f).finiteNonNegative(),
                uploadMbps = (test.uploadMbps.takeIf { it > 0f }
                    ?: test.uploadBps / 1_000_000f).finiteNonNegative(),
                latencyMs = (test.latencyMs.takeIf { it > 0f }
                    ?: (test.latencyS * 1000f).takeIf { it > 0f })?.takeIf { it.isFinite() }
            )
        } finally {
            channel.shutdownNow()
        }
    }

    suspend fun router(): SpeedTestResult = withContext(Dispatchers.IO) {
        val channel = OkHttpChannelBuilder.forAddress("192.168.1.1", 9000).usePlaintext().build()
        try {
            val stub = DeviceGrpcKt.DeviceCoroutineStub(channel)
            val started = stub.withDeadlineAfter(8, TimeUnit.SECONDS).handle(
                Request.newBuilder().setStartSpeedtest(
                    StartSpeedtestRequest.newBuilder().setDurationS(10).setSendTelemetry(false).build()
                ).build()
            )
            check(started.hasStartSpeedtest()) { "O roteador não oferece este teste nesta versão." }
            repeat(30) {
                delay(1_000)
                val response = stub.withDeadlineAfter(5, TimeUnit.SECONDS).handle(
                    Request.newBuilder().setGetSpeedtestStatus(GetSpeedtestStatusRequest.getDefaultInstance()).build()
                )
                check(response.hasGetSpeedtestStatus()) { "O roteador não retornou o andamento do teste." }
                val status = response.getSpeedtestStatus.status
                if (!status.running && (status.down.throughputsMbpsCount > 0 || status.up.throughputsMbpsCount > 0)) {
                    return@withContext SpeedTestResult(
                        downloadMbps = status.down.throughputsMbpsList.finiteAverage(),
                        uploadMbps = status.up.throughputsMbpsList.finiteAverage()
                    )
                }
            }
            error("O teste do roteador não terminou a tempo.")
        } finally {
            channel.shutdownNow()
        }
    }
}

private fun Float.finiteNonNegative(): Float = takeIf { it.isFinite() && it >= 0f } ?: 0f
private fun List<Float>.finiteAverage(): Float = filter { it.isFinite() && it >= 0f }
    .takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f
