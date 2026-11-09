package io.github.lordofpolls.shellwave.feature.settings

import android.content.ClipData
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.BuildConfig
import io.github.lordofpolls.shellwave.core.billing.SupporterState
import io.github.lordofpolls.shellwave.core.billing.SupporterTier
import io.github.lordofpolls.shellwave.core.prefs.ColourTheme
import io.github.lordofpolls.shellwave.core.prefs.ReachabilityInterval
import io.github.lordofpolls.shellwave.core.prefs.ThemeMode
import io.github.lordofpolls.shellwave.service.ACTION_RUN_SCRIPT
import io.github.lordofpolls.shellwave.service.EXTRA_SCRIPT_ID
import io.github.lordofpolls.shellwave.service.EXTRA_TOKEN
import io.github.lordofpolls.shellwave.ui.design.LocalReadableInset
import io.github.lordofpolls.shellwave.ui.design.MachineText
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * The Settings destination, organized into structured Material 3 preference cards with segmented
 * controls, visual theme selectors, and clear category hierarchy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    colourTheme: ColourTheme,
    onColourThemeChange: (ColourTheme) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    exactSchemeColours: Boolean,
    onExactSchemeColoursChange: (Boolean) -> Unit,
    reachabilityEnabled: Boolean,
    onReachabilityEnabledChange: (Boolean) -> Unit,
    reachabilityInterval: ReachabilityInterval,
    onReachabilityIntervalChange: (ReachabilityInterval) -> Unit,
    reachabilityMetered: Boolean,
    onReachabilityMeteredChange: (Boolean) -> Unit,
    automationEnabled: Boolean,
    onAutomationEnabledChange: (Boolean) -> Unit,
    /** Null until the switch has been turned on for the first time, which is what mints one. */
    automationToken: String?,
    onRegenerateAutomationToken: () -> Unit,
    onOpenTerminalSettings: () -> Unit,
    onOpenKnownHosts: () -> Unit,
    onExportConfig: suspend (Uri) -> Unit,
    onImportConfig: suspend (Uri) -> ConfigImportSummary,
    onOpenLicenses: () -> Unit,
    supporterState: SupporterState,
    onBecomeSupporter: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    Scaffold(
        modifier = modifier,
        // MainActivity's Scaffold already applied the system bar insets without consuming them.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LocalReadableInset.current)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 1. APPEARANCE
            SettingsGroupCard(title = "Appearance", icon = Icons.Outlined.Palette) {
                SettingsSegmentedGroup(
                    label = "Theme mode",
                    options = ThemeMode.entries,
                    selected = themeMode,
                    labelOf = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
                    onSelect = onThemeModeChange,
                )

                ColourThemeSelector(
                    selected = colourTheme,
                    onSelect = onColourThemeChange,
                )

                SettingsCardDivider()

                SettingsSwitch(
                    title = "Exact scheme colours",
                    description = "Show colour schemes exactly as authored, without nudging them toward the app's accent. Only affects the terminal.",
                    checked = exactSchemeColours,
                    onCheckedChange = onExactSchemeColoursChange,
                )
            }

            // 2. TERMINAL & INPUT
            SettingsGroupCard(title = "Terminal & Input", icon = Icons.Outlined.Terminal) {
                SettingsRow(
                    title = "Terminal configuration",
                    description = "Font, cursor, scrollback, colour scheme, key bar layouts, bell and full-width mode.",
                    icon = Icons.Outlined.Terminal,
                    chevron = true,
                    onClick = onOpenTerminalSettings,
                )
            }

            // 3. HOSTS & SECURITY
            SettingsGroupCard(title = "Hosts & Security", icon = Icons.Outlined.Dns) {
                SettingsRow(
                    title = "Trusted host keys",
                    description = "The host keys accepted so far, with a fingerprint to check and a way to forget one.",
                    icon = Icons.Outlined.VpnKey,
                    chevron = true,
                    onClick = onOpenKnownHosts,
                )

                SettingsCardDivider()

                SettingsSwitch(
                    title = "Show which hosts are reachable",
                    description = "Probes each saved host's SSH port and marks it UP or DOWN. No credentials used. Stops when you leave the app.",
                    checked = reachabilityEnabled,
                    onCheckedChange = onReachabilityEnabledChange,
                )

                AnimatedVisibility(visible = reachabilityEnabled) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        SettingsSegmentedGroup(
                            label = "How often",
                            options = ReachabilityInterval.entries,
                            selected = reachabilityInterval,
                            labelOf = { it.label },
                            onSelect = onReachabilityIntervalChange,
                        )

                        SettingsSwitch(
                            title = "Allow on mobile data",
                            description = "Off by default, so probing only happens on unmetered networks such as Wi-Fi.",
                            checked = reachabilityMetered,
                            onCheckedChange = onReachabilityMeteredChange,
                        )

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "Hosts behind a jump host aren't probed - a direct connection to them would fail - so they show \"—\".",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }
                }
            }

            // 4. AUTOMATION
            SettingsGroupCard(title = "Automation", icon = Icons.Outlined.Code) {
                AutomationSettings(
                    enabled = automationEnabled,
                    onEnabledChange = onAutomationEnabledChange,
                    token = automationToken,
                    onRegenerate = onRegenerateAutomationToken,
                )
            }

            // 5. BACKUP & RESTORE
            SettingsGroupCard(title = "Backup & Restore", icon = Icons.Outlined.FolderOpen) {
                ConfigExportRow(onExport = onExportConfig)
                SettingsCardDivider()
                ConfigImportRow(onImport = onImportConfig)
            }

            // 6. SUPPORT (when available)
            if (supporterState != SupporterState.Unavailable) {
                SettingsGroupCard(title = "Support", icon = Icons.Outlined.FavoriteBorder) {
                    SupporterRow(state = supporterState, onBecomeSupporter = onBecomeSupporter)
                }
            }

            // 7. ABOUT
            SettingsGroupCard(title = "About", icon = Icons.Outlined.Info) {
                SettingsRow(
                    title = "Version",
                    description = "ShellSurge SSH & Terminal Client",
                    badgeText = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                )
                SettingsCardDivider()
                SettingsRow(
                    title = "Source code",
                    description = SOURCE_REPOSITORY_URL,
                    chevron = true,
                    onClick = { runCatching { uriHandler.openUri(SOURCE_REPOSITORY_URL) } },
                )
                SettingsCardDivider()
                SettingsRow(
                    title = "Licences",
                    description = "Open-source software notices and GPLv3 licence text.",
                    chevron = true,
                    onClick = onOpenLicenses,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ColourThemeSelector(
    selected: ColourTheme,
    onSelect: (ColourTheme) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Colour palette",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ColourTheme.entries.forEach { option ->
                val isSelected = option == selected
                val (title, subtitle, dotColor) = when (option) {
                    ColourTheme.DYNAMIC -> Triple(
                        "Dynamic",
                        "Material You",
                        MaterialTheme.colorScheme.primary,
                    )
                    ColourTheme.SCHEMATIC -> Triple(
                        "Schematic",
                        "Lime accent",
                        Color(0xFFA3E635),
                    )
                    ColourTheme.NOTHING -> Triple(
                        "Nothing",
                        "Dot-matrix",
                        Color(0xFFD71921),
                    )
                }
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.RadioButton) { onSelect(option) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f)
                    },
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(dotColor, CircleShape),
                            )
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The app-wide automation gate: the switch, the token, and the two lines a user needs to wire up a
 * Tasker task without leaving the app to look up an action name.
 */
@Composable
private fun AutomationSettings(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    token: String?,
    onRegenerate: () -> Unit
) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmingRegenerate by rememberSaveable { mutableStateOf(false) }

    SettingsSwitch(
        title = "Let other apps run scripts",
        description = "Lets Tasker run a script over SSH with no confirmation. Each script must opt in, and the request needs the token below.",
        checked = enabled,
        onCheckedChange = onEnabledChange,
    )

    AnimatedVisibility(visible = enabled && token != null) {
        if (token != null) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "AUTOMATION TOKEN",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        // A substituted lookalike glyph here would produce a silent, baffling failure in
                        // another app.
                        MachineText(token, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(
                                        ClipData.newPlainText(
                                            "ShellSurge automation token",
                                            token
                                        )
                                    )
                                )
                            }
                            // Android 13+ confirms clipboard writes itself.
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                Toast.makeText(context, "Token copied", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy token")
                    }
                    OutlinedButton(onClick = { confirmingRegenerate = true }) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Regenerate")
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "In Tasker: Send Intent, Target Activity, action $ACTION_RUN_SCRIPT, with extras " +
                                "$EXTRA_SCRIPT_ID (the script's id, shown when you edit it) and $EXTRA_TOKEN (the token above).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
        }
    }

    if (confirmingRegenerate) {
        AlertDialog(
            onDismissRequest = { confirmingRegenerate = false },
            title = { Text("Generate a new token?") },
            text = { Text("The old token stops working immediately - update any tasks using it.") },
            confirmButton = {
                TextButton(onClick = {
                    onRegenerate()
                    confirmingRegenerate = false
                }) {
                    Text("Generate")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmingRegenerate = false
                }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SupporterRow(state: SupporterState, onBecomeSupporter: (String) -> Unit) {
    when (state) {
        is SupporterState.Loading, is SupporterState.Unavailable -> Unit

        is SupporterState.Purchasable -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsRow(
                title = "Become a supporter",
                description = "A one-time purchase that unlocks nothing - just a way to say thanks. " +
                    "Pick whatever feels right.",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.tiers.forEach { tier: SupporterTier ->
                    FilledTonalButton(onClick = { onBecomeSupporter(tier.purchaseOptionId) }) {
                        Text(tier.priceLabel)
                    }
                }
            }
        }

        is SupporterState.Supporter -> SettingsRow(
            title = "You're a supporter",
            description = "Thank you!",
        )
    }
}

/** `CreateDocument` rather than a fixed path: no storage permission, and the user picks where it lands. */
@Composable
private fun ConfigExportRow(onExport: suspend (Uri) -> Unit) {
    val scope = rememberCoroutineScope()
    var outcome by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            outcome = null
            scope.launch {
                outcome =
                    runCatching { onExport(uri) }
                        .fold(
                            { "Configuration exported." },
                            { it.message ?: "Could not write the export." },
                        )
            }
        }

    SettingsRow(
        title = "Export configuration",
        description = "Writes your hosts, tunnels, scripts and settings to a JSON file. Passwords, " +
                "private keys and passphrases are not included - they stay sealed in this device's keystore.",
        icon = Icons.Outlined.FolderOpen,
        chevron = true,
        onClick = { launcher.launch("shellsurge-config-${LocalDate.now()}.json") },
    )
    outcome?.let {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ConfigImportRow(onImport: suspend (Uri) -> ConfigImportSummary) {
    val scope = rememberCoroutineScope()
    var confirming by rememberSaveable { mutableStateOf(false) }
    var outcome by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            outcome = emptyList()
            scope.launch {
                outcome =
                    runCatching { onImport(uri) }
                        .fold(
                            { it.lines() },
                            { listOf(it.message ?: "Could not read that file.") },
                        )
            }
        }

    SettingsRow(
        title = "Import configuration",
        description = "Reads a file written by Export. Hosts, tunnels, scripts and layouts are added " +
                "to what you already have; app-wide settings are replaced. Nothing is deleted.",
        icon = Icons.Outlined.FileOpen,
        chevron = true,
        onClick = { confirming = true },
    )
    if (outcome.isNotEmpty()) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                outcome.forEach {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Import a configuration?") },
            text = {
                Text(
                    "Everything in the file is added alongside what you already have, so hosts and " +
                            "scripts you kept will appear twice if the file also holds them. Your " +
                            "app-wide settings, terminal profile and colour scheme are replaced by the " +
                            "file's. Passwords and keys are not " +
                            "in an export, so imported hosts need their secret entered again.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    launcher.launch(arrayOf("*/*"))
                }) {
                    Text("Choose file")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }
}
