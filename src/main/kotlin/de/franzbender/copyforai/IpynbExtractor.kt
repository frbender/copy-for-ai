package de.franzbender.copyforai

import org.json.JSONArray
import org.json.JSONObject

object IpynbExtractor {
    fun extractCells(jsonContent: String): String {
        return try {
            val jsonObject = JSONObject(jsonContent)
            val cells: JSONArray = jsonObject.optJSONArray("cells") ?: return "Error: No 'cells' array found."
            val result = StringBuilder()
            for (i in 0 until cells.length()) {
                val cell = cells.optJSONObject(i) ?: continue
                val cellType = cell.optString("cell_type", "unknown")
                if (cellType == "markdown") {
                    result.append("# Markdown:\n")
                    val source = cell.optJSONArray("source")
                    if (source != null) {
                        for (j in 0 until source.length()) {
                            result.append(source.optString(j))
                        }
                    }
                    result.append("\n\n")
                } else if (cellType == "code") {
                    result.append("# Code:\n")
                    val source = cell.optJSONArray("source")
                    if (source != null) {
                        for (j in 0 until source.length()) {
                            result.append(source.optString(j))
                        }
                    }
                    result.append("\n")
                    // Process outputs if available.
                    val outputs = cell.optJSONArray("outputs")
                    if (outputs != null && outputs.length() > 0) {
                        result.append("# Output:\n")
                        for (k in 0 until outputs.length()) {
                            val output = outputs.optJSONObject(k)
                            if (output != null) {
                                val text = output.optString("text", "")
                                val outputLines = text.split("\n")
                                if (outputLines.size > 10) {
                                    result.append("# " + outputLines.take(10).joinToString("\n# "))
                                    result.append("\n# ... (truncated)\n")
                                } else {
                                    result.append("# " + outputLines.joinToString("\n# "))
                                    result.append("\n")
                                }
                            }
                        }
                    }
                    result.append("\n")
                } else {
                    // For other cell types, output the source.
                    result.append("# Cell ($cellType):\n")
                    val source = cell.optJSONArray("source")
                    if (source != null) {
                        for (j in 0 until source.length()) {
                            result.append(source.optString(j))
                        }
                    }
                    result.append("\n\n")
                }
            }
            result.toString()
        } catch (e: Exception) {
            "Error parsing .ipynb file: ${e.message}"
        }
    }
}
