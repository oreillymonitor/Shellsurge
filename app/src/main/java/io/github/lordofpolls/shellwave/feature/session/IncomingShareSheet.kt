package io.github.lordofpolls.shellwave.feature.session

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Terminal
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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.ssh.AgentTurnState
import io.github.lordofpolls.shellwave.ssh.SessionStatus
import io.github.lordofpolls.shellwave.ssh.SessionSummary
import io.github.lordofpolls.shellwave.ui.design.ChromeMonoFontFamily
import kotlinx.coroutines.launch

private sealed interface ShareTarget {
    data class Session(val sessionId: Long) : ShareTarget
    data class Host(val hostId: Long) : ShareTarget
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun IncomingShareSheet(
    payload: IncomingSharePayload,
    summaries: List<SessionSummary>,
    hosts: List<HostEntity>,
    preferredSessionId: Long?,
    onDeliverReadyPrompt: (sessionId: Long, prompt: String, sendNow: Boolean) -> Unit,
    onQueueForSession: (
        sessionId: Long,
        remainingAttachments: List<SharedAttachment>,
        preset: ShareFramingPreset,
        note: String,
        sendNow: Boolean,
    ) -> Unit,
    onConnectHostAndDeliver: (
        host: HostEntity,
        remainingAttachments: List<SharedAttachment>,
        preset: ShareFramingPreset,
        note: String,
        sendNow: Boolean,
    ) -> Unit,
    onAddHost: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var attachments by remember(payload) { mutableStateOf(payload.attachments) }
    var selectedPreset by remember(payload) { mutableStateOf(ShareFramingPreset.NONE) }
    var note by remember(payload) { mutableStateOf("") }
    var hostsExpanded by remember(payload) { mutableStateOf(summaries.isEmpty()) }
    var uploadingMessage by remember(payload) { mutableStateOf<String?>(null) }
    var errorMessage by remember(payload) { mutableStateOf<String?>(null) }
    val uploadedPathsByUri = remember(payload) { mutableStateMapOf<Uri, String>() }

    var selectedTarget by remember(payload) {
        mutableStateOf(defaultShareTarget(summaries, hosts, preferredSessionId))
    }

    LaunchedEffect(summaries, hosts) {
        val currentTarget = selectedTarget
        val stillValid = when (currentTarget) {
            is ShareTarget.Session -> summaries.any { it.id == currentTarget.sessionId }
            is ShareTarget.Host -> hosts.any { it.id == currentTarget.hostId }
            null -> false
        }
        if (!stillValid) {
            selectedTarget = defaultShareTarget(summaries, hosts, preferredSessionId)
        }
    }

    val isUploading = uploadingMessage != null
    val hasTextContent = !payload.subject.isNullOrBlank() || !payload.text.isNullOrBlank()
    val totalItemCount = attachments.size + if (hasTextContent) 1 else 0

    fun submit(sendNow: Boolean) {
        if (isUploading) return
        errorMessage = null

        when (val target = selectedTarget) {
            is ShareTarget.Session -> {
                val summary = summaries.firstOrNull { it.id == target.sessionId } ?: return
                if (summary.status == SessionStatus.CONNECTED) {
                    val missing = attachments.filterNot { uploadedPathsByUri.containsKey(it.uri) }
                    if (missing.isEmpty()) {
                        val prompt = buildSharedPrompt(
                            subject = payload.subject,
                            text = payload.text,
                            uploadedPaths = attachments.map { uploadedPathsByUri.getValue(it.uri) },
                            preset = selectedPreset,
                            note = note,
                        )
                        onDeliverReadyPrompt(summary.id, prompt, sendNow)
                        return
                    }

                    scope.launch {
                        val baseTimestamp = System.currentTimeMillis()
                        for ((index, attachment) in attachments.withIndex()) {
                            if (uploadedPathsByUri.containsKey(attachment.uri)) continue
                            uploadingMessage =
                                "Uploading ${attachment.displayName} (${index + 1}/${attachments.size})…"
                            val result = summary.connection.uploadFileAttachment(
                                uri = attachment.uri,
                                fallbackMimeType = attachment.mimeType,
                                timestampMs = baseTimestamp + index,
                            )
                            val remotePath = result.getOrElse { err ->
                                uploadingMessage = null
                                errorMessage =
                                    "Upload failed for ${attachment.displayName}: ${err.message ?: "SFTP error"}"
                                return@launch
                            }
                            uploadedPathsByUri[attachment.uri] = remotePath
                        }
                        uploadingMessage = null
                        val prompt = buildSharedPrompt(
                            subject = payload.subject,
                            text = payload.text,
                            uploadedPaths = attachments.map { uploadedPathsByUri.getValue(it.uri) },
                            preset = selectedPreset,
                            note = note,
                        )
                        onDeliverReadyPrompt(summary.id, prompt, sendNow)
                    }
                } else if (attachments.isEmpty() && !sendNow) {
                    val prompt = buildSharedPrompt(
                        subject = payload.subject,
                        text = payload.text,
                        uploadedPaths = emptyList(),
                        preset = selectedPreset,
                        note = note,
                    )
                    onDeliverReadyPrompt(summary.id, prompt, false)
                } else {
                    onQueueForSession(summary.id, attachments, selectedPreset, note, sendNow)
                }
            }

            is ShareTarget.Host -> {
                val host = hosts.firstOrNull { it.id == target.hostId } ?: return
                onConnectHostAndDeliver(host, attachments, selectedPreset, note, sendNow)
            }

            null -> Unit
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!isUploading) onDismiss() },
        sheetState = sheetState,
        dragHandle = { AgentSheetDragHandle() },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            SessionSheetHeader(
                icon = Icons.Outlined.Share,
                title = "Share to Session",
                onDismiss = { if (!isUploading) onDismiss() },
            )

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SessionSheetCard {
                    AgentSectionHeader(title = "Shared content")
                    payload.subject?.let { subject ->
                        Text(
                            text = subject,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    payload.text?.let { sharedText ->
                        val looksMachine = sharedText.contains('\n') ||
                            sharedText.startsWith("http", ignoreCase = true) ||
                            sharedText.startsWith("/")
                        Text(
                            text = sharedText,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = if (looksMachine) ChromeMonoFontFamily else null,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (attachments.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            attachments.forEach { attachment ->
                                val sizeLabel = formatAttachmentSize(attachment.sizeBytes)
                                val label = if (sizeLabel != null) {
                                    "${attachment.displayName} ($sizeLabel)"
                                } else {
                                    attachment.displayName
                                }
                                SharedAttachmentChip(
                                    label = label,
                                    isImage = attachment.isImage,
                                    canRemove = !isUploading && totalItemCount > 1,
                                    onRemove = {
                                        attachments = attachments.filterNot { it.uri == attachment.uri }
                                    },
                                )
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AgentSectionHeader(title = "Target", icon = Icons.Outlined.Terminal)
                    if (summaries.isEmpty() && hosts.isEmpty()) {
                        SessionSheetCard {
                            Text(
                                text = "No open sessions or saved hosts yet. Add a host to deliver shared content.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(
                                onClick = onAddHost,
                                shape = AGENT_BUTTON_SHAPE,
                            ) {
                                Text("Add Host")
                            }
                        }
                    } else {
                        summaries.forEach { summary ->
                            val isSelected = selectedTarget == ShareTarget.Session(summary.id)
                            val badge = sessionTargetBadge(summary)
                            ShareTargetRow(
                                icon = Icons.Outlined.Terminal,
                                title = sessionDisplayName(summary.hostId, summary.label, hosts),
                                subtitle = summary.label,
                                badge = badge.first,
                                badgeHighlighted = badge.second,
                                selected = isSelected,
                                enabled = !isUploading,
                                onClick = { selectedTarget = ShareTarget.Session(summary.id) },
                            )
                        }

                        if (hosts.isNotEmpty()) {
                            if (summaries.isNotEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(AGENT_BUTTON_SHAPE)
                                        .clickable(enabled = !isUploading) { hostsExpanded = !hostsExpanded }
                                        .padding(horizontal = 4.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        imageVector = if (hostsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = "Connect a saved host (${hosts.size})",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (hostsExpanded || summaries.isEmpty()) {
                                hosts.forEach { host ->
                                    val isSelected = selectedTarget == ShareTarget.Host(host.id)
                                    ShareTargetRow(
                                        icon = Icons.Outlined.Dns,
                                        title = host.label?.takeIf { it.isNotBlank() } ?: host.hostname,
                                        subtitle = "${host.username}@${host.hostname}:${host.port}",
                                        badge = "New session",
                                        badgeHighlighted = false,
                                        selected = isSelected,
                                        enabled = !isUploading,
                                        onClick = { selectedTarget = ShareTarget.Host(host.id) },
                                    )
                                }
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AgentSectionHeader(title = "Prompt framing")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ShareFramingPreset.entries.forEach { preset ->
                            AgentTabPill(
                                label = preset.label,
                                selected = selectedPreset == preset,
                                onClick = { if (!isUploading) selectedPreset = preset },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    enabled = !isUploading,
                    label = { Text("Note (optional)") },
                    placeholder = { Text("Add extra context or instructions…") },
                    maxLines = 3,
                    shape = AGENT_CARD_SHAPE,
                    modifier = Modifier.fillMaxWidth(),
                )

                uploadingMessage?.let { progressText ->
                    Surface(
                        shape = AGENT_CARD_SHAPE,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = progressText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                errorMessage?.let { err ->
                    Surface(
                        shape = AGENT_CARD_SHAPE,
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                }
            }

            if (selectedTarget != null) {
                val requiresConnect = when (val target = selectedTarget) {
                    is ShareTarget.Host -> true
                    is ShareTarget.Session -> summaries.firstOrNull { it.id == target.sessionId }
                        ?.status != SessionStatus.CONNECTED
                    null -> false
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { submit(sendNow = false) },
                        enabled = !isUploading,
                        shape = AGENT_BUTTON_SHAPE,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (requiresConnect) "Connect & Edit" else "Open in Composer",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    FilledTonalButton(
                        onClick = { submit(sendNow = true) },
                        enabled = !isUploading,
                        shape = AGENT_BUTTON_SHAPE,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Send,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (requiresConnect) "Connect & Send" else "Send now",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private fun defaultShareTarget(
    summaries: List<SessionSummary>,
    hosts: List<HostEntity>,
    preferredSessionId: Long?,
): ShareTarget? {
    preferredSessionId
        ?.takeIf { id -> summaries.any { it.id == id } }
        ?.let { return ShareTarget.Session(it) }
    summaries.firstOrNull { it.status == SessionStatus.CONNECTED }
        ?.let { return ShareTarget.Session(it.id) }
    summaries.firstOrNull()
        ?.let { return ShareTarget.Session(it.id) }
    hosts.firstOrNull()
        ?.let { return ShareTarget.Host(it.id) }
    return null
}

private fun sessionTargetBadge(summary: SessionSummary): Pair<String, Boolean> =
    when (summary.status) {
        SessionStatus.CONNECTED -> when (summary.turnState) {
            AgentTurnState.RESPONSE_NEEDED -> "Needs reply" to true
            AgentTurnState.RUNNING -> "Running" to false
            AgentTurnState.COMPLETED -> "Done" to true
            AgentTurnState.IDLE -> "Connected" to true
        }
        SessionStatus.CONNECTING -> "Connecting" to false
        SessionStatus.RECONNECTING -> "Reconnecting" to false
        SessionStatus.DISCONNECTED -> "Disconnected" to false
        SessionStatus.FAILED -> "Failed" to false
    }

@Composable
private fun SharedAttachmentChip(
    label: String,
    isImage: Boolean,
    canRemove: Boolean,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = if (canRemove) 4.dp else 8.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = if (isImage) Icons.Outlined.Image else Icons.Outlined.AttachFile,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = ChromeMonoFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.heightIn(max = 20.dp),
            )
            if (canRemove) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Remove attachment",
                    modifier = Modifier
                        .size(15.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = onRemove),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ShareTargetRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    badge: String,
    badgeHighlighted: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f)
    }
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    }

    Surface(
        shape = AGENT_CARD_SHAPE,
        color = containerColor,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clip(AGENT_CARD_SHAPE)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = ChromeMonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (badgeHighlighted) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (badgeHighlighted) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
        }
    }
}
