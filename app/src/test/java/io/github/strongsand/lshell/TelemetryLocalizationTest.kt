package io.github.strongsand.lshell

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** Runs the production renderer against the actual PT/EN/ES resources, without an Android runtime. */
class TelemetryLocalizationTest {
    private val resources = File("src/main/res").takeIf { it.isDirectory } ?: File("app/src/main/res")
    private val names = R.string::class.java.fields.associate { it.getInt(null) to it.name }
    private fun strings(folder: String): Map<String, String> = buildMap {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        File(resources, folder).listFiles()!!.filter { it.extension == "xml" }.forEach { file ->
            val nodes = factory.newDocumentBuilder().parse(file).getElementsByTagName("string")
            for (i in 0 until nodes.length) {
                val node = nodes.item(i) as Element
                put(node.getAttribute("name"), node.textContent.replace("\\'", "'"))
            }
        }
    }
    private val locales = listOf("values", "values-en", "values-es").associateWith(::strings)
    private fun render(raw: String, folder: String) = renderTelemetryText(raw) { id, args ->
        String.format(Locale.ROOT, locales.getValue(folder).getValue(names.getValue(id)), *args)
    }
    @Test fun connectionAliasesLocalizeToTheSameValueInEveryLanguage() {
        for (folder in locales.keys) {
            val restored = render("Conexão restaurada", folder)
            listOf("Conexão restabelecida", "Conexão com a antena restabelecida").forEach { assertEquals(restored, render(it, folder)) }
            assertEquals(render("Conexão perdida", folder), render("Conexão com a antena perdida", folder))
        }
        assertEquals("Connection restored", render("Conexão restabelecida", "values-en"))
        assertEquals("Acceso a la antena interrumpido", render("Conexão com a antena perdida", "values-es"))
    }
    @Test fun transitionsLocalizeBothStatesIndividually() {
        assertEquals("Conectado → Buscando satélites", render("Conectado → Buscando Satélites", "values"))
        assertEquals("Connected → Searching for satellites", render("Conectado → Buscando Satélites", "values-en"))
        assertEquals("Conectado → Buscando satélites", render("Conectado → Buscando Satélites", "values-es"))
        for (folder in locales.keys) assertEquals("X → Y", render("X → Y", folder))
    }
    @Test fun persistedDetailAndEmptySummaryAreTranslatedInAllThreeLocales() {
        val cases = mapOf(
            "Não houve dados suficientes neste período" to listOf("Não houve dados suficientes neste período", "Not enough data in this period", "No hubo suficientes datos en este período"),
            "Sem resolução registrada" to listOf("Sem resolução registrada", "No resolution recorded", "Sin resolución registrada"),
            "Resolvido após 3 s" to listOf("Resolvido após 3 s", "Resolved after 3 s", "Resuelto tras 3 s"),
            "Falha durou 3 s" to listOf("Falha durou 3 s", "Interruption lasted 3 s", "La interrupción duró 3 s")
        )
        cases.forEach { (raw, values) -> locales.keys.forEachIndexed { i, folder -> assertEquals(values[i], render(raw, folder)) } }
    }
    @Test fun extremeSummaryGrammarTranslatesCountsFailureAndLossWithoutChangingStoredText() {
        val raw = "Teste Extremo concluído: 5 testes · 1 sem resultado · perda máxima 3,0%"
        assertEquals("Extreme test completed: 5 tests · 1 without a result · peak loss 3,0%", render(raw, "values-en"))
        assertEquals("Prueba extrema completada: 5 pruebas · 1 sin resultado · pérdida máxima 3,0%", render(raw, "values-es"))
        assertEquals("Teste extremo concluído: 5 testes · 1 sem resultado · perda máxima 3,0%", render(raw, "values"))
        assertEquals("Texto livre da sessão", render("Texto livre da sessão", "values-en"))
    }
    @Test fun alertPrefixesTranslateKnownPayloadAndPreserveUnknownPayload() {
        for (folder in locales.keys) {
            val raised = render("Alerta: Desligamento de Emergência por Alta Temperatura", folder)
            val resolved = render("Alerta resolvido: Desligamento de Emergência por Alta Temperatura", folder)
            assertTrue(raised.contains(render("Desligamento de Emergência por Alta Temperatura", folder)))
            assertTrue(resolved.contains(render("Desligamento de Emergência por Alta Temperatura", folder)))
            assertTrue(render("Alerta: firmware XYZ", folder).endsWith("firmware XYZ"))
        }
    }
    @Test fun newDomainResourcesAndFormatParametersExistInEveryLocale() {
        val domain = locales.getValue("values").filterKeys { it.startsWith("domain_") }
        for (folder in locales.keys) {
            assertEquals(domain.keys, locales.getValue(folder).keys.filter { it.startsWith("domain_") }.toSet())
            domain.forEach { (key, value) ->
                val placeholders = Regex("%\\d+\\\$[sd]")
                assertEquals(key, placeholders.findAll(value).map { it.value }.toList(),
                    placeholders.findAll(locales.getValue(folder).getValue(key)).map { it.value }.toList())
            }
        }
    }

    @Test fun interceptedLegacyBranchesKeepTheirResourceInEveryLocale() {
        val expected = mapOf(
            "Estrangulamento Térmico Ativo (Thermal Throttle)" to R.string.editorial_alert_thermal,
            "Desligamento de Emergência por Alta Temperatura" to R.string.editorial_alert_shutdown,
            "Cabo de Rede Limitado a 10/100 Mbps" to R.string.editorial_alert_ethernet,
            "Motores Travados ou Bloqueados" to R.string.editorial_alert_motors,
            "Mastro Inclinado (Não Vertical)" to R.string.editorial_alert_mast,
            "Fonte com limitação térmica" to R.string.editorial_alert_supply,
            "Sem conexão Ethernet" to R.string.editorial_alert_no_ethernet,
            "Economia de energia ativa" to R.string.editorial_alert_saving,
            "Instalação pendente" to R.string.editorial_alert_install,
            "Localização Inesperada da Célula" to R.string.editorial_alert_location,
            "Aquecimento para Derretimento de Gelo Ativo" to R.string.editorial_alert_heating,
            "Antena obstruída agora" to R.string.editorial_alert_obstructed,
            "Perda elevada de pacotes na conexão" to R.string.editorial_alert_loss,
            "Conexão restaurada" to R.string.editorial_stored_122,
            "Conexão perdida" to R.string.editorial_stored_123,
            "Perda elevada de pacotes" to R.string.editorial_moment_loss,
            "Teste Extremo" to R.string.function_extreme,
            "Maior tráfego de download observado" to R.string.editorial_moment_download,
            "Pico de latência" to R.string.editorial_moment_latency,
            "Maior obstrução" to R.string.editorial_moment_obstruction,
            "Dishy reiniciada" to R.string.editorial_moment_restart,
            "Velocidade Ethernet alterada" to R.string.editorial_moment_ethernet
        )
        for (folder in locales.keys) expected.forEach { (raw, id) ->
            assertEquals(raw, locales.getValue(folder).getValue(names.getValue(id)), render(raw, folder))
        }
    }
    @Test fun canonicalStatesStillReachTheirLocalizedFallback() {
        val expected = mapOf(
            "Desconhecido" to R.string.editorial_state_unknown,
            "Conectado" to R.string.editorial_state_connected,
            "Buscando Satélites" to R.string.editorial_state_searching,
            "Inicializando" to R.string.editorial_state_booting,
            "Recolhida (Stowed)" to R.string.editorial_state_stowed,
            "Proteção Térmica" to R.string.editorial_state_thermal_shutdown,
            "Sem Satélites no FOV" to R.string.editorial_state_no_sats,
            "Visada Obstruída" to R.string.editorial_state_obstructed,
            "Sem enlace de descida" to R.string.editorial_state_no_downlink,
            "Sem resposta de rede" to R.string.editorial_state_no_pings,
            "Ajustando antena" to R.string.editorial_state_actuator_activity,
            "Testando cabo" to R.string.editorial_state_cable_test,
            "Em repouso" to R.string.editorial_state_sleeping,
            "Rádio desativado" to R.string.editorial_state_inhibit_rf,
            "Inalcançável (Fora da Rede)" to R.string.editorial_state_unreachable
        )
        for (folder in locales.keys) expected.forEach { (raw, id) ->
            assertEquals(raw, locales.getValue(folder).getValue(names.getValue(id)), render(raw, folder))
        }
        for (folder in locales.keys) {
            assertEquals("Firmware future state", render("Firmware future state", folder))
            assertEquals("Unrecognized → value", render("Unrecognized → value", folder))
        }
    }
    @Test fun technicalThermalDetailStillPrecedesTheCodec() {
        for (folder in locales.keys) {
            val actual = renderTelemetryText("Estrangulamento Térmico Ativo (Thermal Throttle)", true) { id, args ->
                String.format(Locale.ROOT, locales.getValue(folder).getValue(names.getValue(id)), *args)
            }
            assertEquals(locales.getValue(folder).getValue("editorial_alert_thermal_detail"), actual)
        }
    }
}
