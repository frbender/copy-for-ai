package de.franzbender.copyforai.helpers

interface Compactor {
    fun isResponsible(fileName: String, fileExtension: String, fileContent: String): Boolean
    fun compact(fileContent: String): String
}
