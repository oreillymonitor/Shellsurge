package io.github.lordofpolls.shellwave.terminal

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.WcWidth
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.min

/**
 * The vendored engine has no linkify logic, and `termux-shared`'s `TermuxUrlUtils` was never
 * vendored and only covers `scheme://` URLs anyway, so all patterns and multi-line stitching here
 * are written fresh.
 */
enum class LinkType { URL, IP, PATH }

data class LinkRowSpan(
    val row: Int,
    val startCol: Int,
    val endCol: Int,
)

data class TerminalRowSnapshot(
    val externalRow: Int,
    val text: String,
    val wrappedToNext: Boolean,
    val columns: Int = text.length,
)

data class LocalhostCallbackInfo(
    val port: Int,
    val host: String,
    val isOAuthRedirect: Boolean,
    val redirectUri: String? = null,
)

data class TerminalLink(
    val type: LinkType,
    val text: String,
    val startCol: Int,
    val endCol: Int,
    val row: Int,
    val lineNumber: Int? = null,
    val sourceLine: String? = null,
    val displayLabel: String? = null,
    val spans: List<LinkRowSpan> = listOf(LinkRowSpan(row, startCol, endCol)),
)

// Excludes whitespace, quotes/brackets that wrap URLs, and Unicode box-drawing / block glyphs
// (`\u2500-\u259F`) used by TUI frames.
private val URL_PATTERN: Pattern =
    Pattern.compile("""[a-zA-Z][a-zA-Z0-9+.-]*://[^\s<>"'`\u2500-\u259F]+""")

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

private val LEADING_BOX_BORDERS = charArrayOf('│', '┃', '║', '▏', '▎', '▍', '▌')
private val TRAILING_BOX_BORDERS = charArrayOf('│', '┃', '║', '▕', '▐')
private val STRONG_URL_CONTINUATION_CHARS = charArrayOf('&', '=', '?', '%', '/', '-', '_', '+', '~', ':')
private val URL_STRUCTURAL_CHARS = charArrayOf('=', '&', '%', '/', '?', '#')
private val PROSE_CONTINUATION_FIRST_WORDS = setOf(
    "and", "or", "then", "into", "in", "to", "from", "with", "on", "at", "for", "the", "a", "an",
    "paste", "enter", "copy", "open", "visit", "click", "press", "follow", "complete", "finish",
)
private val REDIRECT_QUERY_KEYS = setOf("redirect_uri", "redirect_url", "callback", "callback_url", "return_to")
private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")

private const val MAX_HARD_WRAP_CONTINUATION_ROWS = 25
private const val HARD_WRAP_EDGE_TOLERANCE_COLS = 4
private const val MIN_INNER_WIDTH_FOR_EDGE_WRAP = 24

private data class CellCoord(val row: Int, val col: Int)

private data class LogicalLine(
    val text: String,
    val cellCoords: List<CellCoord>,
    val snapshots: List<TerminalRowSnapshot>,
    val rowCharRanges: List<IntRange>,
)

private fun isBoxDrawingOrBlockChar(ch: Char): Boolean = ch in '\u2500'..'\u259F'

/**
 * Strips common trailing sentence punctuation and unbalanced closing brackets from a regex-matched
 * URL while preserving balanced parentheses inside URLs like Wikipedia links.
 */
internal fun trimTrailingUrlPunctuation(raw: String): String {
    var end = raw.length
    while (end > 0) {
        val last = raw[end - 1]
        val shouldStrip = when (last) {
            '.', ',', ';', ':', '!', '?' -> true
            ')' -> countOccurrences(raw, '(', end) < countOccurrences(raw, ')', end)
            ']' -> countOccurrences(raw, '[', end) < countOccurrences(raw, ']', end)
            '}' -> countOccurrences(raw, '{', end) < countOccurrences(raw, '}', end)
            else -> false
        }
        if (shouldStrip) end-- else break
    }
    return if (end == raw.length) raw else raw.substring(0, end)
}

private fun countOccurrences(text: String, target: Char, length: Int): Int {
    var count = 0
    for (i in 0 until length) {
        if (text[i] == target) count++
    }
    return count
}

private fun computeColumnOffsets(line: String): IntArray {
    val columnAt = IntArray(line.length + 1)
    var column = 0
    var i = 0
    while (i < line.length) {
        columnAt[i] = column
        val ch1 = line[i]
        val width =
            if (Character.isHighSurrogate(ch1) && i + 1 < line.length) {
                val codePoint = Character.toCodePoint(ch1, line[i + 1])
                columnAt[i + 1] = column
                i++
                WcWidth.width(codePoint)
            } else {
                WcWidth.width(ch1.code)
            }
        column += width.coerceAtLeast(0)
        i++
    }
    columnAt[line.length] = column
    return columnAt
}

private fun stripBoxFrame(line: String): Pair<Int, Int> {
    var start = 0
    var end = line.length

    var scanLeft = 0
    while (scanLeft < end && line[scanLeft] == ' ') scanLeft++
    if (scanLeft < end && line[scanLeft] in LEADING_BOX_BORDERS) {
        start = scanLeft + 1
        while (start < end && line[start] == ' ') start++
    }

    var scanRight = end - 1
    while (scanRight >= start && line[scanRight] == ' ') scanRight--
    if (scanRight >= start && line[scanRight] in TRAILING_BOX_BORDERS) {
        end = scanRight
        while (end > start && line[end - 1] == ' ') end--
    }
    return start to end
}

private fun buildLogicalLines(snapshots: List<TerminalRowSnapshot>): List<LogicalLine> {
    if (snapshots.isEmpty()) return emptyList()
    val groups = mutableListOf<LogicalLine>()
    var idx = 0
    while (idx < snapshots.size) {
        val textBuilder = StringBuilder()
        val coords = mutableListOf<CellCoord>()
        val groupSnapshots = mutableListOf<TerminalRowSnapshot>()
        val rowRanges = mutableListOf<IntRange>()

        while (idx < snapshots.size) {
            val snap = snapshots[idx]
            val startIdx = textBuilder.length
            val colOffsets = computeColumnOffsets(snap.text)
            textBuilder.append(snap.text)
            for (charIdx in snap.text.indices) {
                coords += CellCoord(row = snap.externalRow, col = colOffsets[charIdx])
            }
            val endIdx = textBuilder.length
            groupSnapshots += snap
            rowRanges += startIdx until endIdx
            idx++
            if (!snap.wrappedToNext) break
        }
        groups += LogicalLine(
            text = textBuilder.toString(),
            cellCoords = coords,
            snapshots = groupSnapshots,
            rowCharRanges = rowRanges,
        )
    }
    return groups
}

private fun spansFromCharRange(
    coords: List<CellCoord>,
    startChar: Int,
    endCharExclusive: Int,
): List<LinkRowSpan> {
    if (startChar < 0 || endCharExclusive <= startChar || endCharExclusive > coords.size) return emptyList()
    val spans = mutableListOf<LinkRowSpan>()
    var currentRow = coords[startChar].row
    var minCol = coords[startChar].col
    var maxCol = coords[startChar].col
    for (i in (startChar + 1) until endCharExclusive) {
        val c = coords[i]
        if (c.row == currentRow) {
            if (c.col < minCol) minCol = c.col
            if (c.col > maxCol) maxCol = c.col
        } else {
            spans += LinkRowSpan(row = currentRow, startCol = minCol, endCol = maxCol)
            currentRow = c.row
            minCol = c.col
            maxCol = c.col
        }
    }
    spans += LinkRowSpan(row = currentRow, startCol = minCol, endCol = maxCol)
    return spans
}

private fun extractHardWrappedUrlContinuation(
    snapshots: List<TerminalRowSnapshot>,
    snapshotIndexByRow: Map<Int, Int>,
    initialUrl: String,
    initialSpans: List<LinkRowSpan>,
    lastRowEndCharInLine: Int,
    lastRowText: String,
): Pair<String, List<LinkRowSpan>> {
    if (initialSpans.isEmpty()) return initialUrl to initialSpans
    val lastSpan = initialSpans.last()
    val lastSnapshotIdx = snapshotIndexByRow[lastSpan.row] ?: return initialUrl to initialSpans
    val lastSnapshot = snapshots[lastSnapshotIdx]
    if (lastSnapshot.wrappedToNext) return initialUrl to initialSpans

    val (_, lastInnerEnd) = stripBoxFrame(lastRowText)
    if (lastRowEndCharInLine < lastInnerEnd) {
        val trailing = lastRowText.substring(lastRowEndCharInLine, lastInnerEnd)
        if (trailing.isNotBlank()) return initialUrl to initialSpans
    }

    val lastRowCols = computeColumnOffsets(lastRowText)
    val innerRightCol = if (lastInnerEnd > 0) lastRowCols[lastInnerEnd] - 1 else lastSpan.endCol
    val (lastInnerStart, _) = stripBoxFrame(lastRowText)
    val innerLeftCol = lastRowCols[lastInnerStart]
    val innerWidth = innerRightCol - innerLeftCol + 1

    val nearRightEdge =
        innerWidth >= MIN_INNER_WIDTH_FOR_EDGE_WRAP &&
            (innerRightCol - lastSpan.endCol) <= HARD_WRAP_EDGE_TOLERANCE_COLS
    val endsWithStrongContinuation =
        initialUrl.isNotEmpty() && initialUrl.last() in STRONG_URL_CONTINUATION_CHARS

    if (!nearRightEdge && !endsWithStrongContinuation) {
        return initialUrl to initialSpans
    }

    val urlBuilder = StringBuilder(initialUrl)
    val spans = initialSpans.toMutableList()
    var prevNearEdgeOrStrong = nearRightEdge || endsWithStrongContinuation
    var steps = 0
    var nextIdx = lastSnapshotIdx + 1

    while (prevNearEdgeOrStrong && nextIdx < snapshots.size && steps < MAX_HARD_WRAP_CONTINUATION_ROWS) {
        val nextSnap = snapshots[nextIdx]
        val line = nextSnap.text
        val (innerStart, innerEnd) = stripBoxFrame(line)
        if (innerEnd <= innerStart) break

        // Trim leading spaces inside the frame
        var tokenStart = innerStart
        while (tokenStart < innerEnd && line[tokenStart] == ' ') tokenStart++
        if (tokenStart >= innerEnd) break

        val innerTrimmed = line.substring(tokenStart, innerEnd).trimEnd()
        if (innerTrimmed.isEmpty() || innerTrimmed.any { isBoxDrawingOrBlockChar(it) }) break
        if (URL_PATTERN.matcher(innerTrimmed).lookingAt()) break

        var tokenEnd = tokenStart
        while (tokenEnd < innerEnd) {
            val ch = line[tokenEnd]
            if (ch.isWhitespace() || ch == '<' || ch == '>' || ch == '"' || ch == '\'' || ch == '`' || isBoxDrawingOrBlockChar(ch)) {
                break
            }
            tokenEnd++
        }
        if (tokenEnd <= tokenStart) break

        val token = line.substring(tokenStart, tokenEnd)
        val remainderAfterToken = line.substring(tokenEnd, innerEnd).trim()

        val isWholeLineToken = remainderAfterToken.isEmpty()
        val hasUrlStructure = token.any { it in URL_STRUCTURAL_CHARS }
        val remainderFirstWord = remainderAfterToken
            .takeWhile { !it.isWhitespace() }
            .lowercase()
            .trimEnd('.', ',', ':', ';', '!', '?')
        val looksLikeProse = !isWholeLineToken &&
            (!hasUrlStructure || remainderFirstWord in PROSE_CONTINUATION_FIRST_WORDS)

        if (!isWholeLineToken && looksLikeProse) break

        val colOffsets = computeColumnOffsets(line)
        val spanStartCol = colOffsets[tokenStart]
        val spanEndCol = colOffsets[tokenEnd] - 1
        if (spanEndCol < spanStartCol) break

        urlBuilder.append(token)
        spans += LinkRowSpan(row = nextSnap.externalRow, startCol = spanStartCol, endCol = spanEndCol)
        steps++

        if (!isWholeLineToken || nextSnap.wrappedToNext) {
            // If there's non-token text on the same line, stop stitching further rows.
            if (!isWholeLineToken) break
        }

        val nextInnerRightCol = colOffsets[innerEnd] - 1
        val nextInnerLeftCol = colOffsets[innerStart]
        val nextInnerWidth = nextInnerRightCol - nextInnerLeftCol + 1
        val nextNearEdge =
            nextInnerWidth >= MIN_INNER_WIDTH_FOR_EDGE_WRAP &&
                (nextInnerRightCol - spanEndCol) <= HARD_WRAP_EDGE_TOLERANCE_COLS
        val nextStrongEnd = token.last() in STRONG_URL_CONTINUATION_CHARS
        prevNearEdgeOrStrong = nextNearEdge || nextStrongEnd
        nextIdx++
    }

    val rawJoined = urlBuilder.toString()
    val trimmedJoined = trimTrailingUrlPunctuation(rawJoined)
    if (trimmedJoined.length < rawJoined.length && spans.size > initialSpans.size) {
        val trimDelta = rawJoined.length - trimmedJoined.length
        val lastRowSnap = snapshots[snapshotIndexByRow[spans.last().row]!!]
        val lastSpan = spans.last()
        val colOffsets = computeColumnOffsets(lastRowSnap.text)
        var lastTokenEndChar = lastRowSnap.text.length
        for (i in lastRowSnap.text.indices) {
            if (colOffsets[i] == lastSpan.endCol) {
                lastTokenEndChar = i + 1
                break
            }
        }
        val newEndChar = (lastTokenEndChar - trimDelta).coerceAtLeast(1)
        val newEndCol = (colOffsets[newEndChar] - 1).coerceAtLeast(lastSpan.startCol)
        spans[spans.lastIndex] = lastSpan.copy(endCol = newEndCol)
    }

    return trimmedJoined to spans
}

/**
 * Pure row-text extractor for linkified URLs, IP addresses, and file paths. Split out so JVM tests
 * can exercise column mapping and path detection without constructing a full [TerminalEmulator].
 */
internal fun findLinksInLine(line: String, row: Int): List<TerminalLink> =
    findLinksInSnapshots(
        listOf(
            TerminalRowSnapshot(
                externalRow = row,
                text = line,
                wrappedToNext = false,
            )
        )
    )

/**
 * Multi-row link extractor supporting soft-wrapped terminal rows, hard-wrapped/TUI-boxed URLs,
 * trailing punctuation cleanup, and OSC 8 hyperlink label matching.
 */
internal fun findLinksInSnapshots(
    snapshots: List<TerminalRowSnapshot>,
    osc8Links: List<Osc8Hyperlink> = emptyList(),
): List<TerminalLink> {
    if (snapshots.isEmpty()) return emptyList()

    val logicalLines = buildLogicalLines(snapshots)
    val snapshotIndexByRow = snapshots.mapIndexed { index, snap -> snap.externalRow to index }.toMap()
    val results = mutableListOf<TerminalLink>()
    val occupiedCells = mutableSetOf<Pair<Int, Int>>()

    fun isSpanOccupied(spans: List<LinkRowSpan>): Boolean =
        spans.any { span -> (span.startCol..span.endCol).any { col -> (span.row to col) in occupiedCells } }

    fun markSpansOccupied(spans: List<LinkRowSpan>) {
        for (span in spans) {
            for (col in span.startCol..span.endCol) {
                occupiedCells += (span.row to col)
            }
        }
    }

    // Pass 1: OSC 8 hyperlinks matched by their visible label segments in reverse chronological
    // order (most recent first) so they take precedence over truncated/shortened visible URLs.
    for (osc8 in osc8Links) {
        val segments = osc8.labelSegments
            .map { it.trim() }
            .filter { it.length >= 2 }
        if (segments.isEmpty()) continue

        for (logical in logicalLines) {
            if (logical.text.isBlank()) continue
            for (segment in segments) {
                var searchFrom = 0
                while (true) {
                    val idx = logical.text.indexOf(segment, searchFrom)
                    if (idx < 0) break
                    val endIdx = idx + segment.length
                    val spans = spansFromCharRange(logical.cellCoords, idx, endIdx)
                    if (spans.isNotEmpty() && !isSpanOccupied(spans)) {
                        val first = spans.first()
                        results += TerminalLink(
                            type = LinkType.URL,
                            text = osc8.uri,
                            startCol = first.startCol,
                            endCol = first.endCol,
                            row = first.row,
                            displayLabel = osc8.label.takeIf { it.isNotBlank() && it != osc8.uri },
                            spans = spans,
                        )
                        markSpansOccupied(spans)
                    }
                    searchFrom = idx + segment.length
                }
            }
        }
    }

    // Pass 2: URLs (with soft-wrap + hard-wrap stitching) across logical lines.
    for (logical in logicalLines) {
        if (logical.text.isBlank()) continue
        val matcher = URL_PATTERN.matcher(logical.text)
        while (matcher.find()) {
            val rawStart = matcher.start()
            val rawMatch = matcher.group() ?: continue
            val trimmedMatch = trimTrailingUrlPunctuation(rawMatch)
            if (trimmedMatch.isEmpty()) continue
            val trimmedEnd = rawStart + trimmedMatch.length
            val initialSpans = spansFromCharRange(logical.cellCoords, rawStart, trimmedEnd)
            if (initialSpans.isEmpty()) continue

            val lastRowIdxInGroup = logical.rowCharRanges.indexOfLast { trimmedEnd - 1 in it }
            val (finalUrl, finalSpans) =
                if (lastRowIdxInGroup >= 0) {
                    val lastRowRange = logical.rowCharRanges[lastRowIdxInGroup]
                    val lastRowLocalEnd = trimmedEnd - lastRowRange.first
                    extractHardWrappedUrlContinuation(
                        snapshots = snapshots,
                        snapshotIndexByRow = snapshotIndexByRow,
                        initialUrl = trimmedMatch,
                        initialSpans = initialSpans,
                        lastRowEndCharInLine = lastRowLocalEnd,
                        lastRowText = logical.snapshots[lastRowIdxInGroup].text,
                    )
                } else {
                    trimmedMatch to initialSpans
                }

            if (isSpanOccupied(finalSpans)) continue
            val first = finalSpans.first()
            results += TerminalLink(
                type = LinkType.URL,
                text = finalUrl,
                startCol = first.startCol,
                endCol = first.endCol,
                row = first.row,
                spans = finalSpans,
            )
            markSpansOccupied(finalSpans)
        }
    }

    // Pass 3: IPs, absolute paths, and relative code paths across logical lines, skipping anything
    // already claimed by URLs or OSC 8 links.
    fun collectSecondary(
        pattern: Pattern,
        type: LinkType,
        pathGroup: Int = 0,
        lineGroup: Int? = null,
    ) {
        for (logical in logicalLines) {
            if (logical.text.isBlank()) continue
            val trimmedLine = logical.text.trim()
            val matcher = pattern.matcher(logical.text)
            while (matcher.find()) {
                val matchEnd = matcher.end()
                val textStart = matcher.start(pathGroup)
                val textEnd = matcher.end(pathGroup)
                if (textStart < 0 || textEnd <= textStart) continue
                // Make optional :line suffix part of the tappable span too.
                val spans = spansFromCharRange(logical.cellCoords, textStart, matchEnd)
                if (spans.isEmpty() || isSpanOccupied(spans)) continue
                val matchedText = matcher.group(pathGroup) ?: continue
                val lineNo = lineGroup?.let { grp -> matcher.group(grp)?.toIntOrNull() }
                val first = spans.first()
                results += TerminalLink(
                    type = type,
                    text = matchedText,
                    startCol = first.startCol,
                    endCol = first.endCol,
                    row = first.row,
                    lineNumber = lineNo,
                    sourceLine = if (type == LinkType.PATH) trimmedLine else null,
                    spans = spans,
                )
                markSpansOccupied(spans)
            }
        }
    }

    collectSecondary(IP_PATTERN, LinkType.IP)
    collectSecondary(PATH_PATTERN, LinkType.PATH, pathGroup = 1, lineGroup = 2)
    collectSecondary(RELATIVE_CODE_PATH_PATTERN, LinkType.PATH, pathGroup = 1, lineGroup = 2)

    return results
}

private fun snapshotRows(
    emulator: TerminalEmulator,
    startRow: Int,
    endRow: Int,
): List<TerminalRowSnapshot> {
    val screen = emulator.screen
    val minRow = -screen.activeTranscriptRows
    val maxRow = emulator.mRows - 1
    val clampedStart = max(minRow, startRow)
    val clampedEnd = min(maxRow, endRow)
    if (clampedStart > clampedEnd) return emptyList()
    val lastCol = emulator.mColumns - 1
    return (clampedStart..clampedEnd).map { r ->
        TerminalRowSnapshot(
            externalRow = r,
            text = screen.getSelectedText(0, r, lastCol, r, false),
            wrappedToNext = screen.getLineWrap(r),
            columns = emulator.mColumns,
        )
    }
}

/**
 * Built once per row and shared across all patterns rather than probed per-tap.
 */
fun findLinksInRow(emulator: TerminalEmulator, row: Int): List<TerminalLink> {
    val lastCol = emulator.mColumns - 1
    val line = emulator.getSelectedText(0, row, lastCol, row)
    return findLinksInLine(line, row)
}

fun linkAt(
    emulator: TerminalEmulator,
    row: Int,
    col: Int,
    osc8Links: List<Osc8Hyperlink> = emptyList(),
): TerminalLink? {
    val snapshots = snapshotRows(emulator, row - 20, row + 20)
    val links = findLinksInSnapshots(snapshots, osc8Links)
    val hits = links.filter { link ->
        link.spans.any { span -> span.row == row && col in span.startCol..span.endCol }
    }
    return hits.minByOrNull { link ->
        when {
            link.type == LinkType.URL && link.displayLabel != null -> 0
            link.type == LinkType.URL -> 1
            link.type == LinkType.IP -> 2
            else -> 3
        }
    }
}

fun findVisibleUrlLinks(
    emulator: TerminalEmulator,
    topRow: Int,
    osc8Links: List<Osc8Hyperlink> = emptyList(),
): List<TerminalLink> {
    val visibleEndRow = topRow + emulator.mRows - 1
    val snapshots = snapshotRows(emulator, topRow - 12, visibleEndRow + 12)
    return findLinksInSnapshots(snapshots, osc8Links)
        .filter { link ->
            link.type == LinkType.URL &&
                link.spans.any { span -> span.row in topRow..visibleEndRow }
        }
}

fun findAllUrlsInBuffer(
    emulator: TerminalEmulator,
    maxHistoryRows: Int = 500,
    osc8Links: List<Osc8Hyperlink> = emptyList(),
): List<TerminalLink> {
    val snapshots = snapshotRows(
        emulator,
        startRow = -maxHistoryRows,
        endRow = emulator.mRows - 1,
    )
    val onScreenReversed = findLinksInSnapshots(snapshots, osc8Links)
        .filter { it.type == LinkType.URL }
        .asReversed()

    val seen = LinkedHashMap<String, TerminalLink>()
    for (link in onScreenReversed) {
        seen.putIfAbsent(link.text, link)
    }
    for (osc8 in osc8Links) {
        if (!seen.containsKey(osc8.uri)) {
            seen[osc8.uri] = TerminalLink(
                type = LinkType.URL,
                text = osc8.uri,
                startCol = 0,
                endCol = 0,
                row = 0,
                displayLabel = osc8.label.takeIf { it.isNotBlank() && it != osc8.uri },
                spans = emptyList(),
            )
        }
    }
    return seen.values.toList()
}

/**
 * Extracts and stitches a wrapped URL from user-selected terminal text (handling soft newlines and
 * optional TUI box borders).
 */
fun extractUrlFromSelection(selectedText: String): String? {
    if (selectedText.isBlank()) return null
    val cleanedLines = selectedText.lines().mapNotNull { rawLine ->
        val (innerStart, innerEnd) = stripBoxFrame(rawLine)
        if (innerEnd <= innerStart) return@mapNotNull null
        rawLine.substring(innerStart, innerEnd).trim().takeIf { it.isNotEmpty() }
    }
    if (cleanedLines.isEmpty()) return null
    val joined = cleanedLines.joinToString("")
    val matcher = URL_PATTERN.matcher(joined)
    if (!matcher.find()) return null
    val candidate = trimTrailingUrlPunctuation(matcher.group() ?: return null)
    return candidate.takeIf { it.isNotBlank() }
}

/**
 * Inspects [url] for either an OAuth `redirect_uri` (or equivalent callback parameter) pointing to
 * loopback (`localhost` / `127.0.0.1` / `::1`) on an unprivileged port, or a direct loopback URL.
 */
fun detectLocalhostCallback(url: String): LocalhostCallbackInfo? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val rawQuery = uri.rawQuery
    if (!rawQuery.isNullOrBlank()) {
        for (pair in rawQuery.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val key = decodeQueryComponent(pair.substring(0, eq)).lowercase()
            if (key !in REDIRECT_QUERY_KEYS) continue
            var value = decodeQueryComponent(pair.substring(eq + 1))
            if (value.contains("%3A%2F%2F", ignoreCase = true)) {
                value = decodeQueryComponent(value)
            }
            val parsedRedirect = parseLoopbackHttpUri(value)
            if (parsedRedirect != null) {
                return LocalhostCallbackInfo(
                    port = parsedRedirect.second,
                    host = parsedRedirect.first,
                    isOAuthRedirect = true,
                    redirectUri = value,
                )
            }
        }
    }

    val direct = parseLoopbackHttpUri(url) ?: return null
    return LocalhostCallbackInfo(
        port = direct.second,
        host = direct.first,
        isOAuthRedirect = false,
        redirectUri = null,
    )
}

fun urlDisplayHost(url: String): String {
    val uri = runCatching { URI(url) }.getOrNull() ?: return "Open link"
    val host = uri.host?.takeIf { it.isNotBlank() } ?: return uri.scheme ?: "Open link"
    return if (uri.port > 0) "$host:${uri.port}" else host
}

private fun decodeQueryComponent(raw: String): String =
    runCatching { URLDecoder.decode(raw, StandardCharsets.UTF_8.name()) }.getOrDefault(raw)

private fun parseLoopbackHttpUri(raw: String): Pair<String, Int>? {
    val parsed = runCatching { URI(raw) }.getOrNull() ?: return null
    val scheme = parsed.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    val host = parsed.host?.lowercase()?.trim('[', ']') ?: return null
    if (host !in LOOPBACK_HOSTS) return null
    val port = parsed.port
    if (port !in 1024..65535) return null
    return host to port
}
