package io.github.lordofpolls.shellwave.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadableOutputParserTest {

    @Test
    fun `cleanForReadable preserves unified diffs and strips TUI chrome`() {
        val raw = """
            user@host:~/repo$ git diff
            ┌─────────────────────────┐
            │ diff --git a/Foo.kt b/Foo.kt │
            │ @@ -10,2 +10,3 @@       │
            │ -val a = 1              │
            │ +val a = 2              │
            │ +val b = 3              │
            └─────────────────────────┘
            [0] 0:zsh* 12:34 27-Nov-26
            ? for shortcuts
        """.trimIndent()

        val cleaned = TerminalTextSanitizer.cleanForReadable(raw, latestTurnOnly = true)
        assertTrue(cleaned.contains("diff --git a/Foo.kt b/Foo.kt"))
        assertTrue(cleaned.contains("@@ -10,2 +10,3 @@"))
        assertTrue(cleaned.contains("-val a = 1"))
        assertTrue(cleaned.contains("+val a = 2"))
        assertTrue(!cleaned.contains("? for shortcuts"))
        assertTrue(!cleaned.contains("12:34 27-Nov-26"))
    }

    @Test
    fun `cleanForReadable preserves indentation inside fenced code blocks and slices latest turn`() {
        val raw = """
            user@host:~/repo$ echo old
            old output
            user@host:~/repo$ cat snippet.md
            Here is the fix:
            ```kotlin
            fun greet() {
                println("hello")
            }
            ```
            Done.
        """.trimIndent()

        val latest = TerminalTextSanitizer.cleanForReadable(raw, latestTurnOnly = true)
        assertTrue(!latest.contains("old output"))
        assertTrue(latest.contains("    println(\"hello\")"))

        val full = TerminalTextSanitizer.cleanForReadable(raw, latestTurnOnly = false)
        assertTrue(full.contains("old output"))
        assertTrue(full.contains("    println(\"hello\")"))
    }

    @Test
    fun `parseReadableBlocks splits prose and fenced code blocks cleanly`() {
        val text = """
            Reviewing changes:
            ```kotlin
            val x = 1
            ```
            Also check:
            ```
            echo ok
            ```
            All set.
        """.trimIndent()

        val blocks = parseReadableBlocks(text)
        assertEquals(5, blocks.size)
        assertEquals(ReadableBlock.Prose("Reviewing changes:"), blocks[0])
        assertEquals(ReadableBlock.Code("kotlin", "val x = 1"), blocks[1])
        assertEquals(ReadableBlock.Prose("Also check:"), blocks[2])
        assertEquals(ReadableBlock.Code(null, "echo ok"), blocks[3])
        assertEquals(ReadableBlock.Prose("All set."), blocks[4])
    }

    @Test
    fun `parseReadableBlocks handles unclosed trailing code fence`() {
        val text = "Start\n```bash\nls -la\npwd"
        val blocks = parseReadableBlocks(text)
        assertEquals(2, blocks.size)
        assertEquals(ReadableBlock.Prose("Start"), blocks[0])
        assertEquals(ReadableBlock.Code("bash", "ls -la\npwd"), blocks[1])
    }

    @Test
    fun `findUrlsInReadableText extracts distinct trimmed URLs in order`() {
        val text = "Visit (https://example.com/a) and https://example.com/b, or https://example.com/a."
        assertEquals(
            listOf("https://example.com/a", "https://example.com/b"),
            findUrlsInReadableText(text),
        )
    }

    @Test
    fun `parseReadableBlocks parses headings, prompts, lists, quotes, dividers, tables, and diffs`() {
        val text = """
            user@box:~/repo$ ./gradlew test
            ## Build Summary
            This is a soft-wrapped sentence
            that continues cleanly onto the next line.

            Status: passed
            PASS: unit suite

            - First bullet item that
              wraps across lines
            - Second bullet item

            1. Ordered first
            2. Ordered second

            > Quoted note line one
            > line two

            ---

            | Task | State |
            | --- | --- |
            | lint | OK |
            | test | OK |

            diff --git a/Foo.kt b/Foo.kt
            @@ -1,2 +1,2 @@
            -val x = 1
            +val x = 2
        """.trimIndent()

        val blocks = parseReadableBlocks(text)
        assertEquals(ReadableBlock.Command("user@box:~/repo$", "./gradlew test"), blocks[0])
        assertEquals(ReadableBlock.Heading(2, "Build Summary"), blocks[1])
        assertEquals(
            ReadableBlock.Prose("This is a soft-wrapped sentence that continues cleanly onto the next line."),
            blocks[2],
        )
        assertEquals(ReadableBlock.Prose("Status: passed\nPASS: unit suite"), blocks[3])
        assertEquals(
            ReadableBlock.BulletList(
                listOf(
                    ReadableListItem("•", "First bullet item that wraps across lines"),
                    ReadableListItem("•", "Second bullet item"),
                ),
            ),
            blocks[4],
        )
        assertEquals(
            ReadableBlock.BulletList(
                listOf(
                    ReadableListItem("1.", "Ordered first"),
                    ReadableListItem("2.", "Ordered second"),
                ),
            ),
            blocks[5],
        )
        assertEquals(ReadableBlock.Quote("Quoted note line one line two"), blocks[6])
        assertEquals(ReadableBlock.Divider, blocks[7])
        assertEquals(
            ReadableBlock.Table(
                headers = listOf("Task", "State"),
                rows = listOf(listOf("lint", "OK"), listOf("test", "OK")),
            ),
            blocks[8],
        )
        val diff = blocks[9] as ReadableBlock.Diff
        assertEquals("diff --git a/Foo.kt b/Foo.kt", diff.lines.first())
        assertTrue(diff.lines.contains("+val x = 2"))
    }

    @Test
    fun `parseInlineSpans parses bold, italic, inline code, markdown links, and bare urls`() {
        val spans = parseInlineSpans(
            "See **important** *note* in `Foo.kt` at [docs](https://example.com/docs) or https://example.com/raw.",
        )
        assertEquals(
            listOf(
                ReadableInlineSpan.Plain("See "),
                ReadableInlineSpan.Bold("important"),
                ReadableInlineSpan.Plain(" "),
                ReadableInlineSpan.Italic("note"),
                ReadableInlineSpan.Plain(" in "),
                ReadableInlineSpan.InlineCode("Foo.kt"),
                ReadableInlineSpan.Plain(" at "),
                ReadableInlineSpan.Link("docs", "https://example.com/docs"),
                ReadableInlineSpan.Plain(" or "),
                ReadableInlineSpan.Link("https://example.com/raw", "https://example.com/raw"),
                ReadableInlineSpan.Plain("."),
            ),
            spans,
        )
    }
}
