package io.github.lordofpolls.shellwave.feature.settings

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.lordofpolls.shellwave.core.net.TailscaleManager
import io.github.lordofpolls.shellwave.core.net.TailscalePeer
import io.github.lordofpolls.shellwave.core.net.TailscalePingResult
import io.github.lordofpolls.shellwave.core.net.WatchedPeerState
import io.github.lordofpolls.shellwave.ui.design.ChromeMonoFontFamily
import io.github.lordofpolls.shellwave.ui.design.MachineText
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TailnetPeersSheet(
    tailscaleManager: TailscaleManager,
    onConnectPeer: (hostname: String, port: Int, username: String, useTailscaleSsh: Boolean, password: String) -> Unit,
    onSavePeer: (label: String, hostname: String, port: Int, username: String, useTailscaleSsh: Boolean, password: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status by tailscaleManager.status.collectAsState()
    val peers by tailscaleManager.peers.collectAsState()
    val webForwards by tailscaleManager.webForwards.collectAsState()
    val watchedPeers by tailscaleManager.watchedPeers.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var selectedPeer by remember { mutableStateOf<TailscalePeer?>(null) }
    val pingResults = remember { mutableStateMapOf<String, TailscalePingResult>() }

    LaunchedEffect(status.isRunning) {
        if (status.isRunning) {
            tailscaleManager.refreshPeers()
        }
    }

    fun openLoopbackPreview(localPort: Int, scheme: String = "http") {
        val url = "$scheme://127.0.0.1:$localPort"
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
            context.startActivity(intent)
        }.onFailure { err ->
            Toast.makeText(
                context,
                "Could not open browser (${err.message ?: "no browser app"})",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = {
            Surface(
                modifier = Modifier
                    .padding(top = 8.dp, bottom = 4.dp)
                    .size(width = 36.dp, height = 4.dp),
                shape = RoundedCornerShape(2.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            ) {}
        },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Hub,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                ) {
                    Text("Tailnet Devices", style = MaterialTheme.typography.titleMedium)
                    val subtitle = when {
                        status.isRunning -> listOfNotNull(status.selfIp, status.tailnetName).joinToString(" • ").ifBlank { "Connected" }
                        status.needsLogin -> "Sign in required"
                        else -> status.state
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (status.isRunning) {
                    IconButton(onClick = { scope.launch { tailscaleManager.refreshPeers() } }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh tailnet devices")
                    }
                }
            }

            status.lastError?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (webForwards.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Active Tailnet Web Previews",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            webForwards.forEach { fwd ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                    ) {
                                        Text(
                                            text = "${fwd.targetHost}:${fwd.targetPort} → :${fwd.localPort}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = ChromeMonoFontFamily,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.clickable { openLoopbackPreview(fwd.localPort) },
                                        )
                                        IconButton(
                                            onClick = { scope.launch { tailscaleManager.stopWebForward(fwd.localPort) } },
                                            modifier = Modifier.size(22.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Close,
                                                contentDescription = "Stop preview",
                                                modifier = Modifier.size(14.dp),
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (!status.isRunning) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = if (status.needsLogin) {
                                "Authorize this device to browse peers and connect over MagicDNS."
                            } else {
                                "Start embedded Tailscale to browse tailnet devices without using Android's VPN slot."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!status.isActive) {
                                Button(onClick = { scope.launch { tailscaleManager.start() } }) {
                                    Text("Connect Tailscale")
                                }
                            }
                            status.authUrl?.let { url ->
                                Button(onClick = { runCatching { uriHandler.openUri(url) } }) {
                                    Text("Sign in")
                                }
                            }
                        }
                    }
                }
            } else if (peers.isEmpty()) {
                Text(
                    text = "No peers found on this tailnet yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(peers, key = { it.id.ifBlank { it.primaryAddress } }) { peer ->
                        PeerRow(
                            peer = peer,
                            pingResult = pingResults[peer.watchKey],
                            watchState = watchedPeers[peer.watchKey],
                            onToggleWatch = {
                                if (watchedPeers.containsKey(peer.watchKey)) {
                                    tailscaleManager.cancelPeerWatch(peer.watchKey)
                                } else {
                                    tailscaleManager.watchPeerUntilOnline(peer)
                                    Toast.makeText(
                                        context,
                                        if (peer.online) {
                                            "Watching ${peer.hostName.ifBlank { peer.primaryAddress }} for reboot"
                                        } else {
                                            "Watching ${peer.hostName.ifBlank { peer.primaryAddress }} until online"
                                        },
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                            onClick = { selectedPeer = peer },
                        )
                    }
                }
            }
        }
    }

    selectedPeer?.let { peer ->
        PeerConnectDialog(
            peer = peer,
            tailscaleManager = tailscaleManager,
            initialPingResult = pingResults[peer.watchKey],
            isWatched = watchedPeers.containsKey(peer.watchKey),
            onPingResult = { result -> pingResults[peer.watchKey] = result },
            onToggleWatch = {
                if (watchedPeers.containsKey(peer.watchKey)) {
                    tailscaleManager.cancelPeerWatch(peer.watchKey)
                } else {
                    tailscaleManager.watchPeerUntilOnline(peer)
                }
            },
            onOpenWebPreview = { port, scheme ->
                scope.launch {
                    tailscaleManager.startWebForward(peer.primaryAddress, port).fold(
                        onSuccess = { localPort ->
                            openLoopbackPreview(localPort, scheme)
                        },
                        onFailure = { err ->
                            Toast.makeText(
                                context,
                                "Preview failed: ${err.message ?: "cannot forward port $port"}",
                                Toast.LENGTH_LONG,
                            ).show()
                        },
                    )
                }
            },
            onDismiss = { selectedPeer = null },
            onConnect = { username, port, useTsSsh, password ->
                selectedPeer = null
                onDismiss()
                onConnectPeer(peer.primaryAddress, port, username, useTsSsh, password)
            },
            onSave = { username, port, useTsSsh, password ->
                selectedPeer = null
                onSavePeer(
                    peer.hostName.ifBlank { peer.primaryAddress },
                    peer.primaryAddress,
                    port,
                    username,
                    useTsSsh,
                    password,
                )
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeerRow(
    peer: TailscalePeer,
    pingResult: TailscalePingResult?,
    watchState: WatchedPeerState?,
    onToggleWatch: () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (peer.online) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outlineVariant),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = peer.hostName.ifBlank { peer.primaryAddress },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val ipLabel = peer.ips.firstOrNull { !it.contains(':') } ?: peer.ips.firstOrNull().orEmpty()
                val detail = listOfNotNull(
                    ipLabel.takeIf { it.isNotBlank() },
                    peer.os.takeIf { it.isNotBlank() },
                ).joinToString(" • ")
                if (detail.isNotBlank()) {
                    MachineText(detail)
                }
                val pathBadge = peer.pathBadge
                val hasMeta = pathBadge != null ||
                    pingResult != null ||
                    peer.primaryRoutes.isNotEmpty() ||
                    watchState != null
                if (hasMeta) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (pingResult != null) {
                            PeerMetaBadge(
                                text = pingResult.summaryLabel,
                                highlighted = pingResult.direct,
                                isError = !pingResult.isSuccess,
                            )
                        } else if (peer.online && pathBadge != null) {
                            PeerMetaBadge(
                                text = pathBadge.first,
                                highlighted = pathBadge.second,
                            )
                        }
                        if (watchState != null) {
                            PeerMetaBadge(
                                text = if (watchState.waitForOfflineFirst) "Watching reboot" else "Watching online",
                                highlighted = true,
                            )
                        }
                        peer.primaryRoutes.take(2).forEach { cidr ->
                            PeerMetaBadge(text = cidr, highlighted = false, monospace = true)
                        }
                        if (peer.primaryRoutes.size > 2) {
                            PeerMetaBadge(text = "+${peer.primaryRoutes.size - 2} routes", highlighted = false)
                        }
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!peer.online || watchState != null) {
                    IconButton(
                        onClick = onToggleWatch,
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.NotificationsActive,
                            contentDescription = if (watchState != null) "Stop watching peer" else "Watch until online",
                            modifier = Modifier.size(16.dp),
                            tint = if (watchState != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                if (peer.sshHostKeys) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            text = "SSH",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PeerMetaBadge(
    text: String,
    highlighted: Boolean,
    monospace: Boolean = false,
    isError: Boolean = false,
) {
    val container = when {
        isError -> MaterialTheme.colorScheme.errorContainer
        highlighted -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = when {
        isError -> MaterialTheme.colorScheme.onErrorContainer
        highlighted -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = container,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = if (monospace) ChromeMonoFontFamily else null,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeerConnectDialog(
    peer: TailscalePeer,
    tailscaleManager: TailscaleManager,
    initialPingResult: TailscalePingResult?,
    isWatched: Boolean,
    onPingResult: (TailscalePingResult) -> Unit,
    onToggleWatch: () -> Unit,
    onOpenWebPreview: (port: Int, scheme: String) -> Unit,
    onDismiss: () -> Unit,
    onConnect: (username: String, port: Int, useTailscaleSsh: Boolean, password: String) -> Unit,
    onSave: (username: String, port: Int, useTailscaleSsh: Boolean, password: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("root") }
    var portText by remember { mutableStateOf("22") }
    var useTailscaleSsh by remember { mutableStateOf(true) }
    var password by remember { mutableStateOf("") }
    var pingInProgress by remember { mutableStateOf(false) }
    var latestPing by remember(peer.watchKey) { mutableStateOf(initialPingResult) }
    var previewExpanded by remember { mutableStateOf(false) }
    var previewPortText by remember { mutableStateOf("3000") }
    var previewHttps by remember { mutableStateOf(false) }

    val portInt = portText.toIntOrNull()
    val valid = username.isNotBlank() && portInt != null && portInt in 1..65535 &&
        (useTailscaleSsh || password.isNotEmpty())
    val previewPortInt = previewPortText.toIntOrNull()?.takeIf { it in 1..65535 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(peer.hostName.ifBlank { peer.primaryAddress }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MachineText(peer.primaryAddress)

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilledTonalButton(
                        enabled = !pingInProgress && peer.online,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        onClick = {
                            pingInProgress = true
                            scope.launch {
                                val res = tailscaleManager.pingPeer(peer.primaryAddress)
                                latestPing = res
                                onPingResult(res)
                                pingInProgress = false
                            }
                        },
                    ) {
                        if (pingInProgress) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Speed,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                        Spacer(Modifier.size(5.dp))
                        Text("Ping", style = MaterialTheme.typography.labelMedium)
                    }

                    OutlinedButton(
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        onClick = onToggleWatch,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.NotificationsActive,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.size(5.dp))
                        Text(
                            text = when {
                                isWatched -> "Stop watch"
                                peer.online -> "Watch reboot"
                                else -> "Watch online"
                            },
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }

                    if (peer.online) {
                        OutlinedButton(
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            onClick = { previewExpanded = !previewExpanded },
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.OpenInBrowser,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.size(5.dp))
                            Text("Web UI", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                latestPing?.let { ping ->
                    val detail = listOfNotNull(
                        ping.summaryLabel,
                        ping.endpoint.takeIf { ping.direct && it.isNotBlank() },
                    ).joinToString(" · ")
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = ChromeMonoFontFamily,
                        color = if (ping.isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }

                if (previewExpanded) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedTextField(
                                    value = previewPortText,
                                    onValueChange = { previewPortText = it.filter(Char::isDigit).take(5) },
                                    label = { Text("Web port") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { previewHttps = !previewHttps }) {
                                    Text(if (previewHttps) "HTTPS" else "HTTP")
                                }
                                Button(
                                    enabled = previewPortInt != null,
                                    onClick = {
                                        previewPortInt?.let { p ->
                                            onOpenWebPreview(p, if (previewHttps) "https" else "http")
                                        }
                                    },
                                ) {
                                    Text("Open")
                                }
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                listOf(80, 3000, 5173, 8080).forEach { quickPort ->
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { previewPortText = quickPort.toString() },
                                    ) {
                                        Text(
                                            text = ":$quickPort",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = ChromeMonoFontFamily,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (peer.primaryRoutes.isNotEmpty()) {
                    Text(
                        text = "Advertised subnets: ${peer.primaryRoutes.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = ChromeMonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Use Tailscale SSH (keyless)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Authenticates via tailnet identity without a password or key.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = useTailscaleSsh, onCheckedChange = { useTailscaleSsh = it })
                }
                if (!useTailscaleSsh) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = valid,
                    onClick = { onSave(username.trim(), portInt!!, useTailscaleSsh, password) },
                ) {
                    Text("Save")
                }
                Button(
                    enabled = valid,
                    onClick = { onConnect(username.trim(), portInt!!, useTailscaleSsh, password) },
                ) {
                    Text("Connect")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
