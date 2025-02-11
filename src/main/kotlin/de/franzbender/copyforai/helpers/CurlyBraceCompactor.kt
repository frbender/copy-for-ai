package de.franzbender.copyforai.helpers

abstract class CurlyBraceCompactor(private val supportedExtensions: List<String>) : Compactor {

    override fun isResponsible(fileName: String, fileExtension: String, fileContent: String): Boolean {
        return fileExtension.lowercase() in supportedExtensions
    }

    override fun compact(fileContent: String): String {
        val sb = StringBuilder()
        var i = 0
        val n = fileContent.length
        var state = State.NORMAL
        while (i < n) {
            val c = fileContent[i]
            when (state) {
                State.NORMAL -> {
                    when {
                        c == '"' -> { sb.append(c); state = State.STRING }
                        c == '\'' -> { sb.append(c); state = State.CHAR }
                        c == '/' && i + 1 < n -> {
                            when (fileContent[i + 1]) {
                                '/' -> { sb.append("//"); i++; state = State.LINE_COMMENT }
                                '*' -> { sb.append("/*"); i++; state = State.BLOCK_COMMENT }
                                else -> sb.append(c)
                            }
                        }
                        c == '{' -> {
                            if (isFunctionHeader(fileContent, i)) {
                                // Get current line indentation
                                val currentIndent = getCurrentIndentation(fileContent, i)
                                sb.append("{\n")
                                // Collect any leading comments with proper indent
                                val (leadingComments, newIndex) = collectLeadingComments(fileContent, i + 1, currentIndent + "    ")
                                if (leadingComments.isNotBlank()) {
                                    sb.append(leadingComments)
                                    if (!leadingComments.endsWith("\n")) sb.append("\n")
                                }
                                // Insert the placeholder on a new indented line
                                sb.append(currentIndent + "    " + "...\n")
                                i = skipBlock(fileContent, newIndex)
                                sb.append(currentIndent + "}")
                                i++ // move past the closing brace
                                continue
                            } else {
                                sb.append(c)
                            }
                        }
                        else -> sb.append(c)
                    }
                }
                State.STRING -> {
                    sb.append(c)
                    if (c == '\\' && i + 1 < n) { sb.append(fileContent[i + 1]); i++ }
                    else if (c == '"') state = State.NORMAL
                }
                State.CHAR -> {
                    sb.append(c)
                    if (c == '\\' && i + 1 < n) { sb.append(fileContent[i + 1]); i++ }
                    else if (c == '\'') state = State.NORMAL
                }
                State.LINE_COMMENT -> {
                    sb.append(c)
                    if (c == '\n') state = State.NORMAL
                }
                State.BLOCK_COMMENT -> {
                    sb.append(c)
                    if (c == '*' && i + 1 < n && fileContent[i + 1] == '/') {
                        sb.append('/')
                        i++; state = State.NORMAL
                    }
                }
            }
            i++
        }
        return sb.toString()
    }

    // Returns the whitespace at the beginning of the current line.
    private fun getCurrentIndentation(content: String, pos: Int): String {
        var i = pos - 1
        while (i >= 0 && content[i] != '\n') {
            i--
        }
        i++ // move past newline or start at 0
        val sb = StringBuilder()
        while (i < content.length && content[i].isWhitespace() && content[i] != '\n') {
            sb.append(content[i])
            i++
        }
        return sb.toString()
    }

    // Collects leading comment lines (or blank lines) and re-indents them.
    private fun collectLeadingComments(content: String, start: Int, indent: String): Pair<String, Int> {
        var i = start
        val n = content.length
        val commentBuilder = StringBuilder()
        while (i < n) {
            val lineStart = i
            while (i < n && content[i] != '\n') {
                i++
            }
            val line = content.substring(lineStart, i)
            if (line.trim().startsWith("//") || line.trim().startsWith("/*") || line.trim().isEmpty()) {
                commentBuilder.append(indent).append(line.trim()).append("\n")
            } else {
                break
            }
            i++ // skip newline
        }
        return Pair(commentBuilder.toString(), i)
    }

    // Skips from the first character after '{' to the matching '}'.
    private fun skipBlock(content: String, start: Int): Int {
        var i = start
        val n = content.length
        var depth = 1
        var state = State.NORMAL
        while (i < n && depth > 0) {
            val c = content[i]
            when (state) {
                State.NORMAL -> {
                    when {
                        c == '"' -> state = State.STRING
                        c == '\'' -> state = State.CHAR
                        c == '/' && i + 1 < n -> {
                            when (content[i + 1]) {
                                '/' -> { i++; state = State.LINE_COMMENT }
                                '*' -> { i++; state = State.BLOCK_COMMENT }
                            }
                        }
                        c == '{' -> depth++
                        c == '}' -> {
                            depth--
                            if (depth == 0) return i
                        }
                    }
                }
                State.STRING -> {
                    if (c == '\\' && i + 1 < n) i++
                    else if (c == '"') state = State.NORMAL
                }
                State.CHAR -> {
                    if (c == '\\' && i + 1 < n) i++
                    else if (c == '\'') state = State.NORMAL
                }
                State.LINE_COMMENT -> {
                    if (c == '\n') state = State.NORMAL
                }
                State.BLOCK_COMMENT -> {
                    if (c == '*' && i + 1 < n && content[i + 1] == '/') { i++; state = State.NORMAL }
                }
            }
            i++
        }
        return i
    }

    // Determines if the '{' at position pos is starting a function body.
    private fun isFunctionHeader(content: String, pos: Int): Boolean {
        val start = maxOf(0, pos - 200)
        val headerCandidate = content.substring(start, pos)
        if (!headerCandidate.contains('(')) return false

        val trimmedCandidate = headerCandidate.trimStart()
        val firstWord = trimmedCandidate.split(Regex("\\s+")).firstOrNull() ?: ""
        // Exclude control structures.
        val controlKeywords = setOf("if", "for", "while", "switch", "catch", "when", "match")
        if (firstWord in controlKeywords) return false

        // Check for function keywords (for Go, Rust, Kotlin, etc.).
        if (headerCandidate.contains("func ") || headerCandidate.contains("fn ") || headerCandidate.contains("fun "))
            return true

        // For C/C++/Java: if it ends with ')' (parameter list).
        if (headerCandidate.trim().endsWith(")")) return true

        // Also handle cases with return type indicators (e.g. ")->" or "):").
        val pattern = Regex("""\)[\s]*(?::|->)[\s]*[^\s{]+$""")
        if (pattern.containsMatchIn(headerCandidate)) return true

        return false
    }

    private enum class State { NORMAL, STRING, CHAR, LINE_COMMENT, BLOCK_COMMENT }
}
