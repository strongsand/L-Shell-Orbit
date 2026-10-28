package io.github.strongsand.lshell

import org.junit.Assert.*
import org.junit.Test

class BottomNavigationPresentationTest {
    @Test fun primaryRoutesSelectTheirOwnCell() {
        assertEquals(listOf(0, 1, 2, 3), listOf(MainTab.HOME, MainTab.DISH, MainTab.NETWORK, MainTab.SETTINGS)
            .map(BottomNavigationPresentation::index))
    }
    @Test fun detailsKeepTheirParentSelectedAndArHasNoFalseSelection() {
        listOf(MainTab.DOWNLOAD, MainTab.UPLOAD, MainTab.LATENCY, MainTab.SIGNAL, MainTab.POWER)
            .forEach { assertEquals(0, BottomNavigationPresentation.index(it)) }
        assertEquals(-1, BottomNavigationPresentation.index(MainTab.SKY))
        listOf(MainTab.SPEED, MainTab.NORMAL_TEST, MainTab.TEST_RESULTS, MainTab.EXTREME,
            MainTab.EXTREME_HISTORY, MainTab.ENERGY, MainTab.TRAFFIC, MainTab.BEACON)
            .forEach { assertEquals(3, BottomNavigationPresentation.index(it)) }
    }
}
