package io.github.strongsand.lshell.beacon

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.strongsand.lshell.DailyReportStore
import io.github.strongsand.lshell.HistoryStore
import io.github.strongsand.lshell.refreshBeaconWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/** Unique work survives process death/reboot. Android controls actual execution times. */
object BeaconSyncSchedule {
    val intervals = listOf(15L, 30L, 60L, 180L, 360L)
    fun normalizeInterval(minutes: Long): Long = minutes.takeIf { it in intervals } ?: 30L
    private const val PERIODIC = "beacon_periodic_sync"
    private const val IMMEDIATE = "beacon_immediate_sync"
    private val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).build()

    fun configure(context: Context) {
        val preferences = BeaconPreferences(context)
        val work = WorkManager.getInstance(context)
        if (!preferences.settings().backgroundSync || preferences.configuredBeacon() == null) {
            work.cancelUniqueWork(PERIODIC)
            work.cancelUniqueWork(IMMEDIATE)
            return
        }
        val request = PeriodicWorkRequestBuilder<BeaconSyncWorker>(
            normalizeInterval(preferences.settings().syncIntervalMinutes), TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun requestNow(context: Context) {
        val prefs = BeaconPreferences(context)
        if (!prefs.settings().backgroundSync || prefs.configuredBeacon() == null) return
        val request = OneTimeWorkRequestBuilder<BeaconSyncWorker>().setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.KEEP, request)
    }
}

/** No BLE scans, foreground service or permanent polling; one bounded LAN discovery fallback. */
class BeaconSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val prefs = BeaconPreferences(applicationContext)
        if (!prefs.settings().backgroundSync) return Result.success()
        val saved = prefs.configuredBeacon() ?: return Result.success()
        val connectivity = applicationContext.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val networks = connectivity.allNetworks.toList()
        val network = networks.firstOrNull {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: networks.firstOrNull {
            !prefs.settings().wifiOnly &&
                connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        }
        // CONNECTED requires Internet on some devices; a local-only Wi-Fi network is sufficient here.
        if (network == null) return retryBounded()
        // Bind only this client's connections, never the process/Dishy path. Local-only Wi-Fi
        // remains usable when Android chooses cellular as its default Internet network.
        val client = AndroidBeaconLanClient(network, applicationContext)
        val manager = BeaconSyncManager(HistoryStore(applicationContext), prefs, client, onHistoryPersisted = { start, end ->
            DailyReportStore(applicationContext).markStaleForRange(start, end)
        })
        return try {
            suspend fun validate(candidate: BeaconDevice): BeaconDevice? {
                val info = client.info(candidate).getOrNull() ?: return null
                require(info.id == saved.id) { "Different Beacon identity." }
                require(info.protocolVersion == 1) { "Incompatible Beacon protocol." }
                client.status(info).getOrThrow()
                return info
            }
            val device = validate(saved) ?: withTimeoutOrNull(8_000L) {
                AndroidNsdBeaconDiscovery(applicationContext).discover().first { candidate ->
                    candidate.getOrNull()?.let {
                        val info = client.info(it).getOrNull()
                        info?.id == saved.id && info.protocolVersion == 1
                    } == true
                }.getOrThrow().let { validate(it) }
            } ?: return retryBounded()
            if (!prefs.settings().backgroundSync || prefs.configuredBeacon()?.id != saved.id) return Result.success()
            val synced = manager.synchronize(device).getOrThrow()
            BeaconSyncGate.mutex.withLock {
                if (prefs.configuredBeacon()?.id == saved.id) {
                    prefs.saveConfiguredBeacon(device)
                    prefs.recordBackgroundSync(synced.imported)
                }
            }
            refreshBeaconWidget(applicationContext)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            Result.failure()
        } catch (_: Exception) {
            retryBounded()
        } finally {
            manager.close()
        }
    }

    private fun retryBounded() = if (runAttemptCount < 3) Result.retry() else Result.failure()
}
