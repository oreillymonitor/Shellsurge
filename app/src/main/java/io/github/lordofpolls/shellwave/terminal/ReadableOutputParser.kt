package io.github.lordofpolls.shellwave.terminal

sealed interface ReadableBlock {
    data class Heading(val level: Int, val text: String) : ReadableBlock
    data class Command(val prompt: String, val command: String) : ReadableBlock
    data class Prose(val text: String) : ReadableBlock
    data class BulletList(val items: List<ReadableListItem>) : ReadableBlock
    data class Quote(val text: String) : ReadableBlock
    data class Code(val language: String?, val code: String) : ReadableBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : ReadableBlock
    data class Diff(val lines: List<String>) : ReadableBlock
    data object Divider : ReadableBlock
}

data class ReadableListItem(
    val marker: String,
    val text: String,
)

sealed interface ReadableInlineSpan {
    data class Plain(val text: String) : ReadableInlineSpan
    data class Bold(val text: String) : ReadableInlineSpan
    data class Italic(val text: String) : ReadableInlineSpan
    data class InlineCode(val code: String) : ReadableInlineSpan
    data class Link(val label: String, val url: String) : ReadableInlineSpan
}

private val HEADING_REGEX = Regex("""^(#{1,6})\s+(.+?)\s*#*\s*$""")
private val DIVIDER_REGEX = Regex("""^(?:[-*_]\s*){3,}$""")
private val BULLET_ITEM_REGEX = Regex("""^\s*(?:([-*•●])|(\d+[.)]))\s+(.+)$""")
private val QUOTE_REGEX = Regex("""^\s*>\s?(.*)$""")
private val USER_HOST_PROMPT_REGEX = Regex("""^([A-Za-z0-9._-]+@[A-Za-z0-9._-]+:[^$#%]*[$#%])\s+(\S.*)$""")
private val SIMPLE_PROMPT_REGEX = Regex("""^([$%❯➜])\s+(\S.*)$""")
private val TABLE_ROW_REGEX = Regex("""^\|.*\|.*$""")
private val TABLE_SEPARATOR_CELL_REGEX = Regex("""^:?-{2,}:?$""")
private val STATUS_LINE_PREFIX_REGEX = Regex(
    """^(?:✓|✔|✗|✖|⚠|ℹ|ERROR:|WARN:|WARNING:|INFO:|DEBUG:|FAIL:|PASS:|FAILURE:|e:\s|w:\s).*""",
    RegexOption.IGNORE_CASE,
)
private val KEY_VALUE_LINE_REGEX = Regex("""^[A-Za-z0-9 _/-]{1,24}:\s+\S.*$""")

/**
 * Splits cleaned terminal output into structured blocks (headings, commands, bullet/numbered lists,
 * blockquotes, tables, unified diffs, dividers, prose paragraphs, and fenced code blocks) so the
 * reader sheet can present terminal/agent output cleanly.
 */
fun parseReadableBlocks(text: String): List<ReadableBlock> {
    if (text.isBlank()) return emptyList()

    val lines = text.lines()
    val blocks = mutableListOf<ReadableBlock>()
    var i = 0

    while (i < lines.size) {
        val rawLine = lines[i]
        val trimmed = rawLine.trim()

        if (trimmed.isEmpty()) {
            i++
            continue
        }

        // 1. Fenced code block (preserves original indentation).
        if (trimmed.startsWith("```")) {
            val lang = trimmed.removePrefix("```").trim().takeIf { it.isNotEmpty() }
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size) {
                val inner = lines[i]
                if (inner.trim().startsWith("```")) {
                    i++
                    break
                }
                codeLines += inner.trimEnd()
                i++
            }
            val code = codeLines.joinToString("\n").trimEnd()
            if (code.isNotEmpty() || lang != null) {
                blocks += ReadableBlock.Code(language = lang, code = code)
            }
            continue
        }

        // 2. Unified diff block.
        if (trimmed.startsWith("diff --git ") || trimmed.startsWith("@@ ")) {
            val diffLines = mutableListOf<String>()
            while (i < lines.size) {
                val dRaw = lines[i].trimEnd()
                val dTrim = dRaw.trim()
                if (diffLines.isNotEmpty() && dTrim.isEmpty()) break
                val isDiffLine = dRaw.startsWith("diff ") ||
                    dRaw.startsWith("index ") ||
                    dRaw.startsWith("--- ") ||
                    dRaw.startsWith("+++ ") ||
                    dRaw.startsWith("@@") ||
                    dRaw.startsWith("+") ||
                    dRaw.startsWith("-") ||
                    dRaw.startsWith("\\ No newline") ||
                    (diffLines.any { it.startsWith("@@") } && rawLineOrSpace(dRaw))
                if (diffLines.isNotEmpty() && !isDiffLine) break
                diffLines += dRaw
                i++
            }
            blocks += ReadableBlock.Diff(diffLines)
            continue
        }

        // 3. Markdown pipe table (header + separator row).
        if (i + 1 < lines.size && isTableRow(trimmed) && isTableSeparator(lines[i + 1].trim())) {
            val headers = splitTableCells(trimmed)
            i += 2
            val rows = mutableListOf<List<String>>()
            while (i < lines.size) {
                val rTrim = lines[i].trim()
                if (!isTableRow(rTrim) || isTableSeparator(rTrim)) break
                rows += splitTableCells(rTrim)
                i++
            }
            blocks += ReadableBlock.Table(headers = headers, rows = rows)
            continue
        }

        // 4. Horizontal rule / divider.
        if (DIVIDER_REGEX.matches(trimmed)) {
            blocks += ReadableBlock.Divider
            i++
            continue
        }

        // 5. Markdown heading.
        HEADING_REGEX.matchEntire(trimmed)?.let { headingMatch ->
            blocks += ReadableBlock.Heading(
                level = headingMatch.groupValues[1].length,
                text = headingMatch.groupValues[2].trim(),
            )
            i++
            continue
        }

        // 6. Shell / agent command prompt.
        matchCommandPrompt(trimmed)?.let { cmdBlock ->
            blocks += cmdBlock
            i++
            continue
        }

        // 7. Bullet / numbered list.
        if (BULLET_ITEM_REGEX.matches(rawLine)) {
            val items = mutableListOf<ReadableListItem>()
            while (i < lines.size) {
                val itemLine = lines[i]
                val itemTrim = itemLine.trim()
                if (itemTrim.isEmpty()) break
                val match = BULLET_ITEM_REGEX.matchEntire(itemLine)
                if (match != null) {
                    val bullet = match.groupValues[1]
                    val numbered = match.groupValues[2]
                    val marker = if (bullet.isNotEmpty()) "•" else numbered
                    items += ReadableListItem(marker = marker, text = match.groupValues[3].trim())
                    i++
                } else if (
                    items.isNotEmpty() &&
                    !startsSpecialBlock(itemTrim, lines.getOrNull(i + 1)?.trim()) &&
                    !isLineBreakSensitive(itemTrim)
                ) {
                    val last = items.removeAt(items.lastIndex)
                    items += last.copy(text = "${last.text} $itemTrim")
                    i++
                } else {
                    break
                }
            }
            blocks += ReadableBlock.BulletList(items)
            continue
        }

        // 8. Blockquote.
        if (QUOTE_REGEX.matches(rawLine)) {
            val quoteLines = mutableListOf<String>()
            while (i < lines.size) {
                val qMatch = QUOTE_REGEX.matchEntire(lines[i]) ?: break
                val body = qMatch.groupValues[1].trim()
                if (body.isNotEmpty()) quoteLines += body
                i++
            }
            val quoteText = quoteLines.joinToString(" ").trim()
            if (quoteText.isNotEmpty()) {
                blocks += ReadableBlock.Quote(quoteText)
            }
            continue
        }

        // 9. Prose paragraph (reflow soft-wrapped prose lines cleanly while preserving structured lines).
        val paragraphLines = mutableListOf<String>()
        while (i < lines.size) {
            val pRaw = lines[i]
            val pTrim = pRaw.trim()
            if (pTrim.isEmpty()) break
            if (paragraphLines.isNotEmpty() && (startsSpecialBlock(pTrim, lines.getOrNull(i + 1)?.trim()) || BULLET_ITEM_REGEX.matches(pRaw) || QUOTE_REGEX.matches(pRaw))) {
                break
            }
            if (paragraphLines.isEmpty()) {
                paragraphLines += pTrim
            } else {
                val prev = paragraphLines.last()
                val keepNewline = prev.endsWith(":") ||
                    isLineBreakSensitive(prev) ||
                    isLineBreakSensitive(pTrim)
                if (keepNewline) {
                    paragraphLines += pTrim
                } else {
                    paragraphLines[paragraphLines.lastIndex] = "$prev $pTrim"
                }
            }
            i++
        }
        val prose = paragraphLines.joinToString("\n").trim()
        if (prose.isNotEmpty()) {
            blocks += ReadableBlock.Prose(prose)
        }
    }

    return blocks
}

private fun rawLineOrSpace(line: String): Boolean =
    line.startsWith(" ") || line.startsWith("+") || line.startsWith("-") || line.startsWith("@@")

private fun isTableRow(trimmed: String): Boolean =
    TABLE_ROW_REGEX.matches(trimmed) && splitTableCells(trimmed).isNotEmpty()

private fun isTableSeparator(trimmed: String): Boolean {
    if (!TABLE_ROW_REGEX.matches(trimmed)) return false
    val cells = splitTableCells(trimmed)
    return cells.isNotEmpty() && cells.all { TABLE_SEPARATOR_CELL_REGEX.matches(it) }
}

private fun splitTableCells(trimmed: String): List<String> {
    val core = trimmed.removePrefix("|").removeSuffix("|")
    return core.split('|').map { it.trim() }
}

private fun matchCommandPrompt(trimmed: String): ReadableBlock.Command? {
    USER_HOST_PROMPT_REGEX.matchEntire(trimmed)?.let { m ->
        return ReadableBlock.Command(prompt = m.groupValues[1], command = m.groupValues[2].trim())
    }
    SIMPLE_PROMPT_REGEX.matchEntire(trimmed)?.let { m ->
        return ReadableBlock.Command(prompt = m.groupValues[1], command = m.groupValues[2].trim())
    }
    return null
}

private fun startsSpecialBlock(trimmed: String, nextTrimmed: String?): Boolean =
    trimmed.startsWith("```") ||
        trimmed.startsWith("diff --git ") ||
        trimmed.startsWith("@@ ") ||
        (nextTrimmed != null && isTableRow(trimmed) && isTableSeparator(nextTrimmed)) ||
        DIVIDER_REGEX.matches(trimmed) ||
        HEADING_REGEX.matches(trimmed) ||
        matchCommandPrompt(trimmed) != null

private fun isLineBreakSensitive(trimmed: String): Boolean =
    STATUS_LINE_PREFIX_REGEX.matches(trimmed) ||
        KEY_VALUE_LINE_REGEX.matches(trimmed) ||
        trimmed.startsWith("[") ||
        trimmed.contains("://")

/**
 * Parses lightweight inline Markdown (`**bold**`, `*italic*`, `` `code` ``, `[label](url)`) and
 * bare `http(s)://` URLs into ordered spans with zero dropped characters.
 */
fun parseInlineSpans(text: String): List<ReadableInlineSpan> {
    if (text.isEmpty()) return emptyList()

    val spans = mutableListOf<ReadableInlineSpan>()
    val plain = StringBuilder()
    fun flushPlain() {
        if (plain.isNotEmpty()) {
            spans += ReadableInlineSpan.Plain(plain.toString())
            plain.setLength(0)
        }
    }

    var i = 0
    val n = text.length
    while (i < n) {
        val ch = text[i]

        // 1. Inline code `...`
        if (ch == '`') {
            val close = text.indexOf('`', startIndex = i + 1)
            val newline = text.indexOf('\n', startIndex = i + 1)
            if (close > i + 1 && (newline < 0 || close < newline)) {
                flushPlain()
                spans += ReadableInlineSpan.InlineCode(text.substring(i + 1, close))
                i = close + 1
                continue
            }
        }

        // 2. Markdown link [label](scheme://url)
        if (ch == '[') {
            val mid = text.indexOf("](", startIndex = i + 1)
            val newline = text.indexOf('\n', startIndex = i + 1)
            if (mid > i + 1 && (newline < 0 || mid < newline)) {
                val close = text.indexOf(')', startIndex = mid + 2)
                if (close > mid + 2 && (newline < 0 || close < newline)) {
                    val label = text.substring(i + 1, mid)
                    val url = text.substring(mid + 2, close).trim()
                    if (url.contains("://") && url.none { it.isWhitespace() }) {
                        flushPlain()
                        spans += ReadableInlineSpan.Link(label = label, url = url)
                        i = close + 1
                        continue
                    }
                }
            }
        }

        // 3. Bold **...**
        if (ch == '*' && i + 1 < n && text[i + 1] == '*' && i + 2 < n && !text[i + 2].isWhitespace()) {
            val close = text.indexOf("**", startIndex = i + 2)
            val newline = text.indexOf('\n', startIndex = i + 2)
            if (close > i + 2 && !text[close - 1].isWhitespace() && (newline < 0 || close < newline)) {
                flushPlain()
                spans += ReadableInlineSpan.Bold(text.substring(i + 2, close))
                i = close + 2
                continue
            }
        }

        // 4. Italic *...*
        if (
            ch == '*' &&
            (i + 1 >= n || text[i + 1] != '*') &&
            i + 1 < n &&
            !text[i + 1].isWhitespace() &&
            (i == 0 || !text[i - 1].isLetterOrDigit())
        ) {
            val close = text.indexOf('*', startIndex = i + 1)
            val newline = text.indexOf('\n', startIndex = i + 1)
            if (
                close > i + 1 &&
                !text[close - 1].isWhitespace() &&
                (newline < 0 || close < newline) &&
                (close + 1 >= n || text[close + 1] != '*')
            ) {
                flushPlain()
                spans += ReadableInlineSpan.Italic(text.substring(i + 1, close))
                i = close + 1
                continue
            }
        }

        // 5. Bare URL (http://, https://, file://)
        if ((i == 0 || !text[i - 1].isLetterOrDigit()) && matchesUrlPrefix(text, i)) {
            var end = i
            while (end < n && !isUrlTerminator(text[end])) {
                end++
            }
            val cleanedUrl = trimTrailingUrlPunctuation(text.substring(i, end))
            if (cleanedUrl.contains("://") && cleanedUrl.length > cleanedUrl.indexOf("://") + 3) {
                flushPlain()
                spans += ReadableInlineSpan.Link(label = cleanedUrl, url = cleanedUrl)
                i += cleanedUrl.length
                continue
            }
        }

        plain.append(ch)
        i++
    }

    flushPlain()
    return spans
}

private fun matchesUrlPrefix(text: String, index: Int): Boolean =
    text.regionMatches(index, "https://", 0, 8, ignoreCase = true) ||
        text.regionMatches(index, "http://", 0, 7, ignoreCase = true) ||
        text.regionMatches(index, "file://", 0, 7, ignoreCase = true)

private fun isUrlTerminator(ch: Char): Boolean =
    ch.isWhitespace() ||
        ch == '<' ||
        ch == '>' ||
        ch == '"' ||
        ch == '\'' ||
        ch == '`' ||
        ch in '\u2500'..'\u259F'

/**
 * Extracts distinct URLs from cleaned terminal output in order of appearance.
 */
fun findUrlsInReadableText(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    val seen = LinkedHashSet<String>()
    text.lines().forEachIndexed { idx, line ->
        findLinksInLine(line, row = idx)
            .asSequence()
            .filter { it.type == LinkType.URL }
            .forEach { seen.add(it.text) }
    }
    return seen.toList()
}
