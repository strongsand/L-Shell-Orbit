package io.github.strongsand.lshell.beacon

import org.junit.Assert.*
import org.junit.Test

class BeaconRepositoryLeaseTest {
    private class Resource(var pendingManualSync: Boolean = false, var closes: Int = 0)
    private val lease = BeaconRepositoryLease<Resource>({ it.pendingManualSync }, { it.closes++ })

    @Test fun uiAndServiceAcquireExactlyTheSameRepository() {
        val ui = lease.acquire { Resource() }
        val service = lease.acquire { error("must reuse the existing engine") }
        assertSame(ui, service)
        lease.release(ui)
        assertEquals(0, service.closes)
        lease.release(service)
        assertEquals(1, service.closes)
    }

    @Test fun leavingBeforeServiceStartsCannotCancelPendingManualSync() {
        val ui = lease.acquire { Resource(pendingManualSync = true) }
        lease.release(ui)
        assertEquals(0, ui.closes)
        val service = lease.acquire { error("pending engine must survive Activity disposal") }
        assertSame(ui, service)
        service.pendingManualSync = false
        lease.closeIfUnused(service)
        assertEquals(0, service.closes)
        lease.release(service)
        assertEquals(1, service.closes)
    }

    @Test fun recreationDuringSyncReattachesToSameStateAndEvents() {
        val original = lease.acquire { Resource(true) }
        val service = lease.acquire { error("duplicate") }
        lease.release(original)
        val recreated = lease.acquire { error("must restore same state source") }
        service.pendingManualSync = false
        lease.release(service)
        assertEquals(0, recreated.closes)
        assertSame(original, recreated)
        lease.release(recreated)
        assertEquals(1, original.closes)
    }

    @Test fun failedPendingStartWithoutUiReleasesResourcesExactlyOnce() {
        val resource = lease.acquire { Resource(true) }
        lease.release(resource)
        resource.pendingManualSync = false
        lease.closeIfUnused(resource)
        lease.closeIfUnused(resource)
        assertEquals(1, resource.closes)
        assertNotSame(resource, lease.acquire { Resource() })
    }

    @Test fun automaticOnlyRepositoryStillClosesWithTheUi() {
        val resource = lease.acquire { Resource() }
        lease.release(resource)
        assertEquals(1, resource.closes)
    }
}
