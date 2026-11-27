package io.github.lordofpolls.shellwave.feature.session

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.terminal.LinkType
import io.github.lordofpolls.shellwave.terminal.ReadableBlock
import io.github.lordofpolls.shellwave.terminal.findLinksInLine
import io.github.lordofpolls.shellwave.terminal.findUrlsInReadableText
import io.github.lordofpolls.shellwave.terminal.parseReadableBlocks
import kotlinx.coroutines.delay

private enum class ReadableScope { LATEST_TURN, SCROLLBACK }

/**
 * Full-screen-height bottom sheet displaying reflowed, ANSI-stripped terminal output inside a
 * native Android [SelectionContainer] with clickable URLs and 1-tap copyable fenced code blocks.
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
                            .heightIn(max = 520.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        blocks.forEach { block ->
                            when (block) {
                                is ReadableBlock.Prose -> ReadableProseBlock(
                                    text = block.text,
                                    onOpenUrl = onOpenUrl,
                                )
                                is ReadableBlock.Code -> ReadableCodeBlock(
                                    language = block.language,
                                    code = block.code,
                                    onCopy = { onCopyText(block.code) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadableProseBlock(
    text: String,
    onOpenUrl: (String) -> Unit,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, linkColor) {
        buildAnnotatedString {
            val lines = text.lines()
            lines.forEachIndexed { idx, line ->
                if (idx > 0) append('\n')
                val lineStart = length
                append(line)
                val urlLinks = findLinksInLine(line, row = idx).filter { it.type == LinkType.URL }
                for (link in urlLinks) {
                    val startInLine = line.indexOf(link.text)
                    if (startInLine >= 0) {
                        val absStart = lineStart + startInLine
                        val absEnd = absStart + link.text.length
                        addLink(
                            clickable = LinkAnnotation.Clickable(
                                tag = "url",
                                styles = TextLinkStyles(
                                    style = SpanStyle(
                                        color = linkColor,
                                        textDecoration = TextDecoration.Underline,
                                    ),
                                ),
                                linkInteractionListener = { onOpenUrl(link.text) },
                            ),
                            start = absStart,
                            end = absEnd,
                        )
                    }
                }
            }
        }
    }

    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ReadableCodeBlock(
    language: String?,
    code: String,
    onCopy: () -> Unit,
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
                text = language ?: "code",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
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
            Text(
                text = code,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
