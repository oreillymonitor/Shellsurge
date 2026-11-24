package io.github.lordofpolls.shellwave.terminal

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.WcWidth
import java.util.regex.Pattern

/**
 * The vendored engine has no linkify logic, and `termux-shared`'s `TermuxUrlUtils` was never
 * vendored and only covers `scheme://` URLs anyway, so all three patterns here are written fresh.
 */
enum class LinkType { URL, IP, PATH }

data class TerminalLink(
    val type: LinkType,
    val text: String,
    val startCol: Int,
    val endCol: Int,
    val row: Int,
    val lineNumber: Int? = null,
    val sourceLine: String? = null,
)

// A practical subset in place of a ~30-scheme table: a match only ever goes to Intent.ACTION_VIEW,
// so any RFC 3986-shaped scheme is enough to be worth offering "Open" on.
private val URL_PATTERN: Pattern = Pattern.compile("""[a-zA-Z][a-zA-Z0-9+.-]*://[^\s<>"'`]+""")

// Loose: octets aren't range-checked. A false positive makes a non-address tappable, which Copy or
// a failed Open recovers from; a missed real IP is worse for a feature that exists to tap the IP
// you are looking at.
private val IP_PATTERN: Pattern =
    Pattern.compile("""\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}(?::\d{1,5})?\b""")

// Bare absolute paths, optionally followed by :line or :line:col from compiler diagnostics.
// The lookbehind keeps it from firing mid-URL, leaving the "/foo" in "http://host/foo" to URL_PATTERN.
private val PATH_PATTERN: Pattern = Pattern.compile("""(?<![\w/])(/[\w.\-_/]+)(?::(\d+)(?::\d+)?)?""")

// Relative project file paths with common source/config extensions and optional :line(:col),
// e.g. app/src/main/Foo.kt:142:9 or src/index.ts:18. Requiring a slash avoids false matches on
// dotted prose words like "e.g." or "foo.bar".
private val RELATIVE_CODE_PATH_PATTERN: Pattern = Pattern.compile(
    """(?<![\w/.-])((?:[\w.-]+/)+[\w.-]+\.(?:kt|kts|java|py|ts|tsx|js|jsx|rs|go|c|cc|cpp|h|hpp|rb|php|cs|swift|sh|yaml|yml|json|toml|xml|md|gradle|sql|css|scss|html))(?::(\d+)(?::\d+)?)?"""
)

/**
 * Pure row-text extractor for linkified URLs, IP addresses, and file paths. Split out so JVM tests
 * can exercise column mapping and path detection without constructing a full [TerminalEmulator].
 */
internal fun findLinksInLine(line: String, row: Int): List<TerminalLink> {
    if (line.isBlank()) return emptyList()

    val columnAt = IntArray(line.length + 1)
    var column = 0
    var i = 0
    while (i < line.length) {
        columnAt[i] = column
        val ch1 = line[i]
        val width =
            if (Character.isHighSurrogate(ch1) && i + 1 < line.length) {
                val codePoint = Character.toCodePoint(ch1, line[i + 1])
                columnAt[i + 1] = column // the low surrogate shares its high surrogate's column
                i++
                WcWidth.width(codePoint)
            } else {
                WcWidth.width(ch1.code)
            }
        column += width.coerceAtLeast(0)
        i++
    }
    columnAt[line.length] = column

    val trimmedLine = line.trim()
    val results = mutableListOf<TerminalLink>()
    val occupiedCharRanges = mutableListOf<IntRange>()

    fun overlapsOccupied(startChar: Int, endChar: Int): Boolean =
        occupiedCharRanges.any { range -> startChar <= range.last && endChar >= range.first }

    fun collect(
        pattern: Pattern,
        type: LinkType,
        pathGroup: Int = 0,
        lineGroup: Int? = null,
        skipOverlapping: Boolean = false,
    ) {
        val matcher = pattern.matcher(line)
        while (matcher.find()) {
            val matchStart = matcher.start()
            val matchEnd = matcher.end()
            if (skipOverlapping && overlapsOccupied(matchStart, matchEnd - 1)) continue
            val textGroupStart = matcher.start(pathGroup)
            val textGroupEnd = matcher.end(pathGroup)
            if (textGroupStart < 0 || textGroupEnd <= textGroupStart) continue
            val startCol = columnAt[textGroupStart]
            // Make optional :line suffix part of the tappable column range too so tapping the ":42" hits the path.
            val endCol = columnAt[matchEnd] - 1
            if (endCol >= startCol) {
                val lineNo = lineGroup?.let { grp -> matcher.group(grp)?.toIntOrNull() }
                results += TerminalLink(
                    type = type,
                    text = matcher.group(pathGroup),
                    startCol = startCol,
                    endCol = endCol,
                    row = row,
                    lineNumber = lineNo,
                    sourceLine = if (type == LinkType.PATH) trimmedLine else null,
                )
                occupiedCharRanges += matchStart until matchEnd
            }
        }
    }

    // URL first. A URL's own path segment also satisfies PATH_PATTERN, and linkAt takes the first
    // match containing the tapped column, so ordering picks the more specific classification.
    collect(URL_PATTERN, LinkType.URL)
    collect(IP_PATTERN, LinkType.IP)
    collect(PATH_PATTERN, LinkType.PATH, pathGroup = 1, lineGroup = 2)
    collect(
        RELATIVE_CODE_PATH_PATTERN,
        LinkType.PATH,
        pathGroup = 1,
        lineGroup = 2,
        skipOverlapping = true,
    )
    return results
}

/**
 * Built once per row and shared across all patterns rather than probed per-tap: the
 * char-index to column table is the expensive part; the regex is cheap.
 */
fun findLinksInRow(emulator: TerminalEmulator, row: Int): List<TerminalLink> {
    val lastCol = emulator.mColumns - 1
    val line = emulator.getSelectedText(0, row, lastCol, row)
    return findLinksInLine(line, row)
}

fun linkAt(emulator: TerminalEmulator, row: Int, col: Int): TerminalLink? =
    findLinksInRow(emulator, row).firstOrNull { col in it.startCol..it.endCol }
