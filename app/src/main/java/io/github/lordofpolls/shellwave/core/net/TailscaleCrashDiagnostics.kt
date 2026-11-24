package io.github.lordofpolls.shellwave.core.net

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.core.content.edit
import io.github.lordofpolls.shellwave.BuildConfig
import java.io.File
import java.time.Instant

private const val PREFS_NAME = "tailscale_diagnostics"
private const val KEY_DISMISSED_EXIT_TS = "dismissed_exit_ts"

private const val FILE_STAGE = "tsnet_stage.txt"
private const val FILE_UNCAUGHT = "last_uncaught_exception.txt"
private const val FILE_GO_STAGE = "tsnet/go_stage.txt"
private const val FILE_GO_PANIC = "tsnet/go_panic.txt"

private val INTERESTING_EXIT_REASONS = setOf(
    ApplicationExitInfo.REASON_CRASH,
    ApplicationExitInfo.REASON_CRASH_NATIVE,
    ApplicationExitInfo.REASON_ANR,
    ApplicationExitInfo.REASON_LOW_MEMORY,
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
)

private val TRACE_KEYWORDS = listOf(
    "SIG",
    "abort",
    "panic",
    "fatal",
    "libgojni",
    "go/",
    "tsbridge",
    "tsnet",
    "tailscale",
    "runtime",
    "cgo",
    "Exception",
    "Error",
    "libc",
    "libart",
    "Java_",
    "fault",
    "Backtrace",
)

/**
 * Persists startup stage breadcrumbs, uncaught Java/Kotlin exceptions, and Android's
 * [ApplicationExitInfo] native crash tombstones so users without `adb` can copy the exact
 * failure reason straight from the Tailscale settings card.
 */
object TailscaleCrashDiagnostics {

    fun recordStage(context: Context, stage: String) {
        runCatching {
            File(context.filesDir, FILE_STAGE).writeText("${Instant.now()} $stage\n")
        }
    }

    fun recordUncaughtException(context: Context, threadName: String, throwable: Throwable) {
        runCatching {
            val content = buildString {
                appendLine("${Instant.now()} thread=$threadName")
                appendLine(throwable.stackTraceToString().take(8_000))
            }
            File(context.filesDir, FILE_UNCAUGHT).writeText(content)
        }
    }

    fun readLastCrashReport(context: Context): String? {
        val goPanic = readSmallFile(File(context.filesDir, FILE_GO_PANIC), maxChars = 4_000)
        val uncaught = readSmallFile(File(context.filesDir, FILE_UNCAUGHT), maxChars = 4_000)
        val ktStage = readSmallFile(File(context.filesDir, FILE_STAGE), maxChars = 400)
        val goStage = readSmallFile(File(context.filesDir, FILE_GO_STAGE), maxChars = 400)

        val dismissedTs = runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_DISMISSED_EXIT_TS, 0L)
        }.getOrDefault(0L)

        val exitInfo = runCatching {
            val am = context.getSystemService(ActivityManager::class.java) ?: return@runCatching null
            am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
                .firstOrNull { info ->
                    info.reason in INTERESTING_EXIT_REASONS && info.timestamp > dismissedTs
                }
        }.getOrNull()

        if (exitInfo == null && goPanic == null && uncaught == null) {
            return null
        }

        val pageSize = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrNull()
        val abis = Build.SUPPORTED_ABIS
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(", ")
            ?: "unknown"

        return buildString {
            appendLine("=== ShellSurge Crash Diagnostics ===")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("ABIs: $abis | PageSize: ${pageSize ?: "unknown"}")
            ktStage?.let { appendLine("Kotlin stage: $it") }
            goStage?.let { appendLine("Go stage: $it") }
            if (goPanic != null) {
                appendLine("--- Go panic ---")
                appendLine(goPanic)
            }
            if (uncaught != null) {
                appendLine("--- Uncaught JVM exception ---")
                appendLine(uncaught)
            }
            if (exitInfo != null) {
                appendLine("--- Process exit info ---")
                appendLine("Time: ${Instant.ofEpochMilli(exitInfo.timestamp)}")
                appendLine("Reason: ${exitReasonName(exitInfo.reason)} | Status: ${signalName(exitInfo.status)} (${exitInfo.status})")
                exitInfo.description?.takeIf { it.isNotBlank() }?.let {
                    appendLine("Description: $it")
                }
                val traceBytes = runCatching {
                    exitInfo.traceInputStream?.use { it.readBytes() }
                }.getOrNull()
                if (traceBytes != null && traceBytes.isNotEmpty()) {
                    val readable = extractReadableTrace(traceBytes)
                    if (readable.isNotEmpty()) {
                        appendLine("--- Exit trace excerpt ---")
                        appendLine(readable)
                    }
                }
            }
        }.trim()
    }

    fun dismissCrashReport(context: Context) {
        val newestTs = runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            am?.getHistoricalProcessExitReasons(context.packageName, 0, 5)
                ?.maxOfOrNull { it.timestamp }
        }.getOrNull() ?: System.currentTimeMillis()

        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putLong(KEY_DISMISSED_EXIT_TS, newestTs)
            }
        }
        runCatching { File(context.filesDir, FILE_UNCAUGHT).delete() }
        runCatching { File(context.filesDir, FILE_GO_PANIC).delete() }
    }

    private fun readSmallFile(file: File, maxChars: Int): String? =
        runCatching {
            if (!file.exists()) return@runCatching null
            file.readText().trim().takeIf { it.isNotEmpty() }?.take(maxChars)
        }.getOrNull()
}

internal fun signalName(status: Int): String = when (status) {
    4 -> "SIGILL"
    6 -> "SIGABRT"
    7 -> "SIGBUS"
    11 -> "SIGSEGV"
    31 -> "SIGSYS"
    else -> "signal $status"
}

internal fun exitReasonName(reason: Int): String = when (reason) {
    ApplicationExitInfo.REASON_CRASH -> "CRASH"
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
    ApplicationExitInfo.REASON_ANR -> "ANR"
    ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
    else -> "REASON($reason)"
}

internal fun extractReadableTrace(bytes: ByteArray, maxChars: Int = 3_500): String {
    if (bytes.isEmpty()) return ""
    val sampleSize = minOf(bytes.size, 2_048)
    var printableCount = 0
    for (i in 0 until sampleSize) {
        val b = bytes[i].toInt() and 0xFF
        if (b in 0x20..0x7E || b == '\n'.code || b == '\r'.code || b == '\t'.code) {
            printableCount++
        }
    }
    if (printableCount * 100 >= sampleSize * 85) {
        return bytes.decodeToString().trim().take(maxChars)
    }

    // Android 12+ native tombstones are protobuf-encoded; extract printable ASCII runs.
    val runs = ArrayList<String>(256)
    val current = StringBuilder()
    fun flush() {
        if (current.length >= 5) {
            val s = current.toString().trim()
            if (s.length >= 5 && runs.lastOrNull() != s) {
                runs.add(s)
            }
        }
        current.setLength(0)
    }
    for (byte in bytes) {
        val b = byte.toInt() and 0xFF
        if (b in 0x20..0x7E) {
            current.append(b.toChar())
        } else {
            flush()
        }
    }
    flush()

    if (runs.isEmpty()) return ""
    val prioritized = runs.filter { line ->
        TRACE_KEYWORDS.any { kw -> line.contains(kw, ignoreCase = true) }
    }
    val chosen = (if (prioritized.isNotEmpty()) prioritized else runs).take(60)
    return chosen.joinToString("\n").take(maxChars)
}
