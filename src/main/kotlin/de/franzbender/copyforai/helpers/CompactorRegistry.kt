package de.franzbender.copyforai.helpers

object CompactorRegistry {
    private val compactors: List<Compactor> = listOf(
        PythonCompactor(),
        CCompactor(),
        CppCompactor(),
        RustCompactor(),
        GoCompactor(),
        JavaCompactor(),
        KotlinCompactor()
    )

    fun getCompactor(fileName: String, fileExtension: String, fileContent: String): Compactor? {
        return compactors.firstOrNull { it.isResponsible(fileName, fileExtension, fileContent) }
    }
}
