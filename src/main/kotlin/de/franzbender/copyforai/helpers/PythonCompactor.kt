package de.franzbender.copyforai.helpers

class PythonCompactor : Compactor {
    override fun isResponsible(fileName: String, fileExtension: String, fileContent: String): Boolean {
        return fileExtension.lowercase() == "py"
    }

    override fun compact(fileContent: String): String {
        val lines = fileContent.lines().toMutableList()
        val result = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val defMatch = functionRegex.find(line)
            if (defMatch != null) {
                result.add(line)  // add the def line
                val indent = defMatch.groupValues[1]
                val funcName = defMatch.groupValues[2]
                i++
                // Preserve docstring if present (support both """ and ''')
                if (i < lines.size) {
                    val trimmed = lines[i].trim()
                    if (trimmed.startsWith("\"\"\"") || trimmed.startsWith("'''")) {
                        val docDelimiter = if (trimmed.startsWith("\"\"\"")) "\"\"\"" else "'''"
                        result.add(lines[i])
                        i++
                        while (i < lines.size && !lines[i].trim().endsWith(docDelimiter)) {
                            result.add(lines[i])
                            i++
                        }
                        if (i < lines.size) {
                            result.add(lines[i])
                            i++
                        }
                    }
                }
                // Replace the function body with a placeholder.
                if (i < lines.size && lines[i].startsWith(indent + "    ")) {
                    if (funcName == "__init__") {
                        // For constructors, keep self assignments.
                        while (i < lines.size && lines[i].startsWith(indent + "    ")) {
                            if (lines[i].trim().startsWith("self.")) {
                                result.add(lines[i])
                            }
                            i++
                        }
                    } else {
                        result.add(indent + "    " + "...")
                        while (i < lines.size && lines[i].startsWith(indent + "    ")) i++
                    }
                }
            } else {
                result.add(line)
                i++
            }
        }
        return result.joinToString("\n")
    }

    companion object {
        private val functionRegex = Regex("^([ \\t]*)def\\s+(\\w+)\\s*\\(")
    }
}
