package io.github.lordofpolls.shellwave.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import io.github.lordofpolls.shellwave.ssh.KeyboardInteractivePrompt
import io.github.lordofpolls.shellwave.ui.design.MachineText

@Composable
fun KeyboardInteractiveDialog(prompt: KeyboardInteractivePrompt, modifier: Modifier = Modifier) {
    var response by remember(prompt) { mutableStateOf("") }
    var passwordVisible by remember(prompt) { mutableStateOf(false) }

    AlertDialog(
        modifier = modifier,
        onDismissRequest = { prompt.respond("") },
        title = { Text(prompt.name.ifBlank { "Server verification" }) },
        text = {
            Column {
                if (prompt.sessionLabel.isNotBlank()) {
                    // Which server is asking: with several sessions authenticating at once, that is
                    // the point.
                    MachineText(prompt.sessionLabel, style = MaterialTheme.typography.labelMedium)
                }
                if (prompt.instruction.isNotBlank()) Text(prompt.instruction)
                Text(prompt.prompt)
                OutlinedTextField(
                    value = response,
                    onValueChange = { response = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = if (!prompt.echo) {
                        {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = if (passwordVisible) "Hide response" else "Show response",
                                )
                            }
                        }
                    } else null,
                    visualTransformation = if (prompt.echo || passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = if (prompt.echo) KeyboardType.Text else KeyboardType.Password,
                    ),
                )
            }
        },
        confirmButton = { TextButton(onClick = { prompt.respond(response) }) { Text("Submit") } },
        dismissButton = { TextButton(onClick = { prompt.respond("") }) { Text("Cancel") } },
    )
}
