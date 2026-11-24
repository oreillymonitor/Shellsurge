package io.github.lordofpolls.shellwave.feature.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitDiffParserTest {

    @Test
    fun `extractPromptWorkingDir reads shell prompt directories`() {
        assertEquals(
            "~/Projects/shellwave-fork",
            extractPromptWorkingDir("Compiling...\nuser@dev:~/Projects/shellwave-fork$ "),
        )
        assertEquals(
            "/var/www/app",
            extractPromptWorkingDir("root@prod:/var/www/app# "),
        )
        assertNull(extractPromptWorkingDir("plain output line with no prompt"))
    }

    @Test
    fun `parseGitSnapshot parses modified, untracked, and error output`() {
        val raw = """
            __SHELLSURGE_GIT_ROOT__
            /home/dev/repo
            __SHELLSURGE_GIT_BRANCH__
            feature/ai-tools
            __SHELLSURGE_GIT_STATUS__
             M app/Main.kt
            ?? app/NewHelper.kt
            __SHELLSURGE_GIT_DIFF__
            diff --git a/app/Main.kt b/app/Main.kt
            index 123..456 100644
            --- a/app/Main.kt
            +++ b/app/Main.kt
            @@ -1,2 +1,3 @@
             fun main() {
            +    println("hi")
             }
        """.trimIndent()

        val snap = parseGitSnapshot(raw)
        assertNull(snap.error)
        assertEquals("/home/dev/repo", snap.repoRoot)
        assertEquals("feature/ai-tools", snap.branch)
        assertEquals(2, snap.files.size)
        assertEquals("M", snap.files[0].status)
        assertEquals("app/Main.kt", snap.files[0].path)
        assertTrue(snap.files[0].diffHunk.contains("+    println(\"hi\")"))
        assertEquals("??", snap.files[1].status)
        assertEquals("app/NewHelper.kt", snap.files[1].path)

        val errSnap = parseGitSnapshot("__SHELLSURGE_GIT_ERR__ Not a git repository (/home/dev)")
        assertTrue(errSnap.error?.contains("Not a git repository") == true)
    }
}
