package io.github.lordofpolls.shellwave.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.core.net.TailscaleManager
import io.github.lordofpolls.shellwave.core.net.TailscalePeer
import io.github.lordofpolls.shellwave.ui.design.MachineText
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
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
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var selectedPeer by remember { mutableStateOf<TailscalePeer?>(null) }

    LaunchedEffect(status.isRunning) {
        if (status.isRunning) {
            tailscaleManager.refreshPeers()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
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
                                "Authorize this device with your tailnet to browse peers and connect over MagicDNS."
                            } else {
                                "Start ShellSurge's embedded Tailscale node to browse devices on your tailnet without touching Android's VPN slot."
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
                        PeerRow(peer = peer, onClick = { selectedPeer = peer })
                    }
                }
            }
        }
    }

    selectedPeer?.let { peer ->
        PeerConnectDialog(
            peer = peer,
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

@Composable
private fun PeerRow(
    peer: TailscalePeer,
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
            ) {
                Text(
                    text = peer.hostName.ifBlank { peer.primaryAddress },
                    style = MaterialTheme.typography.titleSmall,
                )
                val ipLabel = peer.ips.firstOrNull { !it.contains(':') } ?: peer.ips.firstOrNull().orEmpty()
                val detail = listOfNotNull(
                    ipLabel.takeIf { it.isNotBlank() },
                    peer.os.takeIf { it.isNotBlank() },
                ).joinToString(" • ")
                if (detail.isNotBlank()) {
                    MachineText(detail)
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

@Composable
private fun PeerConnectDialog(
    peer: TailscalePeer,
    onDismiss: () -> Unit,
    onConnect: (username: String, port: Int, useTailscaleSsh: Boolean, password: String) -> Unit,
    onSave: (username: String, port: Int, useTailscaleSsh: Boolean, password: String) -> Unit,
) {
    var username by remember { mutableStateOf("root") }
    var portText by remember { mutableStateOf("22") }
    var useTailscaleSsh by remember { mutableStateOf(true) }
    var password by remember { mutableStateOf("") }
    val portInt = portText.toIntOrNull()
    val valid = username.isNotBlank() && portInt != null && portInt in 1..65535 &&
        (useTailscaleSsh || password.isNotEmpty())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(peer.hostName.ifBlank { peer.primaryAddress }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MachineText(peer.primaryAddress)
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
                Spacer(modifier = Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = valid,
                        onClick = { onSave(username.trim(), portInt!!, useTailscaleSsh, password) },
                    ) {
                        Text("Save host")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = { onConnect(username.trim(), portInt!!, useTailscaleSsh, password) },
            ) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
