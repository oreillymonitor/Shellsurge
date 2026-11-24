package io.github.lordofpolls.shellwave.core.net

import android.app.ApplicationExitInfo
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import io.github.lordofpolls.shellwave.core.prefs.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private class TestDiagContext(private val dir: File) : ContextWrapper(null) {
    private val prefs = FakeSharedPreferences()
    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = dir
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
}

class TailscaleCrashDiagnosticsTest {

    @Test
    fun `extractReadableTrace returns plain text stack traces and extracts native tombstone keywords`() {
        val plain = "java.lang.UnsatisfiedLinkError: dlopen failed\n\tat go.Seq.<clinit>(Seq.java:37)"
        assertEquals(plain, extractReadableTrace(plain.encodeToByteArray()))

        // Simulated binary protobuf tombstone bytes with null separators + non-ASCII headers
        val binaryPayload = byteArrayOf(0x0A, 0x10, 0x01, 0x02, 0x00, 0xFF.toByte()) +
            "Fatal signal 6 (SIGABRT) in libgojni.so".encodeToByteArray() +
            byteArrayOf(0x00, 0x12, 0x08, 0x81.toByte(), 0x82.toByte()) +
            "panic: runtime error: invalid memory address".encodeToByteArray() +
            byteArrayOf(0x00, 0x01, 0x02, 0x03) +
            "unrelated_noise_token".encodeToByteArray()

        val extracted = extractReadableTrace(binaryPayload)
        assertTrue(extracted.contains("SIGABRT"))
        assertTrue(extracted.contains("libgojni.so"))
        assertTrue(extracted.contains("panic: runtime error"))
    }

    @Test
    fun `signalName and exitReasonName map common crash codes cleanly`() {
        assertEquals("SIGABRT", signalName(6))
        assertEquals("SIGSEGV", signalName(11))
        assertEquals("SIGSYS", signalName(31))
        assertEquals("CRASH_NATIVE", exitReasonName(ApplicationExitInfo.REASON_CRASH_NATIVE))
    }

    @Test
    fun `recordStage and uncaught exception files surface in readLastCrashReport and clear on dismiss`() {
        val dir = kotlin.io.path.createTempDirectory("ts-diag-test").toFile().apply { deleteOnExit() }
        val ctx = TestDiagContext(dir)

        assertNull(TailscaleCrashDiagnostics.readLastCrashReport(ctx))

        TailscaleCrashDiagnostics.recordStage(ctx, "1:load_library_gojni")
        TailscaleCrashDiagnostics.recordUncaughtException(
            ctx,
            "Thread-9",
            IllegalStateException("boom from test"),
        )

        val report = TailscaleCrashDiagnostics.readLastCrashReport(ctx)!!
        assertTrue(report.contains("1:load_library_gojni"))
        assertTrue(report.contains("IllegalStateException: boom from test"))

        TailscaleCrashDiagnostics.dismissCrashReport(ctx)
        assertNull(TailscaleCrashDiagnostics.readLastCrashReport(ctx))
    }
}
