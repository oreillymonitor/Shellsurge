package io.github.lordofpolls.shellwave.core.prefs

import android.content.Context
import androidx.core.content.edit
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage

enum class NanoModelTier(val label: String, val preferenceValue: Int) {
    FULL("Full", ModelPreference.FULL),
    FAST("Fast", ModelPreference.FAST),
}

enum class NanoReleaseChannel(val label: String, val stageValue: Int) {
    STABLE("Stable", ModelReleaseStage.STABLE),
    PREVIEW("Preview", ModelReleaseStage.PREVIEW),
}

private const val KEY_NANO_MODEL_TIER = "nano_model_tier"
private const val KEY_NANO_RELEASE_CHANNEL = "nano_release_channel"
private const val KEY_NANO_THINKING_MODE = "nano_thinking_mode"
private const val KEY_NANO_WARMUP_ENABLED = "nano_warmup_enabled"

/**
 * Preferences controlling Android AICore / Gemini Nano model selection and runtime tuning.
 */
object NanoModelPreferences {
    fun getModelTier(context: Context): NanoModelTier {
        val raw = sharedPrefs(context).getString(KEY_NANO_MODEL_TIER, null)
        return raw?.let { runCatching { NanoModelTier.valueOf(it) }.getOrNull() } ?: NanoModelTier.FULL
    }

    fun setModelTier(context: Context, tier: NanoModelTier) {
        sharedPrefs(context).edit { putString(KEY_NANO_MODEL_TIER, tier.name) }
    }

    fun getReleaseChannel(context: Context): NanoReleaseChannel {
        val raw = sharedPrefs(context).getString(KEY_NANO_RELEASE_CHANNEL, null)
        return raw?.let { runCatching { NanoReleaseChannel.valueOf(it) }.getOrNull() }
            ?: NanoReleaseChannel.STABLE
    }

    fun setReleaseChannel(context: Context, channel: NanoReleaseChannel) {
        sharedPrefs(context).edit { putString(KEY_NANO_RELEASE_CHANNEL, channel.name) }
    }

    fun isThinkingModeEnabled(context: Context): Boolean =
        sharedPrefs(context).getBoolean(KEY_NANO_THINKING_MODE, false)

    fun setThinkingModeEnabled(context: Context, enabled: Boolean) {
        sharedPrefs(context).edit { putBoolean(KEY_NANO_THINKING_MODE, enabled) }
    }

    fun isWarmupEnabled(context: Context): Boolean =
        sharedPrefs(context).getBoolean(KEY_NANO_WARMUP_ENABLED, true)

    fun setWarmupEnabled(context: Context, enabled: Boolean) {
        sharedPrefs(context).edit { putBoolean(KEY_NANO_WARMUP_ENABLED, enabled) }
    }
}
