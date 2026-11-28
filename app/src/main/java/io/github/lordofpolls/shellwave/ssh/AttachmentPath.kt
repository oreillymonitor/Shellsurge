package io.github.lordofpolls.shellwave.ssh

private const val MAX_ATTACHMENT_FILENAME_CHARS = 64
private const val MAX_EXTENSION_CHARS = 16

private val MIME_EXTENSION_MAP = mapOf(
    "application/pdf" to "pdf",
    "text/markdown" to "md",
    "text/x-markdown" to "md",
    "text/plain" to "txt",
    "application/json" to "json",
    "text/json" to "json",
    "text/csv" to "csv",
    "application/xml" to "xml",
    "text/xml" to "xml",
    "text/html" to "html",
    "application/zip" to "zip",
    "application/gzip" to "gz",
    "application/x-gzip" to "gz",
    "application/x-yaml" to "yaml",
    "text/yaml" to "yaml",
    "text/x-yaml" to "yaml",
    "image/png" to "png",
    "image/jpeg" to "jpg",
    "image/webp" to "webp",
    "image/gif" to "gif",
)

/**
 * Builds a shell-safe remote path under `/tmp/` for an uploaded prompt attachment, preserving the
 * sanitized filename and extension (or falling back to [mimeType] when needed).
 */
internal fun buildRemoteAttachmentPath(
    displayName: String?,
    mimeType: String?,
    timestampMs: Long,
): String {
    val baseName = displayName
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.trim()
        .orEmpty()

    val lastDot = baseName.lastIndexOf('.')
    val candidateExt = if (lastDot > 0) baseName.substring(lastDot + 1) else null
    val hasValidExt = candidateExt != null &&
        candidateExt.length in 1..MAX_EXTENSION_CHARS &&
        candidateExt.all { it.isAsciiAlphanumeric() }

    val rawStem = if (hasValidExt) baseName.substring(0, lastDot) else baseName
    val resolvedExt = if (hasValidExt) {
        candidateExt.lowercase()
    } else {
        mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.let(MIME_EXTENSION_MAP::get)
    }

    val sanitizedStem = sanitizeStem(rawStem)
    val extSuffix = if (resolvedExt != null) ".$resolvedExt" else ""
    val maxStemLength = (MAX_ATTACHMENT_FILENAME_CHARS - extSuffix.length).coerceAtLeast(1)
    val boundedStem = sanitizedStem
        .take(maxStemLength)
        .trimEnd('_', '-')
        .ifEmpty { "file" }

    return "/tmp/shellsurge-$timestampMs-$boundedStem$extSuffix"
}

private fun sanitizeStem(rawStem: String): String =
    rawStem
        .map { ch -> if (ch.isAsciiAlphanumeric() || ch == '_' || ch == '-') ch else '_' }
        .joinToString("")
        .replace(Regex("_+"), "_")
        .trim('_', '-', '.')
        .ifEmpty { "file" }

private fun Char.isAsciiAlphanumeric(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
