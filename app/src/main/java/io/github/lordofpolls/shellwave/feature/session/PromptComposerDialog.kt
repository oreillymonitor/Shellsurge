package io.github.lordofpolls.shellwave.feature.session

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Multi-line prompt composer for drafting commands or natural-language prompts
 * for AI CLI agents (like antigravity-cli or opencode) using full native mobile
 * keyboard conveniences (voice typing, swipe, autocorrect, spellcheck).
 */
@Composable
internal fun PromptComposerDialog(
    onSend: (prompt: String, sendEnter: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var sendEnter by remember { mutableStateOf(true) }

    val aiShortcuts = listOf(
        "/goal",
        "/plan",
        "/compact",
        "/browser",
        "/grill-me",
        "/boost",
        "/learn",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Outlined.EditNote,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text("Prompt Composer") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Agent prompt / instruction") },
                    placeholder = { Text("Type, swipe, or use voice dictation…") },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        autoCorrectEnabled = true,
                        keyboardType = KeyboardType.Text,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 130.dp, max = 240.dp),
                )

                // Quick insertion chips for AI CLI workflows
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    aiShortcuts.forEach { shortcut ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                text = if (text.isEmpty()) {
                                    "$shortcut "
                                } else if (text.endsWith(" ") || text.endsWith("\n")) {
                                    "$text$shortcut "
                                } else {
                                    "$text $shortcut "
                                }
                            },
                            label = { Text(shortcut, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Checkbox(
                        checked = sendEnter,
                        onCheckedChange = { sendEnter = it },
                    )
                    Text(
                        "Execute immediately (send Enter)",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSend(text, sendEnter)
                    onDismiss()
                },
                enabled = text.isNotBlank(),
            ) {
                Text("Send")
            }
        },
        dismissButton = {
            Row {
                if (text.isNotEmpty()) {
                    TextButton(onClick = { text = "" }) {
                        Text("Clear")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
    )
}
