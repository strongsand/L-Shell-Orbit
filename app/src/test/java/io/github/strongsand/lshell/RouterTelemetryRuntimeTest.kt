package io.github.strongsand.lshell

import io.github.strongsand.lshell.core.network.grpc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test

class RouterTelemetryRuntimeTest {
    private fun info(voltage: Float? = 56.2f) = RouterInfo("router-A", "v3", "", "", 0, 0,
        RouterBootCause(0), emptyList(), "", emptyList(), null, null, null, emptyList(), emptyList(), emptyList(),
        voltage?.let { RouterPoeReading(it, "router-A", "dish-A", 1_000) })

    private suspend fun Channel<RouterTelemetryState>.settled() = withTimeout(3_000) {
        var next = receive()
        while (next.refreshing || (next.info == null && next.error == null)) next = receive()
        next
    }

    @Test fun homeAndRouterShareOneLoopAndDetailsAreOnlyRequestedWhenNeeded() = runBlocking {
        withTimeout(5_000) {
            val requests = Channel<Boolean>(Channel.UNLIMITED)
            val replies = Channel<Result<RouterInfo>>(Channel.UNLIMITED)
            val homeStates = Channel<RouterTelemetryState>(Channel.UNLIMITED)
            val routerStates = Channel<RouterTelemetryState>(Channel.UNLIMITED)
            val runtime = RouterTelemetryRuntime(this, { details -> requests.send(details); replies.receive() },
                { Result.success(Unit) }, 60_000)
            val home = launch { runtime.observe(false).collect { homeStates.send(it) } }
            assertFalse(requests.receive())
            replies.send(Result.success(info()))
            assertNotNull(homeStates.settled().info?.poeInput)
            val secondHome = launch { runtime.observe(false).collect() }
            yield()
            assertTrue(requests.tryReceive().isFailure)
            val router = launch { runtime.observe(true).collect { routerStates.send(it) } }
            assertTrue(requests.receive())
            replies.send(Result.success(info()))
            var detailed = routerStates.settled()
            while (!detailed.includesDetails) detailed = routerStates.settled()
            router.cancelAndJoin()
            runtime.requestRefresh()
            assertFalse(requests.receive())
            replies.send(Result.success(info()))
            home.cancelAndJoin(); secondHome.cancelAndJoin()
        }
    }

    @Test fun failureAndMissingAfterValidClearLivePoeInsteadOfKeepingLastValue() = runBlocking {
        withTimeout(5_000) {
            val replies = Channel<Result<RouterInfo>>(Channel.UNLIMITED)
            val states = Channel<RouterTelemetryState>(Channel.UNLIMITED)
            val runtime = RouterTelemetryRuntime(this, { replies.receive() }, { Result.success(Unit) }, 60_000)
            val observer = launch { runtime.observe(false).collect { states.send(it) } }
            replies.send(Result.success(info()))
            assertNotNull(states.settled().info?.poeInput)
            runtime.requestRefresh(); replies.send(Result.success(info(null)))
            assertNull(states.settled().info!!.poeInput)
            runtime.requestRefresh(); replies.send(Result.failure(IllegalStateException("offline")))
            val failed = states.settled()
            assertEquals("offline", failed.error)
            assertNull(failed.info)
            observer.cancelAndJoin()
        }
    }

    @Test fun lastObserverCancelsTransportAndResumeCannotReplayOldVoltage() = runBlocking {
        withTimeout(5_000) {
            val entered = Channel<Unit>(Channel.UNLIMITED)
            val cancelled = Channel<Unit>(Channel.UNLIMITED)
            val initialStates = Channel<RouterTelemetryState>(Channel.UNLIMITED)
            var calls = 0
            val runtime = RouterTelemetryRuntime(this, {
                entered.send(Unit)
                if (++calls == 1) Result.success(info())
                else try { awaitCancellation() } finally { cancelled.trySend(Unit) }
            }, { Result.success(Unit) }, 60_000)
            var observer = launch { runtime.observe(false).collect { initialStates.send(it) } }
            entered.receive()
            assertNotNull(initialStates.settled().info?.poeInput)
            runtime.requestRefresh()
            entered.receive()
            observer.cancelAndJoin()
            cancelled.receive()
            val states = Channel<RouterTelemetryState>(Channel.UNLIMITED)
            observer = launch { runtime.observe(false).collect { states.send(it) } }
            assertNull(states.receive().info)
            entered.receive()
            observer.cancelAndJoin()
            cancelled.receive()
        }
    }

    @Test fun manualRefreshIsSerializedWithPollingAndRebootInvalidatesPoe() = runBlocking {
        withTimeout(5_000) {
            val requests = Channel<Unit>(Channel.UNLIMITED)
            val replies = Channel<Result<RouterInfo>>(Channel.UNLIMITED)
            val states = Channel<RouterTelemetryState>(Channel.UNLIMITED)
            val rebootEntered = CompletableDeferred<Unit>()
            val rebootDone = CompletableDeferred<Unit>()
            val runtime = RouterTelemetryRuntime(this, { requests.send(Unit); replies.receive() }, {
                rebootEntered.complete(Unit); rebootDone.await(); Result.success(Unit)
            }, 60_000)
            val observer = launch { runtime.observe(true).collect { states.send(it) } }
            requests.receive()
            val manual = async { runtime.refresh() }
            yield()
            assertTrue(requests.tryReceive().isFailure)
            replies.send(Result.success(info()))
            requests.receive()
            replies.send(Result.success(info()))
            assertTrue(manual.await().isSuccess)
            assertNotNull(states.settled().info?.poeInput)
            // Drain settled snapshots before checking the reboot invalidation.
            while (states.tryReceive().isSuccess) { }
            val reboot = async { runtime.reboot() }
            rebootEntered.await()
            assertNull(states.receive().info?.poeInput)
            rebootDone.complete(Unit)
            assertTrue(reboot.await().isSuccess)
            observer.cancelAndJoin()
        }
    }
}
