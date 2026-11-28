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

    @Test
    fun `parseReadableBlocks separates multi-line and boxed user prompts from agent reply`() {
        val rawBoxed = """
            ╭────────────────────────────────────────────╮
            │ ❯ Let's work on the formatted read output  │
            │   of the terminal too. User input is still │
            │   being mixed with terminal output if it   │
            │   is broken by a line.                     │
            ╰────────────────────────────────────────────╯
            Sure, let's inspect ReadableOutputParser.kt and clean that up.
        """.trimIndent()

        val cleaned = TerminalTextSanitizer.cleanForReadable(rawBoxed, latestTurnOnly = true)
        val blocks = parseReadableBlocks(cleaned)
        assertEquals(2, blocks.size)
        assertEquals(
            ReadableBlock.UserPrompt(
                prompt = "❯",
                text = "Let's work on the formatted read output of the terminal too. User input is still being mixed with terminal output if it is broken by a line.",
            ),
            blocks[0],
        )
        assertEquals(
            ReadableBlock.Prose("Sure, let's inspect ReadableOutputParser.kt and clean that up."),
            blocks[1],
        )

        val unboxedWrapped = """
            ❯ Let's work on the formatted read output of the
            terminal too. User input is still being mixed with
            terminal input if it is broken by a line.
            I'll start by reading the parser.
        """.trimIndent()
        val unboxedBlocks = parseReadableBlocks(unboxedWrapped)
        assertEquals(2, unboxedBlocks.size)
        assertTrue(unboxedBlocks[0] is ReadableBlock.UserPrompt)
        assertEquals(
            ReadableBlock.Prose("I'll start by reading the parser."),
            unboxedBlocks[1],
        )

        val shortPrompt = "❯ hi\nHello! How can I help?"
        val shortBlocks = parseReadableBlocks(shortPrompt)
        assertEquals(2, shortBlocks.size)
        assertEquals(ReadableBlock.UserPrompt("❯", "hi"), shortBlocks[0])
        assertEquals(ReadableBlock.Prose("Hello! How can I help?"), shortBlocks[1])
    }

    @Test
    fun `parseReadableBlocks handles shell backslash continuations, activity groups, nested bullets, and strikethrough`() {
        val raw = """
            user@box:~/repo$ ./gradlew test \
              :app:lintFossDebug
            ● Ran 2 commands (grep -rn)
            ● Explored 4 files (ReadableOutputSheet.kt)
              (ctrl+o to expand)
            - Top item
              - Nested item with ~~old~~ **new**
            ❯ 
        """.trimIndent()

        val cleaned = TerminalTextSanitizer.cleanForReadable(raw, latestTurnOnly = true)
        val blocks = parseReadableBlocks(cleaned)
        assertEquals(
            ReadableBlock.Command("user@box:~/repo$", "./gradlew test :app:lintFossDebug"),
            blocks[0],
        )
        assertEquals(
            ReadableBlock.ActivityGroup(
                listOf(
                    "● Ran 2 commands (grep -rn)",
                    "● Explored 4 files (ReadableOutputSheet.kt) (ctrl+o to expand)",
                ),
            ),
            blocks[1],
        )
        assertEquals(
            ReadableBlock.BulletList(
                listOf(
                    ReadableListItem("•", "Top item", indentLevel = 0),
                    ReadableListItem("•", "Nested item with ~~old~~ **new**", indentLevel = 1),
                ),
            ),
            blocks[2],
        )

        val strikeSpans = parseInlineSpans("with ~~old~~ **new**")
        assertEquals(
            listOf(
                ReadableInlineSpan.Plain("with "),
                ReadableInlineSpan.Strikethrough("old"),
                ReadableInlineSpan.Plain(" "),
                ReadableInlineSpan.Bold("new"),
            ),
            strikeSpans,
        )
    }

    @Test
    fun `parseReadableBlocks handles angle bracket multi-line prompts and open-circle thoughts`() {
        val raw = """
            > Is the improved formatting in the rear mode work
              retroactively? I installed the update and reopened
              this chat with its history and it is still not
              formatting multi lines prompts from the user
              correctly.

            ○ Thought for 44.2s (Terminal buffer parsing occur...)
            ● Explored 3 files (TerminalTextSanitizer.kt, Read...)
            ● Ran (grep -rn "ReadableOutputSheet\|cleanForRead...)
            ● Explored 1 file (SessionsScreen.kt)
            ● Ran (grep -rn -A 60 "public...) (ctrl+o to expand)

            Yes — Read Mode works retroactively.
        """.trimIndent()

        val cleaned = TerminalTextSanitizer.cleanForReadable(raw, latestTurnOnly = true)
        val blocks = parseReadableBlocks(cleaned)
        assertEquals(3, blocks.size)
        assertEquals(
            ReadableBlock.UserPrompt(
                prompt = ">",
                text = "Is the improved formatting in the rear mode work retroactively? I installed the update and reopened this chat with its history and it is still not formatting multi lines prompts from the user correctly.",
            ),
            blocks[0],
        )
        assertEquals(
            ReadableBlock.ActivityGroup(
                listOf(
                    "○ Thought for 44.2s (Terminal buffer parsing occur...)",
                    "● Explored 3 files (TerminalTextSanitizer.kt, Read...)",
                    "● Ran (grep -rn \"ReadableOutputSheet\\|cleanForRead...)",
                    "● Explored 1 file (SessionsScreen.kt)",
                    "● Ran (grep -rn -A 60 \"public...) (ctrl+o to expand)",
                ),
            ),
            blocks[1],
        )
        assertEquals(ReadableBlock.Prose("Yes — Read Mode works retroactively."), blocks[2])
    }

    @Test
    fun `cleanForReadable and parseReadableBlocks handle multi-turn angle prompts, URLs in prompts, blockquotes, and Gradle output`() {
        val scrollback = """
            > Check the layout at
              https://example.com/preview
              and fix padding.

            ○ Thought for 3.1s
            All fixed.

            > push and cut

            ○ Thought for 1.8s
            Here are the details:

            > Single-line markdown blockquote inside reply.

            > Task :app:testFossDebugUnitTest
            > Run with --stacktrace
            BUILD SUCCESSFUL in 4s
        """.trimIndent()

        val latestCleaned = TerminalTextSanitizer.cleanForReadable(scrollback, latestTurnOnly = true)
        assertTrue(!latestCleaned.contains("All fixed."))
        assertTrue(latestCleaned.startsWith("> push and cut"))

        val fullCleaned = TerminalTextSanitizer.cleanForReadable(scrollback, latestTurnOnly = false)
        val fullBlocks = parseReadableBlocks(fullCleaned)

        val userPrompts = fullBlocks.filterIsInstance<ReadableBlock.UserPrompt>()
        assertEquals(2, userPrompts.size)
        assertEquals("Check the layout at\nhttps://example.com/preview and fix padding.", userPrompts[0].text)
        assertEquals("push and cut", userPrompts[1].text)

        assertTrue(fullBlocks.contains(ReadableBlock.Quote("Single-line markdown blockquote inside reply.")))
        assertTrue(
            fullBlocks.contains(
                ReadableBlock.Quote("Task :app:testFossDebugUnitTest Run with --stacktrace"),
            ),
        )
    }
}
