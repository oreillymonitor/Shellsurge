package io.github.lordofpolls.shellwave.terminal

sealed interface ReadableBlock {
    data class Prose(val text: String) : ReadableBlock
    data class Code(val language: String?, val code: String) : ReadableBlock
}

/**
 * Splits cleaned terminal output into prose paragraphs and fenced code blocks so the full-screen
 * reader sheet can render selectable prose alongside copyable monospace code cards.
 */
fun parseReadableBlocks(text: String): List<ReadableBlock> {
    if (text.isBlank()) return emptyList()

    val blocks = mutableListOf<ReadableBlock>()
    val proseLines = mutableListOf<String>()
    val codeLines = mutableListOf<String>()
    var inCodeFence = false
    var codeLang: String? = null

    fun flushProse() {
        val prose = proseLines.joinToString("\n").trim()
        proseLines.clear()
        if (prose.isNotEmpty()) {
            blocks += ReadableBlock.Prose(prose)
        }
    }

    fun flushCode() {
        val code = codeLines.joinToString("\n").trimEnd()
        codeLines.clear()
        if (code.isNotEmpty() || codeLang != null) {
            blocks += ReadableBlock.Code(language = codeLang, code = code)
        }
        codeLang = null
    }

    for (line in text.lines()) {
        val trimmed = line.trim()
        if (!inCodeFence) {
            if (trimmed.startsWith("```")) {
                flushProse()
                inCodeFence = true
                codeLang = trimmed.removePrefix("```").trim().takeIf { it.isNotEmpty() }
            } else {
                proseLines += line
            }
        } else {
            if (trimmed.startsWith("```")) {
                inCodeFence = false
                flushCode()
            } else {
                codeLines += line
            }
        }
    }

    if (inCodeFence) {
        flushCode()
    } else {
        flushProse()
    }

    return blocks
}

/**
 * Extracts distinct URLs from cleaned terminal output in order of appearance.
 */
fun findUrlsInReadableText(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    val seen = LinkedHashSet<String>()
    text.lines().forEachIndexed { idx, line ->
        findLinksInLine(line, row = idx)
            .asSequence()
            .filter { it.type == LinkType.URL }
            .forEach { seen.add(it.text) }
    }
    return seen.toList()
}
