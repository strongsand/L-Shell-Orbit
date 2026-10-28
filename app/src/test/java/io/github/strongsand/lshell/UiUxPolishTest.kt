package io.github.strongsand.lshell

import org.junit.Assert.*
import org.junit.Test

class UiUxPolishTest {
    @Test fun enteringAnOutagePeriodStartsLoadingWithoutAnEmptyResult() {
        val state = UiLoadStatus(OutageRange.MONTH)
        assertEquals(UiLoadPhase.LOADING, state.phaseFor(OutageRange.MONTH))
    }

    @Test fun anEmptyOldDayCannotFlashAsZeroOutagesInANewMonth() {
        val old = UiLoadStatus(OutageRange.TODAY).completed(empty = true)
        assertEquals(UiLoadPhase.EMPTY, old.phaseFor(OutageRange.TODAY))
        assertEquals(UiLoadPhase.LOADING, old.phaseFor(OutageRange.MONTH))
    }

    @Test fun anOldLoadedPeriodCannotAppearAsTheNewPeriod() {
        val old = UiLoadStatus(OutageRange.WEEK).completed(empty = false)
        assertEquals(UiLoadPhase.LOADED, old.phaseFor(OutageRange.WEEK))
        assertEquals(UiLoadPhase.LOADING, old.phaseFor(OutageRange.YESTERDAY))
    }

    @Test fun aFailedReadIsAnErrorAndNeverAnEmptyResult() {
        assertEquals(UiLoadPhase.ERROR,
            UiLoadStatus(OutageRange.MONTH).failed().phaseFor(OutageRange.MONTH))
    }

    @Test fun retryingAnEmptyOrFailedPeriodStartsANewLoadingRequest() {
        for (previous in listOf(UiLoadStatus(OutageRange.MONTH to 0).failed(),
                UiLoadStatus(OutageRange.MONTH to 0).completed(empty = true))) {
            assertEquals(UiLoadPhase.LOADING, previous.phaseFor(OutageRange.MONTH to 1))
        }
    }

    @Test fun completionIsTheOnlyPointWhereTheNewRequestBecomesEmptyOrLoaded() {
        val request = OutageRange.MONTH to 2
        assertEquals(UiLoadPhase.EMPTY, UiLoadStatus(request).completed(true).phaseFor(request))
        assertEquals(UiLoadPhase.LOADED, UiLoadStatus(request).completed(false).phaseFor(request))
    }

    @Test fun clearingSearchRestoresTheFullDirectoryInAllThreeLanguages() {
        for ((query, labels) in listOf(
            "trafego" to listOf("Monitor de tráfego", "Relatórios", "Configurações"),
            "traffic" to listOf("Traffic monitor", "Reports", "Settings"),
            "trafico" to listOf("Monitor de tráfico", "Informes", "Ajustes"))) {
            assertEquals(1, labels.count { matchesFunctionSearch(query, it, "") })
            assertEquals(labels, labels.filter { matchesFunctionSearch("", it, "") })
        }
    }
}
