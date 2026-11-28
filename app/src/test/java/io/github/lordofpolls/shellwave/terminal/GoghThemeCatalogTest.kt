package io.github.lordofpolls.shellwave.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GoghThemeCatalogTest {

    @Test
    fun `bundled gogh themes asset parses into over 1000 valid themes`() {
        val assetFile = listOf(
            File("src/main/assets/gogh_themes.json"),
            File("app/src/main/assets/gogh_themes.json"),
        ).firstOrNull { it.exists() }
            ?: error("Could not locate app/src/main/assets/gogh_themes.json")

        val themes = parseGoghCatalogJson(assetFile.readText(Charsets.UTF_8))
        assertTrue("Expected at least 1000 themes, got ${themes.size}", themes.size >= 1000)

        themes.forEach { scheme ->
            assertTrue(scheme.name.isNotBlank())
            assertEquals(16, scheme.ansiColors().size)
        }

        assertTrue("Expected both dark and light themes", themes.any { !isLightBackground(it.background) })
        assertTrue("Expected both dark and light themes", themes.any { isLightBackground(it.background) })
        assertTrue("Expected Dracula in Gogh catalog", themes.any { it.name.equals("Dracula", ignoreCase = true) })
    }
}
