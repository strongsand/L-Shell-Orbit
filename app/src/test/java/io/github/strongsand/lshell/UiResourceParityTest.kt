package io.github.strongsand.lshell

import io.github.strongsand.lshell.ar.ArUiText
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** Checks the actual Android XML, including every translatable key, not a second text catalog. */
class UiResourceParityTest {
    private data class Entry(
        val kind: String,
        val parts: Map<String, String>,
        val translatable: Boolean = true,
        val formatted: Boolean = true
    )
    private val resources = File("src/main/res").takeIf { it.isDirectory } ?: File("app/src/main/res")
    private val folders = listOf("values", "values-en", "values-es")
    private val catalog = folders.associateWith(::readCatalog)
    private val stringNames = R.string::class.java.fields.associate { it.getInt(null) to it.name }

    private fun readCatalog(folder: String): Map<String, Entry> = buildMap {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        File(resources, folder).listFiles()!!.filter { it.extension == "xml" }.forEach { file ->
            val nodes = factory.newDocumentBuilder().parse(file).documentElement.childNodes
            for (i in 0 until nodes.length) {
                val node = nodes.item(i) as? Element ?: continue
                if (node.tagName !in setOf("string", "plurals", "string-array")) continue
                val name = node.getAttribute("name")
                val parts = if (node.tagName == "string") mapOf("" to node.textContent) else buildMap {
                    val items = node.getElementsByTagName("item")
                    for (j in 0 until items.length) {
                        val item = items.item(j) as Element
                        val part = if (node.tagName == "plurals") item.getAttribute("quantity") else j.toString()
                        check(put(part, item.textContent) == null) { "$folder/$name: duplicate $part" }
                    }
                }
                check(put(name, Entry(node.tagName, parts,
                    node.getAttribute("translatable") != "false",
                    node.getAttribute("formatted") != "false")) == null) { "$folder: duplicate $name" }
            }
        }
    }

    private fun placeholders(text: String): List<Pair<Int, String>> {
        var implicit = 0
        return Regex("%(?:(\\d+)\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?([a-zA-Z%])")
            .findAll(text).filter { it.groupValues[2] != "%" }.map {
                val position = it.groupValues[1].toIntOrNull() ?: ++implicit
                position to it.groupValues[2]
            }.sortedWith(compareBy({ it.first }, { it.second })).toList()
    }

    private fun contractIssues(base: Entry, localized: Entry): List<String> = buildList {
        if (base.kind != localized.kind) add("kind")
        if (base.formatted != localized.formatted) add("formatting")
        if (base.parts.keys != localized.parts.keys) add("parts")
        if (base.formatted && localized.formatted) {
            (base.parts.keys intersect localized.parts.keys).forEach { part ->
                if (placeholders(base.parts.getValue(part)) != placeholders(localized.parts.getValue(part))) {
                    add("placeholder:$part")
                }
            }
        }
    }

    private fun text(folder: String, key: String, vararg args: Any): String =
        String.format(Locale.ROOT, catalog.getValue(folder).getValue(key).parts.getValue("").replace("\\'", "'"), *args)

    private fun quantity(folder: String, key: String, count: Int, vararg rest: Any): String {
        // These tests use 1 and 2, whose CLDR categories are one/other in all three locales.
        val entry = catalog.getValue(folder).getValue(key)
        val template = entry.parts.getValue(if (count == 1) "one" else "other").replace("\\'", "'")
        return String.format(Locale.ROOT, template, count, *rest)
    }

    @Test fun allTranslatableKeysHaveExactParity() {
        val expected = catalog.getValue("values").filterValues { it.translatable }.keys
        for (folder in folders.drop(1)) {
            val translated = catalog.getValue(folder).filterKeys { catalog.getValue("values")[it]?.translatable != false }
            assertEquals("Missing or extra resources in $folder", expected, translated.keys)
        }
    }

    @Test fun kindsPluralBranchesArraysAndPlaceholdersMatchInEveryLocale() {
        for ((key, base) in catalog.getValue("values").filterValues { it.translatable }) {
            for (folder in folders.drop(1)) {
                val localized = catalog.getValue(folder)[key] ?: throw AssertionError("Missing $folder/$key")
                assertEquals("$folder/$key", emptyList<String>(), contractIssues(base, localized))
            }
        }
    }

    @Test fun everyPluralHasAnOtherBranch() {
        catalog.forEach { (folder, entries) -> entries.filterValues { it.kind == "plurals" }.forEach { (key, entry) ->
            assertTrue("$folder/$key", "other" in entry.parts)
        } }
    }

    @Test fun contractDetectsRemovedPluralBranchAndChangedResourceKind() {
        val base = Entry("plurals", mapOf("one" to "%1\$d outage", "other" to "%1\$d outages"))
        assertTrue("parts" in contractIssues(base, base.copy(parts = base.parts - "one")))
        assertTrue("kind" in contractIssues(base, base.copy(kind = "string")))
    }

    @Test fun contractDetectsWrongPlaceholderTypeAndLostArgument() {
        val base = Entry("string", mapOf("" to "%1\$d · %2\$s"))
        assertTrue(contractIssues(base, base.copy(parts = mapOf("" to "%1\$s · %2\$s"))).isNotEmpty())
        assertTrue(contractIssues(base, base.copy(parts = mapOf("" to "%1\$d"))).isNotEmpty())
        assertTrue(contractIssues(base, base.copy(parts = mapOf("" to "%2\$s · %1\$d"))).isEmpty())
    }

    @Test fun contractDetectsMissingArrayItemAndHonorsLiteralPercentages() {
        val base = Entry("string-array", mapOf("0" to "First", "1" to "Second"))
        assertTrue("parts" in contractIssues(base, base.copy(parts = mapOf("0" to "Primero"))))
        assertTrue(contractIssues(Entry("string", mapOf("" to "37% analisado"), formatted = false),
            Entry("string", mapOf("" to "37% surveyed"), formatted = false)).isEmpty())
    }

    @Test fun appNameIsExplicitlyNonTranslatableAndAvailableThroughDefaultResources() {
        val app = catalog.getValue("values").getValue("app_name")
        assertFalse(app.translatable)
        assertEquals("L-Shell Orbit", app.parts.getValue(""))
    }

    @Test fun allWidgetPickerNamesExistAndUseTheCorrectLanguage() {
        val suffixes = listOf("summary", "speed", "connection", "test", "alerts", "trends", "obstruction", "report", "beacon")
        for (folder in folders) for (suffix in suffixes) {
            assertTrue(text(folder, "widget_${suffix}_name").startsWith("L-Shell Orbit · "))
        }
        assertEquals("L-Shell Orbit · Resumo", text("values", "widget_summary_name"))
        assertEquals("L-Shell Orbit · Summary", text("values-en", "widget_summary_name"))
        assertEquals("L-Shell Orbit · Resumen", text("values-es", "widget_summary_name"))
    }

    @Test fun notificationTitlesAndBodiesUseLocalizedResources() {
        assertEquals("Teste de notificação", text("values", "ui_notification_test"))
        assertEquals("Notification test", text("values-en", "ui_notification_test"))
        assertEquals("Prueba de notificación", text("values-es", "ui_notification_test"))
        assertEquals("As notificações estão funcionando.", text("values", "ui_notification_test_ok"))
        assertEquals("Notifications are working.", text("values-en", "ui_notification_test_ok"))
        assertEquals("Las notificaciones funcionan.", text("values-es", "ui_notification_test_ok"))
    }

    @Test fun alertAndOutageCountsHaveSingularAndPluralInAllLocales() {
        val singular = listOf("1 queda", "1 outage", "1 interrupción")
        val plural = listOf("2 quedas", "2 outages", "2 interrupciones")
        folders.forEachIndexed { i, folder ->
            assertEquals(singular[i], quantity(folder, "ui_outages", 1))
            assertEquals(plural[i], quantity(folder, "ui_outages", 2))
            assertTrue(quantity(folder, "ui_dish_alerts", 1).contains("1"))
            assertTrue(quantity(folder, "ui_dish_alerts", 2).contains("2"))
        }
        assertEquals("1 dish warning", quantity("values-en", "rich_terminal_warnings", 1))
        assertEquals("2 dish warnings", quantity("values-en", "rich_terminal_warnings", 2))
    }

    @Test fun chartWindowsAndExtremeHistoryPreserveCountsAndMetrics() {
        assertEquals("1 hora", quantity("values", "ui_hours", 1))
        assertEquals("1 hour", quantity("values-en", "ui_hours", 1))
        assertEquals("1 hora", quantity("values-es", "ui_hours", 1))
        assertEquals("2 minutes", quantity("values-en", "ui_minutes", 2))
        assertEquals("1 test · average download 12.4 Mbps", quantity("values-en", "ui_extreme_history_summary", 1, "12.4"))
        assertEquals("2 pruebas · descarga media 12,4 Mbps", quantity("values-es", "ui_extreme_history_summary", 2, "12,4"))
        for (folder in folders) assertEquals("2 min 3 s", text(folder, "ui_duration_minutes_seconds", 2, 3))
    }

    @Test fun nativeRatesUseMbpsInEveryLocale() {
        for (folder in folders) {
            assertEquals("123 Mbps", text(folder, "test_rate_value", "123"))
            assertTrue(text(folder, "test_last_round_compact_rates", "↓123 · ↑4").endsWith("Mbps"))
        }
    }

    @Test fun arStatusCanChangeLanguageWithoutChangingItsStoredStateOrArguments() {
        val status = ArUiText(R.string.editorial_gps_search)
        val rendered = folders.map { folder -> status.resolve { id, args -> text(folder, stringNames.getValue(id), *args) } }
        assertEquals(3, rendered.distinct().size)
        val accuracy = ArUiText(R.string.editorial_gps_accuracy, listOf(8))
        folders.forEach { folder ->
            assertTrue(accuracy.resolve { id, args -> text(folder, stringNames.getValue(id), *args) }.contains("8"))
        }
        assertEquals(listOf(8), accuracy.arguments)
        assertEquals(R.string.editorial_gps_accuracy, accuracy.resource)
    }

    @Test fun arCanvasAndErrorFallbackLabelsResolveInTheCurrentLanguage() {
        val error = ArUiText(R.string.ui_ar_camera_start_error)
        assertEquals("Could not initialize the camera.", error.resolve { id, args -> text("values-en", stringNames.getValue(id), *args) })
        assertEquals("No se pudo iniciar la cámara.", error.resolve { id, args -> text("values-es", stringNames.getValue(id), *args) })
        assertEquals("☀️ Sun (12°)", text("values-en", "ui_ar_sun", 12))
        assertEquals("🌙 Luna (12°)", text("values-es", "ui_ar_moon", 12))
        assertEquals("Point at the sky", text("values-en", "ar_point_sky"))
        val diagnostic = error.copy(diagnostic = "CAMERA_DISABLED")
        assertEquals("CAMERA_DISABLED", diagnostic.resolve { _, _ -> throw AssertionError("unexpected fallback") })
    }

    @Test fun missingMetricCompatibilityNeverUsesTranslatedWordsAsSentinels() {
        listOf("—", "--", "-- ms", "— ms", "— W", "—%", " — W ").forEach { assertTrue(infoRowValueMissing(it)) }
        listOf("0", "0 ms", "0 W", "0%", "Não informado", "Not provided", "No disponible").forEach { assertFalse(infoRowValueMissing(it)) }
        val source = File(resources.parentFile, "java/io/github/strongsand/lshell/MainActivity.kt").readText()
        assertTrue(source.contains("missing: Boolean = infoRowValueMissing(value)"))
        assertTrue(source.contains("missing = snapshot.deviceInfo.ethSpeedMbps == null"))
    }

    @Test fun outagePeriodRestoresOldUiValuesAndNewIdentifiersWithoutTranslation() {
        val legacy = mapOf("Hoje" to OutageRange.TODAY, "Ontem" to OutageRange.YESTERDAY,
            "7 dias" to OutageRange.WEEK, "30 dias" to OutageRange.MONTH)
        legacy.forEach { (saved, expected) -> assertEquals(expected, OutageRange.fromSavedValue(saved)) }
        OutageRange.entries.forEach { assertEquals(it, OutageRange.fromSavedValue(it.name)) }
        assertEquals(OutageRange.TODAY, OutageRange.fromSavedValue("future unknown value"))
    }
}
