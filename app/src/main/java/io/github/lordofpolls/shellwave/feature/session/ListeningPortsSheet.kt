package io.github.lordofpolls.shellwave.feature.session

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import io.github.lordofpolls.shellwave.ui.design.ChromeMonoFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.ssh.SshConnection

/**
 * Bottom sheet scanning listening TCP ports on the remote host (`ss`, `lsof`, or `netstat`) and
 * offering 1-tap SSH local forwarding + opening `http://127.0.0.1:<port>` in the browser.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ListeningPortsSheet(
    connection: SshConnection,
    activeEphemeralPorts: Set<Int>,
    onForwardAndOpen: (Int) -> Unit,
    onForwardOnly: (Int) -> Unit,
    onOpenForwarded: (Int) -> Unit,
    onStopForward: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var refreshTick by remember { mutableIntStateOf(0) }
    var ports by remember { mutableStateOf<List<ListeningPort>?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(connection, refreshTick) {
        ports = null
        scanError = null
        val result = runCatching { connection.execCapture(LISTENING_PORTS_COMMAND) }
        result.fold(
            onSuccess = { cap ->
                if (cap.error != null) {
                    scanError = cap.error
                    ports = emptyList()
                } else {
                    ports = parseListeningPorts(cap.stdout)
                }
            },
            onFailure = { err ->
                scanError = err.message ?: "Failed to scan listening ports"
                ports = emptyList()
            },
        )
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
                icon = Icons.Outlined.Router,
                title = "Listening ports",
                subtitle = ports?.let { if (it.isEmpty()) null else "${it.size} active on host" },
                onDismiss = onDismiss,
                trailing = {
                    IconButton(
                        onClick = { refreshTick++ },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Refresh ports",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (activeEphemeralPorts.isNotEmpty()) {
                    SessionSheetCard {
                        AgentSectionHeader(title = "Active Forwards", icon = Icons.Outlined.VpnKey)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            activeEphemeralPorts.sorted().forEach { port ->
                                AgentUtilityPill(
                                    label = "localhost:$port · Open",
                                    active = true,
                                    isMonospace = true,
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.OpenInBrowser,
                                            contentDescription = null,
                                            modifier = Modifier.size(13.dp),
                                        )
                                    },
                                    onClick = { onOpenForwarded(port) },
                                )
                                AgentUtilityPill(
                                    label = "Stop :$port",
                                    isMonospace = true,
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.Close,
                                            contentDescription = null,
                                            modifier = Modifier.size(13.dp),
                                        )
                                    },
                                    onClick = { onStopForward(port) },
                                )
                            }
                        }
                    }
                }

                val loaded = ports
                when {
                    loaded == null -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        }
                    }
                    scanError != null -> {
                        SessionSheetCard {
                            Text(
                                text = scanError.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            OutlinedButton(onClick = { refreshTick++ }) {
                                Text("Retry")
                            }
                        }
                    }
                    loaded.isEmpty() -> {
                        Text(
                            text = "No unprivileged listening TCP ports (1024–65535) detected on this host.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(loaded, key = { it.port }) { entry ->
                                val forwarded = entry.port in activeEphemeralPorts
                                SessionSheetCard {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            text = ":${entry.port}",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontFamily = ChromeMonoFontFamily,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        val detail = buildString {
                                            append(entry.processName ?: "listening service")
                                            entry.pid?.let { append(" (pid $it)") }
                                            append(" · ${entry.bindAddress}")
                                        }
                                        Text(
                                            text = detail,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                    )
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        if (forwarded) {
                                            AgentUtilityPill(
                                                label = "Open browser",
                                                active = true,
                                                leadingIcon = {
                                                    Icon(
                                                        imageVector = Icons.Outlined.OpenInBrowser,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(14.dp),
                                                    )
                                                },
                                                onClick = { onOpenForwarded(entry.port) },
                                            )
                                            AgentUtilityPill(
                                                label = "Stop forward",
                                                leadingIcon = {
                                                    Icon(
                                                        imageVector = Icons.Outlined.Close,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(14.dp),
                                                    )
                                                },
                                                onClick = { onStopForward(entry.port) },
                                            )
                                        } else {
                                            AgentUtilityPill(
                                                label = "Forward & Open",
                                                active = true,
                                                leadingIcon = {
                                                    Icon(
                                                        imageVector = Icons.Outlined.OpenInBrowser,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(14.dp),
                                                    )
                                                },
                                                onClick = { onForwardAndOpen(entry.port) },
                                            )
                                            AgentUtilityPill(
                                                label = "Forward only",
                                                leadingIcon = {
                                                    Icon(
                                                        imageVector = Icons.Outlined.VpnKey,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(14.dp),
                                                    )
                                                },
                                                onClick = { onForwardOnly(entry.port) },
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
    }
}
