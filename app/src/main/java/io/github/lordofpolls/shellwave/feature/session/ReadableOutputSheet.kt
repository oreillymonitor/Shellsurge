package io.github.lordofpolls.shellwave.feature.session

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import io.github.lordofpolls.shellwave.ui.design.ChromeMonoFontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lordofpolls.shellwave.terminal.ReadableBlock
import io.github.lordofpolls.shellwave.terminal.ReadableInlineSpan
import io.github.lordofpolls.shellwave.terminal.findUrlsInReadableText
import io.github.lordofpolls.shellwave.terminal.parseInlineSpans
import io.github.lordofpolls.shellwave.terminal.parseReadableBlocks
import kotlinx.coroutines.delay

private enum class ReadableScope { LATEST_TURN, SCROLLBACK }
private enum class ReadableRenderMode { FORMATTED, RAW }

/**
 * Full-height bottom sheet displaying reflowed, ANSI-stripped terminal output inside a native
 * [SelectionContainer] with structured Markdown formatting, copyable code/diff cards, clickable
 * links, and a Formatted / Raw toggle.
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
    onOpenLinksSheet: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var scope by remember {
        mutableStateOf(
            if (latestTurnText.isNotBlank()) ReadableScope.LATEST_TURN else ReadableScope.SCROLLBACK,
        )
    }
    var renderMode by remember { mutableStateOf(ReadableRenderMode.FORMATTED) }
    var copiedAll by remember { mutableStateOf(false) }

    LaunchedEffect(copiedAll) {
        if (copiedAll) {
            delay(1500)
            copiedAll = false
        }
    }

    val activeText = when (scope) {
        ReadableScope.LATEST_TURN -> latestTurnText.ifBlank { fullScrollbackText }
        ReadableScope.SCROLLBACK -> fullScrollbackText
    }
    val blocks = remember(activeText) { parseReadableBlocks(activeText) }
    val urls = remember(activeText) { findUrlsInReadableText(activeText) }
    val lineCount = remember(activeText) {
        if (activeText.isBlank()) 0 else activeText.lines().size
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
                .navigationBarsPadding(),
        ) {
            SessionSheetHeader(
                icon = Icons.AutoMirrored.Outlined.Article,
                title = "Readable Output",
                subtitle = if (lineCount > 0) "$lineCount lines · long-press to select" else null,
                trailing = {
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
                },
                onDismiss = onDismiss,
            )

            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AgentTabPill(
                    label = "Latest turn",
                    selected = scope == ReadableScope.LATEST_TURN,
                    onClick = { scope = ReadableScope.LATEST_TURN },
                )
                AgentTabPill(
                    label = "Scrollback",
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
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 580.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (renderMode == ReadableRenderMode.RAW) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                            ) {
                                Text(
                                    text = activeText,
                                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp),
                                    fontFamily = ChromeMonoFontFamily,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        } else {
                            blocks.forEachIndexed { index, block ->
                                when (block) {
                                    is ReadableBlock.Heading -> ReadableHeadingBlock(
                                        level = block.level,
                                        text = block.text,
                                        hasTopSpace = index > 0,
                                        onOpenUrl = onOpenUrl,
                                    )
                                    is ReadableBlock.Command -> ReadableCommandBlock(
                                        prompt = block.prompt,
                                        command = block.command,
                                    )
                                    is ReadableBlock.Prose -> ReadableProseBlock(
                                        text = block.text,
                                        onOpenUrl = onOpenUrl,
                                    )
                                    is ReadableBlock.BulletList -> ReadableBulletListBlock(
                                        block = block,
                                        onOpenUrl = onOpenUrl,
                                    )
                                    is ReadableBlock.Quote -> ReadableQuoteBlock(
                                        text = block.text,
                                        onOpenUrl = onOpenUrl,
                                    )
                                    is ReadableBlock.Table -> ReadableTableBlock(block = block)
                                    is ReadableBlock.Diff -> ReadableDiffBlock(
                                        lines = block.lines,
                                        onCopy = { onCopyText(block.lines.joinToString("\n")) },
                                    )
                                    is ReadableBlock.Code -> ReadableCodeBlock(
                                        language = block.language,
                                        code = block.code,
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

@Composable
private fun rememberFormattedText(
    text: String,
    onOpenUrl: (String) -> Unit,
): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    return remember(text, linkColor, codeBackground) {
        buildAnnotatedString {
            val lines = text.split('\n')
            lines.forEachIndexed { lineIndex, line ->
                if (lineIndex > 0) append('\n')
                for (span in parseInlineSpans(line)) {
                    when (span) {
                        is ReadableInlineSpan.Plain -> append(span.text)
                        is ReadableInlineSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                            append(span.text)
                        }
                        is ReadableInlineSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
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
            }
        }
    }
}

@Composable
private fun ReadableHeadingBlock(
    level: Int,
    text: String,
    hasTopSpace: Boolean,
    onOpenUrl: (String) -> Unit,
) {
    val formatted = rememberFormattedText(text = text, onOpenUrl = onOpenUrl)
    val baseStyle = when {
        level <= 1 -> MaterialTheme.typography.titleMedium
        level == 2 -> MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp)
        else -> MaterialTheme.typography.labelLarge
    }
    Text(
        text = formatted,
        style = baseStyle,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (hasTopSpace) 4.dp else 0.dp),
    )
}

@Composable
private fun ReadableCommandBlock(
    prompt: String,
    command: String,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 7.dp),
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
                style = MaterialTheme.typography.bodySmall,
                fontFamily = ChromeMonoFontFamily,
            )
        }
    }
}

@Composable
private fun ReadableProseBlock(
    text: String,
    onOpenUrl: (String) -> Unit,
) {
    val formatted = rememberFormattedText(text = text, onOpenUrl = onOpenUrl)
    Text(
        text = formatted,
        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ReadableBulletListBlock(
    block: ReadableBlock.BulletList,
    onOpenUrl: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        block.items.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = item.marker,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 2.dp),
                )
                Text(
                    text = rememberFormattedText(text = item.text, onOpenUrl = onOpenUrl),
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
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
    onOpenUrl: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)),
            )
            Text(
                text = rememberFormattedText(text = text, onOpenUrl = onOpenUrl),
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ReadableTableBlock(block: ReadableBlock.Table) {
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
                            text = header,
                            style = MaterialTheme.typography.labelMedium,
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
                                text = row.getOrNull(colIndex).orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
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
    onCopy: () -> Unit,
) {
    ReadableMonospaceCard(
        label = language ?: "code",
        onCopy = onCopy,
    ) {
        Text(
            text = code,
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
            fontFamily = ChromeMonoFontFamily,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ReadableDiffBlock(
    lines: List<String>,
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

    ReadableMonospaceCard(
        label = "diff",
        onCopy = onCopy,
    ) {
        Text(
            text = styled,
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            content()
        }
    }
}
