package io.github.lordofpolls.shellwave.feature.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.terminal.TerminalLink
import io.github.lordofpolls.shellwave.terminal.detectLocalhostCallback
import io.github.lordofpolls.shellwave.terminal.urlDisplayHost

/**
 * Bottom sheet listing every URL detected in the active session's screen/scrollback and OSC 8
 * stream. Gives one-tap access even inside mouse-tracking TUIs (`tmux`, `vim`) and offers
 * automatic SSH local-port tunneling for OAuth `localhost` callbacks.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SessionLinksSheet(
    links: List<TerminalLink>,
    activeEphemeralPorts: Set<Int>,
    canTunnel: Boolean,
    onOpen: (TerminalLink) -> Unit,
    onTunnelAndOpen: (TerminalLink, Int) -> Unit,
    onStopTunnel: (Int) -> Unit,
    onCopy: (TerminalLink) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = if (links.isEmpty()) "Links in session" else "Links in session (${links.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }

            if (activeEphemeralPorts.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    activeEphemeralPorts.sorted().forEach { port ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = "Tunnel active: localhost:$port",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                TextButton(
                                    onClick = { onStopTunnel(port) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.size(4.dp))
                                    Text("Stop")
                                }
                            }
                        }
                    }
                }
            }

            if (links.isEmpty()) {
                Text(
                    text = "No links found in this session's output.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(links, key = { it.text }) { link ->
                        SessionLinkCard(
                            link = link,
                            activeEphemeralPorts = activeEphemeralPorts,
                            canTunnel = canTunnel,
                            onOpen = { onOpen(link) },
                            onTunnelAndOpen = { port -> onTunnelAndOpen(link, port) },
                            onCopy = { onCopy(link) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionLinkCard(
    link: TerminalLink,
    activeEphemeralPorts: Set<Int>,
    canTunnel: Boolean,
    onOpen: () -> Unit,
    onTunnelAndOpen: (Int) -> Unit,
    onCopy: () -> Unit,
) {
    val callbackInfo = detectLocalhostCallback(link.text)
    val portTunneled = callbackInfo != null && callbackInfo.port in activeEphemeralPorts
    val title = link.displayLabel ?: urlDisplayHost(link.text)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (callbackInfo != null) {
                val badgeText = when {
                    portTunneled && callbackInfo.isOAuthRedirect ->
                        "OAuth callback · localhost:${callbackInfo.port} tunneled"
                    portTunneled ->
                        "Localhost:${callbackInfo.port} tunneled"
                    callbackInfo.isOAuthRedirect ->
                        "OAuth callback · localhost:${callbackInfo.port}"
                    else ->
                        "Localhost · port ${callbackInfo.port}"
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            Text(
                text = link.text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (callbackInfo != null && canTunnel) {
                    Button(
                        onClick = { onTunnelAndOpen(callbackInfo.port) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.VpnKey,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(
                            if (portTunneled) {
                                "Open (:${callbackInfo.port} tunneled)"
                            } else {
                                "Tunnel :${callbackInfo.port} & Open"
                            }
                        )
                    }
                    TextButton(onClick = onOpen) {
                        Text("Open")
                    }
                } else {
                    Button(
                        onClick = onOpen,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.OpenInBrowser,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("Open")
                    }
                }
                OutlinedButton(
                    onClick = onCopy,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("Copy")
                }
            }
        }
    }
}
