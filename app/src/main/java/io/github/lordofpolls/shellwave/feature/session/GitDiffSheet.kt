package io.github.lordofpolls.shellwave.feature.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import io.github.lordofpolls.shellwave.ui.design.ChromeMonoFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lordofpolls.shellwave.ssh.SshConnection
import kotlinx.coroutines.launch

internal data class GitChangedFile(
    val status: String,
    val path: String,
    val diffHunk: String,
)

internal data class GitRepoSnapshot(
    val repoRoot: String,
    val branch: String,
    val files: List<GitChangedFile>,
    val error: String? = null,
)

private const val MARKER_ROOT = "__SHELLSURGE_GIT_ROOT__"
private const val MARKER_BRANCH = "__SHELLSURGE_GIT_BRANCH__"
private const val MARKER_STATUS = "__SHELLSURGE_GIT_STATUS__"
private const val MARKER_DIFF = "__SHELLSURGE_GIT_DIFF__"
private const val MARKER_ERR = "__SHELLSURGE_GIT_ERR__"

internal fun extractPromptWorkingDir(tail: String): String? {
    val lines = tail.lines().map { it.trim() }.filter { it.isNotEmpty() }.takeLast(8).reversed()
    val userHostDir = Regex("^[\\w.-]+@[\\w.-]+:(~(?:/[^\\s$#%>]*)?|/[^\\s$#%>]*)[$#%>]?.*$")
    val standaloneDir = Regex("^(~(?:/[^\\s()]+)*|/[^\\s()]+)\\s*(?:\\([^)]+\\))?\\s*[$#%>❯➜]?\\s*$")
    for (line in lines) {
        userHostDir.matchEntire(line)?.let { return it.groupValues[1] }
        standaloneDir.matchEntire(line)?.let { return it.groupValues[1] }
    }
    return null
}

private fun shellQuote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"

internal fun shellDirExpr(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed == "~") return "\"\$HOME\""
    if (trimmed.startsWith("~/")) return "\"\$HOME\"/" + shellQuote(trimmed.removePrefix("~/"))
    return shellQuote(trimmed)
}

internal fun parseGitSnapshot(output: String): GitRepoSnapshot {
    val errLine = output.lines().firstOrNull { it.startsWith(MARKER_ERR) }
    if (errLine != null) {
        return GitRepoSnapshot(
            repoRoot = "",
            branch = "",
            files = emptyList(),
            error = errLine.removePrefix(MARKER_ERR).trim().ifEmpty { "Not a git repository." },
        )
    }

    var section = ""
    val rootLines = mutableListOf<String>()
    val branchLines = mutableListOf<String>()
    val statusLines = mutableListOf<String>()
    val diffLines = mutableListOf<String>()

    for (line in output.lines()) {
        when (line.trim()) {
            MARKER_ROOT -> { section = MARKER_ROOT; continue }
            MARKER_BRANCH -> { section = MARKER_BRANCH; continue }
            MARKER_STATUS -> { section = MARKER_STATUS; continue }
            MARKER_DIFF -> { section = MARKER_DIFF; continue }
        }
        when (section) {
            MARKER_ROOT -> if (line.isNotBlank()) rootLines += line.trim()
            MARKER_BRANCH -> if (line.isNotBlank()) branchLines += line.trim()
            MARKER_STATUS -> if (line.isNotBlank()) statusLines += line
            MARKER_DIFF -> diffLines += line
        }
    }

    val repoRoot = rootLines.firstOrNull().orEmpty()
    val branch = branchLines.firstOrNull().orEmpty()
    if (repoRoot.isBlank()) {
        return GitRepoSnapshot(
            repoRoot = "",
            branch = "",
            files = emptyList(),
            error = "Could not inspect git repository on remote host.",
        )
    }

    // Split unified diff blocks by "diff --git a/... b/..."
    val diffByPath = mutableMapOf<String, String>()
    val currentBlock = StringBuilder()
    var currentPath: String? = null
    val diffHeaderRegex = Regex("^diff --git a/(.+?) b/(.+)$")

    fun flushDiff() {
        val path = currentPath ?: return
        val block = currentBlock.toString().trimEnd()
        if (block.isNotEmpty()) {
            diffByPath[path] = block
        }
    }

    for (line in diffLines) {
        val header = diffHeaderRegex.matchEntire(line)
        if (header != null) {
            flushDiff()
            currentBlock.clear()
            currentPath = header.groupValues[2]
            currentBlock.appendLine(line)
        } else if (currentPath != null) {
            currentBlock.appendLine(line)
        }
    }
    flushDiff()

    val files = statusLines.mapNotNull { raw ->
        if (raw.length < 4) return@mapNotNull null
        val code = raw.substring(0, 2).trim().ifEmpty { "M" }
        val rawPath = raw.substring(3).trim()
        val path = rawPath.substringAfter(" -> ").trim().removeSurrounding("\"")
        if (path.isEmpty()) return@mapNotNull null
        val hunk = diffByPath[path]
            ?: if (code == "??") "(untracked new file)" else "(no textual diff or binary change)"
        GitChangedFile(status = code, path = path, diffHunk = hunk)
    }

    return GitRepoSnapshot(repoRoot = repoRoot, branch = branch, files = files)
}

internal suspend fun fetchGitSnapshot(
    connection: SshConnection,
    explicitDir: String?,
    promptDirHint: String?,
): GitRepoSnapshot {
    val dirOverrideCmd = explicitDir?.takeIf { it.isNotBlank() }?.let { "DIR=${shellDirExpr(it)}" } ?: ""
    val promptFallbackExpr = promptDirHint?.takeIf { it.isNotBlank() }?.let(::shellDirExpr) ?: "\"\$HOME\""

    val script = """
        $dirOverrideCmd
        if [ -z "${'$'}DIR" ]; then
          DIR=${'$'}(tmux display-message -p -t shellsurge '#{pane_current_path}' 2>/dev/null)
        fi
        if [ -z "${'$'}DIR" ]; then
          DIR=${'$'}(tmux display-message -p '#{pane_current_path}' 2>/dev/null)
        fi
        if [ -z "${'$'}DIR" ]; then
          DIR=$promptFallbackExpr
        fi
        cd "${'$'}DIR" 2>/dev/null || cd "${'$'}HOME"
        ROOT=${'$'}(git rev-parse --show-toplevel 2>/dev/null)
        if [ -z "${'$'}ROOT" ]; then
          echo "$MARKER_ERR Not a git repository (${'$'}PWD)"
          exit 0
        fi
        cd "${'$'}ROOT" || exit 1
        echo "$MARKER_ROOT"
        echo "${'$'}ROOT"
        echo "$MARKER_BRANCH"
        git rev-parse --abbrev-ref HEAD 2>/dev/null || echo HEAD
        echo "$MARKER_STATUS"
        git status --short 2>/dev/null
        echo "$MARKER_DIFF"
        (git diff --no-color HEAD 2>/dev/null || git diff --no-color 2>/dev/null) | head -n 1500
    """.trimIndent()

    val capture = connection.execCapture("sh -c ${shellQuote(script)}")
    if (capture.error != null) {
        return GitRepoSnapshot("", "", emptyList(), error = capture.error)
    }
    if (capture.stdout.isBlank() && capture.stderr.isNotBlank()) {
        return GitRepoSnapshot("", "", emptyList(), error = capture.stderr.trim())
    }
    return parseGitSnapshot(capture.stdout)
}

internal suspend fun resolveWorkingDirOnHost(
    connection: SshConnection,
    promptDirHint: String?,
): String {
    val promptExpr = promptDirHint?.takeIf { it.isNotBlank() }?.let(::shellDirExpr) ?: "\"\$HOME\""
    val script = """
        DIR=${'$'}(tmux display-message -p -t shellsurge '#{pane_current_path}' 2>/dev/null)
        if [ -z "${'$'}DIR" ]; then
          DIR=${'$'}(tmux display-message -p '#{pane_current_path}' 2>/dev/null)
        fi
        if [ -z "${'$'}DIR" ]; then
          DIR=$promptExpr
        fi
        cd "${'$'}DIR" 2>/dev/null || cd "${'$'}HOME"
        git rev-parse --show-toplevel 2>/dev/null || pwd
    """.trimIndent()
    val capture = connection.execCapture("sh -c ${shellQuote(script)}")
    return capture.stdout.lines().firstOrNull { it.isNotBlank() }?.trim() ?: "."
}

/**
 * Interactive Mobile Git Diff & Status modal sheet backed by a quiet SSH capture side-channel.
 * Lets the developer inspect uncommitted diffs per file, jump straight to SFTP editing, or hand
 * the changes to their coding agent for review.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GitDiffSheet(
    connection: SshConnection,
    promptDirHint: String?,
    onAskAgent: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onEditFile: ((String) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var pathInput by remember { mutableStateOf(promptDirHint.orEmpty()) }
    var snapshot by remember { mutableStateOf<GitRepoSnapshot?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var expandedFiles by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun reload(customDir: String? = pathInput.takeIf { it.isNotBlank() }) {
        isLoading = true
        scope.launch {
            val loaded = fetchGitSnapshot(connection, customDir, promptDirHint)
            snapshot = loaded
            if (loaded.repoRoot.isNotBlank() && pathInput.isBlank()) {
                pathInput = loaded.repoRoot
            }
            if (loaded.files.size == 1) {
                expandedFiles = setOf(loaded.files.first().path)
            }
            isLoading = false
        }
    }

    LaunchedEffect(connection) {
        reload(explicitDirOrNull(pathInput))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { AgentSheetDragHandle() },
        modifier = modifier,
    ) {
        val branchSubtitle = snapshot?.branch?.takeIf { it.isNotBlank() }?.let { branchText ->
            "Branch: $branchText · ${snapshot?.files?.size ?: 0} changed"
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding(),
        ) {
            SessionSheetHeader(
                icon = Icons.Outlined.Code,
                title = "Git Diff & Status",
                subtitle = branchSubtitle,
                trailing = {
                    IconButton(
                        onClick = { reload(explicitDirOrNull(pathInput)) },
                        enabled = !isLoading,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = "Refresh git status",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
                onDismiss = onDismiss,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = pathInput,
                    onValueChange = { pathInput = it },
                    label = { Text("Repository path") },
                    placeholder = { Text("Auto-detected from tmux / prompt") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = ChromeMonoFontFamily),
                    modifier = Modifier.weight(1f),
                )
            }

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            } else {
                val current = snapshot
                val err = current?.error
                if (err != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                } else if (current == null || current.files.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "Working tree clean (${current?.repoRoot.orEmpty()}).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 2.dp),
                    ) {
                        items(current.files, key = { it.path }) { file ->
                            val isExpanded = file.path in expandedFiles
                            val statusColor = when {
                                file.status.contains("D") -> MaterialTheme.colorScheme.error
                                file.status.contains("A") || file.status == "??" -> Color(0xFF2E7D32)
                                else -> MaterialTheme.colorScheme.primary
                            }

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                expandedFiles = if (isExpanded) {
                                                    expandedFiles - file.path
                                                } else {
                                                    expandedFiles + file.path
                                                }
                                            }
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = statusColor.copy(alpha = 0.16f),
                                        ) {
                                            Text(
                                                text = file.status,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontFamily = ChromeMonoFontFamily,
                                                fontWeight = FontWeight.Bold,
                                                color = statusColor,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                        Text(
                                            text = file.path,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = ChromeMonoFontFamily,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (onEditFile != null && !file.status.contains("D")) {
                                            IconButton(
                                                onClick = {
                                                    val fullPath = current.repoRoot.trimEnd('/') + "/" + file.path
                                                    scope.launch {
                                                        sheetState.hide()
                                                        onDismiss()
                                                        onEditFile(fullPath)
                                                    }
                                                },
                                                modifier = Modifier.size(32.dp),
                                            ) {
                                                Icon(
                                                    Icons.Outlined.Edit,
                                                    contentDescription = "Edit ${file.path}",
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        }
                                        IconButton(
                                            onClick = {
                                                scope.launch {
                                                    sheetState.hide()
                                                    onDismiss()
                                                    onAskAgent("Review my uncommitted changes in ${file.path} and point out any bugs or improvements.")
                                                }
                                            },
                                            modifier = Modifier.size(32.dp),
                                        ) {
                                            Icon(
                                                Icons.Outlined.AutoAwesome,
                                                contentDescription = "Ask agent to review ${file.path}",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                        Icon(
                                            imageVector = if (isExpanded) {
                                                Icons.Outlined.KeyboardArrowUp
                                            } else {
                                                Icons.Outlined.KeyboardArrowDown
                                            },
                                            contentDescription = if (isExpanded) "Collapse diff" else "Expand diff",
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }

                                    if (isExpanded) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                                                .clip(RoundedCornerShape(8.dp)),
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .horizontalScroll(rememberScrollState())
                                                    .padding(8.dp),
                                            ) {
                                                file.diffHunk.lines().forEach { diffLine ->
                                                    val lineColor = when {
                                                        diffLine.startsWith("+") && !diffLine.startsWith("+++") -> Color(0xFF2E7D32)
                                                        diffLine.startsWith("-") && !diffLine.startsWith("---") -> MaterialTheme.colorScheme.error
                                                        diffLine.startsWith("@@") -> MaterialTheme.colorScheme.primary
                                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                                    }
                                                    Text(
                                                        text = diffLine,
                                                        fontFamily = ChromeMonoFontFamily,
                                                        fontSize = 11.sp,
                                                        lineHeight = 15.sp,
                                                        color = lineColor,
                                                        softWrap = false,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Button(
                        onClick = {
                            val summaryList = current.files.take(6).joinToString(", ") { it.path }
                            scope.launch {
                                sheetState.hide()
                                onDismiss()
                                onAskAgent("Review the current git diff across these modified files ($summaryList) and check for bugs, edge cases, or missing tests.")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Review changes with agent")
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            }
        }
    }
}

private fun explicitDirOrNull(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() }
