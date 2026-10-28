package io.github.strongsand.lshell

import io.github.strongsand.lshell.core.model.DishAlerts
import io.github.strongsand.lshell.core.model.DishState
import org.junit.Assert.*
import org.junit.Test

class HistoryEventCompatibilityTest {
    private fun type(label: String) = HistoryEventCodec.parse(label).type
    @Test fun allLegacyLostLabelsAreTheSameEvent() {
        listOf("Conexão perdida", "Conexão com a antena perdida").forEach {
            assertEquals(CanonicalHistoryEventType.CONNECTION_LOST, type(it))
            assertEquals(OccurrenceCategory.CONNECTION, OccurrenceHistory.category(it))
        }
    }
    @Test fun allLegacyRestoredLabelsAreTheSameEvent() {
        listOf("Conexão restabelecida", "Conexão com a antena restabelecida", "Conexão restaurada").forEach {
            assertEquals(CanonicalHistoryEventType.CONNECTION_RESTORED, type(it))
        }
    }
    @Test fun prefixesPreserveUnknownPayloadsAndResolutionDirection() {
        val raised = HistoryEventCodec.parse("Alerta: alerta de firmware futuro")
        val resolved = HistoryEventCodec.parse("Alerta resolvido: alerta de firmware futuro")
        assertEquals(CanonicalHistoryEventType.ALERT_RAISED, raised.type)
        assertEquals(CanonicalHistoryEventType.ALERT_RESOLVED, resolved.type)
        assertEquals("alerta de firmware futuro", raised.payload)
        assertEquals(raised.alertKey, resolved.alertKey)
        assertNotEquals(raised.semanticKey, resolved.semanticKey)
        assertEquals(CanonicalHistoryEventType.ALERT_RAISED, type("Alerta:"))
        assertEquals(CanonicalHistoryEventType.ALERT_RESOLVED, type("Alerta resolvido:"))
    }
    @Test fun everyCurrentDishAlertHasACanonicalCode() {
        val all = DishAlerts(true, true, true, true, true, true, true, true, true, true, true)
        assertEquals(11, all.getActiveAlertsList().size)
        all.getActiveAlertsList().forEach {
            assertNotNull(it, HistoryEventCodec.parse(it).alert)
            assertEquals(HistoryEventCodec.parse(it).alert, HistoryEventCodec.parse("Alerta: $it").alert)
        }
    }
    @Test fun transitionsDecodeBothStatesWithoutGuessingUnknownSides() {
        val event = HistoryEventCodec.parse("Conectado → Buscando Satélites")
        assertEquals(CanonicalHistoryEventType.STATE_TRANSITION, event.type)
        assertEquals(DishState.CONNECTED, event.fromState)
        assertEquals(DishState.SEARCHING, event.toState)
        assertEquals(OccurrenceCategory.CONNECTION, HistoryEventCodec.category(event))
        assertEquals(CanonicalHistoryEventType.UNKNOWN, type("texto arbitrário → outro texto"))
        assertEquals(CanonicalHistoryEventType.UNKNOWN, type("Conectado → firmware futuro"))
    }
    @Test fun allProducedStateLabelsAndIdentifiersAreRecognized() {
        DishState.entries.forEach { state ->
            assertEquals(state, HistoryEventCodec.parse(state.displayName).state)
            assertEquals(state, HistoryEventCodec.parse(state.name).state)
        }
    }
    @Test fun reportAndTestLabelsHaveExplicitTypes() {
        mapOf(
            "Perda elevada de pacotes" to CanonicalHistoryEventType.PACKET_LOSS,
            "Pico de latência" to CanonicalHistoryEventType.LATENCY_PEAK,
            "Maior obstrução" to CanonicalHistoryEventType.OBSTRUCTION_PEAK,
            "Dishy reiniciada" to CanonicalHistoryEventType.DEVICE_RESTART,
            "Velocidade Ethernet alterada" to CanonicalHistoryEventType.ETHERNET_CHANGED,
            "Maior tráfego de download observado" to CanonicalHistoryEventType.DOWNLOAD_PEAK,
            "Teste Extremo" to CanonicalHistoryEventType.EXTREME_TEST,
            "Teste Extremo concluído: 5 testes · 1 sem resultado · perda máxima 3,0%" to CanonicalHistoryEventType.EXTREME_TEST,
            "Teste de velocidade" to CanonicalHistoryEventType.NORMAL_TEST,
            "Beacon conectado" to CanonicalHistoryEventType.BEACON
        ).forEach { (label, expected) -> assertEquals(label, expected, type(label)) }
    }
    @Test fun unknownTextDoesNotAcquireMeaningFromSubstrings() {
        val raw = "Nome do teste de conexão e perda de tempo"
        assertEquals(CanonicalHistoryEventType.UNKNOWN, type(raw))
        assertEquals(OccurrenceCategory.OTHER, OccurrenceHistory.category(raw))
        assertEquals(raw, HistoryEventCodec.parse(raw).raw)
    }
    @Test fun equivalentRecoveryVariantsAreDeduplicatedAtTheSameTimeOnly() {
        val events = listOf("Conexão restabelecida", "Conexão com a antena restabelecida", "Conexão restaurada")
            .map { HistoryEntry(1000, "event", it) } + HistoryEntry(2000, "event", "Conexão restabelecida")
        val shown = OccurrenceHistory.compose(emptyList(), events, emptyList())
        assertEquals(2, shown.size)
        assertEquals(setOf(1000L, 2000L), shown.map { it.time }.toSet())
    }
    @Test fun consolidatedRecoveryDoesNotHideAnotherConnectionEventAtTheSameTime() {
        val moment = ReportMoment(1000, "Conexão restaurada", "Falha durou 3 s")
        val rows = listOf(HistoryEntry(1000, "event", "Conexão restabelecida"),
            HistoryEntry(1000, "event", "Conexão perdida"), HistoryEntry(1000, "event", "Conectado → Buscando Satélites"))
        assertEquals(3, OccurrenceHistory.compose(listOf(moment), rows, emptyList()).size)
    }
    @Test fun unknownEventsAndOrphanAlertsRemainVisible() {
        val rows = listOf("Outro evento", "Outro evento 2", "Alerta: alerta futuro", "Alerta resolvido: alerta futuro")
            .map { HistoryEntry(1000, "event", it) }
        assertEquals(4, OccurrenceHistory.compose(emptyList(), rows, emptyList()).size)
    }
    @Test fun duplicateAlertIsRemovedOnlyForTheSameCanonicalAlert() {
        val title = "Desligamento de Emergência por Alta Temperatura"
        val rows = listOf(HistoryEntry(1000, "event", "Alerta: $title"),
            HistoryEntry(1000, "event", "Alerta: Fonte com limitação térmica"))
        assertEquals(2, OccurrenceHistory.compose(listOf(ReportMoment(1000, title, "Sem resolução registrada")), rows, emptyList()).size)
    }
    @Test fun timelineToneAndPriorityUseTypesForEveryConnectionAlias() {
        assertEquals(4, HistoryEventCodec.priority("Conexão com a antena perdida"))
        assertEquals(2, HistoryEventCodec.timelineTone("Conexão restabelecida"))
        assertEquals(2, HistoryEventCodec.timelineTone("Conectado → Buscando Satélites"))
        assertEquals(3, HistoryEventCodec.priority("Alerta: alerta futuro"))
        assertEquals(3, HistoryEventCodec.timelineTone("Perda elevada de pacotes na conexão"))
    }
    @Test fun trafficMarkersRecognizeLegacyAliasesWithoutChangingTheirStoredDetail() {
        val labels = listOf("Conexão perdida", "Conexão restabelecida", "Sem enlace de descida",
            "Antena obstruída agora", "Perda elevada de pacotes na conexão", "Conectado → Buscando Satélites")
        val expected = listOf(TrafficEventKind.UNREACHABLE, TrafficEventKind.RECONNECT, TrafficEventKind.DOWNLINK,
            TrafficEventKind.OBSTRUCTION, TrafficEventKind.LOSS, TrafficEventKind.STATE)
        val rows = labels.mapIndexed { index, label -> HistoryEntry(index * 1000L, "event", label) }
        val events = TrafficSessionAnalyzer.events(rows, 0, 10_000)
        assertEquals(expected, events.map { it.kind })
        assertEquals(labels, events.map { it.detail })
    }
}
