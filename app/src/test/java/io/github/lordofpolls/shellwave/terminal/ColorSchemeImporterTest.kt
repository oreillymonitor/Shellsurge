package io.github.lordofpolls.shellwave.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorSchemeImporterTest {

    @Test
    fun `parses Ghostty theme format including bare hex values`() {
        val ghostty = """
            palette = 0=#45475a
            palette = 1=#f38ba8
            palette = 2=#a6e3a1
            palette = 3=#f9e2af
            palette = 4=#89b4fa
            palette = 5=#f5c2e7
            palette = 6=#94e2d5
            palette = 7=bac2de
            palette = 8=#585b70
            palette = 9=#f38ba8
            palette = 10=#a6e3a1
            palette = 11=#f9e2af
            palette = 12=#89b4fa
            palette = 13=#f5c2e7
            palette = 14=#94e2d5
            palette = 15=#a6adc8
            background = 1e1e2e
            foreground = #cdd6f4
            cursor-color = #f5e0dc
            selection-background = #585b70
        """.trimIndent()

        val result = importColorScheme(ghostty, fallbackName = "Catppuccin Mocha.conf")
        assertTrue(result.isSuccess)
        val scheme = result.getOrThrow()
        assertEquals("Catppuccin Mocha", scheme.name)
        assertEquals(0xFF1E1E2E.toInt(), scheme.background)
        assertEquals(0xFFCDD6F4.toInt(), scheme.foreground)
        assertEquals(0xFFF5E0DC.toInt(), scheme.cursor)
        assertEquals(0xFF585B70.toInt(), scheme.selection)
        val ansi = scheme.ansiColors()
        assertEquals(16, ansi.size)
        assertEquals(0xFF45475A.toInt(), ansi[0])
        assertEquals(0xFFBAC2DE.toInt(), ansi[7])
        assertEquals(0xFFA6ADC8.toInt(), ansi[15])
    }

    @Test
    fun `parses Alacritty TOML format with 0x and # prefixes`() {
        val toml = """
            [colors.primary]
            background = '#1a1b26'
            foreground = '0xc0caf5'

            [colors.normal]
            black = '#15161e'
            red = '#f7768e'
            green = '#9ece6a'
            yellow = '#e0af68'
            blue = '#7aa2f7'
            magenta = '#bb9af7'
            cyan = '#7dcfff'
            white = '#a9b1d6'

            [colors.bright]
            black = '#414868'
            red = '#f7768e'
            green = '#9ece6a'
            yellow = '#e0af68'
            blue = '#7aa2f7'
            magenta = '#bb9af7'
            cyan = '#7dcfff'
            white = '#c0caf5'
        """.trimIndent()

        val result = importColorScheme(toml, fallbackName = "tokyo-night.toml")
        assertTrue(result.isSuccess)
        val scheme = result.getOrThrow()
        assertEquals("tokyo-night", scheme.name)
        assertEquals(0xFF1A1B26.toInt(), scheme.background)
        assertEquals(0xFFC0CAF5.toInt(), scheme.foreground)
        val ansi = scheme.ansiColors()
        assertEquals(0xFF15161E.toInt(), ansi[0])
        assertEquals(0xFF414868.toInt(), ansi[8])
        assertEquals(0xFFC0CAF5.toInt(), ansi[15])
    }

    @Test
    fun `parses Warp YAML theme`() {
        val warp = """
            name: Cyberpunk Neon
            accent: '#ff007c'
            background: '#0d0221'
            foreground: '#ffffff'
            terminal_colors:
              normal:
                black: '#000000'
                red: '#ff2a6d'
                green: '#05d9e8'
                yellow: '#f9f871'
                blue: '#01012b'
                magenta: '#d100d1'
                cyan: '#00f0ff'
                white: '#d1f7ff'
              bright:
                black: '#4a4a68'
                red: '#ff5c8a'
                green: '#65f4ff'
                yellow: '#ffff99'
                blue: '#3b3b98'
                magenta: '#f368e0'
                cyan: '#70f8ff'
                white: '#ffffff'
        """.trimIndent()

        val result = importColorScheme(warp)
        assertTrue(result.isSuccess)
        val scheme = result.getOrThrow()
        assertEquals("Cyberpunk Neon", scheme.name)
        assertEquals(0xFF0D0221.toInt(), scheme.background)
        assertEquals(0xFFFF007C.toInt(), scheme.cursor)
        val ansi = scheme.ansiColors()
        assertEquals(0xFFFF2A6D.toInt(), ansi[1])
        assertEquals(0xFF65F4FF.toInt(), ansi[10])
    }

    @Test
    fun `parses iTerm2 itermcolors XML plist`() {
        val plist = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>Ansi 0 Color</key>
                <dict>
                    <key>Blue Component</key><real>0.2</real>
                    <key>Green Component</key><real>0.2</real>
                    <key>Red Component</key><real>0.2</real>
                </dict>
                <key>Ansi 1 Color</key>
                <dict>
                    <key>Red Component</key><real>1.0</real>
                    <key>Green Component</key><real>0.0</real>
                    <key>Blue Component</key><real>0.0</real>
                </dict>
                <key>Ansi 2 Color</key>
                <dict>
                    <key>Red Component</key><real>0.0</real>
                    <key>Green Component</key><real>1.0</real>
                    <key>Blue Component</key><real>0.0</real>
                </dict>
                <key>Ansi 3 Color</key>
                <dict>
                    <key>Red Component</key><real>1.0</real>
                    <key>Green Component</key><real>1.0</real>
                    <key>Blue Component</key><real>0.0</real>
                </dict>
                <key>Ansi 4 Color</key>
                <dict>
                    <key>Red Component</key><real>0.0</real>
                    <key>Green Component</key><real>0.0</real>
                    <key>Blue Component</key><real>1.0</real>
                </dict>
                <key>Ansi 5 Color</key>
                <dict>
                    <key>Red Component</key><real>1.0</real>
                    <key>Green Component</key><real>0.0</real>
                    <key>Blue Component</key><real>1.0</real>
                </dict>
                <key>Ansi 6 Color</key>
                <dict>
                    <key>Red Component</key><real>0.0</real>
                    <key>Green Component</key><real>1.0</real>
                    <key>Blue Component</key><real>1.0</real>
                </dict>
                <key>Ansi 7 Color</key>
                <dict>
                    <key>Red Component</key><real>0.8</real>
                    <key>Green Component</key><real>0.8</real>
                    <key>Blue Component</key><real>0.8</real>
                </dict>
                <key>Background Color</key>
                <dict>
                    <key>Red Component</key><real>0.0</real>
                    <key>Green Component</key><real>0.0</real>
                    <key>Blue Component</key><real>0.0</real>
                </dict>
                <key>Foreground Color</key>
                <dict>
                    <key>Red Component</key><real>1.0</real>
                    <key>Green Component</key><real>1.0</real>
                    <key>Blue Component</key><real>1.0</real>
                </dict>
            </dict>
            </plist>
        """.trimIndent()

        val result = importColorScheme(plist, fallbackName = "Classic.itermcolors")
        assertTrue(result.isSuccess)
        val scheme = result.getOrThrow()
        assertEquals("Classic", scheme.name)
        assertEquals(0xFF000000.toInt(), scheme.background)
        assertEquals(0xFFFFFFFF.toInt(), scheme.foreground)
        val ansi = scheme.ansiColors()
        assertEquals(0xFFFF0000.toInt(), ansi[1])
        assertEquals(0xFFFF0000.toInt(), ansi[9]) // copied from 0..7 when bright omitted
    }

    @Test
    fun `parses Kitty and Termux properties and Windows Terminal JSON`() {
        val kitty = """
            foreground #abb2bf
            background #282c34
            cursor #528bff
            color0 #282c34
            color1 #e06c75
            color2 #98c379
            color3 #e5c07b
            color4 #61afef
            color5 #c678dd
            color6 #56b6c2
            color7 #abb2bf
        """.trimIndent()

        val kittyRes = importColorScheme(kitty)
        assertTrue(kittyRes.isSuccess)
        assertEquals(0xFF282C34.toInt(), kittyRes.getOrThrow().background)
        assertEquals(0xFF528BFF.toInt(), kittyRes.getOrThrow().cursor)

        val winTerm = """
            [
              {
                "name": "One Half Dark",
                "background": "#282C34",
                "foreground": "#DCDFE4",
                "cursorColor": "#FFFFFF",
                "selectionBackground": "#474E5D",
                "black": "#282C34",
                "red": "#E06C75",
                "green": "#98C379",
                "yellow": "#E5C07B",
                "blue": "#61AFEF",
                "purple": "#C678DD",
                "cyan": "#56B6C2",
                "white": "#DCDFE4",
                "brightBlack": "#5A6374",
                "brightRed": "#E06C75",
                "brightGreen": "#98C379",
                "brightYellow": "#E5C07B",
                "brightBlue": "#61AFEF",
                "brightPurple": "#C678DD",
                "brightCyan": "#56B6C2",
                "brightWhite": "#DCDFE4"
              }
            ]
        """.trimIndent()

        val wtRes = importColorScheme(winTerm)
        assertTrue(wtRes.isSuccess)
        assertEquals("One Half Dark", wtRes.getOrThrow().name)
        assertEquals(0xFFC678DD.toInt(), wtRes.getOrThrow().ansiColors()[5])
    }

    @Test
    fun `parses Gogh JSON theme with 1-indexed color_01 to color_16`() {
        val goghJson = """
            {
              "name": "3024 Day",
              "author": "",
              "variant": "light",
              "color_01": "#090300",
              "color_02": "#DB2D20",
              "color_03": "#01A252",
              "color_04": "#FDED02",
              "color_05": "#01A0E4",
              "color_06": "#A16A94",
              "color_07": "#B5E4F4",
              "color_08": "#A5A2A2",
              "color_09": "#5C5855",
              "color_10": "#E8BBD0",
              "color_11": "#3A3432",
              "color_12": "#4A4543",
              "color_13": "#807D7C",
              "color_14": "#D6D5D4",
              "color_15": "#CDAB53",
              "color_16": "#F7F7F7",
              "background": "#F7F7F7",
              "foreground": "#4A4543",
              "cursor": "#4A4543"
            }
        """.trimIndent()

        val result = importColorScheme(goghJson)
        assertTrue(result.isSuccess)
        val scheme = result.getOrThrow()
        assertEquals("3024 Day", scheme.name)
        assertEquals(0xFFF7F7F7.toInt(), scheme.background)
        assertEquals(0xFF4A4543.toInt(), scheme.foreground)
        assertEquals(0xFF4A4543.toInt(), scheme.cursor)
        val ansi = scheme.ansiColors()
        assertEquals(0xFF090300.toInt(), ansi[0])
        assertEquals(0xFFF7F7F7.toInt(), ansi[15])
    }

    @Test
    fun `parses Gogh YAML theme with 1-indexed color_01 to color_16`() {
        val goghYaml = """
            name: 'Afterglow'
            color_01: '#151515'
            color_02: '#AC4142'
            color_03: '#7E8E50'
            color_04: '#E5B567'
            color_05: '#6C99BB'
            color_06: '#9F4E85'
            color_07: '#7DD6CF'
            color_08: '#D0D0D0'
            color_09: '#505050'
            color_10: '#AC4142'
            color_11: '#7E8E50'
            color_12: '#E5B567'
            color_13: '#6C99BB'
            color_14: '#9F4E85'
            color_15: '#7DD6CF'
            color_16: '#F5F5F5'
            background: '#212121'
            foreground: '#D0D0D0'
            cursor: '#D0D0D0'
        """.trimIndent()

        val result = importColorScheme(goghYaml)
        assertTrue(result.isSuccess)
        val scheme = result.getOrThrow()
        assertEquals("Afterglow", scheme.name)
        assertEquals(0xFF212121.toInt(), scheme.background)
        assertEquals(0xFFD0D0D0.toInt(), scheme.foreground)
        assertEquals(0xFFD0D0D0.toInt(), scheme.cursor)
        val ansi = scheme.ansiColors()
        assertEquals(0xFF151515.toInt(), ansi[0])
        assertEquals(0xFF7DD6CF.toInt(), ansi[6])
        assertEquals(0xFFF5F5F5.toInt(), ansi[15])
    }

    @Test
    fun `invalid input returns failure with actionable message`() {
        val res = importColorScheme("hello world not a theme")
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull()?.message?.contains("Ghostty") == true)
    }
}
