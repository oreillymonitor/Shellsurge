package io.github.lordofpolls.shellwave.feature.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.mlkit.genai.common.FeatureStatus
import io.github.lordofpolls.shellwave.BuildConfig
import io.github.lordofpolls.shellwave.core.billing.SupporterState
import io.github.lordofpolls.shellwave.core.billing.SupporterTier
import io.github.lordofpolls.shellwave.core.net.TailscaleCrashDiagnostics
import io.github.lordofpolls.shellwave.core.net.TailscaleManager
import io.github.lordofpolls.shellwave.core.prefs.AgentCli
import io.github.lordofpolls.shellwave.core.prefs.AgentCliPreferences
import io.github.lordofpolls.shellwave.core.prefs.AppLockTimeout
import io.github.lordofpolls.shellwave.core.prefs.BellMode
import io.github.lordofpolls.shellwave.core.prefs.BellPreferences
import io.github.lordofpolls.shellwave.core.prefs.ColourTheme
import io.github.lordofpolls.shellwave.core.prefs.NanoModelPreferences
import io.github.lordofpolls.shellwave.core.prefs.NanoModelTier
import io.github.lordofpolls.shellwave.core.prefs.NanoReleaseChannel
import io.github.lordofpolls.shellwave.core.prefs.NotificationPreferences
import io.github.lordofpolls.shellwave.core.prefs.OutputIdleDelay
import io.github.lordofpolls.shellwave.core.prefs.ReachabilityInterval
import io.github.lordofpolls.shellwave.core.prefs.SecurityPreferences
import io.github.lordofpolls.shellwave.core.prefs.TailscalePreferences
import io.github.lordofpolls.shellwave.core.prefs.ThemeMode
import io.github.lordofpolls.shellwave.core.prefs.UpdatePreferences
import io.github.lordofpolls.shellwave.core.prefs.rememberPrefState
import io.github.lordofpolls.shellwave.feature.session.NanoClientManager
import io.github.lordofpolls.shellwave.feature.session.NanoDiagnostics
import io.github.lordofpolls.shellwave.feature.session.formatModelLabel
import io.github.lordofpolls.shellwave.service.ACTION_RUN_SCRIPT
import io.github.lordofpolls.shellwave.service.EXTRA_SCRIPT_ID
import io.github.lordofpolls.shellwave.service.EXTRA_TOKEN
import io.github.lordofpolls.shellwave.ui.design.LocalReadableInset
import io.github.lordofpolls.shellwave.ui.design.MachineText
import kotlinx.coroutines.launch
import java.time.LocalDate

private const val UPSTREAM_SHELLWAVE_SPONSORS_URL = "https://github.com/sponsors/LordOfPolls"

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
    tailscaleManager: TailscaleManager,
    onOpenTailnetPeers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val commandNotifyEnabled by rememberPrefState { NotificationPreferences.isCommandNotifyEnabled(it) }
    val outputIdleDelay by rememberPrefState { NotificationPreferences.getIdleDelay(it) }
    val notifyOnActiveTab by rememberPrefState { NotificationPreferences.isNotifyOnActiveTab(it) }
    val autoDigestOnReturn by rememberPrefState { NotificationPreferences.isAutoDigestOnReturn(it) }
    val bellMode by rememberPrefState { BellPreferences.get(it) }
    val enabledAgents by rememberPrefState { AgentCliPreferences.getEnabledAgents(it) }
    val nanoTier by rememberPrefState { NanoModelPreferences.getModelTier(it) }
    val nanoChannel by rememberPrefState { NanoModelPreferences.getReleaseChannel(it) }
    val nanoThinking by rememberPrefState { NanoModelPreferences.isThinkingModeEnabled(it) }
    val nanoWarmup by rememberPrefState { NanoModelPreferences.isWarmupEnabled(it) }
    val hideInRecents by rememberPrefState { SecurityPreferences.isHideInRecentsEnabled(it) }
    val appLockEnabled by rememberPrefState { SecurityPreferences.isAppLockEnabled(it) }
    val appLockTimeout by rememberPrefState { SecurityPreferences.getAppLockTimeout(it) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val matchedSections = remember(searchQuery) { matchingSettingsSections(searchQuery) }
    val settingsScope = rememberCoroutineScope()
    var nanoDiag by remember { mutableStateOf<NanoDiagnostics?>(null) }
    var nanoActionStatus by remember { mutableStateOf<String?>(null) }
    var nanoBusy by remember { mutableStateOf(false) }

    LaunchedEffect(nanoTier, nanoChannel) {
        nanoDiag = NanoClientManager.inspectDiagnostics(context, forceRefresh = true)
    }
    var systemNotificationsEnabled by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    DisposableEffect(context) {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                systemNotificationsEnabled =
                    NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
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
                .padding(innerPadding),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search settings…") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = LocalReadableInset.current)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = LocalReadableInset.current)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (matchedSections.isEmpty()) {
                    SettingsGroupCard(title = "No matches", icon = Icons.Outlined.Search) {
                        Text(
                            text = "No settings match \"$searchQuery\".",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { searchQuery = "" }) {
                            Text("Clear search")
                        }
                    }
                }

                // 1. APPEARANCE
                if (SettingsSection.APPEARANCE in matchedSections) {
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
                }

                // 2. TERMINAL & INPUT
                if (SettingsSection.TERMINAL in matchedSections) {
                    SettingsGroupCard(title = "Terminal & Input", icon = Icons.Outlined.Terminal) {
                SettingsRow(
                    title = "Terminal configuration",
                    description = "Font, cursor, scrollback, colour scheme, key bar layouts, bell and full-width mode.",
                    icon = Icons.Outlined.Terminal,
                    chevron = true,
                    onClick = onOpenTerminalSettings,
                )
                    }
                }

                // 3. NOTIFICATIONS
                if (SettingsSection.NOTIFICATIONS in matchedSections) {
                    SettingsGroupCard(title = "Notifications", icon = Icons.Outlined.Notifications) {
                if (!systemNotificationsEnabled) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "Notifications are blocked in Android system settings. Enable them to receive command and output alerts.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            OutlinedButton(
                                onClick = {
                                    runCatching {
                                        val settingsIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                        context.startActivity(settingsIntent)
                                    }
                                },
                            ) {
                                Text("Open notification settings")
                            }
                        }
                    }
                }

                SettingsSwitch(
                    title = "Command & output alerts",
                    description = "Alert when a command finishes or needs input while you're away.",
                    checked = commandNotifyEnabled,
                    onCheckedChange = { NotificationPreferences.setCommandNotifyEnabled(context, it) },
                )

                AnimatedVisibility(visible = commandNotifyEnabled) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        SettingsSegmentedGroup(
                            label = "Idle settle delay",
                            description = "Wait after output settles before alerting. 'OSC only' uses explicit shell/agent hooks.",
                            options = OutputIdleDelay.entries,
                            selected = outputIdleDelay,
                            labelOf = { it.label },
                            onSelect = { NotificationPreferences.setIdleDelay(context, it) },
                        )

                        SettingsSwitch(
                            title = "Alert on active tab too",
                            description = "Also notify while the session is visible on screen.",
                            checked = notifyOnActiveTab,
                            onCheckedChange = { NotificationPreferences.setNotifyOnActiveTab(context, it) },
                        )

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "Works automatically for commands and coding agents. Emit OSC 133;D;$? or OSC 9 / OSC 777 for instant hooks.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }
                }

                SettingsCardDivider()

                SettingsSwitch(
                    title = "Auto-summarize completed tabs",
                    description = "Show a summary capsule automatically when returning to a finished tab.",
                    checked = autoDigestOnReturn,
                    onCheckedChange = { NotificationPreferences.setAutoDigestOnReturn(context, it) },
                )

                SettingsCardDivider()

                SettingsSegmentedGroup(
                    label = "Terminal bell",
                    options = BellMode.entries,
                    selected = bellMode,
                    labelOf = BellPreferences::modeName,
                    onSelect = { BellPreferences.set(context, it) },
                )
                    }
                }

                // 4. CODING AGENTS
                if (SettingsSection.CODING_AGENTS in matchedSections) {
                    SettingsGroupCard(title = "Coding Agents", icon = Icons.Outlined.SmartToy) {
                Text(
                    "Choose which CLI coding harnesses appear in Quick Response and Prompt Composer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AgentCli.entries.forEachIndexed { index, agent ->
                    if (index > 0) SettingsCardDivider()
                    SettingsSwitch(
                        title = agent.displayName,
                        description = agent.description,
                        checked = agent in enabledAgents,
                        onCheckedChange = { enabled ->
                            AgentCliPreferences.setAgentEnabled(context, agent, enabled)
                        },
                    )
                }
                    }
                }

                // 5. ON-DEVICE AI (GEMINI NANO)
                if (SettingsSection.ON_DEVICE_AI in matchedSections) {
                    SettingsGroupCard(title = "On-Device AI (Gemini Nano)", icon = Icons.Outlined.AutoAwesome) {
                val diag = nanoDiag
                val statusLabel = when (diag?.status) {
                    FeatureStatus.AVAILABLE -> "Available"
                    FeatureStatus.DOWNLOADABLE -> "Download required"
                    FeatureStatus.DOWNLOADING -> "Downloading…"
                    FeatureStatus.UNAVAILABLE -> "Unavailable"
                    null -> "Checking AICore…"
                    else -> "Status ${diag.status}"
                }
                val capabilities = buildList {
                    if (diag?.supportsSystemPrompt == true) add("System Prompt")
                    if (diag?.supportsPrefixCaching == true) add("Prefix Cache")
                    if (diag?.supportsThinkingMode == true) add("Thinking Mode")
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "${formatModelLabel(diag?.baseModelName, nanoTier, nanoChannel)} · $statusLabel",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (diag?.status == FeatureStatus.AVAILABLE) {
                            val tokenText = diag.tokenLimit?.let { "Context window: $it tokens" } ?: "Context window: dynamic"
                            val featText = if (capabilities.isNotEmpty()) {
                                " · Features: ${capabilities.joinToString(", ")}"
                            } else {
                                ""
                            }
                            Text(
                                text = "$tokenText$featText",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (!diag?.errorMessage.isNullOrBlank()) {
                            Text(
                                text = diag?.errorMessage.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        nanoActionStatus?.let { msg ->
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (diag?.status == FeatureStatus.DOWNLOADABLE) {
                                OutlinedButton(
                                    enabled = !nanoBusy,
                                    onClick = {
                                        nanoBusy = true
                                        nanoActionStatus = "Starting AICore download…"
                                        settingsScope.launch {
                                            try {
                                                nanoDiag = NanoClientManager.downloadIfNeeded(context) { progress ->
                                                    nanoActionStatus = progress
                                                }
                                            } finally {
                                                nanoBusy = false
                                            }
                                        }
                                    },
                                ) {
                                    Text("Download")
                                }
                            } else if (diag?.status == FeatureStatus.AVAILABLE) {
                                OutlinedButton(
                                    enabled = !nanoBusy,
                                    onClick = {
                                        nanoBusy = true
                                        nanoActionStatus = "Warming up Gemini Nano…"
                                        settingsScope.launch {
                                            try {
                                                val res = NanoClientManager.warmupIfEnabled(context, force = true)
                                                nanoActionStatus = if (res.isSuccess) {
                                                    "Gemini Nano warmed up and ready"
                                                } else {
                                                    "Warmup failed: ${res.exceptionOrNull()?.message ?: "unknown error"}"
                                                }
                                            } finally {
                                                nanoBusy = false
                                            }
                                        }
                                    },
                                ) {
                                    Text("Warm up")
                                }
                            }

                            OutlinedButton(
                                enabled = !nanoBusy,
                                onClick = {
                                    nanoBusy = true
                                    nanoActionStatus = "Refreshing AICore status…"
                                    settingsScope.launch {
                                        try {
                                            nanoDiag = NanoClientManager.inspectDiagnostics(context, forceRefresh = true)
                                            nanoActionStatus = null
                                        } finally {
                                            nanoBusy = false
                                        }
                                    }
                                },
                            ) {
                                Text("Refresh")
                            }
                        }
                    }
                }

                SettingsSegmentedGroup(
                    label = "Model preference",
                    description = "Full favors quality; Fast lowers latency.",
                    options = NanoModelTier.entries,
                    selected = nanoTier,
                    labelOf = { it.label },
                    onSelect = {
                        NanoModelPreferences.setModelTier(context, it)
                        NanoClientManager.invalidate()
                        nanoActionStatus = null
                    },
                )

                SettingsSegmentedGroup(
                    label = "Release channel",
                    description = "Preview uses experimental AICore builds when enabled in Developer Options.",
                    options = NanoReleaseChannel.entries,
                    selected = nanoChannel,
                    labelOf = { it.label },
                    onSelect = {
                        NanoModelPreferences.setReleaseChannel(context, it)
                        NanoClientManager.invalidate()
                        nanoActionStatus = null
                    },
                )

                SettingsCardDivider()

                SettingsSwitch(
                    title = "Thinking mode",
                    description = if (diag?.supportsThinkingMode == true) {
                        "Allow Gemini Nano to reason before refining prompts or commands."
                    } else {
                        "Available when supported by your device's AICore model."
                    },
                    checked = nanoThinking,
                    onCheckedChange = { NanoModelPreferences.setThinkingModeEnabled(context, it) },
                )

                SettingsCardDivider()

                SettingsSwitch(
                    title = "Pre-warm on session open",
                    description = "Preload Gemini Nano when opening a session or composer.",
                    checked = nanoWarmup,
                    onCheckedChange = { NanoModelPreferences.setWarmupEnabled(context, it) },
                )
                    }
                }

                // 6. TAILSCALE (EMBEDDED)
                if (SettingsSection.TAILSCALE in matchedSections) {
                    TailscaleSettingsCard(
                        tailscaleManager = tailscaleManager,
                        onOpenTailnetPeers = onOpenTailnetPeers,
                        uriHandler = uriHandler,
                    )
                }

                // 7. HOSTS & SECURITY
                if (SettingsSection.HOSTS_SECURITY in matchedSections) {
                    SettingsGroupCard(title = "Hosts & Security", icon = Icons.Outlined.Dns) {
                SettingsRow(
                    title = "Trusted host keys",
                    description = "Review fingerprints or remove accepted host keys.",
                    icon = Icons.Outlined.VpnKey,
                    chevron = true,
                    onClick = onOpenKnownHosts,
                )

                SettingsCardDivider()

                SettingsSwitch(
                    title = "Show which hosts are reachable",
                    description = "Probes saved hosts' SSH ports while the app is open. Uses no credentials.",
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
                            description = "Off by default so probing only runs on Wi-Fi.",
                            checked = reachabilityMetered,
                            onCheckedChange = onReachabilityMeteredChange,
                        )

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "Hosts behind a jump host aren't probed — a direct connection to them would fail — so they show \"—\".",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }
                }

                SettingsCardDivider()

                SettingsSwitch(
                    title = "Hide screen in app switcher",
                    description = "Masks terminal content in Android Recents and blocks screenshots.",
                    checked = hideInRecents,
                    onCheckedChange = { SecurityPreferences.setHideInRecentsEnabled(context, it) },
                )

                SettingsCardDivider()

                SettingsSwitch(
                    title = "App lock",
                    description = "Require fingerprint, face, or device PIN when opening or returning to ShellSurge.",
                    checked = appLockEnabled,
                    onCheckedChange = { enabled ->
                        if (!enabled) {
                            SecurityPreferences.setAppLockEnabled(context, false)
                        } else {
                            val canAuth = BiometricManager.from(context).canAuthenticate(
                                BIOMETRIC_STRONG or BIOMETRIC_WEAK or DEVICE_CREDENTIAL,
                            ) == BiometricManager.BIOMETRIC_SUCCESS
                            if (canAuth) {
                                SecurityPreferences.setAppLockEnabled(context, true)
                            } else {
                                Toast.makeText(
                                    context,
                                    "Set up a device screen lock or biometric first to enable App lock.",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    },
                )

                AnimatedVisibility(visible = appLockEnabled) {
                    Column(modifier = Modifier.padding(top = 4.dp)) {
                        SettingsSegmentedGroup(
                            label = "Lock after leaving app",
                            options = AppLockTimeout.entries,
                            selected = appLockTimeout,
                            labelOf = { it.label },
                            onSelect = { SecurityPreferences.setAppLockTimeout(context, it) },
                        )
                    }
                }
                    }
                }

                // 8. AUTOMATION
                if (SettingsSection.AUTOMATION in matchedSections) {
                    SettingsGroupCard(title = "Automation", icon = Icons.Outlined.Code) {
                AutomationSettings(
                    enabled = automationEnabled,
                    onEnabledChange = onAutomationEnabledChange,
                    token = automationToken,
                    onRegenerate = onRegenerateAutomationToken,
                )
                    }
                }

                // 9. BACKUP & RESTORE
                if (SettingsSection.BACKUP_RESTORE in matchedSections) {
                    SettingsGroupCard(title = "Backup & Restore", icon = Icons.Outlined.FolderOpen) {
                ConfigExportRow(onExport = onExportConfig)
                SettingsCardDivider()
                ConfigImportRow(onImport = onImportConfig)
                    }
                }

                // 10. SUPPORT
                if (SettingsSection.SUPPORT in matchedSections) {
                    SettingsGroupCard(title = "Support", icon = Icons.Outlined.FavoriteBorder) {
                Text(
                    text = "ShellSurge is a fork of Shellwave. If you find this app useful, please consider supporting the original Shellwave developer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingsCardDivider()
                SettingsRow(
                    title = "Sponsor Shellwave on GitHub",
                    description = UPSTREAM_SHELLWAVE_SPONSORS_URL,
                    chevron = true,
                    onClick = { runCatching { uriHandler.openUri(UPSTREAM_SHELLWAVE_SPONSORS_URL) } },
                )
                SettingsCardDivider()
                SettingsRow(
                    title = "Shellwave repository",
                    description = UPSTREAM_SHELLWAVE_URL,
                    chevron = true,
                    onClick = { runCatching { uriHandler.openUri(UPSTREAM_SHELLWAVE_URL) } },
                )
                if (supporterState != SupporterState.Unavailable) {
                    SettingsCardDivider()
                    SupporterRow(state = supporterState, onBecomeSupporter = onBecomeSupporter)
                }
                    }
                }

                // 11. ABOUT
                if (SettingsSection.ABOUT in matchedSections) {
                    SettingsGroupCard(title = "About", icon = Icons.Outlined.Info) {
                SettingsRow(
                    title = "Version",
                    description = "ShellSurge SSH & Terminal Client",
                    badgeText = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                )
                SettingsCardDivider()
                AppUpdateSection(uriHandler = uriHandler)
                SettingsCardDivider()
                SettingsRow(
                    title = "Releases page",
                    description = RELEASES_PAGE_URL,
                    chevron = true,
                    onClick = { runCatching { uriHandler.openUri(RELEASES_PAGE_URL) } },
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
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun AppUpdateSection(
    uriHandler: UriHandler,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val autoCheckEnabled by rememberPrefState { UpdatePreferences.isAutoCheckEnabled(it) }
    var updateState by remember { mutableStateOf<UpdateCheckState>(UpdateCheckState.Idle) }

    val rowTitle = when (updateState) {
        is UpdateCheckState.UpdateAvailable -> "Update available"
        else -> "Check for updates"
    }
    val rowDescription = when (val state = updateState) {
        UpdateCheckState.Idle -> "Check GitHub Releases for a newer APK."
        UpdateCheckState.Checking -> "Checking GitHub Releases…"
        is UpdateCheckState.UpToDate ->
            "You're on the latest release (${state.release.tagName})."
        is UpdateCheckState.UpdateAvailable -> {
            val preview = state.release.notesPreview?.let { " — $it" }.orEmpty()
            "${state.release.releaseName} is available$preview"
        }
        is UpdateCheckState.NoReleases -> state.message
        is UpdateCheckState.Error -> state.message
    }
    val badgeText = when (val state = updateState) {
        is UpdateCheckState.UpdateAvailable -> "v${state.release.versionName}"
        is UpdateCheckState.UpToDate -> "Up to date"
        else -> null
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsSwitch(
            title = "Check for updates automatically",
            description = "Check GitHub Releases at most every 12 hours and show a banner on Hosts when a newer APK is available.",
            checked = autoCheckEnabled,
            onCheckedChange = { UpdatePreferences.setAutoCheckEnabled(context, it) },
        )
        SettingsCardDivider()
        SettingsRow(
            title = rowTitle,
            description = rowDescription,
            icon = Icons.Outlined.Download,
            badgeText = badgeText,
            onClick = {
                if (updateState != UpdateCheckState.Checking) {
                    updateState = UpdateCheckState.Checking
                    scope.launch {
                        val result = checkForUpdate(BuildConfig.VERSION_NAME)
                        UpdatePreferences.setLastCheckAtMs(context, System.currentTimeMillis())
                        when (result) {
                            is UpdateCheckState.UpdateAvailable -> {
                                UpdatePreferences.saveCachedRelease(context, result.release)
                                UpdatePreferences.setDismissedVersion(context, null)
                            }
                            is UpdateCheckState.UpToDate -> {
                                UpdatePreferences.clearCachedRelease(context)
                            }
                            else -> {}
                        }
                        updateState = result
                    }
                }
            },
        )

        AnimatedVisibility(visible = updateState is UpdateCheckState.UpdateAvailable) {
            val available = updateState as? UpdateCheckState.UpdateAvailable
            if (available != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            val target = available.release.apkDownloadUrl ?: available.release.htmlUrl
                            runCatching { uriHandler.openUri(target) }
                        },
                    ) {
                        Icon(
                            Icons.Outlined.Download,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Download APK")
                    }
                    OutlinedButton(
                        onClick = {
                            runCatching { uriHandler.openUri(available.release.htmlUrl) }
                        },
                    ) {
                        Text("Release notes")
                    }
                }
            }
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

private fun openBrowserUrl(
    context: Context,
    url: String,
    uriHandler: UriHandler,
): Result<Unit> = runCatching {
    val parsed = url.toUri()
    val browsable = Intent(Intent.ACTION_VIEW, parsed).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(browsable)
    } catch (_: ActivityNotFoundException) {
        val plain = Intent(Intent.ACTION_VIEW, parsed).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(plain)
        } catch (_: Throwable) {
            uriHandler.openUri(url)
        }
    }
}

@Composable
private fun TailscaleSettingsCard(
    tailscaleManager: TailscaleManager,
    onOpenTailnetPeers: () -> Unit,
    uriHandler: UriHandler,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val status by tailscaleManager.status.collectAsState()
    val autoStart by rememberPrefState { TailscalePreferences.isAutoStartEnabled(it) }
    val savedHostname by rememberPrefState { TailscalePreferences.getHostname(it) }
    val savedControlUrl by rememberPrefState { TailscalePreferences.getControlUrl(it) }

    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var hostnameInput by remember(savedHostname) { mutableStateOf(savedHostname) }
    var controlUrlInput by remember(savedControlUrl) { mutableStateOf(savedControlUrl) }
    var authKeyInput by rememberSaveable { mutableStateOf("") }
    var pendingBrowserSignIn by rememberSaveable { mutableStateOf(false) }
    var lastAutoOpenedUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var browserLaunchError by remember { mutableStateOf<String?>(null) }
    var crashReport by remember { mutableStateOf(TailscaleCrashDiagnostics.readLastCrashReport(context)) }

    LaunchedEffect(status.authUrl, status.state, pendingBrowserSignIn) {
        if (status.isRunning || !status.isActive) {
            pendingBrowserSignIn = false
            browserLaunchError = null
            return@LaunchedEffect
        }
        val url = status.authUrl
        if (pendingBrowserSignIn && !url.isNullOrBlank() && url != lastAutoOpenedUrl) {
            lastAutoOpenedUrl = url
            pendingBrowserSignIn = false
            openBrowserUrl(context, url, uriHandler).onFailure { err ->
                browserLaunchError =
                    "Could not open browser (${err.message ?: "no browser app found"}). Use Copy link below."
            }
        }
    }

    val (badgeText, badgeColor) = when {
        status.isRunning -> "Connected" to Color(0xFF2E7D32)
        status.needsLogin -> "Sign in required" to MaterialTheme.colorScheme.primary
        status.isActive -> status.state to MaterialTheme.colorScheme.secondary
        else -> "Stopped" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    SettingsGroupCard(
        title = "Tailscale (Embedded)",
        icon = Icons.Outlined.Hub,
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Userspace tsnet — leaves Android's VPN slot free.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = badgeColor.copy(alpha = 0.14f),
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = badgeColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                if (status.isActive && !status.isRunning && status.authUrl == null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = "Waiting for Tailscale sign-in link…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!status.isRunning) {
                    status.detail?.let { info ->
                        Text(
                            text = info,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (status.isRunning) {
                    val info = listOfNotNull(
                        status.selfIp,
                        status.tailnetName,
                        status.selfHostname,
                    ).joinToString(" • ")
                    if (info.isNotBlank()) {
                        MachineText(info)
                    }
                }
                status.lastError?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                browserLaunchError?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        crashReport?.let { report ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Previous crash log",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.error,
                    )
                    MachineText(
                        text = report,
                        maxLines = 10,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = {
                                scope.launch {
                                    clipboard.setClipEntry(
                                        ClipEntry(
                                            ClipData.newPlainText(
                                                "ShellSurge Tailscale crash log",
                                                report,
                                            ),
                                        ),
                                    )
                                }
                                Toast.makeText(context, "Crash log copied", Toast.LENGTH_SHORT).show()
                            },
                        ) {
                            Icon(
                                Icons.Outlined.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.IconSize),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copy crash log")
                        }
                        TextButton(
                            onClick = {
                                TailscaleCrashDiagnostics.dismissCrashReport(context)
                                crashReport = null
                            },
                        ) {
                            Text("Dismiss")
                        }
                    }
                }
            }
        }

        status.authUrl?.let { url ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Complete Tailscale sign-in in your browser:",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    MachineText(url)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                browserLaunchError = null
                                openBrowserUrl(context, url, uriHandler).onFailure { err ->
                                    val msg =
                                        "Could not open browser (${err.message ?: "no browser app found"}). Use Copy link."
                                    browserLaunchError = msg
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                }
                            },
                        ) {
                            Icon(
                                Icons.Outlined.OpenInBrowser,
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.IconSize),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open browser")
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    clipboard.setClipEntry(
                                        ClipEntry(
                                            ClipData.newPlainText(
                                                "Tailscale sign-in link",
                                                url,
                                            ),
                                        ),
                                    )
                                }
                                Toast.makeText(context, "Sign-in link copied", Toast.LENGTH_SHORT).show()
                            },
                        ) {
                            Icon(
                                Icons.Outlined.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.IconSize),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copy link")
                        }
                    }
                }
            }
        }

        SettingsSwitch(
            title = "Auto-start on Tailscale hosts",
            description = "Starts embedded Tailscale automatically for Tailscale hosts and *.ts.net addresses.",
            checked = autoStart,
            onCheckedChange = { TailscalePreferences.setAutoStartEnabled(context, it) },
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (!status.isActive) {
                Button(
                    onClick = {
                        TailscalePreferences.setEnabled(context, true)
                        pendingBrowserSignIn = authKeyInput.isBlank()
                        lastAutoOpenedUrl = null
                        browserLaunchError = null
                        scope.launch { tailscaleManager.start(authKeyInput) }
                    },
                ) {
                    Text("Connect")
                }
            } else {
                OutlinedButton(
                    onClick = {
                        TailscalePreferences.setEnabled(context, false)
                        pendingBrowserSignIn = false
                        lastAutoOpenedUrl = null
                        browserLaunchError = null
                        scope.launch { tailscaleManager.stop() }
                    },
                ) {
                    Text("Disconnect")
                }
            }

            if (status.isRunning) {
                FilledTonalButton(onClick = onOpenTailnetPeers) {
                    Text("Tailnet devices")
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Text(if (showAdvanced) "Hide advanced" else "Advanced")
            }
            if (status.isActive) {
                TextButton(
                    onClick = {
                        pendingBrowserSignIn = false
                        lastAutoOpenedUrl = null
                        browserLaunchError = null
                        scope.launch { tailscaleManager.logout() }
                    },
                ) {
                    Text("Log out", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        AnimatedVisibility(visible = showAdvanced) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = hostnameInput,
                    onValueChange = {
                        hostnameInput = it
                        TailscalePreferences.setHostname(context, it)
                    },
                    label = { Text("Device hostname") },
                    placeholder = { Text("shellsurge") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = controlUrlInput,
                    onValueChange = {
                        controlUrlInput = it
                        TailscalePreferences.setControlUrl(context, it)
                    },
                    label = { Text("Custom Control URL (Headscale, optional)") },
                    placeholder = { Text("https://headscale.example.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = authKeyInput,
                    onValueChange = { authKeyInput = it },
                    label = { Text("Pre-auth key (optional)") },
                    placeholder = { Text("tskey-auth-...") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                    supportingText = {
                        Text("Used once when connecting instead of interactive browser sign-in.")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
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
