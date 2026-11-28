package io.github.lordofpolls.shellwave.feature.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Language
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.lordofpolls.shellwave.ui.design.ChromeMonoFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.core.net.TailscaleWebForward
import io.github.lordofpolls.shellwave.core.net.TailscaleWebTarget
import io.github.lordofpolls.shellwave.terminal.SessionLinkCategory
import io.github.lordofpolls.shellwave.terminal.TerminalLink
import io.github.lordofpolls.shellwave.terminal.classifyLinkScheme
import io.github.lordofpolls.shellwave.terminal.detectLocalhostCallback
import io.github.lordofpolls.shellwave.terminal.extractFileUriPath
import io.github.lordofpolls.shellwave.terminal.groupSessionLinks
import io.github.lordofpolls.shellwave.terminal.linkDisplayTitle

private enum class LinksFilter { ALL, WEB, FILES, OTHER }

/**
 * Bottom sheet listing every URL detected in the active session's screen/scrollback and OSC 8
 * stream, grouped into `http(s)://` web links, `file://` local/remote file links, and other
 * schemes, with automatic SSH local-port tunneling for OAuth `localhost` callbacks.
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
    onOpenRemoteFile: ((remotePath: String) -> Unit)? = null,
    onCopyText: ((String) -> Unit)? = null,
    tailscaleWebForwards: List<TailscaleWebForward> = emptyList(),
    resolveTailscaleWebTarget: ((String) -> TailscaleWebTarget?)? = null,
    onTailscalePreviewAndOpen: ((TerminalLink, TailscaleWebTarget) -> Unit)? = null,
    onStopTailscaleWebForward: ((Int) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val grouped = remember(links) { groupSessionLinks(links) }
    var filter by remember { mutableStateOf(LinksFilter.ALL) }

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
                icon = Icons.Outlined.Link,
                title = "Links in session",
                subtitle = if (links.isEmpty()) null else "${links.size} detected",
                onDismiss = onDismiss,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (activeEphemeralPorts.isNotEmpty()) {
                    SessionSheetCard {
                        AgentSectionHeader(title = "Active OAuth Tunnels", icon = Icons.Outlined.VpnKey)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            activeEphemeralPorts.sorted().forEach { port ->
                                AgentUtilityPill(
                                    label = "localhost:$port · Stop",
                                    active = true,
                                    isMonospace = true,
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.Close,
                                            contentDescription = null,
                                            modifier = Modifier.size(13.dp),
                                        )
                                    },
                                    onClick = { onStopTunnel(port) },
                                )
                            }
                        }
                    }
                }

                if (tailscaleWebForwards.isNotEmpty() && onStopTailscaleWebForward != null) {
                    SessionSheetCard {
                        AgentSectionHeader(title = "Active Tailnet Web Previews", icon = Icons.Outlined.Hub)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            tailscaleWebForwards.forEach { fwd ->
                                AgentUtilityPill(
                                    label = "${fwd.targetHost}:${fwd.targetPort} · Stop",
                                    active = true,
                                    isMonospace = true,
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.Close,
                                            contentDescription = null,
                                            modifier = Modifier.size(13.dp),
                                        )
                                    },
                                    onClick = { onStopTailscaleWebForward(fwd.localPort) },
                                )
                            }
                        }
                    }
                }

                if (links.isNotEmpty()) {
                    val activeCategories = listOf(
                        grouped.web.isNotEmpty(),
                        grouped.files.isNotEmpty(),
                        grouped.other.isNotEmpty(),
                    ).count { it }
                    if (activeCategories > 1) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AgentTabPill(
                                label = "All (${links.size})",
                                selected = filter == LinksFilter.ALL,
                                onClick = { filter = LinksFilter.ALL },
                            )
                            if (grouped.web.isNotEmpty()) {
                                AgentTabPill(
                                    label = "Web (${grouped.web.size})",
                                    selected = filter == LinksFilter.WEB,
                                    onClick = { filter = LinksFilter.WEB },
                                )
                            }
                            if (grouped.files.isNotEmpty()) {
                                AgentTabPill(
                                    label = "Files (${grouped.files.size})",
                                    selected = filter == LinksFilter.FILES,
                                    onClick = { filter = LinksFilter.FILES },
                                )
                            }
                            if (grouped.other.isNotEmpty()) {
                                AgentTabPill(
                                    label = "Other (${grouped.other.size})",
                                    selected = filter == LinksFilter.OTHER,
                                    onClick = { filter = LinksFilter.OTHER },
                                )
                            }
                        }
                    }
                }

                if (links.isEmpty()) {
                    Text(
                        text = "No links found in this session's output.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 440.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if ((filter == LinksFilter.ALL || filter == LinksFilter.WEB) && grouped.web.isNotEmpty()) {
                            linkSectionItems(
                                keyPrefix = "web",
                                headerTitle = "Web links (${grouped.web.size})",
                                headerIcon = Icons.Outlined.Language,
                                sectionLinks = grouped.web,
                                activeEphemeralPorts = activeEphemeralPorts,
                                canTunnel = canTunnel,
                                onOpen = onOpen,
                                onTunnelAndOpen = onTunnelAndOpen,
                                onCopy = onCopy,
                                onOpenRemoteFile = onOpenRemoteFile,
                                onCopyText = onCopyText,
                                resolveTailscaleWebTarget = resolveTailscaleWebTarget,
                                onTailscalePreviewAndOpen = onTailscalePreviewAndOpen,
                            )
                        }

                        if ((filter == LinksFilter.ALL || filter == LinksFilter.FILES) && grouped.files.isNotEmpty()) {
                            linkSectionItems(
                                keyPrefix = "file",
                                headerTitle = "Files (${grouped.files.size})",
                                headerIcon = Icons.Outlined.Description,
                                sectionLinks = grouped.files,
                                activeEphemeralPorts = activeEphemeralPorts,
                                canTunnel = canTunnel,
                                onOpen = onOpen,
                                onTunnelAndOpen = onTunnelAndOpen,
                                onCopy = onCopy,
                                onOpenRemoteFile = onOpenRemoteFile,
                                onCopyText = onCopyText,
                                resolveTailscaleWebTarget = resolveTailscaleWebTarget,
                                onTailscalePreviewAndOpen = onTailscalePreviewAndOpen,
                            )
                        }

                        if ((filter == LinksFilter.ALL || filter == LinksFilter.OTHER) && grouped.other.isNotEmpty()) {
                            linkSectionItems(
                                keyPrefix = "other",
                                headerTitle = "Other links (${grouped.other.size})",
                                headerIcon = Icons.Outlined.Link,
                                sectionLinks = grouped.other,
                                activeEphemeralPorts = activeEphemeralPorts,
                                canTunnel = canTunnel,
                                onOpen = onOpen,
                                onTunnelAndOpen = onTunnelAndOpen,
                                onCopy = onCopy,
                                onOpenRemoteFile = onOpenRemoteFile,
                                onCopyText = onCopyText,
                                resolveTailscaleWebTarget = resolveTailscaleWebTarget,
                                onTailscalePreviewAndOpen = onTailscalePreviewAndOpen,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.linkSectionItems(
    keyPrefix: String,
    headerTitle: String,
    headerIcon: ImageVector,
    sectionLinks: List<TerminalLink>,
    activeEphemeralPorts: Set<Int>,
    canTunnel: Boolean,
    onOpen: (TerminalLink) -> Unit,
    onTunnelAndOpen: (TerminalLink, Int) -> Unit,
    onCopy: (TerminalLink) -> Unit,
    onOpenRemoteFile: ((String) -> Unit)?,
    onCopyText: ((String) -> Unit)?,
    resolveTailscaleWebTarget: ((String) -> TailscaleWebTarget?)?,
    onTailscalePreviewAndOpen: ((TerminalLink, TailscaleWebTarget) -> Unit)?,
) {
    item(key = "header:$keyPrefix") {
        AgentSectionHeader(
            title = headerTitle,
            icon = headerIcon,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
    items(sectionLinks, key = { "$keyPrefix:${it.text}" }) { link ->
        SessionLinkCard(
            link = link,
            activeEphemeralPorts = activeEphemeralPorts,
            canTunnel = canTunnel,
            onOpen = { onOpen(link) },
            onTunnelAndOpen = { port -> onTunnelAndOpen(link, port) },
            onCopy = { onCopy(link) },
            onOpenRemoteFile = onOpenRemoteFile,
            onCopyText = onCopyText,
            resolveTailscaleWebTarget = resolveTailscaleWebTarget,
            onTailscalePreviewAndOpen = onTailscalePreviewAndOpen,
        )
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
    onOpenRemoteFile: ((String) -> Unit)?,
    onCopyText: ((String) -> Unit)?,
    resolveTailscaleWebTarget: ((String) -> TailscaleWebTarget?)?,
    onTailscalePreviewAndOpen: ((TerminalLink, TailscaleWebTarget) -> Unit)?,
) {
    val category = classifyLinkScheme(link.text)
    val callbackInfo = if (category == SessionLinkCategory.WEB) detectLocalhostCallback(link.text) else null
    val tailscaleTarget = if (category == SessionLinkCategory.WEB && callbackInfo == null) {
        resolveTailscaleWebTarget?.invoke(link.text)
    } else {
        null
    }
    val portTunneled = callbackInfo != null && callbackInfo.port in activeEphemeralPorts
    val filePath = if (category == SessionLinkCategory.FILE) extractFileUriPath(link.text) else null
    val title = linkDisplayTitle(link)

    SessionSheetCard {
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
        } else if (tailscaleTarget != null) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Text(
                    text = "Tailnet · ${tailscaleTarget.host}:${tailscaleTarget.port}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = ChromeMonoFontFamily,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }

        Text(
            text = filePath ?: link.text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = ChromeMonoFontFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (category == SessionLinkCategory.FILE) {
                if (onOpenRemoteFile != null && filePath != null) {
                    Button(
                        onClick = { onOpenRemoteFile(filePath) },
                        shape = AGENT_BUTTON_SHAPE,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("Edit file")
                    }
                }
                OutlinedButton(
                    onClick = {
                        if (onCopyText != null) {
                            onCopyText(filePath ?: link.text)
                        } else {
                            onCopy()
                        }
                    },
                    shape = AGENT_BUTTON_SHAPE,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("Copy path")
                }
                if (filePath != null && filePath != link.text) {
                    TextButton(onClick = onCopy) {
                        Text("Copy URI")
                    }
                }
            } else {
                if (callbackInfo != null && canTunnel) {
                    Button(
                        onClick = { onTunnelAndOpen(callbackInfo.port) },
                        shape = AGENT_BUTTON_SHAPE,
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
                            },
                        )
                    }
                    TextButton(onClick = onOpen) {
                        Text("Open")
                    }
                } else if (tailscaleTarget != null && onTailscalePreviewAndOpen != null) {
                    Button(
                        onClick = { onTailscalePreviewAndOpen(link, tailscaleTarget) },
                        shape = AGENT_BUTTON_SHAPE,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Hub,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("Preview via Tailscale")
                    }
                    TextButton(onClick = onOpen) {
                        Text("Open")
                    }
                } else {
                    Button(
                        onClick = onOpen,
                        shape = AGENT_BUTTON_SHAPE,
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
                    shape = AGENT_BUTTON_SHAPE,
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
