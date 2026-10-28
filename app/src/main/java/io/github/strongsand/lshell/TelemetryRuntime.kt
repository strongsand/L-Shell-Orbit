package io.github.strongsand.lshell

import android.content.Context
import io.github.strongsand.lshell.core.data.DishyRepository
import io.github.strongsand.lshell.core.model.DishySnapshot
import io.github.strongsand.lshell.core.network.grpc.DishyGrpcClient
import io.github.strongsand.lshell.core.network.grpc.terminalHistorySize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/** One polling owner map, not one gRPC loop per screen/service. */
internal class TelemetryLeases {
    private val owners = mutableMapOf<String, Long>()
    fun acquire(owner: String, intervalMs: Long) { owners[owner] = intervalMs.coerceAtLeast(1000) }
    fun release(owner: String) { owners.remove(owner) }
    val interval: Long? get() = owners.values.minOrNull()
    val persistStatus get() = owners.keys.any { it != UI && !it.startsWith("$UI:") }
    val statusPersistenceInterval get() = owners[SESSION] ?: owners[MONITOR]
    val historyInterval get() = if (owners.keys.any { it == SESSION || it == UI || it.startsWith("$UI:") }) 5000L else 60000L
    val persistEvents get() = SESSION in owners && MONITOR !in owners
    companion object { const val UI = "ui"; const val MONITOR = "monitor"; const val SESSION = "session" }
}

/** Process-scoped repository; only explicit leases enable polling. No Activity references. */
internal class TelemetryRuntime private constructor(private val context: Context) {
    private val leases = TelemetryLeases()
    private val lock = Any()
    private val dbLock = Any()
    private var historyStore: HistoryStore? = null
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var generation = 0L
    private val _storageFailure = MutableStateFlow(false)
    val storageFailure = _storageFailure.asStateFlow()
    @Volatile private var nextStatusSaveAt = 0L
    val repository = DishyRepository(onStatus = { snapshot ->
        val cadence = synchronized(lock) { leases.statusPersistenceInterval }
        val now = android.os.SystemClock.elapsedRealtime()
        if (cadence != null && now >= nextStatusSaveAt) {
            persist { addReading(snapshot) }
            nextStatusSaveAt = now + cadence
        }
    }, onHistory = { data, snapshot -> importHistory(data, snapshot) }, onEvent = { event ->
        // The alert service already persists its own connection events when enabled.
        if (synchronized(lock) { leases.persistEvents }) persist { addEvent(event.description, event.timestamp) }
    })

    fun acquire(owner: String, intervalMs: Long) = synchronized(lock) {
        generation++
        leases.acquire(owner, intervalMs)
        if (owner == TelemetryLeases.SESSION) nextStatusSaveAt = 0L
        repository.startMonitoring(leases.interval!!, leases.historyInterval)
    }
    fun release(owner: String) = synchronized(lock) {
        generation++
        leases.release(owner)
        val interval = leases.interval
        if (interval == null) {
            repository.close()
            val releasedGeneration = generation
            cleanupScope.launch {
                synchronized(dbLock) {
                    if (generation == releasedGeneration) {
                        historyStore?.close(); historyStore = null
                    }
                }
            }
        } else repository.startMonitoring(interval, leases.historyInterval)
    }
    private fun persist(action: HistoryStore.() -> Unit) {
        try {
            synchronized(dbLock) {
                val store = historyStore ?: HistoryStore(context).also { historyStore = it }
                store.action()
            }
            _storageFailure.value = false
        }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { _storageFailure.value = true }
    }
    private fun importHistory(data: DishyGrpcClient.HistoryReading, snapshot: DishySnapshot) {
        if (!snapshot.isOnline) return
        persist {
            val oldBoot = MonitorPreferences.historyBoot(context)
            val candidateBoot = if (snapshot.deviceInfo.uptimeSeconds > 0)
                System.currentTimeMillis() / 1000 - snapshot.deviceInfo.uptimeSeconds else oldBoot.coerceAtLeast(0)
            val oldCounter = MonitorPreferences.historyCounter(context)
            val sameBoot = oldCounter < data.current && abs(candidateBoot - oldBoot) <= 60
            val boot = if (sameBoot) oldBoot else candidateBoot
            importTerminalHistory(data, if (sameBoot) oldCounter else -1, boot)
            if (data.current > 0 && terminalHistorySize(data) > 0)
                MonitorPreferences.setHistoryCursor(context, data.current - 1, boot)
        }
    }
    companion object {
        @Volatile private var instance: TelemetryRuntime? = null
        fun get(context: Context): TelemetryRuntime = instance ?: synchronized(this) {
            instance ?: TelemetryRuntime(context.applicationContext).also { instance = it }
        }
    }
}
