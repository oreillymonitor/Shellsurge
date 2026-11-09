package io.github.lordofpolls.shellwave.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.core.db.dao.ColorSchemeDao
import io.github.lordofpolls.shellwave.core.db.dao.TerminalProfileDao
import io.github.lordofpolls.shellwave.core.db.entities.ColorSchemeEntity
import io.github.lordofpolls.shellwave.core.db.entities.TerminalProfileEntity
import io.github.lordofpolls.shellwave.core.prefs.BellMode
import io.github.lordofpolls.shellwave.core.prefs.BellPreferences
import io.github.lordofpolls.shellwave.core.prefs.SessionLayoutPreferences
import io.github.lordofpolls.shellwave.core.prefs.TerminalInputPreferences
import io.github.lordofpolls.shellwave.core.prefs.rememberPrefState
import io.github.lordofpolls.shellwave.terminal.DEFAULT_COLOR_SCHEME
import io.github.lordofpolls.shellwave.terminal.DEFAULT_TERMINAL_PROFILE
import io.github.lordofpolls.shellwave.ui.design.BackTopBar
import io.github.lordofpolls.shellwave.ui.design.LocalReadableInset
import kotlinx.coroutines.launch

private enum class TerminalSettingsTab(val title: String) {
    BEHAVIOR_INPUT("Behavior & Input"),
    FONT_CURSOR("Font & Cursor"),
    COLOURS("Colours"),
}

/**
 * Terminal configuration screen with a sticky live terminal preview at the top and three structured
 * tabs below (`Behavior & Input`, `Font & Cursor`, `Colours`) so adjustments are immediately visible
 * without long vertical scrolling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalSettingsScreen(
    terminalProfileDao: TerminalProfileDao,
    colorSchemeDao: ColorSchemeDao,
    onOpenKeyBarLayouts: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val bellMode by rememberPrefState { BellPreferences.get(it) }
    val fullWidthTerminal by rememberPrefState { SessionLayoutPreferences.getFullWidthTerminal(it) }
    val keyboardEnhancedInput by rememberPrefState { TerminalInputPreferences.getKeyboardEnhancedInput(it) }
    var selectedTab by rememberSaveable { mutableStateOf(TerminalSettingsTab.BEHAVIOR_INPUT) }

    var profile by remember { mutableStateOf(DEFAULT_TERMINAL_PROFILE) }
    LaunchedEffect(Unit) {
        terminalProfileDao.getDefault()?.let { profile = it }
    }

    // Insert on first edit, update the same row thereafter. Applied optimistically to local state.
    fun save(updated: TerminalProfileEntity) {
        profile = updated
        scope.launch {
            if (updated.id == 0L) {
                profile = updated.copy(id = terminalProfileDao.insert(updated))
            } else {
                terminalProfileDao.update(updated)
            }
        }
    }

    var scheme by remember { mutableStateOf(DEFAULT_COLOR_SCHEME) }
    LaunchedEffect(Unit) {
        colorSchemeDao.getDefault()?.let { scheme = it }
    }

    fun saveScheme(updated: ColorSchemeEntity) {
        scheme = updated
        scope.launch {
            if (updated.id == 0L) {
                scheme = updated.copy(id = colorSchemeDao.insert(updated))
            } else {
                colorSchemeDao.update(updated)
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        BackTopBar(title = "Terminal", onBack = onBack)

        // Sticky header: Live Terminal Preview + 3-Tab Segmented Row
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LocalReadableInset.current)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LiveTerminalPreviewCard(
                profile = profile,
                scheme = scheme,
            )

            val tabs = TerminalSettingsTab.entries
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                tabs.forEachIndexed { index, tab ->
                    SegmentedButton(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size),
                    ) {
                        Text(
                            text = tab.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LocalReadableInset.current)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (selectedTab) {
                TerminalSettingsTab.BEHAVIOR_INPUT -> {
                    SettingsGroupCard(title = "Key bar", icon = Icons.Outlined.Terminal) {
                        SettingsRow(
                            title = "Manage key bar layouts",
                            description = "Custom keys, macros, one or two rows. A layout is assigned per host.",
                            icon = Icons.Outlined.Terminal,
                            chevron = true,
                            onClick = onOpenKeyBarLayouts,
                        )
                    }

                    SettingsGroupCard(title = "Keyboard & Input", icon = Icons.Outlined.Edit) {
                        SettingsSwitch(
                            title = "Keyboard suggestions & voice typing",
                            description = "Allow native keyboard autocorrect, predictive text, swipe typing, and speech-to-text voice dictation in the terminal. When disabled, the keyboard operates in password mode.",
                            checked = keyboardEnhancedInput,
                            onCheckedChange = { TerminalInputPreferences.setKeyboardEnhancedInput(context, it) },
                        )
                    }

                    SettingsGroupCard(title = "Session behavior", icon = Icons.Outlined.Dns) {
                        SettingsSegmentedGroup(
                            label = "Bell",
                            options = BellMode.entries,
                            selected = bellMode,
                            labelOf = BellPreferences::modeName,
                            onSelect = { BellPreferences.set(context, it) },
                        )

                        SettingsCardDivider()

                        SettingsSwitch(
                            title = "Full-width terminal",
                            description = "On a wide or unfolded screen, give the terminal the whole window instead of splitting it with the session list.",
                            checked = fullWidthTerminal,
                            onCheckedChange = { SessionLayoutPreferences.setFullWidthTerminal(context, it) },
                        )
                    }
                }

                TerminalSettingsTab.FONT_CURSOR -> {
                    TerminalProfileFields(
                        profile = profile,
                        onChange = ::save,
                        previewScheme = scheme,
                        showInlinePreview = false,
                    )
                }

                TerminalSettingsTab.COLOURS -> {
                    ColorSchemeFields(
                        scheme = scheme,
                        onChange = ::saveScheme,
                        showInlinePreview = false,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
