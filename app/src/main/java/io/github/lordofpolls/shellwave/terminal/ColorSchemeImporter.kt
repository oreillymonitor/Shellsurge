package io.github.lordofpolls.shellwave.terminal

import io.github.lordofpolls.shellwave.core.db.entities.ColorSchemeEntity
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt

private const val ANSI_SLOT_COUNT = 16
private const val IMPORT_ERROR_MESSAGE =
    "Could not recognize a terminal color scheme. Paste or open a Ghostty, Alacritty, iTerm2 (.itermcolors), Warp, Kitty, Termux, Gogh, or Windows Terminal theme."

private val STRIP_EXTENSIONS_REGEX =
    Regex("""\.(itermcolors|toml|yaml|yml|json|properties|conf|txt|xresources|xrdb)$""", RegexOption.IGNORE_CASE)

private val ANSI_COLOR_KEY_MAP: Map<String, Int> = mapOf(
    "black" to 0,
    "red" to 1,
    "green" to 2,
    "yellow" to 3,
    "blue" to 4,
    "magenta" to 5,
    "purple" to 5,
    "cyan" to 6,
    "white" to 7,
)

private val BRIGHT_COLOR_KEY_MAP: Map<String, Int> = mapOf(
    "brightblack" to 8,
    "bright_black" to 8,
    "brightred" to 9,
    "bright_red" to 9,
    "brightgreen" to 10,
    "bright_green" to 10,
    "brightyellow" to 11,
    "bright_yellow" to 11,
    "brightblue" to 12,
    "bright_blue" to 12,
    "brightmagenta" to 13,
    "bright_magenta" to 13,
    "brightpurple" to 13,
    "bright_purple" to 13,
    "brightcyan" to 14,
    "bright_cyan" to 14,
    "brightwhite" to 15,
    "bright_white" to 15,
)

/**
 * Normalizes `#RRGGBB`, `0xRRGGBB`, bare 6-char hex `RRGGBB`, and `#RGB` into an opaque ARGB int,
 * stripping surrounding quotes and inline `# comments` preceded by whitespace.
 */
fun normalizeHexColor(token: String): Int? {
    val withoutInlineComment = token.replace(Regex("""\s+#.*$"""), "").trim()
    val unquoted = withoutInlineComment
        .removePrefix("\"")
        .removeSuffix("\"")
        .removePrefix("'")
        .removeSuffix("'")
        .trim()
    if (unquoted.isEmpty()) return null

    val hex = when {
        unquoted.startsWith("#") -> unquoted.substring(1)
        unquoted.startsWith("0x", ignoreCase = true) -> unquoted.substring(2)
        else -> unquoted
    }

    val expanded = when {
        hex.length == 6 && hex.all { it.isHexDigit() } -> hex
        hex.length == 3 && hex.all { it.isHexDigit() } && unquoted.startsWith("#") -> {
            buildString(6) {
                for (ch in hex) {
                    append(ch)
                    append(ch)
                }
            }
        }
        else -> return null
    }
    return 0xFF000000.toInt() or expanded.toInt(16)
}

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

/**
 * Parses and imports terminal color themes exported from Ghostty, Alacritty (TOML or YAML),
 * Warp YAML, iTerm2 (`.itermcolors`), Kitty, Termux `.properties`, Gogh (YAML or JSON),
 * Xresources, or Windows Terminal JSON. Pure JVM-testable with zero `android.graphics.Color`
 * dependency.
 */
fun importColorScheme(rawText: String, fallbackName: String? = null): Result<ColorSchemeEntity> {
    val trimmed = rawText.trim()
    if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException(IMPORT_ERROR_MESSAGE))

    val parsed = when {
        trimmed.contains("<plist", ignoreCase = true) || trimmed.contains("Ansi 0 Color") ->
            parseItermColors(trimmed)
        trimmed.startsWith("{") || (trimmed.startsWith("[") && Regex("""^\[\s*\{""").containsMatchIn(trimmed)) ->
            parseJsonTheme(trimmed)
        else ->
            parseKeyValueTheme(trimmed)
    } ?: return Result.failure(IllegalArgumentException(IMPORT_ERROR_MESSAGE))

    val background = parsed.background
    val foreground = parsed.foreground
    if (background == null || foreground == null || parsed.ansiCount < 8) {
        return Result.failure(IllegalArgumentException(IMPORT_ERROR_MESSAGE))
    }

    val resolvedAnsi = parsed.ansi.copyOf()
    val defaultAnsi = DEFAULT_COLOR_SCHEME.ansiColors()
    for (i in 0 until 8) {
        if (resolvedAnsi[i] == null) {
            resolvedAnsi[i] = defaultAnsi[i]
        }
    }
    for (i in 8 until ANSI_SLOT_COUNT) {
        if (resolvedAnsi[i] == null) {
            resolvedAnsi[i] = resolvedAnsi[i - 8] ?: defaultAnsi[i]
        }
    }

    val ansiInts = IntArray(ANSI_SLOT_COUNT) { idx -> resolvedAnsi[idx] ?: defaultAnsi[idx] }
    val cursor = parsed.cursor ?: if (isLightBackground(background)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    val selection = parsed.selection ?: blendColors(background, foreground, 0.30f)

    val cleanedFallback = fallbackName
        ?.trim()
        ?.let { STRIP_EXTENSIONS_REGEX.replace(it, "").trim() }
        ?.takeIf { it.isNotEmpty() }

    val schemeName = parsed.name
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: cleanedFallback
        ?: "Imported"

    return Result.success(
        ColorSchemeEntity(
            id = 0,
            name = schemeName.take(48),
            isBuiltIn = false,
            background = background,
            foreground = foreground,
            cursor = cursor,
            selection = selection,
            ansiColorsCsv = ansiInts.joinToString(",") { it.toHexColorString() },
        ),
    )
}

private class MutableParsedScheme(
    var name: String? = null,
    var background: Int? = null,
    var foreground: Int? = null,
    var cursor: Int? = null,
    var selection: Int? = null,
    val ansi: Array<Int?> = arrayOfNulls(ANSI_SLOT_COUNT),
    val numberedColors: MutableMap<Int, Int> = mutableMapOf(),
    var hasLeadingZeroUnderscore: Boolean = false,
) {
    val ansiCount: Int get() = ansi.count { it != null }
}

private fun parseItermColors(xml: String): MutableParsedScheme? = runCatching {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isValidating = false
        isExpandEntityReferences = false
        runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }
    val builder = factory.newDocumentBuilder().apply {
        setEntityResolver { _, _ -> InputSource(StringReader("")) }
    }
    val doc = builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    val dicts = doc.getElementsByTagName("dict")
    if (dicts.length == 0) return null
    val rootDict = dicts.item(0) as? Element ?: return null
    val result = MutableParsedScheme()

    val children = rootDict.childNodes
    var currentKey: String? = null
    for (i in 0 until children.length) {
        val node = children.item(i) as? Element ?: continue
        when (node.tagName) {
            "key" -> currentKey = node.textContent.trim()
            "dict" -> {
                val key = currentKey ?: continue
                currentKey = null
                val argb = parseItermRgbDict(node) ?: continue
                when {
                    key.equals("Background Color", ignoreCase = true) -> result.background = argb
                    key.equals("Foreground Color", ignoreCase = true) -> result.foreground = argb
                    key.equals("Cursor Color", ignoreCase = true) -> result.cursor = argb
                    key.equals("Selection Color", ignoreCase = true) -> result.selection = argb
                    key.startsWith("Ansi ", ignoreCase = true) -> {
                        val idx = Regex("""Ansi\s+(\d+)\s+Color""", RegexOption.IGNORE_CASE)
                            .find(key)
                            ?.groupValues
                            ?.get(1)
                            ?.toIntOrNull()
                        if (idx != null && idx in 0 until ANSI_SLOT_COUNT) {
                            result.ansi[idx] = argb
                        }
                    }
                }
            }
            else -> currentKey = null
        }
    }
    result
}.getOrNull()

private fun parseItermRgbDict(dictElement: Element): Int? {
    var r: Double? = null
    var g: Double? = null
    var b: Double? = null
    val children = dictElement.childNodes
    var currentKey: String? = null
    for (i in 0 until children.length) {
        val node = children.item(i) as? Element ?: continue
        when (node.tagName) {
            "key" -> currentKey = node.textContent.trim()
            "real", "integer" -> {
                val value = node.textContent.trim().toDoubleOrNull()
                when (currentKey) {
                    "Red Component" -> r = value
                    "Green Component" -> g = value
                    "Blue Component" -> b = value
                }
                currentKey = null
            }
            else -> currentKey = null
        }
    }
    if (r == null || g == null || b == null) return null
    val ri = (r.coerceIn(0.0, 1.0) * 255.0).roundToInt().coerceIn(0, 255)
    val gi = (g.coerceIn(0.0, 1.0) * 255.0).roundToInt().coerceIn(0, 255)
    val bi = (b.coerceIn(0.0, 1.0) * 255.0).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
}

private fun parseJsonTheme(jsonText: String): MutableParsedScheme? = runCatching {
    val obj: JSONObject = if (jsonText.startsWith("[")) {
        val arr = JSONArray(jsonText)
        if (arr.length() == 0) return null
        arr.getJSONObject(0)
    } else {
        JSONObject(jsonText)
    }
    val result = MutableParsedScheme(
        name = obj.optString("name", "").trim().takeIf { it.isNotEmpty() },
        background = normalizeHexColor(obj.optString("background", "")),
        foreground = normalizeHexColor(obj.optString("foreground", "")),
        cursor = normalizeHexColor(obj.optString("cursorColor", ""))
            ?: normalizeHexColor(obj.optString("cursor", "")),
        selection = normalizeHexColor(obj.optString("selectionBackground", "")),
    )
    ANSI_COLOR_KEY_MAP.forEach { (key, slot) ->
        normalizeHexColor(obj.optString(key, ""))?.let { result.ansi[slot] = it }
    }
    BRIGHT_COLOR_KEY_MAP.forEach { (key, slot) ->
        normalizeHexColor(obj.optString(key, ""))?.let { result.ansi[slot] = it }
    }
    for (i in 1..ANSI_SLOT_COUNT) {
        val goghKey = "color_${i.toString().padStart(2, '0')}"
        normalizeHexColor(obj.optString(goghKey, ""))?.let { result.ansi[i - 1] = it }
    }
    result
}.getOrNull()

private fun parseKeyValueTheme(rawText: String): MutableParsedScheme {
    val result = MutableParsedScheme()
    var sectionPath = emptyList<String>()
    val yamlSections = ArrayDeque<Pair<Int, String>>()

    for (rawLine in rawText.lines()) {
        val lineNoLeadCr = rawLine.trimEnd('\r')
        val trimmed = lineNoLeadCr.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) continue

        // TOML section header: [colors.normal], [colors.bright], [colors.primary], etc.
        if (trimmed.startsWith("[") && trimmed.endsWith("]") && !trimmed.contains("=")) {
            yamlSections.clear()
            sectionPath = trimmed
                .removePrefix("[")
                .removeSuffix("]")
                .trim()
                .lowercase()
                .split(".")
            continue
        }

        // Handle YAML indentation stack when line uses `:` (and not Xresources `*.color0:`)
        val indent = lineNoLeadCr.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)

        // Ghostty `palette = 0=#RRGGBB` (or `palette = 0=RRGGBB`)
        val eqIdx = trimmed.indexOf('=')
        val colonIdx = trimmed.indexOf(':')

        if (eqIdx > 0) {
            val key = trimmed.substring(0, eqIdx).trim()
            val value = trimmed.substring(eqIdx + 1).trim()
            if (key.equals("palette", ignoreCase = true)) {
                val innerEq = value.indexOf('=')
                if (innerEq > 0) {
                    val slot = value.substring(0, innerEq).trim().toIntOrNull()
                    val color = normalizeHexColor(value.substring(innerEq + 1))
                    if (slot != null && slot in 0 until ANSI_SLOT_COUNT && color != null) {
                        result.ansi[slot] = color
                    }
                }
                continue
            }
            assignKeyValue(result, sectionPath, key, value)
            continue
        }

        if (colonIdx > 0) {
            val rawKey = trimmed.substring(0, colonIdx).trim()
            val valuePart = trimmed.substring(colonIdx + 1).trim()

            // YAML nested block opener e.g. `colors:` / `  normal:` / `  bright:`
            if (valuePart.isEmpty() || valuePart.startsWith("#")) {
                while (yamlSections.isNotEmpty() && yamlSections.last().first >= indent) {
                    yamlSections.removeLast()
                }
                yamlSections.addLast(indent to rawKey.lowercase())
                sectionPath = yamlSections.map { it.second }
                continue
            }

            while (yamlSections.isNotEmpty() && yamlSections.last().first >= indent) {
                yamlSections.removeLast()
            }
            val activePath = yamlSections.map { it.second }
            val cleanKey = rawKey.removePrefix("*.").removePrefix(".")
            assignKeyValue(result, activePath, cleanKey, valuePart)
            continue
        }

        // Kitty / whitespace-separated `key #HEX`
        val wsParts = trimmed.split(Regex("""\s+"""), limit = 2)
        if (wsParts.size == 2) {
            assignKeyValue(result, sectionPath, wsParts[0], wsParts[1])
        }
    }

    if (result.numberedColors.isNotEmpty()) {
        val isOneIndexed = !result.numberedColors.containsKey(0) &&
            (result.numberedColors.containsKey(ANSI_SLOT_COUNT) || result.hasLeadingZeroUnderscore)
        result.numberedColors.forEach { (idx, color) ->
            val slot = if (isOneIndexed) idx - 1 else idx
            if (slot in 0 until ANSI_SLOT_COUNT) {
                result.ansi[slot] = color
            }
        }
    }

    return result
}

private fun assignKeyValue(
    result: MutableParsedScheme,
    sectionPath: List<String>,
    rawKey: String,
    rawValue: String,
) {
    val key = rawKey.trim().removePrefix("\"").removeSuffix("\"").lowercase()
    val leafSection = sectionPath.lastOrNull()

    if (key == "name") {
        val cleanedName = rawValue.replace(Regex("""\s+#.*$"""), "").trim()
            .removePrefix("\"").removeSuffix("\"")
            .removePrefix("'").removeSuffix("'")
            .trim()
        if (cleanedName.isNotEmpty() && result.name == null) {
            result.name = cleanedName
        }
        return
    }

    val color = normalizeHexColor(rawValue) ?: return

    // Numbered ANSI keys (`color0`..`color15` or Gogh 1-indexed `color_01`..`color_16`)
    val colorIndexMatch = Regex("""^color_?(\d{1,2})$""").matchEntire(key)
    if (colorIndexMatch != null) {
        val digits = colorIndexMatch.groupValues[1]
        val idx = digits.toIntOrNull()
        if (idx != null && idx in 0..ANSI_SLOT_COUNT) {
            result.numberedColors[idx] = color
            if (key.startsWith("color_") && digits.length == 2 && idx in 1..9) {
                result.hasLeadingZeroUnderscore = true
            }
        }
        return
    }

    // Named ANSI colors inside normal / bright sections (Alacritty / Warp)
    if (leafSection == "bright") {
        ANSI_COLOR_KEY_MAP[key]?.let { baseIdx ->
            result.ansi[baseIdx + 8] = color
            return
        }
    }
    if (leafSection == "normal") {
        ANSI_COLOR_KEY_MAP[key]?.let { baseIdx ->
            result.ansi[baseIdx] = color
            return
        }
    }
    BRIGHT_COLOR_KEY_MAP[key]?.let { brightIdx ->
        result.ansi[brightIdx] = color
        return
    }

    when (key) {
        "background" -> {
            if (leafSection == "selection") {
                result.selection = color
            } else if (leafSection == "cursor") {
                if (result.cursor == null) result.cursor = color
            } else {
                result.background = color
            }
        }
        "foreground" -> {
            if (leafSection != "selection" && leafSection != "cursor") {
                result.foreground = color
            }
        }
        "cursor", "cursor-color", "cursor_color", "cursorcolor" -> {
            result.cursor = color
        }
        "accent" -> {
            if (result.cursor == null) result.cursor = color
        }
        "selection", "selection-background", "selection_background", "selectionbackground" -> {
            result.selection = color
        }
        "text" -> {
            // Alacritty `[colors.cursor]` sometimes uses `cursor = ...`, ignore `text` except nothing needed.
        }
    }
}

internal fun blendColors(bg: Int, fg: Int, fgRatio: Float): Int {
    val bgRatio = 1f - fgRatio
    val r = (((bg shr 16) and 0xFF) * bgRatio + ((fg shr 16) and 0xFF) * fgRatio).roundToInt().coerceIn(0, 255)
    val g = (((bg shr 8) and 0xFF) * bgRatio + ((fg shr 8) and 0xFF) * fgRatio).roundToInt().coerceIn(0, 255)
    val b = ((bg and 0xFF) * bgRatio + (fg and 0xFF) * fgRatio).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
