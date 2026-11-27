package io.github.lordofpolls.shellwave.feature.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.core.db.entities.ScriptEntity
import io.github.lordofpolls.shellwave.feature.scripts.ScriptMode

/**
 * "Run script here". Both modes that can act on an open session are offered, so each row carries a
 * subtitle saying which one it is (typed into the live shell vs. captured in a separate `exec`
 * channel).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScriptPickerDialog(
    scripts: List<ScriptEntity>,
    onPick: (ScriptEntity) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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
                icon = Icons.Outlined.PlayArrow,
                title = "Run a script here",
                subtitle = "${scripts.size} available",
                onDismiss = onDismiss,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SessionSheetCard {
                    scripts.forEach { script ->
                        val isCapture =
                            runCatching { ScriptMode.valueOf(script.mode) }.getOrNull() == ScriptMode.CAPTURE
                        SessionDrawerActionRow(
                            icon = Icons.Outlined.PlayArrow,
                            label = script.name,
                            subtitle = if (isCapture) {
                                "Runs separately, output captured"
                            } else {
                                "Typed into this session"
                            },
                            onClick = { onPick(script) },
                        )
                    }
                }
            }
        }
    }
}
