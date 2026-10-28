package io.github.strongsand.lshell

import org.junit.Assert.*
import org.junit.Test

class AppLaunchNavigationTest {
    @Test fun eachNotificationDestinationIsConsumedOnce() {
        listOf(MainTab.EXTREME, MainTab.SPEED, MainTab.TRAFFIC, MainTab.REPORTS, MainTab.BEACON).forEach { tab ->
            assertEquals(tab, AppLaunchNavigation.destination(tab.name, 7, null))
            assertNull(AppLaunchNavigation.destination(tab.name, 7, 7))
        }
    }

    @Test fun recreationDoesNotOverrideTheUsersCurrentScreenWithAnOldLink() {
        val savedHandledRequest = 7
        // Even if the original Intent still names REPORTS, a restored HISTORY route wins.
        assertNull(AppLaunchNavigation.destination("REPORTS", 7, savedHandledRequest))
        assertNull(AppLaunchNavigation.destination("TRAFFIC", 7, savedHandledRequest))
    }

    @Test fun anotherTapOnTheSameNotificationIsAValidNewRequest() {
        assertEquals(MainTab.REPORTS, AppLaunchNavigation.destination("REPORTS", 8, 7))
        assertEquals(MainTab.TRAFFIC, AppLaunchNavigation.destination("TRAFFIC", 8, 7))
    }

    @Test fun freshLaunchCanOpenAnExplicitResultIncludingRequestZero() {
        assertEquals(MainTab.EXTREME, AppLaunchNavigation.destination("EXTREME", 0, null))
        assertEquals(MainTab.SPEED, AppLaunchNavigation.destination("SPEED", 0, null))
    }

    @Test fun ordinaryStartupAndInvalidRoutesDoNotChangeNavigation() {
        listOf(null, "", "HOME", "unknown").forEach {
            assertNull(AppLaunchNavigation.destination(it, 0, null))
        }
    }
}
