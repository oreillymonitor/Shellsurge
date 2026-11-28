package io.github.lordofpolls.shellwave.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lordofpolls.shellwave.core.db.entities.ColorSchemeEntity
import io.github.lordofpolls.shellwave.core.db.entities.TerminalProfileEntity
import io.github.lordofpolls.shellwave.terminal.BuiltInColorSchemes
import io.github.lordofpolls.shellwave.terminal.DEFAULT_COLOR_SCHEME
import io.github.lordofpolls.shellwave.terminal.FONT_SIZE_SP_RANGE
import io.github.lordofpolls.shellwave.terminal.LINE_HEIGHT_MULTIPLIER_RANGE
import io.github.lordofpolls.shellwave.terminal.SCROLLBACK_LINES_RANGE
import io.github.lordofpolls.shellwave.terminal.TerminalCursorStyle
import io.github.lordofpolls.shellwave.terminal.TerminalFontFamily
import io.github.lordofpolls.shellwave.terminal.ansiColors
import io.github.lordofpolls.shellwave.terminal.autoCursorColorFor
import io.github.lordofpolls.shellwave.terminal.importColorScheme
import io.github.lordofpolls.shellwave.terminal.isLightBackground
import io.github.lordofpolls.shellwave.terminal.loadGoghThemes
import io.github.lordofpolls.shellwave.terminal.parseHexColorOrNull
import io.github.lordofpolls.shellwave.terminal.resolveTerminalTypeface
import io.github.lordofpolls.shellwave.terminal.selectionHighlightColor
import io.github.lordofpolls.shellwave.terminal.toHexColorString
import io.github.lordofpolls.shellwave.ui.design.ChromeMonoFontFamily
import io.github.lordofpolls.shellwave.ui.design.ColorPickerDialog
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

private enum class SchemeFilter(val label: String) {
    ALL("All"),
    DARK("Dark"),
    LIGHT("Light"),
}

/**
 * The terminal-profile and colour-scheme field editors, shared by [TerminalSettingsScreen] and
 * AddEditHostScreen's per-host override so there is no second, drifting copy of every slider,
 * selector, and hex field.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TerminalProfileFields(
    profile: TerminalProfileEntity,
    onChange: (TerminalProfileEntity) -> Unit,
    previewScheme: ColorSchemeEntity = DEFAULT_COLOR_SCHEME,
    showInlinePreview: Boolean = true,
) {
    val context = LocalContext.current
    val pickCustomFont =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                onChange(
                    profile.copy(
                        fontFamily = TerminalFontFamily.CUSTOM.name,
                        customFontUri = uri.toString()
                    )
                )
            }
        }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (showInlinePreview) {
            LiveTerminalPreviewCard(profile = profile, scheme = previewScheme)
        }

        SettingsGroupCard(title = "Typography") {
            Text(
                text = "Font family",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            )
            val currentFamily = TerminalFontFamily.fromStored(profile.fontFamily)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TerminalFontFamily.entries.forEach { family ->
                    val selected = currentFamily == family
                    FilterChip(
                        selected = selected,
                        onClick = {
                            if (family == TerminalFontFamily.CUSTOM) {
                                pickCustomFont.launch(arrayOf("*/*"))
                            } else {
                                onChange(profile.copy(fontFamily = family.name, customFontUri = null))
                            }
                        },
                        label = { Text(family.displayName) },
                    )
                }
            }

            if (currentFamily == TerminalFontFamily.CUSTOM) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = profile.customFontUri?.let {
                                "Using: ${displayNameForUri(context, it) ?: it.substringAfterLast('/')}"
                            } ?: "No file chosen yet",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { pickCustomFont.launch(arrayOf("*/*")) }) {
                            Text("Choose font file...")
                        }
                    }
                }
            }

            SettingsCardDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Font size",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                )
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = "${profile.fontSizeSp.roundToInt()}sp",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
            }
            Slider(
                value = profile.fontSizeSp,
                onValueChange = { onChange(profile.copy(fontSizeSp = it)) },
                valueRange = FONT_SIZE_SP_RANGE,
                steps = (FONT_SIZE_SP_RANGE.endInclusive - FONT_SIZE_SP_RANGE.start).roundToInt() - 1,
                modifier = Modifier.semantics { contentDescription = "Font size" },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Line height",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                )
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = "${"%.1f".format(profile.lineHeightMultiplier)}x",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
            }
            Slider(
                value = profile.lineHeightMultiplier,
                onValueChange = { onChange(profile.copy(lineHeightMultiplier = it)) },
                valueRange = LINE_HEIGHT_MULTIPLIER_RANGE,
                modifier = Modifier.semantics { contentDescription = "Line height" },
            )
        }

        SettingsGroupCard(title = "Cursor & Scrollback") {
            val currentCursorStyle = TerminalCursorStyle.fromStored(profile.cursorStyle)
            SettingsSegmentedGroup(
                label = "Cursor style",
                options = TerminalCursorStyle.entries,
                selected = currentCursorStyle,
                labelOf = { it.displayName },
                onSelect = { onChange(profile.copy(cursorStyle = it.name)) },
            )

            SettingsSwitch(
                title = "Cursor blink",
                description = "Pulse the terminal cursor when the session is focused.",
                checked = profile.cursorBlink,
                onCheckedChange = { onChange(profile.copy(cursorBlink = it)) },
            )

            SettingsCardDivider()

            ScrollbackField(
                scrollbackLines = profile.scrollbackLines,
                onCommit = { onChange(profile.copy(scrollbackLines = it)) },
            )
            Text(
                "Scrollback depth applies to newly-opened sessions, not ones already connected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * [onChange] receives a ready-to-persist `ColorSchemeEntity` for every edit.
 */
@Composable
internal fun ColorSchemeFields(
    scheme: ColorSchemeEntity,
    onChange: (ColorSchemeEntity) -> Unit,
    showInlinePreview: Boolean = true,
) {
    fun edit(transform: ColorSchemeEntity.() -> ColorSchemeEntity) {
        onChange(scheme.transform().copy(isBuiltIn = false, name = "Custom"))
    }
    var pendingBuiltInName by rememberSaveable { mutableStateOf<String?>(null) }
    var schemeFilter by rememberSaveable { mutableStateOf(SchemeFilter.ALL) }
    var schemeSearch by rememberSaveable { mutableStateOf("") }
    var importDialogOpen by rememberSaveable { mutableStateOf(false) }
    var goghSheetOpen by rememberSaveable { mutableStateOf(false) }

    val filteredSchemes = remember(schemeFilter, schemeSearch) {
        val q = schemeSearch.trim().lowercase()
        BuiltInColorSchemes.ALL.filter { preset ->
            val light = isLightBackground(preset.background)
            val matchesTone = when (schemeFilter) {
                SchemeFilter.ALL -> true
                SchemeFilter.DARK -> !light
                SchemeFilter.LIGHT -> light
            }
            val matchesQuery = q.isEmpty() || preset.name.lowercase().contains(q)
            matchesTone && matchesQuery
        }
    }

    @Composable
    fun SchemeColorField(
        label: String,
        color: Int,
        withColor: ColorSchemeEntity.(Int) -> ColorSchemeEntity,
        modifier: Modifier = Modifier,
        trailing: (@Composable () -> Unit)? = null,
    ) = ColorHexField(
        label = label,
        color = color,
        onValid = { edit { withColor(it) } },
        modifier = modifier,
        preview = { ColorSchemePreview(scheme = scheme.withColor(it), modifier = Modifier.fillMaxWidth()) },
        trailing = trailing,
    )

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (showInlinePreview) {
            ColorSchemePreview(scheme = scheme, modifier = Modifier.fillMaxWidth())
        }

        SettingsGroupCard(title = "Theme presets") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${BuiltInColorSchemes.ALL.size} built-in · ${filteredSchemes.size} shown",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (!scheme.isBuiltIn) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Text(
                            text = scheme.name.takeIf { it.isNotBlank() && it != "Custom" } ?: "Custom (edited)",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { goghSheetOpen = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Palette,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Gogh library", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
                OutlinedButton(
                    onClick = { importDialogOpen = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FileOpen,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Import", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SchemeFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = schemeFilter == filter,
                        onClick = { schemeFilter = filter },
                        label = { Text(filter.label) },
                    )
                }
            }

            OutlinedTextField(
                value = schemeSearch,
                onValueChange = { schemeSearch = it },
                placeholder = { Text("Filter themes…") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (schemeSearch.isNotEmpty()) {
                        IconButton(onClick = { schemeSearch = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Clear filter")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            if (filteredSchemes.isEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "No themes match \"$schemeSearch\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = {
                        schemeFilter = SchemeFilter.ALL
                        schemeSearch = ""
                    }) {
                        Text("Reset")
                    }
                }
            }

            filteredSchemes.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pair.forEach { builtin ->
                        val isSelected = scheme.isBuiltIn && scheme.name == builtin.name
                        ColorSchemeSwatchCard(
                            scheme = builtin,
                            isSelected = isSelected,
                            onClick = {
                                if (scheme.isBuiltIn) onChange(builtin.copy(id = scheme.id))
                                else pendingBuiltInName = builtin.name
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (pair.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        BuiltInColorSchemes.ALL.firstOrNull { it.name == pendingBuiltInName }?.let { builtin ->
            AlertDialog(
                onDismissRequest = { pendingBuiltInName = null },
                title = { Text("Replace your custom colours?") },
                text = { Text("Your edited colours are overwritten with ${builtin.name}. This can't be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        pendingBuiltInName = null
                        onChange(builtin.copy(id = scheme.id))
                    }) { Text("Replace") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingBuiltInName = null }) { Text("Cancel") }
                },
            )
        }

        if (importDialogOpen) {
            ImportColorSchemeDialog(
                onImport = { imported ->
                    importDialogOpen = false
                    onChange(imported.copy(id = scheme.id))
                },
                onDismiss = { importDialogOpen = false },
            )
        }

        if (goghSheetOpen) {
            GoghLibrarySheet(
                currentScheme = scheme,
                onSelect = { selected ->
                    goghSheetOpen = false
                    onChange(selected.copy(id = scheme.id))
                },
                onDismiss = { goghSheetOpen = false },
            )
        }

        SettingsGroupCard(title = "Core terminal colours") {
            SchemeColorField("Foreground", scheme.foreground, { copy(foreground = it) })
            SchemeColorField("Background", scheme.background, { copy(background = it) })
            SchemeColorField(
                "Cursor",
                scheme.cursor,
                { copy(cursor = it) },
                trailing = {
                    TextButton(onClick = { edit { copy(cursor = autoCursorColorFor(background)) } }) {
                        Text("Auto")
                    }
                },
            )
            SchemeColorField("Selection", scheme.selection, { copy(selection = it) })
        }

        SettingsGroupCard(title = "ANSI 16-colour palette") {
            val ansi = scheme.ansiColors()
            Text(
                text = "Standard (0–7) & Bright (8–15) ANSI colours. Tap any circle swatch for the visual colour picker.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (baseIndex in 0 until 8) {
                val brightIndex = baseIndex + 8
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    SchemeColorField(
                        label = "$baseIndex ${ANSI_COLOR_NAMES[baseIndex]}",
                        color = ansi[baseIndex],
                        withColor = { newColor ->
                            val updatedAnsi = ansiColors()
                            updatedAnsi[baseIndex] = newColor
                            copy(ansiColorsCsv = updatedAnsi.joinToString(",") { it.toHexColorString() })
                        },
                        modifier = Modifier.weight(1f),
                    )
                    SchemeColorField(
                        label = "$brightIndex ${ANSI_COLOR_NAMES[brightIndex]}",
                        color = ansi[brightIndex],
                        withColor = { newColor ->
                            val updatedAnsi = ansiColors()
                            updatedAnsi[brightIndex] = newColor
                            copy(ansiColorsCsv = updatedAnsi.joinToString(",") { it.toHexColorString() })
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
internal fun ScrollbackField(scrollbackLines: Int, onCommit: (Int) -> Unit) {
    var text by remember(scrollbackLines) { mutableStateOf(scrollbackLines.toString()) }
    val inRange = text.toIntOrNull()?.let { it in SCROLLBACK_LINES_RANGE } == true
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed.filter(Char::isDigit)
            text.toIntOrNull()?.takeIf { it in SCROLLBACK_LINES_RANGE }?.let(onCommit)
        },
        label = { Text("Scrollback lines") },
        supportingText = { Text("${SCROLLBACK_LINES_RANGE.first}-${SCROLLBACK_LINES_RANGE.last}") },
        isError = !inRange,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Standard xterm 16-colour naming, in ColorSchemeEntity.ansiColorsCsv's order. Editor labels only. */
internal val ANSI_COLOR_NAMES =
    listOf(
        "Black",
        "Red",
        "Green",
        "Yellow",
        "Blue",
        "Magenta",
        "Cyan",
        "White",
        "Bright black",
        "Bright red",
        "Bright green",
        "Bright yellow",
        "Bright blue",
        "Bright magenta",
        "Bright cyan",
        "Bright white",
    )

@Composable
internal fun ColorHexField(
    label: String,
    color: Int,
    onValid: (Int) -> Unit,
    modifier: Modifier = Modifier,
    preview: (@Composable (Int) -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    var text by remember(color) { mutableStateOf(color.toHexColorString()) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val parsed = parseHexColorOrNull(text)
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .clickable(onClickLabel = "Pick a colour", role = Role.Button) { picking = true }
                    .semantics { contentDescription = "$label colour" },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(Color(color), CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { newText ->
                    text = newText
                    parseHexColorOrNull(newText)?.let(onValid)
                },
                label = {
                    Text(
                        text = label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                isError = parsed == null,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke()
        }
        if (parsed == null) {
            Text(
                "Enter a colour as #RRGGBB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
    if (picking) {
        ColorPickerDialog(
            initial = color,
            onPick = {
                onValid(it)
                picking = false
            },
            onDismiss = { picking = false },
            preview = preview,
        )
    }
}

/**
 * A swatch per ANSI colour in order, a sample line in the scheme's foreground on its background,
 * and a selection-tinted span next to a cursor-coloured block.
 */
@Composable
internal fun ColorSchemePreview(scheme: ColorSchemeEntity, modifier: Modifier = Modifier) {
    val ansi = scheme.ansiColors()
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = Color(scheme.background),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp)),
            ) {
                ansi.forEach { colour ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(12.dp)
                            .background(Color(colour))
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("The quick brown fox jumps", color = Color(scheme.foreground))
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.background(Color(scheme.selectionHighlightColor()))) {
                    Text(" selected text ", color = Color(scheme.foreground))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .width(8.dp)
                        .height(16.dp)
                        .background(Color(scheme.cursor))
                )
            }
        }
    }
}

/**
 * Combined live terminal preview card showing font family, size, line height, cursor style/blink,
 * selection highlight, and the 16-colour ANSI strip in a compact terminal window frame.
 */
@Composable
internal fun LiveTerminalPreviewCard(
    profile: TerminalProfileEntity,
    scheme: ColorSchemeEntity,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val fontFamily =
        remember(profile.fontFamily, profile.customFontUri) {
            FontFamily(
                resolveTerminalTypeface(
                    context,
                    TerminalFontFamily.fromStored(profile.fontFamily),
                    profile.customFontUri,
                ),
            )
        }
    val textStyle =
        TextStyle(
            color = Color(scheme.foreground),
            fontFamily = fontFamily,
            fontSize = profile.fontSizeSp.sp,
            lineHeight = (profile.fontSizeSp * profile.lineHeightMultiplier).sp,
        )
    val cursorStyle = TerminalCursorStyle.fromStored(profile.cursorStyle)
    val cursorColor = Color(scheme.cursor)
    val blinkPhase =
        if (profile.cursorBlink) {
            rememberInfiniteTransition(label = "cursor blink")
                .animateFloat(0f, 1f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "phase")
        } else {
            null
        }
    val ansi = remember(scheme.ansiColorsCsv) { scheme.ansiColors() }
    val familyDisplayName = remember(profile.fontFamily) {
        TerminalFontFamily.fromStored(profile.fontFamily).displayName
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color(scheme.background),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Terminal window title bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.size(8.dp).background(Color(0xFFFF5F56), CircleShape))
                    Box(modifier = Modifier.size(8.dp).background(Color(0xFFFFBD2E), CircleShape))
                    Box(modifier = Modifier.size(8.dp).background(Color(0xFF27C93F), CircleShape))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "LIVE PREVIEW",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "$familyDisplayName · ${profile.fontSizeSp.roundToInt()}sp · ${scheme.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text("polls@nas:~$ ls --color", style = textStyle)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("bin  etc  ", style = textStyle)
                    Box(modifier = Modifier.background(Color(scheme.selectionHighlightColor()))) {
                        Text("selected", style = textStyle)
                    }
                    Text("  var", style = textStyle)
                }
                Row {
                    Text("polls@nas:~$ ", style = textStyle)
                    Text(
                        " ",
                        style = textStyle,
                        modifier = Modifier.drawBehind {
                            if (blinkPhase != null && blinkPhase.value >= 0.5f) return@drawBehind
                            val thickness = 2.dp.toPx()
                            when (cursorStyle) {
                                TerminalCursorStyle.BLOCK -> drawRect(cursorColor)
                                TerminalCursorStyle.UNDERLINE ->
                                    drawRect(
                                        cursorColor,
                                        Offset(0f, size.height - thickness),
                                        Size(size.width, thickness)
                                    )
                                TerminalCursorStyle.BAR -> drawRect(cursorColor, size = Size(thickness, size.height))
                            }
                        },
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp)),
                ) {
                    ansi.forEach { colour ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(6.dp)
                                .background(Color(colour))
                        )
                    }
                }
            }
        }
    }
}

internal fun displayNameForUri(context: Context, uriString: String): String? =
    runCatching {
        context.contentResolver.query(
            Uri.parse(uriString),
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

private const val MAX_IMPORT_THEME_BYTES = 512 * 1024

private fun readThemeTextCapped(context: Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var remaining = MAX_IMPORT_THEME_BYTES
            while (remaining > 0) {
                val read = input.read(buf, 0, minOf(buf.size, remaining))
                if (read <= 0) break
                out.write(buf, 0, read)
                remaining -= read
            }
            out.toString(Charsets.UTF_8.name())
        }
    }.getOrNull()

@Composable
private fun ImportColorSchemeDialog(
    onImport: (ColorSchemeEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var rawText by rememberSaveable { mutableStateOf("") }
    var pickedFileName by rememberSaveable { mutableStateOf<String?>(null) }
    var fileReadError by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        pickedFileName = displayNameForUri(context, uri.toString())
        val content = readThemeTextCapped(context, uri)
        if (content != null) {
            rawText = content
            fileReadError = null
        } else {
            fileReadError = "Could not read selected file."
        }
    }

    val parseResult = remember(rawText, pickedFileName) {
        if (rawText.isBlank()) null else importColorScheme(rawText, pickedFileName)
    }
    val parsedScheme = parseResult?.getOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import terminal colours") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Paste or pick a Ghostty, Alacritty, iTerm2 (.itermcolors), Warp, Kitty, Termux, Gogh, or Windows Terminal theme (e.g. from terminalcolors.com or gogh.website).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                        Icon(
                            imageVector = Icons.Outlined.FileOpen,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Choose file")
                    }
                    if (!pickedFileName.isNullOrBlank()) {
                        Text(
                            text = pickedFileName.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                OutlinedTextField(
                    value = rawText,
                    onValueChange = {
                        rawText = it
                        fileReadError = null
                    },
                    label = { Text("Theme config / JSON / plist") },
                    placeholder = { Text("background = #1e1e2e\nforeground = #cdd6f4\n…") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = ChromeMonoFontFamily),
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )

                val errorMsg = fileReadError ?: parseResult?.exceptionOrNull()?.message
                if (errorMsg != null) {
                    Text(
                        text = errorMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (parsedScheme != null) {
                    Text(
                        text = "Preview: ${parsedScheme.name}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    ColorSchemePreview(
                        scheme = parsedScheme,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsedScheme != null,
                onClick = { parsedScheme?.let(onImport) },
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ColorSchemeSwatchCard(
    scheme: ColorSchemeEntity,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val previewAnsi = remember(scheme.ansiColorsCsv) { scheme.ansiColors() }
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.RadioButton, onClick = onClick),
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
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = scheme.name,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(scheme.background))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = ">_",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color(scheme.foreground),
                )
                Spacer(modifier = Modifier.weight(1f))
                for (idx in 1..6) {
                    if (idx < previewAnsi.size) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(Color(previewAnsi[idx]), CircleShape),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoghLibrarySheet(
    currentScheme: ColorSchemeEntity,
    onSelect: (ColorSchemeEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val allThemes by produceState<List<ColorSchemeEntity>?>(initialValue = null, context) {
        value = loadGoghThemes(context)
    }
    var filter by rememberSaveable { mutableStateOf(SchemeFilter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }

    val filtered = remember(allThemes, filter, query) {
        val list = allThemes ?: return@remember emptyList()
        val q = query.trim().lowercase()
        list.filter { theme ->
            val light = isLightBackground(theme.background)
            val matchesTone = when (filter) {
                SchemeFilter.ALL -> true
                SchemeFilter.DARK -> !light
                SchemeFilter.LIGHT -> light
            }
            val matchesQuery = q.isEmpty() || theme.name.lowercase().contains(q)
            matchesTone && matchesQuery
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Gogh theme library",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        text = if (allThemes == null) {
                            "Loading offline catalog…"
                        } else {
                            "${filtered.size} of ${allThemes?.size ?: 0} themes · MIT"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SchemeFilter.entries.forEach { tone ->
                    FilterChip(
                        selected = filter == tone,
                        onClick = { filter = tone },
                        label = { Text(tone.label) },
                    )
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search Gogh themes…") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            when {
                allThemes == null -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                filtered.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "No themes match \"$query\".",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = {
                                filter = SchemeFilter.ALL
                                query = ""
                            }) {
                                Text("Reset filters")
                            }
                        }
                    }
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(
                            items = filtered,
                            key = { index, theme -> "${theme.name}_$index" },
                        ) { _, theme ->
                            ColorSchemeSwatchCard(
                                scheme = theme,
                                isSelected = !currentScheme.isBuiltIn && currentScheme.name == theme.name,
                                onClick = { onSelect(theme) },
                            )
                        }
                    }
                }
            }
        }
    }
}
