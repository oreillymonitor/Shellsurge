package io.github.lordofpolls.shellwave.core.prefs

import android.content.Context
import androidx.core.content.edit

private const val PREFS_NAME = "shellwave_prefs"
private const val KEY_FULL_WIDTH_TERMINAL = "full_width_terminal"
private const val KEY_HIDE_KEY_BAR_WITH_KEYBOARD = "hide_key_bar_with_keyboard"
private const val KEY_COMPOSER_RAW_TIP_DISMISSED = "composer_raw_tip_dismissed"
private const val KEY_DEFAULT_KEY_BAR_DECK = "default_key_bar_deck"
private const val KEY_READABLE_FONT_SIZE_SP = "readable_output_font_size_sp"

internal const val DEFAULT_READABLE_FONT_SIZE_SP = 15
internal val READABLE_FONT_SIZE_RANGE = 12..20

enum class DefaultKeyBarDeck(val label: String) {
    AGENT("Agent"),
    SHELL("Shell"),
}

/**
 * Whether the terminal takes the whole wide/unfolded window. Two panes are a choice, never a tax:
 * `tmux` splits, `htop` and long log lines all want every column the unfolded screen has.
 */
object SessionLayoutPreferences {
    fun getFullWidthTerminal(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FULL_WIDTH_TERMINAL, false)

    fun setFullWidthTerminal(context: Context, fullWidth: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_FULL_WIDTH_TERMINAL, fullWidth).apply()
    }

    fun getHideKeyBarWithKeyboard(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_KEY_BAR_WITH_KEYBOARD, false)

    fun setHideKeyBarWithKeyboard(context: Context, hide: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_HIDE_KEY_BAR_WITH_KEYBOARD, hide) }
    }

    fun isComposerRawTipDismissed(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_COMPOSER_RAW_TIP_DISMISSED, false)

    fun setComposerRawTipDismissed(context: Context, dismissed: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_COMPOSER_RAW_TIP_DISMISSED, dismissed) }
    }

    fun getDefaultKeyBarDeck(context: Context): DefaultKeyBarDeck {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEFAULT_KEY_BAR_DECK, null)
        return DefaultKeyBarDeck.entries.firstOrNull { it.name == stored } ?: DefaultKeyBarDeck.AGENT
    }

    fun setDefaultKeyBarDeck(context: Context, deck: DefaultKeyBarDeck) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_DEFAULT_KEY_BAR_DECK, deck.name) }
    }

    fun getReadableFontSizeSp(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_READABLE_FONT_SIZE_SP, DEFAULT_READABLE_FONT_SIZE_SP)
            .coerceIn(READABLE_FONT_SIZE_RANGE)

    fun setReadableFontSizeSp(context: Context, fontSizeSp: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putInt(KEY_READABLE_FONT_SIZE_SP, fontSizeSp.coerceIn(READABLE_FONT_SIZE_RANGE)) }
    }
}
