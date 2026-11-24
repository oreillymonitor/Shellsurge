package io.github.lordofpolls.shellwave.feature.session

import android.content.Context
import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerationConfig
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelConfig
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import io.github.lordofpolls.shellwave.core.prefs.NanoModelPreferences
import io.github.lordofpolls.shellwave.core.prefs.NanoModelTier
import io.github.lordofpolls.shellwave.core.prefs.NanoReleaseChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class NanoConfigKey(
    val tier: NanoModelTier,
    val channel: NanoReleaseChannel,
)

internal data class NanoDiagnostics(
    val status: Int,
    val baseModelName: String? = null,
    val tokenLimit: Int? = null,
    val supportsSystemPrompt: Boolean = false,
    val supportsThinkingMode: Boolean = false,
    val supportsPrefixCaching: Boolean = false,
    val errorMessage: String? = null,
)

internal data class NanoGenerationOutcome(
    val text: String?,
    val baseModelName: String?,
    val unavailableNotice: String?,
)

private const val MAX_SANE_TOKEN_LIMIT = 1_000_000
private const val FALLBACK_UNBOUNDED_TOKEN_LIMIT = 32_768

internal fun sanitizeTokenLimit(rawLimit: Int?): Int? =
    rawLimit?.takeIf { it in 1..MAX_SANE_TOKEN_LIMIT }

internal fun computeMaxInputTokens(
    tokenLimit: Int?,
    maxOutputTokens: Int,
    defaultLimit: Int = 4096,
): Int {
    val effectiveLimit = when {
        tokenLimit == null || tokenLimit <= 0 -> defaultLimit.toLong()
        tokenLimit > MAX_SANE_TOKEN_LIMIT -> FALLBACK_UNBOUNDED_TOKEN_LIMIT.toLong()
        else -> tokenLimit.toLong()
    }
    return (effectiveLimit - maxOutputTokens.toLong() - 128L)
        .coerceAtLeast(512L)
        .coerceAtMost(MAX_SANE_TOKEN_LIMIT.toLong())
        .toInt()
}

internal fun computeMaxInputCharBudget(
    tokenLimit: Int?,
    maxOutputTokens: Int,
    defaultLimit: Int = 4096,
): Int =
    (computeMaxInputTokens(tokenLimit, maxOutputTokens, defaultLimit).toLong() * 4L)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()

internal fun trimTailLinesToCharBudget(text: String, maxChars: Int): String {
    if (maxChars <= 0) return ""
    if (text.length <= maxChars) return text

    val lines = text.lines()
    val selected = ArrayDeque<String>()
    var total = 0

    for (idx in lines.indices.reversed()) {
        val line = lines[idx]
        val addedLen = line.length + if (selected.isEmpty()) 0 else 1
        if (total + addedLen <= maxChars) {
            selected.addFirst(line)
            total += addedLen
        } else {
            break
        }
    }

    if (selected.isNotEmpty()) {
        return selected.joinToString("\n")
    }
    // Single huge line (e.g. minified log/stacktrace): keep the most recent trailing characters.
    return text.takeLast(maxChars)
}

internal fun combineSystemAndUserPrompt(systemInstruction: String, userText: String): String =
    buildString {
        append(systemInstruction.trim())
        append("\n\n")
        append(userText.trim())
    }

internal fun formatModelLabel(
    baseModelName: String?,
    tier: NanoModelTier,
    channel: NanoReleaseChannel,
): String = baseModelName?.takeIf { it.isNotBlank() } ?: "Gemini Nano (${tier.label}/${channel.label})"

/**
 * Centralized manager for Android AICore / ML Kit GenAI `GenerativeModel` instances:
 * handles model-tier & release-channel client lifecycle, warmup, capability inspection,
 * input token-budget trimming, and streaming/non-streaming execution.
 */
internal object NanoClientManager {
    private const val TAG = "NanoClientManager"
    private const val DEFAULT_TOKEN_LIMIT = 4096

    private val lock = Any()
    private var cachedKey: NanoConfigKey? = null
    private var cachedModel: GenerativeModel? = null
    private var cachedDiagnostics: NanoDiagnostics? = null
    private var warmedUpKey: NanoConfigKey? = null

    private fun readKey(context: Context?): NanoConfigKey =
        if (context != null) {
            NanoConfigKey(
                tier = NanoModelPreferences.getModelTier(context),
                channel = NanoModelPreferences.getReleaseChannel(context),
            )
        } else {
            NanoConfigKey(NanoModelTier.FULL, NanoReleaseChannel.STABLE)
        }

    private fun getOrCreateModel(key: NanoConfigKey): GenerativeModel {
        synchronized(lock) {
            cachedModel?.let { existing ->
                if (cachedKey == key) return existing
                runCatching { existing.close() }
                cachedModel = null
                cachedDiagnostics = null
                warmedUpKey = null
            }

            val modelConfig = ModelConfig.builder().apply {
                preference = key.tier.preferenceValue
                releaseStage = key.channel.stageValue
            }.build()
            val genConfig = GenerationConfig.Builder().apply {
                this.modelConfig = modelConfig
            }.build()

            return Generation.getClient(genConfig).also {
                cachedKey = key
                cachedModel = it
            }
        }
    }

    fun invalidate() {
        synchronized(lock) {
            runCatching { cachedModel?.close() }
            cachedModel = null
            cachedKey = null
            cachedDiagnostics = null
            warmedUpKey = null
        }
    }

    suspend fun inspectDiagnostics(
        context: Context,
        forceRefresh: Boolean = false,
    ): NanoDiagnostics = withContext(Dispatchers.IO) {
        val key = readKey(context)
        if (!forceRefresh) {
            synchronized(lock) {
                val diag = cachedDiagnostics
                if (cachedKey == key && diag != null && diag.status == FeatureStatus.AVAILABLE) {
                    return@withContext diag
                }
            }
        }

        try {
            val model = getOrCreateModel(key)
            val status = model.checkStatus()
            val diag = if (status == FeatureStatus.AVAILABLE) {
                buildAvailableDiagnostics(model, status)
            } else {
                NanoDiagnostics(status = status)
            }
            synchronized(lock) {
                if (cachedKey == key) cachedDiagnostics = diag
            }
            diag
        } catch (e: Throwable) {
            val err = e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
            Log.w(TAG, "inspectDiagnostics failed", e)
            NanoDiagnostics(status = FeatureStatus.UNAVAILABLE, errorMessage = err)
        }
    }

    private suspend fun buildAvailableDiagnostics(
        model: GenerativeModel,
        status: Int,
    ): NanoDiagnostics {
        val baseModelName = runCatching { model.getBaseModelName() }.getOrNull()?.takeIf { it.isNotBlank() }
        val tokenLimit = sanitizeTokenLimit(runCatching { model.getTokenLimit() }.getOrNull())
        val supportsSystemPrompt = runCatching { model.isSystemPromptAvailable() }.getOrDefault(false)
        val supportsThinkingMode = runCatching { model.isThinkingModeAvailable() }.getOrDefault(false)
        val supportsPrefixCaching = runCatching { model.isCachingFeatureAvailable() }.getOrDefault(false)
        return NanoDiagnostics(
            status = status,
            baseModelName = baseModelName,
            tokenLimit = tokenLimit,
            supportsSystemPrompt = supportsSystemPrompt,
            supportsThinkingMode = supportsThinkingMode,
            supportsPrefixCaching = supportsPrefixCaching,
        )
    }

    suspend fun warmupIfEnabled(context: Context, force: Boolean = false): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (!force && !NanoModelPreferences.isWarmupEnabled(context)) {
                return@withContext Result.success(Unit)
            }
            val key = readKey(context)
            if (!force) {
                synchronized(lock) {
                    if (warmedUpKey == key) return@withContext Result.success(Unit)
                }
            }
            runCatching {
                val model = getOrCreateModel(key)
                if (model.checkStatus() == FeatureStatus.AVAILABLE) {
                    model.warmup()
                    synchronized(lock) {
                        if (cachedKey == key) warmedUpKey = key
                    }
                }
            }
        }

    suspend fun downloadIfNeeded(
        context: Context,
        onProgress: (String) -> Unit = {},
    ): NanoDiagnostics = withContext(Dispatchers.IO) {
        val key = readKey(context)
        try {
            val model = getOrCreateModel(key)
            val status = model.checkStatus()
            if (status == FeatureStatus.DOWNLOADABLE) {
                collectDownload(model, onProgress)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Download check failed: ${e.message}")
        }
        inspectDiagnostics(context, forceRefresh = true)
    }

    private suspend fun collectDownload(
        model: GenerativeModel,
        onProgress: (String) -> Unit,
    ): Boolean {
        onProgress("Downloading Gemini Nano from AICore…")
        var downloadSucceeded = false
        try {
            model.download().collect { dStatus ->
                when (dStatus) {
                    is DownloadStatus.DownloadProgress -> {
                        val mb = dStatus.totalBytesDownloaded / (1024 * 1024)
                        onProgress("Downloading Gemini Nano: ${mb}MB…")
                    }
                    is DownloadStatus.DownloadCompleted -> {
                        downloadSucceeded = true
                        onProgress("Gemini Nano ready. Generating…")
                    }
                    is DownloadStatus.DownloadFailed -> {
                        Log.w(TAG, "Download failed: $dStatus")
                    }
                    else -> Unit
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Download flow error: ${e.message}")
        }
        return downloadSucceeded
    }

    suspend fun generateText(
        context: Context?,
        systemInstruction: String,
        userText: String,
        temperature: Float,
        topK: Int,
        maxOutputTokens: Int,
        allowThinking: Boolean = false,
        onProgress: (String) -> Unit = {},
        onPartialText: ((String) -> Unit)? = null,
    ): NanoGenerationOutcome = withContext(Dispatchers.IO) {
        val key = readKey(context)
        try {
            val model = getOrCreateModel(key)
            var status = model.checkStatus()

            if (status == FeatureStatus.DOWNLOADABLE) {
                if (collectDownload(model, onProgress)) {
                    status = FeatureStatus.AVAILABLE
                }
            }

            if (status != FeatureStatus.AVAILABLE) {
                val reason = when (status) {
                    FeatureStatus.DOWNLOADING -> "Gemini Nano is downloading via AICore."
                    FeatureStatus.DOWNLOADABLE -> "Gemini Nano download required in AICore."
                    FeatureStatus.UNAVAILABLE -> "Gemini Nano unavailable. Enable AICore in Developer Options."
                    else -> "AICore status: $status"
                }
                return@withContext NanoGenerationOutcome(null, null, reason)
            }

            val diag = synchronized(lock) {
                cachedDiagnostics?.takeIf { cachedKey == key && it.status == FeatureStatus.AVAILABLE }
            } ?: buildAvailableDiagnostics(model, status).also { fresh ->
                synchronized(lock) {
                    if (cachedKey == key) cachedDiagnostics = fresh
                }
            }

            val thinkingWanted = allowThinking &&
                diag.supportsThinkingMode &&
                (context?.let { NanoModelPreferences.isThinkingModeEnabled(it) } ?: false)

            val maxInputTokens = computeMaxInputTokens(diag.tokenLimit, maxOutputTokens, DEFAULT_TOKEN_LIMIT)
            val initialCharBudget = computeMaxInputCharBudget(diag.tokenLimit, maxOutputTokens, DEFAULT_TOKEN_LIMIT)

            fun buildRequest(body: String): GenerateContentRequest {
                val builder = if (diag.supportsSystemPrompt) {
                    GenerateContentRequest.Builder(SystemInstruction(systemInstruction), TextPart(body))
                } else {
                    GenerateContentRequest.Builder(
                        TextPart(combineSystemAndUserPrompt(systemInstruction, body))
                    )
                }
                builder.temperature = temperature
                builder.topK = topK
                builder.maxOutputTokens = maxOutputTokens
                builder.candidateCount = 1
                if (thinkingWanted) {
                    builder.enableThinking = true
                }
                return builder.build()
            }

            // Trim input tail safely to fit inside the active Nano model's context window.
            var boundedBody = trimTailLinesToCharBudget(userText, initialCharBudget)
            repeat(3) {
                val tokenCount = runCatching {
                    model.countTokens(buildRequest(boundedBody)).totalTokens
                }.getOrNull() ?: return@repeat
                if (tokenCount <= maxInputTokens) return@repeat
                val ratio = maxInputTokens.toDouble() / tokenCount.toDouble()
                val nextBudget = (boundedBody.length.toDouble() * ratio * 0.90)
                    .toLong()
                    .coerceIn(256L, Int.MAX_VALUE.toLong())
                    .toInt()
                boundedBody = trimTailLinesToCharBudget(boundedBody, nextBudget)
            }

            val request = buildRequest(boundedBody)
            val generated = if (onPartialText != null) {
                streamOrFallback(model, request, onPartialText)
            } else {
                model.generateContent(request).candidates.firstOrNull()?.text?.trim()
            }

            NanoGenerationOutcome(
                text = generated?.takeIf { it.isNotBlank() },
                baseModelName = diag.baseModelName,
                unavailableNotice = null,
            )
        } catch (e: Throwable) {
            val err = e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
            Log.w(TAG, "Gemini Nano generation failed", e)
            NanoGenerationOutcome(null, null, err)
        }
    }

    private suspend fun streamOrFallback(
        model: GenerativeModel,
        request: GenerateContentRequest,
        onPartialText: (String) -> Unit,
    ): String? {
        val sb = StringBuilder()
        val streamResult = runCatching {
            model.generateContentStream(request).collect { chunk ->
                val part = chunk.candidates.firstOrNull()?.text.orEmpty()
                if (part.isNotEmpty()) {
                    sb.append(part)
                    onPartialText(sb.toString())
                }
            }
            sb.toString().trim()
        }
        val streamed = streamResult.getOrNull()?.takeIf { it.isNotBlank() }
        if (streamed != null) return streamed

        return model.generateContent(request).candidates.firstOrNull()?.text?.trim()
    }
}
