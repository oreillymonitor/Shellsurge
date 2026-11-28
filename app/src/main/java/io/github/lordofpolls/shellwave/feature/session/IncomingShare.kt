package io.github.lordofpolls.shellwave.feature.session

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import java.util.Locale

internal enum class ShareFramingPreset(
    val id: String,
    val label: String,
    val instruction: String?,
) {
    NONE("none", "As-is", null),
    INVESTIGATE("investigate", "Investigate", "Investigate this and explain the root cause:"),
    FIX("fix", "Fix", "Diagnose and fix this issue:"),
    IMPLEMENT("implement", "Implement", "Implement this in the current codebase:"),
    SUMMARIZE("summarize", "Summarize", "Summarize the key points from this:"),
    COMPARE("compare", "Compare", "Compare this with the current branch and suggest changes:"),
}

internal data class SharedAttachment(
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val isImage: Boolean,
)

internal data class IncomingSharePayload(
    val subject: String?,
    val text: String?,
    val attachments: List<SharedAttachment>,
) {
    val isEmpty: Boolean
        get() = subject.isNullOrBlank() && text.isNullOrBlank() && attachments.isEmpty()

    val hasAttachments: Boolean
        get() = attachments.isNotEmpty()
}

data class PendingComposerDraft(
    val sessionId: Long,
    val text: String,
    val nonce: Long = System.nanoTime(),
)

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif", "avif",
)

internal fun normalizeSharedContent(subject: String?, text: String?): Pair<String?, String?> {
    val cleanSubject = subject?.trim()?.takeIf { it.isNotEmpty() }
    val cleanText = text?.trim()?.takeIf { it.isNotEmpty() }
    if (cleanSubject != null && cleanText != null) {
        val exactLineMatch = cleanText.lineSequence().any { it.trim().equals(cleanSubject, ignoreCase = true) }
        val prefixBoundaryMatch = cleanText.startsWith(cleanSubject, ignoreCase = true) &&
            (cleanText.length == cleanSubject.length || !cleanText[cleanSubject.length].isLetterOrDigit())
        if (exactLineMatch || prefixBoundaryMatch) {
            return null to cleanText
        }
    }
    return cleanSubject to cleanText
}

internal fun isImageAttachment(mimeType: String?, displayName: String?): Boolean {
    val cleanMime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
    if (cleanMime != null && cleanMime.startsWith("image/")) {
        return true
    }
    val ext = displayName?.substringAfterLast('.', "")?.trim()?.lowercase(Locale.ROOT)
    return ext != null && ext in IMAGE_EXTENSIONS
}

internal fun resolveEffectiveMimeType(resolvedMimeType: String?, intentMimeType: String?): String? {
    val resolved = resolvedMimeType?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }
    val fromIntent = intentMimeType?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() && it != "*/*" }
    return when {
        resolved == null -> fromIntent
        resolved == "*/*" || resolved == "application/octet-stream" -> fromIntent ?: resolved
        else -> resolved
    }
}

internal fun formatAttachmentSize(sizeBytes: Long): String? {
    if (sizeBytes < 0L) return null
    if (sizeBytes < 1024L) return "$sizeBytes B"
    if (sizeBytes < 1024L * 1024L) return "${(sizeBytes / 1024L).coerceAtLeast(1L)} KB"
    return String.format(Locale.US, "%.1f MB", sizeBytes / (1024.0 * 1024.0))
}

internal fun buildSharedPrompt(
    subject: String?,
    text: String?,
    uploadedPaths: List<String>,
    preset: ShareFramingPreset,
    note: String = "",
): String {
    val (cleanSubject, cleanText) = normalizeSharedContent(subject, text)
    val cleanPaths = uploadedPaths.map { it.trim() }.filter { it.isNotEmpty() }
    val contentBlock = listOfNotNull(cleanSubject, cleanText).joinToString("\n")
    val noteText = note.trim()

    if (preset == ShareFramingPreset.NONE && noteText.isEmpty() && contentBlock.isEmpty() && cleanPaths.isNotEmpty()) {
        return cleanPaths.joinToString(" ")
    }

    val sections = mutableListOf<String>()
    preset.instruction?.let(sections::add)
    if (noteText.isNotEmpty()) sections.add(noteText)
    if (contentBlock.isNotEmpty()) sections.add(contentBlock)
    if (cleanPaths.isNotEmpty()) {
        val attachmentSection = if (cleanPaths.size == 1) {
            "Attached: ${cleanPaths.single()}"
        } else {
            "Attached:\n${cleanPaths.joinToString("\n")}"
        }
        sections.add(attachmentSection)
    }
    return sections.joinToString("\n\n")
}

internal fun extractIncomingShare(context: Context, intent: Intent?): IncomingSharePayload? {
    if (intent == null) return null
    val action = intent.action
    if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null

    val uriSet = LinkedHashSet<Uri>()
    when (action) {
        Intent.ACTION_SEND -> {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(uriSet::add)
        }
        Intent.ACTION_SEND_MULTIPLE -> {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.forEach { uri -> uri?.let(uriSet::add) }
        }
    }

    val clipTexts = mutableListOf<String>()
    intent.clipData?.let { clip ->
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            item.uri?.let(uriSet::add)
            item.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(clipTexts::add)
        }
    }

    val rawSubject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
    val rawText = intent.getStringExtra(Intent.EXTRA_TEXT)
        ?: clipTexts.takeIf { it.isNotEmpty() }?.joinToString("\n")
    val (cleanSubject, cleanText) = normalizeSharedContent(rawSubject, rawText)

    val attachments = uriSet.map { uri ->
        val displayName = querySharedDisplayName(context, uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "shared-file"
        val sizeBytes = querySharedSize(context, uri)
        val resolvedMime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val effectiveMime = resolveEffectiveMimeType(resolvedMime, intent.type)
        SharedAttachment(
            uri = uri,
            displayName = displayName,
            mimeType = effectiveMime,
            sizeBytes = sizeBytes,
            isImage = isImageAttachment(effectiveMime, displayName),
        )
    }

    val payload = IncomingSharePayload(
        subject = cleanSubject,
        text = cleanText,
        attachments = attachments,
    )
    return payload.takeUnless { it.isEmpty }
}

private fun querySharedDisplayName(context: Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    cursor.getString(0)?.trim()?.takeIf { it.isNotEmpty() }
                } else {
                    null
                }
            }
    }.getOrNull()

private fun querySharedSize(context: Context, uri: Uri): Long =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
            }
    }.getOrNull() ?: -1L
