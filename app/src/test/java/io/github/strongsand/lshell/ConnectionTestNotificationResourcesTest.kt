package io.github.strongsand.lshell

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Static RemoteViews contract, without adding an emulator/Robolectric dependency. */
class ConnectionTestNotificationResourcesTest {
    private val resources = File("src/main/res").takeIf { it.isDirectory } ?: File("app/src/main/res")
    private val android = "http://schemas.android.com/apk/res/android"
    private fun elements(path: String): List<Element> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val nodes = factory.newDocumentBuilder().parse(File(resources, path)).getElementsByTagName("*")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test fun activeTestIconIsAMonochromeVectorForSystemChipTinting() {
        val views = elements("drawable/ic_speed_test_notification.xml")
        assertEquals("vector", views.first().tagName)
        assertEquals("24", views.first().getAttributeNS(android, "viewportWidth"))
        assertTrue(views.filter { it.tagName == "path" }.all {
            it.getAttributeNS(android, "fillColor") == "#FFFFFFFF"
        })
    }

    @Test fun shortLastRoundHasOneTrailingRateUnitAndLocalizedSpeechAlternative() {
        for (folder in listOf("values", "values-en", "values-es")) {
            val strings = elements("$folder/connection_notification_strings.xml")
                .filter { it.tagName == "string" }.associate { it.getAttribute("name") to it.textContent }
            val short = strings.getValue("test_last_round_compact_rates")
            assertTrue(short.endsWith("%1\$s Mbps"))
            assertEquals(1, Regex("Mbps").findAll(short).count())
            assertTrue(strings.getValue("test_rate_accessible").contains("megabits"))
        }
    }

    @Test fun beaconPillHasLocalizedTextForEveryStateAndASpokenSuccessLabel() {
        val translations = listOf("values", "values-en", "values-es").map { folder ->
            elements("$folder/beacon_sync_status_strings.xml").filter { it.tagName == "string" }
                .associate { it.getAttribute("name") to it.textContent }
        }
        for (translation in translations) {
            assertEquals(setOf("beacon_pill_syncing", "beacon_pill_synced", "beacon_pill_sync_error",
                "beacon_pill_synced_accessible"), translation.keys)
            assertTrue(translation.values.all { it.contains("Beacon") && it.isNotBlank() })
        }
    }

    @Test fun layoutsOnlyUseFrameworkViewsSupportedByRemoteViews() {
        for (name in listOf("compact", "expanded")) {
            val views = elements("layout/notification_test_result_$name.xml")
            assertTrue(views.all { it.tagName in setOf("LinearLayout", "TextView", "ImageView") })
            assertEquals("wrap_content", views.first().getAttributeNS(android, "layout_height"))
            assertTrue(views.none { it.hasAttributeNS(android, "onClick") })
            assertTrue(views.none { it.hasAttributeNS(android, "text") })
        }
    }

    @Test fun compactLayoutHasBothBoundIdsAndSingleLineTextToRespectSmallSlot() {
        val views = elements("layout/notification_test_result_compact.xml")
        val text = views.filter { it.tagName == "TextView" }
        assertEquals(setOf("@+id/test_result_title", "@+id/test_result_summary"),
            text.map { it.getAttributeNS(android, "id") }.toSet())
        assertTrue(text.all { it.getAttributeNS(android, "maxLines") == "1" })
        assertTrue(text.all { it.getAttributeNS(android, "ellipsize") == "end" })
    }

    @Test fun expandedBindingIdsAndAccessibleMetricGroupsExistExactlyOnce() {
        val views = elements("layout/notification_test_result_expanded.xml")
        val ids = views.map { it.getAttributeNS(android, "id") }.filter { it.isNotEmpty() }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.containsAll(listOf("@+id/test_result_title", "@+id/test_result_summary", "@+id/test_result_status")))
        for (metric in listOf("down", "up", "latency", "duration")) {
            assertTrue(ids.containsAll(listOf("@+id/test_result_$metric", "@+id/test_result_${metric}_label",
                "@+id/test_result_${metric}_value")))
            val group = views.single { it.getAttributeNS(android, "id") == "@+id/test_result_$metric" }
            assertEquals("true", group.getAttributeNS(android, "screenReaderFocusable"))
        }
        assertTrue(views.filter { it.tagName == "ImageView" }
            .all { it.getAttributeNS(android, "importantForAccessibility") == "no" })
    }

    @Test fun localizedNotificationKeysAndFormatArgumentsMatch() {
        fun strings(folder: String) = elements("$folder/connection_notification_strings.xml")
            .filter { it.tagName == "string" }.associate { it.getAttribute("name") to it.textContent }
        val translations = listOf("values", "values-en", "values-es").map(::strings)
        for (translation in translations.drop(1)) {
            assertEquals(translations.first().keys, translation.keys)
            for ((key, text) in translation) assertEquals(key,
                Regex("%\\d+\\$[ds]").findAll(translations.first().getValue(key)).map { it.value }.toList(),
                Regex("%\\d+\\$[ds]").findAll(text).map { it.value }.toList())
        }
    }

    @Test fun ratesHaveEqualWeightSeparateUnitsAndSecondaryMetricsStayTogether() {
        val views = elements("layout/notification_test_result_expanded.xml")
        fun view(id: String) = views.single { it.getAttributeNS(android, "id") == "@+id/$id" }
        for (metric in listOf("down", "up")) {
            val tile = view("test_result_$metric")
            assertEquals("0dp", tile.getAttributeNS(android, "layout_width"))
            assertEquals("1", tile.getAttributeNS(android, "layout_weight"))
            assertEquals("@style/TestResultMetric", view("test_result_${metric}_value")
                .getAttributeNS(android, "textAppearance"))
            assertEquals("no", view("test_result_${metric}_unit").getAttributeNS(android, "importantForAccessibility"))
        }
        val duration = view("test_result_duration")
        val latency = view("test_result_latency")
        assertSame(duration.parentNode, latency.parentNode)
        assertTrue(views.none { it.getAttributeNS(android, "id").contains("power") })
        assertTrue(views.none { it.getAttributeNS(android, "layout_height") == "match_parent" })
    }

    @Test fun tonalResourcesHaveLightDarkAndDynamicVariantsAndTextUsesSystemAppearances() {
        val variants = listOf("values", "values-night", "values-v31", "values-night-v31")
            .map { folder -> elements("$folder/test_notification_colors.xml").filter { it.tagName == "color" } }
        val names = variants.first().map { it.getAttribute("name") }.toSet()
        assertTrue(variants.all { colors -> colors.map { it.getAttribute("name") }.toSet() == names })
        val styles = elements("values/test_notification_styles.xml").filter { it.tagName == "style" }
        assertTrue(styles.all { it.getAttribute("parent").startsWith("TextAppearance.Compat.Notification.") })
        assertTrue(elements("values/test_notification_styles.xml").none { it.getAttribute("name") == "android:textColor" })
    }

    @Test fun narrowHostKeepsRateBaselinesAlignedAndSecondaryValuesCanWrap() {
        val views = elements("layout/notification_test_result_expanded.xml")
        fun view(id: String) = views.single { it.getAttributeNS(android, "id") == "@+id/$id" }
        for (metric in listOf("down", "up")) {
            val label = view("test_result_${metric}_label")
            assertEquals("2", label.getAttributeNS(android, "minLines"))
            assertEquals("2", label.getAttributeNS(android, "maxLines"))
        }
        val secondary = view("test_result_duration").parentNode as Element
        assertEquals("vertical", secondary.getAttributeNS(android, "orientation"))
        for (metric in listOf("duration", "latency")) {
            val row = view("test_result_$metric")
            assertEquals("match_parent", row.getAttributeNS(android, "layout_width"))
            assertEquals("horizontal", row.getAttributeNS(android, "orientation"))
            val value = view("test_result_${metric}_value")
            assertEquals("2", value.getAttributeNS(android, "maxLines"))
            assertEquals("end", value.getAttributeNS(android, "gravity"))
            assertFalse(value.hasAttributeNS(android, "ellipsize"))
        }
    }
}
