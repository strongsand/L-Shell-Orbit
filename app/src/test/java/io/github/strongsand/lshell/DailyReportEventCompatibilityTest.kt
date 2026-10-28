package io.github.strongsand.lshell

import org.junit.Assert.*
import org.junit.Test

class DailyReportEventCompatibilityTest {
    private val samples = listOf(0L, 1000L, 7000L, 8000L).map { HistoryEntry(it, "reading", "Conectado", 10f, 1f, 20f, 0f) }
    private fun report(lost: String, restored: String) = DailyReportGenerator.generate(samples +
        listOf(HistoryEntry(2000, "event", lost), HistoryEntry(7000, "event", restored)), 0, 9000)

    @Test fun legacyVariantsKeepIdenticalAvailabilityDurationsAndMetrics() {
        val reference = report("Conexão com a antena perdida", "Conexão com a antena restabelecida")
        assertEquals(5000L, reference.offlineMs)
        assertEquals(4000L, reference.onlineMs)
        assertEquals(4000f / 9000f * 100f, reference.availabilityPercent!!, .001f)
        for (lost in listOf("Conexão perdida", "Conexão com a antena perdida")) {
            for (restored in listOf("Conexão restaurada", "Conexão restabelecida", "Conexão com a antena restabelecida")) {
                val actual = report(lost, restored)
                assertEquals(reference.interruptions, actual.interruptions)
                assertEquals(reference.onlineMs, actual.onlineMs)
                assertEquals(reference.offlineMs, actual.offlineMs)
                assertEquals(reference.availabilityPercent, actual.availabilityPercent)
                assertEquals(reference.trafficDownload, actual.trafficDownload)
                assertEquals(reference.timeline, actual.timeline)
            }
        }
    }
    @Test fun restoreMustBeAnActualRecoveryEventNotAnyTextContainingRestored() {
        val rows = listOf(HistoryEntry(2000, "event", "Conexão perdida"),
            HistoryEntry(3000, "event", "Alerta: conexão restaurada de outro equipamento"))
        val result = DailyReportGenerator.generate(rows, 0, 8000)
        assertFalse(result.interruptions.single().resolved)
        assertEquals(6000L, result.interruptions.single().durationMs)
    }
    @Test fun canonicalAlertsPairAliasesWithoutPairingADifferentAlert() {
        val rows = listOf(HistoryEntry(1000, "event", "Alerta: Desligamento de Emergência por Alta Temperatura"),
            HistoryEntry(2000, "event", "Alerta resolvido: Fonte com limitação térmica"),
            HistoryEntry(4000, "event", "Alerta resolvido: Desligamento por alta temperatura"))
        val result = DailyReportGenerator.generate(rows, 0, 8000)
        assertEquals("Resolvido após 3 s", result.alerts.single().detail)
        assertEquals("Desligamento de Emergência por Alta Temperatura", result.alerts.single().title)
    }
    @Test fun unknownAlertsUseExactPayloadForSafePairing() {
        val rows = listOf(HistoryEntry(1000, "event", "Alerta: Futuro A"),
            HistoryEntry(2000, "event", "Alerta resolvido: Futuro B"))
        val result = DailyReportGenerator.generate(rows, 0, 8000)
        assertEquals("Sem resolução registrada", result.alerts.single().detail)
    }
    @Test fun oldJsonLabelsRoundTripWithoutBeingRewritten() {
        val old = report("Conexão com a antena perdida", "Conexão restabelecida").copy(
            timeline = listOf(ReportMoment(1000, "Conexão com a antena perdida", "Falha durou 3 s")),
            alerts = listOf(ReportMoment(2000, "Fonte com limitação térmica", "Sem resolução registrada")))
        val json = old.toJson()
        val restored = reportFromJson(json)
        assertEquals(old.timeline, restored.timeline)
        assertEquals(old.alerts, restored.alerts)
        assertEquals(json.toString(), restored.toJson().toString())
    }
    @Test fun emptyDatabaseAndUnrecognizedEventsDoNotInventCoverageOrOutages() {
        val empty = DailyReportGenerator.generate(emptyList(), 0, 8000)
        assertNull(empty.availabilityPercent); assertEquals(0L, empty.monitoredMs)
        assertTrue(empty.interruptions.isEmpty()); assertEquals(48, empty.lossChart.size)
        val unknown = DailyReportGenerator.generate(listOf(HistoryEntry(1000, "event", "Meu teste de conexão")), 0, 8000)
        assertTrue(unknown.interruptions.isEmpty()); assertNull(unknown.availabilityPercent)
    }
}
