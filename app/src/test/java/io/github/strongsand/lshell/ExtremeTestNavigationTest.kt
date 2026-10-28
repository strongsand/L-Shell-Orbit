package io.github.strongsand.lshell

import org.junit.Assert.*
import org.junit.Test

class ExtremeTestNavigationTest {
    private fun running(id: Long = 42) = ConnectionTestState(id = id,
        kind = ConnectionTestKind.EXTREME, phase = ConnectionTestPhase.RUNNING, total = 5)
    private fun completed(id: Long = 42) = running(id).copy(phase = ConnectionTestPhase.COMPLETED)

    @Test fun menuEntryDoesNotSelectRetainedCompletedResult() {
        val selected = ExtremeTestNavigation.followedId(completed(), null, null)
        assertNull(selected)
        assertFalse(ExtremeTestNavigation.showsResult(completed(), selected))
        assertTrue(ConnectionTestPresentation.canStart(completed()))
    }

    @Test fun visibleRunOpensResultThenNewMenuEntryStartsFresh() {
        val followed = ExtremeTestNavigation.followedId(running(), null, null)
        assertEquals(42L, followed)
        assertFalse(ExtremeTestNavigation.showsResult(running(), followed))
        val finishedSelection = ExtremeTestNavigation.followedId(completed(), null, followed)
        assertTrue(ExtremeTestNavigation.showsResult(completed(), finishedSelection))
        assertFalse(ExtremeTestNavigation.showsResult(completed(),
            ExtremeTestNavigation.followedId(completed(), null, null)))
    }

    @Test fun returningDuringRunFollowsActiveExecutionWithoutStartingAnother() {
        val followed = ExtremeTestNavigation.followedId(running(), null, null)
        assertEquals(42L, followed)
        assertFalse(ConnectionTestPresentation.canStart(running()))
        assertEquals(42L, ExtremeTestNavigation.followedId(
            running().copy(phase = ConnectionTestPhase.BETWEEN_ROUNDS), null, followed))
    }

    @Test fun subsequentExecutionReplacesFollowedIdentity() {
        val followed = ExtremeTestNavigation.followedId(running(43), null, 42)
        assertEquals(43L, followed)
        assertTrue(ExtremeTestNavigation.showsResult(completed(43), followed))
        assertFalse(ExtremeTestNavigation.showsResult(completed(42), followed))
    }

    @Test fun notificationSelectsOnlyExplicitMatchingResult() {
        val followed = ExtremeTestNavigation.followedId(completed(), 42, null)
        assertTrue(ExtremeTestNavigation.showsResult(completed(), followed))
        assertFalse(ExtremeTestNavigation.showsResult(completed(43), followed))
        assertEquals(42L, ExtremeTestNavigation.followedId(running(43), 42, followed))
    }

    @Test fun restoredDestinationRetainsSelectionAfterCompletion() {
        // The Long is saved with the destination by rememberSaveable, independently
        // of controller persistence and the Activity's original launch Intent.
        val restoredSelection = ExtremeTestNavigation.followedId(running(), null, null)
        assertTrue(ExtremeTestNavigation.showsResult(completed(),
            ExtremeTestNavigation.followedId(completed(), null, restoredSelection)))
        assertNull(ExtremeTestNavigation.followedId(completed(), null, null))
    }

    @Test fun interruptionAndNormalTestsNeverBecomeExtremeResults() {
        assertFalse(ExtremeTestNavigation.showsResult(
            running().copy(phase = ConnectionTestPhase.INTERRUPTED), 42))
        val normal = running().copy(kind = ConnectionTestKind.NORMAL)
        assertNull(ExtremeTestNavigation.followedId(normal, null, null))
        assertFalse(ExtremeTestNavigation.showsResult(
            completed().copy(kind = ConnectionTestKind.NORMAL), 42))
    }
}
