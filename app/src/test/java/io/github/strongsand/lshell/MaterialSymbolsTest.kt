package io.github.strongsand.lshell

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class MaterialSymbolsTest {
    private val root = File("..").takeIf { File(it, "tools/material-symbols.json").isFile } ?: File(".")

    @Test fun vectorsAreCachedAndDirectionalMirroringIsPreserved() {
        assertSame(OrbitSymbols.Download, OrbitSymbols.Download)
        assertSame(OrbitSymbols.Home, OrbitSymbols.Home)
        assertEquals("MaterialSymbols.Rounded.download", OrbitSymbols.Download.name)
        assertTrue(OrbitSymbols.ArrowBack.autoMirror)
        assertTrue(OrbitSymbols.OpenInNew.autoMirror)
        assertFalse(OrbitSymbols.Download.autoMirror)
        assertEquals(24f, OrbitSymbols.Download.defaultWidth.value)
    }

    @Test fun everyCatalogEntryBuildsWithoutLoadingAndroidResources() {
        val manifest = JSONObject(File(root, "tools/material-symbols.json").readText())
        val aliases = manifest.getJSONObject("symbols")
        for (name in aliases.keys()) {
            val getter = OrbitSymbols::class.java.getMethod("get$name")
            val vector = getter.invoke(OrbitSymbols) as androidx.compose.ui.graphics.vector.ImageVector
            assertTrue(name, vector.root.size > 0)
            assertEquals(name, 24f, vector.defaultWidth.value)
            assertSame(vector, getter.invoke(OrbitSymbols))
        }
    }

    @Test fun remoteViewsAndWidgetAliasesExistAndLicenseShipsWithAssets() {
        val manifest = JSONObject(File(root, "tools/material-symbols.json").readText())
        val aliases = manifest.getJSONObject("drawableAliases")
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        for (name in aliases.keys()) {
            val vector = factory.newDocumentBuilder().parse(File(root, "app/src/main/res/drawable/$name.xml"))
            assertEquals("vector", vector.documentElement.tagName)
            assertTrue(name, vector.getElementsByTagName("path").length > 0)
        }
        val licenses = File(root, "app/src/main/assets/licenses")
        assertTrue(File(licenses, "material-symbols-LICENSE.txt").readText().contains("Version 2.0, January 2004"))
        assertTrue(File(licenses, "material-symbols-NOTICE.txt").readText().contains(manifest.getString("revision")))
    }
}
