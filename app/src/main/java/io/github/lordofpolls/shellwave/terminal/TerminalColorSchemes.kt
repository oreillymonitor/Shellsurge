package io.github.lordofpolls.shellwave.terminal

import com.termux.terminal.TerminalColorScheme
import com.termux.terminal.TerminalColors
import com.termux.terminal.TextStyle
import io.github.lordofpolls.shellwave.core.db.entities.ColorSchemeEntity
import java.util.Properties

/**
 * The built-in palettes, plus the choke point that maps a stored [ColorSchemeEntity] onto the
 * vendored engine's colour model.
 *
 * The engine's colour array is 259 entries: 0-15 the ANSI colours, 16-231 the colour cube, 232-255
 * the greyscale ramp, 256-258 foreground/background/cursor. The editor only touches 0-15 and
 * 256-258, so [ColorSchemeEntity] stores 20 colours.
 *
 * [applyColorSchemeLive] and [applyColorSchemeAsDefault] are the only two places a scheme becomes
 * applied engine colours, which is what lets `SchemeHarmonizer` sit between a stored scheme and
 * these calls without touching the renderer, editor or DAO.
 */
private const val ANSI_COLOR_COUNT = 16

/** A strict subset of `TerminalColors.tryParseColor`, so anything this accepts parses engine-side. */
private val HEX_COLOR_REGEX = Regex("^#[0-9A-Fa-f]{6}$")

/**
 * [TerminalColors.tryParseColor] is a silent no-op on bad input, so without a check on this side a
 * typo looks accepted while doing nothing. Callers must reject a `null` visibly.
 */
fun parseHexColorOrNull(text: String): Int? {
    if (!HEX_COLOR_REGEX.matches(text)) return null
    return 0xFF000000.toInt() or text.substring(1).toInt(16)
}

fun Int.toHexColorString(): String = "#%06X".format(this and 0xFFFFFF)

private fun String.hexToColorInt(): Int = 0xFF000000.toInt() or substring(1).toInt(16)

fun ColorSchemeEntity.ansiColors(): IntArray =
    ansiColorsCsv.split(",").map { it.hexToColorInt() }.toIntArray()

private fun ansiColorsCsv(colors: IntArray): String {
    require(colors.size == ANSI_COLOR_COUNT) { "Expected $ANSI_COLOR_COUNT ANSI colours, got ${colors.size}" }
    return colors.joinToString(",") { it.toHexColorString() }
}

/** Stored opaque; the overlay's translucency is applied here, not baked into the saved value. */
fun ColorSchemeEntity.selectionHighlightColor(): Int = (0x66 shl 24) or (selection and 0x00FFFFFF)

fun ColorSchemeEntity.toColorProperties(): Properties =
    Properties().apply {
        setProperty("foreground", foreground.toHexColorString())
        setProperty("background", background.toHexColorString())
        setProperty("cursor", cursor.toHexColorString())
        ansiColors().forEachIndexed { index, colour ->
            setProperty(
                "color$index",
                colour.toHexColorString()
            )
        }
    }

/**
 * For a session already open when the user edits a colour in Settings. Index by index through
 * TerminalColors.tryParseColor, the only per-index entry point reachable from app code since
 * `TerminalColors.parse` is package-private, rather than writing `mCurrentColors` directly.
 * [toHexColorString] guarantees well-formed values, so `tryParseColor`'s silent-no-op failure mode
 * never triggers here.
 */
fun applyColorSchemeLive(scheme: ColorSchemeEntity, colors: TerminalColors) {
    val ansi = scheme.ansiColors()
    for (index in 0 until ANSI_COLOR_COUNT) colors.tryParseColor(
        index,
        ansi[index].toHexColorString()
    )
    colors.tryParseColor(TextStyle.COLOR_INDEX_FOREGROUND, scheme.foreground.toHexColorString())
    colors.tryParseColor(TextStyle.COLOR_INDEX_BACKGROUND, scheme.background.toHexColorString())
    colors.tryParseColor(TextStyle.COLOR_INDEX_CURSOR, scheme.cursor.toHexColorString())
}

/**
 * The static default template every new emulator's [TerminalColors] resets from at construction.
 * Must run before the emulator is constructed, so [ssh.SessionManager.attemptConnect] calls it on
 * every (re)connect: a background reconnect has no Compose frame in the loop to rely on.
 */
fun applyColorSchemeAsDefault(scheme: ColorSchemeEntity) {
    TerminalColors.COLOR_SCHEME.updateWith(scheme.toColorProperties())
}

/**
 * The same visibility threshold `TerminalColorScheme.setCursorColorForBackground` uses, reusing the
 * engine's own perceived-brightness maths rather than reimplementing luminance. Used for the
 * built-in schemes whose canonical definition has no cursor colour, and offered as the editor's
 * "Auto" action.
 */
fun autoCursorColorFor(background: Int): Int =
    if (TerminalColors.getPerceivedBrightnessOfColor(background) < 130) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()

/** Pure JVM perceived-brightness check (0.299R + 0.587G + 0.114B >= 130). */
fun isLightBackground(argb: Int): Boolean {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return (0.299 * r + 0.587 * g + 0.114 * b) >= 130.0
}

/**
 * The scheme used before the user has ever saved one. Derived from a fresh [TerminalColorScheme]'s
 * own `mDefaultColors` and not transcribed by hand, so it can never drift from the engine's actual
 * built-in palette.
 */
val DEFAULT_COLOR_SCHEME: ColorSchemeEntity =
    TerminalColorScheme().let { engine ->
        ColorSchemeEntity(
            id = 0,
            name = "Default",
            isBuiltIn = true,
            background = engine.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND],
            foreground = engine.mDefaultColors[TextStyle.COLOR_INDEX_FOREGROUND],
            cursor = engine.mDefaultColors[TextStyle.COLOR_INDEX_CURSOR],
            selection = 0xFF2196F3.toInt(),
            ansiColorsCsv = ansiColorsCsv(IntArray(ANSI_COLOR_COUNT) { engine.mDefaultColors[it] }),
        )
    }

/**
 * Exists so [BuiltInColorSchemeContrastTest] can read the committed values instead of a hand-copied
 * duplicate. Touching [BuiltInColorSchemes] from a plain JVM test throws
 * `ExceptionInInitializerError` - Kotlin initializes every property of an `object` together, and
 * GRUVBOX's cursor reaches `android.graphics.Color` through [autoCursorColorFor], unmocked
 * off-device. [BuiltInColorSchemes] builds its foreground/background from this map, so there is one
 * committed value per scheme.
 */
internal data class ForegroundBackground(val foreground: Int, val background: Int)

/** Keyed by the same scheme name `BuiltInColorSchemes` uses - see [ForegroundBackground] for why this is separate. */
internal val BUILT_IN_SCHEME_FOREGROUND_BACKGROUND: Map<String, ForegroundBackground> =
    mapOf(
        "Solarized Dark" to ForegroundBackground(
            foreground = 0xFF839496.toInt(),
            background = 0xFF002B36.toInt()
        ),
        "Solarized Light" to ForegroundBackground(
            foreground = 0xFF657B83.toInt(),
            background = 0xFFFDF6E3.toInt()
        ),
        "Gruvbox" to ForegroundBackground(
            foreground = 0xFFEBDBB2.toInt(),
            background = 0xFF282828.toInt()
        ),
        "Nord" to ForegroundBackground(
            foreground = 0xFFD8DEE9.toInt(),
            background = 0xFF2E3440.toInt()
        ),
        "Dracula" to ForegroundBackground(
            foreground = 0xFFF8F8F2.toInt(),
            background = 0xFF282A36.toInt()
        ),
        "Tango" to ForegroundBackground(
            foreground = 0xFFFFFFFF.toInt(),
            background = 0xFF000000.toInt()
        ),
        "Campbell" to ForegroundBackground(
            foreground = 0xFFCCCCCC.toInt(),
            background = 0xFF0C0C0C.toInt()
        ),
        "Catppuccin Mocha" to ForegroundBackground(
            foreground = 0xFFCDD6F4.toInt(),
            background = 0xFF1E1E2E.toInt()
        ),
        "Catppuccin Latte" to ForegroundBackground(
            foreground = 0xFF4C4F69.toInt(),
            background = 0xFFEFF1F5.toInt()
        ),
        "Tokyo Night" to ForegroundBackground(
            foreground = 0xFFC0CAF5.toInt(),
            background = 0xFF1A1B26.toInt()
        ),
        "Tokyo Night Storm" to ForegroundBackground(
            foreground = 0xFFC0CAF5.toInt(),
            background = 0xFF24283B.toInt()
        ),
        "One Dark" to ForegroundBackground(
            foreground = 0xFFABB2BF.toInt(),
            background = 0xFF282C34.toInt()
        ),
        "Kanagawa" to ForegroundBackground(
            foreground = 0xFFDCD7BA.toInt(),
            background = 0xFF1F1F28.toInt()
        ),
        "Rosé Pine" to ForegroundBackground(
            foreground = 0xFFE0DEF4.toInt(),
            background = 0xFF191724.toInt()
        ),
        "Rosé Pine Dawn" to ForegroundBackground(
            foreground = 0xFF575279.toInt(),
            background = 0xFFFAF4ED.toInt()
        ),
        "Everforest Dark" to ForegroundBackground(
            foreground = 0xFFD3C6AA.toInt(),
            background = 0xFF2D353B.toInt()
        ),
        "Monokai Pro" to ForegroundBackground(
            foreground = 0xFFFCFCFA.toInt(),
            background = 0xFF2D2A2E.toInt()
        ),
        "GitHub Dark" to ForegroundBackground(
            foreground = 0xFFE6EDF3.toInt(),
            background = 0xFF0D1117.toInt()
        ),
        "GitHub Light" to ForegroundBackground(
            foreground = 0xFF1F2328.toInt(),
            background = 0xFFFFFFFF.toInt()
        ),
        "Gruvbox Light" to ForegroundBackground(
            foreground = 0xFF3C3836.toInt(),
            background = 0xFFFBF1C7.toInt()
        ),
        "Ayu Dark" to ForegroundBackground(
            foreground = 0xFFBFBDB6.toInt(),
            background = 0xFF0B0E14.toInt()
        ),
        "OLED Black" to ForegroundBackground(
            foreground = 0xFFE6EDF3.toInt(),
            background = 0xFF000000.toInt()
        ),
        "Amber CRT" to ForegroundBackground(
            foreground = 0xFFFFB000.toInt(),
            background = 0xFF0A0804.toInt()
        ),
        "Green CRT" to ForegroundBackground(
            foreground = 0xFF33FF66.toInt(),
            background = 0xFF040A06.toInt()
        ),
    )

/**
 * None of these values come from Termux. Each palette is sourced from its own project's canonical
 * public definition, cited per scheme.
 *
 * BuiltInColorSchemeContrastTest audits every foreground/background pair against WCAG AA's 4.5:1.
 * Six of seven pass; Solarized Dark narrowly, at ~4.75:1. Solarized Light fails at ~4.13:1 and
 * stays that way: those are Solarized's own reference values, and darkening them would mean
 * shipping something labelled Solarized that isn't.
 */
object BuiltInColorSchemes {
    /**
     * Ethan Schoonover, http://ethanschoonover.com/solarized. ANSI mapping and cursor from the
     * reference X11 defaults, github.com/altercation/solarized/blob/master/xresources/solarized.
     *
     * Dark and light are the same 16 accent/base tones with the base shades swapped end-for-end, so the
     * two colour lists below are mirror images. The xresources file defines no selection; base02/base2
     * come from the usage guide's "highlighted text" background pair.
     */
    val SOLARIZED_DARK =
        buildScheme(
            name = "Solarized Dark",
            background = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Solarized Dark").background,
            foreground = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Solarized Dark").foreground,
            cursor = 0xFF93A1A1.toInt(),
            selection = 0xFF073642.toInt(),
            ansi =
                intArrayOf(
                    0xFF073642.toInt(), 0xFFDC322F.toInt(), 0xFF859900.toInt(), 0xFFB58900.toInt(),
                    0xFF268BD2.toInt(), 0xFFD33682.toInt(), 0xFF2AA198.toInt(), 0xFFEEE8D5.toInt(),
                    0xFF002B36.toInt(), 0xFFCB4B16.toInt(), 0xFF586E75.toInt(), 0xFF657B83.toInt(),
                    0xFF839496.toInt(), 0xFF6C71C4.toInt(), 0xFF93A1A1.toInt(), 0xFFFDF6E3.toInt(),
                ),
        )

    val SOLARIZED_LIGHT =
        buildScheme(
            name = "Solarized Light",
            background = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Solarized Light").background,
            foreground = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Solarized Light").foreground,
            cursor = 0xFF586E75.toInt(),
            selection = 0xFFEEE8D5.toInt(),
            ansi =
                intArrayOf(
                    0xFFEEE8D5.toInt(), 0xFFDC322F.toInt(), 0xFF859900.toInt(), 0xFFB58900.toInt(),
                    0xFF268BD2.toInt(), 0xFFD33682.toInt(), 0xFF2AA198.toInt(), 0xFF073642.toInt(),
                    0xFFFDF6E3.toInt(), 0xFFCB4B16.toInt(), 0xFF93A1A1.toInt(), 0xFF839496.toInt(),
                    0xFF657B83.toInt(), 0xFF6C71C4.toInt(), 0xFF586E75.toInt(), 0xFF002B36.toInt(),
                ),
        )

    /**
     * morhetz, github.com/morhetz/gruvbox, dark-medium (the project default). ANSI values from
     * gruvbox-generalized/blob/master/color.table, dark column. That table defines no cursor, so
     * [autoCursorColorFor] supplies one; selection is its documented next-lighter "bg2".
     */
    val GRUVBOX =
        buildScheme(
            name = "Gruvbox",
            background = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Gruvbox").background,
            foreground = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Gruvbox").foreground,
            cursor = autoCursorColorFor(BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Gruvbox").background),
            selection = 0xFF504945.toInt(),
            ansi =
                intArrayOf(
                    0xFF282828.toInt(), 0xFFCC241D.toInt(), 0xFF98971A.toInt(), 0xFFD79921.toInt(),
                    0xFF458588.toInt(), 0xFFB16286.toInt(), 0xFF689D6A.toInt(), 0xFFA89984.toInt(),
                    0xFF928374.toInt(), 0xFFFB4934.toInt(), 0xFFB8BB26.toInt(), 0xFFFABD2F.toInt(),
                    0xFF83A598.toInt(), 0xFFD3869B.toInt(), 0xFF8EC07C.toInt(), 0xFFEBDBB2.toInt(),
                ),
        )

    /**
     * Arctic Ice Studio / Sven Greb, nordtheme.com. ANSI, background, foreground and cursor from the
     * official Xresources port, github.com/nordtheme/xresources/blob/develop/src/nord.
     *
     * X11 has no selection resource, so nord2 comes from the palette docs, which name it as what "dark
     * designs use ... to colorize the active text editor line, selection, and text highlighting".
     */
    val NORD =
        buildScheme(
            name = "Nord",
            background = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Nord").background,
            foreground = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Nord").foreground,
            cursor = 0xFFD8DEE9.toInt(),
            selection = 0xFF434C5E.toInt(),
            ansi =
                intArrayOf(
                    0xFF3B4252.toInt(), 0xFFBF616A.toInt(), 0xFFA3BE8C.toInt(), 0xFFEBCB8B.toInt(),
                    0xFF81A1C1.toInt(), 0xFFB48EAD.toInt(), 0xFF88C0D0.toInt(), 0xFFE5E9F0.toInt(),
                    0xFF4C566A.toInt(), 0xFFBF616A.toInt(), 0xFFA3BE8C.toInt(), 0xFFEBCB8B.toInt(),
                    0xFF81A1C1.toInt(), 0xFFB48EAD.toInt(), 0xFF8FBCBB.toInt(), 0xFFECEFF4.toInt(),
                ),
        )

    /**
     * draculatheme.com, spec at draculatheme.com/contribute. The one scheme here that names an explicit
     * terminal cursor and selection, so nothing had to be inferred.
     */
    val DRACULA =
        buildScheme(
            name = "Dracula",
            background = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Dracula").background,
            foreground = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Dracula").foreground,
            cursor = 0xFFF8F8F2.toInt(),
            selection = 0xFF44475A.toInt(),
            ansi =
                intArrayOf(
                    0xFF21222C.toInt(), 0xFFFF5555.toInt(), 0xFF50FA7B.toInt(), 0xFFF1FA8C.toInt(),
                    0xFFBD93F9.toInt(), 0xFFFF79C6.toInt(), 0xFF8BE9FD.toInt(), 0xFFF8F8F2.toInt(),
                    0xFF6272A4.toInt(), 0xFFFF6E6E.toInt(), 0xFF69FF94.toInt(), 0xFFFFFFA5.toInt(),
                    0xFFD6ACFF.toInt(), 0xFFFF92DF.toInt(), 0xFFA4FFFF.toInt(), 0xFFFFFFFF.toInt(),
                ),
        )

    /**
     * The Tango Desktop Project palette as shipped as GNOME Terminal's "Tango Dark" builtin,
     * cross-checked against iTerm2-Color-Schemes' xrdb/Builtin%20Tango%20Dark.xrdb, which mirrors that
     * builtin rather than reinterpreting it. Its foreground, cursor and selection are used as-is.
     */
    val TANGO =
        buildScheme(
            name = "Tango",
            background = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Tango").background,
            foreground = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Tango").foreground,
            cursor = 0xFFFFFFFF.toInt(),
            selection = 0xFFB5D5FF.toInt(),
            ansi =
                intArrayOf(
                    0xFF000000.toInt(), 0xFFCC0000.toInt(), 0xFF4E9A06.toInt(), 0xFFC4A000.toInt(),
                    0xFF3465A4.toInt(), 0xFF75507B.toInt(), 0xFF06989A.toInt(), 0xFFD3D7CF.toInt(),
                    0xFF555753.toInt(), 0xFFEF2929.toInt(), 0xFF8AE234.toInt(), 0xFFFCE94F.toInt(),
                    0xFF729FCF.toInt(), 0xFFAD7FA8.toInt(), 0xFF34E2E2.toInt(), 0xFFEEEEEC.toInt(),
                ),
        )

    /**
     * The default Windows Console/Terminal scheme since Windows 10 1809, from Windows Terminal's
     * shipped defaults.json. That entry gives `cursorColor` but no `selectionBackground` - the real
     * default selection tint is a global setting outside any scheme - so selection matches the cursor's
     * white.
     */
    val CAMPBELL =
        buildScheme(
            name = "Campbell",
            background = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Campbell").background,
            foreground = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Campbell").foreground,
            cursor = 0xFFFFFFFF.toInt(),
            selection = 0xFFFFFFFF.toInt(),
            ansi =
                intArrayOf(
                    0xFF0C0C0C.toInt(), 0xFFC50F1F.toInt(), 0xFF13A10E.toInt(), 0xFFC19C00.toInt(),
                    0xFF0037DA.toInt(), 0xFF881798.toInt(), 0xFF3A96DD.toInt(), 0xFFCCCCCC.toInt(),
                    0xFF767676.toInt(), 0xFFE74856.toInt(), 0xFF16C60C.toInt(), 0xFFF9F1A5.toInt(),
                    0xFF3B78FF.toInt(), 0xFFB4009E.toInt(), 0xFF61D6D6.toInt(), 0xFFF2F2F2.toInt(),
                ),
        )

    val CATPPUCCIN_MOCHA =
        buildScheme(
            name = "Catppuccin Mocha",
            background = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Catppuccin Mocha").background,
            foreground = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Catppuccin Mocha").foreground,
            cursor = 0xFFF5E0DC.toInt(),
            selection = 0xFF585B70.toInt(),
            ansi =
                intArrayOf(
                    0xFF45475A.toInt(), 0xFFF38BA8.toInt(), 0xFFA6E3A1.toInt(), 0xFFF9E2AF.toInt(),
                    0xFF89B4FA.toInt(), 0xFFF5C2E7.toInt(), 0xFF94E2D5.toInt(), 0xFFBAC2DE.toInt(),
                    0xFF585B70.toInt(), 0xFFF38BA8.toInt(), 0xFFA6E3A1.toInt(), 0xFFF9E2AF.toInt(),
                    0xFF89B4FA.toInt(), 0xFFF5C2E7.toInt(), 0xFF94E2D5.toInt(), 0xFFA6ADC8.toInt(),
                ),
        )

    val CATPPUCCIN_LATTE =
        buildScheme(
            name = "Catppuccin Latte",
            background = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Catppuccin Latte").background,
            foreground = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Catppuccin Latte").foreground,
            cursor = 0xFFDC8A78.toInt(),
            selection = 0xFFACB0BE.toInt(),
            ansi =
                intArrayOf(
                    0xFF5C5F77.toInt(), 0xFFD20F39.toInt(), 0xFF40A02B.toInt(), 0xFFDF8E1D.toInt(),
                    0xFF1E66F5.toInt(), 0xFFEA76CB.toInt(), 0xFF179299.toInt(), 0xFFACB0BE.toInt(),
                    0xFF6C6F85.toInt(), 0xFFD20F39.toInt(), 0xFF40A02B.toInt(), 0xFFDF8E1D.toInt(),
                    0xFF1E66F5.toInt(), 0xFFEA76CB.toInt(), 0xFF179299.toInt(), 0xFFBCC0CC.toInt(),
                ),
        )

    val TOKYO_NIGHT =
        buildScheme(
            name = "Tokyo Night",
            background = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Tokyo Night").background,
            foreground = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Tokyo Night").foreground,
            cursor = 0xFFC0CAF5.toInt(),
            selection = 0xFF33467C.toInt(),
            ansi =
                intArrayOf(
                    0xFF15161E.toInt(), 0xFFF7768E.toInt(), 0xFF9ECE6A.toInt(), 0xFFE0AF68.toInt(),
                    0xFF7AA2F7.toInt(), 0xFFBB9AF7.toInt(), 0xFF7DCFFF.toInt(), 0xFFA9B1D6.toInt(),
                    0xFF414868.toInt(), 0xFFF7768E.toInt(), 0xFF9ECE6A.toInt(), 0xFFE0AF68.toInt(),
                    0xFF7AA2F7.toInt(), 0xFFBB9AF7.toInt(), 0xFF7DCFFF.toInt(), 0xFFC0CAF5.toInt(),
                ),
        )

    val TOKYO_NIGHT_STORM =
        buildScheme(
            name = "Tokyo Night Storm",
            background = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Tokyo Night Storm").background,
            foreground = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Tokyo Night Storm").foreground,
            cursor = 0xFFC0CAF5.toInt(),
            selection = 0xFF364A82.toInt(),
            ansi =
                intArrayOf(
                    0xFF1D202F.toInt(), 0xFFF7768E.toInt(), 0xFF9ECE6A.toInt(), 0xFFE0AF68.toInt(),
                    0xFF7AA2F7.toInt(), 0xFFBB9AF7.toInt(), 0xFF7DCFFF.toInt(), 0xFFA9B1D6.toInt(),
                    0xFF414868.toInt(), 0xFFF7768E.toInt(), 0xFF9ECE6A.toInt(), 0xFFE0AF68.toInt(),
                    0xFF7AA2F7.toInt(), 0xFFBB9AF7.toInt(), 0xFF7DCFFF.toInt(), 0xFFC0CAF5.toInt(),
                ),
        )

    val ONE_DARK =
        buildScheme(
            name = "One Dark",
            background = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("One Dark").background,
            foreground = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("One Dark").foreground,
            cursor = 0xFF528BFF.toInt(),
            selection = 0xFF3E4451.toInt(),
            ansi =
                intArrayOf(
                    0xFF282C34.toInt(), 0xFFE06C75.toInt(), 0xFF98C379.toInt(), 0xFFE5C07B.toInt(),
                    0xFF61AFEF.toInt(), 0xFFC678DD.toInt(), 0xFF56B6C2.toInt(), 0xFFABB2BF.toInt(),
                    0xFF5C6370.toInt(), 0xFFE06C75.toInt(), 0xFF98C379.toInt(), 0xFFE5C07B.toInt(),
                    0xFF61AFEF.toInt(), 0xFFC678DD.toInt(), 0xFF56B6C2.toInt(), 0xFFFFFFFF.toInt(),
                ),
        )

    val KANAGAWA =
        buildScheme(
            name = "Kanagawa",
            background = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Kanagawa").background,
            foreground = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Kanagawa").foreground,
            cursor = 0xFFC8C093.toInt(),
            selection = 0xFF2D4F67.toInt(),
            ansi =
                intArrayOf(
                    0xFF090618.toInt(), 0xFFC34043.toInt(), 0xFF76946A.toInt(), 0xFFC0A36E.toInt(),
                    0xFF7E9CD8.toInt(), 0xFF957FB8.toInt(), 0xFF6A9589.toInt(), 0xFFC8C093.toInt(),
                    0xFF727169.toInt(), 0xFFE82424.toInt(), 0xFF98BB6C.toInt(), 0xFFE6C384.toInt(),
                    0xFF7FB4CA.toInt(), 0xFF938AA9.toInt(), 0xFF7AA89F.toInt(), 0xFFDCD7BA.toInt(),
                ),
        )

    val ROSE_PINE =
        buildScheme(
            name = "Rosé Pine",
            background = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Rosé Pine").background,
            foreground = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Rosé Pine").foreground,
            cursor = 0xFF524F67.toInt(),
            selection = 0xFF403D52.toInt(),
            ansi =
                intArrayOf(
                    0xFF26233A.toInt(), 0xFFEB6F92.toInt(), 0xFF31748F.toInt(), 0xFFF6C177.toInt(),
                    0xFF9CCFD8.toInt(), 0xFFC4A7E7.toInt(), 0xFFEBBCBA.toInt(), 0xFFE0DEF4.toInt(),
                    0xFF6E6A86.toInt(), 0xFFEB6F92.toInt(), 0xFF31748F.toInt(), 0xFFF6C177.toInt(),
                    0xFF9CCFD8.toInt(), 0xFFC4A7E7.toInt(), 0xFFEBBCBA.toInt(), 0xFFE0DEF4.toInt(),
                ),
        )

    val ROSE_PINE_DAWN =
        buildScheme(
            name = "Rosé Pine Dawn",
            background = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Rosé Pine Dawn").background,
            foreground = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Rosé Pine Dawn").foreground,
            cursor = 0xFFCECACD.toInt(),
            selection = 0xFFDFDAD9.toInt(),
            ansi =
                intArrayOf(
                    0xFFF2E9E1.toInt(), 0xFFB4637A.toInt(), 0xFF286983.toInt(), 0xFFEA9D34.toInt(),
                    0xFF56949F.toInt(), 0xFF907AA9.toInt(), 0xFFD7827E.toInt(), 0xFF575279.toInt(),
                    0xFF9893A5.toInt(), 0xFFB4637A.toInt(), 0xFF286983.toInt(), 0xFFEA9D34.toInt(),
                    0xFF56949F.toInt(), 0xFF907AA9.toInt(), 0xFFD7827E.toInt(), 0xFF575279.toInt(),
                ),
        )

    val EVERFOREST_DARK =
        buildScheme(
            name = "Everforest Dark",
            background = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Everforest Dark").background,
            foreground = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Everforest Dark").foreground,
            cursor = 0xFFD3C6AA.toInt(),
            selection = 0xFF543A48.toInt(),
            ansi =
                intArrayOf(
                    0xFF475258.toInt(), 0xFFE67E80.toInt(), 0xFFA7C080.toInt(), 0xFFDBBC7F.toInt(),
                    0xFF7FBBB3.toInt(), 0xFFD699B6.toInt(), 0xFF83C092.toInt(), 0xFFD3C6AA.toInt(),
                    0xFF475258.toInt(), 0xFFE67E80.toInt(), 0xFFA7C080.toInt(), 0xFFDBBC7F.toInt(),
                    0xFF7FBBB3.toInt(), 0xFFD699B6.toInt(), 0xFF83C092.toInt(), 0xFFD3C6AA.toInt(),
                ),
        )

    val MONOKAI_PRO =
        buildScheme(
            name = "Monokai Pro",
            background = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Monokai Pro").background,
            foreground = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Monokai Pro").foreground,
            cursor = 0xFFFCFCFA.toInt(),
            selection = 0xFF5B595C.toInt(),
            ansi =
                intArrayOf(
                    0xFF403E41.toInt(), 0xFFFF6188.toInt(), 0xFFA9DC76.toInt(), 0xFFFFD866.toInt(),
                    0xFFFC9867.toInt(), 0xFFAB9DF2.toInt(), 0xFF78DCE8.toInt(), 0xFFFCFCFA.toInt(),
                    0xFF727072.toInt(), 0xFFFF6188.toInt(), 0xFFA9DC76.toInt(), 0xFFFFD866.toInt(),
                    0xFFFC9867.toInt(), 0xFFAB9DF2.toInt(), 0xFF78DCE8.toInt(), 0xFFFCFCFA.toInt(),
                ),
        )

    val GITHUB_DARK =
        buildScheme(
            name = "GitHub Dark",
            background = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("GitHub Dark").background,
            foreground = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("GitHub Dark").foreground,
            cursor = 0xFF58A6FF.toInt(),
            selection = 0xFF264F78.toInt(),
            ansi =
                intArrayOf(
                    0xFF484F58.toInt(), 0xFFFF7B72.toInt(), 0xFF3FB950.toInt(), 0xFFD29922.toInt(),
                    0xFF58A6FF.toInt(), 0xFFBC8CFF.toInt(), 0xFF39C5CF.toInt(), 0xFFB1BAC4.toInt(),
                    0xFF6E7681.toInt(), 0xFFFFA198.toInt(), 0xFF56D364.toInt(), 0xFFE3B341.toInt(),
                    0xFF79C0FF.toInt(), 0xFFD2A8FF.toInt(), 0xFF56D4DD.toInt(), 0xFFF0F6FC.toInt(),
                ),
        )

    val GITHUB_LIGHT =
        buildScheme(
            name = "GitHub Light",
            background = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("GitHub Light").background,
            foreground = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("GitHub Light").foreground,
            cursor = 0xFF0969DA.toInt(),
            selection = 0xFFB6E3FF.toInt(),
            ansi =
                intArrayOf(
                    0xFF24292F.toInt(), 0xFFCF222E.toInt(), 0xFF116329.toInt(), 0xFF4D2D00.toInt(),
                    0xFF0969DA.toInt(), 0xFF8250DF.toInt(), 0xFF1B7C83.toInt(), 0xFF6E7781.toInt(),
                    0xFF57606A.toInt(), 0xFFA40E26.toInt(), 0xFF1A7F37.toInt(), 0xFF633C01.toInt(),
                    0xFF218BFF.toInt(), 0xFFA475F9.toInt(), 0xFF3192AA.toInt(), 0xFF8C959F.toInt(),
                ),
        )

    val GRUVBOX_LIGHT =
        buildScheme(
            name = "Gruvbox Light",
            background = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Gruvbox Light").background,
            foreground = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Gruvbox Light").foreground,
            cursor = 0xFF3C3836.toInt(),
            selection = 0xFFD5C4A1.toInt(),
            ansi =
                intArrayOf(
                    0xFFFBF1C7.toInt(), 0xFFCC241D.toInt(), 0xFF98971A.toInt(), 0xFFD79921.toInt(),
                    0xFF458588.toInt(), 0xFFB16286.toInt(), 0xFF689D6A.toInt(), 0xFF7C6F64.toInt(),
                    0xFF928374.toInt(), 0xFF9D0006.toInt(), 0xFF79740E.toInt(), 0xFFB57614.toInt(),
                    0xFF076678.toInt(), 0xFF8F3F71.toInt(), 0xFF427B58.toInt(), 0xFF3C3836.toInt(),
                ),
        )

    val AYU_DARK =
        buildScheme(
            name = "Ayu Dark",
            background = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Ayu Dark").background,
            foreground = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Ayu Dark").foreground,
            cursor = 0xFFE6B450.toInt(),
            selection = 0xFF273747.toInt(),
            ansi =
                intArrayOf(
                    0xFF1E232B.toInt(), 0xFFEA6C73.toInt(), 0xFF7FD962.toInt(), 0xFFF9AF4F.toInt(),
                    0xFF53BDFA.toInt(), 0xFFCDA1FA.toInt(), 0xFF90E1C6.toInt(), 0xFFC7C7C7.toInt(),
                    0xFF686868.toInt(), 0xFFF07178.toInt(), 0xFFAAD94C.toInt(), 0xFFFFB454.toInt(),
                    0xFF59C2FF.toInt(), 0xFFD2A6FF.toInt(), 0xFF95E6CB.toInt(), 0xFFFFFFFF.toInt(),
                ),
        )

    val OLED_BLACK =
        buildScheme(
            name = "OLED Black",
            background = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("OLED Black").background,
            foreground = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("OLED Black").foreground,
            cursor = 0xFFFFFFFF.toInt(),
            selection = 0xFF264F78.toInt(),
            ansi =
                intArrayOf(
                    0xFF1C2128.toInt(), 0xFFFF7B72.toInt(), 0xFF3FB950.toInt(), 0xFFD29922.toInt(),
                    0xFF58A6FF.toInt(), 0xFFBC8CFF.toInt(), 0xFF39C5CF.toInt(), 0xFFB1BAC4.toInt(),
                    0xFF6E7681.toInt(), 0xFFFFA198.toInt(), 0xFF56D364.toInt(), 0xFFE3B341.toInt(),
                    0xFF79C0FF.toInt(), 0xFFD2A8FF.toInt(), 0xFF56D4DD.toInt(), 0xFFFFFFFF.toInt(),
                ),
        )

    val AMBER_CRT =
        buildScheme(
            name = "Amber CRT",
            background = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Amber CRT").background,
            foreground = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Amber CRT").foreground,
            cursor = 0xFFFFCC4D.toInt(),
            selection = 0xFF4A3200.toInt(),
            ansi =
                intArrayOf(
                    0xFF1C1508.toInt(), 0xFFE06224.toInt(), 0xFFC9A428.toInt(), 0xFFFFB000.toInt(),
                    0xFFB37722.toInt(), 0xFFD97B38.toInt(), 0xFFE6B84C.toInt(), 0xFFFFD580.toInt(),
                    0xFF6E5322.toInt(), 0xFFFF8243.toInt(), 0xFFE5C145.toInt(), 0xFFFFC940.toInt(),
                    0xFFD4963B.toInt(), 0xFFF29B52.toInt(), 0xFFFFDA75.toInt(), 0xFFFFF0CC.toInt(),
                ),
        )

    val GREEN_CRT =
        buildScheme(
            name = "Green CRT",
            background = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Green CRT").background,
            foreground = BUILT_IN_SCHEME_FOREGROUND_BACKGROUND.getValue("Green CRT").foreground,
            cursor = 0xFF80FF9E.toInt(),
            selection = 0xFF0F451E.toInt(),
            ansi =
                intArrayOf(
                    0xFF0B1C10.toInt(), 0xFF9EE632.toInt(), 0xFF33FF66.toInt(), 0xFF7CFF4D.toInt(),
                    0xFF1EB86A.toInt(), 0xFF38D996.toInt(), 0xFF5CFFBA.toInt(), 0xFFA8FFBE.toInt(),
                    0xFF2B663D.toInt(), 0xFFBFFF5C.toInt(), 0xFF66FF8C.toInt(), 0xFFA3FF7A.toInt(),
                    0xFF45D98E.toInt(), 0xFF66F0B4.toInt(), 0xFF8CFFD0.toInt(), 0xFFDEFFE6.toInt(),
                ),
        )

    val ALL: List<ColorSchemeEntity> =
        listOf(
            SOLARIZED_DARK,
            SOLARIZED_LIGHT,
            GRUVBOX,
            GRUVBOX_LIGHT,
            NORD,
            DRACULA,
            CATPPUCCIN_MOCHA,
            CATPPUCCIN_LATTE,
            TOKYO_NIGHT,
            TOKYO_NIGHT_STORM,
            ONE_DARK,
            KANAGAWA,
            ROSE_PINE,
            ROSE_PINE_DAWN,
            EVERFOREST_DARK,
            MONOKAI_PRO,
            GITHUB_DARK,
            GITHUB_LIGHT,
            AYU_DARK,
            OLED_BLACK,
            AMBER_CRT,
            GREEN_CRT,
            TANGO,
            CAMPBELL,
        )

    private fun buildScheme(
        name: String,
        background: Int,
        foreground: Int,
        cursor: Int,
        selection: Int,
        ansi: IntArray
    ): ColorSchemeEntity =
        ColorSchemeEntity(
            id = 0,
            name = name,
            isBuiltIn = true,
            background = background,
            foreground = foreground,
            cursor = cursor,
            selection = selection,
            ansiColorsCsv = ansiColorsCsv(ansi),
        )
}
