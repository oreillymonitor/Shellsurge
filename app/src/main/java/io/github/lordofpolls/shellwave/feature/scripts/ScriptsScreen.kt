package io.github.lordofpolls.shellwave.feature.scripts

import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import io.github.lordofpolls.shellwave.R
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.core.db.entities.ScriptEntity
import io.github.lordofpolls.shellwave.core.prefs.WidgetPreferences
import io.github.lordofpolls.shellwave.feature.glance.ScriptTileService
import io.github.lordofpolls.shellwave.feature.glance.ScriptWidget
import io.github.lordofpolls.shellwave.ui.design.ActionTrayChip
import io.github.lordofpolls.shellwave.ui.design.EmptyState
import io.github.lordofpolls.shellwave.ui.design.LocalReadableInset
import io.github.lordofpolls.shellwave.ui.design.MachineText
import io.github.lordofpolls.shellwave.ui.design.ScriptCard
import kotlinx.coroutines.launch

private enum class ScriptsTab { SAVED, TEMPLATES }

/**
 * The Scripts destination, organized into two inline tabs (`My scripts` and `Templates`) so
 * built-in starter templates are directly browsable without a popup menu or dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptsScreen(
    scripts: List<ScriptEntity>,
    hosts: List<HostEntity>,
    onRun: (ScriptEntity) -> Unit,
    onAdd: () -> Unit,
    onEdit: (ScriptEntity) -> Unit,
    onDelete: (ScriptEntity) -> Unit,
    onHistory: (ScriptEntity) -> Unit,
    modifier: Modifier = Modifier,
    onAddFromTemplate: (ScriptTemplate) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // One pending delete regardless of which card was tapped: a card reports intent, the screen
    // owns "did you mean it".
    var pendingDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }

    // WidgetPreferences has no change-notification Flow, so this is re-read through `pinChanges`
    // after every write.
    var pinChanges by remember { mutableStateOf(0) }
    var activeTab by rememberSaveable { mutableStateOf(ScriptsTab.SAVED) }

    Scaffold(
        modifier = modifier,
        // MainActivity's Scaffold already applied the system bar insets without consuming them.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Scripts") },
                windowInsets = WindowInsets(0, 0, 0, 0),
                actions = {
                    IconButton(onClick = onAdd) {
                        Icon(Icons.Outlined.Add, contentDescription = "Add script")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp + LocalReadableInset.current, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionTrayChip(
                    label = "My scripts (${scripts.size})",
                    selected = activeTab == ScriptsTab.SAVED,
                    onClick = { activeTab = ScriptsTab.SAVED },
                )
                ActionTrayChip(
                    label = "Templates (${ScriptTemplates.ALL.size})",
                    selected = activeTab == ScriptsTab.TEMPLATES,
                    onClick = { activeTab = ScriptsTab.TEMPLATES },
                )
            }

            when (activeTab) {
                ScriptsTab.SAVED -> {
                    if (scripts.isEmpty()) {
                        EmptyState(
                            message = "No scripts yet.",
                            actionLabel = "Browse templates",
                            onAction = { activeTab = ScriptsTab.TEMPLATES },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(
                                start = 16.dp + LocalReadableInset.current,
                                end = 16.dp + LocalReadableInset.current,
                                top = 8.dp,
                                bottom = 16.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(scripts, key = { it.id }) { script ->
                                val capture = isCapture(script.mode)
                                val runnableInBackground = capture && script.targetHostId != null
                                val pinned = remember(script.id, pinChanges) {
                                    WidgetPreferences.isPinned(context, script.id)
                                }
                                val isQsTile = remember(script.id, pinChanges) {
                                    WidgetPreferences.qsTileScriptId(context) == script.id
                                }

                                ScriptCard(
                                    name = script.name,
                                    modeAndTarget = scriptModeAndTarget(script, hosts),
                                    accent = script.color?.let { Color(it) },
                                    pinnedToWidget = pinned,
                                    isQsTile = isQsTile,
                                    onRun = { onRun(script) },
                                    onEdit = { onEdit(script) },
                                    onDelete = { pendingDeleteId = script.id },
                                    onHistory = if (capture) ({ onHistory(script) }) else null,
                                    onPinToWidget =
                                        if (!runnableInBackground && !pinned) {
                                            null
                                        } else {
                                            {
                                                WidgetPreferences.setPinned(context, script.id, !pinned)
                                                pinChanges++
                                                scope.launch { ScriptWidget().updateAll(context) }
                                            }
                                        },
                                    onUseForQsTile =
                                        if (!runnableInBackground && !isQsTile) {
                                            null
                                        } else {
                                            {
                                                WidgetPreferences.setQsTileScriptId(
                                                    context,
                                                    if (isQsTile) null else script.id,
                                                )
                                                pinChanges++
                                                if (!isQsTile && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                    context.getSystemService(StatusBarManager::class.java)
                                                        ?.requestAddTileService(
                                                            ComponentName(context, ScriptTileService::class.java),
                                                            script.name,
                                                            Icon.createWithResource(context, R.drawable.ic_stat_shellwave),
                                                            context.mainExecutor,
                                                        ) {}
                                                }
                                            }
                                        },
                                )
                            }
                        }
                    }
                }

                ScriptsTab.TEMPLATES -> {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(
                            start = 16.dp + LocalReadableInset.current,
                            end = 16.dp + LocalReadableInset.current,
                            top = 8.dp,
                            bottom = 16.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(ScriptTemplates.ALL, key = { it.name }) { template ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            Text(
                                                text = template.name,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            Text(
                                                text = template.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        FilledTonalButton(
                                            onClick = { onAddFromTemplate(template) },
                                        ) {
                                            Text("Use")
                                        }
                                    }
                                    MachineText(
                                        text = template.snippet,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    scripts.firstOrNull { it.id == pendingDeleteId }?.let { script ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("Delete script?") },
            text = { Text("Removes \"${script.name}\" and its run history. Can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(script)
                    pendingDeleteId = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingDeleteId = null
                }) { Text("Cancel") }
            },
        )
    }
}

/**
 * The machine meta line: mode and target host, as `CAPTURE · betty`. The host resolves to the same
 * `label ?: hostname` every other surface shows, so one host never appears under two names.
 *
 * A `SEND_TO_CURRENT` script has no target host by definition and renders its mode alone rather
 * than a dangling separator. An unrecognised stored mode renders verbatim, matching how
 * [ScriptMode] decoding degrades elsewhere instead of throwing on a hand-edited row.
 *
 * For the other two modes a missing target is "ask each run", whether the user left it unset or
 * Room's `SET_NULL` cleared it after a host was deleted. That phrase stays out of the mono
 * register: the app's wording, not machine truth.
 */
fun scriptModeAndTarget(script: ScriptEntity, hosts: List<HostEntity>): String {
    val decoded = runCatching { ScriptMode.valueOf(script.mode) }.getOrNull()
    val mode = decoded?.label()?.uppercase() ?: script.mode
    val host = script.targetHostId?.let { id -> hosts.firstOrNull { it.id == id } }
    val target =
        host?.let { it.label ?: it.hostname }
            ?: "Ask each run".takeIf { script.targetHostId == null && decoded != null && decoded != ScriptMode.SEND_TO_CURRENT }
    return if (target == null) mode else "$mode · $target"
}

private fun isCapture(mode: String): Boolean =
    runCatching { ScriptMode.valueOf(mode) }.getOrNull() == ScriptMode.CAPTURE
