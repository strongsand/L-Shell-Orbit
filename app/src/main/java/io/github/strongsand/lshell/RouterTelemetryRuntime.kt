package io.github.strongsand.lshell

import io.github.strongsand.lshell.core.network.grpc.RouterGrpcClient
import io.github.strongsand.lshell.core.network.grpc.RouterInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal data class RouterTelemetryState(
    val info: RouterInfo? = null,
    val refreshing: Boolean = false,
    val error: String? = null,
    val includesDetails: Boolean = false
)

/** One existing 15-second router cadence shared by visible Home/Router consumers.
 * Home requests only get_status; the Router screen retains its clients/diagnostics.
 * No collectors, requests or cached voltage survive the last lifecycle lease.
 */
internal class RouterTelemetryRuntime(
    private val scope: CoroutineScope,
    private val fetch: suspend (Boolean) -> Result<RouterInfo>,
    private val sendReboot: suspend () -> Result<Unit>,
    private val intervalMs: Long = 15_000L
) {
    private val lock = Any()
    private val owners = mutableMapOf<Any, Boolean>()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val transport = Mutex()
    private val state = MutableStateFlow(RouterTelemetryState())
    private var job: Job? = null
    private var generation = 0L

    fun observe(includeDetails: Boolean): Flow<RouterTelemetryState> = flow {
        val owner = Any()
        synchronized(lock) {
            val previouslyDetailed = owners.values.any { it }
            owners[owner] = includeDetails
            if (job == null) {
                generation++
                job = scope.launch {
                    while (currentCoroutineContext().isActive) {
                        refresh()
                        withTimeoutOrNull(intervalMs) { requests.receive() }
                    }
                }
            } else if (includeDetails && !previouslyDetailed) requestRefresh()
        }
        try { emitAll(state) }
        finally {
            synchronized(lock) {
                owners.remove(owner)
                if (owners.isEmpty()) {
                    generation++
                    job?.cancel(); job = null
                    while (requests.tryReceive().isSuccess) { /* discard old manual refreshes */ }
                    state.value = RouterTelemetryState()
                }
            }
        }
    }

    fun requestRefresh() { requests.trySend(Unit) }

    /** Also serialize explicit refreshes with the shared loop; never overlap RPCs. */
    suspend fun refresh(): Result<RouterInfo> = transport.withLock {
        val (epoch, details) = synchronized(lock) {
            state.value = state.value.copy(refreshing = true)
            generation to owners.values.any { it }
        }
        val result = try { fetch(details) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
        synchronized(lock) {
            if (generation == epoch && owners.isNotEmpty()) {
                // Failure or a successful response without PoE clears the live reading.
                state.value = RouterTelemetryState(result.getOrNull(), false,
                    result.exceptionOrNull()?.message ?: if (result.isFailure) "Router unavailable" else null,
                    details)
            }
        }
        result
    }

    suspend fun reboot(): Result<Unit> = transport.withLock {
        synchronized(lock) { state.value = state.value.copy(info = state.value.info?.copy(poeInput = null)) }
        sendReboot().also { requestRefresh() }
    }

    companion object {
        val shared: RouterTelemetryRuntime by lazy {
            val client = RouterGrpcClient()
            RouterTelemetryRuntime(CoroutineScope(SupervisorJob() + Dispatchers.IO), client::fetch, client::reboot)
        }
    }
}
