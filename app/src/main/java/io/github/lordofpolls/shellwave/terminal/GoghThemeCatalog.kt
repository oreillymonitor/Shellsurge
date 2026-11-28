package io.github.lordofpolls.shellwave.terminal

import android.content.Context
import io.github.lordofpolls.shellwave.core.db.entities.ColorSchemeEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

private const val GOGH_ASSET_PATH = "gogh_themes.json"
private const val GOGH_ANSI_COUNT = 16

@Volatile
private var cachedGoghThemes: List<ColorSchemeEntity>? = null

/**
 * Parses the compact offline Gogh catalog (`app/src/main/assets/gogh_themes.json`) into
 * [ColorSchemeEntity] instances. Pure JVM-testable with zero `android.graphics.Color` dependency.
 */
internal fun parseGoghCatalogJson(jsonText: String): List<ColorSchemeEntity> = runCatching {
    val array = JSONArray(jsonText)
    val schemes = ArrayList<ColorSchemeEntity>(array.length())
    for (i in 0 until array.length()) {
        val obj = array.optJSONObject(i) ?: continue
        val name = obj.optString("n", "").trim()
        if (name.isEmpty()) continue

        val background = normalizeHexColor(obj.optString("b", "")) ?: continue
        val foreground = normalizeHexColor(obj.optString("f", "")) ?: continue
        val ansiArr = obj.optJSONArray("a") ?: continue
        if (ansiArr.length() != GOGH_ANSI_COUNT) continue

        val ansiInts = IntArray(GOGH_ANSI_COUNT)
        var validAnsi = true
        for (slot in 0 until GOGH_ANSI_COUNT) {
            val parsed = normalizeHexColor(ansiArr.optString(slot, ""))
            if (parsed == null) {
                validAnsi = false
                break
            }
            ansiInts[slot] = parsed
        }
        if (!validAnsi) continue

        val cursor = normalizeHexColor(obj.optString("c", ""))
            ?: if (isLightBackground(background)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        val selection = blendColors(background, foreground, 0.30f)

        schemes.add(
            ColorSchemeEntity(
                id = 0,
                name = name.take(48),
                isBuiltIn = false,
                background = background,
                foreground = foreground,
                cursor = cursor,
                selection = selection,
                ansiColorsCsv = ansiInts.joinToString(",") { it.toHexColorString() },
            ),
        )
    }
    schemes
}.getOrDefault(emptyList())

/**
 * Loads and caches the bundled offline Gogh theme library on [Dispatchers.IO].
 */
suspend fun loadGoghThemes(context: Context): List<ColorSchemeEntity> {
    cachedGoghThemes?.let { return it }
    return withContext(Dispatchers.IO) {
        cachedGoghThemes?.let { return@withContext it }
        val loaded = runCatching {
            val json = context.assets.open(GOGH_ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseGoghCatalogJson(json)
        }.getOrDefault(emptyList())
        if (loaded.isNotEmpty()) {
            cachedGoghThemes = loaded
        }
        loaded
    }
}
