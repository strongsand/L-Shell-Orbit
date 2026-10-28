package io.github.strongsand.lshell

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import kotlin.math.pow

class WidgetResourceContractTest {
    private val res = File("src/main/res").takeIf { it.exists() } ?: File("app/src/main/res")
    private fun xml(path: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(File(res, path)).documentElement
    private fun Element.android(name: String) = getAttribute("android:$name")

    @Test fun existingProvidersAreResizableWithPreviewDescriptionAndNoPeriodicPolling() {
        listOf("obstruction", "report", "beacon").forEach { kind ->
            val provider = xml("xml/widget_$kind.xml")
            assertEquals("0", provider.android("updatePeriodMillis"))
            assertEquals("horizontal|vertical", provider.android("resizeMode"))
            assertEquals("144dp", provider.android("minResizeWidth"))
            assertEquals("96dp", provider.android("minResizeHeight"))
            assertEquals("3", provider.android("targetCellWidth"))
            assertEquals("2", provider.android("targetCellHeight"))
            assertEquals("260dp", provider.android("minWidth"))
            assertTrue(provider.android("maxResizeWidth").isNotBlank())
            assertEquals("@string/widget_${kind}_description", provider.android("description"))
            assertEquals("@layout/widget_${kind}_preview", provider.android("previewLayout"))
            assertEquals("@drawable/widget_${kind}_preview", provider.android("previewImage"))
            assertTrue(File(res, "layout/widget_${kind}_preview.xml").isFile)
            assertTrue(File(res, "drawable-nodpi/widget_${kind}_preview.png").length() in 1..100_000)
            assertEquals("@layout/widget_loading", provider.android("initialLayout"))
        }
    }

    @Test fun widgetStringsHaveEnglishSpanishParityWithoutBrokenEncoding() {
        fun keys(folder: String): Set<String> {
            val nodes = xml("$folder/widget_strings.xml").getElementsByTagName("string")
            return (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("name") }.toSet()
        }
        val english = keys("values-en")
        assertEquals(english, keys("values-es"))
        assertTrue(keys("values").containsAll(english))
        listOf("values", "values-en", "values-es").forEach {
            val text = File(res, "$it/widget_strings.xml").readText()
            assertFalse(text.contains("Â"))
            assertFalse(text.contains("Ã"))
            assertFalse(text.contains("â€"))
        }
    }

    @Test fun systemOuterCornersAndStaticColorPairsRespectAccessibility() {
        val nodes = xml("values-v31/widget_dimens.xml").getElementsByTagName("dimen")
        val radius = (0 until nodes.length).map { nodes.item(it) as Element }
            .single { it.getAttribute("name") == "widget_outer_radius" }
        assertEquals("@android:dimen/system_app_widget_background_radius", radius.textContent)
        fun colors(folder: String): Map<String, Int> {
            val colors = xml("$folder/widget_colors.xml").getElementsByTagName("color")
            return (0 until colors.length).map { colors.item(it) as Element }
                .associate { it.getAttribute("name") to it.textContent.removePrefix("#").toInt(16) }
        }
        for (folder in listOf("values", "values-night")) {
            val palette = colors(folder)
            for ((fg, bg) in listOf("widget_text" to "widget_background", "widget_muted" to "widget_background",
                "widget_on_primary" to "widget_primary", "widget_accent" to "widget_background")) {
                val a = luminance(palette.getValue(fg)); val b = luminance(palette.getValue(bg))
                assertTrue("$folder $fg/$bg", (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05) >= 4.5)
            }
        }
    }

    private fun luminance(rgb: Int): Double {
        fun linear(c: Int): Double = (c / 255.0).let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return linear(rgb shr 16 and 255) * .2126 + linear(rgb shr 8 and 255) * .7152 + linear(rgb and 255) * .0722
    }
}
