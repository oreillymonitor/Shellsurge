package io.github.lordofpolls.shellwave.feature.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lordofpolls.shellwave.core.prefs.READABLE_FONT_SIZE_RANGE
import io.github.lordofpolls.shellwave.core.prefs.SessionLayoutPreferences
import io.github.lordofpolls.shellwave.terminal.ReadableBlock
import io.github.lordofpolls.shellwave.terminal.ReadableInlineSpan
import io.github.lordofpolls.shellwave.terminal.findUrlsInReadableText
import io.github.lordofpolls.shellwave.terminal.parseInlineSpans
import io.github.lordofpolls.shellwave.terminal.parseReadableBlocks
import io.github.lordofpolls.shellwave.ui.design.ChromeMonoFontFamily
import kotlinx.coroutines.delay

private enum class ReadableScope { LATEST_TURN, SCROLLBACK }
private enum class ReadableRenderMode { FORMATTED, RAW }

private val SUCCESS_STATUS_PREFIX = Regex("""^(?:✓|✔|PASS:|PASSED:)""", RegexOption.IGNORE_CASE)
private val ERROR_STATUS_PREFIX = Regex("""^(?:✗|✖|ERROR:|FAIL:|FAILURE:|FATAL:|e:\s)""", RegexOption.IGNORE_CASE)
private val WARN_STATUS_PREFIX = Regex("""^(?:⚠|WARN:|WARNING:|w:\s)""", RegexOption.IGNORE_CASE)

/**
 * Full-height bottom sheet displaying reflowed, ANSI-stripped terminal output inside a native
 * [SelectionContainer] with distinct user prompt cards, collapsible agent activity groups,
 * structured Markdown formatting, copyable code/diff/command cards, search, text sizing,
 * clickable links, and a Formatted / Raw toggle.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ReadableOutputSheet(
    latestTurnText: String,
    fullScrollbackText: String,
    onRefresh: () -> Unit,
    onCopyText: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isAlternateScreen: Boolean = false,
    onOpenLinksSheet: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var scope by remember {
        mutableStateOf(
            if (latestTurnText.isNotBlank()) ReadableScope.LATEST_TURN else ReadableScope.SCROLLBACK,
        )
    }
    var renderMode by remember { mutableStateOf(ReadableRenderMode.FORMATTED) }
    var copiedAll by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var fontSizeSp by remember { mutableIntStateOf(SessionLayoutPreferences.getReadableFontSizeSp(context)) }
    val listState = rememberLazyListState()
    val rawHorizontalScrollState = rememberScrollState()
    val blockSheetDragConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset = available

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity,
            ): Velocity = available
        }
    }

    LaunchedEffect(copiedAll) {
        if (copiedAll) {
            delay(1500)
            copiedAll = false
        }
    }

    LaunchedEffect(scope, renderMode) {
        listState.scrollToItem(0)
    }

    val latestLineCount = remember(latestTurnText) {
        if (latestTurnText.isBlank()) 0 else latestTurnText.lines().size
    }
    val fullLineCount = remember(fullScrollbackText) {
        if (fullScrollbackText.isBlank()) 0 else fullScrollbackText.lines().size
    }
    val hasOlderScrollback = remember(latestTurnText, fullScrollbackText) {
        fullScrollbackText.isNotBlank() &&
            latestTurnText.isNotBlank() &&
            fullScrollbackText != latestTurnText
    }
    val activeText = when (scope) {
        ReadableScope.LATEST_TURN -> latestTurnText.ifBlank { fullScrollbackText }
        ReadableScope.SCROLLBACK -> fullScrollbackText
    }
    val blocks = remember(activeText) { parseReadableBlocks(activeText) }
    val rawChunks = remember(activeText) {
        if (activeText.isBlank()) {
            emptyList()
        } else {
            activeText.lines().chunked(40).map { it.joinToString("\n") }
        }
    }
    val cleanQuery = searchQuery.trim()
    val filteredBlocks = remember(blocks, cleanQuery) {
        if (cleanQuery.isEmpty()) {
            blocks
        } else {
            blocks.filter { blockMatchesQuery(it, cleanQuery) }
        }
    }
    val matchCount = remember(activeText, cleanQuery) {
        if (cleanQuery.isEmpty()) 0 else countCaseInsensitiveMatches(activeText, cleanQuery)
    }
    val urls = remember(activeText) { findUrlsInReadableText(activeText) }
    val lineCount = remember(activeText) {
        if (activeText.isBlank()) 0 else activeText.lines().size
    }
    val headerSubtitle = when {
        lineCount == 0 -> null
        isAlternateScreen && !hasOlderScrollback -> "$lineCount lines · screen only (TUI alt buffer)"
        !hasOlderScrollback -> "$lineCount lines · single turn in buffer"
        else -> "$lineCount lines · long-press to select"
    }

    fun updateFontSize(delta: Int) {
        val next = (fontSizeSp + delta).coerceIn(READABLE_FONT_SIZE_RANGE)
        if (next != fontSizeSp) {
            fontSizeSp = next
            SessionLayoutPreferences.setReadableFontSizeSp(context, next)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { AgentSheetDragHandle() },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            SessionSheetHeader(
                icon = Icons.AutoMirrored.Outlined.Article,
                title = "Readable Output",
                subtitle = headerSubtitle,
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                if (searchOpen) {
                                    searchOpen = false
                                    searchQuery = ""
                                } else {
                                    searchOpen = true
                                }
                            },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = if (searchOpen) "Close search" else "Search output",
                                tint = if (searchOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        IconButton(
                            onClick = onRefresh,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = "Refresh output",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                },
                onDismiss = onDismiss,
            )

            AnimatedVisibility(visible = searchOpen) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Find in output…") },
                        singleLine = true,
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = "Clear search",
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        },
                        shape = AGENT_CARD_SHAPE,
                        modifier = Modifier.weight(1f),
                    )
                    if (cleanQuery.isNotEmpty()) {
                        Text(
                            text = "$matchCount",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = ChromeMonoFontFamily,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AgentTabPill(
                    label = if (hasOlderScrollback) "Latest turn ($latestLineCount)" else "Latest turn",
                    selected = scope == ReadableScope.LATEST_TURN,
                    onClick = { scope = ReadableScope.LATEST_TURN },
                )
                AgentTabPill(
                    label = if (hasOlderScrollback) "Scrollback ($fullLineCount)" else "Scrollback",
                    selected = scope == ReadableScope.SCROLLBACK,
                    onClick = { scope = ReadableScope.SCROLLBACK },
                )
                AgentTabPill(
                    label = "Formatted",
                    selected = renderMode == ReadableRenderMode.FORMATTED,
                    onClick = { renderMode = ReadableRenderMode.FORMATTED },
                )
                AgentTabPill(
                    label = "Raw",
                    selected = renderMode == ReadableRenderMode.RAW,
                    onClick = { renderMode = ReadableRenderMode.RAW },
                )
                AgentUtilityPill(
                    label = "A-",
                    enabled = fontSizeSp > READABLE_FONT_SIZE_RANGE.first,
                    onClick = { updateFontSize(-1) },
                )
                AgentUtilityPill(
                    label = "A+",
                    enabled = fontSizeSp < READABLE_FONT_SIZE_RANGE.last,
                    onClick = { updateFontSize(1) },
                )
                if (activeText.isNotBlank()) {
                    AgentUtilityPill(
                        label = if (copiedAll) "Copied" else "Copy all",
                        active = copiedAll,
                        leadingIcon = {
                            Icon(
                                imageVector = if (copiedAll) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                        onClick = {
                            onCopyText(activeText)
                            copiedAll = true
                        },
                    )
                }
                if (onOpenLinksSheet != null && urls.isNotEmpty()) {
                    AgentUtilityPill(
                        label = "Links (${urls.size})",
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Link,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                        onClick = onOpenLinksSheet,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            if (activeText.isBlank()) {
                Text(
                    text = "No readable terminal output yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(if (renderMode == ReadableRenderMode.RAW) 0.dp else 10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .nestedScroll(blockSheetDragConnection),
                ) {
                    if (renderMode == ReadableRenderMode.RAW) {
                        itemsIndexed(rawChunks) { _, chunk ->
                            SelectionContainer {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rawHorizontalScrollState),
                                ) {
                                    Text(
                                        text = highlightSearchInPlainText(
                                            text = chunk,
                                            query = cleanQuery,
                                            highlightBg = MaterialTheme.colorScheme.tertiaryContainer,
                                            highlightFg = MaterialTheme.colorScheme.onTertiaryContainer,
                                        ),
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = (fontSizeSp - 2).coerceAtLeast(11).sp,
                                            lineHeight = ((fontSizeSp - 2).coerceAtLeast(11) * 1.48f).sp,
                                        ),
                                        fontFamily = ChromeMonoFontFamily,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    } else if (filteredBlocks.isEmpty()) {
                        item {
                            Text(
                                text = "No matching blocks for \"$cleanQuery\".",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    } else {
                        itemsIndexed(filteredBlocks) { index, block ->
                            SelectionContainer {
                                when (block) {
                                    is ReadableBlock.Heading -> ReadableHeadingBlock(
                                        level = block.level,
                                        text = block.text,
                                        baseFontSizeSp = fontSizeSp,
                                        hasTopSpace = index > 0,
                                        searchQuery = cleanQuery,
                                        onOpenUrl = onOpenUrl,
                                    )
                                    is ReadableBlock.UserPrompt -> ReadableUserPromptBlock(
                                        prompt = block.prompt,
                                        text = block.text,
                                        baseFontSizeSp = fontSizeSp,
                                        searchQuery = cleanQuery,
                                        onOpenUrl = onOpenUrl,
                                        onCopy = { onCopyText(block.text) },
                                    )
                                    is ReadableBlock.Command -> ReadableCommandBlock(
                                        prompt = block.prompt,
                                        command = block.command,
                                        baseFontSizeSp = fontSizeSp,
                                        onCopy = { onCopyText(block.command) },
                                    )
                                    is ReadableBlock.ActivityGroup -> ReadableActivityGroupBlock(
                                        items = block.items,
                                        initiallyExpanded = cleanQuery.isNotEmpty(),
                                    )
                                    is ReadableBlock.Prose -> ReadableProseBlock(
                                        text = block.text,
                                        baseFontSizeSp = fontSizeSp,
                                        searchQuery = cleanQuery,
                                        onOpenUrl = onOpenUrl,
                                    )
                                    is ReadableBlock.BulletList -> ReadableBulletListBlock(
                                        block = block,
                                        baseFontSizeSp = fontSizeSp,
                                        searchQuery = cleanQuery,
                                        onOpenUrl = onOpenUrl,
                                    )
                                    is ReadableBlock.Quote -> ReadableQuoteBlock(
                                        text = block.text,
                                        baseFontSizeSp = fontSizeSp,
                                        searchQuery = cleanQuery,
                                        onOpenUrl = onOpenUrl,
                                    )
                                    is ReadableBlock.Table -> ReadableTableBlock(
                                        block = block,
                                        baseFontSizeSp = fontSizeSp,
                                        searchQuery = cleanQuery,
                                        onOpenUrl = onOpenUrl,
                                    )
                                    is ReadableBlock.Diff -> ReadableDiffBlock(
                                        lines = block.lines,
                                        baseFontSizeSp = fontSizeSp,
                                        onCopy = { onCopyText(block.lines.joinToString("\n")) },
                                    )
                                    is ReadableBlock.Code -> ReadableCodeBlock(
                                        language = block.language,
                                        code = block.code,
                                        baseFontSizeSp = fontSizeSp,
                                        onCopy = { onCopyText(block.code) },
                                    )
                                    ReadableBlock.Divider -> HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 4.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun blockMatchesQuery(block: ReadableBlock, query: String): Boolean =
    when (block) {
        is ReadableBlock.Heading -> block.text.contains(query, ignoreCase = true)
        is ReadableBlock.UserPrompt -> block.text.contains(query, ignoreCase = true)
        is ReadableBlock.Command -> block.command.contains(query, ignoreCase = true) ||
            block.prompt.contains(query, ignoreCase = true)
        is ReadableBlock.ActivityGroup -> block.items.any { it.contains(query, ignoreCase = true) }
        is ReadableBlock.Prose -> block.text.contains(query, ignoreCase = true)
        is ReadableBlock.BulletList -> block.items.any { it.text.contains(query, ignoreCase = true) }
        is ReadableBlock.Quote -> block.text.contains(query, ignoreCase = true)
        is ReadableBlock.Code -> block.code.contains(query, ignoreCase = true) ||
            (block.language?.contains(query, ignoreCase = true) == true)
        is ReadableBlock.Table -> block.headers.any { it.contains(query, ignoreCase = true) } ||
            block.rows.any { row -> row.any { it.contains(query, ignoreCase = true) } }
        is ReadableBlock.Diff -> block.lines.any { it.contains(query, ignoreCase = true) }
        ReadableBlock.Divider -> false
    }

private fun countCaseInsensitiveMatches(text: String, query: String): Int {
    if (query.isEmpty()) return 0
    var count = 0
    var idx = 0
    while (true) {
        val found = text.indexOf(query, startIndex = idx, ignoreCase = true)
        if (found < 0) break
        count++
        idx = found + query.length
    }
    return count
}

@Composable
private fun highlightSearchInPlainText(
    text: String,
    query: String,
    highlightBg: Color,
    highlightFg: Color,
): AnnotatedString = remember(text, query, highlightBg, highlightFg) {
    if (query.isEmpty()) {
        AnnotatedString(text)
    } else {
        buildAnnotatedString {
            append(text)
            var idx = 0
            while (true) {
                val found = text.indexOf(query, startIndex = idx, ignoreCase = true)
                if (found < 0) break
                addStyle(
                    style = SpanStyle(
                        background = highlightBg,
                        color = highlightFg,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    start = found,
                    end = found + query.length,
                )
                idx = found + query.length
            }
        }
    }
}

@Composable
private fun rememberFormattedText(
    text: String,
    onOpenUrl: (String) -> Unit,
    searchQuery: String = "",
    colorizeStatusLines: Boolean = false,
): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    val successColor = MaterialTheme.colorScheme.tertiary
    val errorColor = MaterialTheme.colorScheme.error
    val warnColor = MaterialTheme.colorScheme.secondary
    val highlightBg = MaterialTheme.colorScheme.tertiaryContainer
    val highlightFg = MaterialTheme.colorScheme.onTertiaryContainer

    return remember(
        text,
        searchQuery,
        colorizeStatusLines,
        linkColor,
        codeBackground,
        successColor,
        errorColor,
        warnColor,
        highlightBg,
        highlightFg,
    ) {
        buildAnnotatedString {
            val lines = text.split('\n')
            lines.forEachIndexed { lineIndex, line ->
                if (lineIndex > 0) append('\n')
                val lineStart = length
                for (span in parseInlineSpans(line)) {
                    when (span) {
                        is ReadableInlineSpan.Plain -> append(span.text)
                        is ReadableInlineSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                            append(span.text)
                        }
                        is ReadableInlineSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(span.text)
                        }
                        is ReadableInlineSpan.Strikethrough -> withStyle(
                            SpanStyle(textDecoration = TextDecoration.LineThrough),
                        ) {
                            append(span.text)
                        }
                        is ReadableInlineSpan.InlineCode -> withStyle(
                            SpanStyle(
                                fontFamily = ChromeMonoFontFamily,
                                background = codeBackground,
                            ),
                        ) {
                            append(" ${span.code} ")
                        }
                        is ReadableInlineSpan.Link -> {
                            val start = length
                            append(span.label)
                            val end = length
                            addLink(
                                clickable = LinkAnnotation.Clickable(
                                    tag = "url",
                                    styles = TextLinkStyles(
                                        style = SpanStyle(
                                            color = linkColor,
                                            textDecoration = TextDecoration.Underline,
                                        ),
                                    ),
                                    linkInteractionListener = { onOpenUrl(span.url) },
                                ),
                                start = start,
                                end = end,
                            )
                        }
                    }
                }
                val lineEnd = length
                if (colorizeStatusLines && lineEnd > lineStart) {
                    val trimmed = line.trimStart()
                    val statusTint = when {
                        SUCCESS_STATUS_PREFIX.containsMatchIn(trimmed) -> successColor
                        ERROR_STATUS_PREFIX.containsMatchIn(trimmed) -> errorColor
                        WARN_STATUS_PREFIX.containsMatchIn(trimmed) -> warnColor
                        else -> null
                    }
                    if (statusTint != null) {
                        addStyle(
                            style = SpanStyle(color = statusTint, fontWeight = FontWeight.Medium),
                            start = lineStart,
                            end = lineEnd,
                        )
                    }
                }
            }

            if (searchQuery.isNotEmpty()) {
                val rendered = toAnnotatedString().text
                var idx = 0
                while (true) {
                    val found = rendered.indexOf(searchQuery, startIndex = idx, ignoreCase = true)
                    if (found < 0) break
                    addStyle(
                        style = SpanStyle(
                            background = highlightBg,
                            color = highlightFg,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        start = found,
                        end = found + searchQuery.length,
                    )
                    idx = found + searchQuery.length
                }
            }
        }
    }
}

@Composable
private fun ReadableHeadingBlock(
    level: Int,
    text: String,
    baseFontSizeSp: Int,
    hasTopSpace: Boolean,
    searchQuery: String,
    onOpenUrl: (String) -> Unit,
) {
    val formatted = rememberFormattedText(text = text, onOpenUrl = onOpenUrl, searchQuery = searchQuery)
    val sizeSp = when {
        level <= 1 -> (baseFontSizeSp + 4).sp
        level == 2 -> (baseFontSizeSp + 2).sp
        else -> (baseFontSizeSp + 1).sp
    }
    Text(
        text = formatted,
        style = MaterialTheme.typography.titleMedium.copy(
            fontSize = sizeSp,
            lineHeight = (sizeSp.value * 1.35f).sp,
        ),
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (hasTopSpace) 6.dp else 0.dp),
    )
}

@Composable
private fun ReadableUserPromptBlock(
    prompt: String,
    text: String,
    baseFontSizeSp: Int,
    searchQuery: String,
    onOpenUrl: (String) -> Unit,
    onCopy: () -> Unit,
) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1200)
            copied = false
        }
    }

    Surface(
        shape = AGENT_CARD_SHAPE,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "$prompt You",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = ChromeMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    imageVector = if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                    contentDescription = "Copy prompt",
                    tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            onCopy()
                            copied = true
                        },
                )
            }
            Text(
                text = rememberFormattedText(text = text, onOpenUrl = onOpenUrl, searchQuery = searchQuery),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = baseFontSizeSp.sp,
                    lineHeight = (baseFontSizeSp * 1.48f).sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ReadableCommandBlock(
    prompt: String,
    command: String,
    baseFontSizeSp: Int,
    onCopy: () -> Unit,
) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1200)
            copied = false
        }
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
            ) {
                val styled = buildAnnotatedString {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)) {
                        append(prompt)
                    }
                    append(" ")
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)) {
                        append(command)
                    }
                }
                Text(
                    text = styled,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = (baseFontSizeSp - 2).coerceAtLeast(11).sp,
                    ),
                    fontFamily = ChromeMonoFontFamily,
                )
            }
            Icon(
                imageVector = if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                contentDescription = "Copy command",
                tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable {
                        onCopy()
                        copied = true
                    },
            )
        }
    }
}

@Composable
private fun ReadableActivityGroupBlock(
    items: List<String>,
    initiallyExpanded: Boolean = false,
) {
    var expanded by remember(initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Build,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "${items.size} agent ${if (items.size == 1) "step" else "steps"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Collapse steps" else "Expand steps",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    items.forEach { step ->
                        Text(
                            text = step,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = ChromeMonoFontFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadableProseBlock(
    text: String,
    baseFontSizeSp: Int,
    searchQuery: String,
    onOpenUrl: (String) -> Unit,
) {
    val formatted = rememberFormattedText(
        text = text,
        onOpenUrl = onOpenUrl,
        searchQuery = searchQuery,
        colorizeStatusLines = true,
    )
    Text(
        text = formatted,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontSize = baseFontSizeSp.sp,
            lineHeight = (baseFontSizeSp * 1.52f).sp,
        ),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ReadableBulletListBlock(
    block: ReadableBlock.BulletList,
    baseFontSizeSp: Int,
    searchQuery: String,
    onOpenUrl: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        block.items.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = (item.indentLevel * 14).dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = item.marker,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = baseFontSizeSp.sp,
                        lineHeight = (baseFontSizeSp * 1.5f).sp,
                    ),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 2.dp),
                )
                Text(
                    text = rememberFormattedText(
                        text = item.text,
                        onOpenUrl = onOpenUrl,
                        searchQuery = searchQuery,
                        colorizeStatusLines = true,
                    ),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = baseFontSizeSp.sp,
                        lineHeight = (baseFontSizeSp * 1.5f).sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ReadableQuoteBlock(
    text: String,
    baseFontSizeSp: Int,
    searchQuery: String,
    onOpenUrl: (String) -> Unit,
) {
    val accentColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = rememberFormattedText(text = text, onOpenUrl = onOpenUrl, searchQuery = searchQuery),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = (baseFontSizeSp - 1).coerceAtLeast(11).sp,
                lineHeight = ((baseFontSizeSp - 1).coerceAtLeast(11) * 1.48f).sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRect(
                        color = accentColor,
                        topLeft = Offset.Zero,
                        size = Size(3.dp.toPx(), size.height),
                    )
                }
                .padding(start = 13.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun ReadableTableBlock(
    block: ReadableBlock.Table,
    baseFontSizeSp: Int,
    searchQuery: String,
    onOpenUrl: (String) -> Unit,
) {
    val cellFontSize = (baseFontSizeSp - 2).coerceAtLeast(11).sp
    SessionSheetCard {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    block.headers.forEach { header ->
                        Text(
                            text = rememberFormattedText(text = header, onOpenUrl = onOpenUrl, searchQuery = searchQuery),
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = cellFontSize),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.widthIn(min = 72.dp, max = 220.dp),
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                block.rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        for (colIndex in block.headers.indices) {
                            Text(
                                text = rememberFormattedText(
                                    text = row.getOrNull(colIndex).orEmpty(),
                                    onOpenUrl = onOpenUrl,
                                    searchQuery = searchQuery,
                                ),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = cellFontSize),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.widthIn(min = 72.dp, max = 220.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadableCodeBlock(
    language: String?,
    code: String,
    baseFontSizeSp: Int,
    onCopy: () -> Unit,
) {
    val codeSize = (baseFontSizeSp - 2).coerceAtLeast(11)
    ReadableMonospaceCard(
        label = language ?: "code",
        onCopy = onCopy,
    ) {
        Text(
            text = code,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = codeSize.sp,
                lineHeight = (codeSize * 1.48f).sp,
            ),
            fontFamily = ChromeMonoFontFamily,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ReadableDiffBlock(
    lines: List<String>,
    baseFontSizeSp: Int,
    onCopy: () -> Unit,
) {
    val addColor = MaterialTheme.colorScheme.tertiary
    val removeColor = MaterialTheme.colorScheme.error
    val hunkColor = MaterialTheme.colorScheme.primary
    val defaultColor = MaterialTheme.colorScheme.onSurface
    val metaColor = MaterialTheme.colorScheme.onSurfaceVariant

    val styled = remember(lines, addColor, removeColor, hunkColor, defaultColor, metaColor) {
        buildAnnotatedString {
            lines.forEachIndexed { idx, line ->
                if (idx > 0) append('\n')
                val color: Color = when {
                    line.startsWith("+++") || line.startsWith("---") || line.startsWith("diff ") || line.startsWith("index ") ->
                        metaColor
                    line.startsWith("@@") -> hunkColor
                    line.startsWith("+") -> addColor
                    line.startsWith("-") -> removeColor
                    else -> defaultColor
                }
                val weight = if (line.startsWith("diff ") || line.startsWith("@@")) {
                    FontWeight.SemiBold
                } else {
                    FontWeight.Normal
                }
                withStyle(SpanStyle(color = color, fontWeight = weight)) {
                    append(line)
                }
            }
        }
    }

    val diffSize = (baseFontSizeSp - 2).coerceAtLeast(11)
    ReadableMonospaceCard(
        label = "diff",
        onCopy = onCopy,
    ) {
        Text(
            text = styled,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = diffSize.sp,
                lineHeight = (diffSize * 1.48f).sp,
            ),
            fontFamily = ChromeMonoFontFamily,
        )
    }
}

@Composable
private fun ReadableMonospaceCard(
    label: String,
    onCopy: () -> Unit,
    content: @Composable () -> Unit,
) {
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1200)
            copied = false
        }
    }

    SessionSheetCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = ChromeMonoFontFamily,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            AgentUtilityPill(
                label = if (copied) "Copied" else "Copy",
                active = copied,
                leadingIcon = {
                    Icon(
                        imageVector = if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                    )
                },
                onClick = {
                    onCopy()
                    copied = true
                },
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            content()
        }
    }
}
