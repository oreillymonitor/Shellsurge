package io.github.lordofpolls.shellwave.core.prefs

import android.content.Context
import androidx.core.content.edit

private const val PREFS_NAME = "shellwave_prefs"
private const val KEY_KEYBOARD_ENHANCED_INPUT = "keyboard_enhanced_input"

/**
 * Whether the soft keyboard in the terminal allows native typing enhancements
 * (autocorrect, spellcheck, suggestions strip, swipe typing, and voice dictation).
 *
 * When false, the input field uses KeyboardType.PasswordVisible to prevent the IME
 * from learning or offering suggestions. Defaults to true.
 */
object TerminalInputPreferences {
    fun getKeyboardEnhancedInput(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_KEYBOARD_ENHANCED_INPUT, true)

    fun setKeyboardEnhancedInput(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_KEYBOARD_ENHANCED_INPUT, enabled)
        }
    }
}
